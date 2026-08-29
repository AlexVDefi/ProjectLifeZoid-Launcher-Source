use crate::config;
use crate::error::Result;
use rusqlite::Connection;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SeedResult {
    pub server_created: bool,
    pub account_created: bool,
}

pub fn seed(name: &str, ip: &str, port: u16, username: &str) -> Result<Option<SeedResult>> {
    let db = config::server_list_db();
    if !db.is_file() {
        return Ok(None);
    }

    let mut conn = Connection::open(&db)?;
    let tx = conn.transaction()?;
    let existing = tx.query_row(
        "SELECT id FROM server WHERE ip = ?1 AND port = ?2 ORDER BY id DESC LIMIT 1",
        rusqlite::params![ip, port as i64],
        |row| row.get::<_, i64>(0),
    );
    let (server_id, server_created) = match existing {
        Ok(id) => {
            tx.execute(
                "UPDATE server SET name = ?1 WHERE id = ?2",
                rusqlite::params![name, id],
            )?;
            (id, false)
        }
        Err(rusqlite::Error::QueryReturnedNoRows) => {
            tx.execute(
                "INSERT INTO server (name, ip, port, serverPassword) VALUES (?1, ?2, ?3, '')",
                rusqlite::params![name, ip, port as i64],
            )?;
            (tx.last_insert_rowid(), true)
        }
        Err(e) => return Err(e.into()),
    };

    let account_count: i64 = tx.query_row(
        "SELECT COUNT(*) FROM account WHERE serverId = ?1 AND username = ?2",
        rusqlite::params![server_id, username],
        |row| row.get(0),
    )?;
    let account_created = account_count == 0;
    if account_created {
        tx.execute(
            "INSERT INTO account \
             (serverId, username, password, isSavePassword, isUseSteamRelay, authType, lastLogon) \
             VALUES (?1, ?2, '', 1, 0, 1, datetime('now'))",
            rusqlite::params![server_id, username],
        )?;
    } else {
        tx.execute(
            "UPDATE account SET password = '', isSavePassword = 1, isUseSteamRelay = 0, \
             authType = 1, lastLogon = datetime('now') WHERE serverId = ?1 AND username = ?2",
            rusqlite::params![server_id, username],
        )?;
    }
    tx.commit()?;
    Ok(Some(SeedResult {
        server_created,
        account_created,
    }))
}
