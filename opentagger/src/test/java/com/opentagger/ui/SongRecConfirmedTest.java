package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Test;

/** SongRec seul (inconnu de MusicBrainz) confirmé par le fichier (2026-10-08) — cas réels de Sans_correspondance. */
public class SongRecConfirmedTest {

    private static boolean confirmed(String fileName, String srArtist, String srTitle) {
        String[] fn = TaggingWorker.parseFilename(new File("/x/" + fileName));
        return TaggingWorker.songRecConfirmedByFile(fn[0], fn[1], "", "", srArtist, srTitle);
    }

    @Test public void fileNamedLikeTheRecognizedSongIsConfirmed() {
        assertTrue(confirmed("Miguel Campbell - Into You.mp3", "Miguel Campbell", "Into You"));
        assertTrue(confirmed("02 Red.mp3", "Viken Arman", "Red"));
        assertTrue(confirmed("05 méli-mélo.mp3", "kulturr", "méli-mélo"));
        assertTrue(confirmed("Benson - How I Like It.mp3", "Benson", "How I Like It"));
        assertTrue(confirmed("02. Emma Hewitt, Solarstone - Children (Extended Solarstone Pure Mix).mp3",
                "Emma Hewitt & Solarstone", "Children (Solarstone Pure Mix)"));
        assertTrue(confirmed("03-veust-ralenti_feat_slimka_grandbazaar.mp3", "VEUST & Slimka", "Ralenti (feat. GrandBazaar)"));
        assertTrue(confirmed("Las Trillizas de Oro - (I'll Do Anything to Keep Me) Close to You.mp3",
                "Las Trillizas de Oro", "(I'll Do Anything to Keep Me) Close to You"));
        assertTrue(confirmed("07 - 112 - Dance With Me (3).mp3", "112", "Dance With Me (feat. Beanie Sigel) [Radio MIx] [2016 Remix]"));
    }

    @Test public void shazamAloneAgainstTheFileNameStaysInReview() {
        assertFalse(confirmed("03 - Charly Lownoise & Mental Theo - Wonderful Days (2).mp3", "CHARLS", "Down Days"));
        assertFalse(confirmed("05 - Gareth Emery - Voice Inside.mp3", "Dan Chase", "Voice Inside (feat. Diana Leah)"));
        assertFalse(confirmed("K-391, Alan Walker & Ahrix - End of Time (DJ Snappy! Remix).mp3",
                "Vanxi & KOY Music Group", "End Off Time (Vanxi Remix)"));
        assertFalse(confirmed("05 - Blank & Jones - Perfect Silence.mp3", "Chunkee", "Silence (Echo)"));
        assertFalse(confirmed("04 - Billie Eilish - Bad Guy (2).mp3", "The Orange Boys", "Gustavo Bad"));
        assertFalse(confirmed("03 - There's A Boat That's Leaving Soon For New York.mp3", "Frank Sinatra", "Welcome Home Elvis"));
    }

    @Test public void nothingInTheFileToConfirmStaysInReview() {
        assertFalse(confirmed("09.mp3", "Akvarium & Lee \"Scratch\" Perry", "Иван и Данило"));
        assertFalse(confirmed("03 03 Piste 3 1.mp3", "Laidback Luke", "Hypnotize (Steve Angello Remix)"));
    }

    @Test public void tagsCanConfirmWhenTheFileNameSaysNothing() {
        assertTrue(TaggingWorker.songRecConfirmedByFile("", "", "Akvarium", "Иван и Данило",
                "Akvarium & Lee \"Scratch\" Perry", "Иван и Данило"));
    }
}
