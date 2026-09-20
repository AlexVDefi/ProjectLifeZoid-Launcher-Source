pub mod account;
pub mod bootstrap;
pub mod config;
pub mod error;
pub mod install;
pub mod jvmpath;
pub mod launch;
pub mod linuxfix;
pub mod news;
pub mod patch;
pub mod payload;
pub mod perfmode;
pub mod perfmode_commands;
pub mod query;
pub mod selfupdate;
pub mod serverlist;
pub mod session_log;
pub mod state;
pub mod steam;
pub mod workshop;
pub mod workshop_override;

use error::{Error, Result};
use serde::Serialize;
use state::State;
use std::path::Path;
use tauri::{AppHandle, Emitter, Manager, PhysicalSize, WebviewWindow};
use tauri_plugin_dialog::DialogExt;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub install_dir: Option<String>,
    pub install_dir_manual: bool,
    pub jar_sha256: Option<String>,
    pub build: Option<u64>,
    pub payload_installed: bool,
    pub patch_state: String,
    pub patch_detail: Option<String>,
    pub jar_matches: Option<bool>,
    pub repaired_on_start: bool,
    pub steam_launch_options: Option<String>,
    pub blocking_launch_option: Option<String>,
    pub debug_launch_option: Option<String>,
    pub role_name: Option<String>,
    pub role_grants_debug: Option<bool>,
    pub debug_allowed: bool,
    pub launch_debug: bool,
    pub steam_id: Option<String>,
    pub account_username: Option<String>,
    pub account_confirmed: bool,
    pub account_names: Vec<String>,
    pub server_host: Option<String>,
    pub server_connect_port: Option<u16>,
    pub server_query_port: Option<u16>,
    pub server_overridden: bool,
    pub last_stamp: Option<patch::Stamp>,
    pub problems: Vec<String>,
    pub launcher_version: String,
    pub memory_mb: Option<u32>,
    pub memory_is_default: bool,
    pub memory_supported: bool,
}

pub fn effective_server(m: &payload::Manifest, st: &State) -> payload::ServerInfo {
    match &st.server_override {
        Some(o) => payload::ServerInfo {
            host: o.host.clone(),
            connect_port: o.connect_port,
            query_port: o.query_port,
            name: format!("{} [override]", m.server.name),
        },
        None => m.server.clone(),
    }
}

pub fn requirements(m: &payload::Manifest) -> workshop::Requirements<'_> {
    workshop::Requirements {
        items: &m.mods,
        collection_id: &m.collection_id,
        collection_name: &m.collection_name,
        collection_items: &m.collection_mods,
    }
}

/// Load the launcher state with the username fields pointed at whichever Steam account is
/// signed in. Every path that reads or writes the username goes through this, so a second
/// Steam account on the same PC never inherits the first one's confirmed name.
pub fn load_state_for_active_account() -> State {
    let mut st = State::load();
    if let Ok(id) = steam::resolve_steam_id(st.steam_id_override) {
        if st.bind_identity(id) {
            let _ = st.save();
        }
    }
    st
}

/// Set the username for the account signed in now. Shared by the UI command and plzctl so
/// the two cannot disagree about when a name is locked.
pub fn choose_username(raw: &str) -> Result<String> {
    let name = account::validate(raw)?;
    let mut st = load_state_for_active_account();
    // Switching between characters this account already holds is always fine. A name it does
    // not hold is an attempt to make another character, and the launcher has no way to know
    // whether the account has a spare slot, so it lets the attempt through and lets the server
    // answer. Refusing here, which is what this used to do, would make a bought slot unusable.
    //
    // switch_to_name banks the name being replaced, so the character you are leaving stays in
    // the picker rather than being forgotten the moment you type the new one.
    st.switch_to_name(&name);
    st.save()?;
    Ok(name)
}

/// Adopt a name an admin has already renamed this account to on the server.
///
/// Deliberately not `choose_username`: that banks the name it replaces, and after a rename the
/// old one no longer exists server-side, so the player would be left with a dead character in
/// the picker forever. Unconfirmed on purpose - the next join is what proves the rename landed.
pub fn rename_username(raw: &str) -> Result<String> {
    let name = account::validate(raw)?;
    let mut st = load_state_for_active_account();
    st.rename_active_name(&name);
    st.save()?;
    Ok(name)
}

fn emit(app: &AppHandle, step: &str, detail: &str) {
    let _ = app.emit(
        "play-progress",
        serde_json::json!({ "step": step, "detail": detail }),
    );
}

#[tauri::command]
async fn get_status() -> Result<Status> {
    status().await
}

