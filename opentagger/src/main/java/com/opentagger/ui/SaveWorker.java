package com.opentagger.ui;

import com.opentagger.CaaClient;
import com.opentagger.Config;
import com.opentagger.DeezerClient;
import com.opentagger.FanArtClient;
import com.opentagger.FileRenamer;
import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzOAuth;
import com.opentagger.TagEnrichment;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * "Enregistrer tout" — écrit réellement sur le disque ce que l'identification (TaggingWorker/
 * AlbumCompletionWorker/InfoCompleterWorker/MatchDialog) a laissé en mémoire (statut IDENTIFIED),
 * façon Picard : le disque n'est touché qu'ici, jamais pendant l'identification. Contrepartie du
 * "Tout tagger" qui, depuis la séparation Identifier/Enregistrer, ne fait plus qu'identifier.
 *
 * Délègue tout le travail par fichier à {@link TagEnrichment#saveEntry}, qui centralise ce qui
 * était auparavant dupliqué (avec des variantes divergentes) dans les quatre pipelines ci-dessus :
 * pochette (résolue ICI, pas avant — voir son commentaire), écriture des tags, renommage, cache/
 * historique, soumission MusicBrainz/AcoustID.
 *
 * Parallélisé (un thread-pool, même clé de config "batch.threads" que TaggingWorker/
 * BatchProcessor/AlbumCompletionWorker/InfoCompleterWorker) — chaque fichier enregistré est
 * indépendant des autres, aucune raison de les sérialiser. CaaClient/FanArtClient/TagWriter/
 * FileRenamer/MusicBrainzOAuth sont sans état (ou protégés en interne), partagés comme dans
 * InfoCompleterWorker ; MetadataCache est fermé une seule fois à la fin, après que toutes les
 * tâches ont fini d'écrire.
 */
public class SaveWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>        entries;
    private final int                    maskIndex;
    private final Consumer<String>       onProgress;
    private final Consumer<FileEntry>    onUpdate;
    private final BiConsumer<Integer,Integer> onCount;

    private final CaaClient         caa      = new CaaClient();
    private final FanArtClient      fanArt   = new FanArtClient();
    private final DeezerClient      deezer   = new DeezerClient();
    private final TagWriter         writer   = new TagWriter();
    private final FileRenamer       renamer  = new FileRenamer();
    private final MusicBrainzOAuth  mbOauth  = new MusicBrainzOAuth();

    private final AtomicInteger doneCount = new AtomicInteger();
    private final AtomicInteger saved     = new AtomicInteger();
    private final AtomicInteger errors    = new AtomicInteger();

    // Champ plutôt que variable locale — même raison que TaggingWorker.pool/AlbumCompletionWorker.pool
    // (voir leurs commentaires) : sans ça, stopNow() n'interromprait que le thread de
    // doInBackground(), pas les fichiers déjà en cours d'enregistrement dans le pool.
    private volatile ExecutorService pool;

    // Pool de connexions SQLite réutilisées — UNE par thread du pool, empruntée/rendue à chaque
    // fichier, PAS une nouvelle par fichier (voir processOne() pour l'historique complet du
    // correctif de 2026-07-29 que ceci remplace). Diagnostiqué en direct le 2026-08-17 via JVM
    // Native Memory Tracking : la catégorie "Other" (mémoire native hors-tas) grimpait de 0,2 Mo à
    // 6,27 Go en 1h de gros lot — cause racine identifiée comme la fragmentation native accumulée
    // par des dizaines de milliers d'ouvertures/fermetures de connexions SQLite (une par fichier
    // enregistré), jamais mesurée à l'époque du correctif de contention de threads. Un pool borné
    // à "threads" instances garde l'absence de verrou partagé (chaque thread a SA PROPRE connexion,
    // jamais accédée par un autre) tout en ramenant le nombre d'ouvertures réelles de centaines de
    // milliers à une poignée pour toute la session.
    private final java.util.concurrent.BlockingQueue<MetadataCache> cachePool =
            new java.util.concurrent.LinkedBlockingQueue<>();

    public SaveWorker(List<FileEntry> entries, int maskIndex,
                       Consumer<String> onProgress, Consumer<FileEntry> onUpdate,
                       BiConsumer<Integer,Integer> onCount) {
        this.entries    = entries;
        this.maskIndex  = maskIndex;
        this.onProgress = onProgress;
        this.onUpdate   = onUpdate;
        this.onCount    = onCount;
    }

    /** À appeler à la place de cancel(true) directement (SwingWorker.cancel() est final) — voir
     *  TaggingWorker.stopNow(), même raison et même correctif. */
    public void stopNow() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        cancel(true);
    }

    @Override
    protected Void doInBackground() throws Exception {
        int total = entries.size();
        int threads = Math.max(1, Config.get().num("batch.threads", 3));
        pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        // Une connexion par thread, créées une fois ici plutôt qu'à la demande dans processOne() —
        // voir le commentaire du champ cachePool pour le pourquoi de ce pool.
        for (int i = 0; i < threads; i++) cachePool.add(new MetadataCache());

        try {
            for (int i = 0; i < entries.size(); i++) {
                if (isCancelled()) break;
                final FileEntry entry   = entries.get(i);
                final int       fileIdx = i + 1;
                futures.add(pool.submit(() -> processOne(entry, fileIdx, total)));
            }

            WorkerHub.awaitAll(pool, futures, WorkerHub.defaultFutureTimeoutSec());
        } finally {
            // Toutes les tâches sont finies (ou annulées) à ce stade — aucune ne détient plus de
            // connexion empruntée, sûr de tout fermer.
            for (MetadataCache c : cachePool) c.close();
        }
        return null;
    }

    /** Enregistre un fichier. Appelé en parallèle, une tâche par fichier, depuis le pool créé
     *  dans doInBackground(). */
    private void processOne(FileEntry entry, int fileIdx, int total) {
        if (isCancelled()) return;

        File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
        onProgress.accept(I18n.t("[%s/%s] %s", fileIdx, total, fichier.getName()));

        if (!fichier.exists()) {
            log(I18n.t("▶ SKIP %s (fichier introuvable)", fichier.getName()));
            markError(entry, I18n.t("Fichier introuvable"));
            finish(entry, total);
            return;
        }

        TagInfo ti = entry.result;
        if (ti == null) {
            log(I18n.t("▶ SKIP %s (rien à enregistrer)", fichier.getName()));
            markError(entry, I18n.t("Rien à enregistrer"));
            finish(entry, total);
            return;
        }

        log(I18n.t("▶ SAVE %s", fichier.getName()));
        // Connexion empruntée au pool (une par thread, jamais accédée par un autre thread pendant
        // le prêt — voir cachePool) plutôt qu'ouverte/fermée à chaque fichier : garde l'absence de
        // verrou partagé du correctif 2026-07-29 (chaque connexion reste propre à SON thread) sans
        // payer le coût mémoire natif d'une connexion SQLite par fichier (voir cachePool pour le
        // diagnostic complet, 2026-08-17).
        MetadataCache cache = cachePool.poll();
        if (cache == null) cache = new MetadataCache(); // filet de sécurité, ne devrait jamais arriver
        try {
            TagEnrichment.SaveResult res = TagEnrichment.saveEntry(
                    fichier, ti, caa, fanArt, deezer, writer, renamer, cache, mbOauth,
                    entry.scanRoot, maskIndex, msg -> log("  " + msg));

            // "Pochette non trouvée" ne peut être établi qu'ICI (résolution différée jusqu'à
            // l'Enregistrement, voir TagEnrichment.saveEntry()) — pas ajouté en double si déjà
            // présent (un fichier peut repasser par "Enregistrer tout" plusieurs fois).
            List<String> sugg = entry.suggestions != null
                    ? new ArrayList<>(entry.suggestions) : new ArrayList<>();
            boolean alreadyFlagged = sugg.stream().anyMatch(s -> s.contains("Pochette"));
            if (res.cover() == null && !alreadyFlagged) sugg.add(I18n.t("Pochette non trouvée"));

            String message = res.renameError() != null
                    ? I18n.t("Enregistré, renommage échoué : %s", res.renameError())
                    : res.durationMismatchMoved()
                        ? I18n.t("Durée incohérente avec MusicBrainz — déplacé pour vérification")
                        : "";

            final TagInfo      writtenFinal = res.written();
            final java.nio.file.Path pathFinal = res.finalPath();
            final List<String> suggFinal = sugg;
            final boolean      durationMismatchMoved = res.durationMismatchMoved();
            SwingUtilities.invokeLater(() -> {
                entry.result           = writtenFinal;
                entry.status           = FileEntry.Status.TAGGED;
                entry.currentPath      = pathFinal;
                entry.suggestions      = suggFinal.isEmpty() ? null : suggFinal;
                entry.message          = message;
                entry.durationMismatch = durationMismatchMoved;
            });
            log(I18n.t("  ✔ ENREGISTRÉ %s", fichier.getName()));
            saved.incrementAndGet();
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log(I18n.t("  ✗ ERROR %s : %s", fichier.getName(), msg));
            markError(entry, msg);
        } finally {
            cachePool.offer(cache);
        }

        finish(entry, total);
    }

    /** Muter entry SUR l'EDT, pas ici : ce FileEntry est aussi lu par le TableRowSorter en direct
     *  depuis l'EDT (déjà vu 697× en 3 jours ailleurs dans le projet — voir SafeTableRowSorter
     *  pour le filet de sécurité). */
    private void markError(FileEntry entry, String message) {
        errors.incrementAndGet();
        SwingUtilities.invokeLater(() -> {
            entry.status  = FileEntry.Status.ERROR;
            entry.message = message;
        });
    }

    private void finish(FileEntry entry, int total) {
        onCount.accept(doneCount.incrementAndGet(), total);
        publish(entry);
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            onProgress.accept(errors.get() > 0
                ? I18n.t("Enregistrement terminé — %d fichier(s) enregistré(s), %d erreur(s)", saved.get(), errors.get())
                : I18n.t("Enregistrement terminé — %d fichier(s) enregistré(s)", saved.get()));
        }
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }
}
