package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import com.opentagger.ui.CompilationClusterWorker.CompilationMatch;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Revue des correspondances de compilation trouvées par {@link CompilationClusterWorker} avant
 * écriture — mode "proactif" choisi par l'utilisateur (AskUserQuestion, 2026-07-10) plutôt
 * qu'une réécriture automatique et silencieuse : une correspondance de nom de série peut se
 * tromper (rien ne garantit que "la release contient ce nom" ⇒ "c'est vraiment la bonne édition"),
 * donc chaque piste est présentée avec sa case à cocher, décochée par défaut (même convention que
 * {@link DuplicatesDialog} : "l'utilisateur décide"), et seules les lignes cochées sont écrites.
 */
public class CompilationMatchDialog extends JDialog {

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(CompilationMatchDialog.class.getName());

    private final FileTableModel        tableModel;
    private final List<CompilationMatch> matches;
    /** Correspondance parallèle : boxes[i] ↔ matches.get(i) — même motif que DuplicatesDialog. */
    private final List<JCheckBox> boxes = new ArrayList<>();

    public CompilationMatchDialog(Frame owner, List<CompilationMatch> matches, FileTableModel tableModel) {
        super(owner, I18n.t("Compilations trouvées — %d correspondance(s)", matches.size()), true);
        this.matches    = matches;
        this.tableModel = tableModel;
        setSize(760, 520);
        setMinimumSize(new Dimension(560, 320));
        setLocationRelativeTo(owner);

        JScrollPane scroll = new JScrollPane(buildContent());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll,        BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    private JPanel buildContent() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel intro = new JLabel(I18n.t("<html><b>Cochez les morceaux à relier à leur compilation.</b> "
            + "Rien n'est écrit tant que vous ne cliquez pas sur \"Appliquer\".</html>"));
        intro.putClientProperty("FlatLaf.style", "foreground: #cc7744; font: 11 $defaultFont");
        intro.setBorder(new EmptyBorder(0, 0, 10, 0));
        outer.add(intro);

        JPanel rows = new JPanel(new GridBagLayout());
        int rowIdx = 0;
        for (CompilationMatch m : matches) {
            JCheckBox cb = new JCheckBox();
            cb.setSelected(false); // jamais pré-coché — même convention que DuplicatesDialog
            boxes.add(cb);

            File fichier = m.entry().currentPath != null ? m.entry().currentPath.toFile() : m.entry().file;

            JLabel lblFile = new JLabel(m.entry().filename());
            lblFile.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
            lblFile.setToolTipText(fichier.getAbsolutePath());

            String currentAlbum = m.entry().result != null && !m.entry().result.album.isBlank()
                    ? m.entry().result.album : I18n.t("(album inconnu)");
            JLabel lblChange = new JLabel(currentAlbum + "  →  " + m.matchedTitle());
            lblChange.putClientProperty("FlatLaf.style", "foreground: #a5d6a7; font: 11 $defaultFont");

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = rowIdx++; gc.insets = new Insets(4, 0, 4, 8);
            gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
            rows.add(cb, gc);
            gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL; gc.gridwidth = 1;
            gc.anchor = GridBagConstraints.WEST;
            JPanel textCol = new JPanel();
            textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
            textCol.add(lblFile);
            textCol.add(lblChange);
            rows.add(textCol, gc);
        }
        outer.add(rows);
        return outer;
    }

    private JPanel buildFooter() {
        JButton btnAll   = new JButton(I18n.t("Tout cocher"));
        JButton btnNone  = new JButton(I18n.t("Tout décocher"));
        JButton btnApply = new JButton(I18n.t("Appliquer"));
        JButton btnClose = new JButton(I18n.t("Fermer"));
        btnApply.putClientProperty("FlatLaf.style", "background: #1b5e20");

        btnAll  .addActionListener(e -> boxes.forEach(cb -> cb.setSelected(true)));
        btnNone .addActionListener(e -> boxes.forEach(cb -> cb.setSelected(false)));
        btnApply.addActionListener(e -> applySelected());
        btnClose.addActionListener(e -> dispose());

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));

        JLabel info = new JLabel("  " + I18n.t("%d correspondance(s)", matches.size()));
        info.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(info);

        // Même ordre que DuplicatesDialog/MatchDialog : action principale à l'extrême droite,
        // Fermer juste à sa gauche.
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnAll);
        right.add(btnNone);
        right.add(Box.createHorizontalStrut(12));
        right.add(btnClose);
        right.add(btnApply);
        p.add(left,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void applySelected() {
        List<CompilationMatch> toApply = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).isSelected()) toApply.add(matches.get(i));
        }
        if (toApply.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Aucune correspondance cochée."),
                I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        // Publie la paire (entry, tags écrits) plutôt que de muter entry.result directement dans
        // doInBackground() : entry est aussi comparé en direct par le TableRowSorter depuis l'EDT,
        // une mutation concurrente pendant un tri casse le contrat de Comparator (même piège déjà
        // vu 697× ailleurs dans le projet — voir TaggingWorker/SaveWorker) — la mutation réelle se
        // fait dans process(), qui tourne sur l'EDT.
        record WriteResult(FileEntry entry, TagInfo written) {}
        new SwingWorker<Void, WriteResult>() {
            int written = 0, errors = 0;
            final TagWriter writer = new TagWriter();

            @Override protected Void doInBackground() {
                for (CompilationMatch m : toApply) {
                    File fichier = m.entry().currentPath != null
                            ? m.entry().currentPath.toFile() : m.entry().file;
                    try {
                        TagInfo w = writer.write(fichier, m.updated());
                        publish(new WriteResult(m.entry(), w));
                        written++;
                    } catch (Exception ex) {
                        LOG.warning("[Compilations] " + fichier.getName() + " : " + ex.getMessage());
                        errors++;
                    }
                }
                return null;
            }

            @Override protected void process(List<WriteResult> chunks) {
                for (WriteResult r : chunks) {
                    r.entry().result = r.written();
                    tableModel.update(r.entry());
                }
            }

            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                String msg = I18n.t("%d morceau(x) relié(s) à leur compilation", written)
                        + (errors > 0 ? I18n.t(", %d erreur(s)", errors) : "") + ".";
                JOptionPane.showMessageDialog(CompilationMatchDialog.this, msg,
                        I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        }.execute();
    }
}
