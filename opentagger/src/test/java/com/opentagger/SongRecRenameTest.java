package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.opentagger.SongRecRename.Info;
import com.opentagger.model.TagInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** songrec-rename intégré à OpenTagger : mêmes règles que l'outil Python (cadence), sans jamais appeler Shazam. */
public class SongRecRenameTest {

    private Path dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("songrec-rename-test");
        System.setProperty("opentagger.songrec.state", dir.resolve("etat").toString());
        App.configureTagLibrary();
    }

    @After
    public void tearDown() {
        System.clearProperty("opentagger.songrec.state");
    }

    private static Info info(String artist, String title) { return new Info(artist, title); }

    // ── Même morceau ou autre morceau (cas du test Python test_songrec_identite.py) ──────────────────────────

    @Test
    public void casReels() {
        Info pac = info("2Pac", "Thug Style"), tay = info("Taylor Swift", "Style");
        assertTrue(SongRecRename.autreMorceau(pac, tay));
        assertFalse(SongRecRename.memeMorceau(pac, tay));
        assertTrue(SongRecRename.autreMorceau(info("Sister Sledge", "Let Him Go"), info("2Pac", "Let Them Thangs Go")));
        assertTrue(SongRecRename.autreMorceau(info("Taylor Swift", "Shake It Off"), tay));
    }

    @Test
    public void variantesDuMemeMorceau() {
        Info[][] cas = {
                {info("2Pac", "Can't C Me"), info("2Pac feat. George Clinton", "Can't C Me")},
                {info("113", "113 Fout la Merde"), info("113", "113 fout la m***e (feat. Thomas Bangalter)")},
                {info("Beyonce", "Halo"), info("Beyoncé", "Halo")},
                {info("Kyo - Topic", "Le chemin"), info("Kyo", "Le chemin")},
                {info("The Beatles", "Let It Be - Remastered 2009"), info("Beatles", "Let It Be")},
        };
        for (Info[] c : cas) assertFalse(c[0].artist + " / " + c[1].artist, SongRecRename.autreMorceau(c[0], c[1]));
    }

    @Test
    public void numeroDePiste() {
        Info tay = info("Taylor Swift", "Style");
        Info pac = info("2Pac", "Thug Style");
        pac.track = "14";
        Info same = info("Taylor Swift", "Style");
        same.track = "03";
        assertEquals("", SongRecRename.trackFromOldTags(pac, tay));
        assertEquals("03", SongRecRename.trackFromOldTags(same, tay));
        assertEquals("", SongRecRename.trackFromOldTags(null, tay));
    }

    @Test
    public void rienAComparer() {
        assertFalse(SongRecRename.autreMorceau(info("", ""), info("Taylor Swift", "Style")));
        assertFalse(SongRecRename.autreMorceau(info("Various Artists", "Style"), info("Taylor Swift", "Style")));
    }

    @Test
    public void ratioCommeDifflib() {
        assertEquals(1.0, SongRecRename.ratio("abc", "abc"), 1e-9);
        assertEquals(0.0, SongRecRename.ratio("abc", "xyz"), 1e-9);
        assertEquals(2.0 * 3 / 7, SongRecRename.ratio("abcd", "bcd"), 1e-9);   // difflib : 0.857…
    }

    // ── Noms ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    public void nomsDeDossiers() {
        assertEquals(SongRecRename.folderKey("Jay-Z"), SongRecRename.folderKey("JAY‐Z"));
        assertEquals(SongRecRename.folderKey("Jay-Z"), SongRecRename.folderKey("jay_z"));
        assertEquals(SongRecRename.folderKey("Beyoncé"), SongRecRename.folderKey("Beyonce"));
        assertEquals(SongRecRename.folderKey("Hall and Oates"), SongRecRename.folderKey("Hall & Oates"));
        assertEquals(SongRecRename.folderKey("The Beatles"), SongRecRename.folderKey("Beatles"));
        assertEquals("the", SongRecRename.folderKey("The"));
        assertEquals("David Guetta", SongRecRename.leadArtist("David Guetta & Sia"));
        assertEquals("X", SongRecRename.stripFeaturing("X feat. Y"));
        assertEquals("Earth", SongRecRename.leadArtist("Earth, Wind & Fire"));
        assertTrue(SongRecRename.wellCased("David Guetta"));
        assertTrue(SongRecRename.wellCased("ABBA"));
        assertFalse(SongRecRename.wellCased("david guetta"));
        assertFalse(SongRecRename.wellCased("DAVID GUETTA"));
        assertEquals("AC-DC", SongRecRename.cleanFolderName("AC–DC."));
        assertEquals("a b", SongRecRename.sanitize("a/  b?"));
        assertEquals("../_mybook/A b+c/é.mp3", SongRecRename.unquote("../_mybook/A%20b+c/%C3%A9.mp3"));
        String longStem = "é".repeat(200);
        String fit = SongRecRename.fitFilenameBytes(longStem, ".mp3");
        assertTrue(fit.getBytes(StandardCharsets.UTF_8).length + 4 <= 255);
        assertEquals("mnt_MyBook_itunes_Music.jsonl", SongRecRename.journalName("/mnt/MyBook/itunes/Music"));
        assertTrue(SongRecRename.NOT_AN_ARTIST.matcher("Various Artists").matches());
        assertTrue(SongRecRename.NOT_AN_ARTIST.matcher("CD 2").matches());
    }

    @Test
    public void collaborationChezLePremierArtisteSeulementSIlADejaUneDiscographie() throws Exception {
        Path root = dir.resolve("biblio");
        for (int i = 0; i < 6; i++) touch(root.resolve("David Guetta/Album/" + i + ".mp3"));
        touch(root.resolve("Simon/Single/1.mp3"));
        touch(root.resolve("simon & garfunkel/Bridge/1.mp3"));
        SongRecRename t = tool(Map.of());
        SongRecRename.FolderIndex idx = t.new FolderIndex(root, Set.of());
        assertEquals("David Guetta", idx.artistFolder("David Guetta & Sia"));
        assertEquals("simon & garfunkel", idx.artistFolder("Simon & Garfunkel"));
        assertEquals("David Guetta", idx.artistFolder("DAVID GUETTA feat. Sia"));
        assertEquals("Nouveau", idx.artistFolder("Nouveau"));
    }

    // ── Écriture des tags (-i) ──────────────────────────────────────────────────────────────────────────

    private Path synth(String name, String... codec) throws Exception {
        return synth(3, name, codec);
    }

    private Path synth(int seconds, String name, String... codec) throws Exception {
        Path f = dir.resolve(name);
        Files.createDirectories(f.getParent());
        List<String> cmd = new java.util.ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:beep_factor=4:duration=" + seconds));
        cmd.addAll(List.of(codec));
        cmd.add(f.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg indisponible", p.waitFor() == 0 && Files.isRegularFile(f));
        return f;
    }

    private void oldTwoPac(Path f) throws Exception {
        AudioFile af = AudioFileIO.read(f.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.TITLE, "Thug Style");
        tag.setField(FieldKey.ARTIST, "2Pac");
        tag.setField(FieldKey.ALBUM_ARTIST, "2pac");
        tag.setField(FieldKey.ALBUM_ARTIST_SORT, "2Pac");
        tag.setField(FieldKey.ALBUM, "Until the End of Time");
        tag.setField(FieldKey.TRACK, "14");
        tag.setField(FieldKey.ISRC, "USUM71109243");
        tag.setField(FieldKey.BPM, "96");
        tag.setField(FieldKey.LYRICS, "paroles de 2Pac");
        tag.setField(FieldKey.COMMENT, "rip perso");
        tag.setField(FieldKey.MUSICBRAINZ_RELEASEID, "abc");
        TagWriter.setCustomField(tag, "REPLAYGAIN_TRACK_GAIN", "-6.5 dB");
        af.commit();
    }

    private void autreMorceauEfface(Path f) throws Exception {
        oldTwoPac(f);
        SongRecRename t = tool(Map.of());
        Info nu = info("Taylor Swift", "Style");
        nu.album = "1989";
        nu.year = "2014";
        nu.genre = "Pop";
        assertTrue(t.writeTags(f, nu, false));
        Tag tag = AudioFileIO.read(f.toFile()).getTag();
        assertEquals("Style", tag.getFirst(FieldKey.TITLE));
        assertEquals("Taylor Swift", tag.getFirst(FieldKey.ARTIST));
        assertEquals("1989", tag.getFirst(FieldKey.ALBUM));
        assertEquals("", tag.getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals("", tag.getFirst(FieldKey.ALBUM_ARTIST_SORT));
        assertEquals("", tag.getFirst(FieldKey.LYRICS));
        assertEquals("", tag.getFirst(FieldKey.TRACK));
        assertEquals("", tag.getFirst(FieldKey.ISRC));
        assertEquals("", tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID));
        assertEquals("96", tag.getFirst(FieldKey.BPM));
        assertEquals("rip perso", tag.getFirst(FieldKey.COMMENT));
        assertEquals("-6.5 dB", TagWriter.getCustomField(tag, "REPLAYGAIN_TRACK_GAIN"));
    }

    @Test public void mp3AutreMorceau() throws Exception { autreMorceauEfface(synth("t.mp3", "-c:a", "libmp3lame")); }

    @Test public void flacAutreMorceau() throws Exception { autreMorceauEfface(synth("t.flac", "-c:a", "flac")); }

    @Test public void m4aAutreMorceau() throws Exception { autreMorceauEfface(synth("t.m4a", "-c:a", "aac")); }

    @Test
    public void memeMorceauGardeTout() throws Exception {
        Path f = synth("m.mp3", "-c:a", "libmp3lame");
        oldTwoPac(f);
        assertTrue(tool(Map.of()).writeTags(f, info("2Pac feat. Nate Dogg", "Thug Style"), false));
        Tag tag = AudioFileIO.read(f.toFile()).getTag();
        assertEquals("2pac", tag.getFirst(FieldKey.ALBUM_ARTIST));
        assertEquals("14", tag.getFirst(FieldKey.TRACK));
        assertEquals("paroles de 2Pac", tag.getFirst(FieldKey.LYRICS));
        assertEquals("2Pac feat. Nate Dogg", tag.getFirst(FieldKey.ARTIST));
    }

    // ── Une passe complète, Shazam simulé ──────────────────────────────────────────────────────────────

    private final ByteArrayOutputStream log = new ByteArrayOutputStream();

    private SongRecRename tool(Map<String, Info> answers) {
        SongRecRename.Recognizer fake = f -> {
            Info i = answers.get(f.getName());
            if (i == null) return null;
            Info c = new Info(i.artist, i.title);
            c.album = i.album;
            c.year = i.year;
            return c;
        };
        return new SongRecRename(new PrintStream(log, true, StandardCharsets.UTF_8), fake, null);
    }

    private static void touch(Path p) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[0]);
    }

    @Test
    public void passeComplete() throws Exception {
        Path root = dir.resolve("Music");
        Path a = synth("Music/vrac/piste1.mp3", "-c:a", "libmp3lame");
        Path b = synth("Music/vrac/sans nom.mp3", "-c:a", "libmp3lame");
        Path c = synth("Music/vrac/inconnu.mp3", "-c:a", "libmp3lame");
        Path keep = synth("Music/Freyr/01 Ma chanson.mp3", "-c:a", "libmp3lame");
        Path playlists = dir.resolve("playlists");
        Files.createDirectories(playlists);
        Files.writeString(playlists.resolve("p.m3u8"), "#EXTM3U\n../_mybook/Freyr/01%20Ma%20chanson.mp3\n../_toshiba/autre.mp3\n");
        Info i1 = info("Taylor Swift", "Style");
        i1.album = "1989";
        i1.year = "2014";
        Map<String, Info> answers = new HashMap<>();
        answers.put("piste1.mp3", i1);
        answers.put("sans nom.mp3", info("Adele", "Hello"));
        answers.put("01 Ma chanson.mp3", info("X", "Y"));

        // Simulation : rien ne bouge
        SongRecRename.Options o = SongRecRename.Options.parse(new String[]{"-r", "-i", "-n", "-m", root.toString(),
                "--keep-playlists", playlists.toString(), root.toString()});
        assertEquals(0, tool(answers).run(o));
        assertTrue(Files.exists(a));
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("[dry-run, pas de déplacement]"));

        // Pour de vrai
        o = SongRecRename.Options.parse(new String[]{"-ri", "-m", root.toString(), "--keep-playlists", playlists.toString(), root.toString()});
        assertEquals(0, tool(answers).run(o));
        Path movedA = root.resolve("Taylor Swift/1989 (2014)/Taylor Swift - 1989 - 00 - Style.mp3");
        Path movedB = root.resolve("Adele/Singles/Adele - 00 - Hello.mp3");
        assertTrue(log.toString(StandardCharsets.UTF_8), Files.exists(movedA));
        assertTrue(Files.exists(movedB));
        assertFalse(Files.exists(a));
        assertTrue("non reconnu : laissé", Files.exists(c));
        assertTrue("morceau de playlist : jamais déplacé", Files.exists(keep));
        assertEquals("Style", AudioFileIO.read(movedA.toFile()).getTag().getFirst(FieldKey.TITLE));
        assertFalse("dossier vidé retiré", Files.exists(root.resolve("vrac/piste1.mp3")));

        Path journal = dir.resolve("etat").resolve(SongRecRename.journalName(root.toString()));
        String j = Files.readString(journal);
        assertTrue(j.contains("\"origine\""));
        assertTrue(j.contains("\"echec\":true"));

        // Reprise : rien n'est redemandé
        log.reset();
        assertEquals(0, tool(Map.of()).run(o));
        String second = log.toString(StandardCharsets.UTF_8);
        assertTrue(second, second.contains("↷ déjà traité"));
        assertFalse(second.contains("♪ Taylor Swift"));
    }

    @Test
    public void renommageSimpleSansRacine() throws Exception {
        Path f = synth("simple/01 truc.mp3", "-c:a", "libmp3lame");
        SongRecRename.Options o = SongRecRename.Options.parse(new String[]{f.getParent().toString()});
        assertEquals(0, tool(Map.of("01 truc.mp3", info("Adele", "Hello"))).run(o));
        assertTrue(Files.exists(f.resolveSibling("Adele - Hello.mp3")));
    }

    @Test
    public void doublonsMemeSon() throws Exception {
        assumeTrue("fpcalc indisponible", FpcalcInstaller.resolve() != null);
        Path root = dir.resolve("Lib");
        Path x = synth(40, "Lib/a/x.mp3", "-c:a", "libmp3lame", "-b:a", "128k");
        Path y = synth(40, "Lib/b/y.mp3", "-c:a", "libmp3lame", "-b:a", "320k");
        // même son (même sinus), deux qualités : le 320 k reste, le 128 k part en quarantaine
        SongRecRename t = tool(Map.of("x.mp3", info("Adele", "Hello"), "y.mp3", info("Adele", "Hello")));
        int[] fx = t.fingerprint(x), fy = t.fingerprint(y);
        assumeTrue("empreinte trop courte", fx.length >= 40 && fy.length >= 40);
        assertTrue(SongRecRename.sameSound(fx, fy));
        SongRecRename.Options o = SongRecRename.Options.parse(new String[]{"-r", "-d", root.toString()});
        assertEquals(0, t.run(o));
        String out = log.toString(StandardCharsets.UTF_8);
        assertTrue(out, out.contains("✂ doublon écarté"));
        assertTrue(Files.exists(dir.resolve("_doublons_songrec")) || Files.exists(root.resolve(".doublons")));
    }

    @Test
    public void optionsCommePython() {
        SongRecRename.Options o = SongRecRename.Options.parse(new String[]{"-r", "-i", "-m", "/r", "-d", "--keep-playlists", "/p", "/x"});
        assertTrue(o.recursive && o.tag && o.dedupe);
        assertEquals("/r", o.move);
        assertEquals("/p", o.keepPlaylists);
        assertEquals("/x", o.path);
        assertNull(SongRecRename.Options.parse(new String[]{}).move);
        assertNotNull(SongRecRename.USAGE);
        assertTrue(new File(".").exists());
    }
}
