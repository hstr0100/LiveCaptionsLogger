@echo off

cd /d "%~dp0"

echo Running: .\gradlew clean build
.\gradlew clean build

IF %ERRORLEVEL% NEQ 0 (
    echo Gradle command failed. Exiting...
    exit /b %ERRORLEVEL%
)

echo Build and packaging completed successfully.
