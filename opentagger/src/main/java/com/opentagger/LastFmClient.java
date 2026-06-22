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

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Enrichit le genre d'un TagInfo depuis les tags Last.fm (track puis artist).
     * Ne modifie genre que si toujours vide.
     */
    public void enrichGenres(TagInfo info) throws Exception {
        if (!Config.get().lastfmEnabled()) return;
        if (!info.genre.isBlank()) return;

        // Strategie 1 : tags du morceau
        if (!info.artist.isBlank() && !info.title.isBlank()) {
            List<String> tags = getTrackTags(info.artist, info.title);
            if (!tags.isEmpty()) { info.genre = joinGenres(tags); return; }
        }

        // Strategie 2 : tags de l'artiste
        if (!info.artist.isBlank()) {
            List<String> tags = getArtistTags(info.artist);
            if (!tags.isEmpty()) info.genre = joinGenres(tags);
        }
    }

    private List<String> getTrackTags(String artist, String title) throws Exception {
        String url = BASE_URL
                + "?method=track.getTopTags"
                + "&artist=" + encode(artist)
                + "&track="  + encode(title)
                + "&api_key=" + Config.get().lastfmKey()
                + "&format=json";
        return parseTags(fetch(url));
    }

    private List<String> getArtistTags(String artist) throws Exception {
        String url = BASE_URL
                + "?method=artist.getTopTags"
                + "&artist="  + encode(artist)
                + "&api_key=" + Config.get().lastfmKey()
                + "&format=json";
        return parseTags(fetch(url));
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
        // Last.fm retourne {"error": ...} si probleme
        if (root.has("error")) return null;
        return root;
    }

    private List<String> parseTags(JsonNode root) {
        List<String> result = new ArrayList<>();
        if (root == null) return result;

        // Chemin : toptags.tag[] ou tags.tag[]
        JsonNode tagArray = root.path("toptags").path("tag");
        if (!tagArray.isArray() || tagArray.isEmpty())
            tagArray = root.path("tags").path("tag");
        if (!tagArray.isArray()) return result;

        int max = Config.get().discogsMaxGenres();
        for (JsonNode tag : tagArray) {
            String name = tag.path("name").asText("").trim();
            // Filtrer les tags generiques ou trop courts
            if (!name.isBlank() && name.length() > 2 && !isBlacklisted(name)) {
                result.add(capitalize(name));
                if (result.size() >= max) break;
            }
        }
        return result;
    }

    private boolean isBlacklisted(String tag) {
        String t = tag.toLowerCase();
        return t.equals("seen live") || t.equals("favorites") || t.equals("favourite")
                || t.equals("love") || t.equals("awesome") || t.equals("cool")
                || t.equals("best") || t.equals("good") || t.startsWith("00s")
                || t.startsWith("my ");
    }

    private String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String joinGenres(List<String> tags) {
        return String.join(", ", tags);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
