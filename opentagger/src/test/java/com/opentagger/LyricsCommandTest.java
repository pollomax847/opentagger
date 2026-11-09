package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.junit.Before;
import org.junit.Test;

/** « opentagger --paroles » : seulement les paroles, reprise sans refaire les recherches, annulation. Jamais de réseau. */
public class LyricsCommandTest {

    private static final String PLAIN = "Première ligne\nDeuxième ligne";
    private static final String SYNCED = "[00:01.00]Première ligne\n[00:02.50]Deuxième ligne";

    private Path dir, music, state;
    private final AtomicInteger calls = new AtomicInteger();
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("paroles-test");
        music = dir.resolve("musique");
        state = dir.resolve("etat");
        App.configureTagLibrary();
    }

    private LyricsCommand.Finder found(String plain, String synced) {
        return (artist, title, album, duration, wantPlain) -> {
            calls.incrementAndGet();
            return new LyricsClient.Found(plain, synced, "test", false, "");
        };
    }

    private int run(LyricsCommand.Finder finder, String... args) {
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        return LyricsCommand.run(args, out, finder, state, 2, false);
    }

    private Path synth(String name, String... codec) throws Exception {
        Path f = music.resolve(name);
        Files.createDirectories(f.getParent());
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi", "-i",
                "sine=frequency=440:beep_factor=4:duration=3"));
        cmd.addAll(List.of(codec));
        cmd.add(f.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg indisponible", p.waitFor() == 0 && Files.isRegularFile(f));
        return f;
    }

    private Path tagged(String name, String artist, String title, String... codec) throws Exception {
        Path f = synth(name, codec);
        AudioFile af = AudioFileIO.read(f.toFile());
        Tag tag = af.getTagOrCreateAndSetDefault();
        tag.setField(FieldKey.ARTIST, artist);
        tag.setField(FieldKey.TITLE, title);
        tag.setField(FieldKey.ALBUM, "Album test");
        tag.setField(FieldKey.TRACK, "7");
        af.commit();
        return f;
    }

    private static String lyricsOf(Path f) { return TagWriter.readLyrics(f.toFile()); }

    private static String tagOf(Path f, FieldKey k) throws Exception {
        return AudioFileIO.read(f.toFile()).getTag().getFirst(k);
    }

    @Test
    public void ajouteSeulementLesParoles() throws Exception {
        Path mp3 = tagged("A/a.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        Path flac = tagged("A/b.flac", "Adele", "Skyfall", "-c:a", "flac");
        assertEquals(0, run(found(PLAIN, SYNCED), music.toString()));

        for (Path f : List.of(mp3, flac)) {
            assertEquals(PLAIN, lyricsOf(f).replace("\r\n", "\n").trim());
            assertEquals("7", tagOf(f, FieldKey.TRACK));
            assertEquals("Album test", tagOf(f, FieldKey.ALBUM));
            Path lrc = LyricsCommand.lrcOf(f);
            assertTrue(Files.isRegularFile(lrc));
            assertEquals(SYNCED + "\n", Files.readString(lrc));
        }
        assertEquals(2, calls.get());
        List<String> lines = Files.readAllLines(state.resolve("journal.jsonl"));
        assertEquals(2, lines.size());
        assertEquals("ecrit", new ObjectMapper().readTree(lines.get(0)).path("r").asText());
    }

    @Test
    public void repriseSansRefaireLesRecherches() throws Exception {
        tagged("a.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        tagged("b.mp3", "Inconnu", "Rien", "-c:a", "libmp3lame");
        LyricsCommand.Finder half = (artist, title, album, d, wantPlain) -> {
            calls.incrementAndGet();
            return artist.equals("Adele") ? new LyricsClient.Found(PLAIN, "", "test", false, "")
                                          : new LyricsClient.Found("", "", "", false, "");
        };
        run(half, music.toString());
        assertEquals(2, calls.get());
        run(half, music.toString());
        assertEquals("ni les trouvées ni les « pas trouvées » ne sont recherchées une 2e fois", 2, calls.get());
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("2 déjà cherchés"));
    }

    @Test
    public void erreurReseauRetenteeLaFoisSuivante() throws Exception {
        Path f = tagged("a.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        run((a, t, al, d, w) -> { calls.incrementAndGet(); return new LyricsClient.Found("", "", "", false, "délai dépassé"); },
            music.toString());
        assertEquals("", lyricsOf(f));
        run(found(PLAIN, ""), music.toString());
        assertEquals(2, calls.get());
        assertEquals(PLAIN, lyricsOf(f).trim());
    }

    @Test
    public void dejaLaSansTagsEtFormatsRefuses() throws Exception {
        Path deja = tagged("deja.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        AudioFile af = AudioFileIO.read(deja.toFile());
        af.getTag().setField(FieldKey.LYRICS, "mes paroles");
        af.commit();
        Files.writeString(LyricsCommand.lrcOf(deja), "[00:00.00]mes paroles\n");
        synth("sanstags.mp3", "-c:a", "libmp3lame");
        synth("x.opus", "-c:a", "libopus");
        run(found(PLAIN, SYNCED), music.toString());
        assertEquals(0, calls.get());
        assertEquals("mes paroles", lyricsOf(deja));
        assertEquals("[00:00.00]mes paroles\n", Files.readString(LyricsCommand.lrcOf(deja)));
        assertFalse(Files.exists(state.resolve("journal.jsonl")));
    }

    @Test
    public void parolesDejaDansLeFichierSeulementLeLrc() throws Exception {
        Path f = tagged("a.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        AudioFile af = AudioFileIO.read(f.toFile());
        af.getTag().setField(FieldKey.LYRICS, "mes paroles");
        af.commit();
        run(found(PLAIN, SYNCED), music.toString());
        assertEquals("les paroles déjà là ne sont jamais remplacées", "mes paroles", lyricsOf(f));
        assertEquals(SYNCED + "\n", Files.readString(LyricsCommand.lrcOf(f)));
    }

    @Test
    public void simulationNeChangeRien() throws Exception {
        Path f = tagged("a.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        long before = Files.getLastModifiedTime(f).toMillis();
        run(found(PLAIN, SYNCED), "--simulation", music.toString());
        assertEquals(1, calls.get());
        assertEquals("", lyricsOf(f));
        assertFalse(Files.exists(LyricsCommand.lrcOf(f)));
        assertFalse(Files.exists(state.resolve("journal.jsonl")));
        assertEquals(before, Files.getLastModifiedTime(f).toMillis());
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Adele — Hello : paroles + .lrc synchronisé"));
    }

    @Test
    public void m4aGardeSonSon() throws Exception {
        Path f = tagged("a.m4a", "Adele", "Hello", "-c:a", "aac");
        TagSnapshot.Layout l = TagSnapshot.layout(f);
        assumeTrue(l != null);
        String sound = TagSnapshot.audioHash(f, l);
        run(found(PLAIN, ""), music.toString());
        assertEquals(PLAIN, lyricsOf(f).replace("\r\n", "\n").replace('\r', '\n').trim());
        TagSnapshot.Layout after = TagSnapshot.layout(f);
        assertEquals(sound, TagSnapshot.audioHash(f, after));
        assertEquals("7", tagOf(f, FieldKey.TRACK));
    }

    @Test
    public void annulerRetireCeQuiAEteAjoute() throws Exception {
        Path a = tagged("a.mp3", "Adele", "Hello", "-c:a", "libmp3lame");
        Path b = tagged("b.flac", "Adele", "Skyfall", "-c:a", "flac");
        run(found(PLAIN, SYNCED), music.toString());
        // .lrc de b modifié à la main depuis : il reste
        Files.writeString(LyricsCommand.lrcOf(b), "[00:00.00]corrigé à la main\n");

        run(found(PLAIN, SYNCED), "--annuler", "--simulation", music.toString());
        assertEquals("la simulation de l'annulation ne retire rien", PLAIN, lyricsOf(a).trim());

        run(found(PLAIN, SYNCED), "--annuler", music.toString());
        assertEquals("", lyricsOf(a));
        assertEquals("", lyricsOf(b));
        assertFalse(Files.exists(LyricsCommand.lrcOf(a)));
        assertEquals("[00:00.00]corrigé à la main\n", Files.readString(LyricsCommand.lrcOf(b)));
        assertEquals("Hello", tagOf(a, FieldKey.TITLE));
        assertEquals("7", tagOf(a, FieldKey.TRACK));

        // Après annulation, une nouvelle passe peut les remettre.
        run(found(PLAIN, SYNCED), music.toString());
        assertEquals(PLAIN, lyricsOf(a).trim());
    }

    @Test
    public void limiteArreteApresNRecherches() throws Exception {
        for (int i = 0; i < 5; i++) tagged("f" + i + ".mp3", "Artiste", "Titre " + i, "-c:a", "libmp3lame");
        run(found(PLAIN, ""), "--limite", "2", music.toString());
        assertEquals(2, calls.get());
        run(found(PLAIN, ""), music.toString());
        assertEquals(5, calls.get());
    }

    // ── Choix parmi les résultats de lrclib ─────────────────────────────────────────────────────────────────

    private static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return new ObjectMapper().readTree(s.replace('\'', '"'));
    }

    @Test
    public void choixDeLaVersionDeMemeDuree() throws Exception {
        var results = json("[{'trackName':'Hello','duration':240,'plainLyrics':'live','syncedLyrics':'[00:01.00]live'},"
                + "{'trackName':'Hello','duration':296,'plainLyrics':'studio','syncedLyrics':''},"
                + "{'trackName':'Hello','duration':295,'plainLyrics':'studio2','syncedLyrics':'[00:01.00]studio2'}]");
        LyricsClient.Found f = LyricsClient.pick(results, "Hello", 296);
        assertEquals("la version synchronisée de même durée passe avant", "studio2", f.plain());
        assertEquals("[00:01.00]studio2", f.synced());
    }

    @Test
    public void autreDureeTexteSimpleSeulement() throws Exception {
        var results = json("[{'trackName':'Hello (Live)','duration':240,'plainLyrics':'live','syncedLyrics':'[00:01.00]live'}]");
        LyricsClient.Found f = LyricsClient.pick(results, "Hello", 296);
        assertEquals("live", f.plain());
        assertEquals("jamais de paroles synchronisées d'une autre durée", "", f.synced());
        assertTrue(LyricsClient.pick(json("[{'trackName':'Goodbye','duration':100,'plainLyrics':'x'}]"), "Hello", 296).isEmpty());
    }

    @Test
    public void remixOuLiveSansParolesSynchroniseesDeLOriginal() throws Exception {
        assertTrue(LyricsClient.sameVersion("Hello", "Hello"));
        assertTrue(LyricsClient.sameVersion("Hello (Anevo Remix)", "Hello - Anevo Remix"));
        assertFalse(LyricsClient.sameVersion("Rolling In The Deep (Lenny B. Remix)", "Rolling in the Deep"));
        assertFalse(LyricsClient.sameVersion("Hometown Glory (Live)", "Hometown Glory"));
        var results = json("[{'trackName':'Rolling in the Deep','duration':228,'plainLyrics':'texte','syncedLyrics':'[00:01.00]texte'}]");
        LyricsClient.Found f = LyricsClient.pick(results, "Rolling In The Deep (Lenny B. Remix)", 229);
        assertEquals("texte", f.plain());
        assertEquals("", f.synced());
    }
}
