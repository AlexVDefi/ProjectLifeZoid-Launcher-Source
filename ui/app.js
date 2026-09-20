const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const $ = (id) => document.getElementById(id);

const STEPS = [
    ["repair", "Checking last session"],
    ["preflight", "Locating Project Zomboid"],
    ["manifest", "Verifying manifest"],
    ["verify", "Checking game version"],
    ["server", "Checking server"],
    ["mods", "Checking Workshop mods"],
    ["download", "Syncing patch"],
    ["patch", "Patching launch config"],
    ["account", "Preparing Steam account"],
    ["launch", "Starting through Steam"],
    ["running", "Waiting for patch stamp"],
    ["playing", "Playing"],
    ["restore", "Restoring game files"],
];

const ICON = {
    check: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M13.5 4.5 6.5 11.5 3 8"/></svg>',
    alert: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 2.8 1.8 13.2h12.4L8 2.8Z"/><path d="M8 6.6v3"/><path d="M8 11.6h.01"/></svg>',
    cross: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><circle cx="8" cy="8" r="6"/><path d="M10 6 6 10M6 6l4 4"/></svg>',
    download: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 2.5v7"/><path d="m5 6.8 3 3 3-3"/><path d="M2.8 12.4h10.4"/></svg>',
    discord: '<svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M13.5 3.1A11.1 11.1 0 0 0 10.8 2l-.35.7a9.7 9.7 0 0 0-4.9 0L5.2 2a11.2 11.2 0 0 0-2.7 1.1C.8 5.65.35 8.15.57 10.6A10.9 10.9 0 0 0 3.9 12.3l.8-1.08a6.2 6.2 0 0 1-.98-.48l.23-.18c1.9.88 4.15.88 6.03 0l.28.2c-.32.18-.65.34-.99.47l.8 1.07a10.8 10.8 0 0 0 3.34-1.7c.26-2.84-.44-5.32-1.91-7.49ZM5.33 9.25c-.66 0-1.19-.6-1.19-1.33 0-.74.53-1.34 1.19-1.34.67 0 1.2.6 1.2 1.34 0 .73-.53 1.33-1.2 1.33Zm5.34 0c-.66 0-1.2-.6-1.2-1.33 0-.74.54-1.34 1.2-1.34.67 0 1.2.6 1.2 1.34 0 .73-.53 1.33-1.2 1.33Z"/></svg>',
    link: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6.5 9.5 9.5 6.5M5.2 11.8l-1 .1a2.8 2.8 0 0 1-2.1-4.8l2.2-2.2a2.8 2.8 0 0 1 4.4.55M10.8 4.2l1-.1a2.8 2.8 0 0 1 2.1 4.8l-2.2 2.2a2.8 2.8 0 0 1-4.4-.55"/></svg>',
};

const state = { playing: false, editingName: false, nameMode: null, joinError: null, status: null, server: null, mods: null, startedAt: 0, timer: null };

function toast(title, sub, bad) {
    const el = $("toast");
    el.querySelector(".t").textContent = title;
    el.querySelector(".s").textContent = sub || "";
    el.classList.toggle("bad", !!bad);
    el.classList.add("show");
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => el.classList.remove("show"), 3600);
}

function log(key, text, bad) {
    const box = $("log");
    box.querySelector(".empty")?.remove();
    const line = document.createElement("div");
    const k = document.createElement("span");
    k.className = bad ? "e" : "k";
    k.textContent = (key || "").padEnd(10, " ");
    line.append(k, document.createTextNode(text));
    box.append(line);
    box.scrollTop = box.scrollHeight;
}

function defList(el, rows) {
    el.innerHTML = "";
    for (const [k, v, cls] of rows) {
        const dt = document.createElement("dt");
        dt.textContent = k;
        const dd = document.createElement("dd");
        dd.textContent = v;
        if (cls) dd.className = cls;
        el.append(dt, dd);
    }
}

