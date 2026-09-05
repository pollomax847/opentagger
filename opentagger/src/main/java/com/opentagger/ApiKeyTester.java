package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Vérifie qu'une clé/qu'un identifiant configuré est réellement accepté par le service concerné —
 * un appel réseau minimal par service, jamais celui utilisé pour l'identification réelle (pas de
 * fichier audio nécessaire). Boutons "Tester" dans les Préférences (onglets APIs et MusicBrainz).
 *
 * Convention commune : une clé/un identifiant est jugé valide si le service répond BIEN au-delà
 * d'un simple refus d'authentification — même une erreur "paramètre manquant" (attendu, notre appel
 * minimal omet volontairement le vrai travail) prouve que la clé elle-même a passé l'authentification.
 * Seule une erreur explicitement liée à la clé (invalid api key/token, 401...) est un échec.
 */
public final class ApiKeyTester {

    private ApiKeyTester() {}

    public record Result(boolean ok, String message) {}

    private static final HttpClient   http   = HttpTimeouts.client();
    private static final ObjectMapper mapper = new ObjectMapper();

    // Empreinte AcoustID syntaxiquement valide mais bidon (jamais soumise, juste pour un lookup
    // dont la seule vraie question est "la clé est-elle acceptée ?").
    private static final String DUMMY_FINGERPRINT = "AQAAA0mUaEkSRWES";
    // MBID Queen — artiste connu et stable, utilisé uniquement pour vérifier qu'une clé FanArt.tv
    // ou un serveur MusicBrainz répond correctement, jamais pour une vraie identification.
    private static final String WELL_KNOWN_ARTIST_MBID = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3";

