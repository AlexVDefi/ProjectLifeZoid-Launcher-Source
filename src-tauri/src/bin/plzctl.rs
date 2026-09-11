use app_lib::{
    bootstrap, config, install, launch, patch, payload, query, state::State,
    workshop_override,
};

fn line(k: &str, v: impl std::fmt::Display) {
    println!("{k:<22} {v}");
}

fn workshop_proof_stage() -> app_lib::error::Result<()> {
    if launch::is_game_running() {
        return Err(app_lib::error::Error::GameAlreadyRunning);
    }

    let mut items = Vec::new();
    for value in std::env::args().skip(2) {
        let (item_id, timestamp) = value.split_once('=').ok_or_else(|| {
            app_lib::error::Error::Other(format!(
                "bad Workshop proof item '{value}'; expected ID=TIMESTAMP"
            ))
        })?;
        let item_id = item_id.parse::<u64>().map_err(|_| {
            app_lib::error::Error::Other(format!("bad Workshop item ID in '{value}'"))
        })?;
        let timestamp = timestamp.parse::<u64>().map_err(|_| {
            app_lib::error::Error::Other(format!("bad Workshop timestamp in '{value}'"))
        })?;
        items.push((item_id, timestamp));
    }
    let receipt = workshop_override::stage_proof(&items)?;
    line("proof pack", receipt.pack);
    line("Workshop items", receipt.item_count);
    line("pending receipt", receipt.path.display());
    println!(
        "\nThis is a local Phase 1 handshake proof, not a signed production pack. Run `plzctl play` to use it for one launcher session."
    );
    Ok(())
}

fn workshop_proof_status() -> app_lib::error::Result<()> {
    match workshop_override::pending()? {
        Some(receipt) => {
            line("pending pack", receipt.pack);
            line("pending items", receipt.item_count);
            line("pending receipt", receipt.path.display());
        }
        None => line("pending receipt", "absent"),
    }
    match workshop_override::active()? {
        Some(receipt) => {
            line("active pack", receipt.pack);
            line("active items", receipt.item_count);
            line("active session", receipt.session.unwrap_or_default());
            line("active receipt", receipt.path.display());
        }
        None => line("active receipt", "absent"),
    }
    Ok(())
}

