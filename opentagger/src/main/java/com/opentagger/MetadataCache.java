package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Cache SQLite local — équivalent de la base Apache Derby de Jaikoz.
 *
 * Tables TTL (expiration configurable) :
 *  - recordings(query_hash, json, ts) — réponses MB Recording Search
 *  - lookups(mbid, json, ts)           — réponses MB Recording Lookup
 *
 * Tables permanentes (historique personnel, jamais purgées) :
 *  - tagging_history(mbid, artist, title, album, year, json, ts)
 *      Équivalent du Derby 726 Mo de Jaikoz : stocke le TagInfo final
 *      (après corrections manuelles) pour chaque morceau jamais tagué.
 *  - file_history(path, mbid, ts)
 *      Mémorise quel MBID a été appliqué à chaque chemin de fichier.
 *
 * Tables de traçabilité :
 *  - corrections(path, field, old, new, ts) — historique des corrections
 */
public class MetadataCache {

    private static final Logger LOG = Logger.getLogger(MetadataCache.class.getName());

    private static final String DB_DIR  = System.getProperty("user.home") + "/.opentagger";
    private static final String DB_PATH = DB_DIR + "/cache.db";

    // TTL configurable — s'applique uniquement aux tables recordings/lookups
    private final long ttlMs;

    private Connection conn;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetadataCache() {
        int days = Config.get().num("musicbrainz.cache_days", 30);
        this.ttlMs = (long) days * 86_400_000L;
        init();
    }

