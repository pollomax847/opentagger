package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GenericTrackNamesTest {

    @Test public void bareTrackWordsAndNumberedTracksAreGeneric() {
        for (String s : new String[]{"track", "Track", "piste", "Track 3", "track03", "Piste 12", "audiotrack", "Titre"})
            assertTrue(s, TaggingWorker.isGenericTag(s));
    }

    @Test public void realTitlesAreNotGeneric() {
        for (String s : new String[]{"Gazebo", "Trackstar", "Piste Noire", "Tracks of My Tears", "Another Brick in the Wall"})
            assertFalse(s, TaggingWorker.isGenericTag(s));
    }
}
