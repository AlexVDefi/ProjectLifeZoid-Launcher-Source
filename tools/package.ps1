param(
    [string]$Version,
    [string]$ManifestUrl,
    [switch]$Portable,
    [switch]$LocalTest
)

if ($Portable) { $LocalTest = $true }

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$problems = @()
$warnings = @()

function Fail([string]$msg) { $script:problems += $msg }
function Soft([string]$msg) {
    if ($script:LocalTest) { $script:warnings += $msg } else { $script:problems += $msg }
}
function Line([string]$k, $v) { "{0,-22} {1}" -f $k, $v }
function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

$tauri = Get-Command cargo-tauri -ErrorAction SilentlyContinue
if (-not $tauri) {
    Fail "tauri-cli not found. Install it once with:  cargo install tauri-cli --version ""^2"" --locked"
    Line "tauri-cli" "MISSING"
} else {
    Line "tauri-cli" (& cargo tauri --version)
}

$cargoPath = Join-Path $repo "src-tauri\Cargo.toml"
$confPath = Join-Path $repo "src-tauri\tauri.conf.json"
$cargoText = Get-Content -LiteralPath $cargoPath -Raw
$conf = Get-Content -LiteralPath $confPath -Raw | ConvertFrom-Json

if ($Version) {
    $cargoText = [regex]::Replace($cargoText, '(?m)^version\s*=\s*".*"$', "version = ""$Version""", 1)
    [System.IO.File]::WriteAllText($cargoPath, $cargoText, (New-Object System.Text.UTF8Encoding($false)))
    $confText = Get-Content -LiteralPath $confPath -Raw
    $confText = [regex]::Replace($confText, '"version"\s*:\s*"[^"]*"', """version"": ""$Version""", 1)
    [System.IO.File]::WriteAllText($confPath, $confText, (New-Object System.Text.UTF8Encoding($false)))
    $conf = Get-Content -LiteralPath $confPath -Raw | ConvertFrom-Json
    $cargoText = Get-Content -LiteralPath $cargoPath -Raw
}

$cargoVersion = ([regex]::Match($cargoText, '(?m)^version\s*=\s*"([^"]+)"')).Groups[1].Value
if ($cargoVersion -ne $conf.version) {
    Fail "version mismatch: Cargo.toml $cargoVersion vs tauri.conf.json $($conf.version)"
}
Line "version" $conf.version
Line "product" $conf.productName

$manifestPath = Join-Path $repo "release\manifest.json"
$sigPath = "$manifestPath.sig"
if (-not (Test-Path $manifestPath) -or -not (Test-Path $sigPath)) {
    throw "release/ is missing. Run java\build.ps1 then tools\make-manifest.mjs first."
}
$manifestBytes = [System.IO.File]::ReadAllBytes($manifestPath)
$manifest = [System.Text.Encoding]::UTF8.GetString($manifestBytes) | ConvertFrom-Json

$payloadRs = Get-Content -LiteralPath (Join-Path $repo "src-tauri\src\payload.rs") -Raw
$pinnedHex = ([regex]::Match($payloadRs, 'PUBLIC_KEY_HEX:\s*&str\s*=\s*"([0-9a-fA-F]+)"')).Groups[1].Value
if (-not $pinnedHex) { Fail "could not read PUBLIC_KEY_HEX out of payload.rs" }

$verify = & node (Join-Path $PSScriptRoot "verify-manifest.mjs") $manifestPath $sigPath $pinnedHex 2>&1
if ($LASTEXITCODE -ne 0) {
    Fail "manifest signature does not verify against the key pinned in payload.rs: $verify"
} else {
    Line "signature" "verified against pinned key"
}

$builtAgainstPath = Join-Path $repo "java\dist\built-against.json"
if (Test-Path $builtAgainstPath) {
    $ba = Get-Content -LiteralPath $builtAgainstPath -Raw | ConvertFrom-Json
    if ($ba.builtAgainst.jarSha256 -ne $manifest.builtAgainstJarSha256) {
        Fail "release/ was signed from a different payload than java/dist holds. Re-run make-manifest.mjs."
    }
}
Line "payload build" $manifest.build
Line "client files" @($manifest.files).Count
Line "server files" @($manifest.serverFiles).Count

