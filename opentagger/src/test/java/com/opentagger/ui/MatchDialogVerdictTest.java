package com.opentagger.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.SkipReason;
import com.opentagger.model.TagInfo;
import java.io.File;
import org.junit.Test;

/** Correspondance manuelle (2026-10-14) : le haut de la fenêtre dit pourquoi le fichier est là — messages réels du journal. */
public class MatchDialogVerdictTest {

    private static MatchDialog.Kind kind(FileEntry.Status st, SkipReason r, String msg) {
        FileEntry e = new FileEntry(new File("/m/x.mp3"), new TagInfo());
        e.status = st; e.skipReason = r; e.message = msg;
        MatchDialog.Verdict v = MatchDialog.verdictOf(e);
        return v == null ? null : v.kind();
    }

    @Test public void eachSkipSaysWhatItIs() {
        FileEntry.Status s = FileEntry.Status.SKIPPED;
        assertEquals(MatchDialog.Kind.MIX, kind(s, SkipReason.DURATION_MISMATCH,
                I18n.t("Mix ou enregistrement long (%s) — pas un morceau seul", "47:22")));
        assertEquals(MatchDialog.Kind.CUT, kind(s, SkipReason.DURATION_MISMATCH,
                I18n.t("Fichier coupé : %s au lieu de %s (« %s ») — à retélécharger", "2:02", "5:32", "Graf Zahl")));
        assertEquals(MatchDialog.Kind.VERSION, kind(s, SkipReason.DURATION_MISMATCH,
                I18n.t("Autre version de « %s » (fichier %s, MusicBrainz %s) — à vérifier", "Power of Love", "13:16", "7:42")));
        assertEquals(MatchDialog.Kind.CONTRADICTS, kind(s, SkipReason.LOW_SCORE,
                I18n.t("AcoustID contredit le nom de fichier et les tags (« %s – %s ») — à vérifier", "Hardrive", "Deep Inside")));
        assertEquals(MatchDialog.Kind.SHAZAM_ONLY, kind(s, SkipReason.LOW_SCORE, I18n.t("Reconnu par SongRec (« %s – %s ») mais inconnu "
                + "de MusicBrainz, et ni le nom du fichier ni les tags ne le confirment — à vérifier", "Kassav'", "Kassav an aksyon")));
        assertEquals(MatchDialog.Kind.LOW_SCORE, kind(s, SkipReason.LOW_SCORE, "Score 50% < 90% — 4 candidat(s)"));
        assertEquals(MatchDialog.Kind.UNREADABLE, kind(s, SkipReason.UNREADABLE, "Audio illisible"));
        assertEquals(MatchDialog.Kind.NOT_FOUND, kind(s, SkipReason.NOT_IDENTIFIED, "Non identifié"));
        assertEquals(MatchDialog.Kind.ALREADY, kind(FileEntry.Status.TAGGED, null, ""));
        assertNull(kind(FileEntry.Status.PENDING, null, ""));
    }

    @Test public void releaseTypesInPlainWords() {
        assertEquals(I18n.t("Album") + ", " + I18n.t("Live"), MatchDialog.releaseType("album;live"));
        assertEquals("EP", MatchDialog.releaseType("ep"));
        assertEquals("", MatchDialog.releaseType(""));
    }
}
