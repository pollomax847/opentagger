#!/usr/bin/env bash
# ============================================================
# Construit un dépôt apt minimal (format "flat", un seul paquet)
# à partir du .deb produit par package-deb.sh, signé avec la clé
# dédiée générée dans ~/.opentagger-apt-signing (JAMAIS dans ce
# dépôt git — une clé privée de signature qui fuite permettrait
# à n'importe qui de distribuer un paquet malveillant "signé
# OpenTagger").
#
# Résultat : opentagger/target/apt-repo/ — prêt à être publié
# tel quel sur GitHub Pages (voir les instructions affichées à
# la fin). Ce script ne pousse RIEN sur GitHub lui-même.
#
# Usage : ./build-apt-repo.sh
# ============================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$SCRIPT_DIR/opentagger"
GNUPGHOME="$HOME/.opentagger-apt-signing"
KEY_UID="pollomax847@users.noreply.github.com"

if [ ! -d "$GNUPGHOME" ]; then
    echo "✗ Clé de signature introuvable ($GNUPGHOME)."
    echo "  Elle doit être générée une seule fois — voir la conversation qui a mis en place ce script."
    exit 1
fi

DEB="$(ls "$PROJECT"/target/dist/*.deb 2>/dev/null | head -1)"
if [ -z "$DEB" ]; then
    echo "→ Aucun .deb trouvé, construction via package-deb.sh…"
    "$SCRIPT_DIR/package-deb.sh"
    DEB="$(ls "$PROJECT"/target/dist/*.deb 2>/dev/null | head -1)"
fi
echo "=== Dépôt apt à partir de $(basename "$DEB") ==="

REPO="$PROJECT/target/apt-repo"
rm -rf "$REPO"
mkdir -p "$REPO"
cp "$DEB" "$REPO/"

# Clé publique — c'est la seule partie de la clé qui doit être publiée avec le dépôt.
GNUPGHOME="$GNUPGHOME" gpg --export --armor "$KEY_UID" > "$REPO/opentagger-apt.asc"
echo "→ Clé publique exportée : opentagger-apt.asc"

# Format "flat" (pas de hiérarchie dists/pool) : le plus simple pour un dépôt à un seul paquet —
# apt le supporte nativement (entrée sources.list avec un "./" final, voir instructions ci-dessous).
cd "$REPO"
apt-ftparchive packages . > Packages
gzip -9c Packages > Packages.gz
apt-ftparchive release . > Release
echo "→ Packages/Release générés"

# InRelease (Release + signature dans un seul fichier, clearsigné) : format que l'apt moderne
# préfère. Release.gpg (signature détachée) gardé en plus pour compatibilité avec l'apt plus ancien.
GNUPGHOME="$GNUPGHOME" gpg --batch --yes --clearsign -o InRelease Release
GNUPGHOME="$GNUPGHOME" gpg --batch --yes -abs -o Release.gpg Release
echo "→ Release signé (InRelease + Release.gpg)"

echo
echo "✓ Dépôt prêt : $REPO"
echo
echo "  Pour le publier (une fois que tu as choisi où l'héberger) :"
echo "    - Copier tout le contenu de $REPO vers l'hébergement choisi (ex: GitHub Pages)."
echo
echo "  Pour un utilisateur, ajout du dépôt (à adapter avec la vraie URL d'hébergement) :"
echo '    curl -fsSL <URL>/opentagger-apt.asc | sudo tee /usr/share/keyrings/opentagger.asc >/dev/null'
echo '    echo "deb [signed-by=/usr/share/keyrings/opentagger.asc] <URL> ./" | sudo tee /etc/apt/sources.list.d/opentagger.list'
echo '    sudo apt update && sudo apt install opentagger'
