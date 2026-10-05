package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.MetadataCache;
import com.opentagger.model.TagInfo;
import org.junit.Test;

public class ReleaseProofTest {

    private static TagInfo withRelease(String mbid) {
        TagInfo t = new TagInfo();
        t.releaseMbid = mbid;
        return t;
    }

    @Test
    public void aDiscIdProvesTheAlbum() {
        assertTrue(TaggingWorker.releaseIsProven(MetadataCache.SOURCE_DISCID, withRelease("")));
    }

    @Test
    public void aRecordingIdAloneDoesNotProveTheAlbum() {
        assertFalse(TaggingWorker.releaseIsProven(MetadataCache.SOURCE_MBID, withRelease("")));
        assertFalse(TaggingWorker.releaseIsProven(MetadataCache.SOURCE_MBID, null));
    }

    @Test
    public void aFileThatAlreadyCarriesItsReleaseIdIsProven() {
        assertTrue(TaggingWorker.releaseIsProven(MetadataCache.SOURCE_MBID, withRelease("3907821a-8512-4efe-a273-15056130454d")));
    }

    @Test
    public void otherSourcesNeverProveTheAlbum() {
        assertFalse(TaggingWorker.releaseIsProven(MetadataCache.SOURCE_ACOUSTID, withRelease("x")));
        assertFalse(TaggingWorker.releaseIsProven(MetadataCache.SOURCE_TEXT, withRelease("x")));
        assertFalse(TaggingWorker.releaseIsProven(null, withRelease("x")));
    }
}
