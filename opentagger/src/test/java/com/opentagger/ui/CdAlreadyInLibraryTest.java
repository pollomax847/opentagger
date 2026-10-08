package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.MusicBrainzClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CdAlreadyInLibraryTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static MusicBrainzClient.ReleaseTracklist release(String artist, String album, String... titles) {
        List<MusicBrainzClient.ReleaseTrack> tracks = new java.util.ArrayList<>();
        for (int i = 0; i < titles.length; i++)
            tracks.add(new MusicBrainzClient.ReleaseTrack(1, i + 1, titles.length, titles[i], artist, "", 0, "", "", ""));
        return new MusicBrainzClient.ReleaseTracklist("rel", album, artist, "", "2013", "", false, tracks,
                "", "", "", "", "", "", "", "", "", null);
    }

    @Test
    public void namesMatchWhateverTheCaseAccentsOrPunctuation() {
        assertEquals(CdImportDialog.norm("Christophe Maé"), CdImportDialog.norm("christophe mae"));
        assertEquals(CdImportDialog.norm("L'attrape-rêves"), CdImportDialog.norm("L’Attrape‐reves"));
        assertFalse(CdImportDialog.norm("Christophe Maé").equals(CdImportDialog.norm("Christophe Willem")));
    }

    @Test
    public void fileNamesReduceToTheirTitle() {
        assertEquals("je veux du bonheur", CdImportDialog.titleOfFileName("01 - Je veux du bonheur.mp3"));
        assertEquals("je veux du bonheur", CdImportDialog.titleOfFileName("1. Je veux du bonheur (2).flac"));
        assertEquals("ma douleur ma peine", CdImportDialog.titleOfFileName("02 Ma douleur, ma peine.m4a"));
    }

    @Test
    public void tracksAlreadyOnDiskAreFoundInTheLibraryFolderEvenWithOtherCaseAndAccents() throws Exception {
        Path lib = tmp.newFolder("lib").toPath();
        Path album = Files.createDirectories(lib.resolve("Christophe Mae").resolve("je veux du bonheur"));
        Files.writeString(album.resolve("01 - Je veux du bonheur.mp3"), "x");
        Files.writeString(album.resolve("02 - Ma douleur, ma peine.mp3"), "x");
        Files.writeString(album.resolve("cover.jpg"), "x");
        Path other = Files.createDirectories(lib.resolve("Christophe Willem").resolve("Inventaire"));
        Files.writeString(other.resolve("01 - Je veux du bonheur.mp3"), "x");

        Map<Integer, List<Path>> found = CdImportDialog.findOnDisk(lib,
                release("Christophe Maé", "Je veux du bonheur", "Je veux du bonheur", "Ma douleur, ma peine", "Charly"));

        assertEquals(2, found.size());
        assertTrue(found.containsKey(1));
        assertTrue(found.containsKey(2));
        assertFalse("Charly n'est pas sur le disque", found.containsKey(3));
        assertEquals("seul le dossier du bon artiste et du bon album compte", 1, found.get(1).size());
        assertTrue(found.get(1).get(0).startsWith(album));
    }

    @Test
    public void anotherAlbumOfTheSameArtistIsNotADuplicate() throws Exception {
        Path lib = tmp.newFolder("lib2").toPath();
        Path other = Files.createDirectories(lib.resolve("Christophe Maé").resolve("Mon paradis"));
        Files.writeString(other.resolve("01 - Je veux du bonheur.mp3"), "x");
        assertTrue(CdImportDialog.findOnDisk(lib, release("Christophe Maé", "Je veux du bonheur", "Je veux du bonheur")).isEmpty());
    }

    @Test
    public void noLibraryMeansNothingFound() {
        assertTrue(CdImportDialog.findOnDisk(null, release("A", "B", "C")).isEmpty());
        assertTrue(CdImportDialog.findOnDisk(java.nio.file.Path.of("Z:\\n'existe\\pas"), release("A", "B", "C")).isEmpty());
    }
}
