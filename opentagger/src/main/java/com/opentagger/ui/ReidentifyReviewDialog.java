package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Revue des fichiers "Non identifié" retrouvés par une ré-identification forcée (empreinte audio
 * SongRec/AcoustID uniquement, tags/nom de fichier existants ignorés — voir MainFrame.
 * reidentifyUnmatched()) — équivalent local du mode "Identify and Fix Any Tags" de SongKong,
 * demandé explicitement par l'utilisateur (2026-08-10) après un tour d'horizon Jaikoz/Picard/
 * SongKong : ces trois outils proposent un scan en lot par empreinte pure, avec une revue groupée
 * avant application, qu'OpenTagger n'avait pas encore sous cette forme unifiée.
 *
 * Différence structurelle avec CompilationMatchDialog/DuplicatesDialog : ici, TaggingWorker a DÉJÀ
 * muté chaque FileEntry en IDENTIFIED (avec le nouveau TagInfo) avant l'ouverture de ce dialogue —
 * il n'y a rien à "appliquer" en écriture disque (ça reste le rôle d'"Enregistrer tout", comme pour
 * toute identification normale). Le rôle de ce dialogue est donc inverse : ACCEPTER (ne rien faire,
 * l'identification reste) ou REJETER (annuler, revenir à "Non identifié") chaque proposition — pas
 * de case pré-cochée (même convention que les deux autres dialogues de revue), donc fermer sans
 * rien cocher revient à TOUT rejeter par défaut, jamais à garder silencieusement une identification
 * jamais confirmée.
 */
public class ReidentifyReviewDialog extends JDialog {

    private final FileTableModel   tableModel;
    private final List<FileEntry>  results;
    /** Correspondance parallèle : boxes[i] ↔ results.get(i) — même motif que CompilationMatchDialog. */
    private final List<JCheckBox> boxes = new ArrayList<>();
    private boolean resolved = false;

    public ReidentifyReviewDialog(Frame owner, List<FileEntry> results, FileTableModel tableModel) {
        super(owner, I18n.t("Ré-identification — %d fichier(s) retrouvé(s)", results.size()), true);
        this.results    = results;
        this.tableModel = tableModel;
        setSize(820, 560);
        setMinimumSize(new Dimension(600, 340));
        setLocationRelativeTo(owner);

        JScrollPane scroll = new JScrollPane(buildContent());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll,        BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        // Fermeture par ESCAPE ou la croix de la fenêtre = même chose que "Rejeter tout" : jamais
        // de sortie silencieuse qui garderait des identifications jamais confirmées.
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "reject-all-close");
        getRootPane().getActionMap().put("reject-all-close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { rejectAllAndClose(); } });
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { rejectAllAndClose(); }
        });
    }

    private JPanel buildContent() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel intro = new JLabel(I18n.t(
            "<html><b>Cochez les fichiers à garder identifiés.</b> Retrouvés par empreinte audio "
            + "seule (SongRec/AcoustID), sans tenir compte des tags/nom de fichier existants. "
            + "Décoché = remis \"Non identifié\" comme avant. Rien n'est encore écrit sur le disque "
            + "— utilisez \"Enregistrer tout\" ensuite comme pour un taguage normal.</html>"));
        intro.putClientProperty("FlatLaf.style", "foreground: #cc7744; font: 11 $defaultFont");
        intro.setBorder(new EmptyBorder(0, 0, 10, 0));
        outer.add(intro);

        JPanel rows = new JPanel(new GridBagLayout());
        int rowIdx = 0;
        for (FileEntry entry : results) {
            JCheckBox cb = new JCheckBox();
            cb.setSelected(false); // jamais pré-coché — même convention que les autres revues
            boxes.add(cb);

            File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            TagInfo r = entry.result;

            JLabel lblFile = new JLabel(entry.filename());
            lblFile.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
            lblFile.setToolTipText(fichier.getAbsolutePath());

            String summary = r == null ? I18n.t("(aucune info)")
                : (r.artist.isBlank() ? "" : r.artist + " – ")
                  + (r.title.isBlank() ? I18n.t("(titre inconnu)") : r.title)
                  + (r.album.isBlank() ? "" : "  [" + r.album + "]")
                  + I18n.t("  (score=%d)", r.score);
            JLabel lblMatch = new JLabel(summary);
            lblMatch.putClientProperty("FlatLaf.style", "foreground: #a5d6a7; font: 11 $defaultFont");

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = rowIdx++; gc.insets = new Insets(4, 0, 4, 8);
            gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
            rows.add(cb, gc);
            gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
            gc.anchor = GridBagConstraints.WEST;
            JPanel textCol = new JPanel();
            textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
            textCol.add(lblFile);
            textCol.add(lblMatch);
            rows.add(textCol, gc);
        }
        outer.add(rows);
        return outer;
    }

    private JPanel buildFooter() {
        JButton btnAll     = new JButton(I18n.t("Tout cocher"));
        JButton btnNone     = new JButton(I18n.t("Tout décocher"));
        JButton btnConfirm  = new JButton(I18n.t("Confirmer la sélection"));
        JButton btnRejectAll= new JButton(I18n.t("Rejeter tout"));
        btnConfirm.putClientProperty("FlatLaf.style", "background: #1b5e20");

        btnAll      .addActionListener(e -> boxes.forEach(cb -> cb.setSelected(true)));
        btnNone     .addActionListener(e -> boxes.forEach(cb -> cb.setSelected(false)));
        btnConfirm  .addActionListener(e -> confirmSelectionAndClose());
        btnRejectAll.addActionListener(e -> rejectAllAndClose());

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));

        JLabel info = new JLabel("  " + I18n.t("%d fichier(s) retrouvé(s)", results.size()));
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

    /** Décoché = rejeté : remis "Non identifié" (annule la proposition de TaggingWorker). Coché =
     *  accepté : laissé IDENTIFIED tel quel, prêt pour "Enregistrer tout" comme un taguage normal. */
    private void confirmSelectionAndClose() {
        int accepted = 0, rejected = 0;
        for (int i = 0; i < boxes.size(); i++) {
            FileEntry entry = results.get(i);
            if (boxes.get(i).isSelected()) {
                accepted++;
            } else {
                revertToUnmatched(entry);
                rejected++;
            }
        }
        resolved = true;
        String msg = I18n.t("%d fichier(s) confirmé(s) — utilisez \"Enregistrer tout\" pour écrire "
                + "sur le disque. %d rejeté(s), remis \"Non identifié\".", accepted, rejected);
        JOptionPane.showMessageDialog(this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private void rejectAllAndClose() {
        if (resolved) { dispose(); return; } // déjà traité via confirmSelectionAndClose()
        for (FileEntry entry : results) revertToUnmatched(entry);
        resolved = true;
        dispose();
    }

    private void revertToUnmatched(FileEntry entry) {
        entry.status  = FileEntry.Status.SKIPPED;
        entry.result  = null;
        entry.message = I18n.t("Non identifié");
        tableModel.update(entry);
    }
}
