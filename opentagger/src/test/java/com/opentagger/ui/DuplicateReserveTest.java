package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class DuplicateReserveTest {

    private static FileEntry entry(String name) { return new FileEntry(new File(name), new TagInfo()); }

    private static final Map<String, Long> SIZE = new HashMap<>();

    private static long size(FileEntry e) { return SIZE.getOrDefault(e.file.getName(), 0L); }

    @Test
    public void theBestCopyStaysAndTheOthersGoToTheReserve() {
        FileEntry a = entry("sos.mp3"), b = entry("sos (2).mp3"), c = entry("sos (3).mp3");
        SIZE.put("sos.mp3", 5_000_000L); SIZE.put("sos (2).mp3", 5_300_000L); SIZE.put("sos (3).mp3", 4_900_000L);
        List<FileEntry> extra = AlbumCompletionWorker.surplusCopies(List.of(a, b, c), DuplicateReserveTest::size);
        assertEquals(2, extra.size());
        assertTrue("la plus grosse (meilleure) reste", !extra.contains(b));
        assertTrue(extra.contains(a) && extra.contains(c));
    }

    @Test
    public void onTiesTheFirstCopyStays() {
        FileEntry a = entry("a.mp3"), b = entry("b.mp3");
        SIZE.put("a.mp3", 1L); SIZE.put("b.mp3", 1L);
        List<FileEntry> extra = AlbumCompletionWorker.surplusCopies(List.of(a, b), DuplicateReserveTest::size);
        assertEquals(List.of(b), extra);
    }

    @Test
    public void aSingleCopyIsNeverSurplus() {
        assertTrue(AlbumCompletionWorker.surplusCopies(List.of(entry("x.mp3")), DuplicateReserveTest::size).isEmpty());
        assertTrue(AlbumCompletionWorker.surplusCopies(null, DuplicateReserveTest::size).isEmpty());
    }

    @Test
    public void aCopyIsClaimedOnlyByAnotherRelease() {
        FileEntry copy = entry("copie.mp3");
        Map<String, List<AlbumCompletionWorker.SurplusCopy>> reserve = new HashMap<>();
        reserve.computeIfAbsent("rec-sos", k -> new ArrayList<>()).add(new AlbumCompletionWorker.SurplusCopy(copy, "release-B"));

        assertNull("la release d'origine ne le reprend pas", AlbumCompletionWorker.claimSurplus(reserve, "rec-sos", "release-B"));
        assertSame(copy, AlbumCompletionWorker.claimSurplus(reserve, "rec-sos", "release-A"));
        assertNull("une copie ne sert qu'une fois", AlbumCompletionWorker.claimSurplus(reserve, "rec-sos", "release-A"));
    }

    @Test
    public void anUnknownRecordingOrBlankIdClaimsNothing() {
        Map<String, List<AlbumCompletionWorker.SurplusCopy>> reserve = new HashMap<>();
        assertNull(AlbumCompletionWorker.claimSurplus(reserve, "rec-x", "A"));
        assertNull(AlbumCompletionWorker.claimSurplus(reserve, "", "A"));
        assertNull(AlbumCompletionWorker.claimSurplus(null, "rec-x", "A"));
    }
}
