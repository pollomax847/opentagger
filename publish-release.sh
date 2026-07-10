#!/usr/bin/env bash
# ============================================================
# Publie une release OpenTagger sur le dépôt binaires séparé
# (pollomax847/opentagger-releases) — le dépôt source n'est
# jamais touché par ce script (aucun commit/push git dessus).
# Publie aussi le .deb (release GitHub + dépôt apt signé servi
# via GitHub Pages, docs/apt/ sur ce même dépôt binaires).
#
# La version est incrémentée AUTOMATIQUEMENT (patch, X.Y.Z → X.Y.(Z+1))
# à partir de la DERNIÈRE RELEASE RÉELLEMENT PUBLIÉE sur opentagger-releases
# (pas depuis pom.xml — un bump manuel oublié ne peut donc plus faire
# échouer la publication). pom.xml, le métadata AppStream et le
# settings.properties embarqué sont mis à jour en conséquence avant de
# construire — ce sont des modifications LOCALES, NON commitées : c'est
# à toi de les commiter séparément dans le dépôt source si tu veux les
# garder (voir le rappel affiché à la fin du script).
#
# Usage : ./publish-release.sh ["notes de version"]
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASES_REPO="pollomax847/opentagger-releases"
POM="$SCRIPT_DIR/opentagger/pom.xml"
METAINFO="$SCRIPT_DIR/io.github.pollomax847.OpenTagger.metainfo.xml"
BUNDLED_SETTINGS="$SCRIPT_DIR/opentagger/src/main/resources/settings.properties"

echo "→ Détermination de la prochaine version…"
LATEST_TAG=$(gh release view --repo "$RELEASES_REPO" --json tagName -q .tagName 2>/dev/null || true)
if [ -z "$LATEST_TAG" ]; then
    echo "✗ Impossible de lire la dernière release publiée sur $RELEASES_REPO (dépôt sans release, ou API inaccessible)."
    echo "  Vérifie 'gh release list --repo $RELEASES_REPO', ou bump pom.xml toi-même pour une toute première publication."
    exit 1
fi
LATEST_VERSION="${LATEST_TAG#v}"
IFS='.' read -r MAJOR MINOR PATCH <<< "$LATEST_VERSION"
VERSION="$MAJOR.$MINOR.$((PATCH + 1))"
TAG="v$VERSION"
NOTES="${1:-Version $VERSION}"

echo "=== Publication OpenTagger $TAG (précédente : $LATEST_TAG) ==="

echo "→ Vérification : cette version n'est pas déjà publiée…"
if gh release view "$TAG" --repo "$RELEASES_REPO" >/dev/null 2>&1; then
    echo "✗ La release $TAG existe déjà sur $RELEASES_REPO — incohérent avec la dernière version lue ($LATEST_TAG)."
    echo "  Vérifie l'état du dépôt : gh release list --repo $RELEASES_REPO"
    exit 1
fi

echo "→ Mise à jour de la version ($LATEST_VERSION → $VERSION) dans pom.xml/metainfo.xml/settings.properties…"
# 0,/.../s : ne remplace que la PREMIÈRE occurrence — pom.xml contient aussi des <version> de
# dépendances Maven, seule celle juste après <artifactId> (la nôtre) doit changer.
sed -i "0,/<version>${LATEST_VERSION}<\/version>/s//<version>${VERSION}<\/version>/" "$POM"
sed -i "s/^app.version = ${LATEST_VERSION}\$/app.version = ${VERSION}/" "$BUNDLED_SETTINGS"
TODAY="$(date +%Y-%m-%d)"
sed -i "s#<releases>#<releases>\n    <release version=\"${VERSION}\" date=\"${TODAY}\" />#" "$METAINFO"

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
echo
echo "→ Bump de version $LATEST_VERSION → $VERSION appliqué localement dans pom.xml/metainfo.xml/settings.properties."
echo "  Ce script ne touche jamais le dépôt source : pense à commiter ce bump toi-même, par ex. :"
echo "    git add opentagger/pom.xml io.github.pollomax847.OpenTagger.metainfo.xml opentagger/src/main/resources/settings.properties"
echo "    git commit -m \"chore: bump version to $VERSION\""
