# Offline developer check. Uses a previously verified official archive, never downloads.
param([string]$ArchivePath)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if (!$ArchivePath) { $ArchivePath=Join-Path $projectRoot '.runtime/uv-0.12.16.zip' }
if (!(Test-Path -LiteralPath $ArchivePath -PathType Leaf)) { throw 'Run explicit local NLP setup first to cache the official uv archive.' }
if ((Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'f730454bf09019754e5e5abd71a8aa18683cb739cba0d9c720bac2e7c901160f') { throw 'Verification requires the checksum-pinned official uv archive.' }
. (Join-Path $PSScriptRoot 'nlp-runtime.ps1')
$fixtureRoot=Join-Path $projectRoot ('target/nlp-bootstrap-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$script:downloadAttempts=0
function Invoke-WebRequest { param([switch]$UseBasicParsing,[string]$Uri,[string]$OutFile); $script:downloadAttempts++; throw 'Offline verification blocked a download' }
function New-Fixture {
    param([string]$Name)
    $path=Join-Path $fixtureRoot ($Name+' with spaces')
    New-Item -ItemType Directory -Path (Join-Path $path '.runtime') -Force | Out-Null
    Copy-Item -LiteralPath $ArchivePath -Destination (Join-Path $path '.runtime/uv-0.12.16.zip')
    return $path
}
$cold=New-Fixture 'cold'
$coldUv=Get-ParliamentUv -ProjectRoot $cold
if ($coldUv -ne (Join-Path $cold '.runtime/uv-0.12.16/uv.exe')) { throw 'Cold extraction selected the wrong path' }
$version=& $coldUv --version
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^uv 0\.12\.16') { throw 'Extracted uv executable did not run at the pinned version' }

$recovery=New-Fixture 'interrupted'
$incomplete=Join-Path $recovery '.runtime/uv-0.12.16'
New-Item -ItemType Directory -Path (Join-Path $incomplete 'uv.exe') -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $incomplete 'preserve.txt'),'interrupted installation marker')
[IO.File]::WriteAllText((Join-Path $recovery '.runtime/uv-0.12.16.zip.part'),'unverified interrupted download')
$recoveredUv=Get-ParliamentUv -ProjectRoot $recovery
if (!(Test-Path -LiteralPath $recoveredUv -PathType Leaf)) { throw 'Recovery did not select a real executable' }
$preserved=@(Get-ChildItem -LiteralPath (Join-Path $recovery '.runtime') -Directory -Filter 'uv-incomplete-*')
if ($preserved.Count -ne 1 -or [IO.File]::ReadAllText((Join-Path $preserved[0].FullName 'preserve.txt')) -ne 'interrupted installation marker') { throw 'Incomplete installation was not preserved' }
if ((Get-ChildItem -LiteralPath (Join-Path $recovery '.runtime/uv-0.12.16') -Recurse -Filter uv.exe -File).Count -ne 1) { throw 'Replacement uv installation is nested' }
$directoryCount=@(Get-ChildItem -LiteralPath (Join-Path $recovery '.runtime') -Directory).Count
if ((Get-ParliamentUv -ProjectRoot $recovery) -ne $recoveredUv -or @(Get-ChildItem -LiteralPath (Join-Path $recovery '.runtime') -Directory).Count -ne $directoryCount) { throw 'Repeated setup did not reuse the installation' }
if ($script:downloadAttempts -ne 0) { throw 'Verified cache was downloaded again' }
[IO.File]::WriteAllText($recoveredUv,'interrupted executable extraction')
$recoveredUv=Get-ParliamentUv -ProjectRoot $recovery
if ((Get-FileHash -LiteralPath $recoveredUv -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $coldUv -Algorithm SHA256).Hash) { throw 'Truncated executable was selected instead of recovering the verified cache' }

$corrupt=New-Fixture 'corrupt'
[IO.File]::WriteAllText((Join-Path $corrupt '.runtime/uv-0.12.16.zip'),'corrupt completed cache')
[IO.File]::WriteAllText((Join-Path $corrupt '.runtime/uv-0.12.16.zip.part'),'unverified partial cache')
try { Get-ParliamentUv -ProjectRoot $corrupt | Out-Null; throw 'Corrupt archive was accepted' }
catch { if ($_.Exception.Message -ne 'Offline verification blocked a download') { throw } }
if ($script:downloadAttempts -ne 1 -or (Test-Path -LiteralPath (Join-Path $corrupt '.runtime/uv-0.12.16/uv.exe'))) { throw 'Corrupt or stale archive was extracted' }

