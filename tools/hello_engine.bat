@echo off
rem Зачем: запуск заглушки движка hello_engine.py из Windows.
set "SDK=%~dp0..\.."
set "PY=%SDK%\lib\windows-x86_64\python.exe"
if not exist "%PY%" set "PY=%SDK%\lib\windows-i686\python.exe"
if not exist "%PY%" set "PY=python"
"%PY%" "%~dp0hello_engine.py"
