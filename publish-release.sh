#!/usr/bin/env bash
# ============================================================
# Publie une release OpenTagger sur le dépôt binaires séparé
# (pollomax847/opentagger-releases) — le dépôt source n'est
# jamais touché par ce script.
#
# Usage : ./publish-release.sh ["notes de version"]
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASES_REPO="pollomax847/opentagger-releases"

VERSION=$(grep -m1 '<version>' "$SCRIPT_DIR/opentagger/pom.xml" | sed -E 's/.*<version>(.*)<\/version>.*/\1/')
TAG="v$VERSION"
NOTES="${1:-Version $VERSION}"

echo "=== Publication OpenTagger $TAG ==="

echo "→ Build (mvn clean package)…"
(cd "$SCRIPT_DIR/opentagger" && mvn -q clean package -DskipTests)

JAR="$SCRIPT_DIR/opentagger/target/opentagger.jar"
if [ ! -f "$JAR" ]; then
    echo "✗ JAR introuvable après le build : $JAR"
    exit 1
fi

echo "→ Vérification : cette version n'est pas déjà publiée…"
if gh release view "$TAG" --repo "$RELEASES_REPO" >/dev/null 2>&1; then
    echo "✗ La release $TAG existe déjà sur $RELEASES_REPO."
    echo "  Bump la version dans pom.xml avant de publier, ou supprime la release existante :"
    echo "  gh release delete $TAG --repo $RELEASES_REPO"
    exit 1
fi

echo "→ Création de la release $TAG sur $RELEASES_REPO…"
gh release create "$TAG" "$JAR" \
    --repo "$RELEASES_REPO" \
    --title "$TAG" \
    --notes "$NOTES"

echo "✓ Publié : https://github.com/$RELEASES_REPO/releases/tag/$TAG"
