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

                String artist   = ti.artist.isBlank() ? "?" : ti.artist;
                String title    = ti.title.isBlank()  ? p.getFileName().toString() : ti.title;
                int    duration = -1; // -1 = durée inconnue

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
                pw.println("    </track>");
                count++;
            }
            pw.println("  </trackList>");
            pw.println("</playlist>");
        }
        return count;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