$installDirs = @($env:PZ_INSTALL_DIR, "D:\Games\Steam\steamapps\common\ProjectZomboid",
    "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")
$pzJar = $null
foreach ($d in $installDirs) {
    if ($d -and (Test-Path (Join-Path $d "projectzomboid.jar"))) {
        $pzJar = Join-Path $d "projectzomboid.jar"
        break
    }
}
if ($pzJar) {
    $sha = (Get-FileHash -LiteralPath $pzJar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sha -ne $manifest.builtAgainstJarSha256) {
        Fail "the game has updated since this payload was built. Every launcher shipped now would refuse to launch. Rebuild the payload."
    } else {
        Line "jar" "matches payload"
    }
} else {
    Line "jar" "not checked (no local install found)"
}

if (-not $manifest.newsUrl) {
    Soft "manifest newsUrl is empty; the news panel will sit blank for every player"
} elseif ($manifest.newsUrl -notmatch '^https?://') {
    Soft "manifest newsUrl is a local path ($($manifest.newsUrl)); only this machine can read it"
}
if (-not $manifest.collectionId) {
    Soft "manifest collectionId is empty; the Subscribe button stays disabled"
} elseif (-not $manifest.collectionMods -or @($manifest.collectionMods).Count -eq 0) {
    Soft "manifest has no collectionMods; the launcher cannot verify the collection. Run: python tools/collection-sync.py content $($manifest.collectionId)"
}
$linkNames = @("discord", "website", "tiktok", "youtube")
$setLinks = @($linkNames | Where-Object { $manifest.links.$_ })
if ($setLinks.Count -eq 0) {
    Soft "manifest has no community links; the footer is hidden entirely"
}
$placeholders = @($manifest.mods | Where-Object { $_.name -like "*Placeholder*" })
if ($placeholders.Count -gt 0) {
    Soft "manifest still lists $($placeholders.Count) placeholder mod(s); edit tools/content.json and re-sign"
}
if (-not $manifest.server.host) {
    Soft "manifest server.host is empty"
}
Line "workshop mods" @($manifest.mods).Count
Line "collection mods" @($manifest.collectionMods).Count
Line "server" "$($manifest.server.host):$($manifest.server.connectPort) (query $($manifest.server.queryPort))"

$fallbackUrl = ([regex]::Match(
        (Get-Content -LiteralPath (Join-Path $repo "src-tauri\src\config.rs") -Raw),
        'None => "([^"]+)"')).Groups[1].Value
$bakedUrl = $ManifestUrl
if (-not $bakedUrl -and $Portable) {
    $bakedUrl = "release/manifest.json"
}
if (-not $bakedUrl -and $LocalTest) {
    $bakedUrl = "file:///" + ($manifestPath -replace '\\', '/')
}
if (-not $bakedUrl) { $bakedUrl = $fallbackUrl }
if (-not $bakedUrl) { Fail "could not determine a manifest URL" }
Line "baked-in url" $bakedUrl

