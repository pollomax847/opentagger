@echo off
:: ============================================================
:: OpenTagger 0.9.0 — Installation Windows
:: Lance en tant qu'administrateur pour le menu contextuel global
:: OU double-cliquer normalement pour installation utilisateur seul
:: ============================================================

setlocal EnableDelayedExpansion

set "JAR_SRC=%~dp0opentagger\target\opentagger-0.9.0.jar"
set "INSTALL_DIR=%APPDATA%\OpenTagger"
set "JAR_DEST=%INSTALL_DIR%\opentagger.jar"
set "BAT_DEST=%INSTALL_DIR%\opentagger.bat"

echo === Installation OpenTagger 0.9.0 ===

:: 1. Verifier que Java est disponible
java -version >nul 2>&1
if errorlevel 1 (
    echo ERREUR : Java n'est pas installe ou pas dans le PATH.
    echo Telecharger Java 21+ sur https://adoptium.net
    pause
    exit /b 1
)

:: 2. Copier le JAR
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"
copy /Y "%JAR_SRC%" "%JAR_DEST%" >nul
echo OK JAR installe : %JAR_DEST%

:: 3. Creer le script de lancement
(
    echo @echo off
    echo start javaw -jar "%JAR_DEST%" %%*
) > "%BAT_DEST%"
echo OK Lanceur : %BAT_DEST%

:: 4. Clic-droit sur fichiers audio (* = tous fichiers)
set "REG_FILES=HKCU\Software\Classes\*\shell\OpenTagger"
reg add "%REG_FILES%"          /ve /d "Ouvrir avec OpenTagger" /f >nul
reg add "%REG_FILES%"          /v "Icon" /d "%JAR_DEST%,0"    /f >nul
reg add "%REG_FILES%\command"  /ve /d "javaw -jar \"%JAR_DEST%\" \"%%1\"" /f >nul
echo OK Clic-droit fichiers audio enregistre

:: 5. Clic-droit sur dossiers
set "REG_DIRS=HKCU\Software\Classes\Directory\shell\OpenTagger"
reg add "%REG_DIRS%"           /ve /d "Ouvrir avec OpenTagger" /f >nul
reg add "%REG_DIRS%"           /v "Icon" /d "%JAR_DEST%,0"    /f >nul
reg add "%REG_DIRS%\command"   /ve /d "javaw -jar \"%JAR_DEST%\" \"%%1\"" /f >nul
echo OK Clic-droit dossiers enregistre

:: 6. Clic-droit fond de dossier (clic sur fond vide dans l'explorateur)
set "REG_BG=HKCU\Software\Classes\Directory\Background\shell\OpenTagger"
reg add "%REG_BG%"             /ve /d "Ouvrir avec OpenTagger" /f >nul
reg add "%REG_BG%"             /v "Icon" /d "%JAR_DEST%,0"    /f >nul
reg add "%REG_BG%\command"     /ve /d "javaw -jar \"%JAR_DEST%\" \"%%V\"" /f >nul
echo OK Clic-droit fond de dossier enregistre

echo.
echo === Installation terminee ===
echo Clic-droit sur un fichier audio ou dossier -^> OpenTagger
echo.
echo Pour desinstaller : lancez uninstall-windows.bat
pause
