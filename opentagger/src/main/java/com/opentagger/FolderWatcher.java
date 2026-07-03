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
                            // Petit délai pour laisser le temps à l'OS de finir la copie
                            try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                            onFileAdded.accept(full);
                        }
                    }
                }
            }
            if (!key.reset()) {
                keyToDir.remove(key);
            }
        }
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
