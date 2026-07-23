@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM Kafka SQL Explorer - build script (Windows)
REM Compiles the React/Vite frontend and the Spring Boot backend.
REM Usage:
REM   build.bat            -> full build (frontend + backend), runs tests
REM   build.bat skiptests   -> full build, skips backend tests
REM ============================================================

set "ROOT_DIR=%~dp0"
set "WEBAPP_DIR=%ROOT_DIR%src\main\webapp"
set "SKIP_TESTS="

if /I "%~1"=="skiptests" set "SKIP_TESTS=-DskipTests"

echo ============================================================
echo  [1/3] Checking prerequisites
echo ============================================================

where java >nul 2>nul
if errorlevel 1 (
    echo ERROR: java not found in PATH. Java 21 is required.
    exit /b 1
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo ERROR: mvn not found in PATH. Maven is required.
    exit /b 1
)

where npm >nul 2>nul
if errorlevel 1 (
    echo ERROR: npm not found in PATH. Node.js/npm is required.
    exit /b 1
)

echo ============================================================
echo  [2/3] Building frontend (React / Vite)
echo ============================================================

pushd "%WEBAPP_DIR%"
call npm install
if errorlevel 1 (
    echo ERROR: npm install failed.
    popd
    exit /b 1
)

call npm run build
if errorlevel 1 (
    echo ERROR: npm run build failed.
    popd
    exit /b 1
)
popd

echo ============================================================
echo  [3/3] Building backend (Maven / Spring Boot)
echo ============================================================
REM NOTE: "mvn package" also invokes the frontend-maven-plugin, so the
REM frontend gets rebuilt as part of this step (see CLAUDE.md). This
REM guarantees src\main\resources\static ends up in sync with the JAR
REM even though the frontend was already built above for visibility.

call mvn clean package %SKIP_TESTS%
if errorlevel 1 (
    echo ERROR: Maven build failed.
    exit /b 1
)

echo ============================================================
echo  Build complete. JAR available under target\
echo ============================================================

endlocal
