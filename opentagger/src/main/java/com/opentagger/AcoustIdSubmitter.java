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
import java.util.function.Consumer;

/**
 * Soumet des empreintes AcoustID à la base communautaire AcoustID.
 *
 * Endpoint : POST https://api.acoustid.org/v2/submit — format batch documenté par AcoustID :
 * paramètres indexés par point ("fingerprint.0", "duration.0", "mbid.0", "fingerprint.1"…),
 * PAS de crochets. Une seule requête HTTP peut regrouper plusieurs fichiers (comme le fait
 * Picard) — un ancien format ici utilisait "fingerprint[0]" avec crochets, rejeté par l'API
 * réelle ("missing required parameter fingerprint", HTTP 400), confirmé en conditions réelles.
 *
 * AcoustID traite les soumissions de façon asynchrone : la réponse ne confirme que l'acceptation
 * ("pending"), pas l'import définitif dans la base.
 *
 * Prérequis :
 *  - fpcalc disponible sur le PATH (même binaire que pour l'identification)
 *  - Clé d'application + token utilisateur AcoustID configurés dans settings.properties
 */
public class AcoustIdSubmitter {

    private static final String SUBMIT_URL = "https://api.acoustid.org/v2/submit";

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record SubmissionResult(File file, boolean accepted, String message) {}

    /** Soumet un seul fichier — raccourci pratique, passe par submitBatch avec 1 élément. */
    public SubmissionResult submit(File file, TagInfo tags) throws Exception {
        return submitBatch(List.of(file), List.of(tags), null).get(0);
    }

    /**
     * Soumet plusieurs empreintes en une seule requête HTTP (format batch AcoustID : paramètres
     * indexés par point). Le fingerprinting local (fpcalc) reste par fichier, mais un seul appel
     * réseau regroupe tous les fichiers valides — plus rapide et plus proche du fonctionnement
     * réel de l'API qu'une requête par fichier.
     *
     * @param onProgress callback optionnel appelé pendant le fingerprinting (nom du fichier en cours)
     */
    public List<SubmissionResult> submitBatch(List<File> files, List<TagInfo> tagsList,
                                               Consumer<String> onProgress) throws Exception {
        if (files.size() != tagsList.size())
            throw new IllegalArgumentException("files et tagsList doivent avoir la même taille");

        String appKey    = Config.get().acoustidKey();
        String userToken = Config.get().str("acoustid.user_token", "").trim();
        if (appKey.isBlank())    throw new Exception("Clé AcoustID non configurée (Préférences → APIs → AcoustID API Key)");
        if (userToken.isBlank()) throw new Exception(
            "Token utilisateur AcoustID manquant.\n" +
            "Obtenez-le sur https://acoustid.org/api-key puis ajoutez :\n" +
            "acoustid.user_token = VOTRE_TOKEN\n" +
            "dans ~/.opentagger/settings.properties");

        List<SubmissionResult> results = new ArrayList<>();
        List<File> included = new ArrayList<>(); // fichiers réellement inclus dans la requête (fingerprint OK)

        StringBuilder body = new StringBuilder();
        append(body, "client", appKey);
        append(body, "user",   userToken);

        for (int i = 0; i < files.size(); i++) {
            File file = files.get(i);
            TagInfo tags = tagsList.get(i);
            if (onProgress != null) onProgress.accept("Empreinte… " + file.getName());
            Fingerprinter.Result fp;
            try {
                fp = Fingerprinter.compute(file);
            } catch (Exception ex) {
                results.add(new SubmissionResult(file, false, ex.getMessage()));
                continue;
            }
            int idx = included.size();
            append(body, "duration." + idx,    fp.duration());
            append(body, "fingerprint." + idx, fp.fingerprint());
            if (!tags.recordingMbid.isBlank()) append(body, "mbid."        + idx, tags.recordingMbid);
            if (!tags.title.isBlank())         append(body, "track."       + idx, tags.title);
            if (!tags.artist.isBlank())        append(body, "artist."      + idx, tags.artist);
            if (!tags.album.isBlank())         append(body, "album."       + idx, tags.album);
            if (!tags.albumArtist.isBlank())   append(body, "albumartist." + idx, tags.albumArtist);
            if (!tags.year.isBlank())          append(body, "year."        + idx, tags.year);
            if (!tags.track.isBlank())         append(body, "trackno."     + idx, tags.track);
            if (!tags.discNo.isBlank())        append(body, "discno."      + idx, tags.discNo);
            included.add(file);
        }

        if (included.isEmpty()) return results; // tout a échoué au fingerprinting local

        if (onProgress != null) onProgress.accept("Envoi de " + included.size() + " empreinte(s) à AcoustID…");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUBMIT_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());

        // DIAGNOSTIC TEMPORAIRE — à retirer une fois le format "submissions"/"pending" confirmé
        // par un essai réel réussi (on n'a pour l'instant confirmé que le format des ERREURS).
        String diag = " [brut: " + resp.body() + "]";

        String status = root.path("status").asText("");
        if (!"ok".equals(status)) {
            String msg = root.path("error").path("message").asText("Réponse inattendue (HTTP " + resp.statusCode() + ")");
            for (File f : included) results.add(new SubmissionResult(f, false, msg + diag));
            return results;
        }

        // Réponse documentée : {"status":"ok","submissions":[{"index":0,"id":...,"status":"pending"}, ...]}
        JsonNode submissions = root.path("submissions");
        for (int i = 0; i < included.size(); i++) {
            JsonNode entry = findByIndex(submissions, i);
            if (entry == null) {
                results.add(new SubmissionResult(included.get(i), false, "Pas de résultat retourné pour cet élément" + diag));
                continue;
            }
            String subStatus = entry.path("status").asText("");
            String subId     = entry.path("id").asText("");
            if ("error".equals(subStatus)) {
                String msg = entry.path("error").path("message").asText("Erreur inconnue");
                results.add(new SubmissionResult(included.get(i), false, msg + diag));
            } else {
                // "pending" = accepté, traitement asynchrone côté AcoustID (pas de confirmation immédiate d'import)
                results.add(new SubmissionResult(included.get(i), true,
                        "Accepté (id=" + subId + ", statut=" + subStatus + ")" + diag));
            }
        }
        return results;
    }

    private JsonNode findByIndex(JsonNode submissions, int index) {
        if (!submissions.isArray()) return null;
        for (JsonNode s : submissions) if (s.path("index").asInt(-1) == index) return s;
        // repli si "index" absent : suppose le même ordre que l'envoi
        return index < submissions.size() ? submissions.get(index) : null;
    }

    /** Vérifie que fpcalc est disponible. */
    public static boolean isAvailable() { return FpcalcInstaller.isAvailable(); }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void append(StringBuilder sb, String key, String value) {
        if (!sb.isEmpty()) sb.append('&');
        sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
