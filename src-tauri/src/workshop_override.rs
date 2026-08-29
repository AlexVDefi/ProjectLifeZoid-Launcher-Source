use crate::config;
use crate::error::{Error, Result};
use crate::state;
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};

const SCHEMA: &str = "1";
const MAX_ITEMS: usize = 4096;

#[derive(Debug, Clone)]
struct Receipt {
    pack: String,
    session: Option<String>,
    ready: bool,
    items: BTreeMap<u64, u64>,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ReceiptSummary {
    pub path: PathBuf,
    pub pack: String,
    pub session: Option<String>,
    pub ready: bool,
    pub item_count: usize,
}

fn invalid(message: impl Into<String>) -> Error {
    Error::Other(format!(
        "invalid Workshop compatibility receipt: {}",
        message.into()
    ))
}

fn validate_digest(value: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
    {
        return Err(invalid(
            "pack must be a 64-character lowercase SHA-256 digest",
        ));
    }
    Ok(())
}

fn from_items(pack: String, items: &[(u64, u64)]) -> Result<Receipt> {
    validate_digest(&pack)?;
    if items.is_empty() {
        return Err(invalid("at least one Workshop item is required"));
    }
    if items.len() > MAX_ITEMS {
        return Err(invalid(format!("more than {MAX_ITEMS} items")));
    }

    let mut sorted = BTreeMap::new();
    for &(item_id, timestamp) in items {
        if item_id == 0 || timestamp == 0 {
            return Err(invalid("item IDs and timestamps must be positive integers"));
        }
        if sorted.insert(item_id, timestamp).is_some() {
            return Err(invalid(format!("duplicate Workshop item {item_id}")));
        }
    }

    Ok(Receipt {
        pack,
        session: None,
        ready: false,
        items: sorted,
    })
}

fn proof_digest(items: &[(u64, u64)]) -> Result<String> {
    let mut sorted = BTreeMap::new();
    for &(item_id, timestamp) in items {
        if item_id == 0 || timestamp == 0 {
            return Err(invalid("item IDs and timestamps must be positive integers"));
        }
        if sorted.insert(item_id, timestamp).is_some() {
            return Err(invalid(format!("duplicate Workshop item {item_id}")));
        }
    }

    let mut hash = Sha256::new();
    hash.update(b"plz-workshop-handshake-proof-v1\n");
    for (item_id, timestamp) in sorted {
        hash.update(format!("{item_id}={timestamp}\n").as_bytes());
    }
    Ok(hex::encode(hash.finalize()))
}

fn render(receipt: &Receipt) -> String {
    let mut text = String::from("schema=1\n");
    if receipt.ready {
        text.push_str("ready=true\n");
    }
    if let Some(session) = &receipt.session {
        text.push_str("session=");
        text.push_str(session);
        text.push('\n');
    }
    text.push_str("pack=");
    text.push_str(&receipt.pack);
    text.push('\n');
    for (item_id, timestamp) in &receipt.items {
        text.push_str(&format!("item.{item_id}={timestamp}\n"));
    }
    text
}

fn parse(text: &str, expect_active: bool) -> Result<Receipt> {
    if text.starts_with('\u{feff}') {
        return Err(invalid("UTF-8 BOM is not allowed"));
    }

    let mut schema = None;
    let mut ready = None;
    let mut pack = None;
    let mut session = None;
    let mut items = BTreeMap::new();

    for raw_line in text.lines() {
        if raw_line.is_empty() {
            continue;
        }
        let (key, value) = raw_line
            .split_once('=')
            .ok_or_else(|| invalid(format!("line has no '=': {raw_line}")))?;
        if key.is_empty() || value.is_empty() {
            return Err(invalid("empty keys and values are not allowed"));
        }
        match key {
            "schema" => {
                if schema.replace(value.to_string()).is_some() {
                    return Err(invalid("duplicate schema"));
                }
            }
            "ready" => {
                if ready.replace(value.to_string()).is_some() {
                    return Err(invalid("duplicate ready flag"));
                }
            }
            "pack" => {
                if pack.replace(value.to_string()).is_some() {
                    return Err(invalid("duplicate pack"));
                }
            }
            "session" => {
                if session.replace(value.to_string()).is_some() {
                    return Err(invalid("duplicate session"));
                }
            }
            key if key.starts_with("item.") => {
                if items.len() >= MAX_ITEMS {
                    return Err(invalid(format!("more than {MAX_ITEMS} items")));
                }
                let item_id = key["item.".len()..]
                    .parse::<u64>()
                    .map_err(|_| invalid(format!("bad Workshop item ID in {key}")))?;
                let timestamp = value
                    .parse::<u64>()
                    .map_err(|_| invalid(format!("bad timestamp for Workshop item {item_id}")))?;
                if item_id == 0 || timestamp == 0 || items.insert(item_id, timestamp).is_some() {
                    return Err(invalid(format!(
                        "invalid or duplicate Workshop item {item_id}"
                    )));
                }
            }
            _ => return Err(invalid(format!("unknown or duplicate key {key}"))),
        }
    }

    if schema.as_deref() != Some(SCHEMA) {
        return Err(invalid("unsupported schema"));
    }
    let pack = pack.ok_or_else(|| invalid("missing pack"))?;
    validate_digest(&pack)?;
    if items.is_empty() {
        return Err(invalid("no Workshop items"));
    }

    if expect_active {
        if ready.as_deref() != Some("true") {
            return Err(invalid("active receipt is not ready"));
        }
        let value = session
            .as_deref()
            .ok_or_else(|| invalid("missing session"))?;
        validate_digest(value)
            .map_err(|_| invalid("session must be a lowercase SHA-256 digest"))?;
    } else if ready.is_some() || session.is_some() {
        return Err(invalid("pending receipt contains active-session fields"));
    }

    Ok(Receipt {
        pack,
        session,
        ready: expect_active,
        items,
    })
}

fn read(path: &Path, expect_active: bool) -> Result<Option<Receipt>> {
    match fs::read_to_string(path) {
        Ok(text) => parse(&text, expect_active).map(Some),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(None),
        Err(error) => Err(error.into()),
    }
}

fn write(path: &Path, receipt: &Receipt) -> Result<()> {
    let temp_path = path.with_extension("tmp");
    state::write_no_bom(&temp_path, &render(receipt))?;
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(error) if error.kind() == ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    fs::rename(temp_path, path)?;
    Ok(())
}

fn summary(path: PathBuf, receipt: Receipt) -> ReceiptSummary {
    ReceiptSummary {
        path,
        pack: receipt.pack,
        session: receipt.session,
        ready: receipt.ready,
        item_count: receipt.items.len(),
    }
}

pub fn stage_proof(items: &[(u64, u64)]) -> Result<ReceiptSummary> {
    let pack = proof_digest(items)?;
    let receipt = from_items(pack, items)?;
    let path = config::workshop_override_pending_path();
    write(&path, &receipt)?;
    Ok(summary(path, receipt))
}

pub fn activate_pending() -> Result<Option<ReceiptSummary>> {
    let Some(mut receipt) = read(&config::workshop_override_pending_path(), false)? else {
        clear_active()?;
        return Ok(None);
    };

    let seed = format!(
        "{}\n{}\n{}\n",
        state::now_millis(),
        std::process::id(),
        receipt.pack
    );
    receipt.session = Some(hex::encode(Sha256::digest(seed.as_bytes())));
    receipt.ready = true;

    let path = config::workshop_override_active_path();
    write(&path, &receipt)?;
    Ok(Some(summary(path, receipt)))
}

pub fn pending() -> Result<Option<ReceiptSummary>> {
    let path = config::workshop_override_pending_path();
    Ok(read(&path, false)?.map(|receipt| summary(path, receipt)))
}

pub fn active() -> Result<Option<ReceiptSummary>> {
    let path = config::workshop_override_active_path();
    Ok(read(&path, true)?.map(|receipt| summary(path, receipt)))
}

pub fn clear_active() -> Result<bool> {
    remove_if_present(&config::workshop_override_active_path())
}

pub fn clear_pending() -> Result<bool> {
    remove_if_present(&config::workshop_override_pending_path())
}

fn remove_if_present(path: &Path) -> Result<bool> {
    match fs::remove_file(path) {
        Ok(()) => Ok(true),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(false),
        Err(error) => Err(error.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn receipt_is_sorted_and_matches_the_java_properties_contract() {
        let items = [(200, 900), (100, 800)];
        let receipt = from_items(proof_digest(&items).unwrap(), &items).unwrap();
        let text = render(&receipt);
        assert!(text.find("item.100=800").unwrap() < text.find("item.200=900").unwrap());
        assert_eq!(parse(&text, false).unwrap().items.len(), 2);
        assert!(!text.contains("ready="));
        assert!(!text.contains("session="));
    }

    #[test]
    fn active_receipt_requires_matching_shape() {
        let text = format!(
            "schema=1\nready=true\nsession={}\npack={}\nitem.100=800\n",
            "1".repeat(64),
            "2".repeat(64)
        );
        let receipt = parse(&text, true).unwrap();
        assert!(receipt.ready);
        assert_eq!(receipt.items.get(&100), Some(&800));
        assert!(parse(&text.replace("ready=true\n", ""), true).is_err());
    }

    #[test]
    fn rejects_duplicates_zeroes_unknown_fields_and_uppercase_digests() {
        assert!(from_items("a".repeat(64), &[(1, 2), (1, 3)]).is_err());
        assert!(from_items("a".repeat(64), &[(0, 2)]).is_err());
        assert!(from_items("A".repeat(64), &[(1, 2)]).is_err());
        assert!(parse(
            &format!("schema=1\npack={}\nunknown=x\nitem.1=2\n", "a".repeat(64)),
            false
        )
        .is_err());
    }
}
