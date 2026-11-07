package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.opentagger.model.TagInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * « Annuler un enregistrement » (SaveJournal + TagSnapshot) sur de vrais fichiers MP3, FLAC, M4A et Ogg fabriqués par
 * ffmpeg : après écriture des tags ET renommage, l'annulation doit redonner le fichier d'origine octet pour octet, à son
 * ancienne place. Nécessite ffmpeg (test ignoré sinon).
 */
public class SaveJournalTest {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("annulation-test");
        SaveJournal.useRootForTests(dir.resolve("journal"));
        SaveJournal.resetForTests();
    }

    @After
    public void tearDown() {
        SaveJournal.resetForTests();
        SaveJournal.useRootForTests(null);
    }

    private Path make(String name, String... codec) throws Exception {
        Path f = dir.resolve("avant").resolve(name);
        Files.createDirectories(f.getParent());
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=3", "-metadata", "title=Ancien titre", "-metadata", "artist=Ancien artiste",
                "-metadata", "album=Ancien album"));
        cmd.addAll(List.of(codec));
        cmd.add(f.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg ne sait pas faire " + name, p.waitFor() == 0 && Files.isRegularFile(f) && Files.size(f) > 0);
        return f;
    }

    private static TagInfo nouveaux() {
        TagInfo t = new TagInfo();
        t.artist = "Nouvel artiste";
        t.title = "Nouveau titre";
        t.album = "Nouvel album";
        t.albumArtist = "Nouvel artiste";
        t.track = "3";
        t.year = "2020";
        return t;
    }

    /** Écrit les tags puis déplace le fichier, comme « Enregistrer tout » ; renvoie le nouveau chemin. */
    private Path enregistrer(Path f, String label) throws Exception {
        SaveJournal.Batch b = SaveJournal.begin(label);
        assertNotNull(b);
        try {
            new TagWriter().write(f.toFile(), nouveaux());
            Path dst = dir.resolve("apres").resolve("Nouvel artiste").resolve("03 - Nouveau titre"
                    + f.getFileName().toString().substring(f.getFileName().toString().lastIndexOf('.')));
            Files.createDirectories(dst.getParent());
            FileRenamer.moveFile(f, dst);
            return dst;
        } finally {
            SaveJournal.end(b);
        }
    }

    private void allerRetour(Path f, SaveJournal.Kind attendu) throws Exception {
        byte[] avant = Files.readAllBytes(f);
        long date = Files.getLastModifiedTime(f).toMillis();
        Path dst = enregistrer(f, "test " + f.getFileName());
        assertFalse(Files.exists(f));
        assertTrue(Files.exists(dst));
        assertTrue("les tags ont bien été réécrits", Files.mismatch(dst, writeTemp(avant)) != -1L);
        assertEquals("Nouveau titre", AudioFileIO.read(dst.toFile()).getTag().getFirst(FieldKey.TITLE));

        SaveJournal.BatchInfo lot = SaveJournal.list().get(0);
        assertEquals(1, lot.files());
        assertEquals(1, lot.moved());
        SaveJournal.Entry e = SaveJournal.entries(lot.id()).get(0);
        assertEquals(attendu, e.kind());
        assertEquals("", e.problem());

        List<SaveJournal.UndoResult> r = SaveJournal.undo(lot.id(), null, false, null);
        assertEquals(1, r.size());
        assertEquals(r.get(0).message(), SaveJournal.UndoState.RESTORED, r.get(0).state());
        assertTrue("remis à son ancienne place", Files.exists(f));
        assertFalse(Files.exists(dst));
        assertEquals("octet pour octet", -1L, Files.mismatch(f, writeTemp(avant)));
        assertEquals(date, Files.getLastModifiedTime(f).toMillis());
        assertEquals(1, SaveJournal.list().get(0).undone());
    }

    private Path writeTemp(byte[] data) throws Exception {
        Path t = Files.createTempFile(dir, "ref", ".bin");
        Files.write(t, data);
        return t;
    }

    @Test public void mp3() throws Exception { allerRetour(make("a.mp3", "-c:a", "libmp3lame", "-id3v2_version", "3", "-write_id3v1", "1"), SaveJournal.Kind.RAW); }

    @Test public void flac() throws Exception { allerRetour(make("a.flac", "-c:a", "flac"), SaveJournal.Kind.RAW); }

    @Test public void m4a() throws Exception { allerRetour(make("a.m4a", "-c:a", "aac"), SaveJournal.Kind.RAW); }

    @Test public void oggCopieEntiere() throws Exception { allerRetour(make("a.ogg", "-c:a", "libvorbis"), SaveJournal.Kind.COPY); }

    @Test
    public void mp3AvecPochetteEtGrosTag() throws Exception {
        Path f = make("p.mp3", "-c:a", "libmp3lame");
        AudioFile af = AudioFileIO.read(f.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.LYRICS, "la ".repeat(20_000));
        tag.setField(FieldKey.COMMENT, "rip perso");
        af.commit();
        allerRetour(f, SaveJournal.Kind.RAW);
    }

    @Test
    public void modifieDepuisNestPasEcraseSansForcer() throws Exception {
        Path f = make("m.mp3", "-c:a", "libmp3lame");
        byte[] avant = Files.readAllBytes(f);
        Path dst = enregistrer(f, "lot A");
        // Un autre programme change les tags ensuite (hors journal).
        AudioFile af = AudioFileIO.read(dst.toFile());
        af.getTag().setField(FieldKey.TITLE, "Changé par SongKong");
        af.commit();

        String id = SaveJournal.list().get(0).id();
        List<SaveJournal.UndoResult> r = SaveJournal.undo(id, null, false, null);
        assertEquals(SaveJournal.UndoState.MODIFIED, r.get(0).state());
        assertTrue("laissé tel quel", Files.exists(dst));

        r = SaveJournal.undo(id, null, true, null);
        assertEquals(r.get(0).message(), SaveJournal.UndoState.RESTORED, r.get(0).state());
        assertEquals(-1L, Files.mismatch(f, writeTemp(avant)));
    }

    @Test
    public void sonChangeRienNestRemis() throws Exception {
        Path f = make("s.flac", "-c:a", "flac");
        Path dst = enregistrer(f, "lot B");
        // Le son est remplacé (autre fréquence) : rien ne doit être reconstruit.
        Path autre = make("autre.flac", "-c:a", "flac", "-af", "volume=0.5");
        Files.copy(autre, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String id = SaveJournal.list().get(0).id();
        List<SaveJournal.UndoResult> r = SaveJournal.undo(id, null, true, null);
        assertEquals(SaveJournal.UndoState.FAILED, r.get(0).state());
        assertTrue(Files.exists(dst));
    }

    @Test
    public void ancienEmplacementOccupe() throws Exception {
        Path f = make("o.mp3", "-c:a", "libmp3lame");
        Path dst = enregistrer(f, "lot C");
        Files.writeString(f, "quelqu'un d'autre");
        String id = SaveJournal.list().get(0).id();
        List<SaveJournal.UndoResult> r = SaveJournal.undo(id, null, false, null);
        assertEquals(SaveJournal.UndoState.FAILED, r.get(0).state());
        assertTrue(r.get(0).message().contains("occupe"));
        assertEquals("quelqu'un d'autre", Files.readString(f));
        assertTrue(Files.exists(dst));
    }

    @Test
    public void annexeRemplaceeEstRemise() throws Exception {
        Path cover = dir.resolve("cover.jpg");
        Files.writeString(cover, "ancienne pochette");
        SaveJournal.Batch b = SaveJournal.begin("pochette");
        SaveJournal.beforeSidecar(cover);
        Files.writeString(cover, "nouvelle pochette");
        SaveJournal.afterWrite(cover.toFile());
        SaveJournal.end(b);
        List<SaveJournal.UndoResult> r = SaveJournal.undo(b.id, null, false, null);
        assertEquals(SaveJournal.UndoState.RESTORED, r.get(0).state());
        assertEquals("ancienne pochette", Files.readString(cover));
    }

    @Test
    public void deuxEcrituresDuMemeFichierUnSeulAvant() throws Exception {
        Path f = make("d.mp3", "-c:a", "libmp3lame");
        byte[] avant = Files.readAllBytes(f);
        SaveJournal.Batch b = SaveJournal.begin("double");
        new TagWriter().write(f.toFile(), nouveaux());
        TagInfo t = nouveaux();
        t.title = "Encore autre";
        new TagWriter().write(f.toFile(), t);
        SaveJournal.end(b);
        assertEquals(1, SaveJournal.entries(b.id).size());
        List<SaveJournal.UndoResult> r = SaveJournal.undo(b.id, null, false, null);
        assertEquals(SaveJournal.UndoState.RESTORED, r.get(0).state());
        assertEquals(-1L, Files.mismatch(f, writeTemp(avant)));
    }

    @Test
    public void ecritureHorsLotVaDansAutresEcritures() throws Exception {
        Path f = make("h.flac", "-c:a", "flac");
        new TagWriter().write(f.toFile(), nouveaux());
        SaveJournal.BatchInfo lot = SaveJournal.list().get(0);
        assertEquals(1, lot.files());
        assertTrue(lot.open());
    }

    @Test
    public void decoupageMp3AvecId3v1() throws Exception {
        Path f = make("l.mp3", "-c:a", "libmp3lame", "-write_id3v1", "1");
        TagSnapshot.Layout l = TagSnapshot.layout(f);
        assertNotNull(l);
        assertTrue(l.audioStart() > 10);
        assertEquals(Files.size(f) - 128, l.audioEnd());
        byte[] all = Files.readAllBytes(f);
        assertEquals((byte) 0xFF, all[(int) l.audioStart()]);
    }

    @Test
    public void oublierEffaceLeJournal() throws Exception {
        Path f = make("x.flac", "-c:a", "flac");
        enregistrer(f, "à oublier");
        String id = SaveJournal.list().get(0).id();
        assertTrue(SaveJournal.forget(id));
        assertTrue(SaveJournal.list().isEmpty());
    }
}
