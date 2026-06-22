package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Client OAuth2 pour MusicBrainz.
 *
 * Flow :
 *  1. Démarrer un serveur HTTP local sur un port libre
 *  2. Ouvrir le navigateur sur la page d'autorisation MB
 *  3. L'utilisateur accepte → MB redirige vers http://localhost:{port}/callback?code=...
 *  4. Échanger le code contre un access_token
 *  5. Stocker le token dans Config
 *
 * Pour que cela fonctionne, il faut enregistrer une application sur
 * https://musicbrainz.org/account/applications/register
 * avec redirect_uri = http://localhost (le port est ajouté dynamiquement).
 * Les identifiants (client_id / client_secret) sont configurés dans les Préférences.
 */
public class MusicBrainzOAuth {

    private static final String AUTH_URL  = "https://musicbrainz.org/oauth2/authorize";
    private static final String TOKEN_URL = "https://musicbrainz.org/oauth2/token";
    private static final String USER_URL  = "https://musicbrainz.org/oauth2/userinfo";

    private static final String APP_VER   = Config.get().str("app.version", "0.1.0");
    private static final String TAG_URL   = "https://musicbrainz.org/ws/2/tag?client=OpenTagger-" + APP_VER;
    private static final String RATE_URL  = "https://musicbrainz.org/ws/2/rating?client=OpenTagger-" + APP_VER;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    // ── Autorisation OAuth2 ───────────────────────────────────────────────────

    /**
     * Lance le flow OAuth2 :
     *  - Ouvre le navigateur de l'utilisateur sur la page MB
     *  - Attend le callback (max 90 secondes)
     *  - Retourne l'access_token
     *
     * Doit être appelé depuis un thread non-EDT (SwingWorker).
     */
    public String authorize() throws Exception {
        String clientId     = Config.get().mbClientId();
        String clientSecret = Config.get().mbClientSecret();
        if (clientId.isBlank() || clientSecret.isBlank())
            throw new IllegalStateException(
                "Client ID et Client Secret non configurés.\n" +
                "Enregistrez votre application sur\nhttps://musicbrainz.org/account/applications/register\n" +
                "puis renseignez les identifiants dans Préférences → MusicBrainz.");

        try (ServerSocket srv = new ServerSocket(0)) {
            srv.setSoTimeout(90_000);
            int    port        = srv.getLocalPort();
            String redirectUri = "http://localhost:" + port + "/callback";

            String authUrl = AUTH_URL
                + "?client_id="     + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri="  + encode(redirectUri)
                + "&scope=tag+rating";

            Desktop.getDesktop().browse(URI.create(authUrl));

            try (Socket conn = srv.accept()) {
                String code = parseCode(conn);
                sendOkPage(conn);
                String token = exchangeCode(code, redirectUri, clientId, clientSecret);
                // Persister le token + username
                String username = fetchUsername(token);
                Config.get().set("mb.oauth.token",    token);
                Config.get().set("mb.oauth.username", username);
                return token;
            }
        }
    }

    /** Révoque le token local (supprime de la config — pas d'appel réseau, MB ne supporte pas la révocation). */
    public static void logout() {
        Config.get().set("mb.oauth.token",    "");
        Config.get().set("mb.oauth.username", "");
    }

    // ── Informations utilisateur ──────────────────────────────────────────────

    public String fetchUsername(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(USER_URL))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", Config.get().userAgent())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        return json.has("sub") ? json.get("sub").asText() : "(inconnu)";
    }

    // ── Soumission de tags utilisateur ────────────────────────────────────────

    /**
     * Soumet des user-tags pour un enregistrement MB.
     * Les tags doivent être des mots/expressions courtes (genre, mood…).
     */
    public void submitUserTags(String recordingMbid, List<String> tags, String token) throws Exception {
        if (tags.isEmpty()) return;
        StringBuilder xml = new StringBuilder(
            "<metadata xmlns=\"http://musicbrainz.org/ns/mmd-2.0#\">" +
            "<recording-list><recording id=\"").append(recordingMbid).append("\"><user-tag-list>");
        for (String t : tags)
            xml.append("<user-tag><name>").append(escXml(t)).append("</name></user-tag>");
        xml.append("</user-tag-list></recording></recording-list></metadata>");

        post(TAG_URL, xml.toString(), token, "tag soumission");
    }

    // ── Soumission de rating utilisateur (1–5 étoiles) ───────────────────────

    /**
     * Soumet un rating pour un enregistrement MB.
     * @param stars 1–5
     */
    public void submitRating(String recordingMbid, int stars, String token) throws Exception {
        int mbRating = Math.max(0, Math.min(5, stars)) * 20;
        String xml = "<metadata xmlns=\"http://musicbrainz.org/ns/mmd-2.0#\">" +
            "<recording-list><recording id=\"" + recordingMbid + "\">" +
            "<user-rating>" + mbRating + "</user-rating>" +
            "</recording></recording-list></metadata>";

        post(RATE_URL, xml, token, "rating soumission");
    }

    // ── Helpers HTTP ──────────────────────────────────────────────────────────

    private void post(String url, String xmlBody, String token, String desc) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/xml; charset=UTF-8")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(xmlBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400)
            throw new Exception(desc + " — HTTP " + resp.statusCode() + " : " + resp.body());
    }

    // ── Helpers OAuth ─────────────────────────────────────────────────────────

    private String parseCode(Socket conn) throws IOException {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        String line = br.readLine(); // "GET /callback?code=XXX HTTP/1.1"
        if (line == null) throw new IOException("Réponse navigateur vide");
        int q = line.indexOf('?'), s = line.lastIndexOf(' ');
        if (q < 0 || s < 0) throw new IOException("Paramètres OAuth introuvables");
        for (String part : line.substring(q + 1, s).split("&")) {
            if (part.startsWith("code="))  return part.substring(5);
            if (part.startsWith("error=")) throw new IOException("Accès refusé par MB : " + part.substring(6));
        }
        throw new IOException("Code OAuth introuvable dans le callback");
    }

    private void sendOkPage(Socket conn) {
        String body = "<html><body style='font-family:sans-serif;background:#1e1e1e;color:#ddd;padding:40px'>" +
            "<h2 style='color:#1db954'>&#10003; Connecté à MusicBrainz !</h2>" +
            "<p>Vous pouvez fermer cet onglet et retourner dans OpenTagger.</p>" +
            "</body></html>";
        String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
            "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length +
            "\r\nConnection: close\r\n\r\n" + body;
        try {
            conn.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().flush();
        } catch (IOException ignored) {}
    }

    private String exchangeCode(String code, String redirectUri, String clientId, String clientSecret)
            throws Exception {
        String body = "grant_type=authorization_code"
            + "&code="          + encode(code)
            + "&client_id="     + encode(clientId)
            + "&client_secret=" + encode(clientSecret)
            + "&redirect_uri="  + encode(redirectUri);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", Config.get().userAgent())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        if (json.has("error"))
            throw new Exception("OAuth : " + json.path("error_description").asText(json.path("error").asText()));
        if (!json.has("access_token"))
            throw new Exception("Réponse OAuth inattendue : " + resp.body());
        return json.get("access_token").asText();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
