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

// szExeFile is a NUL-padded UTF-16 image name. tasklist's IMAGENAME filter matched
// case-insensitively, so this does too.
#[cfg(windows)]
fn entry_name_is(raw: &[u16], exe: &str) -> bool {
    let end = raw.iter().position(|&c| c == 0).unwrap_or(raw.len());
    char::decode_utf16(raw[..end].iter().copied())
        .map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER))
        .flat_map(char::to_lowercase)
        .eq(exe.chars().flat_map(char::to_lowercase))
}

// Reading the process table directly instead of shelling out to tasklist.exe.
// wait_for_exit polls this for the whole session, and a process creation every few
// seconds fails outright (0xc000012d) once the machine is near its commit limit.
#[cfg(windows)]
fn process_running(exe: &str) -> bool {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };

    unsafe {
        let Ok(snapshot) = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) else {
            return false;
        };
        let mut entry = PROCESSENTRY32W {
            dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32,
            ..Default::default()
        };
        let mut found = false;
        if Process32FirstW(snapshot, &mut entry).is_ok() {
            loop {
                if entry_name_is(&entry.szExeFile, exe) {
                    found = true;
                    break;
                }
                if Process32NextW(snapshot, &mut entry).is_err() {
                    break;
                }
            }
        }
        let _ = CloseHandle(snapshot);
        found
    }
}

#[cfg(windows)]
pub fn is_game_running() -> bool {
    process_running(GAME_EXE)
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
    wait_for_exit_with(|| {});
}

// The join result lands while the game is still up: the bootstrap Lua writes it the moment
// OnConnectFailed fires. Giving the caller a tick here is what lets a refusal be reported
// then, instead of sitting unread until the player gives up and quits.
pub fn wait_for_exit_with(mut tick: impl FnMut()) {
    while is_game_running() {
        tick();
        std::thread::sleep(Duration::from_secs(3));
    }
}

#[cfg(all(test, windows))]
mod tests {
    use super::*;

    fn own_exe_name() -> String {
        std::env::current_exe()
            .unwrap()
            .file_name()
            .unwrap()
            .to_string_lossy()
            .into_owned()
    }

    #[test]
    fn the_snapshot_finds_the_process_asking() {
        let me = own_exe_name();
        assert!(process_running(&me), "{me} was missing from the snapshot");
    }

    #[test]
    fn the_image_name_is_matched_case_insensitively() {
        assert!(process_running(&own_exe_name().to_uppercase()));
    }

    #[test]
    fn a_process_that_is_not_running_is_not_found() {
        assert!(!process_running("plz-definitely-not-running-9f3c.exe"));
    }

    #[test]
    fn a_prefix_of_a_running_image_name_does_not_match() {
        let me = own_exe_name();
        let mut prefix = me.clone();
        prefix.pop();
        assert!(!process_running(&prefix), "{prefix} matched {me}");
    }
}
