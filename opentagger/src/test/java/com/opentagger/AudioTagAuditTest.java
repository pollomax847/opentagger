package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.opentagger.AudioTagAudit.Candidate;
import com.opentagger.AudioTagAudit.Verdict;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Cas RÉELS de l'échantillon mesuré le 2026-09-20 (250 fichiers "mbid" pris au hasard, empreinte AcoustID
 * comparée aux tags, second avis Shazam) — voir AudioTagAudit — plus le comportement de la base d'audit.
 * Aucun réseau, aucun fichier audio : décision pure et SQLite dans un dossier temporaire.
 */
public class AudioTagAuditTest {

    private static Candidate ac(String artist, String title) { return new Candidate(artist, title, 1.0, 0, ""); }

    private static Verdict verdict(String tagArtist, String tagTitle, Candidate... cands) {
        return AudioTagAudit.compare(tagArtist, tagTitle, List.of(cands)).verdict();
    }

    // ── Les 8 vrais désaccords (confirmés par deux moteurs) ─────────────────────────────────────────

    @Test public void sameArtistWrongTitle_isTitleDiff() {
        // Le cas de l'utilisateur : la vérification de l'étape 0.5 (artiste seul) laissait passer ceux-ci.
        assertEquals(Verdict.TITLE_DIFF, verdict("Bad Bunny", "Un verano sin ti", ac("Bad Bunny", "Después de la playa")));
        assertEquals(Verdict.TITLE_DIFF, verdict("Paul Oakenfold", "Touched By You (mike Hawkins Radio Edit)",
                ac("Paul Oakenfold", "Ibiza (radio edit)")));
    }

    @Test public void completelyDifferentTrack_isOtherTrack() {
        assertEquals(Verdict.OTHER_TRACK, verdict("Atb", "9 Pm (till I Come) (radio Mix)", ac("B.B.E.", "Seven Days and One Week (radio edit)")));
        assertEquals(Verdict.OTHER_TRACK, verdict("Deanna Durbin", "Always", ac("Gentleman", "Time Out")));
        assertEquals(Verdict.OTHER_TRACK, verdict("Tehosekoitin", "Maailma on Sun", ac("Maren Morris", "Dangerous")));
        assertEquals(Verdict.OTHER_TRACK, verdict("Bad Sector", "2001-07-29t22:31:16+01:00 - Peaks", ac("John Legend", "Right by You (For Luna)")));
        assertEquals(Verdict.OTHER_TRACK, verdict("Fakear", "La Belle Âme", ac("Charles Aznavour & Liane Foly", "La Belle et la Bête")));
    }

    // ── Ce qui NE doit PAS être signalé (précision avant rappel) ────────────────────────────────────

    @Test public void variantsOfTheSameSong_areOk() {
        assertEquals(Verdict.OK, verdict("Amerie", "Talkin' to Me (mark Ronson Sunshine Remix)", ac("Amerie", "Talkin' to Me (Mark Ronson Sunshine Remix)")));
        assertEquals(Verdict.OK, verdict("Bad Bunny", "Despues de la Playa", ac("Bad Bunny", "Después de la playa")));   // accents/casse
        assertEquals(Verdict.OK, verdict("Beyonce", "Halo", ac("Beyoncé", "Halo")));
        assertEquals(Verdict.OK, verdict("Paul Oakenfold", "Ibiza", ac("Paul Oakenfold", "Ibiza (radio edit)")));       // qualificatif de version
        assertEquals(Verdict.OK, verdict("Pentatonix", "Hallelujah", ac("Pentatonix & Someone", "Hallelujah")));         // artiste invité
        assertEquals(Verdict.OK, verdict("Queen", "Bohemian Rhapsody - Remastered 2011", ac("Queen", "Bohemian Rhapsody")));
    }

