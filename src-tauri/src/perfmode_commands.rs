//! Tauri commands for the Performance mode toggle.
//!
//! Kept out of lib.rs because the interesting part is the ordering rules, not the plumbing:
//! options.ini may only be touched with the game closed, and turning the toggle off has to
//! respect any of our keys the player has since changed for themselves.

use crate::error::{Error, Result};
use crate::launch;
use crate::payload;
use crate::perfmode;
use crate::state::State;
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PerfModeStatus {
    /// What the toggle should show.
    pub enabled: bool,
    /// How many options.ini keys the preset owns.
    pub key_count: usize,
    /// False when the game is running, which is the one time the file must not be written.
    pub can_change: bool,
}

/// Whether the preset is on right now.
///
/// Answered from options.ini, not from the stored flag. The player can undo the preset by
/// hand in the in-game options screen, and a toggle that keeps claiming to be on after that
/// would be lying about the file it exists to manage.
#[tauri::command]
pub async fn get_performance_mode() -> Result<PerfModeStatus> {
    let st = State::load();
    let applied = st.performance_mode.unwrap_or_default();
    Ok(PerfModeStatus {
        enabled: perfmode::is_active(&applied),
        key_count: applied.written.len().max(perfmode::PROFILE.len()),
        can_change: !launch::is_game_running(),
    })
}

#[tauri::command]
pub async fn set_performance_mode(enabled: bool) -> Result<PerfModeStatus> {
    // The game rewrites the whole of options.ini from memory when the player hits Apply, so
    // anything written underneath a running game is discarded without a word.
    if launch::is_game_running() {
        return Err(Error::GameAlreadyRunning);
    }

    let mut st = State::load();

    if enabled {
        // Re-applying over an active preset would snapshot our own values as "what the player
        // had before", and the toggle could then never get back. Refresh nothing, just report.
        let existing = st.performance_mode.clone().unwrap_or_default();
        if perfmode::is_active(&existing) {
            return Ok(PerfModeStatus {
                enabled: true,
                key_count: existing.written.len(),
                can_change: true,
            });
        }

        // A manifest we cannot reach is not a reason to refuse: fall back to the baked profile
        // rather than making an offline player fight their frame rate.
        let from_manifest = payload::fetch_manifest()
            .await
            .ok()
            .map(|m| m.performance_profile)
            .filter(|p| !p.is_empty());

        let values = perfmode::profile_values(from_manifest.as_ref());
        let applied = perfmode::apply(&values)?;
        let key_count = applied.written.len();
        st.performance_mode = Some(applied);
        st.save()?;

        Ok(PerfModeStatus {
            enabled: true,
            key_count,
            can_change: true,
        })
    } else {
        let applied = st.performance_mode.clone().unwrap_or_default();
        let key_count = applied.written.len();
        perfmode::revert(&applied)?;
        st.performance_mode = None;
        st.save()?;

        Ok(PerfModeStatus {
            enabled: false,
            key_count,
            can_change: true,
        })
    }
}
