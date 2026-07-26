# OpenTagger — Bugs confirmés et corrigés

Revue effectuée sur `pollomax847/opentagger`, branche `feature/opentagger-v1`.
Ce fichier regroupe **uniquement les bugs confirmés et corrigés** (patchs déjà
appliqués dans le clone local), dans l'ordre où ils ont été trouvés. Pour le
reste :
- Découverte structurelle (pipeline CLI, cohérence inter-pipelines) →
  `02-ARCHITECTURE-ET-PIPELINE.md`
- Ce qui a été vérifié sans anomalie / pas encore audité →
  `03-ZONES-AUDITEES-ET-RESTANTES.md`
- Comparaison avec MusicBrainz Picard / OneTagger → `04-COMPARAISON-PICARD-ONETAGGER.md`
- Pistes d'amélioration (au-delà des bugs) → `05-SUGGESTIONS-AMELIORATION.md`

**Correctifs pas poussés sur GitHub** — pas d'identifiants git configurés
pour `pollomax847` dans cet environnement de revue. Appliqués localement
dans le clone (`/home/claude/opentagger`, branche `feature/opentagger-v1`),
à toi de les rapatrier (patch disponible sur demande).

---

## 🐛 Bugs confirmés et corrigés

### `LocalCorrector.java` — `titleCase()` ne met pas le reste du mot en minuscule

**Fichier :** `opentagger/src/main/java/com/opentagger/LocalCorrector.java`
**Méthode :** `titleCase(String s)`

Utilisée pour capitaliser artiste/titre/album **extraits du nom de fichier**
quand les tags sont vides (`extractFromFilenameIfEmpty` → `correctCapitalization`).

Avant :
```java
if (i == 0 || !LOWER_WORDS.contains(w.toLowerCase())) {
    sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
}
```

Seule la première lettre était forcée en majuscule ; le reste du mot
(`w.substring(1)`) était recopié **tel quel**, casse d'origine comprise. Or
les noms de fichiers rippés sont très souvent en MAJUSCULES ou en
minuscules. Résultat concret :

- `"01 - METALLICA - ENTER SANDMAN.mp3"` → artiste/titre restaient
  `"METALLICA"` / `"ENTER SANDMAN"` (pas de vraie mise en forme), alors que
  le résultat attendu est `"Metallica"` / `"Enter Sandman"`.
- La branche `LOWER_WORDS` (articles/prépositions), elle, fait bien
  `w.toLowerCase()` — l'incohérence entre les deux branches confirme qu'il
  s'agit d'un oubli et pas d'un choix voulu.

Après (corrigé) :
```java
sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
```

**Impact réel :** notable — c'est la fonction de "Title Case" appliquée à
chaque fichier sans tags dont le nom sert de repli, un cas très courant
(vieilles bibliothèques rippées, fichiers récupérés). Corrigé dans le clone
local.

### `CaaClient.java` — extension d'image toujours forcée à `.jpg`, même pour du PNG

**Fichier :** `opentagger/src/main/java/com/opentagger/CaaClient.java`
**Méthode :** `tryDownload(String path, MetadataCache cache)`

Le Cover Art Archive sert aussi bien du JPEG que du PNG. Le code vérifiait
déjà la signature des octets (`isImageBytes()`, JPEG `FFD8FF` / PNG
`89504E47`) pour rejeter une réponse d'erreur (HTML/JSON), mais jetait
ensuite cette info et forçait :
```java
String ext = ".jpg";
```
**Impact réel :** cette extension de fichier temporaire est relue plus loin
pour déduire le MIME type à écrire dans le tag :
- `TagWriter.writeCoverOnly()` : `name.endsWith(".png") ? "image/png" :
  "image/jpeg"` — écrase même le MIME type déjà correctement détecté par
  jaudiotagger avec cette valeur basée sur l'extension.
- `TagEnrichment.saveEntry()` (option "copier la pochette en fichier à
  côté") : même sniff par extension pour choisir `.png` vs `.jpg`.

Résultat concret : une pochette CAA réellement en PNG se faisait
embarquer/enregistrer avec un MIME type/extension `image/jpeg` erroné.

Corrigé — l'extension est maintenant dérivée de la même signature déjà
vérifiée (`imageExtension()` retourne `.jpg`, `.png`, ou `null` si ni l'un
ni l'autre) :
```java
String ext = imageExtension(body);
if (ext == null) return null;
```

### `BpmDetector.java` — perte de précision sur le calcul du framerate

**Fichier :** `opentagger/src/main/java/com/opentagger/BpmDetector.java`
**Méthode :** `detect(String filePath)`

Avant :
```java
return computeBpmByAutocorrelation(energy, SAMPLE_RATE / FRAME_SIZE);
```

`SAMPLE_RATE / FRAME_SIZE` (8000 / 186) est une **division entière** qui
tronque le résultat à `43` au lieu de `43,01...`, alors que le paramètre
attendu est un `double fps`. Cette valeur de fps pilote ensuite TOUS les
calculs de lag/BPM de l'autocorrélation (`lagMin`, `lagMax`, boucle de
corrélation, conversion finale lag→BPM).

Après (corrigé) :
```java
return computeBpmByAutocorrelation(energy, (double) SAMPLE_RATE / FRAME_SIZE);
```

**Impact réel :** faible (~0,03 % d'erreur sur le framerate, négligeable une
fois le BPM arrondi à l'entier le plus proche) — mais c'est un vrai bug de
précision, pas une approximation volontaire, et il coûte zéro effort à
corriger. Le correctif est déjà appliqué dans le clone local.

### `MainFrame.buildRenameJob()` — cache d'historique jamais nettoyé après renommage manuel en masse

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/MainFrame.java`
**Méthode :** `buildRenameJob()` (utilisée par "Renommer les fichiers tagués")

`MetadataCache.deleteFileHistory(path)` existe précisément pour supprimer
l'entrée `chemin → mbid` de l'ancien emplacement après un renommage — elle
est bien appelée dans `TagEnrichment.saveEntry()`, `BatchProcessor.java` et
`App.java` (CLI), mais **pas** dans le renommage manuel en masse de
`MainFrame` :

```java
String mbid = cache.getFileTagging(oldPath.toFile().getAbsolutePath());
if (mbid != null)
    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), mbid);
