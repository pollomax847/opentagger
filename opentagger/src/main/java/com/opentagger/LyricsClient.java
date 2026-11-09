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
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Récupération des paroles depuis deux sources en cascade :
 *  1. lrclib.net — pas de clé, meilleure couverture occidentale, seule à retourner aussi les
 *     lyrics synchronisées (voir enrich(), interrogée en premier depuis le 2026-08-12)
 *  2. Lyrics.OVH (api.lyrics.ovh) — pas de clé, couverture large, texte brut seulement
 */
public class LyricsClient {

    private static final String LYRICSOVH = "https://api.lyrics.ovh/v1/";
    private static final String LRCLIB    = "https://lrclib.net/api/get";

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    public void enrich(TagInfo info) {
        if (!info.lyrics.isBlank()) return;
        if (!Config.get().bool("lyrics.enabled", true)) return;

        String artist = clean(info.artist);
        String title  = clean(info.title);
        if (artist.isBlank() || title.isBlank()) return;

        // Source 1 : lrclib.net — meilleure couverture occidentale ET seule des deux sources à
        // fournir des paroles synchronisées (voir tryLrclib()/TagInfo.syncedLyrics, utilisées pour
        // générer un .lrc à côté de l'audio si lyrics.save_lrc). Interrogée en premier pour
        // maximiser les .lrc synchronisés générés — inversé le 2026-08-12 : avant ce correctif,
        // Lyrics.OVH (texte brut uniquement, ci-dessous) répondait souvent en premier et
        // court-circuitait tryLrclib() ("si toujours vide" ci-dessous jamais atteint dès que
        // Lyrics.OVH avait quelque chose), privant silencieusement ces morceaux de tout .lrc
        // synchronisé même quand lrclib.net en avait un.
        tryLrclib(info, artist, title, info.album);

        // Source 2 : Lyrics.OVH (si toujours vide) — texte brut seulement, jamais de version synchronisée.
        if (info.lyrics.isBlank())
            tryLyricsOvh(info, artist, title);
    }

