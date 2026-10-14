package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;
import com.opentagger.ui.LibraryChecks.Check;
import com.opentagger.ui.LibraryChecks.Field;
import com.opentagger.ui.LibraryChecks.Fix;
import com.opentagger.ui.LibraryChecks.Track;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** « À corriger » façon MediaMonkey (2026-10-13) — cas tirés de la vraie bibliothèque (scan_cache). */
public class LibraryChecksTest {

    private static final String VA = "Various Artists";
    private final List<Track<String>> tracks = new ArrayList<>();

    private TagInfo add(String path, String artist, String albumArtist, String title, String album) {
        TagInfo t = new TagInfo();
        t.artist = artist; t.albumArtist = albumArtist; t.title = title; t.album = album;
        t.year = "2001"; t.genre = "Pop"; t.track = String.valueOf(tracks.size() + 1); t.replayGainTrackGain = "-5.00 dB";
        tracks.add(new Track<>(path, Paths.get(path), t, false));
        return t;
    }

    private LibraryChecks.Report<String> run() { return LibraryChecks.analyze(tracks, VA); }

    private static Map<Field, String> fixOf(LibraryChecks.Report<String> r, Check c, String path) {
        for (Fix<String> f : r.fixes(c)) if (f.ref().equals(path)) return f.changes();
        return null;
    }

    @Test public void sameAlbumArtistWrittenDifferentlyIsUnified() {
        add("/m/Syndicate of Law/Right on Time/01.mp3", "Syndicate of Law", "Syndicate of Law", "A", "Right on Time");
        add("/m/Syndicate of Law/Right on Time/02.mp3", "Syndicate of Law", "Syndicate Of Law", "B", "Right on Time");
        add("/m/Syndicate of Law/Right on Time/03.mp3", "Syndicate of Law", "", "C", "Right on Time");
        add("/m/Syndicate of Law/Right on Time/04.mp3", "Syndicate of Law", "Syndicate of Law", "D", "Right on Time");
        var r = run();
        assertEquals(4, r.found(Check.ALBUM_ARTIST_SPLIT).size());
        assertEquals("Syndicate of Law", fixOf(r, Check.ALBUM_ARTIST_SPLIT, "/m/Syndicate of Law/Right on Time/02.mp3").get(Field.ALBUM_ARTIST));
        assertEquals("Syndicate of Law", fixOf(r, Check.ALBUM_ARTIST_SPLIT, "/m/Syndicate of Law/Right on Time/03.mp3").get(Field.ALBUM_ARTIST));
        assertNull(fixOf(r, Check.ALBUM_ARTIST_SPLIT, "/m/Syndicate of Law/Right on Time/01.mp3"));   // déjà bon
    }

    @Test public void aWellWrittenSpellingBeatsCapitals() {
        for (int i = 1; i <= 3; i++) add("/m/James Ingram/Night/0" + i + ".mp3", "James Ingram", "JAMES INGRAM", "T" + i, "It's Your Night");
        add("/m/James Ingram/Night/04.mp3", "James Ingram", "James Ingram", "T4", "It's Your Night");
        add("/m/IAM/Ombre/01.mp3", "IAM", "IAM", "A", "Ombre est lumière");
        add("/m/IAM/Ombre/02.mp3", "IAM", "IAM", "B", "Ombre est lumière");
        add("/m/IAM/Ombre/03.mp3", "IAM", "Iam", "C", "Ombre est lumière");
        var r = run();
        assertEquals("James Ingram", fixOf(r, Check.ALBUM_ARTIST_SPLIT, "/m/James Ingram/Night/01.mp3").get(Field.ALBUM_ARTIST));
        assertEquals("IAM", fixOf(r, Check.ALBUM_ARTIST_SPLIT, "/m/IAM/Ombre/03.mp3").get(Field.ALBUM_ARTIST));   // sigle court
    }

