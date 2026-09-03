package com.opentagger;

import com.opentagger.model.TagInfo;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture SEULE de la base beets (library.db, table items) tournant sur la même machine — vérifiée
 * en direct le 2026-08-29 : 39430 pistes, 30180 avec un recordingMbid MusicBrainz déjà résolu, 19410
 * avec un ISRC. Schéma bien plus riche que Headphones (composer/work classique, ReplayGain, BPM,
 * tonalité, tous les MBID release/release-group/artist/recording) — une vraie source
 * d'identification/enrichissement, pas juste un indice de recherche.
 *
 * Deux modes de correspondance :
 *  1. Chemin EXACT (beets.music_dir + chemin relatif stocké en base = chemin absolu réel) — quasi
 *     certain quand ça matche, beets.music_dir vaut par défaut rename.library_root (même racine
 *     organisée par les deux outils dans le cas courant observé ici).
 *  2. Repli par similarité artiste+titre (comme HeadphonesClient) si le chemin ne correspond à rien
 *     — le fichier a pu être déplacé/renommé par OpenTagger depuis le dernier import beets.
 *
 * Jamais d'écriture dans library.db — beets l'écrit lui-même activement, une écriture externe
 * risquerait sa cohérence interne (même raisonnement que HeadphonesClient).
 */
public class BeetsClient {

    private record Row(String relPath, String artist, String albumArtist, String album, String title,
                        String year, String genre, String recordingMbid, String releaseMbid,
                        String releaseGroupMbid, String artistMbid, String isrc, String track,
                        String discNo, String composer, String work, String bpm) {}

    private static volatile List<Row> cachedRows = null;
    private static volatile Map<String, Row> byAbsPath = null;
    private static volatile long cacheLoadedAt = 0;
    private static final long CACHE_TTL_MS = 10 * 60_000;

    public TagInfo lookupByPath(String absolutePath) {
        if (!Config.get().bool("beets.db_enabled", false)) return null;
        if (absolutePath == null || absolutePath.isBlank()) return null;
        ensureLoaded();
        Row r = byAbsPath.get(absolutePath);
        return r != null ? toTagInfo(r) : null;
    }

    public TagInfo lookupTrack(String artist, String title) {
        if (!Config.get().bool("beets.db_enabled", false)) return null;
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) return null;
        ensureLoaded();
        if (cachedRows.isEmpty()) return null;

        String qArtist = normalizePunctuation(artist);
        String qTitle  = normalizePunctuation(title);

