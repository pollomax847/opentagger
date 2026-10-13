package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Erreurs trouvées dans l'aperçu « Renommer les tagués » du 13/10 — toutes écrites par l'appli elle-même. */
public class LibraryErrorsGuardTest {

    private static TagInfo tags(String artist, String albumArtist, String title, String comp) {
        TagInfo t = new TagInfo();
        t.artist = artist; t.albumArtist = albumArtist; t.title = title; t.isCompilation = comp;
        return t;
    }

    @Test public void youtubeChannelNamesAreCleaned() {
        assertEquals("Amerie", ArtistNames.stripYoutubeChannel("AmerieVEVO"));
        assertEquals("Amerie", ArtistNames.stripYoutubeChannel("Amerievevo"));
        assertEquals("Kyo", ArtistNames.stripYoutubeChannel("Kyo - Topic"));
        assertEquals("Vevo", ArtistNames.stripYoutubeChannel("Vevo"));                 // jamais vidé
        assertEquals("Daft Punk", ArtistNames.stripYoutubeChannel("Daft Punk"));
    }

    @Test public void djMixTrackGetsItsPerformerBack() {
        TagInfo t = tags("Aeroplane", "Aeroplane", "Big in Japan", "1");
        ArtistNames.clean(t, new File("/m/Compilations/Mixmag Presents_ Disco Balearica/02 - Alphaville - Big in Japan.mp3"));
        assertEquals("Alphaville", t.artist);
        assertEquals("Aeroplane", t.albumArtist);                                        // le DJ reste l'artiste de l'album
    }

    @Test public void performerIsNotTakenFromAFileNameThatNamesAnotherSong() {
        TagInfo t = tags("Peter von Poehl", "Peter von Poehl", "Going to Where the Tea Trees Are", "0");
        ArtistNames.clean(t, new File("/m/Compilations/NRJ Fresh Hits 2017/12 - Deorro - Going Up.mp3"));
        assertEquals("Peter von Poehl", t.artist);                                       // mauvais téléchargement : tags gardés
        TagInfo album = tags("3 Doors Down", "3 Doors Down", "Let Me Go", "0");
        ArtistNames.clean(album, new File("/m/Compilations/The Greatest Hits/05 - 3 Doors Down - Let Me Go.mp3"));
        assertEquals("3 Doors Down", album.artist);
        TagInfo feat = tags("Bob Sinclar", "Bob Sinclar", "World, Hold On", "1");
        ArtistNames.clean(feat, new File("/m/Compilations/X/05 - Bob Sinclar & Steve Edwards - World, Hold On.mp3"));
        assertEquals("Bob Sinclar", feat.artist);                                         // simple collaboration : inchangé
    }

    @Test public void vinylNumbersAreKept() {
        assertEquals("Anne Sylvestre/Anne Sylvestre/A5 - Antoinette A Peur du Loup",
                FileRenamer.keepVinylNumber("Anne Sylvestre/Anne Sylvestre/00 - Antoinette A Peur du Loup",
                        "A5 - Antoinette A Peur du Loup.mp3"));
        assertEquals("X/03 - Y", FileRenamer.keepVinylNumber("X/03 - Y", "A5 - Y.mp3"));     // vrai numéro : inchangé
        assertEquals("X/00 - Intro", FileRenamer.keepVinylNumber("X/00 - Intro", "00 - Intro.mp3"));
    }

    @Test public void aTrackNeverLeavesItsCompilationFolder() throws Exception {
        Path root = Files.createTempDirectory("otcomp");
        Path comp = Files.createDirectories(root.resolve("Compilations/#1's"));
        Path f = Files.createFile(comp.resolve("11 - Mariah Carey - I'll Be There.mp3"));
        assertEquals("Compilations/#1's/06 - I'll Be There",
                FileRenamer.stayInCompilationFolder(f, root, "Mariah Carey/MTV Unplugged/06 - I'll Be There"));
        // même album (autre casse / Disc 01) : le rangement normal s'applique
        assertEquals("Compilations/#1’s/Disc 01/11 - x",
                FileRenamer.stayInCompilationFolder(f, root, "Compilations/#1’s/Disc 01/11 - x"));
        // dossier d'artiste ordinaire : rien ne change
        Path album = Files.createDirectories(root.resolve("Ice MC/My World"));
        Path g = Files.createFile(album.resolve("02 - Happy Weekend.mp3"));
        assertEquals("Ice MC/Ice 'n' Green/02 - Happy Weekend",
                FileRenamer.stayInCompilationFolder(g, root, "Ice MC/Ice 'n' Green/02 - Happy Weekend"));
        assertTrue(FileRenamer.isCompilationFolder(comp));
        assertFalse(FileRenamer.isCompilationFolder(album));
    }
}
