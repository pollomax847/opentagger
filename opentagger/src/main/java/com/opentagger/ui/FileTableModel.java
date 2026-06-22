package com.opentagger.ui;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

public class FileTableModel extends AbstractTableModel {

    private static final String[] COLS = {
        "☑", "Fichier", "Artiste", "Artiste Album", "Titre", "Album", "Année", "Genre", "Piste", "Statut"
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
            case COL_STATUS       -> statusLabel(e.status, e.message);
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
        fireTableCellUpdated(row, col);
    }

    private TagInfo activeTags(FileEntry e) {
        if (e.result == null) e.result = new TagInfo();
        return e.result;
    }

    private String statusLabel(FileEntry.Status s, String msg) {
        return switch (s) {
            case PENDING    -> "—";
            case PROCESSING -> "⏳ En cours...";
            case TAGGED     -> "✓ Tagué";
            case SKIPPED    -> "⚠ " + (msg.isBlank() ? "Ignoré" : msg);
            case ERROR      -> "✗ " + (msg.isBlank() ? "Erreur" : msg);
        };
    }
}
