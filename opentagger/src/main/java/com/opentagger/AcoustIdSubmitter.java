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

    // Limite documentée par l'API AcoustID (~1 Mo par requête) — mêmes constantes que Picard
    // (picard/acoustid/manager.py: MAX_PAYLOAD, BATCH_SIZE_REDUCTION_FACTOR). Avant ce fix,
    // submitBatch envoyait TOUS les fichiers fournis dans une seule requête HTTP sans jamais
    // vérifier la taille — sur une grosse sélection (bibliothèque de plusieurs centaines de
    // milliers de fichiers), ça dépasse cette limite et échoue entièrement au lieu de découper
    // automatiquement en plusieurs requêtes, comme le fait Picard.
    private static final int    MAX_PAYLOAD                 = 1_000_000;
    private static final double BATCH_SIZE_REDUCTION_FACTOR  = 0.7;

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public record SubmissionResult(File file, boolean accepted, String message) {}

    /** Une soumission déjà préparée (empreinte calculée) mais pas encore envoyée. */
    private record PreparedSubmission(File file, List<String[]> params, int estimatedSize) {}

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

        // ── 1. Fingerprinting local (par fichier) — construit les paramètres de chaque
        // soumission SANS l'index (attribué plus tard, par requête HTTP, pas globalement,
        // puisqu'un même fichier peut se retrouver dans n'importe quel morceau après découpage).
        List<PreparedSubmission> prepared = new ArrayList<>();
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
            List<String[]> params = new ArrayList<>();
            params.add(new String[]{"duration",    fp.duration()});
            params.add(new String[]{"fingerprint", fp.fingerprint()});
            if (!tags.recordingMbid.isBlank()) params.add(new String[]{"mbid",        tags.recordingMbid});
            if (!tags.title.isBlank())         params.add(new String[]{"track",       tags.title});
            if (!tags.artist.isBlank())        params.add(new String[]{"artist",      tags.artist});
            if (!tags.album.isBlank())         params.add(new String[]{"album",       tags.album});
            if (!tags.albumArtist.isBlank())   params.add(new String[]{"albumartist", tags.albumArtist});
            if (!tags.year.isBlank())          params.add(new String[]{"year",        tags.year});
            if (!tags.track.isBlank())         params.add(new String[]{"trackno",     tags.track});
            if (!tags.discNo.isBlank())        params.add(new String[]{"discno",      tags.discNo});

            // Approximation de la taille du payload — même formule que Picard
            // (Submission.__len__) : somme(clé+valeur+2) puis marge de 3% pour l'urlencode.
            int size = 0;
            for (String[] kv : params) size += kv[0].length() + kv[1].length() + 2;
            prepared.add(new PreparedSubmission(file, params, (int) (size * 1.03)));
        }

        if (prepared.isEmpty()) return results; // tout a échoué au fingerprinting local

        // ── 2. Envoi en plusieurs requêtes si nécessaire, chacune sous MAX_PAYLOAD — découpage
        // adaptatif façon Picard : sur un HTTP 413 (payload trop gros), réduire la taille de lot
        // de 30% et réessayer CE morceau, sans perdre ce qui a déjà été envoyé avec succès avant.
        int batchCap = MAX_PAYLOAD;
        int idx = 0;
        while (idx < prepared.size()) {
            List<PreparedSubmission> chunk = new ArrayList<>();
            int chunkSize = 0;
            int j = idx;
            while (j < prepared.size()) {
                PreparedSubmission ps = prepared.get(j);
                if (!chunk.isEmpty() && chunkSize + ps.estimatedSize() > batchCap) break;
                chunk.add(ps);
                chunkSize += ps.estimatedSize();
                j++;
            }

            if (onProgress != null)
                onProgress.accept("Envoi de " + chunk.size() + " empreinte(s) à AcoustID… ("
                        + (idx + chunk.size()) + "/" + prepared.size() + ")");

            HttpResponse<String> resp = sendChunk(appKey, userToken, chunk);

            if (resp.statusCode() == 413 && chunk.size() > 1) {
                // Payload trop gros : réduire la taille de lot et réessayer CE morceau (pas
                // d'avancement de idx) — même logique que Picard (BATCH_SIZE_REDUCTION_FACTOR).
                batchCap = Math.max(1, (int) (batchCap * BATCH_SIZE_REDUCTION_FACTOR));
                continue;
            }

            addChunkResults(results, chunk, resp);
            idx += chunk.size();
        }
        return results;
    }

    private HttpResponse<String> sendChunk(String appKey, String userToken,
                                            List<PreparedSubmission> chunk) throws Exception {
        StringBuilder body = new StringBuilder();
        append(body, "client", appKey);
        append(body, "user",   userToken);
        for (int i = 0; i < chunk.size(); i++) {
            for (String[] kv : chunk.get(i).params()) {
                append(body, kv[0] + "." + i, kv[1]);
            }
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUBMIT_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private void addChunkResults(List<SubmissionResult> results, List<PreparedSubmission> chunk,
                                  HttpResponse<String> resp) throws Exception {
        JsonNode root;
        try {
            root = mapper.readTree(resp.body());
        } catch (Exception e) {
            for (PreparedSubmission ps : chunk)
                results.add(new SubmissionResult(ps.file(), false, "Réponse invalide (HTTP " + resp.statusCode() + ")"));
            return;
        }

        // DIAGNOSTIC TEMPORAIRE — à retirer une fois le format "submissions"/"pending" confirmé
        // par un essai réel réussi (on n'a pour l'instant confirmé que le format des ERREURS).
        String diag = " [brut: " + resp.body() + "]";

        String status = root.path("status").asText("");
        if (!"ok".equals(status)) {
            String msg = root.path("error").path("message").asText("Réponse inattendue (HTTP " + resp.statusCode() + ")");
            for (PreparedSubmission ps : chunk) results.add(new SubmissionResult(ps.file(), false, msg + diag));
            return;
        }

        // Réponse documentée : {"status":"ok","submissions":[{"index":0,"id":...,"status":"pending"}, ...]}
        JsonNode submissions = root.path("submissions");
        for (int i = 0; i < chunk.size(); i++) {
            File file = chunk.get(i).file();
            JsonNode entry = findByIndex(submissions, i);
            if (entry == null) {
                results.add(new SubmissionResult(file, false, "Pas de résultat retourné pour cet élément" + diag));
                continue;
            }
            String subStatus = entry.path("status").asText("");
            String subId     = entry.path("id").asText("");
            if ("error".equals(subStatus)) {
                String msg = entry.path("error").path("message").asText("Erreur inconnue");
                results.add(new SubmissionResult(file, false, msg + diag));
            } else {
                // "pending" = accepté, traitement asynchrone côté AcoustID (pas de confirmation immédiate d'import)
                results.add(new SubmissionResult(file, true,
                        "Accepté (id=" + subId + ", statut=" + subStatus + ")" + diag));
            }
        }
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