$isRelativeUrl = $bakedUrl -notmatch '^https?://' -and $bakedUrl -notmatch '^file:///' -and $bakedUrl -notmatch '^[A-Za-z]:'
$isLocalUrl = $bakedUrl -notmatch '^https?://'
if ($isRelativeUrl) {
    $relSource = Join-Path $repo ($bakedUrl -replace '/', [char]92)
    if (-not (Test-Path -LiteralPath $relSource)) {
        Fail "$bakedUrl does not exist under the repo; there is nothing to ship beside the exe"
    } else {
        Line "manifest check" "relative, resolved next to the exe at runtime"
    }
    if (-not $Portable) {
        Fail "a relative manifest URL only works in a -Portable build, which ships release/ alongside"
    }
} elseif ($isLocalUrl) {
    Soft "the baked-in manifest URL is local ($bakedUrl); this build works on this machine only"
    $localFile = $bakedUrl -replace '^file:///', '' -replace '/', [char]92
    if (-not (Test-Path -LiteralPath $localFile)) {
        Fail "$localFile does not exist; the baked-in manifest URL points at nothing"
    } else {
        $localJson = Get-Content -LiteralPath $localFile -Raw | ConvertFrom-Json
        if ($localJson.build -ne $manifest.build) {
            Fail "$localFile is build $($localJson.build), release/ is build $($manifest.build)"
        }
    }
} else {
    try {
        $hosted = Invoke-WebRequest -Uri $bakedUrl -UseBasicParsing -TimeoutSec 15
        $hostedJson = $hosted.Content | ConvertFrom-Json
        if ($hostedJson.build -ne $manifest.build) {
            Soft "$bakedUrl serves build $($hostedJson.build), release/ is build $($manifest.build). Upload release/ first."
        } else {
            Line "manifest check" "hosted, serving build $($hostedJson.build)"
        }
    } catch {
        Soft "$bakedUrl is unreachable ($($_.Exception.Message)). Every installed launcher fetches this URL on startup and disables Play without it."
    }
}

""
$warnings | ForEach-Object { "WARN     : $_" }
if ($problems.Count -gt 0) {
    $problems | ForEach-Object { "BLOCKED  : $_" }
    ""
    throw "$($problems.Count) problem(s). Fix them, or pass -LocalTest to build one anyway for hand-testing."
}
if ($warnings.Count -gt 0) {
    ""
    if ($Portable) {
        "PORTABLE TEST BUILD -- fine to hand to admins, not ready for players."
    } else {
        "LOCAL TEST BUILD -- do not distribute."
    }
    ""
}

function Invoke-TauriBuild {
    param([string]$WorkDir, [hashtable]$Vars, [int]$TimeoutSeconds = 900)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cargo"
    $psi.Arguments = "tauri build"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $WorkDir
    foreach ($name in $Vars.Keys) { $psi.Environment[$name] = $Vars[$name] }

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEndAsync()
    $stderr = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        & taskkill /PID $proc.Id /T /F 2>&1 | Out-Null
        throw "cargo tauri build exceeded $TimeoutSeconds s and was killed. If its last line mentions decrypting the signing key, the password variable did not reach it."
    }
    if ($stdout.Result) { Write-Host $stdout.Result }
    if ($stderr.Result) { Write-Host $stderr.Result }
    return $proc.ExitCode
}

"Building..."
$signKey = Join-Path $repo "tools\keys\updater-private.key"
if (-not (Test-Path -LiteralPath $signKey)) {
    throw "missing $signKey -- run: cargo tauri signer generate -w ..\tools\keys\updater-private.key"
}
$buildVars = @{
    PLZ_MANIFEST_URL_BAKED             = $bakedUrl
    TAURI_SIGNING_PRIVATE_KEY          = (Get-Content -LiteralPath $signKey -Raw).Trim()
    TAURI_SIGNING_PRIVATE_KEY_PASSWORD = ""
    CARGO_BUILD_JOBS                   = "4"
}

$buildDir = Join-Path $repo "src-tauri"
$buildExit = Invoke-TauriBuild -WorkDir $buildDir -Vars $buildVars
if ($buildExit -ne 0) {
    Write-Output "build failed; waiting 20s in case the exe is still locked, then retrying once"
    Start-Sleep -Seconds 20
    $buildExit = Invoke-TauriBuild -WorkDir $buildDir -Vars $buildVars
}
if ($buildExit -ne 0) { throw "cargo tauri build failed with exit code $buildExit" }

$nsisDir = Join-Path $repo "src-tauri\target\release\bundle\nsis"
$installer = Get-ChildItem $nsisDir -Filter "*-setup.exe" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $installer) { throw "no installer produced under $nsisDir" }

