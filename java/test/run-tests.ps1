param(
    [string]$PzInstallDir = $env:PZ_INSTALL_DIR,
    [string]$JavacPath = $env:JAVAC_PATH
)

$ErrorActionPreference = "Stop"

$candidates = @()
if ($PzInstallDir) { $candidates += $PzInstallDir }
$candidates += @(
    "D:\Games\Steam\steamapps\common\ProjectZomboid",
    "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid"
)
$install = $null
foreach ($c in $candidates) {
    if ($c -and (Test-Path (Join-Path $c "projectzomboid.jar"))) { $install = $c; break }
}
if (-not $install) { throw "Project Zomboid install not found. Pass -PzInstallDir or set PZ_INSTALL_DIR." }

$javac = $JavacPath
if (-not ($javac -and (Test-Path $javac))) {
    foreach ($base in @("C:\Program Files\Java\jdk-25", "C:\Program Files\Java\jdk-24", "C:\Program Files\Java\jdk-21")) {
        $j = Join-Path $base "bin\javac.exe"
        if (Test-Path $j) { $javac = $j; break }
    }
}
if (-not ($javac -and (Test-Path $javac))) { throw "javac not found. Set JAVA_HOME or pass -JavacPath." }
$javaExe = Join-Path (Split-Path (Split-Path $javac)) "bin\java.exe"

$jar = Join-Path $install "projectzomboid.jar"
$src = Join-Path (Split-Path $PSScriptRoot) "src"
$out = Join-Path $PSScriptRoot "out"

if (Test-Path $out) { Remove-Item -Recurse -Force -LiteralPath $out }
New-Item -ItemType Directory -Force $out | Out-Null

"Install : $install"
"javac   : $javac"
""

$sources = @(
    (Join-Path $src "zombie\network\PLZQueue.java"),
    (Join-Path $src "zombie\network\PLZSlots.java"),
    (Join-Path $src "zombie\network\PLZAccounts.java"),
    (Join-Path $src "zombie\network\LoginQueue.java")
) + @(Get-ChildItem -Recurse -Path $PSScriptRoot -Filter "*.java" | ForEach-Object { $_.FullName })

$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$o = & $javac --release 25 -nowarn -cp $jar -d $out @sources 2>&1
$code = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($code -ne 0) {
    $o | ForEach-Object { Write-Output $_.ToString() }
    throw "javac failed with exit code $code"
}

$failed = 0
foreach ($test in @("zombie.network.PLZQueueConfigTest", "zombie.network.PLZSlotsTest", "zombie.network.LoginQueueReleaseTest")) {
    "=== $test ==="
    $ErrorActionPreference = "Continue"
    # -Xmx256m/SerialGC: these tests need almost no heap, and the default G1 reservation fails
    # outright on a machine with a small paging file ("Native memory allocation (mmap) failed").
    # That looked like a test failure and is not one.
    & $javaExe -Xmx256m -XX:+UseSerialGC -cp "$out;$jar" $test
    if ($LASTEXITCODE -ne 0) { $failed++ }
    $ErrorActionPreference = $prevEap
    ""
}

if ($failed -gt 0) { throw "$failed test class(es) failed" }
"OK"
