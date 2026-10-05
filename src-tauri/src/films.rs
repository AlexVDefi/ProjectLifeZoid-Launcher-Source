use crate::config;
use crate::error::{Error, Result};
use crate::install;
use crate::payload::{self, PayloadFile};
use crate::press;
use crate::session_log;
use base64::Engine;
use ed25519_dalek::{Signature, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::future::Future;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const MAX_FILE_BYTES: u64 = 4 * 1024 * 1024 * 1024;
const MAX_LIBRARY_BYTES: u64 = 64 * 1024 * 1024 * 1024;
const MAX_INDEX_BYTES: u64 = 4 * 1024 * 1024;
const INDEX_VERSION: u32 = 1;
// Signed with the release key, so the prefix keeps a films signature from ever passing as a manifest's.
const INDEX_CONTEXT: &[u8] = b"plz-films-index-v1\n";
const ROOT: &str = "plz";
const EXTENSIONS: [&str; 3] = ["bk2", "ogg", "srt"];
const FETCH_ATTEMPTS: u32 = 3;
pub const REFRESH: Duration = Duration::from_secs(300);
const FIRST_REFRESH: Duration = Duration::from_secs(30);
const BACKGROUND_BYTES_PER_SEC: u64 = 2 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Film {
    pub id: String,
    #[serde(default)]
    pub title: String,
    #[serde(default = "unmarked_counts_as_copyrighted")]
    pub copyrighted: bool,
    #[serde(default)]
    pub rev: String,
    pub files: Vec<PayloadFile>,
}

fn unmarked_counts_as_copyrighted() -> bool {
    true
}

/// `None` in either field means the player has not been asked that yet.
#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Choices {
    pub skip_copyrighted: Option<bool>,
    pub background: Option<bool>,
}

fn parse_choices(text: &str) -> Choices {
    let mut c = Choices::default();
    for line in text.lines() {
        let Some((key, value)) = line.trim().split_once('=') else { continue };
        match (key.trim(), value.trim()) {
            ("copyrighted", "skip") => c.skip_copyrighted = Some(true),
            ("copyrighted", "keep") => c.skip_copyrighted = Some(false),
            ("background", "on") => c.background = Some(true),
            ("background", "off") => c.background = Some(false),
            _ => {}
        }
    }
    c
}

pub fn read_choices() -> Choices {
    fs::read_to_string(config::films_choice_path())
        .map(|t| parse_choices(&t))
        .unwrap_or_default()
}

pub fn write_choices(skip_copyrighted: bool, background: bool) -> Result<()> {
    let path = config::films_choice_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(
        &path,
        format!(
            "copyrighted={}\r\nbackground={}\r\n",
            if skip_copyrighted { "skip" } else { "keep" },
            if background { "on" } else { "off" }
        ),
    )?;
    Ok(())
}

pub fn wanted(films: &[Film], skip_copyrighted: bool) -> Vec<Film> {
    films.iter().filter(|f| !(skip_copyrighted && f.copyrighted)).cloned().collect()
}

#[derive(Deserialize)]
struct Signed {
    payload: String,
    sig: String,
}

#[derive(Debug, Deserialize)]
pub struct Index {
    pub v: u32,
    pub seq: u64,
    pub films: Vec<Film>,
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
    let mut message = INDEX_CONTEXT.to_vec();
    message.extend_from_slice(&payload);
    key.verify_strict(&message, &Signature::from_bytes(&sig))
        .map_err(|_| Error::BadSignature)?;
    let index: Index = serde_json::from_slice(&payload)?;
    if index.v != INDEX_VERSION {
        return Err(refused(format!("unknown index version {}", index.v)));
    }
    check(&index.films)?;
    Ok(index)
}

async fn fetch_document(url: &str) -> Result<Vec<u8>> {
    if let Some(rest) = url.strip_prefix("file:///") {
        return Ok(fs::read(rest.split('?').next().unwrap_or(rest))?);
    }
    let mut response = payload::client()
        .get(url)
        .header("Cache-Control", "no-cache")
        .send()
        .await?;
    let status = response.status();
    if !status.is_success() {
        return Err(Error::Http {
            url: url.to_string(),
            status: status.as_u16(),
        });
    }
    let mut out = Vec::new();
    while let Some(chunk) = response.chunk().await? {
        out.extend_from_slice(&chunk);
        if out.len() as u64 > MAX_INDEX_BYTES {
            return Err(refused("the index is larger than any real one"));
        }
    }
    Ok(out)
}

