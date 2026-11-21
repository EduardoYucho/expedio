@echo off
rem Comando "plumb": repassa os parametros para o plumb.ps1 desta pasta.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ponto.ps1" %*
exit /b %ERRORLEVEL%
