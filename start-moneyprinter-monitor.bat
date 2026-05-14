@echo off
setlocal EnableExtensions DisableDelayedExpansion
title MoneyPrinter Discord Monitor

set "PROJECT_DIR=%~dp0"
set "MONITOR_DIR=%PROJECT_DIR%discord-monitor"
set "ONECLICK_CONFIG=%MONITOR_DIR%\config.oneclick.yml"
set "VENV_PY=%MONITOR_DIR%\.venv\Scripts\python.exe"
set "CDP_PORT=9222"
set "DISCORD_GUILD_IDS=1004707886657699901"
set "DISCORD_CHANNEL_IDS=1271151178905817129"
set "DISCORD_URL=https://discord.com/channels/%DISCORD_GUILD_IDS%/%DISCORD_CHANNEL_IDS%"
set "MULTI_USER_ENABLED=true"
set "GRPC_ENABLED=false"
set "PYTHONUTF8=1"
set "PYTHONIOENCODING=utf-8"

cd /d "%PROJECT_DIR%" || exit /b 1

echo [MoneyPrinter] Loading local .env values...
if exist "%PROJECT_DIR%.env" (
  for /f "usebackq tokens=1,* delims==" %%A in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Get-Content -LiteralPath '%PROJECT_DIR%.env' | ForEach-Object { $line=$_.Trim(); if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) { $idx=$line.IndexOf('='); $k=$line.Substring(0,$idx).Trim(); $v=$line.Substring($idx+1).Trim(); if (($v.StartsWith([char]34) -and $v.EndsWith([char]34)) -or ($v.StartsWith([char]39) -and $v.EndsWith([char]39))) { $v=$v.Substring(1,$v.Length-2) }; if ($k -match '^[A-Za-z_][A-Za-z0-9_]*$') { Write-Output ($k + '=' + $v) } } }"`) do (
    set "%%A=%%B"
  )
) else (
  echo [MoneyPrinter] WARNING: .env not found at "%PROJECT_DIR%.env"
)

rem Force the live channel and runtime mode after reading .env.
set "DISCORD_GUILD_IDS=1004707886657699901"
set "DISCORD_CHANNEL_IDS=1271151178905817129"
set "DISCORD_URL=https://discord.com/channels/%DISCORD_GUILD_IDS%/%DISCORD_CHANNEL_IDS%"
set "MULTI_USER_ENABLED=true"
set "GRPC_ENABLED=false"

if "%GEMINI_API_KEY%"=="" (
  echo [MoneyPrinter] ERROR: GEMINI_API_KEY is not set. Put it in .env or set it in Windows env.
  pause
  exit /b 1
)

if "%MONITOR_API_KEY%"=="" (
  echo [MoneyPrinter] ERROR: MONITOR_API_KEY is not set. Put it in .env or set it in Windows env.
  pause
  exit /b 1
)

echo [MoneyPrinter] Writing one-click monitor config...
if not exist "%MONITOR_DIR%\run-logs" mkdir "%MONITOR_DIR%\run-logs" >nul 2>nul
(
  echo cdp:
  echo   host: "127.0.0.1"
  echo   port: 9222
  echo   reconnect_interval: 5
  echo   max_reconnect_attempts: 0
  echo.
  echo discord:
  echo   channel_ids:
  echo     - "1271151178905817129"
  echo   guild_ids:
  echo     - "1004707886657699901"
  echo   author_ids: []
  echo.
  echo api:
  echo   base_url: "https://hook-fi.com"
  echo   execute_endpoint: "/api/execute-signal"
  echo   parse_endpoint: "/api/parse-signal"
  echo   timeout: 10
  echo   dry_run: false
  echo   multi_user_enabled: true
  echo.
  echo ai:
  echo   enabled: true
  echo   model: "gemini-2.0-flash"
  echo   api_key_env: "GEMINI_API_KEY"
  echo   timeout: 15
  echo   max_retries: 3
  echo   retry_delays: [2, 5, 10]
  echo.
  echo image_signal:
  echo   enabled: true
  echo   dry_run: false
  echo   allowed_symbols:
  echo     - "BTCUSDT"
  echo   max_image_bytes: 5242880
  echo.
  echo logging:
  echo   level: "INFO"
  echo   file: "run-logs/monitor-oneclick.log"
  echo.
  echo grpc:
  echo   enabled: false
  echo   target: "hook-fi.com:9443"
  echo   use_tls: true
  echo   reconnect_interval: 5
) > "%ONECLICK_CONFIG%"
if not exist "%ONECLICK_CONFIG%" (
  echo [MoneyPrinter] ERROR: failed to write config.
  pause
  exit /b 1
)

