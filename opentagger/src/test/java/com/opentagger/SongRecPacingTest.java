package com.opentagger;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Rythme de songrec-rename (2026-10-13) : 1 s après un morceau reconnu, 4 s après un échec, rien d'autre. */
public class SongRecPacingTest {

    @Test public void waitsLikeSongrecRename() {
        long t = 1_000_000L;
        assertEquals(1000, SongRecClient.waitBeforeNextCall(t, t, true, 1000, 4000));
        assertEquals(4000, SongRecClient.waitBeforeNextCall(t, t, false, 1000, 4000));
        assertEquals(2500, SongRecClient.waitBeforeNextCall(t + 1500, t, false, 1000, 4000));
        assertEquals(0, SongRecClient.waitBeforeNextCall(t + 9000, t, false, 1000, 4000));
    }

    @Test public void firstPauseIsOneMinute() {
        assertEquals(60_000L, SongRecClient.FIRST_PAUSE_MS);
    }
}
