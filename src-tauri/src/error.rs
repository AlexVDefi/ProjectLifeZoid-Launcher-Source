use serde::{Serialize, Serializer};

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error(
        "Project Zomboid was not found. Detection only covers Steam's own libraries, \
         so open Details and point the launcher at your game folder."
    )]
    InstallNotFound,

    #[error(
        "{path}\n\
         is not a Project Zomboid install. In Steam, right-click Project Zomboid, \
         choose Manage > Browse local files, and pick the folder that opens."
    )]
    NotAnInstall { path: String },

    #[error("Project Zomboid is already running. Quit it fully, then try again.")]
    GameAlreadyRunning,

    #[error("Steam is not installed, or steam.exe could not be located.")]
    SteamNotFound,

    #[error(
        "This launcher's patch was built for a different version of Project Zomboid.\n\
         The game updated ({actual}) but the payload expects {expected}.\n\
         Wait for a launcher update -- running it anyway would silently revert engine fixes."
    )]
    StaleJar { expected: String, actual: String },

    #[error(
        "The server is running patch build {server}, this launcher has build {local}.\n\
         The server is probably mid-update. Try again in a few minutes."
    )]
    BuildMismatch { server: u64, local: u64 },

    #[error(
        "Steam has not downloaded the server's mod update yet: {mods}.
         Your copy is from {local}; the server is running the build published {expected}.
         The game would sit on the join screen with no explanation, so the launcher stopped here.
         Leave Project Zomboid closed, let Steam finish the Workshop download, then press Play again."
    )]
    WorkshopBehind {
        mods: String,
        local: String,
        expected: String,
    },

    #[error(
        "Steam has not finished with your Workshop mods: {mods}.\n\
         Starting now makes the game ask Steam for mod folders it has not written yet, and \
         the answer is cached for the whole session -- so anything refused stays refused. \
         An invisible character, invisible vehicles and a blank map are the usual result.\n\
         Leave Project Zomboid closed, wait for Steam's Downloads page to go quiet, then \
         press Play again. If Steam shows nothing downloading, restart Steam -- it applies \
         pending Workshop updates on startup."
    )]
    WorkshopNotReady { mods: String },

    #[error("{url} returned {status}")]
    Http { url: String, status: u16 },

    #[error("Payload signature check failed. Refusing to install unverified code.")]
    BadSignature,

    #[error(
        "The update server offered patch build {offered}, but this machine has already seen\n\
         build {seen}. Updates never go backwards, so the launcher stopped rather than install\n\
         older code that a valid signature cannot prove is current.\n\
         If the real server was rolled back on purpose, the next update will carry a HIGHER\n\
         build number and this will clear itself."
    )]
    Rollback { seen: u64, offered: u64 },

    #[error("{path}: expected sha256 {expected}, got {actual}")]
    BadChecksum {
        path: String,
        expected: String,
        actual: String,
    },

    #[error(
        "The game did not start within {0}s. Your install has been restored.\n\
         A common cause is a corrupted ProjectZomboid64.json -- try verifying the game files in Steam."
    )]
    GameNeverStarted(u64),

    #[error("ProjectZomboid64.json is not valid JSON: {0}")]
    MalformedJson(String),

    #[error("could not reach the server ({0})")]
    Query(String),

    #[error("{0}")]
    Io(String),

    #[error("{0}")]
    Other(String),
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::MalformedJson(e.to_string())
    }
}

impl From<reqwest::Error> for Error {
    fn from(e: reqwest::Error) -> Self {
        Error::Other(format!("network: {e}"))
    }
}

impl From<tauri_plugin_updater::Error> for Error {
    fn from(e: tauri_plugin_updater::Error) -> Self {
        Error::Other(format!("launcher update: {e}"))
    }
}

impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Error::Other(format!("server list db: {e}"))
    }
}

impl Serialize for Error {
    fn serialize<S: Serializer>(&self, s: S) -> std::result::Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