    @Test public void strongMajorityWinsOnARealAlbum() {
        for (int i = 1; i <= 4; i++) add("/m/Compilations/Nova Tunes 3.5/0" + i + ".mp3", "Artist " + i, VA, "T" + i, "Nova Tunes 3.5");
        add("/m/Compilations/Nova Tunes 3.5/05.mp3", "Ricky Nelson", "Ricky Nelson", "T5", "Nova Tunes 3.5");
        Map<Field, String> fix = fixOf(run(), Check.ALBUM_ARTIST_SPLIT, "/m/Compilations/Nova Tunes 3.5/05.mp3");
        assertEquals(VA, fix.get(Field.ALBUM_ARTIST));
        assertEquals("1", fix.get(Field.COMPILATION));
    }

    @Test public void theArtistFolderSettlesTwoNames() {
        for (int i = 1; i <= 5; i++) add("/m/Sniper/Gravé dans la roche/0" + i + ".mp3", "Tireur isolé", "Tireur isolé", "T" + i, "Gravé dans la roche");
        add("/m/Sniper/Gravé dans la roche/06.mp3", "Sniper", "Sniper", "T6", "Gravé dans la roche");
        assertEquals("Sniper", fixOf(run(), Check.ALBUM_ARTIST_SPLIT, "/m/Sniper/Gravé dans la roche/01.mp3").get(Field.ALBUM_ARTIST));

        tracks.clear();
        add("/m/Freeman Feat. K. Rhyme Le Roi/L'Palais/01.mp3", "Freeman", "Freeman", "A", "L'Palais de justice");
        add("/m/Freeman Feat. K. Rhyme Le Roi/L'Palais/02.mp3", "Freeman", "Freeman feat. K. Rhyme le Roi", "B", "L'Palais de justice");
        assertTrue(run().fixes(Check.ALBUM_ARTIST_SPLIT).isEmpty());   // dossier « feat. » : on montre seulement
    }

    @Test public void noMajorityIsOnlyShown() {
        add("/m/Compilations/Holy Fire/01.mp3", "Foals", "Foals", "A", "Holy Fire");
        add("/m/Compilations/Holy Fire/02.mp3", "Foals", "Foals", "B", "Holy Fire");
        add("/m/Compilations/Holy Fire/03.mp3", "Foals", VA, "C", "Holy Fire");
        add("/m/Compilations/Holy Fire/04.mp3", "Foals", "", "D", "Holy Fire");
        var r = run();
        assertEquals(4, r.found(Check.ALBUM_ARTIST_SPLIT).size());
        assertTrue(r.fixes(Check.ALBUM_ARTIST_SPLIT).isEmpty());
    }

    @Test public void compilationWhereEachTrackGotItsOwnArtistBecomesVarious() {
        add("/m/Compilations/Jazz/01.mp3", "Quincy Jones", "Quincy Jones", "A", "Jazz 'round Midnight");
        add("/m/Compilations/Jazz/02.mp3", "Ella Fitzgerald", "Ella Fitzgerald", "B", "Jazz 'round Midnight");
        add("/m/Compilations/Jazz/03.mp3", "Chet Baker", "Chet Baker", "C", "Jazz 'round Midnight");
        assertEquals(VA, fixOf(run(), Check.ALBUM_ARTIST_SPLIT, "/m/Compilations/Jazz/02.mp3").get(Field.ALBUM_ARTIST));
    }

    @Test public void missingAlbumArtist() {
        add("/m/Inna/Hot/01.mp3", "Inna", "", "Hot", "Hot");
        add("/m/Inna/Hot/02.mp3", "Inna feat. Play & Win", "", "Déjà Vu", "Hot");
        assertEquals("Inna", fixOf(run(), Check.NO_ALBUM_ARTIST, "/m/Inna/Hot/02.mp3").get(Field.ALBUM_ARTIST));

        tracks.clear();
        add("/m/Compilations/Duo/01.mp3", "Kaaris", "", "A", "Duo");
        add("/m/Compilations/Duo/02.mp3", "Booba", "", "B", "Duo");
        var r = run();
        assertEquals(2, r.found(Check.NO_ALBUM_ARTIST).size());
        assertTrue(r.fixes(Check.NO_ALBUM_ARTIST).isEmpty());   // deux interprètes : impossible de trancher

        add("/m/Compilations/Duo/03.mp3", "Damso", "", "C", "Duo");
        assertEquals(VA, fixOf(run(), Check.NO_ALBUM_ARTIST, "/m/Compilations/Duo/03.mp3").get(Field.ALBUM_ARTIST));
    }

