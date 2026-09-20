use crate::config;
use crate::error::{Error, Result};
use crate::install;
use crate::jvmpath;
use crate::launch;
use crate::session_log;
use crate::state::{self, ActivePatch, State};
use crate::workshop_override;
use serde_json::Value;
use std::fs;
use std::path::Path;

const PROP_STAMP: &str = "-Dplz.stampFile=";
const PROP_BUILD: &str = "-Dplz.build=";
const PROP_WORKSHOP_STATE: &str = "-Dplz.workshopStateFile=";
const PROP_WORKSHOP_SESSION: &str = "-Dplz.workshopSession=";

/// Steam's shipped default across the client, dedicated server and Linux copies alike.
pub const DEFAULT_HEAP_MB: u32 = 3072;

/// True when the install a patch was applied to is not there to restore into. Both halves
/// matter: a missing file inside a folder that exists is a file to put back, while a missing
/// folder is an install that moved, was uninstalled, or lives on a drive that is not mounted.
fn install_is_unreachable(json_path: &Path) -> bool {
    !json_path.is_file() && !json_path.parent().is_some_and(Path::is_dir)
}

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
        if install_is_unreachable(json_path) {
            // The install this patch was applied to is gone -- uninstalled, moved, or on a
            // drive that is not mounted. There is nothing left to put back, and refusing here
            // every time would keep the record forever: the memory setting, the install folder
            // and every launcher update are gated on it, with nothing in the UI the player
            // could use to clear it. apply() strips any leftovers it finds, so a drive that
            // comes back later still ends up with a clean file.
        } else if backup_path.is_file() {
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
    // Before anything is banked or activated: a path the JVM cannot read back is the end of
    // this launch, and unwinding a half-applied patch is worse than never starting one.
    let patch_str = jvmpath::for_jvm(&config::patch_dir(build))?;
    let stamp = jvmpath::for_jvm(&config::stamp_path())?;
    if !config::app_dir().to_string_lossy().is_ascii() {
        session_log::log(
            "patch",
            &format!("the launcher's folder has a non-ASCII name; handing the game {patch_str}"),
        );
    }

    let json_path = install::json_path(install_dir);
    let original_bytes = fs::read(&json_path)?;
    if original_bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        return Err(Error::Other(
            "ProjectZomboid64.json already has a UTF-8 BOM, which stops the game launching. \
             Verify the game files in Steam."
                .into(),
        ));
    }
    let mut json: Value = serde_json::from_slice(&original_bytes)?;

    // A json that still carries our entries is not an original. That happens whenever a
    // session's restore could not run, and banking it as-is would make the leftovers
    // permanent -- every restore after it would put them back. Bank the cleaned form.
    let untouched = json.clone();
    strip_our_entries(&mut json, "ProjectLifeZoidLauncher");
    let original_bytes = if json == untouched {
        original_bytes
    } else {
        (serde_json::to_string_pretty(&json)? + "\n").into_bytes()
    };
    let original_sha = hex::encode(<sha2::Sha256 as sha2::Digest>::digest(&original_bytes));

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

    let cp = json
        .get_mut("classpath")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| Error::MalformedJson("no classpath array".into()))?;
    cp.insert(0, Value::String(patch_str));

    let vm = json
        .get_mut("vmArgs")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| Error::MalformedJson("no vmArgs array".into()))?;
    vm.push(Value::String(format!("{PROP_STAMP}{stamp}")));
    vm.push(Value::String(format!("{PROP_BUILD}{build}")));
    if let Some(receipt) = workshop_receipt {
        let state_path = jvmpath::for_jvm(&receipt.path)?;
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

fn parse_xmx_mb(arg: &str) -> Option<u32> {
    let value = arg.strip_prefix("-Xmx")?;
    let (digits, unit) = value.split_at(value.len().checked_sub(1)?);
    let n: u64 = digits.parse().ok()?;
    match unit {
        "g" | "G" => u32::try_from(n * 1024).ok(),
        "m" | "M" => u32::try_from(n).ok(),
        _ => None,
    }
}

pub fn read_heap_mb(install_dir: &Path) -> Option<u32> {
    let bytes = fs::read(install::json_path_opt(install_dir)?).ok()?;
    let json: Value = serde_json::from_slice(&bytes).ok()?;
    json.get("vmArgs")?
        .as_array()?
        .iter()
        .find_map(|v| v.as_str().and_then(parse_xmx_mb))
}

pub fn set_heap_mb(install_dir: &Path, mb: u32) -> Result<()> {
    let json_path = install::json_path_opt(install_dir).ok_or_else(|| {
        Error::Other("Project Zomboid's memory is not configurable on this platform.".into())
    })?;
    let bytes = fs::read(&json_path)?;
    if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
        return Err(Error::Other(
            "ProjectZomboid64.json already has a UTF-8 BOM, which stops the game launching. \
             Verify the game files in Steam."
                .into(),
        ));
    }
    let mut json: Value = serde_json::from_slice(&bytes)?;
    let vm = json
        .get_mut("vmArgs")
        .and_then(Value::as_array_mut)
        .ok_or_else(|| Error::MalformedJson("no vmArgs array".into()))?;

    let entry = Value::String(format!("-Xmx{mb}m"));
    match vm
        .iter_mut()
        .find(|v| v.as_str().is_some_and(|s| s.starts_with("-Xmx")))
    {
        Some(slot) => *slot = entry,
        None => vm.push(entry),
    }

    let text = serde_json::to_string_pretty(&json)? + "\n";
    state::write_no_bom(&json_path, &text)
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

#[cfg(test)]
mod repair_tests {
    use super::*;

    #[test]
    fn an_install_that_still_has_its_folder_is_reachable() {
        let root = std::env::temp_dir().join(format!("plz-reach-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&root).unwrap();

        let json = root.join("ProjectZomboid64.json");
        assert!(install_is_unreachable(&root.join("gone").join("ProjectZomboid64.json")));
        // The folder is there, so the file is one to put back rather than an install that left.
        assert!(!install_is_unreachable(&json));

        fs::write(&json, "{}").unwrap();
        assert!(!install_is_unreachable(&json));

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn stripping_leaves_a_json_that_apply_can_bank_as_an_original() {
        let mut json: Value = serde_json::from_str(
            r#"{
                "classpath": ["C:/x/ProjectLifeZoidLauncher/patch/36", "zombie.jar"],
                "vmArgs": ["-Xmx8192m", "-Dplz.build=36", "-Dplz.stampFile=C:/x/stamp.json"]
            }"#,
        )
        .unwrap();
        let untouched = json.clone();
        strip_our_entries(&mut json, "ProjectLifeZoidLauncher");

        assert_ne!(json, untouched, "a patched json must not be banked as-is");
        assert_eq!(json["classpath"].as_array().unwrap().len(), 1);
        assert_eq!(json["vmArgs"].as_array().unwrap(), &vec![Value::from("-Xmx8192m")]);

        let clean = json.clone();
        strip_our_entries(&mut json, "ProjectLifeZoidLauncher");
        assert_eq!(json, clean, "a clean json is banked byte for byte");
    }
}

#[cfg(test)]
mod heap_tests {
    use super::*;

    #[test]
    fn parses_gigabyte_and_megabyte_suffixes() {
        assert_eq!(parse_xmx_mb("-Xmx3072m"), Some(3072));
        assert_eq!(parse_xmx_mb("-Xmx8g"), Some(8192));
        assert_eq!(parse_xmx_mb("-Xmx8G"), Some(8192));
        assert_eq!(parse_xmx_mb("-Xmx8192M"), Some(8192));
    }

    #[test]
    fn rejects_anything_that_is_not_an_xmx_flag() {
        assert_eq!(parse_xmx_mb("-Xms2048m"), None);
        assert_eq!(parse_xmx_mb("-Dzomboid.steam=1"), None);
        assert_eq!(parse_xmx_mb("-Xmx"), None);
        assert_eq!(parse_xmx_mb("-Xmxabc"), None);
    }

    fn fake_install(tag: &str, vm_args_json: &str) -> std::path::PathBuf {
        let root = std::env::temp_dir().join(format!("plz-heap-{}-{}", std::process::id(), tag));
        let _ = fs::remove_dir_all(&root);
        let json_path = install::json_path(&root);
        fs::create_dir_all(json_path.parent().unwrap()).unwrap();
        fs::write(&json_path, format!(r#"{{"vmArgs": {vm_args_json}}}"#)).unwrap();
        root
    }

    // macOS ships no ProjectZomboid64.json at all, so read/write_heap_mb refuse there by
    // design (see json_path_opt) -- these round-trip tests only apply to the platforms
    // that have a file to manage.
    #[cfg(not(target_os = "macos"))]
    #[test]
    fn set_heap_mb_replaces_the_existing_entry_in_place() {
        let root = fake_install(
            "replace",
            r#"["-Djava.awt.headless=true", "-Xmx3072m", "-Dzomboid.steam=1"]"#,
        );
        assert_eq!(read_heap_mb(&root), Some(3072));

        set_heap_mb(&root, 8192).unwrap();
        assert_eq!(read_heap_mb(&root), Some(8192));

        let text = fs::read_to_string(install::json_path(&root)).unwrap();
        assert_eq!(
            text.matches("-Xmx").count(),
            1,
            "must not accumulate old entries: {text}"
        );

        let _ = fs::remove_dir_all(&root);
    }

    #[cfg(not(target_os = "macos"))]
    #[test]
    fn set_heap_mb_adds_the_entry_when_none_exists() {
        let root = fake_install("missing", r#"["-Djava.awt.headless=true"]"#);

        set_heap_mb(&root, 6144).unwrap();
        assert_eq!(read_heap_mb(&root), Some(6144));

        let _ = fs::remove_dir_all(&root);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn macos_has_no_manageable_heap_setting() {
        let root = fake_install("macos-unsupported", r#"["-Xmx3072m"]"#);

        assert_eq!(read_heap_mb(&root), None);
        assert!(set_heap_mb(&root, 8192).is_err());

        let _ = fs::remove_dir_all(&root);
    }
}
