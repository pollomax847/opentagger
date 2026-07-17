package com.opentagger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scoring de sélection de RELEASE parmi plusieurs candidates pour un même enregistrement trouvé —
 * port fidèle du tier "preferences" du vrai modèle de MusicBrainz Picard
 * ({@code picard/file.py: FILE_COMPARISON_WEIGHTS['preferences']}, {@code picard/matching.py:
 * _weights_from_preferred_countries/_weights_from_preferred_formats/
 * _weights_from_release_type_scores}), dépôt {@code metabrainz/picard} branche {@code master}
 * consulté le 2026-07-09.
 *
 * Phase 1 seulement (voir le plan de cette tâche) : les tiers "identifiers" (barcode/catno/isrc)
 * et "similarity" (comparaison au fichier lui-même : artiste/album/date/durée/piste) du vrai
 * modèle de Picard demandent de faire passer le contexte du fichier à travers
 * {@code MusicBrainzClient.searchRecording()} et sa dizaine d'appelants — hors scope ici. Cette
 * classe ne compare la release qu'à des RÉGLAGES UTILISATEUR (pays/format/type préférés), jamais
 * au fichier — c'est ce qui rend cette phase réalisable sans changer aucune signature existante.
 *
 * Poids réels de Picard pour ce tier : format=2, releasecountry=2, releasetype=14 — le type de
 * release compte ~7x plus que le pays ou le format seul, chacun scoré indépendamment jusqu'ici.
 */
public final class ReleaseMatcher {

    private ReleaseMatcher() {}

    private static final double WEIGHT_COUNTRY      = 2;
    private static final double WEIGHT_FORMAT        = 2;
    private static final double WEIGHT_RELEASE_TYPE  = 14;
    // picard/const/defaults.py: DEFAULT_RELEASE_SCORE — neutre pour tout type tant que
    // l'utilisateur n'a rien personnalisé, donc aucun changement de comportement par défaut.
    private static final double DEFAULT_TYPE_SCORE   = 0.5;
    // picard/matching.py: un type explicitement noté à 0 par l'utilisateur ("jamais ce type") fait
    // basculer TOUTE la release sur un poids énorme au lieu d'un score nul normal — un score nul à
    // poids normal se ferait encore égaliser par les autres tiers/dimensions ; ce poids la fait
    // quasiment disparaître du classement au lieu de simplement le pénaliser.
    private static final double VETO_WEIGHT          = 9999;

    /**
     * Combine pays/format/type préférés en un seul score 0.0–1.0 — port fidèle de la partie
     * "preferences" de {@code _compare_to_release_parts} + {@code combine_tiers()} réduit à ce
     * seul tier (pas de tiers identifiers/similarity ici, voir Javadoc de classe).
     *
     * @param releaseCountry   pays de la release (peut être vide/blanc)
     * @param preferredCountries pays préférés, priorité décroissante (peut être vide)
     * @param mediaFormats     format de CHAQUE média de la release (CD, Vinyl...) — Picard moyenne
     *                         sur tous les médias, pas seulement le premier
     * @param preferredFormats formats préférés, priorité décroissante (peut être vide)
     * @param primaryType      type primaire MB (Album, Single, EP...), peut être vide
     * @param secondaryTypes   types secondaires MB (Compilation, Live, Soundtrack...)
     * @param releaseTypeScores score configuré par type (défaut 0.5 = neutre pour tout type absent)
     */
    public static double combinePreferences(String releaseCountry, String[] preferredCountries,
                                             List<String> mediaFormats, String[] preferredFormats,
                                             String primaryType, List<String> secondaryTypes,
                                             Map<String, Double> releaseTypeScores) {
        List<double[]> parts = new ArrayList<>();
        addCountryScore(parts, releaseCountry, preferredCountries);
        addFormatScore(parts, mediaFormats, preferredFormats);
        addReleaseTypeScore(parts, primaryType, secondaryTypes, releaseTypeScores);
        return TrackMatcher.linearCombination(parts);
    }

    /** Port fidèle de {@code picard/matching.py:_weights_from_preferred_countries}. */
    private static void addCountryScore(List<double[]> parts, String releaseCountry, String[] preferredCountries) {
        int total = preferredCountries.length;
        if (total == 0) return; // aucune préférence configurée → dimension omise, pas neutre à 0.5
        double score = 0.0;
        if (releaseCountry != null && !releaseCountry.isBlank()) {
            for (int i = 0; i < total; i++) {
                if (preferredCountries[i].trim().equalsIgnoreCase(releaseCountry.trim())) {
                    score = (double) (total - i) / total;
                    break;
                }
            }
        }
        parts.add(new double[]{score, WEIGHT_COUNTRY});
    }

    /**
     * Port fidèle de {@code picard/matching.py:_weights_from_preferred_formats} — moyenne sur
     * TOUS les médias de la release (multi-disques : CD+CD, ou CD+DVD...), pas juste le premier
     * comme le faisait l'ancien code OpenTagger.
     */
    private static void addFormatScore(List<double[]> parts, List<String> mediaFormats, String[] preferredFormats) {
        int total = preferredFormats.length;
        if (total == 0 || mediaFormats.isEmpty()) return;
        double score = 0.0;
        int subtotal = 0;
        for (String format : mediaFormats) {
            if (format == null || format.isBlank()) continue;
            for (int i = 0; i < total; i++) {
                if (preferredFormats[i].trim().equalsIgnoreCase(format.trim())) {
                    score += (double) (total - i) / total;
                    break;
                }
            }
            subtotal++;
        }
        if (subtotal > 0) score /= subtotal;
        parts.add(new double[]{score, WEIGHT_FORMAT});
    }

    /** Port fidèle de {@code picard/matching.py:_weights_from_release_type_scores}. */
    private static void addReleaseTypeScore(List<double[]> parts, String primaryType,
                                             List<String> secondaryTypes, Map<String, Double> typeScores) {
        double otherScore = typeScores.getOrDefault("Other", DEFAULT_TYPE_SCORE);
        boolean vetoed = false;
        double score;
        if (primaryType != null && !primaryType.isBlank()) {
            List<String> typesFound = new ArrayList<>();
            typesFound.add(primaryType);
            if (secondaryTypes != null) typesFound.addAll(secondaryTypes);
            double sum = 0.0;
            for (String type : typesFound) {
                double typeScore = typeScores.getOrDefault(type, otherScore);
                if (typeScore == 0.0) vetoed = true;
                sum += typeScore;
            }
            score = sum / typesFound.size();
        } else {
            score = otherScore;
        }
        parts.add(vetoed ? new double[]{0, VETO_WEIGHT} : new double[]{score, WEIGHT_RELEASE_TYPE});
    }
}
