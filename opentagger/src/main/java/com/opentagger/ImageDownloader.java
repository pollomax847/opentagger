package com.opentagger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Téléchargement d'image générique vers un fichier temporaire — extrait de
 * {@code FanArtClient} pour être réutilisé ailleurs (podcasts) sans dupliquer cette logique.
 */
public final class ImageDownloader {

    private ImageDownloader() {}

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /** Télécharge une image vers un fichier temporaire. Retourne null si échec ou image trop petite. */
    public static Path downloadToTempFile(String imageUrl) throws Exception {
        if (imageUrl == null || imageUrl.isBlank()) return null;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .header("User-Agent", Config.get().userAgent())
                .GET()
                .build();

        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) return null;

        // Détecter le type depuis le Content-Type (l'URL peut ne pas finir par .png/.jpg)
        String ct  = response.headers().firstValue("Content-Type").orElse("");
        String ext = ct.contains("png") ? ".png" : ".jpg";
        Path tmp   = Files.createTempFile("opentagger-cover-", ext);
        Files.copy(response.body(), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (Files.size(tmp) < 1000) { Files.deleteIfExists(tmp); return null; }
        return tmp;
    }
}
