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
local roleWritten = false
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

-- The game throws this Lua state away the moment the server's options arrive:
-- Core.ResetLua wipes UIManager and reloads mods from the SERVER's list only, so this
-- mod is never loaded again. Everything we want to report or show has to happen before
-- that, and the last frame we draw is what the player stares at for the whole load.
local WELCOME_TICKS = 90
local welcomeTicks = 0
local welcomePanel = nil
local statusPanel = nil
local statusLine = ""
local statusNotes = nil

local CONNECTING = "Contacting the server..."
local LOADING_NOTES = {
    "The game is loading the server's mods and Lua now.",
    "On a first join this can take several minutes.",
    "The screen stays frozen the whole time. That is normal.",
    "Do not close the game or the launcher.",
}

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

local FAILURE_GRACE = 180
local failureTicks = 0
local failureReason = nil

local function removeStatus()
    if statusPanel then
        pcall(function() statusPanel:removeFromUIManager() end)
        statusPanel = nil
    end
end

local function setStatus(line, notes)
    if line then statusLine = line end
    statusNotes = notes
end

-- Deliberately zero-sized. A top-level element draws anywhere it likes in screen
-- coordinates, but occludes and swallows input only inside its own rect, so the
-- vanilla connect screen underneath keeps its abort button and its failure text.
local function showStatus()
    if statusPanel then return true end
    if not ISPanel or not getCore or not getTextManager then return false end
    local ok = pcall(function()
        local panel = ISPanel:new(0, 0, 0, 0)
        panel:initialise()
        panel.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
        panel.borderColor = { r = 0, g = 0, b = 0, a = 0 }
        panel.onMouseDown = function() return false end
        panel.onMouseUp = function() return false end
        panel.onRightMouseDown = function() return false end
        panel.onRightMouseUp = function() return false end
        panel.render = function(self)
            local text = getTextManager()
            local titleHgt = text:getFontHeight(UIFont.Large)
            local lineHgt = text:getFontHeight(UIFont.Medium)
            local noteCount = 0
            if statusNotes then noteCount = #statusNotes end
            local w = getCore():getScreenWidth()
            local h = getCore():getScreenHeight()
            local boxW = w - 120
            if boxW > 820 then boxW = 820 end
            local boxH = titleHgt + lineHgt * (noteCount + 1) + 50
            local boxX = (w - boxW) / 2
            -- Below the vanilla connect screen's own text, above its abort button.
            local boxY = h - boxH - 120
            if boxY < 40 then boxY = 40 end
            self:drawRect(boxX, boxY, boxW, boxH, 0.82, 0, 0, 0)
            self:drawRectBorder(boxX, boxY, boxW, boxH, 0.45, 0.75, 0.75, 0.75)
            local cx = w / 2
            local y = boxY + 16
            self:drawTextCentre("Project Life Zoid", cx, y, 1, 1, 1, 1, UIFont.Large)
            y = y + titleHgt + 10
            self:drawTextCentre(statusLine, cx, y, 0.92, 0.92, 0.92, 1, UIFont.Medium)
            y = y + lineHgt + 8
            for i = 1, noteCount do
                self:drawTextCentre(statusNotes[i], cx, y, 0.72, 0.72, 0.72, 1, UIFont.Medium)
                y = y + lineHgt
            end
        end
        panel:addToUIManager()
        panel:setAlwaysOnTop(true)
        statusPanel = panel
    end)
    return ok and statusPanel ~= nil
end

local function serverText(key, fallback)
    if not key then return fallback end
    local full = "UI_servers_" .. key
    local rendered = getText(full)
    if not rendered or rendered == "" or rendered == full then return fallback end
    return rendered
end

-- nil until ConnectToServerState.TestTCP has put a Role on the connection. getAccessLevel is
-- the probe because it throws while there is none; haveAccess cannot be, as it catches that
-- same NullPointerException itself and answers a confident false.
local function roleName()
    if not isClient() then return nil end
    if not getAccessLevel then return nil end
    local ok, name = pcall(getAccessLevel)
    if not ok then return nil end
    return name
