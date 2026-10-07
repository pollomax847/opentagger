# OpenTagger sous Linux

OpenTagger est la **même application** sous Linux et sous Windows : mêmes fenêtres, mêmes réglages, même moteur d'identification.
Les seules différences viennent du système (chemins, lecteur de CD, outils installés) et d'un outil supplémentaire sous Linux : **SongRec**.

> **État de ce document.** Il décrit la version **0.9.39** (préparation de la 1.0). Les fonctions listées dans « À vérifier sur une vraie
> machine Linux » ont été écrites et testées par des tests automatiques, mais **pas encore essayées sur un ordinateur Linux réel** :
> à valider avant de publier la 1.0.

---

## 1. Prérequis

| Outil | Rôle | Obligatoire | Installation (Debian / Ubuntu) |
|---|---|---|---|
| **Java 21+** | exécuter l'application | oui | `sudo apt install openjdk-21-jre` (ou fourni par le paquet `opentagger`) |
| **ffmpeg** | BPM, transcodage, écriture M4A | oui | `sudo apt install ffmpeg` |
| **AtomicParsley** | écriture des tags M4A | recommandé | `sudo apt install atomicparsley` |
| **fpcalc** (Chromaprint) | empreinte audio pour AcoustID | recommandé | `sudo apt install libchromaprint-tools` — ou bouton « Télécharger fpcalc » dans Préférences → Audio ; l'application le télécharge aussi toute seule au démarrage s'il manque |
| **python3** | lecture native d'un CD audio | pour les CD | `sudo apt install python3` |
| **eject** | éjecter le CD après l'import | facultatif | `sudo apt install eject` |
| **cdparanoia** | repli pour lire un CD abîmé | facultatif | `sudo apt install cdparanoia` |
| **SongRec** | identification type Shazam | facultatif (Linux/macOS seulement) | voir ci-dessous |

**SongRec** n'est pas dans les dépôts officiels Debian/Ubuntu :

```bash
sudo add-apt-repository ppa:marin-m/songrec
sudo apt update && sudo apt install songrec
```

Il n'existe pas pour Windows : c'est la seule fonction que Linux a en plus. Sans SongRec, l'identification par l'audio passe par AcoustID,
exactement comme sous Windows. Les réglages liés à SongRec n'apparaissent dans Préférences que si SongRec est installé.

---

## 2. Installation

### Debian / Ubuntu (dépôt apt signé)

```bash
curl -fsSL https://pollomax847.github.io/opentagger-releases/apt/opentagger-apt.asc \
    | sudo tee /usr/share/keyrings/opentagger.asc >/dev/null
echo "deb [signed-by=/usr/share/keyrings/opentagger.asc] https://pollomax847.github.io/opentagger-releases/apt ./" \
    | sudo tee /etc/apt/sources.list.d/opentagger.list
sudo apt update && sudo apt install opentagger
```

### Autres distributions, depuis les sources

```bash
git clone https://github.com/pollomax847/opentagger
cd opentagger/opentagger && mvn package -DskipTests && cd ..
bash install-opentagger.sh
```

Le script installe le `.jar` dans `~/.local/share/opentagger/`, un lanceur dans `~/.local/bin/opentagger`, l'icône et le fichier `.desktop`
(clic droit sur un dossier ou un fichier audio → **Ouvrir avec → OpenTagger**). Si `~/.local/bin` n'est pas dans votre `PATH`, ajoutez
`export PATH="$HOME/.local/bin:$PATH"` à votre `~/.bashrc`.

### Lancement manuel

```bash
java -jar opentagger.jar                        # interface graphique
java -jar opentagger.jar /chemin/vers/musique   # avec un dossier ou un fichier
./opentagger.sh                                 # depuis le dépôt : mémoire réglée, journal dans ~/.local/share/opentagger/opentagger.log
```

---

## 3. Où sont mes données ?

| Quoi | Où |
|---|---|
| Réglages (clés API, masques de renommage…) | `~/.opentagger/settings.properties` et `~/.opentagger/renamemask.properties` |
| Cache et historique (recherches, identifications en attente, historique de taguage) | `~/.opentagger/cache.db` |
| Journal (si lancé par `opentagger.sh`) | `~/.local/share/opentagger/opentagger.log` |

