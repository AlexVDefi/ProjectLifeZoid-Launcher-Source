use crate::config;
use crate::error::{Error, Result};
use crate::install;
use crate::launch;
use crate::state::{self, ActivePatch, State};
use crate::workshop_override;
use serde_json::Value;
use std::fs;
use std::path::Path;

const PROP_STAMP: &str = "-Dplz.stampFile=";
const PROP_BUILD: &str = "-Dplz.build=";
const PROP_WORKSHOP_STATE: &str = "-Dplz.workshopStateFile=";
const PROP_WORKSHOP_SESSION: &str = "-Dplz.workshopSession=";

pub fn repair(st: &mut State) -> Result<bool> {
    if launch::is_game_running()
        && (st.active_patch.is_some() || config::workshop_override_active_path().is_file())
    {
        return Err(Error::GameAlreadyRunning);
    }

    let Some(active) = st.active_patch.clone() else {
        return workshop_override::clear_active();
    };

    if !active.installed_files.is_empty() {
        if let Some(dir) = st.install_dir.clone() {
            remove_installed(&dir, &active.installed_files)?;
        }
    }

    if let (Some(json_path), Some(backup_path), Some(original)) = (
        active.json_path.as_deref(),
        active.backup_path.as_deref(),
        active.original_sha256.as_deref(),
    ) {
        if backup_path.is_file() {
            fs::copy(backup_path, json_path)?;
            let actual = install::sha256_file(json_path)?;
            if actual != original {
                return Err(Error::Other(format!(
                    "restored ProjectZomboid64.json does not match the recorded original \
                     (expected {}, got {}). Verify the game files in Steam.",
                    &original[..12],
                    &actual[..12]
                )));
            }
        } else if !json_path.is_file() {
            return Err(Error::Other(
                "ProjectZomboid64.json and its backup are both missing. \
                 Verify the game files in Steam."
                    .into(),
            ));
        }
    }

    workshop_override::clear_active()?;
    st.active_patch = None;
    st.save()?;
    Ok(true)
}

#[cfg(target_os = "macos")]
fn install_payload(install_dir: &Path, build: u64) -> Result<Vec<String>> {
    let source = config::patch_dir(build);
    let target = install::game_dir(install_dir);
    if !target.is_dir() {
        return Err(Error::Other(format!(
            "{} is missing, so this does not look like a Project Zomboid install.",
            target.display()
        )));
    }

    let mut written = Vec::new();
    for rel in collect_relative(&source, &source)? {
        let from = source.join(&rel);
        let to = target.join(&rel);
        if to.exists() {
            remove_installed(install_dir, &written)?;
            return Err(Error::Other(format!(
                "{} already exists in the game install. Verify the game files in Steam, then \
                 try again.",
                to.display()
            )));
        }
        if let Some(parent) = to.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::copy(&from, &to)?;
        written.push(rel);
    }
    Ok(written)
}

#[cfg(target_os = "macos")]
fn collect_relative(dir: &Path, base: &Path) -> Result<Vec<String>> {
    let mut out = Vec::new();
    for entry in fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            out.extend(collect_relative(&path, base)?);
        } else if let Ok(rel) = path.strip_prefix(base) {
            out.push(rel.to_string_lossy().replace('\\', "/"));
        }
    }
    out.sort();
    Ok(out)
}

#[cfg(target_os = "macos")]
fn remove_installed(install_dir: &Path, files: &[String]) -> Result<()> {
    let target = install::game_dir(install_dir);
    let mut dirs: Vec<std::path::PathBuf> = Vec::new();
    for rel in files {
        let path = target.join(rel);
        match fs::remove_file(&path) {
            Ok(()) => {}
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(Error::Io(format!("could not remove {}: {e}", path.display()))),
        }
        let mut parent = path.parent().map(Path::to_path_buf);
        while let Some(dir) = parent {
            if dir == target || !dir.starts_with(&target) {
                break;
            }
            parent = dir.parent().map(Path::to_path_buf);
            dirs.push(dir);
        }
    }
    dirs.sort();
    dirs.dedup();
    for dir in dirs.into_iter().rev() {
        let _ = fs::remove_dir(&dir);
    }
    Ok(())
}

#[cfg(not(target_os = "macos"))]
fn remove_installed(_install_dir: &Path, _files: &[String]) -> Result<()> {
    Ok(())
}