    private void tryLyricsOvh(TagInfo info, String artist, String title) {
        try {
            String url = LYRICSOVH
                    + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                    + URLEncoder.encode(title,  StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(8, TimeUnit.SECONDS).join();
            if (resp.statusCode() != 200) return;
            String lyrics = JsonText.of(mapper.readTree(resp.body()).path("lyrics"), "").trim();
            if (!lyrics.isBlank()) {
                info.lyrics    = lyrics;
                info.lyricsUrl = "https://www.lyrics.ovh/lyrics/"
                        + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                        + URLEncoder.encode(title,  StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
    }

    private void tryLrclib(TagInfo info, String artist, String title, String album) {
        try {
            String url = LRCLIB
                    + "?artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8)
                    + "&track_name="  + URLEncoder.encode(title,  StandardCharsets.UTF_8)
                    + (album.isBlank() ? "" : "&album_name=" + URLEncoder.encode(album, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(8, TimeUnit.SECONDS).join();
            if (resp.statusCode() != 200) return;
            JsonNode root = mapper.readTree(resp.body());
            // Préserver le brut LRC (timestamps compris) séparément — jeté avant ce correctif,
            // voir TagInfo.syncedLyrics pour où il est réellement utilisé (fichier .lrc à côté de
            // l'audio, pas un tag embarqué).
            String synced = JsonText.of(root.path("syncedLyrics"), "").trim();
            if (!synced.isBlank()) info.syncedLyrics = synced;

            // Préférer les paroles non-synchronisées (plainLyrics) pour le tag texte, fallback
            // syncedLyrics nettoyé de ses timestamps si aucune version plate n'est fournie.
            String plain = JsonText.of(root.path("plainLyrics"), "").trim();
            if (plain.isBlank() && !synced.isBlank())
                plain = synced.replaceAll("\\[\\d+:\\d{2}\\.\\d+\\]\\s*", "")
                              .replaceAll("\n{3,}", "\n\n")
                              .trim();
            if (!plain.isBlank()) {
                info.lyrics    = plain;
                info.lyricsUrl = "https://lrclib.net";
            }
        } catch (Exception ignored) {}
    }

    // ── Recherche pour « opentagger --paroles » (2026-11-09) ─────────────────────────────────────────────

    /**
     * Résultat d'une recherche de paroles. {@code plain} : texte à mettre dans le fichier ; {@code synced} : paroles
     * synchronisées (format LRC) pour le fichier .lrc ; {@code error} : la recherche n'a pas pu se faire (réseau,
     * service saturé) — à retenter plus tard, à ne pas noter comme « pas trouvé ».
     */
    public record Found(String plain, String synced, String source, boolean instrumental, String error) {
        static Found none()               { return new Found("", "", "", false, ""); }
        static Found failed(String why)   { return new Found("", "", "", false, why); }
        public boolean isError()          { return !error.isEmpty(); }
        public boolean isEmpty()          { return plain.isBlank() && synced.isBlank(); }
    }

    /** Lyrics.OVH coupé pour le reste de la session après trop d'échecs de suite (service souvent en panne). */
    private static final java.util.concurrent.atomic.AtomicInteger ovhFailures = new java.util.concurrent.atomic.AtomicInteger();
    private static final int OVH_MAX_FAILURES = 8;
    private static final String LRCLIB_SEARCH = "https://lrclib.net/api/search";
    /** Nom du programme envoyé à lrclib.net et Lyrics.OVH : le lien du projet, jamais l'adresse de contact réglée
     *  pour MusicBrainz (« app.contact »), qui n'a rien à faire chez ces services. */
    private static final String PUBLIC_AGENT = "OpenTagger/" + Config.get().appVersion()
            + " (https://github.com/pollomax847/opentagger)";

    /**
     * Cherche les paroles d'un morceau, en tenant compte de sa durée (évite les paroles d'une autre version).
     *  1. lrclib.net, recherche exacte (artiste, titre, album, durée) ;
     *  2. lrclib.net, recherche large (artiste, titre) : on garde la version de même durée (± 3 s) ; si aucune n'a la
     *     même durée, seulement le texte simple d'un résultat au même titre (jamais ses paroles synchronisées) ;
     *  3. Lyrics.OVH, texte simple, seulement si {@code wantPlain}.
     */
    public Found lookup(String artist, String title, String album, int durationSec, boolean wantPlain) {
        String a = clean(artist), t = clean(title);
        if (a.isBlank() || t.isBlank()) return Found.none();
        String q = "?artist_name=" + enc(a) + "&track_name=" + enc(t);
        String getUrl = LRCLIB + q + (album == null || album.isBlank() ? "" : "&album_name=" + enc(album))
                + (durationSec > 0 ? "&duration=" + durationSec : "");
        Fetched got = fetch(getUrl);
        if (got.error != null) return Found.failed(got.error);
        if (got.status == 200 && got.body != null) {
            Found f = fromLrclib(got.body, sameVersion(title, JsonText.of(got.body.path("trackName"), "")));
            if (f.instrumental() || !f.isEmpty()) return f;
        }
        Fetched search = fetch(LRCLIB_SEARCH + q);
        if (search.error != null) return Found.failed(search.error);
        if (search.status == 200 && search.body != null && search.body.isArray()) {
            Found f = pick(search.body, title, durationSec);
            if (f.instrumental() || !f.isEmpty()) return f;
        }
        if (wantPlain && ovhFailures.get() < OVH_MAX_FAILURES) {
            Found f = ovh(a, t);
            if (f != null) return f;
        }
        return Found.none();
    }

    /** Choix parmi les résultats de la recherche large de lrclib (testé dans LyricsCommandTest). */
    static Found pick(JsonNode results, String title, int durationSec) {
        String cleanTitle = clean(title);
        JsonNode best = null;
        double bestGap = Double.MAX_VALUE;
        boolean bestSynced = false;
        for (JsonNode r : results) {
            double d = r.path("duration").asDouble(0);
            double gap = durationSec > 0 && d > 0 ? Math.abs(d - durationSec) : (durationSec > 0 ? Double.MAX_VALUE : 0);
            if (gap > 3) continue;
            boolean synced = !JsonText.of(r.path("syncedLyrics"), "").isBlank()
                    && sameVersion(title, JsonText.of(r.path("trackName"), ""));
            boolean useful = synced || !JsonText.of(r.path("plainLyrics"), "").isBlank() || r.path("instrumental").asBoolean(false);
            if (!useful) continue;
            if (best == null || (synced && !bestSynced) || (synced == bestSynced && gap < bestGap)) {
                best = r; bestGap = gap; bestSynced = synced;
            }
        }
        if (best != null) return fromLrclib(best, bestSynced);
        // Aucune version de la même durée : le texte simple d'un résultat au même titre reste juste (live, remix…),
        // mais pas ses paroles synchronisées, qui seraient décalées.
        String want = norm(cleanTitle);
        for (JsonNode r : results) {
            if (!norm(clean(JsonText.of(r.path("trackName"), ""))).equals(want)) continue;
            Found f = fromLrclib(r, false);
            if (!f.plain().isBlank()) return f;
        }
        return Found.none();
    }

    /** Mots qui désignent une AUTRE version du morceau (remix, live…) : ses paroles synchronisées seraient décalées. */
    private static final java.util.regex.Pattern VARIANT = java.util.regex.Pattern.compile(
            "(?iu)\\b(remix|rmx|mix|live|edit|version|acoustic|acoustique|unplugged|demo|extended|rework|bootleg|mashup|dub|instrumental)\\b");

    private static Set<String> variants(String title) {
        Set<String> out = new java.util.HashSet<>();
        java.util.regex.Matcher m = VARIANT.matcher(title == null ? "" : title);
        while (m.find()) out.add(m.group(1).toLowerCase(java.util.Locale.ROOT));
        return out;
    }

    /** Un morceau « (… Remix) » ou « (Live) » ne prend les paroles synchronisées que d'un résultat de la même sorte. */
    static boolean sameVersion(String fileTitle, String foundTitle) {
        Set<String> want = variants(fileTitle);
        if (want.isEmpty()) return true;
        Set<String> have = variants(foundTitle);
        for (String w : want) if (have.contains(w)) return true;
        return false;
    }

    private static Found fromLrclib(JsonNode root, boolean keepSynced) {
        if (root.path("instrumental").asBoolean(false)) return new Found("", "", "lrclib.net", true, "");
        String synced = keepSynced ? JsonText.of(root.path("syncedLyrics"), "").trim() : "";
        String plain = JsonText.of(root.path("plainLyrics"), "").trim();
        if (plain.isBlank() && !synced.isBlank())
            plain = synced.replaceAll("\\[\\d+:\\d{2}[.:]\\d+\\]\\s*", "").replaceAll("\n{3,}", "\n\n").trim();
        return new Found(plain, synced, "lrclib.net", false, "");
    }

    private Found ovh(String artist, String title) {
        Fetched got = fetch(LYRICSOVH + enc(artist) + "/" + enc(title));
        if (got.error != null || got.status >= 500) { ovhFailures.incrementAndGet(); return null; }
        ovhFailures.set(0);
        if (got.status != 200 || got.body == null) return null;
        String lyrics = JsonText.of(got.body.path("lyrics"), "").replace("\r\n", "\n").trim();
        return lyrics.isBlank() ? null : new Found(lyrics, "", "lyrics.ovh", false, "");
    }

    private record Fetched(int status, JsonNode body, String error) {}

    /** GET JSON ; un service saturé (429, 503) est réessayé une fois après 30 s. */
    private Fetched fetch(String url) {
        for (int attempt = 0; ; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                        .header("User-Agent", PUBLIC_AGENT)
                        .timeout(Duration.ofSeconds(10)).GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                int st = resp.statusCode();
                if ((st == 429 || st == 503) && attempt == 0) { Thread.sleep(30_000); continue; }
                if (st == 429 || st == 503) return new Fetched(st, null, "service saturé (" + st + ")");
                JsonNode body = null;
                if (st == 200) { try { body = mapper.readTree(resp.body()); } catch (Exception ignored) {} }
                return new Fetched(st, body, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Fetched(0, null, "interrompu");
            } catch (Exception e) {
                String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " : " + e.getMessage());
                return new Fetched(0, null, why.length() > 120 ? why.substring(0, 120) : why);
            }
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String norm(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** Nettoie la chaîne pour l'URL (supprime feat., parenthèses, ponctuation). */
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")
                .replaceAll("(?i)\\s*(feat\\.?|ft\\.?)\\s+.*$", "")
                .trim();
    }
}
