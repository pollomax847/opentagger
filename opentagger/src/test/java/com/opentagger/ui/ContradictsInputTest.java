package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContradictsInputTest {

    @Test
    public void unrelatedCandidateContradictsFilename() {
        assertTrue(TaggingWorker.contradictsInput("Drake", "Summer Sixteen", "", "",
                "T. Rex", "Summer Deep"));
    }

    @Test
    public void sameArtistOrSameTitleIsNotAContradiction() {
        assertFalse(TaggingWorker.contradictsInput("Daft Punk", "One More Time", "", "",
                "Daft Punk", "One More Time"));
        assertFalse(TaggingWorker.contradictsInput("Daft Punk", "One More Time", "", "",
                "Daft Punk", "Harder Better Faster Stronger"));
        assertFalse(TaggingWorker.contradictsInput("Someone", "Havana", "", "",
                "Camila Cabello", "Havana"));
    }

    @Test
    public void nothingUsableMeansNoContradiction() {
        assertFalse(TaggingWorker.contradictsInput("", "", "", "", "Any", "Thing"));
        assertFalse(TaggingWorker.contradictsInput("Drake", "", "", "", "T. Rex", "Summer Deep"));
        assertFalse(TaggingWorker.contradictsInput("", "Track 7", "", "", "T. Rex", "Summer Deep"));
    }
}
