package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Cas réels de la bibliothèque (enquête du 2026-11-07, voir SameSong). */
public class SameSongTest {

    @Test
    public void unAutreMorceauDuMemeMotNEstPasLeMorceauDuFichier() {
        // « Taylor Swift – Style » complété avec les champs de « 2Pac – Thug Style »
        assertFalse(SameSong.matches("Taylor Swift", "Style", "2Pac", "Thug Style"));
        // « Sister Sledge – Let Him Go » complété avec « 2Pac – Let Them Thangs Go »
        assertFalse(SameSong.matches("Sister Sledge", "Let Him Go", "2Pac", "Let Them Thangs Go"));
        // Bon artiste mais autre titre partageant un mot
        assertFalse(SameSong.matches("2Pac", "Style", "2Pac", "Thug Style"));
        // Bon titre mais autre artiste
        assertFalse(SameSong.matches("Taylor Swift", "Style", "Harry Styles", "Style"));
    }

    @Test
    public void lesVariantesDuMemeMorceauSontAcceptees() {
        assertTrue(SameSong.matches("Taylor Swift", "Style", "Taylor Swift", "Style"));
        assertTrue(SameSong.matches("The Beatles", "Let It Be (Remastered 2009)", "The Beatles", "Let It Be"));
        assertTrue(SameSong.matches("2Pac feat. George Clinton", "Can't C Me", "2Pac", "Can't C Me"));
        assertTrue(SameSong.matches("Beyonce", "Halo", "Beyoncé", "Halo"));
    }

    @Test
    public void unFichierQuiNeDitPasQuiIlEstNePeutRienVerifier() {
        assertFalse(SameSong.matches("", "Style", "Taylor Swift", "Style"));
        assertFalse(SameSong.matches("Various Artists", "Style", "Taylor Swift", "Style"));
        assertFalse(SameSong.matches("Taylor Swift", "", "Taylor Swift", "Style"));
        assertFalse(SameSong.matches("Unknown Artist", "Track 1", "Taylor Swift", "Style"));
    }

    @Test
    public void changementDIdentite() {
        assertTrue(SameSong.otherSong("2Pac", "Thug Style", "Taylor Swift", "Style"));
        assertTrue(SameSong.otherSong("Taylor Swift", "Shake It Off", "Taylor Swift", "Style"));
        assertFalse(SameSong.otherSong("Beyonce", "Halo", "Beyoncé", "Halo"));
        assertFalse(SameSong.otherSong("Kyo - Topic", "Le chemin", "Kyo", "Le chemin"));
        assertFalse(SameSong.otherSong("The Beatles", "Let It Be", "The Beatles", "Let It Be (Remastered 2009)"));
        // Rien à comparer d'un côté : on ne jette rien
        assertFalse(SameSong.otherSong("", "", "Taylor Swift", "Style"));
        // Artiste ancien vide, mais titre sans rapport : autre morceau
        assertTrue(SameSong.otherSong("", "Thug Style", "Taylor Swift", "Shake It Off"));
    }
}
