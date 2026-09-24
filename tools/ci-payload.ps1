param(
    [Parameter(Mandatory = $true)][int]$Build,
    [string]$Commit,
    [switch]$Dispatch,
    [string]$JarSha256,
    [string]$Repo,
    [int]$TimeoutMinutes = 60
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$cfg = Get-Content -LiteralPath (Join-Path $PSScriptRoot "public-repo.json") -Raw | ConvertFrom-Json
if (-not $Repo) { $Repo = $cfg.repo }
$workflowFile = "payload.yml"
$signer = "$Repo/.github/workflows/$workflowFile"
$tag = "payload-build-$Build"
$distDir = Join-Path $root "java\dist"

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}
function Invoke-Gh {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { $out = @(& gh @args 2>&1) } finally { $ErrorActionPreference = $prev }
    $script:ghExit = $LASTEXITCODE
    $script:ghErr = ($out | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" }) -join "`n"
    return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" }) -join "`n")
}
function Get-Sha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function To-Utc($value) {
    if ($value -is [datetime]) { return $value.ToUniversalTime() }
    return ([datetimeoffset]::Parse([string]$value)).UtcDateTime
}
function Get-DistHashes([string]$dir) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $dir)) { return $map }
    $full = (Resolve-Path $dir).Path
    foreach ($f in Get-ChildItem -Recurse -File -Path $full -Filter "*.class") {
        $map[($f.FullName.Substring($full.Length + 1) -replace '\\', '/')] = Get-Sha $f.FullName
    }
    return $map
}

if ($Commit -and $Commit -notmatch '^[0-9a-f]{40}$') { throw "-Commit must be a full 40-character sha" }

