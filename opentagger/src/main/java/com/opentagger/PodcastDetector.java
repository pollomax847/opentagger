package com.opentagger;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Dit si un fichier que l'identification musicale n'a pas reconnu ressemble à un épisode de podcast, d'après plusieurs signaux. Ne
 * tague jamais : il SUGGÈRE (« podcast probable ») et l'utilisateur confirme dans le dialogue « Tagger comme podcast… », parce qu'une
 * longue durée seule peut aussi être un mix DJ ou un set live.
 */
public final class PodcastDetector {
    private PodcastDetector() {}

    /** Score à atteindre pour dire « probable ». */
    static final int THRESHOLD = 5;
    /** Durée à partir de laquelle un fichier est « long » (secondes) : 25 min, puis 45 min. */
    static final int LONG_SEC = 25 * 60, VERY_LONG_SEC = 45 * 60;

    public record Verdict(boolean probable, int score, List<String> reasons) {}

    private static final Pattern EPISODE_NAME = Pattern.compile(
            "(?i)(\\bepisode\\b|\\bépisode\\b|\\bep\\.?\\s*\\d{1,4}\\b|\\bs\\d{1,2}\\s?e\\d{1,3}\\b|#\\s?\\d{1,4}\\b|\\b\\d{4}[-_.]\\d{2}[-_.]\\d{2}\\b"
            + "|\\b(vol|volume|n°|no|num|numéro)\\.?\\s?\\d{1,4}\\b)");
    /** Mots qui désignent presque toujours de la musique mixée plutôt qu'un podcast. */
    private static final Pattern MIX_NAME = Pattern.compile("(?i)\\b(mix|megamix|mégamix|dj\\s?set|live\\s?set|mashup|remix|bootleg|non[- ]?stop)\\b");

    private static String fold(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static boolean has(String s) { return s != null && !s.isBlank(); }

    /**
     * @param genre       tag genre du fichier
     * @param podcastUrl  URL de flux déjà présente dans les tags (vide sinon)
     * @param season      numéro de saison déjà présent (vide sinon)
     * @param episode     numéro d'épisode déjà présent (vide sinon)
     * @param durationSec durée du fichier (0 si inconnue)
     * @param fileName    nom du fichier
     * @param folderName  nom du dossier qui le contient
     */
    public static Verdict assess(String genre, String podcastUrl, String season, String episode, int durationSec,
                                 String fileName, String folderName) {
        int score = 0;
        List<String> why = new ArrayList<>();

        if (has(podcastUrl)) { score += 5; why.add("flux de podcast dans les tags"); }
        if (has(season) || has(episode)) { score += 3; why.add("saison/épisode dans les tags"); }
        if (fold(genre).contains("podcast")) { score += 4; why.add("genre « Podcast »"); }
        if (fold(folderName).contains("podcast")) { score += 3; why.add("dossier « Podcasts »"); }
        if (fileName != null && EPISODE_NAME.matcher(fileName).find()) { score += 2; why.add("nom de type épisode"); }
        if (durationSec >= VERY_LONG_SEC) { score += 3; why.add("très long (" + durationSec / 60 + " min)"); }
        else if (durationSec >= LONG_SEC) { score += 2; why.add("long (" + durationSec / 60 + " min)"); }
        // Une émission dure souvent une heure pleine (55 à 62 min) : indice faible, jamais suffisant seul.
        if (durationSec >= 55 * 60 && durationSec <= 62 * 60) { score += 1; why.add("durée d'une émission d'une heure"); }
        if (fileName != null && MIX_NAME.matcher(fileName).find()) { score -= 3; why.add("nom de mix DJ (pénalité)"); }

        return new Verdict(score >= THRESHOLD, score, why);
    }
}
