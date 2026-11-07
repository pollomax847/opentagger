package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Identification par album entier (AlbumFirst) — sans réseau : MusicBrainz est simulé. */
public class AlbumFirstTest {

    private static final String[] TITLES = {"Welcome to New York", "Blank Space", "Style", "Out of the Woods",
            "All You Had to Do Was Stay", "Shake It Off", "I Wish You Would", "Bad Blood", "Wildest Dreams",
            "How You Get the Girl", "This Love", "I Know Places", "Clean"};
    private static final int[] SECS = {212, 231, 231, 235, 193, 219, 207, 211, 220, 247, 250, 195, 271};

    private static MusicBrainzClient.ReleaseTracklist release(String mbid, String album, String albumArtist, boolean compilation,
                                                              String trackArtist, String[] titles, int[] secs) {
        List<MusicBrainzClient.ReleaseTrack> t = new ArrayList<>();
        for (int i = 0; i < titles.length; i++)
            t.add(new MusicBrainzClient.ReleaseTrack(1, i + 1, titles.length, titles[i], trackArtist, "rec-" + mbid + i,
                    secs[i] * 1000, "art-" + trackArtist, "trk-" + mbid + i, ""));
        return new MusicBrainzClient.ReleaseTracklist(mbid, album, albumArtist, "", "2014", "rg-" + mbid, compilation, t,
                "US", "", "Official", "", "", "", "", "Album", "2014", null);
    }

    private static final MusicBrainzClient.ReleaseTracklist TAYLOR = release("taylor", "1989", "Taylor Swift", false, "Taylor Swift", TITLES, SECS);
    /** Même nom, mêmes titres, mêmes durées à 2 s près : la reprise de Ryan Adams. */
    private static final MusicBrainzClient.ReleaseTracklist RYAN = release("ryan", "1989", "Ryan Adams", false, "Ryan Adams", TITLES,
            new int[]{212, 231, 231, 235, 193, 219, 207, 211, 220, 247, 250, 195, 271});

