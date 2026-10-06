use std::io::Write;
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use app_lib::error::{Error, Result};
use app_lib::live::proto::{self, Builder, Reader};
use app_lib::live::{new_key, serve, token};

const W: usize = 1280;
const H: usize = 720;
const FPS: u64 = 30;
const RATE: u32 = 24000;
const BITS: usize = 16;
const BIT_PX: usize = 40;

fn other(e: impl std::fmt::Display) -> Error {
    Error::Other(e.to_string())
}

fn connect(port: u16, key: &str) -> Result<TcpStream> {
    let mut conn = TcpStream::connect(("127.0.0.1", port)).map_err(other)?;
    conn.set_nodelay(true).map_err(other)?;
    proto::write_message(&mut conn, proto::HELLO, &Builder::default().text(key).u16(proto::VERSION).0).map_err(other)?;
    match proto::read_message(&mut conn).map_err(other)? {
        (proto::HELLO_OK, _) => Ok(conn),
        (kind, _) => Err(other(format!("expected HELLO_OK, got {kind}"))),
    }
}

fn texture() -> Vec<u8> {
    let tw = W * 2;
    let mut t = vec![0u8; tw * H * 4];
    for y in 0..H {
        for x in 0..tw {
            let tile = (((x / 64) + (y / 64)) % 2) as i32 * 60;
            let base = 70 + tile + ((x as f32 / 40.0).sin() * 30.0) as i32 + ((x * 7 + y * 13) % 17) as i32;
            let i = (y * tw + x) * 4;
            t[i] = base.clamp(0, 255) as u8;
            t[i + 1] = (base + 20).clamp(0, 255) as u8;
            t[i + 2] = (base - 10).clamp(0, 255) as u8;
            t[i + 3] = 255;
        }
    }
    t
}

fn draw_frame(tex: &[u8], idx: u64, out: &mut [u8]) {
    let off = (idx as usize * 4) % W;
    for y in 0..H {
        let src = (y * W * 2 + off) * 4;
        out[y * W * 4..(y + 1) * W * 4].copy_from_slice(&tex[src..src + W * 4]);
    }
    for bit in 0..BITS {
        let v = if (idx >> bit) & 1 == 1 { 255 } else { 0 };
        for y in 0..BIT_PX {
            for x in bit * BIT_PX..(bit + 1) * BIT_PX {
                let i = (y * W + x) * 4;
                out[i..i + 3].fill(v);
            }
        }
    }
}

fn read_index(rgba: &[u8], w: usize) -> u64 {
    let mut idx = 0u64;
    for bit in 0..BITS {
        let (cx, cy) = (bit * BIT_PX + BIT_PX / 2, BIT_PX / 2);
        let mut sum = 0u32;
        for y in cy - 8..cy + 8 {
            for x in cx - 8..cx + 8 {
                sum += rgba[(y * w + x) * 4 + 1] as u32;
            }
        }
        if sum / 256 > 128 {
            idx |= 1 << bit;
        }
    }
    idx
}

fn tone(start: u64, n: usize) -> Vec<i16> {
    (0..n as u64)
        .map(|i| {
            let s = start + i;
            let rate = RATE as u64;
            let in_burst = s % rate < rate / 10;
            let (freq, amp) = if in_burst { (1000.0, 28000.0) } else { (440.0, 3000.0) };
            ((s as f64 * 2.0 * std::f64::consts::PI * freq / RATE as f64).sin() * amp) as i16
        })
        .collect()
}

fn mean(v: &[f64]) -> f64 {
    if v.is_empty() { 0.0 } else { v.iter().sum::<f64>() / v.len() as f64 }
}

/// A link server alone, for driving the game-side Java from a test JVM.
pub fn serve_only(args: &[String]) -> Result<()> {
    let path = args.first().ok_or_else(|| other("usage: plzctl live-serve <link-file> [seconds]"))?;
    let seconds: u64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(60);
    let stop = Arc::new(AtomicBool::new(false));
    let key = new_key();
    let port = serve(stop.clone(), key.clone()).map_err(other)?;
    let path = std::path::Path::new(path);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(other)?;
    }
    std::fs::write(path, format!("port={port}\r\nkey={key}\r\n")).map_err(other)?;
    println!("link on {port}, written to {}", path.display());
    std::thread::sleep(Duration::from_secs(seconds));
    stop.store(true, Ordering::Relaxed);
    let _ = std::fs::remove_file(path);
    Ok(())
}

