@echo off
rem SPDX-License-Identifier: AGPL-3.0-or-later
rem Copyright (C) 2026 Kafka Explorer Contributors
rem
rem Live forecast demo on Windows with prebuilt images only; requires Docker Desktop.
rem   demo-forecast.cmd [up^|down^|reset^|ps^|logs [service]^|probe]
rem The existing PowerShell launcher generates/reuses local forecast credentials.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0demo.ps1" -Forecast -Prebuilt %*
exit /b %ERRORLEVEL%
