package com.opentagger;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.nio.file.Path;

/**
 * Clé/titre de regroupement par album — partagés entre {@code ui.RenamePreviewDialog},
 * {@code ui.AlbumTreeTableModel} et {@code ui.MainFrame} (Cover Flow), qui avaient (les deux premiers)
 * chacun leur propre copie quasi identique de cette logique avant cette extraction. Priorité à
 * {@code albumArtist} sur {@code artist} ; à défaut de tag album, repli sur le dossier parent —
 * jamais un bucket global unique "sans album" : sur un scan de centaines de milliers de fichiers,
 * des dizaines de milliers de fichiers fraîchement découverts sans tag album le temps d'être
 * identifiés se retrouveraient sinon tous dans UN SEUL groupe géant.
 */
public final class AlbumGrouping {

    private AlbumGrouping() {}

    /** Clé stable pour regrouper — basée sur le chemin PARENT COMPLET en repli (pas juste le nom du
     *  dossier) pour ne jamais confondre deux dossiers homonymes situés ailleurs dans l'arborescence. */
    public static String key(FileEntry e) {
        TagInfo tags = e.activeTags();
        String art = !tags.albumArtist.isBlank() ? tags.albumArtist : tags.artist;
        if (!tags.album.isBlank()) return "album::" + art + "::" + tags.album;
        Path parent = parentOf(e);
        return "folder::" + (parent != null ? parent : e.filename());
    }

    /** Titre lisible affiché pour ce groupe — pas garanti unique (voir {@link #key}, qui l'est). */
    public static String title(FileEntry e) {
        TagInfo tags = e.activeTags();
        String art = !tags.albumArtist.isBlank() ? tags.albumArtist : tags.artist;
        if (!tags.album.isBlank()) return art.isBlank() ? tags.album : art + " – " + tags.album;
        Path parent = parentOf(e);
        return "📁 " + (parent != null ? parent.getFileName() : e.filename());
    }

    private static Path parentOf(FileEntry e) {
        Path p = e.currentPath != null ? e.currentPath : (e.file != null ? e.file.toPath() : null);
        return p != null ? p.getParent() : null;
    }
}
