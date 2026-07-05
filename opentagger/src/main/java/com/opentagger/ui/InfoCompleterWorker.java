package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Passe complète style Jaikoz — enrichit les champs manquants de fichiers déjà identifiés.
 *
 * Contrairement à TaggingWorker, ne re-identifie pas le morceau (on connaît déjà
 * artiste + titre). Complète uniquement les champs vides :
 *   - album / année  → MB search (titre exact, puis titre nettoyé, puis artiste seul)
 *   - artistMbid     → MB artist search
 *   - genre          → Discogs puis Last.fm
 *   - mood           → Last.fm
 *   - BPM            → ffmpeg
 *   - paroles        → LyricsClient
 *   - pochette       → FanArt.tv / Cover Art Archive (si absente du fichier)
 *
 * Parallélisé (un thread-pool, même clé de config "batch.threads" que TaggingWorker/
 * BatchProcessor/AlbumCompletionWorker) — avant ça, cette passe traitait un fichier à la fois
 * malgré exactement le même profil d'appels bloquants (MB, Discogs/Last.fm, BPM ffmpeg, paroles,
 * pochette, écriture) que TaggingWorker avant sa propre parallélisation. MusicBrainzClient et
 * LastFmClient tiennent un état mutable entre appels (même règle déjà établie ailleurs) — instance
 * fraîche par tâche ; Discogs/FanArt/Caa/Lyrics/BpmDetector/TagWriter sont sans état, et
 * TaggerScript/FileRenamer/MetadataCache sont protégés en interne par leurs propres synchronized —
 * tous les quatre restent des champs partagés, comme dans TaggingWorker.
 */
