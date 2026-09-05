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
import java.util.ArrayList;
import java.util.List;

public class LastFmClient {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(LastFmClient.class.getName());
    private static final String BASE_URL = "https://ws.audioscrobbler.com/2.0/";

    // Tags Last.fm classifiés comme "mood"
    private static final String[][] MOOD_MAP = {
        {"happy", "upbeat", "feel good", "feel-good", "joyful", "cheerful", "fun", "positive"},
        {"sad", "melancholic", "melancholy", "depressing", "heartbreak", "emotional", "tearjerker"},
        {"chill", "chillout", "relax", "relaxed", "calm", "peaceful", "soothing", "mellow", "laid back"},
        {"energetic", "energy", "pump up", "adrenaline", "workout", "running", "power"},
        {"aggressive", "angry", "rage", "intense", "harsh"},
        {"romantic", "love", "romance", "sensual"},
        {"party", "dance", "danceable", "club", "rave"},
        {"dark", "haunting", "gloomy", "atmospheric", "noir"},
        {"acoustic", "unplugged", "folk acoustic"},
        {"instrumental", "no vocals"},
    };
    private static final String[] MOOD_LABELS = {
        "Happy", "Sad", "Relaxed", "Energetic", "Aggressive",
        "Romantic", "Party", "Dark", "Acoustic", "Instrumental"
    };

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    private String                       cachedTagsKey  = null;
    private List<GenreFilter.Candidate>  cachedTagsList = null;

    /** Enrichit le genre d'un TagInfo depuis les tags Last.fm. Ne modifie genre que si vide. */
    public void enrichGenres(TagInfo info, MetadataCache cache) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        // Sans clé configurée, la requête échouerait de toute façon — éviter l'appel réseau
        // inutile sur chaque fichier, même pattern que DiscogsClient/FanArtClient.
        if (Config.get().lastfmKey().isBlank()) return;
        if (!info.genre.isBlank()) return;

        List<GenreFilter.Candidate> allTags = fetchAllTags(info, cache);
        List<GenreFilter.Candidate> genreCandidates = new ArrayList<>();
        for (GenreFilter.Candidate c : allTags)
            if (!isMoodTag(c.name().toLowerCase())) genreCandidates.add(c);

