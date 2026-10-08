package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class CdRankReleasesTest {

    @Test
    public void severalVersionsAreRankedByHowManySamplesCiteThem() {
        // « versionA » citée par 3 pistes, « versionB » par 2, « albumOrigine » par une seule (écartée)
        List<String> r = CdAudioIdentifier.rankReleases(List.of(
                Set.of("versionA", "versionB", "albumOrigine"), Set.of("versionA", "versionB"), Set.of("versionA")));
        assertEquals(List.of("versionA", "versionB"), r);
    }

    @Test
    public void pickReleaseIsTheFirstOfTheRanking() {
        assertEquals("compil", CdAudioIdentifier.pickRelease(List.of(Set.of("compil", "a"), Set.of("compil", "b"))));
    }

    @Test
    public void nothingCitedTwiceMeansNoVersionToPropose() {
        assertTrue(CdAudioIdentifier.rankReleases(List.of(Set.of("a"), Set.of("b"), Set.of("c"))).isEmpty());
        assertTrue(CdAudioIdentifier.rankReleases(List.of()).isEmpty());
    }

    @Test
    public void aSingleUnambiguousSampleStillGivesOneVersion() {
        assertEquals(List.of("only"), CdAudioIdentifier.rankReleases(List.of(Set.of("only"), Set.<String>of())));
    }
}
