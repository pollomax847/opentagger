#!/usr/bin/env bash
# ============================================================
# Publie une release OpenTagger sur le dépôt binaires séparé
# (pollomax847/opentagger-releases) — le dépôt source n'est
# jamais touché par ce script. Publie aussi le .deb (release
# GitHub + dépôt apt signé servi via GitHub Pages,
# docs/apt/ sur ce même dépôt binaires).
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

echo "→ Vérification : cette version n'est pas déjà publiée…"
if gh release view "$TAG" --repo "$RELEASES_REPO" >/dev/null 2>&1; then
    echo "✗ La release $TAG existe déjà sur $RELEASES_REPO."
    echo "  Bump la version dans pom.xml avant de publier, ou supprime la release existante :"
    echo "  gh release delete $TAG --repo $RELEASES_REPO"
    exit 1
fi

# package-deb.sh fait son propre "mvn clean package" — pas la peine de builder deux fois ici,
# le jar produit (opentagger/target/opentagger.jar) est le même qu'avant.
echo "→ Construction du .jar + du paquet .deb (package-deb.sh)…"
"$SCRIPT_DIR/package-deb.sh"

JAR="$SCRIPT_DIR/opentagger/target/opentagger.jar"
DEB="$(ls "$SCRIPT_DIR"/opentagger/target/dist/*.deb 2>/dev/null | head -1)"
if [ ! -f "$JAR" ] || [ ! -f "$DEB" ]; then
    echo "✗ .jar ou .deb introuvable après package-deb.sh"
    exit 1
fi

echo "→ Construction du dépôt apt signé (build-apt-repo.sh)…"
"$SCRIPT_DIR/build-apt-repo.sh"
APT_REPO="$SCRIPT_DIR/opentagger/target/apt-repo"
if [ ! -f "$APT_REPO/InRelease" ]; then
    echo "✗ Dépôt apt introuvable après build-apt-repo.sh"
    exit 1
fi

echo "→ Création de la release $TAG sur $RELEASES_REPO (jar + deb)…"
gh release create "$TAG" "$JAR" "$DEB" \
    --repo "$RELEASES_REPO" \
    --title "$TAG" \
    --notes "$NOTES"

echo "→ Mise à jour du dépôt apt (docs/apt/) sur $RELEASES_REPO…"
APT_CLONE="$(mktemp -d)"
trap 'rm -rf "$APT_CLONE"' EXIT
git clone --quiet "https://github.com/$RELEASES_REPO.git" "$APT_CLONE"
rm -rf "$APT_CLONE/docs/apt"
mkdir -p "$APT_CLONE/docs/apt"
# Ne garde que la dernière version dans le dépôt apt (docs/apt/) — les versions précédentes
# restent accessibles indéfiniment via les assets de chaque release GitHub, pas la peine de les
# accumuler aussi ici (le dossier de travail du dépôt apt resterait sinon ~60 Mo plus gros à
# chaque publication).
cp "$APT_REPO"/* "$APT_CLONE/docs/apt/"
(
    cd "$APT_CLONE"
    git add docs/apt
    if git diff --cached --quiet; then
        echo "  (dépôt apt déjà à jour)"
    else
        git -c user.name="OpenTagger release bot" -c user.email="pollomax847@users.noreply.github.com" \
            commit -q -m "apt: publier $TAG"
        git push --quiet origin main
        echo "✓ Dépôt apt mis à jour."
    fi
)

echo "✓ Publié : https://github.com/$RELEASES_REPO/releases/tag/$TAG"
echo "✓ Dépôt apt : https://pollomax847.github.io/opentagger-releases/apt/"
