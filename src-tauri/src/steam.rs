use crate::error::{Error, Result};
use std::fs;
#[cfg(not(windows))]
use std::path::Path;
use std::path::PathBuf;

const STEAM_ID64_BASE: u64 = 76_561_197_960_265_728;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SteamAccount {
    pub steam_id64: u64,
    pub account_name: String,
    pub persona_name: String,
    pub most_recent: bool,
    pub timestamp: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Vdf {
    Str(String),
    Block(Vec<(String, Vdf)>),
}

impl Vdf {
    pub fn get(&self, key: &str) -> Option<&Vdf> {
        match self {
            Vdf::Block(items) => items
                .iter()
                .find(|(k, _)| k.eq_ignore_ascii_case(key))
                .map(|(_, v)| v),
            Vdf::Str(_) => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Vdf::Str(s) => Some(s),
            Vdf::Block(_) => None,
        }
    }

    pub fn entries(&self) -> &[(String, Vdf)] {
        match self {
            Vdf::Block(items) => items,
            Vdf::Str(_) => &[],
        }
    }
}

pub fn parse_vdf(text: &str) -> Vdf {
    let bytes: Vec<char> = text.chars().collect();
    let mut at = 0usize;
    Vdf::Block(parse_block(&bytes, &mut at, 0))
}

fn parse_block(chars: &[char], at: &mut usize, depth: u32) -> Vec<(String, Vdf)> {
    let mut out = Vec::new();
    if depth > 32 {
        return out;
    }
    while *at < chars.len() {
        skip_trivia(chars, at);
        if *at >= chars.len() {
            break;
        }
        match chars[*at] {
            '}' => {
                *at += 1;
                break;
            }
            '"' => {
                let Some(key) = read_quoted(chars, at) else {
                    break;
                };
                skip_trivia(chars, at);
                if *at >= chars.len() {
                    break;
                }
                if chars[*at] == '{' {
                    *at += 1;
                    out.push((key, Vdf::Block(parse_block(chars, at, depth + 1))));
                } else if chars[*at] == '"' {
                    if let Some(value) = read_quoted(chars, at) {
                        out.push((key, Vdf::Str(value)));
                    }
                }
            }
            _ => *at += 1,
        }
    }
    out
}

fn skip_trivia(chars: &[char], at: &mut usize) {
    loop {
        while *at < chars.len() && chars[*at].is_whitespace() {
            *at += 1;
        }
        if *at + 1 < chars.len() && chars[*at] == '/' && chars[*at + 1] == '/' {
            while *at < chars.len() && chars[*at] != '\n' {
                *at += 1;
            }
            continue;
        }
        return;
    }
}

fn read_quoted(chars: &[char], at: &mut usize) -> Option<String> {
    if *at >= chars.len() || chars[*at] != '"' {
        return None;
    }
    *at += 1;
    let mut out = String::new();
    while *at < chars.len() {
        match chars[*at] {
            '\\' if *at + 1 < chars.len() => {
                out.push(chars[*at + 1]);
                *at += 2;
            }
            '"' => {
                *at += 1;
                return Some(out);
            }
            c => {
                out.push(c);
                *at += 1;
            }
        }
    }
    None
}

#[cfg(windows)]
pub fn root() -> Option<PathBuf> {
    use winreg::enums::{HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE};
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok(k) = hkcu.open_subkey(r"Software\Valve\Steam") {
        if let Ok(p) = k.get_value::<String, _>("SteamPath") {
            return Some(PathBuf::from(p.replace('/', "\\")));
        }
    }
    let hklm = RegKey::predef(HKEY_LOCAL_MACHINE);
    if let Ok(k) = hklm.open_subkey(r"Software\WOW6432Node\Valve\Steam") {
        if let Ok(p) = k.get_value::<String, _>("InstallPath") {
            return Some(PathBuf::from(p.replace('/', "\\")));
        }
    }
    None
}

#[cfg(target_os = "linux")]
pub fn root() -> Option<PathBuf> {
    let home = dirs::home_dir()?;
    [
        home.join(".steam/steam"),
        home.join(".steam/root"),
        home.join(".local/share/Steam"),
        home.join(".var/app/com.valvesoftware.Steam/.local/share/Steam"),
    ]
    .into_iter()
    .find(|p| p.join("steamapps").is_dir() || p.join("config").is_dir())
}

#[cfg(target_os = "macos")]
pub fn root() -> Option<PathBuf> {
    let home = dirs::home_dir()?;
    [home.join("Library/Application Support/Steam")]
        .into_iter()
        .find(|p| p.join("steamapps").is_dir() || p.join("config").is_dir())
}

pub fn library_dirs() -> Vec<PathBuf> {
    let Some(root) = root() else {
        return Vec::new();
    };
    let vdf = root.join("steamapps").join("libraryfolders.vdf");
    let Ok(text) = fs::read_to_string(&vdf) else {
        return vec![root];
    };

    let mut out = vec![root];
    let parsed = parse_vdf(&text);
    if let Some(folders) = parsed.get("libraryfolders") {
        for (_, entry) in folders.entries() {
            if let Some(path) = entry.get("path").and_then(Vdf::as_str) {
                out.push(PathBuf::from(path));
            }
        }
    }
    out
}

fn login_users_path() -> Option<PathBuf> {
    Some(root()?.join("config").join("loginusers.vdf"))
}

pub fn login_users() -> Vec<SteamAccount> {
    let Some(path) = login_users_path() else {
        return Vec::new();
    };
    let Ok(text) = fs::read_to_string(&path) else {
        return Vec::new();
    };
    parse_login_users(&text)
}

pub fn parse_login_users(text: &str) -> Vec<SteamAccount> {
    let parsed = parse_vdf(text);
    let Some(users) = parsed.get("users") else {
        return Vec::new();
    };

    let mut out: Vec<SteamAccount> = users
        .entries()
        .iter()
        .filter_map(|(id, entry)| {
            let steam_id64: u64 = id.trim().parse().ok()?;
            if steam_id64 <= STEAM_ID64_BASE {
                return None;
            }
            let flag = |k: &str| entry.get(k).and_then(Vdf::as_str) == Some("1");
            Some(SteamAccount {
                steam_id64,
                account_name: entry
                    .get("AccountName")
                    .and_then(Vdf::as_str)
                    .unwrap_or_default()
                    .to_string(),
                persona_name: entry
                    .get("PersonaName")
                    .and_then(Vdf::as_str)
                    .unwrap_or_default()
                    .to_string(),
                most_recent: flag("MostRecent") || flag("AutoLogin"),
                timestamp: entry
                    .get("Timestamp")
                    .and_then(Vdf::as_str)
                    .and_then(|s| s.parse().ok())
                    .unwrap_or(0),
            })
        })
        .collect();

    out.sort_by(|a, b| {
        b.most_recent
            .cmp(&a.most_recent)
            .then(b.timestamp.cmp(&a.timestamp))
    });
    out
}

#[cfg(windows)]
fn active_from_platform() -> Option<u64> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let key = hkcu.open_subkey(r"Software\Valve\Steam\ActiveProcess").ok()?;
    let account_id: u32 = key.get_value("ActiveUser").ok()?;
    (account_id != 0).then(|| STEAM_ID64_BASE + u64::from(account_id))
}

