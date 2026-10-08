package com.opentagger;

import java.util.List;

/**
 * Classement d'une humeur (mood) à partir d'une liste de tags/genres bruts en texte libre —
 * extrait de LastFmClient (2026-09-13) pour être réutilisable par MusicBrainz et Discogs, qui
 * exposent tous les deux le même genre de folksonomie (tags MB, genre/style Discogs) mais
 * n'avaient jusqu'ici aucun moyen d'alimenter le mood : seul Last.fm le faisait, alors que
 * l'utilisateur a fait remarquer à juste titre que ce n'est pas le seul service à porter cette
 * information. Les deux autres n'ajoutent AUCUN appel réseau supplémentaire : ils réutilisent les
 * tags/genres déjà récupérés pour le genre, jamais un fetch dédié comme le fait Last.fm.
 */
public final class MoodClassifier {

    private MoodClassifier() {}

    private static final String[][] MOOD_MAP = {
        {"happy", "upbeat", "feel good", "feel-good", "joyful", "cheerful", "fun", "positive"},
        {"sad", "melancholic", "melancholy", "depressing", "heartbreak", "emotional", "tearjerker"},
        {"chill", "chillout", "relax", "relaxed", "calm", "peaceful", "soothing", "mellow", "laid back"},
        {"energetic", "energy", "pump up", "adrenaline", "workout", "running", "power"},
        {"aggressive", "angry", "rage", "intense", "harsh"},
        {"romantic", "love", "romance", "sensual"},
        {"party", "dance", "danceable", "club", "rave"},
        {"dark", "haunting", "gloomy", "atmospheric", "noir"},
        {"acoustic", "unplugged", "folk acoustic"},
        {"instrumental", "no vocals"},
    };
    private static final String[] MOOD_LABELS = {
        "Happy", "Sad", "Relaxed", "Energetic", "Aggressive",
        "Romantic", "Party", "Dark", "Acoustic", "Instrumental"
    };

    /** @return le premier label reconnu parmi les tags donnés (déjà en minuscules ou non — la
     *  comparaison est insensible à la casse), ou {@code ""} si aucun ne correspond. */
    public static String classify(List<String> tags) {
        if (tags == null) return "";
        for (String raw : tags) {
            if (raw == null) continue;
            String t = raw.toLowerCase().trim();
            for (int i = 0; i < MOOD_MAP.length; i++) {
                for (String kw : MOOD_MAP[i]) {
                    if (t.contains(kw)) return MOOD_LABELS[i];
                }
            }
        }
        return "";
    }

    /** @return true si ce tag signale une piste instrumentale — même liste que le mood "Instrumental"
     *  ci-dessus mais utilisé séparément par LastFmClient.enrichMood() pour peupler isInstrumental. */
    public static boolean isInstrumentalTag(String tag) {
        if (tag == null) return false;
        String t = tag.toLowerCase().trim();
        return t.equals("instrumental") || t.equals("no vocals");
    }
}
