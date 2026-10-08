use std::mem::ManuallyDrop;
use std::sync::OnceLock;

use windows::core::{Interface, Result, GUID};
use windows::Win32::Media::MediaFoundation::*;
use windows::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, COINIT_MULTITHREADED};
use windows::Win32::System::Variant::VARIANT;

use super::cast::Encoded;
use super::yuv;

/// Hardware MFTs are async: a frame's output usually lands on the next call, so place it by its timestamp.
pub struct HwEncoder {
    transform: IMFTransform,
    events: IMFMediaEventGenerator,
    credits: u32,
    width: usize,
    height: usize,
    nv12: Vec<u8>,
    frames: u64,
    other_events: Vec<i32>,
    headers: Vec<u8>,
    pub name: String,
}

const STALL: std::time::Duration = std::time::Duration::from_millis(250);

/// The SPS and PPS NAL units of an Annex-B access unit, start codes included, if it carries both.
pub fn parameter_sets(au: &[u8]) -> Option<Vec<u8>> {
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 3 <= au.len() {
        if au[i] == 0 && au[i + 1] == 0 && au[i + 2] == 1 {
            let begin = if i > 0 && au[i - 1] == 0 { i - 1 } else { i };
            starts.push((begin, i + 3));
            i += 3;
        } else {
            i += 1;
        }
    }
    let mut sets = Vec::new();
    let (mut sps, mut pps) = (false, false);
    for (n, &(begin, payload)) in starts.iter().enumerate() {
        let end = starts.get(n + 1).map_or(au.len(), |s| s.0);
        match au.get(payload).map(|b| b & 31) {
            Some(7) => sps = true,
            Some(8) => pps = true,
            _ => continue,
        }
        sets.extend_from_slice(&au[begin..end]);
    }
    (sps && pps).then_some(sets)
}

#[cfg(test)]
mod tests {
    use super::parameter_sets;

    #[test]
    fn parameter_sets_are_lifted_out_of_a_keyframe_and_absent_from_a_bare_slice() {
        let au = [0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9, 9];
        assert_eq!(parameter_sets(&au).unwrap(), vec![0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3]);
        assert_eq!(parameter_sets(&[0, 0, 0, 1, 0x65, 9]), None);
        assert_eq!(parameter_sets(&[0, 0, 0, 1, 0x67, 1]), None);
    }
}

fn started() -> bool {
    static STARTED: OnceLock<bool> = OnceLock::new();
    *STARTED.get_or_init(|| unsafe { MFStartup(MF_VERSION, MFSTARTUP_FULL).is_ok() })
}

pub fn init_thread() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
}

unsafe fn set(codec: &ICodecAPI, api: &GUID, value: VARIANT) {
    let _ = codec.SetValue(api, &value);
}

impl HwEncoder {
    pub fn new(width: u16, height: u16, fps: u16, bps: u32) -> Option<HwEncoder> {
        if !started() {
            return None;
        }
        unsafe { Self::open(width, height, fps, bps).ok() }
    }

