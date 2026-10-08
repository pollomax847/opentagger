package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/**
 * Garde-fou de TrashHelper : un dossier contenant de l'audio n'est JAMAIS déplacé à la corbeille (incident du
 * 24/09/2026 : ~8 000 pistes valides parties de la bibliothèque). Seul le REFUS est testé ici — le cas positif
 * écrirait dans la vraie corbeille de l'utilisateur (~/.opentagger/corbeille).
 */
public class TrashHelperGuardTest {

    @Test public void directoryHoldingAudio_isRefusedAndLeftUntouched() throws Exception {
        Path album = Files.createTempDirectory("otguard").resolve("Artiste");
        Path disc  = Files.createDirectories(album.resolve("Album").resolve("Disc 02"));
        Files.write(disc.resolve("01 - Piste.mp3"), new byte[]{1, 2, 3});
        Files.write(album.resolve("Album").resolve("folder.jpg"), new byte[]{1});

        assertFalse("dossier d'artiste avec audio en sous-dossier", TrashHelper.moveDirToTrash(album.toFile()));
        assertFalse("dossier de disque contenant l'audio", TrashHelper.moveDirToTrash(disc.toFile()));
        assertFalse("moveToTrash(dossier) passe par le même garde-fou", TrashHelper.moveToTrash(album.toFile()));
        assertTrue("l'audio est toujours là", Files.exists(disc.resolve("01 - Piste.mp3")));
        assertTrue(Files.exists(album.resolve("Album").resolve("folder.jpg")));
    }

    @Test public void uppercaseAndOddAudioExtensions_areStillDetected() throws Exception {
        Path d = Files.createTempDirectory("otguard2");
        Files.write(d.resolve("TRACK.FLAC"), new byte[]{1});
        assertFalse(TrashHelper.moveDirToTrash(d.toFile()));
        File mka = Files.createTempDirectory("otguard3").resolve("x.mka").toFile();
        Files.write(mka.toPath(), new byte[]{1});
        assertFalse(TrashHelper.moveDirToTrash(mka.getParentFile()));
    }
}
