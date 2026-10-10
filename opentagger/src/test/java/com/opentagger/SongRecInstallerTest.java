package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class SongRecInstallerTest {

    private static Path zipWith(String entryName) throws IOException {
        Path zip = Files.createTempFile("ot-songrec-test", ".zip");
        try (OutputStream o = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry(entryName));
            z.write(new byte[] { 1, 2, 3 });
            z.closeEntry();
        }
        return zip;
    }

    @Test
    public void extractsNormalEntriesUnderDestination() throws Exception {
        Path zip = zipWith("bin/songrec.exe");
        Path dest = Files.createTempDirectory("ot-songrec-dest");
        SongRecInstaller.extract(zip, dest);
        assertTrue(Files.isRegularFile(dest.resolve("bin").resolve("songrec.exe")));
    }

    @Test
    public void refusesEntriesThatEscapeTheDestination() throws Exception {
        Path zip = zipWith("../evil.txt");
        Path dest = Files.createTempDirectory("ot-songrec-dest");
        try {
            SongRecInstaller.extract(zip, dest);
            fail("une entrée « ../ » doit être refusée");
        } catch (IOException expected) {
            assertTrue(Files.notExists(dest.resolveSibling("evil.txt")));
        }
    }

    @Test
    public void sha256IsUppercaseHex() throws Exception {
        Path f = Files.createTempFile("ot-songrec-hash", ".bin");
        Files.write(f, "abc".getBytes());
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", SongRecInstaller.sha256(f));
    }
}
