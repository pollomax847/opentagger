package com.opentagger.model;

/**
 * Reflète EXACTEMENT le modèle de données Jaikoz v12.6 :
 * tous les champs de jaikoz.properties (2252 lignes) + id3columns.properties.
 */
public class TagInfo {

    // ── Score de correspondance ──────────────────────────────────────────────
    public int    score               = 0;

    // ── Durée réelle (secondes) ────────────────────────────────────────────
    // PAS un tag — lue depuis AudioHeader.getTrackLength() (jaudiotagger), déjà disponible sans
    // coût I/O supplémentaire puisque AudioFileIO.read() est de toute façon appelé au scan pour
    // lire les tags (voir MainFrame.readTags()). 0 = inconnue/pas encore lue. Sert notamment à
    // repérer d'un coup d'œil les fichiers vides/corrompus (0:00) sans attendre un échec
    // d'écriture — trouvé en pratique un fichier .m4a de 322 octets, zéro flux audio, qui serait
    // resté invisible dans le tableau sans cette colonne.
    public int    durationSec         = 0;

    // Durée déclarée par MusicBrainz pour l'enregistrement identifié (champ "length", ms → sec) —
    // PAS un tag non plus, jamais écrit dans le fichier (TagWriter ne mappe que des FieldKey
    // explicites). Sert uniquement à la détection d'incohérence (voir TaggingWorker.
    // isDurationMismatch()) : un fichier bien plus court que ce que MusicBrainz annonce pour ce
    // titre suggère un rip tronqué ou une mauvaise identification. 0 = non renseigné par MB.
    public int    mbDurationSec       = 0;

    // ── Standard ────────────────────────────────────────────────────────────
    public String title               = "";
    public String artist              = "";
    public String albumArtist         = "";
    public String album               = "";
    public String year                = "";
    public String genre               = "";
    public String track               = "";
    public String trackTotal          = "";
    public String discNo              = "";
    public String discTotal           = "";

    // ── Listes d'artistes (séparées par \0 pour TXXX multi-valeur) ──────────
    public String artists             = "";    // tous les artistes piste (feat. inclus)
    public String artistsSort         = "";    // sort names correspondants

    // ── Tri ─────────────────────────────────────────────────────────────────
    public String titleSort           = "";
    public String artistSort          = "";
    public String albumSort           = "";
    public String albumArtistSort     = "";
    public String composerSort        = "";
    public String conductorSort       = "";
    public String orchestraSort       = "";
    public String ensembleSort        = "";
    public String choirSort           = "";
    public String lyricistSort        = "";
    public String producerSort        = "";
    public String arrangerSort        = "";

    // ── Compositeurs / contributeurs ─────────────────────────────────────────
    public String composer            = "";
    public String conductor           = "";
    public String orchestra           = "";
    public String ensemble            = "";
    public String choir               = "";
    public String lyricist            = "";
    public String producer            = "";
    public String arranger            = "";
    public String engineer            = "";
    public String mixer               = "";
    public String mixerSort           = "";
    public String djMixer             = "";

    // ── Classique — hiérarchie Work ──────────────────────────────────────────
    public String work                = "";    // MB Work title
    public String workMbid            = "";    // MB Work Id
    public String movement            = "";    // ex: "I. Allegro"
    public String movementNo          = "";    // ex: "1"
    public String movementTotal       = "";    // ex: "4"
    public String titleMovement       = "";    // titre court du mouvement
    public String part                = "";
    public String partType            = "";
    public String partNo              = "";
    public String period              = "";    // Baroque, Romantique…
    public String opus                = "";    // Op. 9, BWV 543…
    public String classicalCatalog    = "";    // BWV, K., etc.
    public String classicalNickname   = "";    // "Les Quatre Saisons"
    public String section             = "";    // opéra — MinimServer
    public String overallWork         = "";    // œuvre parente (opéra)
    public String grouping            = "";    // iTunes Grouping / Work

    // ── Flags ────────────────────────────────────────────────────────────────
    public String isClassical         = "0";
    public String isCompilation       = "0";
    public String isHD                = "0";
    public String isLive              = "0";
    public String isGreatestHits      = "0";
    public String isSoundtrack        = "0";
    public String isInstrumental      = "0";

    // ── Audio / tempo / tonalité ─────────────────────────────────────────────
    public String bpm                 = "";    // ex: "120" (entier)
    public String fbpm                = "";    // ex: "92.0984" (float précis Essentia)
    public String initialKey          = "";    // ex: "Cm", "F#"
    public String language            = "";    // ISO 639 ex: "eng"

    // ── ReplayGain ────────────────────────────────────────────────────────────
    public String replayGainTrackGain = "";    // ex: "-4.73 dB"
    public String replayGainTrackPeak = "";    // ex: "0.983547"
    public String replayGainAlbumGain = "";
    public String replayGainAlbumPeak = "";

