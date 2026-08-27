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
import java.util.function.Consumer;
import java.util.function.BiConsumer;
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
    private final AcoustIdClient           acoustId         = new AcoustIdClient();
    private final SongRecClient            songRec          = new SongRecClient();
    // AudD (fallback reconnaissance audio après SongRec) — jusqu'à ce correctif, ce champ était
    // un AudioRecognitionChain jamais appelé nulle part : le champ "Jeton API AudD" des Réglages
    // n'avait donc aucun effet malgré son apparence de fonctionnalité active.
    private final AudDClient               audd             = new AudDClient();

    // Source d'identification du dernier findTags() — utilisée pour enregistrer le niveau de confiance
    // ThreadLocal : scratch par fichier en cours de traitement (pas un état de client partagé) —
    // avec plusieurs fichiers traités en parallèle sur la même instance de TaggingWorker, un champ
    // simple ferait fuiter la source d'un fichier vers un autre traité au même moment.
    private final ThreadLocal<String> lastFindTagsSource =
            ThreadLocal.withInitial(() -> MetadataCache.SOURCE_TEXT);
    private final DiscogsClient      discogs   = new DiscogsClient();
    private final LastFmClient       lastFm    = new LastFmClient();
    private final LocalCorrector     corrector = new LocalCorrector();
    private final MetadataCache      cache     = new MetadataCache();
    private final BpmDetector        bpmDet    = new BpmDetector();
    private final EssentiaClient     essentia  = new EssentiaClient();
    private final LyricsClient       lyrics    = new LyricsClient();

    private final boolean bpmEnabled      = BpmDetector.isAvailable();
    private final boolean essentiaEnabled = EssentiaClient.isOnPath();
    private final boolean rgEnabled       = Config.get().replayGainEnabled() && ReplayGainAnalyzer.isAvailable();
    private final ReplayGainAnalyzer replayGain = rgEnabled ? new ReplayGainAnalyzer() : null;
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
            queue.sort((a, b) -> incompletenessScore(b.current) - incompletenessScore(a.current));
            long incomplete = queue.stream().filter(e -> incompletenessScore(e.current) >= 4).count();
            if (incomplete > 0)
                onProgress.accept(I18n.t("Priorité : %s fichier(s) très incomplet(s) traité(s) en premier", incomplete));
        } else {
            queue = entries;
        }

        int total = queue.size();
        AtomicInteger done = new AtomicInteger(0);
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

        int threads = Math.max(1, Config.get().num("batch.threads", 6));
        pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();
        int startIdx = done.get();

        for (int i = 0; i < toProcess.size(); i++) {
            if (isCancelled()) break;
            final FileEntry entry  = toProcess.get(i);
            final int       fileIdx = startIdx + i + 1;
            futures.add(pool.submit(() -> {
                if (isCancelled()) return;

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
                if (entry.durationMismatch && Config.get().durationMismatchMoveEnabled()) {
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
                if (Config.get().skippedMoveEnabled() && !entry.durationMismatch
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
                setProgress((doneCount * 100) / total);
                onFileProgress.accept(doneCount, total);
                publish(entry);
            }));
        }

        WorkerHub.awaitAll(pool, futures, WorkerHub.defaultFutureTimeoutSec());

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
    private void processEntry(FileEntry entry, Consumer<String> step,
                               MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm,
                               MetadataCache cache) {
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
                    step.accept(I18n.t("transcodage → %s…", fmt.id.toUpperCase()));
                    java.nio.file.Path transcoded = new com.opentagger.AudioTranscoder()
                        .transcode(entry.currentPath != null ? entry.currentPath
                                   : entry.file.toPath(),
                                   fmt, Config.get().transcodeBitrate(),
                                   Config.get().transcodeDeleteSource());
                    if (transcoded != null) {
                        entry.currentPath = transcoded;
                        fichier = transcoded.toFile();
                        CURRENT_FILE.set(fichier.getName());
                        log("  transcoded → " + transcoded.getFileName());
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

            log(I18n.t("  findTags..."));
            List<TagInfo> results = findTags(fichier, entry.current, entry.forceReidentify, mb, acoustId, lastFm, cache, groupKey);
            entry.forceReidentify = false;
            mb.setPreferredAlbum(""); // reset après findTags — clusterAlbums ne doit pas en bénéficier
            log(I18n.t("  findTags → %s résultat(s)%s", results.size(),
                results.isEmpty() ? "" : " score=" + results.get(0).score));

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty()) {
                entry.status     = FileEntry.Status.SKIPPED;
                entry.skipReason = com.opentagger.model.SkipReason.NOT_IDENTIFIED;
                entry.message = I18n.t("Non identifié") + videoHintIfAny(fichier);
                log(I18n.t("  SKIPPED (non identifié)"));
                return;
            }

            TagInfo best = results.get(0);
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
                entry.candidates = results;
                entry.status     = FileEntry.Status.SKIPPED;
                entry.skipReason = com.opentagger.model.SkipReason.LOW_SCORE;
                entry.message = I18n.t("Score %s%% < %s%% — %s candidat(s)", best.score, seuil, results.size())
                        + videoHintIfAny(fichier);
                log(I18n.t("  SKIPPED score trop bas"));
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
            if (!unverifiedFallback) {
                for (TagInfo candidate : results) {
                    if (candidate.score < seuil) break; // triés par score décroissant : la suite ne fera que pire
                    candidate.durationSec = entry.current.durationSec;
                    if (!FileEntry.isDurationMismatch(entry.current.durationSec, candidate.mbDurationSec)) {
                        durationOk = candidate;
                        break;
                    }
                }
            }
            if (durationOk == null) {
                entry.candidates = results;
                entry.status = FileEntry.Status.SKIPPED;
                entry.durationMismatch = true;
                entry.skipReason = com.opentagger.model.SkipReason.DURATION_MISMATCH;
                entry.message = I18n.t("Durée incohérente : fichier %s vs MusicBrainz %s (%s)",
                    FileTableModel.formatDuration(entry.current.durationSec),
                    FileTableModel.formatDuration(best.mbDurationSec), best.title)
                        + videoHintIfAny(fichier);
                log(I18n.t("  SKIPPED durée incohérente (%ds vs %ds)",
                    entry.current.durationSec, best.mbDurationSec));
                return;
            }
            best = durationOk;

            // Cohérence de groupe (voir groupPinnedRelease) : cette piste vient de trouver une release
            // à haute confiance (score ≥ seuil ET durée cohérente, validé juste au-dessus) — la fixer
            // pour le reste du groupe si aucune autre piste ne l'a déjà fait. putIfAbsent : la première
            // piste gagne : au pire quelques pistes suivent l'un ou l'autre choix si deux pistes du
            // même groupe sont traitées en parallèle et trouvent chacune une release différente, tous
            // deux déjà validés individuellement — jamais pire que le comportement sans épinglage.
            if (Config.get().discIdMatchingEnabled() && !best.releaseMbid.isBlank()) {
                groupPinnedRelease.putIfAbsent(groupKey, best.releaseMbid);
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
                // MB n'a pas retourné de release compilation → restaurer contexte original
                if (origWasCompilation && !origAlbum.isBlank() && !"1".equals(best.isCompilation)) {
                    log(I18n.t("  compilation restaurée : album='%s' albumArtist='%s'",
                        origAlbum, origAlbumArtist));
                    com.opentagger.CompilationRestoreLog.record(
                        fichier.getAbsolutePath(), lastFindTagsSource.get(),
                        origAlbum, origAlbumArtist.isBlank() ? Config.get().vaName() : origAlbumArtist,
                        best.album, best.albumArtist);
                    best.album         = origAlbum;
                    best.albumArtist   = origAlbumArtist.isBlank()
                        ? Config.get().vaName() : origAlbumArtist;
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
            try { lastFm.enrichArtistUrls(best, cache); } catch (Exception ignored) {}

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
            if (rgEnabled) {
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
            entry.skipReason = com.opentagger.model.SkipReason.ERROR_GENERIC;
            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            entry.message = (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                    + videoHintIfAny(f);
            log(I18n.t("  ✗ ERROR %s : %s", entry.filename(), entry.message));
        }
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

    /** Piste MB correspondant au numéro TRACK du fichier — {@code null} si le tag est absent/
     *  illisible ou qu'aucune piste de la release ne porte ce numéro. */
    private MusicBrainzClient.ReleaseTrack findTrackInRelease(MusicBrainzClient.ReleaseTracklist tl, File fichier) {
        String trackTag = readTag(fichier, FieldKey.TRACK);
        if (trackTag == null || trackTag.isBlank()) return null;
        int tn;
        try { tn = Integer.parseInt(trackTag.split("/")[0].trim()); }
        catch (NumberFormatException e) { return null; }
        for (MusicBrainzClient.ReleaseTrack t : tl.tracks()) if (t.trackNo() == tn) return t;
        return null;
    }

    /** Repli de {@link #findTrackInRelease} pour groupPinnedRelease (voir findTags() étape 0.65) :
     *  contrairement au TOC (dossier "album complet" garanti par construction), une piste épinglée
     *  par une AUTRE piste du groupe n'a pas forcément de tag TRACK exploitable (fichier encore
     *  brut) — repli sur la similarité de titre contre chaque piste de la tracklist, seuil déjà
     *  utilisé ailleurs dans ce fichier pour la même famille de décision (voir son appelant). */
    private MusicBrainzClient.ReleaseTrack findTrackInReleaseByTitle(MusicBrainzClient.ReleaseTracklist tl, File fichier) {
        String titleTag = cleanSearchTerm(readTag(fichier, FieldKey.TITLE));
        if (titleTag.isBlank() || isGenericTag(titleTag)) return null;
        MusicBrainzClient.ReleaseTrack bestTrack = null;
        double bestSim = 0;
        for (MusicBrainzClient.ReleaseTrack t : tl.tracks()) {
            double sim = TrackMatcher.titleSimilarity(titleTag.toLowerCase(), t.title().toLowerCase());
            if (sim > bestSim) { bestSim = sim; bestTrack = t; }
        }
        return bestSim >= Config.get().trackMatchingThreshold() ? bestTrack : null;
    }

    // ── Résolution des tags — avec cache SQLite ───────────────────────────────

    private List<TagInfo> findTags(File fichier, TagInfo existingTags, boolean forceReidentify,
                                    MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm,
                                    MetadataCache cache, String groupKey) throws Exception {
        // 0-pre. Réparer les M4A avec structure mdat<moov non lisible par jaudiotagger
        if (TagWriter.repairM4aIfNeeded(fichier)) log(I18n.t("  M4A réparé OK"));

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
                    }
                    // sr == null (rien reconnu par SongRec) : ne contredit pas, tags existants gardés.
                } catch (Exception e) {
                    // SongRec cassé/indisponible ponctuellement : ne pas bloquer sur une panne
                    // d'infra — comportement identique à avant ce correctif dans ce cas précis.
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
        if (Config.get().discIdMatchingEnabled() && fichier.getParentFile() != null) {
            Path folder = fichier.getParentFile().toPath();
            if (!discIdFailedFolders.contains(folder)) {
                MusicBrainzClient.ReleaseTracklist tl =
                        discIdCache.computeIfAbsent(folder, f -> matchFolderByToc(f, mb));
                if (tl == null) {
                    discIdFailedFolders.add(folder);
                } else {
                    MusicBrainzClient.ReleaseTrack myTrack = findTrackInRelease(tl, fichier);
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
                        t.track            = String.valueOf(myTrack.trackNo());
                        t.trackTotal       = String.valueOf(myTrack.trackTotal());
                        if (myTrack.disc() > 0) t.discNo = String.valueOf(myTrack.disc());
                        t.country          = tl.country();
                        t.barcode          = tl.barcode();
                        t.releaseStatus    = tl.releaseStatus();
                        t.label            = tl.label();
                        t.catalogNo        = tl.catalogNo();
                        t.script           = tl.script();
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

        // 0.65. Cohérence de groupe — voir groupPinnedRelease : une AUTRE piste du même groupe (même
        // clé que la vue arborescence — voir AlbumGrouping.key()) a déjà trouvé une release à haute
        // confiance PLUS TÔT dans ce lot. Ne s'applique QUE si le TOC ci-dessus n'a rien donné pour
        // CE fichier (dossier pas assez complet/track manquant/déjà en échec) — le TOC reste toujours
        // prioritaire, checksum sur tout l'album donc plus fiable qu'un simple matching titre/piste
        // contre une release trouvée par une autre piste. Contrairement au TOC, s'applique aussi aux
        // groupes éparpillés sur plusieurs dossiers physiques (regroupement par tag album, pas par
        // dossier).
        if (Config.get().discIdMatchingEnabled() && groupKey != null) {
            String pinnedMbid = groupPinnedRelease.get(groupKey);
            if (pinnedMbid != null && !pinnedTracklistFailed.contains(pinnedMbid)) {
                MusicBrainzClient.ReleaseTracklist tl = pinnedTracklistCache.computeIfAbsent(pinnedMbid, mbid -> {
                    try { return mb.lookupRelease(mbid); } catch (Exception e) { return null; }
                });
                if (tl == null) {
                    pinnedTracklistFailed.add(pinnedMbid);
                } else {
                    MusicBrainzClient.ReleaseTrack myTrack = findTrackInRelease(tl, fichier);
                    if (myTrack == null) myTrack = findTrackInReleaseByTitle(tl, fichier);
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
                        t.track            = String.valueOf(myTrack.trackNo());
                        t.trackTotal       = String.valueOf(myTrack.trackTotal());
                        if (myTrack.disc() > 0) t.discNo = String.valueOf(myTrack.disc());
                        t.country          = tl.country();
                        t.barcode          = tl.barcode();
                        t.releaseStatus    = tl.releaseStatus();
                        t.label            = tl.label();
                        t.catalogNo        = tl.catalogNo();
                        t.script           = tl.script();
                        if (tl.isCompilation()) t.isCompilation = "1";
                        if (myTrack.lengthMs() > 0) t.mbDurationSec = myTrack.lengthMs() / 1000;
                        // Score < TOC (95) : matching titre/numéro contre une release VÉRIFIÉE par une
                        // autre piste, pas un checksum sur ce fichier précis — reste au-dessus du seuil
                        // par défaut (match.min_score_auto=90) et bénéficie en plus du garde-fou durée
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

        // 1. AcoustID (fingerprint) — rapide et parallélisable (aucune limite de débit globale,
        //    contrairement à SongRec/SHAZAM_GATE ci-dessous) : tenté en premier pour cette raison.
        //    Auparavant après SongRec ("source principale" pour la précision) — réorganisé le
        //    2026-08-15 : SongRec sérialise TOUT appel à un seul à la fois dans toute l'appli
        //    (evite le 429 Shazam du 2026-08-09), donc le placer en premier faisait payer ce
        //    goulot sur CHAQUE fichier, même ceux qu'AcoustID aurait suffi à identifier seul. Rien
        //    n'est perdu en théorie : un fichier qu'AcoustID ne reconnaît pas retombe exactement
        //    sur SongRec comme avant, juste dans l'autre ordre.
        if (useAcoustId) {
            // ignore_existing : si un AcoustID est déjà dans les tags et qu'on ne force pas, on skip
            boolean hasExistingId = !readTag(fichier, FieldKey.ACOUSTID_ID).isBlank();
            if (!hasExistingId || Config.get().ignoreExistingFingerprints()) {
                List<TagInfo> r = acoustId.identify(fichier);
                if (!r.isEmpty() && acoustIdResultPlausible(fichier, r.get(0), forceReidentify)) {
                    if (existingTags.durationSec > 0
                            && FileEntry.isDurationMismatch(existingTags.durationSec, r.get(0).mbDurationSec)) {
                        // Même garde-fou que pour SongRec plus bas dans cette méthode : une
                        // empreinte AcoustID à confiance élevée (le bypass juste au-dessus, ou une
                        // similarité d'artiste acceptable) peut malgré tout pointer vers le mauvais
                        // enregistrement — repéré en direct 2026-08-11 sur "1-08 Crank It Up.mp3"
                        // (tags existants corrects : David Guetta Feat. Akon – Crank It Up),
                        // matché par AcoustID à "Dreams", rejeté par la durée mais qui s'arrêtait
                        // là avant ce correctif au lieu de tenter la recherche texte ci-dessous —
                        // pourtant la meilleure chance ici, les tags existants étant fiables.
                        log(I18n.t("  AcoustID : durée incohérente (%ds vs %ds, %s – %s) → poursuite vers le texte",
                                existingTags.durationSec, r.get(0).mbDurationSec, r.get(0).artist, r.get(0).title));
                    } else {
                        lastFindTagsSource.set(MetadataCache.SOURCE_ACOUSTID);
                        return r;
                    }
                }
            }
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

        // 2. SongRec (Shazam) — repli si AcoustID n'a rien trouvé : empreinte audio, identifie la
        //    musique commerciale même avec de faux tags existants, mais sérialisée à un seul appel
        //    à la fois dans toute l'appli (SHAZAM_GATE, voir plus haut) — volontairement en second
        //    maintenant pour ne payer ce goulot que sur les fichiers qu'AcoustID n'a pas su résoudre.
        if (SongRecClient.isAvailable()) {
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
                if (srOk) {
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
                    if (!srMb.isEmpty() && srMb.get(0).score >= 50
                            && !(existingTags.durationSec > 0
                                 && FileEntry.isDurationMismatch(existingTags.durationSec, srMb.get(0).mbDurationSec))) {
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
                    } else if (!srMb.isEmpty() && srMb.get(0).score >= 50) {
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
                        if (!srMbClean.isEmpty() && srMbClean.get(0).score >= 50
                                && !(existingTags.durationSec > 0
                                     && FileEntry.isDurationMismatch(existingTags.durationSec, srMbClean.get(0).mbDurationSec))) {
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
                        } else if (!srMbClean.isEmpty() && srMbClean.get(0).score >= 50) {
                            // Même garde-fou que srMb ci-dessus.
                            log(I18n.t("  SongRec→MB (titre nettoyé) : durée incohérente (%ds vs %ds) → poursuite",
                                    existingTags.durationSec, srMbClean.get(0).mbDurationSec));
                            songRecDurationSuspect = true;
                        }
                    }
                    if (!songRecDurationSuspect) {
                        // MB n'a rien enrichi : garder le résultat SongRec seul
                        sr.score = 85;
                        log(I18n.t("  SongRec seul (MB sans match): %s – %s", sr.artist, sr.title));
                        // Cascade centralisée (au lieu d'une copie inline qui divergerait silencieusement
                        // si TagEnrichment.enrichGenre change) — de toute façon re-noopée sans risque à
                        // l'étape enrichGenre() plus loin dans processEntry() si le genre est déjà rempli.
                        TagEnrichment.enrichGenre(sr, discogs, lastFm, cache);
                        TagEnrichment.enrichClassicalWork(sr, mb, cache);
                        lastFindTagsSource.set(MetadataCache.SOURCE_SONGREC);
                        return List.of(sr);
                    }
                    // songRecDurationSuspect : ne pas retourner sr non plus (voir son commentaire
                    // plus haut) — on laisse tomber vers la recherche texte ci-dessous (AcoustID a
                    // déjà été tenté avant SongRec, voir plus haut dans cette méthode).
                } else {
                    log(I18n.t("  SongRec → rien trouvé"));
                }
            } catch (Exception e) {
                log(I18n.t("  SongRec WARN: %s", e.getMessage()));
            }
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
                if (TagEnrichment.hasNonLatinChars(fn[0]) || TagEnrichment.hasNonLatinChars(fn[1])) {
                    nonLatinInput = true;
                    log(I18n.t("  nom de fichier non-Latin → SongRec en priorité"));
                } else {
                    artist = fn[0]; title = fn[1];
                    // Appliquer isGenericTag sur l'artiste du nom de fichier aussi ("0", "01", etc.)
                    if (isGenericTag(artist)) artist = "";
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

        // NOTE: Le fallback "titre seul" est désactivé — trop de faux positifs.

        // 5b-bis. Fallback titre + album : artiste vide mais album connu dans les tags existants
        // Typique : fichier avec artist="0"/vide mais title+album corrects (ex: M4A mal encodé)
        if (!nonLatinInput && results.isEmpty() && artist.isBlank()
                && !title.isBlank() && !existingAlbum.isBlank()) {
            log(I18n.t("  MB fallback titre+album: '%s' / '%s'", title, existingAlbum));
            results = mb.searchRecording("", title, existingAlbum);
            log(I18n.t("  MB titre+album → %s résultat(s)", results.size()));
            if (!results.isEmpty()) {
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

        // 5e. AudD — dernier recours après SongRec : algorithme de reconnaissance différent,
        // utile quand SongRec ne reconnaît pas le morceau (cf. README : AcoustID → SongRec → AudD).
        if (AudDClient.isAvailable()) {
            log(I18n.t("  AudD fallback..."));
            try {
                TagInfo ad = audd.recognize(fichier);
                if (ad != null) {
                    log(I18n.t("  AudD → %s – %s", ad.artist, ad.title));
                    List<TagInfo> mbResults = mb.searchRecording(ad.artist, ad.title);
                    if (!mbResults.isEmpty() && mbResults.get(0).score >= 50) {
                        TagInfo mbr = mbResults.get(0);
                        // AudD comble ce que MB n'a pas (il fournit aussi l'ISRC Spotify)
                        if (mbr.album.isBlank()   && !ad.album.isBlank())   mbr.album   = ad.album;
                        if (mbr.year.isBlank()    && !ad.year.isBlank())    mbr.year    = ad.year;
                        if (mbr.isrc.isBlank()    && !ad.isrc.isBlank())    mbr.isrc    = ad.isrc;
                        mbr.score = 90;
                        log(I18n.t("  AudD+MB → %s – %s [%s]", mbr.artist, mbr.title, mbr.album));
                        return List.of(mbr);
                    }
                    if (ad.artistMbid.isBlank()) {
                        try {
                            String amid = mb.searchArtistMbid(ad.artist);
                            if (!amid.isBlank()) { ad.artistMbid = amid; log(I18n.t("  artistMbid←MB: %s", amid)); }
                        } catch (Exception ignored) {}
                    }
                    ad.score = 85;
                    log(I18n.t("  AudD seul (MB non confirmé) → %s – %s", ad.artist, ad.title));
                    return List.of(ad);
                }
                log(I18n.t("  AudD → rien trouvé"));
            } catch (Exception e) {
                log(I18n.t("  AudD erreur: %s", e.getMessage()));
                AudDClient.maybePromptTokenUpdate(e.getMessage());
            }
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
            String ytUrl = YouTubeOEmbedClient.extractUrl(readTag(fichier, FieldKey.COMMENT));
            TagInfo ytInfo = ytUrl != null ? YouTubeOEmbedClient.fetch(ytUrl) : null;
            String bcArtist = artist, bcTitle = title;
            if (ytInfo != null && !ytInfo.title.isBlank()) {
                if (!ytInfo.artist.isBlank()) bcArtist = ytInfo.artist;
                bcTitle = ytInfo.title;
            }
            String guessUrl = BandcampClient.guessTrackUrl(bcArtist, bcTitle);
            if (guessUrl != null) {
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
                        log(I18n.t("  Bandcamp (URL devinée, vérifiée) → %s – %s [%s]",
                                bc.artist(), bc.title(), guessUrl));
                        lastFindTagsSource.set(MetadataCache.SOURCE_BANDCAMP);
                        return List.of(bcInfo);
                    }
                } catch (Exception e) {
                    log(I18n.t("  Bandcamp devinette échouée/non trouvée : %s", e.getMessage()));
                }
            }
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
            if (!fbArtist.isBlank() && !fbTitle.isBlank()) {
                TagInfo fallback = new TagInfo();
                fallback.artist      = fbArtist;
                fallback.title       = fbTitle;
                fallback.albumArtist = fbArtist;
                if (!existingAlbum.isBlank()) fallback.album = existingAlbum;
                if (existingTags != null) {
                    if (!existingTags.year.isBlank())  fallback.year  = existingTags.year;
                    if (!existingTags.genre.isBlank()) fallback.genre = existingTags.genre;
                }
                fallback.score = 50;
                log(I18n.t("  Repli tags existants (aucune méthode confirmée) : %s – %s", fbArtist, fbTitle));
                lastFindTagsSource.set(MetadataCache.SOURCE_UNVERIFIED_TAGS);
                return List.of(fallback);
            }
        }
        return results;
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
        if (forceReidentify) return true;
        // Empreinte très forte (même seuil "confiance excellente" qu'AcoustIdClient.
        // fetchBestFromMusicBrainz()) : on fait confiance à l'audio plutôt qu'au tag déjà présent,
        // qui peut lui-même être faux à la source (constaté en direct 2026-08-09 : un fichier tagué
        // "Double Vision - Knockin" par un outil tiers, dont l'empreinte AcoustID — confirmée
        // identique avant/après par une ré-analyse indépendante — pointait en réalité vers "Le
        // Manège Enchanté - Remix 93" ; la comparaison ci-dessous rejetait ce match correct
        // uniquement parce qu'il ne ressemblait pas au tag existant erroné). En dessous de ce
        // seuil, on garde la vérification par similarité : elle reste utile contre un vrai faux
        // positif à confiance faible/moyenne (cf. le cas "Rasputin" de SongRec, même session).
        if (candidate.acoustidConfidence >= 0.9) return true;
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

    /** Retourne true si le tag est générique/inutile pour une recherche. */
    private static final java.util.Set<String> GENERIC_TITLES_WITHOUT_ARTIST = java.util.Set.of(
        "intro", "outro", "skit", "interlude", "bonus", "hidden track",
        "reprise", "instrumental", "medley", "overture", "prelude",
        "remix", "edit", "version", "live", "acoustic"
    );

    private boolean isGenericTag(String s) {
        if (s == null || s.isBlank()) return true;
        String low = s.trim().toLowerCase();
        // Tags par défaut des encodeurs/téléchargeurs
        if (low.matches("unknown artist|unknown|artist|artiste|musique|musiques|music|inconnu|"
                       + "various|various artists|no artist|piste \\d+|track \\d+|"
                       + "titre|title|untitled|inconnu - -.*")) return true;
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
        return false;
    }

    /** Nettoie les artefacts techniques courants d'un terme de recherche. */
    private String cleanSearchTerm(String s) {
        if (s == null) return "";
        return s
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
        if (best.score > 0 && best.score < seuil + 20)
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
    private static int incompletenessScore(com.opentagger.model.TagInfo t) {
        if (t == null) return 10;
        int s = 0;
        if (t.title.isBlank())  s += 3;
        if (t.artist.isBlank()) s += 3;
        if (t.album.isBlank())  s += 2;
        if (t.year.isBlank())   s += 1;
        if (t.genre.isBlank())  s += 1;
        return s;
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

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
