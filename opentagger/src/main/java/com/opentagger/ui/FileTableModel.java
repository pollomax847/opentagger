package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class FileTableModel extends AbstractTableModel {

    private static final String[] COLS = {
        "☑", I18n.t("Fichier"), I18n.t("Artiste"), I18n.t("Artiste Album"), I18n.t("Titre"),
        I18n.t("Album"), I18n.t("Année"), I18n.t("Genre"), I18n.t("Piste"), I18n.t("Statut")
    };
    public static final int COL_SEL          = 0;
    public static final int COL_FILE         = 1;
    public static final int COL_ARTIST       = 2;
    public static final int COL_ALBUM_ARTIST = 3;
    public static final int COL_TITLE        = 4;
    public static final int COL_ALBUM        = 5;
    public static final int COL_YEAR         = 6;
    public static final int COL_GENRE        = 7;
    public static final int COL_TRACK        = 8;
    public static final int COL_STATUS       = 9;

    private final List<FileEntry> entries = new ArrayList<>();

    // ── Ajout / suppression de lignes ────────────────────────────────────────

    public void add(FileEntry e)                  { entries.add(e);     fireTableRowsInserted(entries.size()-1, entries.size()-1); }
    public void update(FileEntry e)               { int i = entries.indexOf(e); if (i>=0) fireTableRowsUpdated(i,i); }
    public void remove(int row)                   { entries.remove(row); fireTableRowsDeleted(row, row); }
    public void clear()                           { entries.clear();   fireTableDataChanged(); }
    public FileEntry get(int row)                 { return entries.get(row); }
    public int indexOf(FileEntry e)               { return entries.indexOf(e); }

    public void replaceAll(List<FileEntry> list) {
        entries.clear();
        entries.addAll(list);
        fireTableDataChanged();
    }

    public void addAll(List<FileEntry> list) {
        if (list.isEmpty()) return;
        int from = entries.size();
        entries.addAll(list);
        fireTableRowsInserted(from, entries.size() - 1);
    }

    /** Supprime un ensemble d'entrées par référence (rollback d'un scan annulé). */
    public void removeEntries(java.util.Set<FileEntry> toRemove) {
        if (toRemove.isEmpty()) return;
        entries.removeIf(toRemove::contains);
        fireTableDataChanged();
    }

    // ── AbstractTableModel ───────────────────────────────────────────────────

    @Override public int getRowCount()    { return entries.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int col) { return COLS[col]; }

    @Override
    public Class<?> getColumnClass(int col) {
        return col == COL_SEL ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return col == COL_SEL || (col >= COL_ARTIST && col <= COL_TRACK);
        // Colonnes éditables : checkbox + Artiste → Piste (inclut "Artiste Album")
    }

    @Override
    public Object getValueAt(int row, int col) {
        FileEntry e  = entries.get(row);
        TagInfo   ti = e.activeTags();
        return switch (col) {
            case COL_SEL          -> e.selected;
            case COL_FILE         -> e.filename();
            case COL_ARTIST       -> ti.artist;
            case COL_ALBUM_ARTIST -> ti.albumArtist;
            case COL_TITLE        -> ti.title;
            case COL_ALBUM        -> ti.album;
            case COL_YEAR         -> ti.year;
            case COL_GENRE        -> ti.genre;
            case COL_TRACK        -> ti.track;
            case COL_STATUS       -> statusLabel(e.status, e.message, e.suggestions);
            default               -> "";
        };
    }

    @Override
    public void setValueAt(Object value, int row, int col) {
        FileEntry e = entries.get(row);
        switch (col) {
            case COL_SEL          -> e.selected = (Boolean) value;
            case COL_ARTIST       -> activeTags(e).artist       = (String) value;
            case COL_ALBUM_ARTIST -> activeTags(e).albumArtist  = (String) value;
            case COL_TITLE        -> activeTags(e).title        = (String) value;
            case COL_ALBUM        -> activeTags(e).album        = (String) value;
            case COL_YEAR         -> activeTags(e).year         = (String) value;
            case COL_GENRE        -> activeTags(e).genre        = (String) value;
            case COL_TRACK        -> activeTags(e).track        = (String) value;
        }
        // L'édition inline ne change pas le statut : les tags sont en mémoire
        // mais pas encore écrits sur disque. Le statut TAGGED ne sera mis à jour
        // que lors d'une écriture réelle (applyDetail() ou TaggingWorker).
        fireTableCellUpdated(row, col);
    }

    private TagInfo activeTags(FileEntry e) {
        if (e.result == null) {
            // Copier depuis current pour ne pas effacer les tags existants lors d'une
            // édition inline (un new TagInfo() vide remplacerait tous les champs affichés).
            TagInfo src = e.current != null ? e.current : new TagInfo();
            TagInfo t = new TagInfo();
            t.score = src.score;
            for (java.lang.reflect.Field f : TagInfo.class.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                try { f.setAccessible(true); f.set(t, f.get(src)); } catch (Exception ignored) {}
            }
            e.result = t;
        }
        return e.result;
    }

    private String statusLabel(FileEntry.Status s, String msg, java.util.List<String> sugg) {
        boolean hasSugg = sugg != null && !sugg.isEmpty();
        String warn = hasSugg ? " ⚠" + sugg.size() : "";
        return switch (s) {
            case PENDING    -> "—";
            case PROCESSING -> I18n.t("⏳ En cours...");
            case TAGGED     -> (msg.isBlank() ? I18n.t("✓ Tagué") : "✓ " + msg) + warn;
            case SKIPPED    -> "⚠ " + (msg.isBlank() ? I18n.t("Ignoré") : msg);
            case ERROR      -> "✗ " + (msg.isBlank() ? I18n.t("Erreur") : msg);
        };
    }

    /** Retourne le tooltip HTML pour une ligne (suggestions si présentes). */
    public String getTooltip(int row) {
        FileEntry e = entries.get(row);
        if (e.suggestions == null || e.suggestions.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("<html><b>" + I18n.t("Suggestions d'amélioration :") + "</b><br>");
        for (String s : e.suggestions) sb.append("&nbsp;⚠&nbsp;").append(s).append("<br>");
        sb.append("</html>");
        return sb.toString();
    }
}