    @Test public void youtubeChannelArtistNames_areOk() {
        assertEquals(Verdict.OK, verdict("AmerieVEVO", "Swag Back", ac("Amerie", "Swag Back")));
        assertEquals(Verdict.OK, verdict("Amerie - Topic", "Swag Back", ac("Amerie", "Swag Back")));
        assertEquals(Verdict.OK, verdict("The Beach Boys", "Don't Talk", ac("Beach Boys", "Don't Talk")));
    }

    @Test public void compilationArtist_cannotContradict() {
        // "Various Artists" est la convention des compilations : ne peut jamais accuser l'audio.
        assertEquals(Verdict.OK, verdict("Various Artists", "Djadja", ac("Aya Nakamura", "Djadja")));
        // Mais le TITRE reste vérifié : une compilation n'excuse pas un mauvais titre.
        assertEquals(Verdict.TITLE_DIFF, verdict("Various Artists", "Un autre titre", ac("Aya Nakamura", "Djadja")));
    }

    @Test public void anyMatchingCandidateClearsTheTag() {
        // Une empreinte renvoie souvent plusieurs enregistrements (rééditions, versions) : un seul suffit.
        assertEquals(Verdict.OK, verdict("Queen", "Bohemian Rhapsody",
                ac("Autre", "Rien à voir"), ac("Queen", "Bohemian Rhapsody (2011 Remaster)")));
    }

    @Test public void sameTitleDifferentArtist_isArtistDiffOnly() {
        // Mix/compilations : l'audio est le bon morceau, l'artiste écrit est le DJ/compilateur.
        assertEquals(Verdict.ARTIST_DIFF, verdict("Cut Killer", "All Night Long", ac("Faith Evans & Puff Daddy", "All Night Long")));
        assertEquals(Verdict.ARTIST_DIFF, verdict("Armin Van Buuren", "Forbidden City", ac("Marc Simz", "Forbidden City")));
        assertFalse(AudioTagAudit.needsSecondOpinion(Verdict.ARTIST_DIFF));
        assertTrue(AudioTagAudit.needsSecondOpinion(Verdict.TITLE_DIFF));
        assertTrue(AudioTagAudit.needsSecondOpinion(Verdict.OTHER_TRACK));
    }

    @Test public void cjkTitlesAreNotErased() {
        assertEquals(Verdict.OK, verdict("つんく♂", "マッスル人形 Perfect Version", ac("つんく♂", "マッスル人形 Perfect Version")));
        assertEquals(Verdict.TITLE_DIFF, verdict("つんく♂", "マッスル人形", ac("つんく♂", "全然ちがう曲")));
    }

    // ── Cas où l'audit ne conclut pas ───────────────────────────────────────────────────────────────

    @Test public void unknownOrWeakFingerprint_isUnverifiable() {
        assertEquals(Verdict.UNVERIFIABLE, AudioTagAudit.compare("A", "B", List.of()).verdict());
        assertEquals(Verdict.UNVERIFIABLE, AudioTagAudit.compare("A", "B", null).verdict());
        Candidate weak = new Candidate("Autre", "Autre chose", 0.6, 0, "");
        AudioTagAudit.Comparison c = AudioTagAudit.compare("A", "B", List.of(weak));
        assertEquals(Verdict.UNVERIFIABLE, c.verdict());
        assertTrue(c.note().contains("faible"));
    }

    @Test public void fileWithoutAnyTag_isUnverifiable() {
        assertEquals(Verdict.UNVERIFIABLE, verdict("", "", ac("X", "Y")));
    }

    @Test public void isSuspect_coversOnlyRealDisagreements() {
        assertTrue(AudioTagAudit.isSuspect(Verdict.TITLE_DIFF));
        assertTrue(AudioTagAudit.isSuspect(Verdict.OTHER_TRACK));
        assertTrue(AudioTagAudit.isSuspect(Verdict.ARTIST_DIFF));
        assertFalse(AudioTagAudit.isSuspect(Verdict.OK));
        assertFalse(AudioTagAudit.isSuspect(Verdict.UNVERIFIABLE));
        assertFalse(AudioTagAudit.isSuspect(Verdict.ERROR));
    }

