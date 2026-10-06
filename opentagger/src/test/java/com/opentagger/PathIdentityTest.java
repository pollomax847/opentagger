package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class PathIdentityTest {

    private static Path abs(String s) { return Paths.get(System.getProperty("java.io.tmpdir"), s); }

    @Test
    public void caseInsensitiveSystemsSeeOneFile() {
        assertEquals(PathIdentity.key(abs("Music/Billie_Eilish/a.mp3"), true),
                     PathIdentity.key(abs("Music/Billie_eilish/a.mp3"), true));
    }

    @Test
    public void caseSensitiveSystemsKeepTwoFiles() {
        assertNotEquals(PathIdentity.key(abs("Music/a.mp3"), false), PathIdentity.key(abs("Music/A.mp3"), false));
    }

    @Test
    public void dotSegmentsAreNormalisedEitherWay() {
        assertEquals(PathIdentity.key(abs("Music/x/../a.mp3"), false), PathIdentity.key(abs("Music/a.mp3"), false));
    }

    @Test
    public void differentFilesStayDifferentEvenIgnoringCase() {
        assertNotEquals(PathIdentity.key(abs("Music/a.mp3"), true), PathIdentity.key(abs("Music/b.mp3"), true));
    }

    @Test
    public void theRunningSystemIsClassifiedConsistently() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (win) assertEquals(true, PathIdentity.caseInsensitiveFileSystem());
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) assertEquals(false, PathIdentity.caseInsensitiveFileSystem());
    }
}
