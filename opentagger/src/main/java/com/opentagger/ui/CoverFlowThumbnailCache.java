package com.opentagger.ui;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Cache de vignettes pour {@link CoverFlowPanel} — décode la pochette embarquée d'un fichier déjà
 * tagué (même technique que {@code MainFrame.loadCoverThumb()} : jaudiotagger → premier artwork →
 * {@code ImageIO}), la met à l'échelle et lui compose un reflet miroir UNE SEULE FOIS par album, puis
 * met le résultat en cache — jamais recalculé à chaque frame d'animation, c'est la décision de perf
 * la plus importante de Cover Flow (voir le plan). Toutes les structures internes ne sont mutées que
 * sur l'EDT (les tâches de fond ne font que décoder ; leur résultat revient via
 * {@code SwingUtilities.invokeLater}), donc aucune synchronisation n'est nécessaire.
 */
public class CoverFlowThumbnailCache {

    public static final int TILE_SIZE          = 220;
    private static final int REFLECTION_HEIGHT = TILE_SIZE / 2;
    private static final int COMPOSITE_HEIGHT  = TILE_SIZE + REFLECTION_HEIGHT;
    private static final int LRU_CAPACITY      = 80;
    // Pool dédié et volontairement petit (2, pas le pool "batch.threads" utilisé pour le taguage
    // réseau) — voir MainFrame.scanTagPool, dont le commentaire documente déjà qu'empiler plusieurs
    // pools sur le même disque (potentiellement mécanique/USB) cause un thrashing sévère si Cover
    // Flow est ouvert pendant qu'un scan/taguage tourne aussi.
    private static final int POOL_SIZE = 2;

    public enum CoverState { UNREQUESTED, LOADING, LOADED, NO_COVER }

    private static final BufferedImage LOADING_COMPOSITE  =
        buildPlaceholder(new Color(0x2A2C30), "♪", new Color(0x4DB6AC));
    private static final BufferedImage NO_COVER_COMPOSITE =
        buildPlaceholder(new Color(0x2A2C30), "♪", new Color(0x6B6B6B));

