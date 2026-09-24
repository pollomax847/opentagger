package com.opentagger;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Logique PURE (aucun réseau, aucun disque) de l'audit "l'audio correspond-il aux tags ?" — sépare la
 * décision de la plomberie (empreinte, AcoustID, Shazam, base, fenêtre : voir AudioTagAuditWorker) pour
 * pouvoir la tester sur des cas réels (AudioTagAuditTest).
 *
 * <p>Origine (2026-09-20, l'utilisateur : "l'écoute de l'audio et le titre ne correspondent pas, déjà eu
 * le cas à plusieurs reprises") : mesure en lecture seule sur 250 fichiers pris au hasard parmi ceux que
 * le cache marque "mbid" (tags existants jugés fiables) — 8 (3 %) avaient un audio qui contredisait leurs
 * tags, confirmé par AcoustID ET Shazam ; deux d'entre eux avaient le BON artiste et un titre faux, cas que
 * l'étape 0.5 de TaggingWorker (qui ne compare que l'artiste) laisse passer par construction. Les seuils
 * ci-dessous ont été calibrés sur ces 250 fichiers réels (voir AudioTagAuditTest).
 *
 * <p>Priorité à la PRÉCISION, pas au rappel : chaque suspect coûte de l'attention à l'utilisateur, donc
 * toute ambiguïté (variante de titre, "feat.", qualificatif de version...) tranche en faveur de "OK".
 */
public final class AudioTagAudit {

    private AudioTagAudit() {}

    public enum Verdict {
        /** Titre ET artiste concordent avec ce que l'empreinte identifie. */
        OK,
        /** Même titre, artiste différent — surtout des compilations/mix où l'artiste écrit est le DJ ou
         *  le compilateur (Cut Killer, Don Cannon...) : l'audio est le bon morceau, sévérité faible. */
        ARTIST_DIFF,
        /** Même artiste, TITRE différent — le cas de l'utilisateur (piste 21 "Un verano sin ti" dont
         *  l'audio est "Después de la playa"). */
        TITLE_DIFF,
        /** Ni le titre ni l'artiste ne concordent : c'est un autre morceau. */
        OTHER_TRACK,
        /** AcoustID ne connaît pas l'empreinte (ou correspondance faible) — ni confirmé ni infirmé. */
        UNVERIFIABLE,
        /** Fichier illisible pour fpcalc. */
        ERROR
    }

    /** Un enregistrement renvoyé par AcoustID pour une empreinte (sans appel MusicBrainz). */
    public record Candidate(String artist, String title, double score, int durationSec, String recordingMbid) {}

    public record Comparison(Verdict verdict, Candidate best, double titleSim, double artistSim, String note) {}

    /** En dessous, une correspondance AcoustID est trop incertaine pour accuser le tag (même seuil que
     *  AcoustIdClient.fetchBestFromMusicBrainz() : "confiance excellente"). */
    public static final double STRONG_SCORE = 0.9;
    static final double TITLE_OK   = 0.6;
    static final double ARTIST_OK  = 0.5;
    static final double ARTIST_SAME = 0.6;

    private static final Set<String> GENERIC_ARTISTS = Set.of(
            "", "various artists", "various", "va", "v a", "divers", "compilation", "artistes varies",
            "artistes divers", "varios artistas", "unknown", "unknown artist", "inconnu", "artiste inconnu",
            "no artist", "n a", "verschiedene interpreten", "diversi", "diverse", "artistes varie");

    /** Mots de qualificatif de version retirés avant de comparer deux titres : "X (Radio Edit)" et
     *  "Y (Radio Edit)" ne doivent pas paraître proches juste parce qu'ils partagent "radio edit". */
    private static final Set<String> VERSION_WORDS = Set.of(
            "radio", "edit", "mix", "remix", "version", "remaster", "remastered", "extended", "original",
            "club", "mono", "stereo", "explicit", "feat", "featuring", "ft", "edition", "deluxe", "single",
            "album");

