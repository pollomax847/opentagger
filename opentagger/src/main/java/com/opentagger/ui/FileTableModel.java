package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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

    // `entries` = TOUS les fichiers ; `visible` = la vue filtrée actuelle, c'est elle que JTable
    // voit (getRowCount()/getValueAt() portent sur `visible`, jamais `entries` directement).
    // Le filtre est appliqué ICI plutôt que via TableRowSorter.setRowFilter() : mesuré en direct
    // (RowSorterCostTest, scratchpad) qu'un RowFilter actif rend CHAQUE insertion ~45x plus lente
    // (6+ s pour 100k lignes contre 133 ms sans filtre) — sur un scan de bibliothèque de plusieurs
    // centaines de milliers de fichiers, ça reproduit le même gel que le bug déjà corrigé plus tôt
    // (tri complet à chaque lot). Filtrer dans le modèle rend le filtre utilisable en permanence,
    // y compris pendant un scan actif, indépendamment de l'état du RowSorter (lui reste réservé au
    // tri par colonne, toujours protégé séparément par MainFrame.beginBulkTableUpdate()).
    private final List<FileEntry> entries = new ArrayList<>();
    private final List<FileEntry> visible = new ArrayList<>();
    // FileEntry → index dans `visible` (pas dans `entries`) — FileEntry n'override pas
    // equals/hashCode, IdentityHashMap correspond au comportement historique de indexOf().
    private final java.util.Map<FileEntry, Integer> indexMap = new java.util.IdentityHashMap<>();
    private Predicate<FileEntry> filter = null; // null = aucun filtre, tout est visible
    // Une entrée déjà visible qui a cessé de correspondre au filtre (ex: un fichier passe TAGGED
    // pendant qu'un filtre "En attente" est actif) n'est PAS retirée immédiatement de `visible` —
    // un retrait précis nécessiterait de réindexer tout ce qui suit (coût O(n) À CHAQUE update(),
    // soit O(n²) sur un run de taguage complet avec un filtre actif). On marque juste `dirty` ;
    // MainFrame.refreshStats() (déjà appelé à intervalle régulier, throttlé à 300ms dans les
    // boucles de scan/taguage) purge via rebuildVisibleIfDirty() — l'entrée reste visible au plus
    // ~300ms de plus que l'idéal, largement préférable à un gel.
    private boolean dirty = false;

    // ── Ajout / suppression de lignes ────────────────────────────────────────

    public void add(FileEntry e) {
        entries.add(e);
        if (matches(e)) {
            visible.add(e);
            indexMap.put(e, visible.size() - 1);
            fireTableRowsInserted(visible.size() - 1, visible.size() - 1);
        }
    }

    public void update(FileEntry e) {
        Integer viewRow = indexMap.get(e);
        boolean wasVisible = viewRow != null;
        boolean nowMatches = matches(e);
        if (wasVisible && nowMatches) {
            fireTableRowsUpdated(viewRow, viewRow);
        } else if (!wasVisible && nowMatches) {
            // Devient visible (ex: un nouveau statut correspond maintenant au filtre actif) —
            // ajouté en fin de vue ; l'ordre exact se corrige au prochain rebuild complet
            // (changement de filtre), pas un souci pour une notification ponctuelle.
            visible.add(e);
            indexMap.put(e, visible.size() - 1);
            fireTableRowsInserted(visible.size() - 1, visible.size() - 1);
        } else if (wasVisible) {
            // Ne correspond plus au filtre actif : retrait différé, voir le commentaire sur `dirty`.
            dirty = true;
        }
    }

    public void remove(int row) {
        FileEntry removed = visible.remove(row);
        entries.remove(removed);
        indexMap.remove(removed);
        reindexVisibleFrom(row);
        fireTableRowsDeleted(row, row);
    }

    public void clear() {
        entries.clear();
        visible.clear();
        indexMap.clear();
        dirty = false;
        fireTableDataChanged();
    }

    public FileEntry get(int row)   { return visible.get(row); }
    public int indexOf(FileEntry e) { Integer i = indexMap.get(e); return i != null ? i : -1; }

    /** Nombre total de fichiers, filtre ignoré — pour l'affichage "N / total" dans MainFrame. */
    public int totalCount() { return entries.size(); }
    public boolean isFiltered() { return filter != null; }

    /** Vue non modifiable de TOUS les fichiers, filtre ignoré — pour les actions qui doivent
     *  raisonner sur la bibliothèque entière (ex: AlbumClusterWorker) plutôt que la vue filtrée
     *  actuelle, contrairement à get()/getRowCount() qui portent sur `visible`. */
    public List<FileEntry> allEntries() { return java.util.Collections.unmodifiableList(entries); }

    public void replaceAll(List<FileEntry> list) {
        entries.clear();
        entries.addAll(list);
        rebuildVisible();
    }

    public void addAll(List<FileEntry> list) {
        if (list.isEmpty()) return;
        entries.addAll(list);
        int from = visible.size();
        for (FileEntry e : list) {
            if (matches(e)) {
                visible.add(e);
                indexMap.put(e, visible.size() - 1);
            }
        }
        if (visible.size() > from) fireTableRowsInserted(from, visible.size() - 1);
    }

    /** Supprime un ensemble d'entrées par référence (rollback d'un scan annulé). */
    public void removeEntries(java.util.Set<FileEntry> toRemove) {
        if (toRemove.isEmpty()) return;
        entries.removeIf(toRemove::contains);
        rebuildVisible();
    }

    /** Change le filtre actif (chips de statut + recherche texte, voir MainFrame.applyFilter()).
     *  Reconstruit `visible` intégralement — rare/délibéré (clic utilisateur), pas le chemin
     *  chaud per-fichier, donc son coût O(n) est sans risque à l'échelle. */
    public void setFilter(Predicate<FileEntry> newFilter) {
        this.filter = newFilter;
        rebuildVisible();
    }

    /** Purge les entrées devenues invisibles depuis leur dernier update() (voir `dirty`). À
     *  appeler périodiquement — MainFrame.refreshStats() le fait déjà à chaque appel. */
    public void rebuildVisibleIfDirty() {
        if (dirty) rebuildVisible();
    }

    private boolean matches(FileEntry e) {
        return filter == null || filter.test(e);
    }

    private void rebuildVisible() {
        visible.clear();
        indexMap.clear();
        for (FileEntry e : entries) {
            if (matches(e)) {
                visible.add(e);
                indexMap.put(e, visible.size() - 1);
            }
        }
        dirty = false;
        fireTableDataChanged();
    }

    /** Ré-indexe visible[from..] dans indexMap — uniquement après une suppression ponctuelle
     *  (rare), jamais sur le chemin chaud add()/update(). */
    private void reindexVisibleFrom(int from) {
        for (int i = from; i < visible.size(); i++) indexMap.put(visible.get(i), i);
    }

    // ── AbstractTableModel ───────────────────────────────────────────────────

    @Override public int getRowCount()    { return visible.size(); }
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
        FileEntry e  = visible.get(row);
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
        FileEntry e = visible.get(row);
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
            // Identifié en mémoire, pas encore écrit sur le disque — voir FileEntry.Status.IDENTIFIED.
            case IDENTIFIED -> (msg.isBlank() ? I18n.t("🔍 Identifié") : "🔍 " + msg) + warn;
            case TAGGED     -> (msg.isBlank() ? I18n.t("✓ Tagué") : "✓ " + msg) + warn;
            case SKIPPED    -> "⚠ " + (msg.isBlank() ? I18n.t("Ignoré") : msg);
            case ERROR      -> "✗ " + (msg.isBlank() ? I18n.t("Erreur") : msg);
        };
    }

    /** Retourne le tooltip HTML pour une ligne (suggestions si présentes). */
    public String getTooltip(int row) {
        FileEntry e = visible.get(row);
        if (e.suggestions == null || e.suggestions.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("<html><b>" + I18n.t("Suggestions d'amélioration :") + "</b><br>");
        for (String s : e.suggestions) sb.append("&nbsp;⚠&nbsp;").append(s).append("<br>");
        sb.append("</html>");
        return sb.toString();
    }
}
