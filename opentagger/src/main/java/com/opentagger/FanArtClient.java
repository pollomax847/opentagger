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

    private static final String BASE_URL = "https://webservice.fanart.tv/v3/music";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Télécharge la pochette d'album dans un fichier temporaire.
     * Retourne le chemin du fichier, ou null si rien trouvé.
     * Stratégie : album cover (release group) → artist thumbnail.
     */
    public Path downloadCover(TagInfo info) throws Exception {
        if (info.artistMbid.isBlank()) return null;

        JsonNode data = fetchArtistData(info.artistMbid);
        if (data == null) return null;

        // Stratégie 1 : pochette de l'album exact (release group)
        if (!info.releaseGroupMbid.isBlank()) {
            String url = extractBestImage(data.path("albums").path(info.releaseGroupMbid).path("albumcover"));
            if (url != null) return download(url);
        }

        // Stratégie 2 : premier album disponible dans la discographie
        JsonNode albums = data.path("albums");
        if (albums.isObject()) {
            for (JsonNode album : albums) {
                String url = extractBestImage(album.path("albumcover"));
                if (url != null) return download(url);
            }
        }

        // Stratégie 3 : photo de l'artiste
        String url = extractBestImage(data.path("artistthumb"));
        if (url != null) return download(url);

        return null;
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

        String ext = imageUrl.endsWith(".png") ? ".png" : ".jpg";
        Path tmp   = Files.createTempFile("opentagger-cover-", ext);
        Files.copy(response.body(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return tmp;
    }
}
