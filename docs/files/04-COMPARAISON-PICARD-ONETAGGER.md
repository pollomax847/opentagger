# OpenTagger — Comparaison avec MusicBrainz Picard et OneTagger

Mise en regard des bugs trouvés/corrigés dans OpenTagger avec des bugs
connus et documentés publiquement de MusicBrainz Picard (l'éditeur de
référence, dont OpenTagger porte plusieurs algorithmes) et OneTagger
(Marekkon5/onetagger, tagueur concurrent). Sources : `NEWS.md` de Picard et
les issues GitHub publiques d'OneTagger — pas leur code source (non chargé
dans cet environnement), donc comparaison sur les bugs *documentés
publiquement*, pas sur une relecture de leur code.

---

## 📊 Comparaison avec MusicBrainz Picard et OneTagger (Marekkon5)

Demandé : mettre en regard les bugs trouvés/corrigés dans OpenTagger avec des
bugs connus des deux références du domaine — **MusicBrainz Picard**
(l'éditeur officiel, dont OpenTagger porte explicitement plusieurs algorithmes
— `TrackMatcher`, `ReleaseMatcher`, `AcoustIdSubmitter`) et **OneTagger**
(Marekkon5/onetagger, tagueur Rust concurrent). Sources : `NEWS.md` de Picard
(historique de versions complet) et les issues GitHub publiques d'OneTagger —
pas le code source des deux projets (non chargé dans cet environnement), donc
la comparaison porte sur les bugs *documentés publiquement*, pas sur une
relecture de leur code.

### Correspondances directes avec des bugs déjà rencontrés (et corrigés) chez Picard