function mmss(ms) {
    const s = Math.floor(ms / 1000);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function safeWebUrl(value) {
    return typeof value === "string" && /^https?:\/\//i.test(value) ? value : "";
}

function socialIcon(name) {
    return name === "discord" ? ICON.discord : ICON.link;
}

function requirementKind(r) {
    if (!r.verified) return "info";
    if (r.behindServer > 0) return "bad";
    if (r.outOfDate > 0) return "warn";
    if (r.missing > 0) return "warn";
    if (r.downloading > 0) return "info";
    return "ok";
}

function plural(n, one, many) {
    return n === 1 ? one : many;
}

function requirementDetail(r) {
    const many = r.total > 1;
    if (!r.verified) return "Open it in Steam and choose Subscribe to all.";
    if (r.behindServer > 0) {
        return many
            ? `${r.behindServer} ${plural(r.behindServer, "mod is", "mods are")} older than this release. Let Steam update ${plural(r.behindServer, "it", "them")}.`
            : "Older than this release. Let Steam update it.";
    }
    if (r.outOfDate > 0) {
        return many
            ? `${r.outOfDate} ${plural(r.outOfDate, "update is", "updates are")} waiting in Steam.`
            : "An update is waiting in Steam.";
    }
    if (r.missing > 0) {
        return many ? `${r.missing} of ${r.total} not subscribed.` : "Not subscribed.";
    }
    if (r.downloading > 0) {
        return many
            ? `Steam is downloading ${r.downloading} of ${r.total}.`
            : "Steam is downloading it.";
    }
    return many ? `All ${r.total} mods installed.` : "Installed.";
}

function paintMods(report) {
    state.mods = report;
    const reqs = report.requirements || [];
    const behind = report.behindServer || 0;
    const stale = report.outOfDate || 0;
    const missing = report.missing || 0;
    const active = report.downloading || 0;

    $("mods-copy").textContent = reqs.length === 0
        ? "No Workshop pack has been published yet."
        : behind > 0
            ? "Play is blocked until Steam finishes updating. Leave the game closed and let it run."
            : stale > 0
                ? "Let Steam apply its updates first, or the server will refuse the join."
                : missing > 0
                    ? "Anything missing downloads when you join. Subscribing now means not waiting on the connect screen."
                    : active > 0
                        ? "Steam is still fetching some of these. You can play; the join waits for them."
                        : reqs.some((r) => !r.verified)
                            ? "This release cannot check the collection for you. Subscribe to it once and Steam keeps it current."
                            : "Nothing to do. Steam keeps these up to date on its own.";

    $("btn-preload-mods").disabled = !report.collectionUrl;

    const list = $("req-list");
    list.innerHTML = "";
    for (const r of reqs) {
        const kind = requirementKind(r);
        const row = document.createElement("button");
        row.className = "req " + kind;
        row.type = "button";
        row.dataset.id = r.id;
        row.title = r.kind === "collection"
            ? "Open the collection in Steam"
            : "Open this mod in Steam";
        row.innerHTML =
            `<span class="req-mark">${kind === "ok" ? ICON.check : kind === "bad" ? ICON.cross : kind === "warn" ? ICON.alert : ICON.download}</span>` +
            '<span class="req-body"><span class="req-name"></span><span class="req-detail"></span><span class="req-names"></span></span>' +
            `<span class="req-go">${ICON.link}</span>`;
        row.querySelector(".req-name").textContent = r.name;
        row.querySelector(".req-detail").textContent = requirementDetail(r);
        const names = row.querySelector(".req-names");
        const shown = r.total > 1 ? r.missingNames || [] : [];
        if (shown.length) {
            const rest = r.missing - shown.length;
            names.textContent = shown.join(", ") + (rest > 0 ? `, and ${rest} more` : "");
        } else {
            names.hidden = true;
        }
        list.append(row);
    }
}

function paintNews(items) {
    const grid = $("news-grid");
    grid.innerHTML = "";
    if (!items.length) {
        grid.innerHTML = '<p class="empty-state">Updates will appear here when the community feed is connected.</p>';
        return;
    }
    for (const item of items) {
        const card = document.createElement("button");
        card.className = "news-card";
        card.type = "button";
        card.dataset.url = safeWebUrl(item.url);
        const image = safeWebUrl(item.image || "");
        if (image) {
            const media = document.createElement("img");
            media.className = "news-image";
            media.src = image;
            media.alt = "";
            card.append(media);
        }
        const body = document.createElement("span");
        body.className = "news-body";
        const meta = document.createElement("span");
        meta.className = "news-meta";
        meta.textContent = [item.tag, item.date].filter(Boolean).join(" · ") || "UPDATE";
        const title = document.createElement("strong");
        title.textContent = item.title || "Read update";
        const excerpt = document.createElement("span");
        excerpt.className = "news-excerpt";
        excerpt.textContent = item.excerpt || "Read the latest from Project Life Zoid.";
        body.append(meta, title, excerpt);
        card.append(body);
        grid.append(card);
    }
}

function paintLinks(links) {
    const box = $("community-links");
    box.innerHTML = "";
    for (const name of ["discord", "website", "tiktok", "youtube"]) {
        const url = safeWebUrl(links[name] || "");
        if (!url) continue;
        const button = document.createElement("button");
        button.className = "social-link";
        button.type = "button";
        button.dataset.url = url;
        button.innerHTML = `${socialIcon(name)}<span>${name}</span>`;
        box.append(button);
    }
    box.hidden = box.childElementCount === 0;
}

function paintServer(s) {
    const pill = $("s-state");
    const label = pill.querySelector("span");

    if (!s) {
        pill.className = "pill off";
        label.textContent = "No response";
        for (const id of ["s-map", "s-version", "s-ping", "s-players"]) $(id).textContent = "—";
        $("s-max").textContent = "";
        return;
    }

    pill.className = "pill on";
    label.textContent = "Online";
    $("s-name").textContent = s.name || "Project Life Zoid";
    $("s-map").textContent = s.map || "—";
    $("s-version").textContent = s.version ? "B" + s.version : "—";
    $("s-ping").textContent = s.pingMs + " ms";
    $("s-players").textContent = s.players;
    $("s-max").textContent = " / " + s.maxPlayers;
}

function setIdentityHelp(text, bad) {
    const el = $("identity-help");
    el.textContent = text || "";
    el.classList.toggle("bad", !!bad);
}

function paintIdentity(st) {
    const name = st.accountUsername;
    const names = st.accountNames || [];
    // One character is the ordinary case and stays a plain label. The picker only appears for
    // an account that bought a slot and filled it, so nobody else sees a dropdown of one.
    const many = names.length > 1;
    const picker = $("identity-picker");

    $("identity-name").hidden = many;
    $("identity-name").textContent = name || "Not chosen";
    $("identity-name").classList.toggle("unset", !name);

    picker.hidden = !many;
    if (many) {
        picker.innerHTML = "";
        for (const known of names) {
            const option = document.createElement("option");
            option.value = known;
            option.textContent = known;
            option.selected = known === name;
            picker.appendChild(option);
        }
    }

    // Once the name is locked in, the front page offers no route to a second character. That
    // lives in the Details drawer instead, so staff point the people who have a slot at it
    // rather than every player finding it here and asking why the server turned them away.
    $("btn-edit-name").hidden = st.accountConfirmed;
    $("btn-edit-name").textContent = name ? "Change" : "Choose";
    $("identity-locked").hidden = !st.accountConfirmed;

    if (state.editingName) return;
    if (state.joinError) return setIdentityHelp(state.joinError, true);
    setIdentityHelp(
        many
            ? "Pick the character you want to play, then press Play."
            : st.accountConfirmed
                ? "Locked to this Steam account. An admin can change it."
                : name
                    ? "Locks in the first time you join."
                    : "The name other players will see."
    );
}

// "add" makes a second character, "rename" adopts a name an admin has already changed on the
// server, and "choose" is the first name of all. Only the mode decides which, never the
// confirmed flag: an account is confirmed for both of the first two.
function startEditingName(mode) {
    const chosen = mode || (state.status?.accountConfirmed ? "add" : "choose");
    state.editingName = true;
    state.nameMode = chosen;
    state.joinError = null;
    $("identity-view").hidden = true;
    $("identity-edit").hidden = false;
    $("name-input").value = chosen === "add" ? "" : (state.status?.accountUsername || "");
    $("name-input").focus();
    $("name-input").select();
    setIdentityHelp(nameModeHelp(chosen));
}

function nameModeHelp(mode) {
    if (mode === "add") {
        return "The name for a new character. It is permanent, other players see it, and the "
            + "server refuses it unless staff have given you a character slot.";
    }
    if (mode === "rename") {
        return "The new name an admin has given your account. Only save it once they have said "
            + "the rename is done, or the server turns you away.";
    }
    return "Max 50 characters. Your in-game character can use your full name.";
}

function stopEditingName() {
    state.editingName = false;
    state.nameMode = null;
    $("identity-edit").hidden = true;
    $("identity-view").hidden = false;
    if (state.status) paintIdentity(state.status);
}

// One row for both sources of -debug: the launcher's own toggle and the player's Steam
// launch options. Only the built-in admin role survives the join with either of them set,
// so an unconfirmed role is a warning and a known denial is a failure.
function debugCheck(st) {
    const wanted = st.launchDebug || !!st.debugLaunchOption;
    if (st.blockingLaunchOption) return ["Debug mode on", "bad"];
    if (!wanted) return ["Launch options ok", "ok"];
    if (st.roleGrantsDebug === true) return ["Debug mode allowed", "ok"];
    if (st.roleGrantsDebug === false) return ["Debug mode refused", "bad"];
    return ["Debug mode on (unverified)", "warn"];
}

function paintChecks(st) {
    const box = $("checks");
    box.innerHTML = "";

    const rows = [
        [st.installDir ? "Game found" : "Game not found", st.installDir ? "ok" : "bad"],
        [st.steamId ? "Steam ready" : "Steam not signed in", st.steamId ? "ok" : "bad"],
        [st.accountUsername ? "Username set" : "No username", st.accountUsername ? "ok" : "warn"],
        debugCheck(st),
        [
            st.jarMatches === true ? "Version matched" : st.jarMatches === false ? "Update required" : "Version unknown",
            st.jarMatches === true ? "ok" : st.jarMatches === false ? "bad" : "warn",
        ],
        [
            { ready: "Patch ready", pending: "Patch downloads on Play" }[st.patchState] || "Patch unavailable",
            { ready: "ok", pending: "info" }[st.patchState] || "bad",
        ],
    ];

    for (const [text, kind] of rows) {
        const el = document.createElement("span");
        el.className = "check " + kind;
        el.innerHTML = kind === "ok" ? ICON.check : kind === "warn" ? ICON.alert : kind === "info" ? ICON.download : ICON.cross;
        el.append(document.createTextNode(text));
        box.append(el);
    }
}

function paintNotice(st) {
    const el = $("notice");
    const problems = [...st.problems];
    if (st.repairedOnStart) {
        problems.unshift("A previous session ended unexpectedly. Your game files have been restored.");
    }
    if (!problems.length) {
        el.hidden = true;
        el.innerHTML = "";
        return;
    }
    const bad = st.jarMatches === false || !st.installDir || !!st.blockingLaunchOption;
    el.hidden = false;
    el.className = "notice" + (bad ? " bad" : "");
    el.innerHTML = bad ? ICON.cross : ICON.alert;
    const span = document.createElement("span");
    span.textContent = problems[0] + (problems.length > 1 ? `  (+${problems.length - 1} more in Details)` : "");
    el.append(span);
}

function paintPlay(st) {
    const btn = $("play");
    const label = $("play-label");
    const sub = $("play-sub");

    if (state.playing) {
        btn.disabled = true;
        label.textContent = "Running";
        sub.textContent = "Restoring when you quit";
        return;
    }
    label.textContent = "Play";

    if (!st.installDir) {
        btn.disabled = true;
        sub.textContent = "Project Zomboid was not found";
    } else if (!st.steamId) {
        btn.disabled = true;
        sub.textContent = "Open and sign in to Steam";
    } else if (st.blockingLaunchOption) {
        btn.disabled = true;
        sub.textContent = `Remove ${st.blockingLaunchOption} from Steam launch options`;
    } else if (!st.accountUsername) {
        btn.disabled = true;
        sub.textContent = "Choose a username first";
    } else if (st.jarMatches === false) {
        btn.disabled = true;
        sub.textContent = "Waiting for a launcher update";
    } else if (st.jarMatches === null || st.build == null) {
        btn.disabled = true;
        sub.textContent = "Cannot reach the patch server";
    } else {
        btn.disabled = false;
        sub.textContent = st.payloadInstalled ? "Launches through Steam" : "Downloads the patch first";
    }
}

function paintInstall(st) {
    const field = $("install-path");
    if (document.activeElement !== field) field.value = st.installDir || "";
    $("install-hint").textContent = !st.installDir
        ? "Project Zomboid was not found. Pick the folder Steam installed it into."
        : st.installDirManual
            ? "Set by hand. The launcher uses this folder instead of searching Steam."
            : "Found automatically. Only change this if the launcher is looking in the wrong place.";
    $("btn-install-clear").hidden = !st.installDirManual;
    $("btn-locate").hidden = !!st.installDir || state.playing;
}

function setInstallError(text) {
    const el = $("install-error");
    el.textContent = text || "";
    el.hidden = !text;
}

async function applyInstallDir(path) {
    setInstallError("");
    try {
        const resolved = await invoke("set_install_dir", { path });
        $("install-path").value = resolved;
        await refreshStatus();
        log("install", "set to " + resolved);
        toast("Game folder set", resolved);
        return true;
    } catch (e) {
        setInstallError(String(e));
        return false;
    }
}

async function browseForInstall() {
    setInstallError("");
    let picked;
    try {
        picked = await invoke("pick_install_dir");
    } catch (e) {
        setInstallError("Could not open the folder picker (" + e + "). Type or paste the path instead.");
        return;
    }
    if (!picked) return;
    $("install-path").value = picked;
    await applyInstallDir(picked);
}

function paintDrawer(st, s) {
    defList($("d-patch"), [
        ["Build", st.build != null ? String(st.build) : "—"],
        [
            "Payload",
            st.patchState === "ready"
                ? "Installed"
                : st.patchState === "pending"
                    ? "Downloads on first Play"
                    : st.patchDetail || "Unavailable",
            { ready: "ok", pending: "" }[st.patchState] ?? "bad",
        ],
        [
            "Game version",
            st.jarMatches === true ? "Matches patch" : st.jarMatches === false ? "Stale" : "Unknown",
            st.jarMatches === true ? "ok" : st.jarMatches === false ? "bad" : "warn",
        ],
        ["Last verified", st.lastStamp ? "Build " + st.lastStamp.build : "Never", st.lastStamp ? "ok" : ""],
        ["Loaded from", st.lastStamp ? st.lastStamp.origin : "—"],
    ]);

    paintInstall(st);
    defList($("d-install"), [
        ["Path", st.installDir || "Not found", st.installDir ? "" : "bad"],
        ["Jar", st.jarSha256 ? st.jarSha256.slice(0, 20) : "—"],
        ["Steam ID", st.steamId || "Not signed in", st.steamId ? "" : "bad"],
        [
            "Username",
            st.accountUsername
                ? st.accountUsername + (st.accountConfirmed ? " (this Steam account)" : " (not joined yet)")
                : "Not chosen",
            st.accountUsername ? "" : "warn",
        ],
        [
            "Steam options",
            st.steamLaunchOptions || "None",
            st.blockingLaunchOption ? "bad" : st.steamLaunchOptions ? "warn" : "",
        ],
    ]);

    const rows = [
        ["Host", st.serverHost || "—"],
        ["Connect port", st.serverConnectPort != null ? String(st.serverConnectPort) : "—"],
        ["Query port", st.serverQueryPort != null ? String(st.serverQueryPort) : "—"],
    ];
    if (s) for (const [k, v] of Object.entries(s.rules)) rows.push([k, v || "(empty)"]);
    defList($("d-server"), rows);

    const characters = st.accountNames || [];
    defList($("d-characters"), [
        ["Playing as", st.accountUsername || "Not chosen"],
        ["On this account", characters.length ? characters.join(", ") : "None yet"],
    ]);
    // Before the first join the front page still owns choosing a name, so there is nothing to
    // add to yet.
    $("btn-new-character").disabled = !st.accountConfirmed;
    // Before the first join the name is still unlocked on the front page, so there is nothing
    // an admin could have renamed and no second route to offer.
    $("btn-change-username").disabled = !st.accountConfirmed;

    const ovBanner = $("server-override-banner");
    ovBanner.hidden = !st.serverOverridden;
    if (st.serverOverridden) {
        ovBanner.textContent =
            "Advanced override active: joining " +
            (st.serverHost || "?") + ":" + (st.serverConnectPort ?? "?") +
            " instead of the normal server.";
        $("server-advanced").open = true;
    }

    const confirmed = st.roleGrantsDebug === true;
    const relevant =
        !!st.debugLaunchOption || st.debugAllowed || st.launchDebug || st.roleGrantsDebug !== null;
    $("debug-toggle-row").hidden = confirmed || !(!!st.debugLaunchOption || st.debugAllowed);
    $("allow-debug").checked = !!st.debugAllowed;
    $("launch-debug").checked = !!st.launchDebug;

    const warn = $("launch-debug-warn");
    warn.classList.toggle("danger", st.roleGrantsDebug !== true);
    warn.classList.toggle("armed", !!st.launchDebug && st.roleGrantsDebug !== true);

    const note = $("debug-role-note");
    if (confirmed) {
        note.textContent = st.roleName
            ? "The server confirmed your role (" + st.roleName + ") may use debug mode."
            : "The server confirmed your role may use debug mode.";
    } else if (st.roleGrantsDebug === false) {
        note.textContent = st.roleName
            ? "The server says your role (" + st.roleName + ") may not use debug mode."
            : "The server says your role may not use debug mode.";
    } else {
        note.textContent = "";
    }
    note.hidden = !relevant || !note.textContent;
}

let fitTimer = null;

function measureContentHeight() {
    const app = document.querySelector(".app");
    const main = document.querySelector("main");
    const hero = document.querySelector(".hero");
    if (!app || !main) return 0;

    const top = main.getBoundingClientRect().top;
    let reach = 0;
    for (const child of main.children) {
        if (child.hidden) continue;
        reach = Math.max(reach, child.getBoundingClientRect().bottom - top + main.scrollTop);
    }
    reach += parseFloat(getComputedStyle(main).paddingBottom) || 0;

    let needed = app.clientHeight - main.clientHeight + Math.max(main.scrollHeight, reach);

    const floor = parseFloat(
        getComputedStyle(document.documentElement).getPropertyValue("--hero-min")
    );
    if (hero && Number.isFinite(floor)) {
        needed += floor - hero.getBoundingClientRect().height;
    }
    return Math.ceil(needed);
}

function fitWindow() {
    clearTimeout(fitTimer);
    fitTimer = setTimeout(async () => {
        const height = measureContentHeight();
        if (height <= 0) return;
        try {
            await invoke("fit_window", { contentHeight: height });
        } catch {
        }
    }, 220);
}

const MEMORY_PRESETS = [3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768];

function paintMemory(st) {
    $("memory-chip").hidden = !st.memorySupported;
    if (!st.memorySupported) return;

    const select = $("select-memory");
    const mb = st.memoryMb;

    let custom = select.querySelector("option[data-custom]");
    if (mb != null && !MEMORY_PRESETS.includes(mb)) {
        if (!custom) {
            custom = document.createElement("option");
            custom.dataset.custom = "1";
            select.append(custom);
        }
        custom.value = String(mb);
        custom.textContent = (mb / 1024).toFixed(1).replace(/\.0$/, "") + " GB (custom)";
    } else if (custom) {
        custom.remove();
    }

    select.value = mb != null ? String(mb) : "";
    select.disabled = state.playing || !st.installDir;
    $("memory-chip").title = mb != null
        ? "Memory allocated to Project Zomboid"
        : "Could not read ProjectZomboid64.json";
    $("memory-warn").hidden = !st.memoryIsDefault;
}

$("select-memory").addEventListener("change", async (e) => {
    const mb = parseInt(e.target.value, 10);
    if (!Number.isFinite(mb)) return;
    e.target.disabled = true;
    try {
        await invoke("set_memory_mb", { mb });
        await refreshStatus();
        toast("Memory set to " + (mb / 1024) + " GB", "Takes effect next time Project Zomboid starts.");
    } catch (err) {
        toast("Could not change memory", String(err), true);
        await refreshStatus();
    }
});

async function refreshStatus() {
    try {
        state.status = await invoke("get_status");
    } catch (e) {
        log("error", String(e), true);
        return;
    }
    const st = state.status;
    $("chip-steam").textContent = st.steamId ? "Steam ready" : "Steam offline";
    $("chip-steam").classList.toggle("off", !st.steamId);
    $("chip-build").textContent = st.build != null ? "Build " + st.build : "Build —";
    $("chip-version").textContent = "v" + st.launcherVersion;
    paintMemory(st);
    paintIdentity(st);
    paintChecks(st);
    paintNotice(st);
    paintPlay(st);
    paintDrawer(st, state.server);
    fitWindow();
}

async function refreshServer() {
    try {
        state.server = await invoke("server_status");
        paintServer(state.server);
    } catch (e) {
        state.server = null;
        paintServer(null);
    }
    if (state.status) paintDrawer(state.status, state.server);
}

async function refreshCommunity() {
    const [report, news, links] = await Promise.allSettled([
        invoke("mods_report"),
        invoke("news_items"),
        invoke("links"),
    ]);
    if (report.status === "fulfilled") paintMods(report.value);
    else $("mods-copy").textContent = "Your mods cannot be checked until the launcher reaches the patch server.";
    if (news.status === "fulfilled") paintNews(news.value);
    if (links.status === "fulfilled") paintLinks(links.value);
    fitWindow();
}

function setStep(key) {
    const i = STEPS.findIndex(([k]) => k === key);
    if (i < 0) return;
    $("bar-fill").style.width = ((i + 1) / STEPS.length) * 100 + "%";
    $("work-step").textContent = STEPS[i][1];
}

// The step labels are static, so a long download and a wedged one looked identical from out
// here and people killed the launcher on the second. Anything the backend says beyond the step
// label goes on its own line.
function setStepDetail(key, detail) {
    const el = $("work-detail");
    const label = (STEPS.find(([k]) => k === key) || [])[1] || "";
    const text = (detail || "").trim();
    el.textContent = text && text !== label ? text : "";
    el.hidden = !el.textContent;
}

function enterWorking() {
    state.playing = true;
    state.startedAt = Date.now();
    $("checks").hidden = true;
    $("progress").hidden = false;
    $("bar-fill").style.width = "0%";
    $("work-detail").textContent = "";
    $("work-detail").hidden = true;
    clearInterval(state.timer);
    state.timer = setInterval(() => {
        $("work-time").textContent = mmss(Date.now() - state.startedAt);
    }, 1000);
    paintPlay(state.status || {});
}

function exitWorking() {
    state.playing = false;
    clearInterval(state.timer);
    $("progress").hidden = true;
    $("checks").hidden = false;
}

function setDrawer(open) {
    const d = $("drawer");
    const b = $("drawer-backdrop");
    $("btn-details").setAttribute("aria-expanded", String(open));
    if (open) {
        d.hidden = false;
        b.hidden = false;
        refreshSteamAccounts();
        refreshStatus();
        requestAnimationFrame(() => {
            d.classList.add("in");
            b.classList.add("in");
        });
    } else {
        d.classList.remove("in");
        b.classList.remove("in");
        setTimeout(() => {
            d.hidden = true;
            b.hidden = true;
        }, 260);
    }
}

$("btn-details").addEventListener("click", () => setDrawer($("drawer").hidden));
$("btn-close").addEventListener("click", () => setDrawer(false));
$("drawer-backdrop").addEventListener("click", () => setDrawer(false));
document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") setDrawer(false);
});

