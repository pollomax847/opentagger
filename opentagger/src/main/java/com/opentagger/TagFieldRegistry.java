package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Table UNIQUE des tags "à plat" (un champ TagInfo ↔ un tag de fichier) qui ne sont pas déjà lus/écrits
 * à la main dans TagWriter/TagReader — créée le 2026-09-20 après un constat en deux temps :
 * <ol>
 *   <li>Picard écrit des tags qu'OpenTagger n'écrivait jamais (MusicBrainz Release Track Id, sous-titre de
 *       disque, id de disque, licence, writer/director, copyright…) — et le même écart existait côté
 *       Discogs/Last.fm/Bandcamp (ids, styles, format, auditeurs, similaires, URL…).</li>
 *   <li>Surtout : {@code MainFrame.readTags()} — documenté "même couverture que TagWriter" — NE RELISAIT PAS
 *       33 champs que TagWriter écrit pourtant (pays, script, code-barres, label, statut, type de parution,
 *       année d'origine, ReplayGain ×4, artistes multiples, drapeaux live/HD/soundtrack, ids Discogs/Apple,
 *       podcast…). Avec {@code tags.clear_existing_tags=true} (repart d'un tag vide), toute réécriture
 *       d'un fichier depuis son TagInfo lu au scan — synchro écoutes Last.fm/ListenBrainz, correction d'un
 *       champ, etc. — EFFAÇAIT silencieusement ces 33 champs.</li>
 * </ol>
 * Chaque entrée sert donc aux DEUX sens : lecture (tous les entrées, fusion "si vide") et écriture (seulement
 * celles marquées {@code writeHere}, les 33 anciennes étant déjà écrites à la main par TagWriter).
 * Un test aller-retour écriture→lecture sur un TagInfo entièrement rempli garantit qu'aucun champ ne redevient
 * "écrit mais jamais relu".
 */
public final class TagFieldRegistry {

    private TagFieldRegistry() {}

    /**
     * @param prop      nom du champ public de {@link TagInfo}
     * @param key       FieldKey jaudiotagger si le tag en a une (noms par format gérés par la lib, compatibles
     *                  Picard vérifiés en écrivant sur MP3/FLAC/OGG/M4A), sinon {@code null}
     * @param custom    nom du champ personnalisé (TXXX / atome freeform / commentaire Vorbis) quand pas de FieldKey
     * @param vorbis    nom de commentaire Vorbis pour le chemin ffmpeg (Opus/AAC/WV — voir FfmpegTagIO)
     * @param label     libellé français du panneau de détail ({@code null} = pas de champ dédié dans l'UI)
     * @param writeHere vrai = écrit par la boucle générique de TagWriter/FfmpegTagIO ; faux = déjà écrit à la
     *                  main ailleurs, l'entrée ne sert qu'à la RELECTURE
     */
    public record Entry(String prop, FieldKey key, String custom, String vorbis, String label, boolean writeHere) {}

    private static Entry read(String prop, FieldKey key, String custom, String vorbis) {
        return new Entry(prop, key, custom, vorbis, null, false);
    }
    private static Entry write(String prop, FieldKey key, String custom, String vorbis, String label) {
        return new Entry(prop, key, custom, vorbis, label, true);
    }

    public static final List<Entry> ALL = List.of(
        // ── NOUVEAUX : Picard (MusicBrainz) ──────────────────────────────────────────────────────
        write("releaseTrackMbid",    FieldKey.MUSICBRAINZ_RELEASE_TRACK_ID,    null, "MUSICBRAINZ_RELEASETRACKID", "ID MusicBrainz de la piste (parution) :"),
        write("discSubtitle",        FieldKey.DISC_SUBTITLE,                   null, "DISCSUBTITLE",               "Sous-titre du disque :"),
        write("discId",              FieldKey.MUSICBRAINZ_DISC_ID,             null, "MUSICBRAINZ_DISCID",         "ID MusicBrainz du disque (TOC) :"),
        write("originalReleaseMbid", FieldKey.MUSICBRAINZ_ORIGINAL_RELEASE_ID, null, "MUSICBRAINZ_ORIGINALALBUMID","ID MusicBrainz de la parution d'origine :"),
        write("copyright",           FieldKey.COPYRIGHT,                       null, "COPYRIGHT",                  "Copyright :"),
        write("subtitle",            FieldKey.SUBTITLE,                        null, "SUBTITLE",                   "Sous-titre :"),
        write("license",             null, "LICENSE",  "LICENSE",  "Licence :"),
        write("writer",              null, "Writer",   "WRITER",   "Auteur (writer) :"),
        write("director",            null, "DIRECTOR", "DIRECTOR", "Réalisateur :"),
        // Même donnée que "country" ("Country", convention Jaikoz), sous le nom que Picard utilise
        // ("MusicBrainz Album Release Country" / RELEASECOUNTRY) — pas de champ UI dédié.
        write("country",             FieldKey.MUSICBRAINZ_RELEASE_COUNTRY,     null, "RELEASECOUNTRY",             null),

        // ── NOUVEAUX : Discogs ───────────────────────────────────────────────────────────────────
        write("discogsMasterId",     null, "DISCOGS_MASTER_ID", "DISCOGS_MASTER_ID", "ID Discogs (master) :"),
        write("discogsArtistId",     null, "DISCOGS_ARTIST_ID", "DISCOGS_ARTIST_ID", "ID Discogs (artiste) :"),
        write("discogsStyles",       null, "DISCOGS_STYLES",    "DISCOGS_STYLES",    "Styles Discogs :"),
        write("discogsFormat",       null, "DISCOGS_FORMAT",    "DISCOGS_FORMAT",    "Format Discogs :"),

        // ── NOUVEAUX : Last.fm (hors playcount personnel, déjà géré) ───────────────────────────────
        write("lastfmUrl",              null, "LASTFM_URL",              "LASTFM_URL",              "Page Last.fm :"),
        write("lastfmListeners",        null, "LASTFM_LISTENERS",        "LASTFM_LISTENERS",        "Auditeurs Last.fm :"),
        write("lastfmGlobalPlaycount",  null, "LASTFM_GLOBAL_PLAYCOUNT", "LASTFM_GLOBAL_PLAYCOUNT", "Écoutes Last.fm (global) :"),
        write("lastfmSimilarArtists",   null, "LASTFM_SIMILAR_ARTISTS",  "LASTFM_SIMILAR_ARTISTS",  "Artistes similaires (Last.fm) :"),

        // ── NOUVEAUX : Bandcamp ──────────────────────────────────────────────────────────────────
        write("bandcampUrl",         null, "BANDCAMP_URL", "BANDCAMP_URL", "Page Bandcamp :"),

        // ── ANCIENS, déjà écrits à la main par TagWriter mais JAMAIS relus (voir Javadoc de classe) ──
        read("artists",        FieldKey.ARTISTS,       null, "ARTISTS"),
        read("artistsSort",    FieldKey.ARTISTS_SORT,  null, "ARTISTS_SORT"),
        read("mixerSort",      FieldKey.MIXER_SORT,    null, "MIXER_SORT"),
        read("fbpm",           FieldKey.FBPM,          null, "FBPM"),
        read("isHD",           FieldKey.IS_HD,             null, "IS_HD"),
        read("isLive",         FieldKey.IS_LIVE,           null, "LIVE"),
        read("isGreatestHits", FieldKey.IS_GREATEST_HITS,  null, "IS_GREATEST_HITS"),
        read("isSoundtrack",   FieldKey.IS_SOUNDTRACK,     null, "IS_SOUNDTRACK"),
        read("isInstrumental", null, "IS_INSTRUMENTAL", "IS_INSTRUMENTAL"),
        read("replayGainTrackGain", null, "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_GAIN"),
        read("replayGainTrackPeak", null, "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_TRACK_PEAK"),
        read("replayGainAlbumGain", null, "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_GAIN"),
        read("replayGainAlbumPeak", null, "REPLAYGAIN_ALBUM_PEAK", "REPLAYGAIN_ALBUM_PEAK"),
        read("discogsId",      null, "DISCOGS_RELEASE_ID", "DISCOGS_RELEASE_ID"),
        read("appleMusicId",   null, "APPLE_MUSIC_ID",     "APPLE_MUSIC_ID"),
        read("script",         FieldKey.SCRIPT,        null, "SCRIPT"),
        read("country",        FieldKey.COUNTRY,       null, "COUNTRY"),
        read("barcode",        FieldKey.BARCODE,       null, "BARCODE"),
        read("catalogNo",      FieldKey.CATALOG_NO,    null, "CATALOGNUMBER"),
        read("releaseType",    FieldKey.MUSICBRAINZ_RELEASE_TYPE,   null, "MUSICBRAINZ_ALBUMTYPE"),
        read("originalYear",   FieldKey.ORIGINAL_YEAR, null, "ORIGINAL YEAR"),
        read("originalDate",   null, "ORIGINALDATE",   "ORIGINALDATE"),
        read("label",          FieldKey.RECORD_LABEL,  null, "LABEL"),
        read("releaseStatus",  FieldKey.MUSICBRAINZ_RELEASE_STATUS, null, "MUSICBRAINZ_ALBUMSTATUS"),
        read("media",          FieldKey.MEDIA,         null, "MEDIA"),
        read("albumArtistMbid",FieldKey.MUSICBRAINZ_RELEASEARTISTID, null, "MUSICBRAINZ_ALBUMARTISTID"),
        read("podcastUrl",         null, "PODCAST_URL", "PODCAST_URL"),
        read("podcastSeason",      null, "SEASON",      "SEASON"),
        read("podcastEpisode",     null, "EPISODE",     "EPISODE"),
        read("podcastEpisodeType", null, "EPISODETYPE", "EPISODETYPE"),
        read("podcastKeywords",    null, "KEYWORDS",    "KEYWORDS"),
        read("listenbrainzPlayCount", null, "LISTENBRAINZ_PLAYCOUNT", "LISTENBRAINZ_PLAYCOUNT"),
        read("lastfmPlayCount",       null, "LASTFM_PLAYCOUNT",        "LASTFM_PLAYCOUNT")
    );

    /** Drapeaux "0"/"1" : la valeur par défaut de TagInfo est "0" (jamais vide) — une lecture "1" doit donc
     *  écraser ce "0", contrairement aux autres champs où seule une valeur VIDE est remplie. */
    private static final Set<String> FLAGS = Set.of("isHD", "isLive", "isGreatestHits", "isSoundtrack", "isInstrumental",
                                                     "isClassical", "isCompilation");

    /**
     * Champs "de base" que FfmpegTagIO.write() écrit à la main (nom de commentaire Vorbis) mais que
     * FfmpegTagIO.read() ne relisait pas — même défaut que MainFrame.readTags(), pour Opus/AAC/WV/WAV :
     * une réécriture (-map_metadata -1) effaçait tout ce qui n'était pas relu. Générée depuis les
     * {@code meta(cmd, "NOM", i.champ)} de write() : {champ TagInfo, nom Vorbis}.
     */
    private static final String[][] FFMPEG_CORE = {
        {"performers", "PERFORMER"},
        {"remixer", "REMIXER"},
        {"workMbid", "MUSICBRAINZ_WORKID"},
        {"titleSort", "TITLESORT"},
        {"artistSort", "ARTISTSORT"},
        {"albumSort", "ALBUMSORT"},
        {"albumArtistSort", "ALBUMARTISTSORT"},
        {"composerSort", "COMPOSERSORT"},
        {"conductorSort", "CONDUCTOR_SORT"},
        {"orchestraSort", "ORCHESTRA_SORT"},
        {"ensembleSort", "ENSEMBLE_SORT"},
        {"choirSort", "CHOIR_SORT"},
        {"lyricistSort", "LYRICIST_SORT"},
        {"producerSort", "PRODUCER_SORT"},
        {"arrangerSort", "ARRANGER_SORT"},
        {"conductor", "CONDUCTOR"},
        {"orchestra", "ORCHESTRA"},
        {"ensemble", "ENSEMBLE"},
        {"choir", "CHOIR"},
        {"lyricist", "LYRICIST"},
        {"producer", "PRODUCER"},
        {"arranger", "ARRANGER"},
        {"engineer", "ENGINEER"},
        {"mixer", "MIXER"},
        {"djMixer", "DJMIXER"},
        {"work", "WORK"},
        {"movement", "MOVEMENT"},
        {"movementNo", "MOVEMENT_NO"},
        {"movementTotal", "MOVEMENT_TOTAL"},
        {"titleMovement", "TITLE_MOVEMENT"},
        {"part", "PART"},
        {"partType", "PART_TYPE"},
        {"partNo", "PARTNUMBER"},
        {"period", "PERIOD"},
        {"opus", "OPUS"},
        {"classicalCatalog", "CLASSICAL_CATALOG"},
        {"classicalNickname", "CLASSICAL_NICKNAME"},
        {"section", "SECTION"},
        {"overallWork", "OVERALL_WORK"},
        {"grouping", "GROUPING"},
        {"isClassical", "IS_CLASSICAL"},
        {"isCompilation", "COMPILATION"},
        {"initialKey", "KEY"},
        {"language", "LANGUAGE"},
        {"mood", "MOOD"},
        {"moodAggressive", "MOOD_AGGRESSIVE"},
        {"moodAcoustic", "MOOD_ACOUSTIC"},
        {"moodElectronic", "MOOD_ELECTRONIC"},
        {"moodHappy", "MOOD_HAPPY"},
        {"moodParty", "MOOD_PARTY"},
        {"moodRelaxed", "MOOD_RELAXED"},
        {"moodSad", "MOOD_SAD"},
        {"moodValence", "MOOD_VALENCE"},
        {"moodArousal", "MOOD_AROUSAL"},
        {"moodDanceability", "MOOD_DANCEABILITY"},
        {"moodInstrumental", "MOOD_INSTRUMENTAL"},
        {"lyrics", "LYRICS"},
        {"lyricsUrl", "URL_LYRICS_SITE"},
        {"rating", "RATING"},
        {"tags", "TAGS"},
        {"amazonId", "ASIN"},
        {"roonAlbumTag", "ROONALBUMTAG"},
        {"roonTrackTag", "ROONTRACKTAG"},
        {"acoustidId", "ACOUSTID_ID"},
        {"acoustidFingerprint", "ACOUSTID_FINGERPRINT"},
        {"artistOfficialUrl", "URL_OFFICIAL_ARTIST_SITE"},
        {"artistWikipediaUrl", "URL_WIKIPEDIA_ARTIST_SITE"},
        {"artistDiscogsUrl", "URL_DISCOGS_ARTIST_SITE"},
        {"releaseOfficialUrl", "URL_OFFICIAL_RELEASE_SITE"},
        {"releaseWikipediaUrl", "URL_WIKIPEDIA_RELEASE_SITE"},
        {"releaseDiscogsUrl", "URL_DISCOGS_RELEASE_SITE"},
        {"artistBio", "ARTIST_BIO"},
        {"artistRealName", "ARTIST_REALNAME"}
    };

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private static Field field(String prop) {
        return FIELDS.computeIfAbsent(prop, p -> {
            try { return TagInfo.class.getField(p); } catch (NoSuchFieldException e) {
                throw new IllegalStateException("TagFieldRegistry : champ TagInfo inconnu : " + p, e);
            }
        });
    }

    public static String get(TagInfo ti, String prop) {
        try { Object v = field(prop).get(ti); return v == null ? "" : (String) v; }
        catch (IllegalAccessException e) { return ""; }
    }

    private static void set(TagInfo ti, String prop, String value) { put(ti, prop, value); }

    /** Affecte un champ TagInfo par son nom (panneau de détail : construction du TagInfo édité). */
    public static void put(TagInfo ti, String prop, String value) {
        try { field(prop).set(ti, value == null ? "" : value); } catch (IllegalAccessException ignored) {}
    }

    // ── Lecture depuis un Tag jaudiotagger (MP3/FLAC/OGG/M4A/…) ─────────────────────────────────

    /** Remplit, dans {@code ti}, chaque champ de la table encore VIDE depuis le tag du fichier — jamais
     *  d'écrasement d'une valeur déjà lue par la lecture "à la main" (même sémantique que mergeWithExisting). */
    public static void readInto(Tag tag, TagInfo ti) {
        if (tag == null) return;
        for (Entry e : ALL) {
            String v;
            try {
                v = e.key() != null ? tag.getFirst(e.key()) : TagWriter.getCustomField(tag, e.custom());
            } catch (Exception ex) { continue; }
            apply(ti, e, v);
        }
    }

    // ── Lecture depuis des commentaires Vorbis/ffprobe (Opus/AAC/WV/WAV — voir FfmpegTagIO) ─────

    /** {@code upperTags} : clés en MAJUSCULES (voir FfmpegTagIO.collectTags). */
    public static void readInto(Map<String, String> upperTags, TagInfo ti) {
        for (Entry e : ALL) {
            if (e.vorbis() == null) continue;
            apply(ti, e, upperTags.get(e.vorbis().toUpperCase()));
        }
        for (String[] c : FFMPEG_CORE) {
            apply(ti, new Entry(c[0], null, null, c[1], null, false), upperTags.get(c[1].toUpperCase()));
        }
    }

    private static void apply(TagInfo ti, Entry e, String raw) {
        if (raw == null) return;
        String v = raw.trim();
        if (v.isEmpty()) return;
        if (FLAGS.contains(e.prop())) {
            if (("1".equals(v) || "true".equalsIgnoreCase(v)) && !"1".equals(get(ti, e.prop()))) set(ti, e.prop(), "1");
            return;
        }
        if ("originalYear".equals(e.prop())) {
            // ID3v2.4 met la date d'origine COMPLÈTE dans TDOR — voir TagInfo.setOriginalFromRaw().
            if (get(ti, "originalYear").isBlank()) ti.setOriginalFromRaw(v);
            return;
        }
        if (get(ti, e.prop()).isBlank()) set(ti, e.prop(), v);
    }
}