fn workshop_proof_clear() -> app_lib::error::Result<()> {
    if launch::is_game_running() {
        return Err(app_lib::error::Error::GameAlreadyRunning);
    }

    let mut st = State::load();
    let restored = patch::repair(&mut st)?;
    let pending = workshop_override::clear_pending()?;
    let active = workshop_override::clear_active()?;
    line("launch config restored", restored);
    line("pending removed", pending);
    line("active removed", active);
    Ok(())
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    let cmd = std::env::args().nth(1).unwrap_or_else(|| "status".into());

    let result: app_lib::error::Result<()> = match cmd.as_str() {
        "status" => {
            let s = app_lib::status().await;
            match s {
                Ok(s) => {
                    line(
                        "install",
                        s.install_dir.clone().unwrap_or("NOT FOUND".into()),
                    );
                    line("jar sha256", s.jar_sha256.clone().unwrap_or("-".into()));
                    line(
                        "patch build",
                        s.build.map(|b| b.to_string()).unwrap_or("-".into()),
                    );
                    line("payload installed", s.payload_installed);
                    line(
                        "jar matches patch",
                        match s.jar_matches {
                            Some(true) => "yes",
                            Some(false) => "NO (stale)",
                            None => "unknown",
                        },
                    );
                    line("repaired on start", s.repaired_on_start);
                    line(
                        "steam id",
                        s.steam_id.clone().unwrap_or("NOT SIGNED IN".into()),
                    );
                    line(
                        "username",
                        match (&s.account_username, s.account_confirmed) {
                            (Some(n), true) => format!("{n} (confirmed by the server)"),
                            (Some(n), false) => format!("{n} (not joined yet)"),
                            (None, _) => "NOT SET -- run `plzctl name <yours>`".into(),
                        },
                    );
                    match bootstrap::read_join_result() {
                        Some(r) => line("last join", format!("{} {}", r.code, r.detail)),
                        None => line("last join", "-"),
                    }
                    line(
                        "steam launch opts",
                        s.steam_launch_options.clone().unwrap_or("(none)".into()),
                    );
                    if let Some(opt) = &s.debug_launch_option {
                        line(
                            "debug mode",
                            format!(
                                "{opt} -> {}",
                                if s.debug_allowed {
                                    "allowed (admin accounts only)"
                                } else {
                                    "BLOCKED -- run `plzctl debug on` if your account is admin"
                                }
                            ),
                        );
                    }
                    line(
                        "server",
                        format!(
                            "{}:{} (query {})",
                            s.server_host.clone().unwrap_or_default(),
                            s.server_connect_port.unwrap_or(0),
                            s.server_query_port.unwrap_or(0)
                        ),
                    );
                    match &s.last_stamp {
                        Some(st) => {
                            line("last stamp build", &st.build);
                            line("last stamp origin", &st.origin);
                        }
                        None => line("last stamp", "never"),
                    }
                    for p in &s.problems {
                        println!("\nPROBLEM: {p}");
                    }
                    Ok(())
                }
                Err(e) => Err(e),
            }
        }
        "server" => match payload::fetch_manifest().await {
            Ok(m) => {
                let sv = app_lib::effective_server(&m, &app_lib::state::State::load());
                match query::query(&sv.host, sv.query_port, 4000).await {
                Ok(s) => {
                    line("name", &s.name);
                    line("map", &s.map);
                    line("players", format!("{}/{}", s.players, s.max_players));
                    line("version", &s.version);
                    line("ping", format!("{} ms", s.ping_ms));
                    for (k, v) in &s.rules {
                        line(&format!("  rule {k}"), v);
                    }
                    match app_lib::server_build_from_rules(&s) {
                        Some(b) => line("plzpatch build", b),
                        None => line(
                            "plzpatch build",
                            "NOT PUBLISHED -- add PLZPATCH<n> to PublicDescription or PublicName",
                        ),
                    }
                    Ok(())
                }
                Err(e) => Err(e),
                }
            }
            Err(e) => Err(e),
        },
        "mods" => match payload::fetch_manifest().await {
            Ok(m) => {
                let report = app_lib::workshop::report(app_lib::requirements(&m));
                line("workshop items", report.total);
                line("installed", report.installed);
                line("downloading", report.downloading);
                line("missing", report.missing);
                line("steam says out of date", report.out_of_date);
                line("older than this release", report.behind_server);
                line("newer than this release", report.ahead_of_server);
                for req in &report.requirements {
                    line(
                        &format!("{} {}", req.kind, req.name),
                        format!(
                            "{}/{} installed{}",
                            req.installed,
                            req.total,
                            if req.verified { "" } else { "  (cannot be verified)" }
                        ),
                    );
                }
                for item in &report.mods {
                    let expected = m
                        .mods
                        .iter()
                        .chain(m.collection_mods.iter())
                        .find(|e| e.id == item.id)
                        .and_then(|e| e.time_updated);
                    line(
                        &format!("  {}", item.name),
                        format!(
                            "yours {} / release {}{}",
                            app_lib::workshop::describe_time(item.time_updated),
                            app_lib::workshop::describe_time(expected),
                            if item.behind_server { "  <-- BLOCKS PLAY" } else { "" }
                        ),
                    );
                }
                if report.behind_server > 0 {
                    println!(
                        "
Steam has not downloaded the release above. Leave the game closed and let it finish."
                    );
                }
                Ok(())
            }
            Err(e) => Err(e),
        },
        "sync" => match payload::fetch_manifest().await {
            Ok(m) => {
                line("build", m.build);
                line("built against jar", &m.built_against_jar_sha256);
                line("client files", m.files.len());
                line(
                    "server files",
                    format!(
                        "{} (deployed by hand, not by the launcher)",
                        m.server_files.len()
                    ),
                );
                for f in &m.server_files {
                    line("  server", &f.path);
                }
                match payload::sync(&m, &|msg: &str| line("syncing", msg)).await {
                    Ok(n) => {
                        line("downloaded", n);
                        line("installed", payload::is_installed(&m));
                        line("patch dir", config::patch_dir(m.build).display());
                        Ok(())
                    }
                    Err(e) => Err(e),
                }
            }
            Err(e) => Err(e),
        },
        "patch" => {
            let mut st = State::load();
            match (
                install::find_install(st.install_dir.as_deref()),
                payload::fetch_manifest().await,
            ) {
                (Ok(dir), Ok(m)) => match patch::apply(&mut st, &dir, m.build) {
                    Ok(()) => {
                        line("patched", install::json_path(&dir).display());
                        println!("\n--- ProjectZomboid64.json ---");
                        println!(
                            "{}",
                            std::fs::read_to_string(install::json_path(&dir)).unwrap_or_default()
                        );
                        println!("run `plzctl restore` to undo");
                        Ok(())
                    }
                    Err(e) => Err(e),
                },
                (Err(e), _) | (_, Err(e)) => Err(e),
            }
        }
        "debug" => {
            let mut st = State::load();
            match std::env::args().nth(2).as_deref() {
                Some("on") | Some("off") => {
                    st.allow_debug = std::env::args().nth(2).as_deref() == Some("on");
                    match st.save() {
                        Ok(()) => {
                            line("allow debug", st.allow_debug);
                            if st.allow_debug {
                                println!(
                                    "
Only the built-in `admin` role carries ConnectWithDebug. moderator and gm do not, and will still be dropped mid-join."
                                );
                            }
                            Ok(())
                        }
                        Err(e) => Err(e),
                    }
                }
                _ => {
                    line("allow debug", st.allow_debug);
                    line("usage", "plzctl debug on|off");
                    Ok(())
                }
            }
        }
        "bootstrap" => match bootstrap::install() {
            Ok(()) => {
                let root = config::launcher_mod_dir();
                line("mod dir", root.display());
                line(
                    "bootstrap",
                    root.join("media/lua/client/PLZLauncher/Bootstrap.lua")
                        .display(),
                );
                line(
                    "translations",
                    root.join("media/lua/shared/Translate/EN/UI.json").display(),
                );
                line(
                    "enabled in",
                    config::zomboid_home().join("mods/default.txt").display(),
                );
                Ok(())
            }
            Err(e) => Err(e),
        },
        "server-override" => {
            let mut st = State::load();
            let args: Vec<String> = std::env::args().skip(2).collect();
            match args.len() {
                0 => {
                    match &st.server_override {
                        Some(o) => {
                            line("override", format!("{}:{}", o.host, o.connect_port));
                            line("query port", o.query_port);
                        }
                        None => line("override", "none (using the signed manifest)"),
                    }
                    Ok(())
                }
                1 if args[0].eq_ignore_ascii_case("off") => {
                    st.server_override = None;
                    match st.save() {
                        Ok(()) => {
                            line("override", "cleared");
                            Ok(())
                        }
                        Err(e) => Err(e),
                    }
                }
                3 => {
                    let connect = args[1].parse::<u16>().unwrap_or(0);
                    let query = args[2].parse::<u16>().unwrap_or(0);
                    if connect == 0 || query == 0 {
                        eprintln!("both ports must be 1..65535");
                        std::process::exit(1);
                    }
                    st.server_override = Some(app_lib::state::ServerOverride {
                        host: args[0].clone(),
                        connect_port: connect,
                        query_port: query,
                    });
                    match st.save() {
                        Ok(()) => {
                            line("override", format!("{}:{}", args[0], connect));
                            line("query port", query);
                            Ok(())
                        }
                        Err(e) => Err(e),
                    }
                }
                _ => {
                    eprintln!("usage: plzctl server-override [<host> <connectPort> <queryPort> | off]");
                    std::process::exit(1);
                }
            }
        }
        "name" => {
            let st = app_lib::load_state_for_active_account();
            match std::env::args().nth(2) {
                Some(raw) => match app_lib::choose_username(&raw) {
                    Ok(name) => {
                        line("username", name);
                        Ok(())
                    }
                    Err(e) => Err(e),
                },
                None => {
                    line(
                        "username",
                        st.account_username.unwrap_or("NOT SET".into()),
                    );
                    line("confirmed", st.account_confirmed);
                    line(
                        "steam account",
                        st.identity_steam_id
                            .map(|id| id.to_string())
                            .unwrap_or("unknown".into()),
                    );
                    Ok(())
                }
            }
        }
        "install" => {
            let mut st = State::load();
            match std::env::args().nth(2).as_deref() {
                Some("auto") => {
                    st.install_dir = None;
                    st.jar = None;
                    match st.save() {
                        Ok(()) => {
                            line(
                                "detected",
                                install::find_install(None)
                                    .map(|p| p.display().to_string())
                                    .unwrap_or("NOT FOUND".into()),
                            );
                            Ok(())
                        }
                        Err(e) => Err(e),
                    }
                }
                Some(path) => match install::resolve_chosen(std::path::Path::new(path)) {
                    Ok(dir) => {
                        st.install_dir = Some(dir.clone());
                        st.jar = None;
                        match st.save() {
                            Ok(()) => {
                                line("install", dir.display());
                                Ok(())
                            }
                            Err(e) => Err(e),
                        }
                    }
                    Err(e) => Err(e),
                },
                None => {
                    line(
                        "chosen",
                        st.install_dir
                            .map(|p| p.display().to_string())
                            .unwrap_or("none (detecting)".into()),
                    );
                    line(
                        "detected",
                        install::find_install(None)
                            .map(|p| p.display().to_string())
                            .unwrap_or("NOT FOUND".into()),
                    );
                    line("usage", "plzctl install <folder> | plzctl install auto");
                    Ok(())
                }
            }
        }
        "restore" => {
            let mut st = State::load();
            match patch::repair(&mut st) {
                Ok(did) => {
                    line("restored", did);
                    Ok(())
                }
                Err(e) => Err(e),
            }
        }
        "stamp" => {
            match patch::read_stamp() {
                Some(s) => {
                    line("build", &s.build);
                    line("first class", &s.first_class);
                    line("origin", &s.origin);
                    line("classpath", &s.classpath);
                }
                None => line("stamp", "absent"),
            }
            Ok(())
        }
        "running" => {
            line("game running", launch::is_game_running());
            Ok(())
        }
        "workshop-proof-stage" => workshop_proof_stage(),
        "workshop-proof-status" => workshop_proof_status(),
        "workshop-proof-clear" => workshop_proof_clear(),
        "play" => match app_lib::run_play(&|step, detail| println!("[{step:<9}] {detail}")).await {
            Ok(r) => {
                line("launched", r.launched);
                line("patch verified", r.patch_verified);
                line("restored", r.restored);
                if let Some(s) = &r.stamp {
                    line("stamp origin", &s.origin);
                    line("stamp classpath", &s.classpath);
                }
                if let Some(name) = &r.bound_username {
                    line("adopted name", name);
                }
                if let Some(e) = &r.join_error {
                    println!(
                        "
JOIN REFUSED: {e}"
                    );
                }
                for n in &r.notes {
                    println!("NOTE: {n}");
                }
                Ok(())
            }
            Err(e) => Err(e),
        },
        other => {
            eprintln!("unknown command: {other}");
            eprintln!(
                "try: status | name [value] | install [folder|auto] | debug on|off | bootstrap | server | mods | sync | patch | restore | stamp | running | workshop-proof-stage ID=TIMESTAMP [...] | workshop-proof-status | workshop-proof-clear | play"
            );
            std::process::exit(2);
        }
    };

    if let Err(e) = result {
        eprintln!("\nERROR: {e}");
        std::process::exit(1);
    }
}
