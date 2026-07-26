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
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * avec redirect_uri = http://localhost:8484
 * Les identifiants (client_id / client_secret) sont configurés dans les Préférences.
 */
public class MusicBrainzOAuth {

    private static final String AUTH_URL      = "https://musicbrainz.org/oauth2/authorize";
    private static final String TOKEN_URL     = "https://musicbrainz.org/oauth2/token";
    private static final String USER_URL      = "https://musicbrainz.org/oauth2/userinfo";
    // beta.musicbrainz.org est le même backend — les tokens sont valides pour ws/2/
    private static final int    CALLBACK_PORT   = 8484;
    private static final String REDIRECT_LOCAL  = "http://localhost:" + CALLBACK_PORT;
    private static final String REDIRECT_OOB    = "urn:ietf:wg:oauth:2.0:oob";
    private static final String REDIRECT_SCHEME = "org.opentagger.app://auth";
    private static final Path   CODE_FILE       = Paths.get(System.getProperty("user.home"),
                                                      ".opentagger", "oauth-code.tmp");

    private static final String APP_VER   = Config.get().appVersion();
    private static final String TAG_URL   = "https://musicbrainz.org/ws/2/tag?client=OpenTagger-" + APP_VER;
    private static final String RATE_URL  = "https://musicbrainz.org/ws/2/rating?client=OpenTagger-" + APP_VER;

    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    // Coupe-circuit par instance : évite de retenter une soumission vouée à l'échec sur CHAQUE
    // fichier d'un run (TaggingWorker/AlbumCompletionWorker/InfoCompleterWorker/MatchDialog créent
    // chacun UNE seule instance de MusicBrainzOAuth réutilisée pour tous leurs fichiers, voir leurs
    // champs `mbOauth`). Trouvé en production : un compte connecté avant l'ajout du rafraîchissement
    // automatique (mb.oauth.refresh_token vide) fait échouer post()/put() en HTTP 401 sur
    // ABSOLUMENT CHAQUE fichier, pour toujours — sans ce coupe-circuit, ça gaspille 2 requêtes HTTP
    // par fichier (POST initial + tentative de rafraîchissement, elle aussi vouée à l'échec) sur
    // toute une bibliothèque de centaines de milliers de fichiers, pour un résultat qui ne peut
    // structurellement jamais réussir tant que l'utilisateur ne se reconnecte pas. Se réinitialise
    // naturellement au prochain run (chaque worker recrée sa propre instance).
    private volatile boolean submissionBroken = false;

    // ── Autorisation OAuth2 ───────────────────────────────────────────────────

    /**
     * Flow OAuth2 :
     *  - mode "scheme"    (défaut) : custom URL scheme org.opentagger.app://auth
     *                                → app installée MB, callback = org.opentagger.app://auth
     *  - mode "localhost" : serveur HTTP local port 8484
     *                                → app web MB, callback = http://localhost:8484
     *  - mode "oob"       : code affiché à l'écran, copier-coller
     *                                → app installée MB, callback = urn:ietf:wg:oauth:2.0:oob
     */
    public String authorize() throws Exception {
        String clientId     = Config.get().mbClientId();
        String clientSecret = Config.get().mbClientSecret();
        if (clientId.isBlank() || clientSecret.isBlank())
            throw new IllegalStateException(
                "Client ID/Secret MusicBrainz manquants dans la configuration de l'application "
                + "(ce n'est plus un réglage utilisateur — contactez le développeur ou réinstallez OpenTagger).");

        String mode = Config.get().str("mb.oauth.mode", "scheme");
        return switch (mode) {
            case "localhost" -> authorizeLocalhost(clientId, clientSecret);
            case "oob"       -> authorizeOob(clientId, clientSecret);
            default          -> authorizeScheme(clientId, clientSecret);
        };
    }