    unsafe fn open(width: u16, height: u16, fps: u16, bps: u32) -> Result<HwEncoder> {
        let input = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_NV12 };
        let output = MFT_REGISTER_TYPE_INFO { guidMajorType: MFMediaType_Video, guidSubtype: MFVideoFormat_H264 };
        let mut list: *mut Option<IMFActivate> = std::ptr::null_mut();
        let mut count = 0u32;
        MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER, Some(&input), Some(&output), &mut list, &mut count)?;
        let activates: Vec<Option<IMFActivate>> = std::slice::from_raw_parts_mut(list, count as usize).iter_mut().map(|a| a.take()).collect();
        CoTaskMemFree(Some(list as _));
        let mut last = Err(windows::core::Error::from(MF_E_NOT_FOUND));
        for activate in activates.into_iter().flatten() {
            last = Self::configure(&activate, width, height, fps, bps);
            if last.is_ok() {
                break;
            }
            let _ = activate.ShutdownObject();
        }
        last
    }

    unsafe fn configure(activate: &IMFActivate, width: u16, height: u16, fps: u16, bps: u32) -> Result<HwEncoder> {
        let mut name = String::new();
        let mut raw = windows::core::PWSTR::null();
        let mut len = 0u32;
        if activate.GetAllocatedString(&MFT_FRIENDLY_NAME_Attribute, &mut raw, &mut len).is_ok() {
            name = raw.to_string().unwrap_or_default();
            CoTaskMemFree(Some(raw.0 as _));
        }
        let transform: IMFTransform = activate.ActivateObject()?;
        let attrs = transform.GetAttributes()?;
        attrs.SetUINT32(&MF_TRANSFORM_ASYNC_UNLOCK, 1)?;
        let _ = attrs.SetUINT32(&MF_LOW_LATENCY, 1);
        let size = (width as u64) << 32 | height as u64;
        let rate = (fps as u64) << 32 | 1;

        let out = MFCreateMediaType()?;
        out.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        out.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_H264)?;
        out.SetUINT32(&MF_MT_AVG_BITRATE, bps)?;
        out.SetUINT64(&MF_MT_FRAME_SIZE, size)?;
        out.SetUINT64(&MF_MT_FRAME_RATE, rate)?;
        out.SetUINT64(&MF_MT_PIXEL_ASPECT_RATIO, 1u64 << 32 | 1)?;
        out.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        // openh264, which every viewer decodes with, only takes constrained baseline
        out.SetUINT32(&MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_ConstrainedBase.0 as u32)?;
        transform.SetOutputType(0, &out, 0)?;

        let inp = MFCreateMediaType()?;
        inp.SetGUID(&MF_MT_MAJOR_TYPE, &MFMediaType_Video)?;
        inp.SetGUID(&MF_MT_SUBTYPE, &MFVideoFormat_NV12)?;
        inp.SetUINT64(&MF_MT_FRAME_SIZE, size)?;
        inp.SetUINT64(&MF_MT_FRAME_RATE, rate)?;
        inp.SetUINT32(&MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive.0 as u32)?;
        transform.SetInputType(0, &inp, 0)?;

        let codec: ICodecAPI = transform.cast()?;
        set(&codec, &CODECAPI_AVEncCommonRateControlMode, VARIANT::from(eAVEncCommonRateControlMode_CBR.0 as u32));
        set(&codec, &CODECAPI_AVEncCommonMeanBitRate, VARIANT::from(bps));
        set(&codec, &CODECAPI_AVLowLatencyMode, VARIANT::from(true));
        // keyframes come from the GOP, never ICodecAPI mid-stream: a SetValue racing a pending output deadlocked NVENC
        set(&codec, &CODECAPI_AVEncMPVGOPSize, VARIANT::from((fps as u32 * super::cast::SEGMENT_MS / 1000).max(1)));

        let events: IMFMediaEventGenerator = transform.cast()?;
        transform.ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0)?;
        transform.ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0)?;
        Ok(HwEncoder {
            transform,
            events,
            credits: 0,
            width: width as usize,
            height: height as usize,
            nv12: Vec::new(),
            frames: 0,
            other_events: Vec::new(),
            headers: Vec::new(),
            name,
        })
    }

    unsafe fn take_output(&mut self, out: &mut Vec<Encoded>) -> Result<()> {
        let info = self.transform.GetOutputStreamInfo(0)?;
        let provides = info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES.0 as u32 | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES.0 as u32) != 0;
        let mut mine = None;
        if !provides {
            let s = MFCreateSample()?;
            s.AddBuffer(&MFCreateMemoryBuffer(info.cbSize.max(1024 * 1024))?)?;
            mine = Some(s);
        }
        let mut buffers = [MFT_OUTPUT_DATA_BUFFER { dwStreamID: 0, pSample: ManuallyDrop::new(mine), dwStatus: 0, pEvents: ManuallyDrop::new(None) }];
        let mut status = 0u32;
        let result = self.transform.ProcessOutput(0, &mut buffers, &mut status);
        let sample = ManuallyDrop::take(&mut buffers[0].pSample);
        drop(ManuallyDrop::take(&mut buffers[0].pEvents));
        result?;
        let Some(sample) = sample else {
            return Ok(());
        };
        let ts_ms = (sample.GetSampleTime().unwrap_or(0) / 10_000).max(0) as u32;
        let key = sample.GetUINT32(&MFSampleExtension_CleanPoint).unwrap_or(0) != 0;
        let buffer = sample.ConvertToContiguousBuffer()?;
        let mut ptr = std::ptr::null_mut();
        let mut len = 0u32;
        buffer.Lock(&mut ptr, None, Some(&mut len))?;
        let mut data = std::slice::from_raw_parts(ptr, len as usize).to_vec();
        buffer.Unlock()?;
        if data.is_empty() {
            return Ok(());
        }
        if key {
            match parameter_sets(&data) {
                Some(sets) => self.headers = sets,
                None if !self.headers.is_empty() => {
                    let mut with = self.headers.clone();
                    with.extend_from_slice(&data);
                    data = with;
                }
                None => {}
            }
        }
        out.push(Encoded { ts_ms, key, data });
        Ok(())
    }

    unsafe fn pump(&mut self, out: &mut Vec<Encoded>) -> Result<bool> {
        let event = match self.events.GetEvent(MF_EVENT_FLAG_NO_WAIT) {
            Ok(e) => e,
            Err(e) if e.code() == MF_E_NO_EVENTS_AVAILABLE => return Ok(false),
            Err(e) => return Err(e),
        };
        let kind = event.GetType()? as i32;
        if kind == METransformNeedInput.0 {
            self.credits += 1;
        } else if kind == METransformHaveOutput.0 {
            self.take_output(out)?;
        } else {
            self.other_events.push(kind);
        }
        Ok(true)
    }

    pub fn encode(&mut self, rgba: &[u8], ts_ms: u32, force_key: bool) -> Result<Vec<Encoded>> {
        let mut out = Vec::new();
        unsafe {
            while self.pump(&mut out)? {}
            let waited = std::time::Instant::now();
            while self.credits == 0 {
                if !self.pump(&mut out)? {
                    if waited.elapsed() > STALL {
                        let msg = format!("no input request in {} ms after {} frames; other events {:?}", STALL.as_millis(), self.frames, self.other_events);
                        return Err(windows::core::Error::new(MF_E_NOTACCEPTING, msg));
                    }
                    std::thread::sleep(std::time::Duration::from_millis(1));
                }
            }
            yuv::rgba_to_nv12(rgba, self.width, self.height, &mut self.nv12);
            let buffer = MFCreateMemoryBuffer(self.nv12.len() as u32)?;
            let mut ptr = std::ptr::null_mut();
            buffer.Lock(&mut ptr, None, None)?;
            std::ptr::copy_nonoverlapping(self.nv12.as_ptr(), ptr, self.nv12.len());
            buffer.Unlock()?;
            buffer.SetCurrentLength(self.nv12.len() as u32)?;
            let sample = MFCreateSample()?;
            sample.AddBuffer(&buffer)?;
            sample.SetSampleTime(ts_ms as i64 * 10_000)?;
            let _ = force_key;
            self.transform.ProcessInput(0, &sample, 0)?;
            self.credits -= 1;
            self.frames += 1;
            while self.pump(&mut out)? {}
        }
        Ok(out)
    }
}

impl Drop for HwEncoder {
    fn drop(&mut self) {
        unsafe {
            let _ = self.transform.ProcessMessage(MFT_MESSAGE_NOTIFY_END_STREAMING, 0);
            if let Ok(shutdown) = self.transform.cast::<IMFShutdown>() {
                let _ = shutdown.Shutdown();
            }
        }
    }
}
