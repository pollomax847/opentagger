package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Map;

/**
 * Traduction de l'interface. Le français est la langue source : le texte français existant sert
 * directement de clé de traduction (pas de clés sémantiques à inventer pour ~2000 chaînes). Quand
 * la langue active est "fr" (ou qu'une clé est absente du dictionnaire), t() renvoie le texte
 * source tel quel — le français reste donc le comportement de repli, aucun fichier
 * messages_fr.json n'est nécessaire.
 *
 * setLanguage() doit être appelé une seule fois, au tout début du lancement (avant la construction
 * du moindre composant Swing) — un changement de langue en cours d'exécution ne modifie pas le
 * texte déjà instancié dans des composants existants (voir SettingsDialog : changer la langue
 * demande un redémarrage).
 */
public final class I18n {

    private static volatile Map<String, String> dict = Map.of();
    private static volatile String lang = "fr";

    private I18n() {}

    public static void setLanguage(String code) {
        lang = code;
        dict = "en".equals(code) ? loadDict("en") : Map.of();
        // Les composants Swing natifs (boutons OK/Annuler/Oui/Non par défaut de JOptionPane,
        // chrome de JFileChooser/JColorChooser…) ne passent pas par notre dictionnaire — ils
        // suivent Locale.getDefault(), via les traductions déjà intégrées au JDK. Sans ceci,
        // passer l'appli en anglais laisserait ces boutons natifs en français.
        java.util.Locale.setDefault("en".equals(code) ? java.util.Locale.ENGLISH : java.util.Locale.FRENCH);
    }

    public static String lang() { return lang; }

    public static String t(String frText) {
        if (frText == null) return null;
        return dict.getOrDefault(frText, frText);
    }

    /** Pour les chaînes formatées (String.format) : traduit le format FR puis applique les arguments. */
    public static String t(String frFormat, Object... args) {
        return String.format(t(frFormat), args);
    }

    private static Map<String, String> loadDict(String code) {
        String path = "/i18n/messages_" + code + ".json";
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) return Map.of();
            return new ObjectMapper().readValue(in, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
