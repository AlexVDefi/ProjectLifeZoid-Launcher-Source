param(
    [string]$PzInstallDir = $env:PZ_INSTALL_DIR,
    [string]$JavacPath = $env:JAVAC_PATH,
    [int]$Build = 1
)

$ErrorActionPreference = "Stop"

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

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

function Get-JarClassMajor([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry = $zip.Entries | Where-Object { $_.FullName -like "zombie/*.class" } | Select-Object -First 1
        if (-not $entry) { throw "no zombie/*.class entry found in $jar" }
        $s = $entry.Open()
        try {
            $buf = New-Object byte[] 8
            $read = 0
            while ($read -lt 8) {
                $n = $s.Read($buf, $read, 8 - $read)
                if ($n -le 0) { break }
                $read += $n
            }
            if ($read -lt 8) { throw "short read on $($entry.FullName)" }
            return [int]$buf[6] * 256 + [int]$buf[7]
        } finally { $s.Dispose() }
    } finally { $zip.Dispose() }
}

$PzInstallDir = Resolve-PzInstall $PzInstallDir
if (-not $PzInstallDir) { throw "Project Zomboid install not found. Pass -PzInstallDir or set PZ_INSTALL_DIR." }

$javac = Resolve-Javac $JavacPath
if (-not $javac) { throw "javac not found. Install a JDK and set JAVA_HOME, or pass -JavacPath." }

$pzJar = Join-Path $PzInstallDir "projectzomboid.jar"
$srcDir = Join-Path $PSScriptRoot "src"
$outDir = Join-Path $PSScriptRoot "dist"

$major = Get-JarClassMajor $pzJar
$release = $major - 44
if ($release -lt 8 -or $release -gt 99) { throw "implausible class file major $major in $pzJar" }

"Install : $PzInstallDir"
"javac   : $javac"
"Jar     : class file major $major -> --release $release"

if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }
New-Item -ItemType Directory -Force $outDir | Out-Null

$payloadPath = Join-Path $PSScriptRoot "payload.json"
if (-not (Test-Path $payloadPath)) { throw "missing $payloadPath" }
$payload = Get-Content -LiteralPath $payloadPath -Raw | ConvertFrom-Json

$sources = @()
foreach ($entry in $payload.sources) {
    $root = $entry.root
    $override = $null
    if ($entry.env) {
        $override = [Environment]::GetEnvironmentVariable($entry.env)
        if ($override) { $root = $override }
    }
    if (-not [System.IO.Path]::IsPathRooted($root)) {
        $root = Join-Path $PSScriptRoot $root
    }
    if ($entry.mirrorFrom -and -not $override) {
        $from = Join-Path $PSScriptRoot $entry.mirrorFrom
        if (Test-Path $from -PathType Container) {
            $from = (Resolve-Path $from).Path
            if (-not (Test-Path $root -PathType Container)) {
                New-Item -ItemType Directory -Force $root | Out-Null
            }
            $mirrorRoot = (Resolve-Path $root).Path

            $wanted = @{}
            foreach ($cls in @($payload.client) + @($payload.server)) {
                $outer = ($cls -replace '\.class$', '') -split '\$' | Select-Object -First 1
                $rel = ($outer -replace '/', [System.IO.Path]::DirectorySeparatorChar) + ".java"
                if (Test-Path -LiteralPath (Join-Path $from $rel)) { $wanted[$rel] = $true }
            }

            $declaredRel = @{}
            foreach ($cls in @($payload.client) + @($payload.server)) {
                $outer = ($cls -replace '\.class$', '') -split '\$' | Select-Object -First 1
                $declaredRel[(($outer -replace '/', [System.IO.Path]::DirectorySeparatorChar) + ".java")] = $true
            }
            $excused = @{}
            foreach ($rel in @($payload.undeclared)) {
                if ($rel) {
                    $excused[($rel -replace '/', [System.IO.Path]::DirectorySeparatorChar)] = $true
                }
            }
            $drifted = @()
            foreach ($file in Get-ChildItem -Recurse -Path $from -Filter "*.java") {
                $rel = $file.FullName.Substring($from.Length + 1)
                if (-not $declaredRel.ContainsKey($rel) -and -not $excused.ContainsKey($rel)) {
                    $drifted += $rel
                }
            }
            if ($drifted.Count -gt 0) {
                $list = ($drifted | ForEach-Object { "    $_" }) -join "`n"
                throw @"
$($drifted.Count) source file(s) in $from are declared by neither the client nor the server
list in payload.json, so they would be silently left out of the payload:

$list

Fix it one of two ways, both deliberate:
  - add the class to "client" and/or "server" in java/payload.json, so it ships; or
  - add the path to "undeclared" in java/payload.json, so the build records that it is
    intentionally not shipped.
"@
            }

            $changed = @()
            foreach ($rel in $wanted.Keys) {
                $src = Join-Path $from $rel
                $dest = Join-Path $mirrorRoot $rel
                $stale = -not (Test-Path -LiteralPath $dest) -or
                    (Get-FileHash -LiteralPath $src).Hash -ne (Get-FileHash -LiteralPath $dest).Hash
                if ($stale) {
                    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
                    Copy-Item -LiteralPath $src -Destination $dest -Force
                    $changed += $rel
                }
            }

            foreach ($file in Get-ChildItem -Recurse -Path $mirrorRoot -Filter "*.java") {
                $rel = $file.FullName.Substring($mirrorRoot.Length + 1)
                if (-not $wanted.ContainsKey($rel)) {
                    Remove-Item -LiteralPath $file.FullName -Force
                    $changed += "removed $rel"
                }
            }

            if ($changed.Count -gt 0) {
                "Mirror  : $($entry.label)  refreshed $($changed.Count) file(s) from $from"
                $changed | ForEach-Object { "          $_" }
                "          Commit these: the mirror is what a clone without ProjectLifeZoid builds."
            }
        }
    }
    if (-not (Test-Path $root -PathType Container)) {
        $hint = ""
        if ($entry.env) { $hint = "  Set $($entry.env) to point at it." }
        throw "source root '$($entry.label)' not found: $root$hint"
    }
    $root = (Resolve-Path $root).Path
    $found = @(Get-ChildItem -Recurse -Path $root -Filter "*.java" | ForEach-Object { $_.FullName })
    if ($found.Count -eq 0) { throw "source root '$($entry.label)' has no .java files: $root" }
    "Source  : $($entry.label)  $($found.Count) file(s)  $root"
    $sources += $found
}

