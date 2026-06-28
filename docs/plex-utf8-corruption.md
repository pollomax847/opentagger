# Détection et correction de métadonnées corrompues dans Plex

## Symptôme

PlexAmp Android affiche **"OOPS — Nous avons eu un problème pour obtenir les données de pollomax"**
sur la page d'accueil, alors que la lecture audio fonctionne encore.

## Cause

Un ou plusieurs fichiers audio ont des tags ID3/MP3 encodés en **Latin-1 (ISO-8859-1)** au lieu
d'**UTF-8**. Plex stocke ces octets tels quels dans sa base de données. Quand PlexAmp reçoit la
réponse JSON de Plex, les octets invalides (ex: `\xff\xfb\xe4`, `\xe0`) font planter le parser
JSON côté client → écran OOPS.

Cas rencontrés :
- Artiste dont le nom contenait des données binaires MP3 (`\xff\xfb` = sync word MP3)
- Album "iTunes Live à Paris - EP" de Gérald De Palmas dont le `à` était en Latin-1 (`\xe0`)

## Diagnostic

### 1. Vérifier que c'est bien un problème de JSON invalide

```bash
TOKEN="WQQySxr3SBPY-Sn77Yuk"

curl -s -H "Accept: application/json" -H "Accept-Encoding: identity" \
  "http://localhost:32400/hubs/sections/1?count=6&includeMyMixes=1&includeStations=1&X-Plex-Token=$TOKEN" \
  -o /tmp/hubs.json

python3 -c "
import json
json.load(open('/tmp/hubs.json', 'rb'))
print('JSON valide')
" 2>&1
```

Si erreur `UnicodeDecodeError` ou `JSONDecodeError` → des octets invalides sont présents.

### 2. Localiser les octets invalides et l'item concerné

```bash
python3 << 'EOF'
raw = open('/tmp/hubs.json', 'rb').read()
bad = []
i = 0
while i < len(raw):
    b = raw[i]
    if b < 0x80: i += 1
    elif 0xC0 <= b < 0xE0 and i+1 < len(raw) and (raw[i+1] & 0xC0) == 0x80: i += 2
    elif 0xE0 <= b < 0xF0 and i+2 < len(raw) and (raw[i+1] & 0xC0) == 0x80 and (raw[i+2] & 0xC0) == 0x80: i += 3
    elif 0xF0 <= b < 0xF8 and i+3 < len(raw) and all((raw[i+j] & 0xC0) == 0x80 for j in range(1,4)): i += 4
    else:
        ctx = raw[max(0, i-100):i+100].decode('utf-8', errors='replace')
        bad.append((i, ctx))
        i += 1

for pos, ctx in bad[:5]:
    print(f"Pos {pos}: ...{ctx}...")
EOF
```

Le contexte montre le champ (`title`, `parentTitle`, `file`, etc.) et les clés `ratingKey` proches.

### 3. Scanner toute la bibliothèque (artistes + albums)

```bash
python3 << 'EOF'
import subprocess

TOKEN = "WQQySxr3SBPY-Sn77Yuk"
BASE = "http://localhost:32400"

def scan_type(type_id, type_name):
    start, size, total = 0, 200, None
    bad_batches = []
    while True:
        r = subprocess.run([
            'curl', '-s', '-H', 'Accept: application/json', '-H', 'Accept-Encoding: identity',
            f'{BASE}/library/sections/1/all?type={type_id}&X-Plex-Token={TOKEN}'
            f'&X-Plex-Container-Start={start}&X-Plex-Container-Size={size}'
        ], capture_output=True)
        raw = r.stdout
        bad = []
        i = 0
        while i < len(raw):
            b = raw[i]
            if b < 0x80: i += 1
            elif 0xC0 <= b < 0xE0 and i+1 < len(raw) and (raw[i+1] & 0xC0) == 0x80: i += 2
            elif 0xE0 <= b < 0xF0 and i+2 < len(raw) and (raw[i+1] & 0xC0) == 0x80 and (raw[i+2] & 0xC0) == 0x80: i += 3
            elif 0xF0 <= b < 0xF8 and i+3 < len(raw) and all((raw[i+j] & 0xC0) == 0x80 for j in range(1,4)): i += 4
            else:
                ctx = raw[max(0, i-80):i+80].decode('utf-8', errors='replace')
                bad.append((i, ctx))
                i += 1
        if bad:
            bad_batches.append((start, bad[:2]))
        import json
        try:
            d = json.loads(raw.decode('utf-8', errors='replace'))
            mc = d.get('MediaContainer', {})
            if total is None:
                total = mc.get('totalSize', mc.get('size', 0))
                print(f"{type_name}: {total} items à scanner")
        except: pass
        start += size
        if total and start >= total:
            break
    if bad_batches:
        print(f"\n⚠ {type_name} - {len(bad_batches)} batch(es) corrompus:")
        for start_pos, contexts in bad_batches:
            for _, ctx in contexts:
                print(f"  Items {start_pos}-{start_pos+size}: ...{ctx[:150]}...")
    else:
        print(f"✓ {type_name}: aucune corruption")

scan_type(8, "Artistes")
scan_type(9, "Albums")
EOF
```

