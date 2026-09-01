use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

pub const PZ_APP_ID: &str = "108600";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModEntry {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub time_updated: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModStatus {
    pub id: String,
    pub name: String,
    pub installed: bool,
    pub downloading: bool,
    pub out_of_date: bool,
    pub behind_server: bool,
    pub ahead_of_server: bool,
    pub time_updated: Option<u64>,
    pub size_bytes: u64,
}

impl ModStatus {
    // Steam has work left on this item. Either is enough to hand the game a mod folder that is
    // not there yet, and ZomboidFileSystem memoises that answer for the whole session.
    pub fn not_ready(&self) -> bool {
        self.downloading || self.out_of_date
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Requirement {
    pub kind: String,
    pub id: String,
    pub name: String,
    pub url: String,
    pub total: usize,
    pub installed: usize,
    pub downloading: usize,
    pub missing: usize,
    pub out_of_date: usize,
    pub behind_server: usize,
    pub ahead_of_server: usize,
    pub verified: bool,
    pub missing_names: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModReport {
    pub total: usize,
    pub installed: usize,
    pub downloading: usize,
    pub missing: usize,
    pub out_of_date: usize,
    pub behind_server: usize,
    pub ahead_of_server: usize,
    pub collection_url: Option<String>,
    pub requirements: Vec<Requirement>,
    pub mods: Vec<ModStatus>,
}

const MISSING_NAMES_SHOWN: usize = 6;

pub fn describe_time(epoch: Option<u64>) -> String {
    let Some(secs) = epoch else {
        return "an unknown date".to_string();
    };
    let days = (secs / 86_400) as i64;
    let (hour, minute) = ((secs % 86_400) / 3600, (secs % 3600) / 60);

    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = era * 400 + yoe as i64 + i64::from(month <= 2);

    format!("{year:04}-{month:02}-{day:02} {hour:02}:{minute:02} UTC")
}

pub fn parse_id(raw: &str) -> Option<String> {
    let raw = raw.trim();
    if raw.is_empty() {
        return None;
    }
    if raw.chars().all(|c| c.is_ascii_digit()) {
        return Some(raw.to_string());
    }
    let after = raw.split("id=").nth(1)?;
    let digits: String = after.chars().take_while(char::is_ascii_digit).collect();
    if digits.is_empty() {
        None
    } else {
        Some(digits)
    }
}

fn workshop_content_dirs() -> Vec<PathBuf> {
    crate::install::steam_library_dirs()
        .into_iter()
        .map(|lib| {
            lib.join("steamapps")
                .join("workshop")
                .join("content")
                .join(PZ_APP_ID)
        })
        .filter(|p| p.is_dir())
        .collect()
}

fn dir_size(path: &PathBuf) -> u64 {
    let mut total = 0u64;
    let Ok(entries) = fs::read_dir(path) else {
        return 0;
    };
    for e in entries.flatten() {
        let Ok(meta) = e.metadata() else { continue };
        if meta.is_dir() {
            total += dir_size(&e.path());
        } else {
            total += meta.len();
        }
    }
    total
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AcfItem {
    pub manifest: String,
    pub latest_manifest: String,
    pub size_bytes: u64,
    pub time_updated: u64,
    pub subscribed: bool,
}

impl AcfItem {
    pub fn out_of_date(&self) -> bool {
        !self.latest_manifest.is_empty()
            && !self.manifest.is_empty()
            && self.latest_manifest != self.manifest
    }

    pub fn behind(&self, expected: Option<u64>) -> bool {
        const SLACK: u64 = 30 * 60;
        match expected {
            Some(want) if want > SLACK && self.time_updated > 0 => {
                self.time_updated < want - SLACK
            }
            _ => false,
        }
    }

    // The other direction, and the one nothing used to catch: Steam updated the item on this
    // machine while the server still runs the copy the release was cut against. PZ refuses a
    // mismatch either way, but only an admin can fix this one, so it warns rather than blocks.
    pub fn ahead(&self, expected: Option<u64>) -> bool {
        const SLACK: u64 = 30 * 60;
        match expected {
            Some(want) if want > 0 && self.time_updated > 0 => self.time_updated > want + SLACK,
            _ => false,
        }
    }
}

pub fn acf_items() -> BTreeMap<String, AcfItem> {
    let mut out: BTreeMap<String, AcfItem> = BTreeMap::new();
    for lib in crate::install::steam_library_dirs() {
        let path = lib
            .join("steamapps")
            .join("workshop")
            .join(format!("appworkshop_{PZ_APP_ID}.acf"));
        let Ok(text) = fs::read_to_string(&path) else {
            continue;
        };
        merge_acf(&text, &mut out);
    }
    out
}

fn merge_acf(text: &str, out: &mut BTreeMap<String, AcfItem>) {
    let parsed = crate::steam::parse_vdf(text);
    let Some((_, root)) = parsed.entries().first() else {
        return;
    };

    if let Some(installed) = root.get("WorkshopItemsInstalled") {
        for (id, entry) in installed.entries() {
            let item = out.entry(id.clone()).or_default();
            item.subscribed = true;
            if let Some(m) = entry.get("manifest").and_then(crate::steam::Vdf::as_str) {
                item.manifest = m.to_string();
            }
            if let Some(s) = entry.get("size").and_then(crate::steam::Vdf::as_str) {
                item.size_bytes = s.parse().unwrap_or(0);
            }
        }
    }

    if let Some(details) = root.get("WorkshopItemDetails") {
        for (id, entry) in details.entries() {
            let item = out.entry(id.clone()).or_default();
            item.subscribed = true;
            let get = |k: &str| {
                entry
                    .get(k)
                    .and_then(crate::steam::Vdf::as_str)
                    .unwrap_or_default()
                    .to_string()
            };
            if item.manifest.is_empty() {
                item.manifest = get("manifest");
            }
            item.latest_manifest = get("latest_manifest");
            item.time_updated = get("timeupdated").parse().unwrap_or(0);
        }
    }
}

pub struct Requirements<'a> {
    pub items: &'a [ModEntry],
    pub collection_id: &'a str,
    pub collection_name: &'a str,
    pub collection_items: &'a [ModEntry],
}

const COLLECTION_FALLBACK_NAME: &str = "Server mod collection";

fn scan(mods: &[ModEntry], dirs: &[PathBuf], acf: &BTreeMap<String, AcfItem>) -> Vec<ModStatus> {
    mods.iter()
        .map(|m| {
            let found = dirs.iter().map(|d| d.join(&m.id)).find(|p| p.is_dir());
            let installed = found.is_some();
            let state = acf.get(&m.id);
            ModStatus {
                id: m.id.clone(),
                name: m.name.clone(),
                installed,
                downloading: !installed && state.is_some_and(|s| s.subscribed),
                out_of_date: installed && state.is_some_and(AcfItem::out_of_date),
                behind_server: installed && state.is_some_and(|s| s.behind(m.time_updated)),
                ahead_of_server: installed && state.is_some_and(|s| s.ahead(m.time_updated)),
                time_updated: state.map(|s| s.time_updated).filter(|t| *t > 0),
                size_bytes: found.as_ref().map(dir_size).unwrap_or(0),
            }
        })
        .collect()
}

fn tally(kind: &str, id: &str, name: &str, statuses: &[ModStatus], verified: bool) -> Requirement {
    let installed = statuses.iter().filter(|m| m.installed).count();
    let downloading = statuses.iter().filter(|m| m.downloading).count();
    Requirement {
        kind: kind.to_string(),
        id: id.to_string(),
        name: name.to_string(),
        url: item_url(id),
        total: statuses.len(),
        installed,
        downloading,
        missing: statuses.len() - installed - downloading,
        out_of_date: statuses.iter().filter(|m| m.out_of_date).count(),
        behind_server: statuses.iter().filter(|m| m.behind_server).count(),
        ahead_of_server: statuses.iter().filter(|m| m.ahead_of_server).count(),
        verified,
        missing_names: statuses
            .iter()
            .filter(|m| !m.installed && !m.downloading)
            .take(MISSING_NAMES_SHOWN)
            .map(|m| m.name.clone())
            .collect(),
    }
}

pub fn report(req: Requirements<'_>) -> ModReport {
    let dirs = workshop_content_dirs();
    let acf = acf_items();

    let collection = scan(req.collection_items, &dirs, &acf);
    let items = scan(req.items, &dirs, &acf);

    let mut requirements: Vec<Requirement> = Vec::new();
    if !req.collection_id.is_empty() {
        let name = if req.collection_name.is_empty() {
            COLLECTION_FALLBACK_NAME
        } else {
            req.collection_name
        };
        requirements.push(tally(
            "collection",
            req.collection_id,
            name,
            &collection,
            !req.collection_items.is_empty(),
        ));
    }
    for (entry, status) in req.items.iter().zip(items.iter()) {
        requirements.push(tally(
            "item",
            &entry.id,
            &entry.name,
            std::slice::from_ref(status),
            true,
        ));
    }

    let statuses: Vec<ModStatus> = collection.into_iter().chain(items).collect();
    let installed = statuses.iter().filter(|m| m.installed).count();
    let downloading = statuses.iter().filter(|m| m.downloading).count();

    ModReport {
        total: statuses.len(),
        installed,
        downloading,
        missing: statuses.len() - installed - downloading,
        out_of_date: statuses.iter().filter(|m| m.out_of_date).count(),
        behind_server: statuses.iter().filter(|m| m.behind_server).count(),
        ahead_of_server: statuses.iter().filter(|m| m.ahead_of_server).count(),
        collection_url: Some(item_url(req.collection_id)).filter(|_| !req.collection_id.is_empty()),
        requirements,
        mods: statuses,
    }
}

pub fn open_in_steam(url: &str) -> Result<()> {
    if !url.starts_with("https://steamcommunity.com/") {
        return Err(Error::Other("refusing to open a non-Steam URL".into()));
    }
    let steam_url = format!("steam://openurl/{url}");
    open_url(&steam_url)
}

pub fn item_url(id: &str) -> String {
    format!("https://steamcommunity.com/sharedfiles/filedetails/?id={id}")
}

fn open_url(url: &str) -> Result<()> {
    tauri_plugin_opener::open_url(url, None::<&str>)
        .map_err(|e| Error::Other(format!("could not open Steam: {e}")))?;
    Ok(())
}

#[cfg(test)]
mod requirement_tests {
    use super::*;

    fn status(name: &str, installed: bool, downloading: bool) -> ModStatus {
        ModStatus {
            id: name.to_string(),
            name: name.to_string(),
            installed,
            downloading,
            out_of_date: false,
            behind_server: false,
            ahead_of_server: false,
            time_updated: None,
            size_bytes: 0,
        }
    }

    #[test]
    fn a_collection_counts_what_is_missing_and_names_a_few_of_them() {
        let items: Vec<ModStatus> = (0..10)
            .map(|i| status(&format!("mod {i}"), i < 2, false))
            .collect();
        let r = tally("collection", "1", "Pack", &items, true);

        assert_eq!((r.total, r.installed, r.missing), (10, 2, 8));
        assert_eq!(r.missing_names.len(), MISSING_NAMES_SHOWN);
        assert_eq!(r.missing_names[0], "mod 2");
    }

    #[test]
    fn downloading_is_neither_installed_nor_missing() {
        let items = vec![
            status("a", true, false),
            status("b", false, true),
            status("c", false, false),
        ];
        let r = tally("collection", "1", "Pack", &items, true);
        assert_eq!((r.installed, r.downloading, r.missing), (1, 1, 1));
        assert_eq!(r.missing_names, vec!["c".to_string()]);
    }

    #[test]
    fn steam_still_working_reads_as_not_ready_either_way() {
        let mut mid_download = status("a", false, true);
        assert!(mid_download.not_ready(), "still downloading");

        mid_download.downloading = false;
        mid_download.installed = true;
        mid_download.out_of_date = true;
        assert!(mid_download.not_ready(), "installed but an update is pending");

        mid_download.out_of_date = false;
        assert!(!mid_download.not_ready(), "installed and current");
    }

    #[test]
    fn an_unsubscribed_mod_is_not_the_same_as_one_steam_is_mid_way_through() {
        // missing is a different failure: the folder never appears, so nothing races to read it.
        // It stays a warning, and the launch gate must not pick it up.
        assert!(!status("c", false, false).not_ready());
    }

    #[test]
    fn a_collection_with_nothing_to_check_reads_as_unverified_not_as_complete() {
        let r = tally("collection", "1", "Pack", &[], false);
        assert!(!r.verified);
        assert_eq!((r.total, r.installed, r.missing), (0, 0, 0));
    }

    #[test]
    fn the_collection_row_is_kept_even_when_it_cannot_be_checked() {
        let m = [ModEntry {
            id: "42".into(),
            name: "PLZ".into(),
            time_updated: None,
        }];
        let report = report(Requirements {
            items: &m,
            collection_id: "77",
            collection_name: "",
            collection_items: &[],
        });
        assert_eq!(report.requirements.len(), 2);
        assert_eq!(report.requirements[0].kind, "collection");
        assert_eq!(report.requirements[0].name, COLLECTION_FALLBACK_NAME);
        assert_eq!(report.requirements[1].id, "42");
        assert!(report.requirements[1].verified);
    }

    #[test]
    fn no_collection_means_no_collection_row_and_no_link() {
        let report = report(Requirements {
            items: &[],
            collection_id: "",
            collection_name: "",
            collection_items: &[],
        });
        assert!(report.requirements.is_empty());
        assert!(report.collection_url.is_none());
    }
}

#[cfg(test)]
mod tests {
    use super::parse_id;

    #[test]
    fn accepts_bare_ids_and_urls() {
        assert_eq!(parse_id("2335368829").as_deref(), Some("2335368829"));
        assert_eq!(
            parse_id("https://steamcommunity.com/sharedfiles/filedetails/?id=2335368829")
                .as_deref(),
            Some("2335368829")
        );
        assert_eq!(
            parse_id("https://steamcommunity.com/workshop/filedetails/?id=2169435993&searchtext=x")
                .as_deref(),
            Some("2169435993")
        );
    }

    #[test]
    fn rejects_junk() {
        assert!(parse_id("").is_none());
        assert!(parse_id("not a mod").is_none());
        assert!(parse_id("https://example.com/?id=abc").is_none());
    }
}

#[cfg(test)]
mod acf_tests {
    use super::*;

    const SAMPLE: &str = r#"
"AppWorkshop"
{
	"appid"		"108600"
	"WorkshopItemsInstalled"
	{
		"111"
		{
			"size"		"1234"
			"manifest"		"AAA"
		}
		"222"
		{
			"size"		"55"
			"manifest"		"OLD"
		}
	}
	"WorkshopItemDetails"
	{
		"111"
		{
			"manifest"		"AAA"
			"latest_manifest"		"AAA"
		}
		"222"
		{
			"manifest"		"OLD"
			"latest_manifest"		"NEW"
		}
		"333"
		{
			"latest_manifest"		"ZZZ"
		}
	}
}
"#;

    fn items() -> BTreeMap<String, AcfItem> {
        let mut out = BTreeMap::new();
        merge_acf(SAMPLE, &mut out);
        out
    }

    #[test]
    fn a_newer_build_steam_has_not_applied_reads_as_out_of_date() {
        let all = items();
        assert!(!all["111"].out_of_date(), "installed build is the current one");
        assert!(all["222"].out_of_date(), "OLD on disk while NEW is current");
        assert_eq!(all["111"].size_bytes, 1234);
    }

    #[test]
    fn an_item_with_nothing_to_compare_is_never_called_stale() {
        let all = items();
        assert!(all["333"].subscribed);
        assert!(!all["333"].out_of_date());
    }

    #[test]
    fn behind_needs_both_numbers_and_ignores_publisher_clock_skew() {
        let mut item = AcfItem {
            time_updated: 1_787_879_368,
            ..AcfItem::default()
        };

        assert!(item.behind(Some(1_787_936_663)));

        assert!(!item.behind(None));
        assert!(!AcfItem::default().behind(Some(1_787_936_663)));

        item.time_updated = 1_787_936_655;
        assert!(!item.behind(Some(1_787_936_663)));

        item.time_updated = 1_787_999_999;
        assert!(!item.behind(Some(1_787_936_663)));
    }

    #[test]
    fn ahead_catches_the_direction_behind_is_blind_to() {
        let expected = Some(1_787_936_663);
        let mut item = AcfItem {
            time_updated: 1_787_999_999,
            ..AcfItem::default()
        };

        // The case that used to reach the join unchecked and come back as a refusal.
        assert!(item.ahead(expected));
        assert!(!item.behind(expected), "the old gate cannot see this one");

        assert!(!item.ahead(None), "nothing to compare against");
        assert!(!AcfItem::default().ahead(expected), "no local time");

        item.time_updated = 1_787_936_655;
        assert!(!item.ahead(expected), "same publish, within the slack");

        item.time_updated = 1_787_879_368;
        assert!(!item.ahead(expected), "older is behind, not ahead");
        assert!(item.behind(expected));
    }

    #[test]
    fn describe_time_renders_a_calendar_date() {
        assert_eq!(describe_time(Some(1_787_879_368)), "2026-08-28 01:09 UTC");
        assert_eq!(describe_time(Some(0)), "1970-01-01 00:00 UTC");
        assert_eq!(describe_time(None), "an unknown date");
    }

    #[test]
    #[ignore]
    fn real_acf_parses() {
        let all = acf_items();
        eprintln!("{} workshop item(s) known to Steam", all.len());
        let stale: Vec<_> = all.iter().filter(|(_, i)| i.out_of_date()).collect();
        eprintln!("{} out of date", stale.len());
        for (id, item) in stale.iter().take(5) {
            eprintln!("  {id}: {} -> {}", item.manifest, item.latest_manifest);
        }
        assert!(!all.is_empty(), "real acf parsed to zero items");
        assert!(
            all.values().any(|i| !i.manifest.is_empty()),
            "no manifest was read, so the keys are wrong"
        );
    }
}
