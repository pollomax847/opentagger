package com.opentagger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verdicts de l'audit "audio ↔ tags" (voir AudioTagAudit/AudioTagAuditWorker), un par fichier.
 *
 * <p>Base SQLite DÉDIÉE ({@code ~/.opentagger/audio_audit.db}) et non une table de cache.db : ce dernier
 * fait plusieurs Go et sert de point de contention à toute la chaîne de taguage (voir MetadataCache,
 * busy_timeout) ; l'audit lit/écrit ici sans jamais concurrencer ces verrous, et une base de quelques Mo
 * se consulte instantanément depuis la fenêtre de revue.
 *
 * <p>Un verdict n'est valable que pour l'état exact du fichier audité ({@code mtime} + {@code size}) :
 * dès que le fichier est réécrit (re-taguage, correction manuelle), son ancien verdict devient périmé —
 * ignoré par la fenêtre de revue et refait au prochain audit. C'est aussi ce qui fait disparaître un
 * suspect de la liste une fois corrigé, sans aucune action de nettoyage.
 */
public final class AudioAuditStore implements AutoCloseable {

    /** Ligne de la base ; {@code shazam} ∈ {"", "AGREE" (Shazam confirme ce que dit AcoustID), "OTHER" (Shazam
     *  dit autre chose que les deux), "SILENT" (rien reconnu), "NA" (SongRec indisponible), "SUPPORTS_TAG"}. */
    public record Row(String path, long mtime, long size, String verdict, String shazam,
                      String tagArtist, String tagTitle, String acArtist, String acTitle,
                      double acScore, int acDurationSec, String srArtist, String srTitle,
                      int fileDurationSec, String note, boolean userOk, long ts) {}

    private static final Path DB_DIR = Paths.get(System.getProperty("user.home"), ".opentagger");

    private final Connection conn;

    private AudioAuditStore(Connection conn) { this.conn = conn; }

    public static AudioAuditStore open() throws SQLException {
        return open(DB_DIR.resolve("audio_audit.db"));
    }

