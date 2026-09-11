use crate::config;
use crate::error::Result;
use crate::install::JarFingerprint;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
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
    /// Which Steam account `account_username`/`account_confirmed` describe. A shared PC
    /// has one launcher state but many Steam accounts, and the server binds a name to a
    /// Steam ID, so a confirmation that is not stamped with an owner locks out every
    /// other account on the machine.
    pub identity_steam_id: Option<u64>,
    /// The name and confirmation parked for every other Steam account seen on this
    /// machine, keyed by SteamID64 as text so the JSON stays readable.
    pub identities: BTreeMap<String, SavedIdentity>,
    pub steam_id_override: Option<u64>,
    pub role_grants_debug: Option<bool>,
    pub role_name: Option<String>,
    pub allow_debug: bool,
    pub launch_debug: bool,
    pub server_override: Option<ServerOverride>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SavedIdentity {
    pub username: Option<String>,
    pub confirmed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerOverride {
    pub host: String,
    pub connect_port: u16,
    pub query_port: u16,
}

impl State {
    // launch_debug counts as permission for the Steam-launch-option block below it: the
    // launcher is adding -debug itself, so refusing to start over a second copy of the same
    // flag would be the launcher blocking its own setting.
    pub fn debug_permitted(&self) -> bool {
        self.allow_debug || self.launch_debug || self.role_grants_debug == Some(true)
    }

    /// Point `account_username`/`account_confirmed` at `steam_id`, parking whatever
    /// belonged to the account they described before. Returns true when something moved,
    /// so the caller knows to save.
    ///
    /// Signing in as an account that has never joined leaves the name unset and
    /// unconfirmed, which is the whole point: it is editable again.
    pub fn bind_identity(&mut self, steam_id: u64) -> bool {
        if self.identity_steam_id == Some(steam_id) {
            return false;
        }
        let key = steam_id.to_string();
        match self.identity_steam_id {
            Some(previous) => {
                self.identities.insert(
                    previous.to_string(),
                    SavedIdentity {
                        username: self.account_username.clone(),
                        confirmed: self.account_confirmed,
                    },
                );
                let restored = self.identities.remove(&key).unwrap_or_default();
                self.account_username = restored.username;
                self.account_confirmed = restored.confirmed;
            }
            // Written before the launcher stamped an owner on the name. It is this
            // account's: the installs this upgrades from only ever had one, and unlocking
            // the field would invite the player who owns the name to change it and be
            // refused by the server. If it was in fact somebody else's, the server says so
            // on the next join and run_play clears the lock then.
            None => {
                self.identities.remove(&key);
            }
        }
        self.identity_steam_id = Some(steam_id);
        true
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

    #[test]
    fn asking_the_launcher_for_debug_does_not_then_block_the_launch() {
        let st = State {
            launch_debug: true,
            ..State::default()
        };
        assert!(st.debug_permitted());
    }
}

#[cfg(test)]
mod identity_binding_tests {
    use super::{SavedIdentity, State};

    const A: u64 = 76_561_198_000_000_001;
    const B: u64 = 76_561_198_000_000_002;

    fn confirmed_as(name: &str, owner: Option<u64>) -> State {
        State {
            account_username: Some(name.into()),
            account_confirmed: true,
            identity_steam_id: owner,
            ..State::default()
        }
    }

    #[test]
    fn a_second_steam_account_starts_with_no_name_and_no_lock() {
        let mut st = confirmed_as("Dave", Some(A));
        assert!(st.bind_identity(B));
        assert_eq!(st.account_username, None);
        assert!(!st.account_confirmed);
    }

    #[test]
    fn the_first_accounts_name_survives_the_switch_and_comes_back() {
        let mut st = confirmed_as("Dave", Some(A));
        st.bind_identity(B);
        st.account_username = Some("Erin".into());
        st.bind_identity(A);
        assert_eq!(st.account_username.as_deref(), Some("Dave"));
        assert!(st.account_confirmed);
        st.bind_identity(B);
        assert_eq!(st.account_username.as_deref(), Some("Erin"));
        assert!(!st.account_confirmed, "Erin never joined");
    }

    #[test]
    fn rebinding_the_same_account_changes_nothing() {
        let mut st = confirmed_as("Dave", Some(A));
        assert!(!st.bind_identity(A));
        assert_eq!(st.account_username.as_deref(), Some("Dave"));
        assert!(st.account_confirmed);
    }

    #[test]
    fn an_account_that_has_already_joined_stays_locked_across_the_upgrade() {
        let mut st = confirmed_as("Dave", None);
        assert!(st.bind_identity(A));
        assert_eq!(st.account_username.as_deref(), Some("Dave"));
        assert!(
            st.account_confirmed,
            "the one account this install ever had is the one that earned the lock"
        );
        assert_eq!(st.identity_steam_id, Some(A));
        assert!(st.identities.is_empty());
    }

    #[test]
    fn a_fresh_install_binds_without_inventing_a_name() {
        let mut st = State::default();
        assert!(st.bind_identity(A));
        assert_eq!(st.account_username, None);
        assert!(!st.account_confirmed);
    }

    #[test]
    fn parked_identities_round_trip_through_the_state_file() {
        let mut st = confirmed_as("Dave", Some(A));
        st.bind_identity(B);
        let text = serde_json::to_string(&st).unwrap();
        let back: State = serde_json::from_str(&text).unwrap();
        assert_eq!(
            back.identities.get(&A.to_string()),
            Some(&SavedIdentity {
                username: Some("Dave".into()),
                confirmed: true,
            })
        );
    }

    #[test]
    fn an_old_state_file_without_the_new_fields_still_loads() {
        let back: State =
            serde_json::from_str(r#"{"account_username":"Dave","account_confirmed":true}"#).unwrap();
        assert_eq!(back.account_username.as_deref(), Some("Dave"));
        assert!(back.account_confirmed);
        assert_eq!(back.identity_steam_id, None);
        assert!(back.identities.is_empty());
    }
}
