package com.opentagger;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;

/** Parcours sans stat() pour les noms à extension audio (2026-09-26) : mêmes fichiers trouvés,
 *  et un dossier nommé « x.mp3 » est remonté tel quel (l'appelant le détecte ensuite). */
public class AudioScannerTrustExtensionTest {

    @Test public void sameAudioFilesFound_andDirectoryNamedLikeAudioIsReported() throws Exception {
        Path root = Files.createTempDirectory("otscan");
        Files.createDirectories(root.resolve("Artiste/Album"));
        for (String n : new String[]{"a.mp3", "b.FLAC", "c.m4a", "d.opus", "e.dsf", "cover.jpg", "._f.mp3"}) {
            Files.createFile(root.resolve("Artiste/Album").resolve(n));
        }
        Files.createDirectories(root.resolve("Bizarre.mp3"));
        Files.createFile(root.resolve("Bizarre.mp3/inside.mp3"));

        List<String> slow = names(root, false);
        List<String> fast = names(root, true);
        assertEquals(List.of("Artiste/Album/a.mp3", "Artiste/Album/b.FLAC", "Artiste/Album/c.m4a",
                "Artiste/Album/d.opus", "Artiste/Album/e.dsf", "Bizarre.mp3/inside.mp3"), slow);
        assertEquals(List.of("Artiste/Album/a.mp3", "Artiste/Album/b.FLAC", "Artiste/Album/c.m4a",
                "Artiste/Album/d.opus", "Artiste/Album/e.dsf", "Bizarre.mp3"), fast);
    }

    private static List<String> names(Path root, boolean trust) {
        List<File> found = new ArrayList<>();
        new AudioScanner().scan(root.toFile(), found::add, () -> false, trust);
        return found.stream().map(f -> root.relativize(f.toPath()).toString().replace('\\', '/'))
                .sorted().collect(Collectors.toList());
    }
}
