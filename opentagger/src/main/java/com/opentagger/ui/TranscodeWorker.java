package com.opentagger.ui;

import com.opentagger.AudioTranscoder;
import com.opentagger.AudioTranscoder.Format;
import com.opentagger.Config;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * SwingWorker pour le transcodage en lot.
 * Met à jour {@link FileEntry#currentPath} vers le fichier transcodé.
 */
public class TranscodeWorker extends SwingWorker<String, TranscodeWorker.Progress> {

    public record Progress(FileEntry entry, Path newPath, String error, int done, int total) {}

    private final List<FileEntry>    entries;
    private final FileTableModel     tableModel;
    private final Consumer<Progress> onProgress; // appelé sur EDT via process()
    private final Runnable           onDone;

    public TranscodeWorker(List<FileEntry>    entries,
                           FileTableModel     tableModel,
                           Consumer<Progress> onProgress,
                           Runnable           onDone) {
        this.entries    = entries;
        this.tableModel = tableModel;
        this.onProgress = onProgress;
        this.onDone     = onDone;
    }

    @Override
    protected String doInBackground() {
        Config cfg    = Config.get();
        Format format = Format.fromId(cfg.transcodeFormat());
        int    bitrate       = cfg.transcodeBitrate();
        boolean deleteSource = cfg.transcodeDeleteSource();

        AudioTranscoder tx = new AudioTranscoder();
        int done = 0, skipped = 0, errors = 0;
        int total = entries.size();

        for (int i = 0; i < entries.size(); i++) {
            if (isCancelled()) break;
            FileEntry e = entries.get(i);
            try {
                Path newPath = tx.transcode(e.currentPath, format, bitrate, deleteSource);
                if (newPath != null) {
                    done++;
                    publish(new Progress(e, newPath, null, done + skipped + errors, total));
                } else {
                    skipped++;
                    publish(new Progress(e, null, null, done + skipped + errors, total));
                }
            } catch (Exception ex) {
                errors++;
                String msg = ex.getMessage() != null ? ex.getMessage() : "erreur";
                publish(new Progress(e, null, msg, done + skipped + errors, total));
            }
        }
        return String.format("Transcodage — ✓ %d converti(s)  déjà OK %d  ✗ %d erreur(s)",
                done, skipped, errors);
    }

    @Override
    protected void process(List<Progress> chunks) {
        for (Progress pr : chunks) {
            if (pr.newPath() != null)
                pr.entry().currentPath = pr.newPath();
            if (pr.error() != null)
                pr.entry().message = "Transcode : " + pr.error();
            tableModel.update(pr.entry());
            if (onProgress != null) onProgress.accept(pr);
        }
    }

    @Override
    protected void done() {
        if (onDone != null) onDone.run();
    }
}
