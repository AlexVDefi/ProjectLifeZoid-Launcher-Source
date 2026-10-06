use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, SyncSender, TrySendError};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use openh264::encoder::{BitRate, Complexity, Encoder, EncoderConfig, FrameRate, IntraFramePeriod, RateControlMode, UsageType};
use openh264::formats::YUVSlices;
use openh264::{OpenH264API, Timestamp};

use super::outbox::Outbox;
use super::relay::Relay;
use super::segment::{AudioBlock, Segment, VideoUnit};
use super::{adpcm, yuv};
use crate::session_log;

pub const SEGMENT_MS: u32 = 2000;
const AUDIO_BLOCK: usize = 480;
const MAX_BACKLOG: usize = 5;
const SLOW_ENCODE_MS: f64 = 25.0;
pub const FALLBACK_SIZE: (u16, u16) = (960, 540);

pub enum CastMsg {
    Video { ts: u32, w: u16, h: u16, rgba: Vec<u8> },
    Audio { ts: u32, rate: u32, pcm: Vec<i16> },
    Stop,
}

pub struct Cast {
    tx: SyncSender<CastMsg>,
    thread: Option<JoinHandle<()>>,
    busy: Arc<AtomicU64>,
}

impl Cast {
    pub fn start(base: String, token: String, stream: String, fps: u16, out: Outbox) -> Cast {
        let (tx, rx) = mpsc::sync_channel(3);
        let busy = Arc::new(AtomicU64::new(0));
        let counted = busy.clone();
        let thread = std::thread::Builder::new()
            .name("live-cast".into())
            .spawn(move || Caster::new(base, token, stream, fps, out, counted).run(rx))
            .ok();
        Cast { tx, thread, busy }
    }

    /// A frame that arrives while the encoder is still busy is dropped here, not queued.
    pub fn video(&self, ts: u32, w: u16, h: u16, rgba: Vec<u8>) -> bool {
        let full = matches!(self.tx.try_send(CastMsg::Video { ts, w, h, rgba }), Err(TrySendError::Full(_)));
        if full {
            self.busy.fetch_add(1, Ordering::Relaxed);
        }
        !full
    }

    pub fn audio(&self, ts: u32, rate: u32, pcm: Vec<i16>) {
        let _ = self.tx.send(CastMsg::Audio { ts, rate, pcm });
    }

