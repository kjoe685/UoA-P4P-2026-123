param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$cacheDirectory = Join-Path $projectRoot '.runtime/hansard'
$corpusFile = Join-Path $cacheDirectory 'Corp_NZHoR_V2.rds'
$partialFile = "$corpusFile.part"
$expectedSize = 1002250606
$expectedMd5 = '9fd5ed34476b1a428ba16079b955d8fc'
$sourceUrl = 'https://dataverse.harvard.edu/api/access/datafile/3758791'
function Test-CorpusFile([string]$candidate) {
    return (Test-Path -LiteralPath $candidate -PathType Leaf) -and
        (Get-Item -LiteralPath $candidate).Length -eq $expectedSize -and
        (Get-FileHash -LiteralPath $candidate -Algorithm MD5).Hash.ToLowerInvariant() -eq $expectedMd5
}
if (Test-Path -LiteralPath $corpusFile) {
    if (-not (Test-CorpusFile $corpusFile)) { throw 'Existing corpus does not match the pinned official file. Check the cache before retrying.' }
    Write-Host "Verified cached official corpus: $corpusFile"
    exit 0
}
New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null
$curlCommand = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curlCommand) { throw 'Windows curl.exe is required for this optional corpus download.' }
Write-Host 'Downloading the official ParlSpeech V2 NZ corpus (1,002,250,606 bytes). Existing partial files resume.'
& $curlCommand.Source --location --fail --retry 3 --continue-at - --output $partialFile $sourceUrl
if ($LASTEXITCODE -ne 0) { throw 'Corpus download interrupted. Re-run this script to resume the retained partial file.' }
if (-not (Test-CorpusFile $partialFile)) { throw 'Downloaded corpus failed the pinned size/MD5 check. The partial file remains for inspection; it is not imported.' }
Move-Item -LiteralPath $partialFile -Destination $corpusFile
$manifest = [ordered]@{
    schemaVersion = 1
    doi = '10.7910/DVN/L4OAKN'
    fileId = 3758791
    fileName = 'Corp_NZHoR_V2.rds'
    bytes = $expectedSize
    officialMd5 = $expectedMd5
    sha256 = (Get-FileHash -LiteralPath $corpusFile -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceUrl = $sourceUrl
    verifiedAt = [DateTimeOffset]::Now.ToString('o')
}
[IO.File]::WriteAllText((Join-Path $cacheDirectory 'source.json'),($manifest | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Host "Verified genuine corpus cache: $corpusFile"
Write-Host 'No speeches have been imported. Memory-aware extraction and corpus validation are the next stage.'
