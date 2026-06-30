package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Recherche de podcasts via l'API iTunes Search (gratuite, sans clé).
 *
 * Endpoint : https://itunes.apple.com/search?term=NAME&media=podcast&entity=podcast&limit=8
 */
public class PodcastSearchClient {

    public record PodcastResult(
        String name,
        String author,
        String feedUrl,
        String artworkUrl,
        String genre,
        int    episodeCount
    ) {
        @Override public String toString() { return name + " — " + author; }
    }

    private static final String SEARCH_BASE =
        "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=10&term=";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Recherche des podcasts via iTunes Search API.
     * Essaie d'abord sans pays, puis avec country=FR si aucun résultat avec feedUrl.
     */
    public static List<PodcastResult> search(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        // Passe 1 : sans restriction de pays (résultats globaux avec feedUrl)
        List<PodcastResult> results = doSearch(client, SEARCH_BASE + encoded);

        // Passe 2 : avec country=FR si rien trouvé (certains podcasts FR n'ont pas feedUrl exposé)
        if (results.isEmpty()) {
            results = doSearch(client, SEARCH_BASE + encoded + "&country=FR");
        }
        return results;
    }

    private static List<PodcastResult> doSearch(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("iTunes Search API : HTTP " + resp.statusCode());

        JsonNode root = MAPPER.readTree(resp.body());
        List<PodcastResult> results = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String feedUrl = r.path("feedUrl").asText("").trim();
            if (feedUrl.isBlank()) continue;
            results.add(new PodcastResult(
                r.path("collectionName").asText("").trim(),
                r.path("artistName").asText("").trim(),
                feedUrl,
                r.path("artworkUrl100").asText("").trim(),
                r.path("primaryGenreName").asText("Podcast").trim(),
                r.path("trackCount").asInt(0)
            ));
        }
        return results;
    }
}
