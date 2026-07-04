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

    private static final String CONFIG_DIR  = configDir();
    private static final String CONFIG_FILE = CONFIG_DIR + "/settings.properties";

    public static String configDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // Windows : %APPDATA%\OpenTagger
            String appdata = System.getenv("APPDATA");
            return (appdata != null ? appdata : System.getProperty("user.home")) + "\\OpenTagger";
        }
        // Linux / macOS : ~/.opentagger
        return System.getProperty("user.home") + "/.opentagger";
    }

    // Holder idiom — thread-safe sans synchronized, initialisation paresseuse
    private static final class Holder {
        static final Config INSTANCE = new Config();
    }

    private final Properties props = new Properties();

    private Config() {
        loadDefaults();
        deployUserConfigIfAbsent();
        loadUserConfig();
    }

    // Silence les loggers jaudiotagger, y compris ceux créés tardivement (ex. ID3v22Tag,
    // Mp4TagWriter — jaudiotagger crée/réaffecte parfois le niveau de ses propres loggers au
    // premier vrai accès, souvent bien après le silencing fait une fois au démarrage dans
    // App.java). Config.get() est appelé en permanence dans tout le code (lecture ET écriture de
    // tags), donc réaffecter ici, à intervalle limité pour rester peu coûteux, couvre les cas que
    // App.java au démarrage et TagWriter.write() (écriture seule) manquaient — ex. les
    // avertissements "Invalid Frame"/"Found padding" pendant un simple scan/lecture de fichiers.
    private static volatile long lastLogSilenceMs = 0;

    static void silenceJaudiotaggerLoggingThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastLogSilenceMs < 2000) return;
        lastLogSilenceMs = now;
        silenceJaudiotaggerLogging();
    }

    static void silenceJaudiotaggerLogging() {
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(java.util.logging.Level.OFF);
        java.util.logging.LogManager.getLogManager().getLoggerNames().asIterator().forEachRemaining(n -> {
            if (n.startsWith("org.jaudiotagger")) java.util.logging.Logger.getLogger(n).setLevel(java.util.logging.Level.OFF);
        });
    }

    public static Config get() {
        silenceJaudiotaggerLoggingThrottled();
        return Holder.INSTANCE;
    }

    /** Recharge la config utilisateur après modification par SettingsDialog. */
    public synchronized void reload() {
        props.clear();
        loadDefaults();
        loadUserConfig();
    }

    // --- Lecture ---

    public synchronized String str(String key) {
        return props.getProperty(key, "").trim();
    }

    public synchronized String str(String key, String fallback) {
        String v = props.getProperty(key, fallback);
        return v == null ? fallback : v.trim();
    }

    public synchronized int num(String key, int fallback) {
        try { return Integer.parseInt(props.getProperty(key, "").trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    public synchronized boolean bool(String key, boolean fallback) {
        String v = props.getProperty(key, "").trim();
        if (v.isEmpty()) return fallback;
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    // --- Raccourcis pour les cles les plus utilisees ---

    public boolean useAcoustId()       { return bool("acoustid.use_acoustid", true); }
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
    public int    defaultRenameMask()       { return num ("rename.default_mask",       3); }
    public boolean autoRenameEnabled()      { return bool("rename.auto_enabled",       false); }
    public boolean deleteEmptyDirsAfterRename()    { return bool("rename.delete_empty_dirs",    true); }
    public boolean followLogAfterRename()          { return bool("rename.follow_log",             true); }
    public String libraryRoot()                    { return str ("rename.library_root",            ""); }
    public String podcastLibraryRoot()             { return str ("podcast.library_root",            ""); }
    public boolean preserveCompilationAlbum()      { return bool("tags.preserve_compilation",     true); }
    public boolean trustExistingMbTags()           { return bool("tags.trust_existing_mb_tags",   true); }
    public boolean albumFirstPassEnabled()         { return bool("albums.album_first_pass",        true); }
    public int     albumFirstPassMinFiles()        { return num ("albums.album_first_pass_min",    2);    }
    public String[] startupFolders()   {
        String v = str("startup.folders");
        return v.isBlank() ? new String[0] : v.split("\\|");
    }

    public String userAgent() {
        return "OpenTagger/" + str("app.version", "0.1") + " (" + contact() + ")";
    }

    // --- Tags écriture ---
    public boolean preserveTimestamps()      { return bool("tags.preserve_timestamps",  false); }
    public boolean clearExistingTags()       { return bool("tags.clear_existing_tags",  false); }
    public boolean preserveImages()          { return bool("tags.preserve_images",       true); }
    public String  id3v2Version()            { return str ("tags.id3v2_version",        "keep"); } // keep / 2.3 / 2.4

    // --- AcoustID fingerprint ---
    public boolean saveAcoustidFingerprints()     { return bool("acoustid.save_fingerprints", true); }
    public boolean ignoreExistingFingerprints()   { return bool("acoustid.ignore_existing",   false); }
    public int     fpcalcThreads()                { return num ("acoustid.fpcalc_threads",    2); }

    // --- Métadonnées ---
    public String  vaName()                 { return str("metadata.va_name",              "Various Artists"); }
    public boolean standardizeArtists()     { return bool("metadata.standardize_artists", false); }

    // --- Genres MB (folksonomy) ---
    public boolean mbUseGenres()       { return bool("mb.use_genres",        false); }
    public int     mbMinGenreUsage()   { return num ("mb.min_genre_usage",   50); }
    public String  mbGenresFilter()    { return str ("mb.genres_filter",     GenreFilter.DEFAULT_FILTER); }

    // --- Pochette fichier ---
    public boolean coverSaveToFile()   { return bool("cover.save_to_file",   false); }
    public boolean coverOverwriteFile(){ return bool("cover.overwrite_file", false); }
    public String  coverFilename()     { return str ("cover.filename",       "cover"); }

    // --- Tags préservés ---
    public String  preservedTags()     { return str ("tags.preserved_tags",  ""); }

    // --- Ponctuation & nettoyage ---
    public boolean correctPunctuation(){ return bool("tags.correct_punctuation", false); }
    public boolean removeId3v1()       { return bool("tags.remove_id3v1",        false); }

    // --- Pochettes locales ---
    public boolean coverSearchLocal()  { return bool("cover.search_local",       true); }

    // --- Barre d'outils secondaire personnalisable (façon Picard) ---
    public static final String DEFAULT_TOOLBAR_ACTIONS = "transcode,submitAcoustId";
    public String[] toolbarActions() {
        String v = str("toolbar.actions", DEFAULT_TOOLBAR_ACTIONS);
        return v.isBlank() ? new String[0] : v.split(",");
    }

    // --- Fournisseurs de pochette : activation individuelle + ordre (façon Picard) ---
    public boolean caaReleaseEnabled()      { return bool("cover.caa_release_enabled",       true); }
    public boolean caaReleaseGroupEnabled() { return bool("cover.caa_release_group_enabled", true); }
    public static final String DEFAULT_COVER_PROVIDER_ORDER = "caa_release,caa_release_group,local,fanart";
    public String[] coverProviderOrder() {
        String v = str("cover.provider_order", DEFAULT_COVER_PROVIDER_ORDER);
        return v.isBlank() ? DEFAULT_COVER_PROVIDER_ORDER.split(",") : v.split(",");
    }

    // --- MB genres max ---
    public int     mbMaxGenres()       { return num ("mb.max_genres",            3); }

    // --- ReplayGain ---
    public boolean replayGainEnabled() { return bool("replaygain.enabled",       false); }

    // --- Translittération artistes ---
    public boolean translateArtists()  { return bool("metadata.translate_artists", false); }
    public String  translateLocale()   { return str ("metadata.translate_locale",  "en"); }

    // --- Album clustering ---
    public boolean albumClusterEnabled(){ return bool("albums.cluster",           false); }

    // --- Ordre de traitement : fichiers incomplets en priorité ---
    public boolean prioritizeIncomplete(){ return bool("batch.prioritize_incomplete", true); }

    // --- Transcodage audio ---
    public boolean transcodeAutoBeforeTag() { return bool("transcode.auto_before_tag", false); }
    public String  transcodeFormat()        { return str ("transcode.format",          "mp3"); }
    public int     transcodeBitrate()       { return num ("transcode.bitrate_kbps",    320);   }
    public boolean transcodeDeleteSource()  { return bool("transcode.delete_source",   false); }

    // --- Releases préférées (codes séparés par virgule) ---
    public String[] preferredCountries()    {
        String v = str("releases.preferred_countries", "");
        return v.isBlank() ? new String[0] : v.split(",");
    }
    public String[] preferredFormats()      {
        String v = str("releases.preferred_formats", "");
        return v.isBlank() ? new String[0] : v.split(",");
    }

    // --- Filtres types de release ---
    public String[] allowedPrimaryTypes()    {
        String v = str("releases.allowed_primary_types", "");
        return v.isBlank() ? new String[0] : v.split(",");
    }
    public String[] excludedSecondaryTypes() {
        String v = str("releases.excluded_secondary_types", "");
        return v.isBlank() ? new String[0] : v.split(",");
    }

    // --- MusicBrainz OAuth ---
    public String mbToken()        { return str("mb.oauth.token"); }
    public String mbUsername()     { return str("mb.oauth.username"); }
    public String mbClientId()     { return str("mb.oauth.client_id"); }
    public String mbClientSecret() { return str("mb.oauth.client_secret"); }
    public boolean mbConnected()   { return !mbToken().isBlank(); }

    /** Met à jour une clé en mémoire et persiste immédiatement sur disque. */
    public synchronized void set(String key, String value) {
        props.setProperty(key, value != null ? value : "");
        persist();
    }

    /** Écrit toutes les propriétés actuelles dans le fichier utilisateur. */
    public synchronized void persist() {
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
