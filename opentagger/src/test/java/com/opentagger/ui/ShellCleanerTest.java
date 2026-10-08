package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Files;

import org.junit.Test;

/** Coquille vide vs vrai son court (2026-09-26). */
public class ShellCleanerTest {

    @Test public void blankId3Header_isShell_butShortRealSound_isNot() throws Exception {
        assumeTrue(new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0);
        File dir = Files.createTempDirectory("otshell").toFile();
        // Reproduit les « __….mp3 » réels : « ID3 » v2.4, puis des zéros, 1 348 octets au total.
        byte[] stub = new byte[1348];
        stub[0] = 'I'; stub[1] = 'D'; stub[2] = '3'; stub[3] = 4; stub[9] = 8;
        File shell = new File(dir, "__14 Chanter.mp3");
        Files.write(shell.toPath(), stub);
        assertTrue(ShellCleaner.isShell(shell, shell.length()));

        File clip = new File(dir, "mot.mp3"); // 1 s de vrai son, comme une méthode de langue
        new ProcessBuilder("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
                "-b:a", "64k", clip.getAbsolutePath()).inheritIO().start().waitFor();
        assumeTrue(clip.length() > 0 && clip.length() < ShellCleaner.MAX_SHELL_BYTES);
        assertFalse(ShellCleaner.isShell(clip, clip.length()));

        assertTrue("0 octet", ShellCleaner.isShell(clip, 0));
        assertFalse("trop gros pour être examiné", ShellCleaner.isShell(shell, 200_000));
    }
}
