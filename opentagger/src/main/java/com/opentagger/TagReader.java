package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;

/**
 * Lecture COMPLÈTE des tags d'un fichier audio vers un {@link TagInfo} — extrait de MainFrame.readTags()
 * (2026-09-20) pour être utilisable et testable hors de l'interface graphique : un test aller-retour
 * "écrire un TagInfo entièrement rempli puis le relire" est la seule vraie garantie qu'aucun champ
 * n'est écrit sans jamais être relu (voir {@link TagFieldRegistry}).
 */
public final class TagReader {

    private TagReader() {}

    /** Lit les champs d'un fichier audio — même couverture que TagWriter. */
    public static TagInfo read(File f) {
        // Opus/AAC/WV/APE : jaudiotagger ne sait pas les lire du tout (voir FfmpegTagIO) — inutile
        // de tenter AudioFileIO.read() en sachant qu'il va échouer.
        if (com.opentagger.FfmpegTagIO.handles(f)) return com.opentagger.FfmpegTagIO.read(f);
        TagInfo ti = new TagInfo();
        AudioFile af = null;
        try {
            af = AudioFileIO.read(f);
        } catch (Exception ignored) {}
        // En-tête audio (durée, débit...) indépendant du tag lui-même, un fichier sans AUCUN tag a
        // quand même une durée — et un fichier dont AudioFileIO.read() plante entièrement (en-tête
        // ID3/atom abîmé mais fichier par ailleurs parfaitement lisible/jouable) mérite quand même
        // qu'on essaie de lui trouver une durée : avant ce correctif, un AudioFileIO.read() en échec
        // sautait purement et simplement la sonde ffprobe ci-dessous, laissant durationSec à 0.
        if (af != null) {
            try {
                if (af.getAudioHeader() != null) ti.durationSec = af.getAudioHeader().getTrackLength();
            } catch (Exception ignored) {}
        }
        // jaudiotagger renvoie parfois 0 pour un .m4a/AAC structurellement valide (constaté en
        // direct : un fichier de 7,8 Mo, flux AAC de 3:56 confirmé par ffprobe, mais
        // getTrackLength()==0 — probablement un souci de parsing des atomes mvhd/mdhd/stts pour
        // certains encodeurs). Grave : durationSec==0 est LE signal utilisé ailleurs pour repérer
        // les fichiers vides/corrompus (voir le commentaire sur ce champ dans TagInfo.java) — un
        // faux 0 fait donc passer un fichier parfaitement bon pour cassé. Contre-vérification via
        // ffprobe (lecture des métadonnées du conteneur seulement, pas un décodage complet — coût
        // négligeable), déclenchée dès que durationSec est encore à 0 à ce stade — que ce soit parce
        // que getTrackLength() a renvoyé 0, ou parce qu'AudioFileIO.read() a échoué plus haut.
        if (ti.durationSec <= 0) {
            int probed = com.opentagger.AudioDuration.probeSeconds(f.getAbsolutePath());
            if (probed > 0) ti.durationSec = probed;
        }
        if (af == null) return ti;
        try {
            Tag tag = af.getTag();
            if (tag == null) return ti;

            // ── Standard ──────────────────────────────────────────────────
            ti.title          = g(tag, FieldKey.TITLE);
            ti.artist         = g(tag, FieldKey.ARTIST);
            ti.albumArtist    = g(tag, FieldKey.ALBUM_ARTIST);
            ti.album          = g(tag, FieldKey.ALBUM);
            ti.setYearFromRaw(g(tag, FieldKey.YEAR));
            ti.track          = g(tag, FieldKey.TRACK);
            ti.trackTotal     = g(tag, FieldKey.TRACK_TOTAL);
            ti.genre          = g(tag, FieldKey.GENRE);
            ti.discNo         = g(tag, FieldKey.DISC_NO);
            ti.discTotal      = g(tag, FieldKey.DISC_TOTAL);
            ti.comment        = comment(tag);

            // ── Tri ───────────────────────────────────────────────────────
            ti.titleSort      = g(tag, FieldKey.TITLE_SORT);
            ti.artistSort     = g(tag, FieldKey.ARTIST_SORT);
            ti.albumSort      = g(tag, FieldKey.ALBUM_SORT);
            ti.albumArtistSort= g(tag, FieldKey.ALBUM_ARTIST_SORT);
            ti.composerSort   = g(tag, FieldKey.COMPOSER_SORT);
            ti.conductorSort  = g(tag, FieldKey.CONDUCTOR_SORT);
            ti.orchestraSort  = g(tag, FieldKey.ORCHESTRA_SORT);
            ti.ensembleSort   = g(tag, FieldKey.ENSEMBLE_SORT);
            ti.choirSort      = g(tag, FieldKey.CHOIR_SORT);
            ti.lyricistSort   = g(tag, FieldKey.LYRICIST_SORT);
            ti.producerSort   = g(tag, FieldKey.PRODUCER_SORT);
            ti.arrangerSort   = g(tag, FieldKey.ARRANGER_SORT);

            // ── Contributeurs ─────────────────────────────────────────────
            ti.composer       = g(tag, FieldKey.COMPOSER);
            ti.conductor      = g(tag, FieldKey.CONDUCTOR);
            ti.orchestra      = g(tag, FieldKey.ORCHESTRA);
            ti.ensemble       = g(tag, FieldKey.ENSEMBLE);
            ti.choir          = g(tag, FieldKey.CHOIR);
            ti.lyricist       = g(tag, FieldKey.LYRICIST);
            ti.producer       = g(tag, FieldKey.PRODUCER);
            ti.arranger       = g(tag, FieldKey.ARRANGER);
            ti.engineer       = g(tag, FieldKey.ENGINEER);
            ti.mixer          = g(tag, FieldKey.MIXER);
            ti.djMixer        = g(tag, FieldKey.DJMIXER);
            ti.performers     = g(tag, FieldKey.PERFORMER);
            ti.remixer        = g(tag, FieldKey.REMIXER);

            // ── Classique ─────────────────────────────────────────────────
            ti.work           = g(tag, FieldKey.WORK);
            ti.workMbid       = g(tag, FieldKey.MUSICBRAINZ_WORK_ID);
            ti.movement       = g(tag, FieldKey.MOVEMENT);
            ti.movementNo     = g(tag, FieldKey.MOVEMENT_NO);
            ti.movementTotal  = g(tag, FieldKey.MOVEMENT_TOTAL);
            ti.titleMovement  = g(tag, FieldKey.TITLE_MOVEMENT);
            ti.part           = g(tag, FieldKey.PART);
            ti.partType       = g(tag, FieldKey.PART_TYPE);
            ti.partNo         = g(tag, FieldKey.PART_NUMBER);
            ti.period         = g(tag, FieldKey.PERIOD);
            ti.opus           = g(tag, FieldKey.OPUS);
            ti.classicalCatalog   = g(tag, FieldKey.CLASSICAL_CATALOG);
            ti.classicalNickname  = g(tag, FieldKey.CLASSICAL_NICKNAME);
            ti.section        = g(tag, FieldKey.SECTION);
            ti.overallWork    = g(tag, FieldKey.OVERALL_WORK);
            ti.grouping       = g(tag, FieldKey.GROUPING);

            // ── Flags ─────────────────────────────────────────────────────
            String isCl = g(tag, FieldKey.IS_CLASSICAL);
            if ("1".equals(isCl) || "true".equalsIgnoreCase(isCl)) ti.isClassical = "1";
            String isCo = g(tag, FieldKey.IS_COMPILATION);
            if ("1".equals(isCo) || "true".equalsIgnoreCase(isCo)) ti.isCompilation = "1";

            // ── Audio ─────────────────────────────────────────────────────
            ti.bpm            = g(tag, FieldKey.BPM);
            ti.initialKey     = g(tag, FieldKey.KEY);
            ti.language       = g(tag, FieldKey.LANGUAGE);

            // ── Paroles ───────────────────────────────────────────────────
            ti.lyrics         = g(tag, FieldKey.LYRICS);
            ti.lyricsUrl      = g(tag, FieldKey.URL_LYRICS_SITE);

            // ── Rating / Tags ─────────────────────────────────────────────
            ti.rating         = g(tag, FieldKey.RATING);
            ti.tags           = g(tag, FieldKey.TAGS);

            // ── Mood ──────────────────────────────────────────────────────
            ti.mood               = g(tag, FieldKey.MOOD);
            ti.moodAggressive     = g(tag, FieldKey.MOOD_AGGRESSIVE);
            ti.moodAcoustic       = g(tag, FieldKey.MOOD_ACOUSTIC);
            ti.moodElectronic     = g(tag, FieldKey.MOOD_ELECTRONIC);
            ti.moodHappy          = g(tag, FieldKey.MOOD_HAPPY);
            ti.moodParty          = g(tag, FieldKey.MOOD_PARTY);
            ti.moodRelaxed        = g(tag, FieldKey.MOOD_RELAXED);
            ti.moodSad            = g(tag, FieldKey.MOOD_SAD);
            ti.moodValence        = g(tag, FieldKey.MOOD_VALENCE);
            ti.moodArousal        = g(tag, FieldKey.MOOD_AROUSAL);
            ti.moodDanceability   = g(tag, FieldKey.MOOD_DANCEABILITY);
            ti.moodInstrumental   = g(tag, FieldKey.MOOD_INSTRUMENTAL);

            // ── URLs ──────────────────────────────────────────────────────
            ti.artistOfficialUrl  = g(tag, FieldKey.URL_OFFICIAL_ARTIST_SITE);
            ti.artistWikipediaUrl = g(tag, FieldKey.URL_WIKIPEDIA_ARTIST_SITE);
            ti.artistDiscogsUrl   = g(tag, FieldKey.URL_DISCOGS_ARTIST_SITE);
            ti.releaseOfficialUrl = g(tag, FieldKey.URL_OFFICIAL_RELEASE_SITE);
            ti.releaseWikipediaUrl= g(tag, FieldKey.URL_WIKIPEDIA_RELEASE_SITE);
            ti.releaseDiscogsUrl  = g(tag, FieldKey.URL_DISCOGS_RELEASE_SITE);

            // ── IDs ───────────────────────────────────────────────────────
            ti.isrc               = g(tag, FieldKey.ISRC);
            ti.amazonId           = g(tag, FieldKey.AMAZON_ID);
            ti.roonAlbumTag       = g(tag, FieldKey.ROONALBUMTAG);
            ti.roonTrackTag       = g(tag, FieldKey.ROONTRACKTAG);
            ti.acoustidId         = g(tag, FieldKey.ACOUSTID_ID);
            ti.acoustidFingerprint= g(tag, FieldKey.ACOUSTID_FINGERPRINT);

            // ── IDs MusicBrainz ───────────────────────────────────────────
            ti.artistMbid         = g(tag, FieldKey.MUSICBRAINZ_ARTISTID);
            ti.releaseMbid        = g(tag, FieldKey.MUSICBRAINZ_RELEASEID);
            ti.recordingMbid      = g(tag, FieldKey.MUSICBRAINZ_TRACK_ID);
            ti.releaseGroupMbid   = g(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);

            // ── Marqueur de taguage (portable, indépendant du cache SQLite) ──
            ti.taggedDate         = TagWriter.getCustomField(tag, "OT_TAGGEDDATE");

            // ── Informations artiste (Discogs/Last.fm) ───────────────────────
            ti.artistBio          = TagWriter.getCustomField(tag, "ARTIST_BIO");
            ti.artistRealName     = TagWriter.getCustomField(tag, "ARTIST_REALNAME");

            // ── Tout le reste (pays, script, code-barres, label, statut, type de parution, année/date
            // d'origine, ReplayGain, artistes multiples, drapeaux, ids Discogs/Apple, podcast, écoutes…
            // + les tags Picard/Discogs/Last.fm/Bandcamp) — voir TagFieldRegistry pour le pourquoi : cette
            // méthode ne relisait que ~90 des ~120 champs écrits, d'où des effacements silencieux à la
            // première réécriture depuis le TagInfo lu au scan.
            TagFieldRegistry.readInto(tag, ti);

        } catch (Exception ignored) {}
        return ti;
    }

