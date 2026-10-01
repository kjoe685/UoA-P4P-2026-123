param([switch]$Models)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$runtimeRoot=Join-Path $projectRoot '.runtime'
$uvRoot=Join-Path $runtimeRoot 'uv-0.12.16'
$uv=Join-Path $uvRoot 'uv.exe'
if (!(Test-Path -LiteralPath $uv)) {
    [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
    New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
    $archive=Join-Path $runtimeRoot 'uv-0.12.16.zip'
    $partial=$archive+'.part'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/astral-sh/uv/releases/download/0.12.16/uv-x86_64-pc-windows-msvc.zip' -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'f730454bf09019754e5e5abd71a8aa18683cb739cba0d9c720bac2e7c901160f') { throw 'UV checksum mismatch' }
    Move-Item -LiteralPath $partial -Destination $archive -Force
    $staging=Join-Path $runtimeRoot ('uv-extract-'+[Guid]::NewGuid().ToString('N'))
    Expand-Archive -LiteralPath $archive -DestinationPath $staging
    Move-Item -LiteralPath $staging -Destination $uvRoot
}
$env:UV_CACHE_DIR=Join-Path $runtimeRoot 'uv-cache'
$env:UV_PYTHON_INSTALL_DIR=Join-Path $runtimeRoot 'python'
$env:UV_PROJECT_ENVIRONMENT=Join-Path $runtimeRoot 'nlp-env'
$env:UV_MANAGED_PYTHON='true'
$arguments=@('sync','--project',(Join-Path $projectRoot 'nlp'),'--locked','--no-dev')
if ($Models) { $arguments+=@('--extra','models') }
& $uv @arguments
if ($LASTEXITCODE -ne 0) { throw 'Local NLP dependency setup failed' }
