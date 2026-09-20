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

pub const DEBUG_ARG: &str = "-debug";

// Anything after the app id is handed on to the game by Steam, and lands on top of whatever
// the player has in Properties > Launch Options rather than replacing it.
pub fn launch(steam_exe: &Path, extra_args: &[&str]) -> Result<()> {
    let mut cmd = Command::new(steam_exe);
    cmd.arg("-applaunch").arg(config::STEAM_APP_ID);
    cmd.args(extra_args);
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

/// How long the game process has to stay missing before the session counts as over.
pub const GONE_CONFIRM_SECS: u64 = 20;

/// How long after firing the launch a missing process is refused as evidence of anything.
///
/// Steam does not hand the game over in one step. On a warm machine a process can appear,
/// exit and be replaced seconds later, and the launcher used to read the gap as the player
/// having quit: it stopped waiting for the stamp, restored ProjectZomboid64.json, and the
/// real game then started against the restored file and ran vanilla all session. Nothing
/// good happens inside the first minute and a half of a launch, so nothing is decided there.
pub const SESSION_FLOOR_SECS: u64 = 90;

/// The whole rule, separated from the clock so it can be tested.
pub fn session_over(since_launch: Duration, absent_for: Option<Duration>) -> bool {
    let Some(absent) = absent_for else {
        return false;
    };
    since_launch.as_secs() >= SESSION_FLOOR_SECS && absent.as_secs() >= GONE_CONFIRM_SECS
}

/// Tracks whether the game is really gone, as opposed to between processes.
pub struct SessionWatch {
    launched: Instant,
    absent_since: Option<Instant>,
}

impl SessionWatch {
    pub fn started_now() -> Self {
        Self {
            launched: Instant::now(),
            absent_since: None,
        }
    }

    /// True while the game process is missing but the session is still being given the benefit
    /// of the doubt. Worth telling the player about: it is the one moment the launcher looks
    /// stuck for a reason that is not a problem.
    pub fn is_absent(&self) -> bool {
        self.absent_since.is_some()
    }

    /// True once the game has been absent long enough, late enough, to believe it.
    pub fn is_over(&mut self) -> bool {
        let now = Instant::now();
        if is_game_running() {
            if let Some(since) = self.absent_since.take() {
                crate::session_log::log(
                    "process",
                    &format!(
                        "the game process is back after {:.1}s; that gap was a relaunch, not the end of the session",
                        now.duration_since(since).as_secs_f32()
                    ),
                );
            }
            return false;
        }
        if self.absent_since.is_none() {
            self.absent_since = Some(now);
            crate::session_log::log(
                "process",
                "no game process found; holding the patch in place to see whether it comes back",
            );
        }
        let over = session_over(
            now.duration_since(self.launched),
            self.absent_since.map(|t| now.duration_since(t)),
        );
        if over {
            crate::session_log::log(
                "process",
                &format!(
                    "the game has been gone for {}s, {}s after the launch; treating the session as over",
                    GONE_CONFIRM_SECS,
                    now.duration_since(self.launched).as_secs()
                ),
            );
        }
        over
    }
}

// The join result lands while the game is still up: the bootstrap Lua writes it the moment
// OnConnectFailed fires. Giving the caller a tick here is what lets a refusal be reported
// then, instead of sitting unread until the player gives up and quits.
pub fn wait_for_exit_with(watch: &mut SessionWatch, mut tick: impl FnMut()) {
    while !watch.is_over() {
        tick();
        std::thread::sleep(Duration::from_secs(3));
    }
}

#[cfg(test)]
mod session_rules {
    use super::*;

    fn secs(n: u64) -> Duration {
        Duration::from_secs(n)
    }

    #[test]
    fn a_running_game_is_never_over() {
        assert!(!session_over(secs(6_000), None));
    }

    #[test]
    fn the_gap_that_broke_raffys_launch_is_not_the_end_of_a_session() {
        // Patched at launch, process seen, process gone three seconds later. The old code
        // restored here, and the real game started four seconds after that.
        assert!(!session_over(secs(3), Some(secs(3))));
        assert!(!session_over(secs(7), Some(secs(4))));
    }

    #[test]
    fn a_long_absence_still_waits_out_the_floor() {
        assert!(!session_over(secs(45), Some(secs(45))));
    }

    #[test]
    fn a_brief_blip_late_in_a_session_is_not_the_end_either() {
        assert!(!session_over(secs(7_200), Some(secs(19))));
    }

    #[test]
    fn a_real_quit_ends_the_session() {
        assert!(session_over(secs(7_200), Some(secs(20))));
        assert!(session_over(secs(90), Some(secs(90))));
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
