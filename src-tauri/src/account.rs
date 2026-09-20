use crate::error::{Error, Result};

pub const MIN_LEN: usize = 2;
// 50 needs PLZ's ServerWorldDatabase shadow on the server. Vanilla refuses over 20 at login.
pub const MAX_LEN: usize = 50;

const FORBIDDEN: &[char] = &[';', '@', '$', ',', '\\', '/', '.', '\'', '?', '"', '#'];

fn reject(msg: &str) -> Error {
    Error::Other(msg.to_string())
}

pub fn validate(raw: &str) -> Result<String> {
    let name = raw.trim();

    if name.is_empty() {
        return Err(reject("Enter a username."));
    }
    if name.chars().count() < MIN_LEN {
        return Err(reject("That name is too short. Use at least 2 characters."));
    }
    if name.chars().count() > MAX_LEN {
        return Err(reject("That name is too long. Use at most 50 characters."));
    }
    if !name.is_ascii() {
        return Err(reject(
            "Use plain English letters and numbers. Accents and other scripts are refused by the server.",
        ));
    }
    if name.chars().any(|c| c.is_ascii_control()) {
        return Err(reject("That name contains a character the server refuses."));
    }
    if let Some(bad) = name.chars().find(|c| FORBIDDEN.contains(c)) {
        return Err(reject(&format!(
            "The server does not allow '{bad}' in a name."
        )));
    }
    if name.to_ascii_lowercase().starts_with("admin") {
        return Err(reject("Names starting with 'admin' are reserved."));
    }

    Ok(name.to_string())
}

#[cfg(test)]
mod tests {
    use super::validate;

    #[test]
    fn accepts_an_ordinary_name() {
        assert_eq!(validate("  Dave Two ").unwrap(), "Dave Two");
    }

    #[test]
    fn enforces_the_server_length_bounds() {
        assert!(validate("D").is_err());
        assert!(validate(&"D".repeat(50)).is_ok());
        assert!(validate(&"D".repeat(51)).is_err());
    }

    #[test]
    fn rejects_every_character_the_server_rejects() {
        for c in [';', '@', '$', ',', '\\', '/', '.', '\'', '?', '"'] {
            assert!(validate(&format!("Da{c}ve")).is_err(), "accepted {c:?}");
        }
    }

    #[test]
    fn rejects_hash_because_access_denied_reasons_split_on_it() {
        assert!(validate("Da##ve").is_err());
    }

    #[test]
    fn rejects_non_ascii_and_the_admin_prefix() {
        assert!(validate("Dàve").is_err());
        assert!(validate("admin").is_err());
        assert!(validate("AdminDave").is_err());
    }

    #[test]
    fn rejects_newlines_because_the_join_file_is_line_based() {
        assert!(validate("Da\nve").is_err());
        assert!(validate("Da\rve").is_err());
    }
}