## Correction

### Cas 1 : Artiste/album avec données binaires (suppression)

Si le `ratingKey` correspond à un item avec un chemin de fichier invalide (données binaires),
supprimer l'entrée Plex directement :

```bash
TOKEN="WQQySxr3SBPY-Sn77Yuk"
RATING_KEY="2169352"   # remplacer par le ratingKey trouvé

curl -s -w "HTTP:%{http_code}" -X DELETE \
  "http://localhost:32400/library/metadata/$RATING_KEY?X-Plex-Token=$TOKEN"
```

### Cas 2 : Tag ID3 en Latin-1 (ré-encodage)

Trouver le fichier via l'API puis ré-encoder les tags en UTF-8 :

```bash
TOKEN="WQQySxr3SBPY-Sn77Yuk"
RATING_KEY="1935230"   # remplacer par le ratingKey de l'album

# Trouver le dossier du fichier
curl -s -H "Accept: application/json" -H "Accept-Encoding: identity" \
  "http://localhost:32400/library/metadata/$RATING_KEY/children?X-Plex-Token=$TOKEN" \
  -o /tmp/album_tracks.json

python3 -c "
import re
raw = open('/tmp/album_tracks.json', 'rb').read()
text = raw.decode('utf-8', errors='replace')
files = re.findall(r'\"file\":\"([^\"]+)\"', text)
for f in files: print(f)
"
```

Puis ré-encoder les tags :

```python
import mutagen.id3, os

album_dir = "/chemin/vers/album"   # remplacer

for fname in os.listdir(album_dir):
    if not fname.endswith('.mp3'):
        continue
    path = os.path.join(album_dir, fname)
    tags = mutagen.id3.ID3(path)
    changed = False
    for key, frame in tags.items():
        if hasattr(frame, 'encoding') and frame.encoding == 0:  # 0 = Latin-1
            frame.encoding = 3  # 3 = UTF-8
            changed = True
    if changed:
        tags.save(path, v2_version=3)
        print(f"Re-encodé: {fname}")
```

Puis forcer le refresh Plex de l'album :

```bash
curl -s -X PUT \
  "http://localhost:32400/library/metadata/$RATING_KEY/refresh?X-Plex-Token=$TOKEN"
```

### Vérification finale

```bash
TOKEN="WQQySxr3SBPY-Sn77Yuk"

curl -s -H "Accept: application/json" -H "Accept-Encoding: identity" \
  "http://localhost:32400/hubs/sections/1?count=6&includeMyMixes=1&X-Plex-Token=$TOKEN" \
  -o /tmp/hubs_check.json

python3 -c "
import json
d = json.load(open('/tmp/hubs_check.json', 'rb'))
print('✓ JSON valide — PlexAmp devrait fonctionner')
" 2>&1
```

## Prévention dans OpenTagger

Lors du taggage de fichiers MP3, toujours forcer l'encodage UTF-8 (encoding=3) sur tous
les frames ID3, pas seulement les nouveaux. Exemple avec mutagen :

```python
from mutagen.id3 import ID3, Encoding

tags = ID3(path)
for frame in tags.values():
    if hasattr(frame, 'encoding'):
        frame.encoding = Encoding.UTF8  # = 3
tags.save(v2_version=3)
```

## Token Plex (local)

Le token se trouve dans :
```
/var/snap/plexmediaserver/common/Library/Application Support/Plex Media Server/Preferences.xml
```
Champ : `PlexOnlineToken`
