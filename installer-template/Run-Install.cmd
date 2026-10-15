@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0Install-OwIDE.ps1"
exit /b %errorlevel%