    public static Result testAcoustId(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return new Result(false, I18n.t("Clé vide"));
        try {
            String url = "https://api.acoustid.org/v2/lookup?client=" + enc(apiKey)
                    + "&meta=recordings&fingerprint=" + DUMMY_FINGERPRINT + "&duration=1";
            JsonNode root = getJson(url, null);
            if (root == null) return new Result(false, I18n.t("Pas de réponse du serveur"));
            String status = root.path("status").asText("");
            if ("error".equals(status)) {
                String msg = root.path("error").path("message").asText("");
                if (msg.toLowerCase().contains("invalid api key") || msg.toLowerCase().contains("insufficient"))
                    return new Result(false, msg);
                return new Result(true, I18n.t("Clé acceptée"));
            }
            return new Result(true, I18n.t("Clé valide"));
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testDiscogs(String key, String secret) {
        if (key == null || key.isBlank() || secret == null || secret.isBlank())
            return new Result(false, I18n.t("Clé ou secret vide"));
        try {
            String url = "https://api.discogs.com/database/search?q=test&type=release&per_page=1";
            String auth = "Discogs key=" + key + ", secret=" + secret;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .header("Authorization", auth)
                    .timeout(HttpTimeouts.apiCall())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) return new Result(false, I18n.t("Identifiants Discogs refusés (401)"));
            if (resp.statusCode() == 200) return new Result(true, I18n.t("Identifiants valides"));
            return new Result(false, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testLastFm(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return new Result(false, I18n.t("Clé vide"));
        try {
            String url = "https://ws.audioscrobbler.com/2.0/?method=chart.gettopartists&api_key="
                    + enc(apiKey) + "&format=json&limit=1";
            JsonNode root = getJson(url, null);
            if (root == null) return new Result(false, I18n.t("Pas de réponse du serveur"));
            if (root.has("error")) {
                int code = root.path("error").asInt(-1);
                String msg = root.path("message").asText("");
                if (code == 10) return new Result(false, msg.isBlank() ? I18n.t("Clé API invalide") : msg);
                return new Result(true, I18n.t("Clé acceptée"));
            }
            return new Result(true, I18n.t("Clé valide"));
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testFanArt(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return new Result(false, I18n.t("Clé vide"));
        try {
            String url = "https://webservice.fanart.tv/v3/music/" + WELL_KNOWN_ARTIST_MBID
                    + "?api_key=" + enc(apiKey);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new Result(true, I18n.t("Clé valide"));
            JsonNode root = mapper.readTree(resp.body());
            String msg = root.path("error message").asText(root.path("status").asText(""));
            if (resp.statusCode() == 401 || resp.statusCode() == 403 || msg.toLowerCase().contains("api key"))
                return new Result(false, msg.isBlank() ? ("HTTP " + resp.statusCode()) : msg);
            // 404 sur l'artiste lui-même (improbable pour Queen) mais clé acceptée
            return new Result(true, I18n.t("Clé acceptée"));
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testAudD(String apiToken) {
        if (apiToken == null || apiToken.isBlank()) return new Result(false, I18n.t("Jeton vide"));
        try {
            String url = "https://api.audd.io/?api_token=" + enc(apiToken);
            JsonNode root = getJson(url, null);
            if (root == null) return new Result(false, I18n.t("Pas de réponse du serveur"));
            String status = root.path("status").asText("");
            if ("error".equals(status)) {
                int code = root.path("error").path("error_code").asInt(-1);
                String msg = root.path("error").path("error_message").asText("");
                if (code == 900 || code == 901 || msg.toLowerCase().contains("token"))
                    return new Result(false, msg.isBlank() ? I18n.t("Jeton invalide") : msg);
                return new Result(true, I18n.t("Jeton accepté"));
            }
            return new Result(true, I18n.t("Jeton valide"));
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testHeadphones(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) return new Result(false, I18n.t("URL vide"));
        if (apiKey == null || apiKey.isBlank()) return new Result(false, I18n.t("Clé API vide"));
        try {
            String base = baseUrl.trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String url = base + "/api?apikey=" + enc(apiKey) + "&cmd=getVersion";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return new Result(false, "HTTP " + resp.statusCode());
            JsonNode root = mapper.readTree(resp.body());
            if (root.has("current_version")) return new Result(true, I18n.t("Connecté (v%s)", root.path("current_version").asText("")));
            return new Result(false, I18n.t("Clé API invalide"));
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testLastFmUsername(String username) {
        if (username == null || username.isBlank()) return new Result(false, I18n.t("Nom d'utilisateur vide"));
        try {
            String url = "https://ws.audioscrobbler.com/2.0/?method=user.getinfo&user=" + enc(username)
                    + "&api_key=" + enc(Config.get().lastfmKey()) + "&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new Result(true, I18n.t("Utilisateur trouvé"));
            if (resp.statusCode() == 404) return new Result(false, I18n.t("Utilisateur introuvable sur Last.fm"));
            return new Result(false, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    public static Result testListenBrainz(String username) {
        if (username == null || username.isBlank()) return new Result(false, I18n.t("Nom d'utilisateur vide"));
        try {
            String url = "https://api.listenbrainz.org/1/user/" + enc(username) + "/listens?count=1";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new Result(true, I18n.t("Utilisateur trouvé"));
            if (resp.statusCode() == 404) return new Result(false, I18n.t("Utilisateur introuvable sur ListenBrainz"));
            return new Result(false, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    /** Teste un serveur MusicBrainz (public ou miroir tiers) avec authentification HTTP Basic
     *  optionnelle — mêmes réglages que MusicBrainzClient (server/authUser/authPass), utilisé à la
     *  fois pour l'API publique et pour un miroir configuré (voir Préférences → MusicBrainz). */
    public static Result testMusicBrainz(String server, String authUser, String authPass) {
        if (server == null || server.isBlank()) return new Result(false, I18n.t("URL de serveur vide"));
        try {
            String base = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
            String url = base + "/artist/" + WELL_KNOWN_ARTIST_MBID + "?fmt=json";
            String basicAuth = null;
            if (authUser != null && !authUser.isBlank() && authPass != null && !authPass.isBlank()) {
                basicAuth = Base64.getEncoder().encodeToString(
                        (authUser + ":" + authPass).getBytes(StandardCharsets.UTF_8));
            }
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET();
            if (basicAuth != null) rb.header("Authorization", "Basic " + basicAuth);
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return new Result(true, I18n.t("Serveur accessible"));
            if (resp.statusCode() == 401) return new Result(false, I18n.t("Authentification refusée (401)"));
            return new Result(false, "HTTP " + resp.statusCode());
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
    }

    private static JsonNode getJson(String url, String authHeader) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .GET();
        if (authHeader != null) rb.header("Authorization", authHeader);
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.body() == null || resp.body().isBlank()) return null;
        return mapper.readTree(resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
