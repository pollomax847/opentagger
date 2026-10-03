package com.opentagger;

/**
 * Règles de la synchronisation des compteurs d'écoute (ListenBrainz / Last.fm → tags des fichiers).
 * Logique pure, testable ; utilisée par les deux workers de synchronisation et par le déclenchement
 * automatique (voir MainFrame.scheduleAutoPlayCountSync).
 */
public final class PlayCounts {

    private PlayCounts() {}

    /** Délai minimal entre deux synchronisations AUTOMATIQUES (le bouton manuel n'est jamais limité). */
    public static final long AUTO_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /**
     * Faut-il réécrire le compteur d'un fichier ? Oui s'il n'a pas de valeur exploitable, ou si le
     * compteur en ligne est STRICTEMENT plus grand que celui déjà écrit. Jamais de baisse : un nombre
     * d'écoutes ne diminue pas, un chiffre en ligne plus petit signale un classement tronqué ou un
     * autre compte — pas une correction. Évite aussi de réécrire inutilement des milliers de fichiers
     * à chaque synchronisation (avant ce garde-fou, chaque fichier retrouvé était réécrit à chaque fois).
     */
    public static boolean needsUpdate(String stored, int online) {
        if (stored == null) return true;
        String s = stored.trim();
        if (s.isEmpty()) return true;
        try {
            return online > Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return true;   // valeur illisible : on la remplace
        }
    }

    /** Le déclenchement automatique est-il dû ? (au plus une fois par 24 h ; 0 = jamais lancé.) */
    public static boolean dueForAutoSync(long lastRunMs, long nowMs) {
        return lastRunMs <= 0 || nowMs - lastRunMs >= AUTO_SYNC_INTERVAL_MS;
    }
}
