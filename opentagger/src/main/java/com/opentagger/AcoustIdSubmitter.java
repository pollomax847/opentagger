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

/**
 * Soumet une empreinte AcoustID à la base communautaire AcoustID.
 *
 * Endpoint : POST https://api.acoustid.org/v2/submit
 *
 * Prérequis :
 *  - fpcalc disponible sur le PATH (même binaire que pour l'identification)
 *  - Clé d'application AcoustID configurée dans settings.properties
 *
 * En cas de succès, l'empreinte est associée au MBID Recording et rendue
 * disponible pour tous les utilisateurs d'AcoustID — équivalent du bouton
 * "Submit Fingerprint" de Jaikoz.
 */
public class AcoustIdSubmitter {

    private static final String SUBMIT_URL = "https://api.acoustid.org/v2/submit";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Génère l'empreinte du fichier avec fpcalc, puis l'envoie à AcoustID.
     *
     * @param file  fichier audio à soumettre
     * @param tags  tags connus du morceau (au moins recordingMbid idéalement)
     * @return message de succès avec le nouvel AcoustID ou confirmation de fusion
     * @throws Exception si fpcalc échoue, si la clé est manquante ou si AcoustID répond une erreur
     */
    public String submit(File file, TagInfo tags) throws Exception {
        String appKey   = Config.get().acoustidKey();
        String userToken = Config.get().str("acoustid.user_token", "").trim();
        if (appKey.isBlank())    throw new Exception("Clé AcoustID non configurée (Préférences → APIs → AcoustID API Key)");
        if (userToken.isBlank()) throw new Exception(
            "Token utilisateur AcoustID manquant.\n" +
            "Obtenez-le sur https://acoustid.org/api-key puis ajoutez :\n" +
            "acoustid.user_token = VOTRE_TOKEN\n" +
            "dans ~/.opentagger/settings.properties");

        // 1. Générer l'empreinte avec fpcalc
        FpcalcResult fp = fingerprint(file);

        // 2. Construire le corps de la requête (format indexed-array d'AcoustID)
        StringBuilder body = new StringBuilder();
        append(body, "client",         appKey);
        append(body, "user",           userToken);
        append(body, "fingerprint[0]", fp.fingerprint);
        append(body, "duration[0]",    fp.duration);
        if (!tags.recordingMbid.isBlank())
            append(body, "mbid[0]",    tags.recordingMbid);
        if (!tags.title.isBlank())
            append(body, "track[0]",   tags.title);
        if (!tags.artist.isBlank())
            append(body, "artist[0]",  tags.artist);
        if (!tags.album.isBlank())
            append(body, "album[0]",   tags.album);
        if (!tags.albumArtist.isBlank())
            append(body, "albumartist[0]", tags.albumArtist);
        if (!tags.year.isBlank())
            append(body, "year[0]",    tags.year);
        if (!tags.track.isBlank())
            append(body, "trackno[0]", tags.track);
        if (!tags.discNo.isBlank())
            append(body, "discno[0]",  tags.discNo);

        // 3. Envoyer la requête
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUBMIT_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());

        String status = root.path("status").asText("");
        if (!"ok".equals(status)) {
            String msg = root.path("error").path("message").asText("Réponse inattendue");
            throw new Exception("AcoustID : " + msg + " (HTTP " + resp.statusCode() + ")");
        }

        // Extraire l'AcoustID attribué — AcoustID retourne un tableau dans "result"
        JsonNode resultNode = root.path("result");
        JsonNode result = resultNode.isArray() && !resultNode.isEmpty()
                        ? resultNode.get(0)
                        : resultNode;
        String newId = result.path("id").asText("");
        int created  = result.path("created").asInt(0);
        int merged   = result.path("merged").asInt(0);

        if (!newId.isBlank()) {
            return "Soumis — AcoustID : " + newId;
        } else if (created > 0) {
            return "Empreinte créée avec succès";
        } else if (merged > 0) {
            return "Empreinte fusionnée avec une entrée existante";
        }
        return "Soumission acceptée";
    }

    /** Vérifie que fpcalc est disponible. */
    public static boolean isAvailable() { return FpcalcInstaller.isAvailable(); }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private FpcalcResult fingerprint(File file) throws Exception {
        String fpcalc = FpcalcInstaller.resolve();
        if (fpcalc == null) throw new Exception("fpcalc introuvable");

        // Utilise -json et -length 120 comme AcoustIdClient, avec timeout pour éviter les blocages infinis
        ProcessBuilder pb = new ProcessBuilder(fpcalc, "-json", "-length", "120", file.getAbsolutePath())
                .redirectErrorStream(false);
        String output = ProcessUtils.readStringWithTimeout(pb, 60);
        if (output == null || output.isBlank())
            throw new Exception("fpcalc timeout ou sortie vide pour " + file.getName());

        try {
            com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(output);
            String fingerprint = json.path("fingerprint").asText("");
            int duration = (int) json.path("duration").asDouble(0);
            if (fingerprint.isBlank() || duration == 0)
                throw new Exception("fpcalc : fingerprint ou durée manquant");
            return new FpcalcResult(String.valueOf(duration), fingerprint);
        } catch (Exception e) {
            throw new Exception("fpcalc : sortie invalide — " + e.getMessage());
        }
    }

    private void append(StringBuilder sb, String key, String value) {
        if (!sb.isEmpty()) sb.append('&');
        sb.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private record FpcalcResult(String duration, String fingerprint) {}
}