        List<String> genres = GenreFilter.filter(genreCandidates, Config.get().genreMaxCount());
        if (!genres.isEmpty()) info.genre = joinGenres(genres);
    }

    /** Enrichit les URLs artiste depuis Last.fm (page Last.fm + lien Wikipedia si disponible). */
    public void enrichArtistUrls(TagInfo info, MetadataCache cache) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        if (!Config.get().lastfmArtistUrlsEnabled()) return;
        if (Config.get().lastfmKey().isBlank()) return;
        if (!info.artistOfficialUrl.isBlank() && !info.artistWikipediaUrl.isBlank()) return;
        if (info.artist.isBlank()) return;

        // Clé de cache SANS l'api_key (contrairement à l'URL réellement appelée) — un secret n'a
        // rien à faire persisté dans le cache SQLite, et il n'a de toute façon aucune valeur
        // discriminante pour l'identité de la requête.
        String cacheKey = "lastfm:artistinfo:" + MetadataCache.queryHash(info.artist, "");
        JsonNode root = fetch(BASE_URL
            + "?method=artist.getInfo"
            + "&artist=" + encode(info.artist)
            + "&api_key=" + Config.get().lastfmKey()
            + "&format=json", cacheKey, cache);
        if (root == null) return;

        JsonNode artist = root.path("artist");
        if (info.artistOfficialUrl.isBlank()) {
            String url = artist.path("url").asText("").trim();
            if (!url.isBlank()) info.artistOfficialUrl = url;
        }
        // Last.fm inclut parfois un lien Wikipedia dans les "links"
        if (info.artistWikipediaUrl.isBlank()) {
            JsonNode links = artist.path("bio").path("links").path("link");
            if (links.isArray()) {
                for (JsonNode link : links) {
                    String href = link.path("href").asText("").trim();
                    if (href.contains("wikipedia.org")) {
                        info.artistWikipediaUrl = href;
                        break;
                    }
                }
            }
        }
    }

    /** Enrichit le mood d'un TagInfo depuis les tags Last.fm. Ne modifie mood que si vide. */
    public void enrichMood(TagInfo info, MetadataCache cache) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        if (Config.get().lastfmKey().isBlank()) return;
        if (!info.mood.isBlank()) return;

        List<GenreFilter.Candidate> allTags = fetchAllTags(info, cache);

        for (GenreFilter.Candidate c : allTags) {
            String t = c.name().toLowerCase().trim();
            if (t.equals("instrumental") || t.equals("no vocals")) {
                info.isInstrumental = "1";
            }
            for (int i = 0; i < MOOD_MAP.length; i++) {
                for (String kw : MOOD_MAP[i]) {
                    if (t.contains(kw)) {
                        info.mood = MOOD_LABELS[i];
                        return;
                    }
                }
            }
        }
    }

    /** Récupère tous les tags bruts Last.fm avec leur popularité (morceau puis artiste en fallback). */
    private List<GenreFilter.Candidate> fetchAllTags(TagInfo info, MetadataCache cache) throws Exception {
        // Cache mémoire d'appel : évite deux requêtes réseau quand enrichGenres() et enrichMood()
        // sont appelés successivement SUR LA MÊME instance (voir doc de classe : jamais partagée
        // entre threads). Le cache SQLite ci-dessous (getRawTags) prend le relais entre pistes
        // différentes/instances différentes du même artiste — façon Picard, un seul cache réseau.
        String key = info.artist + "\0" + info.title;
        if (key.equals(cachedTagsKey)) return cachedTagsList;

        List<GenreFilter.Candidate> tags = List.of();
        if (!info.artist.isBlank() && !info.title.isBlank()) {
            String cacheKey = "lastfm:tags:track:" + MetadataCache.queryHash(info.artist, info.title);
            tags = getRawTags(BASE_URL
                + "?method=track.getTopTags"
                + "&artist=" + encode(info.artist)
                + "&track="  + encode(info.title)
                + "&api_key=" + Config.get().lastfmKey()
                + "&format=json", cacheKey, cache);
        }
        if (tags.isEmpty() && !info.artist.isBlank()) {
            String cacheKey = "lastfm:tags:artist:" + MetadataCache.queryHash(info.artist, "");
            tags = getRawTags(BASE_URL
                + "?method=artist.getTopTags"
                + "&artist="  + encode(info.artist)
                + "&api_key=" + Config.get().lastfmKey()
                + "&format=json", cacheKey, cache);
        }
        cachedTagsKey  = key;
        cachedTagsList = tags;
        return tags;
    }

    /** Last.fm renvoie un "count" de popularité relative (0-100) par tag — capturé pour GenreFilter. */
    private List<GenreFilter.Candidate> getRawTags(String url, String cacheKey, MetadataCache cache) throws Exception {
        JsonNode root = fetch(url, cacheKey, cache);
        List<GenreFilter.Candidate> result = new ArrayList<>();
        if (root == null) return result;

        JsonNode tagArray = root.path("toptags").path("tag");
        if (!tagArray.isArray() || tagArray.isEmpty())
            tagArray = root.path("tags").path("tag");
        if (!tagArray.isArray()) return result;

        for (JsonNode tag : tagArray) {
            String name  = tag.path("name").asText("").trim();
            int    count = tag.path("count").asInt(0);
            if (!name.isBlank() && name.length() > 2)
                result.add(new GenreFilter.Candidate(name, count));
        }
        return result;
    }

    /**
     * Requête HTTP avec mise en cache SQLite persistante façon Picard (QNetworkDiskCache met en
     * cache TOUT appel réseau uniformément, pas seulement MusicBrainz) — avant ce fix, chaque
     * piste d'un même artiste/album refaisait ces appels Last.fm à l'identique. cacheKey est
     * construit SANS l'api_key (contrairement à url) : un secret n'a rien à faire persisté dans
     * le cache, et il n'apporte aucune valeur discriminante pour l'identité de la requête.
     */
    private JsonNode fetch(String url, String cacheKey, MetadataCache cache) throws Exception {
        String cachedJson = cache.getLookup(cacheKey);
        if (cachedJson != null) {
            JsonNode cached = mapper.readTree(cachedJson);
            return cached.has("error") ? null : cached;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .GET()
                .build();
        HttpResponse<String> response = sendWithThrottleRetry(request);
        if (response == null || response.statusCode() != 200) return null;
        JsonNode root = mapper.readTree(response.body());
        if (root.has("error")) return null;
        cache.putLookup(cacheKey, response.body());
        return root;
    }

    /**
     * Avant ce correctif : un 429 (quota Last.fm dépassé) ou toute autre erreur HTTP se traduisait
     * en simple `return null`, indiscernable dans les logs d'un "genre non trouvé" légitime. Une
     * seule retentative après le délai Retry-After (5s à défaut) suffit — Last.fm n'est qu'un
     * repli parmi d'autres dans TagEnrichment.enrichGenre, pas la source principale.
     */
    private HttpResponse<String> sendWithThrottleRetry(HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            long waitMs = 5000;
            try {
                waitMs = Long.parseLong(response.headers().firstValue("Retry-After").orElse("5")) * 1000L;
            } catch (NumberFormatException ignored) {}
            LOG.info("Last.fm 429 (quota dépassé) — nouvelle tentative dans " + (waitMs / 1000) + "s");
            Thread.sleep(waitMs);
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        if (response.statusCode() != 200)
            LOG.warning("Last.fm HTTP " + response.statusCode() + " : " + request.uri());
        return response;
    }

    /**
     * Récupère le classement des pistes les plus écoutées par {@code username} (user.getTopTracks),
     * jusqu'à {@code maxTracks} pistes (pagination automatique, 1000/page — plafond confirmé en
     * direct sur ws.audioscrobbler.com le 2026-08-29). Même architecture que
     * ListenBrainzClient.fetchTopRecordingCounts() (un seul fetch en masse plutôt qu'un appel par
     * fichier) — voir LastFmSyncWorker, son seul appelant.
     *
     * @return map recordingMbid → nombre d'écoutes (les pistes sans MBID résolu côté Last.fm — assez
     *         fréquent, la résolution dépend des tags du scrobble d'origine — sont ignorées, pas une
     *         erreur : rien à quoi les rattacher côté fichiers déjà identifiés par MBID).
     */
    public java.util.Map<String, Integer> fetchTopTrackCounts(String username, int maxTracks) throws Exception {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        int perPage = 1000;
        int page = 1;
        while (counts.size() < maxTracks) {
            int want = Math.min(perPage, maxTracks - counts.size());
            String url = BASE_URL + "?method=user.gettoptracks&user=" + encode(username)
                    + "&api_key=" + Config.get().lastfmKey() + "&format=json"
                    + "&limit=" + want + "&page=" + page;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
                throw new Exception("Last.fm HTTP " + response.statusCode() + " : " + response.body());

            JsonNode tracks = mapper.readTree(response.body()).path("toptracks").path("track");
            if (!tracks.isArray() || tracks.isEmpty()) break;

            for (JsonNode t : tracks) {
                String mbid = t.path("mbid").asText("").trim();
                int    n    = t.path("playcount").asInt(0);
                if (!mbid.isBlank() && n > 0) counts.put(mbid, n);
            }

            if (tracks.size() < want) break; // dernière page (moins de résultats que demandé)
            page++;
        }
        return counts;
    }

    private boolean isMoodTag(String t) {
        for (String[] group : MOOD_MAP)
            for (String kw : group)
                if (t.contains(kw)) return true;
        return false;
    }

    private String joinGenres(List<String> tags) {
        return String.join(", ", tags);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
