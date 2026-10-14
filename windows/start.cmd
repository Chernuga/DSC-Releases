@echo off
REM Launch the app.
REM
REM ELECTRON_RUN_AS_NODE makes electron.exe behave as the plain Node
REM interpreter, so require('electron') yields no `app` object and the app dies
REM immediately with "Cannot read properties of undefined". Some environments
REM set it globally; clearing it here means a launch can never inherit it.

setlocal
set ELECTRON_RUN_AS_NODE=
cd /d "%~dp0"

if not exist "node_modules\electron\dist\electron.exe" (
  echo Electron is not installed. Run:  npm install
  pause
  exit /b 1
)

start "" "node_modules\electron\dist\electron.exe" .
endlocal
