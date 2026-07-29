package com.opentagger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Repère les fichiers vidéo (clips musicaux téléchargés dans un mauvais conteneur, typiquement)
 *  qu'un scan audio classique ({@link AudioScanner}) ignore totalement — voir
 *  {@code ui.VideoRecoveryWorker}, qui tente d'en extraire l'audio et de le tagger si reconnu.
 *  {@code .mp4} est volontairement absent : {@link AudioScanner} le traite déjà comme conteneur
 *  audio (M4A) ; l'inclure ici créerait une ambiguïté entre les deux pipelines. */
public class VideoScanner {

    // .mov/.wmv/.flv/.3gp ajoutés le 2026-07-29 (formats vidéo courants manquants — un .mov
    // Apple/QuickTime, par exemple, n'était vu ni par AudioScanner ni par VideoScanner : invisible
    // des deux côtés). .3gp n'entre pas en conflit avec le ".3gp" éventuel côté AudioScanner —
    // AudioScanner ne gère pas cette extension (seulement mp4/m4a de la même famille mov/mp4/3gp).
    private static final Set<String> EXTENSIONS = Set.of(
        ".webm", ".vob", ".mpg", ".mpeg", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".3gp"
    );

    /** Sous-dossiers créés par {@code ui.VideoRecoveryWorker} pour les vidéos déjà traitées
     *  (converties ou non reconnues) — jamais re-descendus dedans, sinon un scan répété du même
     *  dossier racine retrouverait indéfiniment les mêmes vidéos déjà traitées lors d'une passe
     *  précédente et les retenterait (ou pire, reconvertirait/redoublonnerait celles déjà réussies,
     *  gardées ici uniquement comme filet de sécurité). */
    public static final String CONVERTED_FOLDER    = "Convertis";
    public static final String UNRECOGNIZED_FOLDER  = "Non identifié";

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
                if (CONVERTED_FOLDER.equals(f.getName()) || UNRECOGNIZED_FOLDER.equals(f.getName())) continue;
                scanRecursif(f, fichiers);
            } else if (isVideo(f)) {
                fichiers.add(f);
            }
        }
    }

    private boolean isVideo(File f) {
        String nom = f.getName();
        if (nom.startsWith(".")) return false;
        String lower = nom.toLowerCase();
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
