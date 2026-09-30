$ErrorActionPreference = "Stop"

$sdkDir = "$env:LOCALAPPDATA\Android\Sdk"
$cmdlineLatest = "$sdkDir\cmdline-tools\latest"
$binDir = "$cmdlineLatest\bin"
$sdkManager = "$binDir\sdkmanager.bat"

Write-Host "=== Setting up Android SDK for Friday Mobile APK ==="
Write-Host "Target SDK Directory: $sdkDir"

if (-not (Test-Path $sdkManager)) {
    Write-Host "[1/4] Downloading Android command line tools..."
    $zipPath = "$env:TEMP\cmdline-tools.zip"
    $downloadUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $wc = New-Object System.Net.WebClient
    $wc.DownloadFile($downloadUrl, $zipPath)

    Write-Host "[2/4] Extracting command line tools..."
    $extractTemp = "$env:TEMP\cmdline-extract"
    if (Test-Path $extractTemp) { Remove-Item -Path $extractTemp -Recurse -Force }
    Expand-Archive -Path $zipPath -DestinationPath $extractTemp -Force
    
    New-Item -ItemType Directory -Force -Path $cmdlineLatest | Out-Null
    Copy-Item -Path "$extractTemp\cmdline-tools\*" -Destination $cmdlineLatest -Recurse -Force
    
    Remove-Item -Path $zipPath, $extractTemp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "[2/4] Command line tools installed to $cmdlineLatest"
} else {
    Write-Host "[1/4] Android command line tools already present."
}

Write-Host "[3/4] Accepting licenses and installing platform 35 & build-tools 35.0.0..."
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir

# Accept licenses
"y`ny`ny`ny`ny`ny`ny`n" | & $sdkManager --sdk_root=$sdkDir --licenses | Out-Null

# Install required components
"y`ny`ny`n" | & $sdkManager --sdk_root=$sdkDir "platforms;android-35" "build-tools;35.0.0" "platform-tools"

Write-Host "[4/4] Writing local.properties for Friday Android app..."
$escapedSdk = $sdkDir.Replace("\", "\\")
"sdk.dir=$escapedSdk" | Set-Content -Encoding ascii "d:\codes\Friday\friday-connect-android\local.properties"

Write-Host "=== Android SDK Setup Complete! ==="
