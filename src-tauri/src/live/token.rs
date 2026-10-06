use sha2::{Digest, Sha256};

/// Only the game server mints tokens in production; this exists for plzctl live-loopback.
pub fn sign(secret: &str, stream: &str, exp: u64) -> String {
    let mut key = [0u8; 64];
    if secret.len() > 64 {
        key[..32].copy_from_slice(&Sha256::digest(secret.as_bytes()));
    } else {
        key[..secret.len()].copy_from_slice(secret.as_bytes());
    }
    let pad = |byte: u8| key.iter().map(|k| k ^ byte).collect::<Vec<u8>>();
    let inner = Sha256::new()
        .chain_update(pad(0x36))
        .chain_update(format!("plz-live-v1\n{stream}\n{exp}"))
        .finalize();
    let mac = Sha256::new().chain_update(pad(0x5c)).chain_update(inner).finalize();
    format!("v1.{stream}.{exp}.{}", hex::encode(mac))
}
