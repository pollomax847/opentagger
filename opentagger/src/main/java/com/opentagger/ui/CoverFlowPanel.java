package com.opentagger.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carrousel de pochettes façon iTunes/Finder — pochette centrale droite, voisines penchées/réduites
 * avec reflet, glissement animé entre albums. Pas de vraie perspective 3D (Java2D/AffineTransform ne
 * peut pas exprimer un vrai warp trapézoïdal en un seul drawImage) : l'inclinaison est une
 * approximation par cisaillement (shear) + mise à l'échelle — délibéré, voir le plan.
 *
 * Le décodage/reflet de chaque pochette est entièrement pris en charge par
 * {@link CoverFlowThumbnailCache} et mis en cache — {@link #paintComponent} ne fait jamais plus
 * qu'un {@code drawImage} par vignette visible (≤9), le travail coûteux est découplé du rendu par
 * frame (voir le plan, "Points de vigilance perf").
 */
public class CoverFlowPanel extends JComponent {

    /** Un album affiché dans le carrousel. {@code representativeFile} est une copie figée au moment
     *  de l'instantané (pas une référence vivante vers FileEntry.currentPath, qui peut changer sous
     *  nos pieds si un renommage a lieu pendant que Cover Flow est ouvert). */
    public record AlbumTile(String groupKey, String title, String artist, File representativeFile) {}

    public interface SelectionListener { void onSelectionChanged(AlbumTile tile, int index, int total); }

    private static final int MAX_VISIBLE_RANGE = 4;   // ≤9 vignettes dessinées
    private static final int CACHE_WINDOW      = 6;   // fenêtre de préchargement, plus large que l'affichage
    private static final double EDGE_SCALE     = 0.62;
    private static final double SHEAR_MAX      = 0.75;
    private static final int    BASE_OFFSET_PX = 150;
    private static final int    STEP_PX        = 100;
    private static final float  EDGE_ALPHA     = 0.35f;
    private static final double EASE           = 0.25;

    private final CoverFlowThumbnailCache cache = new CoverFlowThumbnailCache();
    private final Timer animTimer = new Timer(30, this::onTick);

    private List<AlbumTile> allTiles = List.of();
    private List<AlbumTile> visible  = List.of();
    private int    targetIndex     = 0;
    private double displayPosition = 0;
    private Integer lastWindowAnchor = null;

    private final Map<Integer, Rectangle> lastPaintedBounds = new java.util.HashMap<>();
    private SelectionListener selectionListener;

    public CoverFlowPanel() {
        setOpaque(true);
        setPreferredSize(new Dimension(760, 420));
        cache.setOnLoaded(this::repaint);
        installInteractions();
    }

    public void setSelectionListener(SelectionListener l) { this.selectionListener = l; }

    // ── Données ──────────────────────────────────────────────────────────────

    public void setAlbums(List<AlbumTile> tiles) {
        allTiles = new ArrayList<>(tiles);
        visible  = new ArrayList<>(allTiles);
        targetIndex = 0;
        displayPosition = 0;
        lastWindowAnchor = null;
        updateCacheWindowIfNeeded();
        fireSelectionChanged();
        repaint();
    }

    /** Filtre texte insensible à la casse sur artiste/album — préserve l'album actuellement centré
     *  s'il correspond toujours, sinon saute au premier résultat. */
    public void filter(String text) {
        String needle = text == null ? "" : text.trim().toLowerCase();
        String currentKey = (!visible.isEmpty() && targetIndex < visible.size())
                ? visible.get(targetIndex).groupKey() : null;

        List<AlbumTile> next = new ArrayList<>();
        for (AlbumTile t : allTiles) {
            if (needle.isEmpty()
                    || t.title().toLowerCase().contains(needle)
                    || t.artist().toLowerCase().contains(needle)) {
                next.add(t);
            }
        }
        visible = next;
        lastWindowAnchor = null;

        int newIndex = 0;
        if (currentKey != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).groupKey().equals(currentKey)) { newIndex = i; break; }
            }
        }
        targetIndex = visible.isEmpty() ? 0 : Math.min(newIndex, visible.size() - 1);
        displayPosition = targetIndex; // pas d'animation sur un changement de filtre — saut direct
        updateCacheWindowIfNeeded();
        fireSelectionChanged();
        repaint();
    }

    public void dispose() {
        animTimer.stop();
        cache.shutdown();
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private void setTarget(int idx) {
        if (visible.isEmpty()) return;
        int clamped = Math.max(0, Math.min(visible.size() - 1, idx));
        if (clamped == targetIndex) return;
        targetIndex = clamped;
        if (!animTimer.isRunning()) animTimer.start();
        fireSelectionChanged();
    }

    private void fireSelectionChanged() {
        if (selectionListener == null) return;
        AlbumTile tile = (!visible.isEmpty() && targetIndex < visible.size()) ? visible.get(targetIndex) : null;
        selectionListener.onSelectionChanged(tile, targetIndex, visible.size());
    }

    private void onTick(ActionEvent e) {
        double diff = targetIndex - displayPosition;
        if (Math.abs(diff) < 0.01) {
            displayPosition = targetIndex;
            animTimer.stop();
        } else {
            displayPosition += diff * EASE;
        }
        updateCacheWindowIfNeeded();
        repaint();
    }

    // ── Chargement fenêtré des vignettes ────────────────────────────────────

    private void updateCacheWindowIfNeeded() {
        int anchor = (int) Math.round(displayPosition);
        if (lastWindowAnchor != null && lastWindowAnchor == anchor) return;
        lastWindowAnchor = anchor;
        LinkedHashMap<String, File> window = new LinkedHashMap<>();
        addIfInRange(window, anchor);
        for (int dist = 1; dist <= CACHE_WINDOW; dist++) {
            addIfInRange(window, anchor + dist);
            addIfInRange(window, anchor - dist);
        }
        cache.requestWindow(window);
    }

    private void addIfInRange(LinkedHashMap<String, File> window, int idx) {
        if (idx >= 0 && idx < visible.size()) {
            AlbumTile t = visible.get(idx);
            window.put(t.groupKey(), t.representativeFile());
        }
    }

    // ── Rendu ────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        int w = getWidth(), h = getHeight();
        g2.setPaint(new GradientPaint(0, 0, new Color(0x202225), 0, h, new Color(0x08090a)));
        g2.fillRect(0, 0, w, h);

        lastPaintedBounds.clear();
        if (visible.isEmpty()) {
            drawEmptyMessage(g2, w, h);
            g2.dispose();
            return;
        }

        int lo = Math.max(0, (int) Math.floor(displayPosition) - MAX_VISIBLE_RANGE);
        int hi = Math.min(visible.size() - 1, (int) Math.ceil(displayPosition) + MAX_VISIBLE_RANGE);
        List<Integer> order = new ArrayList<>();
        for (int i = lo; i <= hi; i++) order.add(i);
        // Du bord vers le centre (recouvrement correct) : la vignette la plus proche du centre est
        // dessinée en dernier, donc au-dessus.
        order.sort((a, b) -> Double.compare(dist(b), dist(a)));

        int centerX = w / 2;
        int baseY   = h / 2 + CoverFlowThumbnailCache.TILE_SIZE / 5;

        for (int idx : order) {
            double d  = idx - displayPosition;
            double ad = Math.abs(d);
            float alpha = tileAlpha(ad);
            if (alpha <= 0.02f) continue;

            BufferedImage composite = cache.compositeFor(visible.get(idx).groupKey());
            double scale = tileScale(ad);
            double x     = tileX(d, centerX);
            double shear = tileShear(d);

            AffineTransform t = new AffineTransform();
            t.translate(x, baseY);
            t.scale(scale, scale);
            t.shear(shear, 0);
            t.translate(-composite.getWidth() / 2.0, -CoverFlowThumbnailCache.TILE_SIZE / 2.0);

            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.drawImage(composite, t, null);
            g2.setComposite(old);

            int tileW = (int) Math.round(CoverFlowThumbnailCache.TILE_SIZE * scale);
            lastPaintedBounds.put(idx, new Rectangle(
                    (int) Math.round(x - tileW / 2.0),
                    (int) Math.round(baseY - tileW / 2.0),
                    tileW, tileW));
        }
        g2.dispose();
    }

    private double dist(int idx) { return Math.abs(idx - displayPosition); }

    private double tileScale(double ad) {
        if (ad <= 1) return 1.0 - (1.0 - EDGE_SCALE) * ad;
        return EDGE_SCALE * Math.pow(0.94, ad - 1);
    }

    private double tileX(double d, int centerX) {
        double ad = Math.abs(d), side = Math.signum(d);
        if (ad <= 1) return centerX + d * BASE_OFFSET_PX;
        return centerX + side * (BASE_OFFSET_PX + (ad - 1) * STEP_PX);
    }

    private double tileShear(double d) {
        double ramp = Math.min(1.0, Math.abs(d));
        return Math.signum(d) * SHEAR_MAX * ramp;
    }

    private float tileAlpha(double ad) {
        if (ad >= MAX_VISIBLE_RANGE) return 0f;
        return (float) (1.0 - (1.0 - EDGE_ALPHA) * (ad / MAX_VISIBLE_RANGE));
    }

    private void drawEmptyMessage(Graphics2D g2, int w, int h) {
        g2.setColor(new Color(0x888888));
        g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
        String msg = "Aucun album tagué à afficher";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
    }

    // ── Interactions ─────────────────────────────────────────────────────────

    private void installInteractions() {
        setFocusable(true);
        InputMap im = getInputMap(WHEN_FOCUSED);
        ActionMap am = getActionMap();
        im.put(KeyStroke.getKeyStroke("LEFT"),  "cf.prev");
        im.put(KeyStroke.getKeyStroke("RIGHT"), "cf.next");
        im.put(KeyStroke.getKeyStroke("HOME"),  "cf.first");
        im.put(KeyStroke.getKeyStroke("END"),   "cf.last");
        am.put("cf.prev",  action(e -> setTarget(targetIndex - 1)));
        am.put("cf.next",  action(e -> setTarget(targetIndex + 1)));
        am.put("cf.first", action(e -> setTarget(0)));
        am.put("cf.last",  action(e -> setTarget(visible.size() - 1)));

        addMouseWheelListener(e -> setTarget(targetIndex + (e.getWheelRotation() > 0 ? 1 : -1)));
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); }
            @Override public void mouseClicked(MouseEvent e) {
                Integer best = null;
                double bestDist = Double.MAX_VALUE;
                for (Map.Entry<Integer, Rectangle> en : lastPaintedBounds.entrySet()) {
                    if (en.getValue().contains(e.getPoint())) {
                        double dd = dist(en.getKey());
                        if (dd < bestDist) { bestDist = dd; best = en.getKey(); }
                    }
                }
                if (best != null) setTarget(best);
            }
        });
    }

    private AbstractAction action(java.util.function.Consumer<ActionEvent> body) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { body.accept(e); }
        };
    }
}