Sous Windows, les réglages sont dans `%APPDATA%\OpenTagger\` ; le cache `cache.db` est, lui, aussi dans le dossier personnel (`.opentagger`).
Le cache peut être vidé depuis **Bibliothèque → Rapports → Historique de taguage… → Vider le cache / la base…** (jamais vos fichiers audio).

---

## 4. Mises à jour

- **Paquet apt** : `sudo apt update && sudo apt upgrade`.
- **Depuis l'application** : une vérification a lieu une fois par jour (désactivable dans Préférences → Démarrage) ; **Outils → Vérifier les mises à jour…** la force.
- La comparaison se fait sur le **numéro de version** : une nouvelle publication doit donc porter un numéro plus grand que l'ancien
  (c'est pourquoi la 1.0 passe de 0.9.39 à `1.0.0`).

---

## 5. Importer un CD sous Linux

Menu **Bibliothèque → Import / Export → Importer un CD…** (ou le bouton « Importer un CD » de la barre d'outils), puis **Détecter le CD**.

### CD audio

- La lecture est **native** : un script Python 3 (`cd_linux.py`) lit la table des pistes et extrait chaque piste par le pilote du lecteur (`/dev/cdrom`,
  puis `/dev/sr0` à `/dev/sr3`). Aucun outil à installer en plus de `python3`. Si `cdparanoia` est présent, il sert de repli.
- **Permissions** : votre utilisateur doit pouvoir lire le lecteur, en général en faisant partie du groupe `cdrom` :
  `sudo usermod -aG cdrom $USER` (puis ouvrir une nouvelle session). Un lecteur particulier se règle par la clé `cd.device` (ex. `/dev/sr1`).
- **Reconnaissance du disque**, dans l'ordre :
  1. **MusicBrainz** — identifiant exact du disque, puis recherche approchée vérifiée piste par piste ;
  2. **GnuDB (CDDB)** — seulement si MusicBrainz ne propose rien ;
  3. **par l'audio** — pour un CD gravé inconnu : chaque piste extraite est identifiée par empreinte (et par SongRec s'il est installé),
     celles qui sont reconnues sont rangées sous leur vrai nom, les autres restent dans « CD à identifier ».
- Plusieurs versions du disque peuvent être proposées (comme MediaMonkey) ; la colonne « Déjà présent ? » signale les pistes que vous avez déjà.
- Une fois toutes les pistes extraites sans échec, le disque est **éjecté** (commande `eject`). Réglage : Préférences → APIs → « Import de CD ».

**GnuDB** demande de s'identifier avec une adresse de contact : par défaut l'application envoie `opentagger@gnudb.org`. Vous pouvez saisir
**votre propre adresse** dans Préférences → APIs → « Import de CD » ; elle n'est alors envoyée qu'à GnuDB.

### CD de données (photos, documents…)

« Détecter le CD » reconnaît un disque de données (système de fichiers `iso9660` / `udf`, monté par votre bureau sous `/media/…`, `/run/media/…`)
et affiche **ses fichiers dans le même tableau** qu'un CD audio, avec une case par fichier. **Copier la sélection** les copie dans un nouveau
dossier de la bibliothèque, **sans jamais rien écraser** (fichier identique ignoré, fichier différent de même nom copié sous « nom (2) »).
Si le disque n'est pas trouvé automatiquement, l'application demande le dossier où il est monté.

---

## 6. À vérifier sur une vraie machine Linux avant la 1.0

Ces points sont couverts par des tests automatiques, mais **n'ont pas tourné sur un Linux réel** pendant la préparation de la 0.9.39 :

- [ ] Lancement par le paquet apt et par `install-opentagger.sh` (icône, `.desktop`, clic droit).
- [ ] Import d'un **CD audio** : détection, extraction d'une piste, éjection (`eject`).
- [ ] Détection automatique d'un **CD de données** monté (vue « Dossier / Fichier / Taille », copie, éjection).
- [ ] Identification avec **SongRec** installé (et sans) ; les réglages SongRec n'apparaissent que s'il est présent.
- [ ] Limite d'accès disque par **disque mécanique** (déjà propre à Linux : lecture de `/sys/block/*/queue/rotational`).
- [ ] Taguage d'un dossier, enregistrement, renommage dans la bibliothèque, **chemins sensibles à la casse** (`A.mp3` et `a.mp3` restent deux fichiers).
- [ ] Mise à jour depuis l'application vers la 1.0.0.

---

## 7. Dépannage

| Symptôme | Piste |
|---|---|
| « Aucun lecteur de CD détecté » / « Lecture impossible » | `python3` manquant, ou l'utilisateur n'est pas dans le groupe `cdrom` ; essayer `cd.device=/dev/sr0` |
| L'éjection ne fait rien | installer le paquet `eject` |
| Pas d'identification par l'audio | `fpcalc` absent (Préférences → Audio → Télécharger fpcalc) ou clé AcoustID manquante (Préférences → APIs) |
| Réglages SongRec absents | normal si SongRec n'est pas installé |
| Interface lente sur une très grosse bibliothèque | lancer avec `./opentagger.sh` (mémoire jusqu'à 5 Go) ; fermer les autres gros programmes |
| Un fichier « .mp3 » refusé à l'enregistrement | son contenu réel est autre (m4a, ac3…) : renommer l'extension, l'application l'indique |

---

*Voir aussi le [README](../README.md) (fonctions, configuration, compilation) et la [feuille de route](roadmap.md).*
