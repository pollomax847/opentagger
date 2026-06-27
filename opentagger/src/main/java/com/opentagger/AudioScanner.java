package com.opentagger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class AudioScanner {

    private static final Set<String> EXTENSIONS = Set.of(
        ".mp3", ".flac", ".m4a", ".ogg", ".wav",
        ".aac", ".opus", ".wma", ".ape", ".wv",
        ".aiff", ".aif", ".mpc", ".mp4", ".dsf", ".dff"
    );

    public List<File> scan(File dossier) {
        List<File> fichiers = new ArrayList<>();
        scanRecursif(dossier, fichiers);
        return fichiers;
    }

    private void scanRecursif(File dossier, List<File> fichiers) {
        File[] contenu = dossier.listFiles();
        if (contenu == null) return;

        for (File f : contenu) {
            if (f.isDirectory()) {
                scanRecursif(f, fichiers);
            } else if (isAudio(f)) {
                fichiers.add(f);
            }
        }
    }

    private boolean isAudio(File f) {
        String nom = f.getName();
        // Exclure les fichiers cachés et les resource forks macOS (._NomFichier)
        if (nom.startsWith(".")) return false;
        String lower = nom.toLowerCase();
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
