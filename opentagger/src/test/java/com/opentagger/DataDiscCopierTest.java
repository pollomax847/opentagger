package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DataDiscCopierTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path disc() throws Exception {
        Path d = tmp.newFolder("disc").toPath();
        Files.createDirectories(d.resolve("Photos 2006"));
        Files.writeString(d.resolve("Photos 2006").resolve("a.jpg"), "photo A");
        Files.writeString(d.resolve("lisez-moi.txt"), "souvenirs");
        return d;
    }

    @Test public void copiesEverythingKeepingTheFolders() throws Exception {
        Path dest = tmp.newFolder("dest").toPath();
        DataDiscCopier.Result r = DataDiscCopier.copy(disc(), dest);
        assertEquals(2, r.copied());
        assertTrue(Files.exists(dest.resolve("Photos 2006").resolve("a.jpg")));
        assertEquals("souvenirs", Files.readString(dest.resolve("lisez-moi.txt")));
    }

    @Test public void neverOverwritesADifferentFileOfTheSameName() throws Exception {
        Path dest = tmp.newFolder("dest2").toPath();
        Files.writeString(dest.resolve("lisez-moi.txt"), "MON FICHIER A MOI");
        DataDiscCopier.Result r = DataDiscCopier.copy(disc(), dest);
        assertEquals("le fichier existant reste intact", "MON FICHIER A MOI", Files.readString(dest.resolve("lisez-moi.txt")));
        assertEquals("souvenirs", Files.readString(dest.resolve("lisez-moi (2).txt")));
        assertEquals(1, r.renamed());
        assertEquals(1, r.copied());
    }

    @Test public void anIdenticalFileAlreadyThereIsSkipped() throws Exception {
        Path dest = tmp.newFolder("dest3").toPath();
        Files.writeString(dest.resolve("lisez-moi.txt"), "souvenirs");
        DataDiscCopier.Result r = DataDiscCopier.copy(disc(), dest);
        assertEquals(1, r.identical());
        assertTrue(!Files.exists(dest.resolve("lisez-moi (2).txt")));
    }

    @Test public void copyingTwiceChangesNothing() throws Exception {
        Path dest = tmp.newFolder("dest4").toPath();
        Path d = disc();
        DataDiscCopier.copy(d, dest);
        DataDiscCopier.Result second = DataDiscCopier.copy(d, dest);
        assertEquals(2, second.identical());
        assertEquals(0, second.copied() + second.renamed());
    }

    @Test public void measureCountsFilesAndBytes() throws Exception {
        long[] m = DataDiscCopier.measure(disc());
        assertEquals(2, m[0]);
        assertEquals("photo A".length() + "souvenirs".length(), m[1]);
    }
}
