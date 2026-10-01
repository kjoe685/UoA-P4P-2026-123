param([string]$Mode = 'web', [Parameter(ValueFromRemainingArguments = $true)][string[]]$AppArguments)
$ErrorActionPreference = 'Stop'
try {
    Set-Location -LiteralPath $PSScriptRoot
    . (Join-Path $PSScriptRoot 'scripts/runtime.ps1')
    $env:JAVA_HOME = Get-ParliamentJava -ProjectRoot $PSScriptRoot
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
    $env:MAVEN_USER_HOME = Join-Path $PSScriptRoot '.runtime/maven'
    $jar = Join-Path $PSScriptRoot 'target/virtual-parliament.jar'
    $stampFile = Join-Path $PSScriptRoot 'target/launcher-build.sha256'
    $inputs = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -File; Get-Item -LiteralPath (Join-Path $PSScriptRoot 'pom.xml')) | Sort-Object FullName
    $inputHashes = ($inputs | ForEach-Object { $_.FullName.Substring($PSScriptRoot.Length) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }) -join '|'
    $hashAlgorithm = [Security.Cryptography.SHA256]::Create()
    try { $fingerprint = [BitConverter]::ToString($hashAlgorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($inputHashes))).Replace('-', '') } finally { $hashAlgorithm.Dispose() }
    $previousFingerprint = if (Test-Path -LiteralPath $stampFile) { (Get-Content -LiteralPath $stampFile -Raw).Trim() } else { '' }
    if (!(Test-Path -LiteralPath $jar) -or $fingerprint -ne $previousFingerprint) {
        Write-Host 'Building Virtual Parliament (first launch or changed sources)...'
        & (Join-Path $PSScriptRoot 'mvnw.cmd') '-B' ('-Dmaven.repo.local=' + (Join-Path $PSScriptRoot '.maven-cache')) 'verify'
        if ($LASTEXITCODE -ne 0) { throw 'Build failed. Check internet access and the Maven error above, then re-run.' }
        [IO.File]::WriteAllText($stampFile, $fingerprint)
    }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') '-jar' $jar $Mode @AppArguments
    exit $LASTEXITCODE
} catch {
    Write-Host ('Could not launch: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
