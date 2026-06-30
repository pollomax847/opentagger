@echo off
echo === Desinstallation OpenTagger ===
reg delete "HKCU\Software\Classes\*\shell\OpenTagger"                    /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\shell\OpenTagger"            /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\Background\shell\OpenTagger" /f >nul 2>&1
echo OK Entrees registre supprimees
if exist "%APPDATA%\OpenTagger" rmdir /S /Q "%APPDATA%\OpenTagger%"
echo OK Fichiers supprimes
echo === Desinstallation terminee ===
pause
