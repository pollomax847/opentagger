package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class FanArtClient {

    private static final String BASE_URL  = "https://webservice.fanart.tv/v3/music";
    private static final String CAA_URL   = "https://coverartarchive.org";

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Télécharge la pochette d'album dans un fichier temporaire.
     * Retourne le chemin du fichier, ou null si rien trouvé.
     * Stratégie : FanArt.tv (album exact → discographie → artiste) → Cover Art Archive (MusicBrainz).
     */
    public Path downloadCover(TagInfo info) throws Exception {
        // ── 1. FanArt.tv ─────────────────────────────────────────────────────
        if (!info.artistMbid.isBlank()) {
            JsonNode data = fetchArtistData(info.artistMbid);
            if (data != null) {
                // Pochette de l'album exact (release group)
                if (!info.releaseGroupMbid.isBlank()) {
                    String url = extractBestImage(data.path("albums").path(info.releaseGroupMbid).path("albumcover"));
                    if (url != null) return download(url);
                }
                // Premier album dans la discographie
                JsonNode albums = data.path("albums");
                if (albums.isObject()) {
                    for (JsonNode album : albums) {
                        String url = extractBestImage(album.path("albumcover"));
                        if (url != null) return download(url);
                    }
                }
                // Photo de l'artiste (dernier recours FanArt.tv)
                String url = extractBestImage(data.path("artistthumb"));
                if (url != null) return download(url);
            }
        }

        // ── 2. Cover Art Archive (MusicBrainz) ───────────────────────────────
        // Gratuit, sans clé, ~95 % de couverture des releases MB.
        // Essaye : release exacte → release group
        if (!info.releaseMbid.isBlank()) {
            Path p = downloadFromCaa("/release/" + info.releaseMbid + "/front");
            if (p != null) return p;
        }
        if (!info.releaseGroupMbid.isBlank()) {
            Path p = downloadFromCaa("/release-group/" + info.releaseGroupMbid + "/front");
            if (p != null) return p;
        }

        return null;
    }

    private Path downloadFromCaa(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CAA_URL + path))
                    .header("User-Agent", Config.get().userAgent())
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) return null;
            // Détecter le type depuis le Content-Type ou les premiers octets
            String ct  = resp.headers().firstValue("Content-Type").orElse("");
            String ext = ct.contains("png") ? ".png" : ".jpg";
            Path tmp   = Files.createTempFile("opentagger-cover-", ext);
            Files.copy(resp.body(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(tmp) < 1000) { Files.deleteIfExists(tmp); return null; }
            return tmp;
        } catch (Exception e) { return null; }
    }

    private JsonNode fetchArtistData(String artistMbid) throws Exception {
        String url = BASE_URL + "/" + artistMbid + "?api_key=" + Config.get().fanartKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        return mapper.readTree(response.body());
    }

    private String extractBestImage(JsonNode images) {
        if (!images.isArray() || images.isEmpty()) return null;
        // On prend l'image avec le plus de "likes"
        JsonNode best = null;
        int maxLikes  = -1;
        for (JsonNode img : images) {
            int likes = img.path("likes").asInt(0);
            if (likes > maxLikes) { maxLikes = likes; best = img; }
        }
        return best != null ? best.path("url").asText(null) : null;
    }

    private Path download(String imageUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) return null;

        // Détecter le type depuis le Content-Type (l'URL peut ne pas finir par .png/.jpg)
        String ct  = response.headers().firstValue("Content-Type").orElse("");
        String ext = ct.contains("png") ? ".png" : ".jpg";
        Path tmp   = Files.createTempFile("opentagger-cover-", ext);
        Files.copy(response.body(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(tmp) < 1000) { Files.deleteIfExists(tmp); return null; }
        return tmp;
    }
}
