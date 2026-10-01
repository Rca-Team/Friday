param([string]$Python = '.build-venv\Scripts\python.exe', [string]$Iscc = 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& $Python -m pip check
if ($LASTEXITCODE) { throw 'Dependency validation failed.' }
& $Python -m PyInstaller --noconfirm packaging\friday.spec
if ($LASTEXITCODE) { throw 'PyInstaller failed.' }
& $Iscc /Qp packaging\friday.iss
if ($LASTEXITCODE) { throw 'Installer compilation failed.' }
$release = Get-Item dist\Friday-Setup-1.0.1-x64.exe
$hash = (Get-FileHash -LiteralPath $release.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $($release.Name)" | Set-Content -Encoding ascii dist\Friday-Setup-1.0.1-x64.sha256
