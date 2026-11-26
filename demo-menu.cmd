@echo off
rem SPDX-License-Identifier: AGPL-3.0-or-later
rem Copyright (C) 2026 Kafka Explorer Contributors
rem
rem Asks which demo options you want, then runs demo.ps1 with them (twin of demo-menu.sh).
rem Bypass applies to this one process only; nothing on the machine is reconfigured.
rem   demo-menu.cmd [-DryRun]
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0demo-menu.ps1" %*
exit /b %ERRORLEVEL%
