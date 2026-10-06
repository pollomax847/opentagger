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

    /** Clé de comparaison de ce chemin sur le système de fichiers courant. */
    public static String key(Path p) { return key(p, caseInsensitiveFileSystem()); }

    /** Même chose avec la sensibilité à la casse imposée (pour les tests, et pour raisonner sans dépendre de la machine). */
    public static String key(Path p, boolean caseInsensitive) {
        String s = p.toAbsolutePath().normalize().toString();
        return caseInsensitive ? s.toLowerCase(Locale.ROOT) : s;
    }
}