    // ── Paroles ──────────────────────────────────────────────────────────────
    public String lyrics              = "";
    public String lyricsUrl           = "";
    // Paroles synchronisées brutes, format LRC ("[mm:ss.xx] ligne") — écrites en fichier .lrc à
    // côté de l'audio (TagEnrichment.saveEntry()), pas dans un tag embarqué : Plexamp et la
    // plupart des lecteurs lisent le sidecar .lrc, pas une frame ID3 SYLT (mal supportée partout,
    // contrairement au .lrc qui est un standard de facto). lrclib.net (voir LyricsClient) renvoie
    // déjà ces données mais elles étaient jusqu'ici jetées (timestamps retirés pour ne garder que
    // le texte brut dans `lyrics` ci-dessus) — ce champ les préserve en plus, sans rien changer au
    // comportement existant de `lyrics`.
    public String syncedLyrics        = "";

    // ── Rating & tags ────────────────────────────────────────────────────────
    public String rating              = "";    // 0-255 (ID3v2) ou 1-5
    public String tags                = "";    // mots-clés libres

    // ── Mood (Essentia) ──────────────────────────────────────────────────────
    public String mood                = "";    // label général
    public String moodAggressive      = "";    // "aggressive"/"not_aggressive"
    public String moodAcoustic        = "";
    public String moodElectronic      = "";
    public String moodHappy           = "";
    public String moodParty           = "";
    public String moodRelaxed         = "";
    public String moodSad             = "";
    public String moodValence         = "";    // "positive"/"negative"
    public String moodArousal         = "";
    public String moodDanceability    = "";    // "danceable"/"not_danceable"
    public String moodInstrumental    = "";    // "instrumental"/"vocal"

    // ── URLs ─────────────────────────────────────────────────────────────────
    public String artistOfficialUrl   = "";
    public String artistWikipediaUrl  = "";
    public String artistDiscogsUrl    = "";
    public String releaseOfficialUrl  = "";
    public String releaseWikipediaUrl = "";
    public String releaseDiscogsUrl   = "";
    // Pochette renvoyée directement par Shazam (SongRecClient) — l'identification a déjà trouvé
    // cette image, inutile de la re-chercher à l'aveugle via TagEnrichment.resolveCover() (voir
    // son fournisseur "shazam").
    public String shazamCoverUrl      = "";

    // ── IDs & identifiants ───────────────────────────────────────────────────
    public String comment             = "";    // MB disambiguation
    public String isrc                = "";
    public String amazonId            = "";
    public String discogsId           = "";
    public String appleMusicId        = "";    // adamid album (Shazam/SongRec) — pas de FieldKey dédié
    public String roonAlbumTag        = "";
    public String roonTrackTag        = "";
    public String acoustidId          = "";
    public String acoustidFingerprint = "";

    // Source de l'identification (MetadataCache.SOURCE_SONGREC/ACOUSTID/MBID/TEXT) — bookkeeping
    // interne, JAMAIS écrit dans le fichier (TagWriter ne mappe que des FieldKey explicites, pas
    // de réflexion sur TagInfo, donc ce champ est ignoré par l'écriture sans rien à faire de plus).
    // Existait avant seulement comme TaggingWorker.lastFindTagsSource (ThreadLocal, perdu dès la fin
    // de l'appel) — persisté ici pour survivre à la séparation Identifier/Enregistrer (potentiellement
    // des heures, voire une autre session, entre les deux), notamment pour la soumission AcoustID
    // conditionnelle à la source. Vide = source inconnue/non pertinente (repli sur SOURCE_TEXT).
    public String identificationSource = "";

    // ── Métadonnées release (Jaikoz TXXX) ───────────────────────────────────
    public String script              = "";    // Latin, Cyrillic, CJK…
    public String country             = "";    // code ISO-3166 : US, FR, GB…
    public String barcode             = "";
    public String catalogNo           = "";
    public String releaseType         = "";    // Album, Single, EP, Broadcast…
    public String originalYear        = "";    // Première année de parution

    // ── IDs MusicBrainz ──────────────────────────────────────────────────────
    public String artistMbid          = "";
    public String releaseGroupMbid    = "";
    public String releaseMbid         = "";
    public String recordingMbid       = "";

    // ── Podcast ──────────────────────────────────────────────────────────────
    public String podcastUrl          = "";    // URL du flux RSS (TXXX:PODCAST_URL)
    public String podcastSeason       = "";    // numéro de saison (TXXX:SEASON)
    public String podcastEpisode      = "";    // numéro d'épisode (TXXX:EPISODE)
    public String podcastEpisodeType  = "";    // full/trailer/bonus (TXXX:EPISODETYPE)
    public String podcastKeywords     = "";    // mots-clés (TXXX:KEYWORDS)

    // ── Statistiques d'écoute ────────────────────────────────────────────────
    public String listenbrainzPlayCount = "";  // nombre d'écoutes ListenBrainz (TXXX:LISTENBRAINZ_PLAYCOUNT)

    /** Copie superficielle — tous les champs String sont indépendants (immutables). */
    public TagInfo copy() {
        try {
            TagInfo c = new TagInfo();
            c.score = this.score;
            c.durationSec = this.durationSec;
            c.mbDurationSec = this.mbDurationSec;
            for (java.lang.reflect.Field f : TagInfo.class.getFields()) {
                if (f.getType() == String.class) f.set(c, f.get(this));
            }
            return c;
        } catch (Exception e) {
            return this;
        }
    }
}