    /** MusicBrainz simulé : la recherche rend les parutions données, la lecture les retrouve par identifiant. */
    private static AlbumFirst.Lookup fake(MusicBrainzClient.ReleaseTracklist... rels) {
        Map<String, MusicBrainzClient.ReleaseTracklist> byId = new HashMap<>();
        for (var r : rels) byId.put(r.releaseMbid(), r);
        return new AlbumFirst.Lookup() {
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) {
                List<MusicBrainzClient.ReleaseRef> out = new ArrayList<>();
                for (var r : rels) out.add(new MusicBrainzClient.ReleaseRef(r.releaseMbid(), r.album(), List.of(r.tracks().size())));
                return out;
            }
            @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String id) { return byId.get(id); }
        };
    }

    private static List<AlbumFirst.Track> taylorFolder(int count) {
        List<AlbumFirst.Track> g = new ArrayList<>();
        for (int i = 0; i < count; i++)
            g.add(new AlbumFirst.Track("f" + i, TITLES[i], "Taylor Swift", "1989", "", "2014", SECS[i], i + 1, 1, "", ""));
        return g;
    }

    @Test
    public void unAlbumEntierEstReconnuDUnCoup() {
        AlbumFirst.Outcome o = AlbumFirst.identify(fake(TAYLOR), taylorFolder(13), "1989");
        assertNotNull(o.why(), o.decision());
        assertEquals("taylor", o.decision().release().releaseMbid());
        assertEquals(13, o.decision().trackById().size());
        TagInfo t = AlbumFirst.toTagInfo(o.decision().release(), o.decision().trackById().get("f2"));
        assertEquals("Style", t.title);
        assertEquals("Taylor Swift", t.albumArtist);
        assertEquals("3", t.track);
        assertEquals(AlbumFirst.SCORE, t.score);
    }

    @Test
    public void laRepriseHomonymeDUnAutreArtisteEstRefusee() {
        // Ryan Adams en premier dans les résultats : refusé par l'artiste, Taylor Swift retenue.
        AlbumFirst.Outcome o = AlbumFirst.identify(fake(RYAN, TAYLOR), taylorFolder(13), "1989");
        assertEquals("taylor", o.decision().release().releaseMbid());
        // Seule la reprise existe : rien n'est décidé.
        assertNull(AlbumFirst.identify(fake(RYAN), taylorFolder(13), "1989").decision());
    }

    @Test
    public void unDossierAuxAlbumsMelangesNEstPasEssaye() {
        List<AlbumFirst.Track> g = new ArrayList<>();
        String[] albums = {"1989", "Reputation", "Lover", "Folklore"};
        for (int i = 0; i < 4; i++)
            g.add(new AlbumFirst.Track("f" + i, TITLES[i], "Taylor Swift", albums[i], "", "", SECS[i], 1, 1, "", ""));
        assertNull(AlbumFirst.hints(g, "Singles"));
        assertNull(AlbumFirst.identify(fake(TAYLOR), g, "Singles").hints());
    }

    @Test
    public void desTitresFauxNeSuffisentPasMemeAvecLesBonnesDurees() {
        // Numéros et durées justes, mais titres sans rapport : pas assez de preuves par le titre.
        List<AlbumFirst.Track> g = new ArrayList<>();
        for (int i = 0; i < 6; i++)
            g.add(new AlbumFirst.Track("f" + i, "Piste " + (i + 1) + " autre chose", "Taylor Swift", "1989", "", "", SECS[i], i + 1, 1, "", ""));
        assertNull(AlbumFirst.identify(fake(TAYLOR), g, "1989").decision());
    }

    @Test
    public void unFichierEtrangerAuDisqueEstLaissePourLaRechercheHabituelle() {
        List<AlbumFirst.Track> g = taylorFolder(5);
        g.add(new AlbumFirst.Track("x", "Thug Style", "2Pac", "1989", "", "", 300, 14, 1, "", ""));
        AlbumFirst.Decision d = AlbumFirst.identify(fake(TAYLOR), g, "1989").decision();
        assertNotNull(d);
        assertEquals(5, d.trackById().size());
        assertNull(d.trackById().get("x"));
    }

    @Test
    public void unRapprochementParLaSeuleDureeNeChangePasLIdentiteDUnFichier() {
        // « Picture Me Rollin' » apparié à « Wonda Why They Call U Bytch » par la seule durée (vrai cas) : écarté.
        List<AlbumFirst.Track> g = taylorFolder(5);
        g.add(new AlbumFirst.Track("x", "Picture Me Rollin'", "Taylor Swift", "1989", "", "", SECS[6], 99, 1, "", ""));
        AlbumFirst.Decision d = AlbumFirst.identify(fake(TAYLOR), g, "1989").decision();
        assertNotNull(d);
        assertNull(d.trackById().get("x"));
        assertEquals(5, d.trackById().size());
    }

    @Test
    public void unFichierSansVraiTitreEstReconnuParSonNumeroEtSaDuree() {
        List<AlbumFirst.Track> g = taylorFolder(5);
        g.add(new AlbumFirst.Track("t7", "Track 07", "Taylor Swift", "1989", "", "", SECS[6], 7, 1, "", ""));
        AlbumFirst.Decision d = AlbumFirst.identify(fake(TAYLOR), g, "1989").decision();
        assertEquals("I Wish You Would", d.trackById().get("t7").title());
        assertTrue(AlbumFirst.usableTitle("1979"));
        assertFalse(AlbumFirst.usableTitle("Piste 3"));
    }

    @Test
    public void tropDeFichiersEtrangersEmpechentLaDecision() {
        List<AlbumFirst.Track> g = taylorFolder(4);
        for (int i = 0; i < 3; i++)
            g.add(new AlbumFirst.Track("x" + i, "Autre chose " + i, "Taylor Swift", "1989", "", "", 400 + i * 20, 20 + i, 1, "", ""));
        assertNull(AlbumFirst.identify(fake(TAYLOR), g, "1989").decision());   // 4 sur 7 < 70 %
    }

    @Test
    public void quelquesVariousArtistsEgaresNeFontPasUneCompilation() {
        // Vrai dossier « 2Pac / All Eyez On Me » : « 2Pac »/« 2pac » majoritaires, deux « Various Artists » égarés.
        String[] aa = {"2pac", "2Pac", "2Pac", "2Pac", "Various Artists", "2Pac", "2pac", "Various Artists"};
        List<AlbumFirst.Track> g = new ArrayList<>();
        for (int i = 0; i < aa.length; i++)
            g.add(new AlbumFirst.Track("p" + i, "Titre " + i, "2Pac feat. Invité " + i, "All Eyez On Me", aa[i], "1996", 200 + i, i + 1, 1, "", ""));
        AlbumFirst.Hints h = AlbumFirst.hints(g, "All Eyez On Me");
        assertFalse(h.compilation());
        assertEquals("2pac", h.artist().toLowerCase());
    }

    @Test
    public void compilation() {
        String[] titles = {"Freed from Desire", "Be My Lover", "What Is Love", "Rhythm Is a Dancer"};
        String[] artists = {"Gala", "La Bouche", "Haddaway", "Snap!"};
        int[] secs = {210, 241, 270, 333};
        List<MusicBrainzClient.ReleaseTrack> tr = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            tr.add(new MusicBrainzClient.ReleaseTrack(1, i + 1, 4, titles[i], artists[i], "rec" + i, secs[i] * 1000, "", "", ""));
        var va = new MusicBrainzClient.ReleaseTracklist("va", "Dance Machine 90", "Various Artists", "", "1997", "", true, tr,
                "FR", "", "Official", "", "", "", "", "Compilation", "1997", null);
        var sameNameSoloAlbum = new MusicBrainzClient.ReleaseTracklist("solo", "Dance Machine 90", "Gala", "", "1997", "", false, tr,
                "FR", "", "Official", "", "", "", "", "Album", "1997", null);
        List<AlbumFirst.Track> g = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            g.add(new AlbumFirst.Track("c" + i, titles[i], artists[i], "Dance Machine 90", "", "", secs[i], i + 1, 1, "", ""));
        AlbumFirst.Hints h = AlbumFirst.hints(g, "");
        assertTrue(h.compilation());
        assertEquals("va", AlbumFirst.identify(fake(sameNameSoloAlbum, va), g, "").decision().release().releaseMbid());
        assertNull(AlbumFirst.identify(fake(sameNameSoloAlbum), g, "").decision());
    }

    @Test
    public void unAlbumDUnArtisteNEstPasExpliqueParUneCompilationHomonyme() {
        var va = release("va", "1989", "Various Artists", true, "Taylor Swift", TITLES, SECS);
        assertNull(AlbumFirst.identify(fake(va), taylorFolder(13), "1989").decision());
    }

    @Test
    public void extractionSansTagsDAlbumUtiliseLeNomDuDossier() {
        List<AlbumFirst.Track> g = new ArrayList<>();
        for (int i = 0; i < 5; i++)
            g.add(new AlbumFirst.Track("f" + i, TITLES[i], "Taylor Swift", "", "", "", SECS[i], i + 1, 1, "", ""));
        AlbumFirst.Hints h = AlbumFirst.hints(g, "1989");
        assertEquals("1989", h.album());
        assertFalse(h.compilation());
        assertNotNull(AlbumFirst.identify(fake(TAYLOR), g, "1989").decision());
        assertNull(AlbumFirst.hints(g, "Downloads"));
    }

    @Test
    public void nomsDAlbum() {
        assertTrue(AlbumFirst.albumNameAgrees("1989", "1989 (Deluxe)"));
        assertTrue(AlbumFirst.albumNameAgrees("Footloose_ Original Soundtrack of the Pa", "Footloose: Original Soundtrack of the Paramount Motion Picture"));
        assertFalse(AlbumFirst.albumNameAgrees("1989", "Reputation"));
        assertEquals("2Pac", AlbumFirst.leadArtist("2Pac feat. George Clinton"));
        assertEquals("Snap!", AlbumFirst.leadArtist("Snap! (ft. Niki Haris)"));
    }
}
