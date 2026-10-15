@echo off
setlocal
set "DIR=%~dp0"
java -jar "%DIR%..\lib\OwIDE.jar" %*
