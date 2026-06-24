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
 */
public class InfoCompleterWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>        entries;
    private final Consumer<String>       onProgress;
    private final Consumer<FileEntry>    onUpdate;
    private final BiConsumer<Integer,Integer> onCount; // (done, total)

    private final MusicBrainzClient mb      = new MusicBrainzClient();
    private final DiscogsClient     discogs = new DiscogsClient();
    private final LastFmClient      lastFm  = new LastFmClient();
    private final FanArtClient      fanArt  = new FanArtClient();
    private final LyricsClient      lyrics  = new LyricsClient();
    private final BpmDetector       bpmDet  = new BpmDetector();
    private final TagWriter         writer  = new TagWriter();
    private final MetadataCache     cache   = new MetadataCache();
    private final FileRenamer       renamer = new FileRenamer();
    private final MusicBrainzOAuth  mbOauth = new MusicBrainzOAuth();

    private final boolean bpmEnabled   = BpmDetector.isAvailable();
    private final boolean autoRename   = Config.get().autoRenameEnabled();
    private final int     autoMaskIdx  = Config.get().defaultRenameMask();

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
        int done  = 0;

        for (FileEntry entry : entries) {
            if (isCancelled()) break;

            File fichier = entry.currentPath != null
                    ? entry.currentPath.toFile()
                    : entry.file;

            onProgress.accept("[" + (done+1) + "/" + total + "] " + fichier.getName());

            if (!fichier.exists()) {
                log("▶ SKIP " + fichier.getName() + " (fichier introuvable)");
                done++;
                onCount.accept(done, total);
                publish(entry);
                continue;
            }

            log("▶ COMPLÉTER " + fichier.getName());

            try {
                completeEntry(entry, fichier);
            } catch (Exception ex) {
                log("  ✗ erreur: " + ex.getMessage());
            }

            done++;
            onCount.accept(done, total);
            publish(entry);
        }
        return null;
    }

    private void completeEntry(FileEntry entry, File fichier) throws Exception {
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
                Thread.sleep(1100);
                if (full != null && !full.title.isBlank()) {
                    mbr = full;
                    log("  MB lookup→ " + full.artist + " – " + full.title + " [" + full.album + " " + full.year + "]");
                }
            }

            // Fallback : recherche texte si lookup échoue ou MBID absent
            if (mbr == null) {
                log("  MB search: '" + ti.artist + "' / '" + ti.title + "'");
                mbr = mbLookup(ti.artist, ti.title);
                if (mbr == null && ti.title.contains("(")) {
                    String clean = ti.title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    log("  MB search (nettoyé): '" + clean + "'");
                    mbr = mbLookup(ti.artist, clean);
                }
                if (mbr == null && !ti.title.isBlank()) {
                    // Dernier essai : artiste seul (titre trop spécifique)
                    String[] words = ti.title.split("\\s+");
                    if (words.length > 1) {
                        String short1 = words[0] + " " + words[1];
                        mbr = mbLookup(ti.artist, short1);
                        if (mbr != null) log("  MB search (titre court): '" + short1 + "'");
                    }
                }
                Thread.sleep(1100);
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
            } else if (ti.artistMbid.isBlank()) {
                // Fallback minimal : artistMbid pour la pochette
                log("  MB artist search: '" + ti.artist + "'");
                String amid = mb.searchArtistMbid(ti.artist);
                if (!amid.isBlank()) {
                    ti.artistMbid = amid;
                    log("  artistMbid←MB: " + amid);
                    changed = true;
                }
                Thread.sleep(1100);
            }
        }

        // ── 2. Genre ──────────────────────────────────────────────────────
        if (ti.genre.isBlank()) {
            try { discogs.enrichGenres(ti); if (!ti.genre.isBlank()) { log("  genre←discogs=" + ti.genre); changed = true; } } catch (Exception ignored) {}
        }
        if (ti.genre.isBlank()) {
            try { lastFm.enrichGenres(ti);  if (!ti.genre.isBlank()) { log("  genre←lastfm="  + ti.genre); changed = true; } } catch (Exception ignored) {}
        }

        // ── 3. Mood ───────────────────────────────────────────────────────
        if (ti.mood.isBlank()) {
            try { lastFm.enrichMood(ti); if (!ti.mood.isBlank()) { log("  mood←lastfm=" + ti.mood); changed = true; } } catch (Exception ignored) {}
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
        if (!ti.artistMbid.isBlank() && !hasCoverInFile(fichier)) {
            log("  pochette manquante → fanart...");
            try { cover = fanArt.downloadCover(ti); }
            catch (Exception ignored) {}
            log("  cover=" + (cover != null ? cover.getFileName() : "null"));
            if (cover != null) changed = true;
        }

        // ── 7. Écriture si changement ─────────────────────────────────────
        if (changed || cover != null) {
            writer.write(fichier, ti, cover);
            entry.result = ti;
            String icKey = !ti.recordingMbid.isBlank()
                    ? ti.recordingMbid
                    : MetadataCache.syntheticKey(ti.artist, ti.title);
            cache.saveTaggingHistory(ti, icKey);
            // ── 8. Renommage automatique ──────────────────────────────────
            if (autoRename) {
                try {
                    java.nio.file.Path curPath = entry.currentPath != null
                            ? entry.currentPath : fichier.toPath();
                    java.nio.file.Path oldParent = curPath.getParent();
                    java.nio.file.Path root = entry.scanRoot != null ? entry.scanRoot : oldParent;
                    java.nio.file.Path newPath = renamer.rename(curPath, ti, autoMaskIdx, root);
                    if (newPath != null) {
                        entry.currentPath = newPath;
                        FileRenamer.deleteEmptyAncestors(oldParent, root);
                        log("  renommé → " + newPath.getFileName());
                    }
                } catch (Exception ex) {
                    log("  renommage échoué: " + ex.getMessage());
                }
            }
            log("  ✔ mis à jour");
            submitToMusicBrainz(ti);
        } else {
            log("  — déjà complet, rien à faire");
        }
    }

    private void submitToMusicBrainz(TagInfo info) {
        String token = Config.get().str("mb.oauth.token", "");
        if (token.isBlank() || info.recordingMbid.isBlank()) return;

        java.util.List<String> tags = new java.util.ArrayList<>();
        if (!info.genre.isBlank())
            java.util.Arrays.stream(info.genre.split(",")).map(String::trim)
                    .filter(s -> !s.isBlank()).forEach(tags::add);
        if (!info.mood.isBlank()) tags.add(info.mood);

        try {
            if (!tags.isEmpty()) { mbOauth.submitUserTags(info.recordingMbid, tags, token); log("  MB tags soumis"); }
        } catch (Exception e) { log("  MB tags skip: " + e.getMessage()); }

        try {
            int rating = parseStars(info.rating);
            if (rating > 0) { mbOauth.submitRating(info.recordingMbid, rating, token); log("  MB rating soumis: " + rating); }
        } catch (Exception e) { log("  MB rating skip: " + e.getMessage()); }
    }

    private static int parseStars(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= 1 && v <= 5) return v;
            if (v >= 6 && v <= 255) return Math.min(5, (v + 25) / 51);
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Cherche dans MB et retourne le meilleur résultat si score ≥ 70, sinon null. */
    private TagInfo mbLookup(String artist, String title) {
        try {
            List<TagInfo> results = mb.searchRecording(artist, title);
            if (!results.isEmpty() && results.get(0).score >= 70)
                return results.get(0);
        } catch (Exception ignored) {}
        return null;
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
            ti.artistMbid      = tag.getFirst(FieldKey.MUSICBRAINZ_ARTISTID);
            ti.releaseMbid     = tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID);
            ti.recordingMbid   = tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID);
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
