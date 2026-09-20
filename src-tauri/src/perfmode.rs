//! Performance mode: a reversible preset written into the game's options.ini.
//!
//! The launcher is the only place this can be done safely. Core.saveOptions() rewrites the
//! whole of options.ini from its in-memory option set, so a write only sticks if the game
//! loads it afterwards -- which is exactly what pressing Play does. Several of the keys here
//! are the ones the options screen marks "requires restart" for the same reason.
//!
//! Every value below was checked against the decompiled engine, not against the options
//! screen labels, because three of the obvious candidates turned out to be dead in B42 and
//! one of them does something quite different from what its label says. See NOT_INCLUDED.

use crate::config;
use crate::error::Result;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::Path;

/// Keys the preset owns, and what it sets them to.
///
/// Anything not listed is left exactly as the player had it. In particular the whole display
/// block -- width, height, fullScreen, vsync, frameRate -- is the player's monitor setup and
/// is none of our business.
///
/// The ordering is meaningless; options.ini is alphabetised by the game on every save.
pub const PROFILE: &[(&str, &str)] = &[
    // The single biggest win, and the reason this toggle exists. IsoPuddles.render draws one
    // shaded quad per visible exterior ground square every frame for as long as the ground is
    // wet, and at perfPuddles=0 it does it on every z level, not just the ground:
    //   if (z <= 0 || getPerfPuddles() <= 0)
    // Anything >= 1 restricts it to z <= 0, and >= 2 also skips the eight-neighbour rut scan
    // in IsoPuddlesGeometry.init. Ground Only keeps puddles visible and drops both.
    ("perfPuddles", "2"),
    // Medium, NOT Low. Low is puddlesQuality == 2, the only value that reaches the
    // FBORenderCell branch calling invalidateAll() once a second -- throwing away every cached
    // chunk texture for as long as the ground is wet. Medium keeps the live shader path.
    ("puddles", "1"),
    // water_hq -> water. Still a shader, half the work.
    ("water", "1"),
    // Outdoors only. Skips the extra full-screen FBO composite that draws precipitation
    // through the weather mask when you are inside. Rain outdoors is untouched.
    ("renderPrecipitation", "2"),
    // 3D ground items stay 3D. This only picks the lower-resolution item atlas tier:
    //   bMaxZoomIsOne = !getOptionHighResPlacedItems() || zoom >= 0.75F
    ("highResPlacedItems", "false"),
    // Pinned on deliberately. isOption3DGroundItem() is the switch that makes ground items
    // flat sprites, and it must never be what this toggle does.
    ("3DGroundItem", "true"),
    // Declared default is false. Read once at boot into TextureID.useCompressionOption.
    ("textureCompression", "true"),
    // 2048 -> 1024 for textures flagged 128/256, via Core.getMaxTextureSizeFromFlags.
    ("maxTextureSize", "3"),
    // 512, which is the stock default. Pinned so a player who raised it comes back down.
    ("maxVehicleTextureSize", "2"),
    // Zombies Only. Blood, decals, holes and patches stop being composited into zombie
    // clothing textures. Players keep full detail, so your own character still looks right.
    ("simpleClothingTextures", "2"),
    ("simpleWeaponTextures", "true"),
    ("corpseShadows", "false"),
    ("bloodDecals", "5"),
    // 20 -> 10 concurrent ragdoll simulations.
    ("maxActiveRagdolls", "10"),
    ("uiRenderFPS", "30"),
    // Medium, the stock default.
    ("perfSkybox", "1"),
];

// Deliberately left out of PROFILE, with the reason, so nobody "completes" the list later:
//
//   texture2x            Looks like the texture-resolution lever and is not. GameWindow sets
//                        `Core.tileScale = getOptionTexture2x() ? 2 : 1`, and tileScale sizes
//                        world geometry throughout the renderer. Turning it off halves the
//                        resolution of the world itself, which is the opposite of keeping
//                        visual quality.
//   bPerfReflections     Dead in B42. getPerfReflections() has no caller outside Core.
//   renderPrecipIndoors  Dead in B42. No caller anywhere.
//   fogQuality           Its three values are High / Medium / Legacy (Build 40), not a
//                        performance ladder -- Legacy selects the old particle fog rather
//                        than a cheaper one. Which is faster is unmeasured, so it is left be.
//   modelTextureMipmaps  Its own tooltip says it trades more memory for speed. On the
//                        VRAM-limited machines this toggle is aimed at, that is backwards.