    /**
     * Flow custom URL scheme (org.opentagger.app://auth).
     * Enregistre un handler xdg-mime au premier appel.
     * MB redirige vers org.opentagger.app://auth?code=XXX, le handler écrit le code dans un fichier,
     * l'app le lit automatiquement.
     * URL de rappel MB (app installée) : org.opentagger.app://auth
     */
    private String authorizeScheme(String clientId, String clientSecret) throws Exception {
        registerSchemeHandler();
        Files.deleteIfExists(CODE_FILE);
        preCreateCodeFileRestricted();

        String authUrl = AUTH_URL
            + "?client_id="    + encode(clientId)
            + "&response_type=code"
            + "&scope=profile+tag+rating"
            + "&redirect_uri=" + encode(REDIRECT_SCHEME);
        Desktop.getDesktop().browse(new URI(authUrl));

        // Attendre que le handler écrive le code — WatchService évite le busy-wait Thread.sleep
        Path watchDir = CODE_FILE.getParent();
        try (WatchService watcher = watchDir.getFileSystem().newWatchService()) {
            watchDir.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            long deadline = System.currentTimeMillis() + 120_000;
            while (System.currentTimeMillis() < deadline) {
                long remaining = Math.max(100, deadline - System.currentTimeMillis());
                WatchKey key = watcher.poll(remaining, TimeUnit.MILLISECONDS);
                if (key != null) { key.pollEvents(); key.reset(); }
                if (Files.exists(CODE_FILE)) {
                    String code = Files.readString(CODE_FILE).trim();
                    Files.deleteIfExists(CODE_FILE);
                    if (!code.isBlank())
                        return finalizeToken(code, REDIRECT_SCHEME, clientId, clientSecret);
                }
            }
        }
        throw new Exception("Délai dépassé (2 min). Vérifiez que l'app est bien autorisée dans le navigateur.");
    }

