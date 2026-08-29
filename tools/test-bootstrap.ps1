param(
    [string]$PzJar,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$testDir = Join-Path $PSScriptRoot "bootstrap-test"
$outDir = Join-Path $testDir "out"

if (-not $PzJar) {
    foreach ($d in @($env:PZ_INSTALL_DIR,
            "D:\Games\Steam\steamapps\common\ProjectZomboid",
            "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid")) {
        if ($d -and (Test-Path (Join-Path $d "projectzomboid.jar"))) {
            $PzJar = Join-Path $d "projectzomboid.jar"
            break
        }
    }
}
if (-not $PzJar -or -not (Test-Path $PzJar)) {
    throw "projectzomboid.jar not found. Pass -PzJar or set PZ_INSTALL_DIR."
}

function Resolve-Tool([string]$exe) {
    if ($JavaHome) {
        $p = Join-Path $JavaHome "bin\$exe"
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

$javac = Resolve-Tool "javac.exe"
$java = Resolve-Tool "java.exe"
if (-not $javac -or -not $java) {
    throw "a JDK is required (the jar is Java 25). Set JAVA_HOME or install one."
}

$python = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $python) { throw "python is required to build the harness" }

"jar     : $PzJar"
"javac   : $javac"

New-Item -ItemType Directory -Force $outDir | Out-Null

$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$compileOut = & $javac -nowarn -cp $PzJar -d $outDir (Join-Path $testDir "KahluaRun.java") 2>&1
$compileExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($compileExit -ne 0) {
    $compileOut | ForEach-Object { Write-Output $_.ToString() }
    throw "javac failed with exit code $compileExit"
}

& $python (Join-Path $testDir "build-harness.py")
if ($LASTEXITCODE -ne 0) { throw "building the harness failed" }

$ErrorActionPreference = "Continue"
& $java -cp "$outDir;$PzJar" KahluaRun (Join-Path $testDir "bootstrap_test.lua")
$runExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($runExit -ne 0) { throw "bootstrap assertions failed" }
exit 0
