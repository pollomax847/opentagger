package com.opentagger;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Surveille un ensemble de dossiers via WatchService (OS-level, sans polling).
 * Dès qu'un fichier audio apparaît, le callback onFileAdded est appelé sur un thread dédié.
 * Le caller doit dispatcher vers l'EDT si nécessaire (SwingUtilities.invokeLater).
 */
public class FolderWatcher implements Closeable {

    private static final Logger LOG = Logger.getLogger(FolderWatcher.class.getName());
    private static final Set<String> AUDIO_EXT = Set.of(
        "mp3","flac","m4a","aac","ogg","opus","wma","wav","aiff","aif","ape","wv","mp4"
    );

    private final WatchService        watchService;
    private final Map<WatchKey, Path> keyToDir   = new HashMap<>();
    private final Set<Path>           watchedDirs = new HashSet<>();
    private final Consumer<Path>      onFileAdded;
    private final AtomicBoolean       running    = new AtomicBoolean(false);
    private Thread                    watchThread;

    public FolderWatcher(Consumer<Path> onFileAdded) throws IOException {
        this.watchService = FileSystems.getDefault().newWatchService();
        this.onFileAdded  = onFileAdded;
    }

    /** Enregistre un dossier (et tous ses sous-dossiers) pour la surveillance. */
    public synchronized void watch(Path root) {
        if (!Files.isDirectory(root)) return;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!watchedDirs.contains(dir)) {
                        try {
                            WatchKey key = dir.register(watchService, ENTRY_CREATE);
                            keyToDir.put(key, dir);
                            watchedDirs.add(dir);
                        } catch (IOException ignored) {}
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warning("FolderWatcher.watch error: " + e.getMessage());
        }
    }

    /** Démarre le thread de surveillance (idempotent). */
    public synchronized void start() {
        if (running.getAndSet(true)) return;
        watchThread = new Thread(this::loop, "FolderWatcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    /** Arrête la surveillance et libère les ressources. */
    @Override
    public synchronized void close() {
        running.set(false);
        if (watchThread != null) watchThread.interrupt();
        try { watchService.close(); } catch (IOException ignored) {}
    }

    /** Supprime tous les dossiers enregistrés (appelé lors d'un clearFileList). */
    public synchronized void clearAll() {
        for (WatchKey k : keyToDir.keySet()) k.cancel();
        keyToDir.clear();
        watchedDirs.clear();
    }

    private void loop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take(); // bloquant — réveillé par l'OS
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            Path dir = keyToDir.get(key);
            if (dir != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == ENTRY_CREATE) {
                        @SuppressWarnings("unchecked")
                        Path rel  = ((WatchEvent<Path>) event).context();
                        Path full = dir.resolve(rel);

                        if (Files.isDirectory(full)) {
                            // Nouveau sous-dossier → l'enregistrer aussi
                            watch(full);
                        } else if (isAudioFile(full)) {
                            // Un fichier en cours de téléchargement/copie (iTunes Match, navigateur, copie réseau) grossit pendant des
                            // secondes, voire des minutes : on attend qu'il ne bouge plus AVANT de le proposer au taguage, sinon on
                            // tague un fichier tronqué (durée 0:01, « No audio header ») que le téléchargement réécrit ensuite. L'attente
                            // se fait sur un autre thread pour ne pas bloquer la surveillance des autres événements.
                            WAITERS.execute(() -> {
                                if (waitUntilStable(full, 4000, 15 * 60_000L)) onFileAdded.accept(full);
                            });
                        }
                    }
                }
            }
            if (!key.reset()) {
                keyToDir.remove(key);
            }
        }
    }

    private static final java.util.concurrent.ExecutorService WAITERS = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread th = new Thread(r, "folder-watcher-stable-wait");
        th.setDaemon(true);
        return th;
    });

    /**
     * Attend que le fichier ne change plus (taille ET date de modification identiques pendant {@code stableMs}, et lisible).
     * @return vrai s'il est stable ; faux s'il a disparu ou n'est pas stabilisé après {@code maxMs}.
     */
    static boolean waitUntilStable(Path file, long stableMs, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        long lastSize = -1, lastMod = -1, since = System.currentTimeMillis();
        long poll = Math.max(50, Math.min(1000, stableMs / 4));
        while (System.currentTimeMillis() < deadline) {
            try {
                if (!Files.exists(file)) return false;
                long size = Files.size(file);
                long mod = Files.getLastModifiedTime(file).toMillis();
                if (size != lastSize || mod != lastMod) {
                    lastSize = size; lastMod = mod; since = System.currentTimeMillis();
                } else if (size > 0 && System.currentTimeMillis() - since >= stableMs) {
                    try (java.io.InputStream in = Files.newInputStream(file)) { in.read(); } // encore verrouillé par un autre ? alors on attend
                    return true;
                }
                Thread.sleep(poll);
            } catch (java.io.IOException e) {
                since = System.currentTimeMillis(); // verrouillé / en cours d'écriture : on recompte
                try { Thread.sleep(poll); } catch (InterruptedException ie) { return false; }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean isAudioFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        // Exclure les fichiers temporaires créés par TagWriter pendant la réparation/écriture M4A
        // (ot_m4a_*, ot_fix_*) : ils vivent dans ce même dossier surveillé (nécessaire pour un
        // renommage atomique sur NAS/MergerFS) et disparaissent avant que le watcher n'ait le temps
        // de les traiter → sinon "SKIP (fichier introuvable)" en boucle (21 000+ fois en 3 jours).
        if (name.startsWith("ot_m4a_") || name.startsWith("ot_fix_")) return false;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && AUDIO_EXT.contains(name.substring(dot + 1));
    }
}
