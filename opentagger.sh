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

LOG_DIR="$HOME/.local/share/opentagger"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/opentagger.log"
# Rotation simple : garder au plus 2 Mo
if [ -f "$LOG" ] && [ "$(stat -c%s "$LOG" 2>/dev/null || echo 0)" -gt 2097152 ]; then
    mv "$LOG" "${LOG}.old"
fi

java -Xms256m -Xmx3g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:SoftRefLRUPolicyMSPerMB=1 \
     -Dawt.useSystemAAFontSettings=on \
     -Dswing.aatext=true \
     -jar "$JAR" "$@" 2>&1 | tee -a "$LOG"
