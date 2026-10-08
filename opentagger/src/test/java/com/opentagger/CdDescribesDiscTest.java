package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class CdDescribesDiscTest {

    /** Le cas réel : 20 pistes et une durée totale presque identique, mais seules 3 pistes sur 20 concordent. */
    @Test
    public void sameTrackCountAndTotalButDifferentTracksIsRejected() {
        List<Integer> cd = List.of(200, 210, 220, 230, 240, 250, 260, 270, 280, 290);
        List<Integer> other = List.of(200, 185, 220, 239, 231, 291, 233, 240, 231, 358);
        assertEquals(2, CdAudioIdentifier.tracksWithinTolerance(cd, other, 3));
        assertFalse(CdAudioIdentifier.describesDisc(cd, other));
    }

    @Test
    public void aReleaseWithTheSameTrackLengthsIsAccepted() {
        List<Integer> cd = List.of(151, 228, 290, 272, 253, 232, 210, 207, 217, 183);
        List<Integer> mb = List.of(151, 229, 290, 273, 253, 233, 211, 207, 218, 184); // ±1 s, comme une vraie parution
        assertTrue(CdAudioIdentifier.describesDisc(cd, mb));
    }

    @Test
    public void oneOddTrackOutOfTwentyIsTolerated() {
        List<Integer> cd = new java.util.ArrayList<>(java.util.Collections.nCopies(20, 200));
        List<Integer> mb = new java.util.ArrayList<>(cd);
        mb.set(19, 50); // une piste cachée ou prolongée : 19 sur 20 concordent
        assertTrue(CdAudioIdentifier.describesDisc(cd, mb));
        mb.set(18, 50); // deux écarts : 18 sur 20 = 90 % : encore accepté
        assertTrue(CdAudioIdentifier.describesDisc(cd, mb));
        mb.set(17, 50); // trois écarts : 17 sur 20 = 85 % : rejeté
        assertFalse(CdAudioIdentifier.describesDisc(cd, mb));
    }

    @Test
    public void differentTrackCountsOrEmptyAreRejected() {
        assertFalse(CdAudioIdentifier.describesDisc(List.of(100, 200), List.of(100, 200, 300)));
        assertFalse(CdAudioIdentifier.describesDisc(List.of(), List.of()));
    }
}
