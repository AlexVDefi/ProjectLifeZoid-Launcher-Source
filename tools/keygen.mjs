import { generateKeyPairSync } from "node:crypto";
import { mkdirSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const keyDir = resolve(here, "keys");
const privPath = resolve(keyDir, "release-private.pem");

if (existsSync(privPath)) {
    console.error(`refusing to overwrite existing key: ${privPath}`);
    console.error("delete it deliberately if you really mean to rotate.");
    process.exit(1);
}

const { publicKey, privateKey } = generateKeyPairSync("ed25519");

mkdirSync(keyDir, { recursive: true });
writeFileSync(privPath, privateKey.export({ type: "pkcs8", format: "pem" }));

const raw = publicKey.export({ type: "spki", format: "der" }).subarray(12);
const hex = raw.toString("hex");
writeFileSync(resolve(keyDir, "release-public.hex"), hex + "\n");

console.log(`private key -> ${privPath}   (gitignored, never commit)`);
console.log(`public key  -> ${resolve(keyDir, "release-public.hex")}`);
console.log(`\npaste into src-tauri/src/payload.rs:\n`);
console.log(`const PUBLIC_KEY_HEX: &str = "${hex}";`);
