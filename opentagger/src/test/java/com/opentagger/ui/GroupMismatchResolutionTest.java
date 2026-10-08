package com.opentagger.ui;

import static org.junit.Assert.assertEquals;

import com.opentagger.MetadataCache;

import org.junit.Test;

/** Résolution automatique « contredit la release du groupe » (2026-09-26). */
public class GroupMismatchResolutionTest {

    @Test public void siblingEditionOfSameAlbum_isAccepted() {
        assertEquals(TaggingWorker.GroupMismatch.SIBLING_EDITION,
                TaggingWorker.resolveGroupMismatch(5, MetadataCache.SOURCE_TEXT, 90, "rg-elvis", "RG-ELVIS"));
    }

    @Test public void otherReleaseConfirmedByAudioOrHighScore_isAccepted() {
        assertEquals(TaggingWorker.GroupMismatch.CONFIRMED_OTHER_RELEASE,
                TaggingWorker.resolveGroupMismatch(3, MetadataCache.SOURCE_SONGREC, 90, "rg-a", "rg-b"));
        assertEquals(TaggingWorker.GroupMismatch.CONFIRMED_OTHER_RELEASE,
                TaggingWorker.resolveGroupMismatch(3, MetadataCache.SOURCE_TEXT, 100, "rg-a", "rg-b"));
    }

    @Test public void onlyAMediumTextMatchOnAnUnrelatedAlbum_staysForReview() {
        assertEquals(TaggingWorker.GroupMismatch.SKIP,
                TaggingWorker.resolveGroupMismatch(4, MetadataCache.SOURCE_TEXT, 90, "rg-a", "rg-b"));
        assertEquals(TaggingWorker.GroupMismatch.SKIP,
                TaggingWorker.resolveGroupMismatch(4, MetadataCache.SOURCE_TEXT, 90, "", "rg-b"));
    }

    @Test public void noConflictBelowCorroborationOrForTrustedSources() {
        assertEquals(TaggingWorker.GroupMismatch.NO_CONFLICT,
                TaggingWorker.resolveGroupMismatch(1, MetadataCache.SOURCE_TEXT, 90, "rg-a", "rg-b"));
        assertEquals(TaggingWorker.GroupMismatch.NO_CONFLICT,
                TaggingWorker.resolveGroupMismatch(5, MetadataCache.SOURCE_DISCID, 95, "rg-a", "rg-b"));
    }
}