```

Un commentaire dans `TagEnrichment.java` affirmait même à tort que
`MainFrame.buildRenameJob()` "le fait correctement" — ce n'était pas le cas
dans le code actuel (commentaire corrigé au passage).

**Pourquoi c'est plus qu'un simple oubli de nettoyage :** cette table est
utilisée par `TaggingWorker.findTags()` pour un retour **instantané à
confiance totale (score 100)** dès qu'un chemin de fichier a une entrée dans
le cache issue d'une identification par empreinte audio — sans revérifier
l'audio réel. Si le même chemin absolu venait à être réutilisé plus tard par
un fichier complètement différent (re-rip vers un nom de fichier générique,
outil de téléchargement qui réutilise les noms...), l'ancienne
identification lui serait appliquée à 100% de confiance, sans aucune
vérification — exactement le genre de faux positif que le code se donne
déjà la peine d'éviter ailleurs (cf. le commentaire sur "Studieo – Adios"
vs "Bengous – Tié la famille !" dans `TaggingWorker`).

Corrigé — l'ancien chemin est supprimé avant d'enregistrer le nouveau :
```java
String oldAbs = oldPath.toFile().getAbsolutePath();
String mbid = cache.getFileTagging(oldAbs);
if (mbid != null) {
    cache.deleteFileHistory(oldAbs);
    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), mbid);
}
```

**Constat connexe, pas corrigé (portée plus large) :** le même trou existe pour
la **suppression** de fichiers, pas seulement le renommage —
`DuplicatesDialog.deleteSelected()` (suppression de doublons) et
`MainFrame.deleteErrorFiles()` (suppression de fichiers illisibles) déplacent
des fichiers vers la corbeille sans jamais appeler `cache.deleteFileHistory()`
non plus (vérifié : aucune référence à `MetadataCache` dans
`DuplicatesDialog.java`). Le risque théorique est le même (chemin réutilisé
plus tard par un fichier différent → fausse confiance totale), mais je n'ai
pas appliqué de correctif ici : contrairement au renommage, ces deux
dialogues n'ouvrent aucune connexion `MetadataCache` aujourd'hui — en ajouter
une juste pour ce nettoyage est un changement plus large (gestion du cycle de
vie de la connexion, potentiellement partagée) que je préfère te signaler
plutôt que patcher à la volée sans validation.

---



**Fichier :** `opentagger/src/main/java/com/opentagger/PlaylistExporter.java`
**Méthode :** `exportM3u()`

```java
int duration = -1; // -1 = durée inconnue
```
`TagInfo.durationSec` est pourtant déjà connu et affiché ailleurs (colonne
Durée du tableau principal) — l'export `#EXTINF` écrivait systématiquement
"durée inconnue" au lieu de la vraie durée, alors que le format M3U étendu la
supporte et que la plupart des lecteurs (VLC, Kodi, MediaMonkey) l'utilisent
pour le temps total de la playlist / la barre de progression.

Corrigé :
```java
int duration = ti.durationSec > 0 ? ti.durationSec : -1; // -1 = durée inconnue
```

**Complément trouvé en revue UI (`MainFrame.exportPlaylist()`) :** même lacune
dans `exportXspf()` — le champ `<duration>` (en millisecondes, prévu par le
format XSPF) n'était jamais écrit du tout, alors que `ti.durationSec` est
disponible ici aussi. Ajouté :
```java
if (ti.durationSec > 0) pw.println("      <duration>" + (ti.durationSec * 1000) + "</duration>");
```

---


---

## 🎯 Septième round — retour ciblé sur `AlbumCompletionWorker` (demandé explicitement)