    // ── Base d'audit ────────────────────────────────────────────────────────────────────────────────

    private static AudioAuditStore.Row row(String path, long mtime, long size, String verdict, String shazam, long ts) {
        return new AudioAuditStore.Row(path, mtime, size, verdict, shazam, "TagA", "TagT", "AcA", "AcT", 0.97, 200,
                "", "", 201, "", false, ts);
    }

    @Test public void store_roundTrip_userOkSurvivesOnlyWhileFileIsUnchanged() throws Exception {
        Path tmp = Files.createTempDirectory("otaudit");
        try (AudioAuditStore s = AudioAuditStore.open(tmp.resolve("a.db"))) {
            assertNull(s.get("/x.mp3"));
            s.put(row("/x.mp3", 100, 5000, "TITLE_DIFF", "AGREE", 1));
            AudioAuditStore.Row r = s.get("/x.mp3");
            assertNotNull(r);
            assertEquals("TITLE_DIFF", r.verdict());
            assertEquals("AGREE", r.shazam());
            assertEquals(0.97, r.acScore(), 1e-9);
            assertFalse(r.userOk());
            assertTrue(AudioAuditStore.isCurrent(r, 100, 5000));
            assertFalse(AudioAuditStore.isCurrent(r, 101, 5000));   // mtime changé → périmé
            assertFalse(AudioAuditStore.isCurrent(r, 100, 5001));   // taille changée → périmé

            s.markUserOk("/x.mp3");
            assertTrue(s.get("/x.mp3").userOk());
            assertTrue("écarté par l'utilisateur : plus listé", s.suspects(true).isEmpty());

            // Ré-audit du MÊME état de fichier : le choix de l'utilisateur est conservé.
            s.put(row("/x.mp3", 100, 5000, "TITLE_DIFF", "AGREE", 2));
            assertTrue(s.get("/x.mp3").userOk());

            // Le fichier a changé (re-taguage) : ancien choix caduc, le fichier peut être re-signalé.
            s.put(row("/x.mp3", 200, 5100, "TITLE_DIFF", "AGREE", 3));
            assertFalse(s.get("/x.mp3").userOk());
            assertEquals(1, s.suspects(false).size());
        }
    }

    @Test public void store_suspects_orderedAndFiltered() throws Exception {
        Path tmp = Files.createTempDirectory("otaudit");
        try (AudioAuditStore s = AudioAuditStore.open(tmp.resolve("b.db"))) {
            s.put(row("/ok.mp3",      1, 1, "OK",           "",       1));
            s.put(row("/unv.mp3",     1, 1, "UNVERIFIABLE", "",       1));
            s.put(row("/artist.mp3",  1, 1, "ARTIST_DIFF",  "",       9));
            s.put(row("/single.mp3",  1, 1, "TITLE_DIFF",   "SILENT", 5));
            s.put(row("/two.mp3",     1, 1, "OTHER_TRACK",  "AGREE",  2));
            s.put(row("/diverge.mp3", 1, 1, "OTHER_TRACK",  "OTHER",  3));

            List<AudioAuditStore.Row> strict = s.suspects(false);
            assertEquals("ARTIST_DIFF masqué par défaut", 3, strict.size());
            assertEquals("deux moteurs d'accord d'abord", "/two.mp3", strict.get(0).path());
            assertEquals("puis AcoustID seul", "/single.mp3", strict.get(1).path());
            assertEquals("puis moteurs divergents", "/diverge.mp3", strict.get(2).path());

            List<AudioAuditStore.Row> all = s.suspects(true);
            assertEquals(4, all.size());
            assertEquals("la différence d'artiste seul passe en dernier", "/artist.mp3", all.get(3).path());

            Map<String, Integer> counts = s.countsByVerdict();
            assertEquals(Integer.valueOf(1), counts.get("OK"));
            assertEquals(Integer.valueOf(2), counts.get("OTHER_TRACK"));
            assertEquals(Integer.valueOf(1), counts.get("UNVERIFIABLE"));
        }
    }
}
