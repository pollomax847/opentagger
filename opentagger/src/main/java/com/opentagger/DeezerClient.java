package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Télécharge les pochettes depuis l'API publique de Deezer — recherche par texte (artiste +
 * album), aucune clé API requise (contrairement à FanArt.tv/Discogs). Dernier recours dans la
 * cascade {@code TagEnrichment.resolveCover} : sert surtout les fichiers sans MBID exploitable
 * (pas de match MusicBrainz confirmé — ex. identification SongRec/AudD/texte seule), pour lesquels
 * CAA et FanArt.tv ne peuvent structurellement rien renvoyer faute de releaseMbid/releaseGroupMbid/
 * artistMbid.
 */
public class DeezerClient {

    private static final String SEARCH_URL = "https://api.deezer.com/search/album";

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Portrait de l'artiste (API publique, SANS clé) : dernier recours quand ni FanArt.tv (clé requise, MBID requis) ni
     * Discogs n'ont rien. Ne retient le résultat que si le nom de l'artiste trouvé est identique (casse ignorée) :
     * jamais la photo d'un autre artiste sur une recherche texte ambiguë.
     */
    public Path downloadArtistPhoto(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artist == null || info.artist.isBlank()) return null;
        String name = info.artist.trim();
        String cacheKey = "deezer:artist:" + name.toLowerCase();
        String cachedJson = cache.getLookup(cacheKey);
        JsonNode json;
        if (cachedJson != null) {
            json = mapper.readTree(cachedJson);
        } else {
            String url = "https://api.deezer.com/search/artist?q=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8) + "&limit=5";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent()).timeout(HttpTimeouts.apiCall()).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            cache.putLookup(cacheKey, response.body());
            json = mapper.readTree(response.body());
        }
        JsonNode data = json.path("data");
        if (!data.isArray()) return null;
        for (JsonNode a : data) {
            if (!name.equalsIgnoreCase(JsonText.of(a.path("name"), "").trim())) continue;
            String pic = JsonText.of(a.path("picture_xl"), null);
            if (pic == null || pic.isBlank()) pic = JsonText.of(a.path("picture_big"), null);
            // Deezer renvoie une image générique grise quand il n'a pas de portrait : l'URL contient un hash vide.
            if (pic == null || pic.isBlank() || pic.contains("/images/artist//")) return null;
            return ImageDownloader.downloadToTempFile(pic, cache);
        }
        return null;
    }

    /**
     * Cherche l'album par "artiste titre" (recherche texte simple Deezer — la syntaxe avancée
     * artist:"..." album:"..." s'est révélée peu fiable en test manuel) et retourne sa pochette la
     * plus grande disponible. Vérifie que l'artiste du premier résultat correspond (insensible à la
     * casse) avant de télécharger, pour éviter de coller une pochette hors-sujet sur un match texte
     * ambigu.
     */
    public Path downloadCover(TagInfo info, MetadataCache cache) throws Exception {
        if (info.artist.isBlank() || info.album.isBlank()) return null;

        String query = info.artist + " " + info.album;
        String cacheKey = "deezer:album:" + query;
        String cachedJson = cache.getLookup(cacheKey);
        JsonNode json;
        if (cachedJson != null) {
            json = mapper.readTree(cachedJson);
        } else {
            String url = SEARCH_URL + "?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            cache.putLookup(cacheKey, response.body());
            json = mapper.readTree(response.body());
        }

        JsonNode data = json.path("data");
        if (!data.isArray() || data.isEmpty()) return null;
        JsonNode first = data.get(0);

        String foundArtist = JsonText.of(first.path("artist").path("name"), "");
        if (!foundArtist.equalsIgnoreCase(info.artist.trim())) return null;

        String coverUrl = JsonText.of(first.path("cover_xl"), null);
        if (coverUrl == null || coverUrl.isBlank()) coverUrl = JsonText.of(first.path("cover_big"), null);
        return ImageDownloader.downloadToTempFile(coverUrl, cache);
    }
}
