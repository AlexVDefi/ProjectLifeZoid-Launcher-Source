use std::path::PathBuf;

pub const MANIFEST_URL_ENV: &str = "PLZ_MANIFEST_URL";

pub const DEFAULT_MANIFEST_URL: &str = match option_env!("PLZ_MANIFEST_URL_BAKED") {
    Some(url) => url,
    None => "https://launcher.projectlifezoid.com/release/manifest.json",
};

pub const STEAM_APP_ID: &str = "108600";

pub fn app_dir() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("ProjectLifeZoidLauncher")
}

pub fn patch_dir(build: u64) -> PathBuf {
    app_dir().join("patch").join(build.to_string())
}

pub fn backup_dir() -> PathBuf {
    app_dir().join("backup")
}

pub fn state_path() -> PathBuf {
    app_dir().join("state.json")
}

pub fn rollback_guard_path() -> PathBuf {
    app_dir().join("security").join("highest-build.json")
}

pub fn stamp_path() -> PathBuf {
    app_dir().join("runtime").join("stamp.json")
}

pub fn workshop_override_pending_path() -> PathBuf {
    app_dir().join("workshop").join("poc-pending.properties")
}

pub fn workshop_override_active_path() -> PathBuf {
    app_dir()
        .join("runtime")
        .join("workshop-activation.properties")
}

pub fn zomboid_home() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("Zomboid")
}

pub fn server_list_db() -> PathBuf {
    zomboid_home().join("db").join("ServerListSteam.db")
}

/// The Tomb body opt-out, shared with the java patch.
///
/// zombie.plz.PLZBodyOverride reads this exact path inside ZomboidFileSystem.loadMods.
/// It resolves it as getCacheDir() + "/Lua", and getCacheDir() is user.home + "/Zomboid",
/// which is what zomboid_home() is. Keep the two in step or the toggle writes a setting
/// nothing reads.
pub fn body_override_path() -> PathBuf {
    zomboid_home().join("Lua").join("PLZBodyOverride.ini")
}

pub fn join_intent_path() -> PathBuf {
    zomboid_home()
        .join("Lua")
        .join("PLZLauncher")
        .join("join.txt")
}

pub fn join_result_path() -> PathBuf {
    zomboid_home()
        .join("Lua")
        .join("PLZLauncher")
        .join("result.txt")
}

pub fn role_path() -> PathBuf {
    zomboid_home()
        .join("Lua")
        .join("PLZLauncher")
        .join("role.txt")
}

pub fn launcher_mod_dir() -> PathBuf {
    zomboid_home().join("mods").join("PLZLauncher").join("42")
}

/// The game's video/audio settings file.
///
/// Core.saveOptions() rewrites this whole file from its in-memory option set every time the
/// player hits Apply on the options screen, so anything written here only survives if the
/// game has loaded it first. Write it with the game closed, never while it runs.
pub fn options_ini_path() -> PathBuf {
    zomboid_home().join("options.ini")
}
