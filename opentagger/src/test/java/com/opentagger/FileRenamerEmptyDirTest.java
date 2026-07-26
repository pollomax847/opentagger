package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Vérifie deleteEmptyAncestors() sur le scénario signalé par l'utilisateur : un dossier scanné
 * qui ne contient plus aucun audio (déjà déplacé/supprimé par l'appelant) mais qui contient encore
 * une pochette locale et/ou un journal de session doit être traité comme "vide" et supprimé —
 * pas seulement un dossier littéralement sans aucun fichier.
 */
public class FileRenamerEmptyDirTest {

    @Test
    public void supprimeLeDossierAvecPochetteEtLogSansAudio() throws IOException {
        Path root  = Files.createTempDirectory("ot_test_root_");
        Path album = Files.createDirectory(root.resolve("Album Sans Audio"));
        Files.writeString(album.resolve("cover.jpg"), "fake-jpg-bytes");
        Files.writeString(album.resolve("opentagger_scan_20260101.log"), "log content");

        int removed = FileRenamer.deleteEmptyAncestors(album, root);

        assertEquals(1, removed);
        assertFalse("le dossier ne contenant plus qu'une pochette+log doit être supprimé",
                Files.exists(album));
        assertTrue("stopAt (root) ne doit jamais être supprimé", Files.exists(root));
    }

    @Test
    public void neSupprimePasUnDossierAvecUnVraiFichier() throws IOException {
        Path root  = Files.createTempDirectory("ot_test_root_");
        Path album = Files.createDirectory(root.resolve("Album Avec Notes"));
        Files.writeString(album.resolve("cover.jpg"), "fake-jpg-bytes");
        Files.writeString(album.resolve("notes.txt"), "vraies notes de l'utilisateur");

        int removed = FileRenamer.deleteEmptyAncestors(album, root);

        assertEquals(0, removed);
        assertTrue("un fichier réel (non reconnu comme résidu) doit bloquer la suppression",
                Files.exists(album));
        assertTrue(Files.exists(album.resolve("notes.txt")));
    }

    @Test
    public void remonteSurPlusieursNiveauxTantQueVideDeContenuReel() throws IOException {
        Path root   = Files.createTempDirectory("ot_test_root_");
        Path artist = Files.createDirectory(root.resolve("Artiste"));
        Path album  = Files.createDirectory(artist.resolve("Album"));
        Files.writeString(album.resolve("folder.jpg"), "fake-jpg-bytes");

        int removed = FileRenamer.deleteEmptyAncestors(album, root);

        assertEquals(2, removed);
        assertFalse(Files.exists(album));
        assertFalse("le parent devenu vide à son tour doit aussi être remonté et supprimé",
                Files.exists(artist));
        assertTrue(Files.exists(root));
    }
}
