package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MoveToFolderNoDuplicateTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void identicalFileAlreadyAtDestinationIsNotMovedNorDuplicated() throws Exception {
        Path src = tmp.newFolder("src").toPath().resolve("a.mp3");
        Path dstDir = tmp.newFolder("dst").toPath();
        Files.write(src, new byte[]{1, 2, 3, 4});
        Files.write(dstDir.resolve("a.mp3"), new byte[]{1, 2, 3, 4});
        assertNull(FileRenamer.moveToFolder(src, dstDir));
        assertTrue("l'original reste en place", Files.exists(src));
        assertEquals("aucun « (2) » créé", 1, Files.list(dstDir).count());
    }

    @Test
    public void differentFileWithSameNameStillGetsASuffix() throws Exception {
        Path src = tmp.newFolder("src").toPath().resolve("a.mp3");
        Path dstDir = tmp.newFolder("dst").toPath();
        Files.write(src, new byte[]{1, 2, 3, 4});
        Files.write(dstDir.resolve("a.mp3"), new byte[]{9, 9});
        Path moved = FileRenamer.moveToFolder(src, dstDir);
        assertNotNull(moved);
        assertEquals("a (2).mp3", moved.getFileName().toString());
        assertTrue(Files.notExists(src));
    }
}
