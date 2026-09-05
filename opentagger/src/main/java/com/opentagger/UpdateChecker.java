package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Vérifie/télécharge/applique les mises à jour depuis un dépôt GitHub public séparé
 * ({@code pollomax847/opentagger-releases}) dédié uniquement aux binaires — le dépôt source
 * ({@code pollomax847/opentagger}) reste privé, aucun jeton d'authentification n'est nécessaire
 * puisque les releases d'un dépôt public sont lisibles anonymement via l'API GitHub.
 */
public class UpdateChecker {

    private static final String RELEASES_REPO = "pollomax847/opentagger-releases";
    private static final String LATEST_URL =
            "https://api.github.com/repos/" + RELEASES_REPO + "/releases/latest";

    // GitHub redirige (302) les URLs d'assets de release vers un CDN signé — sans suivre les
    // redirections, le téléchargement échoue systématiquement avec "HTTP 302" au lieu du contenu.
    private static final HttpClient http = HttpTimeouts.client();
    private final ObjectMapper mapper = new ObjectMapper();

    public record UpdateInfo(String version, String downloadUrl, String releaseNotes) {}

    /** Retourne les infos de la dernière release si elle est plus récente que la version actuelle, sinon null. */
    public UpdateInfo checkLatest() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(LATEST_URL))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.apiCall())
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return null; // pas encore de release publiée
        if (response.statusCode() != 200)
            throw new Exception("GitHub API HTTP " + response.statusCode() + " : " + response.body());

        JsonNode root = mapper.readTree(response.body());
        String tag = root.path("tag_name").asText("").trim();
        String remoteVersion = tag.startsWith("v") ? tag.substring(1) : tag;
        if (remoteVersion.isBlank()) return null;

        String currentVersion = Config.get().appVersion();
        if (!isNewer(remoteVersion, currentVersion)) return null;

        String jarUrl = null;
        for (JsonNode asset : root.path("assets")) {
            String name = asset.path("name").asText("");
            if (name.endsWith(".jar")) {
                jarUrl = asset.path("browser_download_url").asText("");
                break;
            }
        }
        if (jarUrl == null || jarUrl.isBlank()) return null;

        return new UpdateInfo(remoteVersion, jarUrl, root.path("body").asText(""));
    }

    /** Compare deux versions "X.Y.Z" — true si a strictement plus récente que b. */
    static boolean isNewer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return va > vb;
        }
        return false;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (Exception e) { return 0; }
    }

    /** Télécharge le jar de mise à jour vers un fichier temporaire. */
    public Path download(String url) throws Exception {
        Path temp = Files.createTempFile("opentagger-update-", ".jar");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.largeDownload())
                .GET().build();
        HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(temp));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temp);
            throw new IOException("Téléchargement échoué : HTTP " + response.statusCode());
        }
        // Un jar "fat" shaded fait plusieurs Mo — un fichier anormalement petit signale une
        // réponse d'erreur/HTML plutôt que le vrai binaire.
        if (Files.size(temp) < 1_000_000) {
            Files.deleteIfExists(temp);
            throw new IOException("Fichier téléchargé anormalement petit — abandon.");
        }
        return temp;
    }

    /** Chemin du jar actuellement en cours d'exécution. */
    public static Path currentJarPath() throws Exception {
        return Paths.get(UpdateChecker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /**
     * Remplace le jar actuel par le nouveau. Sous Linux/Mac, remplacement direct et atomique —
     * sûr même pendant que ce jar tourne (l'ancien inode reste utilisable par ce process jusqu'à
     * sa fermeture, le prochain lancement récupère le nouveau fichier). Sous Windows, le fichier
     * est verrouillé par la JVM en cours d'exécution : le nouveau jar est déposé à côté avec un
     * suffixe ".new" ; c'est le lanceur (install-windows.bat) qui le fait glisser en place avant
     * de démarrer la JVM, à son prochain lancement.
     */
    public void applyUpdate(Path downloadedJar) throws Exception {
        Path current = currentJarPath();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Path pending = Paths.get(current.toString() + ".new");
            Files.move(downloadedJar, pending, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        try {
            Files.move(downloadedJar, current,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // downloadedJar vient de Files.createTempFile() (répertoire temp système, ex. /tmp —
            // souvent un montage séparé, parfois tmpfs) : rien ne garantit qu'il soit sur le même
            // système de fichiers que `current` (ex. ~/.local/share/opentagger ou un checkout de
            // dev), et ATOMIC_MOVE l'exige — vécu en vrai par l'utilisateur (AtomicMoveNotSupported
            // -Exception, "Lien physique inter-périphérique invalide"). PAS de repli par simple
            // Files.copy() par-dessus `current` : ce jar est potentiellement EN COURS D'EXÉCUTION
            // (c'est lui-même qui vient de se télécharger sa propre mise à jour) — écrire dans le
            // même inode pendant qu'une classe pas encore chargée pourrait l'être plus tard dans
            // cette session corromprait le process vivant, pas seulement le prochain lancement.
            // On copie donc d'abord vers un fichier temporaire sur le MÊME système de fichiers que
            // `current` (son propre répertoire parent), puis on termine par un rename — atomique
            // par construction puisque même device. Seule la copie initiale traverse les systèmes
            // de fichiers ; le remplacement du jar vivant reste, lui, toujours un rename atomique.
            Path sameDeviceTemp = Files.createTempFile(current.getParent(), "opentagger-update-", ".jar");
            try {
                Files.copy(downloadedJar, sameDeviceTemp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(sameDeviceTemp, current,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(sameDeviceTemp);
                Files.deleteIfExists(downloadedJar);
            }
        }
    }

    /** Relance l'application (nouveau process sur le jar déjà mis à jour) puis quitte le process actuel. */
    public static void restartApp() throws Exception {
        Path jar = currentJarPath();
        new ProcessBuilder("java", "-jar", jar.toString()).inheritIO().start();
        System.exit(0);
    }
}
