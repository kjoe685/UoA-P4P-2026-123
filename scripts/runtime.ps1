# Windows PowerShell 5.1 compatible. Installs tools within the extracted project only.
function Get-ParliamentJava {
    param([string]$ProjectRoot)
    $managedRoot = Join-Path $ProjectRoot '.runtime/jdk-17.0.20.1+1'
    $managedJava = Join-Path $managedRoot 'bin/java.exe'
    if (Test-Path -LiteralPath $managedJava) { return $managedRoot }
    if ($env:PARLIAMENT_MANAGED_JAVA -ne '1') {
        $javaCommand = Get-Command java -ErrorAction SilentlyContinue
        $javacCommand = Get-Command javac -ErrorAction SilentlyContinue
        if ($javaCommand -and $javacCommand) {
            $properties = (& $javaCommand.Source -XshowSettings:properties -version 2>&1 | Out-String)
            if ($properties -match 'java\.specification\.version = (\d+)' -and [int]$Matches[1] -ge 17) {
                if ($properties -match 'java\.home = ([^\r\n]+)') { return $Matches[1].Trim() }
            }
        }
    }
    if ([Environment]::Is64BitOperatingSystem -eq $false) { throw 'The managed Windows runtime requires 64-bit Windows.' }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $runtimeRoot = Join-Path $ProjectRoot '.runtime'
    New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null
    $archive = Join-Path $runtimeRoot 'jdk-17.0.20.1+1.zip'
    $partial = $archive + '.part'
    $expectedHash = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
        Write-Host 'Downloading pinned Java 17 (first launch only)...'
        Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' -OutFile $partial
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'Java download checksum mismatch. Re-run to download it again.' }
        Move-Item -LiteralPath $partial -Destination $archive -Force
    }
    # A unique extraction directory means a previous interrupted extraction cannot be selected.
    $staging = Join-Path $runtimeRoot ('java-extract-' + [Guid]::NewGuid().ToString('N'))
    Expand-Archive -LiteralPath $archive -DestinationPath $staging
    $jdkDirectory = Get-ChildItem -LiteralPath $staging -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/javac.exe') } | Select-Object -First 1
    if (!$jdkDirectory) { throw 'The downloaded Java archive contains no JDK.' }
    Move-Item -LiteralPath $jdkDirectory.FullName -Destination $managedRoot
    return $managedRoot
}