/// What the preset did, so it can be undone.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Applied {
    /// The value the launcher wrote, per key.
    pub written: BTreeMap<String, String>,
    /// What was there before. `None` means the key was absent from the file.
    pub previous: BTreeMap<String, Option<String>>,
}

/// Split `key=value`, tolerating the whitespace the game never writes but a hand edit might.
fn split_line(line: &str) -> Option<(&str, &str)> {
    let trimmed = line.trim_start();
    if trimmed.starts_with('#') || trimmed.starts_with(';') {
        return None;
    }
    let (key, value) = trimmed.split_once('=')?;
    Some((key.trim(), value.trim()))
}

/// Read every `key=value` in the file. Later lines win, matching ConfigFile's own parse order.
pub fn read_values(text: &str) -> BTreeMap<String, String> {
    let mut out = BTreeMap::new();
    for line in text.lines() {
        if let Some((key, value)) = split_line(line) {
            out.insert(key.to_string(), value.to_string());
        }
    }
    out
}

/// Rewrite only the keys in `changes`, leaving every other line byte-identical.
///
/// A key mapped to `None` is removed if present, which is how a key that was absent before the
/// preset gets put back. Keys not already in the file are appended, which is safe because the
/// game re-sorts the whole file the next time it saves.
pub fn merge(text: &str, changes: &BTreeMap<String, Option<String>>) -> String {
    let mut seen: BTreeMap<&str, bool> = changes.keys().map(|k| (k.as_str(), false)).collect();
    let mut out = String::with_capacity(text.len() + 256);

    for line in text.split_inclusive('\n') {
        let body = line.trim_end_matches(['\n', '\r']);
        let ending = &line[body.len()..];

        match split_line(body).and_then(|(key, _)| changes.get_key_value(key)) {
            Some((key, Some(value))) => {
                seen.insert(key.as_str(), true);
                out.push_str(key);
                out.push('=');
                out.push_str(value);
                // A file whose last line has no newline keeps not having one.
                out.push_str(ending);
            }
            // Removal: drop the line entirely.
            Some((key, None)) => {
                seen.insert(key.as_str(), true);
            }
            None => out.push_str(line),
        }
    }

    let missing: Vec<String> = seen
        .iter()
        .filter(|(key, found)| !**found && changes[**key].is_some())
        .map(|(key, _)| (*key).to_string())
        .collect();

    if !missing.is_empty() {
        if !out.is_empty() && !out.ends_with('\n') {
            out.push_str("\r\n");
        }
        for key in missing {
            out.push_str(&key);
            out.push('=');
            out.push_str(changes[&key].as_deref().unwrap_or_default());
            out.push_str("\r\n");
        }
    }

    out
}

/// The values to write: the manifest's profile when it carries one, otherwise the baked one.
///
/// Manifest-driven on purpose. These numbers are reasoned from the engine source but not yet
/// profiled, and retuning them should not need a launcher release for every player.
pub fn profile_values(from_manifest: Option<&BTreeMap<String, String>>) -> BTreeMap<String, String> {
    match from_manifest {
        Some(values) if !values.is_empty() => values.clone(),
        _ => PROFILE
            .iter()
            .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
            .collect(),
    }
}

/// Write the preset, returning what to keep so it can be undone.
pub fn apply(values: &BTreeMap<String, String>) -> Result<Applied> {
    apply_at(&config::options_ini_path(), values)
}

/// Put back what was there before the preset was turned on.
pub fn revert(applied: &Applied) -> Result<usize> {
    revert_at(&config::options_ini_path(), applied)
}

/// True when every key the preset owns still holds the value it wrote.
pub fn is_active(applied: &Applied) -> bool {
    is_active_at(&config::options_ini_path(), applied)
}

