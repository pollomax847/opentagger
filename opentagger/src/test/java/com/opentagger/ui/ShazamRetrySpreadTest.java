package com.opentagger.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Fichiers « Shazam limité » retentés un par un au rythme accepté, pas tous au même instant (2026-10-12). */
public class ShazamRetrySpreadTest {

    @Test public void retriesAreSpacedAfterThePause() {
        long now = 4_000_000_000_000L;                 // loin devant, sans interférer avec d'autres tests
        long pausedUntil = now + 10 * 60_000L;
        long first = TaggingWorker.nextRetrySlot(now, pausedUntil, 15_000L);
        assertEquals(pausedUntil + 30_000L, first);
        assertEquals(first + 15_000L, TaggingWorker.nextRetrySlot(now, pausedUntil, 15_000L));
        assertEquals(first + 30_000L, TaggingWorker.nextRetrySlot(now + 1_000L, pausedUntil, 15_000L));
    }
}
