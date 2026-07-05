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
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
                onProgress.accept(I18n.t("Priorité : %s fichier(s) très incomplet(s) traité(s) en premier", incomplete));
        } else {
            queue = entries;
        }

        // Phase A — Album-first : 1 recherche MB par dossier au lieu de 1 par piste.
        // Cible les dossiers ≥ N fichiers dont les pistes n'ont pas encore de releaseMbid.
        java.util.Set<FileEntry> taggedByAlbum = Config.get().albumFirstPassEnabled()
                ? albumFirstPass(queue) : new java.util.HashSet<>();

        int total = queue.size();
        AtomicInteger done = new AtomicInteger(0);

        // Fichiers déjà tagués par l'album-first pass : juste compter la progression.
        // Le reste part en parallèle (batch.threads, même réglage que BatchProcessor/CLI) —
        // le rate-limit MB reste correct quel que soit le nombre de threads puisqu'il est
        // désormais centralisé dans MusicBrainzClient.getWithRetry(), pas ici.
        List<FileEntry> toProcess = new java.util.ArrayList<>();
        for (FileEntry entry : queue) {
            if (taggedByAlbum.contains(entry)) {
                setProgress((done.incrementAndGet() * 100) / total);
                correctionLog.addEntry(entry);
            } else {
                toProcess.add(entry);
            }
        }

        int threads = Math.max(1, Config.get().num("batch.threads", 3));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
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

                // Instances fraîches par tâche (voir le commentaire sur processEntry()) :
                // jamais les champs partagés mb/acoustId/lastFm quand plusieurs fichiers
                // tournent en même temps.
                processEntry(entry, step, new MusicBrainzClient(), new AcoustIdClient(), new LastFmClient());

                // Si annulé pendant processEntry, remettre l'entrée en attente
                if (isCancelled() && entry.status == FileEntry.Status.PROCESSING) {
                    entry.status  = FileEntry.Status.PENDING;
                    entry.message = "";
                }

                correctionLog.addEntry(entry);

                // Déplacer les fichiers non tagués (SKIPPED/ERROR) vers un dossier dédié si
                // configuré — évite qu'ils restent mélangés dans la bibliothèque organisée par
                // le renommage auto.
                if (Config.get().skippedMoveEnabled()
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

        pool.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
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
                onProgress.accept(I18n.t("Journal écrit → %s", logPath.getFileName()));
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

            onProgress.accept(I18n.t("[album-first] \"%s\" (%d fichiers) — recherche MB…",
                    albumName, files.size()));

            try {
                String relMbid = mb.searchBestRelease(albumName, artistHint);
                if (relMbid == null || relMbid.isBlank()) {
                    onProgress.accept(I18n.t(
                            "[album-first] \"%s\" — non trouvé dans MB (score < 70) → fallback piste/piste",
                            albumName));
                    continue;
                }

                MusicBrainzClient.ReleaseTracklist tl = mb.lookupRelease(relMbid);
                if (tl == null || tl.tracks().isEmpty()) continue;

                onProgress.accept(I18n.t(
                        "[album-first] \"%s\" trouvé — %d pistes, appariement…", tl.album(), tl.tracks().size()));

                // Garde anti-doublon : une piste de la tracklist ne doit jamais être appliquée à
                // deux fichiers différents (une erreur d'appariement a déjà causé, en production,
                // l'application du même MBID/pochette à des dizaines de fichiers sans rapport).
                java.util.Set<String> usedTracks = new java.util.HashSet<>();
                for (FileEntry entry : files) {
                    if (isCancelled()) break;
                    MusicBrainzClient.ReleaseTrack track = matchFileToTrack(entry, tl.tracks());
                    if (track == null) {
                        log(I18n.t("[album-first] %s → pas d'appariement dans tracklist", entry.filename()));
                        continue;
                    }
                    String trackKey = !track.recordingMbid().isBlank()
                            ? track.recordingMbid() : (track.disc() + "/" + track.trackNo());
                    if (!usedTracks.add(trackKey)) {
                        log(I18n.t("[album-first] %s → piste déjà assignée à un autre fichier de ce dossier, ignoré (garde anti-doublon)",
                            entry.filename()));
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
                    ti.artistMbid       = track.artistMbid();
                    ti.isCompilation    = tl.isCompilation() ? "1" : "";
                    ti.score            = 100;

                    File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;

                    // Toutes les étapes d'enrichissement ci-dessous existaient déjà dans la boucle
                    // piste-par-piste plus bas, mais l'album-first pass (chemin emprunté par la
                    // majorité d'une grosse bibliothèque bien organisée en albums) les sautait
                    // TOUTES : ni genre Discogs/Last.fm, ni pochette, ni translittération d'artiste,
                    // ni script tagger, ni empreinte AcoustID, ni soumission MusicBrainz. Trouvé en
                    // observant un run réel où "encore des audios non traduits" concernait presque
                    // exclusivement des fichiers passés par ce chemin (artistMbid restait vide, donc
                    // TagEnrichment.translateArtist() se désactivait silencieusement pour tous).
                    taggerScript.apply(ti);
                    TagEnrichment.enrichGenre(ti, discogs, lastFm);
                    if (ti.mood.isBlank()) { try { lastFm.enrichMood(ti); } catch (Exception ignored) {} }
                    try { lastFm.enrichArtistUrls(ti); } catch (Exception ignored) {}
                    if (bpmEnabled && ti.bpm.isBlank()) {
                        int bpm = bpmDet.detect(fichier.getAbsolutePath());
                        if (bpm > 0) ti.bpm = String.valueOf(bpm);
                    }
                    if (Config.get().saveAcoustidFingerprints() && ti.acoustidFingerprint.isBlank()
                            && FpcalcInstaller.isAvailable()) {
                        try { ti.acoustidFingerprint = Fingerprinter.compute(fichier).fingerprint(); }
                        catch (Exception ignored) {}
                    }
                    if (essentiaEnabled) essentia.analyze(fichier.getAbsolutePath(), ti);
                    TagEnrichment.translateArtist(ti, mb, aliasCache);
                    try { lyrics.enrich(ti); } catch (Exception ignored) {}
                    Path cover = TagEnrichment.resolveCover(ti, fichier, caa, fanArt);

                    writer.write(fichier, ti, cover);
                    TagEnrichment.submitToMusicBrainz(mbOauth, ti, msg -> log("[album-first]   " + msg));
                    String recMbid = track.recordingMbid();
                    cache.saveTaggingHistory(ti, recMbid.isBlank()
                            ? MetadataCache.syntheticKey(ti.artist, ti.title) : recMbid);
                    cache.recordFileTagging(fichier.getAbsolutePath(),
                            recMbid.isBlank() ? MetadataCache.syntheticKey(ti.artist, ti.title) : recMbid,
                            MetadataCache.SOURCE_MBID);

                    // Renommage automatique — sans ce fallback, les fichiers tagués par
                    // album-first étaient les seuls à ne jamais passer par FileRenamer même
                    // quand "renommage auto" est activé (la boucle piste-par-piste plus bas,
                    // qui gère le renommage, les saute puisqu'ils sont déjà dans `done`).
                    java.nio.file.Path renamedPath = null;
                    String renameErr = null;
                    if (maskIndex >= 0) {
                        try {
                            java.nio.file.Path curPath = entry.currentPath != null
                                    ? entry.currentPath : entry.file.toPath();
                            java.nio.file.Path oldParent = curPath.getParent();
                            String libRoot = Config.get().libraryRoot();
                            java.nio.file.Path root =
                                (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                                    ? java.nio.file.Paths.get(libRoot)
                                    : (entry.scanRoot != null ? entry.scanRoot : oldParent);
                            java.nio.file.Path newPath = renamer.rename(curPath, ti, maskIndex, root);
                            if (newPath != null) {
                                renamedPath = newPath;
                                if (Config.get().deleteEmptyDirsAfterRename()) {
                                    FileRenamer.deleteEmptyAncestors(oldParent, root);
                                }
                                log(I18n.t("[album-first]   renommé → %s", newPath));
                            }
                        } catch (Exception ex) {
                            // Visible dans le log ET dans le statut UI — sans ça un déplacement
                            // qui échoue (permissions, disque cible...) est invisible.
                            renameErr = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                            log(I18n.t("[album-first]   renommage échoué : %s", renameErr));
                        }
                    }

                    // Muter entry SUR l'EDT, pas ici : ce FileEntry est aussi lu par le
                    // TableRowSorter en direct depuis l'EDT (déjà vu 697× en 3 jours ailleurs
                    // dans ce même worker — voir SafeTableRowSorter pour le filet de sécurité
                    // sur la boucle piste-par-piste, plus délicate à refactorer sans risque ici).
                    final java.nio.file.Path finalRenamedPath = renamedPath;
                    final String finalRenameErr = renameErr;
                    SwingUtilities.invokeLater(() -> {
                        entry.result  = ti;
                        entry.status  = FileEntry.Status.TAGGED;
                        if (finalRenamedPath != null) entry.currentPath = finalRenamedPath;
                        entry.message = finalRenameErr != null ? I18n.t("Tagué, renommage échoué : %s", finalRenameErr) : "";
                    });
                    publish(entry);
                    done.add(entry);
                    log(I18n.t("[album-first] ✓ %s → piste %s \"%s\"",
                        entry.filename(), track.trackNo(), track.title()));
                }

            } catch (Exception ex) {
                onProgress.accept(I18n.t("[album-first] Erreur \"%s\" : %s", albumName, ex.getMessage()));
                log(I18n.t("[album-first] exception : %s", ex.getMessage()));
            }
        }

        if (!done.isEmpty())
            onProgress.accept(I18n.t("[album-first] %d fichier(s) tagué(s) par album — %d restant(s) en pipeline normal",
                    done.size(), queue.size() - done.size()));
        return done;
    }

    /** Apparie un fichier à une piste de la tracklist : d'abord par numéro, puis par titre. */
    // Mots sans aucun pouvoir discriminant dans un titre (dictaphone/téléphone : "Recording 001",
    // "Titre_Inconnu", "Musique_29007"...) — un fichier ainsi nommé n'est presque jamais un vrai
    // titre catalogué sur MusicBrainz. Le laisser "ressembler" à une piste MB tout aussi générique
    // (ex. un bootleg catalogué "Recording 001" par coïncidence) revient à faire confiance à du
    // bruit textuel. Trouvé en observant un run réel : un fichier déjà nommé "Recording 001" par
    // un dictaphone a matché une piste MB tout aussi vaguement nommée "Recording 001".
    private static final java.util.Set<String> GENERIC_TITLE_WORDS = java.util.Set.of(
            "recording", "rec", "track", "piste", "titre", "title", "inconnu", "unknown",
            "untitled", "download", "audio", "voice", "memo", "musique", "daily", "sound", "clip");

    private boolean isGenericTitle(String normTitle) {
        if (normTitle.isBlank()) return false; // le cas "aucun titre" est géré séparément
        for (String w : normTitle.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (w.chars().allMatch(Character::isDigit)) continue; // un numéro seul est ignoré
            if (!GENERIC_TITLE_WORDS.contains(w)) return false; // mot informatif → pas générique
        }
        return true;
    }

    private MusicBrainzClient.ReleaseTrack matchFileToTrack(
            FileEntry entry, List<MusicBrainzClient.ReleaseTrack> tracks) {
        String title = (entry.current != null && !entry.current.title.isBlank())
                ? entry.current.title : filenameToTitle(entry.filename());
        String normTitle = title.isBlank() ? "" : AlbumCompletionWorker.normalize(title);
        if (isGenericTitle(normTitle)) return null;

        // 1. Par numéro de piste (tag existant ou préfixe dans le nom de fichier) — accepté
        // seulement si aucun titre n'est exploitable pour vérifier (fichier sans tag, nom
        // générique type "Track01"), ou si le titre disponible ressemble au moins un minimum à
        // celui de la piste ciblée. Un simple numéro qui coïncide est un signal bien trop faible
        // pour un dossier qui n'est pas un vrai album : trouvé en observant un run réel où un
        // dossier "vrac" (morceaux totalement sans rapport partageant juste un préfixe numérique
        // coïncidant, ex. "1-01 10 Casseurs Flowters - ...") se faisait réassigner en masse aux
        // pistes d'un album MusicBrainz sans aucun rapport, sur le seul numéro extrait du nom.
        int num = extractTrackNumber(entry);
        if (num > 0) {
            for (MusicBrainzClient.ReleaseTrack t : tracks) {
                if (t.trackNo() != num) continue;
                if (normTitle.isBlank() || titlesResemble(normTitle, AlbumCompletionWorker.normalize(t.title())))
                    return t;
            }
        }

        // 2. Par titre normalisé
        if (normTitle.isBlank()) return null;

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

    /** Ressemblance minimale entre deux titres déjà normalisés : égalité, inclusion, ou mot significatif partagé. */
    private boolean titlesResemble(String a, String b) {
        if (a.isBlank() || b.isBlank()) return false;
        if (a.equals(b)) return true;
        if (a.length() >= 8 && (a.contains(b) || b.contains(a))) return true;
        java.util.Set<String> wordsA = new java.util.HashSet<>();
        for (String w : a.split("\\s+")) if (w.length() >= 4) wordsA.add(w);
        for (String w : b.split("\\s+")) if (w.length() >= 4 && wordsA.contains(w)) return true;
        return false;
    }

    /**
     * Extrait le numéro de piste depuis le tag ou le nom de fichier.
     * Gère "01 - title.mp3" mais aussi le préfixe "disque-piste" ("1-01 06 Title.m4a") :
     * dans ce cas le premier nombre ("1") n'est PAS le numéro de piste (c'est le disque, ou
     * un préfixe parasite) — on prend le DERNIER nombre de la série de préfixes numériques,
     * qui est toujours le plus proche du titre et donc le plus fiable.
     */
    private int extractTrackNumber(FileEntry entry) {
        if (entry.current != null && !entry.current.track.isBlank()) {
            try { return Integer.parseInt(entry.current.track.replaceAll("[^0-9]", "")); }
            catch (NumberFormatException ignored) {}
        }
        java.util.regex.Matcher prefix =
            java.util.regex.Pattern.compile("^(?:\\d{1,3}[\\s.\\-_]+)+").matcher(entry.filename());
        if (prefix.find()) {
            java.util.regex.Matcher nums = java.util.regex.Pattern.compile("\\d{1,3}").matcher(prefix.group());
            int last = 0;
            while (nums.find()) {
                try { last = Integer.parseInt(nums.group()); } catch (NumberFormatException ignored) {}
            }
            if (last > 0) return last;
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

    // mb/acoustId/lastFm passés en paramètres (et non les champs partagés du même nom) : chaque
    // fichier traité en parallèle doit avoir ses propres instances, ces 3 classes gardant un état
    // mutable entre appels (lastRawJson/networkCallMade, lastFingerprint/lastAcoustId,
    // cachedTagsKey/List) — les partager entre threads corromprait les résultats d'un fichier avec
    // ceux d'un autre traité au même moment (même risque déjà documenté dans BatchProcessor). Les
    // paramètres portent volontairement les mêmes noms que les champs de classe : ça masque les
    // champs dans toute cette méthode sans avoir à réécrire le moindre appel mb.xxx()/lastFm.xxx()
    // du corps existant.
    private void processEntry(FileEntry entry, Consumer<String> step,
                               MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm) {
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
            List<TagInfo> results = findTags(fichier, entry.current, entry.forceReidentify, mb, acoustId, lastFm);
            entry.forceReidentify = false;
            mb.setPreferredAlbum(""); // reset après findTags — clusterAlbums ne doit pas en bénéficier
            log(I18n.t("  findTags → %s résultat(s)%s", results.size(),
                results.isEmpty() ? "" : " score=" + results.get(0).score));

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty()) {
                entry.status  = FileEntry.Status.SKIPPED;
                entry.message = I18n.t("Non identifié");
                log(I18n.t("  SKIPPED (non identifié)"));
                return;
            }

            TagInfo best = results.get(0);

            if (best.score < seuil) {
                entry.candidates = results;
                entry.status  = FileEntry.Status.SKIPPED;
                entry.message = I18n.t("Score %s%% < %s%% — %s candidat(s)", best.score, seuil, results.size());
                log(I18n.t("  SKIPPED score trop bas"));
                return;
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
            TagEnrichment.enrichGenre(best, discogs, lastFm);
            log(I18n.t("  genre=%s", best.genre));

            if (best.mood.isBlank()) {
                try { lastFm.enrichMood(best); log(I18n.t("  mood←lastfm=%s", best.mood)); } catch (Exception ignored) {}
            }
            try { lastFm.enrichArtistUrls(best); } catch (Exception ignored) {}

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

            step.accept(I18n.t("pochette…"));
            log(I18n.t("  fanart/caa..."));
            Path cover = TagEnrichment.resolveCover(best, fichier, caa, fanArt);
            log(I18n.t("  cover=%s", cover != null ? cover.getFileName() : "null"));

            // Sauvegarde pochette en fichier séparé si configuré
            if (cover != null && Config.get().bool("cover.save_to_file", false)) {
                try {
                    String fname = Config.get().str("cover.filename", "cover");
                    String ext   = cover.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
                    java.nio.file.Path dest = fichier.toPath().resolveSibling(fname + ext);
                    if (!java.nio.file.Files.exists(dest) || Config.get().bool("cover.overwrite_file", false))
                        java.nio.file.Files.copy(cover, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            }

            // ── ReplayGain (avant écriture pour inclure dans le même commit) ────────
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

            step.accept(I18n.t("écriture tags…"));
            log(I18n.t("  write tags..."));
            best = writer.write(fichier, best, cover);

            // ── Renommage optionnel ───────────────────────────────────────
            String oldFilePath = fichier.getAbsolutePath();
            if (maskIndex >= 0) {
                step.accept(I18n.t("renommage…"));
                try {
                    Path curPath = fichier.toPath();
                    String libRoot = Config.get().libraryRoot();
                    boolean libRootValid = !libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot));
                    Path root = libRootValid
                            ? java.nio.file.Paths.get(libRoot)
                            : (entry.scanRoot != null ? entry.scanRoot : curPath.getParent());
                    String rootNote = libRoot.isBlank() ? I18n.t(" (aucune bibliothèque configurée)")
                           : libRootValid ? I18n.t(" (bibliothèque configurée)")
                           : I18n.t(" (bibliothèque configurée introuvable/inaccessible : '%s' → repli sur le dossier scanné)", libRoot);
                    log(I18n.t("  renommage → racine=%s%s", root, rootNote));
                    Path newPath = renamer.rename(curPath, best, maskIndex, root);
                    if (newPath != null) {
                        Path oldParent    = fichier.toPath().getParent();
                        entry.currentPath = newPath;
                        if (Config.get().deleteEmptyDirsAfterRename()) {
                            FileRenamer.deleteEmptyAncestors(oldParent, root);
                        }
                        entry.message = I18n.t("→ %s", newPath.getFileName());
                        log(I18n.t("  renommé → %s", newPath));
                    } else {
                        log(I18n.t("  renommage : déjà au bon endroit (chemin cible identique)"));
                    }
                } catch (Exception ex) {
                    // Ne plus avaler cette erreur en silence : sans ça, un fichier tagué mais dont
                    // le déplacement échoue (permissions, disque externe non réinscriptible,
                    // caractère non supporté par le système de fichiers cible...) restait dans son
                    // dossier d'origine sans aucune indication de la cause.
                    log(I18n.t("  ✗ renommage ÉCHOUÉ : %s — %s", ex.getClass().getSimpleName(), ex.getMessage()));
                    entry.message = I18n.t("Tagué, renommage échoué : %s",
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
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
            log(I18n.t("  ✔ TAGGED %s%s", fichier.getName(),
                sugg.isEmpty() ? "" : I18n.t(" (%s suggestion(s))", sugg.size())));

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
            cache.recordFileTagging(effectivePath, cacheKey, lastFindTagsSource.get());

            // AcoustIdSubmitter recalcule sa propre empreinte pour CE fichier (pas d'état
            // partagé entre fichiers, contrairement à l'ancien acoustId.submit() qui réutilisait
            // les champs d'instance du dernier identify() — supprimé, il envoyait de toute façon
            // un format de requête rejeté par l'API AcoustID, "missing required parameter
            // fingerprint" observé en production, corrigé dans AcoustIdSubmitter). On limite la
            // soumission automatique aux fichiers réellement identifiés via AcoustID pour ne pas
            // multiplier les appels réseau à chaque taguage — la soumission manuelle en masse
            // (bouton "Soumettre AcoustID") reste disponible pour les autres sources.
            if (MetadataCache.SOURCE_ACOUSTID.equals(lastFindTagsSource.get()) && !best.recordingMbid.isBlank()) {
                try {
                    new AcoustIdSubmitter().submit(fichier, best);
                } catch (Exception ex) {
                    log(I18n.t("  AcoustID submit skip: %s", ex.getMessage()));
                }
            }
            submitToMusicBrainz(best);

        } catch (Exception ex) {
            entry.status  = FileEntry.Status.ERROR;
            entry.message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log(I18n.t("  ✗ ERROR %s : %s", entry.filename(), entry.message));
        }
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }

    // ── Résolution des tags — avec cache SQLite ───────────────────────────────

    private List<TagInfo> findTags(File fichier, TagInfo existingTags, boolean forceReidentify,
                                    MusicBrainzClient mb, AcoustIdClient acoustId, LastFmClient lastFm) throws Exception {
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
        // Si le fichier a déjà releaseMbid + artist + title + album valides → confiance totale.
        // On ne ré-identifie pas ce que Picard a déjà fait : on garde l'album de compilation
        // tel quel, on évite deux allers-retours MB inutiles, et le taguage est instantané.
        if (!forceReidentify && Config.get().trustExistingMbTags() && existingTags != null
                && !existingTags.releaseMbid.isBlank()
                && !existingTags.artist.isBlank()
                && !existingTags.title.isBlank()
                && !existingTags.album.isBlank()
                && !isGenericTag(existingTags.artist)
                && !isGenericTag(existingTags.title)) {
            TagInfo t = existingTags.copy();
            t.score = 100;
            lastFindTagsSource.set(MetadataCache.SOURCE_MBID);
            log(I18n.t("  tags MB existants ✓ [releaseMbid=%s…] %s – %s [%s] → skip identification",
                existingTags.releaseMbid.substring(0, Math.min(8, existingTags.releaseMbid.length())),
                existingTags.artist, existingTags.title, existingTags.album));
            return List.of(t);
        }

        // 1. SongRec (Shazam) — empreinte audio, identifie la musique commerciale même avec
        //    de faux tags existants. Placé AVANT AcoustID pour être la source principale.
        if (SongRecClient.isAvailable()) {
            try {
                log(I18n.t("  SongRec..."));
                TagInfo sr = songRec.recognize(fichier);
                if (sr != null && !sr.artist.isBlank() && !sr.title.isBlank()) {
                    log(I18n.t("  SongRec → %s – %s", sr.artist, sr.title));
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
                    TagEnrichment.enrichGenre(sr, discogs, lastFm);
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
                if (!r.isEmpty()) {
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

        // 5c. SongRec — étape 1 : reconnaissance audio (empreinte Shazam gratuite)
        //              étape 2 : MB complète ce que SongRec a trouvé
        if (SongRecClient.isAvailable()) {
            log(nonLatinInput ? I18n.t("  SongRec (non-Latin)...") : I18n.t("  SongRec fallback..."));
            try {
                TagInfo sr = songRec.recognize(fichier);
                if (sr != null) {
                    log(I18n.t("  SongRec → %s – %s", sr.artist, sr.title));
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
                        log(I18n.t("  SongRec+MB → %s – %s [%s]", mbr.artist, mbr.title, mbr.album));
                        return List.of(mbr);
                    }
                    // MB échoue avec titre complet → réessayer sans qualificatif entre parenthèses
                    // ex: "Song Name (Home Demos)" → "Song Name"
                    String cleanTitle = sr.title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    if (!cleanTitle.equals(sr.title) && !cleanTitle.isBlank()) {
                        log(I18n.t("  SongRec+MB (titre nettoyé): '%s'", cleanTitle));
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
                            log(I18n.t("  SongRec+MB(nettoyé) → %s – %s [%s]", mbr.artist, mbr.title, mbr.album));
                            return List.of(mbr);
                        }
                    }
                    // MB ne confirme pas → garder les données SongRec + chercher artistMbid pour la pochette
                    if (sr.artistMbid.isBlank()) {
                        try {
                            String amid = mb.searchArtistMbid(sr.artist);
                            if (!amid.isBlank()) { sr.artistMbid = amid; log(I18n.t("  artistMbid←MB: %s", amid)); }
                        } catch (Exception ignored) {}
                    }
                    sr.score = 85;
                    log(I18n.t("  SongRec seul (MB non confirmé) → %s – %s", sr.artist, sr.title));
                    return List.of(sr);
                }
            } catch (Exception e) {
                log(I18n.t("  SongRec erreur: %s", e.getMessage()));
            }
        }

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
            log(I18n.t("  cluster: %s fichiers pour release %s", albumFiles.size(), releaseMbid));
            try {
                MusicBrainzClient.ReleaseTracklist tracklist = mb.lookupRelease(releaseMbid);
                if (tracklist == null || tracklist.tracks().isEmpty()) continue;

                int maxDisc = tracklist.tracks().stream().mapToInt(MusicBrainzClient.ReleaseTrack::disc).max().orElse(0);

                for (FileEntry entry : albumFiles) {
                    TagInfo result = entry.result;
                    File entryFile = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                    MusicBrainzClient.ReleaseTrack matched = findBestTrack(tracklist, result, entryFile);
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
                        log(I18n.t("  cluster ok: %s → piste %s/%s", fichier.getName(), result.track, result.trackTotal));
                        entry.result = result;
                        publish(entry);
                    }
                }
                // ── Album ReplayGain (concat analyse) ─────────────────────────────
                if (rgEnabled) {
                    java.util.List<String> paths = albumFiles.stream()
                        .map(e -> e.currentPath != null ? e.currentPath.toString() : e.file.getAbsolutePath())
                        .collect(java.util.stream.Collectors.toList());
                    log(I18n.t("  album RG: analyse %s pistes...", paths.size()));
                    ReplayGainAnalyzer.RGResult albumRg = ReplayGainAnalyzer.analyzeAlbum(paths);
                    if (albumRg != null) {
                        log(I18n.t("  album RG: gain=%s peak=%s", albumRg.trackGain(), albumRg.trackPeak()));
                        for (FileEntry entry : albumFiles) {
                            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                            writer.writeAlbumReplayGain(f, albumRg.trackGain(), albumRg.trackPeak());
                        }
                    }
                }

            } catch (Exception e) {
                log(I18n.t("  cluster erreur: %s", e.getMessage()));
            }
        }
    }

    /** Tolérance de matching par durée entre le fichier et la piste MB (rips/encodages varient de 1-2 s). */
    private static final int DURATION_TOLERANCE_SEC = 3;

    private MusicBrainzClient.ReleaseTrack findBestTrack(MusicBrainzClient.ReleaseTracklist tracklist,
                                                          TagInfo result, File audioFile) {
        // 1. Correspondance par recordingMbid (100% fiable)
        if (!result.recordingMbid.isBlank()) {
            for (var t : tracklist.tracks())
                if (result.recordingMbid.equals(t.recordingMbid())) return t;
        }
        String titleLow = result.title.toLowerCase().trim();

        // 2. Correspondance par numéro de piste (+ disque si connu). Sur une release multi-disques,
        // si le disque n'est pas connu et que plusieurs disques ont ce numéro de piste, NE PAS
        // deviner (bug corrigé : renvoyait silencieusement le disque 1 par défaut) — laisser
        // les étapes suivantes (durée, titre) trancher.
        if (!result.track.isBlank()) {
            try {
                int n = Integer.parseInt(result.track.trim());
                Integer d = result.discNo.isBlank() ? null : Integer.parseInt(result.discNo.trim());
                List<MusicBrainzClient.ReleaseTrack> sameNumber = new java.util.ArrayList<>();
                for (var t : tracklist.tracks()) {
                    if (t.trackNo() != n) continue;
                    if (d != null && t.disc() != 0 && t.disc() != d) continue;
                    sameNumber.add(t);
                }
                if (sameNumber.size() == 1) return sameNumber.get(0);
            } catch (NumberFormatException ignored) {}
        }

        // 3. Correspondance par durée du fichier (utile quand ni le numéro de piste ni le titre
        // ne permettent de trancher — fichier générique/mal renseigné, comme Picard le fait).
        int fileDurSec = audioFile != null ? AudioDuration.probeSeconds(audioFile.getAbsolutePath()) : -1;
        if (fileDurSec > 0) {
            List<MusicBrainzClient.ReleaseTrack> withinTolerance = new java.util.ArrayList<>();
            for (var t : tracklist.tracks()) {
                if (t.lengthMs() <= 0) continue;
                if (Math.abs(t.lengthMs() / 1000 - fileDurSec) <= DURATION_TOLERANCE_SEC) withinTolerance.add(t);
            }
            if (withinTolerance.size() == 1) return withinTolerance.get(0);
            if (withinTolerance.size() > 1 && !titleLow.isBlank()) {
                MusicBrainzClient.ReleaseTrack best = null;
                int bestScore = 0;
                for (var t : withinTolerance) {
                    int sim = titleSimilarity(titleLow, t.title().toLowerCase().trim());
                    if (sim > bestScore) { bestScore = sim; best = t; }
                }
                if (best != null) return best;
            }
        }

        // 4. Correspondance par similarité de titre (dernier recours, toute la tracklist)
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
            s.add(I18n.t("Score modéré (%s%%) — vérifier l'identification", best.score));
        if (cover == null)
            s.add(I18n.t("Pochette non trouvée"));
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

    /**
     * Soumet genres et rating à MusicBrainz si le token OAuth est disponible.
     * Silencieux : une erreur n'interrompt pas le tagging local.
     */
    private void submitToMusicBrainz(TagInfo info) {
        TagEnrichment.submitToMusicBrainz(mbOauth, info, msg -> log("  " + msg));
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
