@echo off
rem Instala (ou atualiza) o Plumb como servico do Windows e cria o comando "plumb".
rem Parametros opcionais: -Porta 8090  -Testes
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\plumb.ps1" instalar %*
exit /b %ERRORLEVEL%
