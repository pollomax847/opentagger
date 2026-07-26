package com.opentagger.ui;

import javax.swing.Icon;
import java.awt.*;
import java.awt.geom.*;

/**
 * Petites icônes vectorielles pour la barre d'outils principale — avant ce correctif, tous les
 * boutons de MainFrame étaient du texte seul, sans aucun repère visuel, contrairement à quasiment
 * toute application desktop moderne (même trait plein/fin que le logo "OT" peint à la main dans
 * buildHeader(), même esprit que les chips de statut : minimaliste, un seul trait, pas de remplissage).
 * Dessinées en Java2D plutôt qu'importées (SVG/PNG) : aucune dépendance supplémentaire, un seul
 * style cohérent, et la couleur suit celle du bouton (accent sombre sur fond teal, gris clair
 * ailleurs) sans avoir à maintenir plusieurs jeux de fichiers par thème/couleur.
 */
public final class ToolbarIcon implements Icon {

    public enum Kind { FOLDER_OPEN, TAG, TAG_CHECK, SAVE, STOP, REFRESH, TRANSCODE, UPLOAD }

    private final Kind kind;
    private final Color color;
    private final int size;

    public ToolbarIcon(Kind kind, Color color) { this(kind, color, 16); }

    public ToolbarIcon(Kind kind, Color color, int size) {
        this.kind = kind;
        this.color = color;
        this.size = size;
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.translate(x, y);
        g2.setColor(color);
        float s = size / 16f; // toutes les coordonnées ci-dessous sont calées sur une grille 16×16
        g2.setStroke(new BasicStroke(1.6f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (kind) {
            case FOLDER_OPEN -> paintFolderOpen(g2, s);
            case TAG         -> paintTag(g2, s, false);
            case TAG_CHECK   -> paintTag(g2, s, true);
            case SAVE        -> paintSave(g2, s);
            case STOP        -> paintStop(g2, s);
            case REFRESH     -> paintRefresh(g2, s);
            case TRANSCODE   -> paintTranscode(g2, s);
            case UPLOAD      -> paintUpload(g2, s);
        }
        g2.dispose();
    }

    private void paintFolderOpen(Graphics2D g2, float s) {
        Path2D p = new Path2D.Float();
        p.moveTo(2*s, 4*s);  p.lineTo(2*s, 12.5*s); p.lineTo(13.5*s, 12.5*s);
        p.lineTo(14.7*s, 6.5*s); p.lineTo(5.3*s, 6.5*s); p.lineTo(4.6*s, 4*s); p.closePath();
        g2.draw(p);
        g2.draw(new Line2D.Float(2*s, 4*s, 7*s, 4*s));
    }

    private void paintTag(Graphics2D g2, float s, boolean check) {
        Path2D p = new Path2D.Float();
        p.moveTo(2.5*s, 2.5*s); p.lineTo(8.5*s, 2.5*s); p.lineTo(13.5*s, 7.5*s);
        p.lineTo(7.5*s, 13.5*s); p.lineTo(2.5*s, 8.5*s); p.closePath();
        g2.draw(p);
        g2.fill(new Ellipse2D.Float(4.3f*s, 4.3f*s, 1.6f*s, 1.6f*s));
        if (check) {
            Path2D ck = new Path2D.Float();
            ck.moveTo(9.5*s, 9.5*s); ck.lineTo(10.8*s, 10.8*s); ck.lineTo(13*s, 8*s);
            g2.setStroke(new BasicStroke(1.8f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(ck);
        }
    }

    private void paintSave(Graphics2D g2, float s) {
        g2.draw(new RoundRectangle2D.Float(2.2f*s, 2.2f*s, 11.6f*s, 11.6f*s, 2.5f*s, 2.5f*s));
        Path2D tray = new Path2D.Float();
        tray.moveTo(5*s, 2.2*s); tray.lineTo(5*s, 6.5*s); tray.lineTo(11*s, 6.5*s); tray.lineTo(11*s, 2.2*s);
        g2.draw(tray);
        g2.draw(new Line2D.Float(5.5f*s, 10.5f*s, 10.5f*s, 10.5f*s));
    }

    private void paintStop(Graphics2D g2, float s) {
        g2.draw(new RoundRectangle2D.Float(3.2f*s, 3.2f*s, 9.6f*s, 9.6f*s, 2f*s, 2f*s));
    }

    private void paintRefresh(Graphics2D g2, float s) {
        Arc2D arc = new Arc2D.Float(2.5f*s, 2.5f*s, 11*s, 11*s, 30, 300, Arc2D.OPEN);
        g2.draw(arc);
        // Pointe de flèche à l'extrémité de l'arc (30°)
        double a = Math.toRadians(30);
        float cx = 8*s, cy = 8*s, r = 5.5f*s;
        float ax = (float) (cx + r * Math.cos(a)), ay = (float) (cy - r * Math.sin(a));
        Path2D arrow = new Path2D.Float();
        arrow.moveTo(ax - 2.6*s, ay - 1.0*s); arrow.lineTo(ax, ay); arrow.lineTo(ax - 1.0*s, ay + 2.6*s);
        g2.draw(arrow);
    }

    private void paintTranscode(Graphics2D g2, float s) {
        // Trois barres façon onde audio, hauteurs différentes — même idée que les VU-mètres,
        // reconnaissable comme "audio" sans dessiner une forme d'onde complète.
        g2.draw(new Line2D.Float(4*s, 11*s, 4*s, 5*s));
        g2.draw(new Line2D.Float(8*s, 13*s, 8*s, 3*s));
        g2.draw(new Line2D.Float(12*s, 10*s, 12*s, 6*s));
    }

    private void paintUpload(Graphics2D g2, float s) {
        g2.draw(new Line2D.Float(8*s, 12.5f*s, 8*s, 4*s));
        Path2D arrow = new Path2D.Float();
        arrow.moveTo(4.8*s, 7.2*s); arrow.lineTo(8*s, 4*s); arrow.lineTo(11.2*s, 7.2*s);
        g2.draw(arrow);
        g2.draw(new Line2D.Float(3.5f*s, 12.5f*s, 12.5f*s, 12.5f*s));
    }
}