$badDownload=Join-Path $fixtureRoot 'bad download with spaces'
New-Item -ItemType Directory -Path (Join-Path $badDownload '.runtime') | Out-Null
function Invoke-WebRequest { param([switch]$UseBasicParsing,[string]$Uri,[string]$OutFile); $script:downloadAttempts++; [IO.File]::WriteAllText($OutFile,'invalid downloaded payload') }
try { Get-ParliamentUv -ProjectRoot $badDownload | Out-Null; throw 'Invalid downloaded payload was accepted' }
catch { if ($_.Exception.Message -notmatch 'checksum mismatch') { throw } }
if ((Test-Path -LiteralPath (Join-Path $badDownload '.runtime/uv-0.12.16.zip')) -or (Test-Path -LiteralPath (Join-Path $badDownload '.runtime/uv-0.12.16/uv.exe'))) { throw 'Invalid download was promoted or extracted' }

$empty=New-Fixture 'missing executable'
$originalUv=Join-Path $empty '.runtime/uv-0.12.16'
New-Item -ItemType Directory -Path $originalUv | Out-Null
[IO.File]::WriteAllText((Join-Path $originalUv 'preserve.txt'),'original incomplete directory')
function Expand-Archive { param([string]$LiteralPath,[string]$DestinationPath); New-Item -ItemType Directory -Path $DestinationPath | Out-Null }
try { Get-ParliamentUv -ProjectRoot $empty | Out-Null; throw 'Extraction without an executable was accepted' }
catch { if ($_.Exception.Message -notmatch 'contains no executable') { throw } }
if ([IO.File]::ReadAllText((Join-Path $originalUv 'preserve.txt')) -ne 'original incomplete directory') { throw 'Invalid extraction replaced original files' }

# Exercise the real setup adapter with a capture-only uv substitute; no packages are installed.
$wiring=Join-Path $fixtureRoot 'setup wiring with spaces'
New-Item -ItemType Directory -Path (Join-Path $wiring 'scripts'),(Join-Path $wiring '.runtime/nlp') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'setup-nlp.ps1') -Destination (Join-Path $wiring 'scripts/setup-nlp.ps1')
@'
function Get-ParliamentUv { param([string]$ProjectRoot); return 'Invoke-ParliamentBootstrapFixture' }
function Invoke-ParliamentBootstrapFixture {
    [IO.File]::WriteAllText((Join-Path (Split-Path -Parent $env:UV_CACHE_DIR) 'captured.json'),(ConvertTo-Json -InputObject @($args)))
    & "$env:SystemRoot/System32/cmd.exe" /c exit 0
}
'@ | Set-Content -LiteralPath (Join-Path $wiring 'scripts/nlp-runtime.ps1') -Encoding UTF8
foreach ($selection in @('base','models','preserved models')) {
    if ($selection -eq 'preserved models') { [IO.File]::WriteAllText((Join-Path $wiring '.runtime/nlp/models.complete'),'existing optional dependencies') }
    & (Join-Path $wiring 'scripts/setup-nlp.ps1') -Models:($selection -eq 'models')
    $captured=Get-Content -LiteralPath (Join-Path $wiring '.runtime/captured.json') -Raw | ConvertFrom-Json
    if ($captured -notcontains '--locked' -or $captured -notcontains '--no-dev' -or $captured -notcontains (Join-Path $wiring 'nlp')) { throw 'Setup lost its locked project arguments' }
    if (($captured -contains '--extra') -ne ($selection -ne 'base') -or ($selection -ne 'base' -and $captured -notcontains 'models')) { throw 'Setup lost optional dependency selection' }
    if ($env:UV_PROJECT_ENVIRONMENT -ne (Join-Path $wiring '.runtime/nlp-env') -or $env:UV_MANAGED_PYTHON -ne 'true') { throw 'Setup lost application-local managed Python' }
}

$result=[ordered]@{result='PASS'; windowsPowerShell=$PSVersionTable.PSVersion.ToString(); checks=@('pinned cold cache','space path','incomplete preservation','repeat reuse','truncated executable recovery','corrupt and stale rejection','download checksum rejection','missing executable rejection','locked setup and model-extra preservation'); networkCalls=0; blockedDownloadAttempts=1; invalidDownloadFixtures=1; fixture=$fixtureRoot; uvVersion=$version}
$result | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $fixtureRoot 'result.json') -Encoding UTF8
$result | ConvertTo-Json -Depth 3
