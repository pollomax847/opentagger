package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class CdAudioIdentifierTest {

    @Test
    public void samplesAreDistinctAndBounded() {
        assertEquals(List.of(1), CdAudioIdentifier.sampleTracks(1));
        assertEquals(List.of(1, 2), CdAudioIdentifier.sampleTracks(2));
        assertEquals(List.of(1, 2, 3), CdAudioIdentifier.sampleTracks(3));
        List<Integer> s19 = CdAudioIdentifier.sampleTracks(19);
        assertEquals(3, s19.size());
        assertEquals(s19.size(), Set.copyOf(s19).size());
        assertEquals(List.of(1, 2, 10), s19);
    }

    @Test
    public void aCompilationWinsWhenSeveralTracksAgree() {
        // chaque piste renvoie son album d'origine ET la compilation : seule la compilation est commune
        assertEquals("compil", CdAudioIdentifier.pickRelease(List.of(
                Set.of("albumA", "compil"), Set.of("albumB", "compil"), Set.of("albumC", "compil"))));
    }

    @Test
    public void twoOutOfThreeIsEnough() {
        assertEquals("compil", CdAudioIdentifier.pickRelease(List.of(
                Set.of("compil"), Set.of("compil"), Set.<String>of())));
    }

    @Test
    public void noAgreementMeansNoAnswer() {
        assertNull(CdAudioIdentifier.pickRelease(List.of(Set.of("a"), Set.of("b"), Set.of("c"))));
        assertNull(CdAudioIdentifier.pickRelease(List.of()));
        assertNull(CdAudioIdentifier.pickRelease(List.of(Set.<String>of(), Set.<String>of())));
    }

    @Test
    public void aSingleUsableSampleIsOnlyTrustedWhenItIsUnambiguous() {
        assertEquals("only", CdAudioIdentifier.pickRelease(List.of(Set.of("only"), Set.<String>of())));
        assertNull(CdAudioIdentifier.pickRelease(List.of(Set.of("a", "b"), Set.<String>of())));
    }
}
