package com.opentagger.ui;

import com.opentagger.Config;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

/**
 * Splash screen affiché pendant 1,8 s au lancement.
 * Pas de dépendance images externes — tout dessiné en Java2D.
 */
public class SplashScreen extends JWindow {

    private static final int W = 460;
    private static final int H = 260;

    public SplashScreen() {
        setSize(W, H);
        setLocationRelativeTo(null);
        setContentPane(new SplashPanel());
    }

    public static void show(Runnable onDone) {
        SplashScreen splash = new SplashScreen();
        splash.setVisible(true);
        Timer timer = new Timer(1800, e -> {
            splash.dispose();
            onDone.run();
        });
        timer.setRepeats(false);
        timer.start();
    }

    // ── Panneau dessiné à la main ─────────────────────────────────────────────

    private static class SplashPanel extends JPanel {

        SplashPanel() {
            setPreferredSize(new Dimension(W, H));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            // ── Fond dégradé ─────────────────────────────────────────────
            GradientPaint bg = new GradientPaint(0, 0, new Color(10, 15, 30),
                    W, H, new Color(18, 28, 56));
            g.setPaint(bg);
            g.fill(new RoundRectangle2D.Float(0, 0, W, H, 20, 20));

            // ── Bordure lumineuse ─────────────────────────────────────────
            g.setColor(new Color(100, 181, 246, 55));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Float(1, 1, W-2, H-2, 20, 20));

            // ── Logo ──────────────────────────────────────────────────────
            BufferedImage icon = AppIcon.at(96);
            g.drawImage(icon, 32, (H - 96) / 2, 96, 96, null);

            // ── Titre ─────────────────────────────────────────────────────
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 36));
            g.drawString("OpenTagger", 150, H / 2 - 10);

            // ── Sous-titre ────────────────────────────────────────────────
            g.setColor(new Color(144, 202, 249));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString("Tagueur audio open-source", 150, H / 2 + 16);

            // ── Version ───────────────────────────────────────────────────
            g.setColor(new Color(120, 144, 156));
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            String ver = "v" + Config.get().str("app.version", "0.1.0");
            g.drawString(ver, 150, H / 2 + 38);

            // ── Bande de couleur décorative (accent) ──────────────────────
            GradientPaint accent = new GradientPaint(
                    150, H - 5, new Color(33, 150, 243),
                    W - 20, H - 5, new Color(0, 188, 212, 0));
            g.setPaint(accent);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Float(150, H - 6, W - 20, H - 6));

            // ── Petite note musicale décorative (droite) ──────────────────
            g.setColor(new Color(100, 181, 246, 60));
            g.setFont(new Font("SansSerif", Font.PLAIN, 80));
            g.drawString("♬", W - 100, H - 20);

            g.dispose();
        }
    }
}
