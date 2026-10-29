@echo off
cd /d "%~dp0"
where python >nul 2>&1
if errorlevel 1 (
  echo Install Python 3.10+ from python.org first.
  pause
  exit /b 1
)
python agent.py
if errorlevel 1 pause
