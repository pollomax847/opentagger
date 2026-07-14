package com.opentagger;

import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Télécharge les pochettes depuis le Cover Art Archive (CAA) de MusicBrainz.
 * Aucune clé API requise. Sources tentées dans l'ordre :
 *   1. CAA release front (relié directement à la release choisie)
 *   2. CAA release-group front (partagé entre toutes les éditions)
 */
public class CaaClient {

    private static final String CAA_URL = "https://coverartarchive.org";

    private static final HttpClient http = HttpTimeouts.client();

    /**
     * Tente de télécharger la pochette avant (front) pour le TagInfo donné.
     * Essaie d'abord par releaseMbid, puis par releaseGroupMbid.
     * Retourne un fichier temporaire ou null en cas d'échec.
     */
    public Path downloadFront(TagInfo info, MetadataCache cache) {
        Path p = downloadFromRelease(info, cache);
        return p != null ? p : downloadFromReleaseGroup(info, cache);
    }

    /** Pochette liée à l'édition exacte (la plus précise) — un des 2 niveaux CAA séparément activables. */
    public Path downloadFromRelease(TagInfo info, MetadataCache cache) {
        if (info.releaseMbid.isBlank()) return null;
        Path p = tryDownload("/release/" + info.releaseMbid + "/front-500", cache);
        if (p != null) return p;
        return tryDownload("/release/" + info.releaseMbid + "/front", cache); // fallback taille complète
    }

    /** Pochette partagée entre toutes les éditions du même album — l'autre niveau CAA. */
    public Path downloadFromReleaseGroup(TagInfo info, MetadataCache cache) {
        if (info.releaseGroupMbid.isBlank()) return null;
        Path p = tryDownload("/release-group/" + info.releaseGroupMbid + "/front-500", cache);
        if (p != null) return p;
        return tryDownload("/release-group/" + info.releaseGroupMbid + "/front", cache);
    }

    /**
     * Mise en cache façon Picard (un seul cache réseau pour tout, pas seulement MusicBrainz) —
     * avant ce fix, chaque piste d'un même album retéléchargeait la même pochette CAA.
     */
    private Path tryDownload(String path, MetadataCache cache) {
        String cacheKey = "caa:" + path;
        try {
            MetadataCache.CachedImage cached = cache.getCachedImage(cacheKey);
            if (cached != null) return writeTempFile(cached.bytes(), cached.ext());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CAA_URL + path))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.apiCall())
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            byte[] body = resp.body();
            if (body == null || body.length < 512) return null; // réponse trop courte → erreur

            // Vérifier la signature JPEG/PNG pour éviter d'écrire du HTML comme image
            if (!isImageBytes(body)) return null;

            String ext = ".jpg";
            cache.putCachedImage(cacheKey, body, ext);
            return writeTempFile(body, ext);
        } catch (Exception e) {
            return null; // timeout, 404, réseau — on ignore silencieusement
        }
    }

    private Path writeTempFile(byte[] body, String ext) throws java.io.IOException {
        Path tmp = Files.createTempFile("opentagger-caa-", ext);
        Files.write(tmp, body);
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Vérifie que les premiers octets correspondent à JPEG (FFD8FF) ou PNG (89504E47). */
    private static boolean isImageBytes(byte[] b) {
        if (b.length < 4) return false;
        // JPEG
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        // PNG
        if ((b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50 && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47) return true;
        return false;
    }
}
