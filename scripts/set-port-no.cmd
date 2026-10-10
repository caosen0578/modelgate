@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem Change this value to permanently configure a different port for this user.
set "MODELGATE_PORT_VALUE=8080"
set "MODELGATE_SCRIPT_EXIT_CODE=0"

rem User scope does not require administrator rights. Do not change Machine scope.
powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; try { $value = $env:MODELGATE_PORT_VALUE; if ($value -notmatch '^[0-9]+$') { throw 'Port must be an integer from 1 to 65535.' }; $port = [int]$value; if ($port -lt 1 -or $port -gt 65535) { throw 'Port must be an integer from 1 to 65535.' }; [Environment]::SetEnvironmentVariable('PORT_NO', $port.ToString(), 'User') } catch { Write-Error $_; exit 1 }"
if errorlevel 1 goto failed

echo [OK] Saved user environment variable: PORT_NO=%MODELGATE_PORT_VALUE%
echo Open a NEW terminal before starting Java. Existing terminals/apps keep their old environment.
echo To undo, run delete-port-no.cmd and then open a new terminal.
goto finish

:failed
set "MODELGATE_SCRIPT_EXIT_CODE=1"
echo [ERROR] Failed to set PORT_NO. See the error message above.

:finish
echo.
if /i not "%~1"=="--no-pause" pause
exit /b %MODELGATE_SCRIPT_EXIT_CODE%
