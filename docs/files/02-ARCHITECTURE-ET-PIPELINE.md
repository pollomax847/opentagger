# OpenTagger — Architecture et cohérence des pipelines

Ce fichier regroupe les découvertes **structurelles** (pas des bugs d'une
ligne, mais des questions de conception qui traversent plusieurs fichiers) :
l'absence de SongRec/AudD dans le pipeline CLI, la matrice de complétude
comparant les 5 pipelines d'enrichissement, et la vérification du mécanisme
de complétion d'album. Voir `01-BUGS-CORRIGES.md` pour les correctifs déjà
appliqués, et `05-SUGGESTIONS-AMELIORATION.md` pour ce qui pourrait être fait
de ces constats (notamment la suggestion n°1 : unifier les pipelines).

---

## 🚨 Découverte majeure — le pipeline CLI (`--dossier`/`BatchProcessor`) n'a JAMAIS SongRec ni AudD

Demande explicite : vérifier que la chaîne d'identification (SongRec → renommage) est
cohérente et que les options sont bien câblées partout. Réponse : **non, pas dans le
pipeline CLI**.

### Ce que fait le pipeline de référence (GUI, `TaggingWorker.findTags()`)

Cascade complète, dans l'ordre : réparation M4A → indice d'album (tag > dossier
parent) → cache/historique local → confiance aux tags MB existants (avec
vérification SongRec anti-faux-positif) → skip SongRec optionnel si MB texte
déjà confiant → **SongRec (source principale, marche même avec des tags
faux)** → lookup MBID existant → AcoustID → recherche texte MB (avec repli nom
de fichier, artiste simplifié, titre+album, détection non-Latin) → **SongRec en
second recours** → AcoustID en dernier recours → **AudD en tout dernier
recours**.

### Ce que fait réellement `BatchProcessor.findTags()` (mode `opentagger --dossier`)

```java
// Stratégie 1 : AcoustID (empreinte audio) si activé
if (useAcoustId) { ... new AcoustIdClient().identify(fichier) ... }

// Stratégie 2 : recherche MusicBrainz par tags existants
String artiste = getTag(fichier, FieldKey.ARTIST);
String titre   = getTag(fichier, FieldKey.TITLE);
...
List<TagInfo> results = mbClient.searchRecording(artiste, titre);
```

