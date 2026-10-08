package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlayCountsTest {

    @Test
    public void missingOrUnreadableStoredValueIsReplaced() {
        assertTrue(PlayCounts.needsUpdate(null, 3));
        assertTrue(PlayCounts.needsUpdate("", 3));
        assertTrue(PlayCounts.needsUpdate("   ", 3));
        assertTrue(PlayCounts.needsUpdate("abc", 3));
    }

    @Test
    public void onlyAStrictlyHigherOnlineCountTriggersAWrite() {
        assertTrue(PlayCounts.needsUpdate("5", 6));
        assertFalse(PlayCounts.needsUpdate("5", 5));    // déjà à jour : pas de réécriture
        assertFalse(PlayCounts.needsUpdate("5", 4));    // jamais de baisse
        assertFalse(PlayCounts.needsUpdate(" 12 ", 12));
    }

    @Test
    public void autoSyncIsDueAtMostOncePerDay() {
        long day = PlayCounts.AUTO_SYNC_INTERVAL_MS;
        long now = 10 * day;
        assertTrue(PlayCounts.dueForAutoSync(0, now));                // jamais lancé
        assertFalse(PlayCounts.dueForAutoSync(now - 1000, now));      // il y a une seconde
        assertFalse(PlayCounts.dueForAutoSync(now - day + 1, now));   // presque 24 h
        assertTrue(PlayCounts.dueForAutoSync(now - day, now));        // 24 h pile
    }
}
