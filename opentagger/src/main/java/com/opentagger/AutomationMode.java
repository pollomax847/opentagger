package com.opentagger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Niveau d'automatisation : UN choix (Manuel / Assisté / Automatique) qui remplace six cases à cocher du menu Tagger.
 * Chaque niveau n'est qu'un jeu de valeurs pour les réglages existants — le reste du code lit toujours les mêmes clés.
 *
 * <ul>
 *   <li><b>Manuel</b> : rien ne démarre seul, rien n'est écrit sans geste explicite.</li>
 *   <li><b>Assisté</b> : le taguage démarre seul après un scan et complète les champs manquants ; l'enregistrement reste un geste.</li>
 *   <li><b>Automatique</b> : tout s'enchaîne (taguage, enregistrement, albums, compilations, ré-identification, compteurs d'écoute,
 *       pochettes et photos manquantes, fichiers non reconnus rangés dans « _À vérifier » de la bibliothèque).</li>
 * </ul>
 */
public enum AutomationMode {
    MANUAL(preset(false, false, "NONE", false, false, false, false, false)),
    ASSISTED(preset(true, false, "FIELDS", false, false, false, false, false)),
    AUTOMATIC(preset(true, true, "FIELDS_AND_ALBUMS", true, true, true, true, true));

    public static final String KEY_MODE = "automation.mode";

    /** Valeurs d'un réglage quand la clé est absente — les mêmes défauts que dans {@link Config}. */
    private static final Map<String, String> DEFAULTS = preset(false, true, "NONE", false, false, false, false, false);

    private final Map<String, String> values;

    AutomationMode(Map<String, String> values) { this.values = values; }

    /** Les réglages que ce niveau impose (clé → valeur). */
    public Map<String, String> settings() { return values; }

    private static Map<String, String> preset(boolean tagOnScan, boolean autoSave, String completion,
                                              boolean group, boolean reidentify, boolean playCounts, boolean artwork, boolean moveToReview) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("tagging.auto_start_on_scan", String.valueOf(tagOnScan));
        m.put("tagging.auto_save_enabled", String.valueOf(autoSave));
        m.put("tagging.post_tag_completion", completion);
        m.put("tagging.auto_group_compilations", String.valueOf(group));
        m.put("tagging.auto_reidentify_unmatched", String.valueOf(reidentify));
        m.put("playcounts.auto_sync", String.valueOf(playCounts));
        m.put("artwork.auto_after_save", String.valueOf(artwork));
        m.put("skipped.move_enabled", String.valueOf(moveToReview));
        m.put("duration_mismatch.move_enabled", String.valueOf(moveToReview));
        return m;
    }

    /**
     * Le niveau qui correspond EXACTEMENT aux réglages actuels, ou {@code null} (« personnalisé ») si aucun ne colle :
     * un utilisateur qui avait déjà réglé ses cases une à une ne voit donc jamais ses choix changés en silence.
     *
     * @param read lit la valeur d'une clé ({@code null} ou vide = clé absente)
     */
    public static AutomationMode detect(Function<String, String> read) {
        for (AutomationMode mode : values()) {
            boolean same = true;
            for (Map.Entry<String, String> e : mode.values.entrySet()) {
                String v = read.apply(e.getKey());
                if (v == null || v.isBlank()) v = DEFAULTS.get(e.getKey());
                if (!e.getValue().equalsIgnoreCase(v.trim())) { same = false; break; }
            }
            if (same) return mode;
        }
        return null;
    }
}