/// A film list the origin no longer serves is an empty one; anything else that fails is an error.
pub async fn fetch_index() -> Result<Vec<Film>> {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let url = format!("{}/films/index.json?t={stamp}", payload::release_base());
    let document = match fetch_document(&url).await {
        Ok(d) => d,
        Err(Error::Http { status: 404, .. }) => return Ok(Vec::new()),
        Err(e) => return Err(e),
    };
    let index = verify_index(&document, payload::PUBLIC_KEY_HEX)?;
    let guard = config::films_seq_guard_path();
    let seen = press::read_seq(&guard);
    if !press::accept_seq(seen, index.seq) {
        return Err(refused(format!("index {} is older than {seen}, already seen", index.seq)));
    }
    if index.seq > seen {
        press::write_seq(&guard, index.seq)?;
    }
    Ok(index.films)
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Report {
    pub films: usize,
    pub ready: usize,
    pub downloaded: usize,
    pub deferred: usize,
    pub bytes: u64,
    pub removed: usize,
    pub failed: Vec<String>,
}

/// Before launch with background downloads on, nothing is fetched: the game starts at once and
/// the session's refresh does the downloading.
#[derive(Clone)]
pub struct Mode {
    pub download: bool,
    pub prune: bool,
    pub pace: Option<Pace>,
}

#[derive(Clone)]
pub struct Pace {
    pub bytes_per_sec: u64,
    pub stop: Arc<AtomicBool>,
}

impl Mode {
    pub fn before_launch(background: bool) -> Self {
        Mode {
            download: !background,
            prune: true,
            pace: None,
        }
    }

    // A running game may hold a retired film open, so the session never deletes.
    fn during_play(stop: Arc<AtomicBool>) -> Self {
        Mode {
            download: true,
            prune: false,
            pace: Some(Pace {
                bytes_per_sec: BACKGROUND_BYTES_PER_SEC,
                stop,
            }),
        }
    }
}

fn film_ready(film: &Film, owned: &BTreeMap<String, Entry>) -> bool {
    film.files.iter().all(|f| owned.get(&f.path).is_some_and(|e| e.sha256 == f.sha256))
}

fn render_ready(films: &[Film], owned: &BTreeMap<String, Entry>, downloading: Option<&str>) -> String {
    let mut out = String::new();
    for film in films.iter().filter(|f| film_ready(f, owned)) {
        out.push_str(&format!("ready={}:{}\r\n", film.id, film.rev));
    }
    if let Some(id) = downloading {
        out.push_str(&format!("downloading={id}\r\n"));
    }
    out
}

fn write_ready(text: &str) {
    let path = config::films_ready_path();
    let written = (|| -> Result<()> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        if fs::read_to_string(&path).ok().as_deref() == Some(text) {
            return Ok(());
        }
        let tmp = path.with_extension("txt.tmp");
        fs::write(&tmp, text)?;
        fs::rename(&tmp, &path)?;
        Ok(())
    })();
    if let Err(e) = written {
        session_log::log("films", &format!("ready list not written: {e}"));
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Entry {
    sha256: String,
    size: u64,
    mtime: u128,
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct Ledger {
    installs: BTreeMap<String, BTreeMap<String, Entry>>,
}

pub fn videos_dir(install: &Path) -> PathBuf {
    install::game_dir(install).join("media").join("videos")
}

fn is_sha(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn is_name(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= 64
        && !s.starts_with('.')
        && s.bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || matches!(b, b'_' | b'-' | b'.'))
}

pub fn is_id(s: &str) -> bool {
    is_name(s) && !s.contains('.')
}

/// `plz/<id>/<file>` with a known extension, and nothing that could climb out of it.
pub fn is_film_path(id: &str, path: &str) -> bool {
    let parts: Vec<&str> = path.split('/').collect();
    let [root, folder, file] = parts.as_slice() else {
        return false;
    };
    *root == ROOT
        && *folder == id
        && is_name(file)
        && file
            .rsplit_once('.')
            .is_some_and(|(stem, ext)| !stem.is_empty() && EXTENSIONS.contains(&ext))
}

fn refused(what: impl Into<String>) -> Error {
    Error::Other(format!("video list refused: {}", what.into()))
}

pub fn check(films: &[Film]) -> Result<()> {
    let mut ids = HashSet::new();
    let mut paths = HashSet::new();
    let mut total = 0u64;
    for film in films {
        if !is_id(&film.id) || !ids.insert(film.id.as_str()) {
            return Err(refused(format!("bad or repeated film id {:?}", film.id)));
        }
        if film.files.is_empty() {
            return Err(refused(format!("{} lists no files", film.id)));
        }
        for f in &film.files {
            if !is_film_path(&film.id, &f.path) || !paths.insert(f.path.as_str()) {
                return Err(refused(format!("bad or repeated path {:?}", f.path)));
            }
            if !is_sha(&f.sha256) || f.size == 0 || f.size > MAX_FILE_BYTES {
                return Err(refused(format!("{} has an impossible hash or size", f.path)));
            }
            total += f.size;
        }
    }
    if total > MAX_LIBRARY_BYTES {
        return Err(refused("the films add up to more than any real library"));
    }
    Ok(())
}

fn local_path(videos: &Path, rel: &str) -> PathBuf {
    rel.split('/').fold(videos.to_path_buf(), |p, part| p.join(part))
}

fn mtime(path: &Path) -> Option<u128> {
    fs::metadata(path)
        .ok()?
        .modified()
        .ok()?
        .duration_since(std::time::UNIX_EPOCH)
        .ok()
        .map(|d| d.as_millis())
}

fn hash_file(path: &Path) -> Option<(String, u64)> {
    let mut file = fs::File::open(path).ok()?;
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    let mut size = 0u64;
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
        size += n as u64;
    }
    Some((hex::encode(hasher.finalize()), size))
}

fn entry_for(path: &Path, f: &PayloadFile) -> Option<Entry> {
    Some(Entry {
        sha256: f.sha256.clone(),
        size: f.size,
        mtime: mtime(path)?,
    })
}

/// Hashing a multi-GB film on every Play is not affordable, so a file whose size and mtime still
/// match what this launcher wrote is taken as unchanged.
fn already_there(path: &Path, f: &PayloadFile, known: Option<&Entry>) -> Option<Entry> {
    let size = fs::metadata(path).ok()?.len();
    if size != f.size {
        return None;
    }
    if let Some(e) = known {
        if e.sha256 == f.sha256 && e.size == size && Some(e.mtime) == mtime(path) {
            return Some(e.clone());
        }
    }
    let (sha, _) = hash_file(path)?;
    (sha == f.sha256).then(|| entry_for(path, f)).flatten()
}

fn human(bytes: u64) -> String {
    let mb = bytes as f64 / (1024.0 * 1024.0);
    if mb >= 1024.0 {
        format!("{:.1} GB", mb / 1024.0)
    } else {
        format!("{mb:.1} MB")
    }
}

type Changed<'a> = dyn Fn(&BTreeMap<String, Entry>, Option<&str>) + Send + Sync + 'a;

#[allow(clippy::too_many_arguments)]
async fn mirror_into<F, Fut>(
    videos: &Path,
    owned: &mut BTreeMap<String, Entry>,
    films: &[Film],
    base: &str,
    fetch: F,
    mode: &Mode,
    progress: &(dyn Fn(&str) + Send + Sync),
    changed: &Changed<'_>,
) -> Report
where
    F: Fn(String, PathBuf, u64) -> Fut,
    Fut: Future<Output = Result<String>>,
{
    let mut report = Report {
        films: films.len(),
        ..Default::default()
    };
    let base = base.trim_end_matches('/');

    let mut pending: Vec<(&Film, &PayloadFile, PathBuf)> = Vec::new();
    for film in films {
        for f in &film.files {
            let dest = local_path(videos, &f.path);
            match already_there(&dest, f, owned.get(&f.path)) {
                Some(entry) => {
                    owned.insert(f.path.clone(), entry);
                }
                None => pending.push((film, f, dest)),
            }
        }
    }
    let stopped = || mode.pace.as_ref().is_some_and(|p| p.stop.load(Ordering::Relaxed));
    if !mode.download {
        report.deferred = pending.iter().map(|(film, _, _)| film.id.as_str()).collect::<HashSet<_>>().len();
        pending.clear();
    }
    changed(owned, None);

    let total = pending.len();
    let total_bytes: u64 = pending.iter().map(|(_, f, _)| f.size).sum();
    let mut broken: HashSet<&str> = HashSet::new();
    let mut current: Option<&str> = None;
    for (i, (film, f, dest)) in pending.iter().enumerate() {
        if stopped() {
            break;
        }
        if current != Some(film.id.as_str()) {
            current = Some(film.id.as_str());
            changed(owned, current);
        }
        let title = if film.title.is_empty() { &film.id } else { &film.title };
        progress(&format!(
            "Video '{title}': file {} of {total} ({} of {} left)",
            i + 1,
            human(f.size),
            human(total_bytes - report.bytes)
        ));
        let failed = |why: String| format!("{}: {why}", f.path);
        if let Some(parent) = dest.parent() {
            if let Err(e) = fs::create_dir_all(parent) {
                report.failed.push(failed(e.to_string()));
                broken.insert(film.id.as_str());
                continue;
            }
        }
        let part = dest.with_extension(format!(
            "{}.part",
            dest.extension().and_then(|e| e.to_str()).unwrap_or_default()
        ));
        let _ = fs::remove_file(&part);
        let got = fetch(format!("{base}/films/{}", f.sha256), part.clone(), f.size).await;
        let landed = match got {
            Ok(sha) if sha == f.sha256 => fs::rename(&part, dest).map_err(|e| e.to_string()),
            Ok(sha) => Err(format!("hash {} does not match the signed {}", &sha[..12], &f.sha256[..12])),
            Err(e) => Err(e.to_string()),
        };
        match landed.and_then(|()| entry_for(dest, f).ok_or_else(|| "vanished after landing".into())) {
            Ok(entry) => {
                owned.insert(f.path.clone(), entry);
                report.downloaded += 1;
                report.bytes += f.size;
            }
            Err(why) => {
                let _ = fs::remove_file(&part);
                report.failed.push(failed(why));
                broken.insert(film.id.as_str());
            }
        }
        if !pending.get(i + 1).is_some_and(|(next, _, _)| next.id == film.id) {
            current = None;
            changed(owned, None);
        }
    }
    if current.is_some() {
        changed(owned, None);
    }
    report.ready = films.iter().filter(|f| film_ready(f, owned)).count();
    if mode.download {
        report.deferred = films.len() - report.ready - broken.len();
    }

    if !mode.prune {
        return report;
    }
    let wanted: HashSet<&str> = films.iter().flat_map(|f| f.files.iter().map(|x| x.path.as_str())).collect();
    let stale: Vec<String> = owned.keys().filter(|p| !wanted.contains(p.as_str())).cloned().collect();
    for rel in stale {
        let Some(entry) = owned.remove(&rel) else { continue };
        let path = local_path(videos, &rel);
        let ours = fs::metadata(&path).is_ok_and(|m| m.len() == entry.size) && mtime(&path) == Some(entry.mtime);
        if ours && fs::remove_file(&path).is_ok() {
            report.removed += 1;
            if let Some(folder) = path.parent() {
                let _ = fs::remove_dir(folder);
            }
        }
    }
    report
}

fn load_ledger() -> Ledger {
    fs::read(config::films_ledger_path())
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .unwrap_or_default()
}

fn save_ledger(ledger: &Ledger) -> Result<()> {
    let path = config::films_ledger_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("json.tmp");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(&serde_json::to_vec_pretty(ledger)?)?;
        f.sync_all()?;
    }
    fs::rename(&tmp, &path)?;
    Ok(())
}

fn copy_hashing(from: &Path, part: &Path, limit: u64) -> Result<String> {
    let mut src = fs::File::open(from)?;
    let mut out = std::io::BufWriter::new(fs::File::create(part)?);
    let mut hasher = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    let mut size = 0u64;
    loop {
        let n = src.read(&mut buf)?;
        if n == 0 {
            break;
        }
        size += n as u64;
        if size > limit {
            return Err(Error::Other(format!("{} is larger than signed", from.display())));
        }
        hasher.update(&buf[..n]);
        out.write_all(&buf[..n])?;
    }
    out.into_inner().map_err(|e| Error::Io(e.to_string()))?.sync_all()?;
    Ok(hex::encode(hasher.finalize()))
}

async fn download_once(url: &str, part: &Path, limit: u64, pace: Option<&Pace>) -> Result<String> {
    let mut response = payload::client().get(url).send().await?;
    let status = response.status();
    if !status.is_success() {
        return Err(Error::Http {
            url: url.to_string(),
            status: status.as_u16(),
        });
    }
    if response.content_length().is_some_and(|n| n > limit) {
        return Err(Error::Other(format!("{url} is larger than signed")));
    }
    let mut out = std::io::BufWriter::new(fs::File::create(part)?);
    let mut hasher = Sha256::new();
    let mut size = 0u64;
    let started = Instant::now();
    while let Some(chunk) = response.chunk().await? {
        size += chunk.len() as u64;
        if size > limit {
            return Err(Error::Other(format!("{url} is larger than signed")));
        }
        hasher.update(&chunk);
        out.write_all(&chunk)?;
        if let Some(p) = pace {
            if p.stop.load(Ordering::Relaxed) {
                return Err(Error::Other("stopped: the game closed".into()));
            }
            let due = Duration::from_secs_f64(size as f64 / p.bytes_per_sec.max(1) as f64);
            let ahead = due.saturating_sub(started.elapsed());
            if ahead >= Duration::from_millis(100) {
                tokio::time::sleep(ahead).await;
            }
        }
    }
    out.into_inner().map_err(|e| Error::Io(e.to_string()))?.sync_all()?;
    Ok(hex::encode(hasher.finalize()))
}

async fn download(url: String, part: PathBuf, limit: u64, pace: Option<Pace>) -> Result<String> {
    if let Some(rest) = url.strip_prefix("file:///") {
        return copy_hashing(&PathBuf::from(rest), &part, limit);
    }
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return copy_hashing(Path::new(&url), &part, limit);
    }
    let mut attempt = 1u32;
    loop {
        match download_once(&url, &part, limit, pace.as_ref()).await {
            Ok(sha) => return Ok(sha),
            Err(e)
                if attempt < FETCH_ATTEMPTS
                    && payload::worth_retrying(&e)
                    && !pace.as_ref().is_some_and(|p| p.stop.load(Ordering::Relaxed)) =>
            {
                tokio::time::sleep(Duration::from_secs(attempt as u64)).await;
                attempt += 1;
            }
            Err(e) => return Err(e),
        }
    }
}

