package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Mode de revue rapide au clavier — inspiré du "QuickTag" d'OneTagger (touches 1-5 pour noter,
 * flèches pour naviguer, sans jamais toucher la souris), demandé le 2026-09-18 après une
 * comparaison OpenTagger vs Picard/OneTagger : contrairement à OneTagger, ce mode ne couvre QUE
 * la note (rating) — pas de genre/mood en saisie libre ici, pour éviter tout conflit entre les
 * touches 1-5 comme raccourci et comme frappe dans un champ texte (OneTagger a de vrais champs de
 * saisie dans son QuickTag ; ici, aucun champ texte n'a jamais le focus pendant la revue).
 *
 * Réutilise le pipeline d'écriture EXISTANT (snapshot undo → TagWriter → rafraîchissement ligne →
 * suivi iTunes) via {@link MainFrame#quickReviewSave}, le même que l'édition manuelle depuis
 * DetailPanel ({@code applyDetail()}) — écriture immédiate sur disque à chaque note, pas une file
 * d'attente à valider plus tard, cohérent avec l'esprit "revue en rafale" du mode.
 */
public class QuickReviewDialog extends JDialog {

    private final MainFrame        owner;
    private final List<FileEntry>  entries;
    private final MetadataCache    correctionsCache = new MetadataCache();
    private int index = 0;

    private final JLabel lblPosition = new JLabel();
    private final JLabel lblTitle    = new JLabel();
    private final JLabel lblArtist   = new JLabel();
    private final JLabel lblAlbum    = new JLabel();
    private final JLabel lblRating   = new JLabel();

    public QuickReviewDialog(MainFrame owner, List<FileEntry> entries) {
        super(owner, I18n.t("Revue rapide (notation au clavier)"), true);
        this.owner   = owner;
        this.entries = entries;

        setLayout(new BorderLayout(12, 12));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        lblPosition.setFont(lblPosition.getFont().deriveFont(Font.PLAIN, 12f));
        lblPosition.setForeground(Color.GRAY);
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 20f));
        lblArtist.setFont(lblArtist.getFont().deriveFont(Font.PLAIN, 15f));
        lblAlbum.setFont(lblAlbum.getFont().deriveFont(Font.PLAIN, 13f));
        lblAlbum.setForeground(Color.GRAY);
        lblRating.setFont(lblRating.getFont().deriveFont(Font.PLAIN, 22f));
        for (JComponent c : List.of(lblPosition, lblTitle, lblArtist, lblAlbum)) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            info.add(c);
        }
        info.add(Box.createVerticalStrut(10));
        lblRating.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(lblRating);
        add(info, BorderLayout.CENTER);

        JLabel help = new JLabel(I18n.t(
            "<html>1-5 = noter et passer au suivant &nbsp;·&nbsp; 0 = effacer la note "
            + "&nbsp;·&nbsp; ← / → = naviguer sans noter &nbsp;·&nbsp; Échap = fermer</html>"));
        help.setFont(help.getFont().deriveFont(Font.PLAIN, 12f));
        help.setForeground(Color.GRAY);
        add(help, BorderLayout.SOUTH);

        bindKeys();
        setPreferredSize(new Dimension(520, 220));
        pack();
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e)  { correctionsCache.close(); }
            @Override public void windowClosing(java.awt.event.WindowEvent e) { correctionsCache.close(); }
        });
        showCurrent();
    }

    private void bindKeys() {
        JRootPane root = getRootPane();
        var im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = root.getActionMap();
        for (int n = 0; n <= 5; n++) {
            String key = "rate" + n;
            im.put(KeyStroke.getKeyStroke(String.valueOf(n).charAt(0)), key);
            final int rating = n;
            am.put(key, new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { rateAndAdvance(rating); }
            });
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0),  "prev");
        am.put("prev", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { move(-1); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next");
        am.put("next", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { move(1); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        am.put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); }
        });
    }

    private void rateAndAdvance(int rating) {
        FileEntry e = entries.get(index);
        // "0" = note vide plutôt que littéralement "0" — cohérent avec le champ texte libre
        // existant (tfRating), où une note "0" n'a pas de sens musical établi.
        owner.quickReviewSave(correctionsCache, e, rating == 0 ? "" : String.valueOf(rating));
        showCurrent(); // reflète la note tout de suite avant d'avancer, repère visuel utile
        move(1);
    }

    private void move(int delta) {
        int next = index + delta;
        if (next < 0 || next >= entries.size()) return; // reste sur place en bord de liste
        index = next;
        showCurrent();
    }

    private void showCurrent() {
        FileEntry e = entries.get(index);
        TagInfo   t = e.activeTags();
        lblPosition.setText(I18n.t("%d / %d", index + 1, entries.size()));
        lblTitle.setText(t.title.isBlank() ? e.filename() : t.title);
        lblArtist.setText(t.artist.isBlank() ? I18n.t("(artiste inconnu)") : t.artist);
        lblAlbum.setText(t.album);
        lblRating.setText(starText(t.rating));
        if (index == entries.size() - 1) {
            setTitle(I18n.t("Revue rapide (notation au clavier) — dernier fichier"));
        } else {
            setTitle(I18n.t("Revue rapide (notation au clavier)"));
        }
    }

    private static String starText(String rating) {
        int n;
        try { n = Integer.parseInt(rating.trim()); } catch (Exception ex) { n = -1; }
        if (n < 0 || n > 5) return I18n.t("(pas de note)");
        return "★".repeat(n) + "☆".repeat(5 - n);
    }
}
