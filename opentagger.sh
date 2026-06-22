#!/usr/bin/env bash
# Lance OpenTagger — GUI si aucun argument, CLI sinon
# Usage :
#   ./opentagger.sh                                  → Interface graphique
#   ./opentagger.sh fichier.mp3 [--acoustid]         → Mode fichier unique
#   ./opentagger.sh --dossier /musique [--masque 3]  → Mode dossier

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/opentagger/target/opentagger.jar"

if [ ! -f "$JAR" ]; then
    echo "JAR introuvable : $JAR"
    echo "Construire d'abord avec : cd opentagger && mvn package"
    exit 1
fi

exec java -Xms150m -Xmx600m \
     -Dawt.useSystemAAFontSettings=on \
     -Dswing.aatext=true \
     -jar "$JAR" "$@"
