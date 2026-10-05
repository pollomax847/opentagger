package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentagger.AlbumMatcher.Item;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AlbumMatcherTest {

    private static final String[] TITLES = {
        "Intro", "Soleil levant", "La route", "Mon ami", "Nuit blanche", "Demain peut-être",
        "Les gens", "Sous la pluie", "Rien à dire", "Retour"
    };
    private static final int[] SECS = {62, 201, 187, 233, 240, 198, 215, 176, 224, 310};

    private static MusicBrainzClient.ReleaseTracklist release(String mbid, String album, String artist, String year, boolean compilation,
                                                              String status, int extra, String... titles) {
        List<MusicBrainzClient.ReleaseTrack> t = new ArrayList<>();
        int n = titles.length + extra;
        for (int i = 0; i < titles.length; i++)
            t.add(new MusicBrainzClient.ReleaseTrack(1, i + 1, n, titles[i], artist, "rec-" + titles[i], SECS[i % SECS.length] * 1000, "", "", ""));
        for (int e = 0; e < extra; e++)
            t.add(new MusicBrainzClient.ReleaseTrack(1, titles.length + e + 1, n, "Bonus " + (e + 1), artist, "rec-bonus-" + e, (150 + e) * 1000, "", "", ""));
        return new MusicBrainzClient.ReleaseTracklist(mbid, album, artist, "", year, "", compilation, t,
                "", "", status, "", "", "", "", "", year, null);
    }

    private static MusicBrainzClient.ReleaseTracklist standard() {
        return release("std", "Les gens", "Artiste", "2010", false, "Official", 0, TITLES);
    }

    private static List<Item> folder(String... titlesToUse) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < titlesToUse.length; i++) {
            int idx = indexOf(titlesToUse[i]);
            items.add(new Item("f" + i, titlesToUse[i], "Artiste", SECS[idx], idx + 1, 1, ""));
        }
        return items;
    }

    private static int indexOf(String title) {
        for (int i = 0; i < TITLES.length; i++) if (TITLES[i].equals(title)) return i;
        throw new IllegalArgumentException(title);
    }

    @Test
    public void aReleaseThatExplainsEveryFileIsChosen() {
        var m = AlbumMatcher.best(folder(TITLES), List.of(standard()), "Les gens", "2010");
        assertNotNull(m);
        assertEquals("std", m.release().releaseMbid());
        assertEquals(10, m.trackByItemId().size());
    }

    @Test
    public void aPopularReleaseThatLeavesOneFileUnexplainedIsRejected() {
        // une autre release du même album qui n'a que 9 des 10 titres : pas acceptable, même si elle ressemble
        String[] nine = java.util.Arrays.copyOf(TITLES, 9);
        var partial = release("partial", "Les gens", "Artiste", "2010", false, "Official", 0, nine);
        assertNull(AlbumMatcher.best(folder(TITLES), List.of(partial), "Les gens", "2010"));
        // avec les deux candidates, la complète gagne
        var m = AlbumMatcher.best(folder(TITLES), List.of(partial, standard()), "Les gens", "2010");
        assertEquals("std", m.release().releaseMbid());
    }

    @Test
    public void copiesOfTheSameSongShareOneSlotAndDoNotBlockTheAlbum() {
        List<Item> items = folder(TITLES);
        items.add(new Item("copie1", "La route", "Artiste", 188, 0, 0, ""));     // même morceau, autre encodage (+1 s)
        items.add(new Item("copie2", "La route (2)", "Artiste", 187, 0, 0, ""));   // copie nommée « (2) »
        var m = AlbumMatcher.best(items, List.of(standard()), "Les gens", "2010");
        assertNotNull("les copies ne doivent pas empêcher l'appariement", m);
        assertEquals(10, m.slots());
        assertEquals(12, m.trackByItemId().size());
        assertEquals(m.trackByItemId().get("f2"), m.trackByItemId().get("copie1"));
        assertEquals(m.trackByItemId().get("f2"), m.trackByItemId().get("copie2"));
    }

    @Test
    public void wrongTitlesAreStillPairedByTrackNumberAndDuration() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) items.add(new Item("f" + i, "Piste " + (i + 1), "Artiste", SECS[i], i + 1, 1, ""));
        var m = AlbumMatcher.best(items, List.of(standard()), "Les gens", "2010");
        assertNotNull(m);
        assertEquals("Soleil levant", m.trackByItemId().get("f1").title());
    }

    @Test
    public void aTitleThatMatchesButWithAnImpossibleDurationDoesNotPair() {
        List<Item> items = folder(TITLES);
        items.set(4, new Item("f4", "Nuit blanche", "Artiste", 600, 5, 1, ""));    // 10 min au lieu de 4 : autre morceau
        assertNull(AlbumMatcher.best(items, List.of(standard()), "Les gens", "2010"));
    }

    @Test
    public void theEditionClosestToTheFolderBeatsADeluxeWithoutAHint() {
        var deluxe = release("deluxe", "Les gens (Deluxe)", "Artiste", "2011", false, "Official", 4, TITLES);
        var m = AlbumMatcher.best(folder(TITLES), List.of(deluxe, standard()), "", "");
        assertEquals("std", m.release().releaseMbid());
        assertEquals(0, m.extraTracks());
    }

    @Test
    public void theAlbumNamedInTheTagsWinsEvenIfItIsTheDeluxe() {
        var deluxe = release("deluxe", "Les gens (Deluxe)", "Artiste", "2011", false, "Official", 4, TITLES);
        var m = AlbumMatcher.best(folder(TITLES), List.of(standard(), deluxe), "Les gens (Deluxe)", "");
        assertEquals("deluxe", m.release().releaseMbid());
    }

    @Test
    public void aCompilationIsNotPreferredWhenAllTracksShareOneArtist() {
        var compil = release("compil", "Hits 2010", "Various Artists", "2010", true, "Official", 20, TITLES);
        var m = AlbumMatcher.best(folder(TITLES), List.of(compil, standard()), "", "");
        assertEquals("std", m.release().releaseMbid());
    }

    @Test
    public void aCompilationIsPreferredWhenTheFolderMixesArtists() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) items.add(new Item("f" + i, TITLES[i], "Artiste " + i, SECS[i], i + 1, 1, ""));
        var compil = release("compil", "Hits 2010", "Various Artists", "2010", true, "Official", 0, TITLES);
        var solo = release("solo", "Les gens", "Artiste 0", "2010", false, "Official", 0, TITLES);
        var m = AlbumMatcher.best(items, List.of(solo, compil), "", "");
        assertEquals("compil", m.release().releaseMbid());
    }

    @Test
    public void withoutAYearHintTheOldestReleaseWins_andAYearHintOverridesIt() {
        var old = release("old", "Les gens", "Artiste", "2005", false, "Official", 0, TITLES);
        var recent = release("recent", "Les gens", "Artiste", "2015", false, "Official", 0, TITLES);
        assertEquals("old", AlbumMatcher.best(folder(TITLES), List.of(recent, old), "Les gens", "").release().releaseMbid());
        assertEquals("recent", AlbumMatcher.best(folder(TITLES), List.of(old, recent), "Les gens", "2015").release().releaseMbid());
    }

    @Test
    public void withoutDurationsATitleMustMatchAlmostExactly() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < TITLES.length; i++) items.add(new Item("f" + i, TITLES[i], "Artiste", 0, 0, 0, ""));
        assertNotNull(AlbumMatcher.best(items, List.of(standard()), "", ""));
        items.set(3, new Item("f3", "Mon ami (live)", "Artiste", 0, 0, 0, ""));          // titre approchant seulement : pas assez
        assertNull(AlbumMatcher.best(items, List.of(standard()), "", ""));
    }

    @Test
    public void emptyOrMissingInputsGiveNothing() {
        assertNull(AlbumMatcher.best(List.of(), List.of(standard()), "", ""));
        assertNull(AlbumMatcher.best(folder(TITLES), List.of(), "", ""));
        assertTrue(AlbumMatcher.acceptable(null, null, "", "").isEmpty());
    }
}
