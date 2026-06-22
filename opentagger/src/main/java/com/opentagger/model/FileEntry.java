package com.opentagger.model;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class FileEntry {

    public enum Status {
        PENDING, PROCESSING, TAGGED, SKIPPED, ERROR
    }

    public final File   file;
    public TagInfo      current;      // tags lus dans le fichier avant traitement
    public TagInfo      result;       // tags proposés après identification API
    public Status       status   = Status.PENDING;
    public String       message  = "";
    public boolean      selected = true;

    /** Chemin actuel du fichier (peut changer après renommage). */
    public Path currentPath;

    /** Candidats non retenus par le batch (score < seuil) — pour sélection manuelle. */
    public List<TagInfo> candidates;

    /**
     * Racine du dossier scanné — le renommage par masque est relatif à cette racine.
     * Ex : racine=/musique, masque={artist}/{album}/{track} - {title}
     *   → /musique/Artist/Album/01 - Title.mp3
     */
    public Path scanRoot;

    public FileEntry(File file, TagInfo current) {
        this.file        = file;
        this.currentPath = file.toPath();
        this.current     = current != null ? current : new TagInfo();
    }

    /** Nom court du fichier actuel (après renommage éventuel). */
    public String filename() {
        return currentPath != null ? currentPath.getFileName().toString() : file.getName();
    }

    /** Retourne les tags actifs : result si disponible, sinon current. */
    public TagInfo activeTags() { return result != null ? result : current; }
}
