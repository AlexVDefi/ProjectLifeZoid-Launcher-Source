use crate::config;
use crate::error::{Error, Result};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::Path;
use std::sync::OnceLock;
use std::time::Duration;

const PUBLIC_KEY_HEX: &str = "cee5e242040235eecccad136376acedf3abb9a4919b15551d23461abc937a403";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PayloadFile {
    pub path: String,
    pub sha256: String,
    pub size: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerInfo {
    pub host: String,
    #[serde(rename = "connectPort")]
    pub connect_port: u16,
    #[serde(rename = "queryPort")]
    pub query_port: u16,
    pub name: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Links {
    #[serde(default)]
    pub discord: String,
    #[serde(default)]
    pub website: String,
    #[serde(default)]
    pub tiktok: String,
    #[serde(default)]
    pub youtube: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Manifest {
    pub schema: u32,
    pub build: u64,
    #[serde(rename = "builtAgainstJarSha256")]
    pub built_against_jar_sha256: String,
    #[serde(rename = "classReleaseTarget")]
    pub class_release_target: u32,
    pub server: ServerInfo,
    #[serde(rename = "requiredServerBuild")]
    pub required_server_build: u64,
    pub files: Vec<PayloadFile>,
    #[serde(rename = "serverFiles", default)]
    pub server_files: Vec<PayloadFile>,

    #[serde(default)]
    pub mods: Vec<crate::workshop::ModEntry>,
    #[serde(rename = "collectionId", default)]
    pub collection_id: String,
    #[serde(rename = "collectionName", default)]
    pub collection_name: String,
    #[serde(rename = "collectionMods", default)]
    pub collection_mods: Vec<crate::workshop::ModEntry>,
    #[serde(rename = "newsUrl", default)]
    pub news_url: String,
    #[serde(default)]
    pub links: Links,
}

fn manifest_url() -> String {
    #[cfg(debug_assertions)]
    {
        if let Ok(url) = std::env::var(config::MANIFEST_URL_ENV) {
            return url;
        }

        let local = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .map(|root| root.join("release").join("manifest.json"));
        if let Some(path) = local.filter(|path| path.is_file()) {
            return format!("file:///{}", path.to_string_lossy().replace('\\', "/"));
        }
    }

    resolve_baked(config::DEFAULT_MANIFEST_URL)
}

fn resolve_baked(baked: &str) -> String {
    if baked.starts_with("http://")
        || baked.starts_with("https://")
        || baked.starts_with("file:///")
    {
        return baked.to_string();
    }
    let path = Path::new(baked);
    if path.is_absolute() {
        return baked.to_string();
    }
    match std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|d| d.join(path)))
    {
        Some(resolved) => resolved.to_string_lossy().into_owned(),
        None => baked.to_string(),
    }
}

fn base_url(manifest: &str) -> String {
    match manifest.rfind(['/', '\\']) {
        Some(i) => manifest[..i].to_string(),
        None => manifest.to_string(),
    }
}

#[cfg(test)]
mod url_tests {
    use super::{base_url, resolve_baked};

    #[test]
    fn base_url_handles_both_separators() {
        assert_eq!(base_url("https://h/p/manifest.json"), "https://h/p");
        assert_eq!(base_url("C:\\x\\release\\manifest.json"), "C:\\x\\release");
        assert_eq!(base_url("manifest.json"), "manifest.json");
    }

    #[test]
    fn absolute_and_remote_urls_are_left_alone() {
        assert_eq!(
            resolve_baked("https://h/p/manifest.json"),
            "https://h/p/manifest.json"
        );
        let absolute = if cfg!(windows) {
            r"C:\x\manifest.json"
        } else {
            "/x/manifest.json"
        };
        assert_eq!(resolve_baked(absolute), absolute);
    }

    #[test]
    fn a_relative_url_lands_next_to_the_executable() {
        let resolved = resolve_baked("release/manifest.json");
        let exe_dir = std::env::current_exe()
            .unwrap()
            .parent()
            .unwrap()
            .to_path_buf();
        assert!(
            std::path::Path::new(&resolved).starts_with(&exe_dir),
            "{resolved} is not under {}",
            exe_dir.display()
        );
    }
}

// reqwest::get builds a default client with no timeout of any kind, so a socket that stalled
// mid-download left this hanging until the player killed the launcher. A read timeout rather
// than a whole-request one: a large file on a slow line is fine, a socket that stops talking
// is not.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(30);
const FETCH_ATTEMPTS: u32 = 3;

fn client() -> &'static reqwest::Client {
    static CLIENT: OnceLock<reqwest::Client> = OnceLock::new();
    CLIENT.get_or_init(|| {
        reqwest::Client::builder()
            .connect_timeout(CONNECT_TIMEOUT)
            .read_timeout(READ_TIMEOUT)
            .build()
            .unwrap_or_else(|_| reqwest::Client::new())
    })
}

async fn fetch_once(url: &str) -> Result<Vec<u8>> {
    let resp = client().get(url).send().await?;
    let status = resp.status();
    if !status.is_success() {
        return Err(Error::Http {
            url: url.to_string(),
            status: status.as_u16(),
        });
    }
    Ok(resp.bytes().await?.to_vec())
}

// A 4xx that is not "slow down" or "try again" means the file is not there. Retrying makes the
// player wait three times as long for the same answer.
fn worth_retrying(e: &Error) -> bool {
    match e {
        Error::Http { status, .. } => *status == 408 || *status == 429 || *status >= 500,
        _ => true,
    }
}

#[cfg(test)]
mod retry_tests {
    use super::worth_retrying;
    use crate::error::Error;

