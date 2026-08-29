use crate::config;
use crate::error::Result;
use crate::install::JarFingerprint;
use serde::{Deserialize, Serialize};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActivePatch {
    #[serde(default)]
    pub json_path: Option<PathBuf>,
    #[serde(default)]
    pub backup_path: Option<PathBuf>,
    #[serde(default)]
    pub original_sha256: Option<String>,
    #[serde(default)]
    pub installed_files: Vec<String>,
    pub build: u64,
    pub patched_at_millis: u128,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct State {
    pub install_dir: Option<PathBuf>,
    pub installed_build: Option<u64>,
    pub jar: Option<JarFingerprint>,
    pub active_patch: Option<ActivePatch>,
    pub account_username: Option<String>,
    pub account_confirmed: bool,
    pub steam_id_override: Option<u64>,
    pub role_grants_debug: Option<bool>,
    pub role_name: Option<String>,
    pub allow_debug: bool,
    pub server_override: Option<ServerOverride>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerOverride {
    pub host: String,
    pub connect_port: u16,
    pub query_port: u16,
}

impl State {
    pub fn debug_permitted(&self) -> bool {
        self.allow_debug || self.role_grants_debug == Some(true)
    }

    pub fn load() -> State {
        fs::read(config::state_path())
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) -> Result<()> {
        let path = config::state_path();
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        let bytes = serde_json::to_vec_pretty(self)?;
        let mut f = fs::File::create(&path)?;
        f.write_all(&bytes)?;
        f.sync_all()?;
        Ok(())
    }
}

pub fn write_no_bom(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    debug_assert!(!text.starts_with('\u{feff}'), "BOM would brick the launch");
    let mut f = fs::File::create(path)?;
    f.write_all(text.as_bytes())?;
    f.sync_all()?;
    Ok(())
}

pub fn now_millis() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

#[cfg(test)]
mod debug_permission_tests {
    use super::State;

    fn state(allow: bool, role: Option<bool>) -> State {
        State {
            allow_debug: allow,
            role_grants_debug: role,
            ..State::default()
        }
    }

    #[test]
    fn the_server_answer_alone_is_enough() {
        assert!(state(false, Some(true)).debug_permitted());
    }

    #[test]
    fn the_manual_opt_in_alone_is_enough() {
        assert!(state(true, None).debug_permitted());
    }

    #[test]
    fn never_told_is_not_a_denial_but_is_not_permission_either() {
        assert!(!state(false, None).debug_permitted());
    }

    #[test]
    fn an_explicit_server_denial_does_not_override_a_deliberate_opt_in() {
        assert!(state(true, Some(false)).debug_permitted());
        assert!(!state(false, Some(false)).debug_permitted());
    }
}
