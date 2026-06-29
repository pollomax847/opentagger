package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v23Frame;
import org.jaudiotagger.tag.id3.ID3v23Tag;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.field.Mp4TagTextField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTagField;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Écriture des tags audio 100% Java via jaudiotagger.
 *
 * Tous les formats sont gérés nativement :
 *   MP3  → ID3v2.3/v2.4 (frames standard + TXXX pour champs custom)
 *   M4A  → atomes iTunes + freeform ----:com.apple.iTunes: pour champs custom
 *   FLAC → VorbisComment (clés standard)
 *   OGG  → VorbisComment (clés standard)
 *
 * Les 6 champs sans FieldKey (ReplayGain ×4, isInstrumental, discogsId)
 * sont écrits via setCustomField() qui dispatche selon le type de tag.
 */
public class TagWriter {

    public TagInfo write(File fichier, TagInfo info) throws Exception {
        return write(fichier, info, null);
    }

    public TagInfo write(File fichier, TagInfo info, Path coverImage) throws Exception {
        long savedTimestamp = Config.get().preserveTimestamps() ? fichier.lastModified() : 0;

        // M4A : réparation si nécessaire avant lecture/écriture
        repairM4aIfNeeded(fichier);

        TagInfo merged;
        Map<String, String> preserved = Map.of();
        try {
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTag();
            if (tag != null) {
                preserved = readPreservedTags(tag);
                merged = Config.get().clearExistingTags()
                        ? info.copy()
                        : mergeWithExisting(info, tag);
            } else {
                merged = info.copy();
            }
        } catch (Exception e) {
            merged = info.copy();
        }

        if (!Config.get().saveAcoustidFingerprints()) {
            merged.acoustidId          = "";
            merged.acoustidFingerprint = "";
        }

        writeNative(fichier, merged, coverImage, preserved, Config.get().clearExistingTags());

        if (savedTimestamp > 0) fichier.setLastModified(savedTimestamp);
        return merged;
    }

    /** Écrit uniquement les champs ReplayGain Track sans toucher aux autres tags. */
    public void writeReplayGain(File fichier, String trackGain, String trackPeak) {
        if ((trackGain == null || trackGain.isBlank())
                && (trackPeak == null || trackPeak.isBlank())) return;
        try {
            repairM4aIfNeeded(fichier);
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTagOrCreateAndSetDefault();
            setCustomField(tag, "REPLAYGAIN_TRACK_GAIN", trackGain);
            setCustomField(tag, "REPLAYGAIN_TRACK_PEAK", trackPeak);
            audio.commit();
        } catch (Exception ignored) {}
    }

    /** Écrit uniquement les champs ReplayGain Album sans toucher aux autres tags. */
    public void writeAlbumReplayGain(File fichier, String albumGain, String albumPeak) {
        if ((albumGain == null || albumGain.isBlank())
                && (albumPeak == null || albumPeak.isBlank())) return;
        try {
            repairM4aIfNeeded(fichier);
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTagOrCreateAndSetDefault();
            setCustomField(tag, "REPLAYGAIN_ALBUM_GAIN", albumGain);
            setCustomField(tag, "REPLAYGAIN_ALBUM_PEAK", albumPeak);
            audio.commit();
        } catch (Exception ignored) {}
    }

    // ── Écriture native jaudiotagger ──────────────────────────────────────────

