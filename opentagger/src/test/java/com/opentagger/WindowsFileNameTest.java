package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WindowsFileNameTest {

    @Test
    public void reservedDeviceNamesGetAPrefix() {
        assertEquals("_CON", FileRenamer.windowsSafeSegmentForce("CON"));
        assertEquals("_nul", FileRenamer.windowsSafeSegmentForce("nul"));
        assertEquals("_Com1", FileRenamer.windowsSafeSegmentForce("Com1"));
        assertEquals("_aux.txt", FileRenamer.windowsSafeSegmentForce("aux.txt"));
        assertEquals("Conan", FileRenamer.windowsSafeSegmentForce("Conan"));       // pas réservé
        assertEquals("05 - Con", FileRenamer.windowsSafeSegmentForce("05 - Con")); // seul le nom ENTIER est réservé
    }

    @Test
    public void trailingDotsSpacesAndControlCharsAreRemoved() {
        assertEquals("Album", FileRenamer.windowsSafeSegmentForce("Album. "));
        assertEquals("Dr", FileRenamer.windowsSafeSegmentForce("Dr."));
        assertEquals("AB", FileRenamer.windowsSafeSegmentForce("A\u0001B"));
        assertEquals("St. Vincent", FileRenamer.windowsSafeSegmentForce("St. Vincent"));
    }

    @Test
    public void longPathsAreShortenedOnWindowsOnly() {
        String root = "G:\\Musiques";
        String name = "x".repeat(300);
        String chemin = "Artist/Album/" + name;
        String cut = FileRenamer.capPathLength(root, chemin, ".mp3", true);
        assertTrue((root + "\\" + cut + " (99).mp3").length() <= 260);
        assertTrue(cut.startsWith("Artist/Album/"));
        assertEquals(chemin, FileRenamer.capPathLength(root, chemin, ".mp3", false));
        assertEquals("Artist/Album/Titre", FileRenamer.capPathLength(root, "Artist/Album/Titre", ".mp3", true));
    }

    @Test
    public void postTagShellUsesCmdOnWindows() {
        var cmd = PostTagCommands.shell("echo hi").command();
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(win ? "cmd.exe" : "sh", cmd.get(0));
        assertEquals(win ? "/c" : "-c", cmd.get(1));
    }
}
