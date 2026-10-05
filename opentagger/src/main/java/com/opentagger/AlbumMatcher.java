package com.opentagger;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Choisit la release MusicBrainz qui décrit un dossier ENTIER, au lieu de voter fichier par fichier.
 *
 * <p>Principe : une release n'est retenue que si elle <b>explique tous les fichiers</b> du dossier (chaque fichier trouve sa piste,
 * une piste ne sert qu'une fois). Les copies d'un même morceau dans le dossier partagent une seule place et ne bloquent donc pas
 * l'appariement. Parmi les releases qui expliquent tout, on départage par la cohérence avec l'album annoncé par les tags, le type de
 * parution, le nombre de pistes en trop, puis l'année.
 *
 * <p>Écrit de façon indépendante : la logique est décrite ici, les seuils sont les nôtres et se règlent sur les fichiers réels.
 */
public final class AlbumMatcher {
    private AlbumMatcher() {}

    /** Un fichier du dossier, avec ce qu'on sait de lui (une valeur absente = 0 ou chaîne vide). */
    public record Item(String id, String title, String artist, int durationSec, int trackNo, int discNo, String recordingMbid) {}

    /** Résultat : la release retenue et la piste associée à chaque fichier (copies comprises). */
    public record Match(MusicBrainzClient.ReleaseTracklist release, Map<String, MusicBrainzClient.ReleaseTrack> trackByItemId,
                        int slots, int extraTracks) {}

    /** Deux fichiers sont des copies du même morceau : titres quasi identiques et durées à 2 s près. */
    static final double DUPLICATE_TITLE_SIMILARITY = 0.90;
    static final int DUPLICATE_DURATION_SEC = 2;
    /** Appariement titre ↔ piste : similarité minimale avec une durée compatible, ou plus stricte sans durée. */
    static final double PAIR_TITLE_SIMILARITY = 0.85;
    static final double PAIR_TITLE_SIMILARITY_NO_DURATION = 0.95;
    static final int PAIR_DURATION_SEC = 3;
    /** Dernier recours : durée seule, à 2 s près, si une seule piste libre convient. */
    static final int LAST_RESORT_DURATION_SEC = 2;

    // ── Normalisation ─────────────────────────────────────────────────────────────────────────────

