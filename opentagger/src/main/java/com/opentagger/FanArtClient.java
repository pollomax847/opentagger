package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class FanArtClient {

    private static final String BASE_URL  = "https://webservice.fanart.tv/v3/music";

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Télécharge la pochette d'album depuis FanArt.tv dans un fichier temporaire.
     * Retourne le chemin du fichier, ou null si rien trouvé.
     * Stratégie : album exact → discographie → photo artiste.
     *
     * Le repli Cover Art Archive est géré séparément par {@code TagEnrichment.resolveCover} via
     * {@code CaaClient} — retiré d'ici pour ne plus interroger CAA deux fois (bug trouvé : ce
     * repli interne dupliquait un chemin de code indépendant de CaaClient).
     */
    public Path downloadCover(TagInfo info) throws Exception {
        if (info.artistMbid.isBlank()) return null;
        // Sans clé API configurée, la requête échouerait de toute façon — éviter l'appel réseau
        // inutile sur chaque fichier, même pattern que DiscogsClient/lastfmEnabled().
        if (Config.get().fanartKey().isBlank()) return null;
        JsonNode data = fetchArtistData(info.artistMbid);
        if (data == null) return null;

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
        return url != null ? download(url) : null;
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
        return ImageDownloader.downloadToTempFile(imageUrl);
    }
}
