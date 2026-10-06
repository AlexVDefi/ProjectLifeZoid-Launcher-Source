use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{SyncSender, TrySendError};
use std::sync::Arc;

use super::proto::{self, Builder};

/// The writer thread's queue. Frames are dropped when the game reads slower than we send, never
/// queued behind each other: a late frame is worth nothing.
#[derive(Clone)]
pub struct Outbox {
    tx: SyncSender<Vec<u8>>,
    pub dropped: Arc<AtomicU64>,
}

impl Outbox {
    pub fn new(tx: SyncSender<Vec<u8>>) -> Self {
        Self { tx, dropped: Arc::new(AtomicU64::new(0)) }
    }

    fn offer(&self, msg: Vec<u8>) -> bool {
        match self.tx.try_send(msg) {
            Ok(()) => true,
            Err(TrySendError::Full(_)) => {
                self.dropped.fetch_add(1, Ordering::Relaxed);
                false
            }
            Err(TrySendError::Disconnected(_)) => false,
        }
    }

    pub fn frame(&self, stream: &str, w: u16, h: u16, rgba: &[u8]) -> bool {
        self.offer(proto::message(proto::FRAME, &Builder::default().text(stream).u16(w).u16(h).bytes(rgba).0))
    }

    pub fn audio(&self, stream: &str, rate: u32, pcm: &[i16]) -> bool {
        self.offer(proto::message(proto::AUDIO, &Builder::default().text(stream).u32(rate).pcm(pcm).0))
    }

    pub fn status(&self, lines: &str) {
        self.offer(proto::message(proto::STATUS, lines.as_bytes()));
    }
}
