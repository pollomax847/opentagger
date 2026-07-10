package com.opentagger;

import com.opentagger.model.TagInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Appariement fichier↔piste par score pondéré — port fidèle du vrai algorithme de MusicBrainz
 * Picard (dépôt {@code metabrainz/picard}, branche {@code master}, consulté le 2026-07-09),
 * PAS une réinvention. Remplace la cascade de paliers indépendants (n°piste+disque → durée →
 * titre) qui existait en 3 copies légèrement différentes ({@code TaggingWorker.matchFileToTrack},
 * {@code AlbumClusterWorker.findBestTrack}) par UN score composite unique combinant tous les
 * signaux à la fois — Picard calcule ce score pour CHAQUE piste candidate et prend la meilleure,
 * il ne s'arrête jamais au premier palier qui "répond".
 *
 * Poids (identiques à {@code picard/metadata.py: Metadata.__weights} + le poids "length" qui y
 * est ajouté séparément) : titre=22, durée=8, artiste=6, n°piste=6, total pistes=5, n°disque=5,
 * total disques=4. Le champ "album" de Picard (poids 12) est délibérément omis ici : au sein
 * d'une tracklist déjà choisie par {@code searchBestRelease}/{@code findBestRelease}, l'album est
 * constant pour toutes les pistes candidates et n'influence donc jamais le classement relatif.
 *
 * Chaque champ n'entre dans la moyenne pondérée que si les DEUX côtés sont renseignés (même règle
 * que {@code Metadata.compare()} : {@code if a and b}) — un champ absent est ignoré, pas pénalisé.
 * Seuil d'acceptation par défaut : 0.4, identique à Picard ({@code picard/options.py:
 * track_matching_threshold}) — voir {@link Config#trackMatchingThreshold()}.
 */
public final class TrackMatcher {

    private TrackMatcher() {}

    private static final int LENGTH_SCORE_THRESHOLD_MS = 30_000;

    // Python re.UNICODE (implicite en Python 3) : \W doit être Unicode-aware pour ne pas couper
    // les caractères accentués français au milieu d'un mot — UNICODE_CHARACTER_CLASS est
    // l'équivalent Java, sans quoi \W ne reconnaîtrait que l'ASCII.
    private static final Pattern SPLIT_WORDS = Pattern.compile("\\W+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Similarité de deux mots par distance de Levenshtein normalisée — port fidèle de
     * {@code picard/util/astrcmp.py:astrcmp_py} (le fallback pur Python documenté comme référence
     * de l'algorithme, la version C fait la même chose plus vite avec un bonus transposition).
     * Retourne 0.0 si l'un des deux mots est vide (y compris si les deux le sont — comportement
     * volontairement repris tel quel de Picard, pas un oubli).
     */
    public static double wordSimilarity(String a, String b) {
        int n = a.length(), m = b.length();
        if (n == 0 || m == 0) return 0.0;

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char bi = b.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int add = prev[j] + 1;
                int del = curr[j - 1] + 1;
                int change = prev[j - 1] + (a.charAt(j - 1) == bi ? 0 : 1);
                curr[j] = Math.min(Math.min(add, del), change);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return 1.0 - (double) prev[n] / Math.max(m, n);
    }

    /**
     * Similarité de deux chaînes multi-mots — port fidèle de {@code picard/similarity.py:
     * similarity2}. Découpe les deux chaînes en mots, apparie chaque mot du côté le plus court au
     * mot le plus proche du côté le plus long (glouton : le meilleur d'abord, retiré de la liste
     * s'il dépasse 0.6 pour ne pas être réutilisé), puis divise la somme des meilleurs scores par
     * la taille du côté court + 0.4 fois les mots du côté long restés non appariés (pénalise les
     * mots en trop sans les traiter comme une correspondance totale ratée).
     */
    public static double titleSimilarity(String a, String b) {
        if (a == null || a.isEmpty() || b == null || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;

        List<String> aWords = splitWords(a.toLowerCase());
        List<String> bWords = splitWords(b.toLowerCase());
        if (aWords.isEmpty() || bWords.isEmpty()) return 0.0;

        List<String> shorter, longer;
        if (aWords.size() > bWords.size()) { shorter = aWords; longer = bWords; }
        else { shorter = bWords; longer = aWords; }
        // Le côté le plus court doit être `shorter` : au-dessus on assigne par taille, pas par
        // provenance — si aWords est le plus long, il faut l'échanger avec longer.
        if (shorter.size() > longer.size()) { List<String> t = shorter; shorter = longer; longer = t; }
        longer = new ArrayList<>(longer); // mutable : des mots en sont retirés au fil de l'appariement

        double score = 0.0;
        for (String sv : shorter) {
            double bestScore = 0.0;
            int bestPos = -1;
            for (int i = 0; i < longer.size(); i++) {
                double s = wordSimilarity(sv, longer.get(i));
                if (s > bestScore) { bestScore = s; bestPos = i; }
            }
            if (bestPos != -1) {
                score += bestScore;
                if (bestScore > 0.6) longer.remove(bestPos);
            }
        }
        return score / (shorter.size() + longer.size() * 0.4);
    }

    private static List<String> splitWords(String s) {
        List<String> out = new ArrayList<>();
        for (String w : SPLIT_WORDS.split(s)) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /**
     * Score de durée — port fidèle de {@code picard/matching.py:length_score}. Dégradé LINÉAIRE
     * et continu jusqu'à 30 secondes d'écart (jamais un seuil binaire dur comme l'ancien ±3s) :
     * 0s d'écart → 1.0, 15s → 0.5, 30s ou plus → 0.0.
     */
    public static double lengthScore(int msA, int msB) {
        return 1.0 - Math.min(Math.abs(msA - msB), LENGTH_SCORE_THRESHOLD_MS) / (double) LENGTH_SCORE_THRESHOLD_MS;
    }

    /**
     * Score composite pondéré d'une piste candidate face aux tags du fichier — port fidèle de
     * {@code picard/metadata.py: Metadata.compare()} (poids : voir la Javadoc de la classe).
     * releaseDiscTotal : nombre total de disques de la release (pas porté par {@link
     * MusicBrainzClient.ReleaseTrack} lui-même, qui ne connaît que son propre n° de disque) —
     * passer 0 si inconnu, le champ est alors simplement ignoré comme les autres champs absents.
     */
    public static double scoreTrack(TagInfo file, MusicBrainzClient.ReleaseTrack candidate,
                                     int fileDurationMs, int releaseDiscTotal) {
        List<double[]> parts = new ArrayList<>();

        if (fileDurationMs > 0 && candidate.lengthMs() > 0)
            parts.add(new double[]{ lengthScore(fileDurationMs, candidate.lengthMs()), 8 });

        if (!isBlank(file.title) && !isBlank(candidate.title()))
            parts.add(new double[]{ titleSimilarity(file.title, candidate.title()), 22 });

        if (!isBlank(file.artist) && !isBlank(candidate.artist()))
            parts.add(new double[]{ titleSimilarity(file.artist, candidate.artist()), 6 });

        Integer fTrack = parseIntOrNull(file.track);
        if (fTrack != null && candidate.trackNo() > 0)
            parts.add(new double[]{ fTrack == candidate.trackNo() ? 1.0 : 0.0, 6 });

        Integer fTrackTotal = parseIntOrNull(file.trackTotal);
        if (fTrackTotal != null && candidate.trackTotal() > 0)
            parts.add(new double[]{ fTrackTotal == candidate.trackTotal() ? 1.0 : 0.0, 5 });

        Integer fDisc = parseIntOrNull(file.discNo);
        if (fDisc != null && candidate.disc() > 0)
            parts.add(new double[]{ fDisc == candidate.disc() ? 1.0 : 0.0, 5 });

        Integer fDiscTotal = parseIntOrNull(file.discTotal);
        if (fDiscTotal != null && releaseDiscTotal > 0)
            parts.add(new double[]{ fDiscTotal == releaseDiscTotal ? 1.0 : 0.0, 4 });

        return linearCombination(parts);
    }

    /**
     * Cherche la piste candidate au score le plus élevé — remplace "premier palier qui répond"
     * par un vrai argmax sur toutes les candidates, comme Picard. Retourne null si la meilleure
     * candidate n'atteint pas le seuil (aucun match plutôt qu'une supposition peu fiable).
     */
    public static MusicBrainzClient.ReleaseTrack findBestTrack(
            TagInfo file, List<MusicBrainzClient.ReleaseTrack> candidates,
            int fileDurationMs, double threshold) {
        if (candidates == null || candidates.isEmpty()) return null;

        int releaseDiscTotal = candidates.stream()
                .mapToInt(MusicBrainzClient.ReleaseTrack::disc).max().orElse(0);

        MusicBrainzClient.ReleaseTrack best = null;
        double bestScore = -1.0;
        for (MusicBrainzClient.ReleaseTrack t : candidates) {
            double s = scoreTrack(file, t, fileDurationMs, releaseDiscTotal);
            if (s > bestScore) { bestScore = s; best = t; }
        }
        return bestScore >= threshold ? best : null;
    }

    /** Port fidèle de {@code picard/util/__init__.py:linear_combination_of_weights}. Visibilité
     *  package (pas private) : réutilisé tel quel par {@link ReleaseMatcher}. */
    static double linearCombination(List<double[]> parts) {
        double total = 0, sumOfProducts = 0;
        for (double[] p : parts) { total += p[1]; sumOfProducts += p[0] * p[1]; }
        return total == 0.0 ? 0.0 : sumOfProducts / total;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim().replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return null; }
    }
}
