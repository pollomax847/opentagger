package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;

/** Aperçu avant enregistrement (SavePreview) : ce qui change vraiment, selon la règle de fusion de TagWriter. */
public class SavePreviewTest {

    private static TagInfo twoPac() {
        TagInfo t = new TagInfo();
        t.artist = "2Pac"; t.title = "Thug Style"; t.album = "Until the End of Time"; t.albumArtist = "2Pac";
        t.lyrics = "paroles de 2Pac"; t.track = "14/17"; t.bpm = "96"; t.comment = "rip perso";
        return t;
    }

    private static Map<String, SavePreview.Change> byField(List<SavePreview.Change> l) {
        return l.stream().collect(Collectors.toMap(SavePreview.Change::field, c -> c));
    }

    @Test
    public void fusionAutreMorceauMontreCeQuiEstEfface() {
        TagInfo nu = new TagInfo();
        nu.artist = "Taylor Swift"; nu.title = "Style"; nu.album = "1989";
        Map<String, SavePreview.Change> c = byField(SavePreview.changes(twoPac(), nu, false));
        assertTrue(SavePreview.otherSong(twoPac(), nu));
        assertEquals("Taylor Swift", c.get("artist").after());
        assertTrue(c.get("artist").identity());
        assertEquals("", c.get("albumArtist").after());     // effacé : ancienne identité
        assertEquals("", c.get("lyrics").after());
        assertFalse(c.containsKey("bpm"));                  // le son reste
        assertFalse(c.containsKey("comment"));
    }

    @Test
    public void fusionMemeMorceauGardeLesAnciensChamps() {
        TagInfo nu = new TagInfo();
        nu.artist = "2Pac"; nu.title = "Thug Style"; nu.year = "2001";
        Map<String, SavePreview.Change> c = byField(SavePreview.changes(twoPac(), nu, false));
        assertEquals(java.util.Set.of("year"), c.keySet());   // seule l'année change
        assertEquals("", c.get("year").before());
        assertEquals("2001", c.get("year").after());
    }

    @Test
    public void effacerLesTagsExistantsMontreToutCeQuiDisparait() {
        TagInfo nu = new TagInfo();
        nu.artist = "2Pac"; nu.title = "Thug Style";
        Map<String, SavePreview.Change> c = byField(SavePreview.changes(twoPac(), nu, true));
        assertTrue(c.containsKey("lyrics"));
        assertTrue(c.containsKey("bpm"));
        assertFalse(c.containsKey("artist"));
    }

    @Test
    public void numerosEtDrapeauxEquivalentsNeSontPasDesChangements() {
        TagInfo a = twoPac(), b = twoPac();
        b.track = "14"; a.isCompilation = "0"; b.isCompilation = "";
        assertTrue(SavePreview.changes(a, b, false).isEmpty());
        assertEquals("Artiste de l'album", SavePreview.label("albumArtist"));
    }
}