    @Test public void severalLineUpsOfOneArtistAreNotACompilation() {
        add("/m/Miles Davis/Plays For Lovers/01.mp3", "Miles Davis", "", "A", "Miles Davis Plays For Lovers");
        add("/m/Miles Davis/Plays For Lovers/02.mp3", "Miles Davis Quintet", "", "B", "Miles Davis Plays For Lovers");
        add("/m/Miles Davis/Plays For Lovers/03.mp3", "Miles Davis Sextet", "", "C", "Miles Davis Plays For Lovers");
        assertEquals("Miles Davis", fixOf(run(), Check.NO_ALBUM_ARTIST, "/m/Miles Davis/Plays For Lovers/02.mp3").get(Field.ALBUM_ARTIST));

        tracks.clear();
        add("/m/Jazz/Plays/01.mp3", "Quincy Jones", "Quincy Jones", "A", "Jazz Plays");
        add("/m/Jazz/Plays/02.mp3", "Ella Fitzgerald", "Ella Fitzgerald", "B", "Jazz Plays");
        add("/m/Jazz/Plays/03.mp3", "Chet Baker", "Chet Baker", "C", "Jazz Plays");
        assertTrue(run().fixes(Check.ALBUM_ARTIST_SPLIT).isEmpty());   // hors dossier de compilation : on montre seulement
    }

    @Test public void yearAndGenreComeFromTheAlbumOnlyWhenUnanimous() {
        add("/m/A/B/01.mp3", "A", "A", "x", "Be Here Now").genre = "Rock";
        add("/m/A/B/02.mp3", "A", "A", "y", "Be Here Now");
        TagInfo third = add("/m/A/B/03.mp3", "A", "A", "z", "Be Here Now");
        third.year = ""; third.genre = "";
        var r = run();
        assertEquals("2001", fixOf(r, Check.NO_YEAR, "/m/A/B/03.mp3").get(Field.YEAR));
        assertNull(fixOf(r, Check.NO_GENRE, "/m/A/B/03.mp3"));   // Rock / Pop : pas d'accord
        assertEquals(1, r.found(Check.NO_GENRE).size());
    }

    @Test public void dumpFoldersAreNotAlbums() {
        add("/home/u/Musiques/a.mp3", "Anggun", "Anggun", "A", "L'essentiel (1977-2007)");
        add("/home/u/Musiques/b.mp3", "Bénabar", "Bénabar & associés", "B", "L'essentiel (1977-2007)");
        add("/home/u/Musiques/c.mp3", "C", "C", "C", "Album C");
        add("/home/u/Musiques/d.mp3", "D", "D", "D", "Album D");
        add("/home/u/Musiques/e.mp3", "E", "E", "E", "Album E");
        assertTrue(run().found(Check.ALBUM_ARTIST_SPLIT).isEmpty());
    }

