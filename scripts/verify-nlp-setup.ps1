param([string]$ResumeFixture, [int]$Port = 8767, [ValidateSet('Base','Models')][string]$Mode = 'Base')
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskTarget = Join-Path $taskRepository 'target'
$taskPrefix = $taskTarget + [IO.Path]::DirectorySeparatorChar
if ($ResumeFixture) {
    $taskFixture = [IO.Path]::GetFullPath($ResumeFixture)
    if (!$taskFixture.StartsWith($taskPrefix,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $taskFixture -PathType Container)) {
        throw 'ResumeFixture must be an existing setup fixture under this project target'
    }
    $taskMode = 'resume'
} else {
    $taskFixture = Join-Path $taskTarget ('official-nlp-setup-' + [guid]::NewGuid().ToString('N') + ' with spaces')
    New-Item -ItemType Directory -Path $taskFixture | Out-Null
    $taskMode = 'fresh'
}
$taskProfile = Join-Path $taskFixture 'profile'
New-Item -ItemType Directory -Path $taskProfile -Force | Out-Null
$taskJava = Join-Path $taskRepository '.runtime/jdk-17.0.20.1+1/bin/java.exe'
if (!(Test-Path -LiteralPath $taskJava -PathType Leaf) -or !(Test-Path -LiteralPath (Join-Path $taskTarget 'test-classes/engine/application/OfficialNlpSetupSmoke.class'))) {
    throw 'Compile test helpers using the managed JDK first'
}
if (!(Test-Path -LiteralPath (Join-Path $taskTarget 'virtual-parliament.jar'))) { throw 'Package the current application first' }
$taskStopSignal = Join-Path $taskFixture ('stop-' + [guid]::NewGuid().ToString('N') + '.requested')
$taskStart = New-Object Diagnostics.ProcessStartInfo
$taskStart.FileName = $taskJava
$taskStart.Arguments = '-Djava.io.tmpdir="' + $taskTarget + '" -cp "' + (Join-Path $taskTarget 'test-classes') + ';' + (Join-Path $taskTarget 'virtual-parliament.jar') + '" engine.application.OfficialNlpSetupSmoke "' + $taskFixture + '" ' + $Port + ' "' + $taskStopSignal + '" ' + $taskMode + ' ' + $Mode.ToLowerInvariant()
$taskStart.WorkingDirectory = $taskRepository
$taskStart.UseShellExecute = $false
$taskStart.CreateNoWindow = $true
$taskStart.RedirectStandardOutput = $true
$taskStart.RedirectStandardError = $true
$taskStart.EnvironmentVariables['USERPROFILE'] = $taskProfile
$taskStart.EnvironmentVariables['PATH'] = $env:SystemRoot + '\System32;' + $env:SystemRoot + ';' + $env:SystemRoot + '\System32\WindowsPowerShell\v1.0'
foreach ($taskKey in @($taskStart.EnvironmentVariables.Keys)) {
    if ($taskKey -match '^(UV_|PIP_|PYTHONPATH$|OLLAMA_|OPENAI_API_KEY$|ANTHROPIC_API_KEY$|GEMINI_API_KEY$|XAI_API_KEY$|HF_|HUGGING_FACE_HUB_TOKEN$|HUGGINGFACE_HUB_TOKEN$|TRANSFORMERS_OFFLINE$)') { $taskStart.EnvironmentVariables.Remove($taskKey) }
}
$taskStart.EnvironmentVariables['PYTHONNOUSERSITE'] = '1'
$taskStart.EnvironmentVariables['PYTHONDONTWRITEBYTECODE'] = '1'
if ($Mode -eq 'Models') {
    $taskStart.EnvironmentVariables['HF_HUB_OFFLINE'] = '1'
    $taskStart.EnvironmentVariables['TRANSFORMERS_OFFLINE'] = '1'
    $taskStart.EnvironmentVariables['HF_HUB_DISABLE_TELEMETRY'] = '1'
    $taskStart.EnvironmentVariables['HF_HUB_DISABLE_SYMLINKS_WARNING'] = '1'
    $taskStart.EnvironmentVariables['HF_HOME'] = Join-Path $taskProfile 'hf-home'
}
Write-Output "Current managed NLP setup fixture: $taskFixture"
if ($Mode -eq 'Models') { Write-Output "Explicit $taskMode CPU dependency setup with checksum-verified copied weights kept offline; no weight transfers or model generation." }
elseif ($taskMode -eq 'resume') { Write-Output 'Explicit resume of retained managed NLP setup; cached fixture environment, no transformer weights or model generation.' }
else { Write-Output 'Explicit fresh lightweight Python/dependency installation; cached verified uv only, no transformer weights or model generation.' }
$taskChild = [Diagnostics.Process]::Start($taskStart)
[ordered]@{ pid=$taskChild.Id; fixture=$taskFixture; port=$Port; stopSignal=$taskStopSignal; mode=$taskMode; setupMode=$Mode; startedAt=(Get-Date -Format o) } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFixture 'process.json') -Encoding UTF8
$taskErrors = $taskChild.StandardError.ReadToEndAsync()
try {
    while ($null -ne ($taskLine = $taskChild.StandardOutput.ReadLine())) {
        Write-Output $taskLine
        Add-Content -LiteralPath (Join-Path $taskFixture 'acceptance.stdout.log') -Value $taskLine
    }
    $taskChild.WaitForExit()
    $taskErrorText = $taskErrors.GetAwaiter().GetResult()
    Set-Content -LiteralPath (Join-Path $taskFixture 'acceptance.stderr.log') -Value $taskErrorText
    if ($taskErrorText) { Write-Output $taskErrorText }
    if ($taskChild.ExitCode -ne 0) { throw 'Current managed NLP setup acceptance failed; preserve its fixture for explicit resume' }
} finally {
    if (!$taskChild.HasExited) {
        Set-Content -LiteralPath $taskStopSignal -Value 'Stop requested'
        if (!$taskChild.WaitForExit(15000)) { Write-Warning 'Cleanup still running; inspect the retained process record' }
    }
    $taskChild.Dispose()
}
