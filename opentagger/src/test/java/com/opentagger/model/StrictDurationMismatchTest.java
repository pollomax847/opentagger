package com.opentagger.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StrictDurationMismatchTest {

    /** Cas réel : "Piggy Bank" (50 Cent) ~4:06 sur MusicBrainz, fichier de 5:51 qui n'était pas ce
     *  morceau — +43 % passe le seuil tolérant (50 %) mais pas le strict. */
    @Test
    public void longerFileBeyondTwentyPercentIsRejected() {
        assertFalse(FileEntry.isDurationMismatch(351, 246));
        assertTrue(FileEntry.isStrictDurationMismatch(351, 246));
    }

    @Test
    public void shorterFileBeyondTwentyPercentIsRejected() {
        assertTrue(FileEntry.isStrictDurationMismatch(180, 266));
    }

    @Test
    public void smallDifferencesAreAccepted() {
        assertFalse(FileEntry.isStrictDurationMismatch(250, 246));
        assertFalse(FileEntry.isStrictDurationMismatch(262, 246)); // +16 s, +6 %
        assertFalse(FileEntry.isStrictDurationMismatch(300, 285)); // +15 s
    }

    @Test
    public void unknownDurationIsNeverAMismatch() {
        assertFalse(FileEntry.isStrictDurationMismatch(0, 246));
        assertFalse(FileEntry.isStrictDurationMismatch(351, 0));
    }
}
