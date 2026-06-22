#!/usr/bin/env bash
# Installe OpenTagger pour l'utilisateur courant
# Après installation : taper 'opentagger' dans un terminal, ou chercher dans les applications

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/opentagger/target/opentagger.jar"

echo "=== OpenTagger — Installation ==="
echo

# 1. Build si besoin
if [ ! -f "$JAR" ]; then
    echo "→ Construction du JAR..."
    mvn -f "$SCRIPT_DIR/opentagger/pom.xml" package -q
    echo "  ✓ Build OK"
fi

# 2. Copier dans ~/bin
mkdir -p "$HOME/bin"
cp "$SCRIPT_DIR/opentagger.sh" "$HOME/bin/opentagger"
chmod +x "$HOME/bin/opentagger"
echo "→ Lanceur installé : ~/bin/opentagger"

# 3. Icône
mkdir -p "$HOME/.local/share/icons/hicolor/256x256/apps"
mkdir -p "$HOME/.local/share/icons/hicolor/48x48/apps"
cp "$SCRIPT_DIR/logo.png"   "$HOME/.local/share/icons/hicolor/256x256/apps/opentagger.png"
cp "$SCRIPT_DIR/logo48.png" "$HOME/.local/share/icons/hicolor/48x48/apps/opentagger.png"
echo "→ Icônes installées"

# 4. Entrée .desktop
mkdir -p "$HOME/.local/share/applications"
# Mettre à jour le chemin du JAR dans le .desktop
JAR_ABS="$(realpath "$JAR")"
sed "s|/home/paulceline/opentagger/opentagger/target/opentagger.jar|$JAR_ABS|g" \
    "$SCRIPT_DIR/opentagger.desktop" \
    > "$HOME/.local/share/applications/opentagger.desktop"
chmod +x "$HOME/.local/share/applications/opentagger.desktop"
update-desktop-database "$HOME/.local/share/applications/" 2>/dev/null || true
gtk-update-icon-cache -f "$HOME/.local/share/icons/hicolor/" 2>/dev/null || true
echo "→ Entrée Applications installée"

# 5. Vérifier ~/bin dans PATH
if ! echo "$PATH" | grep -q "$HOME/bin"; then
    echo
    echo "  ⚠  ~/bin n'est pas dans ton PATH."
    echo "  Ajoute cette ligne à ~/.bashrc ou ~/.zshrc :"
    echo "     export PATH=\"\$HOME/bin:\$PATH\""
fi

echo
echo "✓ Installation terminée !"
echo "  Lance avec : opentagger"
echo "  Ou cherche 'OpenTagger' dans le menu Applications."
