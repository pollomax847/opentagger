package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Discogs en 2e source pour l'album entier (DiscogsClient.parseRelease + AlbumFirst) — sans réseau. */
public class DiscogsAlbumTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String CD_RELEASE = """
            {"id": 4242, "title": "Les Princes De La Ville", "year": 2000, "country": "France", "master_id": 99,
             "uri": "https://www.discogs.com/release/4242",
             "artists": [{"name": "113", "anv": "", "join": ""}], "artists_sort": "113",
             "labels": [{"name": "Small (2)", "catno": "SMA 498112 2"}],
             "identifiers": [{"type": "Barcode", "value": "5 099749 811227"}],
             "formats": [{"name": "CD", "qty": "1", "descriptions": ["Album"]}],
             "styles": ["Gangsta", "Conscious"],
             "tracklist": [
               {"position": "1", "type_": "track", "title": "Intro", "duration": "1:12"},
               {"position": "2", "type_": "track", "title": "Les Princes De La Ville", "duration": "4:31"},
               {"position": "3", "type_": "track", "title": "Ouais Gros", "duration": "4:05",
                "artists": [{"name": "113", "join": "Feat."}, {"name": "Mokobé", "join": ""}]},
               {"position": "4", "type_": "track", "title": "Tonton Du Bled", "duration": "4:23"},
               {"position": "5", "type_": "track", "title": "Jackpotes 2000", "duration": ""}
             ]}""";

    private static final String DOUBLE_CD_VA = """
            {"id": 77, "title": "NRJ Hits Été 2021", "year": 2021,
             "artists": [{"name": "Various", "join": ""}],
             "formats": [{"name": "CD", "qty": "2", "descriptions": ["Compilation"]}],
             "tracklist": [
               {"position": "", "type_": "heading", "title": "CD 1"},
               {"position": "1-1", "type_": "track", "title": "Titre A", "duration": "3:00", "artists": [{"name": "Artiste A (2)", "join": ""}]},
               {"position": "1-2", "type_": "track", "title": "Titre B", "duration": "3:10", "artists": [{"name": "Artiste B", "join": ""}]},
               {"position": "", "type_": "heading", "title": "CD 2"},
               {"position": "2-1", "type_": "track", "title": "Titre C", "duration": "3:20", "artists": [{"name": "Artiste C*", "join": ""}]}
             ]}""";

    private static final String VINYL = """
            {"id": 5, "title": "Face A Face B", "artists": [{"name": "Groupe", "join": ""}],
             "formats": [{"name": "Vinyl", "descriptions": ["LP", "Album"]}],
             "tracklist": [
               {"position": "A1", "type_": "track", "title": "Un"}, {"position": "A2", "type_": "track", "title": "Deux"},
               {"position": "B1", "type_": "index", "title": "Suite", "sub_tracks": [
                  {"position": "B1a", "type_": "track", "title": "Mouvement I"}, {"position": "B1b", "type_": "track", "title": "Mouvement II"}]}
             ]}""";

    @Test
    public void cdSimple() throws Exception {
        MusicBrainzClient.ReleaseTracklist tl = DiscogsClient.parseRelease(M.readTree(CD_RELEASE), "Various Artists");
        assertNotNull(tl);
        assertEquals("", tl.releaseMbid());
        assertEquals("Les Princes De La Ville", tl.album());
        assertEquals("113", tl.albumArtist());
        assertEquals("2000", tl.year());
        assertEquals("Small", tl.label());
        assertEquals("SMA 498112 2", tl.catalogNo());
        assertEquals("5099749811227", tl.barcode());
        assertFalse(tl.isCompilation());
        assertEquals(5, tl.tracks().size());
        MusicBrainzClient.ReleaseTrack t3 = tl.tracks().get(2);
        assertEquals(0, t3.disc());
        assertEquals(3, t3.trackNo());
        assertEquals(5, t3.trackTotal());
        assertEquals("113 Feat. Mokobé", t3.artist());
        assertEquals(245_000, t3.lengthMs());
        assertEquals(0, tl.tracks().get(4).lengthMs());
        assertEquals("113", tl.tracks().get(0).artist());
        assertEquals("4242", tl.releaseMeta().discogsId);
        assertTrue(AlbumFirst.fromDiscogs(tl));
    }

    @Test
    public void doubleCdCompilation() throws Exception {
        MusicBrainzClient.ReleaseTracklist tl = DiscogsClient.parseRelease(M.readTree(DOUBLE_CD_VA), "Various Artists");
        assertEquals("Various Artists", tl.albumArtist());
        assertTrue(tl.isCompilation());
        assertEquals(3, tl.tracks().size());
        assertEquals(1, tl.tracks().get(1).disc());
        assertEquals(2, tl.tracks().get(1).trackNo());
        assertEquals(2, tl.tracks().get(1).trackTotal());
        assertEquals(2, tl.tracks().get(2).disc());
        assertEquals(1, tl.tracks().get(2).trackTotal());
        assertEquals("Artiste A", tl.tracks().get(0).artist());
        assertEquals("Artiste C", tl.tracks().get(2).artist());
    }

    @Test
    public void vinyleNumeroteDansLOrdre() throws Exception {
        MusicBrainzClient.ReleaseTracklist tl = DiscogsClient.parseRelease(M.readTree(VINYL), "Various Artists");
        assertEquals(4, tl.tracks().size());
        assertEquals(3, tl.tracks().get(2).trackNo());
        assertEquals("Suite: Mouvement I", tl.tracks().get(2).title());
        assertEquals(4, tl.tracks().get(3).trackTotal());
    }

    @Test
    public void traductionsEtVariantesDeNomRetirees() throws Exception {
        String jp = """
                {"id": 9, "title": "25", "artists": [{"name": "Adele", "anv": "アデル = Adele", "join": ""}],
                 "formats": [{"name": "CD", "descriptions": ["Album"]}],
                 "tracklist": [{"position": "1", "type_": "track", "title": "Hello = こんにちは", "duration": "4:55"}]}""";
        MusicBrainzClient.ReleaseTracklist tl = DiscogsClient.parseRelease(M.readTree(jp), "Various Artists");
        assertEquals("Adele", tl.albumArtist());
        assertEquals("Hello", tl.tracks().get(0).title());
        assertEquals("Adele", tl.tracks().get(0).artist());
        assertEquals("Ça = Ça", DiscogsClient.withoutTranslation("Ça = Ça"));
    }

    @Test
    public void durees() {
        assertEquals(225_000, DiscogsClient.durationMs("3:45"));
        assertEquals(3_723_000, DiscogsClient.durationMs("1:02:03"));
        assertEquals(0, DiscogsClient.durationMs(""));
        assertEquals(0, DiscogsClient.durationMs("?"));
    }

    // ── Discogs en 2e source dans AlbumFirst ────────────────────────────────────────────────────

    private static List<AlbumFirst.Track> folder113() {
        String[] titles = {"Intro", "Les Princes De La Ville", "Ouais Gros", "Tonton Du Bled", "Jackpotes 2000"};
        int[] secs = {72, 271, 245, 263, 230};
        List<AlbumFirst.Track> g = new ArrayList<>();
        for (int i = 0; i < titles.length; i++)
            g.add(new AlbumFirst.Track("f" + i, titles[i], "113", "Les Princes de la ville", "113", "2000", secs[i], i + 1, 0,
                    "", i == 0 ? "mbid-tag-qui-ne-doit-pas-aller-a-discogs" : ""));
        return g;
    }

    /** MusicBrainz qui ne connaît rien. */
    private static final AlbumFirst.Lookup EMPTY_MB = new AlbumFirst.Lookup() {
        @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) { return List.of(); }
        @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String id) { return null; }
    };

    private static AlbumFirst.Lookup fakeDiscogs(String json, List<String> asked) {
        return new AlbumFirst.Lookup() {
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) {
                return List.of(new MusicBrainzClient.ReleaseRef("discogs:4242", "Les Princes De La Ville", null));
            }
            @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String id) throws Exception {
                asked.add(id);
                return "discogs:4242".equals(id) ? DiscogsClient.parseRelease(M.readTree(json), "Various Artists") : null;
            }
            @Override public boolean usesTaggedReleaseIds() { return false; }
        };
    }

    @Test
    public void discogsPrendLeRelaisQuandMusicBrainzNeSaitPas() {
        List<String> asked = new ArrayList<>();
        AlbumFirst.Outcome o = AlbumFirst.identify(EMPTY_MB, fakeDiscogs(CD_RELEASE, asked), folder113(), "Les Princes de la ville");
        assertNotNull(o.why(), o.decision());
        assertEquals(List.of("discogs:4242"), asked);   // l'identifiant MusicBrainz des tags n'est jamais demandé à Discogs
        TagInfo t = AlbumFirst.toTagInfo(o.decision().release(), o.decision().trackById().get("f2"));
        assertEquals("Ouais Gros", t.title);
        assertEquals("113 Feat. Mokobé", t.artist);
        assertEquals("113", t.albumArtist);
        assertEquals("", t.releaseMbid);
        assertEquals("", t.recordingMbid);
        assertEquals("4242", t.discogsId);
        assertEquals("Small", t.label);
        assertEquals(AlbumFirst.DISCOGS_SCORE, t.score);
    }

    @Test
    public void discogsPasDemandeQuandMusicBrainzADecide() {
        // MusicBrainz connaît l'album : Discogs n'est même pas interrogé.
        List<String> asked = new ArrayList<>();
        MusicBrainzClient.ReleaseTracklist mbRel = DiscogsClient.parseRelease(readQuiet(CD_RELEASE), "Various Artists");
        MusicBrainzClient.ReleaseTracklist asMb = new MusicBrainzClient.ReleaseTracklist("mb-113", mbRel.album(), mbRel.albumArtist(),
                "", "2000", "rg", false, mbRel.tracks(), "", "", "", "", "", "", "", "", "", null);
        AlbumFirst.Lookup mb = new AlbumFirst.Lookup() {
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) {
                return List.of(new MusicBrainzClient.ReleaseRef("mb-113", asMb.album(), List.of(5)));
            }
            @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String id) { return "mb-113".equals(id) ? asMb : null; }
        };
        AlbumFirst.Outcome o = AlbumFirst.identify(mb, fakeDiscogs(CD_RELEASE, asked), folder113(), "Les Princes de la ville");
        assertNotNull(o.decision());
        assertEquals("mb-113", o.decision().release().releaseMbid());
        assertTrue(asked.isEmpty());
    }

    @Test
    public void laDeuxiemeParutionDiscogsEstAussiExaminee() throws Exception {
        // Avant le correctif, toutes les parutions Discogs avaient la même clé (identifiant MusicBrainz vide) : seule la
        // première était examinée. Ici la première est le single (4 pistes), la seconde l'album.
        String single = """
                {"id": 1, "title": "Les Princes De La Ville", "artists": [{"name": "113", "join": ""}],
                 "formats": [{"name": "CD", "descriptions": ["Single"]}],
                 "tracklist": [{"position": "1", "type_": "track", "title": "Les Princes De La Ville", "duration": "4:31"},
                               {"position": "2", "type_": "track", "title": "Instrumental", "duration": "4:31"}]}""";
        AlbumFirst.Lookup dc = new AlbumFirst.Lookup() {
            @Override public List<MusicBrainzClient.ReleaseRef> searchReleases(String album, String artist, int limit) {
                return List.of(new MusicBrainzClient.ReleaseRef("discogs:1", "Les Princes De La Ville", null),
                        new MusicBrainzClient.ReleaseRef("discogs:4242", "Les Princes De La Ville", null));
            }
            @Override public MusicBrainzClient.ReleaseTracklist lookupRelease(String id) throws Exception {
                return DiscogsClient.parseRelease(M.readTree("discogs:1".equals(id) ? single : CD_RELEASE), "Various Artists");
            }
            @Override public boolean usesTaggedReleaseIds() { return false; }
        };
        AlbumFirst.Outcome o = AlbumFirst.identify(EMPTY_MB, dc, folder113(), "Les Princes de la ville");
        assertNotNull(o.why(), o.decision());
        assertEquals("4242", o.decision().release().releaseMeta().discogsId);
    }

    @Test
    public void memesGardeFousPourDiscogs() {
        // Discogs rend un album homonyme d'un AUTRE artiste : refusé comme pour MusicBrainz.
        String other = CD_RELEASE.replace("\"name\": \"113\", \"anv\": \"\"", "\"name\": \"Autre Groupe\", \"anv\": \"\"");
        AlbumFirst.Outcome o = AlbumFirst.identify(EMPTY_MB, fakeDiscogs(other, new ArrayList<>()), folder113(), "Les Princes de la ville");
        assertNull(o.decision());
        assertTrue(o.why(), o.why().contains("Discogs"));
    }

    private static com.fasterxml.jackson.databind.JsonNode readQuiet(String json) {
        try { return M.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
