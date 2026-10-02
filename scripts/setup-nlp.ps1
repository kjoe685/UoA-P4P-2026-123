param([switch]$Models)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runtimeRoot=Join-Path $projectRoot '.runtime'
. (Join-Path $PSScriptRoot 'nlp-runtime.ps1')
$uv=Get-ParliamentUv -ProjectRoot $projectRoot
$env:UV_CACHE_DIR=Join-Path $runtimeRoot 'uv-cache'
$env:UV_PYTHON_INSTALL_DIR=Join-Path $runtimeRoot 'python'
$env:UV_PROJECT_ENVIRONMENT=Join-Path $runtimeRoot 'nlp-env'
$env:UV_MANAGED_PYTHON='true'
$arguments=@('sync','--project',(Join-Path $projectRoot 'nlp'),'--locked','--no-dev')
if ($Models -or (Test-Path -LiteralPath (Join-Path $runtimeRoot 'nlp/models.complete'))) { $arguments+=@('--extra','models') }
& $uv @arguments
if ($LASTEXITCODE -ne 0) { throw 'Local NLP dependency setup failed' }
