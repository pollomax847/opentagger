#!/usr/bin/env bash
# ============================================================
# Génère un paquet .deb OpenTagger via jpackage (JDK 21+, déjà
# utilisé par le projet — jpackage est inclus depuis le JDK 14).
#
# Ce que le .deb apporte que install-opentagger.sh n'a pas :
#   - JRE embarqué → aucune dépendance Java côté utilisateur.
#   - ffmpeg déclaré comme dépendance apt → "apt install ./xxx.deb"
#     (ou dpkg -i + apt --fix-broken install) l'installe tout seul.
#
# fpcalc (chromaprint) et SongRec restent volontairement HORS des
# dépendances .deb :
#   - fpcalc a déjà un bouton d'auto-installation dans l'appli
#     (Préférences → Audio), pas besoin de dépendance système.
#   - SongRec ne vient que d'une PPA tierce (ppa:marin-m/songrec) —
#     le déclarer en dépendance dure casserait "apt install" sur
#     toute machine sans cette PPA déjà ajoutée.
# Les deux restent des prérequis "optionnels" documentés dans le
# README, exactement comme aujourd'hui.
#
# Usage : ./package-deb.sh
# Résultat : opentagger/target/dist/opentagger_<version>_amd64.deb
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$SCRIPT_DIR/opentagger"

if ! command -v jpackage >/dev/null 2>&1; then
    echo "✗ jpackage introuvable — nécessite un JDK 14+ (jpackage est inclus dans le JDK 21 utilisé ici)."
    exit 1
fi

VERSION=$(grep -m1 '<version>' "$PROJECT/pom.xml" | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
echo "=== Paquet .deb OpenTagger $VERSION ==="

echo "→ Build (mvn clean package)…"
(cd "$PROJECT" && mvn -q clean package -DskipTests)

JAR="$PROJECT/target/opentagger.jar"
if [ ! -f "$JAR" ]; then
    echo "✗ JAR introuvable après le build : $JAR"
    exit 1
fi

# jpackage veut un dossier d'entrée propre : juste le jar à embarquer, pas tout target/
# (qui contient aussi le jar "nu" sans dépendances, généré par Maven).
STAGING="$PROJECT/target/jpackage-input"
rm -rf "$STAGING"
mkdir -p "$STAGING"
cp "$JAR" "$STAGING/opentagger.jar"

DIST="$PROJECT/target/dist"
rm -rf "$DIST"
mkdir -p "$DIST"

# --add-modules ALL-MODULE-PATH : embarque TOUS les modules JDK plutôt que de laisser jlink
# essayer de deviner le sous-ensemble minimal nécessaire par analyse statique (jdeps) — l'appli
# n'étant pas modularisée (classpath classique, pas de module-info.java) et utilisant des
# mécanismes dynamiques (réflexion Jackson, scripting Nashorn, JDBC SQLite), cette analyse
# automatique risque de rater un module réellement utilisé à l'exécution mais invisible à
# l'analyse statique — un paquet plus gros (JRE complet) est un compromis largement préférable
# à un ClassNotFoundException chez un utilisateur sur une fonctionnalité précise.
jpackage \
    --type deb \
    --input "$STAGING" \
    --main-jar opentagger.jar \
    --name OpenTagger \
    --app-version "$VERSION" \
    --vendor "pollomax847" \
    --icon "$SCRIPT_DIR/logo.png" \
    --dest "$DIST" \
    --add-modules ALL-MODULE-PATH \
    --linux-package-name opentagger \
    --linux-deb-maintainer "pollomax847@users.noreply.github.com" \
    --linux-menu-group "Audio;Music" \
    --linux-shortcut \
    --linux-package-deps "ffmpeg"

DEB="$(ls "$DIST"/*.deb 2>/dev/null | head -1)"
echo
echo "✓ Paquet généré : $DEB"
echo "  Installation : sudo apt install ./$(basename "$DEB")"
echo
echo "  fpcalc (chromaprint) et SongRec restent optionnels, non inclus dans les"
echo "  dépendances .deb — voir README (fpcalc a un bouton d'auto-installation dans"
echo "  l'appli, SongRec vient d'une PPA tierce, pas des dépôts officiels)."
