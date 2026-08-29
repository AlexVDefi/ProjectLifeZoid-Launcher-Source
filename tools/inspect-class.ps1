param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Class,
    [string]$PzInstallDir = $env:PZ_INSTALL_DIR,
    [string]$JavapPath = $env:JAVAP_PATH,
    [string]$FilesDir,
    [switch]$Full
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

$rel = ($Class -replace '\.', '/') -replace '\.class$', ''
$relPath = ($rel -replace '/', '\') + ".class"

if (-not $FilesDir) { $FilesDir = Join-Path $repo "release\files" }
$shipped = Join-Path $FilesDir $relPath
if (-not (Test-Path -LiteralPath $shipped)) {
    $dist = Join-Path $repo "java\dist\$relPath"
    if (Test-Path -LiteralPath $dist) {
        $shipped = $dist
    } else {
        throw "no shipped class for '$Class'. Looked in $FilesDir and java\dist."
    }
}

function Resolve-Tool([string]$exe, [string]$explicit) {
    if ($explicit -and (Test-Path $explicit)) { return $explicit }
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\$exe"
        if (Test-Path $p) { return $p }
    }
    foreach ($base in @("C:\Program Files\Java\jdk-25", "C:\Program Files\Java\jdk-24",
            "C:\Program Files\Java\jdk-21")) {
        $p = Join-Path $base "bin\$exe"
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command $exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$javap = Resolve-Tool "javap.exe" $JavapPath
if (-not $javap) { throw "javap not found. Install a JDK and set JAVA_HOME, or pass -JavapPath." }

$args = @("-p", "-c", "-constants")
if ($Full) { $args += "-v" }

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("plz-inspect-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $tmp | Out-Null

try {
    $shippedTxt = Join-Path $tmp "shipped.txt"
    & $javap @args -cp $FilesDir $rel.Replace('/', '.') 2>&1 | Set-Content -LiteralPath $shippedTxt -Encoding utf8

    $install = $PzInstallDir
    if (-not $install) {
        foreach ($d in @("D:\Games\Steam\steamapps\common\ProjectZomboid",
                "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")) {
            if (Test-Path (Join-Path $d "projectzomboid.jar")) { $install = $d; break }
        }
    }

    $jar = if ($install) { Join-Path $install "projectzomboid.jar" } else { $null }
    $vanillaTxt = $null
    if ($jar -and (Test-Path $jar)) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
        try {
            $entry = $zip.GetEntry("$rel.class")
            if ($entry) {
                $vanillaDir = Join-Path $tmp "vanilla"
                $dest = Join-Path $vanillaDir $relPath
                New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
                $vanillaTxt = Join-Path $tmp "vanilla.txt"
                & $javap @args -cp $vanillaDir $rel.Replace('/', '.') 2>&1 |
                    Set-Content -LiteralPath $vanillaTxt -Encoding utf8
            }
        } finally { $zip.Dispose() }
    }

    $shippedHash = (Get-FileHash -LiteralPath $shipped -Algorithm SHA256).Hash.ToLowerInvariant()
    "Class   : $rel"
    "Shipped : $shipped"
    "sha256  : $shippedHash"

    if (-not $vanillaTxt) {
        if (-not $jar) {
            "Vanilla : no Project Zomboid install found, showing the shipped class only"
        } else {
            "Vanilla : not in projectzomboid.jar - this class is ADDED by the patch, not a replacement"
        }
        ""
        Get-Content -LiteralPath $shippedTxt
        exit 0
    }

    "Vanilla : $jar"
    ""

    $diff = Compare-Object -ReferenceObject (Get-Content -LiteralPath $vanillaTxt) `
        -DifferenceObject (Get-Content -LiteralPath $shippedTxt)

    if (-not $diff) {
        "IDENTICAL to the vanilla class at bytecode level."
        exit 0
    }

    "Bytecode differences (< vanilla, > shipped):"
    ""
    foreach ($d in $diff) {
        $mark = if ($d.SideIndicator -eq "<=") { "<" } else { ">" }
        "$mark $($d.InputObject)"
    }
    ""
    "$($diff.Count) differing line(s). Run with -Full for the complete constant pool."
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
