package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenericTagNamesTest {

    @Test
    public void downloadPlaceholderNamesAreGeneric() {
        assertTrue(TaggingWorker.isGenericTag("download"));
        assertTrue(TaggingWorker.isGenericTag("Download"));
        assertTrue(TaggingWorker.isGenericTag("download (3)"));
        assertTrue(TaggingWorker.isGenericTag("download_12"));
        assertTrue(TaggingWorker.isGenericTag("downloaded"));
        assertTrue(TaggingWorker.isGenericTag("Nouvel enregistrement 2"));
        assertTrue(TaggingWorker.isGenericTag("Sans titre"));
    }

    @Test
    public void realTitlesContainingTheWordAreNotGeneric() {
        assertFalse(TaggingWorker.isGenericTag("Download Me"));
        assertFalse(TaggingWorker.isGenericTag("Free Download"));
        assertFalse(TaggingWorker.isGenericTag("Piggy Bank"));
    }
}
