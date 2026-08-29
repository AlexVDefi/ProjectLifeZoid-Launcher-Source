fn main() {
    println!("cargo:rerun-if-env-changed=PLZ_MANIFEST_URL_BAKED");
    tauri_build::build()
}
