package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jaudiotagger.tag.FieldKey;
import org.junit.Test;

/** « Fix Album Art » (script MediaMonkey de l'utilisateur, refait le 2026-10-14) : une seule image, la pochette de face,
 *  les autres rendues pour pouvoir les remettre. Fichiers synthétiques dans un dossier temporaire uniquement. */
public class FixAlbumArtTest {

    private static File ffmpeg(Path dir, String name, String... args) throws Exception {
        File out = dir.resolve(name).toFile();
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi"));
        cmd.addAll(List.of(args));
        cmd.add(out.getAbsolutePath());
        Process p;
        try { p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); }
        catch (Exception e) { assumeTrue("ffmpeg absent", false); return null; }
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg n'a pas produit " + name, p.waitFor() == 0 && out.isFile());
        return out;
    }

    @Test public void keepsOnlyTheFrontCoverAndGivesTheOthersBack() throws Exception {
        Path dir = Files.createTempDirectory("otart");
        byte[] red = Files.readAllBytes(ffmpeg(dir, "red.jpg", "-i", "color=c=red:s=64x64", "-frames:v", "1").toPath());
        byte[] blue = Files.readAllBytes(ffmpeg(dir, "blue.jpg", "-i", "color=c=blue:s=64x64", "-frames:v", "1").toPath());
        for (File f : new File[]{ffmpeg(dir, "a.mp3", "-i", "sine=duration=2", "-metadata", "title=Song", "-c:a", "libmp3lame"),
                                 ffmpeg(dir, "a.flac", "-i", "sine=duration=2", "-metadata", "title=Song", "-c:a", "flac")}) {
            TagWriter w = new TagWriter();
            // Dos d'abord, face ensuite : c'est la face qui doit rester, pas la première image.
            w.addImages(f, List.of(new TagWriter.Image(blue, "image/jpeg", 4, "back"),
                                   new TagWriter.Image(red, "image/jpeg", 3, "front")));
            assertTrue(f.getName(), TagReader.read(f).coverInfo.startsWith("2:"));

            TagWriter.AlbumArtFix fix = w.fixAlbumArt(f);
            assertTrue(f.getName(), fix.changed());
            assertEquals(f.getName(), 1, fix.removed().size());
            assertEquals(f.getName(), 4, fix.removed().get(0).pictureType());
            String after = TagReader.read(f).coverInfo;
            assertEquals(f.getName(), "1:" + TagReader.crc(red), after);
            assertEquals(f.getName(), "Song", TagReader.read(f).title);
            assertEquals(f.getName(), "", TagReader.read(f).taggedDate);
            assertFalse(f.getName(), w.fixAlbumArt(f).changed());                    // déjà propre : rien à faire

            w.addImages(f, fix.removed());                                            // « Annuler »
            assertTrue(f.getName(), TagReader.read(f).coverInfo.startsWith("2:"));

            // Écrire des champs ne touche pas aux images.
            w.writeFields(f, Map.of(FieldKey.GENRE, "Pop"), Map.of());
            assertTrue(f.getName(), TagReader.read(f).coverInfo.startsWith("2:"));

            w.removeImages(f);
            assertEquals(f.getName(), "0", TagReader.read(f).coverInfo);
        }
    }

    @Test public void m4aKeepsTheFirstImage() throws Exception {
        Path dir = Files.createTempDirectory("otart4");
        byte[] red = Files.readAllBytes(ffmpeg(dir, "red.jpg", "-i", "color=c=red:s=64x64", "-frames:v", "1").toPath());
        byte[] blue = Files.readAllBytes(ffmpeg(dir, "blue.jpg", "-i", "color=c=blue:s=64x64", "-frames:v", "1").toPath());
        File f = ffmpeg(dir, "a.m4a", "-i", "sine=duration=2", "-metadata", "title=Song", "-c:a", "aac");
        TagWriter w = new TagWriter();
        w.addImages(f, List.of(new TagWriter.Image(red, "image/jpeg", 3, ""), new TagWriter.Image(blue, "image/jpeg", 4, "")));
        assertTrue(TagReader.read(f).coverInfo.startsWith("2:"));
        TagWriter.AlbumArtFix fix = w.fixAlbumArt(f);   // MP4 n'a pas de type d'image : la première reste
        assertEquals(1, fix.removed().size());
        assertEquals("1:" + TagReader.crc(red), TagReader.read(f).coverInfo);
        assertEquals("Song", TagReader.read(f).title);
    }
}
