package com.opentagger.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** « Durée incohérente » remplacé par un verdict (2026-10-13) — cas réels du journal de l'utilisateur. */
public class DurationGapTest {

    @Test public void longRecordingsAreMixesNotSongs() {
        assertEquals(TaggingWorker.DurationGap.LONG_RECORDING, TaggingWorker.durationGap(47 * 60 + 22, 3 * 60 + 58));
        assertEquals(TaggingWorker.DurationGap.LONG_RECORDING, TaggingWorker.durationGap(18 * 60 + 29, 6 * 60 + 7));
    }

    @Test public void muchShorterFilesAreCut() {
        assertEquals(TaggingWorker.DurationGap.CUT, TaggingWorker.durationGap(2 * 60 + 2, 5 * 60 + 32));   // Graf Zahl
        assertEquals(TaggingWorker.DurationGap.CUT, TaggingWorker.durationGap(80, 3 * 60 + 24));            // Fais le vide
    }

    @Test public void otherwiseItIsAnotherVersion() {
        assertEquals(TaggingWorker.DurationGap.OTHER_VERSION, TaggingWorker.durationGap(3 * 60 + 47, 7 * 60 + 47)); // remix édité
        assertEquals(TaggingWorker.DurationGap.OTHER_VERSION, TaggingWorker.durationGap(7 * 60 + 44, 4 * 60 + 18)); // version longue
        assertEquals(TaggingWorker.DurationGap.OTHER_VERSION, TaggingWorker.durationGap(2 * 60 + 37, 3 * 60 + 38)); // radio edit
    }
}
