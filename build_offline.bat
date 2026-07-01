@echo off
REM Offline build (no downloads)
cd /d %~dp0
mvn -o package
pause
