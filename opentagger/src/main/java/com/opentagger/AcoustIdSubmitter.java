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

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    /** {@code skipped} : volontairement NON soumis (AcoustID connaît déjà cette empreinte pour cet
     *  enregistrement) — compté à part, ce n'est ni un succès d'envoi ni une erreur. {@code acoustId} :
     *  identifiant AcoustID renvoyé quand l'import a abouti immédiatement (statut "imported"), sinon "". */
    public record SubmissionResult(File file, boolean accepted, String message, boolean skipped, String acoustId) {
        public SubmissionResult(File file, boolean accepted, String message) { this(file, accepted, message, false, ""); }
    }

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
        AcoustIdClient lookupClient = new AcoustIdClient();

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
            String rec = tags.recordingMbid == null ? "" : tags.recordingMbid.trim();

            // Règle Picard n°1 (picard/acoustid/manager.py, Submission.is_submitted) : ne soumettre QUE si
            // AcoustID ne relie pas déjà cette empreinte à cet enregistrement — sinon on n'apporte rien et on
            // charge inutilement un service gratuit. Une panne réseau ici ne doit pas bloquer la soumission.
            if (!rec.isBlank()) {
                try {
                    java.util.Set<String> known = lookupClient.knownRecordingIds(fp);
                    if (known != null && known.contains(rec.toLowerCase(java.util.Locale.ROOT))) {
                        results.add(new SubmissionResult(file, true,
                                "Déjà connu d'AcoustID pour cet enregistrement — non soumis", true, ""));
                        continue;
                    }
                } catch (Exception ignored) {}
            }

            // Règle Picard n°2 (Submission.valid_duration/args) : le MBID n'est envoyé QUE si la durée du
            // fichier colle à celle de l'enregistrement MusicBrainz (±30 s, FINGERPRINT_MAX_ALLOWED_
            // LENGTH_DIFF_MS) — sinon (autre édition/mix) on envoie les métadonnées texte à la place, jamais
            // les deux : Picard ne mélange pas mbid et texte, l'un OU l'autre.
            boolean sendMbid = !rec.isBlank() && durationPlausible(fp, tags, rec);
            List<String[]> params = new ArrayList<>();
            params.add(new String[]{"duration",    fp.duration()});
            params.add(new String[]{"fingerprint", fp.fingerprint()});
            if (sendMbid) {
                params.add(new String[]{"mbid", rec});
            } else {
                if (!tags.title.isBlank())         params.add(new String[]{"track",       tags.title});
                if (!tags.artist.isBlank())        params.add(new String[]{"artist",      tags.artist});
                if (!tags.album.isBlank())         params.add(new String[]{"album",       tags.album});
                if (!tags.albumArtist.isBlank())   params.add(new String[]{"albumartist", tags.albumArtist});
                if (!tags.year.isBlank())          params.add(new String[]{"year",        tags.year});
                if (!tags.track.isBlank())         params.add(new String[]{"trackno",     tags.track});
                if (!tags.discNo.isBlank())        params.add(new String[]{"discno",      tags.discNo});
            }
            // Champs optionnels documentés (bitrate.#, fileformat.#) — aident AcoustID à départager des
            // encodages différents d'un même enregistrement.
            String fmt = fileFormatOf(file);
            if (!fmt.isBlank()) params.add(new String[]{"fileformat", fmt});
            try {
                long kbps = org.jaudiotagger.audio.AudioFileIO.read(file).getAudioHeader().getBitRateAsNumber();
                if (kbps > 0) params.add(new String[]{"bitrate", String.valueOf(kbps)});
            } catch (Exception ignored) {}

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

            HttpResponse<String> resp = sendChunk(appKey, userToken, chunk, true);
            if (resp.body() != null && resp.statusCode() != 200 && resp.body().toLowerCase().contains("wait")) {
                resp = sendChunk(appKey, userToken, chunk, false);   // l'API refuse "wait" : renvoi sans
            }

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
                                            List<PreparedSubmission> chunk, boolean wait) throws Exception {
        StringBuilder body = new StringBuilder();
        append(body, "client", appKey);
        append(body, "clientversion", Config.get().appVersion());
        // "wait" (secondes, non listé dans la page publique de doc mais utilisé par SongKong :
        // AcoustidHelper/AcoustId.submitListOfFingerprints, AcoustIdSubmitParams.WAIT = "5") : AcoustID attend
        // jusqu'à N s que l'import se fasse, et la réponse porte alors status="imported" + l'AcoustID résultant
        // au lieu du seul "pending".
        if (wait) append(body, "wait", "5");
        append(body, "user",   userToken);
        for (int i = 0; i < chunk.size(); i++) {
            for (String[] kv : chunk.get(i).params()) {
                append(body, kv[0] + "." + i, kv[1]);
            }
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SUBMIT_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Content-Encoding", "gzip")   // corps compressé, préféré par AcoustID (voir AcoustIdClient.gzip)
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.binaryDownload())
                .POST(HttpRequest.BodyPublishers.ofByteArray(AcoustIdClient.gzip(body.toString())))
                .build();
        AcoustIdClient.throttle();   // même plafond de 3 requêtes/s que les lookups (créneau partagé)
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

        String status = JsonText.of(root.path("status"), "");
        if (!"ok".equals(status)) {
            String msg = JsonText.of(root.path("error").path("message"), "Réponse inattendue (HTTP " + resp.statusCode() + ")");
            for (PreparedSubmission ps : chunk) results.add(new SubmissionResult(ps.file(), false, msg));
            return;
        }

        // Réponse documentée : {"status":"ok","submissions":[{"index":0,"id":...,"status":"pending"}, ...]}
        JsonNode submissions = root.path("submissions");
        for (int i = 0; i < chunk.size(); i++) {
            File file = chunk.get(i).file();
            JsonNode entry = findByIndex(submissions, i);
            if (entry == null) {
                results.add(new SubmissionResult(file, false, "Pas de résultat retourné pour cet élément"));
                continue;
            }
            String subStatus = JsonText.of(entry.path("status"), "");
            String subId     = JsonText.of(entry.path("id"), "");
            if ("error".equals(subStatus)) {
                String msg = JsonText.of(entry.path("error").path("message"), "Erreur inconnue");
                results.add(new SubmissionResult(file, false, msg));
            } else if ("imported".equals(subStatus)) {
                // Import immédiat : l'AcoustID de cette empreinte est connu (SongKong l'écrit dans le tag
                // "Acoustid Id" du fichier s'il était vide — ici l'appelant le range dans le TagInfo).
                String acoustId = JsonText.of(entry.path("result").path("id"), "");
                results.add(new SubmissionResult(file, true,
                        "Importé" + (acoustId.isBlank() ? "" : " (AcoustID " + acoustId + ")"), false, acoustId));
            } else {
                // "pending" = accepté, traitement asynchrone côté AcoustID (import pas encore terminé)
                results.add(new SubmissionResult(file, true,
                        "En attente — AcoustID traite la soumission en arrière-plan (id=" + subId + ")"));
            }
        }
    }

    private JsonNode findByIndex(JsonNode submissions, int index) {
        if (!submissions.isArray()) return null;
        for (JsonNode s : submissions) if (s.path("index").asInt(-1) == index) return s;
        // repli si "index" absent : suppose le même ordre que l'envoi
        return index < submissions.size() ? submissions.get(index) : null;
    }

    /** Durée du fichier ≈ durée MusicBrainz de l'enregistrement (±30 s) ? Durée MB inconnue → oui (Picard :
     *  "metadata is None → valide"). Sinon lue depuis le TagInfo, à défaut via un lookup MusicBrainz. */
    private static boolean durationPlausible(Fingerprinter.Result fp, TagInfo tags, String recordingMbid) {
        int mbSec = tags.mbDurationSec;
        if (mbSec <= 0) {
            try {
                TagInfo mb = new MusicBrainzClient().lookupRecording(recordingMbid);
                if (mb != null) mbSec = mb.mbDurationSec;
            } catch (Exception ignored) {}
        }
        if (mbSec <= 0) return true;
        try { return Math.abs(Integer.parseInt(fp.duration()) - mbSec) <= 30; }
        catch (NumberFormatException e) { return true; }
    }

    /** "MP3", "M4A", "FLAC"… d'après l'extension (champ fileformat.# de l'API). */
    private static String fileFormatOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
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
