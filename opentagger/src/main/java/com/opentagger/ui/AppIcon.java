package com.opentagger.ui;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Logo OpenTagger dessiné en Java2D — une étiquette (price-tag) musicale, sans dépendance externe.
 *
 * <p>Réécrit le 2026-09-08 après examen du rendu en grand. La version précédente avait trois défauts
 * qui ne se voyaient qu'agrandie :
 * <ul>
 *   <li><b>anatomie fausse</b> : le trou de ficelle était dans le coin HAUT-droit alors que la
 *       pointe de l'étiquette était en BAS-droit — sur une vraie étiquette le trou est À la pointe.
 *       Résultat : ça ne se lisait pas comme une étiquette mais comme un carré au coin mordu avec
 *       un point isolé ailleurs ;</li>
 *   <li><b>composition à l'étroit</b> : la note débordait presque du corps, barre contre le bord ;</li>
 *   <li><b>trop de couches</b> (carré + dégradé + trou + ficelle + note) : illisible à 16-32px,
 *       c'est-à-dire aux seules tailles où l'icône sert réellement.</li>
 * </ul>
 *
 * <p>Deux variantes désormais, parce que les deux contextes n'ont pas les mêmes besoins :
 * {@link #at(int)} garde le carré arrondi plein (obligatoire pour une icône de fenêtre/barre des
 * tâches, qui doit occuper sa case), tandis que {@link #glyph(int, Color)} ne dessine QUE la marque
 * sur fond transparent — dans une barre d'outils, un carré plein ferait autocollant collé par-dessus.
 */
public class AppIcon {

    /** Teal de l'accent applicatif — le logo et l'interface forment un seul système de couleur. */
    private static final Color TAG_COLOR = new Color(0x4D, 0xB6, 0xAC);
    private static final Color BG_TOP    = new Color(0x1A, 0x1F, 0x22);
    private static final Color BG_BOTTOM = new Color(0x0D, 0x10, 0x12);

    /** Retourne les icônes à 16, 32, 48, 64, 128 et 256 px pour setIconImages(). */
    public static List<Image> all() {
        List<Image> list = new ArrayList<>();
        for (int s : new int[]{16, 32, 48, 64, 128, 256}) list.add(at(s));
        return list;
    }

    /** Icône complète (carré arrondi sombre + étiquette teal) — icône de fenêtre/barre des tâches. */
    public static BufferedImage at(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = prepare(img);
        double sc = size / 100.0;

        g.setPaint(new GradientPaint(0, 0, BG_TOP, size, size, BG_BOTTOM));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 22 * sc, 22 * sc));

        g.setColor(TAG_COLOR);
        g.fill(tagShape(sc));

        // Trou + note évidés dans la couleur du fond plutôt que peints en blanc : une marque à deux
        // couleurs seulement, qui reste lisible une fois réduite à 16px.
        g.setColor(BG_BOTTOM);
        punch(g, sc, size);

        g.dispose();
        return img;
    }

    /**
     * Marque seule sur fond TRANSPARENT, dans la couleur demandée — pour l'en-tête de l'appli, où
     * le carré plein de {@link #at(int)} se lirait comme un autocollant posé sur la barre d'outils.
     * Le trou et la note sont réellement évidés (AlphaComposite.CLEAR), donc le fond de la barre —
     * quel que soit le thème choisi — transparaît au travers.
     */
    public static BufferedImage glyph(int size, Color color) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = prepare(img);
        double sc = size / 100.0;

        g.setColor(color);
        g.fill(tagShape(sc));

        g.setComposite(AlphaComposite.Clear);
        punch(g, sc, size);

        g.dispose();
        return img;
    }

    private static Graphics2D prepare(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    /**
     * Étiquette classique : corps rectangulaire arrondi dont le coin haut-droit est coupé en biais,
     * la coupe formant la pointe. Le trou (voir {@link #punch}) est placé DANS cette pointe, comme
     * sur une vraie étiquette — c'est ce rapport pointe/trou qui fait lire la forme d'un coup d'œil.
     */
    private static Path2D tagShape(double sc) {
        // Format PAYSAGE avec l'extrémité droite entièrement en pointe (2 diagonales, pas un simple
        // coin coupé) : un corps quasi carré avec une petite coupe se lisait comme un "fichier à
        // coin corné" — l'idiome le plus générique du monde des icônes — au lieu d'une étiquette.
        // C'est l'allongement + la vraie pointe qui font reconnaître l'objet d'un coup d'œil.
        double left = 10 * sc, top = 30 * sc, bottom = 80 * sc;
        double bodyRight = 62 * sc, tipX = 90 * sc, midY = 55 * sc;
        double r = 8 * sc;

        Path2D p = new Path2D.Double();
        p.moveTo(left + r, top);
        p.lineTo(bodyRight, top);
        p.lineTo(tipX, midY);                      // diagonale haute → pointe
        p.lineTo(bodyRight, bottom);               // diagonale basse ← pointe
        p.lineTo(left + r, bottom);
        p.quadTo(left, bottom, left, bottom - r);
        p.lineTo(left, top + r);
        p.quadTo(left, top, left + r, top);
        p.closePath();
        return p;
    }

    /** Trou de ficelle + double croche — les deux évidements de la marque. */
    private static void punch(Graphics2D g, double sc, int size) {
        // Trou de ficelle dans la pointe, sur son axe.
        double hr = 5.0 * sc;
        g.fill(new Ellipse2D.Double(70 * sc - hr, 55 * sc - hr, 2 * hr, 2 * hr));

        // ── Double croche, cadrée dans le corps avec de la marge de chaque côté ──
        // Sous 20px, les hampes (≈1px) disparaissent au rendu : une seule tête pleine reste
        // lisible, alors que la note complète tournerait à la bouillie grise.
        double h1x = 26 * sc, h1y = 68 * sc;
        double h2x = 44 * sc, h2y = 64 * sc;
        double headW = 14 * sc, headH = 9.5 * sc;

        AffineTransform saved = g.getTransform();
        g.rotate(Math.toRadians(-18), h1x, h1y);
        g.fill(new Ellipse2D.Double(h1x - headW / 2, h1y - headH / 2, headW, headH));
        g.setTransform(saved);

        if (size < 20) return;

        g.rotate(Math.toRadians(-18), h2x, h2y);
        g.fill(new Ellipse2D.Double(h2x - headW / 2, h2y - headH / 2, headW, headH));
        g.setTransform(saved);

        double stem1x = h1x + headW / 2 - 1.2 * sc;
        double stem2x = h2x + headW / 2 - 1.2 * sc;
        double stemTop1 = 42 * sc, stemTop2 = 38 * sc;
        g.setStroke(new BasicStroke((float) (3.2 * sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(stem1x, h1y - headH / 2, stem1x, stemTop1));
        g.draw(new Line2D.Double(stem2x, h2y - headH / 2, stem2x, stemTop2));

        // Barre de liaison, légèrement inclinée comme sur une vraie partition.
        g.setStroke(new BasicStroke((float) (5.0 * sc), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(stem1x, stemTop1, stem2x, stemTop2));
    }
}
