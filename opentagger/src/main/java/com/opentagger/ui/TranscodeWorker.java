package com.opentagger.ui;

import com.opentagger.AudioTranscoder;
import com.opentagger.AudioTranscoder.Format;
import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * SwingWorker pour le transcodage en lot.
 * Met à jour {@link FileEntry#currentPath} vers le fichier transcodé.
 *
 * Parallélisé (même clé de config "batch.threads" que TaggingWorker/BatchProcessor/
 * AlbumCompletionWorker/InfoCompleterWorker) — un ffmpeg par fichier, séquentiel avant, avec le
 * même profil "subprocess bloquant par fichier" que la chaîne de repli M4A de TagWriter, déjà
 * parallélisée ailleurs. AudioTranscoder est sans état (seul champ : chemin ffmpeg, final),
 * partagé tel quel entre les tâches.
 */
public class TranscodeWorker extends SwingWorker<String, TranscodeWorker.Progress> {

    public record Progress(FileEntry entry, Path newPath, String error, int done, int total) {}

    private final List<FileEntry>    entries;
    private final FileTableModel     tableModel;
    private final Consumer<Progress> onProgress; // appelé sur EDT via process()
    private final Runnable           onDone;

    // Champ plutôt que variable locale de doInBackground() — même raison que TaggingWorker.pool/
    // AlbumCompletionWorker.pool (voir leurs commentaires) : sans ça, stopNow() n'interromprait
    // que le thread de doInBackground(), pas les tâches ffmpeg déjà soumises au pool.
    private volatile ExecutorService pool;

    public TranscodeWorker(List<FileEntry>    entries,
                           FileTableModel     tableModel,
                           Consumer<Progress> onProgress,
                           Runnable           onDone) {
        this.entries    = entries;
        this.tableModel = tableModel;
        this.onProgress = onProgress;
        this.onDone     = onDone;
    }

    /** À appeler à la place de cancel(true) directement (SwingWorker.cancel() est final) — voir
     *  TaggingWorker.stopNow(), même raison et même correctif. */
    public void stopNow() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        cancel(true);
    }

    @Override
    protected String doInBackground() throws Exception {
        Config cfg    = Config.get();
        Format format = Format.fromId(cfg.transcodeFormat());
        int    bitrate       = cfg.transcodeBitrate();
        boolean deleteSource = cfg.transcodeDeleteSource();

        AudioTranscoder tx = new AudioTranscoder();
        AtomicInteger done = new AtomicInteger(), skipped = new AtomicInteger(), errors = new AtomicInteger();
        int total = entries.size();

        int threads = Math.max(1, cfg.num("batch.threads", 3));
        pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (FileEntry e : entries) {
            if (isCancelled()) break;
            futures.add(pool.submit(() -> {
                if (isCancelled()) return;
                try {
                    Path newPath = tx.transcode(e.currentPath, format, bitrate, deleteSource);
                    if (newPath != null) {
                        done.incrementAndGet();
                        publish(new Progress(e, newPath, null, done.get() + skipped.get() + errors.get(), total));
                    } else {
                        skipped.incrementAndGet();
                        publish(new Progress(e, null, null, done.get() + skipped.get() + errors.get(), total));
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                    String msg = ex.getMessage() != null ? ex.getMessage() : I18n.t("erreur");
                    publish(new Progress(e, null, msg, done.get() + skipped.get() + errors.get(), total));
                }
            }));
        }

        pool.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }

        return I18n.t("Transcodage — ✓ %d converti(s)  déjà OK %d  ✗ %d erreur(s)",
                done.get(), skipped.get(), errors.get());
    }

    @Override
    protected void process(List<Progress> chunks) {
        for (Progress pr : chunks) {
            if (pr.newPath() != null)
                pr.entry().currentPath = pr.newPath();
            if (pr.error() != null)
                pr.entry().message = I18n.t("Transcode : %s", pr.error());
            tableModel.update(pr.entry());
            if (onProgress != null) onProgress.accept(pr);
        }
    }

    @Override
    protected void done() {
        if (onDone != null) onDone.run();
    }
}
