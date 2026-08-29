param(
    [string]$ReleaseDir = (Join-Path $PSScriptRoot "..\release")
)

$ErrorActionPreference = "Stop"

$manifestPath = Join-Path $ReleaseDir "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Missing $manifestPath. Run java/build.ps1 and tools/make-manifest.mjs first."
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json

$serverClasses = @($manifest.serverFiles | ForEach-Object { $_.path })
if ($serverClasses.Count -eq 0) {
    throw "manifest.json has no serverFiles. Rebuild with java\build.ps1 then tools\make-manifest.mjs."
}

$resolved = @()
foreach ($rel in $serverClasses) {
    $path = Join-Path (Join-Path $ReleaseDir "files") ($rel -replace '/', '\')
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing $path. Rebuild the payload: java\build.ps1 then tools\make-manifest.mjs."
    }
    $resolved += [pscustomobject]@{ Relative = $rel; Path = $path }
}

$fileList = ($serverClasses | ForEach-Object { "java/$_" }) -join "`n"
$verifyList = ($serverClasses | ForEach-Object { "   server-files/java/$_" }) -join "`n"
$output = Join-Path (Resolve-Path (Join-Path $PSScriptRoot "..")).Path "server-patch-build-$($manifest.build).zip"
$installText = @"
Project Life Zoid dedicated-server patch build $($manifest.build)

WHERE THIS GOES
---------------
The archive already contains the java/ folder. Extract it at the SERVER ROOT -- the folder
that CONTAINS java/ -- not inside java/ itself.

  server-files/                     <- extract here
      java/
          projectzomboid.jar
          zombie/                   <- the classes land beside the jar

If you end up with server-files/java/java/zombie, you extracted one level too deep. Move the
inner java/zombie up so it sits next to projectzomboid.jar and delete the empty folder. The
server's classpath entry is java/, so a class one level deeper is simply not on it: the server
starts normally and the patch does nothing.

STEPS
-----
1. Stop the dedicated server.
2. Extract this archive at the server root, as above.
3. Confirm these exact paths exist:

$verifyList

4. In the active <servername>.ini, add the build tag to PublicDescription:

   PLZPATCH=$($manifest.build)

   Any of these separators work, because some control panels strip "=" out of that field when
   the .ini is saved:

   PLZPATCH=$($manifest.build)   PLZPATCH:$($manifest.build)   PLZPATCH-$($manifest.build)   PLZPATCH $($manifest.build)   PLZPATCH$($manifest.build)

   The NUMBER is what matters. A bare "PLZPATCH" with no number reads as absent and blocks
   every player with "The server has not published its PLZPATCH build", because that is
   genuinely indistinguishable from an unpatched server.

   If the panel mangles the description field entirely, the tag is also read from the server
   NAME (PublicName), so "Project After Zoid PLZPATCH$($manifest.build)" works too.

5. Start the server.

WHAT EACH FILE DOES
-------------------
LoginPacket + PLZAccounts   the passwordless account system. LoginPacket calls PLZAccounts on
                            every login, so a partial install fails at the first join attempt
                            rather than at startup.
IsoDoor                     re-checks door locks server-side. Vanilla applies whatever a client
                            claims. This one fails OPEN: without it every property lock in the
                            mod is advisory and nothing is logged.

SERVER SETTINGS THIS PATCH EXPECTS, in <servername>.ini
-------------------------------------------------------
  Open=false

  Players are gated by SteamID, not by pre-made accounts. Approve each player once with
  /addsteamid <SteamID64>. On their first join the launcher sends the character name they
  chose, and the server binds that name to the SteamID it verified on the connection.

  There are no passwords. Do not use /adduser for players; keep it for staff accounts whose
  names you want to assign yourself.

  To remove someone, use /bansteamid <SteamID64>. It both bans the ID and deletes it from the
  allowed list. /banuser only changes one whitelist row's role, so a banned player could
  simply register a new name.

These classes were compiled against projectzomboid.jar SHA-256:
$($manifest.builtAgainstJarSha256)

Do not install this under Zomboid/mods or Zomboid/Server. A Project Zomboid update may
replace or invalidate it; rebuild and redeploy whenever the server jar changes.
"@

Add-Type -AssemblyName System.IO.Compression
if (Test-Path -LiteralPath $output -PathType Leaf) {
    Remove-Item -LiteralPath $output -Force
}
$stream = [System.IO.File]::Open($output, [System.IO.FileMode]::CreateNew)
try {
    $zip = [System.IO.Compression.ZipArchive]::new(
        $stream,
        [System.IO.Compression.ZipArchiveMode]::Create,
        $false
    )
    try {
        foreach ($class in $resolved) {
            $classEntry = $zip.CreateEntry(
                "java/$($class.Relative)",
                [System.IO.Compression.CompressionLevel]::Optimal
            )
            $classInput = [System.IO.File]::OpenRead($class.Path)
            try {
                $classOutput = $classEntry.Open()
                try {
                    $classInput.CopyTo($classOutput)
                } finally {
                    $classOutput.Dispose()
                }
            } finally {
                $classInput.Dispose()
            }
        }

        $readmeEntry = $zip.CreateEntry("INSTALL.txt")
        $writer = [System.IO.StreamWriter]::new(
            $readmeEntry.Open(),
            [System.Text.UTF8Encoding]::new($false)
        )
        try {
            $writer.Write($installText)
        } finally {
            $writer.Dispose()
        }
    } finally {
        $zip.Dispose()
    }
} finally {
    $stream.Dispose()
}

$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Output "Created: $output"
Write-Output "Classes: $($resolved.Count)"
$resolved | ForEach-Object { Write-Output "         java/$($_.Relative)" }
Write-Output "SHA256 : $hash"
