package com.opentagger;

import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.Test;

public class ByteFormatTest {

    private static String fr(long b) { return ByteFormat.format(b, false, Locale.FRANCE); }
    private static String en(long b) { return ByteFormat.format(b, true, Locale.US); }

    @Test
    public void smallValuesStayInBytes() {
        assertEquals("0 o", fr(0));
        assertEquals("512 o", fr(512));
        assertEquals("0 B", en(-5));
    }

    @Test
    public void scalesInBase1024() {
        assertEquals("1,0 Ko", fr(1024));
        assertEquals("1,0 Mo", fr(1024L * 1024));
        assertEquals("1,5 Go", fr((long) (1.5 * 1024 * 1024 * 1024)));
        assertEquals("1.0 TB", en(1024L * 1024 * 1024 * 1024));
    }

    @Test
    public void noDecimalFromOneHundredUnits() {
        long g = 1024L * 1024 * 1024;
        assertEquals("99,5 Go", fr((long) (99.5 * g)));
        assertEquals("312 Go", fr(312 * g));
        assertEquals("312 GB", en(312 * g));
    }

    @Test
    public void multiTerabyteLibrary() {
        long t = 1024L * 1024 * 1024 * 1024;
        assertEquals("2,5 To", fr((long) (2.5 * t)));
        // au-delà du To, on reste en To (pas d'unité supérieure)
        assertEquals("2048 To", fr(2048 * t));
    }
}
