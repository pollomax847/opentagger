package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Cache SQLite local — équivalent de la base Apache Derby de Jaikoz.
 *
 * Tables TTL (expiration configurable) :
 *  - recordings(query_hash, json, ts) — réponses MB Recording Search
 *  - lookups(mbid, json, ts)           — réponses MB Recording Lookup, releases ("release:xxx"),
 *                                         et depuis 2026-07-09 les réponses JSON Discogs/Last.fm/
 *                                         FanArt.tv (clé générique, pas seulement des MBID malgré
 *                                         le nom de colonne historique)
 *  - image_cache(key, ext, bytes, ts)  — pochettes CAA/FanArt.tv/podcasts (2026-07-09, façon
 *                                         Picard : un seul cache réseau pour tout, pas juste MB)
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
public class MetadataCache implements AutoCloseable {

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
                // Chaque worker ouvre sa propre connexion (synchronized ne protège que sa
                // propre instance) — sans busy_timeout, un accès concurrent depuis une autre
                // connexion échoue immédiatement en SQLITE_BUSY au lieu d'attendre son tour.
                // 5000 → 20000 (2026-07-28) : un échec ici n'est pas fatal (juste un warning, voir
                // recordFileTagging()/saveTaggingHistory()) mais fait retomber silencieusement un
                // fichier pourtant bien taggé en PENDING au prochain scan — observé en direct sur
                // cette machine (plusieurs autres services partagent les mêmes disques physiques,
                // voir la note mémoire sur la contention disque partagée) : des dizaines
                // d'avertissements SQLITE_BUSY sur un seul run de sauvegarde malgré les 5s déjà
                // accordées. Un délai plus généreux coûte au pire quelques secondes d'attente
                // occasionnelles, largement préférable à perdre ce suivi.
                st.execute("PRAGMA busy_timeout=20000");
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
                // ── Undo persistant (2026-08-16, demande utilisateur — écart réel constaté face à
                // SongKong : UndoManager était 100% en mémoire, perdu au moindre redémarrage). Log
                // append-only borné (voir pruneUndoHistory) des éditions manuelles (DetailPanel),
                // rejoué au démarrage dans UndoManager.undoStack via résolution path→FileEntry vivant
                // dans la session courante — un fichier absent de cette session (dossier fermé,
                // renommé depuis) est silencieusement ignoré au rechargement plutôt que de planter.
                st.execute("""
                    CREATE TABLE IF NOT EXISTS undo_history (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        path        TEXT    NOT NULL,
                        description TEXT    NOT NULL,
                        before_json TEXT    NOT NULL,
                        after_json  TEXT    NOT NULL,
                        ts          INTEGER NOT NULL
                    )""");
                st.execute("CREATE INDEX IF NOT EXISTS idx_undo_ts ON undo_history(ts)");
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
                        path          TEXT PRIMARY KEY,
                        mbid          TEXT,
                        ts            INTEGER NOT NULL,
                        identified_by TEXT    DEFAULT 'text'
                    )""");
                // Migration silencieuse pour les bases existantes (SQLite ALTER TABLE)
                try { st.execute("ALTER TABLE file_history ADD COLUMN identified_by TEXT DEFAULT 'text'"); }
                catch (SQLException ignored) { /* colonne déjà présente */ }
                // Sans cet index, loadTaggedPaths() ("WHERE mbid IS NOT NULL AND mbid != ''") force
                // un parcours complet de TOUTE la table à chaque scan — sur un historique accumulé
                // sur des années (des centaines de milliers de lignes), constaté en direct 2026-07-28
                // via jstack : plus de 5 minutes passées dans NativeDB.step() pour cette seule
                // requête, retardant d'autant l'affichage du statut "Tagué" de chaque fichier au
                // scan. Même raison que idx_hist_ts un peu plus bas pour tagging_history.
                st.execute("CREATE INDEX IF NOT EXISTS idx_filehistory_mbid ON file_history(mbid)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_hist_artist ON tagging_history(artist)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_hist_title  ON tagging_history(title)");
                // Sans cet index, "ORDER BY ts DESC LIMIT 5000" (queryHistory) force un tri complet
                // de TOUTE la table avant de garder les 5000 dernières lignes — sur un historique
                // accumulé sur des années (jamais purgé), ce tri pouvait prendre plusieurs minutes.
                // Constaté en direct : gelait tout l'EDT (pas seulement HistoryDialog) puisque
                // l'appel se fait sur le thread Swing, voir HistoryDialog.loadAll().
                st.execute("CREATE INDEX IF NOT EXISTS idx_hist_ts ON tagging_history(ts)");
                // ── Cache du scan (relecture des tags) ──────────────────────
                // Évite de rappeler AudioFileIO.read() (lecture disque + parsing complet) pour un
                // fichier déjà scanné dont ni la taille ni la date de modification n'ont changé
                // depuis — la même heuristique que make/rsync. json = le TagInfo tel que lu par
                // readTags() lui-même (pas une approximation venant d'un autre pipeline comme
                // tagging_history), donc aucun risque de divergence avec ce qu'un vrai reread
                // afficherait.
                st.execute("""
                    CREATE TABLE IF NOT EXISTS scan_cache (
                        path  TEXT    PRIMARY KEY,
                        mtime INTEGER NOT NULL,
                        size  INTEGER NOT NULL,
                        json  TEXT    NOT NULL,
                        ts    INTEGER NOT NULL
                    )""");
                // ── Cache réseau générique (façon Picard : QNetworkDiskCache met en cache TOUT
                // appel réseau uniformément) — Discogs/Last.fm/FanArt.tv/Cover Art Archive
                // n'étaient jamais mis en cache jusqu'ici, contrairement à MusicBrainz
                // (recordings/lookups ci-dessus) : chaque piste d'un même album refaisait un appel
                // réseau identique pour le même genre/pochette. `lookups` sert déjà de cache
                // générique clé→JSON (voir les clés "release:xxx" utilisées par les workers
                // d'albums) — réutilisé tel quel pour les réponses texte de ces 4 fournisseurs
                // (clés "discogs:"/"lastfm:"/"fanart:"). Les pochettes (binaire) ont besoin d'une
                // table à part, BLOB au lieu de TEXT.
                st.execute("""
                    CREATE TABLE IF NOT EXISTS image_cache (
                        key   TEXT    PRIMARY KEY,
                        ext   TEXT    NOT NULL,
                        bytes BLOB    NOT NULL,
                        ts    INTEGER NOT NULL
                    )""");
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
        } catch (Exception e) { LOG.fine("putRecordingSearch : " + e.getMessage()); }
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
        } catch (Exception e) { LOG.fine("putLookup : " + e.getMessage()); }
    }

    // ── Cache image binaire (pochettes CAA/FanArt/podcasts) ──────────────────

    public record CachedImage(byte[] bytes, String ext) {}

    public synchronized CachedImage getCachedImage(String key) {
        if (conn == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT bytes,ext FROM image_cache WHERE key=? AND ts>?")) {
            ps.setString(1, key);
            ps.setLong(2, System.currentTimeMillis() - ttlMs);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new CachedImage(rs.getBytes(1), rs.getString(2)) : null;
            }
        } catch (Exception e) { return null; }
    }

    public synchronized void putCachedImage(String key, byte[] bytes, String ext) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO image_cache(key,ext,bytes,ts) VALUES(?,?,?,?)")) {
            ps.setString(1, key);
            ps.setString(2, ext);
            ps.setBytes(3, bytes);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) { LOG.fine("putCachedImage : " + e.getMessage()); }
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
        } catch (Exception e) { LOG.warning("recordCorrection : " + e.getMessage()); }
    }

    // ── Undo persistant ────────────────────────────────────────────────────────

    /** Snapshot avant/après une édition manuelle — voir UndoManager, rejoué au démarrage. */
    public record UndoRow(String path, String description, String beforeJson, String afterJson, long ts) {}

    /** Best-effort : un échec d'écriture ici ne doit jamais empêcher l'undo en mémoire de
     *  fonctionner pour la session en cours (voir UndoManager.push). */
    public synchronized void pushUndoHistory(String path, String description, String beforeJson,
                                              String afterJson, int maxHistory) {
        if (conn == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO undo_history(path,description,before_json,after_json,ts) VALUES(?,?,?,?,?)")) {
            ps.setString(1, path);
            ps.setString(2, description);
            ps.setString(3, beforeJson);
            ps.setString(4, afterJson);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) { LOG.warning("pushUndoHistory : " + e.getMessage()); return; }
        // Purge au-delà de maxHistory — log borné, pas un historique permanent façon
        // tagging_history (voir commentaire de classe UndoManager).
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM undo_history WHERE id NOT IN "
                    + "(SELECT id FROM undo_history ORDER BY ts DESC LIMIT " + maxHistory + ")");
        } catch (Exception e) { LOG.warning("pushUndoHistory (purge) : " + e.getMessage()); }
    }

    /** Ordonné du plus ancien au plus récent — à rejouer tel quel via UndoManager.push() pour que
     *  la commande la plus récente se retrouve en haut de la pile undo reconstituée. */
    public synchronized List<UndoRow> loadUndoHistory(int limit) {
        List<UndoRow> out = new ArrayList<>();
        if (conn == null) return out;
        String sql = "SELECT path,description,before_json,after_json,ts FROM undo_history "
                + "ORDER BY ts DESC LIMIT " + limit;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new UndoRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5)));
            }
        } catch (Exception e) { LOG.warning("loadUndoHistory : " + e.getMessage()); return out; }
        Collections.reverse(out);
        return out;
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

    /**
     * Source d'identification — détermine le niveau de confiance dans le cache :
     *  "songrec"  : empreinte Shazam  → confiance totale (audio content)
     *  "acoustid" : empreinte AcoustID → confiance totale (audio content)
     *  "mbid"     : MBID existant dans le fichier → confiance totale
     *  "text"     : recherche MB par texte seulement → faible confiance (tags peuvent être faux)
     */
    public static final String SOURCE_SONGREC  = "songrec";
    public static final String SOURCE_ACOUSTID = "acoustid";
    public static final String SOURCE_MBID     = "mbid";
    public static final String SOURCE_TEXT     = "text";
    /** Fichier marqué "déjà taggué" manuellement (MainFrame.markAsAlreadyTagged()) — pas identifié
     *  par OpenTagger lui-même, juste tamponné à partir de ses tags déjà présents sur le disque. */
    public static final String SOURCE_EXISTING = "existing";
    // Distinct de SOURCE_EXISTING (qui signifie "confirmé manuellement par l'utilisateur", voir
    // MainFrame.java) — celui-ci signifie "aucune méthode n'a rien confirmé, tags existants
    // utilisés en dernier recours tels quels" (voir TaggingWorker.findTags(), 2026-08-16).
    public static final String SOURCE_UNVERIFIED_TAGS = "unverified_tags";
    /** Album entier identifié par checksum de durées de pistes (TOC CDDA approximé), pas par
     *  empreinte audio piste par piste — voir TaggingWorker.findTags() étape 0.6 et
     *  MusicBrainzClient.lookupByToc(). Confiance élevée (le checksum porte sur TOUT l'album, pas
     *  une seule piste) mais distincte de SOURCE_ACOUSTID/SOURCE_SONGREC (jamais d'analyse du
     *  contenu audio réel, uniquement les durées déclarées par les fichiers). */
    public static final String SOURCE_DISCID   = "discid";
    /** URL Bandcamp devinée depuis artiste+titre (jamais recherchée — bandcamp.com/search est
     *  bloqué, voir BandcampClient) puis VÉRIFIÉE par similarité avant application — voir
     *  TaggingWorker.findTags() étape 6a. Distincte de SOURCE_UNVERIFIED_TAGS (repli sans aucune
     *  vérification externe, juste en dessous dans la cascade) : celle-ci a été confirmée par le
     *  contenu réel d'une page Bandcamp, pas seulement les tags locaux du fichier. */
    public static final String SOURCE_BANDCAMP = "bandcamp";
    /** Piste rattachée à une release déjà "épinglée" par une autre piste du même groupe/album dans
     *  ce même lot de taguage (matching par numéro de piste ou similarité de titre contre la
     *  tracklist complète de la release) — voir TaggingWorker.groupPinnedRelease et findTags()
     *  étape 0.65. Distincte de SOURCE_DISCID (checksum de durées sur TOUT le dossier) : ici,
     *  seule UNE piste du groupe a été vérifiée (audio ou texte à haute confiance), les autres
     *  suivent sans vérification individuelle propre — évite juste que des pistes du même album
     *  divergent vers des éditions MusicBrainz différentes. */
    public static final String SOURCE_GROUP_PIN = "group_pin";

    /** Enregistre l'association chemin de fichier → MBID après un taguage. */
    public synchronized void recordFileTagging(String path, String mbid) {
        recordFileTagging(path, mbid, SOURCE_TEXT);
    }

    /** Enregistre l'association chemin de fichier → MBID avec la source d'identification. */
    public synchronized void recordFileTagging(String path, String mbid, String source) {
        if (conn == null || path == null) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO file_history(path,mbid,ts,identified_by) VALUES(?,?,?,?)")) {
            ps.setString(1, path);
            ps.setString(2, mbid != null ? mbid : "");
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, source != null ? source : SOURCE_TEXT);
            ps.executeUpdate();
        } catch (Exception e) {
            // Écriture la plus sensible du cache : un verrou SQLite perdu ici efface
            // silencieusement l'association fichier→MBID (le fichier retombe en PENDING au
            // prochain scan malgré des tags corrects sur le disque) — busy_timeout=5000 (voir
            // init()) absorbe la contention normale, donc un échec ici est un vrai signal.
            LOG.warning("recordFileTagging (" + path + ") : " + e.getMessage());
        }
    }

    /** Supprime l'entrée d'un chemin de fichier dans file_history (après renommage). */
    public synchronized void deleteFileHistory(String path) {
        if (conn == null || path == null) return;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM file_history WHERE path=?")) {
            ps.setString(1, path);
            ps.executeUpdate();
        } catch (Exception e) { LOG.warning("deleteFileHistory (" + path + ") : " + e.getMessage()); }
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

    /**
     * Retourne la source d'identification pour un chemin donné.
     * "songrec" / "acoustid" / "mbid" → confiance totale
     * "text" / null → faible confiance, SongRec doit re-vérifier
     */
    public synchronized String getFileTaggingSource(String path) {
        if (conn == null || path == null) return SOURCE_TEXT;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT identified_by FROM file_history WHERE path=?")) {
            ps.setString(1, path);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    return (s != null && !s.isBlank()) ? s : SOURCE_TEXT;
                }
            }
        } catch (Exception e) { /* silencieux */ }
        return SOURCE_TEXT;
    }

    /**
     * Charge uniquement les chemins des fichiers déjà tagués (Set<path>).
     * Version légère pour le scan initial : évite de charger les TagInfo en RAM.
     */
    public synchronized java.util.Set<String> loadTaggedPaths() {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (conn == null) return set;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT path FROM file_history WHERE mbid IS NOT NULL AND mbid != ''")) {
            while (rs.next()) set.add(rs.getString(1));
        } catch (Exception e) { LOG.warning("loadTaggedPaths: " + e.getMessage()); }
        return set;
    }

    /**
     * Charge toute la table file_history en mémoire (path → mbid).
     * Remplace les N appels unitaires à getFileTagging() pendant le scan initial.
     */
    public synchronized java.util.Map<String, String> loadFileHistoryMap() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (conn == null) return map;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT path, mbid FROM file_history WHERE mbid IS NOT NULL AND mbid != ''")) {
            while (rs.next()) map.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) { LOG.warning("loadFileHistoryMap: " + e.getMessage()); }
        return map;
    }

    /**
     * Charge toute la table tagging_history en mémoire (mbid → TagInfo).
     * Remplace les N appels unitaires à getTaggingHistory() pendant le scan initial.
     */
    public synchronized java.util.Map<String, TagInfo> loadTaggingHistoryMap() {
        java.util.Map<String, TagInfo> map = new java.util.HashMap<>();
        if (conn == null) return map;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT mbid, json FROM tagging_history")) {
            while (rs.next()) {
                try {
                    TagInfo ti = mapper.readValue(rs.getString(2), TagInfo.class);
                    map.put(rs.getString(1), ti);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { LOG.warning("loadTaggingHistoryMap: " + e.getMessage()); }
        return map;
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

    /**
     * Historique des corrections manuelles — écrit par MainFrame.recordFieldCorrections()
     * (voir applyDetail()), lu ici. Jusqu'à ce correctif, la table `corrections` était créée et
     * écrivable mais sans aucun appelant ni lecteur dans tout le dépôt : voir HistoryDialog,
     * onglet "Corrections".
     */
    public List<CorrectionEntry> queryCorrections(int limit) {
        List<CorrectionEntry> list = new ArrayList<>();
        if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT path,field,old_value,new_value,ts FROM corrections ORDER BY ts DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    list.add(new CorrectionEntry(
                        nullStr(rs.getString("path")),
                        nullStr(rs.getString("field")),
                        nullStr(rs.getString("old_value")),
                        nullStr(rs.getString("new_value")),
                        rs.getLong("ts")));
            }
        } catch (Exception e) { LOG.warning("queryCorrections : " + e.getMessage()); }
        return list;
    }

    public record CorrectionEntry(
        String path, String field, String oldValue, String newValue, long ts) {}

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

    // ── Cache du scan (path → TagInfo déjà lu, valide tant que mtime/size collent) ───

    public record ScanCacheEntry(long mtime, long size, TagInfo tagInfo) {}

    /**
     * Charge tout scan_cache en mémoire en un seul aller-retour (path → mtime/size/TagInfo),
     * pour éviter N requêtes SQL individuelles pendant le scan d'une bibliothèque de 100k+
     * fichiers — même principe que loadFileHistoryMap()/loadTaggingHistoryMap().
     */
    public synchronized java.util.Map<String, ScanCacheEntry> loadScanCacheMap() {
        java.util.Map<String, ScanCacheEntry> map = new java.util.HashMap<>();
        if (conn == null) return map;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT path, mtime, size, json FROM scan_cache")) {
            while (rs.next()) {
                try {
                    TagInfo ti = mapper.readValue(rs.getString(4), TagInfo.class);
                    map.put(rs.getString(1), new ScanCacheEntry(rs.getLong(2), rs.getLong(3), ti));
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { LOG.warning("loadScanCacheMap: " + e.getMessage()); }
        return map;
    }

    /** Enregistre (ou met à jour) le TagInfo lu pour ce chemin, avec son empreinte mtime/size. */
    public synchronized void putScanCache(String path, long mtime, long size, TagInfo ti) {
        if (conn == null || path == null) return;
        try {
            String json = mapper.writeValueAsString(ti);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO scan_cache(path,mtime,size,json,ts) VALUES(?,?,?,?,?)")) {
                ps.setString(1, path);
                ps.setLong(2, mtime);
                ps.setLong(3, size);
                ps.setString(4, json);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (Exception e) { LOG.fine("putScanCache: " + e.getMessage()); }
    }

    private static String nullStr(String s) { return s != null ? s : ""; }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    /**
     * Génère une clé de cache stable pour une requête artist+title.
     * Le séparateur précédent était un octet NUL brut incrusté directement dans le
     * fichier source (fragile, invisible, non standard) ; remplacé par un échappement Unicode
     * propre qui ne peut pas non plus apparaître dans un tag réel. SHA-256 (au lieu d'un
     * hashCode 32 bits) élimine aussi le risque réel de collision sur une grande bibliothèque
     * (paradoxe des anniversaires ~54k requêtes distinctes).
     */
    public static String queryHash(String artist, String title) {
        try {
            String key = artist.trim().toLowerCase() + '\u0001' + title.trim().toLowerCase();
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 est toujours disponible dans un JRE standard
        }
    }

    public void purgeExpired() {
        if (conn == null) return;
        long cutoff = System.currentTimeMillis() - ttlMs;
        try (PreparedStatement ps1 = conn.prepareStatement("DELETE FROM recordings WHERE ts<?");
             PreparedStatement ps2 = conn.prepareStatement("DELETE FROM lookups WHERE ts<?");
             PreparedStatement ps3 = conn.prepareStatement("DELETE FROM image_cache WHERE ts<?")) {
            ps1.setLong(1, cutoff); ps1.executeUpdate();
            ps2.setLong(1, cutoff); ps2.executeUpdate();
            ps3.setLong(1, cutoff); ps3.executeUpdate();
        } catch (Exception ignored) {}
    }

    /**
     * Supprime du scan_cache les entrées dont le fichier n'existe plus sur disque (déplacé/
     * supprimé/ancien point de montage retiré des dossiers surveillés) — jamais purgée jusqu'ici,
     * la table grossit indéfiniment : 622 406 lignes / 4,3 Go constatés en direct pour une
     * bibliothèque de ~96 000 fichiers. Combiné à un chargement dupliqué par scan concurrent
     * (voir MainFrame.acquireScanCacheMap()), c'est la cause directe d'un OutOfMemoryError observé
     * en direct au lancement (5 dossiers scannés en parallèle). Coûteux (un stat() par ligne, des
     * centaines de milliers d'appels) : à lancer en tâche de fond (SwingWorker), jamais sur l'EDT.
     */
    public int purgeStaleScanCache() {
        if (conn == null) return 0;
        List<String> stale = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT path FROM scan_cache")) {
            while (rs.next()) {
                String path = rs.getString(1);
                if (path == null || !Files.exists(Paths.get(path))) stale.add(path);
            }
        } catch (Exception e) { LOG.warning("purgeStaleScanCache (lecture) : " + e.getMessage()); return 0; }
        if (stale.isEmpty()) return 0;

        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM scan_cache WHERE path=?")) {
                for (String path : stale) { ps.setString(1, path); ps.addBatch(); }
                ps.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            LOG.warning("purgeStaleScanCache (suppression) : " + e.getMessage());
            try { conn.rollback(); } catch (Exception ignored) {}
            return 0;
        } finally {
            try { conn.setAutoCommit(true); } catch (Exception ignored) {}
        }
        return stale.size();
    }

    /** Réclame l'espace disque libéré par les DELETE ci-dessus (SQLite ne rétrécit pas le fichier
     *  tout seul) — à lancer juste après purgeStaleScanCache()/purgeHistory(), hors EDT. */
    public void vacuum() {
        if (conn == null) return;
        try (Statement st = conn.createStatement()) { st.execute("VACUUM"); }
        catch (Exception e) { LOG.warning("vacuum : " + e.getMessage()); }
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
