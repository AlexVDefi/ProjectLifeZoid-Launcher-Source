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
pub const AUDIO_CUSHION_MS: u64 = 200;
const FILL_STEP_MS: i64 = 20;
pub const VIDEO_LEAD_MS: u64 = 150;
const LATE_REBASE_MS: u64 = 1000;
const SCHEDULE_AHEAD_MS: i64 = 1000;

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

enum Event {
    Video(Vec<u8>),
    Audio { ts_ms: u32, samples: u32, rate: u32, data: Vec<u8> },
    Fill(i64),
}

/// Chunks are merged onto one timeline a second before they are due: video runs VIDEO_LEAD_MS past a
/// chunk's end, and the next chunk's voice must not wait for it.
fn schedule(seg: Segment, queue: &mut VecDeque<(i64, Event)>) {
    let cushion = AUDIO_CUSHION_MS as i64;
    let mut all: Vec<(i64, Event)> = queue.drain(..).collect();
    for v in seg.video {
        all.push((v.ts_ms as i64 + VIDEO_LEAD_MS as i64, Event::Video(v.data)));
    }
    for a in seg.audio {
        all.push((a.ts_ms as i64 - cushion, Event::Audio { ts_ms: a.ts_ms, samples: a.samples, rate: seg.audio_rate, data: a.data }));
    }
    let seg_end = (seg.start_ms + seg.dur_ms) as i64;
    let mut tick = seg.start_ms as i64;
    while tick < seg_end {
        // never fill into the next chunk's time: its first words would be trimmed as overlap
        all.push((tick, Event::Fill((tick + cushion - FILL_STEP_MS).min(seg_end - FILL_STEP_MS))));
        tick += FILL_STEP_MS;
    }
    all.sort_by_key(|e| e.0);
    queue.extend(all);
}

/// One gapless PCM line per stream, sent AUDIO_CUSHION_MS ahead, so FMOD's raw buffer never runs dry
/// between words or when a send is a few milliseconds late.
#[derive(Default)]
pub struct AudioLine {
    until: Option<i64>,
    rate: u32,
}

impl AudioLine {
    fn samples(&self, ms: i64) -> i64 {
        ms * self.rate as i64 / 1000
    }

    pub fn reset(&mut self) {
        self.until = None;
    }

    pub fn block(&mut self, ts_ms: u32, rate: u32, pcm: &[i16]) -> Vec<i16> {
        if rate != self.rate {
            self.rate = rate;
            self.until = None;
        }
        let start = self.samples(ts_ms as i64);
        let until = self.until.unwrap_or(start);
        let mut out = Vec::with_capacity(pcm.len());
        if start > until {
            let gap = (start - until).min(self.samples(1000)) as usize;
            out.resize(gap, 0);
            out.extend_from_slice(pcm);
        } else {
            let skip = (until - start) as usize;
            if skip < pcm.len() {
                out.extend_from_slice(&pcm[skip..]);
            }
        }
        self.until = Some(until.max(start) + pcm.len() as i64);
        out
    }

    pub fn fill(&mut self, upto_ms: i64) -> Vec<i16> {
        let Some(until) = self.until else {
            return Vec::new();
        };
        let upto = self.samples(upto_ms);
        if upto <= until {
            return Vec::new();
        }
        self.until = Some(upto);
        vec![0; (upto - until).min(self.samples(1000)) as usize]
    }
}