    private static final Pattern BRACKETS  = Pattern.compile("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]");
    private static final Pattern DASH_TAIL = Pattern.compile("\\s+-\\s+.*$");
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*(?:,|;|&|/|\\+|\\bfeat\\.?(?=\\s)|\\bfeaturing\\b|\\bft\\.?(?=\\s)|\\bwith\\b|\\band\\b|\\bet\\b|\\bx\\b|\\bvs\\.?(?=\\s))\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── Décision ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Compare les tags d'un fichier aux enregistrements qu'AcoustID associe à son empreinte.
     * Seuls les candidats à score ≥ {@link #STRONG_SCORE} comptent ; parmi eux, on retient celui qui
     * ressemble le plus aux tags (une empreinte renvoie souvent plusieurs enregistrements — versions,
     * rééditions — dont un seul suffit à innocenter le tag).
     */
    public static Comparison compare(String tagArtist, String tagTitle, List<Candidate> candidates) {
        if (isBlank(tagTitle) && isBlank(tagArtist))
            return new Comparison(Verdict.UNVERIFIABLE, null, 0, 0, "fichier sans artiste ni titre");
        List<Candidate> strong = new ArrayList<>();
        boolean anyWeak = false;
        if (candidates != null) {
            for (Candidate c : candidates) {
                if (c == null || isBlank(c.title())) continue;
                if (c.score() >= STRONG_SCORE) strong.add(c); else anyWeak = true;
            }
        }
        if (strong.isEmpty())
            return new Comparison(Verdict.UNVERIFIABLE, null, 0, 0,
                    anyWeak ? "correspondance AcoustID trop faible" : "empreinte inconnue d'AcoustID");

        Candidate best = null;
        double bestT = -1, bestA = -1, bestSum = -1;
        for (Candidate c : strong) {
            double t = titleSimilarity(tagTitle, c.title());
            double a = artistSimilarity(tagArtist, c.artist());
            double sum = t + a + c.score() * 0.001;      // le score n'arbitre que les égalités
            if (sum > bestSum) { bestSum = sum; best = c; bestT = t; bestA = a; }
        }

        boolean titleOk  = bestT >= TITLE_OK;
        boolean artistOk = bestA >= ARTIST_OK;
        Verdict v;
        if (titleOk && artistOk)   v = Verdict.OK;
        else if (titleOk)          v = Verdict.ARTIST_DIFF;
        else if (bestA >= ARTIST_SAME) v = Verdict.TITLE_DIFF;
        else                       v = Verdict.OTHER_TRACK;
        return new Comparison(v, best, bestT, bestA, "");
    }

    /** Verdicts qui méritent l'attention de l'utilisateur (tout sauf OK/UNVERIFIABLE/ERROR). */
    public static boolean isSuspect(Verdict v) {
        return v == Verdict.ARTIST_DIFF || v == Verdict.TITLE_DIFF || v == Verdict.OTHER_TRACK;
    }

    /** Verdicts assez graves pour justifier un second avis (Shazam) — pas ARTIST_DIFF (l'audio est le bon
     *  morceau, l'appel serait du gaspillage : SongRec = jusqu'à 4 allers-retours réseau par fichier). */
    public static boolean needsSecondOpinion(Verdict v) {
        return v == Verdict.TITLE_DIFF || v == Verdict.OTHER_TRACK;
    }

    // ── Similarités ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Similarité de titres 0..1, tolérante : maximum sur les variantes {complet, sans parenthèses, sans
     * suffixe " - Remastered 2011"} × {avec/sans mots de version}. Utilise l'algorithme de Picard déjà
     * porté dans {@link TrackMatcher} (mot à mot, Levenshtein) plutôt qu'un second algorithme maison.
     */
    public static double titleSimilarity(String a, String b) {
        double best = 0;
        for (String x : titleVariants(a)) {
            for (String y : titleVariants(b)) {
                best = Math.max(best, TrackMatcher.titleSimilarity(x, y));
                if (best >= 1.0) return 1.0;
            }
        }
        return best;
    }

    /**
     * Similarité d'artistes 0..1 : maximum sur (chaîne entière) et (chaque artiste pris séparément après
     * découpage sur ", & feat. x with…"). Un côté générique ("Various Artists", "Inconnu", vide) ne
     * peut rien contredire : renvoie 1.0.
     */
    public static double artistSimilarity(String tagArtist, String audioArtist) {
        if (isGenericArtist(tagArtist) || isGenericArtist(audioArtist)) return 1.0;
        List<String> ta = artistTokens(tagArtist);
        List<String> aa = artistTokens(audioArtist);
        double best = 0;
        for (String x : ta) for (String y : aa) {
            best = Math.max(best, TrackMatcher.titleSimilarity(x, y));
            if (best >= 1.0) return 1.0;
        }
        return best;
    }

    public static boolean isGenericArtist(String artist) {
        return GENERIC_ARTISTS.contains(fold(artist));
    }

    // ── Normalisation ───────────────────────────────────────────────────────────────────────────────

    private static List<String> titleVariants(String s) {
        List<String> out = new ArrayList<>(4);
        String base = s == null ? "" : s;
        String noDash = DASH_TAIL.matcher(base).replaceAll("");
        for (String v : new String[]{base, BRACKETS.matcher(base).replaceAll(" "),
                                     noDash, BRACKETS.matcher(noDash).replaceAll(" ")}) {
            String full = fold(v);
            if (!full.isEmpty() && !out.contains(full)) out.add(full);
            String core = stripVersionWords(full);
            if (!core.isEmpty() && !out.contains(core)) out.add(core);
        }
        return out;
    }

    private static String stripVersionWords(String folded) {
        StringBuilder sb = new StringBuilder();
        for (String w : folded.split(" ")) {
            if (w.isEmpty() || VERSION_WORDS.contains(w)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString();
    }

    private static List<String> artistTokens(String artist) {
        List<String> out = new ArrayList<>();
        String whole = cleanArtist(fold(artist));
        if (!whole.isEmpty()) out.add(whole);
        if (artist != null) {
            for (String part : ARTIST_SPLIT.split(artist)) {
                String f = cleanArtist(fold(part));
                if (!f.isEmpty() && !out.contains(f)) out.add(f);
            }
        }
        return out;
    }

    /** "The Beatles" = "Beatles" ; "AmerieVEVO" / "Amerie - Topic" (noms de chaînes YouTube, très
     *  fréquents dans les rips) = "Amerie" — vu en direct sur l'échantillon réel du 2026-09-20. */
    private static String cleanArtist(String folded) {
        String s = folded.replaceAll("\\b(topic|official)\\b", " ").replaceAll("vevo$", "").replaceAll("\\s+", " ").trim();
        return s.startsWith("the ") ? s.substring(4) : s;
    }

    /** Minuscules, sans diacritiques, lettres/chiffres Unicode seulement (le CJK/cyrillique est conservé),
     *  espaces simples. */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return n;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
