package com.opentagger;

import static org.junit.Assert.assertEquals;

import com.opentagger.model.TagInfo;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class ArtistPhotoFolderTest {

    private static TagInfo ti(String artist, String albumArtist) {
        TagInfo t = new TagInfo(); t.artist = artist; t.albumArtist = albumArtist; return t;
    }

    @Test
    public void artistAlbumTrackLayoutUsesTheArtistFolder() {
        Path track = Paths.get("G:/Musiques/50 Cent/The Massacre/05 - Piggy Bank.mp3");
        assertEquals(Paths.get("G:/Musiques/50 Cent"), TagEnrichment.artistPhotoFolder(track, ti("50 Cent", "50 Cent")));
    }

    @Test
    public void folderNameMatchIgnoresCaseAndPunctuation() {
        Path track = Paths.get("M/AC_DC/Back in Black/01.mp3");
        assertEquals(Paths.get("M/AC_DC"), TagEnrichment.artistPhotoFolder(track, ti("AC/DC", "")));
    }

    @Test
    public void albumArtistIsUsedWhenTheTrackArtistIsAFeaturing() {
        Path track = Paths.get("M/Daft Punk/Discovery/01.mp3");
        assertEquals(Paths.get("M/Daft Punk"), TagEnrichment.artistPhotoFolder(track, ti("Daft Punk feat. X", "Daft Punk")));
    }

    @Test
    public void unrelatedParentFallsBackToTheAlbumFolder() {
        Path track = Paths.get("M/Compilations/NRJ Hits 2019/02.mp3");
        assertEquals(Paths.get("M/Compilations/NRJ Hits 2019"), TagEnrichment.artistPhotoFolder(track, ti("CNCO", "Various Artists")));
    }

    @Test
    public void trackDirectlyInTheArtistFolder() {
        Path track = Paths.get("M/Enya/Astra.mp3");
        assertEquals(Paths.get("M/Enya"), TagEnrichment.artistPhotoFolder(track, ti("Enya", "")));
    }
}
