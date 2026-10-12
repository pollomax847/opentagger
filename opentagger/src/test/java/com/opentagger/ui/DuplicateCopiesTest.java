package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import com.opentagger.ui.DuplicateDetector.Scope;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

/** Doublons = vraies copies seulement (2026-10-12) — cas réels de MyBook. */
public class DuplicateCopiesTest {

    private static FileEntry e(String path, String artist, String title, String track, int sec) {
        TagInfo t = new TagInfo();
        t.artist = artist; t.title = title; t.track = track; t.durationSec = sec;
        return new FileEntry(new File(path), t);
    }

    @Test public void copiesInTheSameFolderAreFound() {
        var scan = DuplicateDetector.detectCopies(List.of(
                e("/m/Big Sean/Bounce Back/01 Bounce Back.mp3", "Big Sean", "Bounce Back", "1", 222),
                e("/m/Big Sean/Bounce Back/01 - Bounce Back (3).mp3", "Big Sean", "Bounce Back", "1", 223)));
        assertEquals(1, scan.groups().size());
        assertEquals(Scope.SAME_FOLDER, scan.groups().get(0).scope());
    }

    @Test public void differentSongsTaggedAlikeAreNotDuplicates() {
        var scan = DuplicateDetector.detectCopies(List.of(
                e("/m/X/Album/04 Again And Again.mp3", "Status Quo", "Again and Again", "7", 222),
                e("/m/X/Album/05 Caroline.mp3", "Status Quo", "Again and Again", "7", 224),
                e("/m/X/Album/06 Oh' What A Night.mp3", "Status Quo", "Again and Again", "7", 223)));
        assertTrue(scan.groups().isEmpty());
    }

    @Test public void aSongOnACompilationIsNotADuplicateOfTheAlbum() {
        var scan = DuplicateDetector.detectCopies(List.of(
                e("/m/3 Doors Down/Seventeen Days/05 Let Me Go.mp3", "3 Doors Down", "Let Me Go", "5", 232),
                e("/m/Compilations/NRJ Hits 2005/12 - 3 Doors Down - Let Me Go.mp3", "3 Doors Down", "Let Me Go", "12", 232)));
        assertTrue(scan.groups().isEmpty());
        assertEquals(1, scan.songsOnSeveralAlbums());
    }

    @Test public void theSameAlbumFiledTwiceIsFound() {
        var scan = DuplicateDetector.detectCopies(List.of(
                e("/m/3 DOORS DOWN/The Greatest Hits/05 Let Me Go.mp3", "3 Doors Down", "Let Me Go", "5", 232),
                e("/m/Compilations/The Greatest Hits/05 - 3 Doors Down - Let Me Go.mp3", "3 Doors Down", "Let Me Go", "5", 233)));
        assertEquals(1, scan.groups().size());
        assertEquals(Scope.SAME_ALBUM_ELSEWHERE, scan.groups().get(0).scope());
    }

    @Test public void anotherVersionOfTheSameTitleIsKept() {
        var scan = DuplicateDetector.detectCopies(List.of(
                e("/m/A/Single/01 Song.mp3", "A", "Song", "1", 200),
                e("/m/A/Single/01 Song (2).mp3", "A", "Song", "1", 380)));    // version longue : autre durée
        assertTrue(scan.groups().isEmpty());
    }

    @Test public void bestCopyIsQualityThenCleanName() throws Exception {
        Path dir = Files.createTempDirectory("otdup");
        Files.write(dir.resolve("01 Song.mp3"), new byte[1000]);
        Files.write(dir.resolve("01 Song (2).mp3"), new byte[1000]);
        Files.write(dir.resolve("01 Song 1.mp3"), new byte[2000]);          // nettement plus gros = meilleure qualité
        FileEntry clean = e(dir.resolve("01 Song.mp3").toString(), "A", "Song", "1", 200);
        FileEntry copy  = e(dir.resolve("01 Song (2).mp3").toString(), "A", "Song", "1", 200);
        assertSame(clean, DuplicateDetector.bestCopy(List.of(copy, clean), new HashMap<>()));
        FileEntry bigger = e(dir.resolve("01 Song 1.mp3").toString(), "A", "Song", "1", 200);
        assertSame(bigger, DuplicateDetector.bestCopy(List.of(copy, clean, bigger), new HashMap<>()));
    }
}