pub async fn sync(
    install: &Path,
    films: &[Film],
    mode: &Mode,
    progress: &(dyn Fn(&str) + Send + Sync),
) -> Result<Report> {
    check(films)?;
    let key = install.to_string_lossy().into_owned();
    let mut ledger = load_ledger();
    let owned = ledger.installs.entry(key).or_default();
    let pace = mode.pace.clone();
    let report = mirror_into(
        &videos_dir(install),
        owned,
        films,
        &payload::release_base(),
        |url, part, limit| download(url, part, limit, pace.clone()),
        mode,
        progress,
        &|owned, downloading| write_ready(&render_ready(films, owned, downloading)),
    )
    .await;
    save_ledger(&ledger)?;
    Ok(report)
}

/// Downloads films published while the game runs, if the player allowed it. The choice is read
/// every round, so turning it off in Details takes effect without a restart.
pub fn refresh_until(stop: Arc<AtomicBool>, install: PathBuf) {
    let job_stop = stop.clone();
    press::run_every("films-refresh", stop, FIRST_REFRESH, REFRESH, move || {
        let install = install.clone();
        let stop = job_stop.clone();
        async move {
            let choices = read_choices();
            if choices.background != Some(true) {
                return;
            }
            let outcome = match fetch_index().await {
                Ok(list) => {
                    let wanted = wanted(&list, choices.skip_copyrighted.unwrap_or(false));
                    sync(&install, &wanted, &Mode::during_play(stop), &|_| {}).await
                }
                Err(e) => Err(e),
            };
            match outcome {
                Ok(r) if r.downloaded > 0 || !r.failed.is_empty() => {
                    session_log::log("films", &format!("in session: {r:?}"))
                }
                Ok(_) => {}
                Err(e) => session_log::log("films", &format!("in session: not updated: {e}")),
            }
        }
    });
}