fn strip_our_entries(json: &mut Value, patch_marker: &str) {
    if let Some(cp) = json.get_mut("classpath").and_then(Value::as_array_mut) {
        cp.retain(|v| {
            v.as_str()
                .map(|s| !s.contains(patch_marker))
                .unwrap_or(true)
        });
    }
    if let Some(vm) = json.get_mut("vmArgs").and_then(Value::as_array_mut) {
        vm.retain(|v| {
            v.as_str()
                .map(|s| {
                    !s.starts_with(PROP_STAMP)
                        && !s.starts_with(PROP_BUILD)
                        && !s.starts_with(PROP_WORKSHOP_STATE)
                        && !s.starts_with(PROP_WORKSHOP_SESSION)
                })
                .unwrap_or(true)
        });
    }
}

pub fn apply(st: &mut State, install_dir: &Path, build: u64) -> Result<()> {
    repair(st)?;

    #[cfg(target_os = "macos")]
    {
        let installed = install_payload(install_dir, build)?;
        st.active_patch = Some(ActivePatch {
            json_path: None,
            backup_path: None,
            original_sha256: None,
            installed_files: installed,
            build,
            patched_at_millis: state::now_millis(),
        });
        st.save()?;
        let _ = fs::remove_file(config::stamp_path());
        return Ok(());
    }

    #[cfg(not(target_os = "macos"))]
    {
    let json_path = install::json_path(install_dir);
    let original_bytes = fs::read(&json_path)?;
    if original_bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        return Err(Error::Other(
            "ProjectZomboid64.json already has a UTF-8 BOM, which stops the game launching. \
             Verify the game files in Steam."
                .into(),
        ));
    }
    let original_sha = hex::encode(<sha2::Sha256 as sha2::Digest>::digest(&original_bytes));
    let mut json: Value = serde_json::from_slice(&original_bytes)?;

    let backup_path = config::backup_dir().join("ProjectZomboid64.json");
    fs::create_dir_all(config::backup_dir())?;
    fs::write(&backup_path, &original_bytes)?;
    let backup_sha = install::sha256_file(&backup_path)?;
    if backup_sha != original_sha {
        return Err(Error::Other("backup verification failed".into()));
    }

    st.active_patch = Some(ActivePatch {
        json_path: Some(json_path.clone()),
        backup_path: Some(backup_path),
        original_sha256: Some(original_sha),
        installed_files: Vec::new(),
        build,
        patched_at_millis: state::now_millis(),
    });
    st.save()?;

    let workshop_receipt = match workshop_override::activate_pending() {
        Ok(receipt) => receipt,
        Err(error) => {
            st.active_patch = None;
            st.save()?;
            return Err(error);
        }
    };

    let patch_dir = config::patch_dir(build);
    let patch_str = patch_dir.to_string_lossy().replace('\\', "/");
    strip_our_entries(&mut json, "ProjectLifeZoidLauncher");

    let cp = json
        .get_mut("classpath")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| Error::MalformedJson("no classpath array".into()))?;
    cp.insert(0, Value::String(patch_str));

    let stamp = config::stamp_path().to_string_lossy().replace('\\', "/");
    let vm = json
        .get_mut("vmArgs")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| Error::MalformedJson("no vmArgs array".into()))?;
    vm.push(Value::String(format!("{PROP_STAMP}{stamp}")));
    vm.push(Value::String(format!("{PROP_BUILD}{build}")));
    if let Some(receipt) = workshop_receipt {
        let state_path = receipt.path.to_string_lossy().replace('\\', "/");
        let session = receipt
            .session
            .ok_or_else(|| Error::Other("active Workshop receipt has no session".into()))?;
        vm.push(Value::String(format!("{PROP_WORKSHOP_STATE}{state_path}")));
        vm.push(Value::String(format!("{PROP_WORKSHOP_SESSION}{session}")));
    }

    let text = serde_json::to_string_pretty(&json)? + "\n";
    state::write_no_bom(&json_path, &text)?;

    let _ = fs::remove_file(config::stamp_path());
    Ok(())
    }
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Stamp {
    pub build: String,
    pub first_class: String,
    pub origin: String,
    pub classpath: String,
}

pub fn read_stamp() -> Option<Stamp> {
    let bytes = fs::read(config::stamp_path()).ok()?;
    let v: Value = serde_json::from_slice(&bytes).ok()?;
    Some(Stamp {
        build: v.get("build")?.as_str()?.to_string(),
        first_class: v.get("firstClass")?.as_str().unwrap_or("").to_string(),
        origin: v.get("origin")?.as_str().unwrap_or("").to_string(),
        classpath: v.get("classpath")?.as_str().unwrap_or("").to_string(),
    })
}