end

local function writeRole()
    if roleWritten then return end
    if not ours then return end
    local name = roleName()
    if not name then return end
    if not haveAccess then return end
    local ok, granted = pcall(haveAccess, "ConnectWithDebug")
    if not ok then return end
    local writer = getFileWriter(ROLE_FILE, true, false)
    if not writer then return end
    roleWritten = true
    writer:write((granted and "1" or "0") .. "\n" .. name .. "\n")
    writer:close()
end

local function onConnected()
    if not armed then return end
    if not roleName() then return end
    armed = false
    writeResult("OK", "")
    writeRole()
end

local function showFailure(reason)
    failureTicks = 0
    setStatus("Could not connect to Project Life Zoid", {
        reason,
        "Wait a minute, then press Play in the launcher again.",
    })
    showStatus()
end

local function onConnectFailed(message)
    if not armed then return end
    if not message then return end
    local key, detail = classify(message)
    showFailure(message)
    writeResult(key or "Other", detail or message)
end

local function onConnectionStateChanged(state, message, arg)
    if not ours or not state then return end
    -- Two events carry "Connected". RakNetPeerInterface's transport callback fires one WITH a
    -- message, before the connection has a Role and before the server has decided anything;
    -- ConnectToServerState.receiveStartLocation fires the other with NO message, and that one
    -- is the last thing to reach this Lua state before ResetLua destroys it. Only the second is
    -- a join worth reporting: the whitelist, the name check and the workshop pass all refuse
    -- between them, and answering early would swallow the reason.
    if state == "Connected" and message == nil then
        setStatus("Loading the server's content", LOADING_NOTES)
        onConnected()
        return
    end
    if state == "Disconnecting" then return end
    -- A transport-level failure arrives ONLY as this event: the server never answered, so nothing
    -- classifies it and Events.OnConnectFailed is never fired. Leaving the screen bare here is
    -- what made a refused join look like a hang. Give OnConnectFailed a moment to arrive with a
    -- real reason first, and report this one from the tick if it does not.
    if state == "Failed" or state == "Disconnected" then
        showFailure(serverText(message, "The server did not answer."))
        failureReason = message or state
        failureTicks = FAILURE_GRACE
        return
    end
    if state == "FormatMessage" then
        local line = serverText(message, CONNECTING)
        local okFormat, formatted = pcall(string.format, line, tostring(arg))
        if okFormat then line = formatted end
        setStatus(line, nil)
        return
    end
    if state == "Message" then
        setStatus(serverText(message, CONNECTING), nil)
        return
    end
    setStatus(serverText(state, CONNECTING), nil)
end

local function onServerWorkshopItems(state)
    if not ours then return end
    if state == "Error" then
        removeStatus()
        return
    end
    if state == "Success" then return end
    setStatus("Checking your Workshop mods against the server...", nil)
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

local function beginJoin()
    removeWelcome()
    setStatus(CONNECTING, nil)
    local ok, err = pcall(join)
    if not ok then
        armed = true
        writeResult("BootstrapError", tostring(err))
        return
    end
    showStatus()
end

local function onFrontEndTick()
    if failureTicks > 0 then
        failureTicks = failureTicks - 1
        if failureTicks == 0 and armed then
            armed = false
            writeResult("NoResponse", failureReason or "")
        end
    end
    if welcomeTicks <= 0 then return end
    welcomeTicks = welcomeTicks - 1
    if welcomeTicks > 0 then return end
    beginJoin()
end

local function onMainMenuEnter()
    if consumed then return end
    consumed = true
    if not readIntent() then return end
    if showWelcome() then
        welcomeTicks = WELCOME_TICKS
        return
    end
    beginJoin()
end

