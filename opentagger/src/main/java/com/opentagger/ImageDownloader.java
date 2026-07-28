package com.opentagger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Téléchargement d'image générique vers un fichier temporaire — extrait de
 * {@code FanArtClient} pour être réutilisé ailleurs (podcasts) sans dupliquer cette logique.
 */
public final class ImageDownloader {

    private ImageDownloader() {}

    private static final HttpClient http = HttpTimeouts.client();

    /**
     * Télécharge une image vers un fichier temporaire. Retourne null si échec ou image trop
     * petite. Mise en cache façon Picard (un seul cache réseau pour tout, pas seulement
     * MusicBrainz) — partagée par FanArtClient, DeezerClient et TagEnrichment (résolution de
     * pochette Shazam), évite de retélécharger la même pochette d'artiste/album à chaque piste.
     * Le fichier temporaire retourné n'est PAS auto-supprimé : chaque appelant doit le nettoyer
     * lui-même après usage, idéalement dans un {@code finally} (voir TagEnrichment.saveEntry) —
     * sinon une exception après l'appel (écriture de tag en échec...) le laisse traîner
     * indéfiniment.
     */
    public static Path downloadToTempFile(String imageUrl, MetadataCache cache) throws Exception {
        if (imageUrl == null || imageUrl.isBlank()) return null;

        String cacheKey = "image:" + imageUrl;
        MetadataCache.CachedImage cached = cache.getCachedImage(cacheKey);
        if (cached != null) return writeTempFile(cached.bytes(), cached.ext());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.binaryDownload())
                .GET()
                .build();

        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) return null;

        // Détecter le type depuis le Content-Type (l'URL peut ne pas finir par .png/.jpg)
        String ct  = response.headers().firstValue("Content-Type").orElse("");
        String ext = ct.contains("png") ? ".png" : ".jpg";
        byte[] body = response.body();
        if (body == null || body.length < 1000) return null;

        cache.putCachedImage(cacheKey, body, ext);
        return writeTempFile(body, ext);
    }

    private static Path writeTempFile(byte[] body, String ext) throws Exception {
        Path tmp = Files.createTempFile("opentagger-cover-", ext);
        Files.write(tmp, body);
        return tmp;
    }
}
