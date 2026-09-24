package com.opentagger;

import com.opentagger.model.TagInfo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Intégration avec une instance Headphones (music-download-manager) tierce, tournant sur la même
 * machine — LECTURE SEULE uniquement (l'envoi d'albums vers son API a été supprimé le 2026-09-24 : il
 * faisait planter les conteneurs, voir TagEnrichment.saveEntry()) :
 *
 *  Lecture SEULE de sa base SQLite locale (headphones.db, table alltracks) — sert de source
 *     d'identification supplémentaire GRATUITE (aucun appel réseau, juste un fichier local) pour
 *     les pistes que l'utilisateur a déjà téléchargées/organisées via Headphones : ce catalogue est
 *     pré-résolu avec un MBID d'enregistrement fiable, donc un cran de confiance au-dessus d'une
 *     recherche MusicBrainz texte à l'aveugle. Jamais d'écriture dans ce fichier — Headphones
 *     l'écrit lui-même en direct (mode WAL), une écriture externe risquerait sa cohérence interne.
 *
 */
public class HeadphonesClient {

    // ── 1. Lecture directe de la base SQLite (identification) ──────────────────────────────────

    private record Row(String artist, String album, String title, String trackId) {}

    // Cache statique partagé entre toutes les instances/threads (comme discIdCache dans
    // TaggingWorker) : la base Headphones ne change pas assez vite pour justifier une relecture à
    // chaque fichier — ~1500 lignes, un seul SELECT toutes les 10 minutes suffit largement et évite
    // de rajouter de la pression sur une base déjà sous contention (voir son propre écrivain WAL).
    private static volatile List<Row> cachedRows = null;
    private static volatile long cacheLoadedAt = 0;
    private static final long CACHE_TTL_MS = 10 * 60_000;

    /**
     * Cherche une piste par similarité artiste+titre dans le catalogue Headphones déjà localisé
     * (Location non vide). Seuil volontairement strict (0.85 sur les deux) : un faux positif ici
     * écrirait un MAUVAIS MBID avec une confiance modérée (score 88), contrairement à un simple
     * indice de recherche qu'on peut se permettre d'avoir faux de temps en temps.
     * @return null si rien de suffisamment proche, ou si la base est absente/désactivée/illisible —
     *         jamais d'exception propagée (dégradation silencieuse, comme discIdCache).
     */
    public TagInfo lookupTrack(String artist, String title) {
        if (!Config.get().bool("headphones.db_enabled", false)) return null;
        if (artist == null || artist.isBlank() || title == null || title.isBlank()) return null;

        List<Row> rows = loadRows();
        if (rows.isEmpty()) return null;

        // normalizePunctuation() : la table Headphones (données historiques scrapées de
        // MusicBrainz sur des années) mélange ponctuation ASCII et "typographique" pour un MÊME
        // artiste selon la piste — repéré en direct (2026-08-29) : "Jean-Jacques Goldman" (tiret
        // ASCII 0x2D) sur certaines pistes, "Jean‐Jacques Goldman" (U+2010 HYPHEN) sur d'autres,
        // faisant échouer TrackMatcher.titleSimilarity() qui compare les mots tels quels. Normalisé
        // des deux côtés (requête ET lignes chargées) pour rester robuste à cette incohérence.
        String qArtist = normalizePunctuation(artist);
        String qTitle  = normalizePunctuation(title);

        Row best = null;
        double bestScore = 0;
        for (Row r : rows) {
            double sArtist = TrackMatcher.titleSimilarity(qArtist, normalizePunctuation(r.artist()));
            if (sArtist < 0.85) continue;
            double sTitle = TrackMatcher.titleSimilarity(qTitle, normalizePunctuation(r.title()));
            if (sTitle < 0.85) continue;
            double combined = (sArtist + sTitle) / 2;
            if (combined > bestScore) { bestScore = combined; best = r; }
        }
        if (best == null) return null;

        TagInfo t = new TagInfo();
        t.artist        = best.artist();
        t.title         = best.title();
        t.album         = best.album();
        t.recordingMbid = best.trackId();
        t.score         = 88;
        return t;
    }

    /** Réduit les variantes Unicode "typographiques" de tirets/apostrophes à leur équivalent ASCII
     *  — voir le commentaire sur son appelant pour le pourquoi. */
    /** SQL NULL → "" — voir son appelant (fetchRowsWithHardTimeout) pour le pourquoi. */
    private static String nz(String s) { return s != null ? s : ""; }

    private static String normalizePunctuation(String s) {
        if (s == null) return "";
        return s
            .replaceAll("[‐‑‒–—―]", "-")
            .replaceAll("[‘’‛]", "'")
            .replaceAll("[“”‟]", "\"");
    }

