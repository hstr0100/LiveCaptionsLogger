@echo off
setlocal

rem Get and navigate to the directory where this batch file resides
set "batchDir=%~dp0"

cd /d "%batchDir%"

rem Run the JAR file using its relative path to this batch file
rem You might see a console window open up every time this program is started
start "LiveCaptionsLogger" javaw -Xmx256M -jar target\LiveCaptionsLogger.jar

endlocal