    @Test public void caseIsFixedOnlyWhenTitleAndArtistAreBothWrong() {
        add("/m/E/T/01.mp3", "eurythmics", "Eurythmics", "sweet dreams", "touch");
        add("/m/E/T/02.mp3", "METALLICA", "Metallica", "ENTER SANDMAN", "Touch");
        add("/m/E/T/03.mp3", "DJ Khaled", "DJ Khaled", "BIG TIME", "GOD DID");            // graphie voulue
        add("/m/E/T/04.mp3", "deadmau5", "deadmau5", "Arguru (EDX remix)", "Touch");      // artiste stylisé
        add("/m/E/T/05.mp3", "dj snake", "DJ Snake", "loco contigo", "Touch");
        add("/m/E/T/06.mp3", "KENDRICK LAMAR", "Kendrick Lamar", "HUMBLE. TOO", "Touch").recordingMbid = "mb";
        var r = run();
        Map<Field, String> fix = fixOf(r, Check.CASE, "/m/E/T/01.mp3");
        assertEquals("Sweet Dreams", fix.get(Field.TITLE));
        assertEquals("Eurythmics", fix.get(Field.ARTIST));
        assertEquals("Touch", fix.get(Field.ALBUM));
        assertEquals("Metallica", fixOf(r, Check.CASE, "/m/E/T/02.mp3").get(Field.ARTIST));
        assertEquals("Enter Sandman", fixOf(r, Check.CASE, "/m/E/T/02.mp3").get(Field.TITLE));
        assertNull(fixOf(r, Check.CASE, "/m/E/T/03.mp3"));
        assertNull(fixOf(r, Check.CASE, "/m/E/T/04.mp3"));
        assertEquals("DJ Snake", fixOf(r, Check.CASE, "/m/E/T/05.mp3").get(Field.ARTIST));
        assertNull(fixOf(r, Check.CASE, "/m/E/T/06.mp3"));   // graphie officielle MusicBrainz
    }

    @Test public void artistAndTitleSwappedOnlyWithEvidenceBeyondTheFileName() {
        // L'artiste de l'album confirme le nom de fichier.
        add("/m/Eurythmics/Hits/Eurythmics - Sweet Dreams.mp3", "Sweet Dreams", "Eurythmics", "Eurythmics", "");
        // Rip nommé « Titre - Artiste » : ce sont les noms de fichiers qui sont à l'envers, pas les tags.
        add("/m/Stafford, Jo/Once Over Lightly/04 - These Foolish Things - Jo Stafford.mp3", "Jo Stafford", "Jo Stafford",
                "These Foolish Things", "");
        // Dossier d'album au nom d'un titre : ne prouve rien.
        add("/m/Davis, Miles/Enigma/12 - Enigma - Miles Davis.mp3", "Miles Davis", "", "Enigma", "");
        var r = run();
        Map<Field, String> fix = fixOf(r, Check.SWAPPED, "/m/Eurythmics/Hits/Eurythmics - Sweet Dreams.mp3");
        assertEquals("Eurythmics", fix.get(Field.ARTIST));
        assertEquals("Sweet Dreams", fix.get(Field.TITLE));
        assertEquals(1, r.found(Check.SWAPPED).size());
    }

    @Test public void selfTitledSongsAreNeverSwapped() {
        // Vu dans la vraie bibliothèque : « Era » (Era), « Bisso Na Bisso » — le reste de l'album ne prouve rien.
        add("/m/Era/Era/01 - Era.mp3", "Jacana People", "Era", "Era", "Era");
        add("/m/Era/Era/02 - Ameno.mp3", "Era", "Era", "Ameno", "Era");
        add("/m/Era/Era/03 - Mother.mp3", "Era", "Era", "Mother", "Era");
        assertTrue(run().found(Check.SWAPPED).isEmpty());
    }

    @Test public void titleCaseLikeMediaMonkey() {
        assertEquals("The Ketchup Song (Aserejé) (Spanglish Version)", LibraryChecks.titleCase("THE KETCHUP SONG (ASEREJÉ) (SPANGLISH VERSION)"));
        assertEquals("Camila Cabello/Ed Sheeran", LibraryChecks.titleCase("CAMILA CABELLO/ED SHEERAN"));
        assertEquals("Kurupt & C‐Mob", LibraryChecks.titleCase("KURUPT & C‐MOB"));
        assertEquals("Attentat II", LibraryChecks.titleCase("ATTENTAT II"));
        assertEquals("De La Peine Pas De Chagrin", LibraryChecks.titleCase("DE LA PEINE PAS DE CHAGRIN"));
        assertEquals("I Can't Get Away", LibraryChecks.titleCase("i can't get away"));
        assertEquals("DJ Snake x Tyga", LibraryChecks.titleCase("dj snake x tyga"));
        assertFalse(LibraryChecks.shouting("車ゲーム風音楽 I [ぽやすみなさーい]"));
    }

