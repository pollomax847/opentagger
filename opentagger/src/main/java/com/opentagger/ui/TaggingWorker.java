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
 *  1. Cache SQLite   → résultat instantané si déjà connu
 *  2. AcoustID       → fingerprint (optionnel)
 *  3. MusicBrainz    → recherche par texte
 *  4. Shazam         → fallback si non identifié par AcoustID/MB (nécessite clé RapidAPI)
 *  5. LocalCorrector → corrections scripts Jaikoz (5 scripts)
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
        int total = entries.size();
        int done  = 0;

        for (FileEntry entry : entries) {
            if (isCancelled()) break;

            entry.status = FileEntry.Status.PROCESSING;
            publish(entry);
            final int fileIdx   = done + 1;
            final int fileTotal = total;
            final String fname  = entry.filename();
            Consumer<String> step = s -> onProgress.accept(
                String.format("[%d/%d] %s — %s", fileIdx, fileTotal, fname, s));
            step.accept("identification…");

            processEntry(entry, step);

            // Si annulé pendant processEntry, remettre l'entrée en attente
            if (isCancelled() && entry.status == FileEntry.Status.PROCESSING) {
                entry.status  = FileEntry.Status.PENDING;
                entry.message = "";
            }

            setProgress((++done * 100) / total);
            publish(entry);

            // Rate-limit MusicBrainz (1 req/s max)
            if (!isCancelled() && done < total) sleep(1100);
        }

        // Remettre en attente toute entrée restée bloquée en PROCESSING
        for (FileEntry entry : entries) {
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
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
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

            log("▶ START  " + fichier.getName());

            log("  findTags...");
            List<TagInfo> results = findTags(fichier);
            log("  findTags → " + results.size() + " résultat(s)" +
                (results.isEmpty() ? "" : " score=" + results.get(0).score));

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty() || results.get(0).score < seuil) {
                step.accept("reconnaissance SongRec/Shazam/AudD…");
                log("  chain SongRec→Shazam→AudD...");
                List<TagInfo> chain = recognitionChain.recognize(fichier);
                log("  chain → " + chain.size() + " résultat(s)");
                if (!chain.isEmpty()) results = chain;
            }

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

            // Fallback MB search : si album ou artistMbid toujours vides après lookup
            // (pas de release dans MB, ou SongRec-only sans MBID).
            // Pause rate-limit MB entre les deux blocs pour ne pas envoyer deux requêtes à la suite.
            if ((best.album.isBlank() || best.year.isBlank()) && !best.recordingMbid.isBlank()) {
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
            // 0. Pochette locale existante dans le dossier du fichier (folder.jpg, cover.jpg…)
            if (Config.get().coverSearchLocal()) {
                try { cover = findLocalCover(fichier.getParentFile()); } catch (Exception ignored) {}
                if (cover != null) log("  cover←local: " + cover.getFileName());
            }
            // 1. Cover Art Archive (MB officiel, sans clé API, lié à la release exacte)
            if (cover == null && (!best.releaseMbid.isBlank() || !best.releaseGroupMbid.isBlank())) {
                try { cover = caa.downloadFront(best); } catch (Exception ignored) {}
            }
            // 2. FanArt.tv en fallback (meilleur pour les images artiste)
            if (cover == null && Config.get().fanartEnabled() && !best.artistMbid.isBlank()) {
                try { cover = fanArt.downloadCover(best); } catch (Exception ignored) {}
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
            writer.write(fichier, best, cover);

            // ── Renommage optionnel ───────────────────────────────────────
            if (maskIndex >= 0) {
                step.accept("renommage…");
                try {
                    Path curPath = fichier.toPath();
                    Path root    = entry.scanRoot != null ? entry.scanRoot : curPath.getParent();
                    Path newPath = renamer.rename(curPath, best, maskIndex, root);
                    if (newPath != null) {
                        Path oldParent    = fichier.toPath().getParent();
                        entry.currentPath = newPath;
                        FileRenamer.deleteEmptyAncestors(oldParent, root);
                        entry.message = "→ " + newPath.getFileName();
                    }
                } catch (Exception ignored) {}
            }

            entry.result = best;
            entry.status = FileEntry.Status.TAGGED;
            log("  ✔ TAGGED " + fichier.getName());

            // Clé cache : MBID réel si disponible, sinon clé synthétique artist+title
            // Garantit que même les résultats SongRec-only sont mémorisés et ne repassent pas en PENDING
            String cacheKey = !best.recordingMbid.isBlank()
                ? best.recordingMbid
                : MetadataCache.syntheticKey(best.artist, best.title);
            cache.saveTaggingHistory(best, cacheKey);
            cache.recordFileTagging(fichier.getAbsolutePath(), cacheKey);

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

    private List<TagInfo> findTags(File fichier) throws Exception {
        // 0. Historique personnel — ce fichier a-t-il déjà été tagué par OpenTagger ?
        //    Retour instantané sans réseau : c'est le cœur de la "mémoire" personnelle.
        String knownMbid = cache.getFileTagging(fichier.getAbsolutePath());
        if (knownMbid != null) knownMbid = knownMbid.trim();
        if (knownMbid != null && !knownMbid.isBlank()) {
            TagInfo hist = cache.getTaggingHistory(knownMbid);
            // Valider : ne pas utiliser un TagInfo avec artist ET title vides
            if (hist != null && (!hist.artist.isBlank() || !hist.title.isBlank())) {
                hist.score = 100;
                log("  cache hit: " + hist.artist + " – " + hist.title);
                return List.of(hist);
            } else if (hist != null) {
                log("  cache hit IGNORÉ (artiste+titre vides) pour mbid=" + knownMbid);
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
                if (!r.isEmpty()) return r;
            }
        }

        // 3. Tags texte existants, avec fallback sur le nom de fichier
        String artist = readTag(fichier, FieldKey.ARTIST);
        String title  = readTag(fichier, FieldKey.TITLE);

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
                        writer.write(fichier, result);
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

                sleep(1100);
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
