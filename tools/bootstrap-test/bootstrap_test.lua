
local failures = 0
local log = {}
local function note(s) log[#log + 1] = s end
local function check(label, got, want)
    if got ~= want then
        failures = failures + 1
        print("FAIL " .. label .. "  got=" .. tostring(got) .. "  want=" .. tostring(want))
    else
        print("ok   " .. label .. " -> " .. tostring(got))
    end
end

local FILES = {}
local WRITTEN = {}

function getFileReader(path, create)
    local body = FILES[path]
    if not body then return nil end
    local pos = 1
    return {
        readLine = function(self)
            if pos > #body then return nil end
            local nl = string.find(body, "\n", pos)
            local line
            if nl then line = string.sub(body, pos, nl - 1); pos = nl + 1
            else line = string.sub(body, pos); pos = #body + 1 end
            return line
        end,
        close = function(self) end,
    }
end

function getFileWriter(path, create, append)
    return {
        write = function(self, text) WRITTEN[path] = (WRITTEN[path] or "") .. text end,
        close = function(self) end,
    }
end

local SERVERS = {}
function getServerList() return SERVERS end

local function newAccountList()
    local items = {}
    return {
        size = function(self) return #items end,
        get = function(self, i) return items[i + 1] end,
        add = function(self, a) items[#items + 1] = a end,
    }
end

Account = {}
function Account.new()
    local a = { user = "", pwd = "", save = false, relay = false, auth = 0, logon = false }
    a.setUserName = function(self, v) self.user = v end
    a.getUserName = function(self) return self.user end
    a.setPwd = function(self, v) self.pwd = v end
    a.getPwd = function(self) return self.pwd end
    a.setSavePwd = function(self, v) self.save = v end
    a.setUseSteamRelay = function(self, v) self.relay = v end
    a.setAuthType = function(self, v) self.auth = v end
    a.getAuthType = function(self) return self.auth end
    a.setLastLogonNow = function(self) self.logon = true end
    return a
end

Server = {}
function Server.new()
    local s = { name = "", ip = "", port = 0, pwd = "", accounts = newAccountList() }
    s.setName = function(self, v) self.name = v end
    s.getName = function(self) return self.name end
    s.setIp = function(self, v) self.ip = v end
    s.getIp = function(self) return self.ip end
    s.setPort = function(self, v) self.port = v end
    s.getPort = function(self) return self.port end
    s.setServerPassword = function(self, v) self.pwd = v end
    s.getServerPassword = function(self) return self.pwd end
    s.getAccounts = function(self) return self.accounts end
    s.getServerLoadingScreen = function(self) return nil end
    return s
end

function addServerToAccountList(s) SERVERS[#SERVERS + 1] = s; note("addServer") end
function addAccountToAccountList(s, a) s.accounts:add(a); note("addAccount") end
function updateAccountToAccountList(a) note("updateAccount") end
function stopSendSecretKey() note("stopSendSecretKey") end

local ACCOUNT_USED = nil
function getCore()
    return {
        setAccountUsed = function(self, a) ACCOUNT_USED = a end,
        setNoSave = function(self, b) end,
        getScreenWidth = function(self) return 1920 end,
        getScreenHeight = function(self) return 1080 end,
    }
end

local CONNECT_ARGS = nil
ConnectToServer = { instance = {} }
ConnectToServer.instance.connect = function(self, prev, name, user, pwd, ip, localIp, port, spwd, relay, hash, auth)
    CONNECT_ARGS = { name = name, user = user, pwd = pwd, ip = ip, port = port,
                     spwd = spwd, relay = relay, hash = hash, auth = auth }
end
MainScreen = { instance = {} }

local TRANSLATIONS = {}
function getText(key, a)
    local t = TRANSLATIONS[key] or key
    return (string.gsub(t, "%%1", a or ""))
end

local HANDLERS = {}
local function eventSlot(name)
    return { Add = function(fn) HANDLERS[name] = fn end }
end
Events = {
    OnMainMenuEnter = eventSlot("OnMainMenuEnter"),
    OnConnectFailed = eventSlot("OnConnectFailed"),
    OnConnected = eventSlot("OnConnected"),
    OnGameStart = eventSlot("OnGameStart"),
    OnFETick = eventSlot("OnFETick"),
    OnConnectionStateChanged = eventSlot("OnConnectionStateChanged"),
    OnServerWorkshopItems = eventSlot("OnServerWorkshopItems"),
}

UI_ADDED = 0
UI_PANELS = {}
DRAWN = {}
UIFont = { Large = "Large", Medium = "Medium", Small = "Small" }

function getTextManager()
    return { getFontHeight = function(self, font) return 20 end }
end

ISPanel = {}
function ISPanel:new(x, y, w, h)
    local o = { x = x, y = y, w = w, h = h }
    o.initialise = function() end
    o.addToUIManager = function(self)
        UI_ADDED = UI_ADDED + 1
        UI_PANELS[#UI_PANELS + 1] = self
    end
    o.removeFromUIManager = function(self)
        UI_ADDED = UI_ADDED - 1
        local kept = {}
        for i = 1, #UI_PANELS do
            if UI_PANELS[i] ~= self then kept[#kept + 1] = UI_PANELS[i] end
        end
        UI_PANELS = kept
    end
    o.setAlwaysOnTop = function() end
    o.getWidth = function() return w end
    o.getHeight = function() return h end
    o.drawTextCentre = function(self, str) DRAWN[#DRAWN + 1] = tostring(str) end
    o.drawRect = function() end
    o.drawRectBorder = function() end
    return o
end
function ISPanel.render() end

-- Every panel is rendered for real, so a typo in a render body is a test failure
-- and not a blank screen during a live join.
function RENDER_ALL()
    DRAWN = {}
    for i = 1, #UI_PANELS do
        UI_PANELS[i]:render()
    end
end

function DRAWN_HAS(line)
    for i = 1, #DRAWN do
        if DRAWN[i] == line then return true end
    end
    return false
end

-- ACCESS.role is the connection Role. Until ConnectToServerState.TestTCP sets one,
-- getAccessLevel THROWS and haveAccess swallows the same failure and answers false --
-- which is exactly how a real join behaves between RakNet connecting and TestTCP running.
ACCESS = { client = true, capabilities = {}, level = "", role = true }
function isClient() return ACCESS.client end
function haveAccess(capability)
    if not ACCESS.role then return false end
    return ACCESS.capabilities[capability] == true
end
function getAccessLevel()
    if not ACCESS.role then error("NullPointerException: getRole() is null") end
    return ACCESS.level
end

function RELOAD()
local JOIN_FILE = "PLZLauncher/join.txt"
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

end
RELOAD()

local function enterMenu()
    HANDLERS.OnMainMenuEnter()
    for i = 1, 200 do HANDLERS.OnFETick() end
end

print("--- 1. no join file: the mod must do nothing at all ---")
enterMenu()
check("no connect attempted", CONNECT_ARGS, nil)
check("nothing written", WRITTEN["PLZLauncher/result.txt"], nil)

print("")
print("--- 2. a normal join, empty server list ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave The Third\n\nProject Life Zoid\n"
WRITTEN = {}
CONNECT_ARGS = nil
RELOAD()
enterMenu()
check("connected", CONNECT_ARGS ~= nil, true)
if CONNECT_ARGS then
    check("  ip", CONNECT_ARGS.ip, "167.114.174.186")
    check("  port is a string", type(CONNECT_ARGS.port), "string")
    check("  port", CONNECT_ARGS.port, "26915")
    check("  username", CONNECT_ARGS.user, "Dave The Third")
    check("  password stays empty", CONNECT_ARGS.pwd, "")
    check("  doHash false", CONNECT_ARGS.hash, false)
    check("  authType", CONNECT_ARGS.auth, 1)
end
check("server row created", #SERVERS, 1)

print("")
print("--- 3. second launch reuses the existing server row, not a duplicate ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave The Third\n\nProject Life Zoid\n"
CONNECT_ARGS = nil
RELOAD()
enterMenu()
check("still one server row", #SERVERS, 1)
check("connected again", CONNECT_ARGS ~= nil, true)

print("")
print("--- 4. a refused join is reported back to the launcher ---")
WRITTEN = {}
HANDLERS.OnConnectFailed("UI_OnConnectFailed_PLZNameTaken")
check("result written", WRITTEN["PLZLauncher/result.txt"] ~= nil, true)
if WRITTEN["PLZLauncher/result.txt"] then
    check("  code", string.sub(WRITTEN["PLZLauncher/result.txt"], 1, 12), "PLZNameTaken")
end

print("")
print("--- 5. a successful join writes OK ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave The Third\n\nProject Life Zoid\n"
WRITTEN = {}
RELOAD()
enterMenu()
HANDLERS.OnConnected()
check("OK written", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 2), "OK")

print("")
print("--- 6. a crash inside the bootstrap is reported, not swallowed ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
RELOAD()
local realGetServerList = getServerList
getServerList = function() error("simulated engine failure") end
enterMenu()
getServerList = realGetServerList
check("BootstrapError written", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 14), "BootstrapError")

print("")
print("--- 7. the role probe records the CAPABILITY, not the role name ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
RELOAD()
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin", role = true }
enterMenu()
HANDLERS.OnConnected()
HANDLERS.OnGameStart()
check("role written", WRITTEN["PLZLauncher/role.txt"], "1\nadmin\n")

print("")
print("--- 8. a role WITHOUT the capability is recorded as denied ---")
WRITTEN = {}
RELOAD()
ACCESS = { client = true, capabilities = {}, level = "moderator", role = true }
enterMenu()
HANDLERS.OnConnected()
HANDLERS.OnGameStart()
check("denied written", WRITTEN["PLZLauncher/role.txt"], "0\nmoderator\n")

print("")
print("--- 9. a custom role holding the capability still reads as allowed ---")
WRITTEN = {}
RELOAD()
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "builder", role = true }
enterMenu()
HANDLERS.OnConnected()
HANDLERS.OnGameStart()
check("custom role allowed", WRITTEN["PLZLauncher/role.txt"], "1\nbuilder\n")

print("")
print("--- 10. single player and foreign joins write nothing ---")
WRITTEN = {}
RELOAD()
ACCESS = { client = false, capabilities = { ConnectWithDebug = true }, level = "admin", role = true }
enterMenu()
HANDLERS.OnGameStart()
check("nothing written in SP", WRITTEN["PLZLauncher/role.txt"], nil)

WRITTEN = {}
RELOAD()
FILES["PLZLauncher/join.txt"] = nil
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin", role = true }
enterMenu()
HANDLERS.OnGameStart()
check("nothing written for a join we did not start", WRITTEN["PLZLauncher/role.txt"], nil)

print("")
print("--- 11. a build with no haveAccess must not crash ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
RELOAD()
local realHaveAccess = haveAccess
haveAccess = nil
ACCESS = { client = true, capabilities = {}, level = "admin", role = true }
enterMenu()
HANDLERS.OnGameStart()
haveAccess = realHaveAccess
check("no role file, no crash", WRITTEN["PLZLauncher/role.txt"], nil)

print("")
print("--- 12. the welcome overlay holds the join, then hands over to the status box ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
CONNECT_ARGS = nil
RELOAD()
HANDLERS.OnMainMenuEnter()
check("overlay shown", UI_ADDED, 1)
check("join held while it is up", CONNECT_ARGS, nil)
RENDER_ALL()
check("  the welcome is what it draws", DRAWN_HAS("Welcome to Project Life Zoid!"), true)
for i = 1, 89 do HANDLERS.OnFETick() end
check("still held one tick short", CONNECT_ARGS, nil)
HANDLERS.OnFETick()
check("join went ahead", CONNECT_ARGS ~= nil, true)
check("exactly one overlay is left", UI_ADDED, 1)
RENDER_ALL()
check("  the welcome is gone", DRAWN_HAS("Welcome to Project Life Zoid!"), false)
check("  the status box took over", DRAWN_HAS("Project Life Zoid"), true)
check("  and says what it is doing", DRAWN_HAS("Contacting the server..."), true)

print("")
print("--- 13. no join intent means no banner and no connect ---")
FILES["PLZLauncher/join.txt"] = nil
UI_ADDED = 0
CONNECT_ARGS = nil
RELOAD()
enterMenu()
check("nothing shown", UI_ADDED, 0)
check("nothing connected", CONNECT_ARGS, nil)

print("")
print("--- 15. the status box survives the whole connect and names each stage ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
TRANSLATIONS["UI_servers_UDPConnecting"] = "Opening the connection"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
enterMenu()
HANDLERS.OnConnectionStateChanged("UDPConnecting")
RENDER_ALL()
check("the engine wording is shown", DRAWN_HAS("Opening the connection"), true)
HANDLERS.OnServerWorkshopItems("Required", "1234")
RENDER_ALL()
check("the workshop pass is named", DRAWN_HAS("Checking your Workshop mods against the server..."), true)
HANDLERS.OnConnectionStateChanged("AuthPending")
RENDER_ALL()
check("an untranslated state falls back", DRAWN_HAS("Contacting the server..."), true)

print("")
print("--- 16. the last frame before ResetLua explains the freeze ---")
HANDLERS.OnConnectionStateChanged("Connected")
check("the box is still up", UI_ADDED, 1)
RENDER_ALL()
check("it says what is loading", DRAWN_HAS("Loading the server's content"), true)
check("it warns about the freeze", DRAWN_HAS("The screen stays frozen the whole time. That is normal."), true)
check("it says not to close the game", DRAWN_HAS("Do not close the game or the launcher."), true)

print("")
print("--- 17. Connected is the handoff, so the result lands there, not at OnConnected ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin", role = true }
enterMenu()
HANDLERS.OnConnectionStateChanged("Connected")
check("OK written without OnConnected", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 2), "OK")
check("role written without OnGameStart", WRITTEN["PLZLauncher/role.txt"], "1\nadmin\n")

print("")
print("--- 18. a refusal takes the box down so the vanilla screen is readable ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
enterMenu()
check("box up while connecting", UI_ADDED, 1)
HANDLERS.OnConnectFailed("UI_OnConnectFailed_PLZNameTaken")
check("box STAYS on refusal", UI_ADDED, 1)
RENDER_ALL()
check("  and captions the failure", DRAWN_HAS("Could not connect to Project Life Zoid"), true)
check("reason still reported", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 12), "PLZNameTaken")

FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
enterMenu()
HANDLERS.OnConnectionStateChanged("Disconnected", "banned")
check("box stays on a drop", UI_ADDED, 1)

print("")
print("--- 19. RakNet's early Connected is not the join, and must not answer for it ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin", role = false }
enterMenu()
-- the transport callback: a message argument, and no Role on the connection yet
HANDLERS.OnConnectionStateChanged("Connected", "")
check("no result yet", WRITTEN["PLZLauncher/result.txt"], nil)
check("no role guessed", WRITTEN["PLZLauncher/role.txt"], nil)
check("box still up", UI_ADDED, 1)
-- a refusal arriving after it must still be reported
HANDLERS.OnConnectFailed("UI_OnConnectFailed_PLZNotApproved")
check("refusal still reported", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 14), "PLZNotApproved")

print("")
print("--- 20. a Role that appears late is picked up, not locked out by the early event ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin", role = false }
enterMenu()
HANDLERS.OnConnectionStateChanged("Connected", "")
check("still nothing written", WRITTEN["PLZLauncher/role.txt"], nil)
-- TestTCP has now run, and receiveStartLocation fires with NO message
ACCESS.role = true
HANDLERS.OnConnectionStateChanged("Connected")
check("OK written once the role exists", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 2), "OK")
check("role written correctly", WRITTEN["PLZLauncher/role.txt"], "1\nadmin\n")
RENDER_ALL()
check("and the freeze warning is up", DRAWN_HAS("The screen stays frozen the whole time. That is normal."), true)

print("")
print("--- 21. a plain player has an empty access level, which is an answer, not a failure ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
RELOAD()
ACCESS = { client = true, capabilities = {}, level = "", role = true }
enterMenu()
HANDLERS.OnConnectionStateChanged("Connected")
check("recorded as denied with no name", WRITTEN["PLZLauncher/role.txt"], "0\n\n")

print("")
print("--- 22. a server that never answers still explains itself, on screen and to the launcher ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
TRANSLATIONS["UI_servers_ServerFailedToRespond"] = "The server did not respond."
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
enterMenu()
-- this is the ONLY event a transport failure fires: OnConnectFailed never comes
HANDLERS.OnConnectionStateChanged("Failed", "ServerFailedToRespond")
check("the box is still there", UI_ADDED, 1)
RENDER_ALL()
check("  it says it could not connect", DRAWN_HAS("Could not connect to Project Life Zoid"), true)
check("  it gives the engine reason", DRAWN_HAS("The server did not respond."), true)
check("  it says what to do", DRAWN_HAS("Wait a minute, then press Play in the launcher again."), true)
check("nothing reported yet", WRITTEN["PLZLauncher/result.txt"], nil)
for i = 1, 179 do HANDLERS.OnFETick() end
check("still holding for a real reason", WRITTEN["PLZLauncher/result.txt"], nil)
HANDLERS.OnFETick()
check("reported once the grace lapses", string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 10), "NoResponse")

print("")
print("--- 23. a classified refusal wins the race against the transport fallback ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
UI_PANELS = {}
RELOAD()
enterMenu()
HANDLERS.OnConnectionStateChanged("Disconnected", "kicked")
HANDLERS.OnConnectFailed("UI_OnConnectFailed_PLZNotApproved")
for i = 1, 200 do HANDLERS.OnFETick() end
check("the real reason is what the launcher gets",
      string.sub(WRITTEN["PLZLauncher/result.txt"] or "", 1, 14), "PLZNotApproved")

print("")
print("--- 14. a build without ISPanel still joins ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
CONNECT_ARGS = nil
RELOAD()
local realPanel = ISPanel
ISPanel = nil
enterMenu()
ISPanel = realPanel
check("joined immediately", CONNECT_ARGS ~= nil, true)

print("")
if failures > 0 then error(failures .. " bootstrap assertion(s) failed") end
print("ALL BOOTSTRAP ASSERTIONS PASSED")
