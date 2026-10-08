package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;

import java.nio.file.Paths;

import org.junit.Test;

/** « Le fichier bien rangé gagne » — cas réels du 2026-10-08. */
public class AutoDedupPlacementTest {

    private static TagInfo t(String title, String album) {
        TagInfo ti = new TagInfo(); ti.title = title; ti.album = album; return ti;
    }

    @Test public void albumCopyIsWellPlaced_misnamedCompilationFileIsNot() {
        TagInfo poehl = t("Going to Where the Tea Trees Are", "Going to Where the Tea-Trees Are");
        assertTrue(AutoDedup.wellPlaced(Paths.get(
                "/mnt/MyBook/itunes/Music/Peter Von Poehl/Going to Where the Tea-Trees Are/01 - Going to Where the Tea Trees Are.mp3"), poehl));
        assertFalse("contenu ≠ nom de fichier", AutoDedup.wellPlaced(Paths.get(
                "/mnt/MyBook/itunes/Music/Compilations/NRJ Fresh Hits 2017/12 - Deorro - Going Up.mp3"), poehl));
    }

    @Test public void discSubfolderAccentsAndCaseAreTolerated() {
        assertTrue(AutoDedup.wellPlaced(Paths.get(
                "/x/Compilations/Harmonia_ Le Chant des rêves/Disc 02/07 - Michael Nyman - The Heart Asks Pleasure First _ The Promise.mp3"),
                t("The Heart Asks Pleasure First / The Promise", "Harmonia: le chant des rêves")));
    }

    @Test public void missingTagsAreNeverWellPlaced() {
        assertFalse(AutoDedup.wellPlaced(Paths.get("/x/Album/01 - Titre.mp3"), t("", "Album")));
    }
}
