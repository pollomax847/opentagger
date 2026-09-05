package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Intégration avec une instance Headphones (music-download-manager) tierce, tournant sur la même
 * machine — deux usages indépendants, vérifiés en direct le 2026-08-29 :
 *
 *  1. Lecture SEULE de sa base SQLite locale (headphones.db, table alltracks) — sert de source
 *     d'identification supplémentaire GRATUITE (aucun appel réseau, juste un fichier local) pour
 *     les pistes que l'utilisateur a déjà téléchargées/organisées via Headphones : ce catalogue est
 *     pré-résolu avec un MBID d'enregistrement fiable, donc un cran de confiance au-dessus d'une
 *     recherche MusicBrainz texte à l'aveugle. Jamais d'écriture dans ce fichier — Headphones
 *     l'écrit lui-même en direct (mode WAL), une écriture externe risquerait sa cohérence interne.
 *
 *  2. Appels à son API HTTP (documentée, voir ~/headphones/API.md) pour lui signaler un album que
 *     l'utilisateur ne possède pas encore et le mettre en recherche/téléchargement — c'est LE
 *     chemin officiel pour "écrire" côté Headphones, jamais une écriture SQL directe.
 */
public class HeadphonesClient {

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

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

    /** Vrai si Headphones a déjà au moins une piste LOCALISÉE (Location non vide, voir loadRows())
     *  pour cet artiste+album — sert de garde-fou avant d'envoyer automatiquement un album vers
     *  Headphones (queueAlbum) : inutile (et bruyant côté Headphones) de proposer un album qu'il a
     *  déjà téléchargé. Même seuil de similarité (0.85) que lookupTrack(). */
    public boolean isAlbumKnown(String artist, String album) {
        if (!Config.get().bool("headphones.db_enabled", false)) return false;
        if (artist == null || artist.isBlank() || album == null || album.isBlank()) return false;
        List<Row> rows = loadRows();
        if (rows.isEmpty()) return false;

        String qArtist = normalizePunctuation(artist);
        String qAlbum  = normalizePunctuation(album);
        for (Row r : rows) {
            if (TrackMatcher.titleSimilarity(qArtist, normalizePunctuation(r.artist())) < 0.85) continue;
            if (TrackMatcher.titleSimilarity(qAlbum, normalizePunctuation(r.album())) < 0.85) continue;
            return true;
        }
        return false;
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
                try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        // nz() : AlbumTitle notamment peut être NULL (piste hors album) — un TagInfo
                        // avec un champ String null casse toute l'appli en aval (NPE dans
                        // LocalCorrector, isBlank() appelé sans garde) — même bug que BeetsClient,
                        // corrigé en même temps (2026-08-30).
                        rows.add(new Row(nz(rs.getString(1)), nz(rs.getString(2)), nz(rs.getString(3)), nz(rs.getString(4))));
                    }
                }
                result[0] = rows;
            } catch (Exception ignored) {
                result[0] = List.of();
            }
        }, "headphones-db-read");
        worker.setDaemon(true);
        worker.start();
        try { worker.join(timeoutMs); } catch (InterruptedException ignored) {}
        return result[0] != null ? result[0] : List.of();
    }

    // ── 2. API HTTP (recherche + mise en file d'attente côté Headphones) ───────────────────────

    private String apiUrl(String cmd, String extraParams) {
        String base = Config.get().str("headphones.url", "").trim();
        String key  = Config.get().str("headphones.api_key", "").trim();
        if (base.isBlank() || key.isBlank()) return null;
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/api?apikey=" + key + "&cmd=" + cmd + (extraParams != null ? "&" + extraParams : "");
    }

    public boolean isConfigured() {
        return !Config.get().str("headphones.url", "").isBlank()
            && !Config.get().str("headphones.api_key", "").isBlank();
    }

    /** Résultat d'une recherche findAlbum — champs déjà normalisés depuis le JSON brut de l'API. */
    public record AlbumCandidate(String artistId, String artistName, String albumTitle,
                                  String releaseId, int score) {}

    /** Cherche un album côté MusicBrainz via findAlbum (relayé par l'instance Headphones). */
    public List<AlbumCandidate> findAlbum(String query) throws Exception {
        String url = apiUrl("findAlbum", "name=" + enc(query) + "&limit=5");
        if (url == null) return List.of();
        JsonNode root = getJson(url);
        List<AlbumCandidate> out = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode n : root) {
                out.add(new AlbumCandidate(
                    n.path("id").asText(""),
                    n.path("uniquename").asText(n.path("title").asText("")),
                    n.path("title").asText(""),
                    n.path("albumid").asText(""),
                    n.path("score").asInt(0)));
            }
        }
        return out;
    }

    /** Ajoute l'artiste puis met l'album en recherche/téléchargement côté Headphones — même
     *  séquence que l'UI web de Headphones (addArtist requis avant qu'un album lui appartenant
     *  puisse être mis en file). {@code releaseId} = AlbumCandidate.releaseId() (id "albumid" de
     *  findAlbum, une release MusicBrainz précise — voir API.md : addAlbum/queueAlbum prennent tous
     *  deux ce même identifiant, pas le release-group). */
    public boolean queueAlbum(String artistId, String releaseId) throws Exception {
        if (releaseId == null || releaseId.isBlank()) return false;
        if (artistId != null && !artistId.isBlank()) {
            String addArtistUrl = apiUrl("addArtist", "id=" + enc(artistId));
            if (addArtistUrl != null) getJson(addArtistUrl); // "OK" attendu, pas bloquant si échoue
        }
        String url = apiUrl("queueAlbum", "id=" + enc(releaseId));
        if (url == null) return false;
        JsonNode root = getJson(url);
        return root != null;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        return mapper.readTree(resp.body());
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
