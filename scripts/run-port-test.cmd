@echo off
setlocal EnableExtensions DisableDelayedExpansion

rem Change this line to test a different port. No system/user environment is modified.
set "PORT_NO=8080"

if /i "%~1"=="--help" goto usage
if /i "%~1"=="config" set "PORT_NO="
if not "%~1"=="" if /i not "%~1"=="config" if /i not "%~1"=="--check" goto usage

set "MODELGATE_JAR=%~dp0..\target\modelgate-1.0.0-SNAPSHOT.jar"
set "MODELGATE_JAVA=java"
if defined JAVA_HOME set "MODELGATE_JAVA=%JAVA_HOME%\bin\java.exe"
rem Fallback for the JDK already installed on this development machine.
if not defined JAVA_HOME if exist "C:\Program Files\RedHat\java-17-openjdk-17.0.18.0.8-1\bin\java.exe" set "MODELGATE_JAVA=C:\Program Files\RedHat\java-17-openjdk-17.0.18.0.8-1\bin\java.exe"

if defined PORT_NO (
    echo PORT_NO=%PORT_NO% - overriding the listening port for this run only.
) else (
    echo PORT_NO is unset - using server.port from Apollo/application.yml.
)

if not exist "%MODELGATE_JAR%" (
    echo ERROR: JAR not found: "%MODELGATE_JAR%"
    echo Build the project with Maven first.
    exit /b 1
)
rem Run from the project root so relative log paths stay unchanged.
pushd "%~dp0.."
if errorlevel 1 exit /b 1
rem Logback writes UTF-8. Match the console and Java streams to avoid GBK mojibake.
for /f "tokens=2 delims=:" %%C in ('chcp') do set "MODELGATE_OLD_CODEPAGE=%%C"
chcp 65001 >nul
set "MODELGATE_JVM_ENCODING=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
if /i "%~1"=="--check" goto check
if /i "%~2"=="--check" goto check
"%MODELGATE_JAVA%" %MODELGATE_JVM_ENCODING% -jar "%MODELGATE_JAR%"
set "MODELGATE_EXIT_CODE=%ERRORLEVEL%"
goto cleanup

:check
rem Verify encoding settings without starting ModelGate or connecting to DB/Redis.
"%MODELGATE_JAVA%" %MODELGATE_JVM_ENCODING% -XshowSettings:properties -version 2>&1 | findstr /C:"file.encoding =" /C:"sun.stdout.encoding =" /C:"sun.stderr.encoding ="
set "MODELGATE_EXIT_CODE=%ERRORLEVEL%"

:cleanup
if defined MODELGATE_OLD_CODEPAGE chcp %MODELGATE_OLD_CODEPAGE% >nul
popd
exit /b %MODELGATE_EXIT_CODE%

:usage
echo Usage: run-port-test.cmd [config] [--check]
echo Default: run with PORT_NO=8080. Edit the PORT_NO line to change it.
echo config:  ignore PORT_NO and use the normal server.port configuration.
echo --check: verify the selected mode and UTF-8 settings without starting the application.
echo Environment changes are local to this script and its child Java process.
echo The original console code page is restored on normal exit.
exit /b 0