    @Test public void trackNumberFromTheFileName() {
        assertEquals("5", LibraryChecks.trackFromFileName("05 - Titre.mp3"));
        assertEquals("7", LibraryChecks.trackFromFileName("1-07 Titre.mp3"));
        assertEquals("12", LibraryChecks.trackFromFileName("12. Titre.mp3"));
        assertNull(LibraryChecks.trackFromFileName("Titre.mp3"));
        assertNull(LibraryChecks.trackFromFileName("00 - Intro.mp3"));

        add("/m/A/B/01 - x.mp3", "A", "A", "x", "Be Here Now");
        add("/m/A/B/05 - y.mp3", "A", "A", "y", "Be Here Now").track = "";
        add("/m/A/B/01 - z.mp3", "A", "A", "z", "Be Here Now").track = "";   // 1 est déjà pris par la première piste
        var r = run();
        assertEquals("5", fixOf(r, Check.NO_TRACK, "/m/A/B/05 - y.mp3").get(Field.TRACK));
        assertNull(fixOf(r, Check.NO_TRACK, "/m/A/B/01 - z.mp3"));
        assertEquals(2, r.found(Check.NO_TRACK).size());
    }

    @Test public void missingFilesAreOnlyMissing() {
        TagInfo t = new TagInfo();
        tracks.add(new Track<>("/gone.mp3", Paths.get("/gone.mp3"), t, true));
        var r = run();
        assertEquals(List.of("/gone.mp3"), r.found(Check.MISSING_FILE));
        assertTrue(r.found(Check.NO_TITLE).isEmpty());
    }

    @Test public void shoutingNeedsTwoWords() {
        assertTrue(LibraryChecks.shouting("SWEET DREAMS"));
        assertFalse(LibraryChecks.shouting("ABBA"));
        assertFalse(LibraryChecks.shouting("Sweet DREAMS"));
        assertTrue(LibraryChecks.whispering("sweet dreams"));
        assertFalse(LibraryChecks.whispering("u2"));
        assertEquals("jeanlouisaubert", LibraryChecks.key("Jean‐Louis Aubert"));
    }
    @Test public void coversAndAlbumVolume() {
        add("/m/A/Album/01.mp3", "A", "A", "x", "Album Cover").coverInfo = "1:aaaa";
        add("/m/A/Album/02.mp3", "A", "A", "y", "Album Cover").coverInfo = "3:aaaa";
        add("/m/A/Album/03.mp3", "A", "A", "z", "Album Cover").coverInfo = "1:bbbb";
        add("/m/A/Album/04.mp3", "A", "A", "w", "Album Cover").coverInfo = "0";
        add("/m/A/Album/05.mp3", "A", "A", "v", "Album Cover");                       // jamais lu
        add("/m/A/Album/06.opus", "A", "A", "u", "Album Cover");                      // jamais lu, pas modifiable
        tracks.get(0).tags().replayGainAlbumGain = "-6.00 dB";
        var r = run();
        assertEquals(List.of("/m/A/Album/04.mp3"), r.found(Check.NO_COVER));
        assertEquals(List.of("/m/A/Album/02.mp3"), r.found(Check.MULTI_COVER));
        assertEquals(3, r.found(Check.COVER_MISMATCH).size());                        // aaaa / bbbb parmi les fichiers lus
        assertEquals(List.of("/m/A/Album/05.mp3"), r.coversNotRead());
        assertEquals(5, r.found(Check.NO_ALBUM_GAIN).size());
        assertEquals(1, r.albumsWithoutGain().size());
        assertEquals(6, r.albumsWithoutGain().get(0).size());                         // tout l'album est recalculé
        assertEquals(3, LibraryChecks.coverCount("3:aaaa"));
        assertEquals(-1, LibraryChecks.coverCount(""));
    }
}
