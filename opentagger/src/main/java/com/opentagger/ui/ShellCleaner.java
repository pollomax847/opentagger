package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.ProcessUtils;
import com.opentagger.TrashHelper;
import com.opentagger.model.FileEntry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Coquilles vides écartées au scan (2026-09-26, demande utilisateur « on a énormément de coquilles
 * vides dans la bibliothèque »). Mesuré ce jour : 162 fichiers audio SANS AUCUN flux sonore, dont
 * 152 identiques (1 348 octets : une étiquette ID3 vide et rien d'autre, noms « __… »), plus les
 * fichiers de 0 octet. Ils passaient par AcoustID/Shazam pour rien (songrec y restait bloqué jusqu'au
 * timeout) et finissaient « Non identifié ».
 *
 * Coquille = 0 octet, OU moins de 64 Ko ET aucun flux audio selon ffprobe (un vrai son court —
 * méthode de langue, jingle — a un flux audio et n'est jamais concerné). Marquée « Coquille vide »,
 * exclue du taguage, et envoyée dans la corbeille d'OpenTagger (récupérable) si
 * shells.auto_trash_enabled, plafonné par session, journalisé dans ~/.opentagger/coquilles.log.
 */
public final class ShellCleaner {

    private ShellCleaner() {}

    static final long MAX_SHELL_BYTES = 64 * 1024;

    private static final AtomicInteger TRASHED = new AtomicInteger();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shell-cleaner");
        t.setDaemon(true);
        return t;
    });

    /** Appelé en arrière-plan (lecture de tags du scan). Jamais vrai si ffprobe est indisponible. */
    public static boolean isShell(File f, long size) {
        if (size == 0) return true;
        if (size < 0 || size >= MAX_SHELL_BYTES) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(Config.get().str("audio.ffprobe_path", "ffprobe"),
                    "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_name",
                    "-of", "csv=p=0", f.getAbsolutePath());
            pb.redirectErrorStream(false);
            // Sortie vide = aucun flux audio. On distingue « ffprobe absent/planté » (exception) —
            // dans ce cas, jamais de verdict « coquille ».
            Process probe = new ProcessBuilder(Config.get().str("audio.ffprobe_path", "ffprobe"), "-version").start();
            if (!probe.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || probe.exitValue() != 0) return false;
            String out = ProcessUtils.readStringWithTimeout(pb, 10);
            return out == null || out.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /** Sur l'EDT, une fois l'entrée marquée « Coquille vide ». */
    public static void handle(FileEntry entry, Consumer<FileEntry> onRemoved) {
        if (!Config.get().bool("shells.auto_trash_enabled", false)) return;
        int max = Config.get().num("shells.auto_trash_max_per_run", 500);
        File f = (entry.currentPath != null ? entry.currentPath.toFile() : entry.file);
        IO.submit(() -> {
            if (TRASHED.get() >= max || !f.isFile()) return;
            long size = f.length();
            if (size >= MAX_SHELL_BYTES) return;
            if (!TrashHelper.moveToTrash(f)) return;
            TRASHED.incrementAndGet();
            System.out.println("[OT] 🐚 Coquille vide → corbeille OpenTagger : " + f + " (" + size + " octets)");
            try {
                Files.writeString(Paths.get(Config.configDir(), "coquilles.log"),
                        java.time.LocalDateTime.now() + "\t" + f + "\t" + size + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
            javax.swing.SwingUtilities.invokeLater(() -> onRemoved.accept(entry));
        });
    }
}
