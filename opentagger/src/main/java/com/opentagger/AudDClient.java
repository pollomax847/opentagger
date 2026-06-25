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
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;

/**
 * Reconnaissance audio via l'API AudD (https://audd.io).
 *
 * Avantage sur Shazam : envoie le fichier audio directement en multipart
 * sans conversion PCM — plus simple et plus fiable sur les formats variés.
 *
 * Tier gratuit : 100 reconnaissances/jour.
 * Clé à obtenir sur audd.io et à renseigner dans Préférences → APIs → AudD API Token.
 */
public class AudDClient {

    private static final String API_URL = "https://api.audd.io/";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    // ── Reconnaissance ────────────────────────────────────────────────────────

    /**
     * Envoie les 20 premières secondes du fichier à AudD pour reconnaissance.
     * @return TagInfo avec au moins artist+title, ou null si non reconnu
     */
    public TagInfo recognize(File audioFile) throws Exception {
        String key = Config.get().str("audd.api_token", "").trim();
        if (key.isBlank()) return null;

        // Extraire 20 secondes avec ffmpeg si disponible, sinon envoyer le fichier complet
        byte[] audioBytes = extractSegment(audioFile);

        String boundary = "---OT" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipart(boundary, audioBytes,
                audioFile.getName().endsWith(".mp3") ? "audio.mp3" : "audio.bin",
                key);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", Config.get().userAgent())
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("AudD HTTP " + resp.statusCode());

        return parse(resp.body());
    }

    public static boolean isAvailable() {
        return !Config.get().str("audd.api_token", "").isBlank();
    }

    // ── Extraction du segment audio ───────────────────────────────────────────

    private byte[] extractSegment(File f) {
        String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffmpeg,
                "-ss", "5",          // démarrer à 5 secondes
                "-i", f.getAbsolutePath(),
                "-t", "20",          // 20 secondes
                "-f", "mp3",         // encoder en MP3 (format compact)
                "-ab", "128k",
                "-loglevel", "quiet",
                "pipe:1"
            );
            pb.redirectErrorStream(false);
            byte[] data = ProcessUtils.readWithTimeout(pb, 35);
            if (data != null && data.length > 4096) return data; // succès
        } catch (Exception ignored) {}

        // Fallback : envoyer les 2 premiers Mo du fichier original
        try {
            byte[] all = Files.readAllBytes(f.toPath());
            return all.length > 2_000_000 ? java.util.Arrays.copyOf(all, 2_000_000) : all;
        } catch (Exception e) { return new byte[0]; }
    }

    // ── Construction du multipart ────────────────────────────────────────────

    private byte[] buildMultipart(String boundary, byte[] audioBytes, String filename, String token)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // -- api_token
        writePart(baos, boundary, "api_token", token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // -- return
        writePart(baos, boundary, "return", "apple_music,spotify".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // -- file
        baos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        baos.write("Content-Type: audio/mpeg\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        baos.write(audioBytes);
        baos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // -- close
        baos.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return baos.toByteArray();
    }

    private void writePart(ByteArrayOutputStream baos, String boundary, String name, byte[] value)
            throws IOException {
        baos.write(("--" + boundary + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        baos.write(value);
        baos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ── Parsing de la réponse ─────────────────────────────────────────────────

    private TagInfo parse(String json) throws Exception {
        JsonNode root   = mapper.readTree(json);
        String   status = root.path("status").asText("");
        if (!"success".equals(status)) return null;

        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) return null;

        String title  = result.path("title").asText("").trim();
        String artist = result.path("artist").asText("").trim();
        if (title.isBlank() && artist.isBlank()) return null;

        TagInfo ti = new TagInfo();
        ti.title       = title;
        ti.artist      = artist;
        ti.albumArtist = artist;
        ti.score       = 80;

        // Album et date depuis AudD directement
        String album = result.path("album").asText("").trim();
        String date  = result.path("release_date").asText("").trim();
        if (!album.isBlank()) ti.album = album;
        if (date.length() >= 4) ti.year = date.substring(0, 4);

        // ISRC depuis Spotify (très utile pour les IDs)
        JsonNode spotify = result.path("spotify");
        if (!spotify.isMissingNode()) {
            String isrc = spotify.path("external_ids").path("isrc").asText("").trim();
            if (!isrc.isBlank()) ti.isrc = isrc;
            // Album depuis Spotify si plus précis
            if (ti.album.isBlank()) {
                String spAlbum = spotify.path("album").path("name").asText("").trim();
                if (!spAlbum.isBlank()) ti.album = spAlbum;
            }
            if (ti.year.isBlank()) {
                String spDate = spotify.path("album").path("release_date").asText("").trim();
                if (spDate.length() >= 4) ti.year = spDate.substring(0, 4);
            }
        }

        // Genre depuis Apple Music
        JsonNode appleMusic = result.path("apple_music");
        if (!appleMusic.isMissingNode()) {
            JsonNode genres = appleMusic.path("genreNames");
            if (genres.isArray() && genres.size() > 0) {
                String g = genres.get(0).asText("").trim();
                if (!g.isBlank() && !"Music".equals(g)) ti.genre = g;
            }
            // Track number
            int trackNum = appleMusic.path("trackNumber").asInt(0);
            if (trackNum > 0) ti.track = String.valueOf(trackNum);
        }

        return ti;
    }
}
