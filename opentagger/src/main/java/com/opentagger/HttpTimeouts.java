package com.opentagger;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Point central pour le client HTTP et les délais des ~17 classes clientes (MusicBrainz, Discogs,
 * Last.fm, CAA, FanArt, Deezer, AcoustID, ListenBrainz, podcasts...) — avant, chacune construisait
 * son propre HttpClient avec son propre connectTimeout (6/10/15/30s selon le fichier, sans raison
 * fonctionnelle) et, jusqu'au correctif du 2026-07-14, sans .timeout() par requête du tout : un
 * connectTimeout seul ne borne que la poignée de main TCP, pas une connexion qui réussit puis reste
 * bloquée — exactement ce qui a fait tourner un SaveWorker indéfiniment et épuisé le pool statique
 * de SwingWorker. Le correctif du 2026-07-14 a ajouté .timeout(N) dans 13 fichiers séparément, avec
 * des valeurs recopiées à la main (15/20/30/60s) sans constante commune — cette classe remplace ce
 * patch dispersé par un seul point de vérité.
 *
 * Trois paliers, pas un timeout par client : certains endpoints ont légitimement besoin de plus de
 * marge (flux RSS de podcasts, téléchargement du binaire fpcalc) que les appels API classiques.
 * Valeurs par défaut reprises du correctif existant (choix a posteriori sous pression d'incident,
 * pas mesuré) — ajustables sans recompiler via settings.properties.
 */
public final class HttpTimeouts {

    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Un seul HttpClient partagé : thread-safe et réutilisable par conception (voir sa Javadoc). */
    public static HttpClient client() { return SHARED; }

    /** Appels API classiques (MusicBrainz, Discogs, Last.fm, AcoustID, ListenBrainz, Deezer, CAA...). */
    public static Duration apiCall() {
        return Duration.ofSeconds(Config.get().num("http.timeout.api_sec", 20));
    }

    /** Téléchargements binaires courts (pochettes, soumission d'empreinte). */
    public static Duration binaryDownload() {
        return Duration.ofSeconds(Config.get().num("http.timeout.download_sec", 30));
    }

    /** Téléchargements plus volumineux ou lents par nature (flux RSS podcast, binaire fpcalc). */
    public static Duration largeDownload() {
        return Duration.ofSeconds(Config.get().num("http.timeout.large_download_sec", 60));
    }

    private HttpTimeouts() {}
}
