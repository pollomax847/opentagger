package com.opentagger;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Identité d'un fichier par son chemin. Windows (et macOS par défaut) ignorent la casse : « Musique\Billie_Eilish\a.mp3 » et
 * « Musique\Billie_eilish\a.mp3 » sont LE MÊME fichier. Linux la respecte : « a.mp3 » et « A.mp3 » y sont deux fichiers.
 */
public final class PathIdentity {
    private PathIdentity() {}

    public static boolean caseInsensitiveFileSystem() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") || os.contains("mac");
    }

    /** Ensemble de chemins (texte) qui respecte la sensibilité à la casse du système : « E:\Musique\A.mp3 » et « e:\musique\a.mp3 » y sont
     *  le même élément sous Windows/macOS, deux éléments sous Linux. Lecture seule après chargement. */
    public static java.util.Set<String> newPathSet() { return newPathSet(caseInsensitiveFileSystem()); }

    public static java.util.Set<String> newPathSet(boolean caseInsensitive) {
        return caseInsensitive ? new java.util.concurrent.ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER)
                               : new java.util.HashSet<>(); // comportement historique inchangé hors Windows/macOS
    }

    /** Idem pour une table chemin → valeur. */
    public static <V> java.util.Map<String, V> newPathMap() { return newPathMap(caseInsensitiveFileSystem()); }

    public static <V> java.util.Map<String, V> newPathMap(boolean caseInsensitive) {
        return caseInsensitive ? new java.util.concurrent.ConcurrentSkipListMap<>(String.CASE_INSENSITIVE_ORDER)
                               : new java.util.HashMap<>(); // comportement historique inchangé hors Windows/macOS
    }

    /** Clé de comparaison de ce chemin sur le système de fichiers courant. */
    public static String key(Path p) { return key(p, caseInsensitiveFileSystem()); }

    /** Même chose avec la sensibilité à la casse imposée (pour les tests, et pour raisonner sans dépendre de la machine). */
    public static String key(Path p, boolean caseInsensitive) {
        String s = p.toAbsolutePath().normalize().toString();
        return caseInsensitive ? s.toLowerCase(Locale.ROOT) : s;
    }
}
