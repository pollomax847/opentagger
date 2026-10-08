package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.Test;

public class FolderWatcherStableTest {

    @Test(timeout = 20000) public void aFileThatStopsGrowingIsStable() throws Exception {
        Path f = Files.createTempFile("otstable", ".mp3");
        try {
            Files.write(f, new byte[]{1, 2, 3});
            assertTrue(FolderWatcher.waitUntilStable(f, 300, 10_000));
        } finally { Files.deleteIfExists(f); }
    }

    @Test(timeout = 20000) public void aFileStillBeingWrittenIsNotStableUntilItStops() throws Exception {
        Path f = Files.createTempFile("otgrow", ".mp3");
        try {
            Files.write(f, new byte[]{1});
            Thread writer = new Thread(() -> {
                try {
                    for (int i = 0; i < 12; i++) { Files.write(f, new byte[]{1}, StandardOpenOption.APPEND); Thread.sleep(100); }
                } catch (Exception ignored) {}
            });
            long t0 = System.currentTimeMillis();
            writer.start();
            assertTrue(FolderWatcher.waitUntilStable(f, 500, 10_000));
            assertTrue("doit avoir attendu la fin de l'écriture (~1,2 s)", System.currentTimeMillis() - t0 >= 1100);
            writer.join();
        } finally { Files.deleteIfExists(f); }
    }

    @Test(timeout = 20000) public void aMissingFileIsNotStable() throws Exception {
        assertFalse(FolderWatcher.waitUntilStable(Files.createTempDirectory("otmiss").resolve("nope.mp3"), 200, 2000));
    }

    @Test(timeout = 20000) public void aFileThatNeverSettlesTimesOut() throws Exception {
        Path f = Files.createTempFile("otnever", ".mp3");
        Thread writer = new Thread(() -> {
            try { for (int i = 0; i < 40; i++) { Files.write(f, new byte[]{1}, StandardOpenOption.APPEND); Thread.sleep(50); } }
            catch (Exception ignored) {}
        });
        try {
            Files.write(f, new byte[]{1});
            writer.start();
            assertFalse(FolderWatcher.waitUntilStable(f, 1500, 1200));
            writer.join();
        } finally { Files.deleteIfExists(f); }
    }
}