        Row best = null;
        double bestScore = 0;
        for (Row r : cachedRows) {
            double sArtist = TrackMatcher.titleSimilarity(qArtist, normalizePunctuation(r.artist()));
            if (sArtist < 0.85) continue;
            double sTitle = TrackMatcher.titleSimilarity(qTitle, normalizePunctuation(r.title()));
            if (sTitle < 0.85) continue;
            double combined = (sArtist + sTitle) / 2;
            if (combined > bestScore) { bestScore = combined; best = r; }
        }
        return best != null ? toTagInfo(best) : null;
    }

    private TagInfo toTagInfo(Row r) {
        TagInfo t = new TagInfo();
        t.artist           = r.artist();
        t.albumArtist       = r.albumArtist();
        t.title             = r.title();
        t.album             = r.album();
        t.year              = r.year();
        t.genre             = r.genre();
        t.recordingMbid     = r.recordingMbid();
        t.releaseMbid       = r.releaseMbid();
        t.releaseGroupMbid  = r.releaseGroupMbid();
        t.artistMbid        = r.artistMbid();
        t.isrc              = r.isrc();
        t.track             = r.track();
        t.discNo            = r.discNo();
        t.composer          = r.composer();
        t.work              = r.work();
        t.bpm               = r.bpm();
        t.score             = 90; // au-dessus de Headphones (88) : ISRC/MBID complets, pas juste artiste+titre
        return t;
    }

    /** SQL NULL → "" — colonnes optionnelles côté beets (genre/year/composer/work/isrc...), les
     *  champs String de TagInfo doivent toujours être non-null (convention respectée partout
     *  ailleurs dans l'appli, ex. isBlank() appelé sans garde) — repéré en direct 2026-08-30 : sans
     *  ce filtre, un TagInfo avec genre=null (piste sans genre dans beets) faisait planter TOUTE
     *  identification via BeetsClient (NPE dans LocalCorrector, "info.genre" est null), à chaque
     *  reprise du même fichier. */
    private static String nz(String s) { return s != null ? s : ""; }

    private static String normalizePunctuation(String s) {
        if (s == null) return "";
        return s
            .replaceAll("[‐‑‒–—―]", "-")
            .replaceAll("[‘’‛]", "'")
            .replaceAll("[“”‟]", "\"");
    }

    private static synchronized void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (cachedRows != null && (now - cacheLoadedAt) < CACHE_TTL_MS) return;

        String dbPath = Config.get().str("beets.db_path", "");
        String musicDir = Config.get().str("beets.music_dir", "");
        if (musicDir.isBlank()) musicDir = Config.get().str("rename.library_root", "");
        if (dbPath.isBlank() || !new java.io.File(dbPath).exists()) {
            cachedRows = List.of();
            byAbsPath = Map.of();
            cacheLoadedAt = now;
            return;
        }

        List<Row> rows = fetchRowsWithHardTimeout(dbPath, 30_000);
        Map<String, Row> abs = new HashMap<>();
        if (!musicDir.isBlank()) {
            String base = musicDir.endsWith("/") ? musicDir : musicDir + "/";
            for (Row r : rows) {
                if (r.relPath() == null || r.relPath().isBlank()) continue;
                abs.put(base + r.relPath(), r);
            }
        }
        cachedRows = rows;
        byAbsPath = abs;
        cacheLoadedAt = now;
    }

    private static List<Row> fetchRowsWithHardTimeout(String dbPath, long timeoutMs) {
        final List<Row>[] result = new List[]{ null };
        Thread worker = new Thread(() -> {
            List<Row> rows = new ArrayList<>();
            String url = "jdbc:sqlite:file:" + dbPath + "?mode=ro";
            try (Connection conn = DriverManager.getConnection(url)) {
                try (Statement pragma = conn.createStatement()) {
                    pragma.execute("PRAGMA busy_timeout=3000");
                }
                String sql = "SELECT path, artist, albumartist, album, title, year, genre, "
                           + "mb_trackid, mb_albumid, mb_releasegroupid, mb_artistid, isrc, "
                           + "track, disc, composer, work, bpm FROM items "
                           + "WHERE artist IS NOT NULL AND artist != '' AND title IS NOT NULL AND title != ''";
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        byte[] pathBytes = rs.getBytes(1);
                        String relPath = pathBytes != null ? new String(pathBytes, StandardCharsets.UTF_8) : "";
                        int trackNo = rs.getInt(13);
                        int discNo  = rs.getInt(14);
                        int bpmVal  = rs.getInt(17);
                        rows.add(new Row(
                            relPath,
                            nz(rs.getString(2)),
                            nz(rs.getString(3)),
                            nz(rs.getString(4)),
                            nz(rs.getString(5)),
                            nz(rs.getString(6)),
                            nz(rs.getString(7)),
                            nz(rs.getString(8)),
                            nz(rs.getString(9)),
                            nz(rs.getString(10)),
                            nz(rs.getString(11)),
                            nz(rs.getString(12)),
                            trackNo > 0 ? String.valueOf(trackNo) : "",
                            discNo > 0 ? String.valueOf(discNo) : "",
                            nz(rs.getString(15)),
                            nz(rs.getString(16)),
                            bpmVal > 0 ? String.valueOf(bpmVal) : ""));
                    }
                }
                result[0] = rows;
            } catch (Exception ignored) {
                result[0] = List.of();
            }
        }, "beets-db-read");
        worker.setDaemon(true);
        worker.start();
        try { worker.join(timeoutMs); } catch (InterruptedException ignored) {}
        return result[0] != null ? result[0] : List.of();
    }
}