    /**
     * Chaîne de fallback pour M4A :
     *  1. jaudiotagger natif          → tous les champs
     *  2. ffmpeg repair + retry       → tous les champs
     *  3. AtomicParsley               → tous les champs (cover, MBIDs, ReplayGain)
     *  4. ffmpeg direct               → tags standards seulement (dernier recours)
     */
    private void writeNative(File fichier, TagInfo i, Path coverImage,
                              Map<String, String> preserved,
                              boolean clearExisting) throws Exception {
        try {
            doWriteNative(fichier, i, coverImage, preserved, clearExisting);
            return;
        } catch (Exception e) {
            if (!fichier.getName().toLowerCase().endsWith(".m4a")) throw e;
        }
        // Fallback 1 : repair structure ffmpeg + retry jaudiotagger
        if (forcedRepairM4a(fichier)) {
            try {
                doWriteNative(fichier, i, coverImage, preserved, clearExisting);
                return;
            } catch (Exception ignored) {}
        }
        // Fallback 2 : AtomicParsley — full support (cover + MBIDs + ReplayGain)
        try {
            writeM4aViaAtomicParsley(fichier, i, coverImage);
            return;
        } catch (Exception ignored) {}
        // Fallback 3 : ffmpeg — tags standards seulement
        writeM4aViaFfmpeg(fichier, i, coverImage);
    }

