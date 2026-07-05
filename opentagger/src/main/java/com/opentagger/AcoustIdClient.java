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

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MusicBrainzClient mbClient = new MusicBrainzClient();

    // Fingerprint du dernier fichier identifié — réutilisé pour remplir TagInfo.acoustidFingerprint
    private String lastFingerprint = "";
    private String lastAcoustId    = "";

    public List<TagInfo> identify(File fichier) throws Exception {
        lastFingerprint = "";
        lastAcoustId    = "";

        // 1. Générer l'empreinte audio avec fpcalc (JSON, max 120s comme Picard)
        Fingerprinter.Result fp;
        try {
            fp = Fingerprinter.compute(fichier);
        } catch (Exception e) {
            System.out.println("  " + e.getMessage());
            return List.of();
        }
        lastFingerprint = fp.fingerprint();

        // 2. Envoyer l'empreinte à AcoustID avec meta complet (comme Picard)
        String body = "client=" + Config.get().acoustidKey()
                + "&duration=" + fp.duration()
                + "&fingerprint=" + URLEncoder.encode(fp.fingerprint(), StandardCharsets.UTF_8)
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
                // Rate-limit MB : centralisé dans MusicBrainzClient.getWithRetry(), plus besoin
                // de pause manuelle ici même si ce client a son propre MusicBrainzClient interne.
                TagInfo info = mbClient.lookupRecording(mbids.get(i));
                if (info != null && !info.title.isBlank()) {
                    best = List.of(info);
                    if (info.score >= 90) break; // bon résultat, on s'arrête
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.out.println("  MB lookup " + mbids.get(i) + " : " + e.getMessage());
            }
        }
        return best;
    }
}
