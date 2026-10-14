package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Un verdict « Ignoré » retrouvé au démarrage suivant tant que le fichier n'a pas changé (2026-10-14 : 248 alertes sur
 *  259 un matin étaient des fichiers déjà jugés la veille, relancés à chaque redémarrage). Base du dossier de test
 *  (-Duser.home), jamais celle de l'utilisateur. */
public class SkippedVerdictTest {

    @Test public void verdictSurvivesOnlyWhileTheFileIsUnchanged() {
        String path = "/tmp/ot-test/download (46)_1.mp3";
        try (MetadataCache c = new MetadataCache()) {
            c.saveSkippedVerdict(path, 1234, 5678, "UNREADABLE", "Audio illisible");
            MetadataCache.SkippedVerdict v = c.loadSkippedVerdict(path, 1234, 5678);
            assertNotNull(v);
            assertEquals("UNREADABLE", v.reason());
            assertEquals("Audio illisible", v.message());
            assertNull(c.loadSkippedVerdict(path, 1234, 9999));   // fichier modifié : retenté
            assertNull(c.loadSkippedVerdict(path, 4321, 5678));   // taille différente : retenté
            assertNull(c.loadSkippedVerdict("/autre.mp3", 1234, 5678));
        }
    }
}
