package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.AlbumMatcher;
import java.util.Arrays;
import org.junit.Test;

public class AlbumTagGroupingTest {

    @Test
    public void placeholderAlbumNamesNeverGroupFiles() {
        assertTrue(TaggingWorker.isGenericAlbumName(AlbumMatcher.norm("Unknown Album")));
        assertTrue(TaggingWorker.isGenericAlbumName(AlbumMatcher.norm("Album inconnu")));
        assertTrue(TaggingWorker.isGenericAlbumName(AlbumMatcher.norm("Audios")));
        assertTrue(TaggingWorker.isGenericAlbumName(AlbumMatcher.norm("Greatest Hits")));
    }

    @Test
    public void aRipSessionPlaceholderWithItsDateIsARealGroup() {
        // « Album inconnu (05/10/2026 06:46:13) » : propre à une extraction de CD, la date l'individualise
        assertFalse(TaggingWorker.isGenericAlbumName(AlbumMatcher.norm("Album inconnu (05/10/2026 06:46:13)")));
        assertFalse(TaggingWorker.isGenericAlbumName(AlbumMatcher.norm("Back 2 the dance - CD 3")));
    }

    @Test
    public void mostCommonIgnoresBlanksAndPicksTheMajority() {
        assertEquals("2013", TaggingWorker.mostCommon(Arrays.asList("2013", "", null, "2013", "2010")));
        assertEquals("", TaggingWorker.mostCommon(Arrays.asList("", null, " ")));
    }
}
