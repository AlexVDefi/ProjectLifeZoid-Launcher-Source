param(
    [string]$Manifest,
    [string]$PzInstallDir = $env:PZ_INSTALL_DIR,
    [string]$JavacPath = $env:JAVAC_PATH,
    [switch]$KeepOutput
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Resolve-PzInstall([string]$explicit) {
    $candidates = @()
    if ($explicit) { $candidates += $explicit }
    if ($env:PZ_INSTALL_DIR) { $candidates += $env:PZ_INSTALL_DIR }
    $candidates += @(
        "D:\Games\Steam\steamapps\common\ProjectZomboid",
        "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
    )
    $steamRoot = $null
    foreach ($k in @("HKCU:\Software\Valve\Steam", "HKLM:\Software\WOW6432Node\Valve\Steam")) {
        try {
            $p = Get-ItemProperty $k -ErrorAction Stop
            if ($p.SteamPath) { $steamRoot = $p.SteamPath }
            elseif ($p.InstallPath) { $steamRoot = $p.InstallPath }
            if ($steamRoot) { break }
        } catch { }
    }
    if ($steamRoot) {
        $vdf = Join-Path ($steamRoot -replace '/', '\') "steamapps\libraryfolders.vdf"
        if (Test-Path $vdf) {
            foreach ($m in [regex]::Matches((Get-Content $vdf -Raw), '"path"\s+"([^"]+)"')) {
                $lib = $m.Groups[1].Value -replace '\\\\', '\'
                $candidates += ($lib.TrimEnd('\') + "\steamapps\common\ProjectZomboid")
            }
        }
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c "projectzomboid.jar"))) { return (Resolve-Path $c).Path }
    }
    return $null
}

function Resolve-Javac([string]$explicit) {
    if ($explicit -and (Test-Path $explicit)) { return $explicit }
    if ($env:JAVA_HOME) {
        $j = Join-Path $env:JAVA_HOME "bin\javac.exe"
        if (Test-Path $j) { return $j }
    }
    foreach ($base in @(
            "C:\Program Files\Java\jdk-25", "C:\Program Files\Java\jdk-24",
            "C:\Program Files\Java\jdk-21", "C:\Program Files\Microsoft\jdk-21",
            "C:\Program Files\Eclipse Adoptium\jdk-21", "C:\Program Files\Zulu\zulu-21")) {
        $j = Join-Path $base "bin\javac.exe"
        if (Test-Path $j) { return $j }
    }
    $cmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

if (-not $Manifest) {
    $local = Join-Path $repo "release\manifest.json"
    if (Test-Path $local) {
        $Manifest = $local
    } else {
        $cfg = Get-Content (Join-Path $repo "src-tauri\src\config.rs") -Raw
        $m = [regex]::Match($cfg, 'None => "([^"]+manifest\.json)"')
        if (-not $m.Success) { throw "no manifest given and none found locally" }
        $Manifest = $m.Groups[1].Value
    }
}

if ($Manifest -match '^https?://') {
    "Manifest: $Manifest"
    $manifestText = (Invoke-WebRequest -Uri $Manifest -UseBasicParsing).Content
} else {
    "Manifest: $Manifest"
    $manifestText = Get-Content -LiteralPath $Manifest -Raw
}
$man = $manifestText -replace '^\xEF\xBB\xBF', '' | ConvertFrom-Json

$PzInstallDir = Resolve-PzInstall $PzInstallDir
if (-not $PzInstallDir) { throw "Project Zomboid install not found. Pass -PzInstallDir or set PZ_INSTALL_DIR." }
$javac = Resolve-Javac $JavacPath
if (-not $javac) { throw "javac not found. Install a JDK and set JAVA_HOME, or pass -JavacPath." }

$pzJar = Join-Path $PzInstallDir "projectzomboid.jar"
$jarSha = (Get-FileHash -LiteralPath $pzJar -Algorithm SHA256).Hash.ToLowerInvariant()

$javacVersion = (& $javac -version 2>&1 | Out-String).Trim()

""
"Install : $PzInstallDir"
"javac   : $javac  ($javacVersion)"
"Build   : $($man.build)"
if ($man.sourceCommit) {
    "Source  : $($man.sourceCommit)"
    if ($man.sourceRepo) { "          $($man.sourceRepo)" }
    if ($man.sourceDirty -gt 0) {
        "          WARNING: signed from a tree with $($man.sourceDirty) uncommitted change(s);"
        "          that commit does NOT fully describe what shipped."
    }
    $localHead = ""
    try {
        Push-Location $repo
        $localHead = (& git rev-parse HEAD 2>$null | Out-String).Trim()
    } catch { } finally { Pop-Location }
    if ($localHead -and $localHead -ne $man.sourceCommit) {
        "          You are on $($localHead.Substring(0, 12)). To match this release exactly:"
        "            git checkout $($man.sourceCommit)"
    }
}
""

if ($jarSha -ne $man.builtAgainstJarSha256) {
    "Your projectzomboid.jar does NOT match the one this payload was built against."
    "  yours : $jarSha"
    "  built : $($man.builtAgainstJarSha256)"
    ""
    "The game has updated since this release. Class files cannot match; this is expected."
    exit 2
}
"Jar     : matches builtAgainstJarSha256"

$localJdk = ""
$javaExe = Join-Path (Split-Path $javac) $(if ($env:OS -eq 'Windows_NT') { "java.exe" } else { "java" })
if (Test-Path -LiteralPath $javaExe) {
    $props = (& $javaExe -XshowSettings:properties -version 2>&1 | Out-String)
    $localJdk = ("{0} {1}" -f ([regex]::Match($props, '(?m)^\s*java\.vendor = (.+)$')).Groups[1].Value.Trim(),
        ([regex]::Match($props, '(?m)^\s*java\.runtime\.version = (.+)$')).Groups[1].Value.Trim()).Trim()
}
if ($man.jdk -and $man.jdk -ne $localJdk) {
    ""
    "NOTE: this release was built with $($man.jdk), you have $localJdk."
    "      JDK 25 builds have produced identical classes so far, but only the same one is guaranteed to."
} elseif (-not $man.jdk -and $man.javacVersion -and $man.javacVersion -ne $javacVersion) {
    ""
    "NOTE: this release was built with '$($man.javacVersion)', you have '$javacVersion'."
    "      A different compiler build can produce different bytecode from identical source."
}
if ($man.provenance) {
    ""
    "GitHub Actions built this release and attested it. node tools/verify-provenance.mjs checks that"
    "without a JDK or a game install."
}

$outDir = Join-Path ([System.IO.Path]::GetTempPath()) ("plz-reproduce-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $outDir | Out-Null

try {
    $roots = @((Join-Path $repo "java\src"), (Join-Path $repo "java\plz-src"))
    foreach ($r in $roots) {
        if (-not (Test-Path $r)) { throw "source root missing: $r" }
    }
    $sources = (Get-ChildItem -Recurse -Path $roots -Filter *.java).FullName
    "Sources : $($sources.Count) .java file(s)"
    ""

    $release = $man.classReleaseTarget
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = & $javac --release $release -nowarn -cp $pzJar -d $outDir @sources 2>&1
    $exit = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($exit -ne 0) {
        $out | ForEach-Object { Write-Output $_.ToString() }
        throw "javac failed with exit code $exit"
    }

    $entries = @()
    $seen = @{}
    foreach ($e in @($man.files) + @($man.serverFiles)) {
        if (-not $seen.ContainsKey($e.path)) {
            $seen[$e.path] = $true
            $entries += $e
        }
    }

    $matched = 0
    $mismatched = @()
    $absent = @()
    foreach ($e in $entries) {
        $local = Join-Path $outDir ($e.path -replace '/', '\')
        if (-not (Test-Path -LiteralPath $local)) {
            $absent += $e.path
            continue
        }
        $h = (Get-FileHash -LiteralPath $local -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($h -eq $e.sha256) { $matched++ } else { $mismatched += $e.path }
    }

    "$matched/$($entries.Count) class files rebuilt byte-for-byte identical to the signed manifest"
    ""
    foreach ($p in $mismatched) { "  DIFFERS  $p" }
    foreach ($p in $absent) { "  NOT BUILT $p" }

    if ($mismatched.Count -eq 0 -and $absent.Count -eq 0) {
        ""
        "Every class the launcher installs was compiled from the source in this repository."
        exit 0
    }

    ""
    "Some classes did not reproduce. Most likely causes, in order:"
    "  1. a different JDK build than '$($man.javacVersion)'"
    "  2. java/plz-src is out of date against the mod repository it mirrors"
    "  3. the source here is not what produced this release"
    exit 1
} finally {
    if ($KeepOutput) {
        "Output kept at $outDir"
    } else {
        Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue
    }
}
