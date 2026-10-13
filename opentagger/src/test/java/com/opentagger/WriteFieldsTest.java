package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jaudiotagger.tag.FieldKey;
import org.junit.Test;

/** TagWriter.writeFields (corrections « À corriger », 2026-10-13) : seuls les champs demandés changent, et un fichier
 *  jamais identifié ne reçoit pas le marqueur OT_TAGGEDDATE (sinon le scan suivant le croirait tagué). Fichiers
 *  synthétiques dans un dossier temporaire uniquement. */
public class WriteFieldsTest {

    private static File synth(Path dir, String name, String... codecArgs) throws Exception {
        File out = dir.resolve(name).toFile();
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-metadata", "title=Sweet Dreams", "-metadata", "artist=Eurythmics", "-metadata", "genre=Pop",
                "-metadata", "comment=garde-moi"));
        cmd.addAll(List.of(codecArgs));
        cmd.add(out.getAbsolutePath());
        Process p;
        try { p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); }
        catch (Exception e) { assumeTrue("ffmpeg absent", false); return null; }
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg n'a pas produit " + name, p.waitFor() == 0 && out.isFile());
        return out;
    }

    @Test public void onlyTheGivenFieldsChange() throws Exception {
        Path dir = Files.createTempDirectory("otfields");
        for (File f : new File[]{synth(dir, "a.mp3", "-c:a", "libmp3lame"), synth(dir, "a.flac", "-c:a", "flac"),
                                 synth(dir, "a.m4a", "-c:a", "aac")}) {
            new TagWriter().writeFields(f, Map.of(FieldKey.ALBUM_ARTIST, "Eurythmics", FieldKey.TRACK, "5", FieldKey.GENRE, ""),
                    Map.of("REPLAYGAIN_TRACK_GAIN", "-3.00 dB", "REPLAYGAIN_TRACK_PEAK", "0.900000"));
            TagInfo t = TagReader.read(f);
            String n = f.getName();
            assertEquals(n, "Eurythmics", t.albumArtist);
            assertEquals(n, "5", t.track);
            assertEquals(n, "", t.genre);
            assertEquals(n, "Sweet Dreams", t.title);
            assertEquals(n, "Eurythmics", t.artist);
            assertEquals(n, "-3.00 dB", t.replayGainTrackGain);
            assertEquals(n, "", t.taggedDate);
        }
    }
}
