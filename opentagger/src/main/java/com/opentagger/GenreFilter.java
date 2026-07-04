package com.opentagger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Filtre de genres partagé (seuil de popularité + liste d'exclusion façon Picard) — utilisé par
 * les 3 sources de genres (Discogs, Last.fm, MusicBrainz folksonomy) au lieu d'une logique par
 * source. Configuré une seule fois dans les Préférences (clés {@code mb.min_genre_usage} /
 * {@code mb.genres_filter}, historiquement ajoutées pour MusicBrainz), s'applique uniformément
 * peu importe la source — comme la liste de filtre unique de Picard.
 */
public final class GenreFilter {

    private GenreFilter() {}

    // Défaut Picard (-seen live, -fixme, -owned, -favorites) + les entrées équivalentes à
    // l'ancienne liste noire codée en dur de LastFmClient (favourite/love/best/music/spotify...),
    // pour ne pas perdre ce filtrage en passant à une liste unique éditable par l'utilisateur.
    public static final String DEFAULT_FILTER = String.join("\n",
            "-seen live", "-fixme", "-owned", "-favorites", "-favourite", "-love",
            "-awesome", "-cool", "-best", "-good", "-music", "-songs", "-playlist",
            "-spotify", "-youtube", "-all");

    /** @param usagePercent popularité connue (0-100), ou 100 si la source n'en fournit pas (ex. Discogs). */
    public record Candidate(String name, int usagePercent) {
        public Candidate(String name) { this(name, 100); }
    }

    /**
     * Filtre et plafonne une liste de candidats : exclut ceux sous le seuil de popularité
     * ({@code mb.min_genre_usage}, ignoré pour les candidats sans score connu), exclut ceux listés
     * dans {@code mb.genres_filter} (une entrée par ligne, préfixe "-" pour exclure), capitalise,
     * et coupe à {@code maxCount}.
     */
    public static List<String> filter(List<Candidate> candidates, int maxCount) {
        int minUsage = Config.get().num("mb.min_genre_usage", 50);
        Set<String> blacklist = parseBlacklist(Config.get().mbGenresFilter());

        List<String> result = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.usagePercent() < minUsage) continue;
            String name = c.name() == null ? "" : c.name().trim();
            if (name.isBlank() || blacklist.contains(name.toLowerCase())) continue;
            result.add(Character.toUpperCase(name.charAt(0)) + name.substring(1));
            if (result.size() >= maxCount) break;
        }
        return result;
    }

    private static Set<String> parseBlacklist(String filterRaw) {
        Set<String> blacklist = new HashSet<>();
        for (String line : filterRaw.split("[\\r\\n]+")) {
            String l = line.trim();
            if (l.startsWith("-")) blacklist.add(l.substring(1).trim().toLowerCase());
        }
        return blacklist;
    }
}
