use crate::config;
use crate::error::{Error, Result};
use crate::state;
use std::fs;

const MOD_INFO: &str = "name=PLZ Launcher Bootstrap\nid=PLZLauncher\ndescription=One-shot direct-connect handoff for the PLZ Launcher.\n";

const REASON_TRANSLATIONS: &str = r#"{
  "UI_OnConnectFailed_PLZNameTaken": "That username is already taken on this server. Pick a different one in the ProjectLifeZoid Launcher.",
  "UI_OnConnectFailed_PLZWrongCharacter": "This Steam account already plays on ProjectLifeZoid as %1",
  "UI_OnConnectFailed_PLZNotApproved": "This Steam account has not been approved for ProjectLifeZoid yet. Ask an admin to add you."
}
"#;

const BOOTSTRAP_LUA: &str = r#"local JOIN_FILE = "PLZLauncher/join.txt"
local RESULT_FILE = "PLZLauncher/result.txt"
local ROLE_FILE = "PLZLauncher/role.txt"
local consumed = false
local armed = false
local reported = false
local ours = false

local function readIntent()
    local reader = getFileReader(JOIN_FILE, false)
    if not reader then return nil end
    local intent = {
        host = reader:readLine(),
        port = reader:readLine(),
        username = reader:readLine(),
        serverPassword = reader:readLine(),
        serverName = reader:readLine(),
    }
    reader:close()
    if not intent.host or intent.host == "" then return nil end
    return intent
end

local function clearIntent()
    local writer = getFileWriter(JOIN_FILE, true, false)
    writer:write("")
    writer:close()
end

local function ensureServer(intent)
    local servers = getServerList()
    for i = 1, #servers do
        local server = servers[i]
        if server:getIp() == intent.host and tostring(server:getPort()) == intent.port then
            return server
        end
    end

    local server = Server.new()
    server:setName(intent.serverName or "Project Life Zoid")
    server:setIp(intent.host)
    server:setPort(tonumber(intent.port))
    server:setServerPassword(intent.serverPassword or "")
    addServerToAccountList(server)
    return server
end

local function ensureAccount(server, username)
    local accounts = server:getAccounts()
    for i = 0, accounts:size() - 1 do
        local account = accounts:get(i)
        if account:getUserName() == username then
            return account
        end
    end

    local account = Account.new()
    account:setUserName(username)
    account:setPwd("")
    account:setSavePwd(true)
    account:setUseSteamRelay(false)
    account:setAuthType(1)
    account:setLastLogonNow()
    addAccountToAccountList(server, account)
    return account
end

local function writeResult(code, detail)
    if reported then return end
    reported = true
    local writer = getFileWriter(RESULT_FILE, true, false)
    if not writer then return end
    writer:write(code .. "\n" .. (detail or "") .. "\n")
    writer:close()
end

local PLZ_REASONS = { "PLZWrongCharacter", "PLZNameTaken", "PLZNotApproved", "InvalidUsername",
                      "InvalidUsernamePassword", "InvalidServerPassword", "UnknownUsername",
                      "DuplicateAccount", "MaxAccountsReached", "DebugNotAllowed" }
local SENTINEL = string.char(1)