$("play").addEventListener("click", async () => {
    state.joinError = null;
    enterWorking();
    try {
        const r = await invoke("play");
        for (const n of r.notes) log("note", n);
        if (r.boundUsername) {
            log("account", "the server knows this Steam account as " + r.boundUsername);
        }
        if (r.patchVerified) log("verified", "shadow classes loaded from " + r.stamp.origin);

        if (r.joinError) {
            state.joinError = r.joinError;
            log("join", r.joinError, true);
            toast("Could not join", r.joinError, true);
        } else if (r.patchVerified) {
            toast("Session finished", "Patch was active. Game files restored.");
        } else {
            toast("Session finished", "The patch could not be verified. See Details.", true);
        }
        if (r.restored) log("restore", "ProjectZomboid64.json restored");
    } catch (e) {
        log("error", String(e), true);
        toast("Cannot launch", String(e), true);
        setDrawer(true);
    } finally {
        exitWorking();
        await refreshStatus();
    }
});

$("btn-edit-name").addEventListener("click", () => startEditingName("choose"));

$("btn-new-character").addEventListener("click", () => {
    setDrawer(false);
    startEditingName("add");
});

$("btn-change-username").addEventListener("click", () => {
    setDrawer(false);
    startEditingName("rename");
});

$("identity-picker").addEventListener("change", async (e) => {
    const chosen = e.target.value;
    try {
        await invoke("set_account_username", { name: chosen });
        await refreshStatus();
        toast("Character switched", "Press Play to join as " + chosen + ".");
    } catch (err) {
        setIdentityHelp(String(err), true);
    }
});
$("btn-cancel-name").addEventListener("click", stopEditingName);

