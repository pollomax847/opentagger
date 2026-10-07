package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opentagger.MusicBrainzClient;
import com.opentagger.ui.CdImportDialog.Known;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CdKnownTracksTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static MusicBrainzClient.ReleaseTracklist release(String artist, String album, String... titles) {
        List<MusicBrainzClient.ReleaseTrack> tracks = new ArrayList<>();
        for (int i = 0; i < titles.length; i++)
            tracks.add(new MusicBrainzClient.ReleaseTrack(1, i + 1, titles.length, titles[i], artist, "", 0, "", "", ""));
        return new MusicBrainzClient.ReleaseTracklist("rel", album, artist, "", "2013", "", false, tracks,
                "", "", "", "", "", "", "", "", "", null);
    }

    @Test public void tracksAreFoundFromWhatTheAppAlreadyKnowsWithoutTouchingTheDisk() {
        List<Known> known = List.of(
                new Known(Path.of("E:/m/a/1.mp3"), "Christophe Maé", "Christophe Maé", "Je veux du bonheur", "Je veux du bonheur", 1),
                new Known(Path.of("E:/m/a/2.mp3"), "christophe mae", "", "JE VEUX DU BONHEUR", "Ma douleur, ma peine", 2),
                new Known(Path.of("E:/m/b/1.mp3"), "Christophe Willem", "Christophe Willem", "Inventaire", "Je veux du bonheur", 1));
        Map<Integer, List<Path>> found = CdImportDialog.findKnown(known,
                release("Christophe Maé", "Je veux du bonheur", "Je veux du bonheur", "Ma douleur, ma peine"));
        assertEquals(2, found.size());
        assertEquals(List.of(Path.of("E:/m/a/1.mp3")), found.get(1));
        assertEquals(List.of(Path.of("E:/m/a/2.mp3")), found.get(2));
    }

    @Test public void aTrackWithoutTitleMatchFallsBackOnItsNumber() {
        List<Known> known = List.of(new Known(Path.of("x/3.mp3"), "Artiste", "", "Album", "Titre faux", 2));
        Map<Integer, List<Path>> found = CdImportDialog.findKnown(known, release("Artiste", "Album", "A", "B", "C"));
        assertEquals(List.of(Path.of("x/3.mp3")), found.get(2));
    }

    @Test public void anotherAlbumByTheSameTitleIsNotMatched() {
        List<Known> known = List.of(new Known(Path.of("x/1.mp3"), "Autre", "Autre", "Autre album", "Titre", 1));
        assertTrue(CdImportDialog.findKnown(known, release("Artiste", "Album", "Titre")).isEmpty());
    }

    @Test public void theExpectedFolderIsReachedDirectlyWithoutListingTheRoot() throws Exception {
        Path lib = tmp.newFolder("lib").toPath();
        Path album = Files.createDirectories(lib.resolve("Artiste").resolve("Album"));
        Files.writeString(album.resolve("01 - Titre un.mp3"), "x");
        // un dossier voisin volumineux qui ne doit pas être parcouru
        for (int i = 0; i < 50; i++) Files.createDirectories(lib.resolve("Autre " + i).resolve("X"));
        Map<Integer, List<Path>> found = CdImportDialog.findOnDiskDirect(lib, release("Artiste", "Album", "Titre un", "Titre deux"));
        assertEquals(1, found.size());
        assertTrue(found.get(1).get(0).endsWith("01 - Titre un.mp3"));
    }

    @Test public void illegalCharactersInNamesAreHandled() throws Exception {
        Path lib = tmp.newFolder("lib2").toPath();
        assertTrue(CdImportDialog.findOnDiskDirect(lib, release("AC/DC", "Back: In Black?", "T")).isEmpty()); // pas d'exception
    }

    @Test public void mergingKeepsEachFileOnce() throws Exception {
        Path lib = tmp.newFolder("lib3").toPath();
        Path album = Files.createDirectories(lib.resolve("Artiste").resolve("Album"));
        Path f = album.resolve("01 - Titre un.mp3");
        Files.writeString(f, "x");
        List<Known> known = List.of(new Known(f, "Artiste", "Artiste", "Album", "Titre un", 1));
        Map<Integer, List<Path>> all = CdImportDialog.findExisting(lib, known, release("Artiste", "Album", "Titre un"));
        assertEquals(1, all.get(1).size());
    }
}
