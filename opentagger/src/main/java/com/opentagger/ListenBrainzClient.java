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

    /**
     * Récupère le classement des pistes les plus écoutées par {@code username}, jusqu'à
     * {@code maxTracks} pistes (pagination automatique). Les pistes au-delà de ce plafond ne
     * sont simplement pas incluses — pas une erreur, juste une limite pratique pour éviter de
     * paginer indéfiniment pour un très gros utilisateur.
     *
     * @return map recordingMbid → nombre d'écoutes (les entrées sans recording_mbid sont ignorées :
     *         beaucoup de scrobbles ListenBrainz ne sont pas reliés à un enregistrement MusicBrainz)
     */
    public Map<String, Integer> fetchTopRecordingCounts(String username, int maxTracks) throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int offset = 0;
        while (counts.size() < maxTracks) {
            int want = Math.min(PAGE_SIZE, maxTracks - offset);
            if (want <= 0) break;
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

            JsonNode recordings = mapper.readTree(response.body()).path("payload").path("recordings");
            if (!recordings.isArray() || recordings.isEmpty()) break;

            for (JsonNode rec : recordings) {
                String mbid = rec.path("recording_mbid").asText("").trim();
                int    n    = rec.path("listen_count").asInt(0);
                if (!mbid.isBlank() && n > 0) counts.put(mbid, n);
            }

            if (recordings.size() < want) break; // dernière page (moins de résultats que demandé)
            offset += recordings.size();
        }
        return counts;
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