$mainName = "$($conf.mainBinaryName).exe"
$mainExe = Join-Path (Join-Path (Join-Path $repo "src-tauri") "target") (Join-Path "release" $mainName)
if (-not (Test-Path -LiteralPath $mainExe)) {
    throw "$mainExe was not produced"
}
$bytes = [System.IO.File]::ReadAllBytes($mainExe)
$peOffset = [BitConverter]::ToInt32($bytes, 0x3C)
$subsystem = [BitConverter]::ToUInt16($bytes, $peOffset + 4 + 20 + 68)
if ($subsystem -ne 2) {
    $kind = "unknown"
    if ($subsystem -eq 3) { $kind = "console" }
    throw ("$mainName is a $kind binary (PE subsystem $subsystem), not a GUI app. " +
        "cargo-tauri bundled the wrong binary. Check the [[bin]] entries in Cargo.toml.")
}
Line "main binary" "$mainName (GUI, $([int]($bytes.Length / 1KB)) KB)"

$sevenZip = @(
    (Join-Path $env:ProgramFiles "7-Zip\7z.exe"),
    (Join-Path ${env:ProgramFiles(x86)} "7-Zip\7z.exe")
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($sevenZip) {
    $listing = & $sevenZip l $installer.FullName 2>$null
    if (-not ($listing -match [regex]::Escape($mainName))) {
        throw "the installer does not contain $mainName"
    }
    if ($listing -match [regex]::Escape("plzctl.exe")) {
        throw "the installer contains plzctl.exe, the dev harness"
    }
    Line "installer holds" $mainName
} else {
    Line "installer holds" "NOT CHECKED (7-Zip not installed)"
}

$exeText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($mainExe))
if (-not $exeText.Contains($bakedUrl)) {
    throw ("the built binary does not contain $bakedUrl. The compile-time URL did not take; " +
        "check the rerun-if-env-changed line in src-tauri/build.rs.")
}
Line "baked url in exe" "confirmed"

