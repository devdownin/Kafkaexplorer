@echo off
rem SPDX-License-Identifier: AGPL-3.0-or-later
rem Copyright (C) 2026 Kafka Explorer Contributors
rem
rem Runs demo.ps1 past Windows 11's default execution policy, which refuses unsigned scripts.
rem Bypass applies to this one process only; nothing on the machine is reconfigured.
rem   demo.cmd [-NoSpectra] [up^|down^|reset^|ps^|logs [service]^|probe]
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0demo.ps1" %*
exit /b %ERRORLEVEL%
