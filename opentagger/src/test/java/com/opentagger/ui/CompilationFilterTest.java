package com.opentagger.ui;

import com.opentagger.MusicBrainzClient.RecordingRelease;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class CompilationFilterTest {

    private static RecordingRelease rel(String title, boolean compilation) {
        return new RecordingRelease("id-" + title, title, title,
                compilation ? List.of("Compilation") : List.of(), "FR");
    }

    @Test
    public void trackOnItsStudioAlbumIsNotProposedForAnyCompilation() {
        var all = List.of(rel("Real Things", false), rel("Best Hits", true));
        assertTrue(CompilationClusterWorker.onlyPlausibleCompilations(all, "Real Things", new String[0]).isEmpty());
    }

    @Test
    public void trackOnlyExistingOnCompilationsKeepsThem() {
        var all = List.of(rel("NRJ Hits 2019", true), rel("W9 Hits 2019", true));
        assertEquals(2, CompilationClusterWorker.onlyPlausibleCompilations(all, "", new String[0]).size());
    }

    @Test
    public void fileAlreadyInACompilationKeepsThatOne() {
        var all = List.of(rel("Real Things", false), rel("Best Hits", true), rel("Other Mix", true));
        var kept = CompilationClusterWorker.onlyPlausibleCompilations(all, "best hits", new String[0]);
        assertEquals(1, kept.size());
        assertEquals("Best Hits", kept.get(0).releaseTitle());
    }
}
