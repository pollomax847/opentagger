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
     * Enrichit un TagInfo : genres, barcode, catalogue number.
     * Stratégie : artiste + album → artiste + titre → artiste seul.
     */
    public void enrichGenres(TagInfo info) throws Exception {
        if (info.artist.isBlank()) return;

        JsonNode hit = null;
        if (!info.album.isBlank())  hit = searchBest(info.artist, info.album);
        if (hit == null && !info.title.isBlank()) hit = searchBest(info.artist, info.title);
        if (hit == null) hit = searchBest(info.artist, "");
        if (hit == null) return;

        // Genres/styles : ordre et limite configurables
        if (info.genre.isBlank()) {
            String source = Config.get().discogsGenreSource();
            int    max    = Config.get().discogsMaxGenres();
            List<String> genres;
            if ("genre_only".equals(source)) {
                genres = extraireTableau(hit.path("genre"));
            } else if ("genre_then_style".equals(source)) {
                genres = extraireTableau(hit.path("genre"));
                if (genres.isEmpty()) genres = extraireTableau(hit.path("style"));
            } else {
                genres = extraireTableau(hit.path("style"));
                if (genres.isEmpty()) genres = extraireTableau(hit.path("genre"));
            }
            if (max > 0 && genres.size() > max) genres = genres.subList(0, max);
            if (!genres.isEmpty()) info.genre = String.join(", ", genres);
        }

        // Barcode (tableau dans les résultats Discogs)
        if (info.barcode.isBlank()) {
            JsonNode barcodes = hit.path("barcode");
            if (barcodes.isArray() && !barcodes.isEmpty()) {
                String bc = barcodes.get(0).asText("").trim().replaceAll("[^0-9]", "");
                if (!bc.isBlank()) info.barcode = bc;
            }
        }

        // Catalogue number
        if (info.catalogNo.isBlank()) {
            String catno = hit.path("catno").asText("").trim();
            if (!catno.isBlank() && !"none".equalsIgnoreCase(catno)) info.catalogNo = catno;
        }

        // Pays de sortie
        if (info.country.isBlank()) {
            String co = hit.path("country").asText("").trim();
            if (!co.isBlank()) info.country = co;
        }

        // Année (fallback si MB n'a pas fourni l'année)
        if (info.year.isBlank()) {
            String yr = hit.path("year").asText("").trim();
            if (yr.matches("\\d{4}")) info.year = yr;
        }

        // Identifiant et URL Discogs
        if (info.discogsId.isBlank()) {
            long id = hit.path("id").asLong(0);
            if (id > 0) {
                info.discogsId = String.valueOf(id);
                String uri = hit.path("uri").asText("").trim();
                if (!uri.isBlank())
                    info.releaseDiscogsUrl = "https://www.discogs.com" + uri;
            }
        }
    }

    private JsonNode searchBest(String artist, String album) throws Exception {
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
        if (response.statusCode() != 200) return null;

        JsonNode results = mapper.readTree(response.body()).path("results");
        return (results.isArray() && !results.isEmpty()) ? results.get(0) : null;
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
