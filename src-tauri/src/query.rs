use crate::error::{Error, Result};
use serde::Serialize;
use std::collections::BTreeMap;
use std::time::Duration;
use tokio::net::UdpSocket;
use tokio::time::timeout;

const HEADER: [u8; 4] = [0xFF, 0xFF, 0xFF, 0xFF];
const A2S_INFO: u8 = 0x54;
const A2S_RULES: u8 = 0x56;
const S2C_CHALLENGE: u8 = 0x41;
const S2A_INFO: u8 = 0x49;
const S2A_RULES: u8 = 0x45;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    pub online: bool,
    pub name: String,
    pub map: String,
    pub players: u8,
    pub max_players: u8,
    pub version: String,
    pub ping_ms: u64,
    pub rules: BTreeMap<String, String>,
}

struct Reader<'a> {
    b: &'a [u8],
    o: usize,
}

impl<'a> Reader<'a> {
    fn new(b: &'a [u8]) -> Self {
        Reader { b, o: 0 }
    }
    fn u8(&mut self) -> Result<u8> {
        let v = *self
            .b
            .get(self.o)
            .ok_or_else(|| Error::Query("short packet".into()))?;
        self.o += 1;
        Ok(v)
    }
    fn u16(&mut self) -> Result<u16> {
        if self.o + 2 > self.b.len() {
            return Err(Error::Query("short packet".into()));
        }
        let v = u16::from_le_bytes([self.b[self.o], self.b[self.o + 1]]);
        self.o += 2;
        Ok(v)
    }
    fn cstr(&mut self) -> Result<String> {
        let start = self.o;
        while self.o < self.b.len() && self.b[self.o] != 0 {
            self.o += 1;
        }
        let s = String::from_utf8_lossy(&self.b[start..self.o]).into_owned();
        self.o += 1;
        Ok(s)
    }
    fn remaining(&self) -> usize {
        self.b.len().saturating_sub(self.o)
    }
}

async fn exchange(sock: &UdpSocket, payload: &[u8], per_packet: Duration) -> Result<Vec<u8>> {
    sock.send(payload)
        .await
        .map_err(|e| Error::Query(e.to_string()))?;

    let mut parts: BTreeMap<u8, Vec<u8>> = BTreeMap::new();
    let mut expected: Option<u8> = None;
    let mut buf = vec![0u8; 4096];

    loop {
        let n = timeout(per_packet, sock.recv(&mut buf))
            .await
            .map_err(|_| Error::Query("timed out".into()))?
            .map_err(|e| Error::Query(e.to_string()))?;
        if n < 5 {
            continue;
        }
        let head = i32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        if head == -2 {
            if n < 12 {
                continue;
            }
            let total = buf[8];
            let number = buf[9];
            expected = Some(total);
            parts.insert(number, buf[12..n].to_vec());
            if parts.len() == total as usize {
                let mut joined: Vec<u8> = parts.values().flatten().copied().collect();
                if joined.len() >= 4 {
                    joined.drain(..4);
                }
                return Ok(joined);
            }
            continue;
        }
        if expected.is_some() {
            continue;
        }
        return Ok(buf[4..n].to_vec());
    }
}

fn info_request() -> Vec<u8> {
    let mut v = HEADER.to_vec();
    v.push(A2S_INFO);
    v.extend_from_slice(b"Source Engine Query\0");
    v
}

async fn query_kind(sock: &UdpSocket, kind: u8, per_packet: Duration) -> Result<Vec<u8>> {
    let first = if kind == A2S_INFO {
        info_request()
    } else {
        let mut v = HEADER.to_vec();
        v.push(kind);
        v.extend_from_slice(&[0xFF, 0xFF, 0xFF, 0xFF]);
        v
    };

    let body = exchange(sock, &first, per_packet).await?;
    if body.first() == Some(&S2C_CHALLENGE) && body.len() >= 5 {
        let challenge = &body[1..5];
        let mut retry = if kind == A2S_INFO {
            info_request()
        } else {
            let mut v = HEADER.to_vec();
            v.push(kind);
            v
        };
        retry.extend_from_slice(challenge);
        return exchange(sock, &retry, per_packet).await;
    }
    Ok(body)
}

pub async fn query(host: &str, port: u16, timeout_ms: u64) -> Result<ServerStatus> {
    let started = std::time::Instant::now();
    let sock = UdpSocket::bind("0.0.0.0:0")
        .await
        .map_err(|e| Error::Query(e.to_string()))?;
    sock.connect((host, port))
        .await
        .map_err(|e| Error::Query(format!("{host}:{port}: {e}")))?;

    let per_packet = Duration::from_millis(timeout_ms);
    let info = query_kind(&sock, A2S_INFO, per_packet).await?;
    let ping_ms = started.elapsed().as_millis() as u64;

    let mut r = Reader::new(&info);
    if r.u8()? != S2A_INFO {
        return Err(Error::Query("unexpected A2S_INFO reply".into()));
    }
    let _protocol = r.u8()?;
    let name = r.cstr()?;
    let map = r.cstr()?;
    let _folder = r.cstr()?;
    let _game = r.cstr()?;
    let _app_id = r.u16()?;
    let players = r.u8()?;
    let max_players = r.u8()?;

    let mut rules = BTreeMap::new();
    if let Ok(body) = query_kind(&sock, A2S_RULES, per_packet).await {
        let mut rr = Reader::new(&body);
        if matches!(rr.u8(), Ok(S2A_RULES)) {
            if let Ok(count) = rr.u16() {
                for _ in 0..count {
                    if rr.remaining() == 0 {
                        break;
                    }
                    match (rr.cstr(), rr.cstr()) {
                        (Ok(k), Ok(v)) => {
                            rules.insert(k, v);
                        }
                        _ => break,
                    }
                }
            }
        }
    }

    let version = rules.get("version").cloned().unwrap_or_default();

    Ok(ServerStatus {
        online: true,
        name,
        map,
        players,
        max_players,
        version,
        ping_ms,
        rules,
    })
}
