package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** Album du dossier prioritaire (2026-10-08) — noms de dossiers réels de la bibliothèque. */
public class FolderReleaseTest {

    @Test public void folderNameSkipsDiscSubfolderAndBrackets() {
        assertEquals("Hits Total 2013", TaggingWorker.folderAlbumName(new File("/x/Compilations/Hits Total 2013/2-12 Let Her Go.mp3")));
        assertEquals("Fg tendances #electro winter 2016",
                TaggingWorker.folderAlbumName(new File("/x/Compilations/Fg tendances #electro winter 2016/Disc 02/06 - x.mp3")));
        assertEquals("Oxygène", TaggingWorker.folderAlbumName(new File("/x/Jean-Michel Jarre/Oxygène (1976) [FLAC]/01.flac")));
    }

    @Test public void sameAlbumNameToleratesPunctuationAndTruncation() {
        assertTrue(TaggingWorker.sameAlbumName("Now That_s What I Call Running", "Now That's What I Call Running"));
        assertTrue(TaggingWorker.sameAlbumName("Footloose_ Original Soundtrack of the Pa",
                "Footloose: Original Soundtrack of the Paramount Motion Picture"));
        assertFalse(TaggingWorker.sameAlbumName("Hits Total 2013", "Chilled"));
        assertFalse("autre volume", TaggingWorker.sameAlbumName("Bravo Hits 105", "Bravo Hits 116"));
    }
}
