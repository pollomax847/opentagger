package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** Dossiers presque identiques fusionnés avant le scan (2026-10-12), sans jamais écraser un fichier. */
public class LibraryTidyTest {

    @Test public void variantsAreMergedIntoTheCleanestFolder() throws Exception {
        Path root = Files.createTempDirectory("ottidy");
        Files.createDirectories(root.resolve("Jean-Louis Aubert/Bleu blanc vert"));
        Files.writeString(root.resolve("Jean-Louis Aubert/Bleu blanc vert/01.mp3"), "a");
        Files.createDirectories(root.resolve("Jean‐louis Aubert/Bleu blanc vert"));
        Files.writeString(root.resolve("Jean‐louis Aubert/Bleu blanc vert/01.mp3"), "autre");   // même nom : laissé
        Files.writeString(root.resolve("Jean‐louis Aubert/Bleu blanc vert/02.mp3"), "b");
        Files.createDirectories(root.resolve("Jean‐Louis Aubert/IDÉAL STANDARD"));
        Files.writeString(root.resolve("Jean‐Louis Aubert/IDÉAL STANDARD/01.mp3"), "c");
        Files.createDirectories(root.resolve("Robbie_Williams/Escapology"));
        Files.createDirectories(root.resolve("Robbie Williams"));
        Files.createDirectories(root.resolve("Anaïs"));
        Files.createDirectories(root.resolve("Anais"));

        Map<Path, Path> moves = new LinkedHashMap<>();
        LibraryTidy.Result r = LibraryTidy.mergeFolderVariants(root, moves::put, () -> false);

        assertEquals(2, r.groups());
        assertTrue(Files.exists(root.resolve("Jean-Louis Aubert/Bleu blanc vert/02.mp3")));
        assertTrue(Files.exists(root.resolve("Jean-Louis Aubert/IDÉAL STANDARD/01.mp3")));
        assertEquals("a", Files.readString(root.resolve("Jean-Louis Aubert/Bleu blanc vert/01.mp3")));
        assertEquals(1, r.leftInPlace().size());                       // l'autre 01.mp3, intact dans son dossier
        assertTrue(Files.exists(root.resolve("Jean‐louis Aubert/Bleu blanc vert/01.mp3")));
        assertFalse(Files.exists(root.resolve("Jean‐Louis Aubert")));
        assertTrue(Files.isDirectory(root.resolve("Robbie Williams/Escapology")));
        assertFalse(Files.exists(root.resolve("Robbie_Williams")));
        assertTrue(Files.isDirectory(root.resolve("Anaïs")) && Files.isDirectory(root.resolve("Anais")));   // accents gardés
        assertEquals(root.resolve("Robbie Williams/Escapology"), moves.get(root.resolve("Robbie_Williams/Escapology")));
    }

    @Test public void tableEntriesFollowTheirFolder() {
        Map<Path, Path> moves = Map.of(Path.of("/m/Robbie_Williams/Escapology"), Path.of("/m/Robbie Williams/Escapology"));
        assertEquals(Path.of("/m/Robbie Williams/Escapology/01 Feel.mp3"),
                com.opentagger.ui.MainFrameRelocateAccess.relocated(Path.of("/m/Robbie_Williams/Escapology/01 Feel.mp3"), moves));
        assertEquals(null, com.opentagger.ui.MainFrameRelocateAccess.relocated(Path.of("/m/Other/x.mp3"), moves));
    }
}