Events.OnMainMenuEnter.Add(onMainMenuEnter)
Events.OnFETick.Add(onFrontEndTick)
Events.OnConnectFailed.Add(onConnectFailed)
Events.OnConnectionStateChanged.Add(onConnectionStateChanged)
Events.OnServerWorkshopItems.Add(onServerWorkshopItems)
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
        "NoResponse" => Some(
            "The server did not answer, so the connection never got as far as being accepted or              refused. That almost always means the server is busy rather than down: under load it              sheds new connections while everyone already on it keeps playing. Wait a minute and              press Play again."
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
            "The server refused the connection because the game is in debug mode and your account is not an admin. Only the built-in 'admin' role may join with -debug; moderator and gm may not. Turn off 'Start the game in debug mode' under Details, and clear -debug from your Steam launch options."
                .into(),
        ),
        "MaxAccountsReached" => Some(
            "This Steam account has already created the maximum number of characters allowed on this server."
                .into(),
        ),
        // Written by ProjectLifeZoidCore's AfkDisconnectNotice, not by the bootstrap. It is
        // the one code here that describes a session that JOINED FINE and ended later, which
        // is why the caller still treats it as a confirmed account.
        "AFKKick" => Some(
            "The server disconnected you for being away from the keyboard. Nothing is lost, and you can join again straight away."
                .into(),
        ),
        "InvalidUsername" => Some(
            "The server refused that username. Try a different one -- 2 to 20 plain characters, \
             and a word filter applies."
                .into(),
        ),
        // Vanilla's own refusal text, echoed back through the "Other" catch-all. Worth naming
        // because the fix is specific and the raw sentence does not suggest it.
        _ if result.detail.contains("Workshop item version") => Some(
            "One of your Workshop mods is a different version than the server's, so the server \
             refused the connection. Let Steam finish updating your mods -- restart Steam if it \
             looks idle -- then press Play again. If it keeps happening, the server is the one \
             running the older copy and an admin has to update it."
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
        let failed = BOOTSTRAP_LUA
            .split("local function onConnectFailed")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("onConnectFailed not found");
        assert!(failed.contains("if not armed then return end"));
        assert!(failed.contains("if not message then return end"));
    }

    // Core.ResetLua fires while the server options are being read, and it reloads mods from
    // the SERVER's list, which never contains PLZLauncher. OnConnected and OnGameStart both
    // land after that, in a Lua state this file was not loaded into, so the connection state
    // change is the last moment we can report anything at all.
    #[test]
    fn the_join_is_reported_before_the_lua_state_is_thrown_away() {
        let handler = BOOTSTRAP_LUA
            .split("local function onConnectionStateChanged")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("onConnectionStateChanged not found");
        // RakNetPeerInterface fires "Connected" WITH a message as soon as the transport is up,
        // before the connection has a Role and before the server has accepted anything.
        // Answering on that one records a role of "no debug access, no name" and clears the
        // armed flag, which makes onConnectFailed discard every refusal reason.
        assert!(
            handler.contains("if state == \"Connected\" and message == nil then"),
            "the handoff must ignore RakNet's early transport-level Connected"
        );
        assert!(handler.contains("onConnected()"));
        assert!(
            BOOTSTRAP_LUA.contains("Events.OnConnectionStateChanged.Add(onConnectionStateChanged)")
        );
        assert!(BOOTSTRAP_LUA.contains("writeRole()"));
        let write_role = BOOTSTRAP_LUA
            .split("local function writeRole")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("writeRole not found");
        assert!(
            write_role.contains("local name = roleName()")
                && write_role.contains("if not name then return end"),
            "writeRole must refuse to answer while the connection has no Role"
        );
        let probe = BOOTSTRAP_LUA
            .split("local function roleName")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("roleName not found");
        assert!(
            probe.contains("pcall(getAccessLevel)") && !probe.contains("haveAccess"),
            "haveAccess swallows the NullPointerException and answers false, so it cannot be the probe"
        );
    }

    // A screen-sized top-level element occludes isPointOver for everything under it, which
    // would take the vanilla connect screen's abort button and error text with it. The status
    // box draws in screen coordinates from a zero-sized element instead.
    #[test]
    fn the_status_box_does_not_cover_the_connect_screen() {
        let show = BOOTSTRAP_LUA
            .split("local function showStatus")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("showStatus not found");
        assert!(show.contains("ISPanel:new(0, 0, 0, 0)"));
        for handler in [
            "onMouseDown",
            "onMouseUp",
            "onRightMouseDown",
            "onRightMouseUp",
        ] {
            assert!(
                show.contains(&format!("panel.{handler} = function() return false end")),
                "{handler} must not swallow the click"
            );
        }
    }

    // A transport failure never reaches Events.OnConnectFailed: the server simply never
    // answered, so nothing classifies it. Tearing the overlay down there left the player
    // looking at bare menu art with no indication at all, which is the bug this whole
    // feature exists to prevent.
    #[test]
    fn a_failure_leaves_something_on_screen_and_tells_the_launcher() {
        let handler = BOOTSTRAP_LUA
            .split("local function onConnectionStateChanged")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("onConnectionStateChanged not found");
        assert!(
            !handler.contains("removeStatus()"),
            "a connection state change must never leave the screen bare"
        );
        assert!(handler.contains("showFailure("));
        assert!(
            handler.contains("failureTicks = FAILURE_GRACE"),
            "the transport failure must be reported, after a grace period for a real reason"
        );
        let failed = BOOTSTRAP_LUA
            .split("local function onConnectFailed")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("onConnectFailed not found");
        assert!(
            !failed.contains("removeStatus()") && failed.contains("showFailure(message)"),
            "a classified refusal must be captioned, not blanked"
        );
        assert!(explain(&super::JoinResult {
            code: "NoResponse".into(),
            detail: String::new()
        })
        .is_some_and(|t| t.contains("busy")));
    }

    // The frame drawn inside ResetLua is the one the player stares at for the whole content
    // load, so the box has to still be up, and saying something useful, when it is taken.
    #[test]
    fn the_frozen_frame_explains_itself() {
        assert!(BOOTSTRAP_LUA.contains("The screen stays frozen the whole time. That is normal."));
        assert!(BOOTSTRAP_LUA.contains("Do not close the game or the launcher."));
        let begin = BOOTSTRAP_LUA
            .split("local function beginJoin")
            .nth(1)
            .and_then(|s| s.split("\nlocal function ").next())
            .expect("beginJoin not found");
        assert!(
            begin.contains("removeWelcome()"),
            "the welcome modal must go"
        );
        assert!(
            begin.contains("showStatus()"),
            "and the status box must replace it"
        );
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

    // The idle kick is the only code written mid-session rather than at the join, by the
    // mod's own client Lua. It has to explain itself like a refusal without reading like
    // one: the join worked, and lib.rs confirms the account on it for that reason.
    #[test]
    fn an_idle_kick_explains_itself_without_reading_as_a_refusal() {
        let r = parse_join_result("AFKKick\n\n").unwrap();
        let explained = explain(&r).expect("an idle kick has to say why the game closed");
        assert!(
            explained.contains("away from the keyboard"),
            "the idle kick must name the reason, got: {explained}"
        );
        assert!(
            !explained.starts_with("The server refused the connection:"),
            "the idle kick must not fall through to the raw echo"
        );
    }

    #[test]
    fn a_workshop_version_refusal_says_what_to_do_about_it() {
        let r = parse_join_result("Other\nWorkshop item version is different than the server's\n")
            .unwrap();
        let explained = explain(&r).expect("a refusal always explains itself");
        assert!(
            explained.contains("Steam"),
            "the workshop case must point at Steam, got: {explained}"
        );
        assert!(
            !explained.starts_with("The server refused the connection:"),
            "the workshop case must not fall through to the raw echo"
        );
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