### Bug confirmé et corrigé : les candidats de même titre s'écrasaient silencieusement

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/AlbumCompletionWorker.java`
**Méthode :** `doInBackground()` (construction de `candidateIndex`)

L'index des fichiers SKIPPED/PENDING candidats à la complétion était construit
ainsi :
```java
Map<String, FileEntry> candidateIndex = new ConcurrentHashMap<>();
...
if (!key.isBlank()) candidateIndex.put(key, e);
```
`key` est le **titre normalisé**. Deux candidats différents partageant le
même titre normalisé (extrêmement courant : "Intro", "Outro", "Interlude",
"Skit" existent sur de très nombreux albums différents) — le `put()`
écrasait silencieusement le précédent. Concrètement : si ta bibliothèque a
plusieurs fichiers "01 - Intro.mp3" non identifiés provenant d'albums
différents, un seul d'entre eux restait joignable par cette passe de
complétion — les autres devenaient invisibles, sans aucun message
d'erreur, aucune trace dans le journal.

Corrigé — l'index garde désormais une **liste** de candidats par titre
normalisé (`Map<String, List<FileEntry>>`), et `findCandidate()`/le retrait
associé parcourent cette liste (premier candidat compatible avec l'artiste
attendu, via `artistCompatible()` déjà en place).

### Observation, pas corrigée : une 3ᵉ implémentation indépendante du parsing de titre depuis le nom de fichier

`AlbumCompletionWorker.filenameTitle()` réimplémente, avec sa propre regex,
exactement ce que `LocalCorrector.FILE_ARTIST_TITLE`/
`FILE_TRACK_ARTIST_TITLE` font déjà ailleurs dans le projet — un exemple
concret de plus du problème structurel n°1 déjà documenté (logique
dupliquée indépendamment dans plusieurs pipelines). Les deux implémentations
partagent la même ambiguïté inhérente au découpage par tiret
("Titre - Sous-titre" sans artiste réel peut être mal coupé), mais avec des
comportements légèrement différents (celle-ci ne gère pas le format
"NN-piste - artiste - titre" à 3 groupes que `LocalCorrector` reconnaît).
Pas corrigé — fusionner les deux est un choix de refactor, pas un correctif
d'une ligne, à faire en connaissance de cause plutôt qu'en aveugle.

---

## 🖱️ Huitième round — bug rapporté : "Enregistrer tout" enregistre de façon imprévisible

Demande explicite : l'utilisateur a signalé qu'en cliquant sur "Enregistrer
tout", l'enregistrement semble se faire "n'importe quand" / de façon
incohérente.

### Bug confirmé et corrigé : le bouton "Enregistrer tout" n'indique jamais qu'un enregistrement est en cours, alors qu'un second clic l'ANNULE silencieusement

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/MainFrame.java`
**Méthode :** `saveAll()`

`saveAll()` a un comportement **bascule** volontaire, déjà en place :

```java
Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.SAVE);
if (running.isPresent()) {
    running.get().cancel();
    setStatus(I18n.t("Enregistrement annulé."));
    return;
}
```

Premier clic → démarre l'enregistrement. **Second clic pendant qu'il tourne
→ l'annule**, silencieusement (juste une ligne dans la barre de statut,
aussitôt recouverte par les messages suivants). Le problème : contrairement
à `btnTagAll`/`btnTagSel` (désactivés par `startTagging()`, réactivés par
`resetBtns()`), **`btnSaveAll` ne change jamais d'apparence** — vérifié par
recherche exhaustive (`grep "btnSaveAll.set"` → aucun résultat dans tout le
fichier avant ce correctif). Le bouton reste affiché "Enregistrer tout",
parfaitement cliquable, pendant tout l'enregistrement.

**Conséquence concrète** : un utilisateur qui re-clique dessus — parce que
rien ne semble se passer visuellement, ou en pensant lancer un second
enregistrement, ou simplement par réflexe — annule sans le savoir
l'enregistrement en cours. Seule la portion déjà traitée à ce moment-là est
réellement écrite sur le disque ; le reste des fichiers cochés/identifiés
reste tel quel. Vu de l'extérieur, ça ressemble exactement à un
enregistrement "au hasard"/incomplet d'une fois sur l'autre, selon le moment
exact du clic — ce qui correspond au symptôme rapporté.

Corrigé — le bouton signale maintenant clairement l'état en cours :
```java
btnSaveAll.setText(I18n.t("Annuler l'enregistrement"));
btnSaveAll.setToolTipText(I18n.t("Un enregistrement est en cours — cliquer l'annule"));
```
et restaure son texte/tooltip d'origine au `DONE` du `SaveWorker` (dans le
`PropertyChangeListener` déjà présent, juste à côté de la ligne qui cache la
barre de progression). Le comportement bascule (annulation volontaire au
clic) est conservé — seul le retour visuel manquant est corrigé, pour que
ce second clic soit un choix éclairé plutôt qu'un accident.

---

