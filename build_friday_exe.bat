@echo off
setlocal EnableExtensions EnableDelayedExpansion

title Building FRIDAY Desktop App (.exe) - Made by Gaurav
cd /d "%~dp0"

echo ==========================================================================
echo               FRIDAY DESKTOP COMPILER -- MADE BY GAURAV
echo ==========================================================================
echo.

set VENV_PYTHON=%~dp0.venv\Scripts\python.exe
if not exist "%VENV_PYTHON%" (
    echo Python virtual environment not found at .venv. Running bootstrap first...
    powershell.exe -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1"
)

echo [1/3] Checking and installing PyInstaller...
"%VENV_PYTHON%" -m pip install pyinstaller

echo [2/3] Compiling FRIDAY.exe with PyInstaller...
"%VENV_PYTHON%" -m PyInstaller ^
    --noconfirm ^
    --onedir ^
    --windowed ^
    --name "FRIDAY" ^
    --icon "assets\friday.ico" ^
    --version-file "version.txt" ^
    --add-data "assets;assets" ^
    --add-data "config;config" ^
    --add-data "core;core" ^
    --add-data "actions;actions" ^
    --add-data "plugins;plugins" ^
    --add-data "memory;memory" ^
    --add-data "friday_connect;friday_connect" ^
    --add-data "brahma_connect;brahma_connect" ^
    --add-data "dashboard;dashboard" ^
    --add-data "smart_home;smart_home" ^
    --hidden-import "google.genai" ^
    --hidden-import "google.generativeai" ^
    --hidden-import "PyQt6" ^
    --hidden-import "PyQt6.QtCore" ^
    --hidden-import "PyQt6.QtGui" ^
    --hidden-import "PyQt6.QtWidgets" ^
    --hidden-import "fastapi" ^
    --hidden-import "uvicorn" ^
    --hidden-import "sounddevice" ^
    --hidden-import "pycaw" ^
    --hidden-import "comtypes" ^
    --hidden-import "mss" ^
    --hidden-import "pyautogui" ^
    --hidden-import "edge_tts" ^
    main.py

echo.
if %errorlevel% neq 0 (
    echo [ERROR] Build failed! Check the output above.
    pause
    exit /b %errorlevel%
)

echo ==========================================================================
echo [SUCCESS] FRIDAY.exe compiled successfully!
echo Executable located in: %~dp0dist\FRIDAY\FRIDAY.exe
echo ==========================================================================
pause
exit /b 0