$("identity-edit").addEventListener("submit", async (e) => {
    e.preventDefault();
    const renaming = state.nameMode === "rename";
    try {
        await invoke(renaming ? "rename_account_username" : "set_account_username",
                     { name: $("name-input").value });
        stopEditingName();
        await refreshStatus();
        toast(renaming ? "Username changed" : "Username saved",
              "Press Play to join as " + state.status.accountUsername + ".");
    } catch (err) {
        setIdentityHelp(String(err), true);
    }
});

// Read straight off the file the java patch reads, rather than out of launcher State: there is
// no second copy to drift. A failure leaves the box ticked, which is what the patch defaults to.
async function loadBodyOverride() {
    try {
        $("body-override").checked = await invoke("get_body_override");
    } catch {
        $("body-override").checked = true;
    }
}

$("body-override").addEventListener("change", async (e) => {
    try {
        await invoke("set_body_override", { enabled: e.target.checked });
        toast(
            e.target.checked ? "Tomb bodies on" : "Tomb bodies off",
            "Applies the next time you connect. It only changes what you see."
        );
    } catch (err) {
        e.target.checked = !e.target.checked;
        toast("Could not change that", String(err), true);
    }
});

// Read off options.ini every time rather than trusting a stored flag: the player can undo the
// preset by hand in the game's own options screen, and the box has to follow the file.
async function loadPerformanceMode() {
    const note = $("performance-mode-note");
    try {
        const s = await invoke("get_performance_mode");
        $("performance-mode").checked = s.enabled;
        $("performance-mode").disabled = !s.canChange;
        if (!s.canChange) {
            note.textContent =
                "Project Zomboid is running. Quit it before changing this — the game " +
                "rewrites its settings file when it closes and would overwrite the change.";
            note.hidden = false;
        } else {
            note.hidden = true;
        }
    } catch {
        $("performance-mode").checked = false;
        note.hidden = true;
    }
}