## 🔎 Neuvième round — `DetailPanel.java` : même bug que celui d'`AlbumCompletionWorker`, recyclé sur les paroles/URLs externes

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/DetailPanel.java`
**Méthode :** `collect(TagInfo t)`, branche `multiMode` (édition en sélection multiple)

Un commentaire déjà présent dans `collect()` documentait un correctif passé :
les champs classiques (mouvement, opus, section...) étaient affichés et
éditables en mode multi-sélection (`populateMulti()`) mais jamais relus au
moment d'appliquer — une valeur tapée était silencieusement perdue. Ce
correctif avait bien été fait pour les champs classiques, mais **pas** pour
un autre groupe de champs qui a exactement le même problème : les paroles et
les URLs/IDs externes (paroles, URL officielle/Wikipédia/Discogs artiste et
release, ID Discogs, Apple Music, tags Roon album/piste).

`populateMulti()` les vide volontairement en mode multi (commentaire :
*"Paroles, IDs — laisser vide en mode multi"*) — mais **aucun champ n'est
jamais désactivé nulle part dans ce fichier** (vérifié : zéro `setEnabled`
dans tout `DetailPanel.java`), donc ces champs restent pleinement éditables.
Résultat : un utilisateur qui sélectionne plusieurs fichiers, tape par
exemple un tag Roon ou colle des paroles, puis clique "Appliquer", voyait sa
saisie **silencieusement jamais écrite** sur aucun des fichiers sélectionnés
— `collect()` ne lisait tout simplement pas ces champs en mode multi.

Corrigé — ajout des mêmes lectures conditionnelles ("n'écraser que si non
vide") que le reste du bloc `multiMode`, pour : `lyrics`, `artistOfficialUrl`,
`artistWikipediaUrl`, `artistDiscogsUrl`, `releaseOfficialUrl`,
`releaseWikipediaUrl`, `releaseDiscogsUrl`, `discogsId`, `appleMusicId`,
`roonAlbumTag`, `roonTrackTag` (`lyricsUrl` était en fait déjà couvert,
vérifié en relisant le code existant avant d'ajouter un doublon). Les MBIDs/
AcoustID restent volontairement non collectés, comme en mode fichier unique
(lecture seule).

---

## 🎯 Dixième round — focus demandé : détection et suppression de doublons

Trois bugs réels trouvés en relisant `DuplicateDetector.java` et
`DuplicatesDialog.java` en détail.

### Bug 1 (le plus impactant) : la détection de doublons ignore silencieusement les fichiers masqués par un filtre actif

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/MainFrame.java`
**Méthode :** `detectDuplicates()`

```java
List<FileEntry> all = new ArrayList<>();
for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));
```
`getRowCount()`/`get(i)` portent sur `visible` — la vue **déjà filtrée**
(recherche, statut...), jamais sur toute la bibliothèque. C'est une
distinction déjà établie et documentée ailleurs dans ce même fichier
(`refreshStats()` : *"tableModel.allEntries() (jamais affecté par le
filtre, contrairement à getRowCount()/get() qui portent sur la vue déjà
filtrée)"*), mais pas appliquée ici.

**Conséquence concrète :** si un filtre est actif au moment de cliquer
"Détecter les doublons" (recherche en cours, filtre sur un statut...), les
fichiers masqués sont invisibles à la détection. Un vrai doublon dont une
seule des deux copies passe le filtre n'est **jamais signalé**, sans aucun
avertissement — l'utilisateur peut légitimement croire sa bibliothèque
propre alors que la détection n'a scanné qu'un sous-ensemble.

Corrigé — utilise désormais `tableModel.allEntries()` (le pattern déjà
établi ailleurs), la garde "aucun fichier chargé" corrigée de la même façon
par cohérence.

