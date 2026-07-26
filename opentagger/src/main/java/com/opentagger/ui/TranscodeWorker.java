package com.opentagger.ui;

import com.opentagger.AudioTranscoder;
import com.opentagger.AudioTranscoder.Format;
import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import java.awt.Desktop;
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

    /** unreadable = CONFIRMÉ illisible (double vérification, voir AudioTranscoder.
     *  verifyUnreadable()) — pas juste un premier message d'erreur qui y ressemble. trashed =
     *  effectivement envoyé à la corbeille système (implique unreadable, mais pas l'inverse : le
     *  réglage peut être désactivé, ou l'envoi à la corbeille peut lui-même échouer). */
    public record Progress(FileEntry entry, Path newPath, String error, int done, int total,
                            boolean unreadable, boolean trashed) {}

    private final List<FileEntry>    entries;
    private final FileTableModel     tableModel;
    private final Consumer<Progress> onProgress; // appelé sur EDT via process()
    private final Consumer<String>   onDone;      // reçoit le résumé — voir done() ci-dessous

    // Champ plutôt que variable locale de doInBackground() — même raison que TaggingWorker.pool/
    // AlbumCompletionWorker.pool (voir leurs commentaires) : sans ça, stopNow() n'interromprait
    // que le thread de doInBackground(), pas les tâches ffmpeg déjà soumises au pool.
    private volatile ExecutorService pool;

    public TranscodeWorker(List<FileEntry>    entries,
                           FileTableModel     tableModel,
                           Consumer<Progress> onProgress,
                           Consumer<String>   onDone) {
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
                        publish(new Progress(e, newPath, null, done.get() + skipped.get() + errors.get(), total, false, false));
                    } else {
                        skipped.incrementAndGet();
                        publish(new Progress(e, null, null, done.get() + skipped.get() + errors.get(), total, false, false));
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                    String msg = ex.getMessage() != null ? ex.getMessage() : I18n.t("erreur");
                    Path curPath = e.currentPath != null ? e.currentPath : e.file.toPath();

                    // Ne jamais isoler un fichier sur la seule foi de CE message d'erreur : un
                    // échec de transcodage peut venir d'ailleurs (codec de sortie manquant, bitrate
                    // invalide...). Contre-vérification indépendante — un second appel ffmpeg,
                    // décodage seul vers "null", rien écrit sur le disque — seulement si ce premier
                    // message ressemble déjà à une source corrompue ; les deux doivent être
                    // d'accord avant de considérer le fichier réellement illisible.
                    boolean unreadable = AudioTranscoder.isUnreadableSourceError(msg)
                            && tx.verifyUnreadable(curPath);

                    // Corbeille système (récupérable), jamais suppression définitive — voir
                    // MainFrame.deleteErrorFiles() pour le même choix sur les fichiers illisibles
                    // détectés manuellement.
                    boolean trashed = false;
                    if (unreadable && Config.get().transcodeMoveUnreadableEnabled()) {
                        try {
                            Desktop desktop = Desktop.getDesktop();
                            if (desktop.isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                                trashed = desktop.moveToTrash(curPath.toFile());
                            }
                        } catch (Exception trashEx) {
                            msg = msg + " (corbeille échouée: " + trashEx.getMessage() + ")";
                        }
                    }
                    publish(new Progress(e, null, msg, done.get() + skipped.get() + errors.get(), total, unreadable, trashed));
                }
            }));
        }

        WorkerHub.awaitAll(pool, futures, WorkerHub.defaultFutureTimeoutSec());

        return I18n.t("Transcodage — ✓ %d converti(s)  déjà OK %d  ✗ %d erreur(s)",
                done.get(), skipped.get(), errors.get());
    }

    @Override
    protected void process(List<Progress> chunks) {
        for (Progress pr : chunks) {
            if (pr.trashed()) {
                // Fichier parti à la corbeille : plus de ligne à mettre à jour, juste à retirer
                // du tableau (le fichier lui-même n'existe plus à cet emplacement).
                int idx = tableModel.indexOf(pr.entry());
                if (idx >= 0) tableModel.remove(idx);
                if (onProgress != null) onProgress.accept(pr);
                continue;
            }
            if (pr.unreadable())
                pr.entry().status = FileEntry.Status.ERROR;
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
        if (onDone == null) return;
        // get() ici (jamais bloquant : doInBackground() est déjà terminé quand done() est appelé)
        // plutôt que de forcer l'appelant à s'auto-référencer pour récupérer le résumé — voir
        // MainFrame.transcodeFiles(), qui n'a plus besoin de connaître son propre worker.
        String summary;
        try { summary = get(); } catch (Exception ex) { summary = I18n.t("Transcodage terminé"); }
        onDone.accept(summary);
    }
}
