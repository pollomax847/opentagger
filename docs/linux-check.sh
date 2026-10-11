#!/usr/bin/env bash
# Vérification d'OpenTagger sur une machine Linux réelle (à lancer depuis la racine du dépôt) :
#   bash docs/linux-check.sh            -> contrôles de l'environnement + tests automatiques
#   bash docs/linux-check.sh --no-tests -> contrôles seulement (pas de Maven)
# Ne modifie ni votre bibliothèque ni ~/.opentagger : tout se passe dans un dossier temporaire.
# Chaque ligne affiche OK, ECHEC ou SKIP ; le code de sortie est 1 s'il y a au moins un ECHEC.

fail=0
ok()   { printf '  OK     %s\n' "$1"; }
bad()  { printf '  ECHEC  %s\n' "$1"; fail=1; }
skip() { printf '  SKIP   %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

tmp=$(mktemp -d /tmp/opentagger-check.XXXXXX) || exit 1
trap 'rm -rf "$tmp"' EXIT

echo "== 1. Système =="
echo "  $(uname -srm) — $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-inconnu}")"
if have java; then
  v=$(java -version 2>&1 | head -n1)
  major=$(java -version 2>&1 | sed -n '1s/.*"\([0-9]*\)\..*/\1/p; 1s/.*"\([0-9]*\)".*/\1/p' | head -n1)
  if [ "${major:-0}" -ge 21 ] 2>/dev/null; then ok "Java 21+ ($v)"; else bad "Java 21+ requis ($v)"; fi
else bad "java introuvable"; fi

echo "== 2. Outils =="
have ffmpeg  && ok "ffmpeg"  || bad "ffmpeg introuvable (sudo apt install ffmpeg)"
have ffprobe && ok "ffprobe" || bad "ffprobe introuvable"
have fpcalc  && ok "fpcalc"  || skip "fpcalc absent (AcoustID inactif ; l'application le télécharge au démarrage)"
have python3 && ok "python3 (lecture de CD)" || skip "python3 absent (import de CD impossible)"
have eject   && ok "eject"   || skip "eject absent (le CD ne sera pas éjecté)"
have AtomicParsley && ok "AtomicParsley" || skip "AtomicParsley absent (écriture M4A moins sûre)"

echo "== 3. SongRec =="
if have songrec; then
  ok "songrec trouvé : $(songrec --version 2>&1 | head -n1)"
  if have ffmpeg; then
    ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:duration=12" "$tmp/tone.wav" 2>/dev/null
    out=$(timeout 40 songrec audio-file-to-recognized-song "$tmp/tone.wav" 2>"$tmp/songrec.err")
    code=$?
    if [ "$code" -eq 124 ]; then bad "songrec n'a pas répondu en 40 s (réseau ? limite Shazam ?)"
    elif grep -qi "429\|too many" "$tmp/songrec.err"; then skip "Shazam limite les requêtes (429) : réessayer plus tard"
    elif [ -n "$out" ] || [ "$code" -eq 0 ]; then ok "songrec exécuté sur un son de test (un son pur n'est pas reconnu, c'est normal)"
    else bad "songrec a échoué : $(head -c 200 "$tmp/songrec.err")"; fi
  fi
else skip "songrec absent (facultatif sous Linux ; voir docs/LINUX.md)"; fi

echo "== 4. Audio illisible (même contrôle que l'application) =="
if have ffmpeg; then
  head -c 200000 /dev/urandom > "$tmp/abime.m4a"
  if ffmpeg -v error -i "$tmp/abime.m4a" -f null - 2>&1 | grep -qi "invalid data\|error opening input\|moov atom"; then
    ok "un fichier abîmé est bien détecté par ffmpeg"
  else bad "ffmpeg n'a rien signalé sur un fichier d'octets aléatoires"; fi
  ffmpeg -loglevel error -f lavfi -i "sine=frequency=440:duration=2" -q:a 5 "$tmp/sain.mp3" 2>/dev/null
  if [ -s "$tmp/sain.mp3" ] && [ -z "$(ffmpeg -v error -i "$tmp/sain.mp3" -f null - 2>&1)" ]; then
    ok "un fichier sain n'est pas signalé"
  else bad "un fichier sain a produit des erreurs de décodage"; fi
fi

echo "== 5. Disque (limite d'accès par disque mécanique) =="
if [ -d /sys/block ]; then
  n=0
  for f in /sys/block/*/queue/rotational; do [ -r "$f" ] && n=$((n+1)); done
  [ "$n" -gt 0 ] && ok "/sys/block lisible ($n disque(s) détecté(s))" || skip "aucun disque dans /sys/block"
else skip "/sys/block absent"; fi

echo "== 6. Lecteur de CD (facultatif) =="
if ls /dev/sr0 /dev/cdrom >/dev/null 2>&1; then
  ok "lecteur présent : $(ls /dev/sr0 /dev/cdrom 2>/dev/null | head -n1)"
  id -nG | grep -qw cdrom && ok "vous êtes dans le groupe cdrom" || skip "pas dans le groupe cdrom (sudo usermod -aG cdrom \$USER)"
else skip "aucun lecteur de CD"; fi

if [ "$1" != "--no-tests" ]; then
  echo "== 7. Tests automatiques (Maven) =="
  if have mvn && [ -f opentagger/pom.xml ]; then
    # HOME isolé : les tests ne doivent ni lire ni écrire vos vrais réglages / cache.db
    iso=$(mktemp -d /tmp/opentagger-check-home.XXXXXX)
    ( cd opentagger && mvn -q -B -Duser.home="$iso" -DargLine="-Duser.home=$iso" test ) >"$tmp/mvn.log" 2>&1
    if [ $? -eq 0 ]; then ok "tous les tests passent"; else bad "des tests échouent — voir la fin du journal ci-dessous"; tail -n 40 "$tmp/mvn.log"; fi
    rm -rf "$iso"
  else skip "mvn ou opentagger/pom.xml introuvable (lancer depuis la racine du dépôt)"; fi
fi

echo
echo "À faire À LA MAIN (voir docs/LINUX.md §6) : lancement par le paquet apt / install-opentagger.sh, import d'un CD audio,"
echo "CD de données, taguage d'un petit dossier, chemins sensibles à la casse (A.mp3 et a.mp3 = deux fichiers)."
[ "$fail" -eq 0 ] && echo "Résultat : aucun ECHEC." || echo "Résultat : au moins un ECHEC (voir ci-dessus)."
exit "$fail"
