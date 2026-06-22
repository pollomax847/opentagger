package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reconnaissance audio via l'API Shazam (RapidAPI).
 *
 * Utilise ffmpeg pour extraire 15 secondes de PCM brut (8 kHz mono 16-bit),
 * puis envoie ces données à l'endpoint Shazam pour identification.
 *
 * Nécessite une clé RapidAPI (gratuit jusqu'à 500 req/mois).
 * Obtenir une clé sur : https://rapidapi.com/apidojo/api/shazam
 */
public class ShazamClient {

    private static final String API_URL  =
        "https://shazam.p.rapidapi.com/songs/v2/detect?timezone=Europe%2FParis&locale=fr-FR";
    private static final String API_HOST = "shazam.p.rapidapi.com";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    // ── Reconnaissance ──────���───────────────────────────────────��─────────────

    /**
     * Reconnaît un fichier audio avec Shazam.
     * @return TagInfo avec au moins artist+title, ou null si non reconnu
     */
    public TagInfo recognize(File audioFile) throws Exception {
        String key = Config.get().str("rapidapi.key", "").trim();
        if (key.isBlank()) return null;

        String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
        byte[] pcm    = extractPcm(audioFile, ffmpeg);
        if (pcm == null || pcm.length == 0) return null;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("content-type",   "text/plain")
                .header("x-rapidapi-key", key)
                .header("x-rapidapi-host", API_HOST)
                .POST(HttpRequest.BodyPublishers.ofByteArray(pcm))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 429) throw new Exception("Quota RapidAPI dépassé (429)");
            throw new Exception("Shazam HTTP " + resp.statusCode());
        }

        return parse(resp.body());
    }

    /** @return true si une clé RapidAPI est configurée ET ffmpeg est disponible */
    public static boolean isAvailable() {
        return !Config.get().str("rapidapi.key", "").isBlank()
            && BpmDetector.isAvailable(); // BpmDetector.isAvailable() vérifie ffmpeg
    }

    // ── Extraction PCM ──────��─────────────────────────────────────────────────

    private byte[] extractPcm(File f, String ffmpeg) {
        try {
            // Extraire 15 secondes démarrant à 10s (éviter les intros longues)
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg,
                "-ss", "10",                // début à 10 secondes
                "-i", f.getAbsolutePath(),
                "-t", "15",                 // 15 secondes
                "-ar", "8000",              // 8 kHz
                "-ac", "1",                 // mono
                "-f", "s16le",              // PCM 16-bit little-endian
                "-loglevel", "quiet",
                "pipe:1"
            );
            pb.redirectErrorStream(false);
            Process p = pb.start();
            byte[] data = p.getInputStream().readAllBytes();
            p.waitFor();
            return data.length > 0 ? data : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Parsing de la réponse ─────────────────────────────────────────────────

    private TagInfo parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode track = root.path("track");
        if (track.isMissingNode() || track.isNull()) return null;

        String title    = track.path("title").asText("").trim();
        String artist   = track.path("subtitle").asText("").trim();
        if (title.isBlank() && artist.isBlank()) return null;

        TagInfo ti = new TagInfo();
        ti.title       = title;
        ti.artist      = artist;
        ti.albumArtist = artist;
        ti.score       = 82; // score Shazam = fiable mais pas parfait

        // Métadonnées complémentaires depuis sections
        for (JsonNode section : track.path("sections")) {
            if ("SONG".equals(section.path("type").asText())) {
                for (JsonNode meta : section.path("metadata")) {
                    String name = meta.path("title").asText("");
                    String text = meta.path("text").asText("").trim();
                    switch (name) {
                        case "Album"    -> ti.album = text;
                        case "Released" -> ti.year  = text.length() >= 4 ? text.substring(0, 4) : text;
                        case "Label"    -> ti.comment = "Label: " + text;
                    }
                }
            }
        }

        // Genres depuis tag list
        JsonNode genres = track.path("genres");
        if (!genres.isMissingNode()) {
            String primary = genres.path("primary").asText("").trim();
            if (!primary.isBlank()) ti.genre = primary;
        }

        return ti;
    }
}