pub fn run(args: &[String]) -> Result<()> {
    let secret = args.first().cloned().ok_or_else(|| other("usage: plzctl live-loopback <relay-secret> [seconds] [join-after-seconds]"))?;
    let seconds: u64 = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(20);
    let join_after: u64 = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(1);
    let relay = std::env::var(app_lib::config::LIVE_RELAY_URL_ENV).unwrap_or_default();
    if relay.is_empty() {
        return Err(other(format!("set {} to the relay, for example http://127.0.0.1:8787", app_lib::config::LIVE_RELAY_URL_ENV)));
    }
    println!("relay {relay}, {seconds} s at {W}x{H} {FPS} fps");

    let stop = Arc::new(AtomicBool::new(false));
    let (cast_key, view_key) = (new_key(), new_key());
    let cast_port = serve(stop.clone(), cast_key.clone()).map_err(other)?;
    let view_port = serve(stop.clone(), view_key.clone()).map_err(other)?;
    let stream = new_key();
    let exp = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) + 3600;
    let tok = token::sign(&secret, &stream, exp);

    let mut cast = connect(cast_port, &cast_key)?;
    proto::write_message(&mut cast, proto::CAST_START, &Builder::default().u16(W as u16).u16(H as u16).u16(FPS as u16).text(&tok).0).map_err(other)?;
    let mut cast_status = cast.try_clone().map_err(other)?;
    std::thread::spawn(move || {
        while let Ok((kind, payload)) = proto::read_message(&mut cast_status) {
            if kind == proto::STATUS {
                let text = String::from_utf8_lossy(&payload).replace('\n', "  ");
                if text.contains("cast.state=live") || text.contains("error") || text.contains("size=960") || text.contains("ended") {
                    println!("  [cast] {text}");
                }
            }
        }
    });

    let sent_at: Arc<Mutex<Vec<Option<Instant>>>> = Arc::new(Mutex::new(vec![None; (seconds * FPS + 10) as usize]));
    let burst_sent: Arc<Mutex<Vec<Instant>>> = Arc::new(Mutex::new(Vec::new()));
    let (sent, bursts) = (sent_at.clone(), burst_sent.clone());
    let broadcaster = std::thread::spawn(move || -> Result<u64> {
        let tex = texture();
        let mut frame = vec![0u8; W * H * 4];
        let start = Instant::now();
        let mut audio_sent: u64 = 0;
        let mut idx: u64 = 0;
        let mut frames = 0u64;
        while start.elapsed() < Duration::from_secs(seconds) {
            let now_ms = start.elapsed().as_millis() as u64;
            while audio_sent * 1000 / RATE as u64 <= now_ms {
                let ts = (audio_sent * 1000 / RATE as u64) as u32;
                if audio_sent % RATE as u64 == 0 {
                    bursts.lock().map_err(other)?.push(Instant::now());
                }
                let pcm = tone(audio_sent, 480);
                proto::write_message(&mut cast, proto::CAST_AUDIO, &Builder::default().u32(ts).u32(RATE).pcm(&pcm).0).map_err(other)?;
                audio_sent += 480;
            }
            if idx * 1000 / FPS <= now_ms {
                draw_frame(&tex, idx, &mut frame);
                let mut msg = Builder::default().u32((idx * 1000 / FPS) as u32).u16(W as u16).u16(H as u16).0;
                msg.extend_from_slice(&frame);
                sent.lock().map_err(other)?[idx as usize] = Some(Instant::now());
                proto::write_message(&mut cast, proto::CAST_VIDEO, &msg).map_err(other)?;
                idx += 1;
                frames += 1;
            }
            std::thread::sleep(Duration::from_millis(2));
        }
        proto::write_message(&mut cast, proto::CAST_STOP, &[]).map_err(other)?;
        cast.flush().map_err(other)?;
        std::thread::sleep(Duration::from_secs(4));
        Ok(frames)
    });

    std::thread::sleep(Duration::from_secs(join_after));
    let mut view = connect(view_port, &view_key)?;
    view.set_read_timeout(Some(Duration::from_secs(1))).map_err(other)?;
    proto::write_message(&mut view, proto::WATCH, &Builder::default().text(&stream).0).map_err(other)?;
    let watch_started = Instant::now();
    let mut first_frame: Option<Duration> = None;
    let mut latencies = Vec::new();
    let mut frames_seen = 0u64;
    let mut wrong = 0u64;
    let mut last_idx: Option<u64> = None;
    let mut out_of_order = 0u64;
    let mut samples = 0u64;
    let mut av = Vec::new();
    let mut burst_heard: Vec<Instant> = Vec::new();
    let mut in_burst = false;
    let mut frame_times: Vec<(u64, Instant)> = Vec::new();
    let deadline = Instant::now() + Duration::from_secs(seconds + 25);
    let mut ended = false;
    while Instant::now() < deadline && !ended {
        let (kind, payload) = match proto::read_message(&mut view) {
            Ok(m) => m,
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => continue,
            Err(e) => return Err(other(e)),
        };
        let mut r = Reader::new(&payload);
        match kind {
            proto::FRAME => {
                let (Some(_), Some(w), Some(_h)) = (r.text(), r.u16(), r.u16()) else { continue };
                let rgba = r.rest();
                let idx = read_index(rgba, w as usize);
                let now = Instant::now();
                first_frame.get_or_insert(watch_started.elapsed());
                frames_seen += 1;
                if last_idx.is_some_and(|l| idx <= l) {
                    out_of_order += 1;
                }
                last_idx = Some(idx);
                match sent_at.lock().map_err(other)?.get(idx as usize).copied().flatten() {
                    Some(t) => latencies.push((now - t).as_secs_f64() * 1000.0),
                    None => wrong += 1,
                }
                frame_times.push((idx, now));
            }
            proto::AUDIO => {
                let (Some(_), Some(_rate)) = (r.text(), r.u32()) else { continue };
                let pcm = r.pcm();
                samples += pcm.len() as u64;
                let loud = pcm.iter().any(|s| s.unsigned_abs() > 20000);
                if loud && !in_burst {
                    burst_heard.push(Instant::now());
                }
                in_burst = loud;
            }
            proto::STATUS => {
                let text = String::from_utf8_lossy(&payload).to_string();
                println!("  [view] {}", text.replace('\n', "  "));
                if text.contains("=ended") {
                    ended = true;
                }
            }
            _ => {}
        }
    }
    let frames_sent = broadcaster.join().map_err(|_| other("broadcaster panicked"))??;
    stop.store(true, Ordering::Relaxed);

    let sent_bursts = burst_sent.lock().map_err(other)?.len();
    let signed = |a: Instant, b: Instant| if a >= b { (a - b).as_secs_f64() * 1000.0 } else { -((b - a).as_secs_f64() * 1000.0) };
    for heard in &burst_heard {
        let nearest = frame_times
            .iter()
            .filter(|(i, _)| i % FPS == 0)
            .min_by(|x, y| signed(x.1, *heard).abs().total_cmp(&signed(y.1, *heard).abs()));
        if let Some((_, shown)) = nearest {
            av.push(signed(*shown, *heard));
        }
    }
    let (min, max) = latencies.iter().fold((f64::MAX, 0.0f64), |(a, b), &l| (a.min(l), b.max(l)));
    println!();
    println!("frames sent {frames_sent}, shown {frames_seen} ({:.1}%), unreadable {wrong}, out of order {out_of_order}",
        frames_seen as f64 * 100.0 / frames_sent.max(1) as f64);
    println!("video delay: mean {:.0} ms, min {:.0}, max {:.0}; first frame {:.1} s after WATCH", mean(&latencies), min, max,
        first_frame.map(|d| d.as_secs_f64()).unwrap_or(-1.0));
    println!("audio: {samples} samples ({:.1} s), bursts heard {} of {sent_bursts}",
        samples as f64 / RATE as f64, burst_heard.len());
    println!("A/V: picture lands {:.0} ms after its sound (target {} ms, the game's audio buffer)", mean(&av), app_lib::live::VIDEO_LEAD_MS);
    Ok(())
}
