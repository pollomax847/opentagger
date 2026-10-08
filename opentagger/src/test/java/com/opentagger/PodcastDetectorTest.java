package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PodcastDetectorTest {

    @Test
    public void aFeedUrlInTheTagsIsEnough() {
        assertTrue(PodcastDetector.assess("", "https://feeds.example.org/show.rss", "", "", 0, "x.mp3", "").probable());
    }

    @Test
    public void podcastGenreAndALongDurationTogetherAreProbable() {
        assertTrue(PodcastDetector.assess("Podcast", "", "", "", 40 * 60, "emission.mp3", "").probable());
        assertTrue("accents et casse ignorés", PodcastDetector.assess("PODCAST", "", "", "", 30 * 60, "e.mp3", "").probable());
    }

    @Test
    public void aLongFileAloneIsNotEnough_itCouldBeADjMix() {
        assertFalse(PodcastDetector.assess("", "", "", "", 90 * 60, "Discotheque La STATION.mp3", "").probable());
    }

    @Test
    public void aDjMixNameCancelsTheDuration() {
        assertFalse(PodcastDetector.assess("", "", "", "", 80 * 60, "Summer Megamix 2019 - Episode 3.mp3", "").probable());
    }

    @Test
    public void anEpisodeLikeNameInAPodcastFolderIsProbable() {
        assertTrue(PodcastDetector.assess("", "", "", "", 0, "Episode 142 - Les gens.mp3", "Podcasts").probable());
        assertTrue(PodcastDetector.assess("", "", "", "", 0, "2026-09-30 emission.mp3", "Mes Podcasts").probable());
    }

    @Test
    public void seasonAndEpisodeTagsPlusALongDurationAreProbable() {
        assertTrue(PodcastDetector.assess("", "", "2", "14", 35 * 60, "x.mp3", "").probable());
    }

    @Test
    public void aNumberedHourLongShowIsProbable_butAnUnnamedHourLongFileIsNot() {
        assertTrue(PodcastDetector.assess("Other", "", "", "", 55 * 60, "FUNKY PEARLS vol 648.mp3", "").probable());
        assertFalse("« download (13) » d'une heure : rien ne dit que c'est un podcast",
                PodcastDetector.assess("Other", "", "", "", 60 * 60, "download (13).mp3", "").probable());
        assertFalse(PodcastDetector.assess("Other", "", "", "", 39 * 60, "FUNK A L'ANCIENNE MIX.mp3", "").probable());
    }

    @Test
    public void anOrdinarySongIsNeverAPodcast() {
        assertFalse(PodcastDetector.assess("Pop", "", "", "", 215, "01 - Titre.mp3", "Album").probable());
        assertFalse(PodcastDetector.assess("", "", "", "", 0, "", "").probable());
        assertFalse(PodcastDetector.assess(null, null, null, null, 0, null, null).probable());
    }

    @Test
    public void reasonsExplainTheVerdict() {
        var v = PodcastDetector.assess("Podcast", "", "", "", 50 * 60, "emission.mp3", "");
        assertTrue(v.reasons().stream().anyMatch(r -> r.contains("Podcast")));
        assertTrue(v.reasons().stream().anyMatch(r -> r.contains("très long")));
    }
}
