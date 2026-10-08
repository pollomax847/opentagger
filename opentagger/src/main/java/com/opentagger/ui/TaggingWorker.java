package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import java.util.Map;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SwingWorker qui identifie les fichiers sélectionnés en arrière-plan et notifie l'UI après
 * chaque fichier via publish/process. Façon Picard : n'écrit JAMAIS sur le disque — un fichier
 * identifié avec succès reste en mémoire (statut IDENTIFIED) jusqu'à ce que l'utilisateur clique
 * "Enregistrer tout" (voir SaveWorker/TagEnrichment.saveEntry(), qui fait l'écriture, le
 * renommage, le cache/historique et les soumissions MusicBrainz/AcoustID).
 *
 * Pipeline d'identification :
 *  0. Cache SQLite   → résultat instantané si déjà connu (après forceRetag, le cache est vidé)
 *  1. SongRec/Shazam → empreinte audio, source principale — identifie même avec de faux tags
 *  2. AcoustID       → fingerprint alternatif (optionnel)
 *  3. MusicBrainz    → recherche par texte + enrichissement des résultats SongRec/AcoustID
 *  4. LocalCorrector → corrections scripts Jaikoz (5 scripts)
 *  6. Discogs        → genres
 *  7. Last.fm        → genres (fallback)
 *  8. BPM            → détection locale (ffmpeg)
 *  9. Essentia       → mood/key (optionnel, si binaire installé)
 */
