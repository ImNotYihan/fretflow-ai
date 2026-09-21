@echo off
setlocal
set PROJECT_DIR=%~dp0
call "%PROJECT_DIR%build.bat" || exit /b 1
java -jar "%PROJECT_DIR%dist\fretflow-ai.jar" %*