    private void doWriteNative(File fichier, TagInfo i, Path coverImage,
                                Map<String, String> preserved,
                                boolean clearExisting) throws Exception {
        AudioFile audio = AudioFileIO.read(fichier);

        Tag tag;
        if (clearExisting) {
            tag = audio.createDefaultTag();
            audio.setTag(tag);
        } else {
            tag = audio.getTagOrCreateAndSetDefault();
        }

        // ── Champs standard via FieldKey ─────────────────────────────────────
        sf(tag, FieldKey.TITLE,              i.title);
        sf(tag, FieldKey.ARTIST,             i.artist);
        sf(tag, FieldKey.ALBUM_ARTIST,       i.albumArtist);
        sf(tag, FieldKey.ALBUM,              i.album);
        sf(tag, FieldKey.YEAR,               i.year);
        sf(tag, FieldKey.GENRE,              i.genre);
        sf(tag, FieldKey.TRACK,              i.track);
        sf(tag, FieldKey.TRACK_TOTAL,        i.trackTotal);
        sf(tag, FieldKey.DISC_NO,            i.discNo);
        sf(tag, FieldKey.DISC_TOTAL,         i.discTotal);
        sf(tag, FieldKey.COMMENT,            i.comment);

        // ── Artistes multiples ───────────────────────────────────────────────
        sf(tag, FieldKey.ARTISTS,            i.artists);
        sf(tag, FieldKey.ARTISTS_SORT,       i.artistsSort);

        // ── Tri ──────────────────────────────────────────────────────────────
        sf(tag, FieldKey.TITLE_SORT,         i.titleSort);
        sf(tag, FieldKey.ARTIST_SORT,        i.artistSort);
        sf(tag, FieldKey.ALBUM_SORT,         i.albumSort);
        sf(tag, FieldKey.ALBUM_ARTIST_SORT,  i.albumArtistSort);
        sf(tag, FieldKey.COMPOSER_SORT,      i.composerSort);
        sf(tag, FieldKey.CONDUCTOR_SORT,     i.conductorSort);
        sf(tag, FieldKey.ORCHESTRA_SORT,     i.orchestraSort);
        sf(tag, FieldKey.ENSEMBLE_SORT,      i.ensembleSort);
        sf(tag, FieldKey.CHOIR_SORT,         i.choirSort);
        sf(tag, FieldKey.LYRICIST_SORT,      i.lyricistSort);
        sf(tag, FieldKey.PRODUCER_SORT,      i.producerSort);
        sf(tag, FieldKey.ARRANGER_SORT,      i.arrangerSort);
        sf(tag, FieldKey.MIXER_SORT,         i.mixerSort);

        // ── Compositeurs / contributeurs ─────────────────────────────────────
        sf(tag, FieldKey.COMPOSER,           i.composer);
        sf(tag, FieldKey.CONDUCTOR,          i.conductor);
        sf(tag, FieldKey.ORCHESTRA,          i.orchestra);
        sf(tag, FieldKey.ENSEMBLE,           i.ensemble);
        sf(tag, FieldKey.CHOIR,              i.choir);
        sf(tag, FieldKey.LYRICIST,           i.lyricist);
        sf(tag, FieldKey.PRODUCER,           i.producer);
        sf(tag, FieldKey.ARRANGER,           i.arranger);
        sf(tag, FieldKey.ENGINEER,           i.engineer);
        sf(tag, FieldKey.MIXER,              i.mixer);
        sf(tag, FieldKey.DJMIXER,            i.djMixer);

        // ── Classique ────────────────────────────────────────────────────────
        sf(tag, FieldKey.WORK,               i.work);
        sf(tag, FieldKey.MUSICBRAINZ_WORK_ID, i.workMbid);
        sf(tag, FieldKey.MOVEMENT,           i.movement);
        sf(tag, FieldKey.MOVEMENT_NO,        i.movementNo);
        sf(tag, FieldKey.MOVEMENT_TOTAL,     i.movementTotal);
        sf(tag, FieldKey.TITLE_MOVEMENT,     i.titleMovement);
        sf(tag, FieldKey.PART,               i.part);
        sf(tag, FieldKey.PART_TYPE,          i.partType);
        sf(tag, FieldKey.PART_NUMBER,        i.partNo);
        sf(tag, FieldKey.PERIOD,             i.period);
        sf(tag, FieldKey.OPUS,               i.opus);
        sf(tag, FieldKey.CLASSICAL_CATALOG,  i.classicalCatalog);
        sf(tag, FieldKey.CLASSICAL_NICKNAME, i.classicalNickname);
        sf(tag, FieldKey.SECTION,            i.section);
        sf(tag, FieldKey.OVERALL_WORK,       i.overallWork);
        sf(tag, FieldKey.GROUPING,           i.grouping);

        // ── Flags ────────────────────────────────────────────────────────────
        sf(tag, FieldKey.IS_CLASSICAL,       i.isClassical);
        sf(tag, FieldKey.IS_COMPILATION,     i.isCompilation);
        sf(tag, FieldKey.IS_HD,              i.isHD);
        sf(tag, FieldKey.IS_LIVE,            i.isLive);
        sf(tag, FieldKey.IS_GREATEST_HITS,   i.isGreatestHits);
        sf(tag, FieldKey.IS_SOUNDTRACK,      i.isSoundtrack);
        // isInstrumental → pas de FieldKey → champ custom
        setCustomField(tag, "IS_INSTRUMENTAL", i.isInstrumental);

        // ── Audio / tempo / tonalité ──────────────────────────────────────────
        sf(tag, FieldKey.BPM,                i.bpm);
        sf(tag, FieldKey.FBPM,               i.fbpm);
        sf(tag, FieldKey.KEY,                i.initialKey);
        sf(tag, FieldKey.LANGUAGE,           i.language);

        // ── Humeur (Essentia) ─────────────────────────────────────────────────
        sf(tag, FieldKey.MOOD,               i.mood);
        sf(tag, FieldKey.MOOD_AGGRESSIVE,    i.moodAggressive);
        sf(tag, FieldKey.MOOD_ACOUSTIC,      i.moodAcoustic);
        sf(tag, FieldKey.MOOD_ELECTRONIC,    i.moodElectronic);
        sf(tag, FieldKey.MOOD_HAPPY,         i.moodHappy);
        sf(tag, FieldKey.MOOD_PARTY,         i.moodParty);
        sf(tag, FieldKey.MOOD_RELAXED,       i.moodRelaxed);
        sf(tag, FieldKey.MOOD_SAD,           i.moodSad);
        sf(tag, FieldKey.MOOD_VALENCE,       i.moodValence);
        sf(tag, FieldKey.MOOD_AROUSAL,       i.moodArousal);
        sf(tag, FieldKey.MOOD_DANCEABILITY,  i.moodDanceability);
        sf(tag, FieldKey.MOOD_INSTRUMENTAL,  i.moodInstrumental);

        // ── ReplayGain → pas de FieldKey → champs custom ─────────────────────
        setCustomField(tag, "REPLAYGAIN_TRACK_GAIN", i.replayGainTrackGain);
        setCustomField(tag, "REPLAYGAIN_TRACK_PEAK", i.replayGainTrackPeak);
        setCustomField(tag, "REPLAYGAIN_ALBUM_GAIN", i.replayGainAlbumGain);
        setCustomField(tag, "REPLAYGAIN_ALBUM_PEAK", i.replayGainAlbumPeak);

        // ── Paroles ───────────────────────────────────────────────────────────
        sf(tag, FieldKey.LYRICS,             i.lyrics);
        sf(tag, FieldKey.URL_LYRICS_SITE,    i.lyricsUrl);

        // ── Rating & tags ─────────────────────────────────────────────────────
        sf(tag, FieldKey.RATING,             i.rating);
        sf(tag, FieldKey.TAGS,               i.tags);

        // ── Identifiants ─────────────────────────────────────────────────────
        sf(tag, FieldKey.ISRC,               i.isrc);
        sf(tag, FieldKey.AMAZON_ID,          i.amazonId);
        sf(tag, FieldKey.ACOUSTID_ID,        i.acoustidId);
        sf(tag, FieldKey.ACOUSTID_FINGERPRINT, i.acoustidFingerprint);
        sf(tag, FieldKey.ROONALBUMTAG,       i.roonAlbumTag);
        sf(tag, FieldKey.ROONTRACKTAG,       i.roonTrackTag);
        // discogsId → pas de FieldKey numérique → champ custom
        setCustomField(tag, "DISCOGS_RELEASE_ID", i.discogsId);

        // ── Métadonnées release ───────────────────────────────────────────────
        sf(tag, FieldKey.SCRIPT,             i.script);
        sf(tag, FieldKey.COUNTRY,            i.country);
        sf(tag, FieldKey.BARCODE,            i.barcode);
        sf(tag, FieldKey.CATALOG_NO,         i.catalogNo);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASE_TYPE, i.releaseType);
        sf(tag, FieldKey.ORIGINAL_YEAR,      i.originalYear);

