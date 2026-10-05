package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/** Les Préférences ne portent plus le compte MusicBrainz ; la sauvegarde ne doit pourtant jamais effacer ce qui a été réglé ailleurs. */
public class SettingsMbAccountTest {

    private static String source(String name) throws Exception {
        for (String base : new String[]{"src/main/java/com/opentagger/ui/", "opentagger/src/main/java/com/opentagger/ui/"}) {
            Path p = Path.of(base + name);
            if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8);
        }
        throw new IllegalStateException("source introuvable : " + name);
    }

    @Test
    public void thePreferencesNoLongerBuildAnAccountSection() throws Exception {
        String s = source("SettingsDialog.java");
        assertFalse(s.contains("Se connecter à MusicBrainz"));
        assertFalse(s.contains("cmbMbOAuthMode"));
        assertFalse(s.contains("tfMbCollectionId"));
        assertTrue("l'onglet reste pour le serveur / miroir", s.contains("Serveur MusicBrainz"));
    }

    @Test
    public void savingKeepsTheExistingAccountSettings() throws Exception {
        String s = source("SettingsDialog.java");
        assertTrue(s.contains("\"mb.oauth.mode\",                Config.get().str(\"mb.oauth.mode\", \"scheme\")"));
        assertTrue(s.contains("\"mb.oauth.collection_id\",       Config.get().mbCollectionId()"));
        assertTrue(s.contains("\"mb.oauth.token\",               Config.get().mbToken()"));
    }

    @Test
    public void theAccountHasItsOwnDialogReachableFromTheMenu() throws Exception {
        assertTrue(source("MbAccountDialog.java").contains("MusicBrainzOAuth().authorize()"));
        assertTrue(source("MainFrame.java").contains("MbAccountDialog.open(this)"));
    }
}
