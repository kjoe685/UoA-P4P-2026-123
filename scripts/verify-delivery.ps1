# Developer smoke: built-in Windows tools only inside a ZIP snapshot, no model calls.
param([int]$Port = 18089, [switch]$UseCachedJavaArchive, [string]$ResumeFixture)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$fixtureRoot = Join-Path $projectRoot ('target/delivery-' + [Guid]::NewGuid().ToString('N'))
$checkout = Join-Path $fixtureRoot 'ZIP checkout with spaces'
$zipPath = Join-Path $fixtureRoot 'project.zip'
if ($ResumeFixture) {
    $resolvedFixture = (Resolve-Path -LiteralPath $ResumeFixture).Path
    $allowedFixtures = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'target')).Path + [IO.Path]::DirectorySeparatorChar
    if (!$resolvedFixture.StartsWith($allowedFixtures,[StringComparison]::OrdinalIgnoreCase)) { throw 'Resume fixture must be inside this project target directory' }
    $fixtureRoot = $resolvedFixture
    $checkout = Join-Path $fixtureRoot 'ZIP checkout with spaces'
    $zipPath = Join-Path $fixtureRoot 'project.zip'
    if (!(Test-Path -LiteralPath $zipPath) -or !(Test-Path -LiteralPath (Join-Path $checkout 'run.ps1'))) { throw 'Resume requires an existing delivery ZIP fixture' }
}
$savedEnvironment = @{}
foreach ($name in @('PATH','JAVA_HOME','MAVEN_USER_HOME','PARLIAMENT_MANAGED_JAVA','PARLIAMENT_URL')) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
$launcher = $null
function Stop-OwnedLauncher {
    if ($script:launcher -and !$script:launcher.HasExited) {
        & "$env:SystemRoot/System32/taskkill.exe" /PID $script:launcher.Id /T /F 2>&1 | Out-Null
    }
    $script:launcher = $null
}
function Start-Fixture {
    param([string]$Label)
    Write-Host "Starting $Label ZIP launcher"
    $script:launcher = Start-Process -FilePath "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + (Join-Path $checkout 'run.ps1') + '"'),'serve',$Port) -WorkingDirectory $checkout -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $fixtureRoot ($Label + '.stdout.log')) -RedirectStandardError (Join-Path $fixtureRoot ($Label + '.stderr.log'))
    $deadline = (Get-Date).AddMinutes(12)
    while ((Get-Date) -lt $deadline) {
        if ($script:launcher.HasExited) { throw "Launcher exited; inspect $fixtureRoot/$Label.*.log" }
        try { return Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/config" -TimeoutSec 5 } catch { Start-Sleep -Milliseconds 500 }
    }
    throw "Launcher readiness timed out; inspect $fixtureRoot/$Label.*.log"
}
try {
    Write-Host "Delivery fixture: $fixtureRoot"
    if (!$ResumeFixture) {
    New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
    # Git constructs the developer fixture only; ordinary ZIP users do not need it.
    $tracked = @(& git -C $projectRoot ls-files)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inventory tracked snapshot' }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($zipPath,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $tracked) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $projectRoot $name),$name) | Out-Null }
    } finally { $zip.Dispose() }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $checkout
    if (Test-Path -LiteralPath (Join-Path $checkout '.git')) { throw 'ZIP contains Git metadata' }
    if (Test-Path -LiteralPath (Join-Path $checkout '.runtime')) { throw 'ZIP contains runtime cache' }
    if ($UseCachedJavaArchive) {
        New-Item -ItemType Directory -Path (Join-Path $checkout '.runtime') | Out-Null
        Copy-Item -LiteralPath (Join-Path $projectRoot '.runtime/jdk-17.0.20.1+1.zip') -Destination (Join-Path $checkout '.runtime/jdk-17.0.20.1+1.zip')
    }
    # Seed an interrupted wrapper cache without an executable; never run it.
    $properties = Get-Content -LiteralPath (Join-Path $checkout '.mvn/wrapper/maven-wrapper.properties') -Raw | ConvertFrom-StringData
    $urlHash = ([Security.Cryptography.SHA256]::Create().ComputeHash([byte[]][char[]]$properties.distributionUrl) | ForEach-Object { $_.ToString('x2') }) -join ''
    $incompleteMaven = Join-Path $checkout ('.runtime/maven/wrapper/dists/apache-maven-3.9.16/' + $urlHash)
    New-Item -ItemType Directory -Force -Path $incompleteMaven | Out-Null
    [IO.File]::WriteAllText((Join-Path $incompleteMaven 'interrupted.txt'),'incomplete Maven extraction')
    }
    $env:PATH = "$env:SystemRoot/System32;$env:SystemRoot;$env:SystemRoot/System32/WindowsPowerShell/v1.0"
    $env:JAVA_HOME = ''
    $env:MAVEN_USER_HOME = ''
    $env:PARLIAMENT_MANAGED_JAVA = '1'
    $env:PARLIAMENT_URL = "http://127.0.0.1:$Port"
    $config = Start-Fixture 'first'
    if ($config.defaultProvider -ne 'demo') { throw 'ZIP default requires credentials' }
    $readiness = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/local-readiness" -TimeoutSec 5
    if ($readiness.installed -ne $false) { throw 'ZIP unexpectedly has optional NLP dependencies' }
    $ollama = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/ollama-readiness" -TimeoutSec 10
    if ($ollama.installed -ne $false -or $ollama.owned -ne $false) { throw 'ZIP unexpectedly installed or started a local LLM runtime' }
    $packageSmoke = Join-Path $fixtureRoot 'packaged-archive-smoke'
    $managedJava = Join-Path $checkout '.runtime/jdk-17.0.20.1+1/bin/java.exe'
    $smokeOutput = & $managedJava -cp ((Join-Path $checkout 'target/test-classes') + ';' + (Join-Path $checkout 'target/virtual-parliament.jar')) engine.application.OllamaPackageSmoke $packageSmoke 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or !$smokeOutput.Contains('ZIP bundled reader PASS') -or !$smokeOutput.Contains('GZIP_TAR bundled reader PASS') -or !$smokeOutput.Contains('ZSTD_TAR bundled reader PASS')) { throw 'Isolated packaged archive/JNI smoke failed' }
    [IO.File]::WriteAllText((Join-Path $fixtureRoot 'packaged-archive-smoke.log'),$smokeOutput,(New-Object Text.UTF8Encoding $false))
    $run = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/debates" -Method Post -ContentType 'application/json' -Body '{"agentModelPreset":"demo","rounds":1,"groundingCount":0,"members":[{"party":"LABOUR"}],"topics":["ZIP delivery verification"]}' -TimeoutSec 10
    $events = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/debates/$($run.id)/events" -TimeoutSec 20).Content
    if (!$events.Contains('"outcome":"complete"')) { throw 'ZIP demo did not complete' }
    $stamp = Get-Content -LiteralPath (Join-Path $checkout 'target/launcher-build.sha256') -Raw
    $jarTime = (Get-Item -LiteralPath (Join-Path $checkout 'target/virtual-parliament.jar')).LastWriteTimeUtc
    # Exercises the actual double-click batch entry point as a command adapter.
    $cli = & (Join-Path $checkout 'run.cmd') cli config | Out-String
    if ($LASTEXITCODE -ne 0 -or !$cli.Contains('"defaultProvider":"demo"')) { throw 'ZIP run.cmd CLI failed' }
    $menuInput = Join-Path $fixtureRoot 'guided-utf8.input.txt'
    # A supplementary symbol exercises UTF-8 beyond the system code page too.
    $unicodeTopic = 'Te ' + [char]0x0101 + 'hua ' + [char]::ConvertFromUtf32(0x1F3E0)
    [IO.File]::WriteAllText($menuInput,("1`ndemo`n`n" + $unicodeTopic + "`n1`nLABOUR`n`n`n`n0`n`n0`n"),(New-Object Text.UTF8Encoding $false))
    $menu = Start-Process -FilePath "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + (Join-Path $checkout 'run.ps1') + '"'),'cli') -WorkingDirectory $checkout -WindowStyle Hidden -PassThru -RedirectStandardInput $menuInput -RedirectStandardOutput (Join-Path $fixtureRoot 'guided.stdout.log') -RedirectStandardError (Join-Path $fixtureRoot 'guided.stderr.log')
    $null = $menu.Handle # Retain the exit-status handle on Windows PowerShell5.1.
    if (!$menu.WaitForExit(30000)) { & "$env:SystemRoot/System32/taskkill.exe" /PID $menu.Id /T /F 2>&1 | Out-Null; throw 'Guided UTF-8 menu timed out' }
    if ($menu.ExitCode -ne 0) { throw 'Guided UTF-8 menu failed' }
    $allRuns = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/debates" -TimeoutSec 5
    if (!($allRuns | Where-Object { $_.topics[0].title -eq $unicodeTopic })) { throw 'Guided input lost UTF-8 topic text' }
    if ((Get-Item -LiteralPath (Join-Path $checkout 'target/virtual-parliament.jar')).LastWriteTimeUtc -ne $jarTime) { throw 'Repeat command rebuilt unchanged sources' }
    Stop-OwnedLauncher
    # An unattached menu owns its default backend and must release it on exit.
    $probe = New-Object Net.Sockets.TcpClient
    $defaultOccupied = $false
    try { $probe.Connect('127.0.0.1',8080); $defaultOccupied = $true } catch { } finally { $probe.Dispose() }
    $ownership = 'skipped: default port already occupied'
    if (!$defaultOccupied) {
        [IO.File]::WriteAllText($menuInput,"0`n",(New-Object Text.UTF8Encoding $false))
        $env:PARLIAMENT_URL = ''
        $menu = Start-Process -FilePath "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"' + (Join-Path $checkout 'run.ps1') + '"'),'cli') -WorkingDirectory $checkout -WindowStyle Hidden -PassThru -RedirectStandardInput $menuInput -RedirectStandardOutput (Join-Path $fixtureRoot 'owned-menu.stdout.log') -RedirectStandardError (Join-Path $fixtureRoot 'owned-menu.stderr.log')
        $null = $menu.Handle
        if (!$menu.WaitForExit(30000)) { & "$env:SystemRoot/System32/taskkill.exe" /PID $menu.Id /T /F 2>&1 | Out-Null; throw 'Owned menu did not exit' }
        if ($menu.ExitCode -ne 0 -or !(Get-Content -LiteralPath (Join-Path $fixtureRoot 'owned-menu.stdout.log') -Raw).Contains('Started the local backend')) { throw 'Menu backend ownership failed' }
        $probe = New-Object Net.Sockets.TcpClient
        try { $probe.Connect('127.0.0.1',8080); throw 'Owned menu left backend running' } catch [Net.Sockets.SocketException] { $ownership = 'PASS' } finally { $probe.Dispose() }
        $env:PARLIAMENT_URL = "http://127.0.0.1:$Port"
    }
    # A presentation cache must not override committed evidence after actual JAR startup.
    $runDirectory = Join-Path $checkout ('runs/' + $run.id)
    $publicPath = Join-Path $runDirectory 'transcript.json'
    $setupPath = Join-Path $runDirectory 'setup.json'
    $viewPath = Join-Path $runDirectory 'view.json'
    $publicHash = (Get-FileHash -LiteralPath $publicPath -Algorithm SHA256).Hash
    $setupHash = (Get-FileHash -LiteralPath $setupPath -Algorithm SHA256).Hash
    Copy-Item -LiteralPath $viewPath -Destination (Join-Path $fixtureRoot 'replay-original.json')
    $cache = [string[]](Get-Content -LiteralPath $viewPath -Raw | ConvertFrom-Json)
    $corruptedSpeech = $false
    for ($cacheIndex=0;$cacheIndex -lt $cache.Count;$cacheIndex++) {
        $cachedEvent = $cache[$cacheIndex] | ConvertFrom-Json
        if (!$corruptedSpeech -and $cachedEvent.type -eq 'speech') {
            $cachedEvent.text = 'UNCOMMITTED_REPLAY_SENTINEL'
            $cache[$cacheIndex] = $cachedEvent | ConvertTo-Json -Depth 20 -Compress
            $corruptedSpeech = $true
        }
    }
    if (!$corruptedSpeech) { throw 'Completed ZIP fixture has no cached speech to verify recovery' }
    [IO.File]::WriteAllText($viewPath,(ConvertTo-Json -InputObject $cache -Depth 20 -Compress),(New-Object Text.UTF8Encoding $false))
    Copy-Item -LiteralPath $viewPath -Destination (Join-Path $fixtureRoot 'replay-corrupted.json')
    $config = Start-Fixture 'repeat'
    if ((Get-Content -LiteralPath (Join-Path $checkout 'target/launcher-build.sha256') -Raw) -ne $stamp -or (Get-Item -LiteralPath (Join-Path $checkout 'target/virtual-parliament.jar')).LastWriteTimeUtc -ne $jarTime) { throw 'Repeat launch rebuilt unchanged sources' }
    $saved = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/debates/$($run.id)/transcript" -TimeoutSec 5
    if ($saved.outcome -ne 'complete') { throw 'ZIP restart lost committed public evidence' }
    $replay = (Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/debates/$($run.id)/events" -TimeoutSec 20).Content
    if ($replay.Contains('UNCOMMITTED_REPLAY_SENTINEL')) { throw 'Restart published corrupted replay cache text' }
    $replayed = @($replay -split "`n" | Where-Object { $_.StartsWith('data: ') } | ForEach-Object { $_.Substring(6) | ConvertFrom-Json })
    $publicReplay = @($replayed | Where-Object { $_.turnId })
    if ($publicReplay.Count -ne $saved.events.Count) { throw 'Restart replay changed committed evidence coverage' }
    for ($eventIndex=0;$eventIndex -lt $publicReplay.Count;$eventIndex++) {
        $replayedEvent = $publicReplay[$eventIndex]; $sourceEvent = $saved.events[$eventIndex]
        $replayedText = if ($replayedEvent.type -eq 'topic') { $replayedEvent.topic } else { $replayedEvent.text }
        if ($replayedEvent.turnId -ne $sourceEvent.id -or $replayedEvent.topicId -ne $sourceEvent.topicId -or $replayedText -ne $sourceEvent.text) { throw 'Restart replay differs from ordered committed evidence' }
    }
    if ($replayed[0].id -ne $run.id -or $replayed[-1].outcome -ne 'complete' -or $replayed[-1].at -ne $saved.endedAt) { throw 'Restart replay changed sitting or terminal metadata' }
    if ((Get-FileHash -LiteralPath $publicPath -Algorithm SHA256).Hash -ne $publicHash -or (Get-FileHash -LiteralPath $setupPath -Algorithm SHA256).Hash -ne $setupHash) { throw 'Replay repair changed committed public or private setup files' }
    $repairedCache = [string[]](Get-Content -LiteralPath $viewPath -Raw | ConvertFrom-Json)
    if (($repairedCache -join '').Contains('UNCOMMITTED_REPLAY_SENTINEL') -or $repairedCache.Count -ne $replayed.Count) { throw 'Replay repair was not persisted before publication' }
    Copy-Item -LiteralPath $viewPath -Destination (Join-Path $fixtureRoot 'replay-repaired.json')
    [pscustomobject]@{result='PASS';runId=$run.id;publicSha256=$publicHash.ToLowerInvariant();setupSha256=$setupHash.ToLowerInvariant();events=$publicReplay.Count;replay=$replayed.Count;runTime=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureRoot 'replay-recovery.json') -Encoding UTF8
    Stop-OwnedLauncher
    # Simulate a killed extraction and stale partial download, retaining the valid archive.
    $runtimeRoot = (Resolve-Path -LiteralPath (Join-Path $checkout '.runtime')).Path
    $managedRoot = (Resolve-Path -LiteralPath (Join-Path $runtimeRoot 'jdk-17.0.20.1+1')).Path
    if ((Split-Path -Parent $managedRoot) -ne $runtimeRoot) { throw 'Recovery target escaped fixture runtime' }
    Move-Item -LiteralPath $managedRoot -Destination (Join-Path $runtimeRoot 'jdk-before-interruption')
    New-Item -ItemType Directory -Path (Join-Path $managedRoot 'bin') | Out-Null
    [IO.File]::WriteAllText((Join-Path $managedRoot 'bin/interrupted.txt'),'incomplete extraction')
    [IO.File]::WriteAllText((Join-Path $runtimeRoot 'jdk-17.0.20.1+1.zip.part'),'unverified partial download')
    $config = Start-Fixture 'recovery'
    if (!(Test-Path -LiteralPath (Join-Path $managedRoot 'bin/java.exe')) -or !(Test-Path -LiteralPath (Join-Path $managedRoot 'bin/javac.exe'))) { throw 'Interrupted extraction did not recover' }
    if (Test-Path -LiteralPath (Join-Path $managedRoot 'jdk-17.0.20.1+1')) { throw 'Recovered JDK nested inside incomplete root' }
    Stop-OwnedLauncher
    [pscustomobject]@{result='PASS'; fixture=$fixtureRoot; cachedJavaArchive=[bool]$UseCachedJavaArchive; resumedFixture=[bool]$ResumeFixture; menuOwnership=$ownership; replayRecovery='PASS'; checks='ZIP/space path/built-in PATH/first build/demo/optional NLP and LLM absence/packaged ZIP gzip zstd JNI/run.cmd/UTF8 menu/repeat/restart/corrupt replay repair/public and setup hashes/incomplete Java and Maven extraction/stale partial'; runTime=(Get-Date).ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $fixtureRoot 'result.json') -Encoding UTF8
    Write-Output "PASS: isolated ZIP delivery; evidence $fixtureRoot/result.json"
} finally {
    Stop-OwnedLauncher
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name]) }
}
