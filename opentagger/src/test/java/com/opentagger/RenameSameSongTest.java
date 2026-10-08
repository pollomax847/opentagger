package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.opentagger.model.TagInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RenameSameSongTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static TagInfo info() {
        TagInfo t = new TagInfo();
        t.artist = "Avicii feat. Aloe Blacc"; t.title = "SOS"; t.album = "NRJ Summer Hits Only 2019";
        t.albumArtist = "Various Artists"; t.track = "1"; t.trackTotal = "20"; t.discNo = "3"; t.year = "2019";
        return t;
    }

    private static int usableMask(FileRenamer r, TagInfo t) {
        for (int i = 0; i < r.maskCount(); i++) if (!r.preview(t, i, ".mp3").isBlank()) return i;
        throw new IllegalStateException("aucun masque utilisable");
    }

    // ── Comparaison d'audio (empreintes Chromaprint brutes simulées) ─────────────────────────────

    private static int[] randomFingerprint(long seed, int n) {
        Random rnd = new Random(seed);
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = rnd.nextInt();
        return a;
    }

    /** Copie de {@code a} où chaque bit est inversé avec la probabilité {@code p} (un autre encodage du même audio). */
    private static int[] noisy(int[] a, double p, long seed) {
        Random rnd = new Random(seed);
        int[] b = a.clone();
        for (int i = 0; i < b.length; i++)
            for (int bit = 0; bit < 32; bit++) if (rnd.nextDouble() < p) b[i] ^= (1 << bit);
        return b;
    }

    @Test
    public void aReEncodedCopyOfTheSameAudioIsRecognised() {
        int[] a = randomFingerprint(1, 900);
        int[] b = noisy(a, 0.10, 2);
        assertTrue(AudioSimilarity.bestBitErrorRate(a, b) < 0.15);
        assertTrue(AudioSimilarity.sameAudio(new Fingerprinter.Raw(a, 215.0), new Fingerprinter.Raw(b, 215.4)));
    }

    @Test
    public void aSlightlyShiftedStartIsStillTheSameAudio() {
        int[] a = randomFingerprint(3, 900);
        int[] shifted = new int[880];                       // 20 entiers (≈ 2,5 s) de silence en moins au début
        System.arraycopy(noisy(a, 0.08, 4), 20, shifted, 0, 880);
        assertTrue(AudioSimilarity.sameAudio(new Fingerprinter.Raw(a, 215.0), new Fingerprinter.Raw(shifted, 212.6)));
    }

    @Test
    public void twoDifferentSongsAreNeverTheSameAudio() {
        int[] a = randomFingerprint(5, 900), b = randomFingerprint(6, 900);
        assertTrue("deux chansons différentes : environ la moitié des bits diffèrent", AudioSimilarity.bestBitErrorRate(a, b) > 0.40);
        assertFalse(AudioSimilarity.sameAudio(new Fingerprinter.Raw(a, 215.0), new Fingerprinter.Raw(b, 215.0)));
    }

    @Test
    public void aDifferentLengthIsNotTheSameFileEvenIfTheStartMatches() {
        int[] a = randomFingerprint(7, 900);
        assertFalse("version radio (3:30) contre album (5:00)",
                AudioSimilarity.sameAudio(new Fingerprinter.Raw(a, 210.0), new Fingerprinter.Raw(a.clone(), 300.0)));
    }

    @Test
    public void tooShortAnOverlapDoesNotConclude() {
        int[] a = randomFingerprint(8, 100), b = a.clone();
        assertEquals(1.0, AudioSimilarity.bitErrorRate(a, b, 0), 0.0);
        assertFalse(AudioSimilarity.sameAudio(new Fingerprinter.Raw(a, 12.0), new Fingerprinter.Raw(b, 12.0)));
    }

    @Test
    public void unreadableFilesGiveNoVerdictAtAll() throws Exception {
        Path garbage = Files.write(tmp.newFile("x.mp3").toPath(), new byte[]{1, 2, 3});
        Path other = Files.write(tmp.newFile("y.mp3").toPath(), new byte[]{4, 5, 6});
        assertNull("analyse impossible : ni « oui » ni « non »", AudioSimilarity.sameAudio(garbage.toFile(), other.toFile()));
    }

    // ── Renommage ─────────────────────────────────────────────────────────────────────────────────

    @Test
    public void anIdenticalCopyIsNotNumberedAndStaysWhereItIs() throws Exception {
        Path root = tmp.newFolder("lib").toPath();
        Path dossierA = Files.createDirectories(tmp.getRoot().toPath().resolve("srcA"));
        Path dossierB = Files.createDirectories(tmp.getRoot().toPath().resolve("srcB"));
        byte[] same = new byte[4096];
        java.util.Arrays.fill(same, (byte) 7);
        Path a = Files.write(dossierA.resolve("sos.mp3"), same);
        Path b = Files.write(dossierB.resolve("sos copie.mp3"), same);

        FileRenamer r = new FileRenamer();
        TagInfo t = info();
        int mask = usableMask(r, t);
        Path first = r.rename(a, t, mask, root);
        assertNotNull(first);
        assertTrue(Files.exists(first));

        try {
            r.rename(b, t, mask, root);
            fail("une copie identique ne doit pas être renommée en « (2) »");
        } catch (DuplicateFileException e) {
            assertTrue(e.identical());
            assertEquals(first, e.existing());
        }
        assertTrue("le fichier reste là où il est", Files.exists(b));
        try (var files = Files.walk(root)) {
            assertEquals("aucun « (2) » créé", 1, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    public void differentFilesThatCannotBeAnalysedKeepTheOldNumbering() throws Exception {
        Path root = tmp.newFolder("lib2").toPath();
        Path a = Files.write(tmp.newFile("a.mp3").toPath(), new byte[]{1, 2, 3, 4});
        Path b = Files.write(tmp.newFile("b.mp3").toPath(), new byte[]{9, 9, 9, 9, 9});

        FileRenamer r = new FileRenamer();
        TagInfo t = info();
        int mask = usableMask(r, t);
        Path first = r.rename(a, t, mask, root);
        Path second = r.rename(b, t, mask, root);   // octets différents, audio non analysable : aucune preuve d'un doublon
        assertNotNull(second);
        assertTrue(second.getFileName().toString().contains("(2)"));
        assertTrue(Files.exists(first) && Files.exists(second));
    }
}
