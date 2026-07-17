package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.junit.Test;

import java.io.File;

/**
 * Comportement de référence pour AlbumGrouping — extrait de AlbumTreeTableModel.groupKeyFor()/
 * groupTitleFor() (la version retenue comme canonique, plus défensive que l'ancienne implémentation
 * dupliquée de RenamePreviewDialog). Écrit avant l'extraction pour vérifier "zéro changement de
 * comportement" plutôt que de l'affirmer.
 */
public class AlbumGroupingTest {

    private static FileEntry mk(String path, String artist, String albumArtist, String album) {
        TagInfo ti = new TagInfo();
        ti.artist = artist;
        ti.albumArtist = albumArtist;
        ti.album = album;
        FileEntry e = new FileEntry(new File(path), ti);
        e.currentPath = new File(path).toPath();
        return e;
    }

    @Test
    public void albumArtistPreferredOverArtistWhenAlbumPresent() {
        FileEntry e = mk("/lib/Various/Comp/01.mp3", "Track Artist", "Various Artists", "Big Compilation");
        assertEquals("album::Various Artists::Big Compilation", AlbumGrouping.key(e));
        assertEquals("Various Artists – Big Compilation", AlbumGrouping.title(e));
    }

    @Test
    public void artistUsedWhenAlbumArtistBlankAndAlbumPresent() {
        FileEntry e = mk("/lib/Solo/Album/01.mp3", "Solo Artist", "", "Solo Album");
        assertEquals("album::Solo Artist::Solo Album", AlbumGrouping.key(e));
        assertEquals("Solo Artist – Solo Album", AlbumGrouping.title(e));
    }

    @Test
    public void folderFallbackWhenAlbumBlank() {
        FileEntry e = mk("/lib/Divers/randomrip/track.mp3", "", "", "");
        assertEquals("folder::" + new File("/lib/Divers/randomrip").toPath(), AlbumGrouping.key(e));
        assertEquals("📁 randomrip", AlbumGrouping.title(e));
    }

    @Test
    public void differentFoldersSameNameProduceDifferentKeysButSameTitle() {
        FileEntry a = mk("/lib/A/misc/track.mp3", "", "", "");
        FileEntry b = mk("/lib/B/misc/track.mp3", "", "", "");
        assertNotEquals("chemins parents differents -> cles differentes (pas de confusion entre deux dossiers homonymes)",
            AlbumGrouping.key(a), AlbumGrouping.key(b));
        assertEquals(AlbumGrouping.title(a), AlbumGrouping.title(b));
    }

    @Test
    public void rootLevelNoParentFallsBackToFilename() {
        // currentPath sans parent (fichier a la racine) ET tag album vide : repli sur le nom de
        // fichier lui-meme, pas sur un texte generique "dossier inconnu" (comportement reconcilie
        // sur celui, plus defensif, d'AlbumTreeTableModel — voir le plan).
        FileEntry e = mk("track.mp3", "", "", "");
        assertEquals("folder::track.mp3", AlbumGrouping.key(e));
        assertEquals("📁 track.mp3", AlbumGrouping.title(e));
    }
}