    private static synchronized List<Row> loadRows() {
        long now = System.currentTimeMillis();
        if (cachedRows != null && (now - cacheLoadedAt) < CACHE_TTL_MS) return cachedRows;

        String path = Config.get().str("headphones.db_path", "");
        if (path.isBlank() || !new java.io.File(path).exists()) {
            cachedRows = List.of();
            cacheLoadedAt = now;
            return cachedRows;
        }

        // Garde-fou dur sur un THREAD SÉPARÉ, pas juste PRAGMA busy_timeout : constaté en direct
        // (2026-08-29) que DriverManager.getConnection() lui-même peut rester bloqué plusieurs
        // dizaines de secondes contre le fichier headphones.db pendant que Headphones y écrit
        // activement (mode WAL) — AVANT même d'atteindre le PRAGMA, donc busy_timeout seul ne
        // protège pas contre ce cas. Un enrichissement optionnel ne doit JAMAIS pouvoir geler tout
        // le pipeline de taguage ; ce thread reste orphelin (daemon) s'il finit par se débloquer
        // après l'abandon, sans conséquence puisque son résultat n'est alors plus lu.
        // 30s (pas 5s) : le SELECT lui-même prend ~14s même SANS aucune contention (fichier de
        // 6,8 Go, pas d'index couvrant ce filtre) — mesuré en direct sur une copie statique isolée.
        // Coût acceptable : payé une fois par CACHE_TTL_MS (10 min), jamais par fichier taggué.
        List<Row> rows = fetchRowsWithHardTimeout(path, 30_000);
        cachedRows = rows;
        cacheLoadedAt = now;
        return rows;
    }

    private static List<Row> fetchRowsWithHardTimeout(String path, long timeoutMs) {
        final List<Row>[] result = new List[]{ null };
        // Référence exposée au thread appelant pour pouvoir annuler la requête depuis l'EXTÉRIEUR
        // si le timeout expire (voir plus bas) — trouvé en direct (2026-09-13) via lsof que ce
        // timeout était purement cosmétique : worker.join(timeoutMs) abandonne juste l'ATTENTE,
        // le thread worker (daemon) continue de tourner indéfiniment avec sa Connection/Statement
        // grands ouverts jusqu'à ce que le SELECT se termine de lui-même. Sur un fichier de 6,8 Go
        // sans index couvrant, sous la contention d'écriture WAL de Headphones, ce SELECT dépasse
        // les 30s bien plus souvent qu'espéré — chaque dépassement laissait un FD orphelin sur
        // headphones.db, jusqu'à en accumuler 8 en 6h de fonctionnement continu, empêchant
        // wal_checkpoint(TRUNCATE) de purger un WAL de 1,18 Go même Headphones à l'arrêt.
        final Statement[] stmtHolder = new Statement[1];
        Thread worker = new Thread(() -> {
            List<Row> rows = new ArrayList<>();
            // mode=ro : connexion strictement en lecture, ne prend jamais de verrou d'écriture sur
            // un fichier que Headphones écrit lui-même activement (mode WAL) au même moment.
            String url = "jdbc:sqlite:file:" + path + "?mode=ro";
            try (Connection conn = DriverManager.getConnection(url)) {
                try (Statement pragma = conn.createStatement()) {
                    pragma.execute("PRAGMA busy_timeout=3000");
                }
                String sql = "SELECT ArtistName, AlbumTitle, TrackTitle, TrackID FROM alltracks "
                           + "WHERE Location IS NOT NULL AND Location != '' "
                           + "AND TrackID IS NOT NULL AND TrackID != ''";
                try (Statement st = conn.createStatement()) {
                    stmtHolder[0] = st;
                    try (ResultSet rs = st.executeQuery(sql)) {
                        while (rs.next()) {
                            // nz() : AlbumTitle notamment peut être NULL (piste hors album) — un
                            // TagInfo avec un champ String null casse toute l'appli en aval (NPE
                            // dans LocalCorrector, isBlank() appelé sans garde) — même bug que
                            // BeetsClient, corrigé en même temps (2026-08-30).
                            rows.add(new Row(nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4))));
                        }
                    }
                }
                result[0] = rows;
            } catch (Exception ignored) {
                // Inclut SQLITE_INTERRUPT levée par stmt.cancel() ci-dessous en cas de timeout —
                // ignorée volontairement, result[0] reste null et le repli List.of() s'applique.
                result[0] = List.of();
            }
        }, "headphones-db-read");
        worker.setDaemon(true);
        worker.start();
        try { worker.join(timeoutMs); } catch (InterruptedException ignored) {}
        if (worker.isAlive()) {
            // Statement.cancel() est LE mécanisme JDBC/SQLite officiel pour interrompre une requête
            // en cours depuis un AUTRE thread (sqlite3_interrupt() en interne) — vérifié en direct
            // (repro isolé) : rend la main en <1ms, force le SELECT à lever SQLITE_INTERRUPT dans
            // le thread worker, qui ferme alors lui-même Connection/Statement via son propre
            // try-with-resources, libérant le descripteur OS immédiatement. À l'inverse,
            // Connection.close() appelé depuis ce thread PENDANT que le worker est au milieu
            // d'executeQuery() se bloque indéfiniment (confirmé, jamais retourné même après 30s) —
            // ne jamais utiliser cette voie pour annuler depuis l'extérieur.
            try {
                Statement st = stmtHolder[0];
                if (st != null) st.cancel();
            } catch (Exception ignored) {
                // Le worker a pu terminer/fermer son Statement entre isAlive()==true et cancel()
                // (course bénigne) — sans conséquence, result[0] reflète alors le vrai résultat.
            }
        }
        return result[0] != null ? result[0] : List.of();
    }
}