$hash = (Get-FileHash -LiteralPath $installer.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if (-not $Portable) {
    ""
    Line "installer" $installer.FullName
    Line "size" ("{0:N1} MB" -f ($installer.Length / 1MB))
    Line "sha256" $hash
    ""
}
if ($warnings.Count -gt 0 -and -not $Portable) {
    "THIS IS A LOCAL TEST BUILD. $($warnings.Count) thing(s) above must be fixed before it is"
    "worth sending to anyone."
    ""
}
if (-not $Portable) {
    "Unsigned. Windows SmartScreen will warn on first run until this accumulates reputation, or"
    "until it is signed with an EV/OV code-signing certificate. Publish the sha256 above so people"
    "can check what they downloaded."
}

if ($Portable) {
    $portableName = "ProjectLifeZoid-Launcher-$($conf.version)-portable"
    $distDir = Join-Path $repo "dist"
    $portableDir = Join-Path $distDir $portableName
    if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
    New-Item -ItemType Directory -Force $portableDir | Out-Null

    Copy-Item -LiteralPath $mainExe -Destination (Join-Path $portableDir "$($conf.mainBinaryName).exe")
    Copy-Item -LiteralPath (Join-Path $repo "release") -Destination (Join-Path $portableDir "release") -Recurse

    $portableReadme = @"
ProjectLifeZoid Launcher $($conf.version) -- portable test build

Extract this whole folder anywhere and run $($conf.mainBinaryName).exe. Keep release\ next to
the exe: that is where the launcher reads its patch from, and it will not start without it.

There is no installer and nothing is written outside your own user profile.

BEFORE YOU RUN IT
  - Project Zomboid must be installed through Steam, and Steam must be signed in.
  - Clear your Steam launch options for Project Zomboid. '-debug', '-imgui' and '-nosteam' all
    stop you connecting. (Steam > Project Zomboid > Properties > Launch Options.)

FIRST RUN
  1. Press "Copy my Steam ID" under Details and send that number to an admin.
  2. Choose the character name other players will see. There is no password anywhere: the
     server recognises your Steam account. The name is locked in the first time you join.
  3. Press Play.

WHAT IT TOUCHES
  One file in your game install (ProjectZomboid64.json), restored when you quit. If the
  launcher is ever killed mid-session, the next start restores it before doing anything else.

This is a test build. It carries its own copy of the patch rather than fetching updates, so a
newer one has to be sent to you by hand.
"@
    Write-Utf8NoBom (Join-Path $portableDir "README.txt") ($portableReadme -replace "`r`n", "`n")

    $zip = Join-Path $distDir "$portableName.zip"
    Compress-Archive -Path $portableDir -DestinationPath $zip -CompressionLevel Optimal
    $zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Utf8NoBom (Join-Path $distDir "SHA256SUMS.txt") "$zipHash  $portableName.zip`n"

    ""
    Line "portable zip" $zip
    Line "zip size" ("{0:N1} MB" -f ((Get-Item $zip).Length / 1MB))
    Line "sha256" $zipHash
    ""
    "Send that zip. They extract it anywhere and run $($conf.mainBinaryName).exe."
    exit 0
}

if ($LocalTest) {
    ""
    "No dist/ folder was made: a -LocalTest build cannot be sent to anyone."
    exit 0
}

$distDir = Join-Path $repo "dist"
if (Test-Path $distDir) { Remove-Item -Recurse -Force $distDir }
New-Item -ItemType Directory -Force $distDir | Out-Null

$distName = "ProjectLifeZoid-Launcher-$($conf.version)-setup.exe"
Copy-Item -LiteralPath $installer.FullName -Destination (Join-Path $distDir $distName)

Write-Utf8NoBom (Join-Path $distDir "SHA256SUMS.txt") "$hash  $distName`n"

$readme = @"
ProjectLifeZoid Launcher $($conf.version)

WHAT IT DOES
  Installs the Project Life Zoid game patch, launches Project Zomboid through Steam, and puts
  your game install back exactly as it was when you quit.

BEFORE YOU RUN IT
  - Project Zomboid must be installed through Steam, and Steam must be signed in.
  - Clear your Steam launch options for Project Zomboid. '-debug', '-imgui' and '-nosteam' all
    stop you connecting. (Steam > Project Zomboid > Properties > Launch Options.)

FIRST RUN
  1. Open the launcher and press "Copy my Steam ID" under Details.
  2. Send that number to an admin. They approve it once.
  3. Choose the character name other players will see. There is no password anywhere: the
     server recognises your Steam account. The name is locked in the first time you join.
  4. Press Play.

WINDOWS WILL WARN YOU
  The installer is not code-signed, so SmartScreen shows "Windows protected your PC". Choose
  "More info" then "Run anyway". You can check you got the real file first: compare its SHA-256
  against SHA256SUMS.txt with

      certutil -hashfile "$distName" SHA256

WHAT IT TOUCHES
  One file in your game install (ProjectZomboid64.json), restored when you quit. If the
  launcher is ever killed mid-session, the next start restores it before doing anything else.
"@
Write-Utf8NoBom (Join-Path $distDir "README.txt") ($readme -replace "`r`n", "`n")

$sig = Get-ChildItem $nsisDir -Filter "*-setup.exe.sig" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $sig) {
    throw "no .sig beside the installer -- createUpdaterArtifacts or the signing key did not take effect"
}

$siteDir = Join-Path $repo "site"
if (Test-Path $siteDir) { Remove-Item -Recurse -Force $siteDir }
$downloadDir = Join-Path $siteDir "download"
New-Item -ItemType Directory -Force $downloadDir | Out-Null

Copy-Item -LiteralPath $installer.FullName -Destination (Join-Path $downloadDir $distName)
$sigDecoded = [Text.Encoding]::UTF8.GetString(
    [Convert]::FromBase64String((Get-Content -LiteralPath $sig.FullName -Raw).Trim()))
