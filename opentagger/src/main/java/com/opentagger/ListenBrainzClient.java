package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Statistiques d'écoute personnelles depuis ListenBrainz (MetaBrainz, même organisation que
 * MusicBrainz) — lecture seule de stats publiques, pas de clé API requise.
 *
 * Pas d'endpoint "compte pour CETTE piste précise" : seulement un classement paginé des pistes
 * les plus écoutées (/1/stats/user/{username}/recordings). fetchTopRecordingCounts() récupère ce
 * classement une fois (jusqu'à maxTracks) et construit une correspondance recordingMbid → nombre
 * d'écoutes, utilisée ensuite localement fichier par fichier — pas un appel réseau par fichier
 * comme Discogs/Last.fm/CAA, plus proche d'un fetch en masse type album-first pass.
 */
public class ListenBrainzClient {

    private static final String BASE_URL = "https://api.listenbrainz.org/1";
    // Confirmé empiriquement (doc indisponible en 429 au moment de l'implémentation) :
    // count est plafonné à 1000 par page, quelle que soit la valeur demandée au-delà.
    private static final int PAGE_SIZE = 1000;

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Garde-fou contre une boucle infinie si l'API ne signalait jamais la fin (jamais atteint en pratique). */
    private static final int HARD_CAP = 2_000_000;

    /**
     * Récupère TOUT le classement des pistes écoutées par {@code username}, sans plafond (pagination
     * automatique jusqu'à la dernière page : plus de réglage « pistes max » depuis le 2026-10-03, demande
     * utilisateur — un plafond de 1 000 laissait sans compteur tout le reste de la bibliothèque).
     *
     * @return map recordingMbid → nombre d'écoutes (les entrées sans recording_mbid sont ignorées :
     *         beaucoup de scrobbles ListenBrainz ne sont pas reliés à un enregistrement MusicBrainz)
     */
    public Map<String, Integer> fetchTopRecordingCounts(String username) throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int offset = 0;
        while (offset < HARD_CAP) {
            int want = PAGE_SIZE;
            String url = BASE_URL + "/stats/user/" + encode(username) + "/recordings"
                    + "?count=" + want + "&offset=" + offset;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 204) break; // pas encore de stats calculées pour cet utilisateur
            if (response.statusCode() != 200)
                throw new Exception("ListenBrainz HTTP " + response.statusCode() + " : " + response.body());

            JsonNode payload = mapper.readTree(response.body()).path("payload");
            JsonNode recordings = payload.path("recordings");
            if (!recordings.isArray() || recordings.isEmpty()) break;

            for (JsonNode rec : recordings) {
                String mbid = JsonText.of(rec.path("recording_mbid"), "").trim();
                int    n    = rec.path("listen_count").asInt(0);
                if (!mbid.isBlank() && n > 0) counts.put(mbid, n);
            }

            offset += recordings.size();
            // Fin du classement : le total annoncé par l'API est atteint. On ne s'arrête PAS sur « moins de
            // résultats que demandé » : si le serveur plafonne une page à moins que PAGE_SIZE, on s'arrêterait
            // trop tôt et on perdrait silencieusement la suite du classement.
            int total = payload.path("total_recording_count").asInt(-1);
            if (total >= 0 && offset >= total) break;
        }
        return counts;
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
