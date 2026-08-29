use base64::Engine;
use std::path::PathBuf;

fn repo() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("src-tauri has a parent")
        .to_path_buf()
}

fn decode(field: &str) -> String {
    let raw = base64::engine::general_purpose::STANDARD
        .decode(field.trim())
        .expect("field is base64");
    String::from_utf8(raw).expect("decodes to utf8")
}

#[test]
fn the_built_installer_verifies_against_the_baked_pubkey() {
    let latest = repo().join("update").join("latest.json");
    if !latest.exists() {
        eprintln!("no update/latest.json -- run tools/package.ps1 first; skipping");
        return;
    }

    let conf: serde_json::Value =
        serde_json::from_slice(&std::fs::read(repo().join("src-tauri").join("tauri.conf.json")).unwrap())
            .unwrap();
    let pubkey = conf["plugins"]["updater"]["pubkey"]
        .as_str()
        .expect("tauri.conf.json carries an updater pubkey");

    let manifest: serde_json::Value = serde_json::from_slice(&std::fs::read(&latest).unwrap()).unwrap();
    let platform = &manifest["platforms"]["windows-x86_64"];
    let signature = platform["signature"].as_str().expect("signature present");
    let url = platform["url"].as_str().expect("url present");

    let name = url.rsplit('/').next().unwrap();
    let bytes = std::fs::read(repo().join("site").join("download").join(name))
        .unwrap_or_else(|e| panic!("{name} is named by latest.json but missing locally: {e}"));

    let key = minisign_verify::PublicKey::decode(&decode(pubkey)).expect("pubkey decodes");
    let sig = minisign_verify::Signature::decode(&decode(signature)).expect("signature decodes");
    key.verify(&bytes, &sig, true)
        .expect("the installer does NOT verify against the pubkey compiled into the launcher");

    let offered = manifest["version"].as_str().expect("version present");
    assert_eq!(
        offered,
        conf["version"].as_str().unwrap(),
        "latest.json offers a different version than this build"
    );
}

#[test]
fn the_endpoint_document_parses_as_the_plugin_reads_it() {
    let latest = repo().join("update").join("latest.json");
    if !latest.exists() {
        eprintln!("no update/latest.json -- run tools/package.ps1 first; skipping");
        return;
    }

    let release: tauri_plugin_updater::RemoteRelease =
        serde_json::from_slice(&std::fs::read(&latest).unwrap())
            .expect("latest.json does not deserialize the way the plugin reads it");

    let target = tauri_plugin_updater::target().expect("a supported target");
    release
        .download_url(&target)
        .unwrap_or_else(|e| panic!("no download url for {target}: {e}"));
    release
        .signature(&target)
        .unwrap_or_else(|e| panic!("no signature for {target}: {e}"));
    assert!(release.pub_date.is_some(), "pub_date did not parse as RFC3339");
}

#[test]
fn the_published_files_verify_the_way_the_download_page_says() {
    let download = repo().join("site").join("download");
    if !download.is_dir() {
        eprintln!("no site/download -- run tools/package.ps1 first; skipping");
        return;
    }

    let installer = std::fs::read_dir(&download)
        .unwrap()
        .filter_map(|e| e.ok().map(|e| e.path()))
        .find(|p| p.extension().is_some_and(|e| e == "exe"))
        .expect("an installer in site/download");

    let sig_path = installer.with_file_name(format!(
        "{}.minisig",
        installer.file_name().unwrap().to_string_lossy()
    ));
    assert!(
        sig_path.exists(),
        "minisign looks for {}, which is not published",
        sig_path.display()
    );

    let key_text = std::fs::read_to_string(download.join("plz-launcher-updater.pub")).unwrap();
    let sig_text = std::fs::read_to_string(&sig_path).unwrap();

    let key = minisign_verify::PublicKey::decode(&key_text)
        .expect("the published .pub is not in the form minisign reads");
    let sig = minisign_verify::Signature::decode(&sig_text)
        .expect("the published .minisig is not in the form minisign reads");
    key.verify(&std::fs::read(&installer).unwrap(), &sig, true)
        .expect("the published installer does not verify against the published key");

    let sums = std::fs::read_to_string(download.join("SHA256SUMS.txt")).unwrap();
    let claimed = sums.split_whitespace().next().expect("a hash in SHA256SUMS.txt");
    let actual = hex_digest(&std::fs::read(&installer).unwrap());
    assert_eq!(claimed, actual, "SHA256SUMS.txt does not match the published installer");
}

fn hex_digest(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}