Write-Utf8NoBom (Join-Path $downloadDir "$distName.minisig") ($sigDecoded.TrimEnd() + "`n")
$platformDir = Join-Path $repo "platforms"
$platformFiles = @()
if (Test-Path $platformDir) {
    foreach ($f in (Get-ChildItem -Path $platformDir -File |
            Where-Object { $n = $_.Name; @(".deb", ".AppImage", ".dmg", ".app.tar.gz", ".sig") | Where-Object { $n.EndsWith($_) } })) {
        $clean = $f.Name -replace " ", "-"
        if ($clean.EndsWith(".app.tar.gz") -or $clean.EndsWith(".app.tar.gz.sig")) {
            $suffix = if ($clean.EndsWith(".sig")) { ".app.tar.gz.sig" } else { ".app.tar.gz" }
            $stem = $clean.Substring(0, $clean.Length - $suffix.Length)
            $clean = "$stem-$($conf.version)-universal$suffix"
        }
        Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $downloadDir $clean)
        $platformFiles += [pscustomobject]@{
            Name      = $clean
            Length    = $f.Length
            Extension = $f.Extension
            Source    = $f.FullName
        }
    }
}
$mismatched = @($platformFiles | Where-Object {
    -not $_.Name.EndsWith(".sig") -and
    $_.Name -match "[_-](\d+\.\d+\.\d+)[_-]" -and
    $Matches[1] -ne $conf.version
})
if ($mismatched.Count -gt 0) {
    throw ("platforms/ holds builds that are not " + $conf.version + ": " +
        (($mismatched | ForEach-Object { $_.Name }) -join ", ") +
        ". Re-run the workflow for this version, or empty platforms/ to publish Windows alone.")
}

$sums = "$hash  $distName`n"
foreach ($f in ($platformFiles | Where-Object { -not $_.Name.EndsWith('.sig') } | Sort-Object Name)) {
    $h = (Get-FileHash -LiteralPath $f.Source -Algorithm SHA256).Hash.ToLowerInvariant()
    $sums += "$h  $($f.Name)`n"
}
Write-Utf8NoBom (Join-Path $downloadDir "SHA256SUMS.txt") $sums

$pubWrapped = (Get-Content -LiteralPath (Join-Path $repo "tools\keys\updater-private.key.pub") -Raw).Trim()
$pubDecoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($pubWrapped))
$pubKeyLine = ($pubDecoded -split "`n" | Where-Object { $_ -and $_ -notmatch '^untrusted comment:' } |
    Select-Object -First 1).Trim()
if (-not $pubKeyLine) { throw "could not extract the minisign key line from updater-private.key.pub" }
Write-Utf8NoBom (Join-Path $downloadDir "plz-launcher-updater.pub") ($pubDecoded.TrimEnd() + "`n")

$releasePub = (Get-Content -LiteralPath (Join-Path $repo "tools\keys\release-public.hex") -Raw).Trim()
Write-Utf8NoBom (Join-Path $downloadDir "plz-release-pubkey.hex") "$releasePub`n"

