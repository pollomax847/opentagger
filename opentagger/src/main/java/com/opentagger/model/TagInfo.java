package com.opentagger.model;

/**
 * Reflète EXACTEMENT le modèle de données Jaikoz v12.6 :
 * tous les champs de jaikoz.properties (2252 lignes) + id3columns.properties.
 */
public class TagInfo {

    // ── Score de correspondance ──────────────────────────────────────────────
    public int    score               = 0;

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

    // ── Paroles ──────────────────────────────────────────────────────────────
    public String lyrics              = "";
    public String lyricsUrl           = "";

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

    // ── IDs & identifiants ───────────────────────────────────────────────────
    public String comment             = "";    // MB disambiguation
    public String isrc                = "";
    public String amazonId            = "";
    public String discogsId           = "";
    public String roonAlbumTag        = "";
    public String roonTrackTag        = "";
    public String acoustidId          = "";
    public String acoustidFingerprint = "";

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

    /** Copie superficielle — tous les champs String sont indépendants (immutables). */
    public TagInfo copy() {
        try {
            TagInfo c = new TagInfo();
            c.score = this.score;
            for (java.lang.reflect.Field f : TagInfo.class.getFields()) {
                if (f.getType() == String.class) f.set(c, f.get(this));
            }
            return c;
        } catch (Exception e) {
            return this;
        }
    }
}
