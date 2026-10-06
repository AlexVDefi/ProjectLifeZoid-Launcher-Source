use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use openh264::decoder::Decoder;
use openh264::formats::YUVSource;
use tokio::sync::mpsc;
use tokio::time::{sleep, sleep_until, Instant};

use super::adpcm;
use super::outbox::Outbox;
use super::relay::Relay;
use super::segment::Segment;

pub const START_SEGMENTS: usize = 2;
const BEHIND_JUMP: i64 = 5;
pub const VIDEO_LEAD_MS: u64 = 150;
const LATE_REBASE_MS: u64 = 1000;

pub struct Watch {
    stop: Arc<AtomicBool>,
    done: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Watch {
    pub fn start(base: String, stream: String, out: Outbox) -> Watch {
        let stop = Arc::new(AtomicBool::new(false));
        let done = Arc::new(AtomicBool::new(false));
        let (s, d) = (stop.clone(), done.clone());
        let thread = std::thread::Builder::new()
            .name("live-watch".into())
            .spawn(move || {
                run(base, stream, out, s);
                d.store(true, Ordering::Relaxed);
            })
            .ok();
        Watch { stop, done, thread }
    }

    pub fn finished(&self) -> bool {
        self.done.load(Ordering::Relaxed)
    }

    pub fn stop(mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

enum Fetched {
    Segment(Segment),
    Jump,
    Ended,
}

fn run(base: String, stream: String, out: Outbox, stop: Arc<AtomicBool>) {
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(r) => r,
        Err(e) => {
            out.status(&format!("watch.{stream}=error runtime {e}"));
            return;
        }
    };
    runtime.block_on(async {
        let (tx, rx) = mpsc::channel(6);
        let relay = Relay::new(base);
        tokio::join!(fetch(&relay, &stream, tx, &stop, &out), play(&stream, rx, &stop, &out));
    });
}

async fn fetch(relay: &Relay, stream: &str, tx: mpsc::Sender<Fetched>, stop: &AtomicBool, out: &Outbox) {
    let mut head = loop {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        match relay.head(stream).await {
            Ok(Some(h)) => break h,
            Ok(None) => out.status(&format!("watch.{stream}=waiting")),
            Err(e) => out.status(&format!("watch.{stream}=error {e}")),
        }
        sleep(Duration::from_secs(1)).await;
    };
    let mut next = (head.seq - (START_SEGMENTS as i64 - 1)).max(0);
    let mut checked = Instant::now();
    loop {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        let waited = checked.elapsed();
        if waited > Duration::from_secs(4) {
            if let Ok(Some(h)) = relay.head(stream).await {
                head = h;
            }
            checked = Instant::now();
            if head.seq - next > BEHIND_JUMP {
                next = head.seq - (START_SEGMENTS as i64 - 1);
                if tx.send(Fetched::Jump).await.is_err() {
                    return;
                }
            }
        }
        if head.ended && next > head.seq {
            let _ = tx.send(Fetched::Ended).await;
            return;
        }
        match relay.segment(stream, next as u32).await {
            Ok(Some(bytes)) => match Segment::parse(&bytes) {
                Some(seg) => {
                    next += 1;
                    if tx.send(Fetched::Segment(seg)).await.is_err() {
                        return;
                    }
                }
                None => {
                    out.status(&format!("watch.{stream}=error bad segment {next}"));
                    next += 1;
                }
            },
            Ok(None) => {
                if waited > Duration::from_millis(1500) {
                    if let Ok(Some(h)) = relay.head(stream).await {
                        head = h;
                    }
                    checked = Instant::now();
                }
                sleep(Duration::from_millis(500)).await;
            }
            Err(_) => sleep(Duration::from_secs(1)).await,
        }
    }
}

enum Event<'a> {
    Video(&'a [u8]),
    Audio(u32, &'a [u8]),
}

async fn play(stream: &str, mut rx: mpsc::Receiver<Fetched>, stop: &AtomicBool, out: &Outbox) {
    let mut decoder = Decoder::new().ok();
    let mut buffer: VecDeque<Segment> = VecDeque::new();
    let mut clock: Option<(Instant, u32)> = None;
    let mut ended = false;
    let mut primed = false;
    let mut rgba = Vec::new();
    let mut state = "";
    let say = |s: &'static str, state: &mut &'static str| {
        if *state != s {
            *state = s;
            out.status(&format!("watch.{stream}={s}"));
        }
    };
    loop {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        let want = if primed { 1 } else { START_SEGMENTS };
        while !ended && buffer.len() < want {
            say("buffering", &mut state);
            match tokio::time::timeout(Duration::from_millis(500), rx.recv()).await {
                Ok(Some(Fetched::Segment(seg))) => buffer.push_back(seg),
                Ok(Some(Fetched::Jump)) => {
                    buffer.clear();
                    clock = None;
                    primed = false;
                    decoder = Decoder::new().ok();
                }
                Ok(Some(Fetched::Ended)) | Ok(None) => ended = true,
                Err(_) => {
                    if stop.load(Ordering::Relaxed) {
                        return;
                    }
                }
            }
        }
        while let Ok(more) = rx.try_recv() {
            match more {
                Fetched::Segment(seg) => buffer.push_back(seg),
                Fetched::Jump => {
                    buffer.clear();
                    clock = None;
                    primed = false;
                    decoder = Decoder::new().ok();
                }
                Fetched::Ended => ended = true,
            }
        }
        let Some(seg) = buffer.pop_front() else {
            if ended {
                say("ended", &mut state);
                return;
            }
            continue;
        };
        let base = *clock.get_or_insert((Instant::now(), seg.start_ms));
        say("playing", &mut state);
        let mut events: Vec<(u64, Event)> = Vec::with_capacity(seg.video.len() + seg.audio.len());
        for v in &seg.video {
            events.push((v.ts_ms as u64 + VIDEO_LEAD_MS, Event::Video(&v.data)));
        }
        for a in &seg.audio {
            events.push((a.ts_ms as u64, Event::Audio(a.samples, &a.data)));
        }
        events.sort_by_key(|e| e.0);
        let mut base = base;
        for (ts, event) in events {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            let offset = ts.saturating_sub(base.1 as u64);
            let due = base.0 + Duration::from_millis(offset);
            let now = Instant::now();
            if now > due + Duration::from_millis(LATE_REBASE_MS) {
                base = (now - Duration::from_millis(offset), base.1);
            } else {
                sleep_until(due).await;
            }
            match event {
                Event::Video(data) => {
                    let Some(dec) = decoder.as_mut() else { continue };
                    if let Ok(Some(img)) = dec.decode(data) {
                        let (w, h) = img.dimensions();
                        rgba.resize(w * h * 4, 0);
                        img.write_rgba8(&mut rgba);
                        out.frame(stream, w as u16, h as u16, &rgba);
                    }
                }
                Event::Audio(samples, data) => {
                    if let Some(pcm) = adpcm::decode(data, samples as usize) {
                        out.audio(stream, seg.audio_rate, &pcm);
                    }
                }
            }
        }
        clock = Some(base);
        primed = true;
        if buffer.is_empty() && !ended {
            match tokio::time::timeout(Duration::from_millis(50), rx.recv()).await {
                Ok(Some(Fetched::Segment(next))) => buffer.push_back(next),
                Ok(Some(Fetched::Jump)) => {
                    clock = None;
                    primed = false;
                    decoder = Decoder::new().ok();
                }
                Ok(Some(Fetched::Ended)) | Ok(None) => ended = true,
                Err(_) => clock = None,
            }
        }
    }
}