$page = Get-Content -LiteralPath (Join-Path $repo "web\index.html") -Raw
$page = $page.Replace("{{VERSION}}",           $conf.version)
$page = $page.Replace("{{INSTALLER}}",         $distName)
$page = $page.Replace("{{SHA256}}",            $hash)
$page = $page.Replace("{{SIZE_MB}}",           ("{0:N1}" -f ($installer.Length / 1MB)))
$page = $page.Replace("{{BUILT}}",             (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd"))
$page = $page.Replace("{{PAYLOAD_BUILD}}",     [string]$manifest.build)
$page = $page.Replace("{{PAYLOAD_FILES}}",     [string]@($manifest.files).Count)
$page = $page.Replace("{{UPDATER_PUBKEY_RAW}}", $pubKeyLine)
$page = $page.Replace("{{RELEASE_PUBKEY}}",    $releasePub)
$byExt = @{}
foreach ($f in ($platformFiles | Where-Object { -not $_.Name.EndsWith(".sig") })) {
    $byExt[$f.Extension.ToLowerInvariant()] = $f
}
$haveAll = $byExt.ContainsKey(".dmg") -and $byExt.ContainsKey(".deb") -and $byExt.ContainsKey(".appimage")
if ($haveAll) {
    foreach ($pair in @(@(".dmg", "MAC"), @(".deb", "DEB"), @(".appimage", "APPIMAGE"))) {
        $file = $byExt[$pair[0]]
        $page = $page.Replace("{{$($pair[1])_FILE}}", $file.Name)
        $page = $page.Replace("{{$($pair[1])_SIZE_MB}}", ("{0:N1}" -f ($file.Length / 1MB)))
    }
} else {
    $start = $page.IndexOf('<section id="other">')
    if ($start -ge 0) {
        $end = $page.IndexOf('<section>', $start)
        if ($end -gt $start) { $page = $page.Remove($start, $end - $start) }
    }
    $page = $page.Replace('On a Mac or Linux? <a href="#other">Get those builds below.</a>',
        'Mac and Linux builds are not published for this version.')
}

$leftover = [regex]::Matches($page, '\{\{[A-Z0-9_]+\}\}') | ForEach-Object { $_.Value } | Select-Object -Unique
if ($leftover) { throw "web/index.html has unsubstituted placeholders: $($leftover -join ', ')" }
Write-Utf8NoBom (Join-Path $siteDir "index.html") $page

$origin = ([uri]$bakedUrl).GetLeftPart([System.UriPartial]::Authority)
$updateDir = Join-Path $repo "update"
if (Test-Path $updateDir) { Remove-Item -Recurse -Force $updateDir }
New-Item -ItemType Directory -Force $updateDir | Out-Null

$platformMap = @(
    @{ Key = "windows-x86_64"; File = $distName },
    @{ Key = "darwin-aarch64"; Match = "*.app.tar.gz" },
    @{ Key = "darwin-x86_64";  Match = "*.app.tar.gz" },
    @{ Key = "linux-x86_64";   Match = "*.AppImage" }
)

$platforms = [ordered]@{}
foreach ($entry in $platformMap) {
    if ($entry.File) {
        $name = $entry.File
        $sigText = (Get-Content -LiteralPath $sig.FullName -Raw).Trim()
    } else {
        $artifact = $platformFiles |
            Where-Object { -not $_.Name.EndsWith(".sig") -and $_.Name -like $entry.Match } |
            Select-Object -First 1
        if (-not $artifact) { continue }
        $name = $artifact.Name
        $sigFile = $platformFiles | Where-Object { $_.Name -eq ($artifact.Name + ".sig") } | Select-Object -First 1
        if (-not $sigFile) {
            Write-Output ("  no signature for " + $artifact.Name + "; skipping " + $entry.Key)
            continue
        }
        $sigText = (Get-Content -LiteralPath $sigFile.Source -Raw).Trim()
    }
    $platforms[$entry.Key] = [ordered]@{
        signature = $sigText
        url       = "$origin/download/$name"
    }
}

$latest = [ordered]@{
    version   = $conf.version
    notes     = "See the launcher's news panel."
    pub_date  = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    platforms = $platforms
}
Write-Utf8NoBom (Join-Path $updateDir "latest.json") (($latest | ConvertTo-Json -Depth 6) + "`n")

""
Line "download page" "$origin/  ->  /download/$distName"
Line "update endpoint" "/update/latest.json -> /download/$distName"
Line "updater signature" "$($sig.Name) ($((Get-Item $sig.FullName).Length) bytes)"

""

Line "dist folder" $distDir
Get-ChildItem $distDir | ForEach-Object { "          $($_.Name)" }
""
"Before anyone can use it, $bakedUrl must serve:"
"    manifest.json        the signed manifest"
"    manifest.json.sig    its detached signature"
"    files/**             all $(@($manifest.files).Count) payload class files"
"Upload the whole release/ folder to that path. Re-upload it on every build."
""
"Then publish update/ so installed launchers can find this build:"
"    tools\publish-origin.ps1"
"An installed launcher only takes a HIGHER version, so re-publishing an older one is inert"
"rather than dangerous -- but it does leave everyone stranded on whatever they already have."
exit 0
