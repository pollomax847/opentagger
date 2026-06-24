package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AcoustIdClient {

    private static final String LOOKUP_URL = "https://api.acoustid.org/v2/lookup";
    private static final String SUBMIT_URL = "https://api.acoustid.org/v2/submit";
    private static final String MB_URL     = "https://musicbrainz.org/ws/2/recording/";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MusicBrainzClient mbClient = new MusicBrainzClient();

    // Fingerprint du dernier fichier identifié — réutilisé pour submit()
    private String lastFingerprint = "";
    private String lastDuration    = "";
    private String lastAcoustId    = "";

    public List<TagInfo> identify(File fichier) throws Exception {
        lastFingerprint = "";
        lastDuration    = "";
        lastAcoustId    = "";

        // 1. Générer l'empreinte audio avec fpcalc
        Fingerprint fp = fingerprint(fichier);
        if (fp == null) return List.of();
        lastFingerprint = fp.fingerprint;
        lastDuration    = fp.duration;

        // 2. Envoyer l'empreinte à AcoustID
        String body = "client=" + Config.get().acoustidKey()
                + "&duration=" + fp.duration
                + "&fingerprint=" + URLEncoder.encode(fp.fingerprint, StandardCharsets.UTF_8)
                + "&meta=recordings";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LOOKUP_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("  Erreur AcoustID : HTTP " + response.statusCode());
            return List.of();
        }

        // 3. Extraire les IDs MusicBrainz et l'AcoustID depuis la réponse
        JsonNode root = mapper.readTree(response.body());
        List<String> mbids = parseMbids(root);
        extractAcoustId(root);
        if (mbids.isEmpty()) return List.of();

        // 4. Récupérer les tags complets depuis MusicBrainz
        List<TagInfo> results = fetchFromMusicBrainz(mbids.get(0));
        // Stocker l'AcoustID dans le TagInfo pour qu'il soit écrit dans le fichier
        if (!lastAcoustId.isBlank())
            results.forEach(t -> { if (t.acoustidId.isBlank()) t.acoustidId = lastAcoustId; });
        if (!lastFingerprint.isBlank())
            results.forEach(t -> { if (t.acoustidFingerprint.isBlank()) t.acoustidFingerprint = lastFingerprint; });
        return results;
    }

    /**
     * Soumet le fingerprint du dernier fichier identifié à AcoustID.
     * À appeler après identify() + enrichissement MB (pour avoir le recordingMbid définitif).
     * Silencieux si user token absent ou fingerprint manquant.
     */
    public void submit(String recordingMbid) {
        String userToken = Config.get().str("acoustid.user_token", "");
        if (userToken.isBlank() || lastFingerprint.isBlank() || lastDuration.isBlank()) return;

        try {
            StringBuilder body = new StringBuilder()
                .append("client=").append(URLEncoder.encode(Config.get().acoustidKey(), StandardCharsets.UTF_8))
                .append("&user=").append(URLEncoder.encode(userToken, StandardCharsets.UTF_8))
                .append("&fingerprint[]=").append(URLEncoder.encode(lastFingerprint, StandardCharsets.UTF_8))
                .append("&duration[]=").append(URLEncoder.encode(lastDuration, StandardCharsets.UTF_8));
            if (!recordingMbid.isBlank())
                body.append("&mbid[]=").append(URLEncoder.encode(recordingMbid, StandardCharsets.UTF_8));
            if (!lastAcoustId.isBlank())
                body.append("&trackid[]=").append(URLEncoder.encode(lastAcoustId, StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(SUBMIT_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", Config.get().userAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                System.out.println("  AcoustID soumis ✔");
            } else {
                System.out.println("  AcoustID submit HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (Exception e) {
            System.out.println("  AcoustID submit skip: " + e.getMessage());
        }
    }

    private Fingerprint fingerprint(File fichier) throws Exception {
        String fpcalc = FpcalcInstaller.resolve();
        if (fpcalc == null) throw new Exception("fpcalc introuvable — installez-le via Préférences → Audio");
        ProcessBuilder pb = new ProcessBuilder(fpcalc, fichier.getAbsolutePath())
                .redirectErrorStream(true);
        String output = ProcessUtils.readStringWithTimeout(pb, 60);
        if (output == null || output.isEmpty()) {
            System.out.println("  fpcalc timeout ou erreur sur " + fichier.getName());
            return null;
        }

        // fpcalc retourne : DURATION=315\nFINGERPRINT=AQADt...
        Fingerprint fp = new Fingerprint();
        for (String ligne : output.split("\n")) {
            if (ligne.startsWith("DURATION="))    fp.duration    = ligne.substring(9).trim();
            if (ligne.startsWith("FINGERPRINT=")) fp.fingerprint = ligne.substring(12).trim();
        }

        if (fp.duration == null || fp.fingerprint == null) {
            System.out.println("  fpcalc n'a pas pu lire le fichier.");
            return null;
        }
        return fp;
    }

    private List<String> parseMbids(JsonNode root) {
        List<String> mbids = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            if (result.path("score").asDouble() < 0.85) continue;
            for (JsonNode recording : result.path("recordings")) {
                String mbid = recording.path("id").asText("");
                if (!mbid.isBlank()) mbids.add(mbid);
            }
        }
        return mbids;
    }

    /** Extrait le meilleur AcoustID track ID (résultat de score le plus élevé). */
    private void extractAcoustId(JsonNode root) {
        double bestScore = 0;
        for (JsonNode result : root.path("results")) {
            double score = result.path("score").asDouble();
            if (score > bestScore) {
                bestScore  = score;
                lastAcoustId = result.path("id").asText("");
            }
        }
    }

    private List<TagInfo> fetchFromMusicBrainz(String mbid) throws Exception {
        String url = MB_URL + mbid + "?inc=artists+releases&fmt=json";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return List.of();

        JsonNode root = mapper.readTree(response.body());

        TagInfo info = new TagInfo();
        info.score         = 100;
        info.recordingMbid = mbid; // ← stocker l'ID passé en paramètre
        info.title         = root.path("title").asText("");

        // Artiste
        JsonNode credits = root.path("artist-credit");
        if (credits.isArray() && !credits.isEmpty()) {
            info.artist      = credits.get(0).path("name").asText("");
            info.albumArtist = info.artist;
            // MBID de l'artiste
            JsonNode artistNode = credits.get(0).path("artist");
            if (!artistNode.isMissingNode())
                info.artistMbid = artistNode.path("id").asText("");
        }

        // Première release officielle
        JsonNode releases = root.path("releases");
        for (JsonNode r : releases) {
            if ("Official".equals(r.path("status").asText())) {
                info.album      = r.path("title").asText("");
                info.releaseMbid = r.path("id").asText("");
                String date = r.path("date").asText("");
                info.year  = date.length() >= 4 ? date.substring(0, 4) : date;
                // Numéro de piste dans cette release
                JsonNode media = r.path("media");
                if (media.isArray()) for (JsonNode m : media) {
                    JsonNode tracks = m.path("tracks");
                    if (tracks.isArray() && !tracks.isEmpty()) {
                        info.track = tracks.get(0).path("number").asText("");
                        break;
                    }
                }
                break;
            }
        }

        return info.title.isBlank() ? List.of() : List.of(info);
    }

    private static class Fingerprint {
        String duration;
        String fingerprint;
    }
}
