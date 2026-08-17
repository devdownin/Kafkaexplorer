@echo off
setlocal enabledelayedexpansion
REM ============================================================
REM Kafka SQL Explorer - build script (Windows)
REM Compiles the React/Vite frontend and the Spring Boot backend.
REM Usage:
REM   build.bat            -> full build (frontend + backend), runs tests
REM   build.bat skiptests   -> full build, skips backend tests
REM
REM Requires a JDK 25, Maven and Node.js on the machine. If you would rather not
REM install them, docker-compose-build.yml runs the same build in containers:
REM   docker compose -f docker-compose-build.yml run --rm package
REM ============================================================

set "ROOT_DIR=%~dp0"
set "WEBAPP_DIR=%ROOT_DIR%src\main\webapp"
set "SKIP_TESTS="

if /I "%~1"=="skiptests" set "SKIP_TESTS=-DskipTests"

echo ============================================================
echo  [1/3] Checking prerequisites
echo ============================================================

REM All three are checked before bailing out, so a machine with none of them is
REM told once what it is missing instead of being sent to install Java, run again,
REM be sent to install Maven, and so on.
set "MISSING="

where java >nul 2>nul
if errorlevel 1 set "MISSING=!MISSING! java (JDK 25)"

where mvn >nul 2>nul
if errorlevel 1 set "MISSING=!MISSING! mvn (Maven)"

where npm >nul 2>nul
if errorlevel 1 set "MISSING=!MISSING! npm (Node.js)"

if not "!MISSING!"=="" (
    echo ERROR: not found in PATH:!MISSING!
    echo.
    echo You do not have to install them. Docker alone can build this project:
    echo.
    echo    docker compose -f docker-compose-build.yml run --rm package
    echo        builds the JAR into .\target
    echo.
    echo    docker compose -f docker-compose-build.yml run --rm verify
    echo        runs the full gate: Java tests, frontend ESLint and Vitest
    echo.
    echo Otherwise install what is listed above and run this script again.
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
