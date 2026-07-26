package com.opentagger;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Journalise les corrections appliquées par un batch de taguage.
 * Un fichier .log est écrit à la fin de chaque session dans le dossier
 * de la première piste traitée (ou dans ~/.opentagger/ en fallback).
 *
 * Écrit INCRÉMENTALEMENT (une ligne par entrée, flush() sur chaque écriture) dans un fichier
 * temporaire dès la construction, plutôt que d'accumuler tout le journal en RAM jusqu'à un
 * flush() final — avant ce correctif, un crash/kill de l'appli en plein scan (OOM, coupure de
 * courant, force-quit) perdait la totalité des notes de correction de la session, potentiellement
 * des heures de taguage sur une grosse bibliothèque. flush(folder) ne fait plus qu'écrire le
 * résumé final et déplacer ce fichier temporaire déjà à jour vers la destination définitive.
 */
public class CorrectionLog {

    private static final DateTimeFormatter FMT_FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final DateTimeFormatter FMT_HEAD = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final LocalDateTime  startTime = LocalDateTime.now();
    private final Path           tempPath;
    private final BufferedWriter writer;
    private int cntTagged = 0, cntSkipped = 0, cntError = 0;

    public CorrectionLog() {
        String filename = "opentagger_" + startTime.format(FMT_FILE) + ".log";
        Path tmp;
        BufferedWriter w;
        try {
            Path logsDir = Path.of(Config.configDir(), "logs");
            Files.createDirectories(logsDir);
            tmp = logsDir.resolve(filename);
            w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Repli silencieux si ~/.opentagger/logs est inaccessible (permissions, disque plein) :
            // writer reste null, writeLines() ne fait rien, flush() renverra alors null comme avant
            // ce correctif dans ce cas déjà dégradé.
            tmp = null;
            w = null;
        }
        tempPath = tmp;
        writer = w;

        writeLines(
            "════════════════════════════════════════════════════════════════",
            "  OpenTagger — Notes de correction",
            "  Session : " + startTime.format(FMT_HEAD),
            "════════════════════════════════════════════════════════════════",
            "");
    }

    private void writeLines(String... ls) {
        if (writer == null) return;
        try {
            for (String l : ls) { writer.write(l); writer.newLine(); }
            writer.flush();
        } catch (Exception ignored) {}
    }

    // ── Ajout d'une entrée par fichier traité ──────────────────────────────────

    // synchronized : plusieurs fichiers peuvent être traités en parallèle (TaggingWorker,
    // Executors.newFixedThreadPool) et partagent tous la même instance de CorrectionLog.
    public synchronized void addEntry(FileEntry entry) {
        switch (entry.status) {
            case TAGGED  -> cntTagged++;
            case SKIPPED -> cntSkipped++;
            case ERROR   -> cntError++;
            default      -> {}
        }

        List<String> lines = new ArrayList<>();
        lines.add("────────────────────────────────────────────────────────────────");
        lines.add("Fichier : " + entry.filename());
        lines.add("Statut  : " + statusLabel(entry.status));

        TagInfo after = entry.result;
        if (after != null) {
            lines.add("Score   : " + after.score + "%");
            TagInfo before = entry.current != null ? entry.current : new TagInfo();

            List<String> changed = diffTags(before, after);
            lines.add("");
            if (!changed.isEmpty()) {
                lines.add("Tags modifiés (" + changed.size() + ") :");
                for (String c : changed) lines.add("  " + c);
            } else {
                lines.add("Tags : inchangés (déjà corrects)");
            }
        } else if (!entry.message.isBlank()) {
            lines.add("Message : " + entry.message);
        }

        if (entry.suggestions != null && !entry.suggestions.isEmpty()) {
            lines.add("");
            lines.add("Suggestions d'amélioration :");
            for (String s : entry.suggestions) lines.add("  ⚠  " + s);
        }

        lines.add("");
        writeLines(lines.toArray(new String[0]));
    }

    // ── Écriture du fichier ────────────────────────────────────────────────────

    public Path flush(Path folder) {
        long elapsedSec = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();

        writeLines(
            "════════════════════════════════════════════════════════════════",
            "  Résumé de la session",
            String.format("  Durée   : %dm %02ds", elapsedSec / 60, elapsedSec % 60),
            "  Tagués  : " + cntTagged,
            "  Ignorés : " + cntSkipped,
            "  Erreurs : " + cntError,
            "  Total   : " + (cntTagged + cntSkipped + cntError),
            "════════════════════════════════════════════════════════════════");
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}

        if (tempPath == null) return null; // writer jamais ouvert (voir constructeur)

        Path dest = resolveOutputPath(folder);
        try {
            Files.createDirectories(dest.getParent());
            if (!tempPath.equals(dest)) Files.move(tempPath, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        } catch (Exception e) {
            // Le déplacement vers le dossier de la bibliothèque a échoué (cross-device, permissions
            // sur un montage NAS) — le journal reste consultable à son emplacement temporaire,
            // jamais perdu (c'est tout l'intérêt de l'écriture incrémentale ci-dessus).
            return tempPath;
        }
    }

    private Path resolveOutputPath(Path folder) {
        String filename = "opentagger_" + startTime.format(FMT_FILE) + ".log";
        // Essayer le dossier fourni (dossier de la première piste)
        if (folder != null) {
            Path p = folder.resolve(filename);
            if (Files.isWritable(folder)) return p;
        }
        // Fallback : ~/.opentagger/logs/ (c'est déjà là que tempPath a été créé)
        return Path.of(Config.configDir(), "logs", filename);
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private static List<String> diffTags(TagInfo before, TagInfo after) {
        List<String> diffs = new ArrayList<>();
        String[][] fields = {
            {"titre",         before.title,            after.title},
            {"artiste",       before.artist,           after.artist},
            {"artiste album", before.albumArtist,      after.albumArtist},
            {"album",         before.album,            after.album},
            {"année",         before.year,             after.year},
            {"genre",         before.genre,            after.genre},
            {"piste",         before.track,            after.track},
            {"disque",        before.discNo,           after.discNo},
            {"compositeur",   before.composer,         after.composer},
            {"commentaire",   before.comment,          after.comment},
            {"BPM",           before.bpm,              after.bpm},
            {"ReplayGain",    before.replayGainTrackGain, after.replayGainTrackGain},
            {"MBID",          before.recordingMbid,    after.recordingMbid},
            {"pochette",      before.releaseMbid,      after.releaseMbid}, // proxy
        };
        for (String[] f : fields) {
            String bv = f[1] != null ? f[1].trim() : "";
            String av = f[2] != null ? f[2].trim() : "";
            if (bv.equals(av)) continue;
            if (bv.isBlank() && av.isBlank()) continue;
            if      (bv.isBlank()) diffs.add(f[0] + " : [vide] → \"" + av + "\"");
            else if (av.isBlank()) diffs.add(f[0] + " : \"" + bv + "\" → [effacé]");
            else                   diffs.add(f[0] + " : \"" + bv + "\" → \"" + av + "\"");
        }
        return diffs;
    }

    private static String statusLabel(FileEntry.Status s) {
        return switch (s) {
            case TAGGED   -> "✔ Tagué";
            case SKIPPED  -> "— Ignoré";
            case ERROR    -> "✗ Erreur";
            case PENDING  -> "? En attente";
            default       -> s.name();
        };
    }
}