public class TaggingWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>     entries;
    private final boolean             useAcoustId;
    private final Consumer<String>    onProgress;
    private final Consumer<FileEntry> onUpdate;
    // (doneCount, total) à chaque fichier — bug trouvé en direct (2026-08-15) : la seule mise à jour
    // existante passait par setProgress()/le pourcentage 0-100 de SwingWorker, qui ne déclenche un
    // événement "progress" QUE quand la valeur ENTIÈRE change. Sur un lot de 144k fichiers, un point
    // de pourcentage représente ~1445 fichiers — à ~500 fichiers/h, ça pouvait rester des HEURES sans
    // le moindre événement, la barre restant visuellement figée à "0 / 144492" (vide) tout ce temps.
    // Ce callback, lui, se déclenche à CHAQUE fichier, indépendamment du pourcentage arrondi.
    private final java.util.function.BiConsumer<Integer, Integer> onFileProgress;

    private final MusicBrainzClient        mb               = new MusicBrainzClient();
    private final SongRecClient            songRec          = new SongRecClient();

    // Source d'identification du dernier findTags() — utilisée pour enregistrer le niveau de confiance
    // ThreadLocal : scratch par fichier en cours de traitement (pas un état de client partagé) —
    // avec plusieurs fichiers traités en parallèle sur la même instance de TaggingWorker, un champ
    // simple ferait fuiter la source d'un fichier vers un autre traité au même moment.
    private final ThreadLocal<String> lastFindTagsSource =
            ThreadLocal.withInitial(() -> MetadataCache.SOURCE_TEXT);
    private final DiscogsClient      discogs   = new DiscogsClient();
    private final LocalCorrector     corrector = new LocalCorrector();
    private final MetadataCache      cache     = new MetadataCache();
    private final BpmDetector        bpmDet    = new BpmDetector();
    private final EssentiaClient     essentia  = new EssentiaClient();
    private final LyricsClient       lyrics    = new LyricsClient();

    private final boolean bpmEnabled      = BpmDetector.isAvailable();
    private final boolean essentiaEnabled = EssentiaClient.isOnPath();
    // ReplayGain : disponibilité figée à la construction (coûteux à tester), mais le RÉGLAGE est relu à chaque fichier — le « Mode Express » agit ainsi tout de suite, sans relancer la passe en cours.
    private final boolean rgAvailable     = ReplayGainAnalyzer.isAvailable();
    private boolean rgEnabled() { return rgAvailable && Config.get().replayGainEnabled(); }
    private final ReplayGainAnalyzer replayGain = rgAvailable ? new ReplayGainAnalyzer() : null;
    private final TaggerScript taggerScript = new TaggerScript();
    // Cache alias artiste : artistMbid → nom Latin (évite un appel MB par fichier)
    private final java.util.Map<String, String> aliasCache = new java.util.concurrent.ConcurrentHashMap<>();

    // Identification d'album par TOC (voir findTags() étape 0.6) : UN SEUL lookup MB par dossier,
    // pas par fichier — computeIfAbsent garantit qu'entre plusieurs threads traitant la même album
    // en parallèle (pool multi-thread, voir doInBackground()), un seul déclenche réellement l'appel
    // réseau, les autres attendent le résultat déjà en cours de calcul. discIdFailedFolders évite
    // de retenter un dossier déjà jugé non concluant à chaque nouveau fichier qu'il contient.
    private final java.util.Map<Path, MusicBrainzClient.ReleaseTracklist> discIdCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Path> discIdFailedFolders =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Cohérence de groupe pour un lot sans TOC exploitable (pistes sans numéro/durée fiable — voir
    // matchFolderByToc()) : demande utilisateur (2026-08-24) après le correctif "tagger tout l'album
    // depuis la vue arborescence" — s'assurer qu'un clic sur un groupe n'aboutit pas à des pistes
    // éparpillées sur plusieurs releases MusicBrainz différentes (même artiste/titre d'album, mais
    // rééditions/remasters distincts) simplement parce que chaque piste est cherchée indépendamment.
    // putIfAbsent : la PREMIÈRE piste du groupe à trouver une release à haute confiance (score ≥ seuil
    // ET durée cohérente, déjà validé par le code appelant avant l'écriture) fixe la release pour tout
    // le reste du groupe — clé = AlbumGrouping.key(), le même regroupement que la vue arborescence,
    // donc s'applique même à des fichiers répartis sur plusieurs dossiers physiques sous un même tag
    // album. discIdMatchingEnabled réutilisé comme interrupteur (même famille de fonctionnalité
    // "cohérence d'album" côté Préférences, pas la peine d'en exposer un second).
    private final java.util.Map<String, String> groupPinnedRelease =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Nombre de pistes ayant CONFIRMÉ le pin de leur groupe (même releaseMbid que le pin, pin
    // initial inclus) — voir son usage au garde-fou "une seule release par groupe" plus bas : un
    // pin établi par une SEULE piste isolée est une preuve trop faible pour pénaliser tout le reste
    // d'un dossier "fourre-tout" (Téléchargements, Singles...) où le partage de dossier ne
    // présuppose PAS un vrai album cohérent — contrairement à un pin corroboré par plusieurs
    // pistes indépendantes, bien plus probablement un vrai album (cas Vendée 93 : 11 pistes).
    private final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> groupPinAgreement =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, MusicBrainzClient.ReleaseTracklist> pinnedTracklistCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> pinnedTracklistFailed =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ── Journal de corrections ────────────────────────────────────────────────
    private final com.opentagger.CorrectionLog correctionLog = new com.opentagger.CorrectionLog();

    // Pool des tâches par fichier (voir doInBackground()) — champ plutôt que variable locale
    // uniquement pour que cancel() (ci-dessous) puisse l'atteindre. Nécessaire car
    // MainFrame.cancelTagging() appelle worker.cancel(true) pendant que ce pool tourne encore :
    // sans ce champ, cancel(true) n'interrompt QUE le thread de doInBackground() lui-même — pas
    // les tâches déjà soumises au pool, qui continuaient de taguer jusqu'à leur fin naturelle
    // (retour utilisateur : "j'ai cliqué sur arrêter et l'application continue de tagguer").
    private volatile ExecutorService pool;

    // ── Détection de compilation par DOSSIER ────────────────────────────────────
    // Trouvé en direct (2026-09-09) : "Vendée 93" (11 pistes, 7 artistes distincts) éclaté entre 3
    // releases MusicBrainz différentes — le garde-fou "préservation des compilations" juste plus bas
    // (origWasCompilation) ne se déclenche QUE si CE fichier précis porte déjà IS_COMPILATION=1 ou
    // ALBUM_ARTIST="Various Artists". Ici l'artiste album valait "Didier Barbelivien" (un des
    // contributeurs, pas "Various Artists") sur la plupart des pistes — cas très fréquent pour une
    // bibliothèque rippée sans grand soin, jamais couvert par les 3 signaux existants, qui reposent
    // tous sur LE TAG DE CE FICHIER. Le signal le plus fiable est structurel : plusieurs fichiers du
    // MÊME DOSSIER partageant le MÊME tag album mais des artistes tous différents ne peuvent être
    // qu'une compilation, quoi que dise le tag ALBUM_ARTIST de chacun pris isolément. Calculé UNE
    // FOIS sur tout le lot avant de lancer le pool (voir doInBackground()) — jamais réévalué depuis
    // un thread de traitement, donc pas de souci de concurrence sur ce Set une fois rempli.
    private static final int COMPILATION_MIN_FILES   = 3;
    private static final int COMPILATION_MIN_ARTISTS = 3;
    private Set<String> compilationFolderKeys = Set.of();

    /** Clé (dossier parent, album normalisé) — même normalisation que cleanSearchTerm() plus bas
     *  pour que la clé calculée ici et celle recherchée dans processEntry() coïncident toujours. */
    private static String compilationKey(Path parent, String album) {
        return parent + "\u0001" + album.trim().toLowerCase();
    }

    private void precomputeCompilationFolders(List<FileEntry> all) {
        // artiste (en minuscules) par clé dossier/album — Set pour dédupliquer avant de compter.
        Map<String, Set<String>> artistsByKey = new java.util.HashMap<>();
        Map<String, Integer> fileCountByKey = new java.util.HashMap<>();
        for (FileEntry e : all) {
            if (e.current == null || e.current.album.isBlank() || e.current.artist.isBlank()) continue;
            Path dir = e.currentPath != null ? e.currentPath.getParent()
                    : (e.file != null ? e.file.toPath().getParent() : null);
            if (dir == null) continue;
            // cleanSearchTerm() ICI aussi (pas juste côté lookup dans folderLooksLikeCompilation) :
            // sans ça, un album taggé avec underscores/suffixe qualité ("Vendee_93_320k") calculait
            // une clé jamais égale à celle recherchée au runtime (qui, elle, passe déjà par
            // cleanSearchTerm() — voir origAlbum dans processEntry()), silencieusement invalidant
            // toute la détection pour ce dossier précis.
            String key = compilationKey(dir, cleanSearchTerm(e.current.album));
            artistsByKey.computeIfAbsent(key, k -> new java.util.HashSet<>())
                    .add(e.current.artist.trim().toLowerCase());
            fileCountByKey.merge(key, 1, Integer::sum);
        }
        java.util.Set<String> result = new java.util.HashSet<>();
        for (var en : artistsByKey.entrySet()) {
            if (fileCountByKey.get(en.getKey()) >= COMPILATION_MIN_FILES
                    && en.getValue().size() >= COMPILATION_MIN_ARTISTS) {
                result.add(en.getKey());
            }
        }
        compilationFolderKeys = result;
        if (!result.isEmpty()) {
            log(I18n.t("  %d dossier(s) détecté(s) comme compilation par la diversité des artistes",
                    result.size()));
        }
    }

    /** Interrogé UNIQUEMENT depuis la préservation de compilation ci-dessous — voir son commentaire
     *  pour pourquoi ce signal existe. {@code origAlbum} déjà nettoyé par cleanSearchTerm() côté
     *  appelant, cohérent avec la normalisation appliquée dans compilationKey() côté calcul. */
    private boolean folderLooksLikeCompilation(File fichier, String origAlbum) {
        if (origAlbum.isBlank() || fichier.getParentFile() == null) return false;
        return compilationFolderKeys.contains(compilationKey(fichier.getParentFile().toPath(), origAlbum));
    }

    public TaggingWorker(List<FileEntry> entries, boolean useAcoustId,
                         Consumer<String> onProgress, Consumer<FileEntry> onUpdate) {
        this(entries, useAcoustId, onProgress, onUpdate, (done, total) -> {});
    }

    public TaggingWorker(List<FileEntry> entries, boolean useAcoustId, Consumer<String> onProgress,
                         Consumer<FileEntry> onUpdate,
                         java.util.function.BiConsumer<Integer, Integer> onFileProgress) {
        this.entries        = entries;
        this.useAcoustId    = useAcoustId;
        this.onProgress     = onProgress;
        this.onUpdate       = onUpdate;
        this.onFileProgress = onFileProgress;
    }

    private volatile boolean bypassBacklog;

    /** Pour une passe courte et ciblée (ex. les pistes d'un CD inconnu) qui ne doit pas attendre l'arriéré d'enregistrement du reste de la bibliothèque. */
    public void setBypassBacklog(boolean bypass) { this.bypassBacklog = bypass; }

    /** À appeler à la place de cancel(true) directement (SwingWorker.cancel() est final, donc pas
     *  substituable) — interrompt aussi les tâches déjà en cours dans le pool (voir le commentaire
     *  sur `pool`) : cancel(true) seul ne coupe que la boucle de soumission, pas les fichiers déjà
     *  en train d'être identifiés/écrits, qui continuaient jusqu'à leur fin naturelle (retour
     *  utilisateur : "j'ai cliqué sur arrêter et l'application continue de tagguer"). */
    public void stopNow() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        cancel(true);
    }

    @Override
    protected Void doInBackground() {
        // Trier l'ordre de traitement : fichiers très incomplets en premier, presque complets en dernier.
        // L'ordre d'affichage dans le tableau n'est pas modifié (deux listes distinctes).
        List<FileEntry> queue;
        if (Config.get().prioritizeIncomplete()) {
            queue = new java.util.ArrayList<>(entries);
            // Ré-identification demandée explicitement (MainFrame.applyReidentifyRequest) : reste en
            // tête du lot, sinon ces fichiers déjà complets passeraient après tous les incomplets.
            queue.sort((a, b) -> a.keepInPlaceIfSkipped != b.keepInPlaceIfSkipped
                    ? (a.keepInPlaceIfSkipped ? -1 : 1)
                    : incompletenessScore(b.current) - incompletenessScore(a.current));
            long incomplete = queue.stream().filter(e -> incompletenessScore(e.current) >= 4).count();
            if (incomplete > 0)
                onProgress.accept(I18n.t("Priorité : %s fichier(s) très incomplet(s) traité(s) en premier", incomplete));
        } else {
            queue = entries;
        }

        final int total = queue.size();
        batchTotal = total;
        AtomicInteger done = new AtomicInteger(0);
        if (acceptLateFiles) lateAccepted = true;
        try {
        // Chaque fichier part en parallèle (batch.threads, même réglage que BatchProcessor/CLI) —
        // le rate-limit MB reste correct quel que soit le nombre de threads puisqu'il est
        // centralisé dans MusicBrainzClient.getWithRetry(), pas ici.
        //
        // Passe "album-first" (identification en bloc par dossier avant le pipeline piste par
        // piste) supprimée le 2026-07-27 : sur une bibliothèque avec beaucoup de dossiers
        // "compilation"/vrac (noms de titre seuls, artistes disparates au sein d'un même dossier),
        // elle engloutissait des dizaines de milliers de tentatives d'appariement ratées contre une
        // tracklist MB choisie à l'aveugle avant qu'aucun fichier ne soit réellement tagué (retour
        // utilisateur : "rien ne travaille"). Un album correctement identifié piste par piste ici
        // est de toute façon complété ensuite par AlbumCompletionWorker (action manuelle
        // "Compléter les albums") — même travail (regrouper par releaseMbid déjà connu, retrouver
        // les pistes manquantes du même album parmi les PENDING/SKIPPED), mais SANS jamais deviner
        // à l'aveugle une release pour un dossier dont rien n'est encore identifié.
        List<FileEntry> toProcess = queue;

        // Voir son commentaire de classe pour le pourquoi — DOIT s'exécuter avant la boucle de
        // soumission au pool juste en dessous : c'est cet ordre (pas un mécanisme de concurrence)
        // qui garantit que compilationFolderKeys est visible, déjà rempli, de chaque thread du pool.
        precomputeCompilationFolders(entries);

        int threads = Math.max(1, Config.get().batchThreads());
        pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();
        int startIdx = done.get();

        for (int i = 0; i < toProcess.size(); i++) {
            if (isCancelled()) break;
            final FileEntry entry  = toProcess.get(i);
            final int       fileIdx = startIdx + i + 1;
            futures.add(pool.submit(() -> {
                // Ré-identifications demandées pendant ce lot (voir enqueuePriority) : servies avant
                // la tâche suivante du lot, sans attendre sa fin.
                FileEntry prio;
                while (!isCancelled() && (prio = PRIORITY_INBOX.poll()) != null) {
                    if (prio.status == FileEntry.Status.PENDING) {
                        extraTotal.incrementAndGet();
                        runOne(prio, done.get() + 1, done);
                    }
                }
                // Fichiers chargés après le début du lot (voir enqueueLate) : UN par tâche du lot,
                // entrelacés 1:1 avec lui plutôt que tous devant ou tous après.
                FileEntry late = LATE_INBOX.poll();
                if (late != null && !isCancelled() && late.status == FileEntry.Status.PENDING) {
                    extraTotal.incrementAndGet();
                    runOne(late, done.get() + 1, done);
                }
                runOne(entry, fileIdx, done);
            }));
        }

        WorkerHub.awaitAll(pool, futures, WorkerHub.defaultFutureTimeoutSec());
        } finally {
            // Ce qui reste dans LATE_INBOX est encore PENDING : repris par le lot suivant
            // (MainFrame.scheduleAutoTaggingFollowUp), qui vide la file à son lancement.
            if (acceptLateFiles) lateAccepted = false;
        }

        // Passe de cohérence d'ALBUM : les fichiers d'un même dossier se terminent en parallèle et la première piste fixe la release
        // du groupe ; une piste qui finit avant (ou qu'un doublon de compilation attire) pouvait donc sortir sur une AUTRE release
        // alors qu'elle figure aussi sur celle de l'album (ex. « Lucky You » sur « Curtain Call 2 » au lieu de « Kamikaze »).
        try { harmonizeAlbumReleases(queue); }
        catch (Exception ex) { log(I18n.t("  cohérence d'album : ignorée (%s)", ex.getMessage())); }

        // Remettre en attente toute entrée restée bloquée en PROCESSING
        for (FileEntry entry : queue) {
            if (entry.status == FileEntry.Status.PROCESSING) {
                entry.status  = FileEntry.Status.PENDING;
                entry.message = "";
                publish(entry);
            }
        }

        // Album clustering (regroupement par album, ReplayGain) : n'est plus déclenché ici
        // automatiquement — voir AlbumClusterWorker (2026-07-07). Cette passe ne voyait que les
        // fichiers de CE run, pas toute la bibliothèque, ce qui reclusterait/réécrivait les mêmes
        // fichiers avec des valeurs différentes au fil de plusieurs passes successives sur une
        // grosse bibliothèque taguée progressivement — devenue une action manuelle séparée.

        cache.purgeExpired();
        cache.close();
        return null;
    }

    /** File d'attente prioritaire partagée : fichiers à ré-identifier ajoutés PENDANT un lot déjà
     *  lancé (ex. demande reidentify_request.txt dont les fichiers n'étaient pas encore chargés au
     *  démarrage du lot — voir MainFrame.takeReidentifyRequest). Chaque tâche du lot en cours la vide
     *  avant de traiter son propre fichier. Un lot étant un instantané figé, c'est le seul moyen de
     *  les traiter sans attendre sa fin (des heures/jours sur une grosse bibliothèque). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<FileEntry> PRIORITY_INBOX =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Fichiers « Shazam limité » : remis en PENDING à la fin de la pause SongRec et servis au lot
     *  en cours (LATE_INBOX) s'il y en a un, sinon repris par le prochain lot. 2 essais max. */
    private static final java.util.Map<FileEntry, Integer> SHAZAM_RETRIES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.concurrent.ScheduledExecutorService RETRY_TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "shazam-retry"); t.setDaemon(true); return t; });

    private static void scheduleShazamRetry(FileEntry entry) {
        int n = SHAZAM_RETRIES.merge(entry, 1, Integer::sum);
        if (n > 2) return;
        long delay = Math.max(60_000L, com.opentagger.SongRecClient.pausedUntil() - System.currentTimeMillis() + 30_000L);
        RETRY_TIMER.schedule(() -> SwingUtilities.invokeLater(() -> {
            if (entry.status != FileEntry.Status.SKIPPED
                    || entry.skipReason != com.opentagger.model.SkipReason.NETWORK_ERROR) return;
            entry.status = FileEntry.Status.PENDING;
            entry.message = "";
            entry.skipReason = null;
            enqueueLate(java.util.List.of(entry));
        }), delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public static void enqueuePriority(java.util.Collection<FileEntry> entries) {
        PRIORITY_INBOX.addAll(entries);
    }

    /** Fichiers dont le scan (lecture des tags) s'est terminé APRÈS le lancement du lot « Tout
     *  tagger » en cours (2026-09-25, demande utilisateur). Le lot automatique démarre dès la fin
     *  du PREMIER dossier scanné ; un lot étant un instantané figé, les fichiers des dossiers plus
     *  lents (MyBook : 45 min+ de scan) attendaient jusqu'ici la fin de TOUT ce lot — des heures ou
     *  des jours — d'où des fichiers « jamais traités ». Servis au fil de l'eau, un par tâche du
     *  lot. Seul un lot « Tout tagger » les accepte (voir acceptLateFiles), pas une sélection ni un
     *  re-taguage forcé. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<FileEntry> LATE_INBOX =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile boolean lateAccepted = false;

    /** @return false si aucun lot « Tout tagger » ne tourne (les fichiers restent PENDING et seront
     *  pris par le prochain lot). */
    public static boolean enqueueLate(java.util.Collection<FileEntry> entries) {
        if (!lateAccepted) return false;
        LATE_INBOX.addAll(entries);
        return true;
    }

    /** Scan annulé : ses fichiers sont retirés du tableau, ils ne doivent pas être tagués. */
    public static void dropLate(java.util.Collection<FileEntry> entries) {
        LATE_INBOX.removeAll(entries);
    }

    /** Au lancement d'un nouveau lot : ce qui restait dans les files (lot précédent fini avant de
     *  les vider) est encore PENDING, donc déjà repris dans le nouveau lot — les vider évite de
     *  traiter deux fois le même fichier. */
    public static void clearPriority() {
        PRIORITY_INBOX.clear();
        LATE_INBOX.clear();
    }

    private boolean acceptLateFiles = false;
    private volatile int batchTotal = 0;
    private final AtomicInteger extraTotal = new AtomicInteger();

    /** Lot « Tout tagger » : accepte les fichiers chargés après son lancement (voir LATE_INBOX). À
     *  appeler avant execute(). */
    public void acceptLateFiles() { this.acceptLateFiles = true; }

    /** Traitement complet d'UN fichier (identification + déplacements éventuels + progression).
     *  Le total affiché inclut les fichiers ajoutés en cours de lot (PRIORITY_INBOX/LATE_INBOX). */
    private void runOne(FileEntry entry, int fileIdx, AtomicInteger done) {
        final int total = batchTotal + extraTotal.get();
        if (isCancelled()) return;
        if (!bypassBacklog) SaveBacklog.awaitRoom(this::isCancelled); // frein : ne pas identifier plus vite que l'enregistrement ne suit

        entry.status = FileEntry.Status.PROCESSING;
        publish(entry);
        final String fname = entry.filename();
        Consumer<String> step = s -> onProgress.accept(
            String.format("[%d/%d] %s — %s", fileIdx, total, fname, s));
        step.accept(I18n.t("identification…"));

        // Instances fraîches par tâche (voir le commentaire sur processEntry()) : jamais
        // les champs partagés mb/acoustId/lastFm/cache quand plusieurs fichiers tournent en
        // même temps. cache ajoutée à cette règle le 2026-07-29 : ses méthodes sont toutes
        // synchronized sur l'instance (nécessaire pour sa Connection JDBC unique) — la
        // partager entre threads (comme avant ce correctif, avec le champ `cache` de la
        // classe) sérialisait tout le monde au moindre accès au cache, réduisant le
        // parallélisme réel à peu de chose près à du séquentiel sur une grosse bibliothèque
        // (trouvé en direct via jstack, plusieurs threads BLOCKED sur le même moniteur).
        try (MetadataCache taskCache = new MetadataCache()) {
            processEntry(entry, step, new MusicBrainzClient(), new AcoustIdClient(), new LastFmClient(), taskCache);
        }

        // Si annulé pendant processEntry, remettre l'entrée en attente
        if (isCancelled() && entry.status == FileEntry.Status.PROCESSING) {
            entry.status  = FileEntry.Status.PENDING;
            entry.message = "";
        }

        correctionLog.addEntry(entry);

        // Déplacement dédié durée incohérente (voir Config.durationMismatchMoveEnabled(),
        // désactivé par défaut) — indépendant du déplacement générique SKIPPED/ERROR juste
        // en dessous, dossier et bascule séparés. Exclu du bloc générique ci-dessous (voir
        // sa condition "!entry.durationMismatch") pour ne pas tenter un second déplacement
        // du même fichier vers un autre dossier.
        if (entry.durationMismatch && Config.get().durationMismatchMoveEnabled() && !entry.keepInPlaceIfSkipped) {
            try {
                String folder = Config.get().durationMismatchMoveFolder();
                java.nio.file.Path curPath = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
                // Fichier déjà signalé "introuvable" juste au-dessus (déjà relocalisé par un
                // passage précédent, ou disparu entre le scan et ce traitement) : ne pas
                // retenter un déplacement voué à échouer avec NoSuchFileException sur ce
                // même chemin déjà connu absent.
                if (!folder.isBlank() && java.nio.file.Files.exists(curPath)) {
                    java.nio.file.Path moved = FileRenamer.moveToFolder(curPath, java.nio.file.Paths.get(folder));
                    if (moved != null) entry.currentPath = moved;
                }
            } catch (Exception ex) {
                log(I18n.t("  déplacement (durée incohérente) échoué: %s", ex.getMessage()));
            }
        }

        // Déplacer les fichiers non tagués (SKIPPED/ERROR) vers un dossier dédié si
        // configuré — évite qu'ils restent mélangés dans la bibliothèque organisée par
        // le renommage auto.
        // GROUP_MISMATCH exclu (2026-09-25, demande utilisateur) : ce motif ne dit RIEN sur le fichier, seulement
        // qu'une AUTRE édition de l'album a été choisie par les pistes voisines (ex. « That's the Way It Is » :
        // 8 éditions MusicBrainz). Déplacer ces fichiers vers Sans_correspondance arrachait les pistes à leur
        // dossier d'album — l'inverse exact du but du garde-fou (ne pas éclater un album) : 752 fichiers en une
        // journée, dossier passé de 718 à 2 003 fichiers, retraités à chaque démarrage. Ils restent en place,
        // visibles dans le rapport « Non identifiés » (revue manuelle).
        if (Config.get().skippedMoveEnabled() && !entry.durationMismatch
                && entry.skipReason != com.opentagger.model.SkipReason.GROUP_MISMATCH
                && !entry.keepInPlaceIfSkipped
                && (entry.status == FileEntry.Status.SKIPPED || entry.status == FileEntry.Status.ERROR)) {
            try {
                String folder = Config.get().skippedMoveFolder();
                java.nio.file.Path curPath = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
                if (!folder.isBlank() && java.nio.file.Files.exists(curPath)) {
                    java.nio.file.Path moved = FileRenamer.moveToFolder(curPath, java.nio.file.Paths.get(folder));
                    if (moved != null) entry.currentPath = moved;
                }
            } catch (Exception ex) {
                log(I18n.t("  déplacement (non tagué) échoué: %s", ex.getMessage()));
            }
        }

        int doneCount = done.incrementAndGet();
        int shownTotal = Math.max(doneCount, batchTotal + extraTotal.get());
        setProgress((int) ((doneCount * 100L) / shownTotal));
        onFileProgress.accept(doneCount, shownTotal);
        publish(entry);
    }

    @Override
    protected void done() {
        // Écrire le journal de corrections dans le dossier de la première piste
        try {
            java.nio.file.Path folder = entries.stream()
                .filter(e -> e.file != null)
                .map(e -> e.file.getParentFile().toPath())
                .findFirst()
                .orElse(null);
            java.nio.file.Path logPath = correctionLog.flush(folder);
            if (logPath != null)
                onProgress.accept(I18n.t("Journal écrit → %s", logPath.getFileName()));
        } catch (Exception ignored) {}
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
    }

    // Passe "album-first" (albumFirstPass/processAlbumFolder/fetchTracklistCached/
    // matchFileToTrack/extractTrackNumber/filenameToTitle) supprimée le 2026-07-27 — voir le
    // commentaire au point d'appel ci-dessus (doInBackground()) pour le pourquoi. AlbumCompletionWorker
    // couvre le même besoin (compléter un album à partir d'une release déjà connue) sans le défaut
    // de deviner une release à l'aveugle pour un dossier vierge.

    // mb/acoustId/lastFm passés en paramètres (et non les champs partagés du même nom) : chaque
    // fichier traité en parallèle doit avoir ses propres instances, ces 3 classes gardant un état
    // mutable entre appels (lastRawJson/networkCallMade, lastFingerprint/lastAcoustId,
    // cachedTagsKey/List) — les partager entre threads corromprait les résultats d'un fichier avec
    // ceux d'un autre traité au même moment (même risque déjà documenté dans BatchProcessor). Les
    // paramètres portent volontairement les mêmes noms que les champs de classe : ça masque les
    // champs dans toute cette méthode sans avoir à réécrire le moindre appel mb.xxx()/lastFm.xxx()
    // du corps existant.
    /**
     * Point de passage UNIQUE pour "ce fichier a été identifié, mais ne doit JAMAIS être
     * auto-enregistré — revue manuelle nécessaire" (2026-09-19, refactor demandé après audit :
     * "un seul point de décision centralisé, plutôt que des scores/statuts modifiés à plusieurs
     * endroits dispersés").
     *
     * Existe précisément parce que le bug du jour (voir Javadoc historique de
     * shouldSkipForGroupMismatch, conservée ci-dessous) a montré qu'un garde-fou qui se contente
     * de manipuler {@code TagInfo.score} À LA MAIN, sans passer par ici, peut sembler bloquer
     * l'auto-enregistrement alors qu'il ne bloque RIEN en pratique — le seul mécanisme prouvé
     * fonctionner (vérifié par les deux appelants existants ET par saveAll()/SaveWorker, qui ne
     * consultent jamais score) est {@code FileEntry.Status.SKIPPED}. TOUT futur garde-fou de cette
     * famille (un candidat trouvé, mais jugé pas assez fiable pour l'auto-enregistrement) doit
     * appeler cette méthode plutôt que réécrire entry.status/skipReason/message/candidates à la
     * main — la seule garantie que "revue manuelle nécessaire" veut vraiment dire ça.
     *
     * @param results   candidats déjà trouvés (conservés pour une revue manuelle éventuelle, voir
     *                  entry.candidates — même usage que la revue "Non identifiés")
     * @param reason    catégorie pour le rapport "Non identifiés" (voir SkipReason)
     * @param userMsg   message affiché à l'utilisateur (colonne Message du tableau)
     * @param logMsg    ligne de journal (déjà formatée via I18n.t() par l'appelant)
     */
    private void skipForManualReview(FileEntry entry, List<TagInfo> results,
                                      com.opentagger.model.SkipReason reason, String userMsg, String logMsg) {
        entry.candidates = results;
        entry.status     = FileEntry.Status.SKIPPED;
        entry.skipReason = reason;
        entry.message    = userMsg;
        log(logMsg);
    }

    private void processEntry(FileEntry entry, Consumer<String> step,
                               MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm,
                               MetadataCache cache) {
        com.opentagger.SongRecClient.resetThrottled(); // drapeau par thread : jamais hérité du fichier précédent
        try {
            File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            // Positionné ICI (avant tout log() de ce fichier, y compris le bloc transcodage
            // ci-dessous) et non juste avant "▶ START" comme à l'origine : sans ça, un WARN de
            // transcodage (ou le "▶ SKIP fichier introuvable" juste en dessous) s'affichait avec le
            // préfixe [nom_fichier] du fichier PRÉCÉDENT traité par ce thread — CURRENT_FILE n'étant
            // mis à jour qu'après ce bloc, les warnings de transcodage se sont retrouvés attribués
            // au mauvais fichier. Repéré en direct (2026-08-13) en enquêtant sur un pic d'échecs de
            // transcodage apparemment sur des .mp3 déjà au bon format (impossible normalement,
            // AudioTranscoder.transcode() retourne null sans même appeler ffmpeg dans ce cas) — le
            // vrai fichier en échec était systématiquement différent de celui affiché.
            CURRENT_FILE.set(fichier.getName());

            // Vérifier que le fichier existe avant tout traitement
            if (!fichier.exists()) {
                entry.status     = FileEntry.Status.ERROR;
                entry.skipReason = com.opentagger.model.SkipReason.FILE_MISSING;
                entry.message = I18n.t("Fichier introuvable");
                log(I18n.t("▶ SKIP   %s (fichier introuvable)", fichier.getName()));
                return;
            }

            // Transcodage automatique avant taguage si activé
            if (Config.get().transcodeAutoBeforeTag()) {
                try {
                    com.opentagger.AudioTranscoder.Format fmt =
                        com.opentagger.AudioTranscoder.Format.fromId(Config.get().transcodeFormat());
                    java.nio.file.Path srcPath = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
                    String srcName = srcPath.getFileName().toString();
                    String srcExt = srcName.contains(".") ? srcName.substring(srcName.lastIndexOf('.') + 1).toLowerCase() : "";
                    // Un fichier déjà dans le format cible n'est PAS transcodé : on n'annonce donc rien (le message « transcodage → MP3… »
                    // s'affichait aussi pour un .mp3 déjà conforme). Un fichier qui a déjà échoué, inchangé depuis, n'est pas retenté.
                    if (!srcExt.equals(fmt.ext)) {
                        if (com.opentagger.TranscodeFailureMemory.shared().known(srcPath)) {
                            log(I18n.t("  transcodage ignoré : ce fichier a déjà échoué (flux abîmé) et n'a pas changé"));
                        } else {
                            step.accept(I18n.t("transcodage → %s…", fmt.id.toUpperCase()));
                            try {
                                java.nio.file.Path transcoded = new com.opentagger.AudioTranscoder()
                                    .transcode(srcPath, fmt, Config.get().transcodeBitrate(), Config.get().transcodeDeleteSource());
                                if (transcoded != null) {
                                    entry.currentPath = transcoded;
                                    fichier = transcoded.toFile();
                                    CURRENT_FILE.set(fichier.getName());
                                    log("  transcoded → " + transcoded.getFileName());
                                }
                            } catch (Exception txEx) {
                                // Retenu seulement si le fichier lui-même est en cause (flux abîmé), jamais pour un échec passager (disque
                                // plein, ffmpeg absent, délai dépassé) : une contre-vérification de lecture indépendante tranche, comme
                                // pour le bouton « Transcoder ».
                                String m = String.valueOf(txEx.getMessage());
                                com.opentagger.AudioTranscoder checker = new com.opentagger.AudioTranscoder();
                                boolean damaged = com.opentagger.AudioTranscoder.isUnreadableSourceError(m)
                                        || m.contains("Transcodage incomplet")
                                        || (m.contains("Transcodage échoué") && checker.verifyUnreadable(srcPath));
                                if (damaged) com.opentagger.TranscodeFailureMemory.shared().remember(srcPath);
                                throw txEx;
                            }
                        }
                    }
                } catch (Exception txEx) {
                    log(I18n.t("  transcode WARN: %s — poursuite sans transcodage", txEx.getMessage()));
                }
            }

            log(I18n.t("▶ START  %s", fichier.getName()));

            // Clé de groupe pour groupPinnedRelease (voir son commentaire) — calculée UNE FOIS ici sur
            // les tags encore inchangés de cette entrée (entry.result n'est écrit qu'à la fin de
            // processEntry), pour rester identique à la clé qu'a utilisée la vue arborescence au
            // moment où l'utilisateur a cliqué le groupe.
            String groupKey = com.opentagger.AlbumGrouping.key(entry);
            // Coffret multi-CD (écart SongKong, voir DISC_FOLDER_PATTERN/detectDiscHint()) : pour
            // un fichier pas encore identifié (donc AlbumGrouping.key() est retombé sur le dossier,
            // voir sa Javadoc), si CE dossier est lui-même un sous-dossier "CD1"/"Disc 2"/... son
            // parent (le dossier du coffret) devient la clé de groupe à la place — pour que CD1 et
            // CD2 partagent le MÊME groupPinnedRelease au lieu d'être traités comme deux groupes
            // sans rapport. Ajustement LOCAL à l'identification uniquement (pas dans AlbumGrouping
            // lui-même, partagé avec 3 autres classes d'AFFICHAGE — pas de risque de changer leur
            // regroupement visuel pour un correctif qui ne concerne que le matching).
            if (groupKey.startsWith("folder::")) {
                File parentDir = fichier.getParentFile();
                if (parentDir != null && DISC_FOLDER_PATTERN.matcher(parentDir.getName()).find()) {
                    File grandParent = parentDir.getParentFile();
                    if (grandParent != null) groupKey = "folder::" + grandParent;
                }
            }

            // Encodage cassé (UTF-8 relu en Latin-1, « Ã© » au lieu de « é ») : réparé pour la RECHERCHE, sur une copie (entry.current est
            // l'objet affiché, jamais muté ici). Écrit sur le disque plus bas seulement si le fichier reste non identifié : un fichier
            // identifié reçoit de toute façon des tags propres à l'enregistrement. La réparation est vérifiée dans les deux sens
            // (EncodingFixer) : un texte déjà correct n'est jamais touché.
            TagInfo searchTags = entry.current;
            boolean encodingRepaired = false;
            if (Config.get().bool("tagging.fix_encoding", true) && entry.current != null) {
                TagInfo repairedCopy = entry.current.copy();
                if (com.opentagger.EncodingFixer.repairFields(repairedCopy)) {
                    searchTags = repairedCopy;
                    encodingRepaired = true;
                    log(I18n.t("  encodage cassé réparé pour la recherche : %s – %s", repairedCopy.artist, repairedCopy.title));
                }
            }

            log(I18n.t("  findTags..."));
            List<TagInfo> results = findTags(fichier, searchTags, entry.forceReidentify, entry.bandcampOnly, mb, acoustId, lastFm, cache, groupKey);
            entry.forceReidentify = false;
            entry.bandcampOnly    = false;
            mb.setPreferredAlbum(""); // reset après findTags — clusterAlbums ne doit pas en bénéficier
            log(I18n.t("  findTags → %s résultat(s)%s", results.size(),
                results.isEmpty() ? "" : " score=" + results.get(0).score));

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty()) {
                // Non identifié mais l'encodage était cassé : on écrit la correction (seule, sans rien inventer) pour que le fichier ne
                // garde pas « Ã© ». On relit les tags sur le disque (pas la copie en mémoire, possiblement périmée) avant d'écrire.
                if (encodingRepaired) writeEncodingRepair(entry, fichier);
                if (com.opentagger.SongRecClient.wasThrottled()) {
                    // Shazam n'a pas pu être interrogé (limite 429 / pause) : rien ne dit que le
                    // fichier est inconnu — laissé en place, retenté après la pause.
                    entry.status     = FileEntry.Status.SKIPPED;
                    entry.skipReason = com.opentagger.model.SkipReason.NETWORK_ERROR;
                    entry.keepInPlaceIfSkipped = true;
                    entry.message = I18n.t("Pas encore vérifié par Shazam (limite de requêtes) — retenté plus tard");
                    log(I18n.t("  SKIPPED (Shazam limité, à retenter)"));
                    scheduleShazamRetry(entry);
                    return;
                }
                entry.status     = FileEntry.Status.SKIPPED;
                entry.skipReason = com.opentagger.model.SkipReason.NOT_IDENTIFIED;
                entry.message = I18n.t("Non identifié") + videoHintIfAny(fichier);
                // Un épisode de podcast n'a aucune raison d'être reconnu comme musique : on le signale au lieu de le laisser « non identifié ».
                if (Config.get().bool("podcast.suggest", true)) {
                    TagInfo cur = entry.current;
                    java.io.File parent = fichier.getParentFile();
                    PodcastDetector.Verdict v = PodcastDetector.assess(
                            cur != null ? cur.genre : "", cur != null ? cur.podcastUrl : "",
                            cur != null ? cur.podcastSeason : "", cur != null ? cur.podcastEpisode : "",
                            cur != null ? cur.durationSec : 0, fichier.getName(), parent != null ? parent.getName() : "");
                    if (v.probable()) {
                        entry.skipReason = com.opentagger.model.SkipReason.PODCAST_PROBABLE;
                        entry.message = I18n.t("Podcast probable (%s) — « Tagger comme podcast… »", String.join(", ", v.reasons()));
                        log(I18n.t("  SKIPPED (podcast probable : %s)", String.join(", ", v.reasons())));
                        return;
                    }
                }
                log(I18n.t("  SKIPPED (non identifié)"));
                return;
            }

            TagInfo best = results.get(0);
            // Un résultat dont le titre est lui-même générique (« Track 3 », « Piste 12 ») n'apprend rien : c'est typiquement un faux positif
            // (livre audio, enregistrement mal nommé) qui ne doit pas être écrit comme identification — vu en direct sur un CD gravé
            // (« Greg Wise & Saskia Reeves – Track 3 » pour un remix de Fairmont, score 100).
            if (isGenericTag(best.title)) {
                skipForManualReview(entry, results, com.opentagger.model.SkipReason.NOT_IDENTIFIED,
                        I18n.t("Identifié seulement comme « %s » (titre générique) — à vérifier", best.title) + videoHintIfAny(fichier),
                        I18n.t("  SKIPPED titre générique"));
                return;
            }
            // best.durationSec est TOUJOURS 0 par défaut (MusicBrainzClient ne le renseigne jamais
            // — seul mbDurationSec l'est, voir son commentaire) : sans cette ligne, la colonne Durée
            // (qui affiche entry.activeTags().durationSec, donc "result" une fois identifié) retombe
            // à 0:00 pour TOUT fichier identifié, corrompu ou pas — constaté en direct, confondu avec
            // le signal "fichier vide". La vraie durée déjà lue au scan doit survivre à
            // l'identification.
            best.durationSec = entry.current.durationSec;

            // Le repli "tags existants non vérifiés" (SOURCE_UNVERIFIED_TAGS, score=50 volontairement
            // bas — voir findTags()) est EXEMPT du seuil habituel : c'est par construction son seul
            // résultat possible (dernier recours après échec de tout le reste), le seuil n'a donc
            // aucun sens à lui appliquer — il finirait systématiquement rejeté "score trop bas" alors
            // que le but même de ce repli est de sortir ces fichiers de Non identifié.
            // SOURCE_BANDCAMP inclus dans la même exemption : score volontairement bas (55, voir
            // findTags() étape 6a) mais déjà vérifié par sa propre logique (TrackMatcher.
            // titleSimilarity sur le contenu RÉEL renvoyé par Bandcamp) — pas un score de recherche
            // MB comparable au seuil habituel, même raisonnement que SOURCE_UNVERIFIED_TAGS.
            boolean unverifiedFallback = MetadataCache.SOURCE_UNVERIFIED_TAGS.equals(lastFindTagsSource.get())
                    || MetadataCache.SOURCE_BANDCAMP.equals(lastFindTagsSource.get());
            if (best.score < seuil && !unverifiedFallback) {
                skipForManualReview(entry, results, com.opentagger.model.SkipReason.LOW_SCORE,
                        I18n.t("Score %s%% < %s%% — %s candidat(s)", best.score, seuil, results.size())
                                + videoHintIfAny(fichier),
                        I18n.t("  SKIPPED score trop bas"));
                return;
            }

            // ── Cohérence de durée avec MusicBrainz ─────────────────────────────
            // Un score texte élevé peut malgré tout matcher le mauvais enregistrement (édit vs
            // version complète, mauvais rip, fichier tronqué) — la durée déclarée par MB pour CE
            // recording est un signal indépendant du score. Écart jugé significatif seulement au-
            // delà de 20s ET 20% relatif, pour ne pas confondre avec un simple radio edit/remaster
            // (quelques secondes d'écart légitimes). Ne PAS auto-accepter : traité comme un SKIPPED
            // (pas IDENTIFIED), donc jamais proposé à "Enregistrer tout" avec des tags probablement
            // faux pour ce fichier précis.
            //
            // AVANT ce correctif, seul results.get(0) (le mieux scoré côté texte) était vérifié —
            // si CE candidat précis avait la mauvaise durée (ex. "Titre" a un radio edit ET une
            // version complète dans MB, le edit sort en tête du score texte mais le fichier réel
            // est la version complète), le fichier finissait "durée incohérente" et jamais tagué,
            // alors que le BON candidat était déjà présent plus bas dans `results` avec un score
            // toujours au-dessus du seuil. On parcourt maintenant tous les candidats valides par
            // score décroissant (déjà l'ordre renvoyé par l'API MB) et on garde le premier dont la
            // durée est cohérente, avant d'abandonner.
            // Même exemption que ci-dessus pour le repli tags existants : mbDurationSec n'est jamais
            // renseigné pour ce cas (aucun vrai enregistrement MB associé), donc rien à comparer —
            // et le score=50 volontairement bas ferait échouer la garde "candidate.score < seuil"
            // dès le premier tour, aboutissant à tort à "durée incohérente" pour un cas qui n'a
            // simplement pas de durée MB de référence.
            TagInfo durationOk = unverifiedFallback ? best : null;
            // Candidat le plus proche en durée parmi ceux réellement testés ci-dessous — pour que le
            // message affiché en cas de rejet total (voir plus bas) montre le candidat le plus
            // pertinent, pas systématiquement le premier/mieux scoré (results.get(0) via `best`), qui
            // peut être un écart énorme alors qu'un autre candidat, rejeté aussi mais bien plus
            // proche, donnerait un message plus honnête sur ce qui a vraiment été essayé.
            TagInfo closestMiss = null;
            if (!unverifiedFallback) {
                for (TagInfo candidate : results) {
                    if (candidate.score < seuil) break; // triés par score décroissant : la suite ne fera que pire
                    // Candidat "mix DJ continu" (ex. "BPM Continuous DJ Mix (mixed by Art
                    // Department)") : une piste normale matchée par empreinte audio peut tomber sur
                    // l'enregistrement d'un mix qui la CONTIENT en transition — le garde-fou de durée
                    // juste en dessous ne protège pas ce cas quand mbDurationSec n'est pas renseigné
                    // pour ce candidat (SongRec/AcoustID sans confirmation MB, voir leurs branches).
                    // Repéré en direct 2026-08-28 : "Eric Volta - Blood Burgundy" accepté à 85% comme
                    // "Art Department - Bpm Continuous Dj Mix", effaçant titre/artiste/album/année
                    // corrects. Un titre de piste individuelle ne ressemble normalement jamais à ça.
                    // Le garde-fou titre ne doit intervenir QUE quand la durée MB est absente (voir
                    // le commentaire plus haut : isDurationMismatch() exempte mbDurationSec<=0, donc
                    // sans lui un candidat "mix DJ" sans durée renseignée passerait sans protection).
                    // Trouvé en direct (2026-09-07) : ICE MC "MEGAMIX" — un VRAI single "Megamix"
                    // officiel de l'artiste (pas un mix DJ tiers qui le contient), dont mbDurationSec
                    // EST renseigné et colle presque exactement à la durée du fichier (9:28 vs 9:27,
                    // 8:44 vs 8:44) — inconditionnel, ce garde-fou l'excluait quand même avant même de
                    // tester sa durée, donnant le message contradictoire "durée incohérente" affichant
                    // en fait des durées quasi identiques (héritées de results.get(0), jamais celui
                    // réellement écarté ici).
                    if (isContinuousMixTitle(candidate.title) && candidate.mbDurationSec <= 0) continue;
                    candidate.durationSec = entry.current.durationSec;
                    if (!FileEntry.isDurationMismatch(entry.current.durationSec, candidate.mbDurationSec)) {
                        durationOk = candidate;
                        break;
                    }
                    if (candidate.mbDurationSec > 0 && (closestMiss == null
                            || Math.abs(candidate.mbDurationSec - entry.current.durationSec)
                             < Math.abs(closestMiss.mbDurationSec - entry.current.durationSec))) {
                        closestMiss = candidate;
                    }
                }
            }
            if (durationOk == null) {
                TagInfo shown = closestMiss != null ? closestMiss : best;
                if (closestMiss != null && tryTrashTruncated(entry, fichier, closestMiss)) return;   // essai, voir la méthode
                entry.candidates = results;
                entry.status = FileEntry.Status.SKIPPED;
                entry.durationMismatch = true;
                entry.skipReason = com.opentagger.model.SkipReason.DURATION_MISMATCH;
                entry.message = I18n.t("Durée incohérente : fichier %s vs MusicBrainz %s (%s)",
                    FileTableModel.formatDuration(entry.current.durationSec),
                    FileTableModel.formatDuration(shown.mbDurationSec), shown.title)
                        + videoHintIfAny(fichier);
                log(I18n.t("  SKIPPED durée incohérente (%ds vs %ds)",
                    entry.current.durationSec, shown.mbDurationSec));
                return;
            }
            best = durationOk;

            // Empreinte AcoustID acceptée à confiance élevée (acoustIdResultPlausible() la laisse passer
            // sans comparaison) mais SANS durée MusicBrainz pour la confirmer, et dont l'artiste ET le titre
            // n'ont aucun rapport avec le nom de fichier ni les tags : faux positif possible (constaté :
            // « Drake – Summer Sixteen » (484 s) → « T. Rex – Summer Deep »). Pas rejeté (l'empreinte peut
            // avoir raison contre un tag faux) mais jamais auto-enregistré : revue manuelle.
            if (MetadataCache.SOURCE_ACOUSTID.equals(lastFindTagsSource.get()) && best.mbDurationSec <= 0) {
                String[] fn = parseFilename(fichier);
                if (contradictsInput(fn[0], fn[1], entry.current.artist, entry.current.title,
                        best.artist, best.title)) {
                    skipForManualReview(entry, results, com.opentagger.model.SkipReason.LOW_SCORE,
                            I18n.t("AcoustID contredit le nom de fichier et les tags (« %s – %s ») — à vérifier",
                                    best.artist, best.title) + videoHintIfAny(fichier),
                            I18n.t("  ⚠ AcoustID \"%s – %s\" sans rapport avec le fichier — SKIPPED, revue manuelle",
                                    best.artist, best.title));
                    return;
                }
            }

            // "- Topic" (nom de chaîne YouTube auto-générée) — voir stripYoutubeTopicSuffix() pour
            // le pourquoi. Appliqué ici, au tout dernier moment avant usage, pour couvrir toutes les
            // sources (SongRec, AcoustID, MB, replis) sans dupliquer le nettoyage dans chacune.
            best.artist      = stripYoutubeTopicSuffix(best.artist);
            best.albumArtist = stripYoutubeTopicSuffix(best.albumArtist);

            // Album du DOSSIER prioritaire (2026-10-08, demande utilisateur) : une piste rangée dans « Hits Total 2013 » sortait
            // taguée « Chilled » parce que l'enregistrement figure sur les deux et que l'identification avait choisi l'autre —
            // 130 fichiers en une journée, et un fichier ainsi tagué peut même être déplacé hors de son dossier au renommage.
            // Si l'enregistrement figure AUSSI sur une parution qui porte le nom du dossier, on prend celle-là. Avant la
            // cohérence de groupe ci-dessous, pour que le reste du dossier s'aligne sur cette parution.
            if (!best.recordingMbid.isBlank()) {
                try { retargetToFolderRelease(best, fichier, entry.current.durationSec); }
                catch (Exception ex) { log(I18n.t("  album du dossier : ignoré (%s)", ex.getMessage())); }
            }

            // Cohérence de groupe (voir groupPinnedRelease) : cette piste vient de trouver une release
            // à haute confiance (score ≥ seuil ET durée cohérente, validé juste au-dessus) — la fixer
            // pour le reste du groupe si aucune autre piste ne l'a déjà fait. putIfAbsent : la première
            // piste gagne : au pire quelques pistes suivent l'un ou l'autre choix si deux pistes du
            // même groupe sont traitées en parallèle et trouvent chacune une release différente, tous
            // deux déjà validés individuellement — jamais pire que le comportement sans épinglage.
            if (Config.get().discIdMatchingEnabled() && !best.releaseMbid.isBlank()) {
                groupPinnedRelease.putIfAbsent(groupKey, best.releaseMbid);
            }

            // Garde-fou "une seule release par groupe" (écart trouvé vs SongKong, dont la doc
            // annonce un rejet strict "toutes les pistes doivent matcher une seule release" —
            // comparaison 2026-09-18). Directement lié au bug déjà connu "Vendée 93" (11 pistes
            // dispersées sur 3 releases MB sans rapport, ~309 dossiers de la bibliothèque ont la
            // même forme vulnérable) : le mécanisme groupPinnedRelease existant (juste au-dessus)
            // épingle une release dès qu'une piste la trouve, mais ne fait QUE proposer cette
            // release aux pistes suivantes (étape 0.65) — si aucune piste de la release épinglée ne
            // correspond, le code retombe SILENCIEUSEMENT sur une identification indépendante,
            // potentiellement une AUTRE release entière (voir son propre commentaire, "bonus track,
            // single..."). Plutôt qu'un rejet dur du dossier entier (collatéral réel : un bonus
            // track légitime existe, voir ce même commentaire), ce garde-fou SKIPPE le fichier
            // (comme le seuil de score standard juste au-dessus, ligne ~509) quand une piste
            // contredit une release DÉJÀ CORROBORÉE par au moins 2 pistes de son groupe.
            //
            // CORRECTIF CRITIQUE (2026-09-19, trouvé par audit dédié le jour même du déploiement
            // initial) : la toute première version de ce garde-fou plafonnait juste best.score=50
            // en pensant que ça suffirait à empêcher l'auto-enregistrement (commentaire d'origine :
            // "jamais silencieusement auto-enregistrée... reste enregistrable après revue
            // manuelle") — FAUX. Le seul test de seuil du pipeline (ligne 509, "best.score < seuil")
            // a déjà été passé PLUS TÔT dans cette même méthode, avant ce bloc ; rien ne le
            // re-vérifie après coup. entry.status passait ensuite IDENTIFIED sans condition de
            // score (voir plus bas), FileEntry.selected vaut true par défaut et n'est jamais
            // recalculé selon le score, et saveAll()/SaveWorker ne consultent JAMAIS score — donc
            // un fichier "plafonné à 50" suivait EXACTEMENT le même chemin d'auto-enregistrement
            // qu'un match à 100%, sans la moindre friction. Vérifié : jamais déclenché en
            // production entre le déploiement initial et ce correctif (aucune occurrence de
            // "contredit la release du groupe" dans les logs), donc aucun dégât réel confirmé —
            // mais le filet de sécurité documenté n'en existait pas moins pas. Fixé en réutilisant
            // le MÊME mécanisme que le seuil de score standard (SKIPPED, pas juste un score
            // modifié) : seul chemin déjà prouvé bloquer réellement saveAll().
            //
            // Seuil de corroboration (pas juste "un pin existe") : le regroupement se fait par
            // DOSSIER pour des fichiers pas encore identifiés (voir AlbumGrouping.key()) — un
            // dossier "fourre-tout" (Téléchargements, Singles...) n'est pas un vrai album, et une
            // SEULE piste ne prouve rien sur les autres qui partagent juste le même dossier par
            // coïncidence. Exemptés : SOURCE_DISCID (checksum sur CE dossier précis, déjà
            // prioritaire sur le pin lui-même) et SOURCE_MBID (MBID déjà connu avec certitude) —
            // les deux seules sources qui prouvent réellement l'appartenance à une autre release
            // plutôt que de simplement la suggérer.
            if (Config.get().discIdMatchingEnabled() && groupKey != null && !best.releaseMbid.isBlank()) {
                String pinned = groupPinnedRelease.get(groupKey);
                if (pinned != null && pinned.equals(best.releaseMbid)) {
                    groupPinAgreement.computeIfAbsent(groupKey, k -> new java.util.concurrent.atomic.AtomicInteger())
                            .incrementAndGet();
                } else if (pinned != null) {
                    int agreement = groupPinAgreement.getOrDefault(groupKey,
                            new java.util.concurrent.atomic.AtomicInteger()).get();
                    if (shouldSkipForGroupMismatch(agreement, lastFindTagsSource.get(), best.score)
                            && retargetToPinnedRelease(best, pinned, entry.current.durationSec)) {
                        // Le morceau identifié figure AUSSI sur la release du groupe (même enregistrement, ou même
                        // titre et durée compatible) : on le rattache à cette release plutôt que de le skipper.
                        groupPinAgreement.computeIfAbsent(groupKey, k -> new java.util.concurrent.atomic.AtomicInteger())
                                .incrementAndGet();
                    } else if (shouldSkipForGroupMismatch(agreement, lastFindTagsSource.get(), best.score)
                            && ownReleaseIsTrustworthy(best, entry.current.durationSec)) {
                        // Le morceau n'est PAS sur la release du groupe (recherche ci-dessus sans résultat) : ce n'est
                        // donc pas une contradiction mais un dossier mélangé (compilations diverses). Sa propre
                        // identification est certaine (score 100, durée compatible) : on la garde au lieu de skipper.
                        log(I18n.t("  Cohérence de groupe → absent de la release du groupe, release propre conservée [%s]",
                                best.album));
                    } else if (shouldSkipForGroupMismatch(agreement, lastFindTagsSource.get(), best.score)) {
                      // Ni sur la release du groupe, ni « release propre certaine » ci-dessus : résolution
                      // automatique (2026-09-26, Linux, demande utilisateur : « trouver une alternative pour
                      // qu'il ne s'affiche plus, détection auto du bon release »).
                      String pinnedGroup = "";
                      MusicBrainzClient.ReleaseTracklist ptl = pinnedTracklistCache.computeIfAbsent(pinned, mbid -> {
                          try { return mb.lookupRelease(mbid); } catch (Exception e) { return null; }
                      });
                      if (ptl != null) pinnedGroup = ptl.releaseGroupMbid();
                      GroupMismatch verdict = resolveGroupMismatch(agreement, lastFindTagsSource.get(), best.score,
                              pinnedGroup, best.releaseGroupMbid);
                      if (verdict == GroupMismatch.SIBLING_EDITION) {
                          log(I18n.t("  ↪ autre édition du même album que le reste du groupe (même groupe de parutions MusicBrainz) → acceptée"));
                      } else if (verdict == GroupMismatch.CONFIRMED_OTHER_RELEASE) {
                          log(I18n.t("  ↪ piste d'une autre parution que le reste du groupe (bonus, single…), identification sûre (%s, score=%s) → acceptée",
                                  lastFindTagsSource.get(), best.score));
                      } else {
                        String pinnedShort = pinned.substring(0, Math.min(8, pinned.length()));
                        skipForManualReview(entry, results, com.opentagger.model.SkipReason.GROUP_MISMATCH,
                                I18n.t("Contredit la release du groupe [%s] (%d piste(s) concordantes) — score=%s%%",
                                        pinnedShort, agreement, best.score) + videoHintIfAny(fichier),
                                I18n.t("  ⚠ contredit la release du groupe [%s] (%d piste(s) concordantes) — "
                                        + "SKIPPED, revue manuelle nécessaire", pinnedShort, agreement));
                        return;
                      }
                    }
                }
            }

            log(I18n.t("  ✓ identifié : %s – %s (score=%s)", best.artist, best.title, best.score));

            // Enrichir avec lookup si album ou année manquants (la recherche ne retourne pas
            // toujours les releases — ex. remixes, singles sans release dédiée dans MB)
            if ((best.album.isBlank() || best.year.isBlank()) && !best.recordingMbid.isBlank()) {
                step.accept(I18n.t("enrichissement MusicBrainz…"));
                try {
                    String lookupCached = cache.getLookup(best.recordingMbid);
                    TagInfo full = (lookupCached != null)
                        ? mb.parseFromCacheLookup(lookupCached)
                        : mb.lookupRecording(best.recordingMbid);
                    if (full != null) {
                        if (lookupCached == null) cache.putLookup(best.recordingMbid, mb.lastRawJson());
                        // full.artist vient de extractTrackArtists() sur le artist-credit COMPLET de
                        // CET enregistrement précis (déjà confirmé via recordingMbid) — inclut les
                        // featurings/collaborateurs via joinphrase ("Adriatique, Marino Canal et
                        // Delhia De France"), contrairement à best.artist qui peut venir d'une source
                        // plus pauvre (SongRec/AcoustID ne renvoient souvent que l'artiste principal).
                        // Repéré en direct 2026-08-28 : ce bloc enrichissait déjà album/année/pistes
                        // depuis full, mais jamais l'artiste — un featuring correctement retrouvé côté
                        // MusicBrainz restait perdu malgré tout. Écrasement inconditionnel (comme
                        // album/année ci-dessous) : full vient d'un lookup DIRECT sur le MBID déjà
                        // confirmé, donc plus fiable que best.artist quelle que soit sa source.
                        if (!full.artist.isBlank())           best.artist           = full.artist;
                        if (!full.album.isBlank())            best.album            = full.album;
                        if (!full.year.isBlank())             best.year             = full.year;
                        if (!full.track.isBlank())            best.track            = full.track;
                        if (!full.trackTotal.isBlank())       best.trackTotal       = full.trackTotal;
                        if (!full.discNo.isBlank())           best.discNo           = full.discNo;
                        if (!full.releaseMbid.isBlank())      best.releaseMbid      = full.releaseMbid;
                        if (!full.albumArtist.isBlank())      best.albumArtist      = full.albumArtist;
                        if (!full.albumArtistSort.isBlank())  best.albumArtistSort  = full.albumArtistSort;
                        if (!full.releaseGroupMbid.isBlank()) best.releaseGroupMbid = full.releaseGroupMbid;
                        if (!full.artistMbid.isBlank())       best.artistMbid       = full.artistMbid;
                        if (!full.artistSort.isBlank())       best.artistSort       = full.artistSort;
                        if (!full.isrc.isBlank())             best.isrc             = full.isrc;
                        if (!full.language.isBlank())         best.language         = full.language;
                        log(I18n.t("  enrichi←lookup: album='%s' année='%s' artistMbid='%s'",
                            best.album, best.year, best.artistMbid));
                    }
                } catch (Exception ignored) {}
            }

            // Fallback MB search : si album ou artistMbid toujours vides après lookup.
            if (!best.artist.isBlank()
                    && (best.album.isBlank() || best.artistMbid.isBlank())) {
                try {
                    List<TagInfo> mbr = mb.searchRecording(best.artist, best.title);
                    if (!mbr.isEmpty() && mbr.get(0).score >= 70) {
                        TagInfo full = mbr.get(0);
                        if (best.album.isBlank()            && !full.album.isBlank())            best.album            = full.album;
                        if (best.year.isBlank()             && !full.year.isBlank())             best.year             = full.year;
                        if (best.track.isBlank()            && !full.track.isBlank())            best.track            = full.track;
                        if (best.trackTotal.isBlank()       && !full.trackTotal.isBlank())       best.trackTotal       = full.trackTotal;
                        if (best.discNo.isBlank()           && !full.discNo.isBlank())           best.discNo           = full.discNo;
                        if (best.albumArtist.isBlank()      && !full.albumArtist.isBlank())      best.albumArtist      = full.albumArtist;
                        if (best.albumArtistSort.isBlank()  && !full.albumArtistSort.isBlank())  best.albumArtistSort  = full.albumArtistSort;
                        if (best.artistSort.isBlank()       && !full.artistSort.isBlank())       best.artistSort       = full.artistSort;
                        if (best.artistMbid.isBlank()       && !full.artistMbid.isBlank())       best.artistMbid       = full.artistMbid;
                        if (best.releaseMbid.isBlank()      && !full.releaseMbid.isBlank())      best.releaseMbid      = full.releaseMbid;
                        if (best.releaseGroupMbid.isBlank() && !full.releaseGroupMbid.isBlank()) best.releaseGroupMbid = full.releaseGroupMbid;
                        if (best.recordingMbid.isBlank()    && !full.recordingMbid.isBlank())    best.recordingMbid    = full.recordingMbid;
                        if (best.isrc.isBlank()             && !full.isrc.isBlank())             best.isrc             = full.isrc;
                        log(I18n.t("  enrichi←MB search: album='%s' année='%s' artistMbid='%s'",
                            best.album, best.year, best.artistMbid));
                    }
                } catch (Exception ignored) {}
            }

            // ── Préservation des compilations ─────────────────────────────────────
            // Si le fichier original était dans une compilation (albumArtist = Various Artists,
            // ou tag IS_COMPILATION = 1) et que MB n'a pas trouvé de release compilation,
            // on restaure l'album et albumArtist originaux pour ne pas perdre la structure.
            //
            // RESTREINT à SOURCE_TEXT (2026-08-17, retour utilisateur) : cette restauration fait
            // confiance aveuglément à des tags EXISTANTS potentiellement faux (mauvais rip, tag
            // générique laissé par un autre outil) — sans garde-fou, elle écraserait même un
            // résultat frais confirmé par empreinte audio (SongRec/AcoustID) ou par un MBID/TOC
            // déjà vérifié, qui sont des preuves bien plus fiables du VRAI contexte album que
            // d'anciens tags jamais revérifiés. Ne s'applique donc plus que quand l'identification
            // vient d'une simple recherche texte MB — le seul cas où "faire confiance à ce qui
            // était déjà là" a un sens, cohérent avec le bug de confiance aveugle SongRec déjà
            // corrigé cette session pour la même raison. Chaque restauration reste tracée (voir
            // CompilationRestoreLog) pour audit, même dans ce cas restreint.
            if (Config.get().preserveCompilationAlbum()
                    && MetadataCache.SOURCE_TEXT.equals(lastFindTagsSource.get())) {
                String origAlbumArtist   = readTag(fichier, FieldKey.ALBUM_ARTIST);
                String origIsCompilation = readTag(fichier, FieldKey.IS_COMPILATION);
                String origAlbum         = cleanSearchTerm(readTag(fichier, FieldKey.ALBUM));
                boolean origWasCompilation =
                    "1".equals(origIsCompilation.trim())
                    || Config.get().vaName().equalsIgnoreCase(origAlbumArtist)
                    || "Various Artists".equalsIgnoreCase(origAlbumArtist);
                // Signal structurel (2026-09-09) : voir folderLooksLikeCompilation() pour le
                // pourquoi. Indépendant des 3 signaux ci-dessus (qui reposent tous sur le tag
                // ALBUM_ARTIST/IS_COMPILATION de CE fichier précis) — seulement consulté si aucun
                // d'eux n'a déjà tranché.
                boolean folderIsCompilation = !origWasCompilation
                        && folderLooksLikeCompilation(fichier, origAlbum);
                // MB n'a pas retourné de release compilation → restaurer contexte original
                if ((origWasCompilation || folderIsCompilation)
                        && !origAlbum.isBlank() && !"1".equals(best.isCompilation)) {
                    // Signal structurel : ne JAMAIS reprendre l'artiste album DE CE FICHIER — ce
                    // n'est que l'un des contributeurs de la compilation (ex. "Didier Barbelivien"
                    // sur "Vendée 93"), pas l'artiste de la compilation elle-même. C'est justement
                    // cette confusion qui a fait rater les 3 signaux ci-dessus pour ce cas réel.
                    // Signal déjà explicite (Various Artists/IS_COMPILATION) : comportement
                    // inchangé, le tag de ce fichier fait toujours foi.
                    String restoredAlbumArtist = folderIsCompilation
                        ? Config.get().vaName()
                        : (origAlbumArtist.isBlank() ? Config.get().vaName() : origAlbumArtist);
                    log(I18n.t("  compilation restaurée (%s) : album='%s' albumArtist='%s'",
                        folderIsCompilation ? "dossier" : "tag", origAlbum, restoredAlbumArtist));
                    com.opentagger.CompilationRestoreLog.record(
                        fichier.getAbsolutePath(), lastFindTagsSource.get(),
                        origAlbum, restoredAlbumArtist,
                        best.album, best.albumArtist);
                    best.album         = origAlbum;
                    best.albumArtist   = restoredAlbumArtist;
                    best.isCompilation = "1";
                    // track#, disc#, MBID, artist, title restent ceux de MB
                }
            }

            log(I18n.t("  corrector..."));
            // fichier (déjà résolu via entry.currentPath en tête de méthode, ligne ~384) et non
            // entry.file : ce dernier est le chemin D'ORIGINE au chargement et ne change jamais,
            // même après un renommage (même défaut que "Ouvrir le dossier parent"/"Renommer ce
            // fichier" dans MainFrame — corrigé aussi).
            corrector.correct(best, fichier.toPath());
            taggerScript.apply(best);

            step.accept(I18n.t("genres…"));
            log(I18n.t("  genres..."));
            TagEnrichment.enrichGenre(best, discogs, lastFm, cache);
            TagEnrichment.enrichClassicalWork(best, mb, cache);
            log(I18n.t("  genre=%s", best.genre));

            if (best.mood.isBlank()) {
                try { lastFm.enrichMood(best, cache); log(I18n.t("  mood←lastfm=%s", best.mood)); } catch (Exception ignored) {}
            }
            if (best.tags.isBlank()) {
                try { lastFm.enrichTags(best, cache); } catch (Exception ignored) {}
            }
            TagEnrichment.enrichArtistInfo(best, discogs, lastFm, cache);

            if (bpmEnabled && best.bpm.isBlank()) {
                step.accept(I18n.t("BPM…"));
                log(I18n.t("  BPM..."));
                int bpm = bpmDet.detect(fichier.getAbsolutePath());
                if (bpm > 0) best.bpm = String.valueOf(bpm);
                log(I18n.t("  bpm=%s", best.bpm));
            }

            // Empreinte AcoustID : calculée même quand l'identification vient de SongRec/AudD/texte
            // (pas seulement du chemin AcoustID) — comme Picard, qui la calcule systématiquement peu
            // importe la méthode d'identification. acoustidId (le match AcoustID confirmé) n'est PAS
            // renseigné ici : il suppose une vraie correspondance trouvée via l'API, pas juste un
            // calcul local.
            if (Config.get().saveAcoustidFingerprints() && best.acoustidFingerprint.isBlank()
                    && FpcalcInstaller.isAvailable()) {
                step.accept(I18n.t("empreinte…"));
                log(I18n.t("  empreinte..."));
                try {
                    best.acoustidFingerprint = Fingerprinter.compute(fichier).fingerprint();
                    log(I18n.t("  empreinte calculée"));
                } catch (Exception ex) {
                    log(I18n.t("  empreinte: %s", ex.getMessage()));
                }
            }

            if (essentiaEnabled) {
                log(I18n.t("  essentia..."));
                essentia.analyze(fichier.getAbsolutePath(), best);
                log(I18n.t("  mood←essentia=%s", best.mood));
            }

            // ── Translittération artiste (si nom non-Latin et option activée) ──────
            String artistBeforeTranslit = best.artist;
            TagEnrichment.translateArtist(best, mb, aliasCache);
            if (!best.artist.equals(artistBeforeTranslit))
                log(I18n.t("  translit: %s → %s", artistBeforeTranslit, best.artist));

            log(I18n.t("  lyrics..."));
            try { lyrics.enrich(best); } catch (Exception ignored) {}

            // ── ReplayGain (analyse locale, ne touche pas le fichier) ────────────
            if (rgEnabled()) {
                log(I18n.t("  replaygain..."));
                try {
                    ReplayGainAnalyzer.RGResult rg = replayGain.analyze(fichier.getAbsolutePath());
                    if (rg != null) {
                        best.replayGainTrackGain = rg.trackGain();
                        best.replayGainTrackPeak = rg.trackPeak();
                        log(I18n.t("  rg: gain=%s peak=%s", rg.trackGain(), rg.trackPeak()));
                    }
                } catch (Exception ignored) {}
            }

            // Plus d'écriture ici — façon Picard, l'identification ne touche jamais le disque.
            // Pochette, renommage, cache/historique et soumissions MB/AcoustID sont tous
            // déplacés dans TagEnrichment.saveEntry(), appelé plus tard par SaveWorker
            // ("Enregistrer tout") — voir son commentaire pour le pourquoi (notamment la
            // pochette, qui télécharge dans un fichier temporaire ne pouvant pas survivre à
            // l'écart de temps désormais possible entre Identifier et Enregistrer).
            // lastFindTagsSource est un ThreadLocal, perdu à la fin de cet appel : on le
            // persiste dans le TagInfo pour qu'il survive jusqu'à un Enregistrer différé.
            best.identificationSource = lastFindTagsSource.get();

            // ── Auto-capitalisation (opt-in, capitalize.enabled) ───────────────
            // Dernier point avant les suggestions : toutes les autres mutations (script
            // utilisateur, enrichissement genre, translittération, paroles) sont déjà
            // appliquées à `best` à ce stade. Désactivée par défaut — voir TitleCaseFixer.
            if (Config.get().capitalizeEnabled()) {
                java.util.Set<String> lower  = splitCsv(Config.get().capitalizeLowercaseWords(), true);
                java.util.Set<String> upper  = splitCsv(Config.get().capitalizeUppercaseWords(), false);
                java.util.Set<String> prefix = splitCsv(Config.get().capitalizeKeepPrefixes(), false);
                best.title       = TitleCaseFixer.fix(best.title,       lower, upper, prefix);
                best.artist      = TitleCaseFixer.fix(best.artist,      lower, upper, prefix);
                best.albumArtist = TitleCaseFixer.fix(best.albumArtist, lower, upper, prefix);
                best.album       = TitleCaseFixer.fix(best.album,       lower, upper, prefix);
            }

            // ── Suggestions d'amélioration ────────────────────────────────────
            // Sans le critère pochette : elle n'est résolue qu'à l'Enregistrement (voir
            // ci-dessus), donc "Pochette non trouvée" ne peut être établi qu'après coup —
            // c'est SaveWorker qui l'ajoute, une fois TagEnrichment.saveEntry() revenu.
            List<String> sugg = buildSuggestions(best, seuil);

            final TagInfo    finalBest = best;
            final List<String> finalSugg = sugg;
            // Mémorisé tout de suite, hors mémoire vive : une fermeture ou un arrêt de l'app avant l'enregistrement ne perd plus cette
            // identification (retrouvée au scan suivant tant que le fichier n'a pas changé — voir MetadataCache.pending_identified).
            if (Config.get().bool("tagging.persist_pending", true)) {
                try { cache.savePendingIdentified(fichier.getAbsolutePath(), fichier.length(), fichier.lastModified(), finalBest); }
                catch (Exception ignored) {}
            }
            // Muter entry SUR l'EDT, pas ici : ce FileEntry est aussi lu par le TableRowSorter
            // en direct depuis l'EDT — même raison que processAlbumFolder (voir son commentaire
            // / SafeTableRowSorter pour le filet de sécurité).
            SwingUtilities.invokeLater(() -> {
                entry.result = finalBest;
                entry.status = FileEntry.Status.IDENTIFIED;
                if (!finalSugg.isEmpty()) {
                    entry.suggestions = finalSugg;
                    entry.message = "⚠" + finalSugg.size();
                } else {
                    entry.message = "";
                }
            });
            log(I18n.t("  ✓ IDENTIFIED %s%s (pas encore enregistré)", fichier.getName(),
                sugg.isEmpty() ? "" : I18n.t(" (%s suggestion(s))", sugg.size())));

        } catch (Exception ex) {
            entry.status     = FileEntry.Status.ERROR;
            // NETWORK_ERROR distingué d'ERROR_GENERIC (2026-09-05, retour utilisateur après une
            // coupure réseau réelle qui a fait échouer des dizaines de fichiers d'affilée avec
            // ConnectException/HTTP connect timed out) : avant ce correctif, les deux tombaient
            // dans le même seau "Erreur", impossible à distinguer d'un vrai bug/fichier corrompu
            // sans relire chaque message un par un. Ne relance PAS automatiquement ici (une
            // coupure prolongée referait échouer un retry immédiat tout aussi vite) — sert juste à
            // pouvoir filtrer/resélectionner ces fichiers après coup (Rapport Non identifiés) pour
            // un "Forcer le re-taguage" ciblé une fois la connexion revenue, plutôt que fouiller
            // dans des milliers de lignes de journal pour savoir lesquels retenter.
            entry.skipReason = isNetworkException(ex)
                    ? com.opentagger.model.SkipReason.NETWORK_ERROR
                    : com.opentagger.model.SkipReason.ERROR_GENERIC;
            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            entry.message = (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                    + videoHintIfAny(f);
            log(I18n.t("  ✗ ERROR %s : %s", entry.filename(), entry.message));
        }
    }

    /** Vrai si l'exception (ou une de ses causes) est une panne réseau typique (coupure, DNS,
     *  timeout) plutôt qu'un vrai bug/fichier corrompu — voir le commentaire du catch ci-dessus. */
    private static boolean isNetworkException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.net.ConnectException
                    || c instanceof java.net.UnknownHostException
                    || c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.http.HttpTimeoutException
                    || c instanceof javax.net.ssl.SSLException) return true;
        }
        return false;
    }

    /** Indice "ceci est peut-être une vidéo" pour un ".mp4" en échec d'identification — voir
     *  AudioFormatCheck.hasVideoStream() pour le pourquoi (AudioScanner/VideoScanner ne se parlent
     *  pas sur ce cas ambigu). Chaîne vide (jamais null, pour la concaténation directe sur
     *  entry.message) si l'extension n'est pas ".mp4" ou si aucun flux vidéo n'est détecté —
     *  ffprobe n'est appelé QUE pour les ".mp4" déjà en échec, jamais sur le chemin normal.
     */
    private static String videoHintIfAny(File fichier) {
        if (!fichier.getName().toLowerCase().endsWith(".mp4")) return "";
        if (!com.opentagger.AudioFormatCheck.hasVideoStream(fichier)) return "";
        return I18n.t(" — vidéo détectée : voir Outils → Récupérer l'audio des vidéos non reconnues");
    }

    // Nom du fichier en cours de traitement SUR CE THREAD — batch.threads (défaut 6) traite
    // plusieurs fichiers en parallèle, chacun appelant ce même log() static ; sans préfixe, les
    // lignes de fichiers DIFFÉRENTS s'entremêlent dans le flux stdout partagé et rendent impossible
    // de suivre le cheminement (SongRec/AcoustID/texte/durée) d'UN fichier précis en particulier —
    // gênant en direct (2026-08-11) en essayant de diagnostiquer pourquoi un match précis semblait
    // faux : impossible de savoir avec certitude quelles lignes appartenaient réellement au fichier
    // recherché. Positionné une fois par fichier (juste avant "▶ START"), lu à chaque log().
    private static final ThreadLocal<String> CURRENT_FILE = new ThreadLocal<>();

    private static void log(String msg) {
        String ctx = CURRENT_FILE.get();
        String prefix = ctx != null ? "[" + ctx + "] " : "";
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + prefix + msg);
        System.out.flush();
    }

    // ── Identification d'album par TOC (voir findTags() étape 0.6) ───────────────────────────

    /**
     * Un seul lookup MB par ALBUM (jamais par fichier — voir discIdCache dans findTags()). Renvoie
     * {@code null} si le dossier ne se prête pas au matching TOC (trop peu de pistes, numéros de
     * piste manquants/non contigus/dupliqués, durées inconnues pour au moins un fichier) ou si
     * aucun candidat suffisamment confiant n'a été trouvé — jamais d'exception propagée, un échec
     * ici ne doit jamais interrompre le taguage normal du dossier (repli silencieux vers la suite
     * de la cascade, voir l'appelant).
     */
    private MusicBrainzClient.ReleaseTracklist matchFolderByToc(Path folder, MusicBrainzClient mb) {
        try {
            List<FileEntry> siblings = new ArrayList<>();
            for (FileEntry fe : entries) {
                Path p = fe.currentPath != null ? fe.currentPath : fe.file.toPath();
                if (folder.equals(p.getParent())) siblings.add(fe);
            }
            int minTracks = Config.get().discIdMinTracks();
            if (siblings.size() < minTracks) return null;

            // Ordre = numéro de piste EXISTANT, exige une séquence 1..N sans trou ni doublon —
            // bien plus sûr qu'un tri par nom de fichier (jamais garanti fiable sur cette
            // bibliothèque, plusieurs conventions de nommage mélangées selon la source d'origine).
            // Un dossier qui ne respecte pas ça est un candidat trop incertain : on préfère
            // s'abstenir plutôt que risquer un matching TOC sur un ordre faux.
            int[] durations = new int[siblings.size()];
            boolean[] seen = new boolean[siblings.size() + 1];
            for (FileEntry fe : siblings) {
                TagInfo cur = fe.current;
                if (cur == null || cur.durationSec <= 0 || cur.track.isBlank()) return null;
                int tn;
                try { tn = Integer.parseInt(cur.track.split("/")[0].trim()); }
                catch (NumberFormatException e) { return null; }
                if (tn < 1 || tn > siblings.size() || seen[tn]) return null;
                seen[tn] = true;
                durations[tn - 1] = cur.durationSec;
            }

            List<Integer> durList = new ArrayList<>();
            for (int d : durations) durList.add(d);
            List<MusicBrainzClient.DiscIdCandidate> candidates = mb.lookupByToc(durList);
            if (candidates.isEmpty()) return null;

            // Secteurs attendus — mêmes maths que MusicBrainzClient.lookupByToc(), pour classer les
            // candidats renvoyés par le lookup flou.
            int expectedSectors = 150;
            for (int d : durations) expectedSectors += d * 75;

            String bestMbid = null;
            int bestDiff = Integer.MAX_VALUE;
            for (MusicBrainzClient.DiscIdCandidate c : candidates) {
                if (c.trackCount() != siblings.size()) continue;
                int diff = Math.abs(c.sectors() - expectedSectors);
                if (diff < bestDiff) { bestDiff = diff; bestMbid = c.releaseMbid(); }
            }
            // Tolérance généreuse (5% du total) : les durées viennent de fichiers déjà encodés/
            // rippés, pas d'une lecture directe du TOC physique — un écart bien plus large qu'un
            // vrai disque signale un mauvais candidat plutôt qu'une simple imprécision d'arrondi.
            if (bestMbid == null || bestDiff > expectedSectors * 0.05) return null;

            log(I18n.t("  Album complet détecté (%d pistes, dossier '%s') → identification par TOC…",
                    siblings.size(), folder.getFileName()));
            return mb.lookupRelease(bestMbid);
        } catch (Exception e) {
            return null;
        }
    }

    // Motif "CDx"/"Disc x"/"Disque x" — écart trouvé vs SongKong (BoxSetScorer.groupByMedium,
    // analyse du jar décompilé 2026-09-18) : sur un coffret multi-CD organisé en sous-dossiers
    // physiques ("Album/CD1/", "Album/CD2/"), findTrackInRelease()/findTrackInReleaseByTitle()
    // cherchaient jusqu'ici dans TOUTE la tracklist de la release (tous disques confondus) — un
    // "track 5" sur CD1 et un "track 5" sur CD2 sont pourtant deux pistes DIFFÉRENTES de la même
    // release MusicBrainz (medium différent), risque réel de collision numéro de piste ou de titre
    // partagé (intro/bonus répété par disque, fréquent sur les captations live). Volontairement
    // PAS le repli "chiffre final du nom de dossier" de SongKong (trop ambigu — "Bootleg 2019" n'a
    // rien d'un numéro de disque) : seuls le motif explicite CD/Disc/Disque et le tag DISC_NO déjà
    // présent sur le fichier sont retenus, aucun risque de faux indice.
    private static final java.util.regex.Pattern DISC_FOLDER_PATTERN =
            java.util.regex.Pattern.compile("(?i)\\b(?:cd|dis[ck]|disque)\\s*0*(\\d+)\\b");

    /** Numéro de disque probable pour ce fichier (0 = aucun indice fiable) : priorité au tag
     *  DISC_NO déjà présent sur LE FICHIER lui-même (le plus direct), repli sur le nom du dossier
     *  parent immédiat s'il correspond au motif CD/Disc/Disque — voir DISC_FOLDER_PATTERN. */
    private int detectDiscHint(File fichier) {
        String discTag = readTag(fichier, FieldKey.DISC_NO);
        if (discTag != null && !discTag.isBlank()) {
            try { return Integer.parseInt(discTag.split("/")[0].trim()); }
            catch (NumberFormatException ignored) {}
        }
        File parent = fichier.getParentFile();
        if (parent != null) {
            java.util.regex.Matcher m = DISC_FOLDER_PATTERN.matcher(parent.getName());
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    /** Piste MB dont le recordingMbid correspond EXACTEMENT au tag MUSICBRAINZ_TRACK_ID déjà
     *  présent sur le fichier — {@code null} si le tag est absent ou qu'aucune piste ne correspond.
     *  Écart trouvé vs Picard (analyse du code source cloné, 2026-09-18) : {@code Album.
     *  _match_files()} essaie TOUJOURS ce tier "identifiants" (MBID exact) avant tout scoring flou
     *  numéro/titre — un identifiant déjà connu et fiable (venant par ex. d'AcoustID/Shazam à une
     *  étape précédente de findTags(), ou d'un tag déjà correct) ne doit jamais être ignoré au
     *  profit d'une correspondance approximative. Toujours tenté en premier par les deux appelants
     *  ci-dessous, avant findTrackInRelease()/findTrackInReleaseByTitle(). */
    private MusicBrainzClient.ReleaseTrack findTrackByRecordingMbid(MusicBrainzClient.ReleaseTracklist tl, File fichier) {
        String mbidTag = readTag(fichier, FieldKey.MUSICBRAINZ_TRACK_ID);
        if (mbidTag == null || mbidTag.isBlank()) return null;
        String mbid = mbidTag.trim();
        for (MusicBrainzClient.ReleaseTrack t : tl.tracks()) if (mbid.equalsIgnoreCase(t.recordingMbid())) return t;
        return null;
    }

    /** Piste MB correspondant au numéro TRACK du fichier — {@code null} si le tag est absent/
     *  illisible ou qu'aucune piste de la release ne porte ce numéro. Si un indice de disque est
     *  disponible (voir detectDiscHint()), cherche D'ABORD restreint à ce disque — seulement pour
     *  départager une collision réelle (même numéro de piste sur 2 disques) ; repli sur la
     *  recherche non restreinte si rien ne correspond sur le disque indiqué (indice possiblement
     *  faux, ou pistes numérotées en continu sur toute la release plutôt que par disque) — SAUF si
     *  {@code discRestrictedOnly}, voir son Javadoc. */
    private MusicBrainzClient.ReleaseTrack findTrackInRelease(MusicBrainzClient.ReleaseTracklist tl, File fichier) {
        return findTrackInRelease(tl, fichier, false);
    }

    /** @param discRestrictedOnly si {@code true} et qu'un indice de disque existe, N'ACCEPTE QUE
     *  la correspondance sur CE disque précis — jamais le repli non restreint. Écart trouvé par
     *  audit dédié (2026-09-19) : le repli "TOUTE la tracklist par numéro de piste, sans vérifier
     *  le titre" est sûr pour le TOC (étape 0.6, album entier déjà confirmé par checksum de
     *  durées), mais dangereux pour le pin de groupe (étape 0.65, release proposée par UNE seule
     *  autre piste) — un coffret CD1/CD2 mal nommé fusionné à tort avec une AUTRE compilation (ex.
     *  deux volumes d'une série radio classés "CD1"/"CD2" par erreur) pouvait alors matcher un
     *  fichier de CD2 sur une piste de la release mono-disque épinglée par CD1, par pure
     *  coïncidence de numéro — sans jamais passer par la vérification de titre de
     *  findTrackInReleaseByTitle(). Les appelants TOC passent {@code false} (comportement
     *  inchangé, contexte déjà fiable) ; l'appelant "Cohérence de groupe" passe {@code true} : sans
     *  disque correspondant, on préfère retourner {@code null} et laisser le vrai garde-fou de
     *  titre (findTrackInReleaseByTitle(), seuil 0.7) trancher plutôt qu'un simple hasard de numéro. */
    private MusicBrainzClient.ReleaseTrack findTrackInRelease(
            MusicBrainzClient.ReleaseTracklist tl, File fichier, boolean discRestrictedOnly) {
        String trackTag = readTag(fichier, FieldKey.TRACK);
        if (trackTag == null || trackTag.isBlank()) return null;
        int tn;
        try { tn = Integer.parseInt(trackTag.split("/")[0].trim()); }
        catch (NumberFormatException e) { return null; }
        int discHint = detectDiscHint(fichier);
        if (discHint > 0) {
            for (MusicBrainzClient.ReleaseTrack t : tl.tracks())
                if (t.trackNo() == tn && t.disc() == discHint) return t;
            if (discRestrictedOnly) return null;
        }
        for (MusicBrainzClient.ReleaseTrack t : tl.tracks()) if (t.trackNo() == tn) return t;
        return null;
    }

    // Seuil DÉDIÉ, distinct de match.track_matching_threshold (0.4 par défaut) — trouvé en direct
    // 2026-09-02 (retour utilisateur, log réel) : à 0.4, cette méthode choisissait systématiquement
    // "la moins pire" piste d'une release épinglée comportant des dizaines de pistes (ex. "The
    // Annual 2017", grosse compilation), même pour des fichiers sans AUCUN rapport réel — "Facile.mp3"
    // épinglé sur "Jonas Blue – Perfect Strangers", "download(4).mp3.mp3" sur "James Brown – Mind
    // Power"... Aucun rapport de bon sens entre le nom de fichier et le titre choisi. Gravité : le
    // résultat part avec score=92 (voir l'appelant), AU-DESSUS de autocorrector.min_score (90) — donc
    // auto-enregistré sans aucune revue, une vraie corruption de métadonnées à grande échelle sur un
    // dossier de fichiers non identifiés mélangés (pas un vrai album cohérent). 0.4 reste adapté à
    // d'autres usages de trackMatchingThreshold() (filtres plus souples, décisions moins engageantes)
    // — volontairement PAS touché ici pour ne pas modifier leur comportement sans preuve similaire.
    private static final double GROUP_PIN_TITLE_MATCH_THRESHOLD = 0.7;

    /** Repli de {@link #findTrackInRelease} pour groupPinnedRelease (voir findTags() étape 0.65) :
     *  contrairement au TOC (dossier "album complet" garanti par construction), une piste épinglée
     *  par une AUTRE piste du groupe n'a pas forcément de tag TRACK exploitable (fichier encore
     *  brut) — repli sur la similarité de titre contre chaque piste de la tracklist, seuil dédié et
     *  volontairement strict (voir {@link #GROUP_PIN_TITLE_MATCH_THRESHOLD}) puisque le résultat est
     *  auto-enregistré sans revue humaine. */
    private MusicBrainzClient.ReleaseTrack findTrackInReleaseByTitle(MusicBrainzClient.ReleaseTracklist tl, File fichier) {
        String titleTag = cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
        if (titleTag.isBlank() || isGenericTag(titleTag)) return null;
        // Indice de disque (voir detectDiscHint()) : un titre identique/proche peut légitimement
        // se répéter sur plusieurs disques d'un même coffret (intro, outro, bonus track répété) —
        // restreindre au disque indiqué quand disponible évite de choisir la mauvaise occurrence.
        int discHint = detectDiscHint(fichier);
        MusicBrainzClient.ReleaseTrack bestTrack = null;
        double bestSim = 0;
        for (MusicBrainzClient.ReleaseTrack t : tl.tracks()) {
            if (discHint > 0 && t.disc() != discHint) continue;
            double sim = TrackMatcher.titleSimilarity(titleTag.toLowerCase(), t.title().toLowerCase());
            if (sim > bestSim) { bestSim = sim; bestTrack = t; }
        }
        if (bestTrack != null && bestSim >= GROUP_PIN_TITLE_MATCH_THRESHOLD) return bestTrack;
        if (discHint == 0) return null; // déjà cherché sans restriction ci-dessus, rien trouvé
        // Repli non restreint : l'indice de disque était peut-être faux (dossier mal nommé), ou le
        // vrai medium correspondant n'a simplement pas cette piste — ne jamais bloquer un match par
        // ailleurs valable à cause d'un indice qui s'avère trompeur.
        bestTrack = null; bestSim = 0;
        for (MusicBrainzClient.ReleaseTrack t : tl.tracks()) {
            double sim = TrackMatcher.titleSimilarity(titleTag.toLowerCase(), t.title().toLowerCase());
            if (sim > bestSim) { bestSim = sim; bestTrack = t; }
        }
        return bestSim >= GROUP_PIN_TITLE_MATCH_THRESHOLD ? bestTrack : null;
    }

    // ── Résolution des tags — avec cache SQLite ───────────────────────────────

    private List<TagInfo> findTags(File fichier, TagInfo existingTags, boolean forceReidentify,
                                    boolean bandcampOnly,
                                    MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm,
                                    MetadataCache cache, String groupKey) throws Exception {
        // 0-pre. Réparer les M4A avec structure mdat<moov non lisible par jaudiotagger
        if (TagWriter.repairM4aIfNeeded(fichier)) log(I18n.t("  M4A réparé OK"));

        // Mode dédié (FileEntry.bandcampOnly, déclenché via le menu Retraitement) : court-circuite
        // toute la cascade normale (historique, cache, MB, AcoustID/SongRec...) et n'essaie QUE la
        // devinette Bandcamp, à partir des tags actuels du fichier — voir tryBandcampGuess() et son
        // appel normal en 6a plus bas (même logique, factorisée). Contrairement à forceReidentify,
        // ne touche ni le cache ni les MBID déjà présents sur le fichier : ce mode s'applique à des
        // fichiers déjà SKIPPED, rien à préserver ni à effacer.
        if (bandcampOnly) {
            String bcArtist = cleanSearchTerm(readTag(fichier, FieldKey.ARTIST));
            String bcTitle  = cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
            bcArtist = collapseSelfConcatenatedTitle(bcArtist);
            bcTitle  = collapseSelfConcatenatedTitle(bcTitle);
            if (isGenericTag(bcArtist)) bcArtist = "";
            if (isGenericTag(bcTitle))  bcTitle  = "";
            boolean nonLatin = TagEnrichment.hasNonLatinChars(bcArtist) || TagEnrichment.hasNonLatinChars(bcTitle);
            TagInfo bc = nonLatin ? null : tryBandcampGuess(fichier, bcArtist, bcTitle);
            return bc != null ? List.of(bc) : List.of();
        }

        // Indice d'album : tag existant > nom du dossier parent.
        // Permet à pickBestRelease() de favoriser la release MB qui correspond au dossier iTunes.
        // Ex. : dossier "100 Club Hits Edition 2022" → MB préfère cette compilation si elle existe.
        // En reidentification forcée, on ignore le tag album existant pour la même raison que
        // l'artiste/titre plus bas : un album déjà faux biaiserait le choix de release MB vers ce
        // même album erroné, même une fois l'artiste/titre correctement retrouvés via SongRec.
        {
            String tagAlbum    = forceReidentify ? "" : cleanSearchTerm(readTag(fichier, FieldKey.ALBUM));
            String folderAlbum = fichier.getParentFile() != null
                ? fichier.getParentFile().getName() : "";
            // Le tag existant prime ; le dossier parent sert de fallback si tag vide
            String albumHint = tagAlbum.isBlank() ? folderAlbum : tagAlbum;
            mb.setPreferredAlbum(albumHint);
            if (!albumHint.isBlank()) log(I18n.t("  indice album : '%s'", albumHint));
        }

        // 0. Historique personnel — ce fichier a-t-il déjà été tagué par OpenTagger ?
        //    Confiance totale SEULEMENT si identifié par empreinte audio (songrec/acoustid/mbid).
        //    Si identifié par texte ("text"), SongRec doit vérifier car les tags peuvent être faux.
        lastFindTagsSource.set(MetadataCache.SOURCE_TEXT);
        String knownMbid = cache.getFileTagging(fichier.getAbsolutePath());
        if (knownMbid != null) knownMbid = knownMbid.trim();
        if (!forceReidentify && knownMbid != null && !knownMbid.isBlank()) {
            String cachedSource = cache.getFileTaggingSource(fichier.getAbsolutePath());
            boolean trustedSource = MetadataCache.SOURCE_SONGREC.equals(cachedSource)
                                 || MetadataCache.SOURCE_ACOUSTID.equals(cachedSource)
                                 || MetadataCache.SOURCE_MBID.equals(cachedSource);
            TagInfo hist = cache.getTaggingHistory(knownMbid);
            if (hist != null && (!hist.artist.isBlank() || !hist.title.isBlank())) {
                if (trustedSource) {
                    // Empreinte audio → confiance totale, retour instantané
                    hist.score = 100;
                    lastFindTagsSource.set(cachedSource);
                    log(I18n.t("  cache hit [%s] ✓ : %s – %s", cachedSource, hist.artist, hist.title));
                    return List.of(hist);
                } else {
                    // Identification textuelle → on continue vers SongRec pour vérifier
                    log(I18n.t("  cache hit [text] → SongRec va vérifier : %s – %s", hist.artist, hist.title));
                }
            } else if (hist != null) {
                log(I18n.t("  cache hit IGNORÉ (artiste+titre vides) pour mbid=%s", knownMbid));
            }
        }

        // 0.5. Tags MB existants complets (Picard, MusicBrainz Tagger, session précédente).
        // Si le fichier a déjà releaseMbid + artist + title + album valides → confiance totale,
        // SOUS RÉSERVE qu'un passage SongRec rapide (gratuit, ~qq secondes, pas de clé API) ne
        // les contredise pas. Sans cette vérification, un fichier arrivé d'ailleurs avec un
        // releaseMbid fabriqué (rip YouTube mal identifié à l'origine — vu en direct : tags
        // "Studieo – Adios" alors que l'audio réel était "Bengous – Tié la famille !") passait
        // avec un score 100 sans jamais être comparé à l'audio. Une fois confirmé ici, le fichier
        // entre dans l'historique local (étape 0 ci-dessus) : le prochain scan redevient
        // instantané, donc ce coût SongRec n'est payé qu'une fois par fichier, pas à chaque passe.
        if (!forceReidentify && Config.get().trustExistingMbTags() && existingTags != null
                && !existingTags.releaseMbid.isBlank()
                && !existingTags.artist.isBlank()
                && !existingTags.title.isBlank()
                && !existingTags.album.isBlank()
                && !isGenericTag(existingTags.artist)
                && !isGenericTag(existingTags.title)) {
            boolean confirmed = true;
            boolean songRecInconclusive = false;
            if (SongRecClient.isAvailable()) {
                try {
                    TagInfo sr = songRec.recognize(fichier);
                    if (sr != null && !sr.artist.isBlank() && !sr.title.isBlank()) {
                        confirmed = TrackMatcher.titleSimilarity(
                                sr.artist.toLowerCase(), existingTags.artist.toLowerCase())
                                >= Config.get().trackMatchingThreshold();
                        if (!confirmed) {
                            log(I18n.t("  tags MB existants ⚠ contredits par SongRec (%s – %s) → identification complète",
                                    sr.artist, sr.title));
                        }
                    } else {
                        songRecInconclusive = true; // rien reconnu — pas une confirmation, voir ci-dessous
                    }
                } catch (Exception e) {
                    songRecInconclusive = true; // panne ponctuelle : même repli qu'un résultat vide
                }
            }
            // SongRec muet ("rien reconnu") n'est PAS une confirmation — avant ce correctif, ce cas
            // était traité comme "pas contredit, donc tags existants gardés", alors que SongRec
            // échoue couramment sur du contenu ancien/obscur/mal enregistré (un vrai cas trouvé en
            // direct 2026-09-01 : "François Valéry – J'explose" gardé sur la foi de tags préexistants
            // corrects en apparence — recordingMbid + ISRC valides — alors que l'audio réel était
            // "Princesse Erika – Faut Qu'j'travaille" ; SongRec n'avait rien reconnu du tout sur ce
            // fichier). Seconde opinion via AcoustID (moteur d'empreinte différent, peut réussir là où
            // SongRec échoue) UNIQUEMENT dans ce cas précis — pas sur chaque fichier "de confiance",
            // seulement ceux où la première vérification n'a rien pu confirmer. Toujours piloté par
            // les réglages déjà existants (tags.trust_existing_mb_tags, clé AcoustID configurée),
            // aucune nouvelle case à cocher.
            if (confirmed && songRecInconclusive && !Config.get().acoustidKey().isBlank()) {
                try {
                    List<TagInfo> ar = acoustId.identify(fichier);
                    if (!ar.isEmpty() && !ar.get(0).artist.isBlank()) {
                        TagInfo ai = ar.get(0);
                        confirmed = TrackMatcher.titleSimilarity(
                                ai.artist.toLowerCase(), existingTags.artist.toLowerCase())
                                >= Config.get().trackMatchingThreshold();
                        if (!confirmed) {
                            log(I18n.t("  tags MB existants ⚠ contredits par AcoustID (%s – %s) → identification complète",
                                    ai.artist, ai.title));
                        }
                    }
                    // AcoustID vide aussi : toujours rien de concluant, tags existants gardés comme avant.
                } catch (Exception e) {
                    // AcoustID cassé/indisponible ponctuellement : ne pas bloquer sur une panne d'infra.
                }
            }
            if (confirmed) {
                TagInfo t = existingTags.copy();
                t.score = 100;
                lastFindTagsSource.set(MetadataCache.SOURCE_MBID);
                log(I18n.t("  tags MB existants ✓ [releaseMbid=%s…] %s – %s [%s] → skip identification",
                    existingTags.releaseMbid.substring(0, Math.min(8, existingTags.releaseMbid.length())),
                    existingTags.artist, existingTags.title, existingTags.album));
                return List.of(t);
            }
            // sinon : tombe dans l'étape 1 (SongRec/MB) ci-dessous, qui refait l'identification
            // complète — le résultat SongRec qu'on vient de calculer y sera juste recalculé.
        }

        // 0.6. Identification d'album ENTIER par TOC (checksum de durées de pistes, façon "Albunack
        // Disc IDs" de SongKong — voir MusicBrainzClient.lookupByToc()) — demande utilisateur
        // (2026-08-16), choix explicite "automatique dans la cascade" plutôt qu'une action manuelle.
        // Testé en direct contre l'API MusicBrainz publique (lookup flou ws/2/discid?toc=, aucune
        // dépendance au serveur Albunack propriétaire de SongKong, non accessible à un tiers). Placée
        // AVANT AcoustID/SongRec/recherche texte : une fois le dossier résolu (un seul appel réseau
        // par ALBUM, pas par fichier — voir discIdCache), chaque piste devient une identification
        // instantanée et à haute confiance, sans jamais analyser le contenu audio. Ne s'applique
        // qu'aux dossiers "album complet" (≥ discIdMinTracks pistes, numéros de piste 1..N sans trou
        // ni doublon, durées connues pour toutes) — tout dossier qui ne remplit pas ces conditions
        // (compilation en vrac, singles, dossier incomplet) tombe silencieusement dans la suite
        // normale de la cascade, aucune régression pour les cas déjà bien couverts par ailleurs.
        // Jamais en re-taguage forcé (2026-10-02) : ces étapes 0.6/0.61/0.65 n'écoutent pas l'audio
        // (durées de pistes du dossier, base beets, release épinglée par une autre piste + numéro de
        // piste). "Forcer le re-taguage" promet une ré-identification par l'audio (SongRec/AcoustID
        // d'abord) — avant ce correctif, la cohérence de groupe répondait avant SongRec et validait
        // un fichier au bon nom mais à l'audio différent (score=92 sans jamais l'écouter).
        if (!forceReidentify && Config.get().discIdMatchingEnabled() && fichier.getParentFile() != null) {
            Path folder = fichier.getParentFile().toPath();
            if (!discIdFailedFolders.contains(folder)) {
                MusicBrainzClient.ReleaseTracklist tl =
                        discIdCache.computeIfAbsent(folder, f -> matchFolderByToc(f, mb));
                if (tl == null) {
                    discIdFailedFolders.add(folder);
                } else {
                    MusicBrainzClient.ReleaseTrack myTrack = findTrackByRecordingMbid(tl, fichier);
                    if (myTrack == null) myTrack = findTrackInRelease(tl, fichier);
                    // Garde-fou ajouté après un vrai faux positif en direct (2026-09-13) : "(1974)
                    // Open Our Eyes" (Earth, Wind & Fire, 11 pistes, déjà correctement tagué) a été
                    // apparié par TOC à "The Columbia Masters" — un coffret SANS RAPPORT dont le
                    // nombre de pistes et la durée totale tombaient par coïncidence dans la
                    // tolérance de 5% de matchFolderByToc(). Le commentaire ci-dessous prétendait
                    // qu'un checksum sur tout l'album se passait de vérification de titre ; ce cas
                    // réel prouve le contraire. Même garde-fou que celui déjà en place pour la
                    // release "épinglée par groupe" plus bas (étape 0.65, moins fiable en principe)
                    // : si CE fichier a déjà un titre exploitable, il doit rester plausible face au
                    // titre trouvé par numéro de piste — sinon repli sur findTrackInReleaseByTitle()
                    // (vraie meilleure correspondance sur toute la tracklist), qui peut aussi bien
                    // échouer et laisser ce fichier retomber dans la suite normale de la cascade.
                    if (myTrack != null) {
                        String existingTitleTag = cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
                        if (!existingTitleTag.isBlank() && !isGenericTag(existingTitleTag)
                                && TrackMatcher.titleSimilarity(existingTitleTag.toLowerCase(), myTrack.title().toLowerCase())
                                   < GROUP_PIN_TITLE_MATCH_THRESHOLD) {
                            log(I18n.t("  TOC ⚠ titre existant '%s' incompatible avec '%s' (numéro de piste seul) → repli titre",
                                    existingTitleTag, myTrack.title()));
                            myTrack = findTrackInReleaseByTitle(tl, fichier);
                        }
                    }
                    if (myTrack != null) {
                        TagInfo t = new TagInfo();
                        t.artist           = myTrack.artist();
                        t.title            = myTrack.title();
                        t.album            = tl.album();
                        t.albumArtist      = tl.albumArtist().isBlank() ? myTrack.artist() : tl.albumArtist();
                        t.albumArtistSort  = tl.albumArtistSort();
                        t.year             = tl.year();
                        t.originalYear     = tl.originalYear();
                        t.releaseMbid      = tl.releaseMbid();
                        t.releaseGroupMbid = tl.releaseGroupMbid();
                        t.recordingMbid    = myTrack.recordingMbid();
                        t.releaseTrackMbid = myTrack.trackMbid();
                        t.discSubtitle     = myTrack.discTitle();
                        t.track            = String.valueOf(myTrack.trackNo());
                        t.trackTotal       = String.valueOf(myTrack.trackTotal());
                        if (myTrack.disc() > 0) t.discNo = String.valueOf(myTrack.disc());
                        t.country          = tl.country();
                        t.barcode          = tl.barcode();
                        t.releaseStatus    = tl.releaseStatus();
                        t.label            = tl.label();
                        t.catalogNo        = tl.catalogNo();
                        t.script           = tl.script();
                        // Tout le reste des champs de PARUTION (date complète, type "album;live",
                        // langue, ASIN, URLs, drapeaux live/soundtrack...) — voir
                        // TagInfo.applyReleaseLevelFrom() : ce chemin n'en recopiait qu'une partie.
                        t.applyReleaseLevelFrom(tl.releaseMeta());
                        if (tl.isCompilation()) t.isCompilation = "1";
                        // Score élevé mais volontairement < 100 (réservé aux identifications vérifiées
                        // par empreinte audio/MBID direct) : le checksum porte sur TOUT l'album, une
                        // confiance très élevée, mais jamais de vérification du contenu audio réel.
                        t.score = 95;
                        log(I18n.t("  Album identifié par TOC → %s – %s [%s]", t.artist, t.title, tl.album()));
                        lastFindTagsSource.set(MetadataCache.SOURCE_DISCID);
                        return List.of(t);
                    }
                    // tl résolu mais aucune piste MB ne correspond au numéro de CE fichier (tag
                    // TRACK incohérent avec la structure détectée) → suite normale pour ce fichier
                    // seul, le reste de l'album profite quand même du match.
                }
            }
        }

        // (Ex-étapes 0.61/0.62 SUPPRIMÉES le 2026-09-25, demande utilisateur : « beets n'a rien à
        // écrire avec OpenTagger », MusicBrainz/AcoustID doivent passer en premier.) Elles
        // consultaient les bases locales de beets puis de Headphones par similarité artiste+titre,
        // AVANT tout appel AcoustID/MusicBrainz, et renvoyaient directement leur ligne (score 88-90)
        // — une ligne beets importée « as-is » n'étant qu'une copie des tags du fichier, vu en
        // direct : artiste et titre inversés de « Jean Schultheis – Confidence pour confidence »
        // ré-écrits tels quels. Ne pas réintroduire de base tierce devant AcoustID/MusicBrainz.

        // 0.65. Cohérence de groupe — voir groupPinnedRelease : une AUTRE piste du même groupe (même
        // clé que la vue arborescence — voir AlbumGrouping.key()) a déjà trouvé une release à haute
        // confiance PLUS TÔT dans ce lot. Ne s'applique QUE si le TOC ci-dessus n'a rien donné pour
        // CE fichier (dossier pas assez complet/track manquant/déjà en échec) — le TOC reste toujours
        // prioritaire, checksum sur tout l'album donc plus fiable qu'un simple matching titre/piste
        // contre une release trouvée par une autre piste. Contrairement au TOC, s'applique aussi aux
        // groupes éparpillés sur plusieurs dossiers physiques (regroupement par tag album, pas par
        // dossier).
        if (!forceReidentify && Config.get().discIdMatchingEnabled() && groupKey != null) {
            String pinnedMbid = groupPinnedRelease.get(groupKey);
            if (pinnedMbid != null && !pinnedTracklistFailed.contains(pinnedMbid)) {
                MusicBrainzClient.ReleaseTracklist tl = pinnedTracklistCache.computeIfAbsent(pinnedMbid, mbid -> {
                    try { return mb.lookupRelease(mbid); } catch (Exception e) { return null; }
                });
                if (tl == null) {
                    pinnedTracklistFailed.add(pinnedMbid);
                } else {
                    // findTrackInRelease() (numéro de piste EXACT) ne vérifie jamais le titre —
                    // volontairement, pour rester fiable sur le TOC (étape 0.6, album entier
                    // confirmé par empreinte de durées, voir son propre appel de cette même
                    // méthode). Mais ici, la release vient d'être ÉPINGLÉE par un AUTRE fichier du
                    // groupe (confiance bien moindre qu'un TOC) : un simple partage de NUMÉRO DE
                    // PISTE avec une piste de cette release ne prouve rien de bon sens — repéré en
                    // direct 2026-09-05 sur "02 - Dj Kheops-2-I M Still Feel Like Danc.mp3", épinglé
                    // par numéro de piste "2" sur une release totalement sans rapport (même piste
                    // au titre charabia "_ _ _ _..." déjà vue comme faux positif ailleurs cette
                    // session), auto-enregistrable (score=92 > autocorrector.min_score=90). Même
                    // garde-fou que findTrackInReleaseByTitle() : si LE FICHIER a un titre
                    // exploitable, il doit rester plausible face au titre de la piste trouvée par
                    // numéro — sinon on retombe sur findTrackInReleaseByTitle() (qui, lui, cherche
                    // la VRAIE meilleure correspondance sur toute la tracklist).
                    // discRestrictedOnly=true (2026-09-19, audit compilations/coffrets) : ici la
                    // release n'a été confirmée que par UNE autre piste (contexte le moins fiable,
                    // voir SOURCE_GROUP_PIN) — si un indice de disque existe mais ne correspond à
                    // rien sur cette release, mieux vaut retomber sur findTrackInReleaseByTitle()
                    // (vraie vérification) que sur un simple hasard de numéro de piste tous disques
                    // confondus. Voir la Javadoc de findTrackInRelease(tl, fichier, boolean).
                    MusicBrainzClient.ReleaseTrack myTrack = findTrackByRecordingMbid(tl, fichier);
                    if (myTrack == null) myTrack = findTrackInRelease(tl, fichier, true);
                    if (myTrack != null) {
                        String titleTag = cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
                        if (!titleTag.isBlank() && !isGenericTag(titleTag)
                                && TrackMatcher.titleSimilarity(titleTag.toLowerCase(), myTrack.title().toLowerCase())
                                   < GROUP_PIN_TITLE_MATCH_THRESHOLD) {
                            myTrack = null;
                        }
                    }
                    if (myTrack == null) myTrack = findTrackInReleaseByTitle(tl, fichier);
                    // Garde-fou durée STRICT (voir FileEntry.isStrictDurationMismatch) : ce chemin
                    // n'écoute jamais l'audio — il se fie au numéro de piste et au tag titre DÉJÀ
                    // présent dans le fichier (même après un re-taguage forcé), donc un fichier qui
                    // porte le bon nom mais dont l'audio est un autre morceau passait ici avec
                    // score=92. Si la durée ne colle pas, on laisse la cascade audio (AcoustID,
                    // SongRec...) trancher plutôt que d'épingler la piste sur la foi du seul nom.
                    if (myTrack != null && existingTags != null && myTrack.lengthMs() > 0
                            && FileEntry.isStrictDurationMismatch(existingTags.durationSec, myTrack.lengthMs() / 1000)) {
                        log(I18n.t("  Cohérence de groupe ⚠ durée du fichier (%ds) incompatible avec '%s' (%ds) → identification par l'audio",
                                existingTags.durationSec, myTrack.title(), myTrack.lengthMs() / 1000));
                        myTrack = null;
                    }
                    if (myTrack != null) {
                        TagInfo t = new TagInfo();
                        t.artist           = myTrack.artist();
                        t.title            = myTrack.title();
                        t.album            = tl.album();
                        t.albumArtist      = tl.albumArtist().isBlank() ? myTrack.artist() : tl.albumArtist();
                        t.albumArtistSort  = tl.albumArtistSort();
                        t.year             = tl.year();
                        t.originalYear     = tl.originalYear();
                        t.releaseMbid      = tl.releaseMbid();
                        t.releaseGroupMbid = tl.releaseGroupMbid();
                        t.recordingMbid    = myTrack.recordingMbid();
                        t.releaseTrackMbid = myTrack.trackMbid();
                        t.discSubtitle     = myTrack.discTitle();
                        t.track            = String.valueOf(myTrack.trackNo());
                        t.trackTotal       = String.valueOf(myTrack.trackTotal());
                        if (myTrack.disc() > 0) t.discNo = String.valueOf(myTrack.disc());
                        t.country          = tl.country();
                        t.barcode          = tl.barcode();
                        t.releaseStatus    = tl.releaseStatus();
                        t.label            = tl.label();
                        t.catalogNo        = tl.catalogNo();
                        t.script           = tl.script();
                        // Tout le reste des champs de PARUTION (date complète, type "album;live",
                        // langue, ASIN, URLs, drapeaux live/soundtrack...) — voir
                        // TagInfo.applyReleaseLevelFrom() : ce chemin n'en recopiait qu'une partie.
                        t.applyReleaseLevelFrom(tl.releaseMeta());
                        if (tl.isCompilation()) t.isCompilation = "1";
                        if (myTrack.lengthMs() > 0) t.mbDurationSec = myTrack.lengthMs() / 1000;
                        // Score < TOC (95) : matching titre/numéro contre une release VÉRIFIÉE par une
                        // autre piste, pas un checksum sur ce fichier précis — reste au-dessus du seuil
                        // par défaut (autocorrector.min_score=90) et bénéficie en plus du garde-fou durée
                        // ci-dessus (mbDurationSec renseigné, contrairement au TOC).
                        t.score = 92;
                        log(I18n.t("  Cohérence de groupe → %s – %s [%s] (release déjà fixée par une autre piste du groupe)",
                                t.artist, t.title, tl.album()));
                        lastFindTagsSource.set(MetadataCache.SOURCE_GROUP_PIN);
                        return List.of(t);
                    }
                    // Aucune piste de la release épinglée ne correspond (numéro/titre) → ce fichier
                    // n'appartient probablement pas à CETTE édition précise (bonus track, single...) →
                    // suite normale, identification indépendante pour lui seul.
                }
            }
        }

        // 0.7. Détection DJ mix / long format — DOIT passer avant le "0.75" ci-dessous (recherche
        // MB texte rapide) : bug trouvé en direct (2026-08-16) sur "SE-1105.mp3" (65 min) — placée
        // après à l'origine, la recherche texte rapide trouvait un mauvais résultat par coïncidence
        // ("SE-1 Yukako", 22s) et repartait AVANT que cette détection n'ait la moindre chance
        // d'intervenir ; le garde-fou de durée finissait par rejeter ce mauvais match (donc aucune
        // donnée fausse écrite), mais le fichier finissait "Ignoré" au lieu d'utiliser le repli DJ
        // mix qui l'aurait sauvé. Inutile (et coûteux) de lancer AcoustID/SongRec/recherche MB texte
        // sur un fichier de plusieurs dizaines de minutes à plusieurs heures : ça ne correspond à
        // AUCUN enregistrement MusicBrainz unique (mix continu, pas une piste), le temps passé sur
        // les empreintes/recherches serait perdu, et une éventuelle empreinte capterait un segment
        // aléatoire du mix plutôt que "le morceau". Demande utilisateur (2026-08-16) après avoir
        // constaté des mixs DJ/longs formats YouTube finissant à tort "non identifié" (ou pire,
        // matchés à une seule piste au hasard dedans). Détection par durée uniquement (seuil
        // configurable) — le nom de fichier est trop varié pour un mot-clé fiable. Réutilise
        // SOURCE_UNVERIFIED_TAGS (même repli "tags existants non vérifiés" que plus bas dans cette
        // méthode, mêmes exemptions de seuil de score/durée déjà câblées dans processEntry() — pas
        // de nouvelle logique à dupliquer côté score/durée).
        if (Config.get().djMixDetectionEnabled()
                && existingTags != null && existingTags.durationSec >= Config.get().djMixMinDurationSec()) {
            // URL YouTube dans le commentaire (yt-dlp et consorts la préservent souvent) : essayée
            // en premier, avant les tags bruts — un titre de vidéo YouTube est généralement plus
            // fiable qu'un nom de fichier/tag local pour un mix ripé depuis YouTube (voir
            // YouTubeOEmbedClient pour le pourquoi/comment).
            String ytUrl = YouTubeOEmbedClient.extractUrl(readTag(fichier, FieldKey.COMMENT));
            TagInfo ytInfo = ytUrl != null ? YouTubeOEmbedClient.fetch(ytUrl) : null;
            String mixArtist = forceReidentify ? "" : cleanSearchTerm(readTag(fichier, FieldKey.ARTIST));
            String mixTitle  = forceReidentify ? "" : cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
            if (isGenericTag(mixArtist)) mixArtist = "";
            if (isGenericTag(mixTitle))  mixTitle  = "";
            if (ytInfo != null && !ytInfo.title.isBlank()) {
                log(I18n.t("  URL YouTube trouvée dans le commentaire → %s – %s", ytInfo.artist, ytInfo.title));
                if (!ytInfo.artist.isBlank()) mixArtist = ytInfo.artist;
                mixTitle = ytInfo.title;
            }
            if (mixArtist.isBlank() || mixTitle.isBlank()) {
                String[] fn = parseFilename(fichier);
                if (mixArtist.isBlank() && !isGenericTag(fn[0])) mixArtist = fn[0];
                if (mixTitle.isBlank()  && !isGenericTag(fn[1])) mixTitle  = fn[1];
            }
            if (!mixArtist.isBlank() && !mixTitle.isBlank() && !TagEnrichment.hasNonLatinChars(mixArtist)
                    && !TagEnrichment.hasNonLatinChars(mixTitle)) {
                TagInfo mix = new TagInfo();
                mix.artist      = mixArtist;
                mix.title       = mixTitle;
                mix.albumArtist = mixArtist;
                mix.genre = !existingTags.genre.isBlank() ? existingTags.genre : "DJ Mix";
                if (!existingTags.album.isBlank()) mix.album = existingTags.album;
                if (!existingTags.year.isBlank())  mix.year  = existingTags.year;
                mix.score = 50;
                log(I18n.t("  Format long détecté (%ds ≥ %ds) → repli tags directs : %s – %s",
                        existingTags.durationSec, Config.get().djMixMinDurationSec(), mixArtist, mixTitle));
                lastFindTagsSource.set(MetadataCache.SOURCE_UNVERIFIED_TAGS);
                return List.of(mix);
            }
            log(I18n.t("  Format long détecté (%ds) mais aucun tag/nom de fichier exploitable → suite normale",
                    existingTags.durationSec));
        }

        // 0.75. Compromis vitesse optionnel (skipSongRecOnConfidentMb, off par défaut) : si le
        // fichier a un artiste+titre exploitables dans ses tags, tenter une recherche MB texte
        // rapide AVANT SongRec — pas besoin de payer le fingerprint audio si le texte suffit déjà.
        // Ignoré en reidentification forcée (même logique que tagAlbum/artist/title plus haut : on
        // ne fait pas confiance aux tags existants dans ce mode) et si artiste/titre sont vides,
        // génériques ou non-Latin — trop peu fiables pour sauter la vérification audio, on laisse
        // tomber jusqu'à SongRec comme avant. L'indice de dossier ("Musique", etc.) n'entre PAS en
        // jeu ici (seuls les vrais tags du fichier comptent), justement pour éviter le faux-positif
        // que SongRec-en-premier est censé prévenir.
        if (!forceReidentify && Config.get().skipSongRecOnConfidentMb()) {
            String qaArtist = cleanSearchTerm(readTag(fichier, FieldKey.ARTIST));
            String qaTitle  = cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
            if (!qaArtist.isBlank() && !qaTitle.isBlank()
                    && !isGenericTag(qaArtist) && !isGenericTag(qaTitle)
                    && !TagEnrichment.hasNonLatinChars(qaArtist) && !TagEnrichment.hasNonLatinChars(qaTitle)) {
                try {
                    String qaHash   = MetadataCache.queryHash(qaArtist, qaTitle);
                    String qaCached = cache.getRecordingSearch(qaHash);
                    List<TagInfo> qaResults = qaCached != null
                            ? mb.parseFromCache(qaCached)
                            : mb.searchRecording(qaArtist, qaTitle);
                    if (qaCached == null && !qaResults.isEmpty()) cache.putRecordingSearch(qaHash, mb.lastRawJson());
                    if (!qaResults.isEmpty() && qaResults.get(0).score >= Config.get().skipSongRecMinScore()) {
                        log(I18n.t("  MB texte rapide (confiant, score=%s) → SongRec sauté : %s – %s",
                                qaResults.get(0).score, qaResults.get(0).artist, qaResults.get(0).title));
                        return qaResults;
                    }
                } catch (Exception e) {
                    log(I18n.t("  MB texte rapide WARN: %s", e.getMessage()));
                }
            }
        }

        // 1/2. AcoustID vs SongRec — ordre CONDITIONNEL depuis ce correctif (2026-09-02, retour
        // utilisateur : "forcer retaguage c'était songrec en priorité"). Taguage normal (mise à
        // jour massive) : AcoustID d'abord — rapide et parallélisable (aucune limite de débit
        // globale, contrairement à SongRec/SHAZAM_GATE qui sérialise TOUT appel à un seul à la fois
        // dans toute l'appli, pour éviter le 429 Shazam du 2026-08-09) — réorganisé ainsi le
        // 2026-08-15 pour ne pas payer ce goulot sur CHAQUE fichier, même ceux qu'AcoustID aurait
        // suffi à identifier seul. Re-taguage FORCÉ (action volontaire, lot généralement plus
        // restreint, priorité donnée à la fiabilité plutôt qu'au débit) : SongRec d'abord — décision
        // d'origine du projet, jamais implémentée dans le code jusqu'ici (l'ordre était resté
        // identique pour les deux cas depuis le 08-15). Voir tryAcoustId()/trySongRec() pour le
        // détail de chaque étape, inchangé — seul l'ORDRE d'appel change ici.
        if (forceReidentify) {
            List<TagInfo> sr = trySongRec(fichier, existingTags, mb, lastFm, cache);
            if (sr != null) return sr;
        } else {
            List<TagInfo> ai = tryAcoustId(fichier, existingTags, acoustId, forceReidentify);
            if (ai != null) return ai;
        }

        // 1bis. MB Recording ID déjà présent → lookup direct (rapide + précis)
        String existingMbid = readTag(fichier, FieldKey.MUSICBRAINZ_TRACK_ID);
        if (!existingMbid.isBlank()) {
            TagInfo t = null;
            String cached = cache.getLookup(existingMbid);
            if (cached != null) t = mb.parseFromCacheLookup(cached);
            if (t == null) {
                t = mb.lookupRecording(existingMbid);
                if (t != null) cache.putLookup(existingMbid, mb.lastRawJson());
            }
            // Valider : ignorer si artiste ET titre vides (lookup MB incomplet)
            if (t != null && (!t.artist.isBlank() || !t.title.isBlank())) {
                log(I18n.t("  MBID lookup: %s – %s", t.artist, t.title));
                t.score = 100;
                lastFindTagsSource.set(MetadataCache.SOURCE_MBID);
                return List.of(t);
            } else if (t != null) {
                log(I18n.t("  MBID lookup IGNORÉ (artiste+titre vides) mbid=%s", existingMbid));
            }
        }

        // 1/2 (suite) — second essai, dans l'ordre complémentaire au premier ci-dessus (voir son
        // commentaire pour le pourquoi de cet ordre conditionnel).
        if (forceReidentify) {
            List<TagInfo> ai = tryAcoustId(fichier, existingTags, acoustId, forceReidentify);
            if (ai != null) return ai;
        } else {
            List<TagInfo> sr = trySongRec(fichier, existingTags, mb, lastFm, cache);
            if (sr != null) return sr;
        }

        // 3. Tags texte existants, avec fallback sur le nom de fichier
        // En reidentification forcée, on ne fait PAS confiance à l'artiste/titre déjà écrits sur
        // le fichier : ce sont précisément les données que l'utilisateur demande de revérifier
        // (typiquement après le bug de mass-mistagging album-first). Sans ce garde-fou, une
        // recherche texte MusicBrainz basée sur le mauvais artiste/titre pouvait "réussir" avec
        // un résultat tout aussi faux, retourné avant même d'atteindre le second essai SongRec —
        // "Forcer le re-taguage" ne repartait alors jamais vraiment de zéro.
        String artist = forceReidentify ? "" : readTag(fichier, FieldKey.ARTIST);
        String title  = forceReidentify ? "" : readTag(fichier, FieldKey.TITLE);
        // Même correctif que parseFilename() (voir collapseSelfConcatenatedTitle()) mais appliqué
        // ici aux tags DÉJÀ PRÉSENTS dans le fichier — un artiste/titre collé deux fois sans
        // séparateur peut aussi bien venir des tags existants (écrits par un outil tiers en amont)
        // que du nom de fichier ; sans ce même correctif ici, un fichier avec des tags ainsi
        // corrompus ne passait jamais par parseFilename() (readTag() renvoie déjà quelque chose de
        // non-blanc) et restait "Non identifié" malgré le correctif déjà en place côté nom de
        // fichier — repéré en direct (2026-08-13) sur "Onur Enfal[onur Enfal] - Feel It (Radio
        // Edit)[Feel It (Radio Edit)]".
        artist = collapseSelfConcatenatedTitle(artist);
        title  = collapseSelfConcatenatedTitle(title);
        // Lire l'album maintenant (utilisé en fallback plus bas quand artiste manque). Ignoré en
        // reidentification forcée — même raison que tagAlbum/albumHint plus haut : un tag album déjà
        // faux (ex. contamination historique par un nom de dossier de repli, voir "Sans
        // Correspondence" trouvé en direct 2026-08-27) biaiserait la recherche MB ET reviendrait
        // systématiquement via le repli "tags existants" tout en bas de cette méthode — cassant même
        // "Forcer le re-taguage", censé repartir de zéro. Sans ce garde-fou, un album corrompu ne
        // pouvait JAMAIS être corrigé par re-identification forcée, seulement en éditant le tag à la
        // main.
        String existingAlbum = forceReidentify ? "" : cleanSearchTerm(readTag(fichier, FieldKey.ALBUM));

        // Détection hors FR/EN : si les tags contiennent du japonais, coréen, arabe,
        // cyrillique, etc. → inutile de chercher dans MB avec ces termes, SongRec en priorité
        boolean nonLatinInput = TagEnrichment.hasNonLatinChars(artist) || TagEnrichment.hasNonLatinChars(title);
        // Sans SongRec (jamais disponible sous Windows), « SongRec en priorité » ne menait nulle part : le fichier restait « Non identifié »
        // SANS qu'aucune recherche MusicBrainz n'ait été tentée (constaté sur des fichiers aux tags japonais, ex. 西野カナ / This Is How We Do It).
        // MusicBrainz indexe les noms non latins : on lance donc la recherche texte normale avec ces tags.
        final boolean songRecUsable = songRecUsable();
        if (nonLatinInput && !songRecUsable) {
            log(I18n.t("  tags non-Latin, SongRec indisponible → recherche MusicBrainz directe"));
            nonLatinInput = false;
        }
        // true si artist/title viennent d'un split brut du nom de fichier sur " - " (voir plus bas)
        // — parseFilename() suppose l'ordre "Artiste - Titre", mais certains rips (constaté en
        // direct, ex. "J en ai mis du temps - Patrick Fiori.mp3") utilisent l'ordre inverse
        // "Titre - Artiste". Contrairement aux vrais tags ID3 (dont on connaît le sens de chaque
        // champ), un nom de fichier découpé sur "-" est fondamentalement ambigu — seul un second
        // essai avec les deux termes échangés permet de lever le doute si le premier échoue.
        boolean filenameSplitAmbiguous = false;
        if (nonLatinInput) {
            log(I18n.t("  tags non-Latin → SongRec en priorité"));
            artist = ""; title = "";
        } else {
            artist = cleanSearchTerm(artist);
            title  = cleanSearchTerm(title);
            if (isGenericTag(artist)) artist = "";
            if (isGenericTag(title))  title  = "";
            log(I18n.t("  tags lus: artiste='%s' titre='%s'", artist, title));
            if (artist.isBlank() && title.isBlank()) {
                String[] fn = parseFilename(fichier);
                if (songRecUsable && (TagEnrichment.hasNonLatinChars(fn[0]) || TagEnrichment.hasNonLatinChars(fn[1]))) {
                    nonLatinInput = true;
                    log(I18n.t("  nom de fichier non-Latin → SongRec en priorité"));
                } else {
                    artist = fn[0]; title = fn[1];
                    // Appliquer isGenericTag sur l'artiste du nom de fichier aussi ("0", "01", etc.)
                    if (isGenericTag(artist)) artist = "";
                    // Le titre issu du nom de fichier doit lui aussi pouvoir être générique
                    // ("download", "Track 01"…) — sinon il part en recherche texte seule.
                    if (isGenericTag(title)) title = "";
                    filenameSplitAmbiguous = !artist.isBlank() && !title.isBlank();
                    log(I18n.t("  → infos du nom de fichier: artiste='%s' titre='%s'", artist, title));
                }
            } else if (artist.isBlank() && !title.isBlank()) {
                // Artiste vide mais titre connu : essayer de récupérer l'artiste depuis le nom de fichier
                String[] fn = parseFilename(fichier);
                if (!fn[0].isBlank() && !isGenericTag(fn[0]) && !TagEnrichment.hasNonLatinChars(fn[0])) {
                    artist = fn[0];
                    log(I18n.t("  artiste←nom de fichier: '%s'", artist));
                }
                // Dernier recours : titre contient "Artiste-Titre" ou "Artiste - Titre" → splitter
                // Ex: titre="Bob Sinclar-Give A Lil Love" → artiste="Bob Sinclar", titre="Give A Lil Love"
                if (artist.isBlank() && title.contains("-")) {
                    int idx = title.indexOf(" - ");
                    String potArtist, potTitle;
                    if (idx > 0) {
                        potArtist = title.substring(0, idx).trim();
                        potTitle  = title.substring(idx + 3).trim();
                    } else {
                        idx = title.indexOf('-');
                        potArtist = title.substring(0, idx).trim();
                        potTitle  = title.substring(idx + 1).trim();
                    }
                    if (!potArtist.isBlank() && !potTitle.isBlank()
                            && !isGenericTag(potArtist) && !TagEnrichment.hasNonLatinChars(potArtist)
                            && (potArtist.contains(" ") || potArtist.length() >= 5)) {
                        artist = potArtist;
                        title  = potTitle;
                        log(I18n.t("  artiste+titre←split titre: '%s' / '%s'", artist, title));
                    }
                }
                // Titres trop génériques sans artiste → MB donnera trop de faux positifs → SongRec
                if (artist.isBlank() && GENERIC_TITLES_WITHOUT_ARTIST.contains(title.toLowerCase())) {
                    log(I18n.t("  titre générique sans artiste ('%s') → SongRec", title));
                    title = "";
                }
            }
        }

        if (artist.isBlank() && title.isBlank() && !nonLatinInput) {
            log(I18n.t("  → rien à chercher"));
            return List.of();
        }

        List<TagInfo> results = List.of();

        if (!nonLatinInput) {
            // 4. Cache SQLite
            String hash   = MetadataCache.queryHash(artist, title);
            String cached = cache.getRecordingSearch(hash);
            if (cached != null) {
                List<TagInfo> r = mb.parseFromCache(cached);
                log(I18n.t("  MB cache: %s résultat(s)", r.size()));
                if (!r.isEmpty()) return r;
            }

            // 5. MusicBrainz — réseau (avec fallbacks progressifs)
            log(I18n.t("  MB search: '%s' / '%s'", artist, title));
            results = mb.searchRecording(artist, title);
            log(I18n.t("  MB search → %s résultat(s)%s", results.size(),
                results.isEmpty() ? "" : I18n.t(" meilleur score=%s", results.get(0).score)));
            if (!results.isEmpty()) {
                cache.putRecordingSearch(hash, mb.lastRawJson());
                return results;
            }

            // 5b. Fallback: artiste simplifié
            String artistSimple = simplifyArtist(artist);
            if (!artistSimple.equals(artist) && !artistSimple.isBlank() && !isGenericTag(artistSimple)) {
                log(I18n.t("  MB fallback artiste simplifié: '%s'", artistSimple));
                results = mb.searchRecording(artistSimple, title);
                log(I18n.t("  MB fallback → %s résultat(s)", results.size()));
                if (!results.isEmpty()) {
                    cache.putRecordingSearch(hash, mb.lastRawJson());
                    return results;
                }
            }

            // 5b-bis-2. Fallback: ordre inversé "Titre - Artiste" — uniquement si artist/title
            // viennent d'un split de nom de fichier (voir filenameSplitAmbiguous plus haut), jamais
            // pour de vrais tags ID3 (dont on connaît déjà le sens de chaque champ, l'inverser n'
            // aurait aucun sens). Vérifié en direct (2026-08-09) : "03 J en ai mis du temps -
            // Patrick Fiori.mp3" cherchait artiste="J en ai mis du temps"/titre="Patrick Fiori" (0
            // résultat), alors que l'inverse trouve Patrick Fiori à 100% sur MusicBrainz.
            if (filenameSplitAmbiguous) {
                log(I18n.t("  MB fallback ordre inversé: '%s' / '%s'", title, artist));
                String hashSwap = MetadataCache.queryHash(title, artist);
                results = mb.searchRecording(title, artist);
                log(I18n.t("  MB ordre inversé → %s résultat(s)", results.size()));
                if (!results.isEmpty()) {
                    cache.putRecordingSearch(hashSwap, mb.lastRawJson());
                    return results;
                }
            }
        }

        // 5b-ter. Fallback : titre SIMPLIFIÉ (sans « (feat. …) » ni marqueur de copie « (1) »), seulement quand l'ARTISTE est connu — MusicBrainz
        // stocke « Havana » et non « Havana (Feat. Young Thug) » (l'invité est dans le crédit), et la recherche par expression exacte du titre
        // complet ne trouvait rien. Jamais sans artiste : une recherche par titre seul ferait trop de faux positifs.
        if (!nonLatinInput && results.isEmpty() && !artist.isBlank() && !title.isBlank()) {
            String titleSimple = simplifyTitle(title);
            if (!titleSimple.equals(title) && !titleSimple.isBlank() && !isGenericTag(titleSimple)) {
                log(I18n.t("  MB fallback titre simplifié: '%s' / '%s'", artist, titleSimple));
                results = mb.searchRecording(artist, titleSimple);
                log(I18n.t("  MB titre simplifié → %s résultat(s)", results.size()));
                if (!results.isEmpty()) {
                    cache.putRecordingSearch(MetadataCache.queryHash(artist, title), mb.lastRawJson());
                    return results;
                }
            }
        }

        // NOTE: Le fallback "titre seul" est désactivé — trop de faux positifs.

        // 5b-bis-3. Fallback "Titre by Compositeur" — convention fréquente sur du contenu classique/
        // domaine public (ex. "Granada by Isaac Albeniz.mp3", repéré en direct 2026-09-13 parmi les
        // "Non identifié" d'une session réelle). parseFilename() ne connaît que " - " comme
        // séparateur ; ce nom entier partait tel quel comme titre unique, introuvable tel quel.
        // Volontairement en DERNIER recours (jamais en remplacement de la recherche du nom complet
        // ci-dessus) : " by " apparaît aussi dans de vrais titres légitimes ("Stand By Me", "Walk On
        // By") — un split systématique aurait empêché ces titres d'être cherchés tels quels et
        // risqué une identification erronée. Ici, la recherche du texte complet a déjà échoué, donc
        // rien à perdre à tenter la variante scindée.
        if (!nonLatinInput && results.isEmpty() && artist.isBlank() && !title.isBlank()) {
            java.util.regex.Matcher byM = java.util.regex.Pattern
                    .compile("(?i)^(.+?)\\s+by\\s+(.+)$").matcher(title);
            if (byM.matches()) {
                String byTitle    = byM.group(1).trim();
                String byComposer = byM.group(2).trim();
                if (!byTitle.isBlank() && !byComposer.isBlank()
                        && !isGenericTag(byTitle) && !isGenericTag(byComposer)
                        && !TagEnrichment.hasNonLatinChars(byComposer)) {
                    log(I18n.t("  MB fallback 'titre by compositeur': '%s' / '%s'", byTitle, byComposer));
                    results = mb.searchRecording(byComposer, byTitle);
                    log(I18n.t("  MB 'by' → %s résultat(s)", results.size()));
                    if (!results.isEmpty()) {
                        // Score plafonné à 50 (comme "unverified_tags" ailleurs dans ce fichier) : un
                        // découpage devinable depuis un nom de fichier n'a pas la fiabilité d'une
                        // recherche sur des tags déjà propres — searchRecording() renvoie pourtant
                        // toujours 100 par construction. Sans ce plafond, un résultat par coïncidence
                        // faux mais plausible franchirait autocorrector.min_score (90) et s'enregistrerait
                        // sans aucune revue humaine — repéré en vérifiant ce correctif (2026-09-13),
                        // jamais vu en conditions réelles mais même risque que le bug TOC du jour même.
                        for (TagInfo r : results) r.score = 50;
                        cache.putRecordingSearch(MetadataCache.queryHash(byComposer, byTitle), mb.lastRawJson());
                        return results;
                    }
                }
            }
        }

        // 5b-bis-4. Fallback segments séparés par un tiret SANS espaces autour — ex. "Unknown_
        // Artist-Hans_Zimmer-Swords_Crossed.mp3" (repéré en direct 2026-09-13, même lot que le
        // fallback "by" ci-dessus) : parseFilename() ne coupe que sur " - " (avec espaces), donc
        // "Hans Zimmer"/"Swords Crossed" restaient noyés dans un seul bloc non identifiable. Exige
        // AU MOINS 3 segments (pas juste 2) : un simple mot composé à tiret ("Spider-Man", "T-Rex")
        // ne doit jamais être scindé — seul un enchaînement de plusieurs segments signale une vraie
        // convention "PréfixeIgnoré-Artiste-Titre". Chaque segment retenu doit en plus contenir un
        // espace (donc être lui-même multi-mots) : "Hans Zimmer"/"Swords Crossed" passent, un
        // hypothétique "A-B-C" à segments mono-mots resterait rejeté, même heuristique que le
        // fallback bare-hyphen déjà existant plus haut (potArtist.contains(" ")).
        if (!nonLatinInput && results.isEmpty() && artist.isBlank() && !title.isBlank() && title.contains("-")) {
            String[] segs = title.split("-");
            if (segs.length >= 3) {
                String hyTitle  = segs[segs.length - 1].trim();
                String hyArtist = segs[segs.length - 2].trim();
                if (hyTitle.contains(" ") && hyArtist.contains(" ")
                        && !isGenericTag(hyTitle) && !isGenericTag(hyArtist)
                        && !TagEnrichment.hasNonLatinChars(hyArtist)) {
                    log(I18n.t("  MB fallback segments tiret sans espaces: '%s' / '%s'", hyArtist, hyTitle));
                    results = mb.searchRecording(hyArtist, hyTitle);
                    log(I18n.t("  MB segments tiret → %s résultat(s)", results.size()));
                    if (!results.isEmpty()) {
                        // Score plafonné — voir le commentaire identique sur le fallback "by" ci-dessus.
                        // Risque encore un peu plus réel ici : un nom propre à tiret ("Anna-Maria
                        // Jopek") pourrait être coupé au mauvais endroit et tronquer un vrai artiste.
                        for (TagInfo r : results) r.score = 50;
                        cache.putRecordingSearch(MetadataCache.queryHash(hyArtist, hyTitle), mb.lastRawJson());
                        return results;
                    }
                }
            }
        }

        // 5b-bis-5. Fallback tiret asymétrique (espace d'un seul côté) — ex. "24. Savage- Don't Cry
        // Tonight [Saint Paul DJ Remix].mp3" (repéré en direct 2026-09-13, même lot que les deux
        // fallbacks ci-dessus). On arrive ici seulement si parseFilename() n'a trouvé aucun " - "
        // (espaces des deux côtés) — donc si un tiret a un espace d'UN SEUL côté ("X- Y" ou "X -Y"),
        // ce n'est ni le cas symétrique déjà géré ailleurs, ni un mot composé ambigu du type
        // "Spider-Man" (aucun espace des deux côtés, jamais touché ici). Un espacement asymétrique
        // est un signal bien plus fort qu'un simple tiret nu : contrairement au fallback 3-segments
        // ci-dessus, celui-ci accepte un split à 2 segments SEULEMENT parce que ce signal est déjà
        // suffisamment distinctif pour ne pas exiger un 3ᵉ segment de confirmation.
        if (!nonLatinInput && results.isEmpty() && artist.isBlank() && !title.isBlank()) {
            java.util.regex.Matcher asymM = java.util.regex.Pattern
                    .compile("^(.+?)\\s+-(\\S.*)$|^(.+\\S)-\\s+(.+)$").matcher(title);
            if (asymM.matches()) {
                String asymArtist = (asymM.group(1) != null ? asymM.group(1) : asymM.group(3)).trim();
                String asymTitle  = (asymM.group(2) != null ? asymM.group(2) : asymM.group(4)).trim();
                if (!asymArtist.isBlank() && !asymTitle.isBlank()
                        && !isGenericTag(asymArtist) && !isGenericTag(asymTitle)
                        && !TagEnrichment.hasNonLatinChars(asymArtist)
                        && (asymArtist.contains(" ") || asymArtist.length() >= 5)) {
                    log(I18n.t("  MB fallback tiret asymétrique: '%s' / '%s'", asymArtist, asymTitle));
                    results = mb.searchRecording(asymArtist, asymTitle);
                    log(I18n.t("  MB tiret asymétrique → %s résultat(s)", results.size()));
                    if (!results.isEmpty()) {
                        // Score plafonné — voir les deux commentaires identiques sur les fallbacks
                        // "by" / segments tiret ci-dessus.
                        for (TagInfo r : results) r.score = 50;
                        cache.putRecordingSearch(MetadataCache.queryHash(asymArtist, asymTitle), mb.lastRawJson());
                        return results;
                    }
                }
            }
        }

        // 5b-bis. Fallback titre + album : artiste vide mais album connu dans les tags existants
        // Typique : fichier avec artist="0"/vide mais title+album corrects (ex: M4A mal encodé)
        if (!nonLatinInput && results.isEmpty() && artist.isBlank()
                && !title.isBlank() && !existingAlbum.isBlank()) {
            log(I18n.t("  MB fallback titre+album: '%s' / '%s'", title, existingAlbum));
            results = mb.searchRecording("", title, existingAlbum);
            log(I18n.t("  MB titre+album → %s résultat(s)", results.size()));
            if (!results.isEmpty()) {
                // Score plafonné à 50 (même raison que les 3 repêchages "5b-bis-3/4/5" plus haut,
                // trouvé manquant ici par audit dédié 2026-09-13) : une recherche SANS ARTISTE, sur
                // un titre+album pouvant être génériques ("Greatest Hits"...), renvoie plusieurs
                // candidats sans rapport à score=100 — vérifié en direct sur l'API MB réelle :
                // recording:"Yesterday" AND release:"Greatest Hits" renvoie Gheorghe Zamfir, The
                // Golden Strings et Marianne Faithfull à score=100, LE VRAI Beatles ne scorant que
                // 95 (donc classé derrière). Sans ce plafond, un résultat coïncidemment faux
                // franchissait autocorrector.min_score (90) et s'enregistrait sans revue humaine.
                for (TagInfo r : results) r.score = 50;
                cache.putRecordingSearch(MetadataCache.queryHash(title, existingAlbum), mb.lastRawJson());
                return results;
            }
        }

        // 5c. (SongRec) supprimé — était un second appel à songRec.recognize(fichier), IDENTIQUE à
        // celui de l'étape 1 en tout début de méthode (même fichier, même appel, aucun paramètre
        // différent). L'étape 1 retourne TOUJOURS dès qu'elle obtient un résultat exploitable
        // (srOk) ; ce point du code n'est donc atteint que si l'étape 1 a déjà échoué — et Shazam
        // étant un service déterministe, un second appel ne pouvait que reproduire le même échec.
        // Un aller-retour réseau (jusqu'à 30s × plusieurs offsets) gaspillé sur CHAQUE fichier non
        // trouvé par SongRec, en plus d'aggraver inutilement le débit de requêtes vers Shazam —
        // trouvé en creusant le blocage "429 Too Many Requests" du 2026-08-09.

        // 5d. AcoustID en dernier recours (lent mais très précis par empreinte audio)
        if (!useAcoustId && !Config.get().acoustidKey().isBlank()) {
            log(I18n.t("  AcoustID fallback..."));
            List<TagInfo> r = acoustId.identify(fichier);
            log(I18n.t("  AcoustID fallback → %s résultat(s)", r.size()));
            if (!r.isEmpty()) return r;
        }

        // 6a. Dernier recours VÉRIFIÉ EXTERNE : deviner une page piste Bandcamp depuis
        // artiste+titre (voir BandcampClient.guessTrackUrl/fetchTrack, testés en direct le
        // 2026-08-16 sur de vraies pages — structure JSON-LD confirmée, format de durée non
        // standard corrigé). Demande utilisateur explicite (2026-08-16, "construit ça de façon
        // auto") — contrairement au repli "tags existants" juste en dessous (6b, confiance
        // aveugle), celui-ci est confirmé par le contenu réel renvoyé par Bandcamp
        // (TrackMatcher.titleSimilarity sur artiste ET titre) avant d'être appliqué : un essai qui
        // ne correspond pas (mauvaise devinette d'URL, 404, artiste différent) échoue
        // silencieusement, jamais de fausse donnée écrite. Volontairement en tout dernier recours
        // de la cascade (après épuisement de TOUTES les autres méthodes) : limite mécaniquement le
        // volume de requêtes vers un site tiers scrapé sans API officielle à la seule fraction de
        // bibliothèque réellement bloquée ailleurs, pas toute la bibliothèque.
        if (results.isEmpty() && !nonLatinInput && Config.get().bandcampGuessEnabled()) {
            TagInfo bc = tryBandcampGuess(fichier, artist, title);
            if (bc != null) return List.of(bc);
        }

        // 6b. Dernier recours : rien n'a été confirmé, mais les tags déjà présents sur le fichier
        // ont l'air valides (non vides, non génériques, voir isGenericTag() déjà appliqué ci-dessus
        // à artist/title) — les utiliser directement plutôt que déclarer non identifié. Demande
        // utilisateur (2026-08-16) après avoir constaté qu'un outil tiers plus permissif (aucune
        // vérification MB) retrouvait ces mêmes fichiers en faisant confiance aux tags existants —
        // repéré en direct sur "(2003) Alejandro Sanz - No es lo mismo (Paraíso en vivo).mp3" :
        // tags lus correctement, MB/SongRec/AudD tous épuisés sans résultat, fichier pourtant
        // parfaitement identifiable via ses propres tags. Contrairement au bug de confiance aveugle
        // déjà corrigé cette session (SongRec trust bug, qui acceptait des tags AVANT toute
        // vérification), celui-ci n'intervient qu'ICI, tout en bas, après épuisement de TOUTES les
        // autres méthodes. Source dédiée (SOURCE_UNVERIFIED_TAGS, pas SOURCE_TEXT) pour que
        // processEntry() puisse l'exempter du seuil de score habituel (voir plus bas) sans
        // affaiblir ce seuil pour les vraies recherches texte MB.
        if (results.isEmpty() && !nonLatinInput && Config.get().trustReadableTagsFallback()) {
            // URL YouTube dans le commentaire, même repli que pour les mixs DJ (voir étape 0.8) —
            // ici aussi essayée en premier, elle peut rescaper un fichier même si artist/title
            // locaux sont vides/génériques (le titre de la vidéo suffit alors à lui seul).
            String ytUrl = YouTubeOEmbedClient.extractUrl(readTag(fichier, FieldKey.COMMENT));
            TagInfo ytInfo = ytUrl != null ? YouTubeOEmbedClient.fetch(ytUrl) : null;
            String fbArtist = artist, fbTitle = title;
            if (ytInfo != null && !ytInfo.title.isBlank()) {
                log(I18n.t("  URL YouTube trouvée dans le commentaire → %s – %s", ytInfo.artist, ytInfo.title));
                if (!ytInfo.artist.isBlank()) fbArtist = ytInfo.artist;
                fbTitle = ytInfo.title;
            }
            // isContinuousMixTitle() : sans ce garde-fou (2026-09-03, repéré en direct sur "00-02
            // Disco - Classic 80's Megamix - (Rick Astley, Pet Shop Boys...).mp3"), ce repli
            // acceptait "Disco" comme artiste — un résidu du découpage naïf "Genre - Titre" du nom
            // de fichier sur le premier " - ", parseFilename() n'ayant aucun moyen de distinguer un
            // vrai artiste d'un mot de genre ici. Déjà utilisé ailleurs dans la cascade (ligne
            // ~461) pour ne jamais épingler un mix DJ continu à UNE piste précise ; manquait
            // seulement à ce dernier recours. Un mix continu reste "Non identifié" plutôt que
            // recevoir un artiste inventé à score=50.
            // Tags « lisibles » mais en fait recopiés du nom de fichier (« 1-04_Justin_Timberlake_-_What_Goes_Around »), avec le numéro de piste
            // et l'artiste collés dans le titre : on les nettoie AVANT de les écrire, sinon le fichier garde « 1-04 Justin Timberlake » en
            // artiste et en titre.
            String[] cleanedFb = cleanFallbackIdentity(fbArtist, fbTitle);
            fbArtist = cleanedFb[0];
            fbTitle  = cleanedFb[1];
            if (!fbArtist.isBlank() && !fbTitle.isBlank() && !isContinuousMixTitle(fbTitle)) {
                TagInfo fallback = new TagInfo();
                fallback.artist      = fbArtist;
                fallback.title       = fbTitle;
                fallback.albumArtist = fbArtist;
                if (!existingAlbum.isBlank()) fallback.album = existingAlbum;
                if (existingTags != null) {
                    if (!existingTags.year.isBlank())  fallback.year  = existingTags.year;
                    if (!existingTags.genre.isBlank()) fallback.genre = existingTags.genre;
                    // Le numéro de piste (et de disque) déjà présents ne doivent pas être EFFACÉS par ce repli : jusqu'ici « piste : 4 → [effacé] ».
                    if (!existingTags.track.isBlank())      fallback.track      = existingTags.track;
                    if (!existingTags.trackTotal.isBlank()) fallback.trackTotal = existingTags.trackTotal;
                    if (!existingTags.discNo.isBlank())     fallback.discNo     = existingTags.discNo;
                    if (!existingTags.discTotal.isBlank())  fallback.discTotal  = existingTags.discTotal;
                }
                fallback.score = 50;
                log(I18n.t("  Repli tags existants (aucune méthode confirmée) : %s – %s", fbArtist, fbTitle));
                lastFindTagsSource.set(MetadataCache.SOURCE_UNVERIFIED_TAGS);
                return List.of(fallback);
            }
        }
        return results;
    }

    /**
     * Nettoie artiste et titre issus de tags recopiés d'un nom de fichier : « _ » → espace, numéro de piste/disque en tête retiré
     * (« 1-04 », « 04 - »), et « Artiste - » retiré du début du titre quand c'est l'artiste lui-même (ou, si l'artiste est vide, pris
     * dans le titre). Un titre normal reste inchangé.
     */
    private static final java.util.regex.Pattern TRACK_PREFIX = java.util.regex.Pattern.compile(
            "^(?:\\d{1,2}-\\d{1,2}[\\s._-]+|\\d{1,3}\\s*[-._]\\s*(?!\\d))");

    static String stripTrackPrefix(String s) {
        String out = s.trim();
        for (int i = 0; i < 2; i++) {                       // « 1-04 04 - » : jusqu'à deux préfixes enchaînés
            java.util.regex.Matcher m = TRACK_PREFIX.matcher(out);
            if (!m.find() || m.end() >= out.length()) break;
            out = out.substring(m.end()).trim();
        }
        return out;
    }

    private static String tidyFilenameLike(String s) { return s.replace('_', ' ').replaceAll("\\s{2,}", " ").trim(); }

    static String[] cleanFallbackIdentity(String artist, String title) {
        // Le préfixe est retiré AVANT de remplacer les « _ » (un « 04_ » est alors un séparateur explicite), et seulement s'il a la forme d'un
        // numéro de piste : « 1-04 », « 04 - », « 04. », « 04_ ». Jamais « 50 Cent », « 21 Guns » ou « 1-800-273-8255 » (chiffres suivis d'un simple espace).
        String a = tidyFilenameLike(stripTrackPrefix(artist == null ? "" : artist));
        String t = tidyFilenameLike(stripTrackPrefix(title == null ? "" : title));
        int sep = t.indexOf(" - ");
        if (sep > 0) {
            String head = t.substring(0, sep).trim(), rest = t.substring(sep + 3).trim();
            boolean sameAsArtist = !a.isBlank() && head.equalsIgnoreCase(a);
            if (!rest.isEmpty() && (sameAsArtist || a.isBlank())) {
                if (a.isBlank()) a = head;
                t = rest;
            }
        }
        return new String[]{a, t};
    }

    /** Devine puis vérifie une page piste Bandcamp depuis artiste+titre (voir BandcampClient.
     *  guessTrackUrl/fetchTrack) — extrait de l'étape 6a de findTags() (2026-09-19) pour être
     *  réutilisable tel quel par le mode {@code bandcampOnly} (déclenchement manuel, menu
     *  Retraitement), sans dupliquer la logique de vérification (TrackMatcher.titleSimilarity sur
     *  artiste ET titre — un essai qui ne correspond pas échoue silencieusement, jamais de fausse
     *  donnée écrite). Retourne {@code null} si rien de vérifié n'a été trouvé. */
    private TagInfo tryBandcampGuess(File fichier, String artist, String title) {
        String ytUrl = YouTubeOEmbedClient.extractUrl(readTag(fichier, FieldKey.COMMENT));
        TagInfo ytInfo = ytUrl != null ? YouTubeOEmbedClient.fetch(ytUrl) : null;
        String bcArtist = artist, bcTitle = title;
        if (ytInfo != null && !ytInfo.title.isBlank()) {
            if (!ytInfo.artist.isBlank()) bcArtist = ytInfo.artist;
            bcTitle = ytInfo.title;
        }
        String guessUrl = BandcampClient.guessTrackUrl(bcArtist, bcTitle);
        if (guessUrl == null) return null;
        try {
            var bc = BandcampClient.fetchTrack(guessUrl);
            if (bc != null && !bc.title().isBlank() && !bc.artist().isBlank()
                    && TrackMatcher.titleSimilarity(bc.title().toLowerCase(), bcTitle.toLowerCase())
                            >= Config.get().trackMatchingThreshold()
                    && TrackMatcher.titleSimilarity(bc.artist().toLowerCase(), bcArtist.toLowerCase())
                            >= Config.get().trackMatchingThreshold()) {
                TagInfo bcInfo = new TagInfo();
                bcInfo.artist      = bc.artist();
                bcInfo.title       = bc.title();
                bcInfo.albumArtist = bc.artist();
                if (!bc.album().isBlank()) bcInfo.album = bc.album();
                java.util.regex.Matcher ym = java.util.regex.Pattern.compile("\\b(\\d{4})\\b")
                        .matcher(bc.releaseDate());
                if (ym.find()) bcInfo.year = ym.group(1);
                // Score légèrement > le repli "tags existants" (50, voir 6b) : celui-ci est
                // confirmé par une source externe, pas une simple confiance dans le fichier.
                bcInfo.score = 55;
                // Tout ce que la page fournit en plus (2026-09-20) : URL, label, tags, copyright, licence, ISRC.
                bcInfo.bandcampUrl = guessUrl;
                if (bc.extra() != null) {
                    bcInfo.label     = bc.extra().label();
                    bcInfo.tags      = bc.extra().keywords();
                    bcInfo.copyright = bc.extra().copyright();
                    bcInfo.license   = bc.extra().license();
                    bcInfo.isrc      = bc.extra().isrc();
                }
                log(I18n.t("  Bandcamp (URL devinée, vérifiée) → %s – %s [%s]",
                        bc.artist(), bc.title(), guessUrl));
                lastFindTagsSource.set(MetadataCache.SOURCE_BANDCAMP);
                return bcInfo;
            }
        } catch (Exception e) {
            log(I18n.t("  Bandcamp devinette échouée/non trouvée : %s", e.getMessage()));
        }
        return null;
    }

    // clusterAlbums()/findBestTrack()/titleSimilarity() : extraits vers AlbumClusterWorker
    // (2026-07-07, voir sa Javadoc pour le pourquoi).

    private String readTag(File f, FieldKey key) {
        try {
            var af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            String v = tag != null ? tag.getFirst(key) : "";
            return v != null ? v.trim() : "";
        } catch (Exception e) { return ""; }
    }

    /** Étape "AcoustID" de la cascade findTags() — extraite (2026-09-02) pour pouvoir être appelée
     *  soit en premier (taguage normal), soit en second (re-taguage forcé), voir findTags(). Corps
     *  inchangé par rapport à avant cette extraction, seul l'appelant/l'ordre a changé.
     *  @return le résultat si résolu, {@code null} sinon (poursuivre la cascade). */
    private List<TagInfo> tryAcoustId(File fichier, TagInfo existingTags, AcoustIdClient acoustId,
                                       boolean forceReidentify) throws Exception {
        if (!useAcoustId) return null;
        // ignore_existing : si un AcoustID est déjà dans les tags et qu'on ne force pas, on skip
        String storedAcoustId = readTag(fichier, FieldKey.ACOUSTID_ID);
        boolean hasExistingId = !storedAcoustId.isBlank();
        List<TagInfo> r;
        if (hasExistingId && !Config.get().ignoreExistingFingerprints()) {
            // Option "lookup par identifiant" (SongKong) : interroger AcoustID par l'ID déjà présent plutôt que
            // de renoncer — voir Config.acoustidLookupByTrackId() pour le compromis (pas de re-vérification audio).
            if (!Config.get().acoustidLookupByTrackId()) return null;
            r = acoustId.identifyByTrackId(storedAcoustId);
        } else {
            r = acoustId.identify(fichier);
        }
        if (r.isEmpty() || !acoustIdResultPlausible(fichier, r.get(0), forceReidentify)) return null;
        if (existingTags.durationSec > 0
                && FileEntry.isDurationMismatch(existingTags.durationSec, r.get(0).mbDurationSec)) {
            // Une empreinte AcoustID à confiance élevée (le bypass dans acoustIdResultPlausible(),
            // ou une similarité d'artiste acceptable) peut malgré tout pointer vers le mauvais
            // enregistrement — repéré en direct 2026-08-11 sur "1-08 Crank It Up.mp3" (tags
            // existants corrects : David Guetta Feat. Akon – Crank It Up), matché par AcoustID à
            // "Dreams", rejeté par la durée mais qui s'arrêtait là avant ce correctif au lieu de
            // tenter la recherche texte ci-dessous — pourtant la meilleure chance ici, les tags
            // existants étant fiables.
            log(I18n.t("  AcoustID : durée incohérente (%ds vs %ds, %s – %s) → poursuite vers le texte",
                    existingTags.durationSec, r.get(0).mbDurationSec, r.get(0).artist, r.get(0).title));
            return null;
        }
        lastFindTagsSource.set(MetadataCache.SOURCE_ACOUSTID);
        return r;
    }

    /** Étape "SongRec" de la cascade findTags() — extraite (2026-09-02), même raison/mêmes
     *  garanties que {@link #tryAcoustId}. Corps inchangé par rapport à avant cette extraction.
     *  @return le résultat si résolu, {@code null} sinon (poursuivre la cascade). */
    private List<TagInfo> trySongRec(File fichier, TagInfo existingTags, MusicBrainzClient mb,
                                      LastFmClient lastFm, MetadataCache cache) {
        if (!SongRecClient.isAvailable()) return null;
        try {
            log(I18n.t("  SongRec..."));
            TagInfo sr = songRec.recognize(fichier);
            boolean srOk = sr != null && !sr.artist.isBlank() && !sr.title.isBlank();
            if (srOk) {
                log(I18n.t("  SongRec → %s – %s", sr.artist, sr.title));
            } else if (SongRecClient.lastFailureReason() != null) {
                // Jusqu'à ce correctif : un échec SongRec (timeout ffmpeg, aucun match
                // Shazam, réponse illisible...) ne laissait ABSOLUMENT aucune trace dans le
                // journal — le fichier passait directement à l'étape suivante sans que rien
                // n'explique pourquoi, restant PENDING sans indice si les étapes suivantes
                // échouaient aussi.
                log(I18n.t("  SongRec ✗ %s", SongRecClient.lastFailureReason()));
            }
            if (!srOk) {
                log(I18n.t("  SongRec → rien trouvé"));
                return null;
            }
            // true dès qu'un candidat SongRec→MB a été rejeté pour durée incohérente — dans
            // ce cas, le repli final "SongRec seul" (sans vérification MB, ci-dessous) ne
            // doit PAS non plus être rendu tel quel : sr.mbDurationSec y est toujours 0 (non
            // renseigné), donc invisible au filet de sécurité de l'appelant
            // (isDurationMismatch ignore mbSec<=0) — l'accepter reviendrait à contourner en
            // silence la vérification qu'on vient justement de faire échouer.
            boolean songRecDurationSuspect = false;
            // Enrichir avec MusicBrainz (ajoute MBIDs, piste, disque, etc.)
            String srHash = MetadataCache.queryHash(sr.artist, sr.title);
            String srCached = cache.getRecordingSearch(srHash);
            List<TagInfo> srMb;
            if (srCached != null) {
                srMb = mb.parseFromCache(srCached);
            } else {
                srMb = mb.searchRecording(sr.artist, sr.title);
                if (!srMb.isEmpty()) cache.putRecordingSearch(srHash, mb.lastRawJson());
            }
            if (!srMb.isEmpty() && srMb.get(0).score >= 50) {
                boolean durationOk = !(existingTags.durationSec > 0
                        && FileEntry.isDurationMismatch(existingTags.durationSec, srMb.get(0).mbDurationSec));
                if (durationOk && songRecResultPlausible(fichier, srMb.get(0))) {
                    TagInfo best = srMb.get(0);
                    // SongRec comble ce que MB n'a pas (genre, année Shazam, album Shazam)
                    if (best.genre.isBlank()   && !sr.genre.isBlank())   best.genre  = sr.genre;
                    if (best.year.isBlank()    && !sr.year.isBlank())    best.year   = sr.year;
                    if (best.album.isBlank()   && !sr.album.isBlank())   best.album  = sr.album;
                    if (best.comment.isBlank() && !sr.comment.isBlank()) best.comment= sr.comment;
                    best.score = 90;
                    log(I18n.t("  SongRec→MB: %s – %s [%s] score=%s", best.artist, best.title, best.album, best.score));
                    lastFindTagsSource.set(MetadataCache.SOURCE_SONGREC);
                    return srMb;
                } else if (!durationOk) {
                    // Durée incohérente avec CE candidat SongRec→MB précis (empreinte audio
                    // fiable sur un passage court/dégradé, mauvais enregistrement matché malgré
                    // un bon score texte — ex. un extrait DJ-pool de 1min30 reconnu comme un
                    // morceau totalement différent) : ne PAS s'arrêter ici comme avant (l'appelant
                    // finissait alors "Durée incohérente" sans jamais tenter la recherche texte
                    // basée sur le nom de fichier/tags, alors que CELLE-CI aurait pu trouver le
                    // bon enregistrement — repéré en direct 2026-08-11 sur "16741 - Dr. Dre -
                    // What's The Difference.mp3", matché à tort à "Breathe" de Blu Cantrell). On
                    // laisse tomber vers les étapes suivantes (titre nettoyé, texte) au lieu de
                    // rendre ce résultat.
                    log(I18n.t("  SongRec→MB : durée incohérente (%ds vs %ds) → poursuite vers les autres méthodes",
                            existingTags.durationSec, srMb.get(0).mbDurationSec));
                    songRecDurationSuspect = true;
                } else {
                    // Implausible (songRecResultPlausible() a déjà loggé le détail) : même repli
                    // que la durée incohérente ci-dessus, ne pas accepter aveuglément.
                    songRecDurationSuspect = true;
                }
            }
            // MB échoue avec le titre complet → réessayer sans qualificatif entre
            // parenthèses (ex. "Le coach (feat. Vincenzo)" → "Le coach") : SongRec/Shazam
            // renvoie souvent le featuring collé dans le titre, ce qui fait chuter le score
            // MB sous le seuil alors qu'une recherche sur le titre seul matche parfaitement
            // — même correctif déjà présent dans le fallback SongRec plus loin dans la
            // cascade (voir plus bas "titre nettoyé"), qui manquait ici jusqu'à présent.
            String cleanTitle = sr.title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            if (!cleanTitle.equals(sr.title) && !cleanTitle.isBlank()) {
                List<TagInfo> srMbClean = mb.searchRecording(sr.artist, cleanTitle);
                if (!srMbClean.isEmpty() && srMbClean.get(0).score >= 50) {
                    boolean durationOk = !(existingTags.durationSec > 0
                            && FileEntry.isDurationMismatch(existingTags.durationSec, srMbClean.get(0).mbDurationSec));
                    if (durationOk && songRecResultPlausible(fichier, srMbClean.get(0))) {
                        TagInfo best = srMbClean.get(0);
                        if (best.genre.isBlank()   && !sr.genre.isBlank())   best.genre  = sr.genre;
                        if (best.year.isBlank()    && !sr.year.isBlank())    best.year   = sr.year;
                        if (best.album.isBlank()   && !sr.album.isBlank())   best.album  = sr.album;
                        if (best.comment.isBlank() && !sr.comment.isBlank()) best.comment= sr.comment;
                        best.score = 90;
                        log(I18n.t("  SongRec→MB (titre nettoyé '%s'): %s – %s [%s] score=%s",
                                cleanTitle, best.artist, best.title, best.album, best.score));
                        lastFindTagsSource.set(MetadataCache.SOURCE_SONGREC);
                        return srMbClean;
                    } else if (!durationOk) {
                        // Même garde-fou que srMb ci-dessus.
                        log(I18n.t("  SongRec→MB (titre nettoyé) : durée incohérente (%ds vs %ds) → poursuite",
                                existingTags.durationSec, srMbClean.get(0).mbDurationSec));
                        songRecDurationSuspect = true;
                    } else {
                        songRecDurationSuspect = true;
                    }
                }
            }
            if (!songRecDurationSuspect && songRecResultPlausible(fichier, sr)) {
                // MB n'a rien enrichi : garder le résultat SongRec seul
                sr.score = 85;
                log(I18n.t("  SongRec seul (MB sans match): %s – %s", sr.artist, sr.title));
                // Cascade centralisée (au lieu d'une copie inline qui divergerait silencieusement
                // si TagEnrichment.enrichGenre change) — de toute façon re-noopée sans risque à
                // l'étape enrichGenre() plus loin dans processEntry() si le genre est déjà rempli.
                TagEnrichment.enrichGenre(sr, discogs, lastFm, cache);
                TagEnrichment.enrichClassicalWork(sr, mb, cache);
                TagEnrichment.enrichArtistInfo(sr, discogs, lastFm, cache);
                lastFindTagsSource.set(MetadataCache.SOURCE_SONGREC);
                return List.of(sr);
            }
            // songRecDurationSuspect : ne pas retourner sr non plus — laisser tomber vers la suite
            // de la cascade (l'autre méthode, texte...).
            return null;
        } catch (Exception e) {
            log(I18n.t("  SongRec WARN: %s", e.getMessage()));
            return null;
        }
    }

    /**
     * Garde-fou avant d'accepter un résultat AcoustID — trouvé en vérifiant en direct que
     * l'intégration AcoustID fonctionnait bien : sur "Daft Punk – One More Time" (sans ambiguïté
     * possible), AcoustID a renvoyé "Walt Ribeiro" avec une confiance MusicBrainz de 100. Pas un
     * bug de câblage : Chromaprint fait de la correspondance FLOUE sur une base communautaire
     * énorme, et un morceau massivement soumis (des milliers de rips légèrement différents)
     * accumule des empreintes quasi-identiques parfois attachées au mauvais enregistrement.
     * Si le fichier a déjà un artiste renseigné et que l'artiste proposé par AcoustID n'a
     * AUCUN rapport avec (similarité Picard sous 0.3), on se méfie plutôt que d'écraser
     * aveuglément — la cascade continue vers la recherche texte au lieu de renvoyer ce match
     * isolé. Jamais appliqué en réidentification forcée (forceReidentify) : ce mode ignore déjà
     * volontairement les tags existants (voir plus bas dans findTags()), les comparer ici irait
     * à l'encontre de son but.
     *
     * <p>Vérifié en direct sur le cas réel qui a révélé le problème : le TITRE seul n'est PAS un
     * signal fiable ici — l'enregistrement mal attribué à "Walt Ribeiro" avait pour titre
     * littéral {@code Daft Punk 'One More Time' [Volume 2]} (une référence entre guillemets au
     * vrai titre, fréquent dans les compilations/DJ sets), donnant une similarité de titre
     * élevée (~0.65) malgré un artiste totalement faux (~0.18) — un ET sur les deux aurait laissé
     * passer ce cas précis. L'ARTISTE, plus court et moins sujet à ce genre de faux positif par
     * inclusion, est le signal qui compte ici ; le titre n'est plus utilisé du tout dans ce test.
     */
    private boolean acoustIdResultPlausible(File fichier, TagInfo candidate, boolean forceReidentify) {
        // Empreinte très forte (même seuil "confiance excellente" qu'AcoustIdClient.
        // fetchBestFromMusicBrainz()) : on fait confiance à l'audio plutôt qu'au tag déjà présent,
        // qui peut lui-même être faux à la source (constaté en direct 2026-08-09 : un fichier tagué
        // "Double Vision - Knockin" par un outil tiers, dont l'empreinte AcoustID — confirmée
        // identique avant/après par une ré-analyse indépendante — pointait en réalité vers "Le
        // Manège Enchanté - Remix 93" ; la comparaison ci-dessous rejetait ce match correct
        // uniquement parce qu'il ne ressemblait pas au tag existant erroné). En dessous de ce
        // seuil, on garde la vérification par similarité : elle reste utile contre un vrai faux
        // positif à confiance faible/moyenne (cf. le cas "Rasputin" de SongRec, même session).
        // Contrôle de confiance TOUJOURS appliqué, y compris en re-taguage forcé — indépendant du
        // tag existant (une empreinte moyenne/faible n'est pas plus fiable juste parce qu'on a
        // choisi d'ignorer le tag présent). Avant ce correctif (2026-09-02, retour utilisateur —
        // log réel : "Club Nouveau" accepté comme "Ashanti", "Hong Kong Syndikat" comme "Chris Rea",
        // "D'JULZ" comme "Aluna"...), forceReidentify=true renvoyait true INCONDITIONNELLEMENT ici,
        // contournant même ce contrôle de confiance pourtant sans rapport avec le tag existant.
        if (candidate.acoustidConfidence >= 0.9) return true;
        // Sous ce seuil, en re-taguage forcé : rejeté plutôt qu'accepté aveuglément — pas de tag
        // existant fiable à comparer (justement ce qu'on veut pouvoir corriger), donc pas de base
        // pour une seconde chance ; le fichier retombe sur les méthodes suivantes (SongRec, texte).
        if (forceReidentify) return false;
        String existingArtist = readTag(fichier, FieldKey.ARTIST);
        if (existingArtist.isBlank()) return true; // rien à comparer

        double artistSim = TrackMatcher.titleSimilarity(existingArtist.toLowerCase(), candidate.artist.toLowerCase());
        if (artistSim < 0.3) {
            log(I18n.t("  AcoustID SUSPECT (artiste existant \"%s\" sans rapport avec \"%s\") → \"%s – %s\", ignoré",
                    existingArtist, candidate.artist, candidate.artist, candidate.title));
            return false;
        }
        return true;
    }

    /**
     * Garde-fou avant d'accepter un résultat SongRec — jusqu'ici trySongRec() n'avait AUCUNE
     * vérification de plausibilité (contrairement à AcoustID ci-dessus), seulement un score MB≥50
     * et une cohérence de durée — deux contrôles qui ne détectent rien quand le mauvais candidat a
     * une durée proche de l'original (cas réel trouvé en direct 2026-09-03 lors d'un lot "Forcer le
     * re-taguage" : "1-Magnets_(Sg_Lewis_Remix).mp3", 1:23, tags quasi vides, matché par SongRec à
     * "Frank Wilson – Do I Love You (Indeed I Do)" [Northern Soul, 1965] — un score=90 accepté tel
     * quel malgré un nom de fichier sans aucun rapport).
     *
     * <p>Contrairement à acoustIdResultPlausible(), compare contre le NOM DE FICHIER plutôt que le
     * tag ARTIST existant : reste utile même en re-taguage forcé (qui vide volontairement les tags
     * lus, mais n'a jamais traité le nom de fichier comme une donnée à ignorer — voir l'étape 3 de
     * findTags(), qui s'y appuie déjà en dernier recours y compris en mode forcé). Compare à la fois
     * l'artiste ET le titre analysés du nom de fichier (parseFilename()) — contrairement au titre
     * seul jugé peu fiable pour AcoustID (faux positif par référence entre guillemets dans un nom de
     * compilation/DJ-set, voir le commentaire ci-dessus), ce risque ne s'applique pas ici : un nom
     * de fichier de piste individuelle ne contient normalement pas ce genre de référence imbriquée.
     * Retient la MEILLEURE similarité des deux signaux disponibles plutôt que d'exiger les deux, pour
     * rester tolérant aux noms de fichiers qui ne suivent pas le format "Artiste - Titre" (beaucoup
     * n'ont qu'un des deux, voir parseFilename()). */
    /** Vrai si le candidat n'a AUCUN rapport avec l'entrée (nom de fichier et tags) : artiste de
     *  référence ET titre de référence disponibles (non génériques), et tous deux très éloignés du
     *  candidat. Sans référence exploitable des deux côtés → faux (rien ne permet de contredire). */
    static boolean contradictsInput(String fnArtist, String fnTitle, String tagArtist, String tagTitle,
                                    String candArtist, String candTitle) {
        java.util.List<String> artists = new java.util.ArrayList<>();
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (String a : new String[] { fnArtist, tagArtist })
            if (a != null && !a.isBlank() && !isGenericTag(a)) artists.add(a);
        for (String t : new String[] { fnTitle, tagTitle })
            if (t != null && !t.isBlank() && !isGenericTag(t)) titles.add(t);
        if (artists.isEmpty() || titles.isEmpty()) return false;
        double artistSim = 0, titleSim = 0;
        for (String a : artists) artistSim = Math.max(artistSim,
                TrackMatcher.titleSimilarity(a.toLowerCase(), String.valueOf(candArtist).toLowerCase()));
        for (String t : titles) titleSim = Math.max(titleSim,
                TrackMatcher.titleSimilarity(t.toLowerCase(), String.valueOf(candTitle).toLowerCase()));
        return artistSim < 0.3 && titleSim < 0.6;
    }

    private boolean songRecResultPlausible(File fichier, TagInfo candidate) {
        String[] fn = parseFilename(fichier);
        String fnArtist = fn[0], fnTitle = fn[1];
        boolean hasArtist = !fnArtist.isBlank() && !isGenericTag(fnArtist);
        boolean hasTitle  = !fnTitle.isBlank()  && !isGenericTag(fnTitle);
        if (!hasArtist && !hasTitle) return true; // rien d'exploitable dans le nom de fichier

        double bestSim = 0;
        if (hasArtist) bestSim = Math.max(bestSim,
                TrackMatcher.titleSimilarity(fnArtist.toLowerCase(), candidate.artist.toLowerCase()));
        if (hasTitle) bestSim = Math.max(bestSim,
                TrackMatcher.titleSimilarity(fnTitle.toLowerCase(), candidate.title.toLowerCase()));

        if (bestSim < 0.3) {
            log(I18n.t("  SongRec SUSPECT (nom de fichier \"%s\" sans rapport avec \"%s – %s\"), ignoré",
                    fichier.getName(), candidate.artist, candidate.title));
            return false;
        }
        return true;
    }

    /**
     * ESSAI (2026-09-25, demande utilisateur) — un fichier de moins d'une minute dont MusicBrainz annonce une durée bien
     * supérieure (ex. 0:40 vs 3:34) est presque toujours un téléchargement/rip tronqué : au lieu de l'isoler pour revue,
     * on l'envoie à la CORBEILLE (récupérable, jamais de suppression définitive). Désactivé par défaut
     * ({@code duration_mismatch.trash_short_enabled}).
     *
     * <p>Garde-fou contre le vrai risque — un interlude/skit/intro réel de 0:40 apparié à tort à un homonyme de 3:34 :
     * l'identification doit être CONFIRMÉE, soit par l'AUDIO (empreinte AcoustID ou SongRec : le début d'un fichier tronqué
     * reste reconnu), soit par le TEXTE de façon très nette (score ≥ 95 ET le titre écrit dans le fichier ressemble à ≥ 85 %
     * au titre MusicBrainz — le fichier se dit lui-même être ce morceau). Sinon : comportement habituel (isolé pour revue).
     * Chaque envoi est journalisé dans la console ET dans ~/.opentagger/duration_trial.log (pour revue/restauration).
     *
     * @return true si le fichier a été envoyé à la corbeille (l'appelant s'arrête là)
     */
    private static final java.util.concurrent.atomic.AtomicInteger TRIAL_TRASHED = new java.util.concurrent.atomic.AtomicInteger();

    private boolean tryTrashTruncated(FileEntry entry, File fichier, TagInfo shown) {
        Config cfg = Config.get();
        if (!cfg.durationMismatchTrashShortEnabled()) return false;
        int fileSec = entry.current.durationSec;
        if (!FileEntry.isShortTruncated(fileSec, shown.mbDurationSec,
                cfg.durationMismatchTrashShortMaxSec(), cfg.durationMismatchTrashShortMinGapSec())) return false;

        String src = lastFindTagsSource.get();
        boolean audioConfirmed = MetadataCache.SOURCE_ACOUSTID.equals(src) || MetadataCache.SOURCE_SONGREC.equals(src);
        String claimed = entry.current.title == null || entry.current.title.isBlank()
                ? parseFilename(fichier)[1] : entry.current.title;
        boolean textConfirmed = shown.score >= 95 && !claimed.isBlank() && !isGenericTag(claimed)
                && TrackMatcher.titleSimilarity(claimed.toLowerCase(), shown.title.toLowerCase()) >= 0.85;
        if (!audioConfirmed && !textConfirmed) {
            log(I18n.t("  essai durée : fichier court (%ds vs %ds) mais identification non confirmée (source=%s, score=%s) → "
                    + "conservé pour revue", fileSec, shown.mbDurationSec, src, shown.score));
            return false;
        }
        File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
        int cap = cfg.durationMismatchTrashShortMaxPerRun();
        if (TRIAL_TRASHED.get() >= cap) {
            if (TRIAL_TRASHED.get() == cap) {                       // un seul message par session
                TRIAL_TRASHED.incrementAndGet();
                log(I18n.t("  essai durée : plafond de %d fichier(s) à la corbeille atteint pour cette session — les suivants "
                        + "sont isolés pour revue comme avant (duration_mismatch.trash_short_max_per_run)", cap));
            }
            return false;
        }
        if (!f.exists() || !com.opentagger.TrashHelper.moveToTrash(f)) return false;
        TRIAL_TRASHED.incrementAndGet();

        String userMsg = I18n.t("Fichier tronqué (%s vs MusicBrainz %s, « %s ») → corbeille (essai)",
                FileTableModel.formatDuration(fileSec), FileTableModel.formatDuration(shown.mbDurationSec), shown.title);
        log(I18n.t("  🗑 essai durée : %s (%ds vs %ds, %s – %s, source=%s, score=%s) → corbeille",
                f.getName(), fileSec, shown.mbDurationSec, shown.artist, shown.title, src, shown.score));
        try {
            java.nio.file.Files.writeString(java.nio.file.Paths.get(Config.configDir(), "duration_trial.log"),
                    java.time.LocalDateTime.now() + "\t" + f.getAbsolutePath() + "\t" + fileSec + "\t" + shown.mbDurationSec
                            + "\t" + shown.artist + " – " + shown.title + "\t" + src + "\t" + shown.score + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { /* journal d'essai best-effort */ }
        entry.status     = FileEntry.Status.SKIPPED;
        entry.skipReason = com.opentagger.model.SkipReason.DURATION_MISMATCH;
        entry.message    = userMsg;
        return true;
    }

    /** Retourne true si le tag est générique/inutile pour une recherche. */
    private static final java.util.Set<String> GENERIC_TITLES_WITHOUT_ARTIST = java.util.Set.of(
        "intro", "outro", "skit", "interlude", "bonus", "hidden track",
        "reprise", "instrumental", "medley", "overture", "prelude",
        "remix", "edit", "version", "live", "acoustic"
    );

    /** Titre de candidat ressemblant à un mix DJ continu plutôt qu'à une piste individuelle — voir
     *  son appelant (boucle de cohérence de durée dans processEntry()). Volontairement étroit : ne
     *  doit jamais accrocher un vrai titre de piste contenant juste "remix" (très courant), donc
     *  chaque motif exige un mot-clé de mix EN PLUS de "mix" seul. */
    private static final java.util.regex.Pattern CONTINUOUS_MIX_TITLE = java.util.regex.Pattern.compile(
            "(?i)\\b(continuous\\s+mix|non-?stop\\s+mix|megamix|mixed\\s+by|mix\\s+session|"
            + "bpm\\s+continuous|dj\\s*-?\\s*mix)\\b");

    private static boolean isContinuousMixTitle(String title) {
        return title != null && CONTINUOUS_MIX_TITLE.matcher(title).find();
    }

    /** Suffixe "- Topic" (nom de chaîne YouTube Content ID auto-générée, ex. "Adriatique - Topic") —
     *  jamais un vrai crédit d'artiste, peut remonter via une empreinte audio (SongRec/AcoustID)
     *  identifiant un enregistrement dont l'origine YouTube a laissé ce suffixe dans les métadonnées
     *  communautaires. Repéré en direct 2026-08-28 : a remplacé "Adriatique, Marino Canal et Delhia
     *  De France" (crédit complet, correct) par "Adriatique - Topic" (un seul artiste, suffixe
     *  parasite) — une régression, pas une amélioration. */
    private static final java.util.regex.Pattern YOUTUBE_TOPIC_SUFFIX = java.util.regex.Pattern.compile(
            "(?i)\\s*-\\s*topic$");

    private static String stripYoutubeTopicSuffix(String artist) {
        return artist == null ? null : YOUTUBE_TOPIC_SUFFIX.matcher(artist).replaceAll("");
    }

    /** Piste de la release épinglée qui correspond au morceau identifié : d'abord le MÊME enregistrement MusicBrainz,
     *  sinon un titre quasi identique (≥ 0,85) dont la durée est compatible avec le fichier (≥ 0,95 si une des
     *  durées est inconnue). {@code null} si rien de sûr — le garde-fou skippe alors le fichier comme avant. */
    static MusicBrainzClient.ReleaseTrack pickTrackInPinnedRelease(
            java.util.List<MusicBrainzClient.ReleaseTrack> tracks, String recordingMbid, String title, int fileDurationSec) {
        if (recordingMbid != null && !recordingMbid.isBlank())
            for (MusicBrainzClient.ReleaseTrack t : tracks)
                if (recordingMbid.equalsIgnoreCase(t.recordingMbid())) return t;
        if (title == null || title.isBlank()) return null;
        MusicBrainzClient.ReleaseTrack best = null;
        double bestSim = 0;
        for (MusicBrainzClient.ReleaseTrack t : tracks) {
            double sim = TrackMatcher.titleSimilarity(title.toLowerCase(), t.title().toLowerCase());
            boolean durKnown = fileDurationSec > 0 && t.lengthMs() > 0;
            if (durKnown && FileEntry.isStrictDurationMismatch(fileDurationSec, t.lengthMs() / 1000)) continue;
            if (sim >= (durKnown ? 0.85 : 0.95) && sim > bestSim) { best = t; bestSim = sim; }
        }
        if (best != null || fileDurationSec <= 0) return best;
        // Repli (2026-10-08) : titres comparés SANS les précisions de version, et seulement si la durée est connue et
        // cohérente — « Ces Soirées Là (radio Edit) » est la piste « Ces soirées-là » de la compilation du dossier.
        String core = title.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ").trim().toLowerCase();
        if (core.length() < 3) return null;
        for (MusicBrainzClient.ReleaseTrack t : tracks) {
            if (t.lengthMs() <= 0 || FileEntry.isStrictDurationMismatch(fileDurationSec, t.lengthMs() / 1000)) continue;
            String tc = t.title().replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ").trim().toLowerCase();
            double sim = TrackMatcher.titleSimilarity(core, tc);
            if (sim >= 0.9 && sim > bestSim) { best = t; bestSim = sim; }
        }
        return best;
    }

    /** Identification propre au fichier assez sûre pour ignorer le garde-fou de groupe : score maximal ET durée
     *  MusicBrainz connue et compatible avec celle du fichier (jamais sur une durée inconnue). */
    static boolean ownReleaseIsTrustworthy(TagInfo best, int fileDurationSec) {
        return best.score >= 100 && best.mbDurationSec > 0 && fileDurationSec > 0
                && !FileEntry.isDurationMismatch(fileDurationSec, best.mbDurationSec);
    }

    /** Rattache {@code best} (morceau déjà identifié) à la release épinglée du groupe quand il y figure aussi.
     *  @return {@code true} si {@code best} a été réécrit sur cette release. */
    private boolean retargetToPinnedRelease(TagInfo best, String pinnedMbid, int fileDurationSec) {
        if (pinnedTracklistFailed.contains(pinnedMbid)) return false;
        MusicBrainzClient.ReleaseTracklist tl = pinnedTracklistCache.computeIfAbsent(pinnedMbid, mbid -> {
            try { return mb.lookupRelease(mbid); } catch (Exception e) { return null; }
        });
        if (tl == null) { pinnedTracklistFailed.add(pinnedMbid); return false; }
        MusicBrainzClient.ReleaseTrack myTrack = pickTrackInPinnedRelease(tl.tracks(), best.recordingMbid, best.title, fileDurationSec);
        if (myTrack == null) return false;
        applyTrackOfRelease(best, tl, myTrack);
        log(I18n.t("  Cohérence de groupe → rattaché à la release du groupe [%s] : %s – %s",
                tl.album(), best.artist, best.title));
        return true;
    }

    /** Nom d'album porté par le dossier du fichier (le dossier parent, ou le grand-parent si le parent est « Disc 01 »,
     *  « CD2 »…), sans ce qui est entre parenthèses ou crochets (« (2014) », « [MP3 320] »). */
    static String folderAlbumName(File fichier) {
        File dir = fichier.getParentFile();
        if (dir == null) return "";
        String name = dir.getName();
        if (name.matches("(?i)^(disc|disque|cd)\\s*\\d+.*") && dir.getParentFile() != null) name = dir.getParentFile().getName();
        return name.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ").trim();
    }

    /** Deux noms d'album désignent-ils la même parution ? Égaux une fois normalisés, ou l'un est le début de l'autre (noms de
     *  dossier tronqués : « Footloose_ Original Soundtrack of the Pa »), ou très proches. */
    static boolean sameAlbumName(String a, String b) {
        String x = AlbumMatcher.norm(a), y = AlbumMatcher.norm(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        String shorter = x.length() <= y.length() ? x : y, longer = shorter == x ? y : x;
        if (shorter.length() >= 12 && longer.startsWith(shorter)) return true;
        return TrackMatcher.titleSimilarity(x, y) >= 0.92;
    }

    /** Rattache {@code best} à la parution qui porte le nom du dossier, si son enregistrement y figure (voir l'appel). */
    private boolean retargetToFolderRelease(TagInfo best, File fichier, int fileDurationSec) throws Exception {
        String folder = folderAlbumName(fichier);
        String fn = AlbumMatcher.norm(folder);
        if (fn.length() < 4 || isGenericAlbumName(fn)) return false;
        if (sameAlbumName(folder, best.album)) return false;
        // 1) Parutions qui contiennent CET enregistrement ; 2) sinon parutions qui portent le nom du dossier (même chanson
        //    sous un autre enregistrement MusicBrainz, cas le plus fréquent sur les compilations : 128 cas sur 144 mesurés),
        //    où la piste est retrouvée par titre ET durée (pickTrackInPinnedRelease), jamais au hasard.
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        for (MusicBrainzClient.ReleaseRef ref : mb.releasesOfRecording(best.recordingMbid))
            if (!ref.releaseMbid().isBlank() && sameAlbumName(folder, ref.title())) candidates.add(ref.releaseMbid());
        if (candidates.isEmpty()) {
            List<String> byTitle = folderReleaseCache.computeIfAbsent(fn, k -> {
                List<String> ids = new ArrayList<>();
                try {
                    for (MusicBrainzClient.ReleaseRef ref : mb.searchReleasesByTitle(folder, 10))
                        if (!ref.releaseMbid().isBlank() && sameAlbumName(folder, ref.title())) ids.add(ref.releaseMbid());
                } catch (Exception ignored) {}
                return ids.size() > 3 ? ids.subList(0, 3) : ids;
            });
            candidates.addAll(byTitle);
        }
        candidates.remove(best.releaseMbid);
        for (String releaseMbid : candidates) {
            String before = best.album;
            if (retargetToPinnedRelease(best, releaseMbid, fileDurationSec)) {
                log(I18n.t("  Album du dossier « %s » → parution « %s » retenue au lieu de « %s »", folder, best.album, before));
                return true;
            }
        }
        return false;
    }

    /** Parutions trouvées par le NOM d'un dossier (normalisé) — une seule recherche MusicBrainz par dossier et par lot. */
    private final java.util.Map<String, List<String>> folderReleaseCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Release dominante d'un dossier : au moins 3 pistes ET au moins 60 % des pistes identifiées ; sinon {@code null}
     *  (dossier mélangé — compilations diverses — où rien ne doit être uniformisé). */
    static String dominantRelease(java.util.List<String> releases) {
        java.util.Map<String, Integer> n = new java.util.HashMap<>();
        int total = 0;
        for (String r : releases) { if (r == null || r.isBlank()) continue; n.merge(r, 1, Integer::sum); total++; }
        String best = null; int bc = 0;
        for (var e : n.entrySet()) if (e.getValue() > bc) { best = e.getKey(); bc = e.getValue(); }
        return best != null && bc >= 3 && bc * 10 >= total * 6 ? best : null;
    }

    /** Noms d'album qui ne désignent aucun album précis (valeurs de remplissage) : jamais une base de regroupement.
     *  {@code normalized} est déjà passé par {@link AlbumMatcher#norm}. Un « Album inconnu (date heure) » propre à une session
     *  d'extraction, lui, est un vrai regroupement : la date l'individualise. */
    static boolean isGenericAlbumName(String normalized) {
        return java.util.Set.of("unknown album", "album inconnu", "unbekanntes album", "unbekannter album", "audios", "audio", "sans titre",
                "untitled", "no album", "none", "singles", "single", "musique", "music", "divers", "various", "compilation",
                "greatest hits", "best of", "hits").contains(normalized);
    }

    /** Écrit sur le disque la réparation d'encodage d'un fichier resté non identifié, puis met à jour l'affichage (sur l'EDT). */
    private void writeEncodingRepair(FileEntry entry, File fichier) {
        try {
            TagInfo onDisk = com.opentagger.TagReader.read(fichier);
            if (!com.opentagger.EncodingFixer.repairFields(onDisk)) return;
            new com.opentagger.TagWriter().write(fichier, onDisk);
            final TagInfo fixed = onDisk;
            SwingUtilities.invokeLater(() -> {
                if (entry.current != null) {
                    entry.current.title = fixed.title;
                    entry.current.artist = fixed.artist;
                    entry.current.albumArtist = fixed.albumArtist;
                    entry.current.album = fixed.album;
                    entry.current.comment = fixed.comment;
                }
            });
            log(I18n.t("  encodage cassé corrigé dans le fichier : %s – %s", onDisk.artist, onDisk.title));
        } catch (Exception ex) {
            log(I18n.t("  correction d'encodage non écrite : %s", ex.getMessage()));
        }
    }

    /** L'ALBUM (la release) de ce fichier est-il déjà prouvé, donc à ne pas changer ? Oui pour une identification par TOC (le disque
     *  entier), ou si le fichier porte déjà l'identifiant de sa release. Un simple identifiant d'ENREGISTREMENT prouve la chanson, pas
     *  l'album : elle figure sur des dizaines de parutions, et l'album choisi à partir de lui n'est qu'une préférence. */
    static boolean releaseIsProven(String identificationSource, TagInfo currentTags) {
        if (MetadataCache.SOURCE_DISCID.equals(identificationSource)) return true;
        if (!MetadataCache.SOURCE_MBID.equals(identificationSource)) return false;
        return currentTags != null && currentTags.releaseMbid != null && !currentTags.releaseMbid.isBlank();
    }

    /** Valeur la plus fréquente parmi des textes non vides ({@code ""} si aucun). */
    static String mostCommon(List<String> values) {
        java.util.Map<String, Integer> n = new java.util.LinkedHashMap<>();
        for (String v : values) if (v != null && !v.isBlank()) n.merge(v.trim(), 1, Integer::sum);
        String best = ""; int bc = 0;
        for (var e : n.entrySet()) if (e.getValue() > bc) { best = e.getKey(); bc = e.getValue(); }
        return best;
    }

    private static int intOrZero(String s) {
        if (s == null) return 0;
        String d = s.trim().split("[/\\s]")[0];
        try { return Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    /** Cherche, parmi les releases déjà trouvées pour les fichiers d'un dossier, celle qui explique TOUS les fichiers (copies
     *  comprises) et y rattache ceux qui sont ailleurs. Les identifications prouvées par TOC ou par MBID déjà connu ne sont pas
     *  touchées.
     *  @return {@code true} si une telle release existe (le dossier est traité), {@code false} pour laisser l'ancien vote faire */
    private boolean harmonizeByFullMatch(List<FileEntry> group) {
        java.util.Set<String> mbids = new java.util.LinkedHashSet<>();
        for (FileEntry e : group) if (e.result.releaseMbid != null && !e.result.releaseMbid.isBlank()) mbids.add(e.result.releaseMbid);
        if (mbids.isEmpty()) return false;
        List<MusicBrainzClient.ReleaseTracklist> candidates = new java.util.ArrayList<>();
        for (String id : mbids) {
            MusicBrainzClient.ReleaseTracklist tl = pinnedTracklistCache.computeIfAbsent(id, mbid -> {
                try { return mb.lookupRelease(mbid); } catch (Exception ex) { return null; }
            });
            if (tl != null) candidates.add(tl);
        }
        if (candidates.isEmpty()) return false;

        List<AlbumMatcher.Item> items = new java.util.ArrayList<>();
        java.util.Map<String, FileEntry> byId = new java.util.HashMap<>();
        for (FileEntry e : group) {
            String id = e.file.getPath();
            TagInfo t = e.result, cur = e.current;
            items.add(new AlbumMatcher.Item(id, t.title, t.artist, cur != null ? cur.durationSec : 0,
                    cur != null ? intOrZero(cur.track) : 0, cur != null ? intOrZero(cur.discNo) : 0, t.recordingMbid));
            byId.put(id, e);
        }
        String albumHint = mostCommon(group.stream().map(e -> e.current != null ? e.current.album : "").toList());
        String yearHint = mostCommon(group.stream().map(e -> e.current != null ? e.current.year : "").toList());

        AlbumMatcher.Match m = AlbumMatcher.best(items, candidates, albumHint, yearHint);
        if (m == null) return false;

        MusicBrainzClient.ReleaseTracklist tl = m.release();
        for (var en : m.trackByItemId().entrySet()) {
            FileEntry e = byId.get(en.getKey());
            TagInfo t = e.result;
            if (tl.releaseMbid().equals(t.releaseMbid)) continue;
            if (releaseIsProven(t.identificationSource, e.current)) continue;
            String before = t.album;
            applyTrackOfRelease(t, tl, en.getValue());
            log(I18n.t("  Album entier → %s : « %s » → « %s » (la release explique les %d morceaux du dossier)",
                    e.filename(), before, tl.album(), m.slots()));
            publish(e);
        }
        return true;
    }

    /** Rattache à la release dominante de leur dossier les pistes identifiées ailleurs, MAIS seulement si le même morceau
     *  figure sur cette release (même enregistrement, ou même titre à durée compatible) — jamais d'invention. Les
     *  identifications prouvées par TOC ou par MBID déjà connu ne sont pas touchées. */
    private void harmonizeAlbumReleases(List<FileEntry> queue) {
        java.util.Map<String, List<FileEntry>> byDir = new java.util.LinkedHashMap<>();
        for (FileEntry e : queue) {
            if (e.status != FileEntry.Status.IDENTIFIED || e.result == null) continue;
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            File dir = f.getParentFile();
            if (dir == null) continue;
            if (DISC_FOLDER_PATTERN.matcher(dir.getName()).find() && dir.getParentFile() != null) dir = dir.getParentFile();
            byDir.computeIfAbsent(dir.getPath(), k -> new java.util.ArrayList<>()).add(e);
        }
        // Fichiers qui partagent le MÊME ALBUM dans leurs tags, même rangés dans des dossiers différents (un CD extrait dont chaque
        // titre est parti dans le dossier de son artiste) : traités plus bas comme un dossier, si une release les explique tous.
        java.util.Map<String, List<FileEntry>> byAlbumTag = new java.util.LinkedHashMap<>();
        if (Config.get().bool("matching.album_first", true)) {
            for (FileEntry e : queue) {
                if (e.status != FileEntry.Status.IDENTIFIED || e.result == null || e.current == null) continue;
                String key = AlbumMatcher.norm(e.current.album);
                if (key.isEmpty() || isGenericAlbumName(key)) continue;
                byAlbumTag.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
            }
        }
        for (List<FileEntry> group : byAlbumTag.values()) {
            if (group.size() >= 3) harmonizeByFullMatch(group);
        }
        for (List<FileEntry> group : byDir.values()) {
            if (group.size() < 3) continue;
            // Nouveau moteur : la release qui explique TOUS les fichiers du dossier (voir AlbumMatcher). Sans elle, l'ancien vote.
            if (Config.get().bool("matching.album_first", true) && harmonizeByFullMatch(group)) continue;
            String r = dominantRelease(group.stream().map(e -> e.result.releaseMbid).toList());
            if (r == null) continue;
            MusicBrainzClient.ReleaseTracklist tl = pinnedTracklistCache.computeIfAbsent(r, mbid -> {
                try { return mb.lookupRelease(mbid); } catch (Exception ex) { return null; }
            });
            if (tl == null) continue;
            for (FileEntry e : group) {
                TagInfo t = e.result;
                if (r.equals(t.releaseMbid)) continue;
                String src = t.identificationSource;
                if (MetadataCache.SOURCE_DISCID.equals(src) || MetadataCache.SOURCE_MBID.equals(src)) continue;
                MusicBrainzClient.ReleaseTrack tr = pickTrackInPinnedRelease(tl.tracks(), t.recordingMbid, t.title,
                        e.current != null ? e.current.durationSec : 0);
                if (tr == null) continue;
                String before = t.album;
                applyTrackOfRelease(t, tl, tr);
                log(I18n.t("  Cohérence d'album → %s : « %s » → « %s »", e.filename(), before, tl.album()));
                publish(e);
            }
        }
    }
    /** Réécrit les champs de PARUTION de {@code best} d'après la piste {@code myTrack} de la release {@code tl}
     *  (artiste et titre de la piste identifiée restent ceux de {@code best}). */
    static void applyTrackOfRelease(TagInfo best, MusicBrainzClient.ReleaseTracklist tl, MusicBrainzClient.ReleaseTrack myTrack) {
        best.album            = tl.album();
        best.albumArtist      = tl.albumArtist().isBlank() ? best.artist : tl.albumArtist();
        best.albumArtistSort  = tl.albumArtistSort();
        best.year             = tl.year();
        best.originalYear     = tl.originalYear();
        best.releaseMbid      = tl.releaseMbid();
        best.releaseGroupMbid = tl.releaseGroupMbid();
        best.recordingMbid    = myTrack.recordingMbid();
        best.releaseTrackMbid = myTrack.trackMbid();
        best.discSubtitle     = myTrack.discTitle();
        best.track            = String.valueOf(myTrack.trackNo());
        best.trackTotal       = String.valueOf(myTrack.trackTotal());
        best.discNo           = myTrack.disc() > 0 ? String.valueOf(myTrack.disc()) : "";
        best.country          = tl.country();
        best.barcode          = tl.barcode();
        best.releaseStatus    = tl.releaseStatus();
        best.label            = tl.label();
        best.catalogNo        = tl.catalogNo();
        best.script           = tl.script();
        best.applyReleaseLevelFrom(tl.releaseMeta());
        best.isCompilation    = tl.isCompilation() ? "1" : "";   // la nouvelle release décide (l'ancienne pouvait être une compilation)
        if (myTrack.lengthMs() > 0) best.mbDurationSec = myTrack.lengthMs() / 1000;
    }

    /** Cœur de décision du garde-fou "une seule release par groupe" (voir son appelant, dans la
     *  boucle principale, pour le contexte complet) — extrait en méthode statique pure pour être
     *  testable isolément (reflection, comme le reste de la discipline de test de cette session)
     *  sans devoir invoquer toute la chaîne findTags()/MusicBrainzClient. Renommée de
     *  shouldCapForGroupMismatch (2026-09-19) : son appelant ne "plafonne" plus un score qui
     *  n'était de toute façon jamais revérifié, il SKIPPE réellement le fichier — voir le
     *  commentaire d'appel pour le bug que ça corrige. */
    enum GroupMismatch { NO_CONFLICT, SIBLING_EDITION, CONFIRMED_OTHER_RELEASE, SKIP }

    /** Piste identifiée sur une AUTRE release que celle corroborée par ≥ 2 pistes du groupe :
     *  même groupe de parutions (autre édition du même album) → acceptée ; sinon acceptée si
     *  l'identification est sûre (empreinte audio, ou score ≥ 95) ; seul un match texte moyen
     *  reste en revue manuelle. */
    static GroupMismatch resolveGroupMismatch(int agreementOnPinnedRelease, String source, int currentScore,
                                              String pinnedReleaseGroup, String trackReleaseGroup) {
        if (!shouldSkipForGroupMismatch(agreementOnPinnedRelease, source, currentScore)) return GroupMismatch.NO_CONFLICT;
        if (pinnedReleaseGroup != null && !pinnedReleaseGroup.isBlank()
                && pinnedReleaseGroup.equalsIgnoreCase(trackReleaseGroup)) return GroupMismatch.SIBLING_EDITION;
        boolean audio = MetadataCache.SOURCE_ACOUSTID.equals(source) || MetadataCache.SOURCE_SONGREC.equals(source);
        if (audio || currentScore >= 95) return GroupMismatch.CONFIRMED_OTHER_RELEASE;
        return GroupMismatch.SKIP;
    }

    static boolean shouldSkipForGroupMismatch(int agreementOnPinnedRelease, String source, int currentScore) {
        boolean trustedSource = MetadataCache.SOURCE_DISCID.equals(source)
                || MetadataCache.SOURCE_MBID.equals(source);
        return agreementOnPinnedRelease >= 2 && !trustedSource && currentScore > 50;
    }

    // Package-private (pas private) depuis le 2026-09-18 : réutilisé tel quel par
    // CompletenessReportDialog pour rester cohérent avec la définition de "champ incomplet" déjà
    // établie ici (incompletenessScore()) plutôt que d'en dupliquer une variante légèrement
    // différente dans un nouveau fichier.
    static boolean isGenericTag(String s) {
        if (s == null || s.isBlank()) return true;
        String low = s.trim().toLowerCase();
        // Tags par défaut des encodeurs/téléchargeurs — "artiste inconnu"/"unknown album"/
        // "album inconnu" ajoutés (2026-09-05, repéré en direct sur un lot de 53245 fichiers) :
        // équivalents français/ordre inversé de "unknown artist"/"inconnu" déjà couverts, mais
        // absents tels quels — low.matches() exige une correspondance de la CHAÎNE ENTIÈRE, donc
        // "artiste inconnu" (2 mots) ne matchait NI "artiste" NI "inconnu" seuls, ni "unknown
        // artist" (bon ordre, mauvaise langue). Résultat vu en direct : "Artiste Inconnu – Album
        // Inconnu (28- 1" et "Unknown Album – Beres Hammond - Still Going Strong" acceptés tels
        // quels comme artiste/titre par le repli SOURCE_UNVERIFIED_TAGS (score=50).
        if (low.matches("unknown artist|unknown|artist|artiste|musique|musiques|music|inconnu|"
                       + "various|various artists|no artist|no album|piste \\d+|track \\d+|"
                       // « track » / « piste » SEULS : le nom d'un fichier « track12.mp3 » perd ses chiffres à l'analyse et il reste le mot « track »,
                       // que MusicBrainz trouvait volontiers (« Track Track », score 100). Jamais une vraie information.
                       + "track|piste|pista|spur|audiotrack|audio track|cdtrack|cd track|trk|"
                       + "titre|title|untitled|inconnu - -.*|artiste inconnu|album inconnu|"
                       + "unknown album")) return true;
        // Patterns "Unknown Artist_NNN", "Unknown_42", "Musique Ii"
        if (low.matches("unknown\\s?artist[_\\s]\\d+")) return true;
        if (low.matches("musique\\s+ii?")) return true;
        // Trop court pour être utile
        if (low.length() <= 2) return true;
        // Ressemble à un nom de fichier technique (underscores, codes)
        if (low.matches("[a-z0-9_\\-]{1,6}\\d{2,}")) return true;
        // Nom de fichier de pochette (cover.jpg, folder.png…) recopié par erreur dans le titre par
        // un outil tiers en amont (probablement une conversion .opus→.mp3 buggée qui a confondu le
        // nom de fichier de la pochette avec le titre de la piste) — repéré en direct (2026-08-13)
        // sur 6 fichiers avec des tags MB EXISTANTS par ailleurs valides (releaseMbid+artist+album),
        // que le chemin "confiance aux tags existants" ci-dessus ne validait jusqu'ici QUE sur la
        // cohérence de l'artiste via SongRec, jamais sur le titre — ce titre absurde passait donc
        // sans être détecté ni corrigé.
        if (low.matches("(cover|folder|front|back|album|art|artwork)\\.(jpe?g|png|gif|bmp|webp)")) return true;
        // Noms par défaut des outils de téléchargement/enregistrement ("download.m4a", "download (3)",
        // "Nouvel enregistrement 2"…) — aucune information sur le morceau. Avant ce correctif
        // (2026-10-03, journal réel d'un re-taguage forcé de 177 fichiers) "download" partait tel quel
        // en recherche MusicBrainz et chaque fichier était "identifié" comme le premier morceau
        // portant ce titre ("Mr. D – Download", "K2 – Download", score=100 trompeur), puis enregistré.
        if (low.matches("(download(ed)?|nouvel enregistrement|new recording|sans titre)([\\s_\\-]*\\(?\\d+\\)?)?")) return true;
        // Aucun caractère alphanumérique exploitable (ex. tag corrompu "_ _ _ _ _ _ _ _ _ _ _ _ _ _") —
        // repéré en direct 2026-09-01 : cleanSearchTerm() convertit les "_" en espaces, donc ce genre
        // de titre part en recherche MB avec un terme quasi vide ; l'artiste seul suffit alors à MB
        // pour renvoyer N'IMPORTE QUELLE piste de son catalogue avec un score="100" trompeur (rien ne
        // confirme qu'il s'agit bien de CE fichier). Sans signal exploitable, mieux vaut passer par
        // SongRec/AcoustID (empreinte audio réelle) que par une recherche texte qui ne peut que deviner.
        if (s.trim().replaceAll("[^\\p{L}\\p{N}]", "").isBlank()) return true;
        return false;
    }

    /** Nettoie les artefacts techniques courants d'un terme de recherche. */
    private String cleanSearchTerm(String s) {
        if (s == null) return "";
        return s
            // Apostrophes/guillemets typographiques → droits (écart trouvé vs SongKong,
            // EquivalentCharsSimplifier, analyse du jar décompilé 2026-09-18) : contrairement à
            // TrackMatcher.titleSimilarity() (déjà insensible à cette variation — son tokenizer \W+
            // traite les deux formes comme un même séparateur, vérifié en direct), CETTE chaîne part
            // en recherche TEXTE côté serveur MusicBrainz — sa tokenisation à lui échappe à notre
            // contrôle, donc une différence ici peut réellement réduire le rappel de la recherche.
            // "Rock ’n’ Roll" (apostrophe typographique, héritage Windows/Discogs/rip web) normalisé
            // en "Rock 'n' Roll" avant l'envoi, pas juste dans le titre déjà démontré insensible.
            .replaceAll("[‘’‚ʼ]", "'")
            .replaceAll("[“”„]", "\"")
            .replaceAll("[‐‑‒–—―]", "-")
            .replaceAll("(?i)[_\\s]*[\\[(]?\\d{2,3}k[\\])]?$", "")         // _320k, (128k)
            .replaceAll("(?i)[_\\s]*\\(?(HQ|HD|FLAC|MP3|WAV|320|256|192|128)\\)?$", "")
            .replaceAll("(?i)\\s*\\[?OFFICIAL.*$", "")
            .replaceAll("(?i)\\s*[\\[(](karaoke|instrumental|vs karaoke|backing track)[\\])].*$", "")
            .replaceAll("^(?:\\d{2,3}[_\\s])+", "")                          // 04_04_, 07_
            .replace('_', ' ')                                                // underscores → espaces
            .replaceAll("\\s{2,}", " ")
            .trim();
    }

    /** Extrait le premier artiste si l'artiste contient " and ", " & ", " feat", "/" etc. */
    /**
     * Génère une liste de suggestions d'amélioration pour un fichier tagué.
     * Chaque suggestion décrit un point qui mérite vérification manuelle.
     */
    private static List<String> buildSuggestions(TagInfo best, int seuil) {
        List<String> s = new java.util.ArrayList<>();
        // « Modéré » = au-dessus du seuil mais pas parfait. Plafonné à 100 : avec un seuil ≥ 81, « seuil + 20 » dépassait 100 et TOUTE
        // identification (même à 100 %) portait « Score modéré (100%) » — 9 145 avertissements dans un seul journal.
        if (best.score > 0 && best.score < Math.min(seuil + 20, 100))
            s.add(I18n.t("Score modéré (%s%%) — vérifier l'identification", best.score));
        // "Pochette non trouvée" ne peut plus être ajouté ici : la pochette n'est résolue qu'à
        // l'Enregistrement (façon Picard), voir TagEnrichment.saveEntry(). SaveWorker ajoute
        // cette suggestion après coup si saveResult.cover() == null.
        if (best.recordingMbid.isBlank())
            s.add(I18n.t("MBID d'enregistrement manquant"));
        if (best.album.isBlank())
            s.add(I18n.t("Album inconnu"));
        if (best.year.isBlank())
            s.add(I18n.t("Année manquante"));
        if (best.genre.isBlank())
            s.add(I18n.t("Genre manquant"));
        return s;
    }

    /**
     * Score d'incomplétude : plus le score est élevé, plus le fichier manque d'infos.
     * Utilisé pour traiter les fichiers les plus incomplets en priorité.
     *   titre/artiste manquants  → +3 chacun (critique)
     *   album manquant           → +2
     *   année/genre manquants    → +1 chacun
     */
    // Un placeholder ("Unknown Artist", "Inconnu"...) est aussi peu exploitable qu'un champ VIDE —
    // même poids que isBlank() ci-dessous. Absent jusqu'à ce correctif (2026-09-13) : la priorisation
    // ne comptait que les champs blancs, alors qu'isGenericTag() est déjà utilisé partout ailleurs
    // dans ce fichier pour ne JAMAIS faire confiance à ces valeurs pendant l'identification — un
    // fichier "Unknown Artist" n'était donc pas priorisé pour un rattrapage, contrairement à ce que
    // son statut réel (aussi peu identifié qu'un champ vide) mériterait.
    private static int incompletenessScore(com.opentagger.model.TagInfo t) {
        if (t == null) return 10;
        int s = 0;
        if (t.title.isBlank()  || isGenericTag(t.title))  s += 3;
        if (t.artist.isBlank() || isGenericTag(t.artist)) s += 3;
        if (t.album.isBlank()  || isGenericTag(t.album))  s += 2;
        if (t.year.isBlank())   s += 1;
        if (t.genre.isBlank())  s += 1;
        return s;
    }

    private static volatile Boolean songRecUsableCache;

    /** SongRec utilisable ? (résultat mémorisé : sous Linux le test lance un processus, inutile de le refaire à chaque fichier). */
    private static boolean songRecUsable() {
        Boolean v = songRecUsableCache;
        if (v == null) { v = com.opentagger.SongRecClient.isAvailable(); songRecUsableCache = v; }
        return v;
    }

    /** Titre sans invité « (feat. …) » / « [ft. …] » / « feat. … » final, ni marqueur de copie « (1) ». Ne touche PAS aux autres parenthèses
     *  (« (Remix) », « (Live) »… qui distinguent vraiment les enregistrements). */
    static String simplifyTitle(String title) {
        if (title == null) return "";
        String s = title
            .replaceAll("(?i)\\s*[\\(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|avec)\\s[^\\)\\]]*[\\)\\]]", "")
            .replaceAll("(?i)\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.+$", "")
            .replaceAll("\\s*\\(\\d{1,2}\\)\\s*$", "")
            .replaceAll("\\s{2,}", " ")
            .trim();
        return s.isBlank() ? title : s;
    }

    private String simplifyArtist(String artist) {
        if (artist.isBlank()) return artist;
        // Couper sur les séparateurs communs et retourner le premier segment
        String s = artist
            .replaceFirst("(?i)\\s+(and|&|feat\\.?|ft\\.?|vs\\.?|avec|\\+)\\s+.*", "")
            .replaceFirst("\\s*/.*", "")   // couper sur /
            .trim();
        return s.equals(artist) ? artist : s;
    }

    /**
     * Retire un préfixe numérique de piste ou d'identifiant de catalogue/téléchargement en tête
     * de chaîne : "04 04 ", "04 " ou "04 - ", et tout aussi bien "16741 - " (identifiant à 4-5
     * chiffres, très fréquent dans certaines bibliothèques — sans ce retrait, un nom comme
     * "16741 - Dr. Dre - What's The Difference.mp3" analyse l'artiste comme "16741", introuvable
     * tel quel sur MusicBrainz). Bornes {1,6} : couvre un numéro de piste à 1 chiffre comme un
     * identifiant à 5-6 chiffres. Statique et public : réutilisé tel quel par
     * MainFrame pour nettoyer le nom réel du fichier des pistes "Non identifié" (pas seulement
     * l'analyse interne ci-dessous), afin que les deux restent en permanence synchronisés.
     */
    public static String stripLeadingNumericPrefix(String s) {
        return s.replaceAll("^(?:\\d{1,6}[\\s._-]+)+", "").trim();
    }

    /**
     * Retire un suffixe "-temp-NNNNN" en fin de nom — pas ajouté par OpenTagger (aucune trace dans
     * ce code), très probablement un reste d'un outil externe de téléchargement (yt-dlp ou
     * similaire — voir le script d'export playlist désactivé le 2026-07-15) qui n'a jamais terminé
     * son propre renommage final. Repéré en direct (2026-08-11) sur des fichiers comme "Stromae -
     * Formidable-temp-61583.mp3" où le titre analysé restait "Formidable-temp-61583", introuvable
     * tel quel sur MusicBrainz. Statique et public pour la même raison que
     * {@link #stripLeadingNumericPrefix} ci-dessus.
     */
    public static String stripTrailingTempSuffix(String s) {
        return s.replaceAll("-temp-\\d+$", "").trim();
    }

    /**
     * Retire un marqueur de copie/doublon en fin de nom, ex. " (2)", " (3)" — ajouté par la
     * plupart des navigateurs/gestionnaires de téléchargement quand un fichier du même nom existe
     * déjà. Repéré en direct (2026-08-11) : "204 - Various Artists - I Believe (2).mp3" analysait
     * un titre "I Believe (2)", introuvable sur MusicBrainz, alors que la copie canonique "204 -
     * Various Artists - I Believe.mp3" (sans le marqueur) s'identifiait normalement. Uniquement la
     * forme parenthésée — jamais un simple nombre final ("Blink 182", "Level 42", "Sum 41" sont de
     * vrais noms d'artiste, pas des doublons) — pour rester sans ambiguïté.
     */
    public static String stripTrailingCopyMarker(String s) {
        return s.replaceAll("\\s*\\(\\d+\\)$", "").trim();
    }

    /**
     * Retire un long identifiant numérique final (8 chiffres ou plus), collé directement ou via
     * "_" — ex. "Zombie_639158979462958060" (un identifiant de téléchargement, probablement un
     * timestamp .NET/Windows FILETIME). Sans ce retrait, le nettoyage résolution/bitrate plus bas
     * (qui ne retire que 2-3 chiffres) ne consomme que la toute fin de ce long nombre, laissant un
     * résidu ("Zombie 639158979462958") collé au titre, introuvable sur MusicBrainz — repéré en
     * direct (2026-08-11) via un fichier passé de "Durée incohérente" (avant le correctif du repli
     * SongRec→AcoustID→texte) à "Non identifié" une fois ce repli tenté, la recherche texte
     * échouant sur ce titre corrompu. Seuil à 8 chiffres pour rester sans ambiguïté avec un vrai
     * numéro de piste/année (jusqu'à 6 chiffres déjà couvert par {@link #stripLeadingNumericPrefix}).
     */
    public static String stripTrailingLongId(String s) {
        return s.replaceAll("[_\\s]?\\d{8,}$", "").trim();
    }

    /**
     * Réduit un titre collé deux fois de suite SANS séparateur (ex. "Losing My Mind (Radio
     * Edit)Losing My Mind (Radio Edit)") à une seule copie — variante sans tiret du motif "Titre -
     * Titre" déjà géré dans parseFilename() (celui-là a un séparateur détectable, celui-ci non).
     * Comparaison stricte moitié==moitié : sans risque de faux positif, un vrai titre composé de
     * deux moitiés identiques collées est infinitésimalement improbable. Repéré en direct
     * (2026-08-13) sur plusieurs fichiers "Non identifié" au même schéma.
     */
    public static String collapseSelfConcatenatedTitle(String s) {
        if (s == null || s.length() % 2 != 0) return s;
        int half = s.length() / 2;
        String left = s.substring(0, half);
        String right = s.substring(half);
        return (!left.isBlank() && left.equalsIgnoreCase(right)) ? left : s;
    }

    /** Découpe une liste séparée par virgules (réglages capitalize.*) en Set trimé, sans entrées
     *  vides — lowercase=true pour la liste "mots en minuscule" (comparée en minuscule par
     *  TitleCaseFixer), false pour "mots en majuscule"/"préfixes" où la casse de sortie doit être
     *  préservée telle que saisie par l'utilisateur (ex. "U2", "Mc"). */
    private static java.util.Set<String> splitCsv(String csv, boolean lowercase) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            String v = part.trim();
            if (v.isEmpty()) continue;
            out.add(lowercase ? v.toLowerCase() : v);
        }
        return out;
    }

    /**
     * Tente de deviner artiste et titre depuis le nom de fichier.
     * Supporte "Artiste - Titre.mp3" et "Titre.mp3".
     */
    private String[] parseFilename(File f) {
        String name = f.getName().replaceFirst("\\.[^.]+$", "").trim(); // retirer extension
        // Retirer un suffixe "-temp-NNNNN" résiduel d'un outil externe — voir stripTrailingTempSuffix().
        name = stripTrailingTempSuffix(name);
        // Retirer un marqueur de copie/doublon " (2)", " (3)"… — voir stripTrailingCopyMarker().
        name = stripTrailingCopyMarker(name);
        // Retirer un long identifiant numérique final (8+ chiffres) — voir stripTrailingLongId().
        // AVANT le nettoyage résolution/bitrate ci-dessous : celui-ci ne retire que 2-3 chiffres et
        // laisserait sinon un résidu du long identifiant collé au titre.
        name = stripTrailingLongId(name);
        // Retirer résolution/bitrate en fin : "_320k", "(320)", "[HD]"...
        name = name.replaceAll("(?i)[_\\s]*[\\[(]?\\d{2,3}k?[\\])]?$", "").trim();
        // Underscores → espaces (ex: Baby_Don_T_Cry → Baby Don T Cry)
        name = name.replace('_', ' ').replaceAll("\\s{2,}", " ").trim();
        // Retirer préfixe numérique de piste : voir stripLeadingNumericPrefix() ci-dessus — la
        // variante avec tiret ("NN - Artiste - Titre", un des formats de nommage les plus
        // courants) manquait à l'origine ici : le "\\s" seul ne consommait que l'espace après le
        // numéro, laissant un "- " résiduel collé au DÉBUT de l'artiste ensuite extrait ci-dessous
        // (ex. "09 - Frankie Goes To Hollywood - Relax" → artiste analysé "- Frankie Goes To
        // Hollywood" au lieu de "Frankie Goes To Hollywood", introuvable tel quel sur
        // MusicBrainz). Seul un repli sur le nom de fichier (readTag() ayant déjà échoué/renvoyé
        // vide, cas typique d'un fichier sans tags exploitables comme un .dsf mal formé) passe par
        // ici — repéré en direct (2026-08-11) sur un lot de fichiers ainsi nommés, tous "Non
        // identifié" alors que le titre était pourtant lisible.
        name = stripLeadingNumericPrefix(name);
        int sep = name.indexOf(" - ");
        String artist = "", titlePart = name;
        if (sep > 0) {
            artist    = name.substring(0, sep).trim();
            titlePart = name.substring(sep + 3).trim();
            // Artiste lui aussi collé deux fois sans séparateur (ex. "Jaecjossjaecjoss - Oraleorale"
            // → artiste réel "Jaecjoss") — même mécanisme que collapseSelfConcatenatedTitle() sur le
            // titre, repéré sur le même fichier (2026-08-13).
            artist = collapseSelfConcatenatedTitle(artist);
            // Motif inverse "Artiste - NN - Titre" (ex. "The Beach Boys - 01 - Introducing The
            // Beach Boys") : le split ci-dessus laisse le numéro de piste collé au DÉBUT du titre.
            // Repéré en direct (2026-08-11) sur le même lot que le correctif ci-dessus.
            titlePart = stripLeadingNumericPrefix(titlePart);
            // Motif "Artiste - Compilation - Titre" (ex. "Lynda - 100 Hits Summer 2024 - Beau
            // Parleur") ou titre dupliqué "Artiste - Titre - Titre" (ex. "SANTI - Todo De Ti -
            // Todo De Ti") : un second tiret dans titlePart signale soit un nom de compilation/
            // album intercalé (repéré si ce premier segment contient une année, seul signal fiable
            // sans faux-positif sur un vrai qualificatif d'édition comme "Track - Radio Edit"), soit
            // ce même segment répété tel quel. Dans les deux cas, seul ce qui suit le second tiret
            // est le vrai titre — repéré en direct (2026-08-12) sur un lot de fichiers "Non
            // identifié" au format "NN - Artiste - NomCompilation - Titre".
            int innerSep = titlePart.indexOf(" - ");
            if (innerSep > 0) {
                String left  = titlePart.substring(0, innerSep).trim();
                String right = titlePart.substring(innerSep + 3).trim();
                if (left.equalsIgnoreCase(right) || left.matches(".*(19|20)\\d{2}.*")) {
                    titlePart = right;
                }
            }
            // Motif "Artiste - Album - Artiste - Titre" (l'artiste réapparaît PLUS LOIN dans
            // titlePart, pas juste au niveau du split immédiat ci-dessus) — ex. "David Bowie -
            // Heathen - David Bowie - Heathen (The Rays).opus.mp3", typique d'une conversion
            // .opus→.mp3 qui concatène artiste+album+artiste+titre. Repéré en direct (2026-08-14).
            String artistRepeatMarker = " - " + artist + " - ";
            int dupArtistIdx = titlePart.toLowerCase().indexOf(artistRepeatMarker.toLowerCase());
            if (!artist.isBlank() && dupArtistIdx >= 0) {
                titlePart = titlePart.substring(dupArtistIdx + artistRepeatMarker.length()).trim();
            }
            titlePart = collapseSelfConcatenatedTitle(titlePart);
        } else {
            titlePart = collapseSelfConcatenatedTitle(titlePart);
        }
        // Supprimer le préfixe "Unknown Artist-" ou "Unknown-" pour récupérer le vrai artiste
        // Ex: "Unknown Artist-Maroon 5" → "Maroon 5"
        if (!artist.isBlank() && artist.toLowerCase().startsWith("unknown") && artist.contains("-")) {
            String real = artist.substring(artist.indexOf('-') + 1).trim();
            if (!real.isBlank() && !isGenericTag(real)) artist = real;
        }
        return new String[]{ artist, titlePart };
    }

}
