@echo off
rem SPDX-License-Identifier: AGPL-3.0-or-later
rem Launch the complete local Docker Compose forecast stack on Windows.
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" %*
set "exit_code=%ERRORLEVEL%"
endlocal & exit /b %exit_code%
