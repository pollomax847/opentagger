package com.opentagger.ui;

import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.TableRowSorter;
import java.util.Comparator;
import java.util.List;

/**
 * RowSorter pour la vue arborescence par album — ne trie JAMAIS les LIGNES elle-même (
 * {@link AlbumTreeTableModel} maintient déjà son propre ordre aplati, pistes toujours triées par
 * disque/piste à l'intérieur d'un groupe, jamais par la colonne cliquée). Sert uniquement à
 * réutiliser l'affordance native de Swing (flèche de tri dans l'en-tête, cycle clic
 * ascendant/descendant) pour choisir l'ORDRE DES GROUPES : {@link #toggleSortOrder} délègue le vrai
 * tri à {@code AlbumTreeTableModel.setGroupSort()} (qui réordonne {@code flat} et notifie AVANT que
 * ce sorter n'agisse), puis laisse Swing mettre à jour l'icône — le "tri" que Swing effectue
 * lui-même ensuite est un no-op garanti : chaque colonne a un comparateur toujours-égal, donc un tri
 * stable ne change jamais l'ordre déjà établi par le modèle.
 */
public class GroupSortRowSorter extends TableRowSorter<AlbumTreeTableModel> {

    private static final Comparator<Object> ALWAYS_EQUAL = (a, b) -> 0;

    private final AlbumTreeTableModel model;

    public GroupSortRowSorter(AlbumTreeTableModel model) {
        super(model);
        this.model = model;
        for (int c = 0; c < model.getColumnCount(); c++) setComparator(c, ALWAYS_EQUAL);
    }

    @Override
    public void toggleSortOrder(int column) {
        if (!isSortable(column)) return;
        List<? extends SortKey> keys = getSortKeys();
        boolean currentlyAscending = !keys.isEmpty() && keys.get(0).getColumn() == column
                && keys.get(0).getSortOrder() == SortOrder.ASCENDING;
        boolean ascending = !currentlyAscending;
        model.setGroupSort(column, ascending);
        setSortKeys(List.of(new RowSorter.SortKey(column, ascending ? SortOrder.ASCENDING : SortOrder.DESCENDING)));
    }

    // Même filet de sécurité que SafeTableRowSorter : ne jamais planter l'EDT si le modèle change
    // pendant un événement de tri (ici sort() est un no-op de toute façon, mais rowsUpdated() reste
    // appelé par le mécanisme interne de DefaultRowSorter à chaque TableModelEvent).
    @Override public void rowsUpdated(int firstRow, int endRow) {
        try { super.rowsUpdated(firstRow, endRow); } catch (RuntimeException ignored) {}
    }
    @Override public void rowsUpdated(int firstRow, int endRow, int column) {
        try { super.rowsUpdated(firstRow, endRow, column); } catch (RuntimeException ignored) {}
    }
}
