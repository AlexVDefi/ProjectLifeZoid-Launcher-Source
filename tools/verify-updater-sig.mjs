import { createHash, createPublicKey, verify } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const files = process.argv.slice(2);
if (files.length === 0) {
    console.error("usage: verify-updater-sig.mjs <bundle>...   (each checked against <bundle>.sig)");
    process.exit(2);
}

const unwrap = (b64) => Buffer.from(b64.trim(), "base64").toString("utf8").split(/\r?\n/);

const conf = JSON.parse(readFileSync(resolve(here, "..", "src-tauri", "tauri.conf.json"), "utf8"));
const pub = Buffer.from(unwrap(conf.plugins.updater.pubkey)[1], "base64");
if (pub.length !== 42 || pub.subarray(0, 2).toString() !== "Ed") {
    console.error("tauri.conf.json plugins.updater.pubkey is not a minisign Ed25519 public key");
    process.exit(2);
}
const keyId = pub.subarray(2, 10);
const key = createPublicKey({
    key: Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), pub.subarray(10)]),
    format: "der",
    type: "spki",
});

let failed = 0;
for (const file of files) {
    try {
        const lines = unwrap(readFileSync(`${file}.sig`, "utf8"));
        const blob = Buffer.from(lines[1], "base64");
        const trusted = lines[2].replace(/^trusted comment: /, "");
        const globalSig = Buffer.from(lines[3], "base64");
        const alg = blob.subarray(0, 2).toString();
        const sig = blob.subarray(10, 74);
        if (blob.length !== 74 || (alg !== "ED" && alg !== "Ed")) throw new Error("not a minisign Ed25519 signature");
        if (!blob.subarray(2, 10).equals(keyId)) throw new Error("signed by a different key than tauri.conf.json trusts");
        const body = readFileSync(file);
        const message = alg === "ED" ? createHash("blake2b512").update(body).digest() : body;
        if (!verify(null, message, key, sig)) throw new Error("signature does NOT verify");
        if (!verify(null, Buffer.concat([sig, Buffer.from(trusted, "utf8")]), key, globalSig)) {
            throw new Error("trusted comment does NOT verify");
        }
        console.log(`ok  ${file}`);
    } catch (e) {
        failed++;
        console.error(`BAD ${file}: ${e.message ?? e}`);
    }
}
process.exit(failed ? 1 : 0);