$("performance-mode").addEventListener("change", async (e) => {
    const wanted = e.target.checked;
    e.target.disabled = true;
    try {
        const s = await invoke("set_performance_mode", { enabled: wanted });
        e.target.checked = s.enabled;
        toast(
            s.enabled ? "Performance mode on" : "Performance mode off",
            s.enabled
                ? s.keyCount + " graphics settings lowered. Press Play to apply them."
                : "Your previous graphics settings are back. Press Play to apply them."
        );
    } catch (err) {
        e.target.checked = !wanted;
        toast("Could not change that", String(err), true);
    } finally {
        e.target.disabled = false;
        loadPerformanceMode();
    }
});

async function loadServerOverride() {
    try {
        const o = await invoke("get_server_override");
        $("ov-host").value = o ? o.host : "";
        $("ov-connect").value = o ? o.connect_port : "";
        $("ov-query").value = o ? o.query_port : "";
    } catch {
    }
}

$("btn-ov-apply").addEventListener("click", async () => {
    const host = $("ov-host").value.trim();
    try {
        const set = await invoke("set_server_override", {
            host,
            connectPort: Number($("ov-connect").value || 0),
            queryPort: Number($("ov-query").value || 0),
        });
        await refreshStatus();
        toast(
            set ? "Server override set" : "Server override cleared",
            set
                ? "This launcher will now join " + host + " until you clear it."
                : "Back to the normal server."
        );
    } catch (err) {
        toast("Could not set the override", String(err));
    }
});

