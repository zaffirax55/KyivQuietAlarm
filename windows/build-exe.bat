@echo off
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller
python -m PyInstaller --clean --noconfirm --onefile --windowed --name PC-Control-Windows agent.py
pause
