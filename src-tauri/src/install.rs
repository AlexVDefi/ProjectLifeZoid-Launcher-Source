use crate::error::{Error, Result};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JarFingerprint {
    pub sha256: String,
    pub size: u64,
    pub mtime_millis: u128,
}

pub fn game_dir(install: &Path) -> PathBuf {
    #[cfg(target_os = "linux")]
    {
        return install.join("projectzomboid");
    }
    #[cfg(target_os = "macos")]
    {
        return install
            .join("Project Zomboid.app")
            .join("Contents")
            .join("Java");
    }
    #[cfg(not(any(target_os = "linux", target_os = "macos")))]
    {
        install.to_path_buf()
    }
}

pub fn jar_path(install: &Path) -> PathBuf {
    game_dir(install).join("projectzomboid.jar")
}

pub fn json_path_opt(install: &Path) -> Option<PathBuf> {
    #[cfg(target_os = "macos")]
    {
        let _ = install;
        None
    }
    #[cfg(not(target_os = "macos"))]
    {
        Some(game_dir(install).join("ProjectZomboid64.json"))
    }
}

pub fn json_path(install: &Path) -> PathBuf {
    json_path_opt(install).unwrap_or_else(|| game_dir(install).join("ProjectZomboid64.json"))
}

pub fn sha256_file(path: &Path) -> Result<String> {
    let bytes = fs::read(path)?;
    Ok(hex::encode(Sha256::digest(&bytes)))
}

pub fn fingerprint_jar(
    install: &Path,
    previous: Option<&JarFingerprint>,
) -> Result<JarFingerprint> {
    let jar = jar_path(install);
    let meta = fs::metadata(&jar)?;
    let size = meta.len();
    let mtime_millis = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis())
        .unwrap_or(0);

    if let Some(prev) = previous {
        if prev.size == size && prev.mtime_millis == mtime_millis {
            return Ok(prev.clone());
        }
    }

    Ok(JarFingerprint {
        sha256: sha256_file(&jar)?,
        size,
        mtime_millis,
    })
}

fn is_valid_install(dir: &Path) -> bool {
    jar_path(dir).is_file() && json_path_opt(dir).map_or(true, |p| p.is_file())
}

pub fn resolve_chosen(chosen: &Path) -> Result<PathBuf> {
    let mut tries = vec![chosen.to_path_buf(), chosen.join("ProjectZomboid")];
    let mut walk = chosen;
    for _ in 0..3 {
        let Some(parent) = walk.parent() else { break };
        tries.push(parent.to_path_buf());
        walk = parent;
    }
    tries
        .into_iter()
        .find(|c| is_valid_install(c))
        .ok_or_else(|| Error::NotAnInstall {
            path: chosen.display().to_string(),
        })
}

fn candidates(explicit: Option<&Path>) -> Vec<PathBuf> {
    let mut out: Vec<PathBuf> = Vec::new();
    if let Some(p) = explicit {
        out.push(p.to_path_buf());
    }
    if let Ok(env) = std::env::var("PZ_INSTALL_DIR") {
        out.push(PathBuf::from(env));
    }
    #[cfg(windows)]
    out.push(PathBuf::from(
        r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    ));
    out.extend(
        steam_library_dirs()
            .into_iter()
            .map(|lib| lib.join("steamapps").join("common").join("ProjectZomboid")),
    );
    out
}

pub fn steam_root() -> Option<PathBuf> {
    crate::steam::root()
}

pub fn active_steam_id() -> Result<u64> {
    crate::steam::resolve_steam_id(crate::state::State::load().steam_id_override)
}

pub fn debug_launch_option(options: &str) -> Option<String> {
    options
        .split_whitespace()
        .find(|token| matches!(*token, "-debug" | "-imgui" | "-imguidebugviewports"))
        .map(str::to_string)
}

pub fn legacy_steam_username(steam_id: u64) -> String {
    format!("s{steam_id}")
}

pub fn steam_library_dirs() -> Vec<PathBuf> {
    crate::steam::library_dirs()
}

pub fn find_install(explicit: Option<&Path>) -> Result<PathBuf> {
    candidates(explicit)
        .into_iter()
        .find(|c| is_valid_install(c))
        .ok_or(Error::InstallNotFound)
}

