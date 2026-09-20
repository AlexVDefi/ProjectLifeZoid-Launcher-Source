//! A timestamped record of one Play press, written next to the patch state.
//!
//! Raffy Stone's launcher restored his install three seconds after firing the launch, and
//! working out why took a forensic pass over file mtimes on his machine, because the launcher
//! kept no account of its own run. Every progress step and every change in whether the game
//! process exists now lands here, so the next report answers the question by itself.
//!
//! Nothing in here returns an error. A play session must never fail because a log line could
//! not be written.

use crate::config;
use crate::state::now_millis;
use std::fs;
use std::io::Write;
use std::sync::Mutex;
use std::time::Instant;

static START: Mutex<Option<Instant>> = Mutex::new(None);

fn path() -> std::path::PathBuf {
    config::app_dir().join("runtime").join("session.log")
}

fn previous_path() -> std::path::PathBuf {
    config::app_dir().join("runtime").join("session-prev.log")
}

/// Begins a session log, keeping the one before it. Two runs is the useful window: a player
/// reporting a problem has almost always pressed Play again before anyone asks them for the file.
pub fn start(detail: &str) {
    let current = path();
    if let Some(parent) = current.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let _ = fs::rename(&current, previous_path());
    *START.lock().unwrap_or_else(|e| e.into_inner()) = Some(Instant::now());
    log("session", detail);
}

pub fn log(step: &str, detail: &str) {
    let elapsed = START
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .map(|t| t.elapsed().as_millis())
        .unwrap_or(0);
    let line = format!(
        "{}  +{:>7}  [{:<9}] {}\n",
        utc_stamp(now_millis()),
        format!("{}.{:03}s", elapsed / 1000, elapsed % 1000),
        step,
        detail
    );
    let Ok(mut f) = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path())
    else {
        return;
    };
    let _ = f.write_all(line.as_bytes());
}

/// UTC, because the launcher has no timezone database and a wrong local time is worse than an
/// honest offset. The diagnostics report prints local and UTC side by side for correlating.
fn utc_stamp(millis: u128) -> String {
    let secs = (millis / 1000) as i64;
    let ms = (millis % 1000) as u32;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{ms:03}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

// Howard Hinnant's days-from-civil, inverted. Shifting the era to start in March puts the leap
// day at the end of the cycle, which is what makes the month arithmetic branchless.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_a_known_instant() {
        // 2026-09-13T16:03:45.123Z
        assert_eq!(utc_stamp(1_789_315_425_123), "2026-09-13T16:03:45.123Z");
    }

    #[test]
    fn handles_the_leap_day_and_the_epoch() {
        assert_eq!(utc_stamp(0), "1970-01-01T00:00:00.000Z");
        assert_eq!(utc_stamp(1_709_164_800_000), "2024-02-29T00:00:00.000Z");
    }
}
