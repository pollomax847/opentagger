package com.opentagger;

import com.opentagger.model.TagInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Télécharge les pochettes depuis le Cover Art Archive (CAA) de MusicBrainz.
 * Aucune clé API requise. Sources tentées dans l'ordre :
 *   1. CAA release front (relié directement à la release choisie)
 *   2. CAA release-group front (partagé entre toutes les éditions)
 */
public class CaaClient {

    private static final String CAA_URL = "https://coverartarchive.org";

    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    /**
     * Tente de télécharger la pochette avant (front) pour le TagInfo donné.
     * Essaie d'abord par releaseMbid, puis par releaseGroupMbid.
     * Retourne un fichier temporaire ou null en cas d'échec.
     */
    public Path downloadFront(TagInfo info) {
        Path p = downloadFromRelease(info);
        return p != null ? p : downloadFromReleaseGroup(info);
    }

    /** Pochette liée à l'édition exacte (la plus précise) — un des 2 niveaux CAA séparément activables. */
    public Path downloadFromRelease(TagInfo info) {
        if (info.releaseMbid.isBlank()) return null;
        Path p = tryDownload("/release/" + info.releaseMbid + "/front-500");
        if (p != null) return p;
        return tryDownload("/release/" + info.releaseMbid + "/front"); // fallback taille complète
    }

    /** Pochette partagée entre toutes les éditions du même album — l'autre niveau CAA. */
    public Path downloadFromReleaseGroup(TagInfo info) {
        if (info.releaseGroupMbid.isBlank()) return null;
        Path p = tryDownload("/release-group/" + info.releaseGroupMbid + "/front-500");
        if (p != null) return p;
        return tryDownload("/release-group/" + info.releaseGroupMbid + "/front");
    }

    private Path tryDownload(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(CAA_URL + path))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) return null;
            byte[] body = resp.body();
            if (body == null || body.length < 512) return null; // réponse trop courte → erreur

            // Vérifier la signature JPEG/PNG pour éviter d'écrire du HTML comme image
            if (!isImageBytes(body)) return null;

            Path tmp = Files.createTempFile("opentagger-caa-", ".jpg");
            Files.write(tmp, body);
            tmp.toFile().deleteOnExit();
            return tmp;
        } catch (Exception e) {
            return null; // timeout, 404, réseau — on ignore silencieusement
        }
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