pub fn steam_launch_options() -> Option<String> {
    let root = steam_root()?;
    let userdata = root.join("userdata");
    let entries = fs::read_dir(&userdata).ok()?;

    for entry in entries.flatten() {
        let cfg = entry.path().join("config").join("localconfig.vdf");
        let Ok(text) = fs::read_to_string(&cfg) else {
            continue;
        };
        if let Some(v) = launch_options_in(&text) {
            return Some(v);
        }
    }
    None
}

fn launch_options_in(text: &str) -> Option<String> {
    const KEY: &str = "\"108600\"";
    let mut from = 0usize;

    while let Some(rel) = text[from..].find(KEY) {
        let at = from + rel;
        from = at + KEY.len();

        let after = &text[from..];
        if after.trim_start().starts_with('{') {
            let open = from + after.find('{')?;
            let mut depth = 0i32;
            let mut end = open;
            for (i, c) in text[open..].char_indices() {
                match c {
                    '{' => depth += 1,
                    '}' => {
                        depth -= 1;
                        if depth == 0 {
                            end = open + i;
                            break;
                        }
                    }
                    _ => {}
                }
            }
            let block = &text[open..end];
            let lo = block.find("\"LaunchOptions\"")?;
            let rest = &block[lo + "\"LaunchOptions\"".len()..];
            let s = rest.find('"')?;
            let tail = &rest[s + 1..];
            let e = tail.find('"')?;
            let v = tail[..e].trim().to_string();
            return if v.is_empty() { None } else { Some(v) };
        }
    }
    None
}

pub fn steam_exe() -> Result<PathBuf> {
    crate::steam::exe()
}

#[cfg(test)]
mod layout_tests {
    use super::*;

    #[test]
    fn game_dir_matches_the_depot_layout() {
        let install = Path::new("/steam/common/ProjectZomboid");
        let dir = game_dir(install);

        #[cfg(target_os = "linux")]
        assert!(
            dir.ends_with("ProjectZomboid/projectzomboid"),
            "Linux nests the game under projectzomboid/, beside projectzomboid.sh: {dir:?}"
        );
        #[cfg(target_os = "macos")]
        assert!(
            dir.ends_with("Project Zomboid.app/Contents/Java"),
            "macOS keeps the jar inside the app bundle: {dir:?}"
        );
        #[cfg(windows)]
        assert_eq!(dir, install, "Windows keeps the game at the top level");

        assert!(jar_path(install).ends_with("projectzomboid.jar"));
    }

    fn fake_install(tag: &str) -> PathBuf {
        let root = std::env::temp_dir()
            .join(format!("plz-install-{}-{}", std::process::id(), tag))
            .join("common")
            .join("ProjectZomboid");
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(game_dir(&root)).unwrap();
        fs::create_dir_all(root.join("media")).unwrap();
        fs::write(jar_path(&root), b"not really a jar").unwrap();
        if let Some(json) = json_path_opt(&root) {
            fs::write(json, b"{}").unwrap();
        }
        root
    }

    #[test]
    fn a_pick_at_the_wrong_depth_still_finds_the_install() {
        let root = fake_install("depth");

        assert_eq!(resolve_chosen(&root).unwrap(), root, "the root itself");
        assert_eq!(
            resolve_chosen(&game_dir(&root)).unwrap(),
            root,
            "the folder holding the jar, which is NOT the root on Linux or macOS"
        );
        assert_eq!(
            resolve_chosen(&root.join("media")).unwrap(),
            root,
            "one level inside"
        );
        assert_eq!(
            resolve_chosen(root.parent().unwrap()).unwrap(),
            root,
            "steamapps/common, which looks much like the install in a picker"
        );

        let _ = fs::remove_dir_all(root.parent().unwrap());
    }

    #[test]
    fn a_folder_that_is_not_an_install_is_refused_outright() {
        let empty = std::env::temp_dir().join(format!("plz-empty-{}", std::process::id()));
        let _ = fs::remove_dir_all(&empty);
        fs::create_dir_all(&empty).unwrap();

        match resolve_chosen(&empty) {
            Err(Error::NotAnInstall { path }) => assert!(path.contains("plz-empty")),
            other => panic!("expected a refusal naming the folder, got {other:?}"),
        }

        let _ = fs::remove_dir_all(&empty);
    }

    #[test]
    fn only_the_json_platforms_report_a_json() {
        let opt = json_path_opt(Path::new("/steam/common/ProjectZomboid"));
        #[cfg(target_os = "macos")]
        assert!(opt.is_none());
        #[cfg(not(target_os = "macos"))]
        assert!(opt.expect("windows and linux both ship one").ends_with("ProjectZomboid64.json"));
    }
}
