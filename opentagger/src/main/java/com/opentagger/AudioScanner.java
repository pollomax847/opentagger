package com.opentagger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Logger;

public class AudioScanner {

    private static final Logger LOG = Logger.getLogger(AudioScanner.class.getName());

    private static final Set<String> EXTENSIONS = Set.of(
        ".mp3", ".flac", ".m4a", ".ogg", ".wav",
        ".aac", ".opus", ".wma", ".ape", ".wv",
        ".aiff", ".aif", ".mpc", ".mp4", ".dsf", ".dff"
    );

    public List<File> scan(File dossier) {
        List<File> fichiers = new ArrayList<>();
        scan(dossier, fichiers::add);
        return fichiers;
    }

    /** Variante en flux : appelle onFound dès qu'un fichier audio est trouvé, plutôt que
     *  d'attendre la fin du parcours complet de l'arborescence pour tout renvoyer d'un coup —
     *  sur une grosse bibliothèque (disque externe, des dizaines de milliers de fichiers), le
     *  parcours seul (avant même la lecture des tags) peut prendre un temps notable, et rien ne
     *  s'affichait dans le tableau tant qu'il n'était pas terminé. */
    public void scan(File dossier, Consumer<File> onFound) {
        scan(dossier, onFound, () -> false);
    }

    /** Variante en flux + annulable : cancelled est vérifié à chaque fichier/dossier, permettant
     *  d'interrompre un parcours en cours (bouton Annuler) sans attendre qu'il se termine tout seul. */
    public void scan(File dossier, Consumer<File> onFound, BooleanSupplier cancelled) {
        scanRecursif(dossier, onFound, cancelled);
    }

    private void scanRecursif(File dossier, Consumer<File> onFound, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return;
        File[] contenu = dossier.listFiles();
        if (contenu == null) {
            // listFiles() renvoie null (jamais une exception) sur erreur E/S ou permission refusée
            // — jusqu'ici avalé en silence, ce sous-arbre entier disparaissait du scan sans la
            // moindre trace, aucun moyen de savoir que ça s'était produit. Repéré en direct
            // (2026-08-20) : /home/paulceline/Musique (1579 fichiers audio réels) n'avait que 141
            // entrées en cache, toutes des fichiers isolés à la racine — aucune ne venait des
            // sous-dossiers d'artistes, cohérent avec des échecs listFiles() répétés sur ce dossier,
            // qui partage le même disque physique que le cache SQLite de l'appli (contention E/S),
            // contrairement à MyBook/Toshiba montés sur des disques séparés.
            if (dossier.exists()) LOG.warning("scan: listFiles() a échoué (E/S ou permission) : " + dossier.getAbsolutePath());
            return;
        }

        for (File f : contenu) {
            if (cancelled.getAsBoolean()) return;
            if (f.isDirectory()) {
                scanRecursif(f, onFound, cancelled);
            } else if (isAudio(f)) {
                onFound.accept(f);
            }
        }
    }

    private boolean isAudio(File f) {
        String nom = f.getName();
        // Exclure les fichiers cachés et les resource forks macOS (._NomFichier)
        if (nom.startsWith(".")) return false;
        String lower = nom.toLowerCase();
        // Exclure les fichiers temporaires de réparation/écriture M4A de TagWriter — un scan
        // manuel lancé pendant un taguage en cours ne doit pas les mettre en file.
        if (lower.startsWith("ot_m4a_") || lower.startsWith("ot_fix_")) return false;
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }
}
