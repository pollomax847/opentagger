# OpenTagger — Zones auditées (sans anomalie) et zones restantes

Fichier de référence : ce qui a été vérifié sans trouver de problème (pour
éviter de re-regarder deux fois la même chose), et ce qui n'a **pas encore**
été audité, pour cibler la suite efficacement. Les bugs eux-mêmes sont dans
`01-BUGS-CORRIGES.md`.

---

## ✅ Zones auditées, aucune anomalie trouvée

Revue ligne par ligne ou ciblée, sans bug identifié :

- **Threads / `ExecutorService`** (TaggingWorker, SaveWorker,
  AlbumCompletionWorker, VideoRecoveryWorker, TranscodeWorker,
  InfoCompleterWorker, PodcastMatcher) — `shutdown()`/`shutdownNow()`
  cohérents partout, pas de fuite de pool identifiée.
- **`FpcalcInstaller`** (extraction ZIP/tar.gz) — pas de faille zip-slip, le
  chemin de destination est fixe, jamais dérivé du nom d'entrée de l'archive.
- **`MetadataCache`** (SQLite) — toutes les requêtes avec valeurs variables
  passent par `PreparedStatement`, pas d'injection SQL possible.
- **`FileRenamer.moveFile()`** (déplacement cross-device NAS/MergerFS) —
  vérification de taille avant suppression de la source, `COPY_ATTRIBUTES`
  volontairement omis (documenté), sémaphore de limitation cohérent.
- **`TrackMatcher`** (port de l'algo de matching Picard : `wordSimilarity`,
  `titleSimilarity`, `lengthScore`, `scoreTrack`) — logique vérifiée
  correcte, y compris le cas `Integer == int` (auto-unboxing sûr ici).
- **`DuplicateDetector`** / **`PodcastMatcher`** — cohérents avec le README,
  ordre de priorité des formats (FLAC > ALAC > M4A > OGG > MP3) correct.
- **`TagWriter`** — préservation du timestamp (`savedTimestamp > 0`), fusion
  avec les tags existants, écriture pochette seule (`writeCoverOnly`) : rien
  d'anormal.
- **`ReplayGainAnalyzer.runCaptured()`** — légère course théorique entre la
  fin du drain thread (`join(2000)`) et la lecture du buffer, mais sans
  scénario réaliste de déclenchement (le flux est déjà à EOF quand le
  process se termine). Notée pour mémoire, pas corrigée (impact quasi nul).
- **Deux bugs listés comme "non résolus" dans `docs/roadmap.md`** — en
  réalité déjà corrigés dans le code actuel (doc simplement restée
  obsolète) :
  - `detectClassical()` manquant dans `InfoCompleterWorker`/
    `AlbumCompletionWorker` → déjà appelé aux deux endroits.
  - Duplication de `groupKey()`/`groupTitle()` entre `RenamePreviewDialog`
    et `AlbumTreeTableModel` → déjà unifiée dans `AlbumGrouping.java`.
- **Dernier commit (`d839ce4`) passé en revue diff par diff** — garde
  anti-dossier-générique (`isGenericTag`), skip SongRec optionnel, filtre
  PENDING/SKIPPED pour les podcasts, `formatDuration()`, mutation de
  `FileEntry` bien faite via `SwingUtilities.invokeLater()` dans
  `AlbumCompletionWorker` (pas de violation de thread Swing) — rien à
  redire.

---

## ✅ Deuxième round — zones auditées, aucune autre anomalie trouvée

- **`TagEnrichment.java`** (cascades genre/pochette/classique, `saveEntry()`,
  soumission MusicBrainz, translittération, `parseStars()`) — cohérent,
  aucune régression trouvée.
- **`ReleaseMatcher.java`** (scoring de sélection de release façon Picard :
  pays/format/type préféré) — port fidèle vérifié correct, y compris le cas
  "veto" (type noté à 0 par l'utilisateur).
- **`AcoustIdClient.java`** — logique de retry sur 3 MBIDs correcte
  (remarque mineure : `lookupRecording()` assigne toujours score=100, donc
  la boucle s'arrête presque toujours au premier MBID valide — pas un bug,
  juste un commentaire de méthode légèrement trompeur).
- **`AudDClient.java`** (construction multipart, parsing Spotify/Apple
  Music) — rien d'anormal.
- **`SongRecClient.java`** (stratégie multi-offset, extraction ffmpeg,
  parsing JSON Shazam) — rien d'anormal (remarque mineure : le commentaire
  de classe dit "1/3, 2/3 du fichier", le code utilise en réalité
  0/(durée÷3) ou 0/(durée÷4)/(durée÷2) selon la durée — doc légèrement
  désynchronisée du code, sans impact fonctionnel).
- **`MusicBrainzClient`** — rate-limiting global (`mbRateLimit()`,
  `synchronized`, 1,1 s entre requêtes) et retry exponentiel sur 503/429
  avec gestion séparée des erreurs de connexion (`IOException`) — logique
  correcte et bien pensée.

## ✅ Troisième round — zones auditées, aucune autre anomalie trouvée

