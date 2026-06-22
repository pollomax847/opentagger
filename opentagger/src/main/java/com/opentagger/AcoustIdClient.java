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

    private static final String API_URL  = "https://api.acoustid.org/v2/lookup";
    private static final String MB_URL   = "https://musicbrainz.org/ws/2/recording/";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MusicBrainzClient mbClient = new MusicBrainzClient();

    public List<TagInfo> identify(File fichier) throws Exception {
        // 1. Générer l'empreinte audio avec fpcalc
        Fingerprint fp = fingerprint(fichier);
        if (fp == null) return List.of();

        // 2. Envoyer l'empreinte à AcoustID
        String body = "client=" + Config.get().acoustidKey()
                + "&duration=" + fp.duration
                + "&fingerprint=" + URLEncoder.encode(fp.fingerprint, StandardCharsets.UTF_8)
                + "&meta=recordings";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.out.println("  Erreur AcoustID : HTTP " + response.statusCode());
            return List.of();
        }

        // 3. Extraire les IDs MusicBrainz depuis la réponse AcoustID
        List<String> mbids = parseMbids(mapper.readTree(response.body()));
        if (mbids.isEmpty()) return List.of();

        // 4. Récupérer les tags complets depuis MusicBrainz
        return fetchFromMusicBrainz(mbids.get(0));
    }

    private Fingerprint fingerprint(File fichier) throws Exception {
        String fpcalc = FpcalcInstaller.resolve();
        if (fpcalc == null) throw new Exception("fpcalc introuvable — installez-le via Préférences → Audio");
        Process process = new ProcessBuilder(fpcalc, fichier.getAbsolutePath())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes()).trim();
        process.waitFor();

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
        JsonNode results = root.path("results");

        for (JsonNode result : results) {
            double score = result.path("score").asDouble();
            if (score < 0.85) continue; // ignorer les résultats peu fiables

            for (JsonNode recording : result.path("recordings")) {
                String mbid = recording.path("id").asText("");
                if (!mbid.isBlank()) mbids.add(mbid);
            }
        }
        return mbids;
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