echo [MoneyPrinter] Preparing Python environment...
set "PY_BOOT=python"
where python >nul 2>nul
if errorlevel 1 set "PY_BOOT=py -3"

%PY_BOOT% --version >nul 2>nul
if errorlevel 1 (
  echo [MoneyPrinter] ERROR: Python was not found.
  pause
  exit /b 1
)

if not exist "%VENV_PY%" (
  echo [MoneyPrinter] Creating local venv...
  %PY_BOOT% -m venv "%MONITOR_DIR%\.venv"
  if errorlevel 1 (
    echo [MoneyPrinter] ERROR: failed to create venv.
    pause
    exit /b 1
  )
)

pushd "%MONITOR_DIR%" || exit /b 1
"%VENV_PY%" -m pip install --disable-pip-version-check -q -r requirements.txt
if errorlevel 1 (
  echo [MoneyPrinter] ERROR: pip install failed.
  popd
  pause
  exit /b 1
)

echo [MoneyPrinter] Validating monitor config...
"%VENV_PY%" -c "from src.config import load_config; c=load_config(r'%ONECLICK_CONFIG%'); assert c.discord.channel_ids == ['1271151178905817129']; assert c.discord.guild_ids == ['1004707886657699901']; assert c.api.dry_run is False; assert c.api.multi_user_enabled is True; assert c.image_signal.enabled is True; assert c.image_signal.dry_run is False; print('config ok: channel=' + c.discord.channel_ids[0] + ' guild=' + c.discord.guild_ids[0])"
if errorlevel 1 (
  echo [MoneyPrinter] ERROR: config validation failed.
  popd
  pause
  exit /b 1
)

if /I "%~1"=="--check" (
  echo [MoneyPrinter] Check mode complete. Monitor was not started.
  popd
  exit /b 0
)

echo [MoneyPrinter] Stopping any existing Discord monitor process...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$me=$PID; Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $me -and $_.CommandLine -like '*python*src.main*--config*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; Write-Host ('stopped monitor PID ' + $_.ProcessId) }"

echo [MoneyPrinter] Opening Discord in Chrome CDP...
set "CHROME_EXE=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME_EXE%" set "CHROME_EXE=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME_EXE%" (
  echo [MoneyPrinter] ERROR: Chrome was not found.
  popd
  pause
  exit /b 1
)

set "CDP_PROFILE=%LOCALAPPDATA%\MoneyPrinter\Chrome-CDP-Profile"
if not exist "%CDP_PROFILE%" mkdir "%CDP_PROFILE%" >nul 2>nul

powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-RestMethod -Uri 'http://127.0.0.1:%CDP_PORT%/json/version' -TimeoutSec 1 | Out-Null; exit 0 } catch { exit 1 }"
if errorlevel 1 (
  start "MoneyPrinter Chrome CDP" "%CHROME_EXE%" --remote-debugging-port=%CDP_PORT% --user-data-dir="%CDP_PROFILE%" --no-first-run --disable-default-apps "%DISCORD_URL%"
)

for /L %%I in (1,1,20) do (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-RestMethod -Uri 'http://127.0.0.1:%CDP_PORT%/json/version' -TimeoutSec 1 | Out-Null; exit 0 } catch { exit 1 }"
  if not errorlevel 1 goto CDP_READY
  timeout /t 1 /nobreak >nul
)

echo [MoneyPrinter] ERROR: Chrome CDP did not become ready on port %CDP_PORT%.
popd
pause
exit /b 1

:CDP_READY
echo [MoneyPrinter] Starting monitor. Close this window to stop it.
echo [MoneyPrinter] Discord URL: %DISCORD_URL%
"%VENV_PY%" -m src.main --config "%ONECLICK_CONFIG%"
set "EXITCODE=%ERRORLEVEL%"
echo [MoneyPrinter] Monitor stopped with exit code %EXITCODE%.
popd
pause
exit /b %EXITCODE%
