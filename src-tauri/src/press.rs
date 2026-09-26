use crate::config;
use crate::error::{Error, Result};
use crate::session_log;
use base64::Engine;
use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::future::Future;
use std::io::Write;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

/// Made by tools/press_keys.py on the bot host; empty turns the mirror off.
pub const PRESS_PUBLIC_KEY_HEX: &str = "113a17021418b4ae942c66987ab88eaa5b1a0f0a0827e74f19a60cee2eed393d";

const INDEX_VERSION: u32 = 1;
const ICON_SIZE: (u32, u32) = (32, 32);
const PAGE_SIZES: [(u32, u32); 4] = [(724, 1024), (768, 1024), (1024, 1600), ICON_SIZE];
const MAX_INDEX_BYTES: u64 = 4 * 1024 * 1024;
const MAX_PAGES: usize = 100_000;
const MAX_LIBRARY_BYTES: u64 = 16 * 1024 * 1024 * 1024;
pub const REFRESH: Duration = Duration::from_secs(120);

#[derive(Deserialize)]
struct Signed {
    payload: String,
    sig: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Index {
    pub v: u32,
    pub seq: u64,
    #[serde(default)]
    pub generated: u64,
    pub pages: Vec<IndexPage>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PageExt {
    Png,
    Jpg,
}

impl PageExt {
    fn as_str(self) -> &'static str {
        match self {
            PageExt::Png => "png",
            PageExt::Jpg => "jpg",
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct IndexPage {
    pub sha: String,
    pub bytes: u64,
    pub w: u32,
    pub h: u32,
    pub ext: PageExt,
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Report {
    pub seq: u64,
    pub downloaded: usize,
    pub removed: usize,
    pub failed: Vec<String>,
}

fn is_sha(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub fn file_name(page: &IndexPage) -> String {
    format!("plzpress_{}.{}", page.sha, page.ext.as_str())
}

/// Is a half-written .part, for a file this module owns; None for anything else in the folder.
fn own_file(name: &str) -> Option<bool> {
    let (name, partial) = match name.strip_suffix(".part") {
        Some(name) => (name, true),
        None => (name, false),
    };
    let rest = name.strip_prefix("plzpress_")?;
    let sha = rest.strip_suffix(".png").or_else(|| rest.strip_suffix(".jpg"))?;
    is_sha(sha).then_some(partial)
}

fn max_page_bytes(w: u32, h: u32) -> u64 {
    w as u64 * h as u64 * 3 + 65_536
}

fn refused(what: impl Into<String>) -> Error {
    Error::Other(format!("press index refused: {}", what.into()))
}

pub fn verify_index(document: &[u8], key_hex: &str) -> Result<Index> {
    let key_raw: [u8; 32] = hex::decode(key_hex)
        .map_err(|_| Error::BadSignature)?
        .try_into()
        .map_err(|_| Error::BadSignature)?;
    let key = VerifyingKey::from_bytes(&key_raw).map_err(|_| Error::BadSignature)?;
    let signed: Signed = serde_json::from_slice(document).map_err(|_| Error::BadSignature)?;
    let b64 = base64::engine::general_purpose::STANDARD;
    let payload = b64.decode(signed.payload.as_bytes()).map_err(|_| Error::BadSignature)?;
    let sig: [u8; 64] = b64
        .decode(signed.sig.as_bytes())
        .map_err(|_| Error::BadSignature)?
        .try_into()
        .map_err(|_| Error::BadSignature)?;
    key.verify_strict(&payload, &Signature::from_bytes(&sig))
        .map_err(|_| Error::BadSignature)?;
    let index: Index = serde_json::from_slice(&payload)?;
    check_index(&index)?;
    Ok(index)
}

fn check_index(index: &Index) -> Result<()> {
    if index.v != INDEX_VERSION {
        return Err(refused(format!("version {} is not understood", index.v)));
    }
    if index.pages.len() > MAX_PAGES {
        return Err(refused("too many pages"));
    }
    let mut seen = HashSet::new();
    let mut total = 0u64;
    for page in &index.pages {
        if !is_sha(&page.sha) || !seen.insert(page.sha.as_str()) {
            return Err(refused(format!("bad or repeated page {:?}", page.sha)));
        }
        if !PAGE_SIZES.contains(&(page.w, page.h))
            || page.bytes == 0
            || page.bytes > max_page_bytes(page.w, page.h)
        {
            return Err(refused(format!("page {} has an impossible size", page.sha)));
        }
        total += page.bytes;
    }
    if total > MAX_LIBRARY_BYTES {
        return Err(refused("the library is larger than any real one"));
    }
    Ok(())
}

pub fn accept_seq(seen: u64, offered: u64) -> bool {
    offered >= seen
}

#[derive(Serialize, Deserialize)]
struct SeqGuard {
    #[serde(rename = "highestSeenSeq")]
    highest_seen_seq: u64,
}

fn read_seq(path: &Path) -> u64 {
    fs::read(path)
        .ok()
        .and_then(|b| serde_json::from_slice::<SeqGuard>(&b).ok())
        .map(|g| g.highest_seen_seq)
        .unwrap_or(0)
}

fn write_seq(path: &Path, seq: u64) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let bytes = serde_json::to_vec_pretty(&SeqGuard { highest_seen_seq: seq })?;
    let mut f = fs::File::create(path)?;
    f.write_all(&bytes)?;
    f.sync_all()?;
    Ok(())
}

/// Hash, exact size, and a FULL decode: the game neither throws nor recovers on a bad page.
pub fn check_page(bytes: &[u8], page: &IndexPage) -> std::result::Result<(), String> {
    if bytes.len() as u64 != page.bytes {
        return Err(format!("{} bytes, index says {}", bytes.len(), page.bytes));
    }
    if hex::encode(Sha256::digest(bytes)) != page.sha {
        return Err("hash mismatch".into());
    }
    match page.ext {
        PageExt::Png => check_png(bytes, page),
        PageExt::Jpg => check_jpeg(bytes, page),
    }
}

fn check_png(bytes: &[u8], page: &IndexPage) -> std::result::Result<(), String> {
    let limits = png::Limits {
        bytes: page.w as usize * page.h as usize * 4,
    };
    let mut decoder = png::Decoder::new_with_limits(std::io::Cursor::new(bytes), limits);
    decoder.set_transformations(png::Transformations::IDENTITY);
    let mut reader = decoder.read_info().map_err(|e| format!("not a PNG: {e}"))?;
    let info = reader.info();
    if (info.width, info.height) != (page.w, page.h) {
        return Err(format!("{}x{}, index says {}x{}", info.width, info.height, page.w, page.h));
    }
    let alpha_allowed = (page.w, page.h) == ICON_SIZE;
    let colour_ok = match info.color_type {
        png::ColorType::Rgb | png::ColorType::Indexed => true,
        png::ColorType::Rgba => alpha_allowed,
        _ => false,
    };
    if info.bit_depth != png::BitDepth::Eight || !colour_ok || info.is_animated() {
        return Err("not the plain RGB or palette page the bot writes".into());
    }
    let mut buffer = vec![0; reader.output_buffer_size()];
    reader.next_frame(&mut buffer).map_err(|e| format!("damaged: {e}"))?;
    reader.finish().map_err(|e| format!("damaged: {e}"))?;
    Ok(())
}

// The game reads JPEG through ImageIO, which cannot read CMYK, so only 3-channel YCbCr passes.
fn check_jpeg(bytes: &[u8], page: &IndexPage) -> std::result::Result<(), String> {
    use zune_jpeg::zune_core::{bytestream::ZCursor, colorspace::ColorSpace, options::DecoderOptions};
    let options = DecoderOptions::default()
        .set_strict_mode(true)
        .set_max_width(page.w as usize)
        .set_max_height(page.h as usize);
    let mut decoder = zune_jpeg::JpegDecoder::new_with_options(ZCursor::new(bytes), options);
    decoder.decode_headers().map_err(|e| format!("not a JPEG: {e:?}"))?;
    let info = decoder.info().ok_or("not a JPEG")?;
    if (info.width as u32, info.height as u32) != (page.w, page.h) {
        return Err(format!("{}x{}, index says {}x{}", info.width, info.height, page.w, page.h));
    }
    if info.components != 3 || decoder.input_colorspace() != Some(ColorSpace::YCbCr) {
        return Err("not the plain colour JPEG the bot writes".into());
    }
    decoder.decode().map_err(|e| format!("damaged: {e:?}"))?;
    Ok(())
}

fn land(dir: &Path, page: &IndexPage, bytes: &[u8]) -> Result<()> {
    let part = dir.join(format!("{}.part", file_name(page)));
    {
        let mut f = fs::File::create(&part)?;
        f.write_all(bytes)?;
        f.sync_all()?;
    }
    fs::rename(&part, dir.join(file_name(page)))?;
    Ok(())
}

pub async fn mirror_into<F, Fut>(dir: &Path, base: &str, index: &Index, fetch: F) -> Report
where
    F: Fn(String, u64) -> Fut,
    Fut: Future<Output = Result<Vec<u8>>>,
{
    let mut report = Report {
        seq: index.seq,
        ..Default::default()
    };
    if let Err(e) = fs::create_dir_all(dir) {
        report.failed.push(e.to_string());
        return report;
    }
    let mut present: BTreeMap<String, u64> = BTreeMap::new();
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            let Some(partial) = own_file(&name) else {
                continue;
            };
            if partial {
                let _ = fs::remove_file(entry.path());
            } else {
                let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
                present.insert(name, size);
            }
        }
    }
    let base = base.trim_end_matches('/');
    for page in &index.pages {
        let name = file_name(page);
        if present.get(&name) == Some(&page.bytes) {
            continue;
        }
        let short = &page.sha[..12];
        let url = format!("{base}/p/{}.{}", page.sha, page.ext.as_str());
        let bytes = match fetch(url, page.bytes).await {
            Ok(bytes) => bytes,
            Err(e) => {
                report.failed.push(format!("{short}: {e}"));
                continue;
            }
        };
        if let Err(why) = check_page(&bytes, page) {
            report.failed.push(format!("{short}: {why}"));
            continue;
        }
        match land(dir, page, &bytes) {
            Ok(()) => report.downloaded += 1,
            Err(e) => report.failed.push(format!("{short}: {e}")),
        }
    }
    let wanted: HashSet<String> = index.pages.iter().map(file_name).collect();
    for name in present.keys() {
        if !wanted.contains(name) && fs::remove_file(dir.join(name)).is_ok() {
            report.removed += 1;
        }
    }
    report
}

fn press_url() -> String {
    #[cfg(debug_assertions)]
    {
        if let Ok(url) = std::env::var(config::PRESS_URL_ENV) {
            return url;
        }
    }
    config::DEFAULT_PRESS_URL.to_string()
}

// Its own client, never payload's: plzctl blocks the runtime that owns that pool for the whole session.
fn client() -> Result<reqwest::Client> {
    Ok(reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(10))
        .read_timeout(Duration::from_secs(30))
        .build()?)
}

async fn fetch_capped(url: String, limit: u64) -> Result<Vec<u8>> {
    if let Some(rest) = url.strip_prefix("file:///") {
        let bytes = fs::read(rest.split('?').next().unwrap_or(rest))?;
        if bytes.len() as u64 > limit {
            return Err(Error::Other(format!("{url} is larger than expected")));
        }
        return Ok(bytes);
    }
    if !url.starts_with("https://") {
        return Err(Error::Other(format!("refusing a non-https press URL: {url}")));
    }
    let mut response = client()?
        .get(&url)
        .header("Cache-Control", "no-cache")
        .send()
        .await?;
    let status = response.status();
    if !status.is_success() {
        return Err(Error::Http {
            url,
            status: status.as_u16(),
        });
    }
    if response.content_length().is_some_and(|n| n > limit) {
        return Err(Error::Other(format!("{url} is larger than expected")));
    }
    let mut out = Vec::new();
    while let Some(chunk) = response.chunk().await? {
        out.extend_from_slice(&chunk);
        if out.len() as u64 > limit {
            return Err(Error::Other(format!("{url} is larger than expected")));
        }
    }
    Ok(out)
}

pub async fn sync_from(base: &str, key_hex: &str, dir: &Path, guard: &Path) -> Result<Report> {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let document = fetch_capped(format!("{base}/index.json?t={stamp}"), MAX_INDEX_BYTES).await?;
    let index = verify_index(&document, key_hex)?;
    let seen = read_seq(guard);
    if !accept_seq(seen, index.seq) {
        return Err(refused(format!("index {} is older than {seen}, already seen", index.seq)));
    }
    if index.seq > seen {
        write_seq(guard, index.seq)?;
    }
    Ok(mirror_into(dir, base, &index, fetch_capped).await)
}

pub fn write_marker(path: &Path, launcher_version: &str) -> Result<()> {
    let bytes = serde_json::to_vec(&serde_json::json!({ "press": 1, "launcher": launcher_version }))?;
    if fs::read(path).ok().as_deref() == Some(bytes.as_slice()) {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, bytes)?;
    Ok(())
}

pub async fn sync() -> Result<Option<Report>> {
    if PRESS_PUBLIC_KEY_HEX.is_empty() {
        return Ok(None);
    }
    if let Err(e) = write_marker(&config::press_marker_path(), env!("CARGO_PKG_VERSION")) {
        session_log::log("press", &format!("launcher marker not written: {e}"));
    }
    sync_from(
        &press_url(),
        PRESS_PUBLIC_KEY_HEX,
        &config::press_pages_dir(),
        &config::press_seq_guard_path(),
    )
    .await
    .map(Some)
}

pub fn log_outcome(outcome: &Result<Option<Report>>) {
    match outcome {
        Ok(None) => {}
        Ok(Some(r)) => session_log::log(
            "press",
            &format!(
                "index {}: {} downloaded, {} removed, {} failed{}",
                r.seq,
                r.downloaded,
                r.removed,
                r.failed.len(),
                r.failed.first().map(|f| format!(" (first: {f})")).unwrap_or_default()
            ),
        ),
        Err(e) => session_log::log("press", &format!("library not updated: {e}")),
    }
}

pub async fn refresh_until(stop: Arc<AtomicBool>) {
    loop {
        let mut waited = Duration::ZERO;
        while waited < REFRESH {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
            waited += Duration::from_secs(1);
        }
        log_outcome(&sync().await);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use std::collections::HashMap;

    fn key() -> SigningKey {
        SigningKey::from_bytes(&[7u8; 32])
    }

    fn key_hex() -> String {
        hex::encode(key().verifying_key().to_bytes())
    }

    fn page_png(w: u32, h: u32, colour: [u8; 3]) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut out, w, h);
            encoder.set_color(png::ColorType::Rgb);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().unwrap();
            let data: Vec<u8> = (0..w * h).flat_map(|_| colour).collect();
            writer.write_image_data(&data).unwrap();
        }
        out
    }

