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

        // 1. Générer l'empreinte audio avec fpcalc (JSON, max 120s comme Picard)
        Fingerprint fp = fingerprint(fichier);
        if (fp == null) return List.of();
        lastFingerprint = fp.fingerprint;
        lastDuration    = fp.duration;

        // 2. Envoyer l'empreinte à AcoustID avec meta complet (comme Picard)
        String body = "client=" + Config.get().acoustidKey()
                + "&duration=" + fp.duration
                + "&fingerprint=" + URLEncoder.encode(fp.fingerprint, StandardCharsets.UTF_8)
                + "&meta=recordings+releaseids+releasegroups+sources+compress";

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
        if (!"ok".equals(root.path("status").asText())) {
            String errMsg = root.path("error").path("message").asText("statut inconnu");
            System.out.println("  AcoustID erreur: " + errMsg);
            return List.of();
        }

        List<String> mbids = parseMbids(root);
        extractAcoustId(root);
        if (mbids.isEmpty()) return List.of();

        // 4. Récupérer les tags complets depuis MusicBrainz pour les premiers MBIDs
        List<TagInfo> results = fetchBestFromMusicBrainz(mbids);

        if (!lastAcoustId.isBlank())
            results.forEach(t -> { if (t.acoustidId.isBlank()) t.acoustidId = lastAcoustId; });
        if (!lastFingerprint.isBlank())
            results.forEach(t -> { if (t.acoustidFingerprint.isBlank()) t.acoustidFingerprint = lastFingerprint; });
        return results;
    }

    /**
     * Soumet le fingerprint du dernier fichier identifié à AcoustID.
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

        // Comme Picard : -json pour parsing fiable, -length 120 pour analyser seulement 2 min (plus rapide)
        ProcessBuilder pb = new ProcessBuilder(fpcalc, "-json", "-length", "120", fichier.getAbsolutePath())
                .redirectErrorStream(false);
        String output = ProcessUtils.readStringWithTimeout(pb, 60);
        if (output == null || output.isEmpty()) {
            System.out.println("  fpcalc timeout ou erreur sur " + fichier.getName());
            return null;
        }

        // fpcalc -json retourne {"duration":315.2,"fingerprint":"AQADt..."}
        try {
            JsonNode json = mapper.readTree(output);
            String fingerprint = json.path("fingerprint").asText("");
            // fpcalc -json retourne une durée flottante — AcoustID veut un entier
            int duration = (int) json.path("duration").asDouble(0);
            if (fingerprint.isBlank() || duration == 0) {
                System.out.println("  fpcalc n'a pas pu lire le fichier.");
                return null;
            }
            Fingerprint fp = new Fingerprint();
            fp.fingerprint = fingerprint;
            fp.duration    = String.valueOf(duration);
            return fp;
        } catch (Exception e) {
            System.out.println("  fpcalc sortie invalide: " + e.getMessage());
            return null;
        }
    }

    private List<String> parseMbids(JsonNode root) {
        List<String> mbids = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            // Seuil abaissé à 0.5 comme Picard — AcoustID peut donner 0.6-0.8 sur fichiers bruités
            if (result.path("score").asDouble() < 0.5) continue;
            for (JsonNode recording : result.path("recordings")) {
                String mbid = recording.path("id").asText("");
                if (!mbid.isBlank() && !mbids.contains(mbid)) mbids.add(mbid);
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
                bestScore    = score;
                lastAcoustId = result.path("id").asText("");
            }
        }
    }

    /**
     * Essaie jusqu'à 3 MBIDs et retourne le meilleur résultat.
     * Corrige le bug original qui n'essayait que le premier MBID et utilisait
     * une copie incomplète du lookup (sans artist-credits, release-groups, isrcs).
     */
    private List<TagInfo> fetchBestFromMusicBrainz(List<String> mbids) {
        List<TagInfo> best = List.of();
        int tries = Math.min(mbids.size(), 3);
        for (int i = 0; i < tries; i++) {
            try {
                // Utilise mbClient.lookupRecording qui inclut TOUS les inc= nécessaires
                TagInfo info = mbClient.lookupRecording(mbids.get(i));
                if (info != null && !info.title.isBlank()) {
                    best = List.of(info);
                    if (info.score >= 90) break; // bon résultat, on s'arrête
                }
            } catch (Exception e) {
                System.out.println("  MB lookup " + mbids.get(i) + " : " + e.getMessage());
            }
        }
        return best;
    }

    private static class Fingerprint {
        String duration;
        String fingerprint;
    }
}
