package com.opentagger;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.nio.file.Path;

/**
 * Étapes d'enrichissement/bookkeeping partagées entre les pipelines de tagging
 * (App CLI, BatchProcessor, TaggingWorker, InfoCompleterWorker, MatchDialog), pour
 * éviter que chacun recode sa propre version et qu'un correctif appliqué à l'un ne
 * se propage pas aux autres.
 *
 * Les clients (DiscogsClient/LastFmClient/CaaClient/FanArtClient) sont passés en
 * paramètre plutôt qu'instanciés ici : certains appelants tournent dans un pool de
 * threads et doivent garder le contrôle du partage/fraîcheur des instances
 * (voir le commentaire sur LastFmClient dans BatchProcessor — cette classe garde un
 * résultat en cache dans un champ d'instance et ne doit jamais être partagée entre
 * threads concurrents).
 */
public final class TagEnrichment {

    private TagEnrichment() {}

    /** Cascade de genre : Discogs puis Last.fm en repli, uniquement si absent. */
    public static void enrichGenre(TagInfo ti, DiscogsClient discogs, LastFmClient lastFm) {
        if (ti.genre.isBlank()) {
            try { discogs.enrichGenres(ti); } catch (Exception ignored) {}
        }
        if (ti.genre.isBlank()) {
            try { lastFm.enrichGenres(ti); } catch (Exception ignored) {}
        }
    }

    /**
     * Cascade de pochette : essaie chaque fournisseur activé, dans l'ordre configuré
     * ({@code cover.provider_order} — façon Picard, liste de fournisseurs
     * activables/réordonnables), jusqu'au premier succès. Fournisseurs connus :
     * {@code caa_release}, {@code caa_release_group}, {@code local}, {@code fanart}.
     */
    public static Path resolveCover(TagInfo ti, File audioFile, CaaClient caa, FanArtClient fanArt) {
        for (String provider : Config.get().coverProviderOrder()) {
            Path cover = tryProvider(provider.trim(), ti, audioFile, caa, fanArt);
            if (cover != null) return cover;
        }
        return null;
    }

    private static Path tryProvider(String provider, TagInfo ti, File audioFile,
                                     CaaClient caa, FanArtClient fanArt) {
        try {
            switch (provider) {
                case "caa_release":
                    if (Config.get().caaReleaseEnabled() && !ti.releaseMbid.isBlank())
                        return caa.downloadFromRelease(ti);
                    return null;
                case "caa_release_group":
                    if (Config.get().caaReleaseGroupEnabled() && !ti.releaseGroupMbid.isBlank())
                        return caa.downloadFromReleaseGroup(ti);
                    return null;
                case "local":
                    if (Config.get().coverSearchLocal())
                        return findLocalCover(audioFile.getParentFile());
                    return null;
                case "fanart":
                    if (Config.get().fanartEnabled() && !ti.artistMbid.isBlank())
                        return fanArt.downloadCover(ti);
                    return null;
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Cherche une pochette dans le dossier : folder.jpg, cover.jpg, front.jpg… */
    public static Path findLocalCover(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        for (String name : new String[]{
                "folder.jpg", "cover.jpg", "front.jpg", "albumart.jpg", "album.jpg",
                "folder.png", "cover.png", "front.png"}) {
            File f = new File(dir, name);
            if (f.exists() && f.length() > 512) return f.toPath();
        }
        return null;
    }

    /** Enregistre un tagging réussi dans le cache pour éviter les re-lookups. */
    public static void recordSuccess(MetadataCache cache, File fichier, TagInfo written) {
        cache.saveTaggingHistory(written);
        cache.recordFileTagging(fichier.getAbsolutePath(), written.recordingMbid);
    }

    /**
     * Translittère l'artiste vers l'alias MusicBrainz dans la locale préférée si son nom n'est
     * pas en écriture latine (ex. cyrillique, japonais, coréen, chinois, arabe…) et que l'option
     * est activée. {@code aliasCache} évite de refaire le lookup réseau pour le même artiste sur
     * plusieurs pistes — passer une {@code ConcurrentHashMap} si l'appelant tourne dans un pool
     * de threads (voir TaggingWorker).
     */
    public static void translateArtist(TagInfo ti, MusicBrainzClient mb, java.util.Map<String, String> aliasCache) {
        if (!Config.get().translateArtists() || ti.artistMbid.isBlank() || !hasNonLatinChars(ti.artist)) return;
        try {
            String alias = aliasCache.computeIfAbsent(ti.artistMbid, mbid -> {
                try { return mb.lookupArtistAlias(mbid, Config.get().translateLocale()); }
                catch (Exception e) { return ""; }
            });
            if (!alias.isBlank()) {
                ti.artist     = alias;
                ti.artistSort = alias;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Retourne true si la chaîne contient un caractère non-latin (japonais, coréen, chinois,
     * arabe, cyrillique, hébreu, thaï…). Les caractères latins de base + latin étendu
     * (accents FR, etc.) passent.
     */
    public static boolean hasNonLatinChars(String s) {
        if (s == null || s.isBlank()) return false;
        return s.codePoints().anyMatch(cp -> {
            if (!Character.isLetter(cp)) return false;
            if (cp <= 0x024F) return false;
            if (cp >= 0x1E00 && cp <= 0x1EFF) return false; // accents vietnamiens etc.
            return true; // cyrillique, grec, arabe, CJK, hangul, kana…
        });
    }
}