    /** getFirst avec protection NPE et chaîne vide par défaut. */
    /** Commentaire RÉEL d'un fichier. Les MP3 passés par iTunes portent d'abord des cadres COMM techniques
     *  ({@code iTunNORM} : « 00000000 00000210 0000093C… », {@code iTunSMPB}, {@code iTunPGAP}…) ; {@code getFirst(COMMENT)}
     *  renvoyait celui-là, d'où un « commentaire » illisible dans l'appli pour tous ces fichiers. On prend le premier
     *  cadre COMM dont la description n'est pas une valeur technique iTunes. */
    static String comment(Tag tag) {
        if (tag instanceof org.jaudiotagger.tag.id3.AbstractID3v2Tag id3) {
            try {
                // getFields(COMMENT) et non getFields("COMM") : en ID3v2.2 l'identifiant du cadre est « COM ».
                for (org.jaudiotagger.tag.TagField f : id3.getFields(FieldKey.COMMENT)) {
                    if (f instanceof org.jaudiotagger.tag.id3.AbstractID3v2Frame fr
                            && fr.getBody() instanceof org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM b) {
                        String desc = b.getDescription() == null ? "" : b.getDescription();
                        if (desc.regionMatches(true, 0, "iTun", 0, 4)) continue;   // iTunNORM, iTunSMPB, iTunPGAP, iTunes_CDDB_IDs…
                        String text = b.getText();
                        if (text != null && !text.isBlank()) return text;
                    }
                }
                return "";
            } catch (Exception ignored) { /* repli ci-dessous */ }
        }
        return g(tag, FieldKey.COMMENT);
    }

    private static String g(Tag tag, FieldKey key) {
        try { String v = tag.getFirst(key); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }
}