### Bug 2 : deux orthographes du même artiste/titre (avec/sans accents) ne sont jamais reconnues comme doublons

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/DuplicateDetector.java`
**Méthode :** `normalize()` (utilisée par le niveau `TITLE_HEURISTIC`)

```java
return s.toLowerCase().replaceAll("[^a-z0-9]", "");
```
`[a-z0-9]` ne matche que l'ASCII pur — un caractère accentué comme "é" ne
matche ni `a-z` ni `0-9`, donc `[^a-z0-9]` le désigne comme "à supprimer" et
la lettre entière disparaît, plutôt que d'être ramenée à sa forme sans
accent. Testé concrètement :
```
"Céline Dion" → "clinedion"
"Celine Dion" → "celinedion"
```
Deux chaînes différentes — un doublon bien réel (variation d'accentuation
très courante dans une vraie bibliothèque : Céline Dion, Beyoncé, Mötley
Crüe, Björk...) n'était jamais détecté par le niveau titre.

Corrigé par décomposition Unicode NFD (sépare la lettre de sa marque
d'accent) puis suppression des seules marques combinantes, en gardant la
lettre de base :
```java
String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
n = n.replaceAll("\\p{M}", "");
return n.toLowerCase().replaceAll("[^a-z0-9]", "");
```
Vérifié : les deux variantes ci-dessus donnent maintenant `celinedion` dans
les deux cas.

### Bug 3 : un fichier ALAC (sans perte) et un AAC (avec perte) dans un même conteneur .m4a reçoivent le même score de qualité

**Fichier :** `opentagger/src/main/java/com/opentagger/ui/DuplicateDetector.java`
**Méthode :** `qualityScore()` — utilisée pour choisir automatiquement quel
fichier GARDER lors d'une suppression de doublons ("★ Recommandé",
"Sélection intelligente")

```java
case "alac" -> 900_000;
case "m4a"  -> 700_000;
```
Le cas `"alac"` ne peut **jamais** matcher un vrai fichier : ALAC (Apple
Lossless) est toujours stocké avec l'extension `.m4a` sur disque — il
n'existe pas d'extension `.alac` en pratique. Un fichier ALAC sans perte et
un fichier AAC avec perte partageant la même extension `.m4a` recevaient
donc exactement le même score de format (700 000), différenciés seulement
par la taille — risque concret de recommander/pré-cocher pour suppression
le **master sans perte** au profit d'une copie compressée simplement plus
grosse en taille (durée légèrement différente, tags plus lourds...).

Corrigé — un fichier `.m4a` est désormais sondé via jaudiotagger (déjà
utilisé partout ailleurs dans le projet) pour lire son vrai type
d'encodage et distinguer les deux cas réels, avec repli silencieux sur le
score `.m4a` par défaut si la lecture échoue (fichier verrouillé/corrompu).

### Observation, pas corrigée : le nettoyage "dossiers vides" est un no-op silencieux après "Déplacer vers Doublons/"

`moveToDoublonsSelected()` déplace les fichiers cochés dans un sous-dossier
`Doublons/` **à l'intérieur du dossier parent d'origine** de chaque fichier.
La case "Supprimer les dossiers vides après" tente ensuite de nettoyer ces
mêmes dossiers parents — mais ils contiennent désormais toujours au moins
ce sous-dossier `Doublons/` fraîchement créé, donc ne peuvent structurellement
jamais être vus comme vides par `cleanEmptyAncestors()`. La case reste donc
sans le moindre effet après cette action précise (elle fonctionne
normalement après "Déplacer dans la corbeille…", qui ne crée pas ce
sous-dossier). Pas un bug de données (rien de cassé), juste une case à
cocher qui ne fait jamais rien dans ce contexte précis — pas corrigé, un
changement de comportement ici mériterait d'abord ton avis (avertir
l'utilisateur ? désactiver la case pour cette action ?).

---


---

## 🕳️ Onzième round — autres trous trouvés en continuant la recherche

### Bug confirmé et corrigé : la résolution des œuvres classiques (Opus/Mouvement/Catalogue) ne passait jamais par le cache SQLite

**Fichier :** `opentagger/src/main/java/com/opentagger/MusicBrainzClient.java`
**Méthode :** `resolveClassicalWork()` / `fetchWork()`

Vérifié par lecture complète : c'était la **seule** requête MusicBrainz de
tout le projet à ne jamais passer par `MetadataCache` (`getLookup`/
`putLookup`), contrairement à `searchRecording()`, `lookupRelease()`,
`lookupRecording()` qui le font tous systématiquement. Concrètement, pour
une bibliothèque classique — l'usage même que cette méthode cible — une
symphonie de 4 mouvements refait un aller-retour réseau complet vers
MusicBrainz pour CHAQUE mouvement traité (et même deux fois par mouvement :
une pour l'œuvre elle-même, une pour l'œuvre globale parente si besoin du
nombre total de mouvements), sans jamais réutiliser un résultat déjà obtenu
— alors que tous les autres appels MB équivalents ailleurs dans le projet
en bénéficient. Un vrai risque de ralentissement et de sur-sollicitation de
l'API MusicBrainz spécifiquement pour de grosses bibliothèques classiques.

Corrigé — `resolveClassicalWork()`/`fetchWork()` acceptent maintenant un
`MetadataCache`, avec le même schéma clé `"work:" + workMbid` que les autres
caches de lookup. Répercuté sur les **8 points d'appel** dans le projet
(`TagEnrichment.enrichClassicalWork()` et ses 3 appels dans
`TaggingWorker`, 1 dans `InfoCompleterWorker`, 1 dans
`AlbumCompletionWorker`, 1 dans `MatchDialog`, 1 dans `BatchProcessor`, 1
dans `App.java`) — le `cache` nécessaire était déjà disponible à chacun de
ces endroits (utilisé juste à côté pour `enrichGenre()`), donc aucun
changement structurel requis, juste le paramètre manquant ajouté partout de
façon cohérente.

### Observation, pas corrigée : `FolderWatcher` peut oublier de re-surveiller un dossier recréé au même chemin

**Fichier :** `opentagger/src/main/java/com/opentagger/FolderWatcher.java`

`watchedDirs` (utilisé pour éviter un double enregistrement) n'est jamais
nettoyé quand une clé de surveillance expire naturellement (dossier
supprimé — `if (!key.reset()) keyToDir.remove(key);` ne retire que de
`keyToDir`, jamais de `watchedDirs`). Si ce même chemin de dossier est
recréé plus tard (renommage d'album qui repasse par le même nom, par
exemple), `watch()` le verra comme "déjà surveillé" et ne l'enregistrera
jamais réellement — les nouveaux fichiers qui y arrivent ensuite ne
déclencheraient plus la détection automatique. Cas assez marginal
(nécessite suppression puis recréation exacte du même chemin en cours de
session), pas corrigé faute d'un scénario de reproduction confirmé, mais
signalé.

---

## 🚨 Douzième round — découverte majeure : plusieurs champs écrits sur disque n'étaient JAMAIS relus

En poursuivant l'exploration de `MainFrame.java`, revue complète de
`readTags()` — la fonction qui lit un fichier audio pour le charger dans
l'appli — croisée avec `TagWriter.java` (ce qui est réellement écrit sur
disque) et `model/TagInfo.java` (tous les champs existants). Écart
important trouvé, probablement le bug le plus impactant de toute cette
revue en termes de risque réel de perte de données.

### Le problème

**13 champs** sont bien écrits sur disque par `TagWriter` mais n'étaient
**jamais relus** par `readTags()` :

- Les cases à cocher **HD, Live, Greatest Hits, Soundtrack, Instrumental**
  (seules `isClassical`/`isCompilation` parmi les 7 drapeaux étaient
  relues)
- **ARTISTS**, **ARTISTS_SORT** (tous les artistes piste, feat. inclus)
- **MIXER_SORT**
- **FBPM** (BPM précis calculé par Essentia)
- Les **4 valeurs ReplayGain** (gain/pic piste et album)
- **Discogs ID**, **Apple Music ID**

### Pourquoi c'est grave, pas juste cosmétique

Concrètement, à chaque nouveau scan ou redémarrage de l'appli, un fichier
**déjà** tagué "Live" ou avec un Discogs ID déjà renseigné réapparaissait
avec ces champs **vides** dans l'interface — alors que la donnée est bien
là, sur le disque. Et comme `DetailPanel.collect()` réécrit `isHD`/`isLive`/
etc. **sans aucune condition** en mode fichier unique (`t.isHD =
chkHD.isSelected() ? "1" : "0";`), ouvrir un fichier déjà classé "Live",
modifier n'importe quel AUTRE champ (le genre, une faute de frappe...) puis
cliquer "Appliquer" **effaçait silencieusement** la case Live — sans que
rien ne l'indique. Même risque pour Discogs ID/Apple Music ID/ARTISTS/
MIXER_SORT. Pour le ReplayGain et le BPM précis (FBPM), le symptôme est
différent mais réel aussi : recalculés à chaque passe pour rien, puisque
l'appli "oubliait" systématiquement qu'ils étaient déjà présents.

### Corrigé

`readTags()` (`MainFrame.java`) lit maintenant ces 13 champs. Les 8 avec un
`FieldKey` standard (HD/Live/GreatestHits/Soundtrack/ARTISTS/ARTISTS_SORT/
MIXER_SORT/FBPM) suivent exactement le même schéma déjà utilisé pour les 80+
autres champs (`g(tag, FieldKey.X)`) — aucun risque, code identique à
l'existant. Les 5 champs sans `FieldKey` (ReplayGain ×4, Discogs ID, Apple
Music ID, IS_INSTRUMENTAL) nécessitaient une nouvelle méthode
`getCustomField()`, écrite en miroir **exact** des conventions déjà en place
côté écriture (`TagWriter.setCustomField()`) : frame TXXX pour l'ID3v2,
atome freeform `----:com.apple.iTunes:NAME` pour le MP4, commentaire Vorbis
en majuscules pour FLAC/OGG — même dispatch par type de tag, aucune
convention inventée.

**Note d'honnêteté :** cette partie (`getCustomField()`) n'a pas pu être
testée par compilation dans cet environnement (Maven Central bloqué,
rappel en tête de ce rapport) — la logique suit fidèlement le code
d'écriture existant et les conventions standards de jaudiotagger, mais
mérite un test réel de ta part sur un fichier ayant déjà ces valeurs
(rouvrir un fichier avec un ReplayGain/Discogs ID connu et vérifier qu'il
s'affiche bien) avant de faire confiance à 100% à cette partie précise.

---


## 🕸️ Treizième round — bug systémique : de nombreuses actions en masse ignorent silencieusement les fichiers filtrés

Le bug le plus **large en portée** de toute cette revue, trouvé en explorant
le reste de `MainFrame.java`. `FileTableModel` distingue deux listes :
`entries` (TOUS les fichiers chargés) et `visible` (la vue après filtre actif
— recherche, chip de statut cliqué). `tableModel.getRowCount()`/`get(i)`
portent sur `visible` ; `tableModel.allEntries()` (déjà existant, déjà utilisé
correctement à plusieurs endroits comme `refreshStats()`) porte sur `entries`.

**Le problème :** une bonne vingtaine d'actions itéraient sur
`getRowCount()`/`get(i)` alors qu'elles sont censées couvrir **toute la
bibliothèque**, pas la vue filtrée du moment. Concrètement : si un filtre est
resté actif (recherche, ou un chip de statut cliqué) — même sans rapport avec
l'action lancée — les fichiers masqués sont **silencieusement ignorés**, sans
aucun avertissement.

### Actions corrigées (24 emplacements au total, tous dans `MainFrame.java` sauf mention contraire)

| Action | Symptôme avant correctif |
|---|---|
| **`startTagging()` — "Tout tagger"** | Les fichiers cochés mais masqués par un filtre n'étaient jamais identifiés |
| **`saveAll()` — "Enregistrer tout"** | Les fichiers identifiés mais masqués n'étaient jamais écrits sur le disque |
| **`setAllSelected()` — "Tout cocher"/"Tout décocher"** | Les cases des fichiers masqués restaient dans leur état précédent malgré le nom "Tout" |
| **`RenamePreviewDialog.compute()` + exécution réelle du renommage** | Fichiers tagués masqués jamais renommés — ni dans l'aperçu, ni sur le disque (deux bugs distincts, même cause) |
| **`syncListenBrainz()`** | Synchronisation incomplète si un filtre est actif |
| **`forceRetag()` (sans sélection)** | Re-taguage "de tout" limité aux fichiers visibles |
| **`autoCompleteIncomplete()`** | Complétion des champs manquants limitée aux fichiers visibles |
| **`transcodeFiles()` (sans sélection)** | Transcodage "de tout" limité aux fichiers visibles |
| **`exportCsv()`** | Export CSV incomplet si un filtre est actif |
| **`exportPlaylist()`** (compte ET liste réelle) | Playlist exportée incomplète — un fichier tagué masqué en était silencieusement absent |
| **`openPodcastDialog()`** (candidats) | Candidats podcast limités aux fichiers visibles |
| **`deleteErrorFiles()`** | Fichiers en erreur masqués jamais proposés au nettoyage |
| **`submitAcoustId()` (sans sélection)** | Soumission "de tout" limitée aux fichiers visibles |
| **`stopAll()`** (reset des fichiers PROCESSING) | Un fichier PROCESSING masqué restait bloqué dans cet état indéfiniment après "Arrêter", même si le worker sous-jacent est bien annulé |
| **`refreshFolders()` — "Rafraîchir"** | Ne redécouvrait que les dossiers des fichiers visibles |
| **`detectLocalCompilations()`** | Détection de compilation faussée si des pistes de l'album sont masquées |
| **Auto-watch (`FolderWatcher`) — détection "déjà dans la table" + résolution du scanRoot** | Un fichier déjà présent mais masqué pouvait être réajouté en double |
| **`loadSingleFile()`** — dédoublonnage | Idem, sur le chargement d'un fichier unique |
| **`loadDirectory()`** — snapshot "déjà dans la table" | Un fichier déjà chargé mais masqué se faisait réajouter en double lors d'un rescan du même dossier |
| **Menu contextuel "Tagger cet album uniquement"** | Ne désélectionnait que les AUTRES fichiers visibles avant de lancer — un fichier coché mais masqué restait coché et se faisait taguer EN PLUS de l'album ciblé |
| **`AlbumCompletionWorker.doInBackground()`** (fichier séparé) | Ancres ET candidats pour "Compléter les albums" limités à la vue filtrée — lien direct avec le scénario "album incomplet" (NRJ Summer Hits) discuté précédemment : un candidat qui aurait parfaitement complété l'album pouvait être ignoré à cause d'un filtre sans rapport |
| `exportCsv()`/`openPodcastDialog()` — gardes "aucun fichier" | Message trompeur ("aucun fichier") si un filtre masque tout alors que des fichiers sont bien chargés (cohérence mineure, pas de perte de données) |

Rappel : `detectDuplicates()` avait déjà ce même bug, corrigé dans un round
précédent de cette revue — c'est en le retrouvant partout ailleurs dans le
fichier que l'ampleur réelle du problème est devenue visible.

### Pourquoi ça n'avait jamais été remarqué

Le symptôme n'apparaît QUE si un filtre est actif au moment de cliquer — un
utilisateur qui filtre sur "Erreurs" pour nettoyer, oublie de retirer le
filtre, puis clique "Tout tagger" plus tard dans la même session verrait un
sous-ensemble silencieusement ignoré, sans qu'aucun message ne l'indique.
Facile à ne jamais remarquer si on n'a pas justement cette séquence d'usage.

### Ce qui n'a pas été changé

Deux usages de `getRowCount()` restent **intentionnellement** tels quels,
vérifiés comme corrects : le chip "Total" de `refreshStats()` (son rôle
documenté est justement de refléter le filtre actif), et
`entryAtViewRow()`/`entriesAtViewRow()` qui prennent un index de vue en
paramètre (usage légitime, pas un bug).

## 🔄 Quinzième round — la mise à jour automatique risque d'échouer sur la plupart des installations Linux

**Fichier :** `opentagger/src/main/java/com/opentagger/UpdateChecker.java`
**Méthode :** `applyUpdate()`

Le jar téléchargé est créé via `Files.createTempFile()` (dans `/tmp` par
défaut) puis déplacé vers l'emplacement d'installation
(`$HOME/.local/share/opentagger/opentagger.jar`, voir
`install-opentagger.sh`) avec `StandardCopyOption.ATOMIC_MOVE`. Sur la
plupart des distributions Linux modernes basées sur systemd, `/tmp` est un
`tmpfs` — un système de fichiers **différent** de `$HOME`. Un déplacement
atomique entre deux systèmes de fichiers différents est structurellement
impossible côté OS et lève systématiquement
`AtomicMoveNotSupportedException` — ce qui aurait fait échouer "Mise à jour
échouée" à **chaque tentative**, sur la plupart des installations réelles,
pas seulement un cas limite rare.

Le projet avait déjà rencontré et résolu exactement ce même problème
ailleurs (`FileRenamer.moveFile()` — copie + suppression en repli quand le
renommage atomique cross-device échoue), mais ce correctif n'avait pas été
appliqué ici. Corrigé de la même façon :

```java
try {
    Files.move(downloadedJar, current,
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
} catch (java.nio.file.AtomicMoveNotSupportedException e) {
    Files.move(downloadedJar, current, StandardCopyOption.REPLACE_EXISTING);
}
```

### Autres fichiers vérifiés ce round, rien trouvé

`MbContributeDialog.java` (336 lignes, lu en entier — soumission de tags/
rating MusicBrainz), `PodcastDialog.java` et `VideoRecoveryDialog.java` (lus
en entier cette fois, pas seulement la logique cache/concurrence) — tous
propres.

## 🎛️ Seizième round — les derniers 5-10% : formulaires de `SettingsDialog.java`

Demande explicite de finir les parties de `SettingsDialog.java` pas encore
lues ligne à ligne (les `buildXxxPanel()` avec de la vraie logique, pas que
de la mise en page). Trois bugs trouvés.

### Bug 1 : plantage possible (NullPointerException) sur la liste des fournisseurs de pochette

**Méthode :** le bouton "Activer/Désactiver" de la liste des fournisseurs de pochette (CAA, Shazam, Last.fm, local, FanArt, Deezer)

```java
coverProviderEnabled.put(id, !coverProviderEnabled.get(id));
```

`coverProviderEnabled` n'est peuplée qu'avec exactement 6 identifiants
codés en dur. Or `Config.coverProviderOrder()` (qui alimente la liste
affichée) ne filtre **jamais** un identifiant inconnu déjà présent dans le
fichier de config (il ne fait qu'ajouter ceux qui manquent, jamais retirer
un intrus) — un fichier de settings édité à la main, corrompu, ou contenant
un identifiant d'une version passée/future d'OpenTagger produirait un `id`
absent de la map. `coverProviderEnabled.get(id)` retourne alors `null`, et
`!null` lève une `NullPointerException` au unboxing — plantage de ce
bouton. Corrigé avec `getOrDefault(id, true)`, déjà le pattern utilisé
partout ailleurs dans ce même fichier pour lire cette map.

### Bug 2 : le bouton "Se connecter à MusicBrainz" reste bloqué sur "Ouverture du navigateur…" après un échec

**Méthode :** `refreshMbStatus()`

Après un échec de connexion OAuth (identifiants refusés, délai dépassé...),
`refreshMbStatus()` réactive et raffiche le bouton mais ne réinitialisait
jamais son texte — resté affiché "Ouverture du navigateur…" indéfiniment,
même une fois redevenu parfaitement cliquable, jusqu'à ce que l'utilisateur
ferme et rouvre les Préférences. Corrigé : le texte d'origine
("🔑 Se connecter à MusicBrainz") est maintenant restauré à chaque passage
en état non-connecté.

### Bug 3 : le surlignage de la recherche dans les réglages peut rester bloqué en jaune

**Méthode :** `flashComponent()` (recherche "🔎 Rechercher un réglage…")

```java
Color original = c.getBackground();
...
Timer t = new Timer(1200, e -> { c.setBackground(original); ... });
```

`onSettingsSearch()` se déclenche à **chaque frappe** (pas de debounce). Si
l'utilisateur tape plusieurs caractères en moins de 1200 ms et que la
recherche continue de matcher le **même** composant (cas très courant :
taper "enreg" puis "enregi" puis "enregis..." qui matchent tous le même
réglage), le second appel à `flashComponent()` capture le **jaune du flash
en cours** comme "couleur d'origine" à restaurer — le composant reste alors
bloqué en jaune indéfiniment une fois le minuteur du second appel écoulé.
Corrigé en ne gardant qu'un seul flash actif à la fois : un nouveau flash
arrête et restaure proprement l'ancien avant de mémoriser la vraie couleur
d'origine.

### Reste vérifié ce round, rien d'autre trouvé

Listes réordonnables (pays préférés, locales de translittération, séries
de compilations, fournisseurs de pochette) — pattern identique répété 4
fois, correct et cohérent partout. Gestion des scripts utilisateur
(nouveau/supprimer/activer/renommer) — correcte, y compris la sauvegarde
implicite de l'édition en cours avant de changer de sélection.

**Complément — `SettingsDialog.java` désormais couvert à 100 % ligne par
ligne** : tous les `buildXxxPanel()` restants relus en entier
(`buildMatchingPanel`, `buildTagsPanel`, `buildRenamePanel`,
`buildAudioPanel`, `buildToolbarPanel`, `buildStartupPanel`,
`buildApiPanel`) — aperçu de renommage en temps réel, liste des dossiers de
démarrage avec détection de dossier manquant, liste des actions de barre
d'outils, tout est cohérent. Aucune anomalie supplémentaire trouvée au-delà
des 3 bugs ci-dessus.