pub fn apply_at(path: &Path, values: &BTreeMap<String, String>) -> Result<Applied> {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    let current = read_values(&text);

    let mut applied = Applied::default();
    let mut changes: BTreeMap<String, Option<String>> = BTreeMap::new();

    for (key, value) in values {
        applied
            .previous
            .insert(key.clone(), current.get(key).cloned());
        applied.written.insert(key.clone(), value.clone());
        changes.insert(key.clone(), Some(value.clone()));
    }

    write(path, &merge(&text, &changes))?;
    Ok(applied)
}

/// A key whose current value is no longer the one we wrote was changed by the player in the
/// in-game options screen since. Their choice wins: restoring the snapshot over the top of a
/// deliberate change would be the toggle silently undoing the player's own settings.
pub fn revert_at(path: &Path, applied: &Applied) -> Result<usize> {
    let text = std::fs::read_to_string(path).unwrap_or_default();
    let current = read_values(&text);

    let mut changes: BTreeMap<String, Option<String>> = BTreeMap::new();
    for (key, written) in &applied.written {
        if current.get(key).map(String::as_str) != Some(written.as_str()) {
            continue;
        }
        changes.insert(
            key.clone(),
            applied.previous.get(key).cloned().unwrap_or(None),
        );
    }

    let restored = changes.len();
    if restored > 0 {
        write(path, &merge(&text, &changes))?;
    }
    Ok(restored)
}

pub fn is_active_at(path: &Path, applied: &Applied) -> bool {
    if applied.written.is_empty() {
        return false;
    }
    let text = match std::fs::read_to_string(path) {
        Ok(text) => text,
        Err(_) => return false,
    };
    let current = read_values(&text);
    applied
        .written
        .iter()
        .all(|(key, value)| current.get(key) == Some(value))
}