pub async fn status() -> Result<Status> {
    let mut st = load_state_for_active_account();

    let mut problems: Vec<String> = Vec::new();

    // A repair that fails here used to be dropped on the floor, which left the launcher
    // looking healthy while every setting that needs a clean install refused to move -- and
    // the only thing the player saw was one of those settings blaming a game that was not
    // running. Pushed first on purpose: the notice bar shows problems[0] and nothing else.
    // A patch in place while the game is up is the normal state mid-session, not a problem.
    let repaired = match patch::repair(&mut st) {
        Ok(did) => did,
        Err(Error::GameAlreadyRunning) => false,
        Err(e) => {
            problems.push(format!(
                "Your game install is still patched from the last session and the launcher could not put it back: {e} Until that is fixed, the memory setting, the install folder and launcher updates all stay locked."
            ));
            false
        }
    };

    let install_dir = match install::find_install(st.install_dir.as_deref()) {
        Ok(p) => Some(p),
        Err(e) => {
            problems.push(e.to_string());
            None
        }
    };

    let install_dir_manual =
        install_dir.is_some() && install_dir != install::find_install(None).ok();

    let jar = install_dir
        .as_ref()
        .and_then(|p| install::fingerprint_jar(p, st.jar.as_ref()).ok());
    if let (Some(dir), Some(fp)) = (install_dir.as_ref(), jar.as_ref()) {
        st.install_dir = Some(dir.clone());
        st.jar = Some(fp.clone());
        let _ = st.save();
    }

    let memory_mb = install_dir.as_ref().and_then(|p| patch::read_heap_mb(p));
    let memory_is_default = memory_mb.is_some_and(|mb| mb <= patch::DEFAULT_HEAP_MB);
    let memory_supported = install_dir
        .as_ref()
        .is_some_and(|p| install::json_path_opt(p).is_some());

    let mut manifest_error: Option<String> = None;
    let manifest = match payload::fetch_manifest().await {
        Ok(manifest) => Some(manifest),
        Err(e) => {
            problems.push(format!("Could not fetch the patch manifest: {e}"));
            manifest_error = Some(e.to_string());
            None
        }
    };

    let jar_matches = match (&manifest, &jar) {
        (Some(m), Some(f)) => Some(m.built_against_jar_sha256 == f.sha256),
        _ => None,
    };
    if jar_matches == Some(false) {
        problems.push(
            "Project Zomboid has updated since this patch was built. \
             Waiting for a launcher update before it is safe to play."
                .into(),
        );
    }

    let steam_launch_options = install::steam_launch_options();
    let debug_launch_option = steam_launch_options
        .as_deref()
        .and_then(install::debug_launch_option);
    let blocking_launch_option = if st.debug_permitted() {
        None
    } else {
        debug_launch_option.clone()
    };
    if let Some(opt) = &blocking_launch_option {
        problems.push(format!(
            "Your Steam launch options for Project Zomboid contain '{opt}', which puts the game in debug mode. The server disconnects debug clients during the join handshake, so this cannot work. Clear it in Steam > Project Zomboid > Properties > Launch Options."
        ));
    }
    if st.launch_debug && st.role_grants_debug == Some(false) {
        problems.push(format!(
            "The launcher is set to start the game with -debug, but the server has already refused this account for it{}. The join will fail until you turn off 'Start the game in debug mode' under Details.",
            st.role_name
                .as_deref()
                .map(|r| format!(" (role '{r}')"))
                .unwrap_or_default()
        ));
    }
    if let Some(opts) = &steam_launch_options {
        if opts.contains("-nosteam") {
            problems.push(format!(
                "Your Steam launch options for Project Zomboid contain '-nosteam' ({opts}). \
                 That disables the Steam overlay, Workshop sync and Steam auth. \
                 Clear it in Steam > Project Zomboid > Properties > Launch Options."
            ));
        }
    }
    let steam_id = steam::resolve_steam_id(st.steam_id_override).ok();
    if steam_id.is_none() {
        problems.push("Steam is not signed in. Open Steam before pressing Play.".into());
    }
    if st.account_username.is_none() {
        problems.push("Choose the username you want on the server.".into());
    }

    let payload_installed = manifest
        .as_ref()
        .map(payload::is_installed)
        .unwrap_or(false);
    let (patch_state, patch_detail) = match (&manifest_error, payload_installed) {
        (Some(e), _) => ("error", Some(e.clone())),
        (None, true) => ("ready", None),
        (None, false) => ("pending", None),
    };

    Ok(Status {
        install_dir: install_dir.map(|p| p.to_string_lossy().into_owned()),
        install_dir_manual,
        jar_sha256: jar.map(|f| f.sha256),
        build: manifest.as_ref().map(|m| m.build),
        payload_installed,
        patch_state: patch_state.to_string(),
        patch_detail,
        jar_matches,
        repaired_on_start: repaired,
        steam_launch_options,
        blocking_launch_option,
        debug_launch_option,
        role_name: st.role_name.clone(),
        role_grants_debug: st.role_grants_debug,
        debug_allowed: st.allow_debug,
        launch_debug: st.launch_debug,
        steam_id: steam_id.map(|id| id.to_string()),
        account_username: st.account_username.clone(),
        account_confirmed: st.account_confirmed,
        account_names: st.known_names(),
        server_host: manifest.as_ref().map(|m| effective_server(m, &st).host),
        server_connect_port: manifest.as_ref().map(|m| effective_server(m, &st).connect_port),
        server_query_port: manifest.as_ref().map(|m| effective_server(m, &st).query_port),
        server_overridden: st.server_override.is_some(),
        last_stamp: patch::read_stamp(),
        problems,
        launcher_version: env!("CARGO_PKG_VERSION").to_string(),
        memory_mb,
        memory_is_default,
        memory_supported,
    })
}

