package com.opentagger;

import java.io.File;

/**
 * Dit si deux fichiers contiennent le MÊME AUDIO, d'après leurs empreintes Chromaprint brutes — sans se fier au nom du fichier ni à
 * ses tags, qui peuvent être faux. Deux encodages d'une même chanson (autre débit, autre format, léger décalage au début) ont des
 * empreintes très proches ; deux chansons différentes en sont à environ la moitié de bits différents.
 *
 * <p>SongRec ne convient pas ici : il dit QUELLE chanson c'est (en interrogeant Shazam), pas si deux fichiers sont le même audio, et
 * il n'existe pas sous Windows.
 */
public final class AudioSimilarity {
    private AudioSimilarity() {}

    /** Part maximale de bits différents pour parler du même audio (même chanson ré-encodée : ~0,05 à 0,2 ; deux chansons : ~0,5). */
    static final double MAX_BIT_ERROR = 0.30;
    /** Décalage de départ toléré entre les deux empreintes (≈ 8 entiers par seconde : 60 ≈ 7 s). */
    static final int MAX_SHIFT = 60;
    /** Chevauchement minimal comparé (≈ 30 s) ; en dessous, on ne conclut pas. */
    static final int MIN_OVERLAP = 240;
    /** Écart de durée maximal entre les deux fichiers (secondes) : une version radio n'est pas le même fichier que l'album. */
    static final double MAX_DURATION_GAP_SEC = 3.0;

    /** Part de bits différents entre {@code a[i]} et {@code b[i + shift]} sur leur chevauchement ; 1,0 si trop court pour conclure. */
    static double bitErrorRate(int[] a, int[] b, int shift) {
        int start = Math.max(0, -shift);
        int end = Math.min(a.length, b.length - shift);
        int overlap = end - start;
        if (overlap < MIN_OVERLAP) return 1.0;
        long diff = 0;
        for (int i = start; i < end; i++) diff += Integer.bitCount(a[i] ^ b[i + shift]);
        return diff / (32.0 * overlap);
    }

    /** Meilleur taux d'erreur sur tous les décalages tolérés. */
    static double bestBitErrorRate(int[] a, int[] b) {
        double best = 1.0;
        for (int shift = -MAX_SHIFT; shift <= MAX_SHIFT; shift++) best = Math.min(best, bitErrorRate(a, b, shift));
        return best;
    }

    /** Même audio : durées voisines ET empreintes proches. */
    public static boolean sameAudio(Fingerprinter.Raw a, Fingerprinter.Raw b) {
        if (a == null || b == null) return false;
        if (a.durationSec() > 0 && b.durationSec() > 0 && Math.abs(a.durationSec() - b.durationSec()) > MAX_DURATION_GAP_SEC) return false;
        return bestBitErrorRate(a.ints(), b.ints()) <= MAX_BIT_ERROR;
    }

    /**
     * Compare deux fichiers.
     * @return {@code true}/{@code false} s'ils ont été analysés, {@code null} si l'analyse est impossible (fpcalc absent, fichier
     *         illisible) : l'appelant ne doit alors RIEN conclure.
     */
    public static Boolean sameAudio(File x, File y) {
        try {
            return sameAudio(Fingerprinter.computeRaw(x), Fingerprinter.computeRaw(y));
        } catch (Exception e) {
            return null;
        }
    }
}
