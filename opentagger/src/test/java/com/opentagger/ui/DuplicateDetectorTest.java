package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import com.opentagger.ui.DuplicateDetector.Confidence;
import com.opentagger.ui.DuplicateDetector.DuplicateGroup;
import java.io.File;
import java.util.List;
import org.junit.Test;

/** Les 4 niveaux de confiance de la détection de doublons, et leur exclusivité (un fichier n'apparaît que dans le
 *  groupe du niveau le plus fort qui le concerne). */
public class DuplicateDetectorTest {

    private static FileEntry e(String name, String artist, String title, String mbid, String acoustid, String fp) {
        TagInfo t = new TagInfo();
        t.artist = artist; t.title = title;
        t.recordingMbid = mbid; t.acoustidId = acoustid; t.acoustidFingerprint = fp;
        return new FileEntry(new File(name), t);
    }

    private static Confidence only(List<DuplicateGroup> g) {
        assertEquals(1, g.size());
        return g.get(0).confidence();
    }

    @Test
    public void sameRecordingMbidIsMbidExact() {
        var g = DuplicateDetector.detect(List.of(
            e("a.mp3", "X", "Y", "mb1", "", ""), e("b.mp3", "Z", "W", "mb1", "", "")));
        assertEquals(Confidence.MBID_EXACT, only(g));
    }

    @Test
    public void sameAcoustIdWithoutMbidIsAcoustIdExact() {
        var g = DuplicateDetector.detect(List.of(
            e("a.mp3", "X", "Y", "", "ac1", ""), e("b.mp3", "Z", "W", "", "ac1", "")));
        assertEquals(Confidence.ACOUSTID_EXACT, only(g));
    }

    @Test
    public void sameRawFingerprintIsFingerprintExact() {
        var g = DuplicateDetector.detect(List.of(
            e("a.mp3", "X", "Y", "", "", "FP"), e("b.mp3", "Z", "W", "", "", "FP")));
        assertEquals(Confidence.FINGERPRINT_EXACT, only(g));
    }

    @Test
    public void sameArtistAndTitleOnlyIsHeuristic() {
        var g = DuplicateDetector.detect(List.of(
            e("a.mp3", "Daft Punk", "One More Time", "", "", ""),
            e("b.mp3", "daft punk", "One More Time", "", "", "")));
        assertEquals(Confidence.TITLE_HEURISTIC, only(g));
    }

    @Test
    public void titleAloneNeverMakesAGroup() {
        var g = DuplicateDetector.detect(List.of(
            e("a.mp3", "Artist A", "Intro", "", "", ""), e("b.mp3", "Artist B", "Intro", "", "", "")));
        assertTrue(g.isEmpty());
    }

    @Test
    public void aFileIsOnlyInTheStrongestGroup() {
        // a et b partagent le MBID ; b et c partageraient aussi l'empreinte, mais b est déjà pris par le niveau MBID
        var a = e("a.mp3", "X", "Y", "mb1", "", "FP");
        var b = e("b.mp3", "X", "Y", "mb1", "", "FP");
        var c = e("c.mp3", "X", "Y", "", "", "FP");
        var g = DuplicateDetector.detect(List.of(a, b, c));
        assertEquals(1, g.size());
        assertEquals(Confidence.MBID_EXACT, g.get(0).confidence());
        assertEquals(2, g.get(0).files().size());
    }

    @Test
    public void singleFilesProduceNoGroup() {
        assertTrue(DuplicateDetector.detect(List.of(e("a.mp3", "X", "Y", "mb1", "ac", "fp"))).isEmpty());
    }
}
