@echo off
rem ClockDash one-click build script (ASCII only: cmd.exe misreads UTF-8 batch files).
rem Source lives on a non-ASCII path, AGP refuses to build there, so we mirror the
rem project to an ASCII path, build, and copy the APK back next to this script.
setlocal
set SCRIPT_DIR=%~dp0
set BUILD_ROOT=C:\Users\admin\clockdash-build
set SRC=%SCRIPT_DIR%android
set DST=%BUILD_ROOT%\project
set JAVA_HOME=%BUILD_ROOT%\jdk-17.0.2
set GRADLE=%BUILD_ROOT%\gradle-8.7\bin\gradle.bat

echo [1/3] Sync source to %DST% ...
robocopy "%SRC%" "%DST%" /MIR /XD build .gradle >nul
if errorlevel 8 ( echo Sync failed & exit /b 1 )

echo [2/3] Building release APK (first run downloads dependencies) ...
pushd "%DST%"
call "%GRADLE%" assembleRelease --no-daemon
if errorlevel 1 ( popd & echo Build FAILED & exit /b 1 )
popd

echo [3/3] Copy APK back ...
if not exist "%SCRIPT_DIR%dist" mkdir "%SCRIPT_DIR%dist"
copy /y "%DST%\app\build\outputs\apk\release\app-release.apk" "%SCRIPT_DIR%dist\ClockDash-latest.apk" >nul
del /q "%SCRIPT_DIR%dist\ClockDash-v1.0.apk" "%SCRIPT_DIR%dist\ClockDash-v1.5.apk" 2>nul
echo.
echo DONE: %SCRIPT_DIR%dist\ClockDash-latest.apk (version inside app)
endlocal
