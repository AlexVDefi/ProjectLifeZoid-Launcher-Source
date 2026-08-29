import { createPublicKey, verify } from "node:crypto";
import { readFileSync } from "node:fs";

const [manifestPath, sigPath, publicKeyHex] = process.argv.slice(2);
if (!manifestPath || !sigPath || !publicKeyHex) {
    console.error("usage: verify-manifest.mjs <manifest.json> <manifest.json.sig> <publicKeyHex>");
    process.exit(2);
}

if (!/^[0-9a-fA-F]{64}$/.test(publicKeyHex)) {
    console.error(`not a 32-byte ed25519 public key: ${publicKeyHex}`);
    process.exit(2);
}

const spki = Buffer.concat([
    Buffer.from("302a300506032b6570032100", "hex"),
    Buffer.from(publicKeyHex, "hex"),
]);

try {
    const key = createPublicKey({ key: spki, format: "der", type: "spki" });
    const ok = verify(null, readFileSync(manifestPath), key, readFileSync(sigPath));
    if (!ok) {
        console.error("signature does NOT verify");
        process.exit(1);
    }
    console.log("ok");
} catch (e) {
    console.error(String(e.message ?? e));
    process.exit(1);
}
