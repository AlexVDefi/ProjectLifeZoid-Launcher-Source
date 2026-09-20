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
    /// Every username the server has confirmed for the Steam account named by
    /// `identity_steam_id`, the active one included. One entry is the ordinary case; a
    /// second only appears for an account that bought a character slot. Empty in state
    /// files written before slots existed, which `known_names` reads as just the active
    /// name so no migration pass is needed.
    pub account_names: Vec<String>,
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
    /// What Performance mode wrote into options.ini, kept so the toggle can undo itself.
    ///
    /// The snapshot has to live here rather than being re-derived: once the preset is on, the
    /// file no longer remembers what the player had before it.
    pub performance_mode: Option<crate::perfmode::Applied>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct SavedIdentity {
    pub username: Option<String>,
    pub confirmed: bool,
    pub names: Vec<String>,
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
                let parked_names = self.known_names();
                self.identities.insert(
                    previous.to_string(),
                    SavedIdentity {
                        username: self.account_username.clone(),
                        confirmed: self.account_confirmed,
                        names: parked_names,
                    },
                );
                let restored = self.identities.remove(&key).unwrap_or_default();
                self.account_username = restored.username;
                self.account_confirmed = restored.confirmed;
                self.account_names = restored.names;
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

    /// The characters this account may switch between.
    ///
    /// Falls back to the single active name so a state file written before slots existed reads
    /// correctly. An unconfirmed name is not included: the server has not agreed it exists yet.
    pub fn known_names(&self) -> Vec<String> {
        if !self.account_names.is_empty() {
            return self.account_names.clone();
        }
        match (&self.account_username, self.account_confirmed) {
            (Some(name), true) => vec![name.clone()],
            _ => Vec::new(),
        }
    }

    pub fn knows_name(&self, name: &str) -> bool {
        self.known_names()
            .iter()
            .any(|known| known.eq_ignore_ascii_case(name))
    }

    /// Record a name the server has accepted. Returns true when something changed, so the
    /// caller knows to save.
    ///
    /// The server's spelling replaces ours when only case differs. Usernames match
    /// case-insensitively there, so keeping both spellings would offer the player two entries
    /// for one character.
    pub fn remember_name(&mut self, name: &str) -> bool {
        let mut names = self.known_names();
        match names
            .iter_mut()
            .find(|known| known.eq_ignore_ascii_case(name))
        {
            Some(slot) => *slot = name.to_string(),
            None => names.push(name.to_string()),
        }
        if self.account_names == names {
            return false;
        }
        self.account_names = names;
        true
    }

    /// Move the active name to `name`, banking whatever confirmed name it replaces, and report
    /// whether the new one is already known.
    ///
    /// The banking MUST happen before `account_username` moves. In a state file written before
    /// the name list existed, the first character is only ever `account_username`, and
    /// `known_names` falls back to it. Once the active name has changed, that fallback returns
    /// the NEW name and the first one is gone for good - which dropped the original character
    /// off the picker for everyone upgrading from a launcher that predates the list, leaving
    /// them typing both names from memory forever.
    pub fn switch_to_name(&mut self, name: &str) -> bool {
        if self.account_confirmed {
            if let Some(current) = self.account_username.clone() {
                self.remember_name(&current);
            }
        }
        let known = self.knows_name(name);
        self.account_username = Some(name.to_string());
        self.account_confirmed = known;
        known
    }

    /// Point the launcher at a name an admin has already changed on the server, dropping the
    /// one it replaces.
    ///
    /// The old name is NOT banked, which is the whole difference from `switch_to_name`: the
    /// server no longer holds it, so leaving it in the picker offers a character nobody can
    /// join as. A name this account already holds is an ordinary switch and keeps both.
    pub fn rename_active_name(&mut self, name: &str) -> bool {
        if self.knows_name(name) {
            return self.switch_to_name(name);
        }
        if let Some(current) = self.account_username.clone() {
            self.forget_name(&current);
        }
        self.account_username = Some(name.to_string());
        self.account_confirmed = false;
        false
    }

    /// Drop a name the server has refused as not this account's. Returns true when something
    /// changed. Without this a rejected name would sit in the picker forever.
    pub fn forget_name(&mut self, name: &str) -> bool {
        let before = self.known_names();
        let after: Vec<String> = before
            .iter()
            .filter(|known| !known.eq_ignore_ascii_case(name))
            .cloned()
            .collect();
        if self.account_names == after {
            return false;
        }
        self.account_names = after;
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
                names: vec!["Dave".into()],
            })
        );
    }

    #[test]
    fn a_confirmed_name_is_known_even_without_the_list() {
        // State files written before slots existed have no account_names at all.
        let st = confirmed_as("Dave", Some(A));
        assert_eq!(st.known_names(), vec!["Dave".to_string()]);
        assert!(st.knows_name("dave"), "names match case-insensitively");
        assert!(!st.knows_name("Erin"));
    }

    #[test]
    fn an_unconfirmed_name_is_not_known_yet() {
        let mut st = State::default();
        st.account_username = Some("Erin".into());
        st.account_confirmed = false;
        assert!(st.known_names().is_empty(), "the server has not agreed it exists");
    }

    #[test]
    fn remembering_builds_the_picker_list() {
        let mut st = confirmed_as("Dave", Some(A));
        assert!(st.remember_name("Erin"));
        assert_eq!(st.known_names(), vec!["Dave".to_string(), "Erin".to_string()]);
        assert!(!st.remember_name("Erin"), "already there, nothing changed");
        // The server's spelling wins, rather than showing one character twice.
        assert!(st.remember_name("ERIN"));
        assert_eq!(st.known_names(), vec!["Dave".to_string(), "ERIN".to_string()]);
    }

    #[test]
    fn forgetting_drops_a_refused_name() {
        let mut st = confirmed_as("Dave", Some(A));
        st.remember_name("Erin");
        assert!(st.forget_name("erin"));
        assert_eq!(st.known_names(), vec!["Dave".to_string()]);
        assert!(!st.forget_name("Erin"), "already gone");
    }

    #[test]
    fn each_steam_account_parks_its_own_characters() {
        let mut st = confirmed_as("Dave", Some(A));
        st.remember_name("Erin");
        st.bind_identity(B);
        assert!(st.known_names().is_empty(), "a fresh Steam account has no characters");
        st.bind_identity(A);
        assert_eq!(st.known_names(), vec!["Dave".to_string(), "Erin".to_string()]);
    }

    #[test]
    fn adding_a_second_character_keeps_the_first_in_the_picker() {
        // The exact upgrade path: a state file written before the name list existed holds one
        // confirmed name and no account_names at all.
        let mut st = confirmed_as("RedChili5", Some(A));
        assert!(st.account_names.is_empty(), "the upgrade case starts with no list");

        // Type the second character's name in the launcher...
        let known = st.switch_to_name("Spiffo Fairy");
        assert!(!known, "a brand new name is not one this account holds yet");
        assert!(!st.account_confirmed, "the server has not agreed to it yet");

        // ...then join successfully with it, which is what records it.
        st.account_confirmed = true;
        st.remember_name("Spiffo Fairy");

        assert_eq!(
            st.known_names(),
            vec!["RedChili5".to_string(), "Spiffo Fairy".to_string()],
            "both characters must stay offered, or the player types names from memory"
        );
    }

    #[test]
    fn switching_back_to_a_known_character_needs_no_reconfirmation() {
        let mut st = confirmed_as("RedChili5", Some(A));
        st.switch_to_name("Spiffo Fairy");
        st.account_confirmed = true;
        st.remember_name("Spiffo Fairy");

        let known = st.switch_to_name("RedChili5");
        assert!(known, "a character this account already holds is known");
        assert!(st.account_confirmed, "so it does not need confirming again");
        assert_eq!(st.account_username.as_deref(), Some("RedChili5"));
        assert_eq!(st.known_names().len(), 2, "and nothing was lost in the swap");
    }

    #[test]
    fn an_admin_rename_replaces_the_old_name_rather_than_banking_it() {
        let mut st = confirmed_as("RedChili5", Some(A));

        let known = st.rename_active_name("RedChili");
        assert!(!known, "the server has not seen the new spelling from this launcher yet");
        assert_eq!(st.account_username.as_deref(), Some("RedChili"));
        assert!(!st.account_confirmed, "the next join is what proves the rename landed");
        assert!(
            st.known_names().is_empty(),
            "the old name is gone server-side, so offering it would offer a dead character"
        );
    }

    #[test]
    fn an_admin_rename_leaves_the_accounts_other_characters_alone() {
        let mut st = confirmed_as("RedChili5", Some(A));
        st.remember_name("Spiffo Fairy");

        st.rename_active_name("RedChili");
        assert_eq!(st.known_names(), vec!["Spiffo Fairy".to_string()]);

        st.account_confirmed = true;
        st.remember_name("RedChili");
        assert_eq!(
            st.known_names(),
            vec!["Spiffo Fairy".to_string(), "RedChili".to_string()]
        );
    }

    #[test]
    fn renaming_to_a_character_this_account_already_holds_is_just_a_switch() {
        let mut st = confirmed_as("RedChili5", Some(A));
        st.remember_name("Spiffo Fairy");

        let known = st.rename_active_name("Spiffo Fairy");
        assert!(known);
        assert!(st.account_confirmed, "no reconfirmation needed");
        assert_eq!(
            st.known_names(),
            vec!["RedChili5".to_string(), "Spiffo Fairy".to_string()],
            "a misfired rename must not eat a character"
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