- **`DiscogsClient.java`** (recherche release, cascade genre/style,
  cache SQLite par requête) — cohérent.
- **`LastFmClient.java`** (genre, mood, URLs artiste, cache mémoire +
  SQLite à deux niveaux) — vérifié en détail, y compris la correspondance
  `MOOD_MAP`/`MOOD_LABELS` (10 entrées de chaque côté, index alignés) et le
  cache d'appel `cachedTagsKey`/`cachedTagsList` (mono-instance, jamais
  partagé entre threads par design) — rien d'anormal.
- **`FanArtClient.java`** — logique de repli release exacte → discographie
  → photo artiste correcte ; remarque mineure : les réponses en échec
  (404/clé invalide) ne sont pas mises en cache négativement, donc
  ré-interrogées à chaque fichier d'un même artiste sans pochette FanArt —
  cohérent avec Discogs/CaaClient (même choix partout), pas un bug isolé.
- **`DeezerClient.java`** — vérification de l'artiste trouvé avant
  téléchargement (évite de coller une pochette hors-sujet), repli
  `cover_xl` → `cover_big`, `null` géré proprement par
  `ImageDownloader.downloadToTempFile()`.
- **`ImageDownloader.java`** — détection correcte du type d'image depuis
  l'en-tête `Content-Type` (contrairement au bug CaaClient ci-dessus,
  déjà correct ici — sert de référence pour le correctif appliqué).
- **`MusicBrainzClient`** — `searchBestRelease()`, `lookupRelease()`,
  `parseTracks()`, `parseReleaseFromCache()` : logique de sélection de
  release et parsing de tracklist vérifiés, rien d'anormal (seuil de
  score 70, détection compilation par nom "Various Artists"/secondary-type,
  gestion disque unique vs multi-disques cohérente).
- **`Config.java`** (lecture des tableaux/maps de préférences :
  `coverProviderOrder()`, `translateLocales()`, `preferredCountries()`,
  `preferredFormats()`, `releaseTypeScores()`) — parsing correct, valeurs
  par défaut cohérentes, migration automatique des nouvelles clés pour les
  utilisateurs existants (`coverProviderOrder()`) bien pensée.

## ✅ Quatrième round — zones auditées, aucune autre anomalie trouvée

- **`AlbumClusterWorker.java`**, **`CompilationClusterWorker.java`**,
  **`InfoCompleterWorker.java`** (logique complète, pas juste le diff du
  dernier commit) — mutation `FileEntry` toujours bien faite via
  `SwingUtilities.invokeLater()`, caches release/tracklist cohérents,
  rien d'anormal.
- **`GenreFilter.java`** — filtre + capitalisation ; même limite mineure que
  `LocalCorrector.titleCase()` en théorie (un genre tout en majuscules
  resterait tel quel après la première lettre), mais pas de contradiction
  interne comme pour `titleCase()` — pas retenu comme bug confirmé.
- **`Fingerprinter.java`** — double-checked locking correct sur le
  sémaphore `fpcalcThreads`, rien d'anormal.
- **`AcoustIdSubmitter.java`** — découpage adaptatif du payload par lots
  (soumission par blocs sous la limite ~1 Mo, réduction 30% sur HTTP 413)
  vérifié correct, port fidèle de Picard confirmé.
- **`ListenBrainzClient.java`**, **`PlaylistExporter.java`** (hors le bug
  ci-dessus), **`LyricsClient.java`**, **`TaggerScript.java`**, **`I18n.java`**
  — rien d'anormal.
- **`App.java`** (CLI) — lu en bonne partie, voir la découverte majeure
  ci-dessus concernant `BatchProcessor`.

## ✅ Cinquième round — zones auditées, aucune autre anomalie trouvée

- **`EssentiaClient.java`**, **`AudioScanner.java`**, **`VideoScanner.java`**,
  **`MatchDialog.java`** (logique complète) — lus en entier, voir la matrice
  de complétude ci-dessus pour ce qui en ressort.
- **`CompilationMatchDialog.java`** (application des correspondances
  compilation) — écrit directement via `writer.write()` sans repasser par
  tout le pipeline d'enregistrement ; cohérent avec son rôle étroit
  (le `TagInfo` appliqué est déjà pleinement enrichi en amont).

-e 
---

## ✅ Sixième round — revue UI approfondie, un bug trouvé (ci-dessus)

Menus (`buildMenuTagger()`, `buildMenuEdition()`, `buildMenuOutils()`,
`buildMenuAffichage()`), filtre de recherche (`applyFilter()`), organisation
en dossiers (`organizeFiles()`), transcodage (`transcodeFiles()`),
complétion/groupement d'albums (`completeAlbums()`, `clusterAlbums()`,
`groupByCompilations()`), suppression de fichiers illisibles
(`deleteErrorFiles()`) — tous lus en entier. Toutes les gardes anti-
concurrence entre workers (`WorkerHub.blockerLabels()`) sont cohérentes.
`RenamePreviewDialog.compute()`, `PodcastDialog.java`,
`VideoRecoveryDialog.java` vérifiés pour les mêmes classes de bugs (cache,
concurrence) — rien d'autre trouvé.