    pub fn stop(mut self) {
        let _ = self.tx.send(CastMsg::Stop);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

fn bitrate_for(w: u16, h: u16) -> u32 {
    let pixels = w as u64 * h as u64;
    (pixels * 3_000_000 / (1280 * 720)).clamp(500_000, 4_000_000) as u32
}

fn new_encoder(w: u16, h: u16, fps: u16) -> Option<Encoder> {
    let config = EncoderConfig::new()
        .bitrate(BitRate::from_bps(bitrate_for(w, h)))
        .max_frame_rate(FrameRate::from_hz(fps as f32))
        .rate_control_mode(RateControlMode::Bitrate)
        .usage_type(UsageType::CameraVideoRealTime)
        .complexity(Complexity::Low)
        .intra_frame_period(IntraFramePeriod::from_num_frames(0))
        .num_threads(4)
        .skip_frames(true);
    Encoder::with_api_config(OpenH264API::from_source(), config).ok()
}

struct Building {
    seg: Segment,
}

struct Caster {
    stream: String,
    fps: u16,
    out: Outbox,
    upload: SyncSender<Upload>,
    uploader: Option<JoinHandle<()>>,
    encoder: Option<(u16, u16, Encoder)>,
    building: Option<Building>,
    audio: VecDeque<AudioBlock>,
    adpcm: adpcm::Encoder,
    audio_rate: u32,
    yuv: Vec<u8>,
    seq: u32,
    clock: Option<(u32, Instant)>,
    encode_ms: f64,
    encoded: u64,
    skipped_by_rc: u64,
    dropped_busy: u64,
    bytes_this_seg: usize,
    fallback_sent: bool,
    busy: Arc<AtomicU64>,
}

impl Caster {
    fn new(base: String, token: String, stream: String, fps: u16, out: Outbox, busy: Arc<AtomicU64>) -> Self {
        let (upload, rx) = mpsc::sync_channel(64);
        let s = stream.clone();
        let o = out.clone();
        let uploader = std::thread::Builder::new()
            .name("live-upload".into())
            .spawn(move || upload_loop(base, token, s, rx, o))
            .ok();
        Self {
            stream,
            fps: fps.clamp(1, 60),
            out,
            upload,
            uploader,
            encoder: None,
            building: None,
            audio: VecDeque::new(),
            adpcm: adpcm::Encoder::default(),
            audio_rate: 24000,
            yuv: Vec::new(),
            seq: 0,
            clock: None,
            encode_ms: 0.0,
            encoded: 0,
            skipped_by_rc: 0,
            dropped_busy: 0,
            bytes_this_seg: 0,
            busy,
            fallback_sent: false,
        }
    }

    fn now_ms(&self) -> Option<u32> {
        self.clock.map(|(ts, at)| ts + at.elapsed().as_millis() as u32)
    }

    fn run(mut self, rx: Receiver<CastMsg>) {
        self.out.status(&format!("cast.state=starting\ncast.stream={}", self.stream));
        loop {
            match rx.recv_timeout(Duration::from_millis(250)) {
                Ok(CastMsg::Video { ts, w, h, rgba }) => self.video(ts, w, h, &rgba),
                Ok(CastMsg::Audio { ts, rate, pcm }) => self.audio(ts, rate, &pcm),
                Ok(CastMsg::Stop) | Err(RecvTimeoutError::Disconnected) => break,
                Err(RecvTimeoutError::Timeout) => {
                    let late = match (&self.building, self.now_ms()) {
                        (Some(b), Some(now)) => now >= b.seg.start_ms + SEGMENT_MS + 500,
                        _ => false,
                    };
                    if late {
                        let end = self.building.as_ref().map(|b| b.seg.start_ms + SEGMENT_MS).unwrap_or(0);
                        self.close(end);
                    }
                }
            }
        }
        if let Some(end) = self.now_ms() {
            self.close(end);
        }
        let _ = self.upload.send(Upload::End);
        if let Some(t) = self.uploader.take() {
            let _ = t.join();
        }
        self.out.status("cast.state=ended");
    }

    fn video(&mut self, ts: u32, w: u16, h: u16, rgba: &[u8]) {
        if w < 16 || h < 16 || w % 2 != 0 || h % 2 != 0 || rgba.len() != w as usize * h as usize * 4 {
            return;
        }
        self.clock = Some((ts, Instant::now()));
        let resized = !matches!(&self.encoder, Some((ew, eh, _)) if *ew == w && *eh == h);
        if resized {
            self.close(ts);
            match new_encoder(w, h, self.fps) {
                Some(e) => self.encoder = Some((w, h, e)),
                None => {
                    self.out.status("cast.error=encoder");
                    return;
                }
            }
        }
        let boundary = match &self.building {
            Some(b) => ts >= b.seg.start_ms + SEGMENT_MS,
            None => true,
        };
        if boundary {
            self.close(ts);
            self.building = Some(Building {
                seg: Segment {
                    seq: self.seq,
                    start_ms: ts,
                    width: w,
                    height: h,
                    fps: self.fps,
                    audio_rate: self.audio_rate,
                    ..Segment::default()
                },
            });
        }
        let Some((_, _, encoder)) = self.encoder.as_mut() else {
            return;
        };
        if boundary {
            encoder.force_intra_frame();
        }
        let t0 = Instant::now();
        let (wu, hu) = (w as usize, h as usize);
        yuv::rgba_to_i420(rgba, wu, hu, &mut self.yuv);
        let (yp, rest) = self.yuv.split_at(wu * hu);
        let (up, vp) = rest.split_at(wu * hu / 4);
        let source = YUVSlices::new((yp, up, vp), (wu, hu), (wu, wu / 2, wu / 2));
        let data = match encoder.encode_at(&source, Timestamp::from_millis(ts as u64)) {
            Ok(bits) => bits.to_vec(),
            Err(e) => {
                self.out.status(&format!("cast.error=encode {e}"));
                return;
            }
        };
        let ms = t0.elapsed().as_secs_f64() * 1000.0;
        self.encode_ms = if self.encoded == 0 { ms } else { self.encode_ms * 0.95 + ms * 0.05 };
        self.encoded += 1;
        if data.is_empty() {
            self.skipped_by_rc += 1;
        } else if let Some(b) = self.building.as_mut() {
            self.bytes_this_seg += data.len();
            b.seg.video.push(VideoUnit { ts_ms: ts, data });
        }
        if !self.fallback_sent && self.encoded > 90 && self.encode_ms > SLOW_ENCODE_MS && (w, h) != FALLBACK_SIZE {
            self.fallback_sent = true;
            self.out.status(&format!("cast.size={}x{}", FALLBACK_SIZE.0, FALLBACK_SIZE.1));
            session_log::log("live", &format!("encode {:.1} ms at {w}x{h}, asking for {:?}", self.encode_ms, FALLBACK_SIZE));
        }
    }

    fn audio(&mut self, ts: u32, rate: u32, pcm: &[i16]) {
        if !(8000..=48000).contains(&rate) || pcm.is_empty() {
            return;
        }
        if rate != self.audio_rate {
            self.audio_rate = rate;
            self.adpcm = adpcm::Encoder::default();
            if let Some(b) = self.building.as_mut() {
                b.seg.audio_rate = rate;
            }
        }
        for (i, chunk) in pcm.chunks(AUDIO_BLOCK).enumerate() {
            let offset = (i * AUDIO_BLOCK) as u64 * 1000 / rate as u64;
            self.audio.push_back(AudioBlock {
                ts_ms: ts + offset as u32,
                samples: chunk.len() as u32,
                data: self.adpcm.block(chunk),
            });
        }
        if self.clock.is_none() {
            self.clock = Some((ts, Instant::now()));
        }
    }

    fn close(&mut self, end: u32) {
        let Some(mut b) = self.building.take() else {
            return;
        };
        b.seg.dur_ms = end.saturating_sub(b.seg.start_ms).clamp(1, SEGMENT_MS * 2);
        let until = b.seg.start_ms + b.seg.dur_ms;
        while self.audio.front().is_some_and(|a| a.ts_ms < until) {
            if let Some(a) = self.audio.pop_front() {
                b.seg.audio.push(a);
            }
        }
        let frames = b.seg.video.len();
        let bytes = b.seg.to_bytes();
        let kbps = self.bytes_this_seg as u64 * 8 / b.seg.dur_ms.max(1) as u64;
        self.bytes_this_seg = 0;
        let seq = b.seg.seq;
        self.seq += 1;
        if self.upload.try_send(Upload::Segment(seq, bytes)).is_err() {
            self.dropped_busy += 1;
        }
        self.out.status(&format!(
            "cast.state=live\ncast.seq={seq}\ncast.frames={frames}\ncast.encodeMs={:.1}\ncast.kbps={kbps}\ncast.size={}x{}\ncast.rcSkipped={}\ncast.busy={}\ncast.dropped={}",
            self.encode_ms, b.seg.width, b.seg.height, self.skipped_by_rc, self.busy.load(Ordering::Relaxed), self.dropped_busy
        ));
    }
}

enum Upload {
    Segment(u32, Vec<u8>),
    End,
}

fn upload_loop(base: String, token: String, stream: String, rx: Receiver<Upload>, out: Outbox) {
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(r) => r,
        Err(e) => {
            out.status(&format!("cast.error=runtime {e}"));
            return;
        }
    };
    let relay = Relay::new(base);
    let mut queue: VecDeque<(u32, Vec<u8>)> = VecDeque::new();
    let mut ending = false;
    let mut skipped = 0u64;
    let mut refused = false;
    loop {
        if queue.is_empty() && !ending {
            match rx.recv() {
                Ok(Upload::Segment(seq, bytes)) => queue.push_back((seq, bytes)),
                Ok(Upload::End) | Err(_) => ending = true,
            }
        }
        while let Ok(next) = rx.try_recv() {
            match next {
                Upload::Segment(seq, bytes) => queue.push_back((seq, bytes)),
                Upload::End => ending = true,
            }
        }
        while queue.len() > MAX_BACKLOG {
            queue.pop_front();
            skipped += 1;
        }
        let Some((seq, bytes)) = queue.pop_front() else {
            if ending {
                break;
            }
            continue;
        };
        if refused {
            continue;
        }
        let started = Instant::now();
        let mut done = false;
        for attempt in 0..3u32 {
            match runtime.block_on(relay.put_segment(&stream, seq, &token, bytes.clone())) {
                Ok(()) => {
                    done = true;
                    break;
                }
                Err(crate::error::Error::Http { status: 403, .. }) => {
                    refused = true;
                    out.status("cast.error=token refused by the relay");
                    break;
                }
                Err(e) => {
                    session_log::log("live", &format!("upload {seq} attempt {attempt}: {e}"));
                    std::thread::sleep(Duration::from_millis(500 << attempt));
                }
            }
        }
        if done {
            out.status(&format!(
                "cast.uploaded={seq}\ncast.uploadMs={}\ncast.backlog={}\ncast.skipped={skipped}",
                started.elapsed().as_millis(),
                queue.len()
            ));
        } else if !refused {
            out.status(&format!("cast.error=upload {seq} failed"));
        }
    }
    if !refused {
        if let Err(e) = runtime.block_on(relay.end(&stream, &token)) {
            session_log::log("live", &format!("end: {e}"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bitrate_scales_with_the_picture() {
        assert_eq!(bitrate_for(1280, 720), 3_000_000);
        assert_eq!(bitrate_for(960, 540), 1_687_500);
        assert_eq!(bitrate_for(160, 90), 500_000);
        assert_eq!(bitrate_for(3840, 2160), 4_000_000);
    }
}