    private void init() {
        try {
            Path dir = Paths.get(DB_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);

            // SQLite via JDBC (pilote livré dans le fat JAR)
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                // WAL mode : permet les lectures concurrentes pendant les écritures
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS recordings (
                        query_hash TEXT PRIMARY KEY,
                        json       TEXT NOT NULL,
                        ts         INTEGER NOT NULL
                    )""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS lookups (
                        mbid TEXT PRIMARY KEY,
                        json TEXT NOT NULL,
                        ts   INTEGER NOT NULL
                    )""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS corrections (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        path      TEXT    NOT NULL,
                        field     TEXT    NOT NULL,
                        old_value TEXT,
                        new_value TEXT,
                        ts        INTEGER NOT NULL
                    )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_corr_path ON corrections(path)");
                // ── Historique personnel permanent (équivalent Derby 726 Mo) ─
                st.execute("""
                    CREATE TABLE IF NOT EXISTS tagging_history (
                        mbid   TEXT PRIMARY KEY,
                        artist TEXT,
                        title  TEXT,
                        album  TEXT,
                        year   TEXT,
                        json   TEXT NOT NULL,
                        ts     INTEGER NOT NULL
                    )""");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS file_history (
                        path TEXT PRIMARY KEY,
                        mbid TEXT,
                        ts   INTEGER NOT NULL
                    )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_hist_artist ON tagging_history(artist)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_hist_title  ON tagging_history(title)");
            }
        } catch (Exception e) {
            LOG.warning("Cache SQLite indisponible : " + e.getMessage());
            conn = null;
        }
    }

    // ── Recording Search ─────────────────────────────────────────────────────

    public synchronized String getRecordingSearch(String queryHash) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json FROM recordings WHERE query_hash=? AND ts>?")) {
            ps.setString(1, queryHash);
            ps.setLong(2, System.currentTimeMillis() - ttlMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) { return null; }
    }

    public synchronized void putRecordingSearch(String queryHash, String json) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO recordings(query_hash,json,ts) VALUES(?,?,?)")) {
            ps.setString(1, queryHash);
            ps.setString(2, json);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // ── Recording Lookup (par MBID) ──────────────────────────────────────────

    public synchronized String getLookup(String mbid) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json FROM lookups WHERE mbid=? AND ts>?")) {
            ps.setString(1, mbid);
            ps.setLong(2, System.currentTimeMillis() - ttlMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) { return null; }
    }

    public synchronized void putLookup(String mbid, String json) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO lookups(mbid,json,ts) VALUES(?,?,?)")) {
            ps.setString(1, mbid);
            ps.setString(2, json);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // ── Historique des corrections ────────────────────────────────────────────

    public synchronized void recordCorrection(String filePath, String field, String oldVal, String newVal) {
        if (conn == null) return;
        if (newVal == null || newVal.equals(oldVal)) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO corrections(path,field,old_value,new_value,ts) VALUES(?,?,?,?,?)")) {
            ps.setString(1, filePath);
            ps.setString(2, field);
            ps.setString(3, oldVal);
            ps.setString(4, newVal);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    // ── Historique personnel (tagging_history + file_history) ────────────────

    /**
     * Génère une clé cache synthétique pour les fichiers identifiés sans MBID réel
     * (ex: résultat SongRec seul). Préfixe "syn_" pour les distinguer des MBIDs MB.
     */
    public static String syntheticKey(String artist, String title) {
        String s = (artist + "###" + title).toLowerCase().trim();
        return "syn_" + Integer.toHexString(s.hashCode() & 0x7FFFFFFF);
    }

    /**
     * Sauvegarde le TagInfo final dans l'historique personnel.
     * Accepte un keyOverride pour les fichiers sans recordingMbid (SongRec, AudD…).
     */
    public synchronized void saveTaggingHistory(TagInfo t) {
        saveTaggingHistory(t, t.recordingMbid);
    }

    public synchronized void saveTaggingHistory(TagInfo t, String key) {
        if (conn == null || key == null || key.isBlank()) return;
        try {
            String json = mapper.writeValueAsString(t);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO tagging_history(mbid,artist,title,album,year,json,ts) VALUES(?,?,?,?,?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, t.artist);
                ps.setString(3, t.title);
                ps.setString(4, t.album);
                ps.setString(5, t.year);
                ps.setString(6, json);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (Exception e) { LOG.warning("saveTaggingHistory : " + e.getMessage()); }
    }

    /**
     * Récupère le TagInfo de l'historique pour un MBID donné.
     * Retourne null si non trouvé.
     */
    public synchronized TagInfo getTaggingHistory(String mbid) {
        if (conn == null || mbid == null || mbid.isBlank()) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT json FROM tagging_history WHERE mbid=?")) {
            ps.setString(1, mbid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapper.readValue(rs.getString(1), TagInfo.class);
            }
        } catch (Exception e) { LOG.fine("getTaggingHistory : " + e.getMessage()); }
        return null;
    }

    /** Enregistre l'association chemin de fichier → MBID après un taguage. */
    public synchronized void recordFileTagging(String path, String mbid) {
        if (conn == null || path == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO file_history(path,mbid,ts) VALUES(?,?,?)")) {
            ps.setString(1, path);
            ps.setString(2, mbid != null ? mbid : "");
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /**
     * Retourne le MBID précédemment appliqué à ce chemin, ou null.
     * Permet de re-tagger instantanément depuis l'historique sans appel réseau.
     */
    public synchronized String getFileTagging(String path) {
        if (conn == null || path == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT mbid FROM file_history WHERE path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String m = rs.getString(1);
                    return (m != null && !m.isBlank()) ? m : null;
                }
            }
        } catch (Exception e) { /* silencieux */ }
        return null;
    }

    /** Nombre total d'entrées dans l'historique personnel. */
    public synchronized int historyCount() {
        if (conn == null) return 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tagging_history")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * Recherche dans l'historique — filtre optionnel sur artiste et/ou titre.
     * Retourne au plus 5000 entrées, triées par date décroissante.
     */
    public List<HistoryEntry> queryHistory(String artistFilter, String titleFilter) {
        List<HistoryEntry> list = new ArrayList<>();
        if (conn == null) return list;
        String af = artistFilter != null ? artistFilter.toLowerCase().trim() : "";
        String tf = titleFilter  != null ? titleFilter .toLowerCase().trim() : "";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT mbid,artist,title,album,year,ts FROM tagging_history ORDER BY ts DESC LIMIT 5000")) {
            while (rs.next()) {
                String art = nullStr(rs.getString("artist"));
                String tit = nullStr(rs.getString("title"));
                if (!af.isBlank() && !art.toLowerCase().contains(af)) continue;
                if (!tf.isBlank() && !tit.toLowerCase().contains(tf))  continue;
                list.add(new HistoryEntry(
                    nullStr(rs.getString("mbid")),
                    art, tit,
                    nullStr(rs.getString("album")),
                    nullStr(rs.getString("year")),
                    rs.getLong("ts")));
            }
        } catch (Exception e) { LOG.warning("queryHistory : " + e.getMessage()); }
        return list;
    }

    public record HistoryEntry(
        String mbid, String artist, String title, String album, String year, long ts) {}

    public record ExportEntry(
        String mbid, String artist, String title, String album, String year, String json, long ts) {}

    /**
     * Exporte tout l'historique personnel au format ExportEntry (inclut le JSON complet).
     * Utilisé pour partager la base avec d'autres utilisateurs.
     */
    public List<ExportEntry> exportHistory() {
        List<ExportEntry> list = new ArrayList<>();
        if (conn == null) return list;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT mbid,artist,title,album,year,json,ts FROM tagging_history ORDER BY ts DESC")) {
            while (rs.next()) list.add(new ExportEntry(
                nullStr(rs.getString("mbid")),
                nullStr(rs.getString("artist")),
                nullStr(rs.getString("title")),
                nullStr(rs.getString("album")),
                nullStr(rs.getString("year")),
                nullStr(rs.getString("json")),
                rs.getLong("ts")));
        } catch (Exception e) { LOG.warning("exportHistory : " + e.getMessage()); }
        return list;
    }

    /**
     * Importe des entrées dans l'historique (INSERT OR IGNORE — ne remplace pas les entrées existantes).
     * Retourne le nombre d'entrées réellement insérées.
     */
    public int importHistory(List<ExportEntry> entries) {
        if (conn == null || entries.isEmpty()) return 0;
        int count = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO tagging_history(mbid,artist,title,album,year,json,ts) VALUES(?,?,?,?,?,?,?)")) {
            for (ExportEntry e : entries) {
                if (e.mbid().isBlank()) continue;
                ps.setString(1, e.mbid());
                ps.setString(2, e.artist());
                ps.setString(3, e.title());
                ps.setString(4, e.album());
                ps.setString(5, e.year());
                ps.setString(6, e.json());
                ps.setLong(7, e.ts());
                if (ps.executeUpdate() > 0) count++;
            }
        } catch (Exception e) { LOG.warning("importHistory : " + e.getMessage()); }
        return count;
    }

    private static String nullStr(String s) { return s != null ? s : ""; }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    /** Génère une clé de cache stable pour une requête artist+title. */
    public static String queryHash(String artist, String title) {
        return Integer.toHexString((artist + " " + title).toLowerCase().hashCode() & 0x7FFFFFFF);
    }

    public void purgeExpired() {
        if (conn == null) return;
        long cutoff = System.currentTimeMillis() - ttlMs;
        try (PreparedStatement ps1 = conn.prepareStatement("DELETE FROM recordings WHERE ts<?");
             PreparedStatement ps2 = conn.prepareStatement("DELETE FROM lookups WHERE ts<?")) {
            ps1.setLong(1, cutoff); ps1.executeUpdate();
            ps2.setLong(1, cutoff); ps2.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** Supprime tout l'historique personnel (action irréversible, demande confirmation dans l'UI). */
    public void purgeHistory() {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM tagging_history");
            st.execute("DELETE FROM file_history");
            st.execute("DELETE FROM corrections");
        } catch (Exception ignored) {}
    }

    public void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (Exception ignored) {}
    }

    public boolean isAvailable() { return conn != null; }
}
