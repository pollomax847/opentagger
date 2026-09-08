package com.opentagger;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Point de passage UNIQUE pour "supprimer" un fichier/dossier de façon réversible — TOUJOURS via
 * la corbeille système si disponible, JAMAIS de suppression définitive silencieuse sinon.
 *
 * <p>Trouvé en direct (2026-09-05) : {@code Desktop.isSupported(Action.MOVE_TO_TRASH)} renvoie
 * {@code false} sur cette machine — confirmé par un test direct dans le MÊME environnement que
 * l'appli (même DISPLAY, même bureau Cinnamon/X11 actif) : limitation connue de l'intégration
 * GTK/gio d'AWT sur Linux, indépendante du fait qu'un vrai bureau graphique tourne. Les 7 sites qui
 * testaient ce booléen puis faisaient {@code trashSupported ? desktop.moveToTrash(f) : f.delete()}
 * tombaient donc TOUS dans le repli {@code f.delete()} — une suppression PERMANENTE — malgré le
 * message "jamais de suppression définitive" affiché à l'utilisateur partout où ce choix est
 * proposé. Repéré après que l'utilisateur a "supprimé" 175 fichiers ainsi via cette voie, introuvables
 * ensuite dans AUCUNE corbeille réelle vérifiée (ni {@code ~/.local/share/Trash}, ni les dossiers
 * {@code .Trash-1000}/{@code .Trashes} des volumes montés) — quasi certainement perdus pour de bon.
 *
 * <p>Repli : déplacement vers {@code ~/.opentagger/corbeille/} (réutilise
 * {@link FileRenamer#moveFile}, même robustesse cross-device — copie+suppression sécurisée si la
 * corbeille applicative est sur un disque différent du fichier d'origine — que le reste de
 * l'application, mais via {@link FileRenamer#TRASH_MOVE_LIMIT}, un sémaphore DÉDIÉ plutôt que celui
 * du pipeline de renommage automatique — voir sa Javadoc, trouvé en direct 2026-09-07 qu'une
 * suppression utilisateur pouvait rester bloquée indéfiniment derrière le pipeline en arrière-plan)
 * plutôt qu'une suppression — cohérent avec la préférence déjà établie de rester non-destructif
 * face au doute.
 */
public final class TrashHelper {
    private TrashHelper() {}

    private static final Path FALLBACK_DIR = Paths.get(Config.configDir(), "corbeille");

    /** @return true si le fichier a bien été retiré de son emplacement d'origine (corbeille
     *  système OU repli local) — jamais de suppression définitive, jamais silencieux. */
    public static boolean moveToTrash(File f) {
        if (systemTrash(f)) return true;
        return fallbackMove(f);
    }

    /** Même contrat que {@link #moveToTrash(File)} pour un dossier entier (ex. doublon d'album) —
     *  la corbeille système gère nativement les dossiers ; le repli déplace le dossier tel quel
     *  (avec son contenu) dans la corbeille applicative. */
    public static boolean moveDirToTrash(File dir) {
        if (systemTrash(dir)) return true;
        return fallbackMove(dir);
    }

    /** Pour les textes affichés à l'utilisateur (confirmations/infobulles) — beaucoup annonçaient
     *  "corbeille système" inconditionnellement alors que {@code Desktop.moveToTrash()} est
     *  confirmé indisponible sur cette machine (voir Javadoc de classe) : un mensonge de fait qui a
     *  fait croire à l'utilisateur (retour direct, 2026-09-07) que ses fichiers avaient disparu sans
     *  trace après une suppression pourtant bien réussie, juste dans {@link #FALLBACK_DIR} au lieu
     *  de la corbeille de son bureau. Évalué une seule fois (le support ne change pas en cours de
     *  session) plutôt qu'à chaque appel — même coût que {@code isSystemTrashAvailable()} sinon
     *  répété pour rien à chaque dialogue de confirmation.
     */
    private static final boolean SYSTEM_TRASH_AVAILABLE;
    static {
        boolean available;
        try {
            available = Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (Exception e) {
            available = false;
        }
        SYSTEM_TRASH_AVAILABLE = available;
    }

    /** Phrase honnête à afficher dans toute confirmation/infobulle de suppression — dit où les
     *  fichiers vont RÉELLEMENT sur CETTE machine, jamais juste "corbeille système" par défaut. */
    public static String destinationDescription() {
        return SYSTEM_TRASH_AVAILABLE
                ? "la corbeille système"
                : "le dossier de corbeille de l'application (" + FALLBACK_DIR + ")";
    }

    private static boolean systemTrash(File f) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                return Desktop.getDesktop().moveToTrash(f);
            }
        } catch (Exception ignored) {
            // repli ci-dessous
        }
        return false;
    }

    private static boolean fallbackMove(File f) {
        try {
            Files.createDirectories(FALLBACK_DIR);
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext  = dot > 0 ? name.substring(dot) : "";
            Path dest = FALLBACK_DIR.resolve(name);
            int n = 2;
            while (Files.exists(dest)) {
                if (n > 100) return false; // même garde-fou que FileRenamer (collisions extrêmes)
                dest = FALLBACK_DIR.resolve(stem + " (" + (n++) + ")" + ext);
            }
            if (f.isDirectory()) {
                // Files.move() seul échoue souvent sur un dossier NON VIDE traversant deux
                // systèmes de fichiers (cas le plus probable ici : corbeille applicative sur le
                // disque home, dossier source ailleurs) — copie récursive puis suppression de
                // l'original, jamais l'inverse (ne jamais supprimer avant confirmation que la
                // copie a bien pris).
                copyDirRecursively(f.toPath(), dest);
                deleteDirRecursively(f.toPath());
            } else {
                // Sémaphore DÉDIÉ (TRASH_MOVE_LIMIT), pas celui du pipeline de renommage auto — voir
                // sa Javadoc dans FileRenamer : trouvé en direct (2026-09-07) qu'une suppression
                // demandée par l'utilisateur pouvait rester bloquée en Semaphore.acquire() derrière
                // le flux continu de renommages cross-device d'une passe de re-taguage en cours.
                FileRenamer.moveFile(f.toPath(), dest, FileRenamer.TRASH_MOVE_LIMIT);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record TrashStats(int fileCount, long totalBytes) {}

    /** Contenu actuel de la corbeille APPLICATIVE (jamais la corbeille système, hors du contrôle de
     *  l'appli) — pour prévisualiser avant de la vider (voir emptyFallbackTrash()). */
    public static TrashStats fallbackTrashStats() {
        int count = 0;
        long bytes = 0;
        if (Files.isDirectory(FALLBACK_DIR)) {
            try (var walk = Files.walk(FALLBACK_DIR)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p)) {
                        count++;
                        try { bytes += Files.size(p); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        return new TrashStats(count, bytes);
    }

    /** Vide DÉFINITIVEMENT la corbeille applicative — le dernier geste irréversible qu'un
     *  utilisateur choisit explicitement une fois déjà rassuré que ses fichiers y sont bien
     *  arrivés (voir destinationDescription()) ; jamais automatique, jamais appelé ailleurs que
     *  depuis une confirmation explicite (voir MainFrame, son seul appelant). */
    public static void emptyFallbackTrash() throws IOException {
        if (!Files.isDirectory(FALLBACK_DIR)) return;
        File[] children = FALLBACK_DIR.toFile().listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) deleteDirRecursively(c.toPath());
            else Files.delete(c.toPath());
        }
    }

    private static void copyDirRecursively(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteDirRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            // Ordre inverse (fichiers avant dossiers) : un dossier ne peut être supprimé que vide.
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
