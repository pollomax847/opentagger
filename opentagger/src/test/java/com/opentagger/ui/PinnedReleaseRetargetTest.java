package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.opentagger.MusicBrainzClient.ReleaseTrack;
import java.util.List;
import org.junit.Test;

public class PinnedReleaseRetargetTest {

    private static ReleaseTrack t(int no, String title, String rec, int lengthMs) {
        return new ReleaseTrack(1, no, 10, title, "Artist", rec, lengthMs, "", "tm" + no, "");
    }

    private final List<ReleaseTrack> tracks = List.of(
            t(1, "Intro", "rec-1", 60_000),
            t(2, "Piggy Bank", "rec-2", 246_000),
            t(3, "Candy Shop", "rec-3", 209_000));

    @Test
    public void sameRecordingIsFoundEvenWithADifferentTitle() {
        assertEquals(2, TaggingWorker.pickTrackInPinnedRelease(tracks, "REC-2", "autre titre", 0).trackNo());
    }

    @Test
    public void sameTitleWithCompatibleDurationIsAccepted() {
        assertEquals(3, TaggingWorker.pickTrackInPinnedRelease(tracks, "other", "Candy Shop", 210).trackNo());
    }

    @Test
    public void sameTitleWithWrongDurationIsRejected() {
        assertNull(TaggingWorker.pickTrackInPinnedRelease(tracks, "other", "Piggy Bank", 3600));
    }

    @Test
    public void unknownDurationNeedsAnAlmostIdenticalTitle() {
        assertNull(TaggingWorker.pickTrackInPinnedRelease(
                List.of(t(2, "Piggy Bank", "rec-2", 0)), "other", "Piggy Banks Remix", 0));
        assertEquals(2, TaggingWorker.pickTrackInPinnedRelease(
                List.of(t(2, "Piggy Bank", "rec-2", 0)), "other", "Piggy Bank", 0).trackNo());
    }

    @Test
    public void ownReleaseIsKeptOnlyWhenScoreIsMaxAndDurationIsKnownAndCompatible() {
        com.opentagger.model.TagInfo ti = new com.opentagger.model.TagInfo();
        ti.score = 100; ti.mbDurationSec = 240;
        org.junit.Assert.assertTrue(TaggingWorker.ownReleaseIsTrustworthy(ti, 242));
        org.junit.Assert.assertFalse(TaggingWorker.ownReleaseIsTrustworthy(ti, 0));      // durée du fichier inconnue
        org.junit.Assert.assertFalse(TaggingWorker.ownReleaseIsTrustworthy(ti, 3600));   // durée incompatible
        ti.score = 92;
        org.junit.Assert.assertFalse(TaggingWorker.ownReleaseIsTrustworthy(ti, 242));    // score non maximal
        ti.score = 100; ti.mbDurationSec = 0;
        org.junit.Assert.assertFalse(TaggingWorker.ownReleaseIsTrustworthy(ti, 242));    // durée MB inconnue
    }

    @Test
    public void unrelatedTitleIsRejected() {
        assertNull(TaggingWorker.pickTrackInPinnedRelease(tracks, "other", "Totally Different Song", 200));
    }
}