    fn page_jpeg(w: u32, h: u32, colour: [u8; 3], cmyk: bool) -> Vec<u8> {
        let mut out = Vec::new();
        let encoder = jpeg_encoder::Encoder::new(&mut out, 88);
        if cmyk {
            let data: Vec<u8> = (0..w * h).flat_map(|_| [colour[0], colour[1], colour[2], 0]).collect();
            encoder.encode(&data, w as u16, h as u16, jpeg_encoder::ColorType::Cmyk).unwrap();
        } else {
            let data: Vec<u8> = (0..w * h).flat_map(|_| colour).collect();
            encoder.encode(&data, w as u16, h as u16, jpeg_encoder::ColorType::Rgb).unwrap();
        }
        out
    }

    fn sha(bytes: &[u8]) -> String {
        hex::encode(Sha256::digest(bytes))
    }

    fn ext_of(bytes: &[u8]) -> &'static str {
        if bytes.starts_with(b"\xff\xd8") { "jpg" } else { "png" }
    }

    fn name_of(bytes: &[u8]) -> String {
        format!("plzpress_{}.{}", sha(bytes), ext_of(bytes))
    }

    fn signed(payload: &serde_json::Value, with: &SigningKey) -> Vec<u8> {
        let payload = serde_json::to_vec(payload).unwrap();
        let b64 = base64::engine::general_purpose::STANDARD;
        serde_json::to_vec(&serde_json::json!({
            "payload": b64.encode(&payload),
            "sig": b64.encode(with.sign(&payload).to_bytes()),
        }))
        .unwrap()
    }

    fn index_for(pages: &[&[u8]], seq: u64) -> serde_json::Value {
        let entries: Vec<_> = pages
            .iter()
            .map(|p| {
                serde_json::json!({"sha": sha(p), "bytes": p.len(), "w": 768, "h": 1024, "ext": ext_of(p)})
            })
            .collect();
        serde_json::json!({"v": 1, "seq": seq, "generated": 1, "pages": entries})
    }

    fn parsed(pages: &[&[u8]], seq: u64) -> Index {
        verify_index(&signed(&index_for(pages, seq), &key()), &key_hex()).unwrap()
    }

    fn fetcher(
        files: HashMap<String, Vec<u8>>,
    ) -> impl Fn(String, u64) -> std::future::Ready<Result<Vec<u8>>> {
        move |url: String, limit: u64| {
            std::future::ready(match files.get(&url) {
                Some(b) if b.len() as u64 <= limit => Ok(b.clone()),
                Some(_) => Err(Error::Other("too large".into())),
                None => Err(Error::Http { url, status: 404 }),
            })
        }
    }

    fn scratch(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("plz-press-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn run<F: Future>(f: F) -> F::Output {
        tokio::runtime::Builder::new_current_thread().build().unwrap().block_on(f)
    }

    #[test]
    fn the_marker_tells_the_game_which_launcher_ran() {
        let dir = scratch("marker");
        let path = dir.join("PLZPress").join("launcher.json");
        write_marker(&path, "0.7.0").unwrap();
        let written: serde_json::Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(written, serde_json::json!({ "press": 1, "launcher": "0.7.0" }));
        let before = fs::metadata(&path).unwrap().modified().unwrap();
        std::thread::sleep(Duration::from_millis(20));
        write_marker(&path, "0.7.0").unwrap();
        assert_eq!(fs::metadata(&path).unwrap().modified().unwrap(), before);
        write_marker(&path, "0.7.1").unwrap();
        assert!(String::from_utf8(fs::read(&path).unwrap()).unwrap().contains("0.7.1"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_signed_index_verifies() {
        let page = page_png(768, 1024, [1, 2, 3]);
        let index = parsed(&[&page], 4);
        assert_eq!((index.seq, index.pages.len()), (4, 1));
    }

    #[test]
    fn another_key_or_an_edit_is_refused() {
        let page = page_png(768, 1024, [1, 2, 3]);
        let other = SigningKey::from_bytes(&[9u8; 32]);
        assert!(verify_index(&signed(&index_for(&[&page], 4), &other), &key_hex()).is_err());
        let mut doc: serde_json::Value =
            serde_json::from_slice(&signed(&index_for(&[&page], 4), &key())).unwrap();
        let b64 = base64::engine::general_purpose::STANDARD;
        doc["payload"] = b64
            .encode(serde_json::to_vec(&index_for(&[&page], 999)).unwrap())
            .into();
        assert!(verify_index(&serde_json::to_vec(&doc).unwrap(), &key_hex()).is_err());
        assert!(verify_index(b"not json", &key_hex()).is_err());
    }

    #[test]
    fn impossible_pages_are_refused_even_when_signed() {
        for bad in [
            serde_json::json!({"sha": "A".repeat(64), "bytes": 10, "w": 768, "h": 1024, "ext": "png"}),
            serde_json::json!({"sha": "a".repeat(64), "bytes": 10, "w": 800, "h": 1024, "ext": "png"}),
            serde_json::json!({"sha": "a".repeat(64), "bytes": 0, "w": 768, "h": 1024, "ext": "png"}),
            serde_json::json!({"sha": "a".repeat(64), "bytes": 99_000_000, "w": 768, "h": 1024, "ext": "jpg"}),
            serde_json::json!({"sha": "../../evil", "bytes": 10, "w": 768, "h": 1024, "ext": "png"}),
            serde_json::json!({"sha": "a".repeat(64), "bytes": 10, "w": 768, "h": 1024, "ext": "exe"}),
            serde_json::json!({"sha": "a".repeat(64), "bytes": 10, "w": 768, "h": 1024, "ext": "png/../x"}),
            serde_json::json!({"sha": "a".repeat(64), "bytes": 10, "w": 768, "h": 1024}),
        ] {
            let doc = signed(&serde_json::json!({"v": 1, "seq": 1, "pages": [bad]}), &key());
            assert!(verify_index(&doc, &key_hex()).is_err());
        }
        let twice = serde_json::json!({"sha": "a".repeat(64), "bytes": 10, "w": 768, "h": 1024, "ext": "png"});
        let doc = signed(&serde_json::json!({"v": 1, "seq": 1, "pages": [twice.clone(), twice]}), &key());
        assert!(verify_index(&doc, &key_hex()).is_err());
        let doc = signed(&serde_json::json!({"v": 2, "seq": 1, "pages": []}), &key());
        assert!(verify_index(&doc, &key_hex()).is_err());
    }

    #[test]
    fn an_older_index_is_a_rollback() {
        assert!(accept_seq(0, 1));
        assert!(accept_seq(5, 5));
        assert!(!accept_seq(5, 4));
    }

    #[test]
    fn pages_must_fully_decode_as_the_bot_writes_them() {
        let good = page_png(768, 1024, [10, 20, 30]);
        let index = parsed(&[&good], 1);
        assert!(check_page(&good, &index.pages[0]).is_ok());
        let mut truncated = good.clone();
        truncated.truncate(good.len() - 20);
        let bad = IndexPage { bytes: truncated.len() as u64, sha: sha(&truncated), ..index.pages[0].clone() };
        assert!(check_page(&truncated, &bad).is_err());
        let small = page_png(64, 64, [1, 1, 1]);
        let lying = IndexPage { bytes: small.len() as u64, sha: sha(&small), w: 768, h: 1024, ext: PageExt::Png };
        assert!(check_page(&small, &lying).is_err());
        assert!(check_page(b"swapped", &index.pages[0]).is_err());
        let a4 = page_png(724, 1024, [10, 20, 30]);
        let magazine = IndexPage { bytes: a4.len() as u64, sha: sha(&a4), w: 724, h: 1024, ext: PageExt::Png };
        assert!(check_page(&a4, &magazine).is_ok());
        let doc = signed(
            &serde_json::json!({"v": 1, "seq": 1, "pages": [
                {"sha": sha(&a4), "bytes": a4.len(), "w": 724, "h": 1024, "ext": "png"}]}),
            &key(),
        );
        assert!(verify_index(&doc, &key_hex()).is_ok());
    }

    fn rgba_png(w: u32, h: u32) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let mut encoder = png::Encoder::new(&mut out, w, h);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder.write_header().unwrap();
            writer.write_image_data(&vec![128; (w * h * 4) as usize]).unwrap();
        }
        out
    }

    #[test]
    fn only_an_icon_may_carry_transparency() {
        let icon = rgba_png(32, 32);
        let as_icon = IndexPage { sha: sha(&icon), bytes: icon.len() as u64, w: 32, h: 32, ext: PageExt::Png };
        assert!(check_page(&icon, &as_icon).is_ok());
        let page = rgba_png(768, 1024);
        let as_page = IndexPage { sha: sha(&page), bytes: page.len() as u64, w: 768, h: 1024, ext: PageExt::Png };
        assert!(check_page(&page, &as_page).is_err());
        let doc = signed(
            &serde_json::json!({"v": 1, "seq": 1, "pages": [
                {"sha": sha(&icon), "bytes": icon.len(), "w": 32, "h": 32, "ext": "png"}]}),
            &key(),
        );
        assert!(verify_index(&doc, &key_hex()).is_ok());
    }

    #[test]
    fn jpeg_pages_fully_decode_and_must_be_colour_ycbcr() {
        let good = page_jpeg(768, 1024, [10, 120, 30], false);
        let index = parsed(&[&good], 1);
        assert_eq!(index.pages[0].ext, PageExt::Jpg);
        assert!(check_page(&good, &index.pages[0]).is_ok());
        let as_png = IndexPage { ext: PageExt::Png, ..index.pages[0].clone() };
        assert!(check_page(&good, &as_png).is_err());
        let mut truncated = good.clone();
        truncated.truncate(good.len() / 2);
        let cut = IndexPage { bytes: truncated.len() as u64, sha: sha(&truncated), ..index.pages[0].clone() };
        assert!(check_page(&truncated, &cut).is_err());
        let cmyk = page_jpeg(768, 1024, [10, 120, 30], true);
        let ink = IndexPage { bytes: cmyk.len() as u64, sha: sha(&cmyk), ..index.pages[0].clone() };
        assert!(check_page(&cmyk, &ink).is_err());
        let small = page_jpeg(64, 64, [1, 1, 1], false);
        let lying = IndexPage { bytes: small.len() as u64, sha: sha(&small), ..index.pages[0].clone() };
        assert!(check_page(&small, &lying).is_err());
    }

    #[test]
    fn mirror_downloads_missing_pages_and_purges_only_its_own_files() {
        let dir = scratch("mirror");
        let one = page_png(768, 1024, [200, 0, 0]);
        let two = page_jpeg(768, 1024, [0, 200, 0], false);
        let stale = page_jpeg(768, 1024, [0, 0, 200], false);
        fs::write(dir.join(name_of(&stale)), &stale).unwrap();
        fs::write(dir.join(format!("{}.part", name_of(&one))), b"half").unwrap();
        fs::write(dir.join(format!("{}.part", name_of(&two))), b"half").unwrap();
        fs::write(dir.join("notes.txt"), b"keep").unwrap();
        fs::write(dir.join("plzpress_notahash.png"), b"keep").unwrap();
        fs::write(dir.join(format!("plzpress_{}.gif", sha(&one))), b"keep").unwrap();
        let files = HashMap::from([
            (format!("https://o/press/p/{}.png", sha(&one)), one.clone()),
            (format!("https://o/press/p/{}.jpg", sha(&two)), two.clone()),
        ]);
        let report = run(mirror_into(&dir, "https://o/press/", &parsed(&[&one, &two], 3), fetcher(files)));
        assert_eq!((report.downloaded, report.removed, report.failed.len()), (2, 1, 0));
        assert_eq!(fs::read(dir.join(name_of(&one))).unwrap(), one);
        assert_eq!(fs::read(dir.join(name_of(&two))).unwrap(), two);
        assert!(name_of(&two).ends_with(".jpg"));
        assert!(!dir.join(name_of(&stale)).exists());
        assert!(dir.join("notes.txt").exists() && dir.join("plzpress_notahash.png").exists());
        assert!(dir.join(format!("plzpress_{}.gif", sha(&one))).exists());
        assert!(!dir.join(format!("{}.part", name_of(&one))).exists());
        assert!(!dir.join(format!("{}.part", name_of(&two))).exists());
        let again = run(mirror_into(&dir, "https://o/press", &parsed(&[&one, &two], 4), fetcher(HashMap::new())));
        assert_eq!((again.downloaded, again.failed.len()), (0, 0));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_page_the_origin_swapped_is_never_written() {
        let dir = scratch("swapped");
        let one = page_png(768, 1024, [200, 0, 0]);
        let impostor = page_png(768, 1024, [0, 0, 0]);
        let files = HashMap::from([(format!("https://o/press/p/{}.png", sha(&one)), impostor)]);
        let report = run(mirror_into(&dir, "https://o/press", &parsed(&[&one], 1), fetcher(files)));
        assert_eq!((report.downloaded, report.failed.len()), (0, 1));
        assert!(fs::read_dir(&dir).unwrap().next().is_none());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn sync_refuses_a_replayed_older_index_and_keeps_the_cache() {
        let dir = scratch("replay");
        let guard = dir.join("seq.json");
        let pages = dir.join("pages");
        let one = page_png(768, 1024, [5, 5, 5]);
        fs::create_dir_all(&pages).unwrap();
        fs::write(pages.join(name_of(&one)), &one).unwrap();
        write_seq(&guard, 10).unwrap();
        fs::write(dir.join("index.json"), signed(&index_for(&[], 9), &key())).unwrap();
        let base = format!("file:///{}", dir.to_string_lossy().replace('\\', "/"));
        let outcome = run(sync_from(&base, &key_hex(), &pages, &guard));
        assert!(outcome.is_err());
        assert!(pages.join(name_of(&one)).exists());
        assert_eq!(read_seq(&guard), 10);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn sync_moves_the_watermark_forward() {
        let dir = scratch("forward");
        let guard = dir.join("seq.json");
        fs::write(dir.join("index.json"), signed(&index_for(&[], 12), &key())).unwrap();
        let base = format!("file:///{}", dir.to_string_lossy().replace('\\', "/"));
        let report = run(sync_from(&base, &key_hex(), &dir.join("pages"), &guard)).unwrap();
        assert_eq!(report.seq, 12);
        assert_eq!(read_seq(&guard), 12);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    #[ignore = "needs PLZ_PRESS_FIXTURE_DIR/_KEY from the bot's own build_index"]
    fn an_index_the_bot_signed_mirrors() {
        let dir = std::path::PathBuf::from(std::env::var("PLZ_PRESS_FIXTURE_DIR").unwrap());
        let key = std::env::var("PLZ_PRESS_FIXTURE_KEY").unwrap();
        let out = scratch("bot-fixture");
        let base = format!("file:///{}", dir.to_string_lossy().replace('\\', "/"));
        let report = run(sync_from(&base, &key, &out.join("pages"), &out.join("seq.json"))).unwrap();
        assert!(report.failed.is_empty(), "{:?}", report.failed);
        assert_eq!((report.seq, report.downloaded), (7, 3));
        let _ = fs::remove_dir_all(&out);
    }

    #[test]
    fn plain_http_is_refused() {
        let outcome = run(fetch_capped("http://o/press/index.json".into(), 10));
        assert!(outcome.is_err());
    }
}
