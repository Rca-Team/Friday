param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$androidProject = Join-Path $projectRoot 'friday-connect-android'
$pythonExe = Join-Path $projectRoot '.build-venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonExe)) { throw 'Project build Python is missing. Prepare the desktop build environment first.' }
if (-not $env:JAVA_HOME) {
    $jdkCandidate = Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'jdk-17*' } | Select-Object -First 1
    if ($jdkCandidate) { $env:JAVA_HOME = $jdkCandidate.FullName }
}
if (-not $env:JAVA_HOME) { throw 'JDK 17 is required. Set JAVA_HOME to the installed JDK.' }
Push-Location $projectRoot
try {
    & $pythonExe (Join-Path $PSScriptRoot 'prepare_android_voice.py')
    if ($LASTEXITCODE -ne 0) { throw 'Offline voice preparation failed.' }
    & $pythonExe (Join-Path $PSScriptRoot 'android_inventory.py')
    if ($LASTEXITCODE -ne 0) { throw 'Desktop capability inventory failed.' }
    Push-Location $androidProject
    try {
        if ($SkipChecks) { & .\gradlew.bat assembleRelease --console=plain }
        else { & .\gradlew.bat testDebugUnitTest lintDebug assembleRelease --console=plain }
        if ($LASTEXITCODE -ne 0) { throw 'Android verification/build failed.' }
    } finally { Pop-Location }
    $sourceApk = Join-Path $androidProject 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $sourceApk)) { throw 'The generated APK was not found.' }
    $outputDirectory = Join-Path $projectRoot 'dist'
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
    $outputApk = Join-Path $outputDirectory 'Friday-AI-Android-1.0.0.apk'
    Copy-Item -LiteralPath $sourceApk -Destination $outputApk -Force
    $digest = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()
    "$digest  Friday-AI-Android-1.0.0.apk" | Set-Content -LiteralPath (Join-Path $outputDirectory 'Friday-AI-Android-1.0.0.sha256') -Encoding ascii
    & $pythonExe (Join-Path $PSScriptRoot 'verify_android_content.py')
    if ($LASTEXITCODE -ne 0) { throw 'Packaged asset verification failed.' }
    Copy-Item -LiteralPath (Join-Path $androidProject 'README.md') -Destination (Join-Path $outputDirectory 'Friday-AI-Android-Setup.md') -Force
    Copy-Item -LiteralPath (Join-Path $androidProject 'ANDROID_VERIFICATION.md') -Destination (Join-Path $outputDirectory 'Friday-AI-Android-Verification.md') -Force
    # A rebuilt APK must never retain a receipt for different bytes.
    $receiptPath = Join-Path $outputDirectory 'Friday-AI-Android-1.0.0-verification.json'
    if (Test-Path -LiteralPath $receiptPath) {
        $priorReceipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
        if ($priorReceipt.sha256 -ne $digest) { Remove-Item -LiteralPath $receiptPath }
    }
    Write-Host "APK: $outputApk"
    Write-Host "SHA256: $digest"
} finally { Pop-Location }
