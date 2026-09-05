package com.opentagger;

import java.util.Set;

/**
 * Capitalisation automatique optionnelle (Préférences → Tags, capitalize.enabled), inspirée du
 * "AutoEdit"/capitalizer de SongKong. Toujours en aval de toute autre mutation du champ (script
 * utilisateur, enrichissement, translittération) — voir le point d'appel dans TaggingWorker.
 *
 * Comportement volontairement simple (pas de dictionnaire de noms propres) : un mot non couvert par
 * une des trois listes de config est normalisé à "Première lettre majuscule, reste minuscule",
 * ce qui aplatirait une casse interne intentionnelle non listée (ex. "DiCaprio", "LeBron") — c'est
 * le compromis assumé de toute capitalisation automatique par règles plutôt que par dictionnaire ;
 * l'utilisateur ajuste via les listes d'exception s'il rencontre un cas réel dans sa bibliothèque.
 */
public class TitleCaseFixer {

    public static String fix(String s, Set<String> lowercase, Set<String> uppercase, Set<String> keepPrefixes) {
        if (s == null || s.isBlank()) return s;
        String[] words = s.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) out.append(' ');
            boolean edge = (i == 0 || i == words.length - 1);
            out.append(fixWord(words[i], edge, lowercase, uppercase, keepPrefixes));
        }
        return out.toString();
    }

    private static String fixWord(String word, boolean forceCapitalize,
                                   Set<String> lowercase, Set<String> uppercase, Set<String> keepPrefixes) {
        if (word.isEmpty()) return word;
        for (String u : uppercase) {
            if (u.equalsIgnoreCase(word)) return u;
        }
        String lower = word.toLowerCase();
        for (String prefix : keepPrefixes) {
            if (prefix.isBlank()) continue;
            String p = prefix.toLowerCase();
            if (lower.startsWith(p) && word.length() > p.length()) {
                return capitalizeFirst(prefix) + capitalizeFirst(word.substring(p.length()));
            }
        }
        if (!forceCapitalize && lowercase.contains(lower)) return lower;
        return capitalizeHyphenated(word);
    }

    private static String capitalizeFirst(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String capitalizeHyphenated(String word) {
        String[] parts = word.split("-", -1);
        if (parts.length == 1) return capitalizeFirst(word);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('-');
            sb.append(capitalizeFirst(parts[i]));
        }
        return sb.toString();
    }
}