| Bug OpenTagger (ce rapport) | Équivalent Picard documenté |
|---|---|
| `LocalCorrector.titleCase()` — reste du mot pas mis en minuscule | **PICARD-3000** — *"Children's Music is shown as 'Children'S Music'"* : même famille de bug (title-case mal géré sur un mot déjà partiellement en majuscule/avec apostrophe), corrigé dans Picard 2.13. |
| `CaaClient` — extension forcée à `.jpg` même pour du PNG | **PICARD-2919** — *"Unrecognized image data" error when fetching GIF Cover Art* : même famille (mauvaise détection de format d'image en pochette), et **PICARD-1976** — *"Cover art providers do not handle URLs with query arguments correctly"*. |
| `TrackMatcher`/`ReleaseMatcher` — scoring composite pondéré | **PICARD-3080** *(Consider track no. when matching files to AcoustID results)*, **PICARD-2867**/**PICARD-3284**/**PICARD-3291** *(improve release matching with tiered scoring + barcode/catno/date)* — Picard a lui-même dû ajouter ces signaux progressivement ; `ReleaseMatcher.java` le documente explicitement : **"Phase 1 seulement — les tiers identifiers (barcode/catno/isrc) et similarity du vrai modèle Picard... hors scope ici"**. Autrement dit : OpenTagger a sciemment porté une version *partielle* du modèle Picard, et le tier manquant (identifiers) est précisément celui que Picard a lui-même ajouté récemment (PICARD-3291, 2026). C'est un vrai écart fonctionnel documenté, pas juste une comparaison théorique. |
| Cascade de repli nom de fichier (`extractFromFilenameIfEmpty`, `parseFilename`) | **PICARD-1568**, **PICARD-2419**, **PICARD-3229**, **PICARD-3149**, **PICARD-3067** — Picard a corrigé de nombreux bugs sur ce même problème (parsing piste+titre depuis le nom de fichier : "index out of range" sur `1.opus`, "UB40" pris pour un numéro de piste, point résiduel après le numéro...). OpenTagger n'a pas ces bugs précis (regex différente), mais c'est un signal que cette classe de parsing est intrinsèquement fragile — le pattern `FILE_TRACK_ARTIST_TITLE`/`FILE_ARTIST_TITLE` de `LocalCorrector` n'a pas été testé contre des noms de fichiers aussi pathologiques que ceux remontés à Picard (un seul chiffre, extension inhabituelle, tiret en début de nom...). |
| `SafeTableRowSorter` (course EDT/thread pendant le tri) | **PICARD-2953** *(Windows: incorrect sort order)*, **PICARD-3014** *(sorting on macOS does not sort empty values as expected)*, **PICARD-3116** *(sorting columns does not work on Apple M2)* — même classe de bug (tri de tableau UI peu fiable en environnement concurrent/multi-plateforme), déjà bien couverte côté OpenTagger par ce filet de sécurité. |
| Renommage cross-device (vérif taille avant suppression) | **PICARD-2019** *(Saving tracks to SMB share on Windows 10 results in ever more nested folders)*, **PICARD-2021** *(SameFileError when moving files between network path and local path on Windows)* — Picard a eu les mêmes classes de bugs sur le renommage réseau/cross-device qu'OpenTagger a anticipées (`FileRenamer.moveFile()`, commentaire explicite sur mergerfs/NAS). Ici OpenTagger est en avance : ces cas sont déjà gérés. |

### Correspondances avec des bugs connus (ouverts ou fermés) d'OneTagger

| Bug/limite OpenTagger | Équivalent OneTagger documenté |
|---|---|
| Pipeline CLI sans SongRec/AudD (découverte majeure ci-dessus) | **Issue #152** *("AutoTag broken... Missing artist tag!")* — le matching d'OneTagger dépend fortement des tags déjà présents et échoue durement s'ils manquent, sans repli robuste. OpenTagger GUI a la cascade la plus complète des trois (SongRec en tête, indépendant des tags) — mais ironiquement, c'est justement cette cascade qui **manque entièrement** au pipeline CLI d'OpenTagger, le ramenant au niveau de fragilité d'OneTagger sur ce point précis. |
| `CaaClient`/pochette — bug MIME | **Issue #36** *("Not writing/overwriting Album Art")*, **Issue #38** *("No album art")* — la pochette qui ne s'écrit pas ou mal est un problème récurrent et non résolu chez OneTagger (issues ouvertes sans fix identifié), alors que côté OpenTagger c'était un bug de métadonnée (MIME type), pas une absence totale — sévérité moindre. |
| `GenreFilter`/`enrichGenre` (ne remplit que si vide, jamais de fusion) | **Issue #220** *("Merge/append genre & styles results in duplicate entries")* — OneTagger a un vrai bug de doublons de genre lors de la fusion. OpenTagger évite structurellement cette classe de bug : `TagEnrichment.enrichGenre()` ne remplit **jamais** un champ déjà rempli (pas de fusion du tout), donc pas de doublons possibles — choix de conception plus prudent, mais aussi moins permissif si l'utilisateur veut vraiment cumuler plusieurs sources. |
| `TrackMatcher.titleSimilarity()` (fuzzy, tolère parenthèses/qualificatifs) | **Issue #245** *("Unable to Match Songs to Beatport when Beatport includes (Extended Mix) in the Title")* — OneTagger n'a pas de mécanisme équivalent au nettoyage de titre (`cleanTitle`, suppression du contenu entre parenthèses) qu'OpenTagger applique systématiquement en repli SongRec/MB. C'est un axe où OpenTagger est structurellement plus robuste. |
| `FileRenamer.moveFile()` (vérif taille, gestion cross-device) | **Issue #157** *("Unable to rename a file... data provided contains a nul byte")* — OneTagger plante purement et simplement sur un octet nul dans un tag lors du renommage, sans repli. OpenTagger ne semble pas avoir de garde explicite contre un octet nul dans un champ de tag avant de construire un nom de fichier — **point à vérifier** (voir ci-dessous), potentiel angle mort partagé. |

### Point à vérifier suite à cette comparaison (pas encore fait)

L'issue OneTagger #157 (octet nul dans un tag → échec de renommage) soulève une
question légitime pour OpenTagger : `FileRenamer`/`TaggerScript` ne semblent
avoir aucune étape de nettoyage explicite qui rejetterait ou nettoierait un
caractère nul (`\0`) avant de construire un chemin de fichier — un tag corrompu
(mauvais encodage, script utilisateur buggé) pourrait en théorie produire le
même échec. Pas confirmé comme bug réel dans OpenTagger (pas reproduit, juste
un angle mort structurel repéré par analogie) — à tester si tu veux creuser.

### Constat général

- Sur le **matching/scoring**, OpenTagger est structurellement plus proche de
  l'état de l'art (Picard) que d'OneTagger — le score composite pondéré et le
  nettoyage de titre (parenthèses, feat.) couvrent des classes de bugs
  qu'OneTagger a en ouvert. Mais le portage du modèle Picard reste
  **partiel et documenté comme tel** (tier "identifiers" barcode/catno/isrc
  manquant dans `ReleaseMatcher`).
- Sur la **robustesse du renommage/fichiers**, OpenTagger est en avance sur
  les deux (gestion cross-device, nettoyage AppleDouble, vérification de
  taille) — aucun équivalent documenté aux crashs bruts de Picard ou OneTagger
  sur ce point.
- Sur la **cohérence inter-pipelines**, c'est l'inverse : Picard n'a qu'UN
  seul pipeline de tagging (pas de mode CLI séparé avec sa propre logique
  divergente), donc cette classe de bug ne peut structurellement pas exister
  chez lui. OneTagger a un mode CLI mais son "AutoTag" partage le même moteur
  que le GUI (pas de second `findTags()` réécrit à la main). **OpenTagger est
  le seul des trois où ce bug peut exister — et il existe** (BatchProcessor).

---

