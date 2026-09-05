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
import java.util.concurrent.TimeUnit;

/**
 * Récupération des paroles depuis deux sources en cascade :
 *  1. lrclib.net — pas de clé, meilleure couverture occidentale, seule à retourner aussi les
 *     lyrics synchronisées (voir enrich(), interrogée en premier depuis le 2026-08-12)
 *  2. Lyrics.OVH (api.lyrics.ovh) — pas de clé, couverture large, texte brut seulement
 */
public class LyricsClient {

    private static final String LYRICSOVH = "https://api.lyrics.ovh/v1/";
    private static final String LRCLIB    = "https://lrclib.net/api/get";

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    public void enrich(TagInfo info) {
        if (!info.lyrics.isBlank()) return;
        if (!Config.get().bool("lyrics.enabled", true)) return;

        String artist = clean(info.artist);
        String title  = clean(info.title);
        if (artist.isBlank() || title.isBlank()) return;

        // Source 1 : lrclib.net — meilleure couverture occidentale ET seule des deux sources à
        // fournir des paroles synchronisées (voir tryLrclib()/TagInfo.syncedLyrics, utilisées pour
        // générer un .lrc à côté de l'audio si lyrics.save_lrc). Interrogée en premier pour
        // maximiser les .lrc synchronisés générés — inversé le 2026-08-12 : avant ce correctif,
        // Lyrics.OVH (texte brut uniquement, ci-dessous) répondait souvent en premier et
        // court-circuitait tryLrclib() ("si toujours vide" ci-dessous jamais atteint dès que
        // Lyrics.OVH avait quelque chose), privant silencieusement ces morceaux de tout .lrc
        // synchronisé même quand lrclib.net en avait un.
        tryLrclib(info, artist, title, info.album);

        // Source 2 : Lyrics.OVH (si toujours vide) — texte brut seulement, jamais de version synchronisée.
        if (info.lyrics.isBlank())
            tryLyricsOvh(info, artist, title);
    }

    private void tryLyricsOvh(TagInfo info, String artist, String title) {
        try {
            String url = LYRICSOVH
                    + URLEncoder.encode(artist, StandardCharsets.UTF_8) + "/"
                    + URLEncoder.encode(title,  StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(8, TimeUnit.SECONDS).join();
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
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> resp = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(8, TimeUnit.SECONDS).join();
            if (resp.statusCode() != 200) return;
            JsonNode root = mapper.readTree(resp.body());
            // Préserver le brut LRC (timestamps compris) séparément — jeté avant ce correctif,
            // voir TagInfo.syncedLyrics pour où il est réellement utilisé (fichier .lrc à côté de
            // l'audio, pas un tag embarqué).
            String synced = root.path("syncedLyrics").asText("").trim();
            if (!synced.isBlank()) info.syncedLyrics = synced;

            // Préférer les paroles non-synchronisées (plainLyrics) pour le tag texte, fallback
            // syncedLyrics nettoyé de ses timestamps si aucune version plate n'est fournie.
            String plain = root.path("plainLyrics").asText("").trim();
            if (plain.isBlank() && !synced.isBlank())
                plain = synced.replaceAll("\\[\\d+:\\d{2}\\.\\d+\\]\\s*", "")
                              .replaceAll("\n{3,}", "\n\n")
                              .trim();
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
