import { createHash, createPublicKey, verify } from "node:crypto";
import { readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const PAYLOAD_RS = join(REPO, "src-tauri", "src", "payload.rs");
const CONFIG_RS = join(REPO, "src-tauri", "src", "config.rs");

function fail(msg) {
    console.error(msg);
    process.exit(1);
}

function pinnedKey() {
    const src = readFileSync(PAYLOAD_RS, "utf8");
    const m = src.match(/const PUBLIC_KEY_HEX: &str = "([0-9a-fA-F]{64})"/);
    if (!m) fail(`could not read PUBLIC_KEY_HEX from ${PAYLOAD_RS}`);
    return m[1];
}

function defaultUrl() {
    const src = readFileSync(CONFIG_RS, "utf8");
    const m = src.match(/None => "([^"]+manifest\.json)"/);
    return m ? m[1] : null;
}

const args = process.argv.slice(2);
function flag(name) {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : null;
}

const localDir = flag("--dir");
const url = flag("--url") ?? (localDir ? null : defaultUrl());
const keyHex = flag("--key") ?? pinnedKey();
const includeServer = args.includes("--server");

if (!/^[0-9a-fA-F]{64}$/.test(keyHex)) fail(`not a 32-byte ed25519 public key: ${keyHex}`);
if (!localDir && !url) fail("nothing to check: pass --url or --dir");

async function getBytes(where) {
    if (localDir) {
        const p = resolve(localDir, where);
        if (!existsSync(p)) return null;
        return readFileSync(p);
    }
    const base = url.replace(/manifest\.json$/, "");
    const res = await fetch(base + where);
    if (!res.ok) return null;
    return Buffer.from(await res.arrayBuffer());
}

const source = localDir ? resolve(localDir) : url;
console.log(`source     ${source}`);
console.log(`public key ${keyHex}`);
console.log(`           read from ${PAYLOAD_RS}`);
console.log("");

const manifestBytes = await getBytes("manifest.json");
if (!manifestBytes) fail("could not read manifest.json");
const sigBytes = await getBytes("manifest.json.sig");
if (!sigBytes) fail("could not read manifest.json.sig");

const spki = Buffer.concat([
    Buffer.from("302a300506032b6570032100", "hex"),
    Buffer.from(keyHex, "hex"),
]);

let signatureOk = false;
try {
    const key = createPublicKey({ key: spki, format: "der", type: "spki" });
    signatureOk = verify(null, manifestBytes, key, sigBytes);
} catch (e) {
    fail(`signature check could not run: ${String(e.message ?? e)}`);
}

if (!signatureOk) fail("SIGNATURE DOES NOT VERIFY -- stop here, do not trust these files");
console.log("signature  ok, manifest is signed by that key");

const manifest = JSON.parse(manifestBytes.toString("utf8").replace(/^﻿/, ""));
console.log(`build      ${manifest.build}`);
console.log(`built with ${manifest.javacVersion || "(not recorded)"} ${manifest.javacFlags || ""}`);
console.log(`against    projectzomboid.jar sha256 ${manifest.builtAgainstJarSha256}`);
console.log("");

const entries = includeServer
    ? [...manifest.files, ...manifest.serverFiles]
    : manifest.files;
const seen = new Set();
const wanted = entries.filter((e) => {
    if (seen.has(e.path)) return false;
    seen.add(e.path);
    return true;
});

let ok = 0;
const bad = [];
const missing = [];

for (const entry of wanted) {
    const bytes = await getBytes(`files/${entry.path}`);
    if (!bytes) {
        missing.push(entry.path);
        continue;
    }
    const got = createHash("sha256").update(bytes).digest("hex");
    if (got === entry.sha256) {
        ok += 1;
    } else {
        bad.push({ path: entry.path, want: entry.sha256, got });
    }
}

console.log(`${ok}/${wanted.length} files match the signed manifest`);

for (const b of bad) {
    console.log(`  MISMATCH ${b.path}`);
    console.log(`           signed ${b.want}`);
    console.log(`           served ${b.got}`);
}
for (const p of missing) {
    console.log(`  MISSING  ${p}`);
}

if (bad.length || missing.length) {
    console.log("");
    console.log("This release is NOT intact. Do not run it.");
    process.exit(1);
}

console.log("");
console.log("Every file is exactly what was signed.");
console.log("To also prove those class files came from the source in this repo, run:");
console.log("  .\\tools\\reproduce-payload.ps1");
