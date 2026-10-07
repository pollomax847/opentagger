package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class GnuDbClientTest {

    private static CdRipper.Toc nevermind() {
        int[] off = {150, 22767, 41887, 58317, 72102, 91375, 104652, 115380, 132165, 143932, 159870, 174597};
        int leadOut = 267257;
        List<CdRipper.Track> tracks = new ArrayList<>();
        for (int i = 0; i < off.length; i++)
            tracks.add(new CdRipper.Track(i + 1, (i + 1 < off.length ? off[i + 1] : leadOut) - off[i]));
        return new CdRipper.Toc(tracks);
    }

    @Test public void cddbDiscIdIsEightHexDigitsAndMatchesTheLiveServerFamily() {
        // Valeur vérifiée contre le serveur GnuDB (7 oct. 2026) : les fiches « Nirvana / Nevermind » renvoyées portent des identifiants
        // voisins (a70de98b, a70de989…) — même durée utile et même nombre de pistes, seule la somme des chiffres diffère selon la pression.
        assertEquals("a70de90c", nevermind().cddbDiscId());
    }

    @Test public void offsetsStartAfterTheTwoSecondLeadIn() {
        int[] o = nevermind().cddbOffsets();
        assertEquals(12, o.length);
        assertEquals(150, o[0]);
        assertEquals(22767, o[1]);
        assertEquals(3563, nevermind().cddbTotalSeconds());
    }

    @Test public void helloUsesTheEmailSplitInUserAndHost() {
        assertEquals("paul+example.com+OpenTagger+0.9.39", GnuDbClient.helloParam("paul@example.com", "0.9.39"));
    }

    @Test public void anEmptyOrBrokenEmailFallsBackToTheDefault() {
        assertEquals("opentagger+gnudb.org", GnuDbClient.helloIdentity(""));
        assertEquals("opentagger+gnudb.org", GnuDbClient.helloIdentity("pas un mail"));
        assertEquals("opentagger+gnudb.org", GnuDbClient.helloIdentity("a@b@c"));
        assertEquals("opentagger+gnudb.org", GnuDbClient.helloIdentity(null));
    }

    @Test public void queryCommandListsOffsetsThenSeconds() {
        assertEquals("cddb query abcd1234 3 150 20000 40000 2800", GnuDbClient.queryCommand("abcd1234", new int[]{150, 20000, 40000}, 2800));
    }

    @Test public void parsesASingleExactMatch() {
        List<GnuDbClient.Match> m = GnuDbClient.parseQuery("200 rock 9d09330c Nirvana / Nevermind\r\n");
        assertEquals(1, m.size());
        assertEquals("rock", m.get(0).category());
        assertEquals("9d09330c", m.get(0).discId());
        assertEquals("Nirvana / Nevermind", m.get(0).title());
        assertTrue(m.get(0).exact());
    }

    @Test public void parsesAListOfMatchesUntilTheDot() {
        List<GnuDbClient.Match> m = GnuDbClient.parseQuery("211 Found inexact matches, list follows (until terminating `.`)\n"
                + "data a70de98b Nirvana / Nevermind\nrock a70de989 Nirvana / Nevermind [Deluxe]\n.\nignored x y\n");
        assertEquals(2, m.size());
        assertEquals("Nirvana / Nevermind [Deluxe]", m.get(1).title());
        assertEquals(false, m.get(0).exact());
    }

    @Test public void noMatchOrErrorGivesAnEmptyList() {
        assertTrue(GnuDbClient.parseQuery("202 No match found\n").isEmpty());
        assertTrue(GnuDbClient.parseQuery("500 Unknown application\n").isEmpty());
        assertTrue(GnuDbClient.parseQuery(null).isEmpty());
    }

    private static final String XMCD = "210 rock a70de98b CD database entry follows (until terminating `.`)\n"
            + "# xmcd\nDISCID=a70de98b\nDTITLE=Nirvana / Nevermind\nDYEAR=1991\nDGENRE=Rock\n"
            + "TTITLE0=Smells Like Teen\nTTITLE0=Spirit\nTTITLE1=In Bloom\nEXTD=\n.\n";

    @Test public void parsesAnXmcdEntryIncludingTitlesSplitOverSeveralLines() {
        GnuDbClient.Disc d = GnuDbClient.parseRead("rock", "a70de98b", XMCD);
        assertNotNull(d);
        assertEquals("Nirvana", d.artist());
        assertEquals("Nevermind", d.album());
        assertEquals("1991", d.year());
        assertEquals("Rock", d.genre());
        assertEquals(List.of("Smells Like Teen" + "Spirit", "In Bloom"), d.tracks());
    }

    @Test public void aTracklistNeedsTheSameNumberOfTracksAsTheDisc() {
        GnuDbClient.Disc d = GnuDbClient.parseRead("rock", "a70de98b", XMCD); // 2 pistes, le disque en a 12
        assertNull(GnuDbClient.toTracklist(d, nevermind()));
    }

    @Test public void variousArtistsDiscsSplitArtistAndTitlePerTrack() {
        List<CdRipper.Track> two = List.of(new CdRipper.Track(1, 15000), new CdRipper.Track(2, 16500));
        GnuDbClient.Disc d = new GnuDbClient.Disc("misc", "11111111", "Various", "Hits", "2001", "Pop",
                List.of("Abba / Waterloo", "Queen / Bohemian Rhapsody"));
        MusicBrainzClient.ReleaseTracklist t = GnuDbClient.toTracklist(d, new CdRipper.Toc(two));
        assertNotNull(t);
        assertTrue(t.isCompilation());
        assertEquals("Abba", t.tracks().get(0).artist());
        assertEquals("Waterloo", t.tracks().get(0).title());
        assertEquals(200_000, t.tracks().get(0).lengthMs());
    }
}
