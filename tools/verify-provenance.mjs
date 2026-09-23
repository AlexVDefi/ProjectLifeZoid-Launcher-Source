import { createHash, createPublicKey, verify } from "node:crypto";
import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const REPO = dirname(dirname(fileURLToPath(import.meta.url)));
const PAYLOAD_RS = join(REPO, "src-tauri", "src", "payload.rs");
const CONFIG_RS = join(REPO, "src-tauri", "src", "config.rs");

function fail(msg, code = 1) {
    console.error(msg);
    process.exit(code);
}

const args = process.argv.slice(2);
function flag(name) {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : null;
}

function pinnedKey() {
    const m = readFileSync(PAYLOAD_RS, "utf8").match(/const PUBLIC_KEY_HEX: &str = "([0-9a-fA-F]{64})"/);
    if (!m) fail(`could not read PUBLIC_KEY_HEX from ${PAYLOAD_RS}`);
    return m[1];
}

function defaultUrl() {
    const m = readFileSync(CONFIG_RS, "utf8").match(/None => "([^"]+manifest\.json)"/);
    return m ? m[1] : null;
}

const localDir = flag("--dir");
const url = flag("--url") ?? (localDir ? null : defaultUrl());
const keyHex = flag("--key") ?? pinnedKey();
if (!localDir && !url) fail("nothing to check: pass --url or --dir");

async function getBytes(where) {
    if (localDir) {
        const p = resolve(localDir, where);
        return existsSync(p) ? readFileSync(p) : null;
    }
    const res = await fetch(url.replace(/manifest\.json$/, "") + where);
    return res.ok ? Buffer.from(await res.arrayBuffer()) : null;
}

const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");

console.log(`manifest   ${localDir ? resolve(localDir) : url}`);
const manifestBytes = await getBytes("manifest.json");
const sigBytes = await getBytes("manifest.json.sig");
if (!manifestBytes || !sigBytes) fail("could not read manifest.json and its signature");

const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), Buffer.from(keyHex, "hex")]);
const key = createPublicKey({ key: spki, format: "der", type: "spki" });
if (!verify(null, manifestBytes, key, sigBytes)) fail("SIGNATURE DOES NOT VERIFY -- stop here, do not trust these files");
console.log(`signature  ok (key ${keyHex.slice(0, 16)}..., read from payload.rs)`);

const manifest = JSON.parse(manifestBytes.toString("utf8").replace(/^﻿/, ""));
const prov = manifest.provenance;
console.log(`build      ${manifest.build}`);
if (!prov) {
    fail(
        "\nThis manifest carries no CI provenance. It was signed before builds moved to GitHub Actions,\n" +
            "so the only check available is .\\tools\\reproduce-payload.ps1.",
    );
}
if (prov.commit !== manifest.sourceCommit) fail("provenance.commit and sourceCommit disagree");
console.log(`source     https://github.com/${prov.repo}/tree/${prov.commit}`);
console.log(`built by   ${prov.run}`);
console.log("");

const sumsUrl = `https://github.com/${prov.repo}/releases/download/${prov.release}/payload-sha256sums.txt`;
const res = await fetch(sumsUrl);
if (!res.ok) fail(`could not download ${sumsUrl} (${res.status})`);
const sumsBytes = Buffer.from(await res.arrayBuffer());
if (sha256(sumsBytes) !== prov.sums) fail(`${sumsUrl} is not the file the manifest names (sha256 differs)`);
console.log(`sums file  matches the signed manifest (${prov.release})`);

const work = mkdtempSync(join(tmpdir(), "plz-provenance-"));
try {
    const sumsPath = join(work, "payload-sha256sums.txt");
    writeFileSync(sumsPath, sumsBytes);
    const gh = spawnSync(
        "gh",
        [
            "attestation", "verify", sumsPath,
            "--repo", prov.repo,
            "--signer-workflow", `${prov.repo}/${prov.workflow}`,
            "--source-digest", prov.commit,
            "--deny-self-hosted-runners",
        ],
        { encoding: "utf8" },
    );
    if (gh.error || gh.status === null) {
        fail(
            "\nThe GitHub CLI (gh) is needed to check the attestation: https://cli.github.com\n" +
                `Or check it by hand at https://github.com/${prov.repo}/attestations`,
            2,
        );
    }
    if (gh.status !== 0) {
        console.error(gh.stdout + gh.stderr);
        fail("ATTESTATION DOES NOT VERIFY -- GitHub did not build this from that commit");
    }
    console.log(`attested   by GitHub: ${prov.workflow} built it from ${prov.commit.slice(0, 12)} on a GitHub-hosted runner`);
} finally {
    rmSync(work, { recursive: true, force: true });
}

const attested = new Map();
for (const line of sumsBytes.toString("utf8").split(/\r?\n/)) {
    const m = line.match(/^([0-9a-f]{64}) [ *]?(.+)$/);
    if (m) attested.set(m[2], m[1]);
}

const seen = new Set();
const entries = [...manifest.files, ...(manifest.serverFiles ?? [])].filter((e) => !seen.has(e.path) && seen.add(e.path));
const bad = entries.filter((e) => attested.get(e.path) !== e.sha256);
console.log(`classes    ${entries.length - bad.length}/${entries.length} match what CI built`);
for (const e of bad) console.log(`  NOT FROM CI  ${e.path}`);
if (bad.length) fail("\nThe signed manifest lists class files that CI did not build. Do not trust this release.");

console.log("");
console.log("Every class the launcher installs is byte-for-byte what GitHub Actions compiled from the");
console.log(`public source at that commit. Read it at https://github.com/${prov.repo}/tree/${prov.commit}`);
console.log("To check the files your launcher actually downloads match the manifest: node tools/verify-release.mjs");
