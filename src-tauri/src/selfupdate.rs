use crate::error::{Error, Result};
use crate::state::State;
use crate::{launch, patch};
use serde::Serialize;
use tauri::AppHandle;
use tauri_plugin_updater::UpdaterExt;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub available: bool,
    pub current_version: String,
    pub version: String,
    pub notes: String,
}

#[cfg(target_os = "linux")]
fn refuse_if_not_self_updatable() -> Result<()> {
    if std::env::var_os("APPIMAGE").is_none() {
        return Err(Error::Other(
            "This copy was installed from a package, which cannot replace itself. Download the              latest version from the website and install it the same way you did before."
                .into(),
        ));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn refuse_if_not_self_updatable() -> Result<()> {
    use std::path::Path;

    fn parent_is_writable(dir: &Path) -> bool {
        let probe = dir.join(".plz-update-probe");
        match std::fs::File::create(&probe) {
            Ok(_) => {
                let _ = std::fs::remove_file(&probe);
                true
            }
            Err(_) => false,
        }
    }

    let exe = std::env::current_exe()
        .map_err(|e| Error::Other(format!("Could not locate the running launcher: {e}")))?;

    let Some(bundle) = exe
        .ancestors()
        .find(|p| p.extension().is_some_and(|ext| ext.eq_ignore_ascii_case("app")))
    else {
        return Ok(());
    };
    let Some(parent) = bundle.parent() else {
        return Ok(());
    };
    if parent_is_writable(parent) {
        return Ok(());
    }

    let shown = bundle.display().to_string();
    Err(Error::Other(if shown.starts_with("/Volumes/") {
        "The launcher is running from the disk image, which cannot be written to, so it cannot \
         replace itself. Drag it into your Applications folder, eject the disk image, and open \
         it from there -- then updating will work from now on."
            .into()
    } else if shown.contains("/AppTranslocation/") {
        "macOS is running this copy from a locked temporary folder because it is still marked \
         as downloaded, so it cannot replace itself. Move it to your Applications folder, then \
         run this in Terminal once:\n\n\
         xattr -dr com.apple.quarantine \"/Applications/Project Life Zoid Launcher.app\"\n\n\
         Open it again afterwards and updating will work from now on."
            .into()
    } else {
        format!(
            "The folder this launcher is in is read-only, so it cannot replace itself:\n{shown}\n\n\
             Move it to your Applications folder and open it from there, or download the latest \
             version from the website."
        )
    }))
}

#[cfg(not(any(target_os = "linux", target_os = "macos")))]
fn refuse_if_not_self_updatable() -> Result<()> {
    Ok(())
}

fn explain_install_failure(e: Error) -> Error {
    let text = e.to_string();
    if text.contains("Read-only file system") || text.contains("os error 30") {
        return Error::Other(
            "The launcher could not replace itself because it is running from a read-only \
             location -- usually the disk image it was downloaded in. Move it to your \
             Applications folder and open it from there, then try again."
                .into(),
        );
    }
    e
}

fn refuse_if_mid_session() -> Result<()> {
    if launch::is_game_running() {
        return Err(Error::Other(
            "Quit Project Zomboid first. Updating the launcher now would leave your game install \
             patched, because the installer stops the launcher before it can put things back."
                .into(),
        ));
    }
    if State::load().active_patch.is_some() {
        return Err(Error::Other(
            "Your game install is still patched from a session that has not finished. Restart \
             the launcher so it can restore the install, then update."
                .into(),
        ));
    }
    Ok(())
}

pub async fn check(app: &AppHandle) -> Result<UpdateInfo> {
    let current = app.package_info().version.to_string();
    let found = app.updater()?.check().await?;
    Ok(match found {
        Some(update) => UpdateInfo {
            available: true,
            current_version: current,
            version: update.version,
            notes: update.body.unwrap_or_default(),
        },
        None => UpdateInfo {
            available: false,
            version: current.clone(),
            current_version: current,
            notes: String::new(),
        },
    })
}

pub async fn install(app: &AppHandle) -> Result<()> {
    refuse_if_not_self_updatable()?;
    refuse_if_mid_session()?;

    let updater = app
        .updater_builder()
        .on_before_exit(|| {
            let mut st = State::load();
            let _ = patch::repair(&mut st);
        })
        .build()?;

    let Some(update) = updater.check().await? else {
        return Err(Error::Other("No update is available.".into()));
    };
    refuse_if_mid_session()?;

    update
        .download_and_install(|_, _| {}, || {})
        .await
        .map_err(|e| explain_install_failure(e.into()))?;
    Ok(())
}
