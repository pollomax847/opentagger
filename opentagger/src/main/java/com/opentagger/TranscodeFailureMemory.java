package com.opentagger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Se souvient des fichiers que ffmpeg n'a pas réussi à convertir (flux abîmé), pour ne PAS les retenter à chaque taguage : sans cela,
 * un fichier corrompu faisait relancer ffmpeg sur toute sa durée à chaque passe. Un fichier est reconnu par son chemin, sa taille et sa
 * date de modification : s'il est remplacé ou réparé, il est retenté.
 */
public final class TranscodeFailureMemory {

    private final Path file;
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    private static volatile TranscodeFailureMemory shared;

    /** Mémoire partagée de l'application (fichier dans le dossier de configuration). */
    public static TranscodeFailureMemory shared() {
        if (shared == null) {
            synchronized (TranscodeFailureMemory.class) {
                if (shared == null) shared = new TranscodeFailureMemory(Path.of(Config.configDir()).resolve("transcode_failed.txt"));
            }
        }
        return shared;
    }

    public TranscodeFailureMemory(Path file) {
        this.file = file;
        try {
            if (Files.isRegularFile(file)) keys.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException ignored) { /* mémoire illisible : on repart de zéro, au pire une tentative de plus */ }
    }

    static String keyOf(Path source) {
        try {
            return source.toAbsolutePath().normalize() + "|" + Files.size(source) + "|" + Files.getLastModifiedTime(source).toMillis();
        } catch (IOException e) {
            return source.toAbsolutePath().normalize() + "|?|?";
        }
    }

    /** Vrai si ce fichier, dans cet état exact, a déjà échoué. */
    public boolean known(Path source) {
        return keys.contains(keyOf(source));
    }

    /** Note l'échec de ce fichier, dans son état actuel. */
    public void remember(Path source) {
        String key = keyOf(source);
        if (!keys.add(key)) return;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(file, List.of(key), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { /* au pire, retenté à la prochaine session */ }
    }
}
