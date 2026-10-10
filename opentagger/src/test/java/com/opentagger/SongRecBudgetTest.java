package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class SongRecBudgetTest {

    @Before
    public void reset() { SongRecClient.resetBudget(); }

    @Test
    public void callsBeyondTheBudgetAreRefusedUntilTheWindowSlides() {
        long t0 = 1_000_000L;
        for (int i = 0; i < 3; i++) assertEquals(0, SongRecClient.reserveCall(t0 + i, 3));
        long until = SongRecClient.reserveCall(t0 + 10, 3);
        assertEquals(t0 + 10 * 60_000L, until);
        // la fenêtre a glissé : le plus ancien appel est sorti, un nouvel appel passe
        assertEquals(0, SongRecClient.reserveCall(t0 + 10 * 60_000L + 1, 3));
    }

    @Test
    public void zeroMeansUnlimited() {
        for (int i = 0; i < 1000; i++) assertEquals(0, SongRecClient.reserveCall(5_000L + i, 0));
    }

    @Test
    public void budgetPausesSongRecUntilTheWindowFrees() {
        long now = System.currentTimeMillis();
        SongRecClient.reserveCall(now, 1);
        assertTrue(SongRecClient.reserveCall(now + 1, 1) > now);
        assertTrue(SongRecClient.isPaused());
    }

    @Test
    public void decodeErrorsAreRecognisedInFfmpegOutput() {
        assertNull(AudioTranscoder.firstDecodeError(""));
        assertNull(AudioTranscoder.firstDecodeError(null));
        assertNotNull(AudioTranscoder.firstDecodeError("[aac @ 0x1] channel element 0.0 duplicate\nfoo"));
        assertEquals("Error submitting packet to decoder: Invalid data found",
            AudioTranscoder.firstDecodeError("x\nError submitting packet to decoder: Invalid data found\n"));
    }
}
