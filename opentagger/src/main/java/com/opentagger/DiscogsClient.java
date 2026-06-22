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

public class DiscogsClient {

    private static final String BASE_URL = "https://api.discogs.com";

    private String authHeader() {
        return "Discogs key=" + Config.get().discogsKey() + ", secret=" + Config.get().discogsSecret();
    }

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Enrichit un TagInfo existant avec les genres et styles Discogs.
     * Stratégie : artiste + album → si rien, artiste seul.
     */
    public void enrichGenres(TagInfo info) throws Exception {
        if (info.artist.isBlank()) return;

        // Stratégie 1 : artiste + album
        if (!info.album.isBlank()) {
            List<String> genres = search(info.artist, info.album);
            if (!genres.isEmpty()) { info.genre = String.join(", ", genres); return; }
        }

        // Stratégie 2 : artiste + titre
        if (!info.title.isBlank()) {
            List<String> genres = search(info.artist, info.title);
            if (!genres.isEmpty()) { info.genre = String.join(", ", genres); return; }
        }

        // Stratégie 3 : artiste seul (prend le genre le plus fréquent)
        List<String> genres = search(info.artist, "");
        if (!genres.isEmpty()) info.genre = String.join(", ", genres);
    }

    private List<String> search(String artist, String album) throws Exception {
        String url = BASE_URL + "/database/search?type=release&per_page=5"
                + "&artist=" + encode(artist)
                + (album.isBlank() ? "" : "&release_title=" + encode(album));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent",    Config.get().userAgent())
                .header("Authorization", authHeader())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return List.of();

        JsonNode results = mapper.readTree(response.body()).path("results");
        if (!results.isArray() || results.isEmpty()) return List.of();

        // Styles d'abord (plus précis), sinon genres
        JsonNode premier = results.get(0);
        List<String> genres = extraireTableau(premier.path("style"));
        if (genres.isEmpty()) genres = extraireTableau(premier.path("genre"));
        return genres;
    }

    private List<String> extraireTableau(JsonNode node) {
        List<String> liste = new ArrayList<>();
        if (node.isArray()) node.forEach(n -> liste.add(n.asText()));
        return liste;
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
