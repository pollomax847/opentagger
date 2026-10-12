package com.opentagger;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Un fichier rangé rejoint le dossier d'artiste déjà présent au lieu d'en créer une variante (2026-10-12, MyBook :
 *  « Jean-Louis Aubert », « Jean‐Louis Aubert », « Jean‐louis Aubert »… côte à côte). */
public class FolderVariantReuseTest {

    @Test public void existingVariantIsReused() throws Exception {
        Path root = Files.createTempDirectory("otvariants");
        Files.createDirectories(root.resolve("Jean-Louis Aubert/Bleu blanc vert"));
        Files.createDirectories(root.resolve("Robbie_Williams"));
        Files.createDirectories(root.resolve("Anaïs"));

        // trait d'union typographique de MusicBrainz + casse différente → dossier existant
        assertEquals("Jean-Louis Aubert/Bleu blanc vert/01 - Voilà, c'est fini",
                FileRenamer.resolveExistingDirs(root, "Jean‐louis Aubert/Bleu Blanc Vert/01 - Voilà, c'est fini"));
        assertEquals("Jean-Louis Aubert/Bleu blanc vert/01 - x",
                FileRenamer.resolveExistingDirs(root, "Jean‐Louis Aubert/BLEU BLANC VERT/01 - x"));
        // « _ » à la place des espaces
        assertEquals("Robbie_Williams/Escapology/01 - Feel", FileRenamer.resolveExistingDirs(root, "Robbie Williams/Escapology/01 - Feel"));
        // les accents restent significatifs, le nom de fichier n'est jamais touché
        assertEquals("Anais/Album/01 - x", FileRenamer.resolveExistingDirs(root, "Anais/Album/01 - x"));
        assertEquals("Nouveau/Album/01 - X", FileRenamer.resolveExistingDirs(root, "Nouveau/Album/01 - X"));
    }

    @Test public void bestLookingVariantWins() {
        assertEquals("Wu-Tang Clan", FileRenamer.preferredName("Wu‐tang Clan", "Wu-Tang Clan"));
        assertEquals("Robbie Williams", FileRenamer.preferredName("ROBBIE WILLIAMS", "Robbie Williams"));
        assertEquals("Robbie Williams", FileRenamer.preferredName("Robbie_Williams", "Robbie Williams"));
        assertEquals(FileRenamer.folderKey("L’Algérino"), FileRenamer.folderKey("l'algérino"));
    }
}
