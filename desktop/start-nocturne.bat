@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-nocturne.ps1"
if errorlevel 1 (
  echo.
  echo NOCTURNE did not start. Read the message above, then try again.
  pause
)
