package com.opentagger.ui;

import javax.swing.RowSorter;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import java.util.List;

/**
 * TableRowSorter qui survit à "Comparison method violates its general contract!" et aux
 * incohérences transitoires de rang face au modèle.
 *
 * Cette première exception apparaît quand une ligne affichée est comparée deux fois avec des
 * valeurs différentes AU MILIEU d'un même tri — ici parce que des workers d'arrière-plan
 * (TaggingWorker en tête, confirmé 697 fois en 3 jours de logs de production) mutent encore
 * certains champs de FileEntry/TagInfo pendant que ce thread trie, avant d'avoir fini de notifier
 * l'EDT. La plupart de ces sites ont été corrigés à la source (mutation différée sur l'EDT), mais
 * TaggingWorker gère aussi l'annulation en relisant son propre statut de manière synchrone entre
 * deux étapes : y appliquer le même correctif risquait d'introduire un vrai bug de contrôle pour
 * éliminer un bug d'affichage. Ce filet de sécurité couvre ce cas (et tout autre non prévu) sans
 * toucher à cette logique : le tri en échec est simplement abandonné, et se corrige de lui-même au
 * prochain événement de la table (très proche dans le temps pendant un taguage actif).
 *
 * Trouvé en production (log utilisateur réel, "invalid range" répété 4600+ fois pendant un
 * taguage de bibliothèque de plusieurs heures) : `DefaultRowSorter.checkAgainstModel()` lève un
 * `IndexOutOfBoundsException("Invalid range")` — PAS un `IllegalArgumentException` — quand le
 * nombre de lignes qu'on lui annonce ne correspond plus au modèle au moment où l'EDT traite
 * l'événement (même cause racine que ci-dessus : plusieurs `FileTableModel.update()` mettant à
 * jour des lignes différentes se chevauchent avec l'ajout/retrait de lignes pendant un run actif).
 * Cette exception est une sœur d'`IllegalArgumentException` (pas une sous-classe) : elle passait
 * donc tout droit à travers l'ancien filtre, plantait l'EDT à chaque occurrence malgré le
 * commentaire de classe promettant de couvrir "tout autre [cas] non prévu". Élargi à
 * `RuntimeException` — filet de sécurité d'affichage déjà scopé à des méthodes qui ne font que
 * déléguer au sorter Swing, donc élargir n'y cache aucune vraie erreur de logique métier ailleurs.
 *
 * 2026-07-14 : `rowsInserted()` n'était PAS dans ce filet — trouvé via 154 occurrences du même
 * `IndexOutOfBoundsException("Invalid range")` sur `AWT-EventQueue-0` dans un log de production de
 * 19h+ (`DefaultRowSorter.checkAgainstModel` → `rowsInserted` → `JTable.notifySorter`, jamais
 * `rowsUpdated`/`sort`). Cause identique aux deux premières : `FileTableModel.add()`/`update()`
 * insèrent des lignes pendant qu'un run de taguage tourne, même course que celle déjà documentée
 * ci-dessus. Ajouté `rowsInserted`/`rowsDeleted` (sœur symétrique, même risque non encore observé
 * en prod mais même mécanisme) au filet.
 */
public class SafeTableRowSorter<M extends TableModel> extends TableRowSorter<M> {

    public SafeTableRowSorter(M model) {
        super(model);
    }

    @Override
    public void sort() {
        try {
            super.sort();
        } catch (RuntimeException ignored) {
            // Comparateur/rang temporairement incohérent — pas fatal, on retente au prochain événement.
        }
    }

    @Override
    public void rowsInserted(int firstRow, int endRow) {
        try {
            super.rowsInserted(firstRow, endRow);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void rowsDeleted(int firstRow, int endRow) {
        try {
            super.rowsDeleted(firstRow, endRow);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void rowsUpdated(int firstRow, int endRow) {
        try {
            super.rowsUpdated(firstRow, endRow);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public void rowsUpdated(int firstRow, int endRow, int column) {
        try {
            super.rowsUpdated(firstRow, endRow, column);
        } catch (RuntimeException ignored) {}
    }

    @Override
    public List<? extends RowSorter.SortKey> getSortKeys() {
        try {
            return super.getSortKeys();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }
}