local function classify(message)
    for i = 1, #PLZ_REASONS do
        local key = PLZ_REASONS[i]
        local rendered = getText("UI_OnConnectFailed_" .. key, SENTINEL)
        local head, tail = string.match(rendered, "^(.-)" .. SENTINEL .. "(.*)$")
        if head and (#head > 0 or #tail > 0) then
            local room = #message - #head - #tail
            if room > 0
                and string.sub(message, 1, #head) == head
                and (#tail == 0 or string.sub(message, -#tail) == tail)
            then
                return key, string.sub(message, #head + 1, #head + room)
            end
        elseif not head and message == rendered then
            return key, nil
        end
    end
    return nil, nil
end

local function onConnectFailed(message)
    if not armed or not message then return end
    local key, detail = classify(message)
    writeResult(key or "Other", detail or message)
end

local function onConnected()
    if not armed then return end
    armed = false
    writeResult("OK", "")
end

local function writeRole()
    if not ours then return end
    if not isClient() then return end
    if not haveAccess then return end
    local ok, granted = pcall(haveAccess, "ConnectWithDebug")
    if not ok then return end
    local name = ""
    if getAccessLevel then
        local okName, value = pcall(getAccessLevel)
        if okName and value then name = value end
    end
    local writer = getFileWriter(ROLE_FILE, true, false)
    if not writer then return end
    writer:write((granted and "1" or "0") .. "\n" .. name .. "\n")
    writer:close()
end

local function join()
    local intent = readIntent()
    if not intent then return end
    clearIntent()
    armed = true
    ours = true

    local server = ensureServer(intent)
    local account = ensureAccount(server, intent.username)

    server:setName(intent.serverName or "Project Life Zoid")
    server:setServerPassword(intent.serverPassword or "")
    account:setPwd("")
    account:setSavePwd(true)
    account:setUseSteamRelay(false)
    account:setAuthType(1)
    account:setLastLogonNow()
    getCore():setAccountUsed(account)
    updateAccountToAccountList(account)
    stopSendSecretKey()
    getCore():setNoSave(false)

    ConnectToServer.instance.loadingBackground = server:getServerLoadingScreen()
    ConnectToServer.instance:connect(
        MainScreen.instance,
        server:getName(),
        account:getUserName(),
        account:getPwd(),
        server:getIp(),
        "",
        tostring(server:getPort()),
        server:getServerPassword(),
        false,
        false,
        account:getAuthType()
    )
end

local WELCOME_TICKS = 90
local welcomeTicks = 0
local welcomePanel = nil

local function removeWelcome()
    if welcomePanel then
        pcall(function() welcomePanel:removeFromUIManager() end)
        welcomePanel = nil
    end
end

local function showWelcome()
    if not ISPanel or not getCore then return false end
    local ok = pcall(function()
        local w, h = getCore():getScreenWidth(), getCore():getScreenHeight()
        local panel = ISPanel:new(0, 0, w, h)
        panel:initialise()
        panel.backgroundColor = { r = 0, g = 0, b = 0, a = 0.82 }
        panel.borderColor = { r = 0, g = 0, b = 0, a = 0 }
        panel.render = function(self)
            ISPanel.render(self)
            local cx = self:getWidth() / 2
            local cy = self:getHeight() / 2
            self:drawTextCentre("Welcome to Project Life Zoid!", cx, cy - 24, 1, 1, 1, 1, UIFont.Large)
            self:drawTextCentre(
                "You are being connected to the server, please wait...",
                cx, cy + 10, 0.8, 0.8, 0.8, 1, UIFont.Medium
            )
        end
        panel:addToUIManager()
        panel:setAlwaysOnTop(true)
        welcomePanel = panel
    end)
    return ok and welcomePanel ~= nil
end

local function onFrontEndTick()
    if welcomeTicks <= 0 then return end
    welcomeTicks = welcomeTicks - 1
    if welcomeTicks > 0 then return end
    removeWelcome()
    local ok, err = pcall(join)
    if not ok then
        armed = true
        writeResult("BootstrapError", tostring(err))
    end
end

local function onMainMenuEnter()
    if consumed then return end
    consumed = true
    if not readIntent() then return end
    if showWelcome() then
        welcomeTicks = WELCOME_TICKS
        return
    end
    local ok, err = pcall(join)
    if not ok then
        armed = true
        writeResult("BootstrapError", tostring(err))
    end
end

Events.OnMainMenuEnter.Add(onMainMenuEnter)
Events.OnFETick.Add(onFrontEndTick)
Events.OnConnectFailed.Add(onConnectFailed)
Events.OnConnected.Add(onConnected)
Events.OnGameStart.Add(writeRole)
"#;

fn enable_in_default_mods() -> Result<()> {
    let path = config::zomboid_home().join("mods").join("default.txt");
    let mut text = fs::read_to_string(&path)
        .unwrap_or_else(|_| "VERSION = 1,\n\nmods\n{\n}\n\nmaps\n{\n}\n".to_string());
    if text.lines().any(|line| {
        line.trim()
            .strip_prefix("mod")
            .and_then(|rest| rest.trim().strip_prefix('='))
            .map(str::trim)
            == Some("PLZLauncher,")
    }) {
        return Ok(());
    }

    let mods_at = text
        .find("mods")
        .ok_or_else(|| Error::Other("Zomboid mods/default.txt has no mods section".into()))?;
    let open = text[mods_at..]
        .find('{')
        .map(|at| mods_at + at)
        .ok_or_else(|| {
            Error::Other("Zomboid mods/default.txt has a malformed mods section".into())
        })?;
    let close = text[open..].find('}').map(|at| open + at).ok_or_else(|| {
        Error::Other("Zomboid mods/default.txt has a malformed mods section".into())
    })?;
    text.insert_str(close, "    mod = PLZLauncher,\n");
    state::write_no_bom(&path, &text)
}

pub fn install() -> Result<()> {
    let root = config::launcher_mod_dir();
    state::write_no_bom(&root.join("mod.info"), MOD_INFO)?;
    state::write_no_bom(
        &root
            .join("media")
            .join("lua")
            .join("client")
            .join("PLZLauncher")
            .join("Bootstrap.lua"),
        BOOTSTRAP_LUA,
    )?;
    state::write_no_bom(
        &root
            .join("media")
            .join("lua")
            .join("shared")
            .join("Translate")
            .join("EN")
            .join("UI.json"),
        REASON_TRANSLATIONS,
    )?;
    enable_in_default_mods()
}

pub fn write_join_intent(host: &str, port: u16, username: &str, server_name: &str) -> Result<()> {
    for value in [host, username, server_name] {
        if value.contains(['\r', '\n']) {
            return Err(Error::Other("invalid newline in join settings".into()));
        }
    }
    let contents = format!("{host}\n{port}\n{username}\n\n{server_name}\n");
    state::write_no_bom(&config::join_intent_path(), &contents)
}

pub fn clear_join_intent() {
    let _ = fs::remove_file(config::join_intent_path());
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JoinResult {
    pub code: String,
    pub detail: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RoleInfo {
    pub grants_debug: bool,
    pub name: String,
}

pub fn clear_role() {
    let _ = fs::remove_file(config::role_path());
}

pub fn read_role() -> Option<RoleInfo> {
    parse_role(&fs::read_to_string(config::role_path()).ok()?)
}

fn parse_role(text: &str) -> Option<RoleInfo> {
    let mut lines = text.lines();
    let granted = lines.next()?.trim();
    let grants_debug = match granted {
        "1" => true,
        "0" => false,
        _ => return None,
    };
    Some(RoleInfo {
        grants_debug,
        name: lines.next().unwrap_or("").trim().to_string(),
    })
}

pub fn clear_join_result() {
    let _ = fs::remove_file(config::join_result_path());
}

pub fn read_join_result() -> Option<JoinResult> {
    let text = fs::read_to_string(config::join_result_path()).ok()?;
    parse_join_result(&text)
}

fn parse_join_result(text: &str) -> Option<JoinResult> {
    let mut lines = text.lines();
    let code = lines.next()?.trim();
    if code.is_empty() {
        return None;
    }
    Some(JoinResult {
        code: code.to_string(),
        detail: lines.next().unwrap_or("").trim().to_string(),
    })
}

pub fn explain(result: &JoinResult) -> Option<String> {
    match result.code.as_str() {
        "OK" => None,
        "PLZNameTaken" => Some(
            "That username is already taken on the server. Choose a different one and press Play again."
                .into(),
        ),
        "PLZWrongCharacter" if !result.detail.is_empty() => Some(format!(
            "This Steam account already plays on the server as \"{}\". \
             The launcher has switched to it. Press Play to continue, or ask an admin if you want it renamed.",
            result.detail
        )),
        "PLZWrongCharacter" => Some(
            "This Steam account already plays on the server under a different username. \
             Ask an admin to tell you the name, or to rename it."
                .into(),
        ),
        "PLZNotApproved" => Some(
            "Your Steam account has not been approved for the server yet. \
             Send your Steam ID to an admin, then try again."
                .into(),
        ),
        "BootstrapError" => Some(format!(
            "The launcher's in-game handoff failed before it could connect: {}. This is a launcher bug, not a server problem -- please report it.",
            result.detail
        )),
        "InvalidUsernamePassword" => Some(
            "The server already has an account with that name, holding a password the launcher cannot produce -- usually one made with /adduser or joined by hand before the server patch. An admin can clear it with /removeuserfromwhitelist <name>, or you can pick a different name."
                .into(),
        ),
        "InvalidServerPassword" => Some(
            "The server refused the server password. The launcher sends what its signed manifest carries, so tools/server.json is out of date with the server's .ini."
                .into(),
        ),
        "UnknownUsername" => Some(
            "The server did not recognise this account, and your Steam ID is not on its allow list. Send your Steam ID to an admin so they can run /addsteamid."
                .into(),
        ),
        "DuplicateAccount" => Some(
            "An account with that name already exists and is password-protected. Pick a different name, or ask an admin to remove the old one."
                .into(),
        ),
        "DebugNotAllowed" => Some(
            "The server refused the connection because the game is in debug mode and your account is not an admin. Only the built-in 'admin' role may join with -debug; moderator and gm may not. Turn off 'Allow debug mode' under Details, or clear -debug from your Steam launch options."
                .into(),
        ),
        "MaxAccountsReached" => Some(
            "This Steam account has already created the maximum number of characters allowed on this server."
                .into(),
        ),
        "InvalidUsername" => Some(
            "The server refused that username. Try a different one -- 2 to 20 plain characters, \
             and a word filter applies."
                .into(),
        ),
        _ => Some(format!("The server refused the connection: {}", result.detail)),
    }
}

#[cfg(test)]
mod tests {
    use super::{explain, parse_join_result, BOOTSTRAP_LUA, REASON_TRANSLATIONS};

    #[test]
    fn bootstrap_uses_the_saved_account_and_not_the_popup() {
        assert!(BOOTSTRAP_LUA.contains("ConnectToServer.instance:connect"));
        assert!(BOOTSTRAP_LUA.contains("getCore():setAccountUsed(account)"));
        assert!(!BOOTSTRAP_LUA.contains("ServerConnectPopup"));
    }

    #[test]
    fn iterates_the_server_table_as_a_table() {
        let servers = BOOTSTRAP_LUA
            .split("local function ensureServer")
            .nth(1)
            .and_then(|s| s.split("local function ensureAccount").next())
            .expect("ensureServer not found");
        assert!(
            !servers.contains("servers:size()") && !servers.contains("servers:get("),
            "ensureServer is treating the server list as a Java List"
        );
        assert!(servers.contains("for i = 1, #servers do"));
    }

    #[test]
    fn a_crash_in_the_handoff_is_reported_not_swallowed() {
        assert!(BOOTSTRAP_LUA.contains("pcall(join)"));
        assert!(BOOTSTRAP_LUA.contains("writeResult(\"BootstrapError\""));
    }

    #[test]
    fn bootstrap_reports_the_outcome_of_our_join_only() {
        assert!(BOOTSTRAP_LUA.contains("Events.OnConnectFailed.Add(onConnectFailed)"));
        assert!(BOOTSTRAP_LUA.contains("if not armed or not message then return end"));
    }

    #[test]
    fn every_classified_reason_has_a_translation() {
        for key in ["PLZWrongCharacter", "PLZNameTaken", "PLZNotApproved"] {
            assert!(
                REASON_TRANSLATIONS.contains(&format!("UI_OnConnectFailed_{key}")),
                "no translation for {key}"
            );
            assert!(
                BOOTSTRAP_LUA.contains(&format!("\"{key}\"")),
                "not classified: {key}"
            );
        }
    }

    #[test]
    fn translations_have_no_bare_percent() {
        let bytes: Vec<char> = REASON_TRANSLATIONS.chars().collect();
        for (i, c) in bytes.iter().enumerate() {
            if *c == '%' {
                assert!(
                    bytes.get(i + 1).is_some_and(char::is_ascii_digit),
                    "bare % at {i} in the reason translations"
                );
            }
        }
    }

    #[test]
    fn parses_the_result_file() {
        let r = parse_join_result("PLZWrongCharacter\nDave\n").unwrap();
        assert_eq!(r.code, "PLZWrongCharacter");
        assert_eq!(r.detail, "Dave");
        assert_eq!(parse_join_result("OK\n\n").unwrap().detail, "");
        assert!(parse_join_result("").is_none());
        assert!(parse_join_result("\n").is_none());
    }

    #[test]
    fn a_successful_join_needs_no_explanation() {
        assert!(explain(&parse_join_result("OK\n\n").unwrap()).is_none());
        assert!(explain(&parse_join_result("PLZNameTaken\n\n").unwrap()).is_some());
    }
}

#[cfg(test)]
mod role_tests {
    use super::parse_role;

    #[test]
    fn only_an_explicit_flag_is_an_answer() {
        assert_eq!(parse_role("1
admin
").unwrap().grants_debug, true);
        assert_eq!(parse_role("0
moderator
").unwrap().grants_debug, false);
        assert_eq!(parse_role("1
admin
").unwrap().name, "admin");
    }

    #[test]
    fn anything_unreadable_is_not_told_rather_than_denied() {
        for text in ["", "
", "yes
admin
", "true
", "  
admin
"] {
            assert!(parse_role(text).is_none(), "{text:?} should not be an answer");
        }
    }

    #[test]
    fn a_missing_role_name_is_still_a_valid_answer() {
        let role = parse_role("1

").expect("still an answer");
        assert!(role.grants_debug);
        assert_eq!(role.name, "");
    }
}
