package com.opentagger;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * Export de listes de lecture dans deux formats :
 *  - M3U étendu (#EXTM3U) : compatible avec VLC, Winamp, MediaMonkey, Kodi…
 *  - XSPF (XML Shareable Playlist Format) : standard W3C
 *
 * Seuls les fichiers ayant un statut TAGGED sont inclus.
 * Les chemins sont écrits en absolu.
 */
public class PlaylistExporter {

    /** Exporte en M3U étendu. */
    public static int exportM3u(List<FileEntry> entries, File out) throws IOException {
        int count = 0;
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            pw.println("#EXTM3U");
            pw.println();
            for (FileEntry e : entries) {
                if (e.status != FileEntry.Status.TAGGED) continue;
                TagInfo ti = e.activeTags();
                Path    p  = e.currentPath != null ? e.currentPath : e.file.toPath();
                if (!p.toFile().exists()) continue;

                String artist   = m3u(ti.artist.isBlank() ? "?" : ti.artist);
                String title    = m3u(ti.title.isBlank()  ? p.getFileName().toString() : ti.title);
                // ti.durationSec est déjà connu (colonne Durée du tableau principal) — avant ce
                // correctif, -1 ("durée inconnue") était écrit systématiquement, alors que la
                // plupart des lecteurs (VLC, Kodi, MediaMonkey) l'utilisent pour le temps total
                // affiché / la barre de progression de la playlist.
                int    duration = ti.durationSec > 0 ? ti.durationSec : -1;

                pw.println("#EXTINF:" + duration + "," + artist + " - " + title);
                pw.println(p.toAbsolutePath());
                pw.println();
                count++;
            }
        }
        return count;
    }

    /** Exporte en XSPF. */
    public static int exportXspf(List<FileEntry> entries, File out) throws IOException {
        int count = 0;
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<playlist version=\"1\" xmlns=\"http://xspf.org/ns/0/\">");
            pw.println("  <trackList>");
            for (FileEntry e : entries) {
                if (e.status != FileEntry.Status.TAGGED) continue;
                TagInfo ti = e.activeTags();
                Path    p  = e.currentPath != null ? e.currentPath : e.file.toPath();
                if (!p.toFile().exists()) continue;

                pw.println("    <track>");
                pw.println("      <location>file://" + xml(p.toAbsolutePath().toString()) + "</location>");
                if (!ti.title.isBlank())  pw.println("      <title>"  + xml(ti.title)  + "</title>");
                if (!ti.artist.isBlank()) pw.println("      <creator>" + xml(ti.artist) + "</creator>");
                if (!ti.album.isBlank())  pw.println("      <album>"   + xml(ti.album)  + "</album>");
                if (!ti.track.isBlank()) {
                    try { pw.println("      <trackNum>" + Integer.parseInt(ti.track) + "</trackNum>"); }
                    catch (NumberFormatException ignored) {}
                }
                // <duration> XSPF est en millisecondes — champ prévu par le format, jamais écrit
                // avant ce correctif alors que ti.durationSec est disponible ici comme pour le M3U.
                if (ti.durationSec > 0) pw.println("      <duration>" + (ti.durationSec * 1000) + "</duration>");
                pw.println("    </track>");
                count++;
            }
            pw.println("  </trackList>");
            pw.println("</playlist>");
        }
        return count;
    }

    /**
     * M3U n'a pas d'échappement (contrairement au XSPF/XML ci-dessous, déjà protégé par xml()) :
     * le format est ligne-par-ligne, donc un saut de ligne ou caractère de contrôle dans un tag
     * scrappé/corrompu (déjà vu ailleurs dans ce dépôt — voir MetadataCache.queryHash, un octet
     * NUL trouvé dans un fichier source réel) casse la structure "#EXTINF:...,Artiste - Titre" en
     * plusieurs lignes, dont l'une peut être interprétée comme un chemin de fichier fantôme par le
     * lecteur qui ouvre la playlist.
     */
    private static String m3u(String s) {
        return s.replaceAll("[\\r\\n\\p{Cntrl}]+", " ").trim();
    }

    private static String xml(String s) {
        // Caractères de contrôle interdits par XML 1.0 (hors tabulation/LF/CR, autorisés) — un tag
        // scrappé/corrompu contenant un octet de contrôle brut (même cause que m3u() ci-dessus)
        // produirait sinon un XSPF que certains lecteurs XML stricts refusent d'ouvrir.
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
