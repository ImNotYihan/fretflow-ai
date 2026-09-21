@echo off
setlocal
set PROJECT_DIR=%~dp0
set CLASSES_DIR=%PROJECT_DIR%build\classes
set DIST_DIR=%PROJECT_DIR%dist
set SOURCE_LIST=%PROJECT_DIR%build\main-sources.txt

if exist "%CLASSES_DIR%" rmdir /s /q "%CLASSES_DIR%"
if not exist "%CLASSES_DIR%" mkdir "%CLASSES_DIR%"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
(for /r "%PROJECT_DIR%src\main\java" %%F in (*.java) do @echo "%%F") > "%SOURCE_LIST%"
javac --release 17 -encoding UTF-8 -d "%CLASSES_DIR%" @"%SOURCE_LIST%" || exit /b 1
jar --create --file "%DIST_DIR%\fretflow-ai.jar" --main-class dev.fretflow.Main -C "%CLASSES_DIR%" . || exit /b 1

echo Built %DIST_DIR%\fretflow-ai.jar
