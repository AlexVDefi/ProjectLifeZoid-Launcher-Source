import { createHash, createPrivateKey, sign } from "node:crypto";
import { cpSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "..");

function arg(name, fallback) {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const distDir = resolve(repo, "java", "dist");
const outDir = resolve(repo, arg("out", "release"));
const privPath = resolve(here, "keys", "release-private.pem");

const builtAgainstPath = join(distDir, "built-against.json");
let builtAgainst;
try {
    builtAgainst = JSON.parse(readFileSync(builtAgainstPath, "utf8").replace(/^﻿/, ""));
} catch {
    console.error(`missing ${builtAgainstPath} -- run java/build.ps1 first`);
    process.exit(1);
}

if (!Array.isArray(builtAgainst.client) || !Array.isArray(builtAgainst.server)) {
    console.error(`${builtAgainstPath} has no client/server split -- rebuild with java/build.ps1`);
    process.exit(1);
}
if (builtAgainst.client.length === 0 || builtAgainst.server.length === 0) {
    console.error("refusing to sign a release with an empty client or server payload");
    process.exit(1);
}

rmSync(outDir, { recursive: true, force: true });
mkdirSync(join(outDir, "files"), { recursive: true });

function collect(relPaths) {
    return relPaths.map((rel) => {
        const abs = join(distDir, rel.split("/").join(sep));
        let bytes;
        try {
            bytes = readFileSync(abs);
        } catch {
            console.error(`${rel} is listed in built-against.json but missing from ${distDir}`);
            process.exit(1);
        }
        const dest = join(outDir, "files", rel);
        mkdirSync(dirname(dest), { recursive: true });
        cpSync(abs, dest);
        return {
            path: rel,
            sha256: createHash("sha256").update(bytes).digest("hex"),
            size: statSync(abs).size,
        };
    });
}

const files = collect([...builtAgainst.client].sort());
const serverFiles = collect([...builtAgainst.server].sort());

const build = Number(arg("build", builtAgainst.build ?? 1));

function readJson(path) {
    return JSON.parse(readFileSync(path, "utf8").replace(/^﻿/, ""));
}

const serverCfg = readJson(resolve(here, "server.json"));
for (const key of ["host", "connectPort", "queryPort", "name"]) {
    if (serverCfg[key] === undefined) {
        console.error(`tools/server.json is missing "${key}"`);
        process.exit(1);
    }
}
const server = {
    host: serverCfg.host,
    connectPort: serverCfg.connectPort,
    queryPort: serverCfg.queryPort,
    name: serverCfg.name,
};

const contentCfg = readJson(resolve(here, "content.json"));

function workshopId(raw) {
    const s = String(raw).trim();
    if (/^\d+$/.test(s)) return s;
    const m = s.match(/[?&]id=(\d+)/);
    return m ? m[1] : null;
}

function workshopUpdatedOverrides() {
    const out = new Map();
    for (let i = 0; i < process.argv.length; i++) {
        if (process.argv[i] !== "--workshop-updated") continue;
        const raw = process.argv[i + 1];
        if (!raw) {
            console.error("--workshop-updated needs a value: <epoch> or <id>=<epoch>");
            process.exit(1);
        }
        const [key, value] = raw.includes("=") ? raw.split("=", 2) : ["*", raw];
        const epoch = Number(value);
        if (!Number.isInteger(epoch) || epoch <= 0) {
            console.error(`--workshop-updated: ${JSON.stringify(raw)} is not epoch seconds`);
            process.exit(1);
        }
        out.set(key, epoch);
    }
    return out;
}

const updatedOverrides = workshopUpdatedOverrides();

const mods = (contentCfg.mods ?? []).map((m, i) => {
    const id = workshopId(m.id ?? m);
    if (!id) {
        console.error(`content.json mods[${i}]: cannot read a Workshop id from ${JSON.stringify(m.id ?? m)}`);
        process.exit(1);
    }
    const entry = { id, name: String(m.name ?? id) };
    const updated = updatedOverrides.get(id) ?? updatedOverrides.get("*") ?? m.timeUpdated;
    if (updated) entry.timeUpdated = Number(updated);
    return entry;
});

if (updatedOverrides.size === 0 && !mods.some((m) => m.timeUpdated)) {
    console.warn("WARNING: no Workshop publish time recorded.");
    console.warn("         If a Workshop item changed in this release, players whose Steam has");
    console.warn("         not caught up will hang on the join screen with no explanation.");
    console.warn("         Pass --workshop-updated <epoch> to close that window.");
}

const seen = new Set();
for (const m of mods) {
    if (seen.has(m.id)) {
        console.error(`content.json lists Workshop id ${m.id} more than once`);
        process.exit(1);
    }
    seen.add(m.id);
}

const collectionMods = (contentCfg.collectionMods ?? []).map((m, i) => {
    const id = workshopId(m.id ?? m);
    if (!id) {
        console.error(`content.json collectionMods[${i}]: cannot read a Workshop id from ${JSON.stringify(m.id ?? m)}`);
        process.exit(1);
    }
    if (seen.has(id)) {
        console.error(`content.json lists Workshop id ${id} in both mods and collectionMods`);
        process.exit(1);
    }
    seen.add(id);
    return { id, name: String(m.name ?? id) };
});

if (contentCfg.collectionMods?.length && !contentCfg.collectionId) {
    console.error("content.json lists collectionMods but no collectionId to attach them to");
    process.exit(1);
}

const manifest = {
    schema: 1,
    build,
    builtAgainstJarSha256: builtAgainst.builtAgainst.jarSha256,
    classReleaseTarget: builtAgainst.classReleaseTarget,
    javacVersion: String(builtAgainst.javacVersion ?? ""),
    javacFlags: String(builtAgainst.javacFlags ?? ""),
    sourceRepo: String(contentCfg.sourceRepo ?? ""),
    sourceCommit: String(builtAgainst.sourceCommit ?? ""),
    sourceDirty: Number(builtAgainst.sourceDirty ?? 0),
    server,
    requiredServerBuild: build,
    files,
    serverFiles,

    mods,
    collectionId: String(contentCfg.collectionId ?? ""),
    collectionName: String(contentCfg.collectionName ?? ""),
    collectionMods,
    newsUrl: String(contentCfg.newsUrl ?? ""),
    links: {
        discord: String(contentCfg.links?.discord ?? ""),
        website: String(contentCfg.links?.website ?? ""),
        tiktok: String(contentCfg.links?.tiktok ?? ""),
        youtube: String(contentCfg.links?.youtube ?? ""),
    },
};

const bytes = Buffer.from(JSON.stringify(manifest, null, 2) + "\n", "utf8");
writeFileSync(join(outDir, "manifest.json"), bytes);

let privateKey;
try {
    privateKey = createPrivateKey(readFileSync(privPath));
} catch {
    console.error(`missing signing key: ${privPath}`);
    console.error("run: node tools/keygen.mjs");
    process.exit(1);
}
writeFileSync(join(outDir, "manifest.json.sig"), sign(null, bytes, privateKey));

console.log(`build            ${build}`);
console.log(`client files     ${files.length}`);
console.log(`server files     ${serverFiles.length}`);
console.log(`builtAgainstJar  ${manifest.builtAgainstJarSha256}`);
console.log(`mods             ${mods.length}${manifest.collectionId ? "" : "  (no collectionId set)"}`);
console.log(`collection mods  ${collectionMods.length}${collectionMods.length ? "" : "  (collection cannot be verified)"}`);
console.log(`newsUrl          ${manifest.newsUrl || "(none)"}`);
console.log(`out              ${outDir}`);
for (const f of files) console.log(`  client  ${f.sha256.slice(0, 12)}  ${String(f.size).padStart(6)}  ${f.path}`);
for (const f of serverFiles) console.log(`  server  ${f.sha256.slice(0, 12)}  ${String(f.size).padStart(6)}  ${f.path}`);
