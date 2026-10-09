package com.opentagger.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FallbackIdentityTest {

    @Test public void theJustinTimberlakeCase() {
        // Tags réels relevés : titre « 1-04_Justin_Timberlake_-_What_Goes_Around..._Comes_Around », artiste « 1-04 Justin Timberlake »
        assertArrayEquals(new String[]{"Justin Timberlake", "What Goes Around... Comes Around"},
                TaggingWorker.cleanFallbackIdentity("1-04 Justin Timberlake", "1-04_Justin_Timberlake_-_What_Goes_Around..._Comes_Around"));
    }

    @Test public void artistTakenFromTheTitleWhenMissing() {
        assertArrayEquals(new String[]{"Justin Timberlake", "SexyBack"},
                TaggingWorker.cleanFallbackIdentity("", "04 - Justin Timberlake - SexyBack"));
    }

    @Test public void normalTagsAreUntouched() {
        assertArrayEquals(new String[]{"50 Cent", "21 Questions"}, TaggingWorker.cleanFallbackIdentity("50 Cent", "21 Questions"));
        assertArrayEquals(new String[]{"Jay-Z", "99 Problems"}, TaggingWorker.cleanFallbackIdentity("Jay-Z", "99 Problems"));
        assertArrayEquals(new String[]{"Logic", "1-800-273-8255"}, TaggingWorker.cleanFallbackIdentity("Logic", "1-800-273-8255"));
        assertArrayEquals(new String[]{"Blink-182", "What's My Age Again?"}, TaggingWorker.cleanFallbackIdentity("Blink-182", "What's My Age Again?"));
    }

    @Test public void aTitleThatSimplyContainsADashIsKeptWhenTheHeadIsNotTheArtist() {
        assertArrayEquals(new String[]{"Daft Punk", "Around the World - Radio Edit"},
                TaggingWorker.cleanFallbackIdentity("Daft Punk", "Around the World - Radio Edit"));
    }

    @Test public void realShortTitlesAreNotPlaceholders() {
        for (String s : new String[]{"2U", "HP", "2010", "1990", "江南", "Gazebo", "Track Star", "Piste Noire", "Hello"})
            assertFalse(s, TaggingWorker.isPlaceholderTitle(s));
    }

    @Test public void realPlaceholdersAre() {
        for (String s : new String[]{"Track 3", "track03", "Piste 12", "Track", "Titre", "Untitled", "Audio Track 07", "Sans titre"})
            assertTrue(s, TaggingWorker.isPlaceholderTitle(s));
    }

    @Test public void leadingPunctuationAndConfigJunkAreNotWrittenAsArtist() {
        assertArrayEquals(new String[]{"Kid Cudi vs Bad Bunny", "Titre"}, TaggingWorker.cleanFallbackIdentity("- Kid Cudi vs Bad Bunny", "Titre"));
        assertArrayEquals(new String[]{"", "Afrobombas"}, TaggingWorker.cleanFallbackIdentity(", Renderer Allowaccessjs=true, Fautoupdatedisabled=false", "Afrobombas"));
    }

    @Test public void trackPrefixShapes() {
        assertEquals("Titre", TaggingWorker.stripTrackPrefix("04 - Titre"));
        assertEquals("Titre", TaggingWorker.stripTrackPrefix("1-04 Titre"));
        assertEquals("Titre", TaggingWorker.stripTrackPrefix("04_Titre"));
        assertEquals("Titre", TaggingWorker.stripTrackPrefix("04. Titre"));
        assertEquals("21 Guns", TaggingWorker.stripTrackPrefix("21 Guns"));
        assertEquals("2.5 Years", TaggingWorker.stripTrackPrefix("2.5 Years"));
        assertEquals("1999", TaggingWorker.stripTrackPrefix("1999"));
    }
}
