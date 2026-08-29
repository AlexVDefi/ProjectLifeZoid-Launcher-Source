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
}

UI_ADDED = 0
UIFont = { Large = "Large", Medium = "Medium", Small = "Small" }
ISPanel = {}
function ISPanel:new(x, y, w, h)
    local o = { x = x, y = y, w = w, h = h }
    o.initialise = function() end
    o.addToUIManager = function() UI_ADDED = UI_ADDED + 1 end
    o.removeFromUIManager = function() UI_ADDED = UI_ADDED - 1 end
    o.setAlwaysOnTop = function() end
    o.getWidth = function() return w end
    o.getHeight = function() return h end
    o.drawTextCentre = function() end
    return o
end
function ISPanel.render() end

ACCESS = { client = true, capabilities = {}, level = "" }
function isClient() return ACCESS.client end
function haveAccess(capability) return ACCESS.capabilities[capability] == true end
function getAccessLevel() return ACCESS.level end
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
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin" }
enterMenu()
HANDLERS.OnConnected()
HANDLERS.OnGameStart()
check("role written", WRITTEN["PLZLauncher/role.txt"], "1\nadmin\n")

print("")
print("--- 8. a role WITHOUT the capability is recorded as denied ---")
WRITTEN = {}
RELOAD()
ACCESS = { client = true, capabilities = {}, level = "moderator" }
enterMenu()
HANDLERS.OnConnected()
HANDLERS.OnGameStart()
check("denied written", WRITTEN["PLZLauncher/role.txt"], "0\nmoderator\n")

print("")
print("--- 9. a custom role holding the capability still reads as allowed ---")
WRITTEN = {}
RELOAD()
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "builder" }
enterMenu()
HANDLERS.OnConnected()
HANDLERS.OnGameStart()
check("custom role allowed", WRITTEN["PLZLauncher/role.txt"], "1\nbuilder\n")

print("")
print("--- 10. single player and foreign joins write nothing ---")
WRITTEN = {}
RELOAD()
ACCESS = { client = false, capabilities = { ConnectWithDebug = true }, level = "admin" }
enterMenu()
HANDLERS.OnGameStart()
check("nothing written in SP", WRITTEN["PLZLauncher/role.txt"], nil)

WRITTEN = {}
RELOAD()
FILES["PLZLauncher/join.txt"] = nil
ACCESS = { client = true, capabilities = { ConnectWithDebug = true }, level = "admin" }
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
ACCESS = { client = true, capabilities = {}, level = "admin" }
enterMenu()
HANDLERS.OnGameStart()
haveAccess = realHaveAccess
check("no role file, no crash", WRITTEN["PLZLauncher/role.txt"], nil)

print("")
print("--- 12. the welcome overlay holds the join, then lets it through ---")
FILES["PLZLauncher/join.txt"] = "167.114.174.186\n26915\nDave\n\nPLZ\n"
WRITTEN = {}
UI_ADDED = 0
CONNECT_ARGS = nil
RELOAD()
HANDLERS.OnMainMenuEnter()
check("overlay shown", UI_ADDED, 1)
check("join held while it is up", CONNECT_ARGS, nil)
for i = 1, 89 do HANDLERS.OnFETick() end
check("still held one tick short", CONNECT_ARGS, nil)
HANDLERS.OnFETick()
check("overlay removed", UI_ADDED, 0)
check("join went ahead", CONNECT_ARGS ~= nil, true)

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
