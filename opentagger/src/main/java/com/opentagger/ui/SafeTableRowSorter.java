package com.opentagger.ui;

import javax.swing.RowSorter;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.util.List;

/**
 * TableRowSorter qui survit à "Comparison method violates its general contract!".
 *
 * Cette exception apparaît quand une ligne affichée est comparée deux fois avec des valeurs
 * différentes AU MILIEU d'un même tri — ici parce que des workers d'arrière-plan (TaggingWorker
 * en tête, confirmé 697 fois en 3 jours de logs de production) mutent encore certains champs de
 * FileEntry/TagInfo pendant que ce thread trie, avant d'avoir fini de notifier l'EDT. La plupart
 * de ces sites ont été corrigés à la source (mutation différée sur l'EDT), mais TaggingWorker
 * gère aussi l'annulation en relisant son propre statut de manière synchrone entre deux étapes :
 * y appliquer le même correctif risquait d'introduire un vrai bug de contrôle pour éliminer un
 * bug d'affichage. Ce filet de sécurité couvre ce cas (et tout autre non prévu) sans toucher à
 * cette logique : le tri en échec est simplement abandonné, et se corrige de lui-même au
 * prochain événement de la table (très proche dans le temps pendant un taguage actif).
 */
public class SafeTableRowSorter<M extends TableModel> extends TableRowSorter<M> {

    public SafeTableRowSorter(M model) {
        super(model);
    }

    @Override
    public void sort() {
        try {
            super.sort();
        } catch (IllegalArgumentException ignored) {
            // Comparateur temporairement incohérent — pas fatal, on retente au prochain événement.
        }
    }

    @Override
    public void rowsUpdated(int firstRow, int endRow) {
        try {
            super.rowsUpdated(firstRow, endRow);
        } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void rowsUpdated(int firstRow, int endRow, int column) {
        try {
            super.rowsUpdated(firstRow, endRow, column);
        } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public List<? extends RowSorter.SortKey> getSortKeys() {
        try {
            return super.getSortKeys();
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }
}
