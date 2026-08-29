package com.opentagger;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
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

    public synchronized double dbl(String key, double fallback) {
        try { return Double.parseDouble(props.getProperty(key, "").trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    // --- Raccourcis pour les cles les plus utilisees ---

    public String  uiLanguage()        { return str("ui.language", "fr"); }
    // Le bouton X minimise la fenêtre au lieu de quitter — le taguage/enregistrement continue en
    // fond (utile vu qu'un run peut durer des heures sur une grosse bibliothèque). Le menu
    // "Quitter" (MainFrame.quitApp(), avec sa confirmation si une opération est en cours) reste le
    // seul vrai moyen de fermer l'appli — inchangé, gate uniquement le bouton X de la fenêtre.
    public boolean closeMinimizesToTaskbar() { return bool("ui.close_minimizes", true); }
    public boolean updateCheckEnabled() { return bool("update.check_enabled", true); }
    public long    lastUpdateCheckMs()  { try { return Long.parseLong(str("update.last_check_ms", "0")); } catch (Exception e) { return 0; } }
    public void    setLastUpdateCheckMs(long ms) { set("update.last_check_ms", String.valueOf(ms)); }
    // "match.use_acoustid" est un ALIAS de "acoustid.use_acoustid" (même réglage, deux noms) —
    // la clé "match.use_acoustid" existait dans settings.properties depuis longtemps SANS être lue
    // par aucun code, purement décorative (source de confusion signalée par l'utilisateur,
    // 2026-08-11 : "je ne suis pas sûr que l'AcoustID n'est pas utilisé de partout", en la voyant à
    // false dans son fichier alors qu'AcoustID était bien actif). "acoustid.use_acoustid" reste la
    // clé canonique et prioritaire si les deux sont présentes ; sinon la clé présente, quel que
    // soit son nom, est respectée — pour qu'aucune des deux ne redevienne un piège silencieux.
    public boolean useAcoustId() {
        if (props.getProperty("acoustid.use_acoustid") != null) return bool("acoustid.use_acoustid", true);
        if (props.getProperty("match.use_acoustid")    != null) return bool("match.use_acoustid",    true);
        return true;
    }
    public String acoustidKey()        { return str("acoustid.api_key"); }
    public String discogsKey()         { return str("discogs.consumer_key"); }
    public String discogsSecret()      { return str("discogs.consumer_secret"); }
    public String fanartKey()          { return str("fanart.api_key"); }
    public String lastfmKey()          { return str("lastfm.api_key"); }
    public String contact()            { return str("app.contact"); }
    public int    minScoreAuto()       { return num("autocorrector.min_score", 85); }
    // Seuil du score composite pondéré fichier↔piste (TrackMatcher), même valeur par défaut que
    // Picard (picard/options.py: track_matching_threshold = 0.4) — voir TrackMatcher.findBestTrack().
    public double trackMatchingThreshold() { return dbl("match.track_matching_threshold", 0.4); }
    public int    mbResultsLimit()     { return num("musicbrainz.results_limit", 5); }
    public boolean mbOnlyOfficial()    { return bool("musicbrainz.only_official", true); }

    // --- Serveur MusicBrainz personnalisé (miroir) --- la clé musicbrainz.server existait déjà
    // dans settings.properties mais n'était en réalité JAMAIS lue par MusicBrainzClient (BASE_URL
    // y était codé en dur sur musicbrainz.org) — corrigé pour permettre un vrai miroir tiers (ex.
    // service payant type Headphones Indexer, musicbrainz.codeshy.com, qui élimine les erreurs 503
    // de contention en heure de pointe). Authentification HTTP Basic optionnelle (vide = aucune,
    // comme pour l'API publique). mbRateLimitMs() reste à 1100 par défaut (voir le commentaire
    // MB_MIN_INTERVAL_MS dans MusicBrainzClient — NE JAMAIS descendre en dessous sur la vraie API
    // publique musicbrainz.org, risque de bannissement IP) ; à réduire/désactiver UNIQUEMENT si le
    // serveur configuré est un miroir tiers avec sa propre capacité, jamais sur l'API publique.
    public String  mbServer()          { return str("musicbrainz.server", "https://musicbrainz.org/ws/2"); }
    public String  mbAuthUser()        { return str("musicbrainz.auth_user", ""); }
    public String  mbAuthPass()        { return str("musicbrainz.auth_pass", ""); }
    public int     mbRateLimitMs()     { return num("musicbrainz.rate_limit_ms", 1100); }
    public String discogsGenreSource() { return str("discogs.genre_source", "style_then_genre"); }
    public int    discogsMaxGenres()   { return num("discogs.max_genres", 3); }
    public boolean fanartEnabled()     { return bool("fanart.download_cover", true); }
    public boolean lastfmEnabled()     { return bool("lastfm.use_tags", true); }
    // URL officielle artiste + lien Wikipedia — appel Last.fm SÉPARÉ de celui du genre/mood
    // (artist.getInfo, pas track.getTopTags/artist.getTopTags) : ne peut pas être fusionné avec
    // eux (données différentes), donc chaque fichier paie ce round-trip réseau en plus même si
    // l'utilisateur ne se sert jamais de ces deux champs. Séparé de lastfmEnabled() pour que
    // désactiver JUSTE ça (et garder genre/mood Last.fm) économise un appel réseau par fichier
    // sans rien perdre d'autre. Défaut true = comportement inchangé pour qui ne touche pas ce réglage.
    public boolean lastfmArtistUrlsEnabled() { return bool("lastfm.fetch_artist_urls", true); }
    public int    defaultRenameMask()       { return num ("rename.default_mask",       3); }
    public boolean autoRenameEnabled()      { return bool("rename.auto_enabled",       false); }
    public boolean deleteEmptyDirsAfterRename()    { return bool("rename.delete_empty_dirs",    true); }
    public boolean followLogAfterRename()          { return bool("rename.follow_log",             true); }
    public String libraryRoot()                    { return str ("rename.library_root",            ""); }
    // Case à cocher UI qui grise/dégrise tfLibraryRoot dans SettingsDialog (voir bindGate()) —
    // défaut true pour ne rien changer au comportement des utilisateurs ayant déjà configuré ce
    // dossier avant l'ajout de cette case.
    public boolean useLibraryRootEnabled()         { return bool("rename.use_library_root",       true); }
    public String podcastLibraryRoot()             { return str ("podcast.library_root",            ""); }
    public boolean skippedMoveEnabled()            { return bool("skipped.move_enabled",         false); }
    public String  skippedMoveFolder()             { return str ("skipped.move_folder",              ""); }
    // Déplacement dédié des fichiers dont la durée ne correspond pas à celle déclarée par
    // MusicBrainz (rip tronqué/mauvais match probable) — indépendant du déplacement générique
    // SKIPPED/ERROR ci-dessus, désactivé par défaut (jamais de déplacement sans action explicite).
    public boolean durationMismatchMoveEnabled()   { return bool("duration_mismatch.move_enabled", false); }
    public String  durationMismatchMoveFolder()    { return str ("duration_mismatch.move_folder",      ""); }
    // Récupération vidéo (voir VideoScanner/VideoRecoveryWorker) automatique à chaque scan de
    // dossier (Ouvrir dossier/Rafraîchir) — activée par défaut à la demande explicite de
    // l'utilisateur, qui trouvait le dialogue manuel "Bibliothèque → Récupérer l'audio..." trop
    // pénible à déclencher lui-même à chaque fois.
    public boolean videoAutoRecover()              { return bool("video.auto_recover",           true); }
    // Lance automatiquement l'identification (PAS l'enregistrement, qui reste toujours manuel —
    // façon Picard, voir FileEntry.Status.IDENTIFIED) sur les fichiers PENDING dès qu'un scan de
    // dossier se termine (manuel "Ouvrir dossier" ou dossiers de startup.folders au lancement) —
    // désactivé par défaut, comme les autres auto-déclenchements du menu Tagger. Demandé le
    // 2026-08-12 : après un redémarrage, le taguage ne reprenait jamais tout seul, obligeant à
    // recliquer "Tagger" à chaque fois.
    public boolean autoTagOnScan()                 { return bool("tagging.auto_start_on_scan",  false); }
    // Défaut true (2026-08-16, demande utilisateur) : dernier recours quand AUCUNE méthode
    // (cache/MBID/AcoustID/SongRec/recherche texte MB/AudD) n'a rien confirmé, mais que les tags
    // déjà présents dans le fichier ont l'air valides (non vides, non génériques) — voir
    // TaggingWorker.findTags() fin de méthode. Contrairement au bug de confiance aveugle déjà
    // corrigé cette session (SongRec trust bug, qui acceptait des tags AVANT toute vérification),
    // celui-ci n'intervient qu'EN DERNIER ; score volontairement bas (voir son usage) et source
    // dédiée (MetadataCache.SOURCE_UNVERIFIED_TAGS) pour rester visible/filtrable séparément des
    // identifications réellement confirmées.
    public boolean trustReadableTagsFallback()     { return bool("tagging.trust_readable_tags_fallback", true); }
    // Détection DJ mix / long format (2026-08-16, demande utilisateur) — voir TaggingWorker.
    // findTags() étape 0.8. Défaut actif, seuil 20 min : sous ce seuil, un titre "long" (single
    // étendu, morceau classique...) reste traité normalement ; au-dessus, quasiment toujours un mix
    // continu/podcast/set live qui ne correspond à aucun enregistrement MB unique.
    public boolean djMixDetectionEnabled()         { return bool("tagging.dj_mix_detection_enabled", true); }
    public int     djMixMinDurationSec()           { return num("tagging.dj_mix_min_duration_sec", 1200); }
    // Défaut true (2026-08-15) : scheduleAutoSaveFollowUp() (MainFrame) existait déjà avant ce
    // réglage mais s'armait trop tard sur une grosse bibliothèque (voir son commentaire) — une fois
    // corrigé, activé par défaut pour préserver le comportement voulu à l'origine (jamais rien ne
    // reste IDENTIFIED en mémoire sans jamais être écrit). Désactivable pour repasser en contrôle
    // 100% manuel (façon Picard strict) si préféré — voir chkAutoSaveEnabled.
    public boolean autoSaveEnabled()               { return bool("tagging.auto_save_enabled",    true); }
    // Défaut true (2026-08-16, demande utilisateur) : synchronise les playlists sidecar (.m3u/
    // .m3u8/.pls) trouvées dans le dossier d'origine à chaque renommage/déplacement de fichier
    // (FileRenamer.moveFile — voir PlaylistSync). Constat réel motivant : plusieurs .pls de la
    // bibliothèque référençaient déjà un nom de fichier obsolète (mojibake jamais recorrigé), preuve
    // que ces playlists pourrissent silencieusement sans ce correctif.
    public boolean syncPlaylistsOnRename()         { return bool("tagging.sync_playlists_on_rename", true); }
    // Défaut true (2026-08-16, demande utilisateur, choix explicite "automatique dans la cascade"
    // plutôt qu'une action manuelle) : identification d'album entier par checksum de durées façon
    // "Albunack Disc IDs" de SongKong — voir TaggingWorker.findTags() étape 0.6 et
    // MusicBrainzClient.lookupByToc(), testé en direct contre l'API MusicBrainz publique.
    public boolean discIdMatchingEnabled()         { return bool("tagging.discid_matching_enabled", true); }
    // Nombre minimal de pistes consécutives (TrackNo 1..N sans trou) requis dans un dossier avant
    // de tenter un lookup TOC — sous ce seuil, le checksum porte sur trop peu de données pour être
    // discriminant (un simple single ou EP de 2 pistes produirait trop de faux positifs plausibles).
    public int     discIdMinTracks()               { return num("tagging.discid_min_tracks", 3); }
    // Défaut true (2026-08-16, demande utilisateur explicite "construit ça de façon auto") : devine
    // une URL Bandcamp depuis artiste+titre (jamais de recherche réelle — bloquée, voir
    // BandcampClient) en tout dernier recours dans TaggingWorker.findTags(), uniquement si RIEN
    // d'autre n'a identifié le fichier, et seulement appliqué si le contenu récupéré correspond
    // vraiment (TrackMatcher.titleSimilarity) — jamais de fausse donnée écrite sur un essai raté.
    public boolean bandcampGuessEnabled()          { return bool("tagging.bandcamp_guess_enabled", true); }
    // Substitution de préfixe pour convertir un chemin "Location" de l'XML iTunes (souvent un
    // lecteur Windows, ex. "C:/Users/xxx/OneDrive/Musiques") vers le point de montage réel sur ce
    // système (ex. "/mnt/Music") — voir ITunesLibraryImporter.resolveLocalPath(). Vide par défaut
    // (aucune substitution) : l'utilisateur doit le configurer une fois pour son propre système,
    // même logique que le script itunes_path_updater.py déjà utilisé pour ce même problème.
    public String  itunesXmlPathFrom()             { return str("itunes.xml_path_from", ""); }
    public String  itunesXmlPathTo()               { return str("itunes.xml_path_to",   ""); }
    // Chemin du fichier XML lui-même — mémorisé pour ne pas le re-choisir via JFileChooser à
    // chaque import/écriture (demande utilisateur 2026-08-16). Modifiable dans Préférences >
    // iTunes, et mis à jour automatiquement dès qu'un fichier est choisi dans ITunesImportDialog/
    // MainFrame.writeItunesXmlCorrections.
    public String  itunesXmlFilePath()             { return str("itunes.xml_file_path", ""); }
    public boolean preserveCompilationAlbum()      { return bool("tags.preserve_compilation",     true); }
    public boolean trustExistingMbTags()           { return bool("tags.trust_existing_mb_tags",   true); }
    // Compromis vitesse/fiabilité demandé le 2026-07-17 : SongRec (empreinte audio) est la source
    // principale de TaggingWorker.findTags() par design (identifie même avec des tags/dossiers
    // pourris — voir le commentaire de classe) mais coûte plusieurs secondes par fichier. Off par
    // défaut : ne change rien tant que l'utilisateur ne l'active pas explicitement. Une fois activé,
    // un fichier avec artiste+titre exploitables dans ses tags (pas génériques, pas venant du nom de
    // dossier) tente une recherche MB texte rapide AVANT SongRec ; si le score dépasse ce seuil, le
    // résultat est gardé tel quel et SongRec est sauté pour ce fichier.
    public boolean skipSongRecOnConfidentMb()      { return bool("tagging.skip_songrec_on_confident_mb", false); }
    public int     skipSongRecMinScore()           { return num ("tagging.skip_songrec_min_score",  90);    }

    /** 3 états au lieu de 2 cases à cocher séparées ("Compléter aussi les fichiers incomplets" +
     * "Compléter les albums automatiquement après le taguage") — fusionnées le 2026-07-16 à la
     * demande de l'utilisateur, qui trouvait qu'il y avait trop d'options au nom proche faisant
     * presque la même chose. Migre une fois depuis les deux anciennes clés booléennes pour ne pas
     * changer silencieusement un réglage déjà choisi. */
    public enum PostTagCompletion { NONE, FIELDS, FIELDS_AND_ALBUMS }

    public PostTagCompletion postTagCompletion() {
        String v = str("tagging.post_tag_completion", "");
        if (v.isBlank()) {
            boolean fields = bool("tagging.auto_complete_incomplete", false);
            boolean albums = bool("tagging.auto_complete_albums", false);
            PostTagCompletion migrated = !fields ? PostTagCompletion.NONE
                    : (albums ? PostTagCompletion.FIELDS_AND_ALBUMS : PostTagCompletion.FIELDS);
            set("tagging.post_tag_completion", migrated.name());
            return migrated;
        }
        try { return PostTagCompletion.valueOf(v); } catch (Exception e) { return PostTagCompletion.NONE; }
    }
    public void setPostTagCompletion(PostTagCompletion mode) { set("tagging.post_tag_completion", mode.name()); }
    public String[] startupFolders()   {
        String v = str("startup.folders");
        return v.isBlank() ? new String[0] : v.split("\\|");
    }

    public String userAgent() {
        return "OpenTagger/" + appVersion() + " (" + contact() + ")";
    }

    /**
     * Version affichée (titre fenêtre, splash, User-Agent). Priorité au manifeste du jar
     * (Implementation-Version, injecté depuis project.version à la construction — voir pom.xml)
     * plutôt qu'à settings.properties : ce fichier n'est écrit qu'une fois à la création de la
     * config utilisateur et restait figé à l'ancienne version après chaque mise à jour tant que
     * ce fichier existait déjà (constaté en direct : app.version=0.9.1 après une mise à jour vers
     * 0.9.3, dans un bac à sable créé avant la mise à jour). Le repli sur settings.properties ne
     * sert qu'en développement (lancement depuis target/classes, sans jar/manifeste réel).
     */
    public String appVersion() {
        String fromManifest = getClass().getPackage().getImplementationVersion();
        return fromManifest != null ? fromManifest : str("app.version", "0.1.0");
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

    // --- Paroles synchronisées (.lrc) --- même esprit opt-in que la pochette fichier ci-dessus.
    public boolean saveLrcFile()       { return bool("lyrics.save_lrc",      false); }

    // --- Tags préservés ---
    public String  preservedTags()     { return str ("tags.preserved_tags",  ""); }

    // --- Tags jamais modifiés --- différent de preservedTags ci-dessus (qui ne restaure l'ancienne
    // valeur QUE si la nouvelle est vide) : ici le champ garde SA valeur d'origine quoi qu'il arrive,
    // même si le taguage a trouvé une nouvelle valeur non vide (ex. protéger une RATING/COMMENT
    // éditée à la main d'un écrasement silencieux — voir TagWriter.readNeverModifyTags()).
    public String  neverModifyTags()   { return str ("tags.never_modify",    ""); }

    // --- Auto-capitalisation --- opt-in (désactivée par défaut : des artistes/titres stylisés
    // intentionnellement — "will.i.am", "MC5", "iamamiwhoami" — seraient sinon cassés sans que
    // l'utilisateur l'ait demandé). Voir TitleCaseFixer.fix(), appelé depuis TaggingWorker une fois
    // toutes les autres mutations (script, genre, translittération...) déjà appliquées.
    public boolean capitalizeEnabled()        { return bool("capitalize.enabled", false); }
    public String  capitalizeLowercaseWords() { return str("capitalize.lowercase_words",
            "de,le,la,les,du,des,et,ou,à,au,aux,the,of,and,or,in,on,at,to,vs"); }
    public String  capitalizeUppercaseWords() { return str("capitalize.uppercase_words", ""); }
    public String  capitalizeKeepPrefixes()   { return str("capitalize.keep_prefixes", "Mc,Mac,O'"); }

    // --- Portrait d'artiste --- opt-in, désactivé par défaut (même esprit que cover.save_to_file).
    // Sidecar dans le dossier ALBUM (pas le dossier artiste, qui varie selon le masque de
    // renommage actif — remonter d'un niveau serait fragile) — voir TagEnrichment.saveEntry().
    public boolean artistPhotoEnabled()  { return bool("artist_photo.enabled",  false); }
    public String  artistPhotoFilename() { return str ("artist_photo.filename", "artist"); }

    // --- Ponctuation & nettoyage ---
    public boolean correctPunctuation(){ return bool("tags.correct_punctuation", false); }
    public boolean removeId3v1()       { return bool("tags.remove_id3v1",        false); }

    // --- Pochettes locales ---
    public boolean coverSearchLocal()  { return bool("cover.search_local",       true); }

    // --- Barre d'outils secondaire personnalisable (façon Picard) ---
    // "saveAll" ("Enregistrer tout") N'EST PLUS ICI : promu bouton fixe de l'en-tête (demande
    // explicite, symétrique à "Rafraîchir" qui devient lui optionnel/déplaçable — voir
    // MainFrame.buildHeader()). Toute valeur "toolbar.actions" déjà persistée contenant "saveAll"
    // (installs qui l'avaient ajouté manuellement pendant la brève période où c'était une action
    // secondaire) est simplement ignorée sans erreur — comportement générique déjà en place pour
    // tout id inconnu (voir MainFrame.findToolbarAction()/SettingsDialog.load()).
    public static final String DEFAULT_TOOLBAR_ACTIONS = "refreshFolders,transcode,submitAcoustId,cdImport";
    public String[] toolbarActions() {
        String v = str("toolbar.actions", DEFAULT_TOOLBAR_ACTIONS);
        if (v.isBlank()) return new String[0];
        String[] actions = v.split(",");
        // Migration : "refreshFolders" n'existait pas comme action secondaire avant que
        // "Rafraîchir" devienne optionnel — toute valeur "toolbar.actions" déjà persistée (installs
        // existantes) datant d'avant cet ajout doit se voir prépendre "refreshFolders" une bonne
        // fois. Gardée par `toolbar.refresh_migrated` : SANS ce garde-fou, cette migration
        // s'exécutait à CHAQUE appel (pas seulement la première fois malgré ce que prétendait ce
        // commentaire) — retirer "Rafraîchir" dans les Préférences le sauvegardait bien sans lui,
        // mais le tout prochain appel à toolbarActions() (SettingsDialog.save() → MainFrame.
        // onPreferencesSaved() → populateSecondaryToolbar(), dans la même seconde) le réinjectait
        // aussitôt — ce bouton précis ne pouvait donc jamais être retiré, quoi que fasse
        // l'utilisateur dans l'UI. `toolbar.refresh_migrated` est écrit à chaque
        // SettingsDialog.save() (voir save()) : dès la première sauvegarde après cette version,
        // le choix de l'utilisateur (avec ou sans "Rafraîchir") devient définitivement autoritaire.
        if (!bool("toolbar.refresh_migrated", false)
                && Arrays.stream(actions).noneMatch("refreshFolders"::equals)) {
            String[] migrated = new String[actions.length + 1];
            migrated[0] = "refreshFolders";
            System.arraycopy(actions, 0, migrated, 1, actions.length);
            return migrated;
        }
        return actions;
    }

    // --- Fournisseurs de pochette : activation individuelle + ordre (façon Picard) ---
    public boolean caaReleaseEnabled()      { return bool("cover.caa_release_enabled",       true); }
    public boolean caaReleaseGroupEnabled() { return bool("cover.caa_release_group_enabled", true); }
    // Aucune clé requise (API publique Deezer) — activé par défaut, dernier recours texte
    // (artiste+album) pour les fichiers sans MBID exploitable, voir DeezerClient.
    public boolean deezerEnabled()          { return bool("deezer.enabled",                  true); }
    // Pochette déjà renvoyée par SongRec/Shazam au moment de l'identification (TagInfo.
    // shazamCoverUrl) — aucune requête réseau supplémentaire ici, juste télécharger l'URL déjà
    // en main ; utile notamment pour les fichiers identifiés par SongRec sans confirmation
    // MusicBrainz, pour lesquels CAA/FanArt ne peuvent structurellement rien renvoyer.
    public boolean shazamCoverEnabled()     { return bool("shazam.download_cover",           true); }
    public static final String DEFAULT_COVER_PROVIDER_ORDER = "caa_release,caa_release_group,shazam,local,fanart,deezer";
    public String[] coverProviderOrder() {
        String v = str("cover.provider_order", DEFAULT_COVER_PROVIDER_ORDER);
        java.util.List<String> order = new java.util.ArrayList<>(java.util.Arrays.asList(
                v.isBlank() ? DEFAULT_COVER_PROVIDER_ORDER.split(",") : v.split(",")));
        // Rattrape tout fournisseur connu absent d'une valeur persistée plus ancienne (ex. un
        // cover.provider_order sauvegardé avant l'ajout de "shazam") — sinon un nouveau fournisseur
        // par défaut n'est jamais essayé pour un utilisateur existant tant qu'il ne rouvre pas les
        // Préférences (même logique déjà utilisée par SettingsDialog.loadSettings()).
        for (String id : DEFAULT_COVER_PROVIDER_ORDER.split(","))
            if (!order.contains(id)) order.add(id);
        return order.toArray(new String[0]);
    }

    // --- MB genres max ---
    public int     mbMaxGenres()       { return num ("mb.max_genres",            3); }

    // --- ReplayGain ---
    public boolean replayGainEnabled() { return bool("replaygain.enabled",       false); }

    // --- Clé musicale ---
    // Camelot (8A/8B…) plutôt que la notation standard (Cm/F#…) — utile pour le mixage DJ
    // (compatibilité harmonique), voir comparaison avec OneTagger dans docs/files/.
    public boolean writeCamelotKey()   { return bool("audio.camelot_key",        false); }

    // --- Marqueur de taguage portable ---
    // Tag OT_TAGGEDDATE écrit dans le fichier lui-même (pas seulement le cache SQLite local) —
    // survit à un cache perdu/corrompu ou un fichier déplacé hors suivi. Toujours actif : aucune
    // raison de désactiver un marqueur purement additif, contrairement aux options ci-dessus qui
    // changent un format de sortie.

    // --- Commande post-traitement ---
    // Exécutée une fois à la fin d'un run de taguage complet (pas par fichier — bibliothèques de
    // 100k+ fichiers, un hook par fichier serait ingérable) — ex: déclencher un scan Plex, un
    // rebalance mergerfs. Chaîne vide = désactivé.
    // Conservée uniquement pour la migration automatique vers PostTagCommands (liste) — ne plus
    // écrire cette clé après la migration, voir PostTagCommands.load().
    public String  postTagCommand()    { return str ("hooks.post_tag_command",   ""); }

    // --- Scripts tagger (TaggerScript) ---
    // Case globale "Activer les scripts" façon Picard (enable_tagger_scripts) — coupe tous les
    // scripts d'un coup sans avoir à décocher chacun individuellement. Défaut true : ne change
    // rien pour qui avait déjà des scripts actifs avant l'ajout de cette case.
    public boolean scriptsEnabled()    { return bool("scripts.enabled",          true); }

    // --- Translittération artistes ---
    public boolean translateArtists()  { return bool("metadata.translate_artists", false); }

    /** Locales cibles pour l'alias de translittération, par ordre de priorité (ex: "fr,en") —
     *  essayées une à une avant le repli automatique sur "en" (voir MusicBrainzClient.
     *  lookupArtistAlias) : la plupart des alias de romanisation MusicBrainz sont tagués
     *  locale=en, peu importe la langue réellement préférée par l'utilisateur, donc une seule
     *  langue choisie manquait souvent sa cible — confirmé en direct (2026-07-10) : zéro
     *  traduction réussie avec juste "fr" configuré, malgré des dizaines d'artistes non-latins
     *  ayant en fait un alias "en" exploitable sur MusicBrainz. */
    public String[] translateLocales() {
        String v = str("metadata.translate_locale", "en");
        return v.isBlank() ? new String[]{"en"} : v.split(",");
    }

    /** Noms de séries de compilations (ex. "Stars 80", "NRJ", "Fun Radio", "RFM") — voir
     *  ui.CompilationClusterWorker ("Grouper par compilations…"), qui matche par défaut n'importe
     *  quelle release marquée "Compilation" par MusicBrainz (secondary-type officiel, automatique) ;
     *  cette liste ne sert plus qu'à ajouter en complément un nom de titre précis, pour les rares cas
     *  où MusicBrainz ne marquerait pas le secondary-type. Vide par défaut : la détection MB seule
     *  suffit, aucune liste requise. */
    public String[] compilationSeriesNames() {
        String v = str("compilation.series_names", "");
        return v.isBlank() ? new String[0] : v.split(",");
    }

    // --- Ordre de traitement : fichiers incomplets en priorité ---
    public boolean prioritizeIncomplete(){ return bool("batch.prioritize_incomplete", true); }

    // --- Transcodage audio ---
    public boolean transcodeAutoBeforeTag() { return bool("transcode.auto_before_tag", false); }
    public String  transcodeFormat()        { return str ("transcode.format",          "mp3"); }
    public int     transcodeBitrate()       { return num ("transcode.bitrate_kbps",    320);   }
    public boolean transcodeDeleteSource()  { return bool("transcode.delete_source",   false); }
    // Fichiers que ffmpeg refuse carrément d'ouvrir (moov atom manquant, flux corrompu, souvent
    // carrément 0 octet — dégâts collatéraux constatés de l'incident disque plein du 2026-07-15)
    // — confirmé par DEUX passes ffmpeg indépendantes (voir AudioTranscoder.verifyUnreadable()),
    // jamais sur la foi d'un seul message d'erreur. Désactivé par défaut : envoyés à la corbeille
    // système (récupérable), jamais supprimés définitivement, jamais sans activation explicite.
    public boolean transcodeMoveUnreadableEnabled() { return bool("transcode.move_unreadable_enabled", false); }

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

    /**
     * Score de préférence par type de release (Album, Compilation, Live...), format
     * "Type:score,Type:score" (ex. "Album:1.0,Compilation:0.3") — façon Picard
     * (picard/options.py: release_type_scores). Absent par défaut → carte vide → chaque type non
     * configuré reçoit 0.5 (neutre, voir ReleaseMatcher), aucun changement de comportement tant
     * que l'utilisateur ne personnalise rien. Pas encore d'éditeur dans Réglages (portée réduite,
     * voir le plan de cette tâche) — clé modifiable à la main dans settings.properties.
     */
    public java.util.Map<String, Double> releaseTypeScores() {
        java.util.Map<String, Double> map = new java.util.HashMap<>();
        String raw = str("releases.type_scores", "");
        if (raw.isBlank()) return map;
        for (String pair : raw.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                try { map.put(kv[0].trim(), Double.parseDouble(kv[1].trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return map;
    }

    // --- MusicBrainz OAuth ---
    public String mbToken()        { return str("mb.oauth.token"); }
    public String mbUsername()     { return str("mb.oauth.username"); }
    public String mbClientId()     { return str("mb.oauth.client_id"); }
    public String mbClientSecret() { return str("mb.oauth.client_secret"); }
    public boolean mbConnected()   { return !mbToken().isBlank(); }
    /** MBID de la collection MusicBrainz personnelle où ajouter les releases taguées (optionnel). */
    public String mbCollectionId() { return str("mb.oauth.collection_id", ""); }

    public String listenbrainzUsername()  { return str("listenbrainz.username", ""); }
    public int    listenbrainzMaxTracks() { return num("listenbrainz.max_tracks", 1000); }

    public String lastfmUsername()  { return str("lastfm.username", ""); }
    public int    lastfmMaxTracks() { return num("lastfm.max_tracks", 1000); }

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
        // Reader (pas InputStream) : props.load(InputStream) impose TOUJOURS ISO-8859-1 quel que
        // soit le contenu réel du fichier — voir le commentaire détaillé de loadUserConfig()
        // juste en dessous, même bug, même correctif.
        try (Reader r = new java.io.InputStreamReader(
                getClass().getResourceAsStream("/settings.properties"), java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (Exception e) {
            System.err.println("[Config] Impossible de charger les defauts : " + e.getMessage());
        }
    }

    private void loadUserConfig() {
        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) return;
        // Reader en UTF-8 (pas Files.newInputStream + props.load(InputStream)) : la surcharge
        // InputStream de Properties.load() est contractuellement figée en ISO-8859-1 (documenté
        // dans le Javadoc de Properties), alors que SettingsDialog.save() écrit via
        // Files.newBufferedWriter() — UTF-8 par défaut sous NIO.2. Résultat avant ce correctif :
        // toute valeur accentuée sauvegardée depuis les Préférences (ex. "à" dans une liste de
        // mots) revenait mojibake au chargement suivant ("Ã " au lieu de "à ", exactement le motif
        // que corrige EncodingFixer sur les tags). Repéré en direct (2026-08-14) sur
        // capitalize.lowercase_words juste après son premier enregistrement depuis l'UI.
        try (Reader r = Files.newBufferedReader(path, java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(r);
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
