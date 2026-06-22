package com.opentagger;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.util.List;

/**
 * Chaîne de reconnaissance audio pour les fichiers non identifiés par AcoustID/MB.
 *
 * Ordre de tentative :
 *  1. Shazam  — reconnaissance audio via RapidAPI  (clé : rapidapi.key)
 *  2. AudD    — reconnaissance audio directe       (clé : audd.api_token)
 *
 * Après chaque identification réussie :
 *  - Lookup MusicBrainz pour récupérer les IDs complets
 *  - Enrichissement genre via Discogs puis Last.fm (déjà intégrés)
 */
public class AudioRecognitionChain {

    private final MusicBrainzClient mb      = new MusicBrainzClient();
    private final DiscogsClient     discogs = new DiscogsClient();
    private final LastFmClient      lastFm  = new LastFmClient();

    // ── Point d'entrée ────────────────────────────────────────────────────────

    /**
     * Tente de reconnaître le fichier via Shazam → AudD.
     * @return liste de candidats (peut être vide), le premier ayant le score le plus élevé
     */
    public List<TagInfo> recognize(File fichier) {
        // ── 1. Shazam ──────────────────────────────────────────────────────
        if (ShazamClient.isAvailable()) {
            List<TagInfo> r = tryService(() -> new ShazamClient().recognize(fichier),
                    fichier.getName(), "Shazam");
            if (!r.isEmpty()) return r;
        }

        // ── 2. AudD ────────────────────────────────────────────────────────
        if (AudDClient.isAvailable()) {
            List<TagInfo> r = tryService(() -> new AudDClient().recognize(fichier),
                    fichier.getName(), "AudD");
            if (!r.isEmpty()) return r;
        }

        return List.of();
    }

    public static boolean isAnyAvailable() {
        return ShazamClient.isAvailable() || AudDClient.isAvailable();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<TagInfo> tryService(RecognitionSupplier supplier,
            String filename, String serviceName) {
        try {
            TagInfo ti = supplier.get();
            if (ti == null) return List.of();
            return enrichWithMb(ti, serviceName);
        } catch (Exception e) {
            log(serviceName, filename, e.getMessage());
            return List.of();
        }
    }

    /**
     * Enrichit un TagInfo avec MB (IDs) puis Discogs/Last.fm (genre).
     * Si MB confirme avec score ≥ 50, retourne le résultat MB.
     * Sinon retourne le TagInfo du service d'origine.
     */
    private List<TagInfo> enrichWithMb(TagInfo ti, String serviceName) {
        TagInfo result = ti;

        if (!ti.artist.isBlank() || !ti.title.isBlank()) {
            try {
                List<TagInfo> mbResults = mb.searchRecording(ti.artist, ti.title);
                if (!mbResults.isEmpty() && mbResults.get(0).score >= 50) {
                    TagInfo best = mbResults.get(0);
                    if (best.album.isBlank()  && !ti.album.isBlank())  best.album  = ti.album;
                    if (best.year.isBlank()   && !ti.year.isBlank())   best.year   = ti.year;
                    if (best.genre.isBlank()  && !ti.genre.isBlank())  best.genre  = ti.genre;
                    if (best.isrc.isBlank()   && !ti.isrc.isBlank())   best.isrc   = ti.isrc;
                    if (best.track.isBlank()  && !ti.track.isBlank())  best.track  = ti.track;
                    result = best;
                    System.out.printf("[%s → MB] %s — %s (score=%d)%n",
                            serviceName, best.artist, best.title, best.score);
                    // Genre via Discogs puis Last.fm si toujours vide
                    if (result.genre.isBlank()) { try { discogs.enrichGenres(result); } catch (Exception ignored) {} }
                    if (result.genre.isBlank()) { try { lastFm.enrichGenres(result);  } catch (Exception ignored) {} }
                    return mbResults;
                }
            } catch (Exception ignored) {}
        }

        // MB n'a pas confirmé : utiliser le résultat du service seul + enrichir le genre
        System.out.printf("[%s] %s — %s (score=%d, sans MB)%n",
                serviceName, ti.artist, ti.title, ti.score);
        if (result.genre.isBlank()) { try { discogs.enrichGenres(result); } catch (Exception ignored) {} }
        if (result.genre.isBlank()) { try { lastFm.enrichGenres(result);  } catch (Exception ignored) {} }
        return List.of(result);
    }

    private static void log(String service, String file, String msg) {
        System.out.printf("[%s] %s : %s%n", service, file, msg);
    }

    @FunctionalInterface
    private interface RecognitionSupplier {
        TagInfo get() throws Exception;
    }
}