#[cfg(not(windows))]
fn active_from_platform() -> Option<u64> {
    let text = fs::read_to_string(root()?.join("registry.vdf")).ok()?;
    let parsed = parse_vdf(&text);
    let active = parsed
        .get("Registry")?
        .get("HKCU")?
        .get("Software")?
        .get("Valve")?
        .get("Steam")?
        .get("ActiveProcess")?
        .get("ActiveUser")?
        .as_str()?
        .parse::<u32>()
        .ok()?;
    (active != 0).then(|| STEAM_ID64_BASE + u64::from(active))
}

pub fn resolve_steam_id(override_id: Option<u64>) -> Result<u64> {
    if let Some(id) = override_id {
        return Ok(id);
    }
    if let Some(id) = active_from_platform() {
        return Ok(id);
    }
    if let Some(account) = login_users().into_iter().next() {
        return Ok(account.steam_id64);
    }
    Err(Error::Other(
        "Could not work out which Steam account you are using. Open Steam and sign in, or choose \
         your account under Details."
            .into(),
    ))
}

#[cfg(windows)]
pub fn exe() -> Result<PathBuf> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;

    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok(k) = hkcu.open_subkey(r"Software\Valve\Steam") {
        if let Ok(p) = k.get_value::<String, _>("SteamExe") {
            let path = PathBuf::from(p.replace('/', "\\"));
            if path.is_file() {
                return Ok(path);
            }
        }
    }
    let fallback = PathBuf::from(r"C:\Program Files (x86)\Steam\steam.exe");
    if fallback.is_file() {
        return Ok(fallback);
    }
    Err(Error::SteamNotFound)
}

