# ProjectLifeZoid Launcher

Desktop launcher for the ProjectLifeZoid Project Zomboid server. It installs the server's
custom Java classes, starts the game through Steam, and puts your install back exactly as it
was when you quit.

This repository holds the launcher and the Java patch it installs, in full. It is public so
you can read what runs on your machine and check what you downloaded against it yourself.
Deployment and server-operations tooling is not here; nothing that runs on your machine is
missing.

## What it does to your game

Project Zomboid has no Java mod loader. The only mechanism is classpath shadowing, so the
launcher edits **one file** in your install, `ProjectZomboid64.json`, and adds one entry:

```json
"classpath": [
  "C:/Users/<you>/AppData/Local/ProjectLifeZoidLauncher/patch/1",
  ".",
  "projectzomboid.jar"
]
```

No class files are ever copied into the game folder. The file is backed up before the edit and
restored when the game exits. If the launcher is killed mid-session, the next start notices and
restores it before doing anything else.

macOS has no `ProjectZomboid64.json`, so there the payload is copied into the app bundle's
`Contents/Java` instead. Every file written is recorded, and a restore removes exactly those.

## What it does not do

- No web sign-in, no password. You pick a username; identity comes from the Steam session
  you are already in.
- No subscribing to Workshop items on your behalf. It opens the Steam page and you decide.
- No admin rights. The installer is per-user.
- No telemetry.

## Verify it yourself

