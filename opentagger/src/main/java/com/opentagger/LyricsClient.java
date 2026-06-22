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

/**
 * Récupération des paroles depuis lyricsovh (API publique, pas de clé).
 * Endpoint : https://api.lyrics.ovh/v1/{artist}/{title}
 */
public class LyricsClient {

    private static final String BASE = "https://api.lyrics.ovh/v1/";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Remplit info.lyrics si vide et que les paroles sont trouvées.
     * Silencieux en cas d'échec.
     */
    public void enrich(TagInfo info) {
        if (!info.lyrics.isBlank()) return;
        if (!Config.get().bool("lyrics.enabled", true)) return;

        String artist = clean(info.artist);
        String title  = clean(info.title);
        if (artist.isBlank() || title.isBlank()) return;

        try {
            String url = BASE
                    + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                    + URLEncoder.encode(title,  StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;

            JsonNode root = mapper.readTree(resp.body());
            String lyrics = root.path("lyrics").asText("").trim();
            if (!lyrics.isBlank()) {
                info.lyrics = lyrics;
                // URL de référence (LyricsOvh reconstruit l'URL canonique)
                info.lyricsUrl = "https://www.lyrics.ovh/lyrics/"
                        + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                        + URLEncoder.encode(title,  StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
    }

    /** Nettoie la chaîne pour l'URL (supprime feat., parenthèses, ponctuation). */
    private String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")   // (feat. X), [Live]
                .replaceAll("(?i)\\s*(feat\\.?|ft\\.?)\\s+.*$", "")
                .trim();
    }
}
