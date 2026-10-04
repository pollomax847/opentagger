package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CdDiscIdTest {

    private static CdRipper.Toc toc(int... lengths) {
        List<CdRipper.Track> t = new ArrayList<>();
        for (int i = 0; i < lengths.length; i++) t.add(new CdRipper.Track(i + 1, lengths[i]));
        return new CdRipper.Toc(t);
    }

    @Test
    public void discIdHasMusicBrainzShape() {
        String id = toc(11325, 17100, 21750).discId();
        assertEquals(28, id.length());
        assertTrue(id, id.matches("[A-Za-z0-9._]{27}-"));
    }

    /** Vecteur de la documentation MusicBrainz (Nirvana « Nevermind », TOC 1 12 267257 150 22767 …) ; ce Disc ID a été
     *  confirmé en direct : /ws/2/discid/I5l9cCSFccLKFEKS.7wqSZAorPU- renvoie les releases « Nevermind ». */
    @Test
    public void matchesTheDiscIdMusicBrainzKnowsForNevermind() {
        int[] off = {150, 22767, 41887, 58317, 72102, 91375, 104652, 115380, 132165, 143932, 159870, 174597};
        int leadOut = 267257;
        List<CdRipper.Track> tracks = new ArrayList<>();
        for (int i = 0; i < off.length; i++)
            tracks.add(new CdRipper.Track(i + 1, (i + 1 < off.length ? off[i + 1] : leadOut) - off[i]));
        assertEquals("I5l9cCSFccLKFEKS.7wqSZAorPU-", new CdRipper.Toc(tracks).discId());
    }

    @Test
    public void sameDiscSameIdDifferentDiscDifferentId() {
        assertEquals(toc(100, 200, 300).discId(), toc(100, 200, 300).discId());
        assertNotEquals(toc(100, 200, 300).discId(), toc(100, 200, 301).discId());
    }

    @Test
    public void totalSectorsIncludesTheTwoSecondLeadIn() {
        assertEquals(150 + 100 + 200, toc(100, 200).totalSectors());
    }

    @Test
    public void nonContiguousOrEmptyDiscHasNoId() {
        assertEquals("", new CdRipper.Toc(List.of()).discId());
        assertEquals("", new CdRipper.Toc(List.of(new CdRipper.Track(2, 100), new CdRipper.Track(3, 100))).discId());
        assertEquals("", new CdRipper.Toc(List.of(new CdRipper.Track(1, 100), new CdRipper.Track(3, 100))).discId());
    }
}
