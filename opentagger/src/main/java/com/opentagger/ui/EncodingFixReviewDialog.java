package com.opentagger.ui;

import com.opentagger.EncodingFixer;
import com.opentagger.I18n;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Revue des corrections d'encodage cassé (mojibake — voir {@link EncodingFixer}) trouvées par
 * MainFrame.fixEncoding() (Outils → Re-traitement). Même convention que ReidentifyReviewDialog :
 * aucune case pré-cochée, fermer la fenêtre = tout rejeter, rien n'est écrit sur le disque avant
 * confirmation explicite — mais contrairement à ce dernier, la confirmation ÉCRIT immédiatement
 * (pas de second passage par "Enregistrer tout" : ces fichiers sont déjà tagués, on corrige juste
 * un texte déjà en place, pas une nouvelle identification en attente).
 */
public class EncodingFixReviewDialog extends JDialog {

    /** Un fichier candidat : `before` = tag actuel complet (lu sur disque), `after` = copie avec
     *  uniquement les champs mojibake corrigés — le reste identique, donc sûr à réécrire tel quel
     *  quel que soit le réglage "Effacer les tags existants". */
    public static class Candidate {
        public final File     file;
        public final FileEntry entry;
        public final TagInfo  before;
        public final TagInfo  after;
        public Candidate(File file, FileEntry entry, TagInfo before, TagInfo after) {
            this.file = file; this.entry = entry; this.before = before; this.after = after;
        }
    }

    private final FileTableModel     tableModel;
    private final List<Candidate>    candidates;
    private final List<JCheckBox>    boxes = new ArrayList<>();
    private boolean resolved = false;

    public EncodingFixReviewDialog(Frame owner, List<Candidate> candidates, FileTableModel tableModel) {
        super(owner, I18n.t("Correction d'encodage — %d fichier(s)", candidates.size()), true);
        this.candidates = candidates;
        this.tableModel = tableModel;
        setSize(860, 580);
        setMinimumSize(new Dimension(640, 360));
        setLocationRelativeTo(owner);

        JScrollPane scroll = new JScrollPane(buildContent());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll,        BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "reject-all-close");
        getRootPane().getActionMap().put("reject-all-close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { rejectAllAndClose(); } });
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { rejectAllAndClose(); }
        });
    }

    private Map<String, String[]> diffFields(Candidate c) {
        Map<String, String[]> diffs = new LinkedHashMap<>();
        put(diffs, "Titre",          c.before.title,       c.after.title);
        put(diffs, "Artiste",        c.before.artist,      c.after.artist);
        put(diffs, "Artiste album",  c.before.albumArtist, c.after.albumArtist);
        put(diffs, "Album",          c.before.album,       c.after.album);
        put(diffs, "Commentaire",    c.before.comment,     c.after.comment);
        return diffs;
    }

    private void put(Map<String, String[]> diffs, String label, String before, String after) {
        if (before != null && after != null && !before.equals(after)) diffs.put(label, new String[]{before, after});
    }

    private JPanel buildContent() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel intro = new JLabel(I18n.t(
            "<html><b>Cochez les fichiers à corriger.</b> Texte UTF-8 mal réinterprété en Latin-1 par "
            + "un autre outil (ex. \"Ã©\" → \"é\"). Décoché = fichier laissé tel quel. Confirmer écrit "
            + "immédiatement sur le disque (ces fichiers sont déjà tagués — pas de second passage par "
            + "\"Enregistrer tout\").</html>"));
        intro.putClientProperty("FlatLaf.style", "foreground: #cc7744; font: 11 $defaultFont");
        intro.setBorder(new EmptyBorder(0, 0, 10, 0));
        outer.add(intro);

        JPanel rows = new JPanel(new GridBagLayout());
        int rowIdx = 0;
        for (Candidate c : candidates) {
            JCheckBox cb = new JCheckBox();
            cb.setSelected(false);
            boxes.add(cb);

            JLabel lblFile = new JLabel(c.file.getName());
            lblFile.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
            lblFile.setToolTipText(c.file.getAbsolutePath());

            StringBuilder diffHtml = new StringBuilder("<html>");
            for (Map.Entry<String, String[]> e : diffFields(c).entrySet()) {
                diffHtml.append(I18n.t(e.getKey())).append(" : ")
                        .append(escape(e.getValue()[0])).append(" &rarr; <b>").append(escape(e.getValue()[1])).append("</b><br>");
            }
            diffHtml.append("</html>");
            JLabel lblDiff = new JLabel(diffHtml.toString());
            lblDiff.putClientProperty("FlatLaf.style", "foreground: #a5d6a7; font: 11 $defaultFont");

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = rowIdx++; gc.insets = new Insets(4, 0, 4, 8);
            gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE; gc.anchor = GridBagConstraints.NORTH;
            rows.add(cb, gc);
            gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
            gc.anchor = GridBagConstraints.WEST;
            JPanel textCol = new JPanel();
            textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
            textCol.add(lblFile);
            textCol.add(lblDiff);
            rows.add(textCol, gc);
        }
        outer.add(rows);
        return outer;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel buildFooter() {
        JButton btnAll      = new JButton(I18n.t("Tout cocher"));
        JButton btnNone      = new JButton(I18n.t("Tout décocher"));
        JButton btnConfirm   = new JButton(I18n.t("Corriger la sélection"));
        JButton btnRejectAll = new JButton(I18n.t("Rejeter tout"));
        btnConfirm.putClientProperty("FlatLaf.style", "background: #1b5e20");

        btnAll      .addActionListener(e -> boxes.forEach(cb -> cb.setSelected(true)));
        btnNone     .addActionListener(e -> boxes.forEach(cb -> cb.setSelected(false)));
        btnConfirm  .addActionListener(e -> confirmSelectionAndClose());
        btnRejectAll.addActionListener(e -> rejectAllAndClose());

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));

        JLabel info = new JLabel("  " + I18n.t("%d fichier(s) détecté(s)", candidates.size()));
        info.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(info);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnAll);
        right.add(btnNone);
        right.add(Box.createHorizontalStrut(12));
        right.add(btnRejectAll);
        right.add(btnConfirm);
        p.add(left,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void confirmSelectionAndClose() {
        TagWriter writer = new TagWriter();
        int fixed = 0, rejected = 0, failed = 0;
        for (int i = 0; i < boxes.size(); i++) {
            Candidate c = candidates.get(i);
            if (!boxes.get(i).isSelected()) { rejected++; continue; }
            try {
                writer.write(c.file, c.after);
                if (c.entry != null && c.entry.result != null) {
                    c.entry.result.title       = c.after.title;
                    c.entry.result.artist      = c.after.artist;
                    c.entry.result.albumArtist = c.after.albumArtist;
                    c.entry.result.album       = c.after.album;
                    c.entry.result.comment     = c.after.comment;
                    tableModel.update(c.entry);
                }
                fixed++;
            } catch (Exception ex) {
                failed++;
            }
        }
        resolved = true;
        String msg = I18n.t("%d fichier(s) corrigé(s). %d rejeté(s), laissé(s) tel quel.", fixed, rejected)
                + (failed > 0 ? I18n.t(" %d échec(s) d'écriture.", failed) : "");
        JOptionPane.showMessageDialog(this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void rejectAllAndClose() {
        if (resolved) { dispose(); return; }
        resolved = true;
        dispose();
    }
}
