package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class AutomationModeTest {

    private static Map<String, String> store(AutomationMode m) { return new HashMap<>(m.settings()); }

    @Test
    public void everyModeIsDetectedFromItsOwnSettings() {
        for (AutomationMode m : AutomationMode.values())
            assertEquals(m, AutomationMode.detect(store(m)::get));
    }

    @Test
    public void aSingleDifferingSettingMeansCustom() {
        Map<String, String> s = store(AutomationMode.AUTOMATIC);
        s.put("playcounts.auto_sync", "false");
        assertNull(AutomationMode.detect(s::get));
    }

    @Test
    public void absentKeysUseTheApplicationDefaults() {
        // Application neuve : auto-enregistrement actif par défaut, tout le reste coupé → ni Manuel ni Assisté ni Automatique.
        assertNull(AutomationMode.detect(k -> null));
    }

    @Test
    public void manualAndAssistedDifferOnlyWhereExpected() {
        Map<String, String> a = AutomationMode.MANUAL.settings();
        Map<String, String> b = AutomationMode.ASSISTED.settings();
        assertEquals(a.keySet(), b.keySet());
        assertEquals("false", a.get("tagging.auto_save_enabled"));
        assertEquals("false", b.get("tagging.auto_save_enabled"));
        assertEquals("true", b.get("tagging.auto_start_on_scan"));
    }

    @Test
    public void onlyAutomaticTurnsOnArtworkAndSave() {
        assertEquals("true", AutomationMode.AUTOMATIC.settings().get("artwork.auto_after_save"));
        assertEquals("true", AutomationMode.AUTOMATIC.settings().get("tagging.auto_save_enabled"));
        assertEquals("false", AutomationMode.ASSISTED.settings().get("artwork.auto_after_save"));
    }
}
