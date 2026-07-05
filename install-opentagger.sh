#!/bin/bash
# ============================================================
# OpenTagger — Installation clic-droit Linux
# Lance : bash install-opentagger.sh
# ============================================================

set -e

JAR_SRC="$(dirname "$(realpath "$0")")/opentagger/target/opentagger-0.9.0.jar"
INSTALL_DIR="$HOME/.local/share/opentagger"
BIN_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
ICON_DIR="$HOME/.local/share/icons/hicolor/128x128/apps"

echo "=== Installation OpenTagger 0.9.0 ==="

# 1. Copier le JAR
mkdir -p "$INSTALL_DIR"
cp "$JAR_SRC" "$INSTALL_DIR/opentagger.jar"
echo "✓ JAR installé → $INSTALL_DIR/opentagger.jar"

# 2. Script de lancement
mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/opentagger" << 'EOF'
#!/bin/bash
exec java -jar "$HOME/.local/share/opentagger/opentagger.jar" "$@"
EOF
chmod +x "$BIN_DIR/opentagger"
echo "✓ Lanceur → $BIN_DIR/opentagger"

# 3. Icône (utilise une icône audio générique si pas d'icône custom)
mkdir -p "$ICON_DIR"
ICON_PATH="$ICON_DIR/opentagger.png"
if [ ! -f "$ICON_PATH" ]; then
    # Copie l'icône générique audio du système comme fallback
    SYSTEM_ICON=$(find /usr/share/icons -name "audio-x-generic.png" 2>/dev/null | head -1)
    if [ -n "$SYSTEM_ICON" ]; then
        cp "$SYSTEM_ICON" "$ICON_PATH"
    fi
fi

# 4. Fichier .desktop
mkdir -p "$DESKTOP_DIR"
cat > "$DESKTOP_DIR/opentagger.desktop" << EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=OpenTagger
GenericName=Tagueur Audio
Comment=Identifier et taguer les fichiers audio automatiquement
Exec=$BIN_DIR/opentagger %F
Icon=opentagger
MimeType=audio/mpeg;audio/flac;audio/mp4;audio/x-m4a;audio/ogg;audio/opus;audio/x-wav;audio/x-aiff;inode/directory;
Categories=Audio;Music;
Terminal=false
StartupNotify=true
Keywords=music;tag;audio;mp3;flac;musicbrainz;
EOF
echo "✓ Fichier .desktop → $DESKTOP_DIR/opentagger.desktop"

# 5. Mettre à jour la base de données MIME + applications
# NOTE : ne PAS faire "xdg-mime default ... inode/directory" ici — ça remplacerait le
# gestionnaire de fichiers par défaut de TOUT le système par OpenTagger (plus moyen
# d'ouvrir un dossier normalement, y compris depuis OpenTagger lui-même via "Ouvrir le
# dossier parent", qui se relance alors en boucle). Le MimeType déclaré dans le .desktop
# suffit à faire apparaître OpenTagger dans le menu "Ouvrir avec" pour un dossier — sans
# jamais forcer ce choix par défaut.
update-desktop-database "$DESKTOP_DIR" 2>/dev/null && echo "✓ Base applications mise à jour" || true

echo ""
echo "=== Installation terminée ==="
echo "Clic-droit sur un fichier audio ou dossier → Ouvrir avec → OpenTagger"
echo ""
echo "Note : si '$BIN_DIR' n'est pas dans votre PATH, ajoutez cette ligne dans ~/.bashrc :"
echo "  export PATH=\"\$HOME/.local/bin:\$PATH\""