$("btn-ov-clear").addEventListener("click", async () => {
    try {
        await invoke("set_server_override", { host: "", connectPort: 0, queryPort: 0 });
        await loadServerOverride();
        await refreshStatus();
        toast("Server override cleared", "Back to the normal server.");
    } catch (err) {
        toast("Could not clear the override", String(err));
    }
});

$("launch-debug").addEventListener("change", async (e) => {
    try {
        await invoke("set_launch_debug", { enabled: e.target.checked });
        await refreshStatus();
        log("debug", e.target.checked ? "launching with -debug" : "launching normally");
        toast(
            e.target.checked ? "Debug mode on" : "Debug mode off",
            e.target.checked
                ? "The game will start with -debug. Only the server's built-in admin role can join like that."
                : "The game will start normally."
        );
    } catch (err) {
        e.target.checked = !e.target.checked;
        toast("Could not change that", String(err), true);
    }
});

$("allow-debug").addEventListener("change", async (e) => {
    try {
        await invoke("set_allow_debug", { allowed: e.target.checked });
        await refreshStatus();
        toast(
            e.target.checked ? "Debug joins allowed" : "Debug joins blocked",
            e.target.checked
                ? "Only works if the server grants your role debug access. It will confirm on your next join."
                : "The launcher will refuse to start with -debug set."
        );
    } catch (err) {
        e.target.checked = !e.target.checked;
        toast("Could not change that", String(err), true);
    }
});

