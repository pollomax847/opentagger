package com.opentagger.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SimplifyTitleTest {

    @Test
    public void removesGuestsInParenthesesOrBrackets() {
        assertEquals("Havana", TaggingWorker.simplifyTitle("Havana (Feat. Young Thug)"));
        assertEquals("Havana", TaggingWorker.simplifyTitle("Havana (feat. Young Thug)"));
        assertEquals("Bright Side", TaggingWorker.simplifyTitle("Bright Side (feat. Julia Church)"));
        assertEquals("Wasted", TaggingWorker.simplifyTitle("Wasted [ft. Gavin James]"));
        assertEquals("Excusez-moi", TaggingWorker.simplifyTitle("Excusez-moi (feat. Barbara Carlotti, Faada Freddy, Jeanne Cherhal, Bruno Podalydès)"));
    }

    @Test
    public void removesATrailingFeaturingAndCopyMarkers() {
        assertEquals("Song", TaggingWorker.simplifyTitle("Song feat. Someone"));
        assertEquals("Bright Side", TaggingWorker.simplifyTitle("Bright Side (1)"));
        assertEquals("Bright Side", TaggingWorker.simplifyTitle("Bright Side (feat. Julia Church) (1)"));
    }

    @Test
    public void keepsParenthesesThatDistinguishRecordings() {
        assertEquals("Self Sacrifice (Luka Kiesa Remix)", TaggingWorker.simplifyTitle("Self Sacrifice (Luka Kiesa Remix)"));
        assertEquals("Mon apache (Live)", TaggingWorker.simplifyTitle("Mon apache (Live)"));
        assertEquals("Stand By Me", TaggingWorker.simplifyTitle("Stand By Me"));
    }

    @Test
    public void neverReturnsAnEmptyTitle() {
        assertEquals("(feat. X)", TaggingWorker.simplifyTitle("(feat. X)"));
        assertEquals("", TaggingWorker.simplifyTitle(null));
    }
}
