package com.opentagger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class ITunesReportTest {

    @Test
    public void normalizeIgnoresCaseAndDotSegments() {
        assertEquals(ITunesReport.normalize("music/Artist/../Artist/Song.MP3"),
                     ITunesReport.normalize("MUSIC/artist/song.mp3"));
        assertEquals("", ITunesReport.normalize("   "));
        assertEquals("", ITunesReport.normalize(null));
    }

    @Test
    public void parsesScanStream() {
        ITunesReport.Builder b = new ITunesReport.Builder();
        assertNull(b.accept("N\t5"));
        b.accept("L\tmusic/a.mp3");
        b.accept("L\tmusic/b.mp3");
        b.accept("D\t\tArtiste\tTitre\tAlbum");              // chemin vide : fichier disparu
        b.accept("D\tmusic/c.mp3\tA2\tT2\tAl2");
        b.accept("E\t42\tboom");
        b.accept("bruit inattendu");
        assertArrayEquals(new int[]{50, 200}, b.accept("P\t50\t200"));
        assertFalse(b.isDone());
        b.accept("DONE");
        assertTrue(b.isDone());

        ITunesReport.ScanResult r = b.build();
        assertEquals(5, r.totalTracks());
        assertEquals(2, r.fileTracks());
        assertEquals(1, r.errors());
        assertEquals(2, r.dead().size());
        assertEquals("Titre", r.dead().get(0).title());
        assertEquals("", r.dead().get(0).location());
        assertEquals("music/c.mp3", r.dead().get(1).location());
        assertTrue(r.locations().contains(ITunesReport.normalize("music/a.mp3")));
        assertEquals(2, r.diagnostics().size());
    }

    @Test
    public void emptyFieldsKeepTheirColumns() {
        ITunesReport.Builder b = new ITunesReport.Builder();
        b.accept("D\tx.mp3\t\t\t");   // artiste/titre/album vides
        assertEquals("", b.build().dead().get(0).artist());
    }

    @Test
    public void notInITunesComparesNormalizedPaths() {
        Path inItunes = Paths.get("Music/Artist/One.mp3");
        Path missing  = Paths.get("Music/Artist/Two.mp3");
        java.util.Set<String> loc = new java.util.HashSet<>();
        loc.add(ITunesReport.normalize("MUSIC/ARTIST/ONE.MP3"));
        List<Path> out = ITunesReport.notInITunes(List.of(inItunes, missing), loc);
        assertEquals(List.of(missing), out);
    }
}