**`DuplicatesDialog.java` lu en détail** pour sa logique de suppression
(`deleteSelected()`) — voir la découverte connexe juste au-dessus (même
trou de cache que le bug corrigé, mais pas patché ici, portée plus large).

-e 
---

## ✅ Quatorzième round — SettingsDialog, MusicBrainzOAuth, CoverFlowPanel, petits fichiers restants

- **`SettingsDialog.load()`/`save()` (2258 lignes)** — vérification exhaustive,
  clé par clé, de la correspondance entre chaque champ UI lu (`load()`) et
  écrit (`save()`) contre les getters réels de `Config.java`. **Aucun
  décalage trouvé** — les ~70 réglages vérifiés utilisent tous exactement la
  même clé des deux côtés. Fichier remarquablement cohérent malgré sa
  taille.
- **`MusicBrainzOAuth.java` (481 lignes)** — lu en entier (3 flows OAuth :
  scheme/localhost/oob, rafraîchissement de token, coupe-circuit
  `submissionBroken`). Rien de significatif trouvé ; une piste marginale
  notée ci-dessous, pas corrigée.
- **`CoverFlowPanel.java` (273 lignes)** — lu en entier. Pur rendu/animation
  Java2D, aucune logique de données — rien à signaler.
- **`AlbumGrouping.java`** — lu en entier, cohérent.
- **`DiskIoThrottle.java`** — lu en entier ; détection HDD/SSD par
  `/proc/mounts` + `/sys/block/.../rotational`, très défensif. Limite
  mineure notée : le stripping de suffixe de partition ne gère pas le
  format `mmcblk0p1` (cartes SD/eMMC) — resterait traité comme non-mécanique
  par défaut (fail-safe, pas de risque, juste un frein disque non appliqué
  sur ce type de stockage spécifique). Pas corrigé (trop marginal).

### Piste marginale notée (pas corrigée) : `MusicBrainzOAuth.authorizeLocalhost()`

Si le serveur de rappel local rencontre une exception SANS message
(`e.getMessage()` retourne `null`) avant d'avoir reçu un code ou un
paramètre d'erreur, `codeRef` ET `errorRef` restent tous les deux `null` —
le code continue alors avec `code = null`, ce qui produirait une
`NullPointerException` peu claire dans `exchangeCode()` plutôt que le
message d'erreur soigné prévu pour ce chemin. Cas extrêmement marginal
(exception réseau sans message), non corrigé.

## ✅ Seizième round — derniers points d'attache vérifiés

- **`MusicBrainzClient.lookupArtistAlias()`/`findAliasByLocale()`** — relus
  en entier, logique de repli multi-locale/anglais/écriture-latine vérifiée
  correcte, cohérente avec son propre historique de bugs déjà documenté.
- **`MbContributeDialog.java`** (336 lignes, soumission tags/rating MB) —
  lu en entier (round précédent) ; les points d'appel réels de
  `submitUserTags()`/`submitRating()`/`addReleaseToCollection()` sont
  désormais tracés : uniquement depuis ce dialogue et `TagEnrichment.java`
  (soumission automatique post-taguage). Rien d'anormal.
- **`PodcastDialog.java`, `VideoRecoveryDialog.java`** — relus intégralement
  ligne à ligne cette fois (pas seulement la logique cache/concurrence du
  round 6). Rien trouvé.
- **`installDragDrop()`, `loadFiles()`, `restoreWindowGeometry()`/
  `saveWindowGeometry()`, `chooseMask()`, `onPreferencesSaved()`,
  `activeOperations()`, `confirmQuit()`/`quitApp()`** — tous lus, rien
  d'anormal. La gestion multi-écran de `restoreWindowGeometry()` est
  particulièrement soignée (bureau virtuel combiné vs moniteur principal,
  déjà des correctifs documentés pour des bugs réels rencontrés).

## 📍 Statut de couverture

À ce stade, la quasi-totalité du code porteur de logique/"intelligence" a
été auditée : tous les fichiers `.java` du paquet racine `com.opentagger`
(clients réseau, matching, cache, TagWriter, workers), et la totalité des
méthodes de `MainFrame.java`/dialogues qui touchent aux tags, au disque, au
réseau ou à l'état partagé. Le reste non lu ligne à ligne (une partie des
`buildXxxPanel()`/menus de `SettingsDialog.java` et `MainFrame.java`) est de
la construction Swing pure (labels, layouts, styles) sans logique de
données — risque de bug de ce type de code jugé faible, mais "faible" n'est
pas "nul" : pas de garantie absolue à 100 %.

## 🔍 Zones restantes, très résiduelles

- Les `buildXxxPanel()`/constructeurs de menus purement visuels de
  `SettingsDialog.java`/`MainFrame.java` — construction de formulaire, pas
  de logique de données.
- Tests unitaires (`AlbumGroupingTest.java`, `AppTest.java`) — non exécutés
  (pas de build Maven possible dans cet environnement).
- Compilation/tests automatisés du projet dans son ensemble — jamais
  possible dans cet environnement (Maven Central bloqué). Tout ce rapport
  vient d'une lecture manuelle, sans retour de compilateur.

---