    fn http(status: u16) -> Error {
        Error::Http {
            url: "https://h/files/x".into(),
            status,
        }
    }

    #[test]
    fn a_missing_file_fails_on_the_first_answer() {
        assert!(!worth_retrying(&http(404)));
        assert!(!worth_retrying(&http(403)));
    }

    #[test]
    fn a_server_that_is_struggling_gets_another_go() {
        for status in [408, 429, 500, 502, 503] {
            assert!(worth_retrying(&http(status)), "{status} should retry");
        }
    }

    #[test]
    fn a_transport_failure_is_the_case_retries_exist_for() {
        assert!(worth_retrying(&Error::Other("network: timed out".into())));
    }
}

async fn fetch(url: &str) -> Result<Vec<u8>> {
    if let Some(rest) = url.strip_prefix("file:///") {
        return Ok(fs::read(rest.replace('/', "\\"))?);
    }
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Ok(fs::read(url)?);
    }

    let mut attempt = 1u32;
    loop {
        match fetch_once(url).await {
            Ok(bytes) => return Ok(bytes),
            Err(e) if attempt < FETCH_ATTEMPTS && worth_retrying(&e) => {
                tokio::time::sleep(Duration::from_secs(attempt as u64)).await;
                attempt += 1;
            }
            Err(e) => return Err(e),
        }
    }
}

pub async fn fetch_manifest() -> Result<Manifest> {
    let url = manifest_url();
    let bytes = fetch(&url).await?;
    let sig_bytes = fetch(&format!("{url}.sig")).await?;

    let key_raw: [u8; 32] = hex::decode(PUBLIC_KEY_HEX)
        .map_err(|e| Error::Other(format!("bad embedded public key: {e}")))?
        .try_into()
        .map_err(|_| Error::Other("embedded public key is not 32 bytes".into()))?;
    let key = VerifyingKey::from_bytes(&key_raw).map_err(|_| Error::BadSignature)?;

    let sig_raw: [u8; 64] = sig_bytes
        .as_slice()
        .try_into()
        .map_err(|_| Error::BadSignature)?;
    key.verify(&bytes, &Signature::from_bytes(&sig_raw))
        .map_err(|_| Error::BadSignature)?;

    let manifest: Manifest = serde_json::from_slice(&bytes)?;
    guard_rollback(&url, &manifest)?;
    Ok(manifest)
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
struct RollbackGuard {
    #[serde(rename = "highestSeenBuild")]
    highest_seen_build: u64,
}

fn read_watermark() -> u64 {
    fs::read(config::rollback_guard_path())
        .ok()
        .and_then(|b| serde_json::from_slice::<RollbackGuard>(&b).ok())
        .map(|g| g.highest_seen_build)
        .unwrap_or(0)
}

fn write_watermark(build: u64) -> Result<()> {
    let path = config::rollback_guard_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let bytes = serde_json::to_vec_pretty(&RollbackGuard {
        highest_seen_build: build,
    })?;
    let mut f = fs::File::create(&path)?;
    f.write_all(&bytes)?;
    f.sync_all()?;
    Ok(())
}

fn is_rollback(seen: u64, offered: u64) -> bool {
    offered < seen
}

fn guard_rollback(url: &str, m: &Manifest) -> Result<()> {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return Ok(());
    }
    let seen = read_watermark();
    if is_rollback(seen, m.build) {
        return Err(Error::Rollback {
            seen,
            offered: m.build,
        });
    }
    if m.build > seen {
        write_watermark(m.build)?;
    }
    Ok(())
}

#[cfg(test)]
mod rollback_tests {
    use super::is_rollback;

    #[test]
    fn a_lower_build_is_refused_and_the_same_build_is_not() {
        assert!(is_rollback(9, 8), "build 8 after 9 is a rollback");
        assert!(!is_rollback(9, 9), "re-fetching the current build is normal");
        assert!(!is_rollback(9, 10), "a newer build is an update");
        assert!(!is_rollback(0, 1), "a first run must not be blocked");
    }
}

fn file_matches(path: &Path, expect: &PayloadFile) -> bool {
    let Ok(bytes) = fs::read(path) else {
        return false;
    };
    bytes.len() as u64 == expect.size && hex::encode(Sha256::digest(&bytes)) == expect.sha256
}

pub fn is_installed(m: &Manifest) -> bool {
    let dir = config::patch_dir(m.build);
    m.files.iter().all(|f| file_matches(&dir.join(&f.path), f))
}

// The whole sync used to report one line before the loop started, so a big file and a wedged
// connection looked the same from the outside and players killed the launcher on the second.
// Hashing every file up front costs nothing extra -- it was already hashed inside the loop --
// and it buys a count the caller can actually show.
pub async fn sync(m: &Manifest, progress: &(dyn Fn(&str) + Send + Sync)) -> Result<usize> {
    let dir = config::patch_dir(m.build);
    let base = base_url(&manifest_url());

    let pending: Vec<&PayloadFile> = m
        .files
        .iter()
        .filter(|f| !file_matches(&dir.join(&f.path), f))
        .collect();
    let total = pending.len();

    for (i, f) in pending.iter().enumerate() {
        progress(&format!(
            "Syncing patch file {} of {total} ({})",
            i + 1,
            f.path
        ));
        let dest = dir.join(&f.path);
        let bytes = fetch(&format!("{base}/files/{}", f.path)).await?;
        let actual = hex::encode(Sha256::digest(&bytes));
        if actual != f.sha256 || bytes.len() as u64 != f.size {
            return Err(Error::BadChecksum {
                path: f.path.clone(),
                expected: f.sha256.clone(),
                actual,
            });
        }
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&dest, &bytes)?;
    }
    Ok(total)
}