pub fn describe(report: &Report) -> Option<String> {
    if report.films == 0 && report.removed == 0 {
        return None;
    }
    let mut parts = Vec::new();
    if report.downloaded > 0 {
        parts.push(format!("downloaded {} file(s), {}", report.downloaded, human(report.bytes)));
    }
    if report.deferred > 0 {
        parts.push(format!("{} video(s) will download while you play", report.deferred));
    }
    if report.removed > 0 {
        parts.push(format!("removed {} old file(s)", report.removed));
    }
    if !report.failed.is_empty() {
        let broken = report.films - report.ready - report.deferred;
        parts.push(format!(
            "{} of {} video(s) incomplete, they will retry next Play (first: {})",
            broken, report.films, report.failed[0]
        ));
    }
    (!parts.is_empty()).then(|| format!("Cinema videos: {}.", parts.join("; ")))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::Arc;

    fn sha(bytes: &[u8]) -> String {
        hex::encode(Sha256::digest(bytes))
    }

    fn file(path: &str, bytes: &[u8]) -> PayloadFile {
        PayloadFile {
            path: path.into(),
            sha256: sha(bytes),
            size: bytes.len() as u64,
        }
    }

    fn film(id: &str, files: Vec<PayloadFile>) -> Film {
        Film {
            id: id.into(),
            title: String::new(),
            copyrighted: true,
            rev: format!("r{id}"),
            files,
        }
    }

    #[test]
    fn skipping_drops_only_copyrighted_films_and_an_unmarked_film_counts_as_one() {
        let mut free = film("free", vec![file("plz/free/seg_000.bk2", b"f")]);
        free.copyrighted = false;
        let unmarked: Film = serde_json::from_value(serde_json::json!({
            "id": "unmarked", "files": [{ "path": "plz/unmarked/seg_000.bk2", "sha256": sha(b"u"), "size": 1 }]
        }))
        .unwrap();
        assert!(unmarked.copyrighted);
        let all = [free, unmarked, film("paid", vec![file("plz/paid/seg_000.bk2", b"p")])];
        assert_eq!(wanted(&all, false).len(), 3);
        let kept: Vec<String> = wanted(&all, true).into_iter().map(|f| f.id).collect();
        assert_eq!(kept, vec!["free".to_string()]);
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("plz-films-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn run<F: Future>(f: F) -> F::Output {
        tokio::runtime::Builder::new_current_thread().build().unwrap().block_on(f)
    }

    struct Origin {
        blobs: HashMap<String, Vec<u8>>,
        calls: Arc<AtomicUsize>,
    }

    impl Origin {
        fn new(served: &[&[u8]]) -> Self {
            Origin {
                blobs: served.iter().map(|b| (format!("https://o/release/films/{}", sha(b)), b.to_vec())).collect(),
                calls: Arc::new(AtomicUsize::new(0)),
            }
        }

        fn fetch(&self) -> impl Fn(String, PathBuf, u64) -> std::future::Ready<Result<String>> + '_ {
            move |url, part, _limit| {
                self.calls.fetch_add(1, Ordering::SeqCst);
                std::future::ready(match self.blobs.get(&url) {
                    Some(bytes) => fs::write(&part, bytes).map(|()| sha(bytes)).map_err(Error::from),
                    None => Err(Error::Http { url, status: 404 }),
                })
            }
        }
    }

    fn mirror_with(videos: &Path, owned: &mut BTreeMap<String, Entry>, films: &[Film], origin: &Origin, mode: &Mode) -> Report {
        run(mirror_into(videos, owned, films, "https://o/release", origin.fetch(), mode, &|_| {}, &|_, _| {}))
    }

    fn mirror(videos: &Path, owned: &mut BTreeMap<String, Entry>, films: &[Film], origin: &Origin) -> Report {
        mirror_with(videos, owned, films, origin, &Mode { download: true, prune: true, pace: None })
    }

    #[test]
    fn only_plain_paths_inside_the_films_own_folder_pass() {
        assert!(is_film_path("spiffo", "plz/spiffo/seg_000.bk2"));
        assert!(is_film_path("spiffo", "plz/spiffo/spiffo.ogg"));
        assert!(is_film_path("spiffo", "plz/spiffo/spiffo.en.srt"));
        for bad in [
            "plz/other/seg_000.bk2",
            "plz/spiffo/../seg_000.bk2",
            "plz/spiffo/..",
            "plz/spiffo/sub/seg.bk2",
            "plz/spiffo/seg.exe",
            "plz/spiffo/.bk2",
            "plz/spiffo/.hidden.bk2",
            "plz/spiffo/Seg.bk2",
            "plz\\spiffo\\seg.bk2",
            "/plz/spiffo/seg.bk2",
            "videos/spiffo/seg.bk2",
            "plz/spiffo/seg.bk2/",
        ] {
            assert!(!is_film_path("spiffo", bad), "{bad} should be refused");
        }
        assert!(!is_id("a.b") && !is_id("") && !is_id("..") && is_id("night-of-the-living_dead"));
    }

    #[test]
    fn a_list_with_repeats_or_impossible_files_is_refused_whole() {
        let a = file("plz/a/seg_000.bk2", b"x");
        assert!(check(&[film("a", vec![a.clone()])]).is_ok());
        assert!(check(&[film("a", vec![a.clone(), a.clone()])]).is_err());
        assert!(check(&[film("a", vec![a.clone()]), film("a", vec![file("plz/a/seg_001.bk2", b"y")])]).is_err());
        assert!(check(&[film("b", vec![a.clone()])]).is_err());
        assert!(check(&[film("a", vec![])]).is_err());
        let mut huge = a.clone();
        huge.size = MAX_FILE_BYTES + 1;
        assert!(check(&[film("a", vec![huge])]).is_err());
        let mut bad_sha = a;
        bad_sha.sha256 = "ABC".into();
        assert!(check(&[film("a", vec![bad_sha])]).is_err());
    }

    #[test]
    fn missing_files_download_and_a_second_run_fetches_nothing() {
        let videos = scratch("fresh");
        let (s0, au) = (b"segment zero".as_slice(), b"audio".as_slice());
        let films = [film("spiffo", vec![file("plz/spiffo/seg_000.bk2", s0), file("plz/spiffo/spiffo.ogg", au)])];
        let origin = Origin::new(&[s0, au]);
        let mut owned = BTreeMap::new();

        let first = mirror(&videos, &mut owned, &films, &origin);
        assert_eq!((first.downloaded, first.ready, first.failed.len()), (2, 1, 0));
        assert_eq!(fs::read(videos.join("plz/spiffo/seg_000.bk2")).unwrap(), s0);
        assert_eq!(owned.len(), 2);

        let second = mirror(&videos, &mut owned, &films, &origin);
        assert_eq!((second.downloaded, second.ready), (0, 1));
        assert_eq!(origin.calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn bytes_that_do_not_match_the_signed_hash_never_land() {
        let videos = scratch("swap");
        let signed = b"the real segment".as_slice();
        let films = [film("a", vec![file("plz/a/seg_000.bk2", signed)])];
        let mut origin = Origin::new(&[]);
        origin.blobs.insert(format!("https://o/release/films/{}", sha(signed)), b"something else".to_vec());
        let mut owned = BTreeMap::new();

        let report = mirror(&videos, &mut owned, &films, &origin);
        assert_eq!((report.downloaded, report.ready, report.failed.len()), (0, 0, 1));
        assert!(!videos.join("plz/a/seg_000.bk2").exists());
        assert!(!videos.join("plz/a/seg_000.bk2.part").exists());
        assert!(owned.is_empty());
    }

    #[test]
    fn a_matching_file_already_in_place_is_adopted_without_a_download() {
        let videos = scratch("adopt");
        let bytes = b"copied by hand".as_slice();
        fs::create_dir_all(videos.join("plz/a")).unwrap();
        fs::write(videos.join("plz/a/seg_000.bk2"), bytes).unwrap();
        let films = [film("a", vec![file("plz/a/seg_000.bk2", bytes)])];
        let origin = Origin::new(&[bytes]);
        let mut owned = BTreeMap::new();

        let report = mirror(&videos, &mut owned, &films, &origin);
        assert_eq!((report.downloaded, report.ready), (0, 1));
        assert_eq!(origin.calls.load(Ordering::SeqCst), 0);
        assert!(owned.contains_key("plz/a/seg_000.bk2"));
    }

    #[test]
    fn a_wrong_file_in_place_is_replaced() {
        let videos = scratch("replace");
        let bytes = b"the signed one".as_slice();
        fs::create_dir_all(videos.join("plz/a")).unwrap();
        fs::write(videos.join("plz/a/seg_000.bk2"), b"an old encode!").unwrap();
        let films = [film("a", vec![file("plz/a/seg_000.bk2", bytes)])];
        let origin = Origin::new(&[bytes]);
        let mut owned = BTreeMap::new();

        let report = mirror(&videos, &mut owned, &films, &origin);
        assert_eq!(report.downloaded, 1);
        assert_eq!(fs::read(videos.join("plz/a/seg_000.bk2")).unwrap(), bytes);
    }

    #[test]
    fn a_dropped_film_is_removed_but_files_the_launcher_never_wrote_are_kept() {
        let videos = scratch("prune");
        let (old, keep) = (b"retired film".as_slice(), b"still showing".as_slice());
        let origin = Origin::new(&[old, keep]);
        let mut owned = BTreeMap::new();
        mirror(
            &videos,
            &mut owned,
            &[film("old", vec![file("plz/old/seg_000.bk2", old)]), film("keep", vec![file("plz/keep/seg_000.bk2", keep)])],
            &origin,
        );
        fs::create_dir_all(videos.join("plz/dev")).unwrap();
        fs::write(videos.join("plz/dev/seg_000.bk2"), b"a developer's test clip").unwrap();

        let report = mirror(&videos, &mut owned, &[film("keep", vec![file("plz/keep/seg_000.bk2", keep)])], &origin);
        assert_eq!(report.removed, 1);
        assert!(!videos.join("plz/old").exists(), "the emptied folder goes too");
        assert!(videos.join("plz/keep/seg_000.bk2").exists());
        assert!(videos.join("plz/dev/seg_000.bk2").exists());
        assert_eq!(owned.len(), 1);
    }

    #[test]
    fn a_retired_file_someone_changed_since_is_left_alone() {
        let videos = scratch("edited");
        let old = b"retired film".as_slice();
        let origin = Origin::new(&[old]);
        let mut owned = BTreeMap::new();
        mirror(&videos, &mut owned, &[film("old", vec![file("plz/old/seg_000.bk2", old)])], &origin);
        fs::write(videos.join("plz/old/seg_000.bk2"), b"replaced by the player, longer").unwrap();

        let report = mirror(&videos, &mut owned, &[], &origin);
        assert_eq!(report.removed, 0);
        assert!(videos.join("plz/old/seg_000.bk2").exists());
        assert!(owned.is_empty());
    }

    #[test]
    fn one_missing_file_marks_only_its_own_film_incomplete() {
        let videos = scratch("partial");
        let (a, b) = (b"film a".as_slice(), b"film b".as_slice());
        let origin = Origin::new(&[a]);
        let mut owned = BTreeMap::new();
        let report = mirror(
            &videos,
            &mut owned,
            &[film("a", vec![file("plz/a/seg_000.bk2", a)]), film("b", vec![file("plz/b/seg_000.bk2", b)])],
            &origin,
        );
        assert_eq!((report.films, report.ready, report.failed.len()), (2, 1, 1));
        assert!(describe(&report).unwrap().contains("1 of 2 video(s) incomplete"));
    }

    #[test]
    fn a_published_film_entry_parses() {
        let entry = serde_json::json!({
            "id": "spiffoparty", "title": "Spiffo Party", "copyrighted": false, "width": 640, "height": 360, "fps": 30,
            "segMs": 60000, "durationMs": 44533, "audio": "plz/spiffoparty/spiffoparty.ogg",
            "segments": [{ "file": "plz/spiffoparty/seg_000.bk2", "frames": 1336, "ms": 44533 }],
            "files": [
                { "path": "plz/spiffoparty/seg_000.bk2", "sha256": "55f96a73a3c79fec41d713d158a4b2a3f8a2c5cb8f62ee426893700de1de43cd", "size": 8497292 },
                { "path": "plz/spiffoparty/spiffoparty.ogg", "sha256": "6fa157c47e56394317c2f456af09a48e4057244ddcd97d22f6d19d563e194ef4", "size": 670810 }
            ]
        });
        let parsed: Film = serde_json::from_value(entry).unwrap();
        assert_eq!(parsed.files.len(), 2);
        assert!(!parsed.copyrighted);
        assert!(check(&[parsed]).is_ok());
    }

    #[test]
    #[ignore = "downloads from the live origin"]
    fn a_real_file_streams_from_the_live_origin() {
        let dir = scratch("live");
        std::env::set_var(config::MANIFEST_URL_ENV, "https://launcher.projectlifezoid.com/release/manifest.json");
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            let m = payload::fetch_manifest().await.unwrap();
            let f = m.files.iter().min_by_key(|f| f.size).unwrap();
            let part = dir.join("x.part");
            let got = download(format!("{}/files/{}", payload::release_base(), f.path), part.clone(), f.size, None).await.unwrap();
            assert_eq!(got, f.sha256);
            assert_eq!(fs::metadata(&part).unwrap().len(), f.size);
            assert!(download(format!("{}/files/{}", payload::release_base(), f.path), part, f.size - 1, None).await.is_err());
        });
    }

    #[test]
    fn each_question_counts_as_unanswered_until_its_own_line_is_written() {
        assert_eq!(parse_choices(""), Choices::default());
        let old = parse_choices("copyrighted=skip\r\n");
        assert_eq!((old.skip_copyrighted, old.background), (Some(true), None));
        let both = parse_choices("copyrighted=keep\r\nbackground=on\r\n");
        assert_eq!((both.skip_copyrighted, both.background), (Some(false), Some(true)));
        assert_eq!(parse_choices("background=maybe").background, None);
    }

    #[test]
    fn before_launch_with_background_on_fetches_nothing_but_keeps_what_is_there() {
        let videos = scratch("defer");
        let (have, new) = (b"already here".as_slice(), b"published today".as_slice());
        fs::create_dir_all(videos.join("plz/old")).unwrap();
        fs::write(videos.join("plz/old/seg_000.bk2"), have).unwrap();
        let films = [film("old", vec![file("plz/old/seg_000.bk2", have)]), film("new", vec![file("plz/new/seg_000.bk2", new)])];
        let origin = Origin::new(&[have, new]);
        let mut owned = BTreeMap::new();

        let report = mirror_with(&videos, &mut owned, &films, &origin, &Mode::before_launch(true));
        assert_eq!((report.downloaded, report.ready, report.deferred, report.failed.len()), (0, 1, 1, 0));
        assert_eq!(origin.calls.load(Ordering::SeqCst), 0);
        assert!(describe(&report).unwrap().contains("1 video(s) will download while you play"));
    }

    #[test]
    fn the_session_refresh_never_deletes_a_film_the_game_may_have_open() {
        let videos = scratch("noprune");
        let old = b"retired mid-session".as_slice();
        let origin = Origin::new(&[old]);
        let mut owned = BTreeMap::new();
        mirror(&videos, &mut owned, &[film("old", vec![file("plz/old/seg_000.bk2", old)])], &origin);

        let session = Mode::during_play(Arc::new(AtomicBool::new(false)));
        let report = mirror_with(&videos, &mut owned, &[], &origin, &session);
        assert_eq!(report.removed, 0);
        assert!(videos.join("plz/old/seg_000.bk2").exists());
        assert!(owned.contains_key("plz/old/seg_000.bk2"), "the next Play still knows it may delete it");
    }

    #[test]
    fn a_stopped_session_starts_no_new_download() {
        let videos = scratch("stopped");
        let bytes = b"too late".as_slice();
        let origin = Origin::new(&[bytes]);
        let mut owned = BTreeMap::new();
        let session = Mode::during_play(Arc::new(AtomicBool::new(true)));
        let report = mirror_with(&videos, &mut owned, &[film("a", vec![file("plz/a/seg_000.bk2", bytes)])], &origin, &session);
        assert_eq!((report.downloaded, report.deferred), (0, 1));
        assert_eq!(origin.calls.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn the_game_hears_about_each_film_as_it_lands() {
        let videos = scratch("ready");
        let (a, b) = (b"film a".as_slice(), b"film b".as_slice());
        let films = [
            film("a", vec![file("plz/a/seg_000.bk2", a), file("plz/a/a.ogg", b"a sound")]),
            film("b", vec![file("plz/b/seg_000.bk2", b)]),
        ];
        let origin = Origin::new(&[a, b"a sound", b]);
        let mut owned = BTreeMap::new();
        let seen = std::sync::Mutex::new(Vec::new());
        run(mirror_into(
            &videos,
            &mut owned,
            &films,
            "https://o/release",
            origin.fetch(),
            &Mode { download: true, prune: true, pace: None },
            &|_| {},
            &|owned, downloading| seen.lock().unwrap().push(render_ready(&films, owned, downloading)),
        ));
        let seen = seen.into_inner().unwrap();
        assert_eq!(
            seen,
            vec![
                "".to_string(),
                "downloading=a\r\n".to_string(),
                "ready=a:ra\r\n".to_string(),
                "ready=a:ra\r\ndownloading=b\r\n".to_string(),
                "ready=a:ra\r\nready=b:rb\r\n".to_string(),
            ]
        );
    }

    fn signed_index(payload: &serde_json::Value, context: &[u8]) -> Vec<u8> {
        use ed25519_dalek::{Signer, SigningKey};
        let key = SigningKey::from_bytes(&[9u8; 32]);
        let payload = serde_json::to_vec(payload).unwrap();
        let mut message = context.to_vec();
        message.extend_from_slice(&payload);
        let b64 = base64::engine::general_purpose::STANDARD;
        serde_json::to_vec(&serde_json::json!({
            "payload": b64.encode(&payload),
            "sig": b64.encode(key.sign(&message).to_bytes()),
        }))
        .unwrap()
    }

    #[test]
    fn only_an_index_signed_as_a_film_list_is_accepted() {
        use ed25519_dalek::SigningKey;
        let key_hex = hex::encode(SigningKey::from_bytes(&[9u8; 32]).verifying_key().to_bytes());
        let listing = serde_json::json!({ "v": 1, "seq": 5, "films": [{
            "id": "a", "title": "A", "copyrighted": false, "rev": "abc",
            "files": [{ "path": "plz/a/seg_000.bk2", "sha256": sha(b"x"), "size": 1 }]
        }]});
        let index = verify_index(&signed_index(&listing, INDEX_CONTEXT), &key_hex).unwrap();
        assert_eq!((index.seq, index.films[0].rev.as_str()), (5, "abc"));
        assert!(verify_index(&signed_index(&listing, b""), &key_hex).is_err(), "a bare release signature is not a film list");
        let mut future = listing.clone();
        future["v"] = serde_json::json!(2);
        assert!(verify_index(&signed_index(&future, INDEX_CONTEXT), &key_hex).is_err());
    }

    #[test]
    fn a_local_copy_larger_than_signed_is_refused() {
        let dir = scratch("limit");
        let src = dir.join("src.bk2");
        fs::write(&src, b"twelve bytes").unwrap();
        assert!(copy_hashing(&src, &dir.join("out.part"), 11).is_err());
        assert_eq!(copy_hashing(&src, &dir.join("out.part"), 12).unwrap(), sha(b"twelve bytes"));
    }
}
