const STEPS: [i32; 89] = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97,
    107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
    876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871,
    5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
    27086, 29794, 32767,
];
const INDEX_STEP: [i32; 16] = [-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8];
pub const HEADER: usize = 4;

#[derive(Default, Clone, Copy)]
struct State {
    predictor: i32,
    index: i32,
}

impl State {
    fn apply(&mut self, nibble: u8) -> i16 {
        let step = STEPS[self.index as usize];
        let mut diff = step >> 3;
        if nibble & 4 != 0 {
            diff += step;
        }
        if nibble & 2 != 0 {
            diff += step >> 1;
        }
        if nibble & 1 != 0 {
            diff += step >> 2;
        }
        if nibble & 8 != 0 {
            self.predictor -= diff;
        } else {
            self.predictor += diff;
        }
        self.predictor = self.predictor.clamp(-32768, 32767);
        self.index = (self.index + INDEX_STEP[nibble as usize]).clamp(0, 88);
        self.predictor as i16
    }

    fn encode(&mut self, sample: i16) -> u8 {
        let step = STEPS[self.index as usize];
        let mut diff = sample as i32 - self.predictor;
        let mut nibble = 0u8;
        if diff < 0 {
            nibble = 8;
            diff = -diff;
        }
        if diff >= step {
            nibble |= 4;
            diff -= step;
        }
        if diff >= step >> 1 {
            nibble |= 2;
            diff -= step >> 1;
        }
        if diff >= step >> 2 {
            nibble |= 1;
        }
        self.apply(nibble);
        nibble
    }
}

/// Carries its state across blocks for quality, but stamps it into every block header, so each
/// block decodes on its own.
#[derive(Default)]
pub struct Encoder {
    state: State,
}

impl Encoder {
    pub fn block(&mut self, pcm: &[i16]) -> Vec<u8> {
        let mut out = Vec::with_capacity(HEADER + pcm.len().div_ceil(2));
        out.extend_from_slice(&(self.state.predictor as i16).to_le_bytes());
        out.push(self.state.index as u8);
        out.push(0);
        for pair in pcm.chunks(2) {
            let lo = self.state.encode(pair[0]);
            let hi = if pair.len() > 1 { self.state.encode(pair[1]) } else { 0 };
            out.push(lo | (hi << 4));
        }
        out
    }
}

pub fn decode(block: &[u8], samples: usize) -> Option<Vec<i16>> {
    if block.len() < HEADER || block.len() - HEADER < samples.div_ceil(2) || block[2] > 88 {
        return None;
    }
    let mut state = State {
        predictor: i16::from_le_bytes([block[0], block[1]]) as i32,
        index: block[2] as i32,
    };
    let mut out = Vec::with_capacity(samples);
    for i in 0..samples {
        let byte = block[HEADER + i / 2];
        let nibble = if i % 2 == 0 { byte & 15 } else { byte >> 4 };
        out.push(state.apply(nibble));
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(n: usize, start: usize) -> Vec<i16> {
        (start..start + n)
            .map(|i| ((i as f64 * 2.0 * std::f64::consts::PI * 440.0 / 24000.0).sin() * 12000.0) as i16)
            .collect()
    }

    fn snr_db(a: &[i16], b: &[i16]) -> f64 {
        let signal: f64 = a.iter().map(|&s| (s as f64).powi(2)).sum();
        let noise: f64 = a.iter().zip(b).map(|(&x, &y)| (x as f64 - y as f64).powi(2)).sum();
        10.0 * (signal / noise.max(1.0)).log10()
    }

    #[test]
    fn voice_survives_at_a_usable_quality_and_blocks_decode_alone() {
        let mut enc = Encoder::default();
        let mut all_in = Vec::new();
        let mut blocks = Vec::new();
        for b in 0..20 {
            let pcm = sine(480, b * 480);
            blocks.push(enc.block(&pcm));
            all_in.extend(pcm);
        }
        let mut all_out = Vec::new();
        for block in &blocks {
            all_out.extend(decode(block, 480).unwrap());
        }
        assert!(snr_db(&all_in[480..], &all_out[480..]) > 20.0);
        let alone = decode(&blocks[13], 480).unwrap();
        assert_eq!(alone, all_out[13 * 480..14 * 480]);
        assert_eq!(blocks[0].len(), HEADER + 240);
    }

    #[test]
    fn odd_lengths_and_garbage_are_handled() {
        let mut enc = Encoder::default();
        let block = enc.block(&sine(7, 0));
        assert_eq!(decode(&block, 7).unwrap().len(), 7);
        assert!(decode(&block, 9).is_none());
        assert!(decode(&[0, 0, 200, 0, 1], 2).is_none());
        assert!(decode(&[0], 0).is_none());
    }
}
