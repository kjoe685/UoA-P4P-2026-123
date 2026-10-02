# Windows PowerShell 5.1 compatible. Called only by explicit optional NLP setup.
function Get-ParliamentUv {
    param([string]$ProjectRoot)
    $runtimeRoot=Join-Path $ProjectRoot '.runtime'
    $uvRoot=Join-Path $runtimeRoot 'uv-0.12.16'
    $uv=Join-Path $uvRoot 'uv.exe'
    $checksum='f730454bf09019754e5e5abd71a8aa18683cb739cba0d9c720bac2e7c901160f'
    $stamp=Join-Path $uvRoot 'uv.bootstrap.sha256'
    if ((Test-Path -LiteralPath $uv -PathType Leaf) -and (Test-Path -LiteralPath $stamp -PathType Leaf)) {
        $identity=$checksum+':'+(Get-FileHash -LiteralPath $uv -Algorithm SHA256).Hash.ToLowerInvariant()
        if ([IO.File]::ReadAllText($stamp).Trim() -eq $identity) { return $uv }
    }
    New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
    $archive=Join-Path $runtimeRoot 'uv-0.12.16.zip'
    $partial=$archive+'.part'
    if (!(Test-Path -LiteralPath $archive -PathType Leaf) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum) {
        [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12
        Write-Host 'Downloading pinned uv for optional local analysis...'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/astral-sh/uv/releases/download/0.12.16/uv-x86_64-pc-windows-msvc.zip' -OutFile $partial
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum) { throw 'uv download checksum mismatch. Re-run local setup to try again.' }
        Move-Item -LiteralPath $partial -Destination $archive -Force
    }
    Write-Host 'Extracting verified uv archive...'
    $staging=Join-Path $runtimeRoot ('uv-extract-'+[Guid]::NewGuid().ToString('N'))
    Expand-Archive -LiteralPath $archive -DestinationPath $staging
    if (!(Test-Path -LiteralPath (Join-Path $staging 'uv.exe') -PathType Leaf)) { throw 'Verified uv archive contains no executable. Re-run local setup.' }
    # A file can exist after interrupted extraction. Select it only with verified identity.
    $identity=$checksum+':'+(Get-FileHash -LiteralPath (Join-Path $staging 'uv.exe') -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $staging 'uv.bootstrap.sha256'),$identity)
    if (Test-Path -LiteralPath $uvRoot) {
        $resolvedRuntime=(Resolve-Path -LiteralPath $runtimeRoot).Path
        $resolvedUv=(Resolve-Path -LiteralPath $uvRoot).Path
        if ((Split-Path -Parent $resolvedUv) -ne $resolvedRuntime) { throw 'Managed uv directory is outside the local runtime.' }
        Move-Item -LiteralPath $resolvedUv -Destination (Join-Path $resolvedRuntime ('uv-incomplete-'+[Guid]::NewGuid().ToString('N')))
    }
    Move-Item -LiteralPath $staging -Destination $uvRoot
    if (!(Test-Path -LiteralPath $uv -PathType Leaf)) { throw 'uv extraction is incomplete. Re-run local setup.' }
    return $uv
}
