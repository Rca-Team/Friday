@echo off
setlocal EnableExtensions EnableDelayedExpansion

title FRIDAY AI - Created by Gaurav - Tactical Launcher
cd /d "%~dp0"

color 0C

echo ==========================================================================
echo   ______ _____  _____ _____             __     __
echo  ^|  ____^|  __ \^|_   _^|  __ \     /\     \ \   / /
echo  ^| ^|__  ^| ^|__) ^| ^| ^| ^| ^|  ^| ^|   /  \     \ \_/ / 
echo  ^|  __^| ^|  _  /  ^| ^| ^| ^|  ^| ^|  / /\ \     \   /  
echo  ^| ^|    ^| ^| \ \ _^| ^|_^| ^|__^| ^| / ____ \     ^| ^|   
echo  ^|_^|    ^|_^|  \_\_____^|_____/ /_/    \_\    ^|_^|   
echo.
echo                 TACTICAL AI ASSISTANT -- MADE BY GAURAV
echo ==========================================================================
echo.
echo Initializing FRIDAY Tactical Core...

set FRIDAY_SKIP_UPDATE=1
set BRAHMA_SKIP_UPDATE=1

set "PY_BIN="
if exist "%~dp0.venv\Scripts\python.exe" (
  set "PY_BIN=%~dp0.venv\Scripts\python.exe"
) else if exist "%~dp0.build-venv\Scripts\python.exe" (
  set "PY_BIN=%~dp0.build-venv\Scripts\python.exe"
)

if defined PY_BIN (
  echo Launching FRIDAY AI via !PY_BIN!...
  "!PY_BIN!" "%~dp0main.py" %*
) else (
  echo Virtual environment not found. Running initial setup...
  powershell.exe -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1"
)

if %errorlevel% neq 0 (
  echo.
  echo FRIDAY process terminated.
  pause
  exit /b %errorlevel%
)
exit /b 0
