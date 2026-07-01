package com.opentagger;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.util.List;

/**
 * Chaîne de reconnaissance audio pour les fichiers non identifiés par MB.
 *
 * Étape 1 — Reconnaissance audio (empreinte Shazam) :
 *   1a. SongRec — client Shazam open-source, sans clé API (binaire : songrec)
 *   1b. AudD    — algorithme différent, en fallback        (clé : audd.api_token)
 *
 * Étape 2 — Complétion MusicBrainz :
 *   SongRec/AudD fournissent titre + artiste.
 *   MB ajoute : MBID, album complet, track#, disc#, albumArtist, année précise,
 *               langue, pays, ISRC, etc.
 *   Les champs MB manquants sont comblés par les données SongRec/AudD.
 */
public class AudioRecognitionChain {

    private final MusicBrainzClient mb      = new MusicBrainzClient();
    private final SongRecClient     songRec = new SongRecClient();
    private final DiscogsClient     discogs = new DiscogsClient();
    private final LastFmClient      lastFm  = new LastFmClient();
    private final AudDClient        audd    = new AudDClient();

    /**
     * Tente de reconnaître le fichier via SongRec → AudD,
     * puis enrichit le résultat avec MusicBrainz.
     */
    public List<TagInfo> recognize(File fichier) {
        // ── 1a. SongRec (empreinte Shazam gratuite) ──────────────────────
        if (SongRecClient.isAvailable()) {
            List<TagInfo> r = tryService(() -> songRec.recognize(fichier),
                    fichier.getName(), "SongRec");
            if (!r.isEmpty()) return r;
        }

        // ── 1b. AudD (algorithme différent, en fallback) ─────────────────
        if (AudDClient.isAvailable()) {
            List<TagInfo> r = tryService(() -> audd.recognize(fichier),
                    fichier.getName(), "AudD");
            if (!r.isEmpty()) return r;
        }

        return List.of();
    }

    public static boolean isAnyAvailable() {
        return SongRecClient.isAvailable() || AudDClient.isAvailable();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<TagInfo> tryService(RecognitionSupplier supplier,
            String filename, String serviceName) {
        try {
            TagInfo sr = supplier.get();
            if (sr == null) return List.of();
            return completeWithMb(sr, serviceName);
        } catch (Exception e) {
            System.out.printf("[%s] %s : erreur — %s%n", serviceName, filename, e.getMessage());
            return List.of();
        }
    }

    /**
     * Étape 2 : MB complète ce que SongRec/AudD a trouvé.
     *
     * SongRec fournit : titre, artiste, genre, album, année (données Shazam).
     * MB ajoute       : recordingMbid, releaseMbid, releaseGroupMbid, artistMbid,
     *                   albumArtist, artistSort, track#, disc#, langue, pays, ISRC.
     * Les champs que MB ne trouve pas sont conservés depuis SongRec.
     */
    private List<TagInfo> completeWithMb(TagInfo sr, String serviceName) {
        System.out.printf("[%s] identifié : %s — %s%n", serviceName, sr.artist, sr.title);

        if (!sr.artist.isBlank() && !sr.title.isBlank()) {
            try {
                List<TagInfo> mbResults = mb.searchRecording(sr.artist, sr.title);
                if (!mbResults.isEmpty() && mbResults.get(0).score >= 50) {
                    TagInfo mbr = mbResults.get(0);

                    // MB est la source principale (IDs, structure album, métadonnées normalisées)
                    // SongRec comble ce que MB n'a pas
                    if (mbr.album.isBlank()   && !sr.album.isBlank())   mbr.album   = sr.album;
                    if (mbr.year.isBlank()    && !sr.year.isBlank())    mbr.year    = sr.year;
                    if (mbr.genre.isBlank()   && !sr.genre.isBlank())   mbr.genre   = sr.genre;
                    if (mbr.isrc.isBlank()    && !sr.isrc.isBlank())    mbr.isrc    = sr.isrc;
                    if (mbr.track.isBlank()   && !sr.track.isBlank())   mbr.track   = sr.track;
                    if (mbr.comment.isBlank() && !sr.comment.isBlank()) mbr.comment = sr.comment;

                    mbr.score = 90;
                    System.out.printf("[%s → MB] %s — %s [%s] (score=%d)%n",
                            serviceName, mbr.artist, mbr.title, mbr.album, mbr.score);

                    if (mbr.genre.isBlank()) { try { discogs.enrichGenres(mbr); } catch (Exception ignored) {} }
                    if (mbr.genre.isBlank()) { try { lastFm.enrichGenres(mbr);  } catch (Exception ignored) {} }
                    return mbResults;
                }
            } catch (Exception ignored) {}
        }

        // MB n'a rien trouvé : on garde les données SongRec/AudD telles quelles
        sr.score = 85;
        System.out.printf("[%s] %s — %s (MB non trouvé, données audio conservées)%n",
                serviceName, sr.artist, sr.title);
        if (sr.genre.isBlank()) { try { discogs.enrichGenres(sr); } catch (Exception ignored) {} }
        if (sr.genre.isBlank()) { try { lastFm.enrichGenres(sr);  } catch (Exception ignored) {} }
        return List.of(sr);
    }

    @FunctionalInterface
    private interface RecognitionSupplier {
        TagInfo get() throws Exception;
    }
}
