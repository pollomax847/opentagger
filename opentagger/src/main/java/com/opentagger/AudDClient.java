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

    // Une seule popup par session, même si les 12 threads de taguage tombent tous sur la même
    // erreur d'authentification en même temps (jeton invalide → échoue IDENTIQUEMENT pour chaque
    // fichier tenté) — sans ce verrou, autant de popups que d'appels AudD en vol au moment où le
    // jeton expire. Retour utilisateur (2026-08-21) : "faut mettre un popup pour la mettre à jour
    // même sans passer par préférences" après avoir découvert le jeton expiré/invalide caché dans
    // le journal, jamais signalé autrement qu'en texte noyé parmi des milliers de lignes.
    private static final java.util.concurrent.atomic.AtomicBoolean TOKEN_PROMPT_SHOWN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Vrai si le message d'erreur AudD indique un jeton invalide/inactif/expiré (par opposition à
     *  un quota dépassé ou un souci réseau transitoire, qui ne se règlent pas en changeant le jeton). */
    public static boolean isAuthError(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("authorization failed") || m.contains("api_token is incorrect")
                || m.contains("invalid, or inactive") || m.contains("api token");
    }

    /** Popup non bloquant (appelable depuis n'importe quel thread de fond) proposant de corriger
     *  le jeton AudD directement, sans passer par la fenêtre Préférences complète — au plus UNE
     *  fois par session, quel que soit le nombre de fichiers qui échouent avec la même cause. */
    public static void maybePromptTokenUpdate(String errorMessage) {
        if (!isAuthError(errorMessage)) return;
        if (!TOKEN_PROMPT_SHOWN.compareAndSet(false, true)) return;
        javax.swing.SwingUtilities.invokeLater(() -> {
            String current = Config.get().str("audd.api_token", "");
            javax.swing.JTextField field = new javax.swing.JTextField(current, 30);
            Object[] message = {
                I18n.t("Le jeton API AudD est invalide, inactif ou expiré — chaque reconnaissance "
                     + "échoue avec cette même erreur depuis le début de cette session :"),
                new javax.swing.JLabel("<html><i>" + errorMessage + "</i></html>"),
                I18n.t("Nouveau jeton (audd.io → tableau de bord) :"),
                field
            };
            int ok = javax.swing.JOptionPane.showConfirmDialog(null, message,
                    I18n.t("Jeton AudD invalide"), javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (ok == javax.swing.JOptionPane.OK_OPTION) {
                String updated = field.getText().trim();
                if (!updated.isBlank() && !updated.equals(current)) {
                    Config.get().set("audd.api_token", updated);
                }
            }
        });
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient http = HttpTimeouts.client();

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
                .timeout(HttpTimeouts.largeDownload())
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
        if ("error".equals(status)) {
            // AudD répond en HTTP 200 même en cas d'erreur (jeton invalide/inactif, quota, etc.) —
            // sans ce check, une vraie erreur de compte était confondue avec "rien trouvé" et
            // disparaissait silencieusement du journal (cf. bug SongRec identique, déjà corrigé).
            String msg = root.path("error").path("error_message").asText("");
            throw new Exception(msg.isBlank() ? "AudD status=error" : msg);
        }
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
