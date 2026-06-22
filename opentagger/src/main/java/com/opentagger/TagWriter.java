package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class TagWriter {

    public void write(File fichier, TagInfo info) throws Exception {
        write(fichier, info, null);
    }

    public void write(File fichier, TagInfo info, Path coverImage) throws Exception {
        AudioFile audio = AudioFileIO.read(fichier);
        Tag tag = audio.getTagOrCreateDefault();

        // Construction du mapping field → valeur pour une écriture uniforme
        Map<FieldKey, String> fields = buildFieldMap(info);
        for (Map.Entry<FieldKey, String> entry : fields.entrySet()) {
            setIfNonBlank(tag, entry.getKey(), entry.getValue());
        }

        // Pochette
        if (coverImage != null) {
            Artwork artwork = ArtworkFactory.createArtworkFromFile(coverImage.toFile());
            tag.deleteArtworkField();
            tag.setField(artwork);
        }

        audio.commit();
    }

    private Map<FieldKey, String> buildFieldMap(TagInfo i) {
        Map<FieldKey, String> m = new LinkedHashMap<>();

        // ── Standard ──────────────────────────────────────────────────────
        m.put(FieldKey.TITLE,              i.title);
        m.put(FieldKey.ARTIST,             i.artist);
        m.put(FieldKey.ALBUM_ARTIST,       i.albumArtist);
        m.put(FieldKey.ALBUM,              i.album);
        m.put(FieldKey.YEAR,               i.year);
        m.put(FieldKey.TRACK,              i.track);
        m.put(FieldKey.TRACK_TOTAL,        i.trackTotal);
        m.put(FieldKey.GENRE,              i.genre);
        m.put(FieldKey.DISC_NO,            i.discNo);
        m.put(FieldKey.DISC_TOTAL,         i.discTotal);
        m.put(FieldKey.COMMENT,            i.comment);

        // ── Tri ───────────────────────────────────────────────────────────
        m.put(FieldKey.TITLE_SORT,         i.titleSort);
        m.put(FieldKey.ARTIST_SORT,        i.artistSort);
        m.put(FieldKey.ALBUM_SORT,         i.albumSort);
        m.put(FieldKey.ALBUM_ARTIST_SORT,  i.albumArtistSort);
        m.put(FieldKey.COMPOSER_SORT,      i.composerSort);
        m.put(FieldKey.CONDUCTOR_SORT,     i.conductorSort);
        m.put(FieldKey.ORCHESTRA_SORT,     i.orchestraSort);
        m.put(FieldKey.ENSEMBLE_SORT,      i.ensembleSort);
        m.put(FieldKey.CHOIR_SORT,         i.choirSort);
        m.put(FieldKey.LYRICIST_SORT,      i.lyricistSort);
        m.put(FieldKey.PRODUCER_SORT,      i.producerSort);
        m.put(FieldKey.ARRANGER_SORT,      i.arrangerSort);

        // ── Contributeurs ─────────────────────────────────────────────────
        m.put(FieldKey.COMPOSER,           i.composer);
        m.put(FieldKey.CONDUCTOR,          i.conductor);
        m.put(FieldKey.ORCHESTRA,          i.orchestra);
        m.put(FieldKey.ENSEMBLE,           i.ensemble);
        m.put(FieldKey.CHOIR,              i.choir);
        m.put(FieldKey.LYRICIST,           i.lyricist);
        m.put(FieldKey.PRODUCER,           i.producer);
        m.put(FieldKey.ARRANGER,           i.arranger);
        m.put(FieldKey.ENGINEER,           i.engineer);
        m.put(FieldKey.MIXER,              i.mixer);
        m.put(FieldKey.DJMIXER,            i.djMixer);

        // ── Classique ─────────────────────────────────────────────────────
        m.put(FieldKey.WORK,               i.work);
        m.put(FieldKey.MUSICBRAINZ_WORK_ID, i.workMbid);
        m.put(FieldKey.MOVEMENT,           i.movement);
        m.put(FieldKey.MOVEMENT_NO,        i.movementNo);
        m.put(FieldKey.MOVEMENT_TOTAL,     i.movementTotal);
        m.put(FieldKey.TITLE_MOVEMENT,     i.titleMovement);
        m.put(FieldKey.PART,               i.part);
        m.put(FieldKey.PART_TYPE,          i.partType);
        m.put(FieldKey.PART_NUMBER,        i.partNo);
        m.put(FieldKey.PERIOD,             i.period);
        m.put(FieldKey.OPUS,               i.opus);
        m.put(FieldKey.CLASSICAL_CATALOG,  i.classicalCatalog);
        m.put(FieldKey.CLASSICAL_NICKNAME, i.classicalNickname);
        m.put(FieldKey.SECTION,            i.section);
        m.put(FieldKey.OVERALL_WORK,       i.overallWork);
        m.put(FieldKey.GROUPING,           i.grouping);

        // ── Flags ─────────────────────────────────────────────────────────
        if ("1".equals(i.isClassical))   m.put(FieldKey.IS_CLASSICAL,   "1");
        if ("1".equals(i.isCompilation)) m.put(FieldKey.IS_COMPILATION, "1");

        // ── Audio ─────────────────────────────────────────────────────────
        m.put(FieldKey.BPM,                i.bpm);
        m.put(FieldKey.KEY,                i.initialKey);
        m.put(FieldKey.LANGUAGE,           i.language);

        // ── Paroles ───────────────────────────────────────────────────────
        m.put(FieldKey.LYRICS,             i.lyrics);
        m.put(FieldKey.URL_LYRICS_SITE,    i.lyricsUrl);

        // ── Rating / Tags ─────────────────────────────────────────────────
        m.put(FieldKey.RATING,             i.rating);
        m.put(FieldKey.TAGS,               i.tags);

        // ── Mood ──────────────────────────────────────────────────────────
        m.put(FieldKey.MOOD,               i.mood);
        m.put(FieldKey.MOOD_AGGRESSIVE,    i.moodAggressive);
        m.put(FieldKey.MOOD_ACOUSTIC,      i.moodAcoustic);
        m.put(FieldKey.MOOD_ELECTRONIC,    i.moodElectronic);
        m.put(FieldKey.MOOD_HAPPY,         i.moodHappy);
        m.put(FieldKey.MOOD_PARTY,         i.moodParty);
        m.put(FieldKey.MOOD_RELAXED,       i.moodRelaxed);
        m.put(FieldKey.MOOD_SAD,           i.moodSad);
        m.put(FieldKey.MOOD_VALENCE,       i.moodValence);
        m.put(FieldKey.MOOD_AROUSAL,       i.moodArousal);
        m.put(FieldKey.MOOD_DANCEABILITY,  i.moodDanceability);
        m.put(FieldKey.MOOD_INSTRUMENTAL,  i.moodInstrumental);

        // ── URLs ──────────────────────────────────────────────────────────
        m.put(FieldKey.URL_OFFICIAL_ARTIST_SITE,  i.artistOfficialUrl);
        m.put(FieldKey.URL_WIKIPEDIA_ARTIST_SITE, i.artistWikipediaUrl);
        m.put(FieldKey.URL_DISCOGS_ARTIST_SITE,   i.artistDiscogsUrl);
        m.put(FieldKey.URL_OFFICIAL_RELEASE_SITE, i.releaseOfficialUrl);
        m.put(FieldKey.URL_WIKIPEDIA_RELEASE_SITE, i.releaseWikipediaUrl);
        m.put(FieldKey.URL_DISCOGS_RELEASE_SITE,  i.releaseDiscogsUrl);

        // ── IDs ───────────────────────────────────────────────────────────
        m.put(FieldKey.ISRC,               i.isrc);
        m.put(FieldKey.AMAZON_ID,          i.amazonId);
        m.put(FieldKey.ROONALBUMTAG,       i.roonAlbumTag);
        m.put(FieldKey.ROONTRACKTAG,       i.roonTrackTag);
        m.put(FieldKey.ACOUSTID_ID,        i.acoustidId);
        m.put(FieldKey.ACOUSTID_FINGERPRINT, i.acoustidFingerprint);

        // ── IDs MusicBrainz ───────────────────────────────────────────────
        m.put(FieldKey.MUSICBRAINZ_ARTISTID,         i.artistMbid);
        m.put(FieldKey.MUSICBRAINZ_RELEASEID,         i.releaseMbid);
        m.put(FieldKey.MUSICBRAINZ_TRACK_ID,          i.recordingMbid);
        m.put(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID,  i.releaseGroupMbid);

        return m;
    }

    private void setIfNonBlank(Tag tag, FieldKey key, String value) {
        if (value == null || value.isBlank()) return;
        try { tag.setField(key, value); }
        catch (Exception ignored) {}
    }
}