        // ── IDs MusicBrainz ───────────────────────────────────────────────────
        sf(tag, FieldKey.MUSICBRAINZ_ARTISTID,         i.artistMbid);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID, i.releaseGroupMbid);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASEID,        i.releaseMbid);
        sf(tag, FieldKey.MUSICBRAINZ_TRACK_ID,         i.recordingMbid);

        // ── URLs ──────────────────────────────────────────────────────────────
        sf(tag, FieldKey.URL_OFFICIAL_ARTIST_SITE,   i.artistOfficialUrl);
        sf(tag, FieldKey.URL_WIKIPEDIA_ARTIST_SITE,  i.artistWikipediaUrl);
        sf(tag, FieldKey.URL_DISCOGS_ARTIST_SITE,    i.artistDiscogsUrl);
        sf(tag, FieldKey.URL_DISCOGS_RELEASE_SITE,   i.releaseDiscogsUrl);
        sf(tag, FieldKey.URL_OFFICIAL_RELEASE_SITE,  i.releaseOfficialUrl);
        sf(tag, FieldKey.URL_WIKIPEDIA_RELEASE_SITE, i.releaseWikipediaUrl);

        // ── Tags preservés ────────────────────────────────────────────────────
        for (Map.Entry<String, String> e : preserved.entrySet()) {
            try {
                sf(tag, FieldKey.valueOf(e.getKey().toUpperCase()), e.getValue());
            } catch (Exception ignored) {}
        }

        // ── Pochette ──────────────────────────────────────────────────────────
        if (coverImage != null) {
            try {
                Artwork art = ArtworkFactory.createArtworkFromFile(coverImage.toFile());
                tag.deleteArtworkField();
                tag.setField(art);
            } catch (Exception ignored) {}
        }

        audio.commit();
    }

    // ── Fallback M4A via AtomicParsley ───────────────────────────────────────

    /**
     * Écrit tous les champs M4A via AtomicParsley (fallback 2).
     * Supporte : tags standards, sort fields, cover art, MusicBrainz IDs, ReplayGain,
     * Acoustid, Discogs, flags custom — via atomes freeform ----:com.apple.iTunes:
     */
    private static void writeM4aViaAtomicParsley(File fichier, TagInfo i, Path coverImage)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("AtomicParsley");
        cmd.add(fichier.getAbsolutePath());

        // Tags standards
        apField(cmd, "--title",       i.title);
        apField(cmd, "--artist",      i.artist);
        apField(cmd, "--albumArtist", i.albumArtist);
        apField(cmd, "--album",       i.album);
        apField(cmd, "--year",        i.year);
        apField(cmd, "--genre",       i.genre);
        apField(cmd, "--composer",    i.composer);
        apField(cmd, "--comment",     i.comment);
        apField(cmd, "--lyrics",      i.lyrics);
        apField(cmd, "--grouping",    i.grouping);

        if (!i.track.isBlank()) {
            String trk = i.trackTotal.isBlank() ? i.track : i.track + "/" + i.trackTotal;
            apField(cmd, "--tracknum", trk);
        }
        if (!i.discNo.isBlank()) {
            String dsk = i.discTotal.isBlank() ? i.discNo : i.discNo + "/" + i.discTotal;
            apField(cmd, "--disk", dsk);
        }
        if (!i.bpm.isBlank()) apField(cmd, "--BPM", i.bpm);
        if (i.isCompilation.equals("1"))
            apField(cmd, "--compilation", "true");

        // Sort fields
        apField(cmd, "--sortTitle",       i.titleSort);
        apField(cmd, "--sortArtist",      i.artistSort);
        apField(cmd, "--sortAlbum",       i.albumSort);
        apField(cmd, "--sortAlbumArtist", i.albumArtistSort);
        apField(cmd, "--sortComposer",    i.composerSort);

        // Pochette
        if (coverImage != null && coverImage.toFile().exists())
            apField(cmd, "--artwork", coverImage.toAbsolutePath().toString());

        // MusicBrainz IDs
        apFreeform(cmd, "MusicBrainz Track Id",        i.recordingMbid);
        apFreeform(cmd, "MusicBrainz Album Id",         i.releaseMbid);
        apFreeform(cmd, "MusicBrainz Release Group Id", i.releaseGroupMbid);
        apFreeform(cmd, "MusicBrainz Artist Id",        i.artistMbid);

        // ReplayGain
        apFreeform(cmd, "REPLAYGAIN_TRACK_GAIN", i.replayGainTrackGain);
        apFreeform(cmd, "REPLAYGAIN_TRACK_PEAK", i.replayGainTrackPeak);
        apFreeform(cmd, "REPLAYGAIN_ALBUM_GAIN", i.replayGainAlbumGain);
        apFreeform(cmd, "REPLAYGAIN_ALBUM_PEAK", i.replayGainAlbumPeak);

        // Custom
        apFreeform(cmd, "IS_INSTRUMENTAL",    i.isInstrumental);
        apFreeform(cmd, "DISCOGS_RELEASE_ID", i.discogsId);
        apFreeform(cmd, "ACOUSTID_ID",        i.acoustidId);

        cmd.add("--overWrite");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread.ofVirtual().start(() -> {
            try { p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        });
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); throw new Exception("AtomicParsley timeout"); }
        if (p.exitValue() != 0) throw new Exception("AtomicParsley exit=" + p.exitValue());
    }

    private static void apField(List<String> cmd, String flag, String value) {
        if (value != null && !value.isBlank()) {
            cmd.add(flag); cmd.add(value);
        }
    }

    private static void apFreeform(List<String> cmd, String name, String value) {
        if (value == null || value.isBlank()) return;
        cmd.add("--freeForm"); cmd.add(name);
        cmd.add("--freeFormMeaning"); cmd.add("com.apple.iTunes");
        cmd.add("--freeFormValue"); cmd.add(value);
    }

    // ── Fallback M4A via ffmpeg ───────────────────────────────────────────────

    /**
     * Écrit les tags M4A standards via ffmpeg (fallback quand jaudiotagger échoue).
     * Champs supportés : titre, artiste, artiste album, album, année, genre, piste,
     * disque, commentaire, compositeur, paroles, BPM, grouping.
     * Champs NON supportés : MusicBrainz IDs, ReplayGain, sort fields, moods, pochette.
     */
    private static void writeM4aViaFfmpeg(File fichier, TagInfo i, Path coverImage)
            throws Exception {
        // Sortie dans un fichier temporaire — créé avant la commande pour gérer le cas
        // où ffmpeg ne peut pas créer le fichier lui-même (permissions, espace disque)
        File tmp = File.createTempFile("ot_m4a_", ".m4a", fichier.getParentFile());
        tmp.delete(); // ffmpeg crée le fichier lui-même

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");
        cmd.add("-i"); cmd.add(fichier.getAbsolutePath());
        // Pas de second input cover : ffmpeg nécessite -map et -disposition pour ça,
        // trop fragile ; la pochette sera ignorée dans ce fallback.
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-movflags"); cmd.add("+faststart"); // moov avant mdat — garanti lisible
        cmd.add("-map_metadata"); cmd.add("-1"); // effacer tags existants

        // Champs standards ffmpeg → M4A
        ffMeta(cmd, "title",        i.title);
        ffMeta(cmd, "artist",       i.artist);
        ffMeta(cmd, "album_artist", i.albumArtist);
        ffMeta(cmd, "album",        i.album);
        ffMeta(cmd, "date",         i.year);
        ffMeta(cmd, "genre",        i.genre);
        ffMeta(cmd, "composer",     i.composer);
        ffMeta(cmd, "comment",      i.comment);
        ffMeta(cmd, "lyrics",       i.lyrics);
        ffMeta(cmd, "grouping",     i.grouping);

        // Piste : "numéro/total"
        if (!i.track.isBlank()) {
            String trk = i.trackTotal.isBlank() ? i.track : i.track + "/" + i.trackTotal;
            ffMeta(cmd, "track", trk);
        }
        // Disque : "numéro/total"
        if (!i.discNo.isBlank()) {
            String dsk = i.discTotal.isBlank() ? i.discNo : i.discNo + "/" + i.discTotal;
            ffMeta(cmd, "disc", dsk);
        }
        if (!i.bpm.isBlank())           ffMeta(cmd, "bpm", i.bpm);
        if (!i.isCompilation.isBlank()) ffMeta(cmd, "compilation", i.isCompilation);
        if (!i.language.isBlank())      ffMeta(cmd, "language", i.language);

        cmd.add(tmp.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (Exception ignored) {}
            });
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); tmp.delete(); throw new Exception("timeout"); }
            if (p.exitValue() != 0 || !tmp.exists() || tmp.length() == 0) {
                tmp.delete();
                throw new Exception("ffmpeg exit=" + p.exitValue());
            }
            Files.move(tmp.toPath(), fichier.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            tmp.delete();
            throw new Exception("ffmpeg M4A fallback: " + e.getMessage());
        }
    }

    private static void ffMeta(List<String> cmd, String key, String value) {
        if (value != null && !value.isBlank()) {
            cmd.add("-metadata"); cmd.add(key + "=" + value);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** setField via FieldKey, ignore valeurs vides ou clé non supportée par ce format. */
    private static void sf(Tag tag, FieldKey key, String value) {
        if (value == null || value.isBlank()) return;
        try {
            tag.setField(key, value);
        } catch (Exception ignored) {}
    }

    /**
     * Champs sans FieldKey (ReplayGain ×4, IS_INSTRUMENTAL, DISCOGS_RELEASE_ID).
     * Dispatch selon le type de tag :
     *   ID3v2   → frame TXXX avec la description comme nom
     *   M4A     → atome freeform ----:com.apple.iTunes:NOM
     *   Vorbis  → clé plain-text (FLAC/OGG)
     */
    private static void setCustomField(Tag tag, String name, String value) {
        if (value == null || value.isBlank()) return;
        try {
            if (tag instanceof AbstractID3v2Tag id3) {
                writeTxxx(id3, name, value);
            } else if (tag instanceof Mp4Tag mp4) {
                writeMp4Freeform(mp4, name, value);
            } else {
                // FLAC / OGG — VorbisComment plain-text
                tag.addField(new VorbisCommentTagField(name.toUpperCase(), value));
            }
        } catch (Exception ignored) {}
    }

    /** Ajoute (ou remplace) une frame TXXX dans un tag ID3v2. */
    private static void writeTxxx(AbstractID3v2Tag id3, String description, String value) {
        try {
            FrameBodyTXXX body = new FrameBodyTXXX((byte) 0, description, value);
            // Choisir ID3v2.4 ou ID3v2.3 selon le tag existant
            if (id3 instanceof ID3v24Tag) {
                ID3v24Frame frame = new ID3v24Frame("TXXX");
                frame.setBody(body);
                id3.setFrame(frame);
            } else {
                ID3v23Frame frame = new ID3v23Frame("TXXX");
                frame.setBody(body);
                id3.setFrame(frame);
            }
        } catch (Exception ignored) {}
    }

    /** Ajoute un atome freeform ----:com.apple.iTunes:NOM dans un tag M4A. */
    private static void writeMp4Freeform(Mp4Tag mp4, String name, String value) {
        try {
            String atomId = "----:com.apple.iTunes:" + name;
            Mp4TagTextField field = new Mp4TagTextField(atomId, value);
            mp4.addField(field);
        } catch (Exception ignored) {}
    }

    // ── Réparation M4A ────────────────────────────────────────────────────────

    /**
     * Passe toujours par ffmpeg (même si jaudiotagger peut lire le fichier).
     * Utilisé en fallback quand le writer jaudiotagger échoue (mdat<moov lisible mais non écrivable).
     */
    private static boolean forcedRepairM4a(File f) {
        return runFfmpegRepair(f);
    }

    /**
     * Répare les M4A illisibles par jaudiotagger via ffmpeg -movflags +faststart.
     * Idempotent : si le fichier est déjà lisible ET écrivable, ne fait rien.
     */
    public static boolean repairM4aIfNeeded(File f) {
        if (!f.getName().toLowerCase().endsWith(".m4a")) return false;
        try {
            AudioFileIO.read(f);
            return false; // lecture OK — on tente quand même l'écriture normalement
        } catch (Exception e) {
            // Lecture impossible → forcer la réparation
        }
        return runFfmpegRepair(f);
    }

    private static boolean runFfmpegRepair(File f) {
        try {
            File tmp = File.createTempFile("ot_fix_", ".m4a", f.getParentFile());
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", f.getAbsolutePath(),
                "-c", "copy", "-movflags", "+faststart", tmp.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (Exception ignored) {}
            });
            boolean exited = p.waitFor(120, TimeUnit.SECONDS);
            if (!exited) { p.destroyForcibly(); tmp.delete(); return false; }
            if (p.exitValue() == 0 && tmp.length() > 0) {
                Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            tmp.delete();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    // ── Merge et preserved — lecture jaudiotagger ─────────────────────────────

    private TagInfo mergeWithExisting(TagInfo info, Tag tag) {
        TagInfo m = info.copy();
        for (FieldKey key : FieldKey.values()) {
            String existing = getTagFirst(tag, key);
            if (existing == null || existing.isBlank()) continue;
            try {
                Field f = fieldFor(key);
                if (f == null) continue;
                String cur = (String) f.get(m);
                if (cur == null || cur.isBlank()) {
                    if (key == FieldKey.TRACK || key == FieldKey.DISC_NO) {
                        int slash = existing.indexOf('/');
                        existing = slash >= 0 ? existing.substring(0, slash).trim() : existing;
                    }
                    f.set(m, existing);
                }
            } catch (Exception ignored) {}
        }
        return m;
    }

    private Map<String, String> readPreservedTags(Tag tag) {
        String raw = Config.get().str("tags.preserved_tags", "");
        Map<String, String> result = new LinkedHashMap<>();
        if (raw.isBlank()) return result;
        for (String name : raw.split("\\|")) {
            name = name.trim();
            if (name.isBlank()) continue;
            try {
                FieldKey key = FieldKey.valueOf(name.toUpperCase());
                String val = getTagFirst(tag, key);
                if (val != null && !val.isBlank()) result.put(name, val);
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    private String getTagFirst(Tag tag, FieldKey key) {
        try { return tag.getFirst(key); } catch (Exception e) { return ""; }
    }

    private static final Map<FieldKey, Field> KEY_TO_FIELD = new HashMap<>();

    private Field fieldFor(FieldKey key) {
        return KEY_TO_FIELD.computeIfAbsent(key, k -> {
            String name = switch (k) {
                case TITLE              -> "title";
                case ARTIST             -> "artist";
                case ALBUM_ARTIST       -> "albumArtist";
                case ALBUM              -> "album";
                case YEAR               -> "year";
                case TRACK              -> "track";
                case TRACK_TOTAL        -> "trackTotal";
                case GENRE              -> "genre";
                case DISC_NO            -> "discNo";
                case DISC_TOTAL         -> "discTotal";
                case COMMENT            -> "comment";
                case TITLE_SORT         -> "titleSort";
                case ARTIST_SORT        -> "artistSort";
                case ALBUM_SORT         -> "albumSort";
                case ALBUM_ARTIST_SORT  -> "albumArtistSort";
                case COMPOSER           -> "composer";
                case COMPOSER_SORT      -> "composerSort";
                case LYRICIST           -> "lyricist";
                case LYRICIST_SORT      -> "lyricistSort";
                case ARRANGER           -> "arranger";
                case ARRANGER_SORT      -> "arrangerSort";
                case CONDUCTOR          -> "conductor";
                case CONDUCTOR_SORT     -> "conductorSort";
                case PRODUCER           -> "producer";
                case PRODUCER_SORT      -> "producerSort";
                case ENGINEER           -> "engineer";
                case MIXER              -> "mixer";
                case MIXER_SORT         -> "mixerSort";
                case DJMIXER            -> "djMixer";
                case ORCHESTRA          -> "orchestra";
                case ORCHESTRA_SORT     -> "orchestraSort";
                case ENSEMBLE           -> "ensemble";
                case ENSEMBLE_SORT      -> "ensembleSort";
                case CHOIR              -> "choir";
                case CHOIR_SORT         -> "choirSort";
                case LANGUAGE           -> "language";
                case ISRC               -> "isrc";
                case BPM                -> "bpm";
                case MOOD               -> "mood";
                case LYRICS             -> "lyrics";
                case MUSICBRAINZ_TRACK_ID         -> "recordingMbid";
                case MUSICBRAINZ_ARTISTID         -> "artistMbid";
                case MUSICBRAINZ_RELEASEID        -> "releaseMbid";
                case MUSICBRAINZ_RELEASE_GROUP_ID -> "releaseGroupMbid";
                default -> null;
            };
            if (name == null) return null;
            try {
                Field f = TagInfo.class.getField(name);
                f.setAccessible(true);
                return f;
            } catch (Exception e) { return null; }
        });
    }
}
