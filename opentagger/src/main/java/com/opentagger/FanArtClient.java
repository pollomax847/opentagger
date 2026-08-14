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

public class FanArtClient {

    private static final String BASE_URL  = "https://webservice.fanart.tv/v3/music";

    private static final HttpClient http = HttpTimeouts.client();
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
    public Path downloadCover(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artistMbid.isBlank()) return null;
        // Sans clé API configurée, la requête échouerait de toute façon — éviter l'appel réseau
        // inutile sur chaque fichier, même pattern que DiscogsClient/lastfmEnabled().
        if (Config.get().fanartKey().isBlank()) return null;
        JsonNode data = fetchArtistData(info.artistMbid, cache);
        if (data == null) return null;

        // Pochette de l'album exact (release group)
        if (!info.releaseGroupMbid.isBlank()) {
            String url = extractBestImage(data.path("albums").path(info.releaseGroupMbid).path("albumcover"));
            if (url != null) return download(url, cache);
        }
        // Premier album dans la discographie
        JsonNode albums = data.path("albums");
        if (albums.isObject()) {
            for (JsonNode album : albums) {
                String url = extractBestImage(album.path("albumcover"));
                if (url != null) return download(url, cache);
            }
        }
        // Photo de l'artiste (dernier recours FanArt.tv)
        String url = extractBestImage(data.path("artistthumb"));
        return url != null ? download(url, cache) : null;
    }

    /**
     * Mise en cache façon Picard (un seul cache réseau pour tout) — clé SANS l'api_key
     * (contrairement à url, qui l'embarque) : un secret n'a rien à faire persisté dans le cache.
     */
    private JsonNode fetchArtistData(String artistMbid, MetadataCache cache) throws Exception {
        String cacheKey = "fanart:artist:" + artistMbid;
        String cachedJson = cache.getLookup(cacheKey);
        if (cachedJson != null) return mapper.readTree(cachedJson);

        String url = BASE_URL + "/" + artistMbid + "?api_key=" + Config.get().fanartKey();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;

        cache.putLookup(cacheKey, response.body());
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

    private Path download(String imageUrl, MetadataCache cache) throws Exception {
        return ImageDownloader.downloadToTempFile(imageUrl, cache);
    }

    /**
     * Photo de l'artiste (distincte de la pochette album) — réutilise fetchArtistData()/
     * extractBestImage(), déjà appelées par downloadCover() ci-dessus comme repli de dernier
     * recours sur "artistthumb" ; ici exposée comme sa propre sortie, sans dépendre d'un échec de
     * pochette album. Aucun nouvel appel réseau : fetchArtistData() est déjà mise en cache
     * ("fanart:artist:<mbid>"), un fichier avec pochette ET portrait d'artiste ne coûte donc pas
     * une deuxième requête HTTP.
     */
    public Path downloadArtistPhoto(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artistMbid.isBlank()) return null;
        if (Config.get().fanartKey().isBlank()) return null;
        JsonNode data = fetchArtistData(info.artistMbid, cache);
        if (data == null) return null;
        String url = extractBestImage(data.path("artistthumb"));
        return url != null ? download(url, cache) : null;
    }
}