public class InfoCompleterWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>        entries;
    private final Consumer<String>       onProgress;
    private final Consumer<FileEntry>    onUpdate;
    private final BiConsumer<Integer,Integer> onCount; // (done, total)

    private final DiscogsClient     discogs = new DiscogsClient();
    private final FanArtClient      fanArt  = new FanArtClient();
    private final CaaClient         caa     = new CaaClient();
    private final TaggerScript      taggerScript = new TaggerScript();
    private final java.util.Map<String, String> aliasCache = new ConcurrentHashMap<>();
    private final LyricsClient      lyrics  = new LyricsClient();
    private final BpmDetector       bpmDet  = new BpmDetector();
    private final TagWriter         writer  = new TagWriter();
    private final MetadataCache     cache   = new MetadataCache();
    private final FileRenamer       renamer = new FileRenamer();
    private final MusicBrainzOAuth  mbOauth = new MusicBrainzOAuth();

    private final boolean bpmEnabled   = BpmDetector.isAvailable();
    private final boolean autoRename   = Config.get().autoRenameEnabled();
    private final int     autoMaskIdx  = Config.get().defaultRenameMask();

    private final AtomicInteger doneCount = new AtomicInteger();

    public InfoCompleterWorker(List<FileEntry> entries,
                               Consumer<String> onProgress,
                               Consumer<FileEntry> onUpdate,
                               BiConsumer<Integer,Integer> onCount) {
        this.entries    = entries;
        this.onProgress = onProgress;
        this.onUpdate   = onUpdate;
        this.onCount    = onCount;
    }

    @Override
    protected Void doInBackground() throws Exception {
        int total = entries.size();

        int threads = Math.max(1, Config.get().num("batch.threads", 3));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            if (isCancelled()) break;
            final FileEntry entry  = entries.get(i);
            final int       fileIdx = i + 1;
            futures.add(pool.submit(() ->
                    processOne(entry, fileIdx, total, new MusicBrainzClient(), new LastFmClient())));
        }

        pool.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        // cache n'était jamais fermé avant — connexion SQLite qui fuyait pour toute la durée de
        // vie de l'objet (contrairement à AlbumCompletionWorker/TaggingWorker, qui ferment déjà
        // leur MetadataCache). Fermer seulement après que toutes les tâches ont fini d'écrire.
        cache.close();
        return null;
    }

    /** Traite un fichier. Appelé en parallèle, une tâche par fichier, depuis le pool créé dans
     *  doInBackground(). */
    private void processOne(FileEntry entry, int fileIdx, int total,
                             MusicBrainzClient mb, LastFmClient lastFm) {
        if (isCancelled()) return;

        File fichier = entry.currentPath != null
                ? entry.currentPath.toFile()
                : entry.file;

        onProgress.accept("[" + fileIdx + "/" + total + "] " + fichier.getName());

        if (!fichier.exists()) {
            log("▶ SKIP " + fichier.getName() + " (fichier introuvable)");
            onCount.accept(doneCount.incrementAndGet(), total);
            publish(entry);
            return;
        }

        log("▶ COMPLÉTER " + fichier.getName());

        try {
            completeEntry(entry, fichier, mb, lastFm);
        } catch (Exception ex) {
            log("  ✗ erreur: " + ex.getMessage());
        }

        onCount.accept(doneCount.incrementAndGet(), total);
        publish(entry);
    }

    private void completeEntry(FileEntry entry, File fichier, MusicBrainzClient mb, LastFmClient lastFm) throws Exception {
        // Lire le TagInfo actuel depuis e.result ou depuis le fichier
        TagInfo ti = entry.result != null ? entry.result : readTagsFromFile(fichier);
        if (ti == null || (ti.artist.isBlank() && ti.title.isBlank())) {
            log("  ignoré (artiste+titre vides)");
            return;
        }

        boolean changed = false;

        // ── 1. MusicBrainz : album, année, IDs ────────────────────────────
        // Condition allégée : déclencher uniquement si données essentielles manquantes
        boolean needsMb = ti.album.isBlank() || ti.year.isBlank() || ti.artistMbid.isBlank();
        if (needsMb) {
            TagInfo mbr = null;

            // Lookup direct si recordingMbid connu → plus rapide et précis que text search
            if (!ti.recordingMbid.isBlank()) {
                log("  MB lookup: " + ti.recordingMbid);
                TagInfo full = mb.lookupRecording(ti.recordingMbid);
                if (full != null && !full.title.isBlank()) {
                    mbr = full;
                    log("  MB lookup→ " + full.artist + " – " + full.title + " [" + full.album + " " + full.year + "]");
                }
            }

            // Fallback : recherche texte si lookup échoue ou MBID absent
            if (mbr == null) {
                log("  MB search: '" + ti.artist + "' / '" + ti.title + "'");
                mbr = mbLookup(mb, ti.artist, ti.title);
                if (mbr == null && ti.title.contains("(")) {
                    String clean = ti.title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    log("  MB search (nettoyé): '" + clean + "'");
                    mbr = mbLookup(mb, ti.artist, clean);
                }
                if (mbr == null && !ti.title.isBlank()) {
                    // Dernier essai avec titre tronqué — seuil plus élevé (90%)
                    // pour éviter d'accepter un enregistrement différent partageant les 2 premiers mots
                    String[] words = ti.title.split("\\s+");
                    if (words.length > 1) {
                        String short1 = words[0] + " " + words[1];
                        TagInfo candidate = mbLookupStrict(mb, ti.artist, short1, 90);
                        if (candidate != null && titlesSimilar(ti.title, candidate.title)) {
                            mbr = candidate;
                            log("  MB search (titre court): '" + short1 + "'");
                        }
                    }
                }
            }

            if (mbr != null) {
                log("  MB trouvé: " + mbr.artist + " – " + mbr.title + " [" + mbr.album + " " + mbr.year + "]");
                changed |= fillBlank(ti, "album",             mbr.album);
                changed |= fillBlank(ti, "year",              mbr.year);
                changed |= fillBlank(ti, "track",             mbr.track);
                changed |= fillBlank(ti, "trackTotal",        mbr.trackTotal);
                changed |= fillBlank(ti, "discNo",            mbr.discNo);
                changed |= fillBlank(ti, "albumArtist",       mbr.albumArtist);
                changed |= fillBlank(ti, "albumArtistSort",   mbr.albumArtistSort);
                changed |= fillBlank(ti, "artistSort",        mbr.artistSort);
                changed |= fillBlank(ti, "artistMbid",        mbr.artistMbid);
                changed |= fillBlank(ti, "releaseMbid",       mbr.releaseMbid);
                changed |= fillBlank(ti, "releaseGroupMbid",  mbr.releaseGroupMbid);
                changed |= fillBlank(ti, "recordingMbid",     mbr.recordingMbid);
                changed |= fillBlank(ti, "isrc",              mbr.isrc);
                changed |= fillBlank(ti, "language",          mbr.language);
                changed |= fillBlank(ti, "script",            mbr.script);
                changed |= fillBlank(ti, "country",           mbr.country);
                changed |= fillBlank(ti, "releaseType",       mbr.releaseType);
                changed |= fillBlank(ti, "originalYear",      mbr.originalYear);
                changed |= fillBlank(ti, "artists",           mbr.artists);
                changed |= fillBlank(ti, "artistsSort",       mbr.artistsSort);
            } else if (ti.artistMbid.isBlank()) {
                // Fallback minimal : artistMbid pour la pochette
                log("  MB artist search: '" + ti.artist + "'");
                String amid = mb.searchArtistMbid(ti.artist);
                if (!amid.isBlank()) {
                    ti.artistMbid = amid;
                    log("  artistMbid←MB: " + amid);
                    changed = true;
                }
            }
        }

        // ── 2. Genre ──────────────────────────────────────────────────────
        String genreBefore = ti.genre;
        TagEnrichment.enrichGenre(ti, discogs, lastFm);
        if (!ti.genre.equals(genreBefore)) { log("  genre=" + ti.genre); changed = true; }

        // ── 3. Mood ───────────────────────────────────────────────────────
        if (ti.mood.isBlank()) {
            try { lastFm.enrichMood(ti); if (!ti.mood.isBlank()) { log("  mood←lastfm=" + ti.mood); changed = true; } } catch (Exception ignored) {}
        }

        // ── 3b. Translittération artiste (si nom non-Latin et option activée) ───
        String artistBeforeTranslit = ti.artist;
        TagEnrichment.translateArtist(ti, mb, aliasCache);
        if (!ti.artist.equals(artistBeforeTranslit)) {
            log("  translit: " + artistBeforeTranslit + " → " + ti.artist);
            changed = true;
        }

        // ── 4. BPM ────────────────────────────────────────────────────────
        if (ti.bpm.isBlank() && bpmEnabled) {
            int bpm = bpmDet.detect(fichier.getAbsolutePath());
            if (bpm > 0) { ti.bpm = String.valueOf(bpm); log("  bpm=" + bpm); changed = true; }
        }

        // ── 5. Paroles ─────────────────────────────────────────────────────
        if (ti.lyrics.isBlank()) {
            try {
                String before = ti.lyrics;
                lyrics.enrich(ti);
                if (!ti.lyrics.equals(before)) { log("  lyrics trouvées"); changed = true; }
            } catch (Exception ignored) {}
        }

        // ── 6. Pochette (vérifier si absente du fichier) ──────────────────
        Path cover = null;
        if (!hasCoverInFile(fichier)) {
            log("  pochette manquante → recherche (CAA/local/fanart)...");
            cover = TagEnrichment.resolveCover(ti, fichier, caa, fanArt);
            log("  cover=" + (cover != null ? cover.getFileName() : "null"));
            if (cover != null) changed = true;
        }

        // ── 7. Script tagger utilisateur (avant le test de changement : un script
        // activé est une modification intentionnelle même si non détectable par diff) ──
        if (taggerScript.apply(ti)) changed = true;

        // ── 8. Écriture si changement ─────────────────────────────────────
        if (changed || cover != null) {
            writer.write(fichier, ti, cover);
            String icKey = !ti.recordingMbid.isBlank()
                    ? ti.recordingMbid
                    : MetadataCache.syntheticKey(ti.artist, ti.title);
            cache.saveTaggingHistory(ti, icKey);
            // Toutes les autres pipelines (TaggingWorker/BatchProcessor/App/MatchDialog/
            // AlbumCompletionWorker/PodcastWorker) appellent aussi recordFileTagging — absent
            // ici jusqu'à présent. Cas concret : recordingMbid était vide et vient d'être rempli
            // (étape 1 ci-dessus, MB release lookup) sans jamais mettre à jour cette association
            // fichier→MBID dans le cache.
            cache.recordFileTagging(fichier.getAbsolutePath(), ti.recordingMbid);
            // ── 9. Renommage automatique ──────────────────────────────────
            java.nio.file.Path newPath = null;
            String renameError = null;
            if (autoRename) {
                try {
                    java.nio.file.Path curPath = entry.currentPath != null
                            ? entry.currentPath : fichier.toPath();
                    java.nio.file.Path oldParent = curPath.getParent();
                    // Même résolution de racine que TaggingWorker : "dossier racine bibliothèque"
                    // prioritaire s'il est configuré, sinon le dossier scanné (avant : toujours
                    // scanRoot, ignorant silencieusement ce réglage pour la passe complète).
                    String libRoot = Config.get().libraryRoot();
                    java.nio.file.Path root =
                        (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                            ? java.nio.file.Paths.get(libRoot)
                            : (entry.scanRoot != null ? entry.scanRoot : oldParent);
                    newPath = renamer.rename(curPath, ti, autoMaskIdx, root);
                    if (newPath != null) {
                        if (Config.get().deleteEmptyDirsAfterRename()) {
                            FileRenamer.deleteEmptyAncestors(oldParent, root);
                        }
                        log("  renommé → " + newPath);
                    }
                } catch (Exception ex) {
                    // Ne plus se contenter d'un log console : sans indication dans l'UI, un
                    // déplacement qui échoue (permissions, disque cible, etc.) est invisible.
                    renameError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    log("  renommage échoué: " + renameError);
                }
            }
            // Muter entry SUR l'EDT, pas ici : ce FileEntry est aussi lu par le TableRowSorter
            // en direct depuis l'EDT, et une mutation concurrente pendant un tri casse le
            // contrat de Comparator (déjà vu 697× en 3 jours dans TaggingWorker — même défaut).
            final java.nio.file.Path finalNewPath = newPath;
            final String finalRenameError = renameError;
            SwingUtilities.invokeLater(() -> {
                entry.result = ti;
                if (finalNewPath != null) entry.currentPath = finalNewPath;
                if (finalRenameError != null) entry.message = "Renommage échoué : " + finalRenameError;
            });
            log("  ✔ mis à jour");
            submitToMusicBrainz(ti);
        } else {
            log("  — déjà complet, rien à faire");
        }
    }

    private void submitToMusicBrainz(TagInfo info) {
        TagEnrichment.submitToMusicBrainz(mbOauth, info, msg -> log("  " + msg));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Cherche dans MB et retourne le meilleur résultat si score ≥ 70, sinon null. */
    private TagInfo mbLookup(MusicBrainzClient mb, String artist, String title) {
        return mbLookupStrict(mb, artist, title, 70);
    }

    /** Cherche dans MB et retourne le meilleur résultat si score ≥ minScore, sinon null. */
    private TagInfo mbLookupStrict(MusicBrainzClient mb, String artist, String title, int minScore) {
        try {
            List<TagInfo> results = mb.searchRecording(artist, title);
            if (!results.isEmpty() && results.get(0).score >= minScore)
                return results.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** Retourne true si deux titres partagent ≥ 60% de leurs mots (après normalisation). */
    private static boolean titlesSimilar(String a, String b) {
        if (a == null || b == null) return false;
        String[] wa = a.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        String[] wb = b.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        if (wa.length == 0 || wb.length == 0) return false;
        java.util.Set<String> setB = new java.util.HashSet<>(java.util.Arrays.asList(wb));
        int shared = 0;
        for (String w : wa) if (setB.contains(w)) shared++;
        return shared >= Math.max(1, (int)(wa.length * 0.6));
    }

    /** Remplit un champ vide. Retourne true si modifié. */
    private boolean fillBlank(TagInfo ti, String field, String value) {
        if (value == null || value.isBlank()) return false;
        try {
            var f = TagInfo.class.getField(field);
            String cur = (String) f.get(ti);
            if (cur == null || cur.isBlank()) { f.set(ti, value); return true; }
        } catch (Exception ignored) {}
        return false;
    }

    /** Lit les tags essentiels depuis le fichier (artist, title, album, year, mbids…). */
    private TagInfo readTagsFromFile(File f) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            if (tag == null) return null;
            TagInfo ti = new TagInfo();
            ti.artist          = tag.getFirst(FieldKey.ARTIST);
            ti.title           = tag.getFirst(FieldKey.TITLE);
            ti.album           = tag.getFirst(FieldKey.ALBUM);
            ti.albumArtist     = tag.getFirst(FieldKey.ALBUM_ARTIST);
            ti.year            = tag.getFirst(FieldKey.YEAR);
            ti.track           = tag.getFirst(FieldKey.TRACK);
            ti.genre           = tag.getFirst(FieldKey.GENRE);
            ti.bpm             = tag.getFirst(FieldKey.BPM);
            ti.mood            = tag.getFirst(FieldKey.MOOD);
            ti.lyrics          = tag.getFirst(FieldKey.LYRICS);
            ti.trackTotal       = tag.getFirst(FieldKey.TRACK_TOTAL);
            ti.discNo           = tag.getFirst(FieldKey.DISC_NO);
            ti.discTotal        = tag.getFirst(FieldKey.DISC_TOTAL);
            ti.artistSort       = tag.getFirst(FieldKey.ARTIST_SORT);
            ti.albumArtistSort  = tag.getFirst(FieldKey.ALBUM_ARTIST_SORT);
            ti.isrc             = tag.getFirst(FieldKey.ISRC);
            ti.language         = tag.getFirst(FieldKey.LANGUAGE);
            ti.script           = tag.getFirst(FieldKey.SCRIPT);
            ti.artistMbid       = tag.getFirst(FieldKey.MUSICBRAINZ_ARTISTID);
            ti.releaseMbid      = tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID);
            ti.recordingMbid    = tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID);
            ti.releaseGroupMbid = tag.getFirst(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);
            // Nettoyer les nulls
            var cls = TagInfo.class;
            for (var fld : cls.getFields()) {
                if (fld.getType() == String.class && fld.get(ti) == null)
                    fld.set(ti, "");
            }
            return ti;
        } catch (Exception e) { return null; }
    }

    /** Vérifie si le fichier a déjà une pochette embarquée. */
    private boolean hasCoverInFile(File f) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            return tag != null && tag.getFirstArtwork() != null;
        } catch (Exception e) { return false; }
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }
}
