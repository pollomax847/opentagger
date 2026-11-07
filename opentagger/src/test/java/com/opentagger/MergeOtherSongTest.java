package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.opentagger.model.TagInfo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.Test;

/**
 * TagWriter.mergeWithExisting (réglage tags.clear_existing_tags=false) : quand le fichier devient un AUTRE morceau,
 * rien de l'ancienne identité ne doit rester (cas réel du 2026-11-07 : « Taylor Swift – Style » avec l'artiste de
 * l'album « 2pac » et les paroles de 2Pac). Fichier MP3 synthétique dans un dossier temporaire ; nécessite ffmpeg.
 */
public class MergeOtherSongTest {

    private static Tag oldTwoPacTag(Path dir) throws Exception {
        File mp3 = dir.resolve("t.mp3").toFile();
        Process p = new ProcessBuilder("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=2", mp3.getAbsolutePath()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg indisponible", p.waitFor() == 0 && mp3.isFile());
        AudioFile af = AudioFileIO.read(mp3);
        Tag tag = af.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.ARTIST, "2Pac");
        tag.setField(FieldKey.TITLE, "Thug Style");
        tag.setField(FieldKey.ALBUM_ARTIST, "2Pac");
        tag.setField(FieldKey.ALBUM_ARTIST_SORT, "2Pac");
        tag.setField(FieldKey.LYRICS, "paroles de 2Pac");
        tag.setField(FieldKey.ISRC, "USUM71109243");
        tag.setField(FieldKey.TRACK, "14");
        tag.setField(FieldKey.BPM, "96");
        tag.setField(FieldKey.COMMENT, "rip perso");
        af.commit();
        return AudioFileIO.read(mp3).getTag();
    }

    @Test
    public void unAutreMorceauNeGardeQueCeQuiDecritLeSon() throws Exception {
        Path dir = Files.createTempDirectory("merge-other-song");
        Tag tag = oldTwoPacTag(dir);
        TagInfo neu = new TagInfo();
        neu.artist = "Taylor Swift";
        neu.title = "Style";
        neu.album = "1989";

        TagInfo m = new TagWriter().mergeWithExisting(neu, tag);

        assertEquals("Taylor Swift", m.artist);
        assertEquals("Style", m.title);
        assertEquals("", m.albumArtist);
        assertEquals("", m.lyrics);
        assertEquals("", m.isrc);
        assertEquals("", m.track);
        assertEquals("96", m.bpm);
        assertEquals("rip perso", m.comment);
    }

    @Test
    public void leMemeMorceauGardeSesChampsCommeAvant() throws Exception {
        Path dir = Files.createTempDirectory("merge-same-song");
        Tag tag = oldTwoPacTag(dir);
        TagInfo neu = new TagInfo();
        neu.artist = "2Pac";
        neu.title = "Thug Style";

        TagInfo m = new TagWriter().mergeWithExisting(neu, tag);

        assertEquals("2Pac", m.albumArtist);
        assertEquals("paroles de 2Pac", m.lyrics);
        assertEquals("USUM71109243", m.isrc);
        assertEquals("14", m.track);
    }
}
