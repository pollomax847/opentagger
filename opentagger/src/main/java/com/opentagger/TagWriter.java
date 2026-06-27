package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v23Tag;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.id3.ID3v23Frame;
import org.jaudiotagger.tag.id3.ID3v24Frame;
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
        Tag tag = getOrCreateTag(audio);

        // Préserver les timestamps avant toute modification
        long savedTimestamp = Config.get().preserveTimestamps() ? fichier.lastModified() : 0;

        // Sauvegarder la pochette existante si on vide les tags ET qu'on veut la préserver
        Artwork savedArtwork = null;
        if (Config.get().clearExistingTags() && Config.get().preserveImages() && coverImage == null) {
            try { savedArtwork = tag.getFirstArtwork(); } catch (Exception ignored) {}
        }

        // Effacer tous les champs existants si configuré (Clear Existing Tags comme Picard)
        if (Config.get().clearExistingTags()) {
            try { tag.deleteArtworkField(); } catch (Exception ignored) {}
            for (FieldKey key : FieldKey.values()) {
                try { tag.deleteField(key); } catch (Exception ignored) {}
            }
        }

        // Fusionner avec les tags existants si on ne les efface pas.
        // Les champs vides dans TagInfo sont complétés par les valeurs déjà présentes
        // (évite d'effacer trackTotal/discTotal en ID3v2.3 "3/12").
        TagInfo merged = Config.get().clearExistingTags() ? info.copy() : mergeWithExisting(info, tag);

        // Supprimer empreinte AcoustID si désactivé
        if (!Config.get().saveAcoustidFingerprints()) {
            merged.acoustidFingerprint = "";
            merged.acoustidId          = "";
        }

        // Sauvegarder les tags préservés AVANT toute écriture
        Map<FieldKey, String> preserved = readPreservedTags(tag);

        // Construction du mapping field → valeur pour une écriture uniforme
        Map<FieldKey, String> fields = buildFieldMap(merged);
        for (Map.Entry<FieldKey, String> entry : fields.entrySet()) {
            // Ne pas écraser un tag préservé si la nouvelle valeur est non-vide
            if (!preserved.containsKey(entry.getKey()))
                setIfNonBlank(tag, entry.getKey(), entry.getValue());
        }

        // Restaurer les tags préservés (priorité absolue sur toute autre valeur)
        for (Map.Entry<FieldKey, String> entry : preserved.entrySet()) {
            try { tag.setField(entry.getKey(), entry.getValue()); } catch (Exception ignored) {}
        }

        // Champs TXXX non couverts par FieldKey — écrits directement
        if (tag instanceof AbstractID3v2Tag id3) {
            if (info.fbpm != null && !info.fbpm.isBlank())
                writeTxxx(id3, "FBPM", info.fbpm);

            // TXXX:ARTISTS / ARTISTS_SORT (tous les artistes feat.)
            String allArtists = info.artists != null && !info.artists.isBlank()
                    ? info.artists : "";
            String allSorts   = info.artistsSort != null && !info.artistsSort.isBlank()
                    ? info.artistsSort : "";
            // Toujours écrire TXXX:ARTISTS (au moins l'artiste principal, comme SongKong)
            String txArtists = allArtists.isBlank() ? info.artist : allArtists;
            String txSorts   = allSorts.isBlank()   ? info.artistSort : allSorts;
            if (!txArtists.isBlank()) writeTxxx(id3, "ARTISTS",      txArtists);
            if (!txSorts.isBlank())   writeTxxx(id3, "ARTISTS_SORT", txSorts);

            // TXXX:ALBUM_ARTISTS / ALBUM_ARTISTS_SORT
            if (!info.albumArtist.isBlank())     writeTxxx(id3, "ALBUM_ARTISTS",      info.albumArtist);
            if (!info.albumArtistSort.isBlank()) writeTxxx(id3, "ALBUM_ARTISTS_SORT", info.albumArtistSort);
        }

        // Pochette : nouvelle image, pochette sauvegardée (preserve_images), ou inchangée
        if (coverImage != null) {
            Artwork artwork = ArtworkFactory.createArtworkFromFile(coverImage.toFile());
            tag.deleteArtworkField();
            tag.setField(artwork);
        } else if (savedArtwork != null) {
            try { tag.setField(savedArtwork); } catch (Exception ignored) {}
        }

        // Supprimer ID3v1 si configuré (MP3 seulement — ID3v1 = footer 128 octets inutile)
        if (Config.get().removeId3v1() && audio instanceof org.jaudiotagger.audio.mp3.MP3File) {
            org.jaudiotagger.tag.TagOptionSingleton.getInstance().setId3v1Save(false);
        }

        // ReplayGain : écriture via TXXX (MP3) ou champ libre (FLAC/OGG)
        // Doit être AVANT commit pour être inclus dans le même write
        if (info.replayGainTrackGain != null && !info.replayGainTrackGain.isBlank()) {
            if (tag instanceof AbstractID3v2Tag id3) {
                writeTxxx(id3, "REPLAYGAIN_TRACK_GAIN", info.replayGainTrackGain);
                if (!info.replayGainTrackPeak.isBlank()) writeTxxx(id3, "REPLAYGAIN_TRACK_PEAK", info.replayGainTrackPeak);
            } else if (tag instanceof org.jaudiotagger.tag.flac.FlacTag flac) {
                try { flac.setField("REPLAYGAIN_TRACK_GAIN", info.replayGainTrackGain); } catch (Exception ignored) {}
                try { if (!info.replayGainTrackPeak.isBlank()) flac.setField("REPLAYGAIN_TRACK_PEAK", info.replayGainTrackPeak); } catch (Exception ignored) {}
            } else if (tag instanceof org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag vorbis) {
                try { vorbis.setField("REPLAYGAIN_TRACK_GAIN", info.replayGainTrackGain); } catch (Exception ignored) {}
                try { if (!info.replayGainTrackPeak.isBlank()) vorbis.setField("REPLAYGAIN_TRACK_PEAK", info.replayGainTrackPeak); } catch (Exception ignored) {}
            }
        }

        audio.commit();

        // Restaurer les timestamps du fichier (Picard : preserve_timestamps)
        if (savedTimestamp > 0) fichier.setLastModified(savedTimestamp);
    }

    /**
     * Écrit les tags ReplayGain Track Gain/Peak dans le fichier.
     * MP3 (ID3v2) : TXXX:REPLAYGAIN_TRACK_GAIN / TXXX:REPLAYGAIN_TRACK_PEAK
     * FLAC/OGG (VorbisComment) : champs texte libres du même nom
     * M4A : non supporté (jaudiotagger n'expose pas les atomes freeform iTunes)
     */
    public void writeReplayGain(File fichier, String trackGain, String trackPeak) {
        if ((trackGain == null || trackGain.isBlank()) && (trackPeak == null || trackPeak.isBlank())) return;
        try {
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTag();
            if (tag == null) return;

            if (tag instanceof AbstractID3v2Tag id3) {
                if (trackGain != null && !trackGain.isBlank()) writeTxxx(id3, "REPLAYGAIN_TRACK_GAIN", trackGain);
                if (trackPeak != null && !trackPeak.isBlank()) writeTxxx(id3, "REPLAYGAIN_TRACK_PEAK", trackPeak);
            } else if (tag instanceof org.jaudiotagger.tag.flac.FlacTag flac) {
                if (trackGain != null && !trackGain.isBlank()) flac.setField("REPLAYGAIN_TRACK_GAIN", trackGain);
                if (trackPeak != null && !trackPeak.isBlank()) flac.setField("REPLAYGAIN_TRACK_PEAK", trackPeak);
            } else if (tag instanceof org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag vorbis) {
                if (trackGain != null && !trackGain.isBlank()) vorbis.setField("REPLAYGAIN_TRACK_GAIN", trackGain);
                if (trackPeak != null && !trackPeak.isBlank()) vorbis.setField("REPLAYGAIN_TRACK_PEAK", trackPeak);
            }
            // M4A (Mp4Tag) : pas d'API freeform dans jaudiotagger 3.0.1 — ignoré

            audio.commit();
        } catch (Exception ignored) {}
    }

    /**
     * Écrit REPLAYGAIN_ALBUM_GAIN / REPLAYGAIN_ALBUM_PEAK dans le fichier.
     * Utilisé par la passe album clustering après analyse collective des pistes.
     */
    public void writeAlbumReplayGain(File fichier, String albumGain, String albumPeak) {
        if ((albumGain == null || albumGain.isBlank()) && (albumPeak == null || albumPeak.isBlank())) return;
        try {
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTag();
            if (tag == null) return;
            if (tag instanceof AbstractID3v2Tag id3) {
                if (albumGain != null && !albumGain.isBlank()) writeTxxx(id3, "REPLAYGAIN_ALBUM_GAIN", albumGain);
                if (albumPeak != null && !albumPeak.isBlank()) writeTxxx(id3, "REPLAYGAIN_ALBUM_PEAK", albumPeak);
            } else if (tag instanceof org.jaudiotagger.tag.flac.FlacTag flac) {
                if (albumGain != null && !albumGain.isBlank()) flac.setField("REPLAYGAIN_ALBUM_GAIN", albumGain);
                if (albumPeak != null && !albumPeak.isBlank()) flac.setField("REPLAYGAIN_ALBUM_PEAK", albumPeak);
            } else if (tag instanceof org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag vorbis) {
                if (albumGain != null && !albumGain.isBlank()) vorbis.setField("REPLAYGAIN_ALBUM_GAIN", albumGain);
                if (albumPeak != null && !albumPeak.isBlank()) vorbis.setField("REPLAYGAIN_ALBUM_PEAK", albumPeak);
            }
            audio.commit();
        } catch (Exception ignored) {}
    }

    /**
     * Pour les MP3 : garantit un tag ID3v2 dans la version configurée.
     *  - "keep" (défaut) : préserve la version existante ; crée v2.3 si aucun tag
     *  - "2.3"           : convertit/crée toujours en ID3v2.3 (compatibilité voiture/NAS)
     *  - "2.4"           : convertit/crée toujours en ID3v2.4 (standard actuel)
     * ID3v1 ne supporte pas ISRC, MOOD, TPOS, TLAN, etc. — on le passe toujours en ID3v2.
     * Pour les autres formats (FLAC, M4A, OGG) : comportement jaudiotagger standard.
     */
    private Tag getOrCreateTag(AudioFile audio) {
        if (audio instanceof org.jaudiotagger.audio.mp3.MP3File mp3) {
            String ver = Config.get().id3v2Version();
            if ("2.3".equals(ver)) {
                if (mp3.hasID3v2Tag() && mp3.getID3v2Tag() instanceof ID3v23Tag v23) return v23;
                ID3v23Tag v23 = mp3.hasID3v2Tag()
                        ? new ID3v23Tag(mp3.getID3v2Tag())   // convertit v2.4 → v2.3
                        : new ID3v23Tag();
                mp3.setID3v2Tag(v23);
                return v23;
            } else if ("2.4".equals(ver)) {
                if (mp3.hasID3v2Tag() && mp3.getID3v2Tag() instanceof ID3v24Tag v24) return v24;
                ID3v24Tag v24 = mp3.hasID3v2Tag()
                        ? new ID3v24Tag(mp3.getID3v2Tag())   // convertit v2.3 → v2.4
                        : new ID3v24Tag();
                mp3.setID3v2Tag(v24);
                return v24;
            } else {
                // "keep" : préserve la version existante
                if (mp3.hasID3v2Tag()) return mp3.getID3v2Tag();
                ID3v23Tag v23 = new ID3v23Tag();
                mp3.setID3v2Tag(v23);
                return v23;
            }
        }
        return audio.getTagOrCreateDefault();
    }

    /**
     * Retourne un TagInfo où les champs vides sont complétés par les valeurs
     * déjà présentes dans le tag du fichier. Les valeurs de {@code info} ont
     * priorité quand elles sont non-vides.
     */
    private TagInfo mergeWithExisting(TagInfo info, Tag tag) {
        TagInfo m = info.copy();
        for (FieldKey key : FieldKey.values()) {
            String existing = getTagFirst(tag, key);
            if (existing == null || existing.isBlank()) continue;
            try {
                java.lang.reflect.Field f = fieldFor(key);
                if (f == null) continue;
                String cur = (String) f.get(m);
                if (cur == null || cur.isBlank()) {
                    // Pour TRACK et DISC_NO, jaudiotagger peut retourner "N/Total" en ID3v2.3.
                    // On ne garde que la partie avant le "/" pour éviter de doubler le total.
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

    private String getTagFirst(Tag tag, FieldKey key) {
        try { return tag.getFirst(key); } catch (Exception e) { return ""; }
    }

    /**
     * Lit et retourne les tags que l'utilisateur veut préserver (tags.preserved_tags).
     * Format config : noms FieldKey séparés par | ex: "RATING|COMMENT|CUSTOM1"
     * Retourne une map vide si la config est absente ou si tous les champs sont vides.
     */
    private Map<FieldKey, String> readPreservedTags(Tag tag) {
        String raw = Config.get().str("tags.preserved_tags", "");
        Map<FieldKey, String> result = new LinkedHashMap<>();
        if (raw.isBlank()) return result;
        for (String name : raw.split("\\|")) {
            name = name.trim();
            if (name.isBlank()) continue;
            try {
                FieldKey key = FieldKey.valueOf(name.toUpperCase());
                String val = getTagFirst(tag, key);
                if (val != null && !val.isBlank()) result.put(key, val);
            } catch (IllegalArgumentException ignored) {
                // Nom de FieldKey inconnu — on ignore silencieusement
            }
        }
        return result;
    }

    private static final java.util.Map<FieldKey, java.lang.reflect.Field> KEY_TO_FIELD =
            new java.util.HashMap<>();

    private java.lang.reflect.Field fieldFor(FieldKey key) {
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
                case LYRICIST           -> "lyricist";
                case LANGUAGE           -> "language";
                case ISRC               -> "isrc";
                case BPM                -> "bpm";
                case MOOD               -> "mood";
                case LYRICS             -> "lyrics";
                case RATING             -> "rating";
                case MUSICBRAINZ_TRACK_ID           -> "recordingMbid";
                case MUSICBRAINZ_ARTISTID           -> "artistMbid";
                case MUSICBRAINZ_RELEASEID          -> "releaseMbid";
                case MUSICBRAINZ_RELEASE_GROUP_ID   -> "releaseGroupMbid";
                default -> null;
            };
            if (name == null) return null;
            try {
                java.lang.reflect.Field f = com.opentagger.model.TagInfo.class.getField(name);
                f.setAccessible(true);
                return f;
            } catch (Exception e) { return null; }
        });
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
        if ("1".equals(i.isClassical))    m.put(FieldKey.IS_CLASSICAL,    "1");
        if ("1".equals(i.isCompilation))  m.put(FieldKey.IS_COMPILATION,  "1");
        if ("1".equals(i.isLive))         m.put(FieldKey.IS_LIVE,         "1");
        if ("1".equals(i.isHD))           m.put(FieldKey.IS_HD,           "1");
        if ("1".equals(i.isSoundtrack))   m.put(FieldKey.IS_SOUNDTRACK,   "1");
        if ("1".equals(i.isGreatestHits)) m.put(FieldKey.IS_GREATEST_HITS,"1");

        // ── Audio ─────────────────────────────────────────────────────────
        m.put(FieldKey.BPM,                i.bpm);
        m.put(FieldKey.KEY,                i.initialKey);
        m.put(FieldKey.LANGUAGE,           i.language);
        // FBPM : TXXX:FBPM (BPM décimal Essentia, comme Jaikoz/SongKong)
        // Pas de FieldKey standard — écrit après la boucle

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
        m.put(FieldKey.MUSICBRAINZ_ARTISTID,          i.artistMbid);
        m.put(FieldKey.MUSICBRAINZ_RELEASEID,          i.releaseMbid);
        m.put(FieldKey.MUSICBRAINZ_TRACK_ID,           i.recordingMbid);
        m.put(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID,   i.releaseGroupMbid);
        m.put(FieldKey.MUSICBRAINZ_RELEASE_COUNTRY,    i.country);
        m.put(FieldKey.MUSICBRAINZ_RELEASE_TYPE,       i.releaseType);

        // ── Métadonnées release (Jaikoz TXXX) ────────────────────────────
        m.put(FieldKey.SCRIPT,        i.script);
        m.put(FieldKey.BARCODE,       i.barcode);
        m.put(FieldKey.CATALOG_NO,    i.catalogNo);
        m.put(FieldKey.ORIGINAL_YEAR, i.originalYear);

        return m;
    }

    private void setIfNonBlank(Tag tag, FieldKey key, String value) {
        if (value == null || value.isBlank()) return;
        // MOOD : toujours écrire TXXX:MOOD comme Jaikoz/SongKong (compatible v2.3 et v2.4)
        if (key == FieldKey.MOOD && tag instanceof AbstractID3v2Tag id3) {
            writeTxxx(id3, "MOOD", value);
            return;
        }
        try {
            tag.setField(key, value);
        } catch (Exception ignored) {}
    }

    private void writeTxxx(AbstractID3v2Tag id3tag, String description, String value) {
        try {
            FrameBodyTXXX body = new FrameBodyTXXX();
            body.setDescription(description);
            body.setText(value);
            // Utiliser le type de frame correspondant à la version du tag (v2.3 ou v2.4)
            // Évite la corruption du fichier quand on insère un frame v2.3 dans un tag v2.4
            if (id3tag instanceof ID3v24Tag) {
                ID3v24Frame frame = new ID3v24Frame("TXXX");
                frame.setBody(body);
                id3tag.setFrame(frame);
            } else {
                ID3v23Frame frame = new ID3v23Frame("TXXX");
                frame.setBody(body);
                id3tag.setFrame(frame);
            }
        } catch (Exception ignored) {}
    }
}
