# Offline developer check. Uses the project POM and cached tools/dependencies in an isolated fixture.
param([string]$RepositoryCache)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
if (!$RepositoryCache) { $RepositoryCache=Join-Path $projectRoot '.maven-cache' }
$RepositoryCache=(Resolve-Path -LiteralPath $RepositoryCache).Path
if (!$env:JAVA_HOME) { $env:JAVA_HOME=Join-Path $projectRoot '.runtime/jdk-17.0.20.1+1' }
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
if (!(Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Use an installed JDK17 or run the launcher once before this offline check.' }
$env:MAVEN_USER_HOME=Join-Path $projectRoot '.runtime/maven'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$fixture=Join-Path $projectRoot ('target/repackaging-'+[Guid]::NewGuid().ToString('N')+' with spaces')
$sourceDirectory=Join-Path $fixture 'src/main/java/engine'
New-Item -ItemType Directory -Path $sourceDirectory -Force | Out-Null
$pom=Join-Path $fixture 'pom.xml'
Copy-Item -LiteralPath (Join-Path $projectRoot 'pom.xml') -Destination $pom
$source=Join-Path $sourceDirectory 'Launcher.java'
[IO.File]::WriteAllText($source,'package engine; public final class Launcher { public static void main(String[] args) { System.out.println("Synthetic packaging fixture"); } }',(New-Object Text.UTF8Encoding($false)))
$sourceHash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
$jar=Join-Path $fixture 'target/virtual-parliament.jar'
$staleName='synthetic/stale-postprocessing-only.txt'
function Invoke-Package {
    param([string]$Name)
    $log=Join-Path $fixture ($Name+'.log')
    Write-Host "Packaging isolated fixture: $Name"
    & (Join-Path $projectRoot 'mvnw.cmd') -o "-Dmaven.repo.local=$RepositoryCache" -f $pom -DskipTests package *> $log
    if ($LASTEXITCODE -ne 0) { throw "Isolated packaging failed; inspect $log" }
}
function Add-StaleEntry {
    $archive=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Update)
    try {
        if ($archive.GetEntry($staleName)) { throw 'A previous stale fixture entry survived packaging.' }
        $entry=$archive.CreateEntry($staleName)
        $writer=New-Object IO.StreamWriter($entry.Open(),(New-Object Text.UTF8Encoding($false)))
        try { $writer.Write('Synthetic postprocessing content absent from source/classes/dependencies') }
        finally { $writer.Dispose() }
    } finally { $archive.Dispose() }
}
function Assert-FreshPackage {
    $archive=[IO.Compression.ZipFile]::OpenRead($jar)
    try {
        if ($archive.GetEntry($staleName)) { throw 'Stale postprocessing entry survived repeated packaging.' }
        if (!$archive.GetEntry('engine/Launcher.class') -or !$archive.GetEntry('com/fasterxml/jackson/databind/ObjectMapper.class')) {
            throw 'Packaged application/dependency classes are missing.'
        }
    } finally { $archive.Dispose() }
    $output=& $java -jar $jar
    if ($LASTEXITCODE -ne 0 -or $output -ne 'Synthetic packaging fixture') { throw 'The isolated packaged main did not run.' }
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $sourceHash) { throw 'Fixture source changed during repeated packaging.' }
}
$result=[ordered]@{result='RUNNING'; fixture=$fixture; windowsPowerShell=$PSVersionTable.PSVersion.ToString(); networkCalls=0; sourceSha256=$sourceHash; checks=@()}
try {
    Invoke-Package 'initial'
    Assert-FreshPackage
    foreach($pass in @('repeat','repeat-again')) {
        Add-StaleEntry
        Invoke-Package $pass
        Assert-FreshPackage
    }
    $result.result='PASS'
    $result.checks=@('cached offline packaging','space path','fresh base JAR after postprocessing','second repeat fresh creation','packaged synthetic main','application and dependency classes','unchanged source')
} catch {
    $result.result='FAIL'; $result.failure=$_.Exception.Message
    throw
} finally {
    $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $fixture 'result.json') -Encoding UTF8
    $result | ConvertTo-Json -Depth 4 | Write-Host
}
