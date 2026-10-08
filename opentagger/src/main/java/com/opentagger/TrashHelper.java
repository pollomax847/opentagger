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
        if (refuseIfHoldsAudio(f)) return false;
        if (systemTrash(f)) return true;
        return fallbackMove(f);
    }

    /** Même contrat que {@link #moveToTrash(File)} pour un dossier entier (ex. doublon d'album) —
     *  la corbeille système gère nativement les dossiers ; le repli déplace le dossier tel quel
     *  (avec son contenu) dans la corbeille applicative. */
    public static boolean moveDirToTrash(File dir) {
        if (refuseIfHoldsAudio(dir)) return false;
        if (systemTrash(dir)) return true;
        return fallbackMove(dir);
    }

    // ── Garde-fou « jamais un dossier qui contient de l'audio » (2026-09-24) ─────────────────────
    // Incident réel : ~8 000 pistes audio VALIDES (76,8 Go, ~5 000 dossiers artiste/album/disque) ont quitté
    // la bibliothèque (MyBook) pour cette corbeille le 24/09 entre 06:27 et 18:33, sans aucune trace dans le
    // journal ni confirmation retrouvée (l'utilisateur affirme n'avoir rien supprimé de tel). La cause exacte
    // n'a pas pu être identifiée — mais le seul usage légitime de la corbeille au niveau DOSSIER est le
    // nettoyage d'orphelins, par définition SANS audio. Refuser ici tout dossier contenant de l'audio (ou dont
    // le contenu ne peut pas être listé : dans le doute, on ne touche pas) rend cette classe d'incident
    // impossible via ce point de passage, quel que soit l'appelant.
    private static final java.util.Set<String> AUDIO_EXT = java.util.Set.of(
        ".mp3", ".flac", ".m4a", ".ogg", ".wav", ".aac", ".opus", ".wma", ".ape", ".wv",
        ".aiff", ".aif", ".mpc", ".mp4", ".dsf", ".dff", ".mka");

    private static boolean refuseIfHoldsAudio(File f) {
        if (f == null || !f.isDirectory()) return false;
        boolean holdsAudio;
        try (java.util.stream.Stream<Path> walk = Files.walk(f.toPath())) {
            holdsAudio = walk.anyMatch(p -> {
                String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return !n.startsWith(".") && Files.isRegularFile(p) && AUDIO_EXT.stream().anyMatch(n::endsWith);
            });
        } catch (Exception e) {
            holdsAudio = true;   // contenu illisible : dans le doute, on ne déplace rien
        }
        if (holdsAudio) {
            System.out.println("[OT] ⛔ Corbeille REFUSÉE : le dossier contient de l'audio (ou n'a pas pu être listé) — "
                    + f.getAbsolutePath() + " ← " + callerOf());
        }
        return holdsAudio;
    }

    /** Premier appelant hors TrashHelper, « Classe.méthode:ligne » — pour que la prochaine mise à la corbeille
     *  inattendue soit traçable dans le journal (jusqu'ici ces déplacements étaient totalement silencieux). */
    private static String callerOf() {
        for (StackTraceElement el : new Throwable().getStackTrace()) {
            if (!el.getClassName().equals(TrashHelper.class.getName()))
                return el.getClassName().replaceAll(".*\\.", "") + "." + el.getMethodName() + ":" + el.getLineNumber();
        }
        return "?";
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
        System.out.println("[OT] 🗑 Corbeille : " + f.getAbsolutePath() + (f.isDirectory() ? " [dossier]" : "")
                + " ← " + callerOf());
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
