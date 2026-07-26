#!/bin/bash
# ============================================================
# OpenTagger — Installation clic-droit Linux
# Lance : bash install-opentagger.sh
# ============================================================

set -e

# opentagger.jar (sans suffixe de version) est le jar "fat" avec toutes les dépendances
# embarquées — celui qui se lance réellement. opentagger-X.Y.Z.jar (le jar "nu" généré par
# Maven) n'a ni Main-Class ni dépendances et ne s'exécute pas ; référencer un nom versionné ici
# se périmait de toute façon à chaque release (encore "0.9.0" alors que le pom.xml était déjà
# passé à 0.9.1, puis 0.9.3).
JAR_SRC="$(dirname "$(realpath "$0")")/opentagger/target/opentagger.jar"
INSTALL_DIR="$HOME/.local/share/opentagger"
BIN_DIR="$HOME/.local/bin"
DESKTOP_DIR="$HOME/.local/share/applications"
ICON_BASE="$HOME/.local/share/icons/hicolor"
LOGO_SRC="$(dirname "$(realpath "$0")")/logo.png"
LOGO48_SRC="$(dirname "$(realpath "$0")")/logo48.png"

echo "=== Installation OpenTagger ==="

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

# 3. Icône — le vrai logo OpenTagger (logo.png/logo48.png, à côté de ce script), pas une icône
# système générique. Avant ce correctif : repli systématique sur audio-x-generic.png (une simple
# note de musique rose, sans rapport avec l'identité de l'appli), ET seulement si le fichier
# n'existait pas déjà — une fois ce générique copié une première fois, il restait bloqué en place
# pour toujours, aucune réinstallation ultérieure ne le remplaçait par le vrai logo. Copié à CHAQUE
# install (comme le JAR et le .desktop juste au-dessus), dans les deux tailles disponibles pour un
# rendu net selon le contexte (menu, barre des tâches, alt-tab...).
if [ -f "$LOGO_SRC" ]; then
    mkdir -p "$ICON_BASE/256x256/apps"
    cp "$LOGO_SRC" "$ICON_BASE/256x256/apps/opentagger.png"
    if [ -f "$LOGO48_SRC" ]; then
        mkdir -p "$ICON_BASE/48x48/apps"
        cp "$LOGO48_SRC" "$ICON_BASE/48x48/apps/opentagger.png"
    fi
    # 128×128 : taille la plus demandée par les gestionnaires de fichiers/menus (GNOME, Cinnamon).
    # Généré depuis logo.png si ImageMagick est dispo, sinon copie brute du 256×256 (mise à
    # l'échelle un peu grossière côté affichage, mais toujours le vrai logo — jamais le générique).
    mkdir -p "$ICON_BASE/128x128/apps"
    if command -v convert >/dev/null 2>&1; then
        convert "$LOGO_SRC" -resize 128x128 "$ICON_BASE/128x128/apps/opentagger.png"
    else
        cp "$LOGO_SRC" "$ICON_BASE/128x128/apps/opentagger.png"
    fi
    # gtk-update-icon-cache EXIGE un index.theme dans le dossier ciblé ("No theme index file.",
    # exit 1) — absent par défaut de ~/.local/share/icons/hicolor (seul le thème SYSTÈME,
    # /usr/share/icons/hicolor, en a un). Sans lui, la régénération du cache échouait silencieusement
    # à chaque install (erreur avalée par le "|| true" précédent) : le fichier PNG était bien copié
    # au bon endroit, mais l'environnement de bureau continuait à afficher l'ancienne icône (ou
    # rien) depuis son cache jamais invalidé. Contenu minimal standard (mêmes tailles que celles
    # installées ci-dessus) — c'est le repli documenté par la spec XDG Icon Theme pour un thème
    # "hicolor" local qui ne fait qu'ajouter des icônes au thème système du même nom.
    if [ ! -f "$ICON_BASE/index.theme" ]; then
        cat > "$ICON_BASE/index.theme" << 'EOF'
[Icon Theme]
Name=Hicolor
Comment=Fallback icon theme
Hidden=true
Directories=48x48/apps,128x128/apps,256x256/apps

[48x48/apps]
Size=48
Context=Applications
Type=Fixed

[128x128/apps]
Size=128
Context=Applications
Type=Fixed

[256x256/apps]
Size=256
Context=Applications
Type=Fixed
EOF
    fi
    command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f -q "$ICON_BASE" && echo "✓ Cache d'icônes régénéré"
    echo "✓ Icône installée → $ICON_BASE/{256x256,128x128,48x48}/apps/opentagger.png"
else
    echo "⚠ logo.png introuvable à côté de ce script — icône non installée."
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
Categories=AudioVideo;Audio;Music;
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