C'est TOUT. **Ni SongRec, ni AudD ne sont jamais appelés** — confirmé : aucune
référence à `SongRecClient` ou `AudDClient` nulle part dans `BatchProcessor.java`.
Concrètement, pour un fichier avec des tags absents ou faux et `--acoustid` non
demandé : le pipeline CLI ne cherche même pas à l'identifier (`artiste.isBlank()
&& titre.isBlank()` → retour immédiat, aucun repli nom de fichier). C'est
exactement le scénario que SongRec est censé couvrir en premier dans le GUI
("identifie même avec de faux tags").

### Autres options/étapes absentes du pipeline CLI (vérifié par grep, zéro référence)

- **ReplayGain** (`ReplayGainAnalyzer`) — jamais calculé en CLI, même si activé
  dans les Réglages.
- **Soumission MusicBrainz** (`MusicBrainzOAuth` — tags/rating/collection) —
  jamais appelée.
- **Soumission AcoustID** (`AcoustIdSubmitter`) — jamais appelée.
- **Essentia** (mood/key) — jamais appelé.
- **Paroles synchronisées (.lrc)** et **pochette recopiée en fichier à côté**
  (`cover.save_to_file`) — ignorés, car ces deux options ne sont câblées que
  dans `TagEnrichment.saveEntry()`, jamais utilisé par `BatchProcessor`.
- **Restauration du contexte compilation** (`preserveCompilationAlbum` —
  évite qu'une compilation identifiée sans release compilation perde son
  album/albumArtist d'origine) — absent.
- **Repli nom de fichier**, **artiste simplifié**, **titre+album**, **détection
  non-Latin** (routage vers SongRec en priorité) — tous absents ; seule une
  recherche MB brute sur les tags existants est tentée.

### Bug supplémentaire trouvé dans le raccourci cache (`BatchProcessor.processOne()`)

```java
if (cachedMbid != null) {
    TagInfo hist = cache.getTaggingHistory(cachedMbid);
    if (hist != null) {
        writer.write(fichier, hist);   // écrit… et s'arrête là
        appliques.incrementAndGet();
        return;
    }
}
```
Même quand un fichier est reconnu via l'historique local, ce chemin **n'applique
jamais le renommage** (`maskIndex` totalement ignoré ici, contrairement au chemin
normal juste en dessous) — un fichier déjà identifié lors d'un scan précédent et
retrouvé par un second scan CLI avec `--masque N` ne sera jamais renommé, alors
que le même scénario côté GUI (cache hit dans `TaggingWorker.findTags()`) suit
bien tout le pipeline normal jusqu'au renommage.

### Pourquoi c'est resté invisible

Le CLI n'est vraisemblablement quasi jamais utilisé par l'utilisateur (le
README ne documente que l'usage GUI et `install-opentagger.sh` installe un
lanceur GUI) — mais le code CLI existe, est maintenu (BPM/mood/lyrics y ont
été ajoutés à des dates différentes d'après les commentaires "même gap :
absent du CLI/batch jusqu'à présent"), et personne ne semble avoir remarqué
que SongRec/AudD n'y ont eux jamais été ajoutés du tout.

### Correctif proposé (pas encore appliqué — portée trop large pour un patch sûr sans validation)

Plutôt que corriger en aveugle, la vraie question à trancher avant de coder :
`BatchProcessor` devrait-il **appeler `TagEnrichment.identifyFromAudio()`**
(la cascade SongRec→AcoustID→AudD déjà extraite et partagée, voir
`TagEnrichment.java`) en repli quand la recherche texte échoue, et migrer
vers `TagEnrichment.saveEntry()` pour la partie écriture/renommage/soumissions
au lieu de dupliquer sa propre logique ? Ça réglerait d'un coup SongRec, AudD,
ReplayGain, OAuth, AcoustID submit, LRC, cover-to-file et le bug du cache-hit
sans renommage — mais c'est un changement structurel, pas un correctif d'une
ligne, donc je ne l'ai pas appliqué sans ton feu vert.

---

-e 
---

## 🔬 Recherche minutieuse — la matrice de complétude par pipeline

Demande explicite : chercher ce qui pourrait être amélioré côté "intelligence"
si ce n'est pas déjà fait. En creusant au-delà du seul cas CLI/BatchProcessor
déjà documenté ci-dessus, il apparaît que **le même problème existe, à des
degrés divers, entre TOUS les pipelines qui enrichissent un `TagInfo`** — pas
seulement CLI vs GUI. Voici la matrice complète, vérifiée fichier par fichier
(grep + lecture) sur les 5 endroits qui appellent tout ou partie de la
cascade d'enrichissement :

| Étape | `TaggingWorker`(GUI, auto) | `InfoCompleterWorker`(Passe complète) | `AlbumCompletionWorker` | `BatchProcessor`(CLI) | `MatchDialog`(manuel) |
|---|---|---|---|---|---|
| SongRec / AudD (identification) | ✅ | *(N/A, ré-identifie pas)* | *(N/A)* | ❌ **(voir découverte majeure)** | *(N/A, recherche manuelle)* |
| Genre (Discogs/Last.fm) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Détection classique + Opus/Catalogue | ✅ | ✅ | ✅ | ✅ (via `corrector.correct()`) | ✅ |
| Mood (Last.fm) | ✅ | ✅ | ❌ | ✅ | ❌ |
| BPM (ffmpeg) | ✅ | ✅ | ❌ | ✅ | ❌ |
| **Essentia (mood/key ML)** | ✅ | ❌ | ❌ | ❌ | ❌ |
| Paroles (Lyrics.OVH/lrclib) | ✅ | ✅ | ❌ | ✅ | ❌ |
| Translittération artiste | ✅ | ✅ | ✅ | ✅ | ❌ |
| URLs artiste (Last.fm) | ✅ | ❌ | ❌ | ✅ | ❌ |
| Empreinte AcoustID (calcul local) | ✅ | ❌ | ✅ | ✅ | ✅ |
| ReplayGain piste | ✅ | ❌ | *(album, worker dédié)* | ❌ | ❌ |

**Essentia n'est câblé que dans UN SEUL pipeline sur cinq** (`TaggingWorker`) —
c'est la divergence la plus nette : un utilisateur qui a installé Essentia
pour le mood/tonalité ML ne bénéficie de rien de tout ça dès qu'il utilise
"Passe complète" (`InfoCompleterWorker`), la correspondance manuelle
(`MatchDialog`), la complétion d'album (`AlbumCompletionWorker`) ou le CLI —
seul le taguage automatique initial en profite.

**`InfoCompleterWorker` ne calcule jamais l'empreinte AcoustID** — vérifié par
grep, zéro référence à `Fingerprinter` dans tout le fichier, alors que les
4 autres pipelines le font. Concret : un fichier identifié une première fois
par SongRec/texte (sans empreinte calculée à l'époque), puis repassé
uniquement par "Passe complète" plus tard, n'aura jamais d'empreinte —
et donc jamais accès au niveau `FINGERPRINT_EXACT` de `DuplicateDetector`
tant qu'il ne repasse pas par un des 4 autres pipelines.

**`AlbumCompletionWorker` et `MatchDialog` n'enrichissent ni mood, ni BPM, ni
paroles** — plausible comme limite de portée volontaire (leur rôle est de
retrouver une tracklist ou confirmer un match précis, pas de tout enrichir),
mais l'incohérence est réelle : les deux appellent bien `enrichGenre()` et
`enrichClassicalWork()`, donc "on n'enrichit que le strict nécessaire" n'est
pas le principe réellement appliqué — c'est un sous-ensemble non expliqué de
la liste complète.

### Bug de conception supplémentaire trouvé : Essentia écrase le BPM sans vérifier s'il est déjà rempli

Dans `TaggingWorker.processEntry()`, l'ordre est : BPM ffmpeg (seulement `if
(bpmEnabled && best.bpm.isBlank())`) **puis** Essentia, qui écrit
`info.bpm = ...` **sans condition** dans `EssentiaClient.extractFields()` dès
qu'il détecte un BPM, même si le champ était déjà rempli par ffmpeg juste
avant. Tout le reste du pipeline suit scrupuleusement la règle "on ne remplit
que si vide" (`enrichGenre`, `enrichMood`, `fillBlank`...) — ce cas est
la seule exception, sans commentaire l'expliquant. Ce n'est peut-être pas
un bug si Essentia est jugé plus fiable et censé prévaloir intentionnellement,
mais rien dans le code ne le documente comme un choix — à clarifier (au
minimum par un commentaire, au mieux en rendant ce choix explicite/configurable).

### Amélioration identifiée : `acoustIdResultPlausible()` ne vérifie que l'artiste, jamais la durée

Le garde-fou anti-faux-positif AcoustID (`TaggingWorker.acoustIdResultPlausible()`)
compare uniquement la similarité artiste existant vs artiste trouvé — jamais
la durée du fichier vs la durée de l'enregistrement candidat, alors que
`AudioDuration`/`TrackMatcher.lengthScore()` existent déjà ailleurs dans le
code pour ce calcul précis. Picard a le même genre de garde-fou côté
soumission (PICARD-2396, "ne pas soumettre d'empreinte AcoustID en cas
d'écart de durée significatif") — ajouter une vérification de durée ici
renforcerait la détection de faux positifs AcoustID (fingerprint qui matche
un enregistrement complètement différent par coïncidence acoustique) sans
casser le garde-fou existant, juste en le complétant.

### Amélioration déjà notée dans `docs/roadmap.md`, toujours pas faite

`TagEnrichment.translateArtist()` ne traite que le champ **Artiste** — pour
un enregistrement classique où compositeur/chef d'orchestre/orchestre sont
remplis via les relations MusicBrainz (résolution classique déjà en place,
voir `resolveClassicalWork`), ces mêmes champs peuvent contenir un nom non
Latin sans jamais être translittérés, contrairement à Artiste. Le
commentaire de code le note déjà lui-même comme "à confirmer avant d'investir
dessus" — je n'ai pas de bibliothèque classique sous la main pour confirmer
si c'est un problème réel en pratique, mais la lacune est bien réelle dans
le code, pas seulement théorique.

---

-e 
---

## ❓ Vérification demandée — scénario "album présent mais incomplet" (ex. compilation genre NRJ Summer Hits)

Question posée : pour un album/compilation déjà présent dans la
bibliothèque mais auquel il manque des pistes (les fichiers manquants
existant ailleurs, non identifiés), le pipeline de complétion d'album
(`AlbumCompletionWorker`, "Compléter les albums…") est-il bien codé pour
détecter et signaler ce cas ?

**Réponse : le mécanisme fonctionne, avec une précondition importante et un
vrai trou de visibilité — corrigé ci-dessous.**

**Précondition à connaître** : ce pipeline ne DÉCOUVRE pas un album à partir
de rien — il ne peut compléter que les albums ayant déjà **au moins une
piste identifiée de façon fiable** (statut TAGGED ou IDENTIFIED, via
SongRec/AcoustID/MBID, pas une recherche texte) présente dans la
bibliothèque chargée. Si aucune piste de "NRJ Summer Hits 2010" n'est
encore identifiée du tout, l'album n'est pas encore "connu" de l'appli et
cette passe ne peut rien faire pour lui — il faut d'abord tagger au moins un
morceau de la compilation (manuellement ou via "Tout tagger") pour que
l'album entre dans le radar de la complétion.

Une fois cette précondition remplie, l'algorithme (déjà détaillé dans le
Javadoc de la classe) récupère la tracklist complète sur MusicBrainz,
repère les pistes manquantes, et cherche parmi les fichiers SKIPPED/PENDING
de toute la bibliothèque un titre correspondant — peu importe où ils se
trouvent sur le disque. Deux bugs déjà trouvés et corrigés plus haut dans ce
rapport touchent directement ce mécanisme et s'appliquent pleinement à ton
scénario :
- Le **round 7** (candidats de même titre qui s'écrasaient silencieusement)
  — pertinent si plusieurs pistes égarées de compilations différentes
  partagent un titre générique.
- Le fait qu'`artistCompatible()` fasse confiance au seul titre quand le
  candidat n'a AUCUN artiste renseigné (cas très courant pour un fichier
  isolé mal tagué, exactement ton scénario) — garde-fou volontairement
  permissif dans ce cas précis, pas un bug, mais à avoir en tête : un
  fichier totalement étranger avec un titre par coïncidence proche pourrait
  en théorie être réclamé pour la mauvaise piste s'il n'a pas d'artiste du
  tout.

**Le vrai trou trouvé maintenant, et corrigé** : la passe indiquait bien, EN
DÉBUT de traitement, "Album : X (N piste(s) trouvée(s) / M au total)" — mais
si une piste manquante ne trouvait AUCUN candidat correspondant nulle part
dans la bibliothèque, ce cas était simplement ignoré en silence, sans
JAMAIS le signaler. Impossible de savoir, à la fin, **lesquelles**
précisément manquaient encore — seulement le compte de départ. Corrigé :
`processRelease()` (`AlbumCompletionWorker.java`) garde maintenant la trace
des pistes non retrouvées et publie, à la fin du traitement de chaque
album, un message explicite dans le Journal :
```
⚠ NRJ Summer Hits 2010 : album toujours incomplet — 3 piste(s) introuvable(s)
  dans la bibliothèque : 4. Titre A, 9. Titre B, 14. Titre C
```
C'est exactement l'information qui manquait pour répondre à ta question
"est-ce que l'appli détecte et me dit lesquels sont sans album complet" —
maintenant, oui, explicitement, piste par piste, à la fin de chaque passe.

---