#[tauri::command]
async fn server_status() -> Result<query::ServerStatus> {
    let m = payload::fetch_manifest().await?;
    let sv = effective_server(&m, &State::load());
    query::query(&sv.host, sv.query_port, 3000).await
}

/// Long enough to cover Steam handing the game over more than once. The shadow class writes the
/// stamp as it loads, so a wait this long only ever ends in a real answer.
const STAMP_WAIT_SECS: u64 = 180;

/// Why the stamp never arrived, which is the difference between a patch that did not load and a
/// game that was not there to load it.
#[derive(Debug, Clone, Copy, PartialEq)]
enum NoStamp {
    /// The game stayed up and never wrote one.
    TimedOut,
    /// The game went away and stayed away.
    GameGone,
}

fn wait_for_stamp(
    progress: &(dyn Fn(&str, &str) + Send + Sync),
    watch: &mut launch::SessionWatch,
) -> std::result::Result<patch::Stamp, NoStamp> {
    progress("running", "Game running. Waiting for the patch stamp");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(STAMP_WAIT_SECS);
    let mut said_absent = false;
    while std::time::Instant::now() < deadline {
        if let Some(s) = patch::read_stamp() {
            session_log::log("stamp", &format!("stamp written by {}", s.origin));
            return Ok(s);
        }
        if watch.is_over() {
            session_log::log("stamp", "gave up: the game is gone for good");
            return Err(NoStamp::GameGone);
        }
        // Steam can take the game away and bring it back while it finishes starting. Saying so
        // is the difference between a launcher that looks wedged and one that looks patient.
        if watch.is_absent() != said_absent {
            said_absent = watch.is_absent();
            progress(
                "running",
                if said_absent {
                    "Waiting for Steam to finish starting the game. Holding the patch in place"
                } else {
                    "Game running. Waiting for the patch stamp"
                },
            );
        }
        std::thread::sleep(std::time::Duration::from_millis(750));
    }
    session_log::log(
        "stamp",
        &format!("gave up: {STAMP_WAIT_SECS}s passed with the game still running and no stamp"),
    );
    Err(NoStamp::TimedOut)
}

fn parse_plzpatch(text: &str) -> Option<u64> {
    const TAG: &str = "PLZPATCH";
    let mut from = 0usize;
    while let Some(at) = text[from..].find(TAG) {
        let after = from + at + TAG.len();
        let rest = &text[after..];
        let mut chars = rest.chars().peekable();
        let mut skipped = 0;
        while let Some(&c) = chars.peek() {
            if c.is_ascii_digit() || skipped >= 2 {
                break;
            }
            if matches!(c, '=' | ':' | '-' | '_' | ' ' | '#') {
                chars.next();
                skipped += 1;
            } else {
                break;
            }
        }
        let digits: String = chars.take_while(char::is_ascii_digit).collect();
        if let Ok(build) = digits.parse() {
            return Some(build);
        }
        from = after;
    }
    None
}

pub fn server_build_from_rules(s: &query::ServerStatus) -> Option<u64> {
    s.rules
        .get("description")
        .and_then(|d| parse_plzpatch(d))
        .or_else(|| parse_plzpatch(&s.name))
}

#[cfg(test)]
mod plzpatch_tests {
    use super::parse_plzpatch;

    #[test]
    fn accepts_every_separator_a_panel_might_leave_behind() {
        for text in [
            "PLZPATCH=5",
            "PLZPATCH:5",
            "PLZPATCH-5",
            "PLZPATCH 5",
            "PLZPATCH5",
        ] {
            assert_eq!(parse_plzpatch(text), Some(5), "failed on {text:?}");
        }
        assert_eq!(
            parse_plzpatch("Welcome to Project After Zoid. PLZPATCH:12"),
            Some(12)
        );
    }

    #[test]
    fn a_tag_with_no_number_is_absent_not_zero() {
        assert_eq!(
            parse_plzpatch("Welcome to Project After Zoid. PLZPATCH"),
            None
        );
        assert_eq!(parse_plzpatch("no tag here"), None);
        assert_eq!(parse_plzpatch(""), None);
    }

