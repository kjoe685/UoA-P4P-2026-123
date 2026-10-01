# Run with Windows PowerShell 5.1 or newer. Uses no provider credentials.
param([int]$Port = 18086)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$oldPath = $env:PATH
$oldManaged = $env:PARLIAMENT_MANAGED_JAVA
$launcherProcess = $null
try {
    # Simulate a machine with only built-in Windows tools on PATH.
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot;$env:SystemRoot\System32\WindowsPowerShell\v1.0"
    $env:PARLIAMENT_MANAGED_JAVA = '1'
    $launcherProcess = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + (Join-Path $projectRoot 'run.ps1') + '"'), 'serve', $Port) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot 'target/launcher-smoke.stdout.log') -RedirectStandardError (Join-Path $projectRoot 'target/launcher-smoke.stderr.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        if ($launcherProcess.HasExited) { throw 'Launcher exited before the server started. Read target/launcher-smoke.*.log.' }
        try { $config = Invoke-RestMethod -Uri "http://localhost:$Port/api/config"; $ready = $true; break } catch { Start-Sleep -Milliseconds 200 }
    }
    if (!$ready) { throw 'Launcher readiness timed out.' }
    if ($config.defaultProvider -ne 'demo') { throw 'Expected credential-free default demo.' }
    $run = Invoke-RestMethod -Uri "http://localhost:$Port/api/debates" -Method Post -ContentType 'application/json' -Body '{"provider":"demo","rounds":1,"members":[{"party":"LABOUR"}],"topics":["Launcher verification"]}'
    $events = (Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$Port/api/debates/$($run.id)/events").Content
    if (!$events.Contains('"outcome":"complete"')) { throw 'Demo did not complete.' }
    Write-Output 'PASS: Windows PowerShell launcher, built-in-only PATH, managed Java/Maven, HTTP demo.'
} finally {
    if ($launcherProcess) {
        & "$env:SystemRoot\System32\taskkill.exe" /PID $launcherProcess.Id /T /F 2>&1 | Out-Null
    }
    $env:PATH = $oldPath
    $env:PARLIAMENT_MANAGED_JAVA = $oldManaged
}
