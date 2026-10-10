@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "MODELGATE_SCRIPT_EXIT_CODE=0"

rem Delete only the current user's PORT_NO. Machine-level variables are not touched.
powershell.exe -NoProfile -Command "$ErrorActionPreference = 'Stop'; try { [Environment]::SetEnvironmentVariable('PORT_NO', $null, 'User'); if ($null -ne [Environment]::GetEnvironmentVariable('PORT_NO', 'Machine')) { Write-Warning 'A Machine-level PORT_NO still exists. This script does not remove it.' } } catch { Write-Error $_; exit 1 }"
if errorlevel 1 goto failed

echo [OK] Removed user environment variable: PORT_NO
echo Open a NEW terminal before starting Java. Existing terminals/apps keep their old environment.
echo If no Machine-level PORT_NO exists, ModelGate will use server.port from Apollo/application.yml.
goto finish

:failed
set "MODELGATE_SCRIPT_EXIT_CODE=1"
echo [ERROR] Failed to delete PORT_NO. See the error message above.

:finish
echo.
if /i not "%~1"=="--no-pause" pause
exit /b %MODELGATE_SCRIPT_EXIT_CODE%