/// Write via a temp file in the same directory, so a crash mid-write cannot leave the player
/// with a truncated options.ini and a game that boots into a default display mode.
fn write(path: &Path, text: &str) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let tmp = path.with_extension("ini.plztmp");
    std::fs::write(&tmp, text)?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn changes(pairs: &[(&str, Option<&str>)]) -> BTreeMap<String, Option<String>> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), v.map(str::to_string)))
            .collect()
    }

    #[test]
    fn merge_rewrites_only_the_named_keys() {
        let text = "version=8\r\nperfPuddles=0\r\nzoom=true\r\n";
        let out = merge(text, &changes(&[("perfPuddles", Some("2"))]));
        assert_eq!(out, "version=8\r\nperfPuddles=2\r\nzoom=true\r\n");
    }

    #[test]
    fn merge_appends_a_key_that_is_not_there_yet() {
        let out = merge("version=8\r\n", &changes(&[("puddles", Some("1"))]));
        assert_eq!(out, "version=8\r\npuddles=1\r\n");
    }

    #[test]
    fn merge_removes_a_key_mapped_to_none() {
        let text = "version=8\r\npuddles=1\r\nzoom=true\r\n";
        let out = merge(text, &changes(&[("puddles", None)]));
        assert_eq!(out, "version=8\r\nzoom=true\r\n");
    }

    #[test]
    fn merge_keeps_a_missing_trailing_newline_missing() {
        let out = merge("zoom=true", &changes(&[("zoom", Some("false"))]));
        assert_eq!(out, "zoom=false");
    }

    #[test]
    fn merge_leaves_comments_and_blank_lines_alone() {
        let text = "# mine\r\n\r\nperfPuddles=0\r\n";
        let out = merge(text, &changes(&[("perfPuddles", Some("2"))]));
        assert_eq!(out, "# mine\r\n\r\nperfPuddles=2\r\n");
    }

    fn scratch(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("plz-perf-{}-{}", std::process::id(), tag));
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("options.ini")
    }

    fn preset(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs
            .iter()
            .map(|(k, v)| ((*k).to_string(), (*v).to_string()))
            .collect()
    }

    /// The round trip that makes the toggle safe to flip: on, then off, gets the file back
    /// byte for byte, including a key that was not in the file before the preset added it.
    #[test]
    fn apply_then_revert_restores_the_file_exactly() {
        let path = scratch("roundtrip");
        let original = "version=8\r\nperfPuddles=0\r\nzoom=true\r\n";
        std::fs::write(&path, original).unwrap();

        let applied = apply_at(&path, &preset(&[("perfPuddles", "2"), ("puddles", "1")])).unwrap();
        assert!(is_active_at(&path, &applied));

        let on = std::fs::read_to_string(&path).unwrap();
        assert_eq!(read_values(&on).get("perfPuddles").unwrap(), "2");
        assert_eq!(read_values(&on).get("puddles").unwrap(), "1");

        assert_eq!(revert_at(&path, &applied).unwrap(), 2);
        // perfPuddles is back at 0 and puddles, absent to begin with, is gone again.
        assert_eq!(std::fs::read_to_string(&path).unwrap(), original);
        assert!(!is_active_at(&path, &applied));
    }

    /// A player who changed one of our keys in the game's own options keeps their change.
    #[test]
    fn revert_leaves_a_key_the_player_has_since_changed() {
        let path = scratch("playeredit");
        std::fs::write(&path, "perfPuddles=0\r\npuddles=0\r\n").unwrap();

        let applied = apply_at(&path, &preset(&[("perfPuddles", "2"), ("puddles", "1")])).unwrap();

        // The player then picks High puddles themselves, in game.
        let edited = merge(
            &std::fs::read_to_string(&path).unwrap(),
            &changes(&[("puddles", Some("0"))]),
        );
        std::fs::write(&path, &edited).unwrap();
        assert!(!is_active_at(&path, &applied));

        // Only perfPuddles is rolled back; their puddles=0 survives untouched.
        assert_eq!(revert_at(&path, &applied).unwrap(), 1);
        let after = read_values(&std::fs::read_to_string(&path).unwrap());
        assert_eq!(after.get("perfPuddles").map(String::as_str), Some("0"));
        assert_eq!(after.get("puddles").map(String::as_str), Some("0"));
    }

    /// A player with no options.ini yet (fresh install) gets a file, not an error.
    #[test]
    fn apply_works_when_there_is_no_options_file() {
        let path = scratch("nofile");
        let _ = std::fs::remove_file(&path);

        let applied = apply_at(&path, &preset(&[("perfPuddles", "2")])).unwrap();
        assert!(is_active_at(&path, &applied));
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "perfPuddles=2\r\n");

        revert_at(&path, &applied).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "");
    }

    /// The whole baked profile against a realistic file: nothing outside the profile moves.
    #[test]
    fn the_full_profile_touches_only_its_own_keys() {
        let path = scratch("fullprofile");
        let original = "version=8\r\nwidth=2560\r\nheight=1019\r\nfullScreen=false\r\n\
                        frameRate=165\r\nvsync=false\r\nperfPuddles=0\r\npuddles=0\r\n\
                        3DGroundItem=true\r\nsoundVolume=5\r\n";
        std::fs::write(&path, original).unwrap();

        let applied = apply_at(&path, &profile_values(None)).unwrap();
        let after = read_values(&std::fs::read_to_string(&path).unwrap());

        // The display block is the player's and must be untouched.
        for key in ["width", "height", "fullScreen", "frameRate", "vsync", "soundVolume"] {
            assert_eq!(
                after.get(key),
                read_values(original).get(key),
                "{key} must not be changed by the preset"
            );
        }
        assert_eq!(after.get("perfPuddles").map(String::as_str), Some("2"));
        assert_eq!(after.get("3DGroundItem").map(String::as_str), Some("true"));

        revert_at(&path, &applied).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), original);
    }

    #[test]
    fn the_baked_profile_keeps_3d_items_on() {
        let values = profile_values(None);
        assert_eq!(values.get("3DGroundItem").map(String::as_str), Some("true"));
        // The lever that would have flattened them must not be in the profile at all.
        assert!(!values.contains_key("texture2x"));
    }

    #[test]
    fn a_manifest_profile_replaces_the_baked_one() {
        let from_manifest: BTreeMap<String, String> =
            [("perfPuddles".to_string(), "3".to_string())]
                .into_iter()
                .collect();
        let values = profile_values(Some(&from_manifest));
        assert_eq!(values.len(), 1);
        assert_eq!(values.get("perfPuddles").map(String::as_str), Some("3"));
    }

    #[test]
    fn an_empty_manifest_profile_falls_back_to_the_baked_one() {
        let empty = BTreeMap::new();
        assert_eq!(profile_values(Some(&empty)), profile_values(None));
    }
}
