package com.opentagger.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * FlowLayout qui passe les composants à la ligne suivante quand la largeur disponible manque, au
 * lieu de les laisser dépasser hors de vue (comportement par défaut de FlowLayout — jamais de
 * retour à la ligne, les composants en trop deviennent simplement inaccessibles hors des limites
 * du conteneur parent). Utilisé pour la barre d'outils principale et la bande de statistiques —
 * signalé par l'utilisateur (2026-08-11) : boutons masqués/impossibles à cliquer en fenêtre
 * réduite. Implémentation standard (calcule sa propre hauteur préférée en simulant le passage à
 * la ligne, contrairement à FlowLayout qui ne prévoit qu'une seule ligne).
 */
class WrapLayout extends FlowLayout {

    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            // Largeur disponible : celle du conteneur qui HÉBERGE ce panneau (ex. la région CENTER
            // d'un BorderLayout), pas ce panneau lui-même — au premier appel (pas encore posé), sa
            // propre largeur vaut 0, ce qui ferait passer chaque composant sur sa propre ligne.
            int targetWidth = target.getSize().width;
            Container container = target;
            while (container.getSize().width == 0 && container.getParent() != null) {
                container = container.getParent();
            }
            targetWidth = container.getSize().width;
            if (targetWidth == 0) targetWidth = Integer.MAX_VALUE;

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2);
            int maxWidth = targetWidth - horizontalInsetsAndGap;

            Dimension dim = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int nmembers = target.getComponentCount();
            for (int i = 0; i < nmembers; i++) {
                Component m = target.getComponent(i);
                if (!m.isVisible()) continue;
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                    dim.width = Math.max(dim.width, rowWidth);
                    dim.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) rowWidth += hgap;
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            dim.width = Math.max(dim.width, rowWidth);
            dim.height += rowHeight + vgap;
            dim.width += horizontalInsetsAndGap;
            dim.height += insets.top + insets.bottom + vgap;
            return dim;
        }
    }
}