Nothing players run is compiled on a maintainer's machine. The Java payload and the launcher
installers are both built by GitHub Actions in this repository, from a public commit, and each
build carries a [build provenance attestation](https://docs.github.com/actions/security-for-github-actions/using-artifact-attestations)
signed by GitHub naming the commit and workflow that produced it.

The signing keys are not on a maintainer's machine either. The manifest key and the launcher
updater key are secrets of this repository's `release` environment, which only runs from `main`
and only after the maintainer approves the run. `sign-manifest.yml` signs a manifest only for a
payload `payload.yml` attested, and `sign-updater.yml` signs only installers `build.yml` attested,
so neither can sign a file built anywhere else.

**1. Check the installer you downloaded**

```powershell
gh attestation verify ProjectLifeZoid-Launcher-<version>-setup.exe --repo AlexVDefi/ProjectLifeZoid-Launcher-Source
```

This proves `.github/workflows/build.yml` built that exact file from a commit in this repository.
Without the GitHub CLI, compare its SHA-256 against `SHA256SUMS.txt`, published beside the
download, which only proves you got the file that was published.

**2. Check every file against the signed manifest**

```bash
node tools/verify-release.mjs
```

This fetches the live manifest and its signature, verifies the signature against the key
compiled into the launcher (`PUBLIC_KEY_HEX` in `src-tauri/src/payload.rs`, read straight out
of the source so you are checking the key it really uses), then downloads every payload file
and compares its SHA-256 to the manifest. It reports any file that does not match and exits
non-zero.

Add `--dir release` to check a local copy, or `--server` to include the server-side classes.

A hostile or broken host can withhold files. It cannot make the launcher install anything that
was not signed.

To check the manifest itself was signed by this repository's workflow, download the live
`manifest.json` from the launcher's release URL and run:

```powershell
gh attestation verify manifest.json --repo AlexVDefi/ProjectLifeZoid-Launcher-Source --signer-workflow AlexVDefi/ProjectLifeZoid-Launcher-Source/.github/workflows/sign-manifest.yml
```

`node tools/verify-updater-sig.mjs <installer>` checks a launcher update's `.sig` against the
updater key `tauri.conf.json` pins.

**3. Prove those class files came from this source**

A signature proves who published a file, not what is in it. The quickest way to close that gap
needs only Node and the GitHub CLI:

```bash
node tools/verify-provenance.mjs
```

It checks the manifest signature, then follows the manifest's `provenance` block to the
`payload-build-<n>` release in this repository, confirms GitHub attests that
`.github/workflows/payload.yml` produced that release's `payload-sha256sums.txt` from the commit
the manifest names, and checks every class the manifest lists against it.

To trust nothing but your own compiler, rebuild it instead:

```powershell
.\tools\reproduce-payload.ps1
```

It checks your `projectzomboid.jar` against the manifest's `builtAgainstJarSha256`, recompiles
`java/src` and `java/plz-src` with the compiler and flags the manifest records, and compares
every resulting `.class` byte-for-byte against the signed hashes. The build is deterministic,
so a clean run means every class the launcher installs was compiled from the source in this
repository and nothing else.

The manifest names the exact commit it was built from, so there is no guessing which version
of this repository produced a release:

```json
"sourceRepo":   "https://github.com/<owner>/<repo>",
"sourceCommit": "<40-character commit sha>",
"sourceDirty":  0
```

That field is inside the signed manifest, so it cannot be altered without breaking the
signature. The tool tells you to `git checkout` that commit if you are on a different one. A
non-zero `sourceDirty` is an admission that the release was signed from a tree with
uncommitted changes, and that the commit does not fully describe what shipped.

The manifest records `jdk` and `javacFlags` for exactly this reason. CI compiles with Temurin
25.0.4+7; Oracle JDK 25 has produced identical classes, but the tool warns when yours differs.
Builds made through CI are always `sourceDirty: 0`.

**4. Read what a class actually does**

```powershell
.\tools\inspect-class.ps1 zombie.ZomboidGlobals
```

Most of the payload is *shadow classes*: whole-file replacements for classes already in
`projectzomboid.jar`. This disassembles the shipped class, disassembles the vanilla one out of
your own jar, and prints the difference, so you can see what changed rather than take a claim
on trust. Classes that do not exist in vanilla are reported as additions and printed in full.

`-Full` includes the constant pool.

**What these checks do not prove**

**An attestation proves where a file was built, not that the source is harmless.** It rules out
a maintainer shipping something other than what is published here; judging what is published is
still up to you. The launcher executable does not reproduce byte-for-byte (Rust, Tauri and NSIS
do not), so for the installer the attestation is the check.

**The server's Workshop mod is not in this repository.** This repo covers the launcher and the
Java patch it installs. The Lua content the server runs ships through Steam and is not
published here, so nothing above says anything about it.

`java/plz-src` is mirrored from the ProjectLifeZoid mod repository, which is private. Step 3
proves the shipped classes were compiled from the `.java` files you can read here; it says
nothing about that repository's history.

**5. Watch what it changes**

`plzctl` runs the whole flow headlessly, so you can inspect each step:

```powershell
cd src-tauri
cargo build --bin plzctl --features cli

.\target\debug\plzctl.exe status    # install, jar hash, version gate, username
.\target\debug\plzctl.exe sync      # verify signature, download payload
.\target\debug\plzctl.exe patch     # apply, and leave applied
.\target\debug\plzctl.exe stamp     # what the classes recorded at boot
.\target\debug\plzctl.exe restore
```

## How a launch runs

1. Repair anything a previous session left behind.
2. Find the install; refuse if the game is already running.
3. Fetch the manifest and verify its signature before parsing it.
4. Gate on version: your `projectzomboid.jar` SHA-256 must match the manifest's
   `builtAgainstJarSha256`, and the live server's patch build must match the manifest's.
5. Download any missing payload files, each checked against the signed manifest.
6. Back up `ProjectZomboid64.json`, set a dirty flag, then edit it.
7. Seed your username and a one-shot join request.
8. Launch `steam.exe -applaunch 108600`.
9. Confirm the classes loaded, read back why a join was refused if it was.
10. Restore on exit.

There is no override for the version gate. A stale shadow class does not crash: it silently
reverts every engine fix in the classes it replaces, and the symptom appears weeks later.

## Getting on the server

1. Install the launcher, press **Copy my Steam ID** in Details.
2. An admin runs `/addsteamid <SteamID64>` once.
3. Type a username, press Play.

On the first join the server binds that name to the SteamID it verified on the connection.
Every later join is checked against that binding. Usernames are 2 to 20 ASCII characters and
cannot start with `admin`; the survivor you build in game can be named anything.

If you would rather not run the launcher at all, [docs/MANUAL-INSTALL.md](docs/MANUAL-INSTALL.md)
walks through installing the same patch by hand and joining from the vanilla server browser,
including the three version checks the launcher does for you and what happens when you skip them.

## Build from source

Requires Rust, Node, a JDK, and a Project Zomboid install.

```powershell
.\java\build.ps1 -Build <n>            # compile the Java payload
node tools\keygen.mjs                  # one-time: create a signing key of your own
$env:PLZ_MANIFEST_SIGNING_KEY = Get-Content tools\keys\release-private.pem -Raw
node tools\make-manifest.mjs --build <n> --allow-unverified   # produce release/ (manifest + signature + files)
.\tools\make-server-patch.ps1          # package the dedicated-server half
.\tools\package.ps1                    # build the installer
```

`java/build.ps1` reads the class file version out of the jar and picks `--release` from it,
rather than hardcoding one.

`package.ps1` refuses to build if the signature does not verify against the key pinned in
`payload.rs`, if `release/` was signed from a different payload than `java/dist` holds, if the
game has updated since the payload was built, or if the baked-in manifest URL does not serve
that build. Use `-LocalTest` to build without a host.

Tests:

```powershell
cd src-tauri; cargo test --features cli   # launcher
.\tools\test-bootstrap.ps1                # in-game bootstrap Lua, run in Kahlua
.\java\test\run-tests.ps1                 # server queue config
```

## Layout

| Path | What |
|---|---|
| `src-tauri/` | the launcher: Rust core, Tauri shell, `plzctl` harness |
| `ui/` | the interface, plain HTML/CSS/JS, no bundler |
| `java/src` | Java classes owned by this repo |
| `java/plz-src` | gameplay patches, mirrored from the ProjectLifeZoid mod repo |
| `java/payload.json` | which classes ship, and to which side |
| `tools/` | build and signing scripts, and the three verification tools above |
| `release.json` | every release, newest first |

The launcher installs only the client half of the payload. Server classes are deployed to the
server by hand and never sent to a player.

## Notes for contributors

- `ui/` is embedded into the binary at compile time. Editing it and restarting the exe changes
  nothing; rebuild.
- The manifest URL is compiled in, not read at runtime. A signature stops a forged manifest but
  not an old genuine one, so a runtime override would be a rollback vector.
- A shadow class is a whole-file replacement. Copy the vanilla source exactly, never let a
  static initializer throw, and classify the class in `payload.json` or the build refuses.
- Editing a `.java` file changes its compiled bytes even when the code is unchanged, because
  javac records line numbers. Comment-only edits break reproduction against an already-signed
  release; rebuild and re-sign.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
