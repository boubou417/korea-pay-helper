@echo off
setlocal
cd /d "%~dp0"

echo ==================================================
echo Pay Helper - Android preparation
echo ==================================================

where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js was not found in PATH.
  echo Install Node.js LTS, reopen this window, then run this file again.
  pause
  exit /b 1
)

where npm >nul 2>nul
if errorlevel 1 (
  echo [ERROR] npm was not found in PATH.
  pause
  exit /b 1
)

echo.
echo [1/3] Installing exact npm dependencies from package-lock.json...
call npm ci
if errorlevel 1 goto :failed

echo.
echo [2/3] Building Pay Helper web assets...
call npm run build
if errorlevel 1 goto :failed

echo.
echo [3/3] Syncing Capacitor Android project...
call npx cap sync android
if errorlevel 1 goto :failed

echo.
echo ==================================================
echo Android preparation completed successfully.
echo You can now open the android folder in Android Studio
echo and run Sync Project with Gradle Files / Build APK.
echo ==================================================
pause
exit /b 0

:failed
echo.
echo [ERROR] Android preparation failed. See the command output above.
pause
exit /b 1
