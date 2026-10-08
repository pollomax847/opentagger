package com.opentagger.ui;

import java.util.List;
import javax.swing.ListSelectionModel;

/**
 * Sélection de beaucoup de lignes d'un coup. Appeler addSelectionInterval() ligne par ligne déclenchait, à CHAQUE ligne, le
 * rafraîchissement du panneau de détail sur toute la sélection déjà accumulée : sur 50 000 fichiers c'est quadratique, et l'interface
 * restait gelée des heures (menu resté ouvert à l'écran, fil d'événements saturé). Ici : un seul événement final, et des plages
 * contiguës plutôt qu'une ligne à la fois.
 */
public final class RowSelection {
    private RowSelection() {}

    /** Remplace la sélection par ces lignes (indices de vue, triés croissants). */
    public static void select(ListSelectionModel model, List<Integer> rows) {
        model.setValueIsAdjusting(true);
        try {
            model.clearSelection();
            int i = 0;
            while (i < rows.size()) {
                int start = rows.get(i), end = start;
                while (i + 1 < rows.size() && rows.get(i + 1) == end + 1) { i++; end = rows.get(i); }
                model.addSelectionInterval(start, end);
                i++;
            }
        } finally {
            model.setValueIsAdjusting(false);
        }
    }
}