$("btn-install-browse").addEventListener("click", browseForInstall);

$("btn-install-apply").addEventListener("click", async () => {
    await applyInstallDir($("install-path").value);
});

$("install-path").addEventListener("keydown", (e) => {
    if (e.key === "Enter") $("btn-install-apply").click();
});

$("btn-install-clear").addEventListener("click", async () => {
    setInstallError("");
    try {
        const found = await invoke("clear_install_dir");
        await refreshStatus();
        toast(
            found ? "Back to automatic" : "Nothing found automatically",
            found || "Pick the folder by hand, or open Steam and install the game."
        );
    } catch (e) {
        setInstallError(String(e));
    }
});

$("btn-locate").addEventListener("click", async () => {
    setDrawer(true);
    await browseForInstall();
});

$("btn-copy-steamid").addEventListener("click", async () => {
    const id = state.status?.steamId;
    if (!id) return toast("Not signed in", "Open Steam first.", true);
    try {
        await navigator.clipboard.writeText(id);
        toast("Steam ID copied", "Send it to an admin to get access.");
    } catch {
        toast("Could not copy", id, true);
    }
});

$("btn-refresh").addEventListener("click", async () => {
    await Promise.all([refreshStatus(), refreshServer()]);
    toast("Refreshed", "");
});

$("btn-restore").addEventListener("click", async () => {
    try {
        const did = await invoke("restore_now");
        toast(
            did ? "Restored" : "Nothing to restore",
            did ? "ProjectZomboid64.json is back to normal" : "Your install is already clean"
        );
        log("restore", did ? "restored from backup" : "nothing to restore");
        await refreshStatus();
    } catch (e) {
        toast("Restore failed", String(e), true);
        log("error", String(e), true);
    }
});