if ($Dispatch) {
    if (-not $Commit) { throw "-Dispatch needs -Commit, the public commit to build" }
    if (-not $JarSha256) {
        $local = Join-Path $distDir "built-against.json"
        if (-not (Test-Path -LiteralPath $local)) { throw "no -JarSha256 given and no local java/dist/built-against.json to read it from" }
        $JarSha256 = (Get-Content -LiteralPath $local -Raw | ConvertFrom-Json).builtAgainst.jarSha256
    }
    $since = [DateTime]::UtcNow.AddSeconds(-10)
    "Dispatch : $workflowFile on $Repo  build $Build  commit $Commit"
    $null = Invoke-Gh workflow run $workflowFile --repo $Repo --ref main -f "build=$Build" -f "commit=$Commit" -f "jar_sha256=$JarSha256"
    if ($ghExit -ne 0) { throw "gh workflow run failed: $ghErr" }

    $runId = $null
    for ($i = 0; $i -lt 30 -and -not $runId; $i++) {
        Start-Sleep -Seconds 4
        $listed = Invoke-Gh run list --repo $Repo --workflow $workflowFile --event workflow_dispatch --limit 10 --json "databaseId,headSha,createdAt"
        if ($ghExit -ne 0) { continue }
        $runs = $listed | ConvertFrom-Json
        $mine = @($runs | Where-Object { $_.headSha -eq $Commit -and (To-Utc $_.createdAt) -ge $since } |
            Sort-Object { To-Utc $_.createdAt } -Descending)
        if ($mine.Count -gt 0) { $runId = $mine[0].databaseId }
    }
    if (-not $runId) { throw "the dispatched run never appeared in gh run list" }
    $runUrl = "https://github.com/$Repo/actions/runs/$runId"
    "Run      : $runUrl"

    $deadline = [DateTime]::UtcNow.AddMinutes($TimeoutMinutes)
    $last = ""
    while ($true) {
        $viewed = Invoke-Gh run view $runId --repo $Repo --json "status,conclusion"
        if ($ghExit -ne 0) { Start-Sleep -Seconds 20; continue }
        $run = $viewed | ConvertFrom-Json
        $now = "$($run.status) $($run.conclusion)".Trim()
        if ($now -ne $last) { "           $(([DateTime]::UtcNow).ToString('HH:mm:ss'))  $now"; $last = $now }
        if ($run.status -eq "completed") { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw "run $runId still $($run.status) after $TimeoutMinutes minutes: $runUrl" }
        Start-Sleep -Seconds 20
    }
    if ($run.conclusion -ne "success") {
        throw "the payload workflow finished '$($run.conclusion)'. Read it with: gh run view $runId --repo $Repo --log-failed"
    }
}

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("plz-ci-" + $tag + "-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $stage | Out-Null
try {
    $null = Invoke-Gh release download $tag --repo $Repo --dir $stage
    if ($ghExit -ne 0) { throw "no $tag release on $Repo. Dispatch the payload workflow for build $Build first. ($ghErr)" }
    $sumsPath = Join-Path $stage "payload-sha256sums.txt"
    $zipPath = Join-Path $stage "$tag.zip"
    foreach ($p in @($sumsPath, $zipPath)) {
        if (-not (Test-Path -LiteralPath $p)) { throw "$tag is missing $(Split-Path -Leaf $p)" }
    }

    $attested = @{}
    foreach ($p in @($sumsPath, $zipPath)) {
        $verifyArgs = @("attestation", "verify", $p, "--repo", $Repo, "--signer-workflow", $signer,
            "--deny-self-hosted-runners", "--format", "json")
        if ($Commit) { $verifyArgs += @("--source-digest", $Commit) }
        $out = Invoke-Gh @verifyArgs
        if ($ghExit -ne 0) { throw "$(Split-Path -Leaf $p) has no valid attestation from $signer. $ghErr" }
        $cert = @($out | ConvertFrom-Json)[0].verificationResult.signature.certificate
        $attested[$p] = [pscustomobject]@{
            commit = [string]$cert.sourceRepositoryDigest
            run    = [string]$cert.runInvocationURI
        }
    }
    $attestedCommit = $attested[$sumsPath].commit
    $runUri = $attested[$sumsPath].run
    if ($attestedCommit -notmatch '^[0-9a-f]{40}$') { throw "the attestation names no source commit" }
    if ($attested[$zipPath].commit -ne $attestedCommit -or $attested[$zipPath].run -ne $runUri) {
        throw "the sums file and the zip were attested by different runs"
    }
    if ($Commit -and $attestedCommit -ne $Commit) { throw "$tag was built from $attestedCommit, not $Commit" }
    "Attested : $tag  $Repo@$attestedCommit"
    "           $runUri"

    $before = Get-DistHashes $distDir
    if (Test-Path -LiteralPath $distDir) { Remove-Item -Recurse -Force $distDir }
    New-Item -ItemType Directory -Force $distDir | Out-Null
    Expand-Archive -LiteralPath $zipPath -DestinationPath $distDir

    $sums = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($sumsPath)) {
        if (-not $line.Trim()) { continue }
        $m = [regex]::Match($line, '^([0-9a-f]{64}) [ *]?(.+)$')
        if (-not $m.Success) { throw "unreadable line in the sums file: $line" }
        $sums[$m.Groups[2].Value] = $m.Groups[1].Value
    }
    $after = Get-DistHashes $distDir
    $bad = @($sums.Keys | Where-Object { $after[$_] -ne $sums[$_] })
    $extra = @($after.Keys | Where-Object { -not $sums.ContainsKey($_) })
    if ($bad.Count -gt 0 -or $extra.Count -gt 0) {
        throw "the zip does not match its attested sums file ($($bad.Count) differ, $($extra.Count) unlisted)"
    }

    $meta = Get-Content -LiteralPath (Join-Path $distDir "built-against.json") -Raw | ConvertFrom-Json
    if ([int]$meta.build -ne $Build) { throw "the CI payload says build $($meta.build), not $Build" }
    if ($meta.sourceCommit -ne $attestedCommit) { throw "built-against.json names $($meta.sourceCommit), the attestation $attestedCommit" }
    if ([int]$meta.sourceDirty -ne 0) { throw "the CI checkout was dirty" }

    Copy-Item -LiteralPath $sumsPath -Destination (Join-Path $distDir "payload-sha256sums.txt")
    $provenance = [ordered]@{
        repo     = $Repo
        commit   = $attestedCommit
        workflow = ".github/workflows/$workflowFile"
        run      = $runUri
        release  = $tag
        sums     = Get-Sha $sumsPath
        zip      = Get-Sha $zipPath
        jdk      = [string]$meta.jdk
    }
    Write-Utf8NoBom (Join-Path $distDir "provenance.json") (($provenance | ConvertTo-Json) + "`n")

    "Payload  : $($after.Count) class file(s) from CI, jar $($meta.builtAgainst.jarSha256)"
    "JDK      : $($meta.jdk)"
    if ($before.Count -gt 0) {
        $differs = @($after.Keys | Where-Object { $before.ContainsKey($_) -and $before[$_] -ne $after[$_] })
        $onlyCi = @($after.Keys | Where-Object { -not $before.ContainsKey($_) })
        $onlyLocal = @($before.Keys | Where-Object { -not $after.ContainsKey($_) })
        if ($differs.Count + $onlyCi.Count + $onlyLocal.Count -eq 0) {
            "Local    : your local build was byte-identical"
        } else {
            "WARNING  : the local build differed from CI ($($differs.Count) differ, $($onlyCi.Count) only in CI, $($onlyLocal.Count) only local)."
            "           CI is what ships. A JDK other than $($meta.jdk), or sources edited after the commit, explain it."
            @($differs + $onlyCi + $onlyLocal) | Sort-Object | Select-Object -First 15 | ForEach-Object { "             $_" }
        }
    }
} finally {
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
}
