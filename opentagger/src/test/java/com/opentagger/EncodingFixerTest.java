package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;
import org.junit.Test;

public class EncodingFixerTest {

    /** Ce que produit une écriture UTF-8 relue en Latin-1. */
    private static String broken(String s) {
        return new String(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    @Test
    public void utf8ReadAsLatin1IsRepaired() {
        assertEquals("Café", EncodingFixer.fix(broken("Café")));
        assertEquals("Sinéad O’Connor", EncodingFixer.fix(broken("Sinéad O’Connor")));
        assertEquals("Âge tendre", EncodingFixer.fix(broken("Âge tendre")));
    }

    @Test
    public void textThatIsAlreadyCorrectIsNeverTouched() {
        for (String ok : new String[]{"Café", "Dis-Moi Bébé", "Sinéad", "Plain ASCII", "日本語", "Ünïcödé", "À l'ancienne", ""})
            assertEquals(ok, EncodingFixer.fix(ok));
        assertFalse(EncodingFixer.isSuspect("Café"));
    }

    @Test
    public void repairingIsIdempotent() {
        String once = EncodingFixer.fix(broken("Beyoncé"));
        assertEquals("Beyoncé", once);
        assertEquals(once, EncodingFixer.fix(once));
    }

    @Test
    public void repairFieldsFixesOnlyTheBrokenFields() {
        TagInfo t = new TagInfo();
        t.title = broken("Dis-Moi Bébé");
        t.artist = "Drs. P";
        t.album = broken("Été");
        t.comment = "";
        assertTrue(EncodingFixer.repairFields(t));
        assertEquals("Dis-Moi Bébé", t.title);
        assertEquals("Drs. P", t.artist);
        assertEquals("Été", t.album);
        assertFalse("rien d'autre à réparer", EncodingFixer.repairFields(t));
    }

    @Test
    public void nothingToRepairMeansNoChange() {
        TagInfo t = new TagInfo();
        t.title = "Titre"; t.artist = "Artiste";
        assertFalse(EncodingFixer.repairFields(t));
        assertFalse(EncodingFixer.repairFields(null));
    }
}