async fn play(stream: &str, mut rx: mpsc::Receiver<Fetched>, stop: &AtomicBool, out: &Outbox) {
    let mut decoder = Decoder::new().ok();
    let mut buffer: VecDeque<Segment> = VecDeque::new();
    let mut clock: Option<(Instant, u32)> = None;
    let mut ended = false;
    let mut line = AudioLine::default();
    let mut rgba = Vec::new();
    let mut state = "";
    let say = |s: &'static str, state: &mut &'static str| {
        if *state != s {
            *state = s;
            out.status(&format!("watch.{stream}={s}"));
        }
    };
    let mut queue: VecDeque<(i64, Event)> = VecDeque::new();
    loop {
        if stop.load(Ordering::Relaxed) {
            return;
        }
        let mut incoming = Vec::new();
        while let Ok(more) = rx.try_recv() {
            incoming.push(more);
        }
        let waiting = clock.is_none() && !ended && buffer.len() < START_SEGMENTS;
        let starved = clock.is_some() && queue.is_empty() && buffer.is_empty() && !ended;
        if incoming.is_empty() && (waiting || starved) {
            say("buffering", &mut state);
            match tokio::time::timeout(Duration::from_millis(500), rx.recv()).await {
                Ok(Some(f)) => incoming.push(f),
                Ok(None) => ended = true,
                Err(_) => {}
            }
        }
        for f in incoming {
            match f {
                Fetched::Segment(seg) => buffer.push_back(seg),
                Fetched::Jump => {
                    buffer.clear();
                    queue.clear();
                    clock = None;
                    decoder = Decoder::new().ok();
                }
                Fetched::Ended => ended = true,
            }
        }
        if clock.is_none() {
            if !ended && buffer.len() < START_SEGMENTS {
                continue;
            }
            let Some(first) = buffer.front() else {
                say("ended", &mut state);
                return;
            };
            clock = Some((Instant::now(), first.start_ms));
            line.reset();
        }
        let Some(mut base) = clock else {
            continue;
        };
        let now_key = base.1 as i64 + base.0.elapsed().as_millis() as i64;
        while queue.back().is_none_or(|(k, _)| *k < now_key + SCHEDULE_AHEAD_MS) {
            match buffer.pop_front() {
                Some(seg) => schedule(seg, &mut queue),
                None => break,
            }
        }
        let Some(&(key, _)) = queue.front() else {
            if ended && buffer.is_empty() {
                say("ended", &mut state);
                return;
            }
            continue;
        };
        say("playing", &mut state);
        let offset = (key - base.1 as i64).max(0) as u64;
        let due = base.0 + Duration::from_millis(offset);
        let now = Instant::now();
        if now > due + Duration::from_millis(LATE_REBASE_MS) {
            base = (now - Duration::from_millis(offset), base.1);
            clock = Some(base);
            line.reset();
        } else if due > now {
            sleep_until(due.min(now + Duration::from_millis(50))).await;
            continue;
        }
        let Some((_, event)) = queue.pop_front() else {
            continue;
        };
        match event {
            Event::Video(data) => {
                let Some(dec) = decoder.as_mut() else { continue };
                if let Ok(Some(img)) = dec.decode(&data) {
                    let (w, h) = img.dimensions();
                    rgba.resize(w * h * 4, 0);
                    img.write_rgba8(&mut rgba);
                    out.frame(stream, w as u16, h as u16, &rgba);
                }
            }
            Event::Audio { ts_ms, samples, rate, data } => {
                if let Some(pcm) = adpcm::decode(&data, samples as usize) {
                    let pcm = line.block(ts_ms, rate, &pcm);
                    if !pcm.is_empty() {
                        out.audio(stream, rate, &pcm);
                    }
                }
            }
            Event::Fill(upto) => {
                let silence = line.fill(upto);
                if !silence.is_empty() {
                    out.audio(stream, line.rate, &silence);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn consecutive_blocks_pass_through_untouched() {
        let mut line = AudioLine::default();
        assert_eq!(line.block(1000, 24000, &[1; 480]), vec![1; 480]);
        assert_eq!(line.block(1020, 24000, &[2; 480]), vec![2; 480]);
    }

    #[test]
    fn a_pause_between_words_becomes_silence_not_a_gap() {
        let mut line = AudioLine::default();
        line.block(1000, 24000, &[1; 480]);
        let next = line.block(1100, 24000, &[2; 480]);
        assert_eq!(next.len(), 80 * 24 + 480);
        assert!(next[..80 * 24].iter().all(|&s| s == 0));
        assert!(next[80 * 24..].iter().all(|&s| s == 2));
    }

    #[test]
    fn the_fill_keeps_the_line_going_and_a_late_block_is_trimmed_not_doubled() {
        let mut line = AudioLine::default();
        assert!(line.fill(5000).is_empty(), "no line before the first word");
        line.block(1000, 24000, &[1; 480]);
        assert_eq!(line.fill(1100).len(), 80 * 24);
        assert!(line.fill(1100).is_empty());
        let late = line.block(1090, 24000, &[3; 480]);
        assert_eq!(late, vec![3; 240]);
    }

    #[test]
    fn a_long_silence_is_capped_and_a_rate_change_restarts_the_line() {
        let mut line = AudioLine::default();
        line.block(0, 24000, &[1; 480]);
        assert_eq!(line.fill(60_000).len(), 24000);
        assert_eq!(line.block(70_000, 16000, &[1; 320]).len(), 320);
    }
}