    public static String norm(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Titre sans ce qui est entre parenthèses ou crochets (« (feat. X) », « [Remastered] »). */
    static String withoutBrackets(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ").trim();
    }

    /** Similarité stricte : les parenthèses comptent (« (live) », « (Deluxe) » changent de morceau ou d'édition). */
    static double plainSimilarity(String a, String b) {
        String na = norm(a), nb = norm(b);
        if (na.isEmpty() || nb.isEmpty()) return 0.0;
        return TrackMatcher.titleSimilarity(na, nb);
    }

    /** Similarité indulgente : ignore aussi ce qui est entre parenthèses (« (feat. X) », « [Remastered] »). À n'employer que
     *  lorsqu'une durée compatible garde-fou le rapprochement. */
    static double lenientSimilarity(String a, String b) {
        double plain = plainSimilarity(a, b);
        String ba = norm(withoutBrackets(a)), bb = norm(withoutBrackets(b));
        if (ba.isEmpty() || bb.isEmpty()) return plain;
        return Math.max(plain, TrackMatcher.titleSimilarity(ba, bb));
    }

    static double titleSimilarity(String a, String b) { return lenientSimilarity(a, b); }

    // ── Copies du même morceau ────────────────────────────────────────────────────────────────────

    /** Regroupe les fichiers qui sont des copies du même morceau ; chaque groupe occupe UNE place. */
    static List<List<Item>> dedupe(List<Item> items) {
        List<List<Item>> slots = new ArrayList<>();
        for (Item it : items) {
            List<Item> home = null;
            for (List<Item> slot : slots) {
                if (sameSong(slot.get(0), it)) { home = slot; break; }
            }
            if (home == null) { home = new ArrayList<>(); slots.add(home); }
            home.add(it);
        }
        return slots;
    }

    private static boolean sameSong(Item a, Item b) {
        if (notBlank(a.recordingMbid()) && notBlank(b.recordingMbid())) return a.recordingMbid().equalsIgnoreCase(b.recordingMbid());
        boolean durKnown = a.durationSec() > 0 && b.durationSec() > 0;
        if (durKnown && Math.abs(a.durationSec() - b.durationSec()) > DUPLICATE_DURATION_SEC) return false;
        // Sans durée pour garantir, seul un titre identique compte (« (live) » ne doit pas fondre deux versions en une).
        return durKnown ? lenientSimilarity(a.title(), b.title()) >= DUPLICATE_TITLE_SIMILARITY
                        : plainSimilarity(a.title(), b.title()) >= 1.0;
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ── Appariement d'un dossier avec UNE release ─────────────────────────────────────────────────

    private record Pair(int slot, int track, double score) {}

    /** Associe chaque place à une piste de {@code rel} ; {@code null} si une place reste sans piste. */
    static Map<Integer, MusicBrainzClient.ReleaseTrack> pairWithRelease(List<List<Item>> slots, MusicBrainzClient.ReleaseTracklist rel) {
        List<MusicBrainzClient.ReleaseTrack> tracks = rel.tracks();
        if (tracks == null || tracks.size() < slots.size()) return null;   // une release plus courte que le dossier ne peut pas tout expliquer

        List<Pair> eligible = new ArrayList<>();
        for (int s = 0; s < slots.size(); s++) {
            Item it = slots.get(s).get(0);
            for (int t = 0; t < tracks.size(); t++) {
                double sc = pairScore(it, tracks.get(t));
                if (sc > 0) eligible.add(new Pair(s, t, sc));
            }
        }
        eligible.sort(Comparator.comparingDouble(Pair::score).reversed());

        Map<Integer, MusicBrainzClient.ReleaseTrack> out = new HashMap<>();
        boolean[] trackUsed = new boolean[tracks.size()];
        for (Pair p : eligible) {
            if (out.containsKey(p.slot()) || trackUsed[p.track()]) continue;
            out.put(p.slot(), tracks.get(p.track()));
            trackUsed[p.track()] = true;
        }

        // Dernier recours : durée seule, si UNE seule piste libre convient et que l'artiste n'est pas contradictoire.
        for (int s = 0; s < slots.size(); s++) {
            if (out.containsKey(s)) continue;
            Item it = slots.get(s).get(0);
            if (it.durationSec() <= 0) continue;
            int found = -1, count = 0;
            for (int t = 0; t < tracks.size(); t++) {
                if (trackUsed[t]) continue;
                int len = tracks.get(t).lengthMs() / 1000;
                if (len > 0 && Math.abs(len - it.durationSec()) <= LAST_RESORT_DURATION_SEC && artistCompatible(it, tracks.get(t))) { found = t; count++; }
            }
            if (count == 1) { out.put(s, tracks.get(found)); trackUsed[found] = true; }
        }
        return out.size() == slots.size() ? out : null;
    }

    /** Score d'un couple fichier ↔ piste, 0 s'ils ne vont pas ensemble. */
    static double pairScore(Item it, MusicBrainzClient.ReleaseTrack t) {
        if (notBlank(it.recordingMbid()) && notBlank(t.recordingMbid()) && it.recordingMbid().equalsIgnoreCase(t.recordingMbid())) return 100.0;
        int len = t.lengthMs() / 1000;
        boolean durKnown = it.durationSec() > 0 && len > 0;
        boolean durOk = durKnown && Math.abs(len - it.durationSec()) <= PAIR_DURATION_SEC;
        if (durKnown && !durOk) {
            // durée incompatible : seul un enregistrement identique (traité plus haut) pourrait encore convenir
            return 0;
        }
        double sim = durKnown ? lenientSimilarity(it.title(), t.title()) : plainSimilarity(it.title(), t.title());
        boolean numberOk = it.trackNo() > 0 && it.trackNo() == t.trackNo() && (it.discNo() <= 0 || t.disc() <= 0 || it.discNo() == t.disc());
        if (durOk && numberOk) return 20 + sim * 10;                       // bon numéro et bonne durée, même si le titre est faux
        if (sim >= (durKnown ? PAIR_TITLE_SIMILARITY : PAIR_TITLE_SIMILARITY_NO_DURATION)) return 10 + sim * 10 + (numberOk ? 3 : 0) + (durOk ? 2 : 0);
        return 0;
    }

    private static boolean artistCompatible(Item it, MusicBrainzClient.ReleaseTrack t) {
        if (!notBlank(it.artist()) || !notBlank(t.artist())) return true;
        return titleSimilarity(it.artist(), t.artist()) >= 0.5 || norm(t.artist()).contains(norm(it.artist())) || norm(it.artist()).contains(norm(t.artist()));
    }

    // ── Choix de la release ───────────────────────────────────────────────────────────────────────

    /**
     * Toutes les releases qui expliquent TOUS les fichiers, de la meilleure à la moins bonne.
     * @param albumHint album annoncé par les tags des fichiers (peut être vide)
     * @param yearHint  année annoncée par les tags (peut être vide)
     */
    public static List<Match> acceptable(List<Item> items, List<MusicBrainzClient.ReleaseTracklist> candidates, String albumHint, String yearHint) {
        if (items == null || items.isEmpty() || candidates == null) return List.of();
        List<List<Item>> slots = dedupe(items);
        boolean variedArtists = distinctArtists(items) >= 3;
        int hintYear = parseYear(yearHint);

        List<Match> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MusicBrainzClient.ReleaseTracklist rel : candidates) {
            if (rel == null || !seen.add(String.valueOf(rel.releaseMbid()))) continue;
            Map<Integer, MusicBrainzClient.ReleaseTrack> pairs = pairWithRelease(slots, rel);
            if (pairs == null) continue;
            Map<String, MusicBrainzClient.ReleaseTrack> byItem = new LinkedHashMap<>();
            for (int s = 0; s < slots.size(); s++) for (Item it : slots.get(s)) byItem.put(it.id(), pairs.get(s));
            out.add(new Match(rel, byItem, slots.size(), rel.tracks().size() - slots.size()));
        }
        out.sort(rankOrder(albumHint, hintYear, variedArtists));
        return out;
    }

    /** La meilleure release qui explique tout le dossier, ou {@code null}. */
    public static Match best(List<Item> items, List<MusicBrainzClient.ReleaseTracklist> candidates, String albumHint, String yearHint) {
        List<Match> all = acceptable(items, candidates, albumHint, yearHint);
        return all.isEmpty() ? null : all.get(0);
    }

    private static Comparator<Match> rankOrder(String albumHint, int hintYear, boolean variedArtists) {
        return Comparator
                .comparing((Match m) -> -albumAgreement(m, albumHint))                                // l'album des tags d'abord
                .thenComparing(m -> m.release().isCompilation() != variedArtists)                      // compilation seulement si les artistes varient
                .thenComparing(m -> m.extraTracks())                                                   // l'édition la plus proche du dossier
                .thenComparing(m -> !"official".equalsIgnoreCase(String.valueOf(m.release().releaseStatus())))
                .thenComparing(m -> yearDistance(m, hintYear))                                         // année des tags, sinon la plus ancienne
                .thenComparing(m -> String.valueOf(m.release().releaseMbid()));                       // ordre stable
    }

    /** 2 : même nom d'album, 1 : nom approchant, 0 : sinon ou pas d'indice. Les parenthèses comptent : « (Deluxe) » est une autre édition. */
    private static int albumAgreement(Match m, String albumHint) {
        if (!notBlank(albumHint)) return 0;
        if (norm(albumHint).equals(norm(m.release().album()))) return 2;
        return plainSimilarity(albumHint, m.release().album()) >= 0.85 ? 1 : 0;
    }

    private static int yearDistance(Match m, int hintYear) {
        int y = parseYear(m.release().originalYear());
        if (y <= 0) y = parseYear(m.release().year());
        if (y <= 0) return Integer.MAX_VALUE;
        return hintYear > 0 ? Math.abs(y - hintYear) : y;      // sans année annoncée : la plus ancienne gagne
    }

    private static int parseYear(String s) {
        if (s == null || s.length() < 4) return 0;
        try { return Integer.parseInt(s.substring(0, 4)); } catch (NumberFormatException e) { return 0; }
    }

    private static int distinctArtists(List<Item> items) {
        Set<String> a = new HashSet<>();
        for (Item it : items) if (notBlank(it.artist())) a.add(norm(it.artist()));
        return a.size();
    }
}
