package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import com.opentagger.ui.CompilationClusterWorker.CompilationMatch;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
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
    /** Correspondance parallèle : selected[i] ↔ matches.get(i) — état des cases à cocher, porté par
     *  le TableModel plutôt que par un composant Swing par ligne (voir buildTable()). */
    private final boolean[] selected;
    private AbstractTableModel matchTableModel;

    public CompilationMatchDialog(Frame owner, List<CompilationMatch> matches, FileTableModel tableModel) {
        super(owner, I18n.t("Compilations trouvées — %d correspondance(s)", matches.size()), true);
        this.matches    = matches;
        this.tableModel = tableModel;
        this.selected   = new boolean[matches.size()];
        setSize(760, 520);
        setMinimumSize(new Dimension(560, 320));
        setLocationRelativeTo(owner);

        // JTable (rendu virtualisé — seules les lignes visibles ont un composant réel) plutôt qu'un
        // JCheckBox + JPanel construits À LA MAIN pour CHAQUE correspondance et empilés dans un seul
        // conteneur GridBagLayout : viable pour quelques dizaines de lignes, mais avec le mode "en
        // attente jusqu'à la fin de session" (deferDialog, voir MainFrame.flushPendingCompilationMatches)
        // qui peut accumuler des milliers de correspondances sur une session d'endurance de plusieurs
        // heures, l'ouverture de cette fenêtre gelait l'EDT plusieurs minutes dans Component.
        // addNotify()/updateZOrder() — la mise en page Swing d'un conteneur à des milliers d'enfants
        // directs dégénère. Repéré en direct (2026-08-20) : appli complètement figée à l'ouverture,
        // même le propre filet de sécurité SelfHealthMonitor (qui tourne sur ce même thread EDT) ne
        // pouvait pas intervenir — seul un kill -9 manuel a permis de récupérer.
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(new EmptyBorder(10, 14, 0, 14));
        JLabel intro = new JLabel(I18n.t("<html><b>Cochez les morceaux à relier à leur compilation.</b> "
            + "Rien n'est écrit tant que vous ne cliquez pas sur \"Appliquer\".</html>"));
        intro.putClientProperty("FlatLaf.style", "foreground: #cc7744; font: 11 $defaultFont");
        intro.setBorder(new EmptyBorder(0, 0, 10, 0));
        top.add(intro, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(buildTable());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setBorder(null);
        top.add(scroll, BorderLayout.CENTER);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(top,           BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    private JTable buildTable() {
        matchTableModel = new AbstractTableModel() {
            @Override public int getRowCount() { return matches.size(); }
            @Override public int getColumnCount() { return 2; }
            @Override public String getColumnName(int col) {
                return col == 0 ? "" : I18n.t("Piste  →  compilation");
            }
            @Override public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int col) { return col == 0; }
            @Override public void setValueAt(Object v, int row, int col) {
                if (col != 0) return;
                selected[row] = Boolean.TRUE.equals(v);
                fireTableCellUpdated(row, col);
            }
            @Override public Object getValueAt(int row, int col) {
                if (col == 0) return selected[row];
                CompilationMatch m = matches.get(row);
                String currentAlbum = m.entry().result != null && !m.entry().result.album.isBlank()
                        ? m.entry().result.album : I18n.t("(album inconnu)");
                return "<html><b>" + escapeHtml(m.entry().filename()) + "</b><br>"
                        + "<span style='color:#a5d6a7'>" + escapeHtml(currentAlbum) + "  →  "
                        + escapeHtml(m.matchedTitle()) + "</span></html>";
            }
        };
        JTable t = new JTable(matchTableModel);
        t.setRowHeight(38);
        t.setShowGrid(false);
        t.setFillsViewportHeight(true);
        t.getColumnModel().getColumn(0).setMaxWidth(30);
        t.getColumnModel().getColumn(0).setMinWidth(30);
        // Tooltip = chemin complet, même info que le lblFile.setToolTipText() de la version
        // précédente — un seul renderer partagé par colonne (JTable), pas un composant par ligne.
        t.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tbl, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(tbl, v, sel, focus, row, col);
                File fichier = matches.get(row).entry().currentPath != null
                        ? matches.get(row).entry().currentPath.toFile() : matches.get(row).entry().file;
                setToolTipText(fichier.getAbsolutePath());
                return c;
            }
        });
        return t;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel buildFooter() {
        JButton btnAll   = new JButton(I18n.t("Tout cocher"));
        JButton btnNone  = new JButton(I18n.t("Tout décocher"));
        JButton btnApply = new JButton(I18n.t("Appliquer"));
        JButton btnClose = new JButton(I18n.t("Fermer"));
        btnApply.putClientProperty("FlatLaf.style", "background: #1b5e20");

        btnAll  .addActionListener(e -> { java.util.Arrays.fill(selected, true);  matchTableModel.fireTableDataChanged(); });
        btnNone .addActionListener(e -> { java.util.Arrays.fill(selected, false); matchTableModel.fireTableDataChanged(); });
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
        for (int i = 0; i < matches.size(); i++) {
            if (selected[i]) toApply.add(matches.get(i));
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
