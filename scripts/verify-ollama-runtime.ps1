param(
    [string]$ResumeFixture,
    [int]$Port = 11439
)
$ErrorActionPreference = 'Stop'
$taskRepository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskTarget = Join-Path $taskRepository 'target'
$taskPrefix = $taskTarget + [IO.Path]::DirectorySeparatorChar
if ($ResumeFixture) {
    $taskFixture = [IO.Path]::GetFullPath($ResumeFixture)
    if (!$taskFixture.StartsWith($taskPrefix, [StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath $taskFixture -PathType Container)) {
        throw 'ResumeFixture must be an existing directory under this project target'
    }
} else {
    $taskFixture = Join-Path $taskTarget ('official-ollama-' + [guid]::NewGuid().ToString('N') + ' with spaces')
    New-Item -ItemType Directory -Path $taskFixture | Out-Null
}
$taskProfile = Join-Path $taskFixture 'profile'
New-Item -ItemType Directory -Path $taskProfile -Force | Out-Null
$taskJava = Join-Path $taskRepository '.runtime/jdk-17.0.20.1+1/bin/java.exe'
if (!(Test-Path -LiteralPath $taskJava -PathType Leaf)) { throw 'Build first using run.cmd or mvnw.cmd and the pinned managed JDK' }
if (!(Test-Path -LiteralPath (Join-Path $taskTarget 'test-classes/engine/application/OfficialOllamaSmoke.class'))) {
    throw 'Compile test helpers first with mvnw.cmd test-compile'
}
if (!(Test-Path -LiteralPath (Join-Path $taskTarget 'virtual-parliament.jar'))) { throw 'Build the packaged application first with mvnw.cmd verify' }
$taskStopSignal = Join-Path $taskFixture ('stop-' + [guid]::NewGuid().ToString('N') + '.requested')
$taskArguments = '-Djava.io.tmpdir="' + $taskTarget + '" -cp "' + (Join-Path $taskTarget 'test-classes') + ';' + (Join-Path $taskTarget 'virtual-parliament.jar') + '" engine.application.OfficialOllamaSmoke "' + $taskFixture + '" ' + $Port + ' "' + $taskStopSignal + '"'
$taskStart = New-Object Diagnostics.ProcessStartInfo
$taskStart.FileName = $taskJava
$taskStart.Arguments = $taskArguments
$taskStart.WorkingDirectory = $taskRepository
$taskStart.UseShellExecute = $false
$taskStart.CreateNoWindow = $true
$taskStart.RedirectStandardOutput = $true
$taskStart.RedirectStandardError = $true
# Only this child receives a test profile. The owner process/system environment is unchanged.
$taskStart.EnvironmentVariables['USERPROFILE'] = $taskProfile
foreach ($taskKey in @($taskStart.EnvironmentVariables.Keys)) {
    if ($taskKey -match '^(OLLAMA_|OPENAI_API_KEY$|ANTHROPIC_API_KEY$|GEMINI_API_KEY$|XAI_API_KEY$|HF_TOKEN$|HUGGING_FACE_HUB_TOKEN$)') {
        $taskStart.EnvironmentVariables.Remove($taskKey)
    }
}
Write-Output "Official runtime acceptance fixture: $taskFixture"
Write-Output 'Explicitly downloads the pinned official archive only; no model weights or generation.'
$taskChild = [Diagnostics.Process]::Start($taskStart)
[ordered]@{ pid = $taskChild.Id; fixture = $taskFixture; port = $Port; stopSignal = $taskStopSignal; startedAt = (Get-Date -Format o) } |
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
    if ($taskChild.ExitCode -ne 0) { throw 'Official runtime acceptance failed; preserve this fixture for diagnosis/resume' }
} finally {
    if (!$taskChild.HasExited) {
        # Signal the Java helper to cancel its durable job and close the owned runtime.
        Set-Content -LiteralPath $taskStopSignal -Value 'Stop requested'
        if (!$taskChild.WaitForExit(15000)) { Write-Warning 'Acceptance cleanup is still running; inspect the retained fixture process record' }
    }
    $taskChild.Dispose()
}
