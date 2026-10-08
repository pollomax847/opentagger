package com.opentagger;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * Garantit qu'AUCUN champ de {@link TagInfo} n'est écrit sans être relu (et inversement) : écrit un TagInfo
 * dont CHAQUE champ texte est rempli, via TagWriter, sur de vrais fichiers audio synthétiques (dossier
 * temporaire — jamais la bibliothèque de l'utilisateur), puis relit via TagReader et compare champ par champ.
 *
 * Existe parce que MainFrame.readTags() ne relisait pas 33 champs que TagWriter écrit (2026-09-20 — voir
 * TagFieldRegistry), et parce que l'écriture native M4A échouait systématiquement depuis l'introduction
 * du premier champ personnalisé (Mp4TagTextField au lieu de Mp4TagReverseDnsField pour les atomes "----",
 * voir TagWriter.writeMp4Freeform) — deux régressions silencieuses qu'aucun test n'attrapait.
 *
 * Nécessite ffmpeg (génération des fichiers de test) : test ignoré s'il est absent. Les réglages de
 * l'utilisateur (~/.opentagger) sont lus mais JAMAIS modifiés par ce test.
 */
public class TagRoundTripTest {

    /** Non persistés dans le fichier, ou dépendants d'un réglage utilisateur (notation Camelot de la
     *  tonalité, empreintes AcoustID désactivables) — hors comparaison. */
    private static final Set<String> SKIP = Set.of(
            "identificationSource", "shazamCoverUrl", "syncedLyrics", "remixerSort", "taggedDate",
            "year", "initialKey", "acoustidId", "acoustidFingerprint");

    private static TagInfo fullTagInfo() throws Exception {
        TagInfo t = new TagInfo();
        for (Field f : TagInfo.class.getFields()) {
            if (f.getType() == String.class) f.set(t, "V_" + f.getName());
        }
        t.year = "2014"; t.date = "2014-05-15"; t.originalYear = "2013"; t.originalDate = "2013-11-02";
        t.track = "3"; t.trackTotal = "12"; t.discNo = "1"; t.discTotal = "2";
        t.movementNo = "2"; t.movementTotal = "4"; t.bpm = "120"; t.fbpm = "120.5"; t.rating = "4";
        for (String flag : new String[]{"isClassical", "isCompilation", "isHD", "isLive",
                                        "isGreatestHits", "isSoundtrack", "isInstrumental"})
            TagInfo.class.getField(flag).set(t, "1");
        return t;
    }

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private static File synth(Path dir, String name, String... codecArgs) throws Exception {
        File out = dir.resolve(name).toFile();
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "quiet", "-y", "-f", "lavfi",
                "-i", "sine=frequency=440:duration=3"));
        cmd.addAll(List.of(codecArgs));
        cmd.add(out.getAbsolutePath());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue("ffmpeg n'a pas produit " + name, p.waitFor() == 0 && out.isFile());
        return out;
    }

    private static List<String> diff(TagInfo expected, TagInfo got) throws Exception {
        List<String> bad = new ArrayList<>();
        for (Field f : TagInfo.class.getFields()) {
            if (f.getType() != String.class || SKIP.contains(f.getName())) continue;
            String e = (String) f.get(expected);
            String g = (String) f.get(got);
            if (!e.equals(g == null ? "" : g)) bad.add(f.getName() + " [attendu=" + e + " lu=" + g + "]");
        }
        return bad;
    }

    @Test
    public void tousLesChampsSontRelusApresEcriture() throws Exception {
        assumeTrue("ffmpeg absent", ffmpegAvailable());
        Path dir = Files.createTempDirectory("ot_roundtrip_");
        File[] files = {
            synth(dir, "t.mp3",  "-c:a", "libmp3lame"),
            synth(dir, "t.flac", "-c:a", "flac"),
            synth(dir, "t.ogg",  "-c:a", "libvorbis"),
            // M4A : format "ipod" (atomes comme un fichier iTunes) — c'est CE cas qui échouait en natif.
            synth(dir, "t.m4a",  "-c:a", "aac", "-f", "ipod", "-movflags", "+faststart"),
            synth(dir, "t.opus", "-c:a", "libopus"),   // chemin ffmpeg (FfmpegTagIO)
        };
        TagInfo expected = fullTagInfo();
        List<String> report = new ArrayList<>();
        for (File f : files) {
            new TagWriter().write(f, expected);
            List<String> bad = diff(expected, TagReader.read(f));
            if (!bad.isEmpty()) report.add(f.getName() + " → " + bad.size() + " écart(s) : " + bad);
        }
        assertTrue("Champs écrits mais non relus (ou perdus) :\n" + String.join("\n", report), report.isEmpty());
    }
}
