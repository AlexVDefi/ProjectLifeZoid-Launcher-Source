use crate::error::Result;
use std::path::Path;

/// A path spelled the way the JVM will read it back out of ProjectZomboid64.json.
///
/// On Windows pzexe hands the file's bytes to JNI_CreateJavaVM untouched and the JVM decodes
/// them with sun.jnu.encoding, which is the system ANSI codepage, never UTF-8. An accent
/// written as UTF-8 therefore arrives as two wrong characters: the classpath entry points at
/// nothing, java drops it without a word, and the session runs vanilla. 8.3 short names are
/// ASCII, which every codepage agrees on.
pub fn for_jvm(path: &Path) -> Result<String> {
    let long = path.to_string_lossy().replace('\\', "/");
    if long.is_ascii() {
        return Ok(long);
    }

    // Everywhere else the JVM reads the bytes as they were written.
    #[cfg(not(windows))]
    return Ok(long);

    #[cfg(windows)]
    {
        if let Some(short) = short_path(path) {
            let short = short.replace('\\', "/");
            if short.is_ascii() {
                return Ok(short);
            }
        }
        Err(crate::error::Error::PathNotAscii { path: long })
    }
}

/// The 8.3 form of a path that need not exist yet: the nearest ancestor that does is shortened
/// and the rest put back on. Everything the launcher asks about has an ASCII tail, so only an
/// ancestor can be carrying the accent.
#[cfg(windows)]
fn short_path(path: &Path) -> Option<String> {
    let mut tail = Vec::new();
    let mut base = path.to_path_buf();
    while !base.exists() {
        tail.push(base.file_name()?.to_os_string());
        if !base.pop() {
            return None;
        }
    }

    let mut out = std::path::PathBuf::from(short_path_of_existing(&base)?);
    for name in tail.into_iter().rev() {
        out.push(name);
    }
    Some(out.to_string_lossy().into_owned())
}

#[cfg(windows)]
fn short_path_of_existing(path: &Path) -> Option<String> {
    use std::ffi::OsString;
    use std::os::windows::ffi::{OsStrExt, OsStringExt};
    use windows::core::PCWSTR;
    use windows::Win32::Storage::FileSystem::GetShortPathNameW;

    let wide: Vec<u16> = path
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();

    let needed = unsafe { GetShortPathNameW(PCWSTR(wide.as_ptr()), None) };
    if needed == 0 {
        return None;
    }
    let mut buf = vec![0u16; needed as usize];
    let written = unsafe { GetShortPathNameW(PCWSTR(wide.as_ptr()), Some(&mut buf)) };
    if written == 0 || written as usize >= buf.len() {
        return None;
    }
    Some(
        OsString::from_wide(&buf[..written as usize])
            .to_string_lossy()
            .into_owned(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(windows)]
    use std::fs;

    #[test]
    fn an_ascii_path_is_handed_over_with_forward_slashes() {
        let path = Path::new(r"C:\Users\Player\AppData\Local\ProjectLifeZoidLauncher\patch\51");
        let out = for_jvm(path).unwrap();
        assert!(!out.contains('\\'), "{out}");
        assert!(out.ends_with("ProjectLifeZoidLauncher/patch/51"), "{out}");
    }

    #[cfg(windows)]
    #[test]
    fn an_accent_in_the_path_is_replaced_by_a_form_every_codepage_can_read() {
        let root = std::env::temp_dir().join(format!("plz-jvmpath-{}", std::process::id()));
        let dir = root.join("Emiliano Cárdenas").join("patch").join("51");
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&dir).unwrap();

        match for_jvm(&dir) {
            Ok(out) => {
                assert!(out.is_ascii(), "the JVM cannot read this back: {out}");
                assert_eq!(
                    fs::canonicalize(&out).unwrap(),
                    fs::canonicalize(&dir).unwrap(),
                    "the short form must name the same folder"
                );
            }
            // 8.3 name creation can be switched off per volume, and then there is no ASCII
            // spelling to find. Refusing is the point: the alternative is a silent vanilla
            // session that cannot join the server.
            Err(crate::error::Error::PathNotAscii { path }) => {
                assert!(path.contains("Cárdenas"), "{path}")
            }
            other => panic!("expected a short path or a refusal, got {other:?}"),
        }

        let _ = fs::remove_dir_all(&root);
    }

    #[cfg(windows)]
    #[test]
    fn a_file_that_does_not_exist_yet_is_shortened_through_its_parent() {
        let root = std::env::temp_dir().join(format!("plz-jvmstamp-{}", std::process::id()));
        let existing = root.join("Usário");
        let _ = fs::remove_dir_all(&root);
        fs::create_dir_all(&existing).unwrap();

        let stamp = existing.join("runtime").join("stamp.json");
        if let Ok(out) = for_jvm(&stamp) {
            assert!(out.is_ascii(), "{out}");
            assert!(out.ends_with("/runtime/stamp.json"), "{out}");
        }

        let _ = fs::remove_dir_all(&root);
    }
}
