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
    // "Instrument: Nom" par soliste, joints par "; " — relations MB "performer"/"instrument"/
    // "vocal" (2026-09-18, écart trouvé vs SongKong : ces relations existaient déjà dans les
    // réponses MB mais n'étaient jamais lues, contrairement à composer/conductor/orchestra...).
    public String performers          = "";
    // Écart trouvé vs OneTagger (2026-09-18, analyse du code source cloné + binaire local) : champ
    // dédié absent d'OpenTagger, alors que la bibliothèque contient de vrais remixes crédités MB
    // (ex. "Ride It (Lenjix remix)", vérifié en direct sur MusicBrainz — relation "remixer" réelle).
    public String remixer             = "";
    public String remixerSort         = "";
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

    // ── Informations artiste (biographie / vrai nom) ─────────────────────────
    // Cascade Discogs (profil, par nom exact) → Last.fm (bio.summary) en repli — voir
    // TagEnrichment.enrichArtistInfo(). Pas de FieldKey jaudiotagger dédié (aucun standard ID3/MP4
    // n'existe pour une biographie) : écrit en TXXX/atome freeform "ARTIST_BIO" via setCustomField()
    // (TagWriter), comme LISTENBRAINZ_PLAYCOUNT/OT_TAGGEDDATE.
    public String artistBio           = "";
    // Vrai nom derrière un nom de scène/pseudonyme (ex. "Robert Zimmerman" pour Bob Dylan) —
    // uniquement Discogs (champ "realname" de sa ressource /artists/{id}), Last.fm ne l'expose pas.
    public String artistRealName      = "";

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
    // Désambiguïsation MB, description de podcast, ou tag COMMENT déjà présent dans le fichier —
    // affiché/éditable dans DetailPanel (onglet Général).
    public String comment             = "";
    public String isrc                = "";
    public String amazonId            = "";
    public String discogsId           = "";
    public String appleMusicId        = "";    // adamid album (Shazam/SongRec) — pas de FieldKey dédié
    public String roonAlbumTag        = "";
    public String roonTrackTag        = "";
    public String acoustidId          = "";
    public String acoustidFingerprint = "";
    // Score de confiance AcoustID (0.0-1.0) du candidat ayant produit ce résultat — PAS un tag
    // fichier (aucun FieldKey, jamais écrit sur disque), seulement transitoire en mémoire pour que
    // TaggingWorker.acoustIdResultPlausible() puisse faire confiance à un match très fort même s'il
    // ne ressemble pas aux tags déjà présents (qui peuvent eux-mêmes être faux à la source — voir
    // AcoustIdClient.fetchBestFromMusicBrainz(), qui calculait déjà ce score puis le jetait avant
    // ce correctif, 2026-08-09).
    public double acoustidConfidence  = 0;

    // Marqueur "déjà tagué par OpenTagger" écrit directement dans le fichier (tag custom
    // OT_TAGGEDDATE, date ISO — voir TagWriter), en plus du suivi par MetadataCache (SQLite
    // local). Portable et lisible par n'importe quel outil, contrairement au cache — utile si le
    // cache est perdu/corrompu ou si le fichier est déplacé hors du suivi de l'appli (voir la
    // fragilité déjà rencontrée sur file_history après un rename externe). Non éditable : réécrit
    // à chaque enregistrement avec la date du jour, jamais lu depuis un champ UI.
    public String taggedDate          = "";

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
    // Types de parution MusicBrainz — primaire PUIS secondaires, minuscules, séparés par ";" (ex.
    // "album;live", "album;compilation;dj-mix"), comme le fait Picard (releasetype multi-valeur) ;
    // avant le 2026-09-20 seul le type primaire ("Album") était retenu, jamais "live"/"compilation"...
    public String releaseType         = "";
    public String originalYear        = "";    // Première année de parution
    // Dates COMPLÈTES telles que MusicBrainz les donne ("2014-05-15", "2014-05" ou "2014") — year/
    // originalYear restent toujours 4 chiffres (utilisés par le scoring, les masques de renommage...) ;
    // date/originalDate ne servent qu'à ce que le fichier reçoive la même précision que Picard.
    public String date                = "";
    public String originalDate        = "";

    // ── Tags que Picard écrit et qu'OpenTagger n'écrivait pas (2026-09-20) — voir TagFieldRegistry ──
    public String releaseTrackMbid    = "";    // "MusicBrainz Release Track Id" : la piste DANS cette parution
                                                // (≠ recordingMbid, l'enregistrement, partagé entre parutions)
    public String discSubtitle        = "";    // titre du disque (media[].title), ex. "Live at Wembley"
    public String discId              = "";    // "MusicBrainz Disc Id" (TOC du CD) — seulement si identifié par TOC
    public String originalReleaseMbid = "";    // "MusicBrainz Original Album Id"
    public String license             = "";    // URL de licence (relation MB "license", Bandcamp…)
    public String writer              = "";    // relation MB "writer"
    public String director            = "";    // relations MB "audio director"/"video director"
    public String copyright           = "";    // TCOP / cprt / COPYRIGHT
    public String subtitle            = "";    // TIT3 / SUBTITLE

    // ── Discogs (voir DiscogsClient) ─────────────────────────────────────────────────────────
    public String discogsMasterId     = "";    // id du "master release" Discogs (toutes éditions confondues)
    public String discogsArtistId     = "";
    public String discogsStyles       = "";    // styles Discogs bruts, ex. "Deep House, Tech House"
    public String discogsFormat       = "";    // ex. "Vinyl, 12\", 33 ⅓ RPM, EP"

    // ── Last.fm (voir LastFmClient) — hors playcount PERSONNEL (lastfmPlayCount, ci-dessus) ─────
    public String lastfmUrl           = "";    // page Last.fm de la piste
    public String lastfmListeners     = "";    // nombre d'auditeurs distincts (global)
    public String lastfmGlobalPlaycount = "";  // nombre d'écoutes total (global, tous utilisateurs)
    public String lastfmSimilarArtists = "";   // artistes similaires, séparés par "; "

    // ── Bandcamp (voir BandcampClient) ────────────────────────────────────────────────────────
    public String bandcampUrl         = "";
    // Label discographique + statut de parution + support physique/numérique — présents dans la
    // réponse MB (label-info[].label.name, status, media[].format) mais jamais extraits ni écrits
    // avant ce correctif : comparé côté à côté avec Picard sur un même fichier, ces 3 champs (plus
    // barcode/catalogNo ci-dessus, qui existaient déjà mais n'étaient jamais renseignés) restaient
    // vides dans OpenTagger alors que MusicBrainz les fournit pour la quasi-totalité des releases.
    public String label               = "";    // Label discographique
    public String releaseStatus       = "";    // Official, Bootleg, Promotion…
    public String media               = "";    // CD, Digital Media, Vinyl…

    // ── IDs MusicBrainz ──────────────────────────────────────────────────────
    public String artistMbid          = "";
    public String albumArtistMbid     = "";    // Id MusicBrainz de l'ARTISTE DE LA PARUTION — distinct
                                                // d'artistMbid (l'artiste de la PISTE) : les deux peuvent
                                                // diverger (piste avec featuring, compilation Various Artists…).
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
    public String lastfmPlayCount       = "";  // nombre d'écoutes Last.fm (TXXX:LASTFM_PLAYCOUNT)

    /** Vrai si `s` ressemble à un placeholder générique plutôt qu'à une vraie valeur d'identité
     *  (artiste/titre/album) — trop court pour être un nom réel ("1", "0", "-"), ou un des libellés
     *  par défaut classiques d'un encodeur/outil de rip ("Unknown Artist", "Various", "Track 01"...).
     *  Volontairement plus restreint que TaggingWorker.isGenericTag() (motifs de nom de fichier
     *  hors de propos ici) — utilisé par TagWriter.mergeWithExisting() et InfoCompleterWorker pour
     *  ne jamais faire confiance/perpétuer une valeur déjà cassée lue sur un fichier existant.
     *  Repéré en direct (2026-08-13) : TPE1=ARTIST="1" sur un fichier par ailleurs parfaitement
     *  identifié (TPE2/genre/ISRC/sort-names tous corrects) — recopié tel quel par
     *  InfoCompleterWorker faute de validation, jamais corrigé depuis. */
    public static boolean isGenericIdentityValue(String s) {
        if (s == null) return true;
        String low = s.trim().toLowerCase();
        // <= 1 caractère (pas 2) : "U2" est un vrai nom d'artiste à 2 caractères, ne pas le
        // rejeter sur la seule longueur. En revanche un très court nombre PUR ("1", "01") n'est
        // quasiment jamais un vrai nom — "311" (3 chiffres) reste accepté, lui.
        if (low.length() <= 1) return true;
        if (low.length() <= 2 && low.matches("\\d+")) return true;
        return low.matches("unknown artist|unknown|artist|artiste|various|various artists|"
                          + "no artist|inconnu|track \\d+|piste \\d+|untitled|titre|title");
    }

    private static final java.util.regex.Pattern ISO_DATE_PREFIX =
            java.util.regex.Pattern.compile("^(\\d{4}(?:-\\d{2}(?:-\\d{2})?)?)");

    /** "2014", "2014-05" ou "2014-05-15" (exactement, rien après) — le format que renvoie MusicBrainz. */
    public static boolean isIsoDate(String s) {
        return s != null && s.matches("\\d{4}(-\\d{2}(-\\d{2})?)?");
    }

    /** Valeur à écrire dans le champ "date" du fichier : la date complète si elle est connue ET
     *  cohérente avec {@link #year} (même année — sinon l'utilisateur a corrigé l'année à la main et
     *  une date MB périmée ne doit pas la contredire), sinon l'année seule comme avant. */
    public String dateForWrite() {
        String y = year == null ? "" : year.trim();
        String d = date == null ? "" : date.trim();
        if (isIsoDate(d) && (y.isBlank() || d.startsWith(y.length() >= 4 ? y.substring(0, 4) : y))) return d;
        return y;
    }

    /** Renseigne {@link #year} (4 chiffres) et {@link #date} (complète) depuis la valeur BRUTE d'un tag
     *  de fichier — un fichier déjà tagué par Picard peut contenir "2014-05-15" (voire avec heure,
     *  "2014-05-15T00:00"), qu'on ne veut plus perdre en ne gardant que l'année à la relecture. Valeur
     *  non reconnue comme une date → conservée telle quelle dans year, comme avant. */
    public void setYearFromRaw(String raw) {
        String r = raw == null ? "" : raw.trim();
        java.util.regex.Matcher m = ISO_DATE_PREFIX.matcher(r);
        if (m.find() && m.group(1).length() > 4) {
            year = m.group(1).substring(0, 4);
            date = m.group(1);
        } else {
            year = r;
        }
    }

    /** Comme {@link #setYearFromRaw} pour l'année/date d'ORIGINE : ID3v2.4 range la date d'origine
     *  complète dans TDOR ("1994-10-31"), qu'on ne veut pas laisser dans originalYear (4 chiffres). */
    public void setOriginalFromRaw(String raw) {
        String r = raw == null ? "" : raw.trim();
        java.util.regex.Matcher m = ISO_DATE_PREFIX.matcher(r);
        if (m.find() && m.group(1).length() > 4) {
            originalYear = m.group(1).substring(0, 4);
            if (originalDate == null || originalDate.isBlank()) originalDate = m.group(1);
        } else {
            originalYear = r;
        }
    }

    /**
     * Copie, dans les champs encore VIDES de cette instance, tout ce qui est propre à la PARUTION
     * (pas à la piste) depuis {@code r} — un TagInfo jetable issu de MusicBrainzClient
     * (parseReleaseTracklist → ReleaseTracklist.releaseMeta). Point de passage UNIQUE pour tous les
     * chemins qui identifient une piste depuis une tracklist déjà connue (TOC, cohérence de groupe,
     * complétion d'album, regroupement) : avant, chacun recopiait à la main SA propre sous-liste de
     * champs (TaggingWorker en oubliait plusieurs, AlbumCompletionWorker presque tous), d'où des
     * fichiers dont les tags de parution différaient selon LE CHEMIN qui les avait identifiés
     * (date complète, type de parution, label, pays... présents ou non au hasard).
     *
     * @return vrai si au moins un champ a réellement été rempli
     */
    public boolean applyReleaseLevelFrom(TagInfo r) {
        if (r == null) return false;
        boolean changed = false;
        String y4 = year != null && year.length() >= 4 ? year.substring(0, 4) : "";
        if (isBlank(date) && !isBlank(r.date) && (y4.isBlank() || r.date.startsWith(y4))) { date = r.date; changed = true; }
        if (isBlank(originalDate)     && !isBlank(r.originalDate))     { originalDate     = r.originalDate;     changed = true; }
        if (isBlank(originalYear)     && !isBlank(r.originalYear))     { originalYear     = r.originalYear;     changed = true; }
        if (isBlank(releaseType)      && !isBlank(r.releaseType))      { releaseType      = r.releaseType;      changed = true; }
        if (isBlank(country)          && !isBlank(r.country))          { country          = r.country;          changed = true; }
        if (isBlank(barcode)          && !isBlank(r.barcode))          { barcode          = r.barcode;          changed = true; }
        if (isBlank(releaseStatus)    && !isBlank(r.releaseStatus))    { releaseStatus    = r.releaseStatus;    changed = true; }
        if (isBlank(label)            && !isBlank(r.label))            { label            = r.label;            changed = true; }
        if (isBlank(catalogNo)        && !isBlank(r.catalogNo))        { catalogNo        = r.catalogNo;        changed = true; }
        if (isBlank(script)           && !isBlank(r.script))           { script           = r.script;           changed = true; }
        if (isBlank(language)         && !isBlank(r.language))         { language         = r.language;         changed = true; }
        if (isBlank(amazonId)         && !isBlank(r.amazonId))         { amazonId         = r.amazonId;         changed = true; }
        if (isBlank(albumArtistMbid)  && !isBlank(r.albumArtistMbid))  { albumArtistMbid  = r.albumArtistMbid;  changed = true; }
        if (isBlank(license)          && !isBlank(r.license))          { license          = r.license;          changed = true; }
        if (isBlank(originalReleaseMbid) && !isBlank(r.originalReleaseMbid)) { originalReleaseMbid = r.originalReleaseMbid; changed = true; }
        if (isBlank(releaseOfficialUrl)  && !isBlank(r.releaseOfficialUrl))  { releaseOfficialUrl  = r.releaseOfficialUrl;  changed = true; }
        if (isBlank(releaseWikipediaUrl) && !isBlank(r.releaseWikipediaUrl)) { releaseWikipediaUrl = r.releaseWikipediaUrl; changed = true; }
        if ("1".equals(r.isLive)         && !"1".equals(isLive))         { isLive         = "1"; changed = true; }
        if ("1".equals(r.isSoundtrack)   && !"1".equals(isSoundtrack))   { isSoundtrack   = "1"; changed = true; }
        if ("1".equals(r.isGreatestHits) && !"1".equals(isGreatestHits)) { isGreatestHits = "1"; changed = true; }
        if ("1".equals(r.isCompilation)  && !"1".equals(isCompilation))  { isCompilation  = "1"; changed = true; }
        return changed;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Copie superficielle — tous les champs String sont indépendants (immutables). */
    public TagInfo copy() {
        try {
            TagInfo c = new TagInfo();
            c.score = this.score;
            c.durationSec = this.durationSec;
            c.mbDurationSec = this.mbDurationSec;
            c.acoustidConfidence = this.acoustidConfidence;
            for (java.lang.reflect.Field f : TagInfo.class.getFields()) {
                if (f.getType() == String.class) f.set(c, f.get(this));
            }
            return c;
        } catch (Exception e) {
            return this;
        }
    }
}
