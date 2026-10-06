use std::io::{self, Read, Write};

pub const VERSION: u16 = 1;
pub const MAX_MESSAGE: usize = 32 * 1024 * 1024;

pub const HELLO: u8 = 1;
pub const CAST_START: u8 = 2;
pub const CAST_VIDEO: u8 = 3;
pub const CAST_AUDIO: u8 = 4;
pub const CAST_STOP: u8 = 5;
pub const WATCH: u8 = 6;
pub const UNWATCH: u8 = 7;

pub const HELLO_OK: u8 = 65;
pub const FRAME: u8 = 66;
pub const AUDIO: u8 = 67;
pub const STATUS: u8 = 68;

pub fn message(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(payload.len() + 5);
    out.push(kind);
    out.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    out.extend_from_slice(payload);
    out
}

pub fn read_message(r: &mut impl Read) -> io::Result<(u8, Vec<u8>)> {
    let mut head = [0u8; 5];
    r.read_exact(&mut head)?;
    let len = u32::from_be_bytes([head[1], head[2], head[3], head[4]]) as usize;
    if len > MAX_MESSAGE {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "message too large"));
    }
    let mut payload = vec![0u8; len];
    r.read_exact(&mut payload)?;
    Ok((head[0], payload))
}

pub fn write_message(w: &mut impl Write, kind: u8, payload: &[u8]) -> io::Result<()> {
    w.write_all(&message(kind, payload))
}

#[derive(Default)]
pub struct Builder(pub Vec<u8>);

impl Builder {
    pub fn u16(mut self, v: u16) -> Self {
        self.0.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn u32(mut self, v: u32) -> Self {
        self.0.extend_from_slice(&v.to_be_bytes());
        self
    }
    pub fn text(mut self, v: &str) -> Self {
        let bytes = &v.as_bytes()[..v.len().min(255)];
        self.0.push(bytes.len() as u8);
        self.0.extend_from_slice(bytes);
        self
    }
    pub fn bytes(mut self, v: &[u8]) -> Self {
        self.0.extend_from_slice(v);
        self
    }
    pub fn pcm(mut self, v: &[i16]) -> Self {
        for s in v {
            self.0.extend_from_slice(&s.to_be_bytes());
        }
        self
    }
}

pub struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    pub fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, at: 0 }
    }
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.at.checked_add(n)?;
        let out = self.bytes.get(self.at..end)?;
        self.at = end;
        Some(out)
    }
    pub fn u16(&mut self) -> Option<u16> {
        Some(u16::from_be_bytes(self.take(2)?.try_into().ok()?))
    }
    pub fn u32(&mut self) -> Option<u32> {
        Some(u32::from_be_bytes(self.take(4)?.try_into().ok()?))
    }
    pub fn text(&mut self) -> Option<String> {
        let len = self.take(1)?[0] as usize;
        String::from_utf8(self.take(len)?.to_vec()).ok()
    }
    pub fn rest(&mut self) -> &'a [u8] {
        let out = &self.bytes[self.at..];
        self.at = self.bytes.len();
        out
    }
    pub fn pcm(&mut self) -> Vec<i16> {
        self.rest().chunks_exact(2).map(|c| i16::from_be_bytes([c[0], c[1]])).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn messages_and_fields_round_trip() {
        let payload = Builder::default().text("abc").u16(1280).u32(99).pcm(&[-2, 300]).0;
        let wire = message(FRAME, &payload);
        let (kind, got) = read_message(&mut &wire[..]).unwrap();
        assert_eq!(kind, FRAME);
        let mut r = Reader::new(&got);
        assert_eq!(r.text().as_deref(), Some("abc"));
        assert_eq!(r.u16(), Some(1280));
        assert_eq!(r.u32(), Some(99));
        assert_eq!(r.pcm(), vec![-2, 300]);
        assert_eq!(r.u16(), None);
    }

    #[test]
    fn an_oversized_length_is_refused_before_allocating() {
        let mut wire = vec![CAST_VIDEO];
        wire.extend_from_slice(&u32::MAX.to_be_bytes());
        assert!(read_message(&mut &wire[..]).is_err());
    }
}
