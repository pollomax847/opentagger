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
import java.util.ArrayList;
import java.util.List;

public class LastFmClient {

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

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private String                       cachedTagsKey  = null;
    private List<GenreFilter.Candidate>  cachedTagsList = null;

    /** Enrichit le genre d'un TagInfo depuis les tags Last.fm. Ne modifie genre que si vide. */
    public void enrichGenres(TagInfo info) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        // Sans clé configurée, la requête échouerait de toute façon — éviter l'appel réseau
        // inutile sur chaque fichier, même pattern que DiscogsClient/FanArtClient.
        if (Config.get().lastfmKey().isBlank()) return;
        if (!info.genre.isBlank()) return;

        List<GenreFilter.Candidate> allTags = fetchAllTags(info);
        List<GenreFilter.Candidate> genreCandidates = new ArrayList<>();
        for (GenreFilter.Candidate c : allTags)
            if (!isMoodTag(c.name().toLowerCase())) genreCandidates.add(c);

        List<String> genres = GenreFilter.filter(genreCandidates, Config.get().num("lastfm.max_genres", 3));
        if (!genres.isEmpty()) info.genre = joinGenres(genres);
    }

    /** Enrichit les URLs artiste depuis Last.fm (page Last.fm + lien Wikipedia si disponible). */
    public void enrichArtistUrls(TagInfo info) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        if (Config.get().lastfmKey().isBlank()) return;
        if (!info.artistOfficialUrl.isBlank() && !info.artistWikipediaUrl.isBlank()) return;
        if (info.artist.isBlank()) return;

        JsonNode root = fetch(BASE_URL
            + "?method=artist.getInfo"
            + "&artist=" + encode(info.artist)
            + "&api_key=" + Config.get().lastfmKey()
            + "&format=json");
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
    public void enrichMood(TagInfo info) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        if (Config.get().lastfmKey().isBlank()) return;
        if (!info.mood.isBlank()) return;

        List<GenreFilter.Candidate> allTags = fetchAllTags(info);

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
    private List<GenreFilter.Candidate> fetchAllTags(TagInfo info) throws Exception {
        // Cache : évite deux requêtes réseau quand enrichGenres() et enrichMood() sont appelés successivement
        String key = info.artist + "\0" + info.title;
        if (key.equals(cachedTagsKey)) return cachedTagsList;

        List<GenreFilter.Candidate> tags = List.of();
        if (!info.artist.isBlank() && !info.title.isBlank()) {
            tags = getRawTags(BASE_URL
                + "?method=track.getTopTags"
                + "&artist=" + encode(info.artist)
                + "&track="  + encode(info.title)
                + "&api_key=" + Config.get().lastfmKey()
                + "&format=json");
        }
        if (tags.isEmpty() && !info.artist.isBlank()) {
            tags = getRawTags(BASE_URL
                + "?method=artist.getTopTags"
                + "&artist="  + encode(info.artist)
                + "&api_key=" + Config.get().lastfmKey()
                + "&format=json");
        }
        cachedTagsKey  = key;
        cachedTagsList = tags;
        return tags;
    }

    /** Last.fm renvoie un "count" de popularité relative (0-100) par tag — capturé pour GenreFilter. */
    private List<GenreFilter.Candidate> getRawTags(String url) throws Exception {
        JsonNode root = fetch(url);
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

    private JsonNode fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonNode root = mapper.readTree(response.body());
        if (root.has("error")) return null;
        return root;
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
