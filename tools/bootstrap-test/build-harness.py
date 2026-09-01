"""Build a Kahlua-runnable harness that actually EXECUTES the bootstrap mod.

The bootstrap crashed in-game on `servers:size()` because getServerList returns a Kahlua table,
not a Java List. Parsing the file could never have caught that. This stubs every PZ global the
bootstrap touches, with the SHAPES the decompiled source actually returns, then drives the two
entry points and asserts the connect call arrived with the right arguments.
"""
import io
import os
import re

SP = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(SP))
BOOTSTRAP_RS = os.path.join(REPO, "src-tauri", "src", "bootstrap.rs")
src = io.open(BOOTSTRAP_RS, encoding="utf-8", newline="").read()
lua = re.search(r'const BOOTSTRAP_LUA: &str = r#"(.*?)"#;', src, re.S).group(1)

PRELUDE = r"""
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
"""

BODY = r"""
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
"""

harness = PRELUDE + "\nfunction RELOAD()\n" + lua + "\nend\nRELOAD()\n" + BODY
io.open(os.path.join(SP, "bootstrap_test.lua"), "w", encoding="utf-8", newline="\n").write(harness)
print("wrote bootstrap_test.lua")
