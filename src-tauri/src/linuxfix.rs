//! The second workaround for a launcher window that opens white on Linux.
//!
//! The first one lives next door in `soften_webkit_rendering`, which has set
//! `WEBKIT_DISABLE_DMABUF_RENDERER=1` since before this file existed. That it is already shipped
//! is the reason this file is here: players still hitting a white window HAVE that fix, so
//! whatever is left is not the DMABUF renderer.
//!
//! A no-op everywhere but Linux, and written so a machine that never had the problem is left
//! exactly as it was.

/// Set once on the re-executed process so it cannot loop.
#[cfg(target_os = "linux")]
const REEXEC_MARKER: &str = "PLZ_LAUNCHER_WAYLAND_PRELOAD";

/// Escape hatch for a player the preload makes worse. Documented in the support reply.
#[cfg(target_os = "linux")]
const OPT_OUT: &str = "PLZ_LAUNCHER_NO_WAYLAND_PRELOAD";

#[cfg(not(target_os = "linux"))]
pub fn apply() {}

/// Must run before the dynamic loader matters, so it is one of the first things `run` does.
#[cfg(target_os = "linux")]
pub fn apply() {
    preload_system_wayland();
}

/// Re-run ourselves with the SYSTEM libwayland-client preloaded.
///
/// This is the workaround players found for themselves:
///
/// ```text
/// LD_PRELOAD=/usr/lib64/libwayland-client.so.0 ./ProjectLifeZoidLauncher_x.y.z_amd64.appimage
/// ```
///
/// The AppImage is built on Ubuntu 22.04 and carries that distribution's Wayland client library
/// with it. On a machine whose compositor is newer - `/usr/lib64` in the report above is a
/// Fedora/openSUSE layout, not Ubuntu's - the bundled copy is the older of the two and the window
/// never paints. Preloading the system one puts the pair back in step.
///
/// LD_PRELOAD is read by the dynamic loader at exec time, so it cannot be set from inside a
/// process that is already running: the only way to apply it to ourselves is to start again. That
/// is what this does, once, and only when every one of these holds:
///
/// - we are running from an AppImage at all (`APPIMAGE` is set by its runtime),
/// - the session is Wayland, so the library is actually in play,
/// - `LD_PRELOAD` is unset, so a player already doing this by hand is left alone,
/// - the marker is unset, so the re-executed process does not do it again,
/// - the opt-out is unset,
/// - and a system libwayland-client actually exists to point at.
///
/// Any of those missing, or the exec failing, and the launcher carries on exactly as before. The
/// cost when it does fire is one extra AppImage mount at startup.
#[cfg(target_os = "linux")]
fn preload_system_wayland() {
    use std::os::unix::process::CommandExt;

    if std::env::var_os(REEXEC_MARKER).is_some()
        || std::env::var_os(OPT_OUT).is_some()
        || std::env::var_os("LD_PRELOAD").is_some()
        || std::env::var_os("WAYLAND_DISPLAY").is_none()
    {
        return;
    }

    let Some(appimage) = std::env::var_os("APPIMAGE") else {
        return;
    };

    let Some(lib) = system_wayland_client() else {
        return;
    };

    eprintln!(
        "PLZ launcher: restarting with LD_PRELOAD={} to work around the blank-window bug. \
         Set {}=1 to skip this.",
        lib.display(),
        OPT_OUT
    );

    // exec replaces this process, so it only returns when it FAILED. Falling through is correct:
    // a launcher that opens white is still better than one that does not open.
    let error = std::process::Command::new(&appimage)
        .args(std::env::args_os().skip(1))
        .env(REEXEC_MARKER, "1")
        .env("LD_PRELOAD", &lib)
        .exec();
    eprintln!("PLZ launcher: that restart did not happen ({error}); carrying on without it.");
}

/// The distribution's own libwayland-client, in the places the major layouts put it.
#[cfg(target_os = "linux")]
fn system_wayland_client() -> Option<std::path::PathBuf> {
    const DIRS: [&str; 5] = [
        "/usr/lib64",
        "/usr/lib/x86_64-linux-gnu",
        "/usr/lib",
        "/lib64",
        "/lib/x86_64-linux-gnu",
    ];

    DIRS.iter()
        .map(|dir| std::path::Path::new(dir).join("libwayland-client.so.0"))
        .find(|path| path.is_file())
}