    private final Map<String, BufferedImage> lru = new LinkedHashMap<>(LRU_CAPACITY, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > LRU_CAPACITY;
        }
    };
    private final Map<String, CoverState>  state   = new java.util.HashMap<>();
    private final Map<String, Future<?>>   pending = new java.util.HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
        Thread t = new Thread(r, "coverflow-thumb-loader");
        t.setDaemon(true);
        return t;
    });

    private Runnable onLoaded;

    /** Appelé sur l'EDT chaque fois qu'une vignette termine de charger (pour déclencher un repaint). */
    public void setOnLoaded(Runnable r) { this.onLoaded = r; }

    public CoverState stateOf(String key) { return state.getOrDefault(key, CoverState.UNREQUESTED); }

    /** Composite prêt à dessiner (pochette + reflet) — jamais null : vignette réelle si chargée,
     *  sinon l'un des deux placeholders statiques partagés (jamais confondus, voir le plan). */
    public BufferedImage compositeFor(String key) {
        CoverState s = stateOf(key);
        if (s == CoverState.LOADED)    return lru.get(key);
        if (s == CoverState.NO_COVER)  return NO_COVER_COMPOSITE;
        return LOADING_COMPOSITE; // UNREQUESTED et LOADING se rendent identiquement
    }

    /**
     * Fenêtre de chargement — {@code windowInOrder} doit déjà être ordonnée du centre vers les bords
     * (chargement des plus proches en premier ; un {@code ExecutorService} standard traite sa file
     * en FIFO, donc l'ordre de soumission suffit à prioriser sans file de priorité dédiée — la
     * fenêtre réelle reste petite, ~13 entrées maximum). Annule les requêtes en vol pour toute clé
     * qui n'est plus dans la fenêtre (navigation rapide) — jamais {@code interrupt()} sur une lecture
     * jaudiotagger déjà démarrée (comportement non vérifié sous interruption), {@code cancel(false)}
     * seulement.
     */
    public void requestWindow(LinkedHashMap<String, File> windowInOrder) {
        pending.entrySet().removeIf(en -> {
            if (!windowInOrder.containsKey(en.getKey())) {
                en.getValue().cancel(false);
                return true;
            }
            return false;
        });
        for (Map.Entry<String, File> en : windowInOrder.entrySet()) {
            String key = en.getKey();
            CoverState s = stateOf(key);
            if (s == CoverState.LOADED || s == CoverState.NO_COVER || pending.containsKey(key)) continue;
            state.put(key, CoverState.LOADING);
            File file = en.getValue();
            pending.put(key, pool.submit(() -> loadOne(key, file)));
        }
    }

    private void loadOne(String key, File file) {
        BufferedImage composite;
        try {
            BufferedImage art = readEmbeddedArtwork(file);
            composite = art != null ? buildComposite(art) : null;
        } catch (Throwable t) {
            // Filet de sécurité volontairement large (Throwable, pas juste Exception) : sans lui,
            // une erreur inattendue pendant le décodage/composite (image corrompue, OutOfMemoryError
            // sur une pochette énorme...) laisserait cette vignette bloquée en LOADING pour toujours,
            // silencieusement — puisque ExecutorService avale les exceptions non récupérées d'une
            // tâche soumise via submit() sans jamais appeler le SwingUtilities.invokeLater() qui suit,
            // confirmé en développant cette classe (une erreur de classpath produisait exactement ce
            // symptôme). Loggé plutôt que masqué : un vrai bug reste visible, juste sans bloquer l'UI.
            System.err.println("[CoverFlow] échec chargement " + file + " : " + t);
            composite = null;
        }
        final BufferedImage finalComposite = composite;
        SwingUtilities.invokeLater(() -> {
            pending.remove(key);
            if (finalComposite != null) {
                lru.put(key, finalComposite);
                state.put(key, CoverState.LOADED);
            } else {
                state.put(key, CoverState.NO_COVER);
            }
            if (onLoaded != null) onLoaded.run();
        });
    }

    /** Même technique que MainFrame.loadCoverThumb() : jaudiotagger → premier artwork → ImageIO. */
    private static BufferedImage readEmbeddedArtwork(File f) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            if (tag == null) return null;
            Artwork art = tag.getFirstArtwork();
            if (art == null) return null;
            byte[] data = art.getBinaryData();
            try (InputStream in = new ByteArrayInputStream(data)) {
                return ImageIO.read(in);
            }
        } catch (Exception e) { return null; }
    }

    public void shutdown() { pool.shutdownNow(); }

    // ── Construction du composite pochette + reflet ─────────────────────────

    private static BufferedImage buildComposite(BufferedImage art) {
        BufferedImage cover = scaleToTile(art);

        BufferedImage composite = new BufferedImage(TILE_SIZE, COMPOSITE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = composite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cover, 0, 0, null);

        // Reflet : la pochette retournée verticalement en place (flip autour de son propre axe, pas
        // décalée hors-cadre) — seule la moitié haute du résultat (= moitié BASSE de la pochette
        // d'origine, adjacente au reflet) est gardée, donnant une continuité visuelle à la jointure.
        BufferedImage reflection = new BufferedImage(TILE_SIZE, REFLECTION_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D rg = reflection.createGraphics();
        rg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform flip = AffineTransform.getScaleInstance(1, -1);
        flip.translate(0, -TILE_SIZE);
        rg.drawImage(cover, flip, null);
        rg.dispose();

        // Fondu : opaque (partiellement) à la jointure, transparent en bas.
        Graphics2D mg = reflection.createGraphics();
        mg.setComposite(AlphaComposite.DstIn);
        mg.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 90),
                                       0, REFLECTION_HEIGHT, new Color(255, 255, 255, 0)));
        mg.fillRect(0, 0, TILE_SIZE, REFLECTION_HEIGHT);
        mg.dispose();

        g.drawImage(reflection, 0, TILE_SIZE, null);
        g.dispose();
        return composite;
    }

    /** Redimensionne en remplissant le carré TILE_SIZE×TILE_SIZE (recadrage centré si la pochette
     *  n'est pas déjà carrée — rare en pratique, la plupart des pochettes embarquées le sont). */
    private static BufferedImage scaleToTile(BufferedImage src) {
        int sw = src.getWidth(), sh = src.getHeight();
        double scale = Math.max((double) TILE_SIZE / sw, (double) TILE_SIZE / sh);
        int dw = (int) Math.ceil(sw * scale), dh = (int) Math.ceil(sh * scale);
        BufferedImage scaled = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, (TILE_SIZE - dw) / 2, (TILE_SIZE - dh) / 2, dw, dh, null);
        g.dispose();
        return scaled;
    }

    private static BufferedImage buildPlaceholder(Color bg, String glyph, Color glyphColor) {
        BufferedImage art = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = art.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(bg);
        g.fillRoundRect(0, 0, TILE_SIZE, TILE_SIZE, 18, 18);
        g.setColor(glyphColor);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 72f));
        FontMetrics fm = g.getFontMetrics();
        int tx = (TILE_SIZE - fm.stringWidth(glyph)) / 2;
        int ty = (TILE_SIZE - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(glyph, tx, ty);
        g.dispose();
        return buildComposite(art);
    }
}
