pub const MAGIC: &[u8; 4] = b"PLZL";
pub const VERSION: u8 = 1;
pub const AUDIO_IMA_ADPCM: u8 = 1;
pub const MAX_BYTES: usize = 4 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq)]
pub struct VideoUnit {
    pub ts_ms: u32,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct AudioBlock {
    pub ts_ms: u32,
    pub samples: u32,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct Segment {
    pub seq: u32,
    pub start_ms: u32,
    pub dur_ms: u32,
    pub width: u16,
    pub height: u16,
    pub fps: u16,
    pub audio_rate: u32,
    pub video: Vec<VideoUnit>,
    pub audio: Vec<AudioBlock>,
}

impl Segment {
    pub fn to_bytes(&self) -> Vec<u8> {
        let size: usize = self.video.iter().map(|v| v.data.len() + 8).sum::<usize>()
            + self.audio.iter().map(|a| a.data.len() + 12).sum::<usize>()
            + 40;
        let mut out = Vec::with_capacity(size);
        out.extend_from_slice(MAGIC);
        out.push(VERSION);
        out.extend_from_slice(&self.seq.to_le_bytes());
        out.extend_from_slice(&self.start_ms.to_le_bytes());
        out.extend_from_slice(&self.dur_ms.to_le_bytes());
        out.extend_from_slice(&self.width.to_le_bytes());
        out.extend_from_slice(&self.height.to_le_bytes());
        out.extend_from_slice(&self.fps.to_le_bytes());
        out.extend_from_slice(&self.audio_rate.to_le_bytes());
        out.push(AUDIO_IMA_ADPCM);
        out.extend_from_slice(&(self.video.len() as u32).to_le_bytes());
        for v in &self.video {
            out.extend_from_slice(&v.ts_ms.to_le_bytes());
            out.extend_from_slice(&(v.data.len() as u32).to_le_bytes());
            out.extend_from_slice(&v.data);
        }
        out.extend_from_slice(&(self.audio.len() as u32).to_le_bytes());
        for a in &self.audio {
            out.extend_from_slice(&a.ts_ms.to_le_bytes());
            out.extend_from_slice(&a.samples.to_le_bytes());
            out.extend_from_slice(&(a.data.len() as u32).to_le_bytes());
            out.extend_from_slice(&a.data);
        }
        out
    }

    pub fn parse(bytes: &[u8]) -> Option<Segment> {
        if bytes.len() > MAX_BYTES {
            return None;
        }
        let mut r = Cursor { bytes, at: 0 };
        if r.take(4)? != MAGIC || r.u8()? != VERSION {
            return None;
        }
        let mut seg = Segment {
            seq: r.u32()?,
            start_ms: r.u32()?,
            dur_ms: r.u32()?,
            width: r.u16()?,
            height: r.u16()?,
            fps: r.u16()?,
            audio_rate: r.u32()?,
            ..Segment::default()
        };
        if r.u8()? != AUDIO_IMA_ADPCM {
            return None;
        }
        let videos = r.u32()? as usize;
        if videos > bytes.len() / 8 {
            return None;
        }
        for _ in 0..videos {
            let ts_ms = r.u32()?;
            let len = r.u32()? as usize;
            seg.video.push(VideoUnit { ts_ms, data: r.take(len)?.to_vec() });
        }
        let audios = r.u32()? as usize;
        if audios > bytes.len() / 12 {
            return None;
        }
        for _ in 0..audios {
            let ts_ms = r.u32()?;
            let samples = r.u32()?;
            let len = r.u32()? as usize;
            seg.audio.push(AudioBlock { ts_ms, samples, data: r.take(len)?.to_vec() });
        }
        (r.at == bytes.len()).then_some(seg)
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.at.checked_add(n)?;
        let out = self.bytes.get(self.at..end)?;
        self.at = end;
        Some(out)
    }
    fn u8(&mut self) -> Option<u8> {
        Some(self.take(1)?[0])
    }
    fn u16(&mut self) -> Option<u16> {
        Some(u16::from_le_bytes(self.take(2)?.try_into().ok()?))
    }
    fn u32(&mut self) -> Option<u32> {
        Some(u32::from_le_bytes(self.take(4)?.try_into().ok()?))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Segment {
        Segment {
            seq: 7,
            start_ms: 14000,
            dur_ms: 2000,
            width: 1280,
            height: 720,
            fps: 30,
            audio_rate: 24000,
            video: vec![
                VideoUnit { ts_ms: 14000, data: vec![0, 0, 0, 1, 0x67, 1, 2] },
                VideoUnit { ts_ms: 14033, data: vec![0, 0, 0, 1, 0x41] },
            ],
            audio: vec![AudioBlock { ts_ms: 14005, samples: 480, data: vec![1; 244] }],
        }
    }

    #[test]
    fn round_trips() {
        let seg = sample();
        assert_eq!(Segment::parse(&seg.to_bytes()), Some(seg));
        let empty = Segment::default();
        assert_eq!(Segment::parse(&empty.to_bytes()), Some(empty));
    }

    #[test]
    fn truncated_padded_or_foreign_bytes_are_refused() {
        let bytes = sample().to_bytes();
        for cut in [0, 3, 5, 30, bytes.len() - 1] {
            assert!(Segment::parse(&bytes[..cut]).is_none(), "cut at {cut}");
        }
        let mut padded = bytes.clone();
        padded.push(0);
        assert!(Segment::parse(&padded).is_none());
        let mut foreign = bytes.clone();
        foreign[0] = b'X';
        assert!(Segment::parse(&foreign).is_none());
        let mut huge_count = bytes;
        huge_count[29..33].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(Segment::parse(&huge_count).is_none());
    }
}