let updateReady = null;

function paintUpdate(info) {
    const note = $("update-note");
    const btn = $("btn-update");
    if (info && info.available) {
        updateReady = info;
        note.textContent = "Version " + info.version + " is available.";
        note.classList.add("ready");
        btn.textContent = "Install " + info.version;
    } else {
        updateReady = null;
        note.textContent = info ? "Up to date (" + info.currentVersion + ")." : "";
        note.classList.remove("ready");
        btn.textContent = "Check for updates";
    }
    $("btn-details").classList.toggle("has-update", !!updateReady);
    $("btn-details").title = updateReady ? "An update is available" : "";
}

async function checkForUpdate(quiet) {
    try {
        const info = await invoke("check_for_update");
        paintUpdate(info);
        if (info.available) {
            log("update", "launcher " + info.version + " available");
        } else if (!quiet) {
            toast("Up to date", "Launcher " + info.currentVersion + " is the latest.");
        }
    } catch (e) {
        $("update-note").textContent = "Could not check for updates.";
        if (!quiet) toast("Update check failed", String(e), true);
        log("error", "update check: " + String(e), true);
    }
}

$("btn-update").addEventListener("click", async () => {
    const btn = $("btn-update");
    if (!updateReady) {
        btn.disabled = true;
        await checkForUpdate(false);
        btn.disabled = false;
        return;
    }
    btn.disabled = true;
    $("update-note").textContent = "Downloading " + updateReady.version + "...";
    try {
        await invoke("install_update");
    } catch (e) {
        $("update-note").textContent = String(e);
        toast("Update failed", String(e), true);
        log("error", "update install: " + String(e), true);
        btn.disabled = false;
    }
});

async function refreshSteamAccounts() {
    let accounts = [];
    try {
        accounts = await invoke("steam_accounts");
    } catch (e) {
        log("error", "steam accounts: " + String(e), true);
    }
    const row = $("steam-account-row");
    const sel = $("steam-account");
    row.hidden = accounts.length < 2 && accounts.some((a) => a.selected);
    sel.innerHTML = "";
    for (const a of accounts) {
        const opt = document.createElement("option");
        opt.value = a.steamId;
        opt.textContent =
            (a.personaName || a.accountName || a.steamId) + (a.mostRecent ? " (signed in)" : "");
        opt.selected = a.selected;
        sel.appendChild(opt);
    }
    if (!accounts.length) {
        const opt = document.createElement("option");
        opt.textContent = "No Steam accounts found";
        sel.appendChild(opt);
        row.hidden = false;
    }
}

$("steam-account").addEventListener("change", async (e) => {
    try {
        await invoke("set_steam_account", { steamId: e.target.value });
        await Promise.all([refreshStatus(), refreshSteamAccounts()]);
        toast("Account set", "The server will confirm it on your next join.");
    } catch (err) {
        toast("Could not set that account", String(err), true);
    }
});

$("btn-preload-mods").addEventListener("click", async () => {
    try {
        await invoke("mods_open_collection");
        toast("Steam opened", "Choose ‘Subscribe to all’, then come back here.");
        setTimeout(refreshMods, 2500);
    } catch (e) {
        toast("Could not open Workshop", String(e), true);
    }
});

async function refreshMods() {
    try {
        paintMods(await invoke("mods_report"));
    } catch (e) {
        $("mods-copy").textContent = "Could not read your Workshop library.";
    }
    fitWindow();
}

$("req-list").addEventListener("click", async (e) => {
    const row = e.target.closest(".req");
    if (!row) return;
    try {
        await invoke("mods_open_item", { id: row.dataset.id });
    } catch (err) {
        toast("Could not open Workshop item", String(err), true);
    }
});

$("news-grid").addEventListener("click", async (e) => {
    const card = e.target.closest(".news-card");
    if (!card?.dataset.url) return;
    try {
        await invoke("open_link", { url: card.dataset.url });
    } catch (err) {
        toast("Could not open update", String(err), true);
    }
});

$("community-links").addEventListener("click", async (e) => {
    const link = e.target.closest(".social-link");
    if (!link?.dataset.url) return;
    try {
        await invoke("open_link", { url: link.dataset.url });
    } catch (err) {
        toast("Could not open link", String(err), true);
    }
});

listen("play-progress", (e) => {
    setStep(e.payload.step);
    setStepDetail(e.payload.step, e.payload.detail);
    log(e.payload.step, e.payload.detail);
});

$("hero").addEventListener("error", () => {
    $("hero").style.display = "none";
});

$("log").innerHTML = '<div class="empty">No activity yet</div>';
$("memory-warn").innerHTML = ICON.alert;
refreshStatus();
loadServerOverride();
loadBodyOverride();
loadPerformanceMode();
refreshServer();
refreshCommunity();
checkForUpdate(true);
setInterval(refreshServer, 30000);
setInterval(refreshMods, 15000);
