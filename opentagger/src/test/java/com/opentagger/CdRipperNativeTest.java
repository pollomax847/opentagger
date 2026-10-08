package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class CdRipperNativeTest {

    @Test
    public void tocKeepsOnlyAudioTracks() {
        var r = CdRipper.parseNative(List.of("T\t1\t17296\t1", "T\t2\t15000\t1", "T\t3\t40000\t0", "DONE"), null);
        assertEquals(2, r.tracks().size());
        assertEquals(17296, r.tracks().get(0).lengthSectors());
        assertEquals(230, r.tracks().get(0).durationSec());
        assertTrue(r.done());
        assertEquals("", r.errorCode());
    }

    @Test
    public void errorLineIsReportedAndNoiseIgnored() {
        var r = CdRipper.parseNative(List.of("avertissement quelconque", "ERR\tNODISC\taucun disque"), null);
        assertEquals("NODISC", r.errorCode());
        assertFalse(r.done());
        assertTrue(r.tracks().isEmpty());
    }

    @Test
    public void ripReportsProgressAndReadErrors() {
        List<Integer> pct = new ArrayList<>();
        var r = CdRipper.parseNative(List.of("P\t50\t200", "P\t200\t200", "RIPPED\t3", "DONE"), pct::add);
        assertEquals(List.of(25, 100), pct);
        assertEquals(3, r.readErrors());
        assertTrue(r.done());
    }

    @Test
    public void cdparanoiaFallbackFormatStillParses() {
        var toc = CdRipper.parseToc("  1.    17296 [03:50.46]        0 [00:00.00]    no   no  2\n  2.    100 [00:01.25]  17296 [03:50.46] no no 2\n");
        assertEquals(2, toc.tracks().size());
    }
}
