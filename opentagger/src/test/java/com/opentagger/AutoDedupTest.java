package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.opentagger.model.TagInfo;
import com.opentagger.ui.AutoDedupAccess;

import org.junit.Test;

/** Règle « vrai doublon » de AutoDedup (2026-09-26). */
public class AutoDedupTest {

    private static TagInfo t(String rec, String rel, String disc, String track) {
        TagInfo ti = new TagInfo();
        ti.recordingMbid = rec; ti.releaseMbid = rel; ti.discNo = disc; ti.track = track;
        return ti;
    }

    @Test public void sameRecordingSameReleaseSameTrack_isDuplicate() {
        assertTrue(AutoDedupAccess.sameTrack(t("r1", "A", "1", "04"), t("r1", "a", "", "4/12")));
    }

    @Test public void sameSongOnAnotherAlbum_isNeverDuplicate() {
        assertFalse("compilation / best-of", AutoDedupAccess.sameTrack(t("r1", "A", "1", "4"), t("r1", "B", "1", "4")));
        assertFalse("autre piste du même album", AutoDedupAccess.sameTrack(t("r1", "A", "1", "4"), t("r1", "A", "1", "5")));
        assertFalse("autre disque", AutoDedupAccess.sameTrack(t("r1", "A", "1", "4"), t("r1", "A", "2", "4")));
        assertFalse("sans MBID", AutoDedupAccess.sameTrack(t("", "", "1", "4"), t("", "", "1", "4")));
        assertFalse("sans numéro de piste", AutoDedupAccess.sameTrack(t("r1", "A", "1", ""), t("r1", "A", "1", "")));
    }

    @Test public void durationsMustBeKnownAndClose() {
        assertTrue(AutoDedupAccess.durationsClose(214, 216));
        assertFalse("un fichier tronqué", AutoDedupAccess.durationsClose(29, 191));
        assertFalse("durée inconnue", AutoDedupAccess.durationsClose(0, 191));
    }
}