#[cfg(target_os = "linux")]
pub fn exe() -> Result<PathBuf> {
    for candidate in ["/usr/bin/steam", "/usr/local/bin/steam", "/bin/steam"] {
        let path = Path::new(candidate);
        if path.is_file() {
            return Ok(path.to_path_buf());
        }
    }
    Ok(PathBuf::from("steam"))
}

#[cfg(target_os = "macos")]
pub fn exe() -> Result<PathBuf> {
    let app = Path::new("/Applications/Steam.app/Contents/MacOS/steam_osx");
    if app.is_file() {
        return Ok(app.to_path_buf());
    }
    Err(Error::SteamNotFound)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"
"users"
{
	"76561197971580262"
	{
		"AccountName"		"olduser"
		"PersonaName"		"Old"
		"AutoLogin"		"0"
		"Timestamp"		"1700000000"
	}
	"76561199480960000"
	{
		"AccountName"		"current"
		"PersonaName"		"Current Player"
		"AutoLogin"		"1"
		"Timestamp"		"1787681900"
	}
}
"#;

    #[test]
    fn the_section_key_is_the_steamid_and_autologin_sorts_first() {
        let users = parse_login_users(SAMPLE);
        assert_eq!(users.len(), 2);
        assert_eq!(users[0].steam_id64, 76_561_199_480_960_000);
        assert_eq!(users[0].persona_name, "Current Player");
        assert!(users[0].most_recent);
        assert_eq!(users[1].account_name, "olduser");
    }

    #[test]
    fn ties_fall_back_to_the_newest_login() {
        let text = SAMPLE.replace("\"AutoLogin\"\t\t\"1\"", "\"AutoLogin\"\t\t\"0\"");
        let users = parse_login_users(&text);
        assert_eq!(users[0].timestamp, 1_787_681_900);
    }

    #[test]
    fn a_missing_or_unreadable_file_is_an_empty_list_not_an_error() {
        assert!(parse_login_users("").is_empty());
        assert!(parse_login_users("{{{ not a vdf").is_empty());
        assert!(parse_login_users("\"users\" { \"0\" { } }").is_empty());
    }

    #[test]
    fn escaped_backslashes_in_paths_collapse() {
        let v = parse_vdf(r#""libraryfolders" { "1" { "path" "D:\\Games\\Steam" } }"#);
        let p = v
            .get("libraryfolders")
            .and_then(|f| f.get("1"))
            .and_then(|e| e.get("path"))
            .and_then(Vdf::as_str)
            .unwrap();
        assert_eq!(p, r"D:\Games\Steam");
    }

    #[test]
    fn keys_match_regardless_of_case() {
        let v = parse_vdf(r#""Users" { "76561197971580263" { "personaname" "lower" } }"#);
        let name = v
            .get("users")
            .and_then(|u| u.get("76561197971580263"))
            .and_then(|e| e.get("PersonaName"))
            .and_then(Vdf::as_str);
        assert_eq!(name, Some("lower"));
    }

    #[test]
    #[ignore]
    fn real_loginusers_parses() {
        let Some(path) = login_users_path() else {
            eprintln!("no Steam root detected; skipping");
            return;
        };
        let Ok(text) = std::fs::read_to_string(&path) else {
            eprintln!("no loginusers.vdf at {}; skipping", path.display());
            return;
        };
        let users = parse_login_users(&text);
        eprintln!("{} account(s) parsed from {}", users.len(), path.display());
        for u in &users {
            eprintln!("  {} most_recent={} ts={}", u.steam_id64, u.most_recent, u.timestamp);
        }
        assert!(!users.is_empty(), "real file parsed to zero accounts");
        assert!(users.iter().all(|u| u.steam_id64 > STEAM_ID64_BASE));
    }
}
