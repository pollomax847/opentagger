package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * "Récupérer l'audio des vidéos non reconnues" — identifie chaque fichier vidéo d'un dossier
 * (SongRec → AcoustID → AudD, voir {@link TagEnrichment#identifyFromAudio}) et, seulement si
 * reconnu, extrait l'audio en MP3 ({@link AudioTranscoder}), le tague/range
 * ({@link TagEnrichment#saveEntry}, même point d'écriture partagé que SaveWorker/PodcastWorker/
 * AlbumCompletionWorker) puis déplace la vidéo d'origine dans un sous-dossier "Convertis" à côté
 * d'elle — jamais supprimée : un test réel sur les 5 vraies vidéos de l'utilisateur a montré qu'une
 * identification SongRec peut être un faux positif confirmé par MusicBrainz (score ≥ 50 n'y change
 * rien, MB ne vérifie que l'existence d'un enregistrement pour cet artiste/titre, pas que l'audio
 * correspond vraiment) — la vidéo doit donc rester récupérable après coup, pas supprimée sur un
 * verdict qui peut être faux. Une vidéo non reconnue est déplacée dans un sous-dossier "Non
 * identifié" à côté d'elle (même logique de non-suppression) — permet de distinguer d'un coup d'œil
 * ce qui reste à traiter manuellement de ce qui n'a simplement pas encore été scanné, et évite de la
 * réessayer indéfiniment à chaque scan du même dossier ({@link VideoScanner} exclut ce sous-dossier
 * de sa récursion). Si l'enregistrement échoue après transcodage, la vidéo n'est PAS déplacée — le
 * MP3 orphelin produit dans ce cas est nettoyé pour ne pas laisser un doublon non tagué à côté de
 * la vidéo intacte.
 *
 * Parallélisé (même clé "batch.threads" que TaggingWorker/SaveWorker/AlbumCompletionWorker).
 * SongRecClient/AudDClient/CaaClient/FanArtClient/TagWriter/FileRenamer/MusicBrainzOAuth sont sans
 * état, partagés entre tâches (même motif que SaveWorker) ; MusicBrainzClient/AcoustIdClient
 * tiennent un état mutable entre appels, instance fraîche par tâche (même motif que TaggingWorker/
 * AlbumCompletionWorker).
 */
public class VideoRecoveryWorker extends SwingWorker<Void, String> {

    private final List<File> videos;
    private final Path       scanRoot;
    private final Consumer<String> onProgress;
    // Compteur numérique séparé du texte (onProgress ci-dessus) — pour la barre de progression
    // partagée (bas-droite de MainFrame), absente jusqu'ici sur le déclenchement AUTOMATIQUE de ce
    // worker (après chaque scan de dossier, voir MainFrame) alors que fileIdx/total étaient déjà
    // connus ici (déjà utilisés dans le message texte "[fileIdx/total] nom"). Retour utilisateur,
    // 2026-08-10.
    private final java.util.function.BiConsumer<Integer, Integer> onNumericProgress;

    private final SongRecClient    songRec = new SongRecClient();
    private final AudDClient       audd     = new AudDClient();
    private final CaaClient        caa      = new CaaClient();
    private final FanArtClient     fanArt   = new FanArtClient();
    private final DeezerClient     deezer   = new DeezerClient();
    private final TagWriter        writer   = new TagWriter();
    private final FileRenamer      renamer  = new FileRenamer();
    private final MusicBrainzOAuth mbOauth  = new MusicBrainzOAuth();

    private final AtomicInteger converted    = new AtomicInteger();
    private final AtomicInteger unrecognized = new AtomicInteger();
    private final AtomicInteger errors       = new AtomicInteger();

    // Champ plutôt que variable locale — même raison que SaveWorker.pool/TaggingWorker.pool (voir
    // leurs commentaires) : sans ça, stopNow() n'interromprait que le thread de doInBackground(),
    // pas les fichiers déjà en cours de traitement dans le pool.
    private volatile ExecutorService pool;

    // Pool de connexions SQLite — une par thread, voir SaveWorker.cachePool pour le diagnostic
    // complet (2026-08-17) : même anti-motif "une connexion par fichier" que SaveWorker/
    // InfoCompleterWorker avant leur correctif, moindre impact ici (bien moins de vidéos que de
    // fichiers audio dans une bibliothèque) mais corrigé pour la même raison.
    private final java.util.concurrent.BlockingQueue<MetadataCache> cachePool =
            new java.util.concurrent.LinkedBlockingQueue<>();

    public VideoRecoveryWorker(List<File> videos, Path scanRoot, Consumer<String> onProgress) {
        this(videos, scanRoot, onProgress, null);
    }

    public VideoRecoveryWorker(List<File> videos, Path scanRoot, Consumer<String> onProgress,
                                java.util.function.BiConsumer<Integer, Integer> onNumericProgress) {
        this.videos            = videos;
        this.scanRoot          = scanRoot;
        this.onProgress        = onProgress;
        this.onNumericProgress = onNumericProgress;
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
        int total = videos.size();
        int threads = Math.max(1, Config.get().num("batch.threads", 3));
        pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        int maskIndex = Config.get().autoRenameEnabled() ? Config.get().defaultRenameMask() : -1;

        for (int i = 0; i < threads; i++) cachePool.add(new MetadataCache());

        for (int i = 0; i < total; i++) {
            if (isCancelled()) break;
            final File video   = videos.get(i);
            final int  fileIdx = i + 1;
            futures.add(pool.submit(() -> processOne(video, fileIdx, total, maskIndex)));
        }

        pool.shutdown();
        try {
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            for (MetadataCache c : cachePool) c.close();
        }
        return null;
    }

    private void processOne(File video, int fileIdx, int total, int maskIndex) {
        if (isCancelled()) return;
        publish(I18n.t("[%s/%s] %s", fileIdx, total, video.getName()));
        if (onNumericProgress != null) onNumericProgress.accept(fileIdx, total);

        TagInfo ti;
        try {
            ti = TagEnrichment.identifyFromAudio(video, songRec, new AcoustIdClient(), audd,
                    new MusicBrainzClient(), msg -> publish("  " + msg));
        } catch (Exception ex) {
            errors.incrementAndGet();
            publish(I18n.t("  ✗ ERREUR identification %s : %s", video.getName(), ex.getMessage()));
            return;
        }

        if (ti == null) {
            unrecognized.incrementAndGet();
            try {
                Path movedTo = moveToSubfolder(video, VideoScanner.UNRECOGNIZED_FOLDER);
                publish(I18n.t("  — non reconnu, déplacée vers : %s", movedTo));
            } catch (Exception ex) {
                publish(I18n.t("  — non reconnu, déplacement échoué (%s), vidéo conservée : %s",
                        ex.getMessage(), video.getName()));
            }
            return;
        }

        Path mp3Path = null;
        try {
            mp3Path = new AudioTranscoder().transcode(video.toPath(),
                    AudioTranscoder.Format.MP3, Config.get().transcodeBitrate(), false);
            if (mp3Path == null) {
                // Extension déjà "mp3" — ne devrait jamais arriver pour un scan vidéo, mais par
                // sécurité on ne tague jamais la vidéo elle-même (jaudiotagger ne la lit pas).
                throw new IOException("Extraction audio impossible (format déjà mp3 ?)");
            }

            // Connexion empruntée au pool (une par thread, voir cachePool) plutôt qu'ouverte/
            // fermée à chaque vidéo.
            TagEnrichment.SaveResult res;
            MetadataCache cache = cachePool.poll();
            if (cache == null) cache = new MetadataCache(); // filet de sécurité
            try {
                res = TagEnrichment.saveEntry(mp3Path.toFile(), ti, caa, fanArt,
                        deezer, writer, renamer, cache, mbOauth, scanRoot, maskIndex, msg -> publish("  " + msg));
            } finally {
                cachePool.offer(cache);
            }

            Path movedTo = moveToSubfolder(video, VideoScanner.CONVERTED_FOLDER);
            converted.incrementAndGet();
            publish(I18n.t("  ✔ CONVERTI %s → %s – %s (%s) — vidéo déplacée : %s", video.getName(),
                    res.written().artist, res.written().title, res.finalPath(), movedTo));
        } catch (Exception ex) {
            errors.incrementAndGet();
            publish(I18n.t("  ✗ ERREUR %s : %s", video.getName(), ex.getMessage()));
            // Ne jamais laisser un MP3 non tagué/orphelin à côté d'une vidéo qu'on n'a PAS
            // déplacée.
            if (mp3Path != null) {
                try { Files.deleteIfExists(mp3Path); } catch (Exception ignored) {}
            }
        }
    }

    /** Déplace la vidéo dans un sous-dossier à côté d'elle (jamais supprimée — voir la Javadoc de
     *  la classe pour le pourquoi) — collision de nom évitée par un suffixe numérique, même motif
     *  que {@link AudioTranscoder#transcode}. {@code folderName} est toujours l'une des constantes
     *  {@link VideoScanner#CONVERTED_FOLDER}/{@link VideoScanner#UNRECOGNIZED_FOLDER}, que
     *  VideoScanner exclut explicitement de sa récursion pour ne jamais retraiter indéfiniment les
     *  mêmes vidéos déjà triées lors d'un scan ultérieur du même dossier. */
    private Path moveToSubfolder(File video, String folderName) throws IOException {
        Path targetDir = video.toPath().getParent().resolve(folderName);
        Files.createDirectories(targetDir);
        String name = video.getName();
        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        Path dest = targetDir.resolve(name);
        for (int i = 1; Files.exists(dest); i++) dest = targetDir.resolve(stem + "_" + i + ext);
        Files.move(video.toPath(), dest);
        return dest;
    }

    @Override
    protected void process(List<String> chunks) {
        for (String s : chunks) onProgress.accept(s);
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            onProgress.accept(I18n.t("Terminé — %d converti(s), %d non reconnu(s), %d erreur(s)",
                    converted.get(), unrecognized.get(), errors.get()));
        }
    }

    public int getConverted()    { return converted.get(); }
    public int getUnrecognized() { return unrecognized.get(); }
    public int getErrors()       { return errors.get(); }
}
