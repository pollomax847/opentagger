package com.opentagger;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Singleton de configuration.
 * Charge d'abord les valeurs par defaut depuis le JAR (settings.properties),
 * puis surcharge avec ~/.opentagger/settings.properties si present.
 */
public class Config {

    private static final String CONFIG_DIR  = System.getProperty("user.home") + "/.opentagger";
    private static final String CONFIG_FILE = CONFIG_DIR + "/settings.properties";

    private static Config instance;
    private final Properties props = new Properties();

    private Config() {
        loadDefaults();
        deployUserConfigIfAbsent();
        loadUserConfig();
    }

    public static Config get() {
        if (instance == null) instance = new Config();
        return instance;
    }

    /** Recharge la config utilisateur après modification par SettingsDialog. */
    public void reload() {
        props.clear();
        loadDefaults();
        loadUserConfig();
    }

    // --- Lecture ---

    public String str(String key) {
        return props.getProperty(key, "").trim();
    }

    public String str(String key, String fallback) {
        String v = props.getProperty(key, fallback);
        return v == null ? fallback : v.trim();
    }

    public int num(String key, int fallback) {
        try { return Integer.parseInt(str(key)); }
        catch (NumberFormatException e) { return fallback; }
    }

    public boolean bool(String key, boolean fallback) {
        String v = str(key);
        if (v.isEmpty()) return fallback;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    // --- Raccourcis pour les cles les plus utilisees ---

    public String acoustidKey()        { return str("acoustid.api_key"); }
    public String discogsKey()         { return str("discogs.consumer_key"); }
    public String discogsSecret()      { return str("discogs.consumer_secret"); }
    public String fanartKey()          { return str("fanart.api_key"); }
    public String lastfmKey()          { return str("lastfm.api_key"); }
    public String contact()            { return str("app.contact"); }
    public int    minScoreAuto()       { return num("autocorrector.min_score", 85); }
    public int    mbResultsLimit()     { return num("musicbrainz.results_limit", 5); }
    public boolean mbOnlyOfficial()    { return bool("musicbrainz.only_official", true); }
    public String discogsGenreSource() { return str("discogs.genre_source", "style_then_genre"); }
    public int    discogsMaxGenres()   { return num("discogs.max_genres", 3); }
    public boolean fanartEnabled()     { return bool("fanart.download_cover", true); }
    public boolean lastfmEnabled()     { return bool("lastfm.use_tags", true); }
    public int    defaultRenameMask()  { return num("rename.default_mask", 3); }

    public String userAgent() {
        return "OpenTagger/" + str("app.version", "0.1") + " (" + contact() + ")";
    }

    // --- MusicBrainz OAuth ---
    public String mbToken()        { return str("mb.oauth.token"); }
    public String mbUsername()     { return str("mb.oauth.username"); }
    public String mbClientId()     { return str("mb.oauth.client_id"); }
    public String mbClientSecret() { return str("mb.oauth.client_secret"); }
    public boolean mbConnected()   { return !mbToken().isBlank(); }

    /** Met à jour une clé en mémoire et persiste immédiatement sur disque. */
    public void set(String key, String value) {
        props.setProperty(key, value != null ? value : "");
        persist();
    }

    /** Écrit toutes les propriétés actuelles dans le fichier utilisateur. */
    public void persist() {
        try {
            Path dir = Paths.get(CONFIG_DIR);
            if (!Files.exists(dir)) Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(Paths.get(CONFIG_FILE))) {
                props.store(w, "OpenTagger user settings");
            }
        } catch (IOException e) {
            System.err.println("[Config] persist failed: " + e.getMessage());
        }
    }

    // --- Chargement ---

    private void loadDefaults() {
        try (InputStream in = getClass().getResourceAsStream("/settings.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) {
            System.err.println("[Config] Impossible de charger les defauts : " + e.getMessage());
        }
    }

    private void loadUserConfig() {
        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) return;
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("[Config] Impossible de charger " + CONFIG_FILE + " : " + e.getMessage());
        }
    }

    private void deployUserConfigIfAbsent() {
        Path dir  = Paths.get(CONFIG_DIR);
        Path file = Paths.get(CONFIG_FILE);
        try {
            if (!Files.exists(dir))  Files.createDirectories(dir);
            if (!Files.exists(file)) {
                try (InputStream in = getClass().getResourceAsStream("/settings.properties")) {
                    if (in != null) Files.copy(in, file);
                }
                // Copier aussi renamemask.properties
                Path masks = Paths.get(CONFIG_DIR + "/renamemask.properties");
                try (InputStream in = getClass().getResourceAsStream("/renamemask.properties")) {
                    if (in != null && !Files.exists(masks)) Files.copy(in, masks);
                }
                System.out.println("[Config] Fichiers de configuration crees dans " + CONFIG_DIR);
            }
        } catch (IOException e) {
            System.err.println("[Config] Impossible de creer " + CONFIG_DIR + " : " + e.getMessage());
        }
    }
}