$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javacVersion = (& $javac -version 2>&1 | Out-String).Trim()
$javacOutput = & $javac --release $release -nowarn -cp $pzJar -d $outDir @sources 2>&1
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($javacExit -ne 0) {
    $javacOutput | ForEach-Object { Write-Output $_.ToString() }
    throw "javac failed with exit code $javacExit"
}

$jarItem = Get-Item $pzJar
$jarSha = (Get-FileHash $pzJar -Algorithm SHA256).Hash.ToLowerInvariant()

$classes = Get-ChildItem -Recurse -Path $outDir -Filter "*.class" |
    ForEach-Object { $_.FullName.Substring($outDir.Length + 1) -replace '\\', '/' } | Sort-Object

$clientSet = @{}; foreach ($n in $payload.client) { $clientSet[$n] = $true }
$serverSet = @{}; foreach ($n in $payload.server) { $serverSet[$n] = $true }

$clientFiles = @(); $serverFiles = @(); $unclassified = @(); $produced = @{}
foreach ($rel in $classes) {
    $outer = ($rel -replace '\.class$', '') -replace '\$.*$', ''
    $produced[$outer] = $true
    $classified = $false
    if ($clientSet.ContainsKey($outer)) {
        $clientFiles += $rel
        $classified = $true
    }
    if ($serverSet.ContainsKey($outer)) {
        $serverFiles += $rel
        $classified = $true
    }
    if (-not $classified) { $unclassified += $outer }
}

$unclassified = @($unclassified | Sort-Object -Unique)
if ($unclassified.Count -gt 0) {
    throw ("payload.json classifies neither client nor server for: " + ($unclassified -join ", ") +
        "`nAdd each to the client or server list in java/payload.json.")
}

$missing = @(@($payload.client) + @($payload.server) | Where-Object { -not $produced.ContainsKey($_) })
if ($missing.Count -gt 0) {
    throw ("payload.json lists classes that produced no output: " + ($missing -join ", ") +
        "`nEither the source was removed or the name is misspelled.")
}
if ($clientFiles.Count -eq 0) { throw "no client classes produced; the launcher would ship an empty payload" }
if ($serverFiles.Count -eq 0) { throw "no server classes produced; the server patch would be empty" }

$sourceCommit = ""
$sourceDirty = 0
try {
    Push-Location $PSScriptRoot
    $sourceCommit = (& git rev-parse HEAD 2>$null | Out-String).Trim()
    $sourceDirty = @(& git status --porcelain -- "src" "plz-src" "payload.json").Count
} catch { } finally { Pop-Location }

$meta = [ordered]@{
    build              = $Build
    builtAtUtc         = (Get-Date).ToUniversalTime().ToString("o")
    classReleaseTarget = $release
    javacVersion       = $javacVersion
    javacFlags         = "--release $release -nowarn"
    sourceCommit       = $sourceCommit
    sourceDirty        = $sourceDirty
    builtAgainst       = [ordered]@{
        jarSha256 = $jarSha
        jarSize   = $jarItem.Length
        jarMtime  = $jarItem.LastWriteTimeUtc.ToString("o")
    }
    classes            = $classes
    client             = $clientFiles
    server             = $serverFiles
}
Write-Utf8NoBom (Join-Path $outDir "built-against.json") (($meta | ConvertTo-Json -Depth 6) + "`n")

"OK      : $($classes.Count) .class files -> $outDir"
"Client  : $($clientFiles.Count) file(s) from $(@($payload.client).Count) class(es)"
@($payload.client) | Sort-Object | ForEach-Object { "          $_" }
"Server  : $($serverFiles.Count) file(s) from $(@($payload.server).Count) class(es)"
@($payload.server) | Sort-Object | ForEach-Object { "          $_" }
"Jar sha : $jarSha"
