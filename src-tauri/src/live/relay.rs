use std::time::Duration;

use serde::Deserialize;

use crate::config;
use crate::error::{Error, Result};

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct Head {
    pub seq: i64,
    #[serde(rename = "segMs")]
    pub seg_ms: u64,
    pub ended: bool,
}

pub fn base_url() -> Option<String> {
    let url = std::env::var(config::LIVE_RELAY_URL_ENV)
        .ok()
        .filter(|u| !u.is_empty())
        .unwrap_or_else(|| config::DEFAULT_LIVE_RELAY_URL.to_string());
    let url = url.trim_end_matches('/').to_string();
    (url.starts_with("https://") || url.starts_with("http://")).then_some(url)
}

/// One per thread: a reqwest client keeps its pool on the runtime that made it, and every live
/// thread runs its own runtime.
pub struct Relay {
    base: String,
    client: reqwest::Client,
}

fn http(url: &str, status: reqwest::StatusCode) -> Error {
    Error::Http { url: url.to_string(), status: status.as_u16() }
}

impl Relay {
    pub fn new(base: String) -> Self {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(5))
            .timeout(Duration::from_secs(15))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self { base, client }
    }

    fn url(&self, stream: &str, tail: &str) -> String {
        format!("{}/v1/live/{stream}/{tail}", self.base)
    }

    pub async fn put_segment(&self, stream: &str, seq: u32, token: &str, body: Vec<u8>) -> Result<()> {
        let url = self.url(stream, &format!("seg/{seq}"));
        let resp = self.client.put(&url).bearer_auth(token).body(body).send().await?;
        if !resp.status().is_success() {
            return Err(http(&url, resp.status()));
        }
        Ok(())
    }

    pub async fn end(&self, stream: &str, token: &str) -> Result<()> {
        let url = self.url(stream, "end");
        let resp = self.client.post(&url).bearer_auth(token).send().await?;
        if !resp.status().is_success() {
            return Err(http(&url, resp.status()));
        }
        Ok(())
    }

    pub async fn head(&self, stream: &str) -> Result<Option<Head>> {
        let url = self.url(stream, "head");
        let resp = self.client.get(&url).header("Cache-Control", "no-cache").send().await?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if !resp.status().is_success() {
            return Err(http(&url, resp.status()));
        }
        let bytes = resp.bytes().await?;
        serde_json::from_slice(&bytes).map(Some).map_err(|e| Error::Other(format!("relay head: {e}")))
    }

    pub async fn segment(&self, stream: &str, seq: u32) -> Result<Option<Vec<u8>>> {
        let url = self.url(stream, &format!("seg/{seq}"));
        let resp = self.client.get(&url).send().await?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        if !resp.status().is_success() {
            return Err(http(&url, resp.status()));
        }
        Ok(Some(resp.bytes().await?.to_vec()))
    }
}

pub fn stream_of(token: &str) -> Option<&str> {
    let mut parts = token.split('.');
    let (Some("v1"), Some(stream), Some(exp), Some(mac), None) =
        (parts.next(), parts.next(), parts.next(), parts.next(), parts.next())
    else {
        return None;
    };
    let ok = stream.len() == 32
        && stream.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        && !exp.is_empty()
        && exp.bytes().all(|b| b.is_ascii_digit())
        && mac.len() == 64;
    ok.then_some(stream)
}

pub fn valid_stream(stream: &str) -> bool {
    stream.len() == 32 && stream.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[cfg(test)]
mod tests {
    use super::*;

    const VECTOR: &str = "v1.00112233445566778899aabbccddeeff.1700000000.db9199898b6e1f619160fb0e95c8ed8653372ab899beb18f13778b6334d6189a";

    #[test]
    fn the_stream_comes_out_of_a_token_and_nothing_else_does() {
        assert_eq!(stream_of(VECTOR), Some("00112233445566778899aabbccddeeff"));
        assert_eq!(stream_of("v2.00112233445566778899aabbccddeeff.1.aa"), None);
        assert_eq!(stream_of("v1.00112233445566778899AABBCCDDEEFF.1700000000.db9199898b6e1f619160fb0e95c8ed8653372ab899beb18f13778b6334d6189a"), None);
        assert_eq!(stream_of(&format!("{VECTOR}.x")), None);
        assert!(valid_stream("00112233445566778899aabbccddeeff"));
        assert!(!valid_stream("../../etc"));
    }

    #[test]
    fn the_test_signer_matches_the_relay_vector() {
        assert_eq!(super::super::token::sign("vector-secret", "00112233445566778899aabbccddeeff", 1700000000), VECTOR);
    }
}
