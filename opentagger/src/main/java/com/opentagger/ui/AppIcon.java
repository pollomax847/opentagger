package com.opentagger.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Logo OpenTagger dessiné en Java2D — une étiquette (price-tag) musicale.
 * Génère les icônes à toutes les résolutions sans dépendance externe.
 */
public class AppIcon {

    /** Retourne les icônes à 16, 32, 48, 64, 128 et 256 px pour setIconImages(). */
    public static List<Image> all() {
        List<Image> list = new ArrayList<>();
        for (int s : new int[]{16, 32, 48, 64, 128, 256}) list.add(at(s));
        return list;
    }

    /** Génère l'icône à une taille précise (carrée). */
    public static BufferedImage at(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,      RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double sc = size / 100.0;
        draw(g, sc, size);
        g.dispose();
        return img;
    }

    // ── Dessin principal ─────────────────────────────────────────────────────

    private static void draw(Graphics2D g, double sc, int size) {

        // ── 1. Fond arrondi dégradé bleu nuit ────────────────────────────────
        GradientPaint bg = new GradientPaint(
            0, 0,    new Color(14, 21, 42),
            size, size, new Color(7, 12, 26));
        g.setPaint(bg);
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 22*sc, 22*sc));

        // ── 2. Corps de l'étiquette (price-tag) ──────────────────────────────
        //    Forme : rectangle aux coins ronds avec pointe en bas à droite
        GradientPaint tagGrad = new GradientPaint(
            (float)(12*sc), (float)(12*sc), new Color(25, 118, 210),
            (float)(82*sc), (float)(80*sc), new Color(13, 71, 161));
        g.setPaint(tagGrad);

        Path2D tag = tagShape(sc);
        g.fill(tag);

        // Contour subtil
        g.setPaint(new Color(100, 181, 246, 80));
        g.setStroke(new BasicStroke((float)(0.8*sc)));
        g.draw(tag);

        // ── 3. Trou de l'étiquette ─────────────────────────────────────────
        double hx = 72*sc, hy = 20*sc, hr = 4.5*sc;
        g.setColor(new Color(7, 12, 26));
        g.fill(new Ellipse2D.Double(hx - hr, hy - hr, 2*hr, 2*hr));

        // ── 4. Ficelle de l'étiquette ─────────────────────────────────────
        if (size >= 32) {
            g.setColor(new Color(144, 202, 249, 160));
            g.setStroke(new BasicStroke((float)(1.5*sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new CubicCurve2D.Double(
                hx + hr, hy,
                hx + 8*sc, hy - 4*sc,
                hx + 12*sc, hy + 4*sc,
                hx + 10*sc, hy + 14*sc));
        }

        // ── 5. Notes de musique doubles (♬) ───────────────────────────────
        g.setColor(Color.WHITE);
        drawDoubleNote(g, sc, size);
    }

    private static Path2D tagShape(double sc) {
        // Rectangle avec pointe au bas-droit (price-tag classique orienté à plat)
        double x1 = 11*sc, y1 = 13*sc;
        double x2 = 80*sc, y2 = 13*sc;
        double x3 = 80*sc, y3 = 58*sc;
        double px = 60*sc, py = 82*sc;  // pointe
        double x4 = 11*sc, y4 = 82*sc;
        double r  =  6*sc;              // rayon des coins

        Path2D p = new Path2D.Double();
        p.moveTo(x1 + r, y1);
        p.lineTo(x2 - r, y1);  p.quadTo(x2, y1, x2, y1 + r);
        p.lineTo(x3, y3);
        p.lineTo(px, py);
        p.lineTo(x4, y4);      p.quadTo(x1, y4, x1, y4 - r);
        p.lineTo(x1, y1 + r);  p.quadTo(x1, y1, x1 + r, y1);
        p.closePath();
        return p;
    }

    private static void drawDoubleNote(Graphics2D g, double sc, int size) {
        // Deux têtes de note + tiges + barre de liaison en haut
        float sw = (float)(2.8 * sc);
        if (size <= 24) sw = (float)(2.0 * sc);

        // ── Tête note gauche ─────────────────────────────────────────────
        double nx1 = 28*sc, ny1 = 63*sc;
        AffineTransform saved = g.getTransform();
        g.rotate(Math.toRadians(-18), nx1, ny1);
        g.fill(new Ellipse2D.Double(nx1 - 8*sc, ny1 - 5*sc, 14*sc, 9*sc));
        g.setTransform(saved);

        // ── Tige note gauche ─────────────────────────────────────────────
        g.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double stem1x = nx1 + 5.5*sc;
        g.draw(new Line2D.Double(stem1x, ny1 - 5*sc, stem1x, ny1 - 32*sc));

        // ── Tête note droite ─────────────────────────────────────────────
        double nx2 = 48*sc, ny2 = 57*sc;
        g.rotate(Math.toRadians(-18), nx2, ny2);
        g.fill(new Ellipse2D.Double(nx2 - 8*sc, ny2 - 5*sc, 14*sc, 9*sc));
        g.setTransform(saved);

        // ── Tige note droite ─────────────────────────────────────────────
        double stem2x = nx2 + 5.5*sc;
        g.draw(new Line2D.Double(stem2x, ny2 - 5*sc, stem2x, ny2 - 32*sc));

        // ── Barre de liaison ─────────────────────────────────────────────
        //    légère inclinaison comme une vraie partition
        g.setStroke(new BasicStroke((float)(4.5*sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(stem1x, ny1 - 32*sc, stem2x, ny2 - 32*sc));
        if (size >= 48) {
            // Deuxième barre (double croche)
            g.setStroke(new BasicStroke((float)(3.5*sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(stem1x, ny1 - 25*sc, stem2x, ny2 - 25*sc));
        }
    }
}