    /**
     * Pré-crée oauth-code.tmp en 0600 (propriétaire seul) AVANT d'ouvrir le navigateur — le script
     * oauth-handler.sh y écrit ensuite via `echo ... > fichier`, qui TRONQUE un fichier existant
     * sans jamais réinitialiser ses permissions. Sans ce correctif, le fichier créé par le script
     * héritait des permissions par défaut du umask (souvent 644, lisible par tout utilisateur
     * local) : sur une machine multi-utilisateurs, n'importe quel autre compte local pouvait lire
     * le code d'autorisation OAuth pendant la fenêtre (jusqu'à 2 min) où le fichier existe, et
     * potentiellement l'échanger contre un token avant l'appli elle-même. Ignoré sur Windows
     * (PosixFilePermission n'existe pas sur NTFS ; authorizeScheme y utilise de toute façon le
     * registre + --oauth-callback, pas ce fichier).
     */
    private void preCreateCodeFileRestricted() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            java.util.Set<PosixFilePermission> perms = java.util.EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.createFile(CODE_FILE, PosixFilePermissions.asFileAttribute(perms));
        } catch (Exception ignored) {
            // FileAlreadyExistsException (race improbable) ou filesystem sans support POSIX —
            // le script écrira quand même, juste sans la garantie de permissions restreintes.
        }
    }

    /** Installe le handler URL scheme pour OAuth (Linux via xdg-mime, Windows via registre). */
    private void registerSchemeHandler() throws Exception {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            // Windows : enregistrer org.opentagger.app:// dans le registre
            String jar = new java.io.File(
                    MusicBrainzOAuth.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).getAbsolutePath();
            String cmd = "javaw -jar \"" + jar + "\" --oauth-callback \"%1\"";
            String base = "HKCU\\Software\\Classes\\org.opentagger.app";
            new ProcessBuilder("reg","add",base,"/ve","/d","URL:OpenTagger OAuth","/f").start().waitFor();
            new ProcessBuilder("reg","add",base,"/v","URL Protocol","/d","","/f").start().waitFor();
            new ProcessBuilder("reg","add",base+"\\shell\\open\\command","/ve","/d",cmd,"/f").start().waitFor();
            return;
        }

        // Linux / macOS : xdg-mime
        String home = System.getProperty("user.home");
        Path appsDir = Paths.get(home, ".local", "share", "applications");
        Files.createDirectories(appsDir);

        Path script = Paths.get(Config.configDir(), "oauth-handler.sh");
        String sh = "#!/bin/bash\nurl=\"$1\"\ncode=\"${url#*code=}\"\ncode=\"${code%%&*}\"\necho \"$code\" > '" + CODE_FILE + "'\n";
        Files.writeString(script, sh);
        new ProcessBuilder("chmod", "+x", script.toString()).start().waitFor();

        Path desktop = appsDir.resolve("opentagger-oauth.desktop");
        Files.writeString(desktop, "[Desktop Entry]\nVersion=1.0\nType=Application\n"
            + "Name=OpenTagger OAuth\nExec=" + script + " %u\n"
            + "MimeType=x-scheme-handler/org.opentagger.app;\nNoDisplay=true\nTerminal=false\n");

        new ProcessBuilder("xdg-mime","default","opentagger-oauth.desktop",
                           "x-scheme-handler/org.opentagger.app").start().waitFor();
        new ProcessBuilder("update-desktop-database", appsDir.toString()).start().waitFor();
    }

    /** Flow localhost : serveur HTTP local sur port 8484. URL de rappel MB = http://localhost:8484 */
    private String authorizeLocalhost(String clientId, String clientSecret) throws Exception {
        AtomicReference<String> codeRef  = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread server = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(CALLBACK_PORT)) {
                ss.setSoTimeout(120_000);
                try (Socket conn = ss.accept();
                     BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                     OutputStream   out = conn.getOutputStream()) {

                    String line = in.readLine();
                    String code = null, err = null;
                    if (line != null) {
                        int sp = line.indexOf(' '), sp2 = line.lastIndexOf(' ');
                        String path = (sp >= 0 && sp2 > sp) ? line.substring(sp + 1, sp2) : "";
                        int q = path.indexOf('?');
                        if (q >= 0) {
                            for (String p : path.substring(q + 1).split("&")) {
                                if (p.startsWith("code="))
                                    code = URLDecoder.decode(p.substring(5), StandardCharsets.UTF_8);
                                else if (p.startsWith("error="))
                                    err = URLDecoder.decode(p.substring(6), StandardCharsets.UTF_8);
                            }
                        }
                    }
                    if (code != null) codeRef.set(code);
                    else errorRef.set(err != null ? err : "code absent");

                    String html = code != null
                        ? "<html><body><h2>Connecté !</h2><p>Vous pouvez fermer cet onglet.</p></body></html>"
                        : "<html><body><h2>Erreur</h2><p>" + err + "</p></body></html>";
                    String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n"
                                + "Content-Length: " + html.getBytes(StandardCharsets.UTF_8).length
                                + "\r\nConnection: close\r\n\r\n" + html;
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                if (codeRef.get() == null) errorRef.set(e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        server.setDaemon(true);
        server.start();

        String authUrl = AUTH_URL
            + "?client_id="    + encode(clientId)
            + "&response_type=code"
            + "&scope=profile+tag+rating"
            + "&redirect_uri=" + REDIRECT_LOCAL;
        Desktop.getDesktop().browse(new URI(authUrl));

        if (!latch.await(125, TimeUnit.SECONDS))
            throw new Exception("Délai dépassé (2 min). Essayez le mode OOB dans Préférences → MusicBrainz.");

        String err = errorRef.get();
        if (err != null) throw new Exception(
            "Autorisation échouée : " + err +
            "\n\nSi vous avez 'Mismatched redirect URI', changez mb.oauth.mode=oob dans les settings.");

        String code = codeRef.get();
        return finalizeToken(code, REDIRECT_LOCAL, clientId, clientSecret);
    }

    /** Flow OOB : MB affiche le code à l'écran, l'utilisateur le copie. URL de rappel MB = urn:ietf:wg:oauth:2.0:oob */
    private String authorizeOob(String clientId, String clientSecret) throws Exception {
        String authUrl = AUTH_URL
            + "?client_id="    + encode(clientId)
            + "&response_type=code"
            + "&scope=profile+tag+rating"
            + "&redirect_uri=" + encode(REDIRECT_OOB);
        Desktop.getDesktop().browse(new URI(authUrl));

        String[] result = new String[1];
        javax.swing.SwingUtilities.invokeAndWait(() ->
            result[0] = javax.swing.JOptionPane.showInputDialog(
                null,
                "<html>MusicBrainz s'est ouvert dans le navigateur.<br><br>" +
                "Connectez-vous et autorisez OpenTagger,<br>" +
                "puis copiez le <b>code d'autorisation</b> affiché et collez-le ici :</html>",
                "Code d'autorisation MusicBrainz",
                javax.swing.JOptionPane.PLAIN_MESSAGE)
        );
        String code = result[0];
        if (code == null || code.isBlank()) throw new Exception("Code non fourni.");
        return finalizeToken(code.trim(), REDIRECT_OOB, clientId, clientSecret);
    }

    private String finalizeToken(String code, String redirectUri, String clientId, String clientSecret)
            throws Exception {
        TokenPair tokens = exchangeCode(code, redirectUri, clientId, clientSecret);
        String username = fetchUsername(tokens.accessToken());
        Config.get().set("mb.oauth.token",         tokens.accessToken());
        Config.get().set("mb.oauth.refresh_token", tokens.refreshToken());
        Config.get().set("mb.oauth.username",      username);
        return tokens.accessToken();
    }

    /** Révoque le token local (supprime de la config — pas d'appel réseau, MB ne supporte pas la révocation). */
    public static void logout() {
        Config.get().set("mb.oauth.token",         "");
        Config.get().set("mb.oauth.refresh_token", "");
        Config.get().set("mb.oauth.username",      "");
    }

    /**
     * Rafraîchit l'access_token expiré via le refresh_token stocké (grant_type=refresh_token).
     * Les access_token MusicBrainz expirent au bout d'1h (expires_in=3600) — sans ce
     * rafraîchissement, chaque soumission de tag/rating échouait en 401 dès que le token
     * expirait, pour toujours, jusqu'à ce que l'utilisateur refasse toute l'autorisation OAuth
     * manuellement. Retourne le nouvel access_token, ou null si le rafraîchissement est
     * impossible (pas de refresh_token stocké, ou identifiants manquants).
     */
    private String refreshAccessToken() {
        String clientId     = Config.get().mbClientId();
        String clientSecret = Config.get().mbClientSecret();
        String refreshToken = Config.get().str("mb.oauth.refresh_token", "");
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) return null;

        try {
            String body = "grant_type=refresh_token"
                + "&refresh_token=" + encode(refreshToken)
                + "&client_id="     + encode(clientId)
                + "&client_secret=" + encode(clientSecret);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(resp.body());
            if (json.has("error") || !json.has("access_token")) return null;

            String newAccessToken = json.get("access_token").asText();
            Config.get().set("mb.oauth.token", newAccessToken);
            // MusicBrainz renvoie un nouveau refresh_token à chaque rafraîchissement — le garder.
            if (json.has("refresh_token"))
                Config.get().set("mb.oauth.refresh_token", json.get("refresh_token").asText());
            return newAccessToken;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Informations utilisateur ──────────────────────────────────────────────

    public String fetchUsername(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(USER_URL))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new Exception("Erreur HTTP " + resp.statusCode() + " lors de la récupération du compte MB.");
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

    // ── Ajout à une collection utilisateur ────────────────────────────────────

    /**
     * Ajoute une release à une collection MusicBrainz existante de l'utilisateur.
     * La collection doit déjà exister (créée manuellement sur musicbrainz.org — l'API ne permet
     * pas d'en créer une) ; son MBID se trouve dans l'URL de la page de la collection.
     */
    public void addReleaseToCollection(String collectionMbid, String releaseMbid, String token) throws Exception {
        String url = "https://musicbrainz.org/ws/2/collection/" + collectionMbid + "/releases/" + releaseMbid
                + "?client=OpenTagger-" + APP_VER;
        put(url, token, "ajout à la collection");
    }

    // ── Helpers HTTP ──────────────────────────────────────────────────────────

    private void post(String url, String xmlBody, String token, String desc) throws Exception {
        if (submissionBroken)
            throw new Exception(desc + " — désactivé pour ce run (reconnectez MusicBrainz dans Réglages)");
        HttpResponse<String> resp = doPost(url, xmlBody, token);
        if (resp.statusCode() == 401) {
            // Access token expiré (durée de vie 1h côté MB) : tenter un rafraîchissement
            // silencieux via le refresh_token stocké, puis rejouer la requête une seule fois.
            String refreshed = refreshAccessToken();
            if (refreshed == null) {
                submissionBroken = true; // pas de refresh_token exploitable → ne peut plus réussir ce run
            } else {
                resp = doPost(url, xmlBody, refreshed);
                if (resp.statusCode() == 401) submissionBroken = true; // token rafraîchi aussi rejeté
            }
        }
        if (resp.statusCode() >= 400)
            throw new Exception(desc + " — HTTP " + resp.statusCode() + " : " + resp.body());
    }

    private void put(String url, String token, String desc) throws Exception {
        if (submissionBroken)
            throw new Exception(desc + " — désactivé pour ce run (reconnectez MusicBrainz dans Réglages)");
        HttpResponse<String> resp = doPut(url, token);
        if (resp.statusCode() == 401) {
            String refreshed = refreshAccessToken();
            if (refreshed == null) {
                submissionBroken = true;
            } else {
                resp = doPut(url, refreshed);
                if (resp.statusCode() == 401) submissionBroken = true;
            }
        }
        if (resp.statusCode() >= 400)
            throw new Exception(desc + " — HTTP " + resp.statusCode() + " : " + resp.body());
    }

    private HttpResponse<String> doPut(String url, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> doPost(String url, String xmlBody, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/xml; charset=UTF-8")
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .POST(HttpRequest.BodyPublishers.ofString(xmlBody, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── Helpers OAuth ─────────────────────────────────────────────────────────

    /** Paire access_token/refresh_token renvoyée par MB à l'échange initial et au rafraîchissement. */
    private record TokenPair(String accessToken, String refreshToken) {}

    private TokenPair exchangeCode(String code, String redirectUri, String clientId, String clientSecret)
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
                .timeout(HttpTimeouts.apiCall())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(resp.body());
        if (json.has("error"))
            throw new Exception("OAuth : " + json.path("error_description").asText(json.path("error").asText()));
        if (!json.has("access_token"))
            throw new Exception("Réponse OAuth inattendue : " + resp.body());
        String refreshToken = json.has("refresh_token") ? json.get("refresh_token").asText() : "";
        return new TokenPair(json.get("access_token").asText(), refreshToken);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
