package com.opentagger;

import java.util.Locale;

/**
 * Affichage d'une taille en octets avec l'unité adaptée (o/Ko/Mo/Go/To en français, B/KB/MB/GB/TB
 * en anglais). Base 1024, comme l'Explorateur Windows — la même valeur que celle qu'y verrait
 * l'utilisateur pour le même dossier. Indépendant de la plateforme (Windows/Linux).
 */
public final class ByteFormat {

    private ByteFormat() {}

    private static final String[] UNITS_FR = {"o", "Ko", "Mo", "Go", "To"};
    private static final String[] UNITS_EN = {"B", "KB", "MB", "GB", "TB"};

    /** Format pour la langue courante de l'interface et la locale par défaut. */
    public static String format(long bytes) {
        return format(bytes, "en".equals(I18n.lang()), Locale.getDefault());
    }

    /** Version pure (testable) : {@code english} choisit les unités, {@code locale} le séparateur
     *  décimal. Une décimale sous 100 unités (« 12,5 Go »), aucune au-dessus (« 312 Go »). */
    public static String format(long bytes, boolean english, Locale locale) {
        String[] units = english ? UNITS_EN : UNITS_FR;
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " " + units[0];
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        return String.format(locale, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }
}
