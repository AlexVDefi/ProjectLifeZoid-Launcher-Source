mod adpcm;
mod cast;
#[cfg(windows)]
mod hwenc;
mod link;
mod outbox;
pub mod proto;
mod relay;
mod segment;
pub mod token;
mod watch;
mod yuv;

use std::collections::hash_map::RandomState;
use std::fs;
use std::hash::BuildHasher;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use crate::{config, session_log};

pub use cast::{LADDER, SEGMENT_MS};
pub use link::serve;
pub use watch::{START_SEGMENTS, VIDEO_LEAD_MS};

/// RandomState is seeded from the OS RNG per instance; two of them give 128 unpredictable bits
/// without another dependency.
pub fn new_key() -> String {
    let a = RandomState::new().hash_one(std::process::id());
    let b = RandomState::new().hash_one(std::time::SystemTime::now());
    format!("{a:016x}{b:016x}")
}

/// Runs for one play session: the game finds the port and key in live-link.txt.
pub fn serve_until(stop: Arc<AtomicBool>) {
    let key = new_key();
    let port = match serve(stop.clone(), key.clone()) {
        Ok(port) => port,
        Err(e) => {
            session_log::log("live", &format!("link not started: {e}"));
            return;
        }
    };
    let path = config::live_link_path();
    let written = path
        .parent()
        .map(fs::create_dir_all)
        .unwrap_or(Ok(()))
        .and_then(|_| fs::write(path.with_extension("txt.tmp"), format!("port={port}\r\nkey={key}\r\n")))
        .and_then(|_| fs::rename(path.with_extension("txt.tmp"), &path));
    if let Err(e) = written {
        session_log::log("live", &format!("link file not written: {e}"));
        return;
    }
    session_log::log("live", &format!("link on 127.0.0.1:{port}"));
    let _ = std::thread::Builder::new().name("live-link-file".into()).spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            std::thread::sleep(Duration::from_millis(500));
        }
        let _ = fs::remove_file(&path);
    });
}
