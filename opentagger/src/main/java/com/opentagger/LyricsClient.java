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
 * Récupération des paroles depuis deux sources en cascade :
 *  1. Lyrics.OVH (api.lyrics.ovh) — pas de clé, couverture large
 *  2. lrclib.net — pas de clé, meilleure couverture occidentale, retourne aussi les lyrics synchros
 */
public class LyricsClient {

    private static final String LYRICSOVH = "https://api.lyrics.ovh/v1/";
    private static final String LRCLIB    = "https://lrclib.net/api/get";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public void enrich(TagInfo info) {
        if (!info.lyrics.isBlank()) return;
        if (!Config.get().bool("lyrics.enabled", true)) return;

        String artist = clean(info.artist);
        String title  = clean(info.title);
        if (artist.isBlank() || title.isBlank()) return;

        // Source 1 : Lyrics.OVH
        tryLyricsOvh(info, artist, title);

        // Source 2 : lrclib.net (si toujours vide)
        if (info.lyrics.isBlank())
            tryLrclib(info, artist, title, info.album);
    }

    private void tryLyricsOvh(TagInfo info, String artist, String title) {
        try {
            String url = LYRICSOVH
                    + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                    + URLEncoder.encode(title,  StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            String lyrics = mapper.readTree(resp.body()).path("lyrics").asText("").trim();
            if (!lyrics.isBlank()) {
                info.lyrics    = lyrics;
                info.lyricsUrl = "https://www.lyrics.ovh/lyrics/"
                        + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                        + URLEncoder.encode(title,  StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
    }

    private void tryLrclib(TagInfo info, String artist, String title, String album) {
        try {
            String url = LRCLIB
                    + "?artist_name=" + URLEncoder.encode(artist, StandardCharsets.UTF_8)
                    + "&track_name="  + URLEncoder.encode(title,  StandardCharsets.UTF_8)
                    + (album.isBlank() ? "" : "&album_name=" + URLEncoder.encode(album, StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonNode root = mapper.readTree(resp.body());
            // Préférer les paroles non-synchronisées (plainLyrics), fallback syncedLyrics nettoyé
            String plain = root.path("plainLyrics").asText("").trim();
            if (plain.isBlank()) {
                String synced = root.path("syncedLyrics").asText("").trim();
                if (!synced.isBlank())
                    plain = synced.replaceAll("\\[\\d{2}:\\d{2}\\.\\d+\\]\\s*", "").trim();
            }
            if (!plain.isBlank()) {
                info.lyrics    = plain;
                info.lyricsUrl = "https://lrclib.net";
            }
        } catch (Exception ignored) {}
    }

    /** Nettoie la chaîne pour l'URL (supprime feat., parenthèses, ponctuation). */
    private String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "")
                .replaceAll("(?i)\\s*(feat\\.?|ft\\.?)\\s+.*$", "")
                .trim();
    }
}
