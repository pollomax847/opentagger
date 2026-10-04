package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Commande(s) shell exécutées une fois, dans l'ordre, à la fin d'un run de taguage complet —
 * anciennement un champ texte unique ({@code hooks.post_tag_command}), étendu en liste (dans
 * l'esprit de {@link TaggerScript}, même stockage JSON) pour pouvoir en enchaîner plusieurs
 * (ex : scan Plex, puis rebalance mergerfs).
 */
public class PostTagCommands {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STORE_PATH = Config.configDir() + "/post_tag_commands.json";

    /**
     * Charge la liste. Migration automatique : si {@code post_tag_commands.json} n'existe pas
     * encore et que l'ancienne propriété unique {@code hooks.post_tag_command} est renseignée,
     * elle est reprise comme premier (et seul) élément — pour ne rien perdre de la config
     * existante. Même pattern que {@link TaggerScript#loadScripts()}.
     */
    /** Lanceur de commande « shell » du système : {@code sh -c} (Linux/macOS) ou {@code cmd.exe /c} (Windows, où
     *  {@code sh} n'existe pas — la commande post-taguage échouait toujours à se lancer). */
    public static ProcessBuilder shell(String command) {
        boolean win = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return win ? new ProcessBuilder("cmd.exe", "/c", command) : new ProcessBuilder("sh", "-c", command);
    }

    public static List<String> load() {
        File f = new File(STORE_PATH);
        if (!f.exists()) {
            String legacy = Config.get().postTagCommand().trim();
            List<String> migrated = new ArrayList<>();
            if (!legacy.isBlank()) migrated.add(legacy);
            if (!migrated.isEmpty()) save(migrated);
            return migrated;
        }
        try {
            String[] arr = MAPPER.readValue(f, String[].class);
            List<String> list = new ArrayList<>();
            for (String s : arr) list.add(s);
            return list;
        } catch (Exception e) {
            System.err.println("[PostTagCommands] Lecture post_tag_commands.json impossible : " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static void save(List<String> commands) {
        try {
            File f = new File(STORE_PATH);
            f.getParentFile().mkdirs();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(f, commands);
        } catch (Exception e) {
            System.err.println("[PostTagCommands] Écriture post_tag_commands.json impossible : " + e.getMessage());
        }
    }
}
