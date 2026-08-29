pub mod account;
pub mod bootstrap;
pub mod config;
pub mod error;
pub mod install;
pub mod launch;
pub mod news;
pub mod patch;
pub mod payload;
pub mod query;
pub mod selfupdate;
pub mod serverlist;
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
    pub steam_id: Option<String>,
    pub account_username: Option<String>,
    pub account_confirmed: bool,
    pub server_host: Option<String>,
    pub server_connect_port: Option<u16>,
    pub server_query_port: Option<u16>,
    pub server_overridden: bool,
    pub last_stamp: Option<patch::Stamp>,
    pub problems: Vec<String>,
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
    let mut st = State::load();
    let repaired = patch::repair(&mut st).unwrap_or(false);

    let mut problems: Vec<String> = Vec::new();

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
    if let Some(opts) = &steam_launch_options {
        if opts.contains("-nosteam") {
            problems.push(format!(
                "Your Steam launch options for Project Zomboid contain '-nosteam' ({opts}). \
                 That disables the Steam overlay, Workshop sync and Steam auth. \
                 Clear it in Steam > Project Zomboid > Properties > Launch Options."
            ));
        }
    }
    let steam_id = install::active_steam_id().ok();
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
        steam_id: steam_id.map(|id| id.to_string()),
        account_username: st.account_username.clone(),
        account_confirmed: st.account_confirmed,
        server_host: manifest.as_ref().map(|m| effective_server(m, &st).host),
        server_connect_port: manifest.as_ref().map(|m| effective_server(m, &st).connect_port),
        server_query_port: manifest.as_ref().map(|m| effective_server(m, &st).query_port),
        server_overridden: st.server_override.is_some(),
        last_stamp: patch::read_stamp(),
        problems,
    })
}

#[tauri::command]
async fn server_status() -> Result<query::ServerStatus> {
    let m = payload::fetch_manifest().await?;
    let sv = effective_server(&m, &State::load());
    query::query(&sv.host, sv.query_port, 3000).await
}

fn wait_for_stamp(progress: &(dyn Fn(&str, &str) + Send + Sync)) -> Option<patch::Stamp> {
    progress("running", "Game running. Waiting for the patch stamp");
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(120);
    while std::time::Instant::now() < deadline {
        if let Some(s) = patch::read_stamp() {
            return Some(s);
        }
        if !launch::is_game_running() {
            return None;
        }
        std::thread::sleep(std::time::Duration::from_millis(750));
    }
    None
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
    let mut notes: Vec<String> = Vec::new();
    let mut st = State::load();

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
            "Remove '{opt}' from your Steam launch options for Project Zomboid first. It puts the game in debug mode, and the server drops debug clients mid-join. If your account is a server admin, turn on 'Allow debug mode' under Details instead."
        )));
    }
    let steam = install::steam_exe()?;
    let _steam_id = install::active_steam_id()?;
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
        notes.push(format!(
            "{} required mod(s) are not subscribed. Use Pre-load mods, or the server will make \
             you download them on the connect screen.",
            mods.missing
        ));
    }
    if mods.downloading > 0 {
        notes.push(format!(
            "Steam is still downloading {} mod(s). You can play, but the join will wait for them.",
            mods.downloading
        ));
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

    let stale: Vec<&str> = mods
        .mods
        .iter()
        .filter(|x| x.out_of_date)
        .map(|x| x.name.as_str())
        .collect();
    if !stale.is_empty() {
        notes.push(format!(
            "Steam has an update it has not applied for: {}. The server checks mod versions \
             during the join, so this is likely to disconnect you. Let Steam finish updating, \
             then press Play again.",
            stale.join(", ")
        ));
    }

    progress("download", "Syncing the patch payload");
    let fetched = payload::sync(&m).await?;
    if fetched > 0 {
        notes.push(format!("Downloaded {fetched} patch file(s)."));
    }
    st.installed_build = Some(m.build);
    st.install_dir = Some(install_dir.clone());
    st.jar = Some(fp);
    st.save()?;

    progress("patch", "Patching the launch configuration");
    patch::apply(&mut st, &install_dir, m.build)?;

    let outcome = (|| -> Result<Option<patch::Stamp>> {
        progress("account", "Preparing your Steam-bound game account");
        bootstrap::install()?;
        bootstrap::clear_join_result();
        bootstrap::clear_role();
        let _ = serverlist::seed(&sv.name, &sv.host, sv.connect_port, &username)?;
        bootstrap::write_join_intent(&sv.host, sv.connect_port, &username, &sv.name)?;

        progress("launch", "Starting Project Zomboid through Steam");
        launch::launch(&steam)?;
        launch::wait_for_start(120)?;

        let stamp = wait_for_stamp(progress);

        progress(
            "playing",
            match stamp {
                Some(_) => "Patch active. Enjoy. Restoring on exit",
                None => "Playing. The patch could not be verified. Restoring on exit",
            },
        );
        launch::wait_for_exit();
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
        Some(r) if r.code == "OK" => {
            if !st.account_confirmed {
                st.account_confirmed = true;
                st.save()?;
            }
            None
        }
        Some(r) => {
            if r.code == "DebugNotAllowed" && st.role_grants_debug != Some(false) {
                st.role_grants_debug = Some(false);
                st.save()?;
            }
            if r.code == "PLZWrongCharacter" && !r.detail.is_empty() {
                bound_username = Some(r.detail.clone());
                st.account_username = Some(r.detail.clone());
                st.account_confirmed = true;
                st.save()?;
            }
            bootstrap::explain(r)
        }
        None => None,
    };

    let stamp = outcome?;
    if stamp.is_none() {
        notes.push(
            "The game started but never wrote a patch stamp, so the shadow classes may not \
             have loaded. Report this."
                .into(),
        );
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
    let name = account::validate(&name)?;
    let mut st = State::load();
    if st.account_confirmed && st.account_username.as_deref() != Some(name.as_str()) {
        return Err(Error::Other(
            "Your account already exists on the server under its current username. Ask an admin to rename it."
                .into(),
        ));
    }
    st.account_username = Some(name.clone());
    st.save()?;
    Ok(name)
}

#[tauri::command]
async fn set_allow_debug(allowed: bool) -> Result<bool> {
    let mut st = State::load();
    st.allow_debug = allowed;
    st.save()?;
    Ok(allowed)
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

fn guard_install_change(st: &State) -> Result<()> {
    if st.active_patch.is_some() {
        return Err(Error::Other(
            "Quit Project Zomboid first. This session's patch is still in place, and the \
             launcher has to put your game files back before the folder can change."
                .into(),
        ));
    }
    Ok(())
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
    guard_install_change(&st)?;
    st.install_dir = Some(resolved.clone());
    st.jar = None;
    st.save()?;
    Ok(resolved.to_string_lossy().into_owned())
}

#[tauri::command]
async fn clear_install_dir() -> Result<Option<String>> {
    let mut st = State::load();
    guard_install_change(&st)?;
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
            set_allow_debug,
            restore_now,
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
            fit_window
        ])
        .run(tauri::generate_context!())
        .expect("error while running the launcher");
}