    #[test]
    fn does_not_reach_past_the_separator_window_for_a_stray_number() {
        assert_eq!(parse_plzpatch("PLZPATCH    7"), None);
        assert_eq!(parse_plzpatch("PLZPATCH and later 2026"), None);
    }

    #[test]
    fn finds_a_valid_tag_after_a_broken_one() {
        assert_eq!(parse_plzpatch("PLZPATCH ... PLZPATCH:9"), Some(9));
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PlayResult {
    pub launched: bool,
    pub patch_verified: bool,
    pub stamp: Option<patch::Stamp>,
    pub restored: bool,
    pub join_error: Option<String>,
    pub bound_username: Option<String>,
    pub notes: Vec<String>,
}

#[tauri::command]
async fn play(app: AppHandle) -> Result<PlayResult> {
    let handle = app.clone();
    run_play(&move |step, detail| emit(&handle, step, detail)).await
}

pub async fn run_play(progress: &(dyn Fn(&str, &str) + Send + Sync)) -> Result<PlayResult> {
    session_log::start(&format!(
        "launcher {} play run",
        env!("CARGO_PKG_VERSION")
    ));
    let logged = |step: &str, detail: &str| {
        session_log::log(step, detail);
        progress(step, detail);
    };
    let progress: &(dyn Fn(&str, &str) + Send + Sync) = &logged;

    let mut notes: Vec<String> = Vec::new();
    let mut st = load_state_for_active_account();

    progress("repair", "Checking for a previous session");
    if patch::repair(&mut st)? {
        notes.push("Restored ProjectZomboid64.json left patched by a previous session.".into());
    }

    progress("preflight", "Locating Project Zomboid");
    let install_dir = install::find_install(st.install_dir.as_deref())?;
    if launch::is_game_running() {
        return Err(Error::GameAlreadyRunning);
    }
    if let Some(opt) = install::steam_launch_options()
        .as_deref()
        .and_then(install::debug_launch_option)
        .filter(|_| !st.debug_permitted())
    {
        return Err(Error::Other(format!(
            "Remove '{opt}' from your Steam launch options for Project Zomboid first. It puts the game in debug mode, and the server drops debug clients mid-join. If your account is a server admin, use the debug options under Details instead."
        )));
    }
    let steam = install::steam_exe()?;
    let launch_debug = st.launch_debug;
    let debug_args: &[&str] = if launch_debug {
        &[launch::DEBUG_ARG]
    } else {
        &[]
    };
    if launch_debug {
        notes.push(
            "Started with -debug, as set under Details. Only the server's built-in 'admin' role may join a debug client; every other role is disconnected during the join."
                .into(),
        );
    }
    // Steam can change accounts while the launcher sits open, so the name is re-bound here
    // rather than trusted from whenever the window last refreshed.
    if st.bind_identity(steam::resolve_steam_id(st.steam_id_override)?) {
        st.save()?;
    }
    let username = match st.account_username.as_deref() {
        Some(name) => account::validate(name)?,
        None => {
            return Err(Error::Other(
                "Choose the username you want on the server, then press Play.".into(),
            ))
        }
    };

    progress("manifest", "Fetching the signed patch manifest");
    let m = payload::fetch_manifest().await?;

    progress("verify", "Checking the game version");
    let fp = install::fingerprint_jar(&install_dir, st.jar.as_ref())?;
    if fp.sha256 != m.built_against_jar_sha256 {
        return Err(Error::StaleJar {
            expected: m.built_against_jar_sha256[..12].to_string(),
            actual: fp.sha256[..12].to_string(),
        });
    }

    progress("server", "Checking the server");
    let sv = effective_server(&m, &st);
    match query::query(&sv.host, sv.query_port, 3000).await {
        Ok(s) => match server_build_from_rules(&s) {
            Some(server_build) if server_build != m.required_server_build => {
                return Err(Error::BuildMismatch {
                    server: server_build,
                    local: m.required_server_build,
                });
            }
            Some(_) => {}
            None => {
                return Err(Error::Other(
                    "The server has not published its PLZPATCH build. Passwordless Steam accounts require the matching server patch, so launch is blocked until the server update is complete."
                        .into(),
                ));
            }
        },
        Err(e) => notes.push(format!(
            "Could not reach the server ({e}). Launching anyway; you may not be able to connect."
        )),
    }

    progress("mods", "Checking your Workshop mods");
    let mods = workshop::report(requirements(&m));
    if mods.total > 0 {
        progress(
            "mods",
            &format!(
                "Workshop mods: {} of {} ready{}",
                mods.installed,
                mods.total,
                if mods.downloading > 0 {
                    format!(", {} still downloading", mods.downloading)
                } else {
                    String::new()
                }
            ),
        );
    }
    if mods.missing > 0 {
        let note = format!(
            "{} required mod(s) are not subscribed. Use Pre-load mods, or the server will make \
             you download them on the connect screen.",
            mods.missing
        );
        progress("mods", &note);
        notes.push(note);
    }
    if mods.behind_server > 0 {
        let behind: Vec<&workshop::ModStatus> =
            mods.mods.iter().filter(|x| x.behind_server).collect();
        let names = behind
            .iter()
            .map(|x| x.name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        let expected = m
            .mods
            .iter()
            .filter(|e| behind.iter().any(|b| b.id == e.id))
            .filter_map(|e| e.time_updated)
            .max();
        return Err(Error::WorkshopBehind {
            mods: names,
            local: workshop::describe_time(
                behind.iter().filter_map(|x| x.time_updated).min(),
            ),
            expected: workshop::describe_time(expected),
        });
    }

    // Deliberately a warning and not a block, unlike WorkshopBehind above. Being ahead means
    // Steam updated the mod and the server has not been rebuilt against it yet -- nothing the
    // player can do, and blocking Play would lock everyone out on the mod author's schedule.
    let ahead: Vec<&str> = mods
        .mods
        .iter()
        .filter(|x| x.ahead_of_server)
        .map(|x| x.name.as_str())
        .collect();
    if !ahead.is_empty() {
        let note = format!(
            "Steam has a newer copy of {} than this release was built against. The server \
             checks mod versions during the join, so it may refuse you until an admin updates \
             the server. Nothing you can do from here -- report it if the join fails.",
            ahead.join(", ")
        );
        progress("mods", &note);
        notes.push(note);
    }

    // A block, not a note, and for a different reason than WorkshopBehind above. Launching into
    // a half-finished Steam sync is what produces the unreadable bug: ZomboidFileSystem
    // enumerates the mod folders once and memoises the answer, so any folder Steam has not
    // written yet has every asset under it refused for the rest of the session. The player sees
    // an invisible character, invisible vehicles and a blank map, and nothing in the log names
    // Steam. Unlike ahead_of_server this is the player's to fix and it fixes itself -- they only
    // have to wait -- so stopping here costs a minute and saves the session.
    let not_ready: Vec<&str> = mods
        .mods
        .iter()
        .filter(|x| x.not_ready())
        .map(|x| x.name.as_str())
        .collect();
    if !not_ready.is_empty() {
        return Err(Error::WorkshopNotReady {
            mods: not_ready.join(", "),
        });
    }

    progress("download", "Syncing the patch payload");
    let fetched = payload::sync(&m, &|msg: &str| progress("download", msg)).await?;
    if fetched > 0 {
        notes.push(format!("Downloaded {fetched} patch file(s)."));
    }
    st.installed_build = Some(m.build);
    st.install_dir = Some(install_dir.clone());
    st.jar = Some(fp);
    st.save()?;

    progress("patch", "Patching the launch configuration");
    patch::apply(&mut st, &install_dir, m.build)?;

    let outcome = (|| -> Result<std::result::Result<patch::Stamp, NoStamp>> {
        progress("account", "Preparing your Steam-bound game account");
        bootstrap::install()?;
        bootstrap::clear_join_result();
        bootstrap::clear_role();
        let _ = serverlist::seed(&sv.name, &sv.host, sv.connect_port, &username)?;
        bootstrap::write_join_intent(&sv.host, sv.connect_port, &username, &sv.name)?;

        progress(
            "launch",
            if launch_debug {
                "Starting Project Zomboid through Steam with -debug"
            } else {
                "Starting Project Zomboid through Steam"
            },
        );
        launch::launch(&steam, debug_args)?;
        let mut watch = launch::SessionWatch::started_now();
        launch::wait_for_start(120)?;
        session_log::log("launch", "a game process exists");

        let stamp = wait_for_stamp(progress, &mut watch);

        progress(
            "playing",
            match stamp {
                Ok(_) => "Patch active. Enjoy. Restoring on exit",
                Err(_) => "Playing. The patch could not be verified. Restoring on exit",
            },
        );
        // The last code reported, not merely "have we reported". A session writes "OK" the
        // moment the join lands, and an idle kick overwrites it two hours later - latching on
        // the first result would swallow every mid-session result there will ever be.
        let mut last_code: Option<String> = None;
        launch::wait_for_exit_with(&mut watch, || {
            let Some(result) = bootstrap::read_join_result() else {
                return;
            };
            if last_code.as_deref() == Some(result.code.as_str()) {
                return;
            }
            last_code = Some(result.code.clone());
            if let Some(explained) = bootstrap::explain(&result) {
                progress(
                    "join-failed",
                    &format!("{explained} You can close the game now."),
                );
            }
        });
        Ok(stamp)
    })();

    let join = bootstrap::read_join_result();

    if let Some(role) = bootstrap::read_role() {
        if st.role_grants_debug != Some(role.grants_debug)
            || st.role_name.as_deref() != Some(role.name.as_str())
        {
            st.role_grants_debug = Some(role.grants_debug);
            st.role_name = Some(role.name.clone());
            st.save()?;
        }
    }

    bootstrap::clear_join_intent();
    progress("restore", "Restoring your game install");
    let restored = patch::repair(&mut st)?;

    let mut bound_username = None;
    let join_error = match &join {
        Some(r) if r.code == "OK" || r.code == "AFKKick" => {
            let mut dirty = false;
            if !st.account_confirmed {
                st.account_confirmed = true;
                dirty = true;
            }
            if let Some(name) = st.account_username.clone() {
                dirty |= st.remember_name(&name);
            }
            if dirty {
                st.save()?;
            }
            bootstrap::explain(r)
        }
        Some(r) => {
            if r.code == "DebugNotAllowed" && st.role_grants_debug != Some(false) {
                st.role_grants_debug = Some(false);
                st.save()?;
            }
            // Every one of these is the server saying this name is not this account's, and
            // each explanation tells the player to pick a different one. A stale local
            // confirmation would leave the name locked and the advice impossible to follow.
            if matches!(
                r.code.as_str(),
                "PLZNameTaken" | "InvalidUsername" | "DuplicateAccount" | "InvalidUsernamePassword"
            ) {
                let mut dirty = false;
                if st.account_confirmed {
                    st.account_confirmed = false;
                    dirty = true;
                }
                if let Some(name) = st.account_username.clone() {
                    dirty |= st.forget_name(&name);
                }
                if dirty {
                    st.save()?;
                }
            }
            if r.code == "PLZWrongCharacter" && !r.detail.is_empty() {
                // The server sends every name this account holds, comma separated. An account
                // with a character slot has more than one, and the player needs to see all of
                // them to work out which they meant to type.
                let names: Vec<String> = r
                    .detail
                    .split(',')
                    .map(|name| name.trim().to_string())
                    .filter(|name| !name.is_empty())
                    .collect();
                if let Some(first) = names.first() {
                    bound_username = Some(first.clone());
                    st.account_username = Some(first.clone());
                    st.account_names = names;
                    st.account_confirmed = true;
                    st.save()?;
                }
            }
            bootstrap::explain(r)
        }
        None => None,
    };

    let outcome = outcome?;
    let stamp = outcome.as_ref().ok().cloned();
    match &outcome {
        Ok(_) => {}
        Err(NoStamp::GameGone) => notes.push(
            "Project Zomboid closed before it loaded the ProjectLifeZoid patch, so nothing was \
             verified this session."
                .into(),
        ),
        Err(NoStamp::TimedOut) => notes.push(format!(
            "The game ran for {} minutes without loading the shadow classes, so this session was \
             unpatched. Report this and send an admin the log at {}.",
            STAMP_WAIT_SECS / 60,
            config::app_dir().join("runtime").join("session.log").display()
        )),
    }

    Ok(PlayResult {
        launched: true,
        patch_verified: stamp.is_some(),
        stamp,
        restored,
        join_error,
        bound_username,
        notes,
    })
}

#[tauri::command]
async fn set_account_username(name: String) -> Result<String> {
    choose_username(&name)
}

#[tauri::command]
async fn rename_account_username(name: String) -> Result<String> {
    rename_username(&name)
}

#[tauri::command]
async fn set_allow_debug(allowed: bool) -> Result<bool> {
    let mut st = State::load();
    st.allow_debug = allowed;
    st.save()?;
    Ok(allowed)
}

#[tauri::command]
async fn set_launch_debug(enabled: bool) -> Result<bool> {
    let mut st = State::load();
    st.launch_debug = enabled;
    st.save()?;
    Ok(enabled)
}

/// Whether the player wants the Tomb body overhaul.
///
/// Deliberately NOT kept in the launcher State: the file IS the setting. The java patch re-reads
/// it on every connect, so a second copy here could only ever drift from it. Missing or
/// unparseable means ON, matching the java default - the mods are in the server list, and
/// dropping them because a settings file could not be read would be the stranger outcome.
fn read_body_override() -> bool {
    let text = match std::fs::read_to_string(config::body_override_path()) {
        Ok(text) => text,
        Err(_) => return true,
    };

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.to_ascii_lowercase().starts_with("enabled=") {
            // Byte slice is safe: the prefix just matched and is pure ASCII.
            let value = trimmed["enabled=".len()..].trim();
            return !value.eq_ignore_ascii_case("false") && value != "0";
        }
    }

    true
}

#[tauri::command]
async fn get_body_override() -> Result<bool> {
    Ok(read_body_override())
}

#[tauri::command]
async fn set_body_override(enabled: bool) -> Result<bool> {
    let path = config::body_override_path();
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    std::fs::write(&path, format!("enabled={}\r\n", enabled))?;
    Ok(enabled)
}

#[tauri::command]
async fn set_server_override(
    host: String,
    connect_port: u16,
    query_port: u16,
) -> Result<bool> {
    let mut st = State::load();
    let host = host.trim().to_string();
    if host.is_empty() {
        st.server_override = None;
        st.save()?;
        return Ok(false);
    }
    if host.contains("://") || host.contains('/') || host.contains(' ') {
        return Err(Error::Other(
            "Server address must be a bare host or IP, with no http:// and no path.".into(),
        ));
    }
    if connect_port == 0 || query_port == 0 {
        return Err(Error::Other("Both ports must be from 1 to 65535.".into()));
    }
    st.server_override = Some(state::ServerOverride {
        host,
        connect_port,
        query_port,
    });
    st.save()?;
    Ok(true)
}

#[tauri::command]
async fn get_server_override() -> Result<Option<state::ServerOverride>> {
    Ok(State::load().server_override)
}

#[tauri::command]
async fn restore_now() -> Result<bool> {
    let mut st = State::load();
    patch::repair(&mut st)
}

#[tauri::command]
async fn set_memory_mb(mb: u32) -> Result<()> {
    if !(1024..=65536).contains(&mb) {
        return Err(Error::Other(
            "Choose a memory amount between 1 GB and 64 GB.".into(),
        ));
    }
    let mut st = State::load();
    clear_session_patch(
        &mut st,
        "changing the memory setting needs a clean copy of ProjectZomboid64.json",
    )?;
    let install_dir = install::find_install(st.install_dir.as_deref())?;
    patch::set_heap_mb(&install_dir, mb)
}

/// Put the install back before a setting edits the file a session's patch is sitting on.
///
/// This used to be a bare "is a patch recorded?" refusal telling the player to quit the game.
/// The record outlives the session whenever the restore could not run -- the game left a
/// process behind, the install moved, a drive was not mounted -- and status() swallowed that
/// failure, so all the player ever saw was a launcher telling them to quit a game that was
/// not running. Retry the repair here and report what actually went wrong.
fn clear_session_patch(st: &mut State, why: &str) -> Result<()> {
    if st.active_patch.is_none() {
        return Ok(());
    }
    patch::repair(st).map(|_| ()).map_err(|e| match e {
        Error::GameAlreadyRunning => Error::Other(format!(
            "Quit Project Zomboid first -- it is still running. This session's patch is still \
             in place, and {why}."
        )),
        other => Error::Other(format!(
            "The launcher could not put your game install back after the last session, so \
             {why}. {other}"
        )),
    })
}

fn guard_install_change(st: &mut State) -> Result<()> {
    clear_session_patch(
        st,
        "the launcher has to put your game files back before the folder can change",
    )
}

#[tauri::command]
async fn set_install_dir(path: String) -> Result<String> {
    let trimmed = path.trim();
    if trimmed.is_empty() {
        return Err(Error::Other("Choose a folder first.".into()));
    }
    let cleaned = trimmed.trim_matches('"');
    let resolved = install::resolve_chosen(Path::new(cleaned))?;

    let mut st = State::load();
    guard_install_change(&mut st)?;
    st.install_dir = Some(resolved.clone());
    st.jar = None;
    st.save()?;
    Ok(resolved.to_string_lossy().into_owned())
}

#[tauri::command]
async fn clear_install_dir() -> Result<Option<String>> {
    let mut st = State::load();
    guard_install_change(&mut st)?;
    st.install_dir = None;
    st.jar = None;
    st.save()?;
    Ok(install::find_install(None)
        .ok()
        .map(|p| p.to_string_lossy().into_owned()))
}

#[tauri::command]
async fn pick_install_dir(app: AppHandle) -> Result<Option<String>> {
    let start = State::load()
        .install_dir
        .or_else(|| install::steam_root().map(|r| r.join("steamapps").join("common")))
        .filter(|p| p.is_dir());

    let mut dialog = app
        .dialog()
        .file()
        .set_title("Where is Project Zomboid installed?");
    if let Some(dir) = start {
        dialog = dialog.set_directory(dir);
    }
    Ok(dialog
        .blocking_pick_folder()
        .and_then(|f| f.into_path().ok())
        .map(|p| p.to_string_lossy().into_owned()))
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
#[tauri::command]
async fn mods_report() -> Result<workshop::ModReport> {
    let m = payload::fetch_manifest().await?;
    Ok(workshop::report(requirements(&m)))
}

#[tauri::command]
async fn mods_open_collection() -> Result<()> {
    let m = payload::fetch_manifest().await?;
    if m.collection_id.is_empty() {
        return Err(Error::Other(
            "No Workshop collection is set for this server yet.".into(),
        ));
    }
    workshop::open_in_steam(&format!(
        "https://steamcommunity.com/sharedfiles/filedetails/?id={}",
        m.collection_id
    ))
}

#[tauri::command]
async fn mods_open_item(id: String) -> Result<()> {
    workshop::open_in_steam(&workshop::item_url(&id))
}

#[tauri::command]
async fn news_items() -> Result<Vec<news::NewsItem>> {
    let m = payload::fetch_manifest().await?;
    news::fetch(&m.news_url, 6).await
}

#[tauri::command]
async fn links() -> Result<payload::Links> {
    Ok(payload::fetch_manifest().await?.links)
}

#[tauri::command]
async fn open_link(url: String) -> Result<()> {
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err(Error::Other("refusing to open a non-web link".into()));
    }
    tauri_plugin_opener::open_url(&url, None::<&str>)
        .map_err(|e| Error::Other(format!("could not open link: {e}")))?;
    Ok(())
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SteamAccountView {
    pub steam_id: String,
    pub persona_name: String,
    pub account_name: String,
    pub most_recent: bool,
    pub selected: bool,
}

#[tauri::command]
async fn steam_accounts() -> Result<Vec<SteamAccountView>> {
    let chosen = steam::resolve_steam_id(State::load().steam_id_override).ok();
    Ok(steam::login_users()
        .into_iter()
        .map(|a| SteamAccountView {
            selected: Some(a.steam_id64) == chosen,
            steam_id: a.steam_id64.to_string(),
            persona_name: a.persona_name,
            account_name: a.account_name,
            most_recent: a.most_recent,
        })
        .collect())
}

#[tauri::command]
async fn set_steam_account(steam_id: Option<String>) -> Result<()> {
    let parsed = match steam_id.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        Some(text) => Some(text.parse::<u64>().map_err(|_| {
            Error::Other("That is not a SteamID. It is a 17-digit number.".into())
        })?),
        None => None,
    };
    let mut st = State::load();
    st.steam_id_override = parsed;
    if let Ok(id) = steam::resolve_steam_id(st.steam_id_override) {
        st.bind_identity(id);
    }
    st.save()?;
    Ok(())
}

#[tauri::command]
async fn check_for_update(app: AppHandle) -> Result<selfupdate::UpdateInfo> {
    selfupdate::check(&app).await
}

#[tauri::command]
async fn install_update(app: AppHandle) -> Result<()> {
    selfupdate::install(&app).await
}

const MIN_WINDOW_WIDTH: f64 = 880.0;

fn apply_height_bounds(window: &WebviewWindow, content_height: Option<f64>) {
    let Ok(Some(monitor)) = window.current_monitor() else {
        return;
    };
    let scale = window.scale_factor().unwrap_or_else(|_| monitor.scale_factor());
    let work = monitor.work_area().size;

    let chrome = match (window.outer_size(), window.inner_size()) {
        (Ok(outer), Ok(inner)) => outer.height.saturating_sub(inner.height),
        _ => 0,
    };
    let max_inner = work.height.saturating_sub(chrome).max(1);

    let min_inner = content_height
        .filter(|h| h.is_finite() && *h > 0.0)
        .map(|h| ((h * scale).ceil() as u32).min(max_inner));

    let _ = window.set_max_size(Some(PhysicalSize::new(work.width, max_inner)));
    if let Some(min) = min_inner {
        let min_width = (MIN_WINDOW_WIDTH * scale).round() as u32;
        let _ = window.set_min_size(Some(PhysicalSize::new(min_width.min(work.width), min)));
    }

    if let Ok(inner) = window.inner_size() {
        let target = inner.height.clamp(min_inner.unwrap_or(0), max_inner);
        if target != inner.height {
            let _ = window.set_size(PhysicalSize::new(inner.width, target));
        }
    }
}

#[tauri::command]
fn fit_window(window: WebviewWindow, content_height: f64) {
    apply_height_bounds(&window, Some(content_height));
}

#[cfg(target_os = "linux")]
fn soften_webkit_rendering() {
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }
}

#[cfg(not(target_os = "linux"))]
fn soften_webkit_rendering() {}

pub fn run() {
    soften_webkit_rendering();
    linuxfix::apply();
    tauri::Builder::default()
        .setup(|app| {
            if let Some(window) = app.get_webview_window("main") {
                apply_height_bounds(&window, None);
            }
            Ok(())
        })
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .invoke_handler(tauri::generate_handler![
            get_status,
            server_status,
            play,
            set_account_username,
            rename_account_username,
            set_allow_debug,
            set_launch_debug,
            restore_now,
            set_memory_mb,
            set_install_dir,
            clear_install_dir,
            pick_install_dir,
            mods_report,
            mods_open_collection,
            mods_open_item,
            news_items,
            links,
            open_link,
            check_for_update,
            install_update,
            steam_accounts,
            set_steam_account,
            set_server_override,
            get_server_override,
            set_body_override,
            get_body_override,
            perfmode_commands::get_performance_mode,
            perfmode_commands::set_performance_mode,
            fit_window
        ])
        .run(tauri::generate_context!())
        .expect("error while running the launcher");
}
