package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentagger.MusicBrainzClient.ReleaseTrack;
import com.opentagger.MusicBrainzClient.ReleaseTracklist;
import com.opentagger.model.TagInfo;
import java.util.List;
import org.junit.Test;

public class AlbumCoherencePassTest {

    @Test
    public void dominantReleaseNeedsThreeTracksAndASixtyPercentMajority() {
        assertEquals("A", TaggingWorker.dominantRelease(List.of("A", "A", "A", "B")));            // 3/4
        assertEquals("A", TaggingWorker.dominantRelease(List.of("A", "A", "A", "A", "B", "C")));  // 4/6
        assertNull(TaggingWorker.dominantRelease(List.of("A", "A", "B", "B")));                   // pas de majorité
        assertNull(TaggingWorker.dominantRelease(List.of("A", "A", "B")));                        // moins de 3 pistes
        assertNull(TaggingWorker.dominantRelease(List.of("A", "A", "A", "B", "C", "D", "E")));    // 3/7 : dossier mélangé
        assertNull(TaggingWorker.dominantRelease(List.of("", "A", "A")));                         // blancs ignorés, 2 pistes seulement
    }

    @Test
    public void mixedFoldersOfCompilationsAreLeftAlone() {
        // dossier « Various Artists » : 5 releases différentes, aucune dominante → rien à uniformiser
        assertNull(TaggingWorker.dominantRelease(List.of("A", "B", "C", "D", "E", "A")));
    }

    @Test
    public void applyingATrackOfAnotherReleaseRewritesTheReleaseFieldsAndClearsTheCompilationFlag() {
        TagInfo t = new TagInfo();
        t.artist = "Eminem"; t.title = "Lucky You"; t.album = "Curtain Call 2"; t.releaseMbid = "cc2"; t.isCompilation = "1";
        t.track = "2"; t.discNo = "1"; t.label = "Ancien label";
        ReleaseTrack tr = new ReleaseTrack(1, 3, 13, "Lucky You", "Eminem", "rec-3", 244_000, "", "trk-3", "");
        ReleaseTracklist tl = new ReleaseTracklist("kam", "Kamikaze", "Eminem", "Eminem", "2018", "rg-kam", false,
                List.of(tr), "US", "123", "Official", "Shady", "B0", "Latn", "", "album", "2018", null);
        TaggingWorker.applyTrackOfRelease(t, tl, tr);
        assertEquals("Kamikaze", t.album);
        assertEquals("kam", t.releaseMbid);
        assertEquals("3", t.track);
        assertEquals("13", t.trackTotal);
        assertEquals("", t.isCompilation);
        assertEquals("Shady", t.label);
        assertEquals("Lucky You", t.title);
        assertTrue(t.mbDurationSec == 244);
    }
}
