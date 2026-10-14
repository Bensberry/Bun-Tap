@echo off
rem ---------------------------------------------------------------------------
rem  Builds the BunTap debug APK.
rem
rem  Gradle 9 needs Java 17 or newer. If JAVA_HOME is not set, this script uses
rem  the Java runtime that ships with Android Studio ("jbr").
rem
rem  Output: app\build\outputs\apk\debug\app-debug.apk
rem ---------------------------------------------------------------------------
setlocal

if defined JAVA_HOME goto build

for %%D in (
    "%ProgramFiles%\Android\Android Studio\jbr"
    "%ProgramFiles%\Android\Android Studio1\jbr"
    "%LOCALAPPDATA%\Programs\Android Studio\jbr"
) do (
    if exist "%%~D\bin\java.exe" (
        set "JAVA_HOME=%%~D"
        goto build
    )
)

echo Could not find Java 17+. Install Android Studio or set JAVA_HOME to a JDK 17+ folder.
exit /b 1

:build
echo Using JAVA_HOME=%JAVA_HOME%
call "%~dp0gradlew.bat" assembleDebug
if errorlevel 1 exit /b 1

echo.
echo APK built: %~dp0app\build\outputs\apk\debug\app-debug.apk
endlocal
