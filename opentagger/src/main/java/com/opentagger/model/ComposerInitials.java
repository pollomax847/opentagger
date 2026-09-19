package com.opentagger.model;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * Abrège en initiales les quelques compositeurs classiques dont le nom complet dans un titre
 * d'album généré ("Johann Sebastian Bach: Goldberg Variations...") est à la fois long et source
 * d'ambiguïté avec des homonymes de la même famille — écart trouvé vs SongKong (audit 2026-09-18) :
 * son {@code album_composers_use_initials.txt} liste précisément les 3 Bach (J.S./C.P.E./J.C.),
 * jamais interchangeables mais tous les trois juste "Bach" à l'oreille. Contrairement au fichier
 * SongKong (indexé par MBID compositeur, un champ qu'OpenTagger ne porte pas encore sur TagInfo),
 * cette liste est indexée par nom complet — plus simple, et {@code TagInfo.composer} est déjà la
 * seule donnée disponible aux points d'appel (ClassicalDisplay, futurs masques de renommage).
 */
public final class ComposerInitials {

    private ComposerInitials() {}

    private static final Set<String> NAMES = load();

    private static Set<String> load() {
        Set<String> names = new HashSet<>();
        try (InputStream in = ComposerInitials.class.getResourceAsStream("/composer_initials.txt");
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String n = line.trim();
                if (!n.isEmpty()) names.add(n);
            }
        } catch (Exception ignored) {
            // dégradation silencieuse comme les autres listes de référence (ClassicalExceptions...)
        }
        return names;
    }

    /** @return le nom tel quel s'il n'est pas dans la liste, sinon sa forme en initiales
     *  ("Johann Sebastian Bach" → "J.S. Bach") — prénoms/second prénoms réduits à leur initiale,
     *  nom de famille (dernier mot) toujours conservé intact. */
    public static String displayName(String fullName) {
        if (fullName == null || !NAMES.contains(fullName)) return fullName;
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length < 2) return fullName;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (!parts[i].isEmpty()) sb.append(Character.toUpperCase(parts[i].charAt(0))).append('.');
        }
        sb.append(' ').append(parts[parts.length - 1]);
        return sb.toString();
    }
}