    /** Surcharge pour les tests (base temporaire). */
    public static AudioAuditStore open(Path dbFile) throws SQLException {
        try { Files.createDirectories(dbFile.toAbsolutePath().getParent()); } catch (Exception ignored) {}
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=20000");
            st.execute("""
                CREATE TABLE IF NOT EXISTS audio_audit (
                    path          TEXT PRIMARY KEY,
                    mtime         INTEGER NOT NULL,
                    size          INTEGER NOT NULL,
                    verdict       TEXT    NOT NULL,
                    shazam        TEXT    NOT NULL DEFAULT '',
                    tag_artist    TEXT,
                    tag_title     TEXT,
                    ac_artist     TEXT,
                    ac_title      TEXT,
                    ac_score      REAL    DEFAULT 0,
                    ac_duration   INTEGER DEFAULT 0,
                    sr_artist     TEXT,
                    sr_title      TEXT,
                    file_duration INTEGER DEFAULT 0,
                    note          TEXT,
                    user_ok       INTEGER NOT NULL DEFAULT 0,
                    ts            INTEGER NOT NULL
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_verdict ON audio_audit(verdict)");
        }
        return new AudioAuditStore(c);
    }

    /** Verdict enregistré pour ce chemin, ou null. Ne juge PAS s'il est périmé (voir {@link #isCurrent}). */
    public synchronized Row get(String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM audio_audit WHERE path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? row(rs) : null; }
        }
    }

    /**
     * {mtime en ms, taille} du fichier en UN SEUL appel système, ou null s'il est absent / n'est pas un fichier
     * régulier. Un seul appel plutôt que isFile()+lastModified()+length() : sur cette machine un stat sur la
     * bibliothèque (mergerfs/MyBook partagés avec Plex, Navidrome, Headphones…) a été mesuré à 350-500 ms
     * l'unité en charge — trois appels par fichier triplaient le coût de toute revérification. À ne JAMAIS
     * appeler depuis l'EDT (même leçon que le gel de scan du 2026-08-30).
     */
    public static long[] statOf(java.io.File f) {
        try {
            java.nio.file.attribute.BasicFileAttributes at = Files.readAttributes(
                    f.toPath(), java.nio.file.attribute.BasicFileAttributes.class);
            return at.isRegularFile() ? new long[]{at.lastModifiedTime().toMillis(), at.size()} : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Vrai si le verdict correspond à l'état ACTUEL du fichier (même mtime et même taille). */
    public static boolean isCurrent(Row r, long mtime, long size) {
        return r != null && r.mtime() == mtime && r.size() == size;
    }

    /** Insère ou remplace. Le "confirmé par l'utilisateur" ne survit que si le fichier est inchangé. */
    public synchronized void put(Row r) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO audio_audit (path, mtime, size, verdict, shazam, tag_artist, tag_title, ac_artist, ac_title,
                                         ac_score, ac_duration, sr_artist, sr_title, file_duration, note, user_ok, ts)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(path) DO UPDATE SET
                    user_ok = CASE WHEN audio_audit.mtime = excluded.mtime AND audio_audit.size = excluded.size
                                   THEN audio_audit.user_ok ELSE excluded.user_ok END,
                    mtime = excluded.mtime, size = excluded.size, verdict = excluded.verdict,
                    shazam = excluded.shazam, tag_artist = excluded.tag_artist, tag_title = excluded.tag_title,
                    ac_artist = excluded.ac_artist, ac_title = excluded.ac_title, ac_score = excluded.ac_score,
                    ac_duration = excluded.ac_duration, sr_artist = excluded.sr_artist, sr_title = excluded.sr_title,
                    file_duration = excluded.file_duration, note = excluded.note, ts = excluded.ts
                """)) {
            int i = 1;
            ps.setString(i++, r.path());       ps.setLong(i++, r.mtime());     ps.setLong(i++, r.size());
            ps.setString(i++, r.verdict());    ps.setString(i++, r.shazam());
            ps.setString(i++, r.tagArtist());  ps.setString(i++, r.tagTitle());
            ps.setString(i++, r.acArtist());   ps.setString(i++, r.acTitle());
            ps.setDouble(i++, r.acScore());    ps.setInt(i++, r.acDurationSec());
            ps.setString(i++, r.srArtist());   ps.setString(i++, r.srTitle());
            ps.setInt(i++, r.fileDurationSec()); ps.setString(i++, r.note());
            ps.setInt(i++, r.userOk() ? 1 : 0); ps.setLong(i, r.ts());
            ps.executeUpdate();
        }
    }

    /** "L'audio est bon" décidé par l'utilisateur : le fichier n'est plus jamais signalé (tant qu'il ne change pas). */
    public synchronized void markUserOk(String path) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE audio_audit SET user_ok=1 WHERE path=?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        }
    }

    /**
     * Suspects non écartés par l'utilisateur, les plus sûrs d'abord (deux moteurs d'accord, puis
     * TITLE_DIFF/OTHER_TRACK avant ARTIST_DIFF). {@code includeArtistOnly} : inclure aussi les différences
     * d'artiste seul (compilations/mix DJ — beaucoup de bruit, masquées par défaut). Les lignes périmées ne
     * sont PAS filtrées ici (il faudrait toucher au disque) : voir AudioTagAuditPanel.
     */
    public synchronized List<Row> suspects(boolean includeArtistOnly) throws SQLException {
        String in = includeArtistOnly ? "('TITLE_DIFF','OTHER_TRACK','ARTIST_DIFF')" : "('TITLE_DIFF','OTHER_TRACK')";
        List<Row> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM audio_audit WHERE user_ok=0 AND verdict IN " + in
                     + " ORDER BY CASE shazam WHEN 'AGREE' THEN 0 WHEN 'SILENT' THEN 1 WHEN 'NA' THEN 1 ELSE 2 END,"
                     + " CASE verdict WHEN 'ARTIST_DIFF' THEN 1 ELSE 0 END, ts DESC")) {
            while (rs.next()) out.add(row(rs));
        }
        return out;
    }

    /** Nombre de fichiers par verdict (pour la ligne d'état de la fenêtre). */
    public synchronized Map<String, Integer> countsByVerdict() throws SQLException {
        Map<String, Integer> m = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT verdict, COUNT(*) FROM audio_audit GROUP BY verdict")) {
            while (rs.next()) m.put(rs.getString(1), rs.getInt(2));
        }
        return m;
    }

    @Override public synchronized void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getString("path"), rs.getLong("mtime"), rs.getLong("size"), rs.getString("verdict"),
                nz(rs.getString("shazam")), nz(rs.getString("tag_artist")), nz(rs.getString("tag_title")),
                nz(rs.getString("ac_artist")), nz(rs.getString("ac_title")), rs.getDouble("ac_score"),
                rs.getInt("ac_duration"), nz(rs.getString("sr_artist")), nz(rs.getString("sr_title")),
                rs.getInt("file_duration"), nz(rs.getString("note")), rs.getInt("user_ok") != 0, rs.getLong("ts"));
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
