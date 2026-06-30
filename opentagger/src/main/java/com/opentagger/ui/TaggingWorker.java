package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import java.util.Map;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/**
 * SwingWorker qui traite les fichiers sélectionnés en arrière-plan
 * et notifie l'UI après chaque fichier via publish/process.
 *
 * Pipeline complet :
 *  0. Cache SQLite   → résultat instantané si déjà connu (après forceRetag, le cache est vidé)
 *  1. SongRec/Shazam → empreinte audio, source principale — identifie même avec de faux tags
 *  2. AcoustID       → fingerprint alternatif (optionnel)
 *  3. MusicBrainz    → recherche par texte + enrichissement des résultats SongRec/AcoustID
 *  4. LocalCorrector → corrections scripts Jaikoz (5 scripts)
 *  6. Discogs        → genres
 *  7. Last.fm        → genres (fallback)
 *  8. BPM            → détection locale (ffmpeg)
 *  9. Essentia       → mood/key (optionnel, si binaire installé)
 * 10. FanArt.tv      → pochette
 * 11. TagWriter      → écriture tags
 * 11. FileRenamer    → renommage (optionnel)
 */
public class TaggingWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>     entries;
    private final int                 maskIndex;
    private final boolean             useAcoustId;
    private final Consumer<String>    onProgress;
    private final Consumer<FileEntry> onUpdate;

    private final MusicBrainzClient        mb               = new MusicBrainzClient();
    private final AcoustIdClient           acoustId         = new AcoustIdClient();
    private final SongRecClient            songRec          = new SongRecClient();
    private final AudioRecognitionChain    recognitionChain = new AudioRecognitionChain();

    // Source d'identification du dernier findTags() — utilisée pour enregistrer le niveau de confiance
    private String  lastFindTagsSource   = MetadataCache.SOURCE_TEXT;
    private final DiscogsClient      discogs   = new DiscogsClient();
    private final LastFmClient       lastFm    = new LastFmClient();
    private final FanArtClient       fanArt    = new FanArtClient();
    private final CaaClient          caa       = new CaaClient();
    private final LocalCorrector     corrector = new LocalCorrector();
    private final TagWriter          writer    = new TagWriter();
    private final FileRenamer        renamer   = new FileRenamer();
    private final MetadataCache      cache     = new MetadataCache();
    private final BpmDetector        bpmDet    = new BpmDetector();
    private final EssentiaClient     essentia  = new EssentiaClient();
    private final LyricsClient       lyrics    = new LyricsClient();
    private final MusicBrainzOAuth   mbOauth   = new MusicBrainzOAuth();

    private final boolean bpmEnabled      = BpmDetector.isAvailable();
    private final boolean essentiaEnabled = EssentiaClient.isOnPath();
    private final boolean rgEnabled       = Config.get().replayGainEnabled() && ReplayGainAnalyzer.isAvailable();
    private final ReplayGainAnalyzer replayGain = rgEnabled ? new ReplayGainAnalyzer() : null;
    private final TaggerScript taggerScript = new TaggerScript();
    // Cache alias artiste : artistMbid → nom Latin (évite un appel MB par fichier)
    private final java.util.Map<String, String> aliasCache = new java.util.concurrent.ConcurrentHashMap<>();

    // ── Journal de corrections ────────────────────────────────────────────────
    private final com.opentagger.CorrectionLog correctionLog = new com.opentagger.CorrectionLog();

    public TaggingWorker(List<FileEntry> entries, boolean useAcoustId, int maskIndex,
                         Consumer<String> onProgress, Consumer<FileEntry> onUpdate) {
        this.entries     = entries;
        this.useAcoustId = useAcoustId;
        this.maskIndex   = maskIndex;
        this.onProgress  = onProgress;
        this.onUpdate    = onUpdate;
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
                onProgress.accept("Priorité : " + incomplete + " fichier(s) très incomplet(s) traité(s) en premier");
        } else {
            queue = entries;
        }

        // Phase A — Album-first : 1 recherche MB par dossier au lieu de 1 par piste.
        // Cible les dossiers ≥ N fichiers dont les pistes n'ont pas encore de releaseMbid.
        java.util.Set<FileEntry> taggedByAlbum = Config.get().albumFirstPassEnabled()
                ? albumFirstPass(queue) : new java.util.HashSet<>();

        int total = queue.size();
        int done  = 0;

        for (FileEntry entry : queue) {
            if (taggedByAlbum.contains(entry)) {
                setProgress((++done * 100) / total);
                correctionLog.addEntry(entry);
                continue;
            }
            if (isCancelled()) break;

            entry.status = FileEntry.Status.PROCESSING;
            publish(entry);
            final int fileIdx   = done + 1;
            final int fileTotal = total;
            final String fname  = entry.filename();
            Consumer<String> step = s -> onProgress.accept(
                String.format("[%d/%d] %s — %s", fileIdx, fileTotal, fname, s));
            step.accept("identification…");

            // Reset avant chaque fichier : on mesure tous les appels MB de processEntry entier
            mb.resetNetworkFlag();

            processEntry(entry, step);

            // Si annulé pendant processEntry, remettre l'entrée en attente
            if (isCancelled() && entry.status == FileEntry.Status.PROCESSING) {
                entry.status  = FileEntry.Status.PENDING;
                entry.message = "";
            }

            correctionLog.addEntry(entry);

            setProgress((++done * 100) / total);
            publish(entry);

            // Rate-limit MusicBrainz : sleep SEULEMENT si un vrai appel HTTP a eu lieu
            // Cache hits (source fiable) = 0 ms d'attente
            boolean usedNet = mb.wasNetworkCalled();
            if (!isCancelled() && done < total && usedNet) {
                log("  [rate-limit] appel réseau MB → pause 1.1s");
                sleep(1100);
            } else if (!isCancelled() && done < total) {
                log("  [rate-limit] cache hit → pas de pause");
            }
        }

        // Remettre en attente toute entrée restée bloquée en PROCESSING
        for (FileEntry entry : queue) {
            if (entry.status == FileEntry.Status.PROCESSING) {
                entry.status  = FileEntry.Status.PENDING;
                entry.message = "";
                publish(entry);
            }
        }

        // ── Album clustering : passe 2 — corriger numéros de piste par album ──────
        if (Config.get().albumClusterEnabled() && !isCancelled()) {
            clusterAlbums(entries);
        }

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
                onProgress.accept("Journal écrit → " + logPath.getFileName());
        } catch (Exception ignored) {}
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
    }

    // ── Phase album-first ─────────────────────────────────────────────────────

    /**
     * Groupe les fichiers en attente par dossier parent.
     * Pour chaque groupe ≥ albumFirstPassMinFiles, cherche la release dans MB
     * par nom d'album (dossier ou tag existant), récupère la tracklist complète
     * et apparie chaque fichier à une piste (numéro, puis titre).
     * Résultat : ensemble des FileEntry tagués — ils seront sautés dans la boucle per-track.
     */
    private java.util.Set<FileEntry> albumFirstPass(List<FileEntry> queue) {
        java.util.Set<FileEntry> done = new java.util.HashSet<>();
        int minFiles = Config.get().albumFirstPassMinFiles();

        // Grouper par dossier parent
        java.util.Map<java.nio.file.Path, List<FileEntry>> byFolder = new java.util.LinkedHashMap<>();
        for (FileEntry e : queue) {
            java.nio.file.Path folder =
                (e.currentPath != null ? e.currentPath : e.file.toPath()).getParent();
            byFolder.computeIfAbsent(folder, k -> new java.util.ArrayList<>()).add(e);
        }

        for (java.util.Map.Entry<java.nio.file.Path, List<FileEntry>> group : byFolder.entrySet()) {
            if (isCancelled()) break;
            List<FileEntry> files = group.getValue();
            if (files.size() < minFiles) continue;

            java.nio.file.Path folder = group.getKey();
            String folderName = folder.getFileName() != null ? folder.getFileName().toString() : "";

            // Déterminer le nom d'album et l'artiste hint depuis les tags existants
            String albumName  = "";
            String artistHint = "";
            for (FileEntry e : files) {
                if (e.current != null) {
                    if (albumName.isBlank()  && !e.current.album.isBlank())       albumName  = e.current.album;
                    if (artistHint.isBlank() && !e.current.albumArtist.isBlank()) artistHint = e.current.albumArtist;
                    if (artistHint.isBlank() && !e.current.artist.isBlank())      artistHint = e.current.artist;
                }
            }
            if (albumName.isBlank()) albumName = folderName;
            if (albumName.isBlank()) continue;

            onProgress.accept(String.format("[album-first] \"%s\" (%d fichiers) — recherche MB…",
                    albumName, files.size()));

            try {
                mb.resetNetworkFlag();
                String relMbid = mb.searchBestRelease(albumName, artistHint);
                if (mb.wasNetworkCalled()) { mb.resetNetworkFlag(); sleep(1100); }
                if (relMbid == null || relMbid.isBlank()) {
                    onProgress.accept(String.format(
                            "[album-first] \"%s\" — non trouvé dans MB (score < 70) → fallback piste/piste",
                            albumName));
                    continue;
                }

                mb.resetNetworkFlag();
                MusicBrainzClient.ReleaseTracklist tl = mb.lookupRelease(relMbid);
                if (mb.wasNetworkCalled()) { mb.resetNetworkFlag(); sleep(1100); }
                if (tl == null || tl.tracks().isEmpty()) continue;

                onProgress.accept(String.format(
                        "[album-first] \"%s\" trouvé — %d pistes, appariement…", tl.album(), tl.tracks().size()));

                for (FileEntry entry : files) {
                    if (isCancelled()) break;
                    MusicBrainzClient.ReleaseTrack track = matchFileToTrack(entry, tl.tracks());
                    if (track == null) {
                        log("[album-first] " + entry.filename() + " → pas d'appariement dans tracklist");
                        continue;
                    }

                    TagInfo ti = new TagInfo();
                    ti.title            = track.title();
                    ti.artist           = track.artist();
                    ti.albumArtist      = tl.albumArtist();
                    ti.albumArtistSort  = tl.albumArtistSort();
                    ti.album            = tl.album();
                    ti.year             = tl.year();
                    ti.track            = track.trackNo()  > 0 ? String.valueOf(track.trackNo())  : "";
                    ti.trackTotal       = track.trackTotal()> 0 ? String.valueOf(track.trackTotal()): "";
                    ti.discNo           = track.disc()     > 0 ? String.valueOf(track.disc())     : "";
                    ti.releaseMbid      = tl.releaseMbid();
                    ti.releaseGroupMbid = tl.releaseGroupMbid();
                    ti.recordingMbid    = track.recordingMbid();
                    ti.isCompilation    = tl.isCompilation() ? "1" : "";
                    ti.score            = 100;

                    File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                    writer.write(fichier, ti);
                    String recMbid = track.recordingMbid();
                    cache.saveTaggingHistory(ti, recMbid.isBlank()
                            ? MetadataCache.syntheticKey(ti.artist, ti.title) : recMbid);
                    cache.recordFileTagging(fichier.getAbsolutePath(),
                            recMbid.isBlank() ? MetadataCache.syntheticKey(ti.artist, ti.title) : recMbid,
                            MetadataCache.SOURCE_MBID);

                    entry.result  = ti;
                    entry.status  = FileEntry.Status.TAGGED;
                    entry.message = "";
                    publish(entry);
                    done.add(entry);
                    log("[album-first] ✓ " + entry.filename()
                        + " → piste " + track.trackNo() + " \"" + track.title() + "\"");
                }

            } catch (Exception ex) {
                onProgress.accept(String.format("[album-first] Erreur \"%s\" : %s", albumName, ex.getMessage()));
                log("[album-first] exception : " + ex.getMessage());
            }
        }

        if (!done.isEmpty())
            onProgress.accept(String.format("[album-first] %d fichier(s) tagué(s) par album — %d restant(s) en pipeline normal",
                    done.size(), queue.size() - done.size()));
        return done;
    }

    /** Apparie un fichier à une piste de la tracklist : d'abord par numéro, puis par titre. */
    private MusicBrainzClient.ReleaseTrack matchFileToTrack(
            FileEntry entry, List<MusicBrainzClient.ReleaseTrack> tracks) {
        // 1. Par numéro de piste (tag existant ou préfixe dans le nom de fichier)
        int num = extractTrackNumber(entry);
        if (num > 0) {
            for (MusicBrainzClient.ReleaseTrack t : tracks)
                if (t.trackNo() == num) return t;
        }

        // 2. Par titre normalisé
        String title = (entry.current != null && !entry.current.title.isBlank())
                ? entry.current.title : filenameToTitle(entry.filename());
        if (title.isBlank()) return null;
        String normTitle = AlbumCompletionWorker.normalize(title);

        // Correspondance exacte
        for (MusicBrainzClient.ReleaseTrack t : tracks)
            if (normTitle.equals(AlbumCompletionWorker.normalize(t.title()))) return t;

        // Correspondance par inclusion (longueur min 8 pour éviter les faux positifs)
        for (MusicBrainzClient.ReleaseTrack t : tracks) {
            String normMb = AlbumCompletionWorker.normalize(t.title());
            if (normTitle.length() >= 8 && (normTitle.contains(normMb) || normMb.contains(normTitle)))
                return t;
        }
        return null;
    }

    /** Extrait le numéro de piste depuis le tag ou le nom de fichier ("01 - title.mp3"). */
    private int extractTrackNumber(FileEntry entry) {
        if (entry.current != null && !entry.current.track.isBlank()) {
            try { return Integer.parseInt(entry.current.track.replaceAll("[^0-9]", "")); }
            catch (NumberFormatException ignored) {}
        }
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("^(\\d{1,3})[\\s.\\-_]").matcher(entry.filename());
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /** Titre à partir du nom de fichier (retire extension, numéro de piste, "Artiste - "). */
    private String filenameToTitle(String filename) {
        int dot = filename.lastIndexOf('.');
        String name = dot > 0 ? filename.substring(0, dot) : filename;
        name = name.replaceAll("^\\d{1,3}[\\s.\\-_]+", "");
        name = name.replaceAll("^[^-]+ - ", "");
        return name.trim();
    }

    private void processEntry(FileEntry entry, Consumer<String> step) {
        try {
            File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;

            // Vérifier que le fichier existe avant tout traitement
            if (!fichier.exists()) {
                entry.status  = FileEntry.Status.ERROR;
                entry.message = "Fichier introuvable";
                log("▶ SKIP   " + fichier.getName() + " (fichier introuvable)");
                return;
            }

            // Transcodage automatique avant taguage si activé
            if (Config.get().transcodeAutoBeforeTag()) {
                try {
                    com.opentagger.AudioTranscoder.Format fmt =
                        com.opentagger.AudioTranscoder.Format.fromId(Config.get().transcodeFormat());
                    step.accept("transcodage → " + fmt.id.toUpperCase() + "…");
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
                    log("  transcode WARN: " + txEx.getMessage() + " — poursuite sans transcodage");
                }
            }

            log("▶ START  " + fichier.getName());

            log("  findTags...");
            List<TagInfo> results = findTags(fichier, entry.current);
            mb.setPreferredAlbum(""); // reset après findTags — clusterAlbums ne doit pas en bénéficier
            log("  findTags → " + results.size() + " résultat(s)" +
                (results.isEmpty() ? "" : " score=" + results.get(0).score));

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty()) {
                entry.status  = FileEntry.Status.SKIPPED;
                entry.message = "Non identifié";
                log("  SKIPPED (non identifié)");
                return;
            }

            TagInfo best = results.get(0);

            if (best.score < seuil) {
                entry.candidates = results;
                entry.status  = FileEntry.Status.SKIPPED;
                entry.message = "Score " + best.score + "% < " + seuil + "% — " + results.size() + " candidat(s)";
                log("  SKIPPED score trop bas");
                return;
            }

            log("  ✓ identifié : " + best.artist + " – " + best.title + " (score=" + best.score + ")");

            // Enrichir avec lookup si album ou année manquants (la recherche ne retourne pas
            // toujours les releases — ex. remixes, singles sans release dédiée dans MB)
            if ((best.album.isBlank() || best.year.isBlank()) && !best.recordingMbid.isBlank()) {
                step.accept("enrichissement MusicBrainz…");
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
                        log("  enrichi←lookup: album='" + best.album + "' année='" + best.year + "' artistMbid='" + best.artistMbid + "'");
                    }
                } catch (Exception ignored) {}
            }

            // Fallback MB search : si album ou artistMbid toujours vides après lookup.
            // Ne pause que si un vrai appel MB a eu lieu (évite d'attendre pour un cache hit).
            if ((best.album.isBlank() || best.year.isBlank()) && !best.recordingMbid.isBlank()
                    && mb.wasNetworkCalled()) {
                mb.resetNetworkFlag(); // flag réinitialisé : le prochain appel en bénéficiera aussi
                sleep(1100);
            }
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
                        log("  enrichi←MB search: album='" + best.album + "' année='" + best.year + "' artistMbid='" + best.artistMbid + "'");
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
                    log("  compilation restaurée : album='" + origAlbum
                        + "' albumArtist='" + origAlbumArtist + "'");
                    best.album         = origAlbum;
                    best.albumArtist   = origAlbumArtist.isBlank()
                        ? Config.get().vaName() : origAlbumArtist;
                    best.isCompilation = "1";
                    // track#, disc#, MBID, artist, title restent ceux de MB
                }
            }

            log("  corrector...");
            corrector.correct(best, entry.file.toPath());
            taggerScript.apply(best);

            step.accept("genres…");
            log("  genres...");
            if (best.genre.isBlank()) {
                try { discogs.enrichGenres(best); log("  genre←discogs=" + best.genre); } catch (Exception ignored) {}
            }
            if (best.genre.isBlank()) {
                try { lastFm.enrichGenres(best);  log("  genre←lastfm="  + best.genre); } catch (Exception ignored) {}
            }
            log("  genre=" + best.genre);

            if (best.mood.isBlank()) {
                try { lastFm.enrichMood(best); log("  mood←lastfm=" + best.mood); } catch (Exception ignored) {}
            }
            try { lastFm.enrichArtistUrls(best); } catch (Exception ignored) {}

            if (bpmEnabled && best.bpm.isBlank()) {
                step.accept("BPM…");
                log("  BPM...");
                int bpm = bpmDet.detect(fichier.getAbsolutePath());
                if (bpm > 0) best.bpm = String.valueOf(bpm);
                log("  bpm=" + best.bpm);
            }

            if (essentiaEnabled) {
                log("  essentia...");
                essentia.analyze(fichier.getAbsolutePath(), best);
                log("  mood←essentia=" + best.mood);
            }

            // ── Translittération artiste (si nom non-Latin et option activée) ──────
            if (Config.get().translateArtists() && !best.artistMbid.isBlank()
                    && hasNonLatinChars(best.artist)) {
                try {
                    String alias = aliasCache.computeIfAbsent(best.artistMbid, mbid -> {
                        try { return mb.lookupArtistAlias(mbid, Config.get().translateLocale()); }
                        catch (Exception e) { return ""; }
                    });
                    if (!alias.isBlank()) {
                        log("  translit: " + best.artist + " → " + alias);
                        best.artist     = alias;
                        best.artistSort = alias;
                    }
                } catch (Exception ignored) {}
            }

            log("  lyrics...");
            try { lyrics.enrich(best); } catch (Exception ignored) {}

            step.accept("pochette…");
            log("  fanart/caa...");
            Path cover = null;
            // 1. Cover Art Archive en priorité (lié à la release exacte via MBID — plus fiable)
            if (!best.releaseMbid.isBlank() || !best.releaseGroupMbid.isBlank()) {
                try { cover = caa.downloadFront(best); } catch (Exception ignored) {}
                if (cover != null) log("  cover←caa");
            }
            // 2. Pochette locale en fallback (folder.jpg, cover.jpg… dans le dossier du fichier)
            if (cover == null && Config.get().coverSearchLocal()) {
                try { cover = findLocalCover(fichier.getParentFile()); } catch (Exception ignored) {}
                if (cover != null) log("  cover←local: " + cover.getFileName());
            }
            // 3. FanArt.tv en dernier recours
            if (cover == null && Config.get().fanartEnabled() && !best.artistMbid.isBlank()) {
                try { cover = fanArt.downloadCover(best); } catch (Exception ignored) {}
                if (cover != null) log("  cover←fanart");
            }
            // 3. Sauvegarde pochette en fichier séparé si configuré
            if (cover != null && Config.get().bool("cover.save_to_file", false)) {
                try {
                    String fname = Config.get().str("cover.filename", "cover");
                    String ext   = cover.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
                    java.nio.file.Path dest = fichier.toPath().resolveSibling(fname + ext);
                    if (!java.nio.file.Files.exists(dest) || Config.get().bool("cover.overwrite_file", false))
                        java.nio.file.Files.copy(cover, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            }
            log("  cover=" + (cover != null ? cover.getFileName() : "null"));

            // ── ReplayGain (avant écriture pour inclure dans le même commit) ────────
            if (rgEnabled) {
                log("  replaygain...");
                try {
                    ReplayGainAnalyzer.RGResult rg = replayGain.analyze(fichier.getAbsolutePath());
                    if (rg != null) {
                        best.replayGainTrackGain = rg.trackGain();
                        best.replayGainTrackPeak = rg.trackPeak();
                        log("  rg: gain=" + rg.trackGain() + " peak=" + rg.trackPeak());
                    }
                } catch (Exception ignored) {}
            }

            step.accept("écriture tags…");
            log("  write tags...");
            best = writer.write(fichier, best, cover);

            // ── Renommage optionnel ───────────────────────────────────────
            String oldFilePath = fichier.getAbsolutePath();
            if (maskIndex >= 0) {
                step.accept("renommage…");
                try {
                    Path curPath = fichier.toPath();
                    String libRoot = Config.get().libraryRoot();
                    Path root = (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                            ? java.nio.file.Paths.get(libRoot)
                            : (entry.scanRoot != null ? entry.scanRoot : curPath.getParent());
                    Path newPath = renamer.rename(curPath, best, maskIndex, root);
                    if (newPath != null) {
                        Path oldParent    = fichier.toPath().getParent();
                        entry.currentPath = newPath;
                        if (Config.get().deleteEmptyDirsAfterRename()) {
                            FileRenamer.deleteEmptyAncestors(oldParent, root);
                        }
                        entry.message = "→ " + newPath.getFileName();
                    }
                } catch (Exception ignored) {}
            }

            // ── Suggestions d'amélioration ────────────────────────────────────
            List<String> sugg = buildSuggestions(best, cover, seuil);
            if (!sugg.isEmpty()) {
                entry.suggestions = sugg;
                String sep = entry.message.isBlank() ? "" : " · ";
                entry.message += sep + "⚠" + sugg.size();
            }

            entry.result = best;
            entry.status = FileEntry.Status.TAGGED;
            log("  ✔ TAGGED " + fichier.getName() + (sugg.isEmpty() ? "" : " (" + sugg.size() + " suggestion(s))"));

            // Clé cache : MBID réel si disponible, sinon clé synthétique artist+title
            // Garantit que même les résultats SongRec-only sont mémorisés et ne repassent pas en PENDING
            String cacheKey = !best.recordingMbid.isBlank()
                ? best.recordingMbid
                : MetadataCache.syntheticKey(best.artist, best.title);
            cache.saveTaggingHistory(best, cacheKey);

            // Utiliser le chemin effectif (post-renommage) pour le log — pas l'ancien chemin
            String effectivePath = entry.currentPath != null
                ? entry.currentPath.toAbsolutePath().toString()
                : oldFilePath;
            if (Config.get().followLogAfterRename() && !effectivePath.equals(oldFilePath)) {
                // Supprimer l'entrée de l'ancien chemin pour ne pas laisser d'orphelin
                cache.deleteFileHistory(oldFilePath);
            }
            cache.recordFileTagging(effectivePath, cacheKey, lastFindTagsSource);

            if (!best.recordingMbid.isBlank()) acoustId.submit(best.recordingMbid);
            submitToMusicBrainz(best);

        } catch (Exception ex) {
            entry.status  = FileEntry.Status.ERROR;
            entry.message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log("  ✗ ERROR " + entry.filename() + " : " + entry.message);
        }
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }

    // ── Résolution des tags — avec cache SQLite ───────────────────────────────

    private List<TagInfo> findTags(File fichier, TagInfo existingTags) throws Exception {
        // 0-pre. Réparer les M4A avec structure mdat<moov non lisible par jaudiotagger
        if (TagWriter.repairM4aIfNeeded(fichier)) log("  M4A réparé OK");

        // Indice d'album : tag existant > nom du dossier parent.
        // Permet à pickBestRelease() de favoriser la release MB qui correspond au dossier iTunes.
        // Ex. : dossier "100 Club Hits Edition 2022" → MB préfère cette compilation si elle existe.
        {
            String tagAlbum    = cleanSearchTerm(readTag(fichier, FieldKey.ALBUM));
            String folderAlbum = fichier.getParentFile() != null
                ? fichier.getParentFile().getName() : "";
            // Le tag existant prime ; le dossier parent sert de fallback si tag vide
            String albumHint = tagAlbum.isBlank() ? folderAlbum : tagAlbum;
            mb.setPreferredAlbum(albumHint);
            if (!albumHint.isBlank()) log("  indice album : '" + albumHint + "'");
        }

        // 0. Historique personnel — ce fichier a-t-il déjà été tagué par OpenTagger ?
        //    Confiance totale SEULEMENT si identifié par empreinte audio (songrec/acoustid/mbid).
        //    Si identifié par texte ("text"), SongRec doit vérifier car les tags peuvent être faux.
        lastFindTagsSource = MetadataCache.SOURCE_TEXT;
        String knownMbid = cache.getFileTagging(fichier.getAbsolutePath());
        if (knownMbid != null) knownMbid = knownMbid.trim();
        if (knownMbid != null && !knownMbid.isBlank()) {
            String cachedSource = cache.getFileTaggingSource(fichier.getAbsolutePath());
            boolean trustedSource = MetadataCache.SOURCE_SONGREC.equals(cachedSource)
                                 || MetadataCache.SOURCE_ACOUSTID.equals(cachedSource)
                                 || MetadataCache.SOURCE_MBID.equals(cachedSource);
            TagInfo hist = cache.getTaggingHistory(knownMbid);
            if (hist != null && (!hist.artist.isBlank() || !hist.title.isBlank())) {
                if (trustedSource) {
                    // Empreinte audio → confiance totale, retour instantané
                    hist.score = 100;
                    lastFindTagsSource = cachedSource;
                    log("  cache hit [" + cachedSource + "] ✓ : " + hist.artist + " – " + hist.title);
                    return List.of(hist);
                } else {
                    // Identification textuelle → on continue vers SongRec pour vérifier
                    log("  cache hit [text] → SongRec va vérifier : " + hist.artist + " – " + hist.title);
                }
            } else if (hist != null) {
                log("  cache hit IGNORÉ (artiste+titre vides) pour mbid=" + knownMbid);
            }
        }

        // 0.5. Tags MB existants complets (Picard, MusicBrainz Tagger, session précédente).
        // Si le fichier a déjà releaseMbid + artist + title + album valides → confiance totale.
        // On ne ré-identifie pas ce que Picard a déjà fait : on garde l'album de compilation
        // tel quel, on évite deux allers-retours MB inutiles, et le taguage est instantané.
        if (Config.get().trustExistingMbTags() && existingTags != null
                && !existingTags.releaseMbid.isBlank()
                && !existingTags.artist.isBlank()
                && !existingTags.title.isBlank()
                && !existingTags.album.isBlank()
                && !isGenericTag(existingTags.artist)
                && !isGenericTag(existingTags.title)) {
            TagInfo t = existingTags.copy();
            t.score = 100;
            lastFindTagsSource = MetadataCache.SOURCE_MBID;
            log("  tags MB existants ✓ [releaseMbid=" + existingTags.releaseMbid.substring(0,
                    Math.min(8, existingTags.releaseMbid.length())) + "…] "
                + existingTags.artist + " – " + existingTags.title
                + " [" + existingTags.album + "] → skip identification");
            return List.of(t);
        }

        // 1. SongRec (Shazam) — empreinte audio, identifie la musique commerciale même avec
        //    de faux tags existants. Placé AVANT AcoustID pour être la source principale.
        if (SongRecClient.isAvailable()) {
            try {
                log("  SongRec...");
                TagInfo sr = songRec.recognize(fichier);
                if (sr != null && !sr.artist.isBlank() && !sr.title.isBlank()) {
                    log("  SongRec → " + sr.artist + " – " + sr.title);
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
                        log("  SongRec→MB: " + best.artist + " – " + best.title + " [" + best.album + "] score=" + best.score);
                        lastFindTagsSource = MetadataCache.SOURCE_SONGREC;
                        return srMb;
                    }
                    // MB n'a rien enrichi : garder le résultat SongRec seul
                    sr.score = 85;
                    log("  SongRec seul (MB sans match): " + sr.artist + " – " + sr.title);
                    if (sr.genre.isBlank()) { try { discogs.enrichGenres(sr); } catch (Exception ignored) {} }
                    if (sr.genre.isBlank()) { try { lastFm.enrichGenres(sr);  } catch (Exception ignored) {} }
                    lastFindTagsSource = MetadataCache.SOURCE_SONGREC;
                    return List.of(sr);
                } else {
                    log("  SongRec → rien trouvé");
                }
            } catch (Exception e) {
                log("  SongRec WARN: " + e.getMessage());
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
                log("  MBID lookup: " + t.artist + " – " + t.title);
                t.score = 100;
                lastFindTagsSource = MetadataCache.SOURCE_MBID;
                return List.of(t);
            } else if (t != null) {
                log("  MBID lookup IGNORÉ (artiste+titre vides) mbid=" + existingMbid);
            }
        }

        // 2. AcoustID (fingerprint)
        if (useAcoustId) {
            // ignore_existing : si un AcoustID est déjà dans les tags et qu'on ne force pas, on skip
            boolean hasExistingId = !readTag(fichier, FieldKey.ACOUSTID_ID).isBlank();
            if (!hasExistingId || Config.get().ignoreExistingFingerprints()) {
                List<TagInfo> r = acoustId.identify(fichier);
                if (!r.isEmpty()) {
                    lastFindTagsSource = MetadataCache.SOURCE_ACOUSTID;
                    return r;
                }
            }
        }

        // 3. Tags texte existants, avec fallback sur le nom de fichier
        String artist = readTag(fichier, FieldKey.ARTIST);
        String title  = readTag(fichier, FieldKey.TITLE);
        // Lire l'album maintenant (utilisé en fallback plus bas quand artiste manque)
        String existingAlbum = cleanSearchTerm(readTag(fichier, FieldKey.ALBUM));

        // Détection hors FR/EN : si les tags contiennent du japonais, coréen, arabe,
        // cyrillique, etc. → inutile de chercher dans MB avec ces termes, SongRec en priorité
        boolean nonLatinInput = hasNonLatinChars(artist) || hasNonLatinChars(title);
        if (nonLatinInput) {
            log("  tags non-Latin → SongRec en priorité");
            artist = ""; title = "";
        } else {
            artist = cleanSearchTerm(artist);
            title  = cleanSearchTerm(title);
            if (isGenericTag(artist)) artist = "";
            if (isGenericTag(title))  title  = "";
            log("  tags lus: artiste='" + artist + "' titre='" + title + "'");
            if (artist.isBlank() && title.isBlank()) {
                String[] fn = parseFilename(fichier);
                if (hasNonLatinChars(fn[0]) || hasNonLatinChars(fn[1])) {
                    nonLatinInput = true;
                    log("  nom de fichier non-Latin → SongRec en priorité");
                } else {
                    artist = fn[0]; title = fn[1];
                    // Appliquer isGenericTag sur l'artiste du nom de fichier aussi ("0", "01", etc.)
                    if (isGenericTag(artist)) artist = "";
                    log("  → infos du nom de fichier: artiste='" + artist + "' titre='" + title + "'");
                }
            } else if (artist.isBlank() && !title.isBlank()) {
                // Artiste vide mais titre connu : essayer de récupérer l'artiste depuis le nom de fichier
                String[] fn = parseFilename(fichier);
                if (!fn[0].isBlank() && !isGenericTag(fn[0]) && !hasNonLatinChars(fn[0])) {
                    artist = fn[0];
                    log("  artiste←nom de fichier: '" + artist + "'");
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
                            && !isGenericTag(potArtist) && !hasNonLatinChars(potArtist)
                            && (potArtist.contains(" ") || potArtist.length() >= 5)) {
                        artist = potArtist;
                        title  = potTitle;
                        log("  artiste+titre←split titre: '" + artist + "' / '" + title + "'");
                    }
                }
                // Titres trop génériques sans artiste → MB donnera trop de faux positifs → SongRec
                if (artist.isBlank() && GENERIC_TITLES_WITHOUT_ARTIST.contains(title.toLowerCase())) {
                    log("  titre générique sans artiste ('" + title + "') → SongRec");
                    title = "";
                }
            }
        }

        if (artist.isBlank() && title.isBlank() && !nonLatinInput) {
            log("  → rien à chercher");
            return List.of();
        }

        List<TagInfo> results = List.of();

        if (!nonLatinInput) {
            // 4. Cache SQLite
            String hash   = MetadataCache.queryHash(artist, title);
            String cached = cache.getRecordingSearch(hash);
            if (cached != null) {
                List<TagInfo> r = mb.parseFromCache(cached);
                log("  MB cache: " + r.size() + " résultat(s)");
                if (!r.isEmpty()) return r;
            }

            // 5. MusicBrainz — réseau (avec fallbacks progressifs)
            log("  MB search: '" + artist + "' / '" + title + "'");
            results = mb.searchRecording(artist, title);
            log("  MB search → " + results.size() + " résultat(s)" +
                (results.isEmpty() ? "" : " meilleur score=" + results.get(0).score));
            if (!results.isEmpty()) {
                cache.putRecordingSearch(hash, mb.lastRawJson());
                return results;
            }

            // 5b. Fallback: artiste simplifié
            String artistSimple = simplifyArtist(artist);
            if (!artistSimple.equals(artist) && !artistSimple.isBlank() && !isGenericTag(artistSimple)) {
                log("  MB fallback artiste simplifié: '" + artistSimple + "'");
                results = mb.searchRecording(artistSimple, title);
                log("  MB fallback → " + results.size() + " résultat(s)");
                if (!results.isEmpty()) {
                    cache.putRecordingSearch(hash, mb.lastRawJson());
                    return results;
                }
            }
        }

        // NOTE: Le fallback "titre seul" est désactivé — trop de faux positifs.

        // 5b-bis. Fallback titre + album : artiste vide mais album connu dans les tags existants
        // Typique : fichier avec artist="0"/vide mais title+album corrects (ex: M4A mal encodé)
        if (!nonLatinInput && results.isEmpty() && artist.isBlank()
                && !title.isBlank() && !existingAlbum.isBlank()) {
            log("  MB fallback titre+album: '" + title + "' / '" + existingAlbum + "'");
            results = mb.searchRecording("", title, existingAlbum);
            log("  MB titre+album → " + results.size() + " résultat(s)");
            if (!results.isEmpty()) {
                cache.putRecordingSearch(MetadataCache.queryHash(title, existingAlbum), mb.lastRawJson());
                return results;
            }
        }

        // 5c. SongRec — étape 1 : reconnaissance audio (empreinte Shazam gratuite)
        //              étape 2 : MB complète ce que SongRec a trouvé
        if (SongRecClient.isAvailable()) {
            log(nonLatinInput ? "  SongRec (non-Latin)..." : "  SongRec fallback...");
            try {
                TagInfo sr = songRec.recognize(fichier);
                if (sr != null) {
                    log("  SongRec → " + sr.artist + " – " + sr.title);
                    // MB complète : MBID, album complet, track#, disc#, albumArtist, année…
                    List<TagInfo> mbResults = mb.searchRecording(sr.artist, sr.title);
                    if (!mbResults.isEmpty() && mbResults.get(0).score >= 50) {
                        TagInfo mbr = mbResults.get(0);
                        // SongRec comble ce que MB n'a pas
                        if (mbr.album.isBlank()   && !sr.album.isBlank())   mbr.album   = sr.album;
                        if (mbr.year.isBlank()     && !sr.year.isBlank())    mbr.year    = sr.year;
                        if (mbr.genre.isBlank()    && !sr.genre.isBlank())   mbr.genre   = sr.genre;
                        if (mbr.isrc.isBlank()     && !sr.isrc.isBlank())    mbr.isrc    = sr.isrc;
                        if (mbr.track.isBlank()    && !sr.track.isBlank())   mbr.track   = sr.track;
                        if (mbr.comment.isBlank()  && !sr.comment.isBlank()) mbr.comment = sr.comment;
                        mbr.score = 90;
                        log("  SongRec+MB → " + mbr.artist + " – " + mbr.title + " [" + mbr.album + "]");
                        return List.of(mbr);
                    }
                    // MB échoue avec titre complet → réessayer sans qualificatif entre parenthèses
                    // ex: "Song Name (Home Demos)" → "Song Name"
                    String cleanTitle = sr.title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    if (!cleanTitle.equals(sr.title) && !cleanTitle.isBlank()) {
                        log("  SongRec+MB (titre nettoyé): '" + cleanTitle + "'");
                        List<TagInfo> mbClean = mb.searchRecording(sr.artist, cleanTitle);
                        if (!mbClean.isEmpty() && mbClean.get(0).score >= 50) {
                            TagInfo mbr = mbClean.get(0);
                            if (mbr.album.isBlank()   && !sr.album.isBlank())   mbr.album   = sr.album;
                            if (mbr.year.isBlank()     && !sr.year.isBlank())    mbr.year    = sr.year;
                            if (mbr.genre.isBlank()    && !sr.genre.isBlank())   mbr.genre   = sr.genre;
                            if (mbr.isrc.isBlank()     && !sr.isrc.isBlank())    mbr.isrc    = sr.isrc;
                            if (mbr.track.isBlank()    && !sr.track.isBlank())   mbr.track   = sr.track;
                            if (mbr.comment.isBlank()  && !sr.comment.isBlank()) mbr.comment = sr.comment;
                            if (!sr.title.equals(cleanTitle)) mbr.title = sr.title;
                            mbr.score = 85;
                            log("  SongRec+MB(nettoyé) → " + mbr.artist + " – " + mbr.title + " [" + mbr.album + "]");
                            return List.of(mbr);
                        }
                    }
                    // MB ne confirme pas → garder les données SongRec + chercher artistMbid pour la pochette
                    if (sr.artistMbid.isBlank()) {
                        try {
                            String amid = mb.searchArtistMbid(sr.artist);
                            if (!amid.isBlank()) { sr.artistMbid = amid; log("  artistMbid←MB: " + amid); }
                        } catch (Exception ignored) {}
                    }
                    sr.score = 85;
                    log("  SongRec seul (MB non confirmé) → " + sr.artist + " – " + sr.title);
                    return List.of(sr);
                }
            } catch (Exception e) {
                log("  SongRec erreur: " + e.getMessage());
            }
        }

        // 5d. AcoustID en dernier recours (lent mais très précis par empreinte audio)
        if (!useAcoustId && !Config.get().acoustidKey().isBlank()) {
            log("  AcoustID fallback...");
            List<TagInfo> r = acoustId.identify(fichier);
            log("  AcoustID fallback → " + r.size() + " résultat(s)");
            if (!r.isEmpty()) return r;
        }
        return results;
    }

    /**
     * Passe 2 : groupe les fichiers taguées par releaseMbid, fait un seul lookupRelease
     * par album, et re-corrige numéros/totaux de piste + albumArtist cohérent.
     */
    private void clusterAlbums(List<FileEntry> entries) {
        // Grouper par releaseMbid
        java.util.Map<String, java.util.List<FileEntry>> groups = new java.util.LinkedHashMap<>();
        for (FileEntry e : entries) {
            if (e.status == FileEntry.Status.TAGGED && e.result != null
                    && !e.result.releaseMbid.isBlank()) {
                groups.computeIfAbsent(e.result.releaseMbid, k -> new java.util.ArrayList<>()).add(e);
            }
        }

        for (java.util.Map.Entry<String, java.util.List<FileEntry>> group : groups.entrySet()) {
            if (isCancelled()) break;
            java.util.List<FileEntry> albumFiles = group.getValue();
            if (albumFiles.size() < 2) continue;

            String releaseMbid = group.getKey();
            log("  cluster: " + albumFiles.size() + " fichiers pour release " + releaseMbid);
            try {
                MusicBrainzClient.ReleaseTracklist tracklist = mb.lookupRelease(releaseMbid);
                if (tracklist == null || tracklist.tracks().isEmpty()) continue;

                int maxDisc = tracklist.tracks().stream().mapToInt(MusicBrainzClient.ReleaseTrack::disc).max().orElse(0);

                for (FileEntry entry : albumFiles) {
                    TagInfo result = entry.result;
                    MusicBrainzClient.ReleaseTrack matched = findBestTrack(tracklist, result);
                    if (matched == null) continue;

                    boolean changed = false;
                    if (matched.trackNo() > 0 && !String.valueOf(matched.trackNo()).equals(result.track)) {
                        result.track = String.valueOf(matched.trackNo()); changed = true;
                    }
                    if (matched.trackTotal() > 0 && !String.valueOf(matched.trackTotal()).equals(result.trackTotal)) {
                        result.trackTotal = String.valueOf(matched.trackTotal()); changed = true;
                    }
                    if (maxDisc > 1 && matched.disc() > 0) {
                        result.discNo    = String.valueOf(matched.disc());
                        result.discTotal = String.valueOf(maxDisc);
                        changed = true;
                    }
                    if (!tracklist.albumArtist().isBlank()) result.albumArtist     = tracklist.albumArtist();
                    if (!tracklist.albumArtistSort().isBlank()) result.albumArtistSort = tracklist.albumArtistSort();
                    if (tracklist.isCompilation()) result.isCompilation = "1";

                    if (changed) {
                        File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                        result = writer.write(fichier, result);
                        log("  cluster ok: " + fichier.getName() + " → piste " + result.track + "/" + result.trackTotal);
                        entry.result = result;
                        publish(entry);
                    }
                }
                // ── Album ReplayGain (concat analyse) ─────────────────────────────
                if (rgEnabled) {
                    java.util.List<String> paths = albumFiles.stream()
                        .map(e -> e.currentPath != null ? e.currentPath.toString() : e.file.getAbsolutePath())
                        .collect(java.util.stream.Collectors.toList());
                    log("  album RG: analyse " + paths.size() + " pistes...");
                    ReplayGainAnalyzer.RGResult albumRg = ReplayGainAnalyzer.analyzeAlbum(paths);
                    if (albumRg != null) {
                        log("  album RG: gain=" + albumRg.trackGain() + " peak=" + albumRg.trackPeak());
                        for (FileEntry entry : albumFiles) {
                            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                            writer.writeAlbumReplayGain(f, albumRg.trackGain(), albumRg.trackPeak());
                        }
                    }
                }

                if (mb.wasNetworkCalled()) { mb.resetNetworkFlag(); sleep(1100); }
            } catch (Exception e) {
                log("  cluster erreur: " + e.getMessage());
            }
        }
    }

    private MusicBrainzClient.ReleaseTrack findBestTrack(MusicBrainzClient.ReleaseTracklist tracklist, TagInfo result) {
        // 1. Correspondance par recordingMbid (100% fiable)
        if (!result.recordingMbid.isBlank()) {
            for (var t : tracklist.tracks())
                if (result.recordingMbid.equals(t.recordingMbid())) return t;
        }
        // 2. Correspondance par numéro de piste + disc
        if (!result.track.isBlank()) {
            try {
                int n = Integer.parseInt(result.track.trim());
                int d = result.discNo.isBlank() ? 1 : Integer.parseInt(result.discNo.trim());
                for (var t : tracklist.tracks())
                    if (t.trackNo() == n && (t.disc() == 0 || t.disc() == d)) return t;
            } catch (NumberFormatException ignored) {}
        }
        // 3. Correspondance par similarité de titre
        String titleLow = result.title.toLowerCase().trim();
        if (titleLow.isBlank()) return null;
        MusicBrainzClient.ReleaseTrack best = null;
        int bestScore = 0;
        for (var t : tracklist.tracks()) {
            int sim = titleSimilarity(titleLow, t.title().toLowerCase().trim());
            if (sim > bestScore && sim >= 70) { bestScore = sim; best = t; }
        }
        return best;
    }

    private static int titleSimilarity(String a, String b) {
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) return 90;
        java.util.Set<String> ta = new java.util.HashSet<>(java.util.Arrays.asList(a.split("\\s+")));
        java.util.Set<String> tb = new java.util.HashSet<>(java.util.Arrays.asList(b.split("\\s+")));
        long common = ta.stream().filter(tb::contains).count();
        int total = ta.size() + tb.size();
        return total == 0 ? 0 : (int)(common * 2 * 100 / total);
    }

    /** Cherche une pochette dans le dossier : folder.jpg, cover.jpg, front.jpg… */
    private static Path findLocalCover(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        for (String name : new String[]{
                "folder.jpg","cover.jpg","front.jpg","albumart.jpg","album.jpg",
                "folder.png","cover.png","front.png"}) {
            File f = new File(dir, name);
            if (f.exists() && f.length() > 512) return f.toPath();
        }
        return null;
    }

    private String readTag(File f, FieldKey key) {
        try {
            var af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            String v = tag != null ? tag.getFirst(key) : "";
            return v != null ? v.trim() : "";
        } catch (Exception e) { return ""; }
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
        if (low.matches("unknown artist|unknown|artist|artiste|musique|music|inconnu|"
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
    private static List<String> buildSuggestions(TagInfo best, Path cover, int seuil) {
        List<String> s = new java.util.ArrayList<>();
        if (best.score > 0 && best.score < seuil + 20)
            s.add("Score modéré (" + best.score + "%) — vérifier l'identification");
        if (cover == null)
            s.add("Pochette non trouvée");
        if (best.recordingMbid.isBlank())
            s.add("MBID d'enregistrement manquant");
        if (best.album.isBlank())
            s.add("Album inconnu");
        if (best.year.isBlank())
            s.add("Année manquante");
        if (best.genre.isBlank())
            s.add("Genre manquant");
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

    /**
     * Retourne true si la chaîne contient des caractères de scripts non-Latin
     * (japonais, coréen, chinois, arabe, cyrillique, hébreu, thaï…).
     * Les caractères Latin de base + Latin étendu (accents FR, etc.) passent.
     */
    static boolean hasNonLatinChars(String s) {
        if (s == null || s.isBlank()) return false;
        return s.codePoints().anyMatch(cp -> {
            if (!Character.isLetter(cp)) return false;
            // Latin Basic (0000-007F), Latin-1 Supplement (0080-00FF),
            // Latin Extended A/B (0100-024F), Latin Extended Additional (1E00-1EFF)
            if (cp <= 0x024F) return false;
            if (cp >= 0x1E00 && cp <= 0x1EFF) return false; // accents vietnamiens etc.
            return true; // cyrillique, grec, arabe, CJK, hangul, kana…
        });
    }

    /**
     * Soumet genres et rating à MusicBrainz si le token OAuth est disponible.
     * Silencieux : une erreur n'interrompt pas le tagging local.
     */
    private void submitToMusicBrainz(TagInfo info) {
        String token = Config.get().str("mb.oauth.token", "");
        if (token.isBlank() || info.recordingMbid.isBlank()) return;

        // Tags : genres + mood
        java.util.List<String> tags = new java.util.ArrayList<>();
        if (!info.genre.isBlank())
            java.util.Arrays.stream(info.genre.split(",")).map(String::trim)
                    .filter(s -> !s.isBlank()).forEach(tags::add);
        if (!info.mood.isBlank()) tags.add(info.mood);

        try {
            if (!tags.isEmpty()) {
                mbOauth.submitUserTags(info.recordingMbid, tags, token);
                log("  MB tags soumis: " + tags);
            }
        } catch (Exception e) {
            log("  MB tags skip: " + e.getMessage());
        }

        // Rating (valeur 1–5 uniquement)
        try {
            int rating = parseStars(info.rating);
            if (rating > 0) {
                mbOauth.submitRating(info.recordingMbid, rating, token);
                log("  MB rating soumis: " + rating + " étoile(s)");
            }
        } catch (Exception e) {
            log("  MB rating skip: " + e.getMessage());
        }
    }

    /** Convertit une valeur de rating en étoiles 1–5. Retourne 0 si non applicable. */
    private static int parseStars(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= 1 && v <= 5) return v;
            if (v >= 6 && v <= 255) return Math.max(1, Math.min(5, (int) Math.round(v * 5.0 / 255)));
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
