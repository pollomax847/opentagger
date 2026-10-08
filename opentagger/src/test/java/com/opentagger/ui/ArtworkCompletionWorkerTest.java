package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.TagEnrichment;
import com.opentagger.ui.ArtworkCompletionWorker.Item;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ArtworkCompletionWorkerTest {

    private static Item item(String path, String artist, String albumArtist, String album) {
        return new Item(new File(path), artist, albumArtist, album, "", "", "");
    }

    @Test
    public void tracksOfOneAlbumShareOneSearch() {
        List<Item> items = List.of(
                item("/m/Daft Punk/Discovery/1.mp3", "Daft Punk", "", "Discovery"),
                item("/m/Daft Punk/Discovery/2.mp3", "daft punk", "Daft Punk", "DISCOVERY!"),
                item("/m/Daft Punk/Homework/1.mp3", "Daft Punk", "", "Homework"));
        Map<String, List<Item>> g = ArtworkCompletionWorker.groupByAlbum(items);
        assertEquals(2, g.size());
        assertEquals(2, g.values().iterator().next().size());
    }

    @Test
    public void tracksWithoutAlbumAreIgnoredForCovers() {
        assertTrue(ArtworkCompletionWorker.groupByAlbum(List.of(item("/m/a.mp3", "X", "", ""))).isEmpty());
    }

    @Test
    public void oneArtistFolderMeansOnePhotoSearchAndVariousArtistsGetNone() {
        List<Item> items = List.of(
                item("/m/Daft Punk/Discovery/1.mp3", "Daft Punk", "", "Discovery"),
                item("/m/Daft Punk/Homework/1.mp3", "Daft Punk", "", "Homework"),
                item("/m/Various/Hits/1.mp3", "Someone", "Various Artists", "Hits"));
        Map<String, List<Item>> g = ArtworkCompletionWorker.groupByArtistFolder(items);
        assertEquals(1, g.size());
        assertEquals(2, g.values().iterator().next().size());
    }

    @Test
    public void existingPhotoIsRecognisedInEitherFormat() throws Exception {
        Path dir = Files.createTempDirectory("otphoto");
        try {
            assertFalse(ArtworkCompletionWorker.hasPhoto(dir, "artist"));
            Files.write(dir.resolve("artist.png"), new byte[]{1});
            assertTrue(ArtworkCompletionWorker.hasPhoto(dir, "artist"));
        } finally {
            Files.deleteIfExists(dir.resolve("artist.png"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    public void aLocalFolderJpgIsNeverDeletedAsIfItWereATemporaryDownload() throws Exception {
        Path dir = Files.createTempDirectory("otcover");
        Path local = dir.resolve("folder.jpg");
        Path temp = dir.resolve("ot-download-123.jpg");
        try {
            Files.write(local, new byte[]{1});
            Files.write(temp, new byte[]{1});
            TagEnrichment.discardTemporaryCover(local);
            TagEnrichment.discardTemporaryCover(temp);
            assertTrue("la pochette de l'utilisateur doit rester", Files.exists(local));
            assertFalse("le téléchargement temporaire doit partir", Files.exists(temp));
        } finally {
            Files.deleteIfExists(local);
            Files.deleteIfExists(temp);
            Files.deleteIfExists(dir);
        }
    }
}
