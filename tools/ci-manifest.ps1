param(
    [Parameter(Mandatory = $true)][int]$Build,
    [Parameter(Mandatory = $true)][long]$WorkshopUpdated,
    [Parameter(Mandatory = $true)][string]$Commit,
    [string]$Repo,
    [int]$TimeoutMinutes = 120
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $Repo) { $Repo = (Get-Content -LiteralPath (Join-Path $PSScriptRoot "public-repo.json") -Raw | ConvertFrom-Json).repo }
$workflowFile = "sign-manifest.yml"
$signer = "$Repo/.github/workflows/$workflowFile"
$tag = "payload-build-$Build"
$releaseDir = Join-Path $root "release"

function Invoke-Gh {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { $out = @(& gh @args 2>&1) } finally { $ErrorActionPreference = $prev }
    $script:ghExit = $LASTEXITCODE
    $script:ghErr = ($out | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" }) -join "`n"
    return (($out | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" }) -join "`n")
}
function To-Utc($value) {
    if ($value -is [datetime]) { return $value.ToUniversalTime() }
    return ([datetimeoffset]::Parse([string]$value)).UtcDateTime
}

if ($Commit -notmatch '^[0-9a-f]{40}$') { throw "-Commit must be a full 40-character sha" }
if ($WorkshopUpdated -le 0) { throw "-WorkshopUpdated must be epoch seconds" }

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("plz-sign-" + $tag + "-" + [guid]::NewGuid().ToString("N"))
$local = Join-Path $stage "local"
New-Item -ItemType Directory -Force $stage | Out-Null
try {
    $ErrorActionPreference = "Continue"
    try {
        $made = @(& node (Join-Path $PSScriptRoot "make-manifest.mjs") --build $Build --workshop-updated $WorkshopUpdated --unsigned --out $local 2>&1)
    } finally { $ErrorActionPreference = "Stop" }
    if ($LASTEXITCODE -ne 0) { $made | ForEach-Object { "$_" }; throw "could not build the manifest locally, so there is nothing to compare CI's against" }
    $made | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^\s+(client|server)\s' }

    $since = [DateTime]::UtcNow.AddSeconds(-10)
    ""
    "Dispatch : $workflowFile on $Repo  build $Build  workshop $WorkshopUpdated  commit $Commit"
    $null = Invoke-Gh workflow run $workflowFile --repo $Repo --ref main -f "build=$Build" -f "workshop_updated=$WorkshopUpdated" -f "commit=$Commit"
    if ($ghExit -ne 0) { throw "gh workflow run failed: $ghErr" }

    $runId = $null
    for ($i = 0; $i -lt 30 -and -not $runId; $i++) {
        Start-Sleep -Seconds 4
        $listed = Invoke-Gh run list --repo $Repo --workflow $workflowFile --event workflow_dispatch --limit 10 --json "databaseId,headSha,createdAt"
        if ($ghExit -ne 0) { continue }
        $mine = @($listed | ConvertFrom-Json | Where-Object { $_.headSha -eq $Commit -and (To-Utc $_.createdAt) -ge $since } |
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
        if ($ghExit -ne 0) { Start-Sleep -Seconds 15; continue }
        $run = $viewed | ConvertFrom-Json
        $now = "$($run.status) $($run.conclusion)".Trim()
        if ($now -ne $last) {
            "           $(([DateTime]::UtcNow).ToString('HH:mm:ss'))  $now"
            if ($run.status -eq "waiting") { "           APPROVE the release environment: $runUrl" }
            $last = $now
        }
        if ($run.status -eq "completed") { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw "run $runId still $($run.status) after $TimeoutMinutes minutes: $runUrl" }
        Start-Sleep -Seconds 15
    }
    if ($run.conclusion -ne "success") {
        throw "the signing workflow finished '$($run.conclusion)'. Read it with: gh run view $runId --repo $Repo --log-failed"
    }

    $signed = Join-Path $stage "ci"
    $null = Invoke-Gh release download $tag --repo $Repo --dir $signed --pattern "manifest.json" --pattern "manifest.json.sig"
    if ($ghExit -ne 0) { throw "could not download the signed manifest from ${tag}: $ghErr" }
    $ciManifest = Join-Path $signed "manifest.json"
    $ciSig = Join-Path $signed "manifest.json.sig"

    $out = Invoke-Gh attestation verify $ciManifest --repo $Repo --signer-workflow $signer --deny-self-hosted-runners --source-digest $Commit --format json
    if ($ghExit -ne 0) { throw "manifest.json on $tag has no valid attestation from $signer at $Commit. $ghErr" }
    $runs = @($out | ConvertFrom-Json | ForEach-Object { $_ } | ForEach-Object { [string]$_.verificationResult.signature.certificate.runInvocationURI })
    if (-not @($runs | Where-Object { $_ -match "/actions/runs/$runId/" })) {
        throw "manifest.json on $tag was not attested by run $runId; another signing run replaced it"
    }
    "Attested : manifest.json  $Repo@$Commit  run $runId"

    $mine = [System.IO.File]::ReadAllBytes((Join-Path $local "manifest.json"))
    $theirs = [System.IO.File]::ReadAllBytes($ciManifest)
    if ([Convert]::ToBase64String($mine) -ne [Convert]::ToBase64String($theirs)) {
        throw ("CI signed a manifest this machine does not reproduce. tools/content.json or tools/server.json here " +
            "differs from $Repo@$Commit, or java/dist is not the $tag payload. Nothing was changed in release/.")
    }
    Copy-Item -LiteralPath $ciSig -Destination (Join-Path $local "manifest.json.sig")

    $payloadRs = Get-Content -LiteralPath (Join-Path $root "src-tauri\src\payload.rs") -Raw
    $pinnedHex = ([regex]::Match($payloadRs, 'PUBLIC_KEY_HEX:\s*&str\s*=\s*"([0-9a-fA-F]+)"')).Groups[1].Value
    & node (Join-Path $PSScriptRoot "verify-manifest.mjs") (Join-Path $local "manifest.json") (Join-Path $local "manifest.json.sig") $pinnedHex
    if ($LASTEXITCODE -ne 0) { throw "CI's signature does not verify against the key payload.rs pins" }

    if (Test-Path -LiteralPath $releaseDir) { Remove-Item -Recurse -Force $releaseDir }
    Copy-Item -Recurse -LiteralPath $local -Destination $releaseDir
    "Signed   : release/ holds build $Build, byte-identical to what CI signed, signature verified"
} finally {
    Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
}
