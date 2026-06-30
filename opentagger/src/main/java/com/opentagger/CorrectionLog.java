package com.opentagger;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

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
 */
public class CorrectionLog {

    private static final DateTimeFormatter FMT_FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm");
    private static final DateTimeFormatter FMT_HEAD = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final List<String>    lines     = new ArrayList<>();
    private final LocalDateTime   startTime = LocalDateTime.now();
    private int cntTagged = 0, cntSkipped = 0, cntError = 0;

    public CorrectionLog() {
        lines.add("════════════════════════════════════════════════════════════════");
        lines.add("  OpenTagger — Notes de correction");
        lines.add("  Session : " + startTime.format(FMT_HEAD));
        lines.add("════════════════════════════════════════════════════════════════");
        lines.add("");
    }

    // ── Ajout d'une entrée par fichier traité ──────────────────────────────────

    public void addEntry(FileEntry entry) {
        switch (entry.status) {
            case TAGGED  -> cntTagged++;
            case SKIPPED -> cntSkipped++;
            case ERROR   -> cntError++;
            default      -> {}
        }

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
    }

    // ── Écriture du fichier ────────────────────────────────────────────────────

    public Path flush(Path folder) {
        long elapsedSec = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();

        lines.add("════════════════════════════════════════════════════════════════");
        lines.add("  Résumé de la session");
        lines.add(String.format("  Durée   : %dm %02ds", elapsedSec / 60, elapsedSec % 60));
        lines.add("  Tagués  : " + cntTagged);
        lines.add("  Ignorés : " + cntSkipped);
        lines.add("  Erreurs : " + cntError);
        lines.add("  Total   : " + (cntTagged + cntSkipped + cntError));
        lines.add("════════════════════════════════════════════════════════════════");

        Path dest = resolveOutputPath(folder);
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, lines, StandardCharsets.UTF_8);
            return dest;
        } catch (Exception e) {
            return null;
        }
    }

    private Path resolveOutputPath(Path folder) {
        String filename = "opentagger_" + startTime.format(FMT_FILE) + ".log";
        // Essayer le dossier fourni (dossier de la première piste)
        if (folder != null) {
            Path p = folder.resolve(filename);
            if (Files.isWritable(folder)) return p;
        }
        // Fallback : ~/.opentagger/logs/
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
