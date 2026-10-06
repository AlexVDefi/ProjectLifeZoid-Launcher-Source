use std::collections::HashMap;
use std::io::{self, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::cast::Cast;
use super::outbox::Outbox;
use super::proto::{self, Builder, Reader};
use super::relay;
use super::watch::Watch;
use crate::session_log;

const MAX_WATCHES: usize = 4;

type Active = Arc<Mutex<Option<TcpStream>>>;

/// Binds 127.0.0.1 on a free port and serves one game at a time; a newer connection that knows
/// the key replaces the older one.
pub fn serve(stop: Arc<AtomicBool>, key: String) -> io::Result<u16> {
    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    let port = listener.local_addr()?.port();
    listener.set_nonblocking(true)?;
    let active: Active = Arc::new(Mutex::new(None));
    std::thread::Builder::new().name("live-link".into()).spawn(move || {
        while !stop.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((conn, _)) => {
                    let (key, active) = (key.clone(), active.clone());
                    let _ = std::thread::Builder::new()
                        .name("live-session".into())
                        .spawn(move || session(conn, &key, &active));
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(100)),
                Err(e) => {
                    session_log::log("live", &format!("accept: {e}"));
                    std::thread::sleep(Duration::from_millis(500));
                }
            }
        }
        if let Some(old) = active.lock().ok().and_then(|mut a| a.take()) {
            let _ = old.shutdown(Shutdown::Both);
        }
    })?;
    Ok(port)
}

fn same(a: &str, b: &str) -> bool {
    a.len() == b.len() && a.bytes().zip(b.bytes()).fold(0u8, |d, (x, y)| d | (x ^ y)) == 0
}

fn session(mut conn: TcpStream, key: &str, active: &Active) {
    let _ = conn.set_nonblocking(false);
    let _ = conn.set_nodelay(true);
    let _ = conn.set_read_timeout(Some(Duration::from_secs(5)));
    let hello = match proto::read_message(&mut conn) {
        Ok((proto::HELLO, payload)) => payload,
        _ => return,
    };
    let mut r = Reader::new(&hello);
    let (Some(given), Some(_version)) = (r.text(), r.u16()) else {
        return;
    };
    if !same(&given, key) {
        session_log::log("live", "a local connection gave the wrong key");
        return;
    }
    let _ = conn.set_read_timeout(None);
    if proto::write_message(&mut conn, proto::HELLO_OK, &Builder::default().u16(proto::VERSION).0).is_err() {
        return;
    }
    if let Ok(mut slot) = active.lock() {
        if let Some(old) = slot.take() {
            let _ = old.shutdown(Shutdown::Both);
        }
        *slot = conn.try_clone().ok();
    }

    let (tx, rx) = mpsc::sync_channel::<Vec<u8>>(8);
    let out = Outbox::new(tx);
    if let Ok(mut writer) = conn.try_clone() {
        let _ = std::thread::Builder::new().name("live-write".into()).spawn(move || {
            for msg in rx {
                if writer.write_all(&msg).is_err() {
                    break;
                }
            }
        });
    }
    let mut state = Session { cast: None, watches: HashMap::new(), out };
    match relay::base_url() {
        Some(base) => state.out.status(&format!("link.relay={base}")),
        None => state.out.status("link.relay=none"),
    }
    while let Ok((kind, payload)) = proto::read_message(&mut conn) {
        state.handle(kind, payload);
    }
    state.close();
}

struct Session {
    cast: Option<Cast>,
    watches: HashMap<String, Watch>,
    out: Outbox,
}

fn in_background(name: &str, f: impl FnOnce() + Send + 'static) {
    let _ = std::thread::Builder::new().name(name.into()).spawn(f);
}

impl Session {
    fn handle(&mut self, kind: u8, mut payload: Vec<u8>) {
        match kind {
            proto::CAST_START => {
                let mut r = Reader::new(&payload);
                let (Some(_w), Some(_h), Some(fps), Some(token)) = (r.u16(), r.u16(), r.u16(), r.text()) else {
                    return;
                };
                if let Some(old) = self.cast.take() {
                    in_background("live-cast-stop", move || old.stop());
                }
                let Some(stream) = relay::stream_of(&token).map(str::to_string) else {
                    self.out.status("cast.error=bad token");
                    return;
                };
                let Some(base) = relay::base_url() else {
                    self.out.status("cast.error=no relay configured");
                    return;
                };
                self.cast = Some(Cast::start(base, token, stream, fps, self.out.clone()));
            }
            proto::CAST_VIDEO => {
                if payload.len() < 8 {
                    return;
                }
                let rgba = payload.split_off(8);
                let mut r = Reader::new(&payload);
                let (Some(ts), Some(w), Some(h)) = (r.u32(), r.u16(), r.u16()) else {
                    return;
                };
                if let Some(cast) = &self.cast {
                    cast.video(ts, w, h, rgba);
                }
            }
            proto::CAST_AUDIO => {
                let mut r = Reader::new(&payload);
                let (Some(ts), Some(rate)) = (r.u32(), r.u32()) else {
                    return;
                };
                let pcm = r.pcm();
                if let Some(cast) = &self.cast {
                    cast.audio(ts, rate, pcm);
                }
            }
            proto::CAST_STOP => {
                if let Some(old) = self.cast.take() {
                    in_background("live-cast-stop", move || old.stop());
                }
            }
            proto::WATCH => {
                let Some(stream) = Reader::new(&payload).text() else {
                    return;
                };
                if !relay::valid_stream(&stream) {
                    return;
                }
                self.watches.retain(|_, w| !w.finished());
                if self.watches.contains_key(&stream) || self.watches.len() >= MAX_WATCHES {
                    return;
                }
                let Some(base) = relay::base_url() else {
                    self.out.status(&format!("watch.{stream}=error no relay configured"));
                    return;
                };
                let watch = Watch::start(base, stream.clone(), self.out.clone());
                self.watches.insert(stream, watch);
            }
            proto::UNWATCH => {
                let Some(stream) = Reader::new(&payload).text() else {
                    return;
                };
                if let Some(w) = self.watches.remove(&stream) {
                    in_background("live-watch-stop", move || w.stop());
                }
            }
            _ => {}
        }
    }

    fn close(&mut self) {
        if let Some(cast) = self.cast.take() {
            in_background("live-cast-stop", move || cast.stop());
        }
        for (_, w) in self.watches.drain() {
            in_background("live-watch-stop", move || w.stop());
        }
    }
}
