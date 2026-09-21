@echo off
setlocal
set PROJECT_DIR=%~dp0
set BUILD_DIR=%PROJECT_DIR%build
if not exist "%BUILD_DIR%\classes" mkdir "%BUILD_DIR%\classes"
dir /s /b "%PROJECT_DIR%src\main\java\*.java" > "%BUILD_DIR%\main-sources.txt"
javac --release 17 -encoding UTF-8 -d "%BUILD_DIR%\classes" @"%BUILD_DIR%\main-sources.txt" || exit /b 1
xcopy /e /i /y "%PROJECT_DIR%src\main\resources" "%BUILD_DIR%\classes" >nul
java -cp "%BUILD_DIR%\classes" dev.fretflow.Main %*

