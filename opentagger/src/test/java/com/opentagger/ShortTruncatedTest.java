package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.FileEntry;

import org.junit.Test;

/** Décision « fichier court et manifestement tronqué » (essai de mise à la corbeille, 2026-09-25). */
public class ShortTruncatedTest {

    @Test public void userExample_0m40_vs_3m34_isTruncated() {
        assertTrue(FileEntry.isShortTruncated(40, 214, 60, 60));
    }

    @Test public void notShortEnough_orNotFarEnoughFromMusicBrainz_isKept() {
        assertFalse("70 s : au-dessus du plafond d'1 min", FileEntry.isShortTruncated(70, 400, 60, 60));
        assertFalse("écart de 30 s seulement (radio edit, intro coupée…)", FileEntry.isShortTruncated(50, 80, 60, 60));
        assertTrue("limite : 55 s vs 118 s (écart 63 s, plus du double)", FileEntry.isShortTruncated(55, 118, 60, 60));
        assertFalse("écart suffisant (51 s) mais pas le double (110 < 2×59)", FileEntry.isShortTruncated(59, 110, 60, 50));
    }

    @Test public void unknownDurations_areNeverTruncated() {
        assertFalse(FileEntry.isShortTruncated(0, 214, 60, 60));      // durée du fichier pas encore lue par le scan
        assertFalse(FileEntry.isShortTruncated(40, 0, 60, 60));       // MusicBrainz sans durée : rien à comparer
        assertFalse(FileEntry.isShortTruncated(-1, -1, 60, 60));
    }

    @Test public void longerThanReference_isNeverTruncated() {
        assertFalse(FileEntry.isShortTruncated(40, 40, 60, 60));
        assertFalse(FileEntry.isShortTruncated(50, 30, 60, 60));
    }
}
