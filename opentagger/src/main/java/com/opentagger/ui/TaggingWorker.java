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
        this.entries     = entries;
        this.useAcoustId = useAcoustId;
        this.onProgress  = onProgress;
        this.onUpdate    = onUpdate;
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
                        if (!folder.isBlank()) {
                            java.nio.file.Path curPath = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
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
                        if (!folder.isBlank()) {
                            java.nio.file.Path curPath = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
                            java.nio.file.Path moved = FileRenamer.moveToFolder(curPath, java.nio.file.Paths.get(folder));
                            if (moved != null) entry.currentPath = moved;
                        }
                    } catch (Exception ex) {
                        log(I18n.t("  déplacement (non tagué) échoué: %s", ex.getMessage()));
                    }
                }

                setProgress((done.incrementAndGet() * 100) / total);
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

            // Vérifier que le fichier existe avant tout traitement
            if (!fichier.exists()) {
                entry.status  = FileEntry.Status.ERROR;
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
                        log("  transcoded → " + transcoded.getFileName());
                    }
                } catch (Exception txEx) {
                    log(I18n.t("  transcode WARN: %s — poursuite sans transcodage", txEx.getMessage()));
                }
            }

            log(I18n.t("▶ START  %s", fichier.getName()));

            log(I18n.t("  findTags..."));
            List<TagInfo> results = findTags(fichier, entry.current, entry.forceReidentify, mb, acoustId, lastFm, cache);
            entry.forceReidentify = false;
            mb.setPreferredAlbum(""); // reset après findTags — clusterAlbums ne doit pas en bénéficier
            log(I18n.t("  findTags → %s résultat(s)%s", results.size(),
                results.isEmpty() ? "" : " score=" + results.get(0).score));

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty()) {
                entry.status  = FileEntry.Status.SKIPPED;
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

            if (best.score < seuil) {
                entry.candidates = results;
                entry.status  = FileEntry.Status.SKIPPED;
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
            TagInfo durationOk = null;
            for (TagInfo candidate : results) {
                if (candidate.score < seuil) break; // triés par score décroissant : la suite ne fera que pire
                candidate.durationSec = entry.current.durationSec;
                if (!FileEntry.isDurationMismatch(entry.current.durationSec, candidate.mbDurationSec)) {
                    durationOk = candidate;
                    break;
                }
            }
            if (durationOk == null) {
                entry.candidates = results;
                entry.status = FileEntry.Status.SKIPPED;
                entry.durationMismatch = true;
                entry.message = I18n.t("Durée incohérente : fichier %s vs MusicBrainz %s (%s)",
                    FileTableModel.formatDuration(entry.current.durationSec),
                    FileTableModel.formatDuration(best.mbDurationSec), best.title)
                        + videoHintIfAny(fichier);
                log(I18n.t("  SKIPPED durée incohérente (%ds vs %ds)",
                    entry.current.durationSec, best.mbDurationSec));
                return;
            }
            best = durationOk;

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
            if (Config.get().preserveCompilationAlbum()) {
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
            entry.status  = FileEntry.Status.ERROR;
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

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }

    // ── Résolution des tags — avec cache SQLite ───────────────────────────────

    private List<TagInfo> findTags(File fichier, TagInfo existingTags, boolean forceReidentify,
                                    MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm,
                                    MetadataCache cache) throws Exception {
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

        // 0.7. Compromis vitesse optionnel (skipSongRecOnConfidentMb, off par défaut) : si le
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

        // 1. SongRec (Shazam) — empreinte audio, identifie la musique commerciale même avec
        //    de faux tags existants. Placé AVANT AcoustID pour être la source principale.
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
                    // journal — le fichier passait directement à l'étape suivante (AcoustID) sans
                    // que rien n'explique pourquoi, restant PENDING sans indice si les étapes
                    // suivantes échouaient aussi.
                    log(I18n.t("  SongRec ✗ %s", SongRecClient.lastFailureReason()));
                }
                if (srOk) {
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
                        }
                    }
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
                } else {
                    log(I18n.t("  SongRec → rien trouvé"));
                }
            } catch (Exception e) {
                log(I18n.t("  SongRec WARN: %s", e.getMessage()));
            }
        }

        // 1. MB Recording ID déjà présent → lookup direct (rapide + précis)
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

        // 2. AcoustID (fingerprint)
        if (useAcoustId) {
            // ignore_existing : si un AcoustID est déjà dans les tags et qu'on ne force pas, on skip
            boolean hasExistingId = !readTag(fichier, FieldKey.ACOUSTID_ID).isBlank();
            if (!hasExistingId || Config.get().ignoreExistingFingerprints()) {
                List<TagInfo> r = acoustId.identify(fichier);
                if (!r.isEmpty() && acoustIdResultPlausible(fichier, r.get(0), forceReidentify)) {
                    lastFindTagsSource.set(MetadataCache.SOURCE_ACOUSTID);
                    return r;
                }
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
        // Lire l'album maintenant (utilisé en fallback plus bas quand artiste manque)
        String existingAlbum = cleanSearchTerm(readTag(fichier, FieldKey.ALBUM));

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
     * Tente de deviner artiste et titre depuis le nom de fichier.
     * Supporte "Artiste - Titre.mp3" et "Titre.mp3".
     */
    private String[] parseFilename(File f) {
        String name = f.getName().replaceFirst("\\.[^.]+$", "").trim(); // retirer extension
        // Retirer résolution/bitrate en fin : "_320k", "(320)", "[HD]"...
        name = name.replaceAll("(?i)[_\\s]*[\\[(]?\\d{2,3}k?[\\])]?$", "").trim();
        // Underscores → espaces (ex: Baby_Don_T_Cry → Baby Don T Cry)
        name = name.replace('_', ' ').replaceAll("\\s{2,}", " ").trim();
        // Retirer préfixe numérique de piste : "04 04 " ou "04 "
        name = name.replaceAll("^(?:\\d{2,3}\\s)+", "").trim();
        int sep = name.indexOf(" - ");
        String artist = "", titlePart = name;
        if (sep > 0) {
            artist    = name.substring(0, sep).trim();
            titlePart = name.substring(sep + 3).trim();
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
