package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class PathIdentityCollectionsTest {

    @Test public void caseInsensitiveSetFindsTheSameFileWrittenDifferently() {
        Set<String> s = PathIdentity.newPathSet(true);
        s.add("E:\\itunes\\Music\\Billie_Eilish\\a.mp3");
        assertTrue(s.contains("e:\\ITUNES\\music\\billie_eilish\\A.MP3"));
        assertFalse(s.contains("E:\\itunes\\Music\\Billie_Eilish\\b.mp3"));
    }

    @Test public void caseSensitiveSetKeepsLinuxSemantics() {
        Set<String> s = PathIdentity.newPathSet(false);
        s.add("/music/a.mp3");
        assertFalse(s.contains("/music/A.mp3"));
        assertTrue(s.contains("/music/a.mp3"));
    }

    @Test public void caseInsensitiveMapResolvesEitherSpelling() {
        Map<String, Integer> m = PathIdentity.newPathMap(true);
        m.put("E:\\Music\\X.mp3", 7);
        assertEquals(Integer.valueOf(7), m.get("e:\\music\\x.mp3"));
        assertEquals(1, m.size());
        m.put("e:\\MUSIC\\x.MP3", 8); // même fichier : remplacé, pas dupliqué
        assertEquals(1, m.size());
    }

    @Test public void caseSensitiveMapKeepsBothSpellings() {
        Map<String, Integer> m = PathIdentity.newPathMap(false);
        m.put("/m/x.mp3", 1); m.put("/m/X.mp3", 2);
        assertEquals(2, m.size());
    }
}
