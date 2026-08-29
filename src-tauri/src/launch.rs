use crate::config;
use crate::error::{Error, Result};
use std::path::Path;
use std::process::Command;
use std::time::{Duration, Instant};

#[cfg(windows)]
pub const GAME_EXE: &str = "ProjectZomboid64.exe";
#[cfg(target_os = "linux")]
pub const GAME_EXE: &str = "ProjectZomboid64";
#[cfg(target_os = "macos")]
pub const GAME_EXE: &str = "JavaAppLauncher";

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

fn no_window(cmd: &mut Command) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let _ = cmd;
}

#[cfg(windows)]
pub fn is_game_running() -> bool {
    let mut cmd = Command::new("tasklist");
    cmd.args(["/FI", &format!("IMAGENAME eq {GAME_EXE}"), "/NH"]);
    no_window(&mut cmd);
    match cmd.output() {
        Ok(out) => String::from_utf8_lossy(&out.stdout).contains(GAME_EXE),
        Err(_) => false,
    }
}

#[cfg(not(windows))]
pub fn is_game_running() -> bool {
    Command::new("pgrep")
        .args(["-f", GAME_EXE])
        .output()
        .map(|out| !out.stdout.is_empty())
        .unwrap_or(false)
}

pub fn launch(steam_exe: &Path) -> Result<()> {
    let mut cmd = Command::new(steam_exe);
    cmd.arg("-applaunch").arg(config::STEAM_APP_ID);
    no_window(&mut cmd);
    cmd.spawn().map_err(|e| {
        Error::Io(format!(
            "could not start Steam ({}): {e}",
            steam_exe.display()
        ))
    })?;
    Ok(())
}

pub fn wait_for_start(timeout_secs: u64) -> Result<()> {
    let deadline = Instant::now() + Duration::from_secs(timeout_secs);
    while Instant::now() < deadline {
        if is_game_running() {
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(500));
    }
    Err(Error::GameNeverStarted(timeout_secs))
}

pub fn wait_for_exit() {
    while is_game_running() {
        std::thread::sleep(Duration::from_secs(3));
    }
}
