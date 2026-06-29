package com.opentagger.ui;

import com.opentagger.FileRenamer;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Dialogue d'aperçu du renommage avant application.
 *
 * Affiche un tableau "Chemin actuel → Nouveau chemin" avec code couleur.
 * Pendant l'exécution du renommage, affiche une barre de progression.
 * Le bouton "Fermer" est toujours actif.
 */
public class RenamePreviewDialog extends JDialog {

    public enum RowState { WILL_RENAME, ALREADY_OK, ERROR }

    public record PreviewRow(FileEntry entry, String oldName, String newName,
                             String newPath, RowState state, String errorMsg) {}

    /**
     * Contrat du renommage exécuté en arrière-plan.
     * onProgress(n) : appelé sur l'EDT après chaque fichier (n = nombre traités)
     * onDone()      : appelé sur l'EDT à la fin
     */
    @FunctionalInterface
    public interface RenameJob {
        void start(IntConsumer onProgress, Runnable onDone);
    }

    private final List<PreviewRow>  rows;
    private final long              willRenameCount;

    // Composants footer
    private JButton      btnApply;
    private JButton      btnClose;
    private JProgressBar progressBar;
    private JLabel       lblProgress;
    private JPanel       progressPanel;

    public RenamePreviewDialog(Frame owner, List<PreviewRow> rows, RenameJob job) {
        this(owner, rows, "Aperçu du renommage", job);
    }

    public RenamePreviewDialog(Frame owner, List<PreviewRow> rows, String title, RenameJob job) {
        super(owner, title, false); // non-modal → fermeture libre
        this.rows            = rows;
        this.willRenameCount = rows.stream().filter(r -> r.state() == RowState.WILL_RENAME).count();

        setSize(900, 560);
        setMinimumSize(new Dimension(640, 360));
        setLocationRelativeTo(owner);

        long errors = rows.stream().filter(r -> r.state() == RowState.ERROR).count();
        JLabel lblSummary = new JLabel(buildSummaryText(willRenameCount, errors, rows.size()));
        lblSummary.setBorder(new EmptyBorder(8, 12, 8, 12));

        JScrollPane scroll = new JScrollPane(buildTable());
        scroll.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(lblSummary, BorderLayout.NORTH);
        getContentPane().add(scroll,     BorderLayout.CENTER);
        getContentPane().add(buildFooter(job), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // ── Footer avec barre de progression ──────────────────────────────────────

    private JPanel buildFooter(RenameJob job) {
        btnApply = new JButton("Appliquer (" + willRenameCount + " renommage(s))");
        btnClose = new JButton("Fermer");
        btnApply.setEnabled(willRenameCount > 0);
        if (willRenameCount > 0)
            btnApply.putClientProperty("FlatLaf.style", "background: #1a6030");

        progressBar = new JProgressBar(0, (int) willRenameCount);
        progressBar.setStringPainted(true);
        progressBar.setString("");
        progressBar.setPreferredSize(new Dimension(200, 18));

        lblProgress = new JLabel("  ");
        lblProgress.putClientProperty("FlatLaf.style", "foreground: #aaaaaa; font: 11 $defaultFont");

        progressPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        progressPanel.add(progressBar);
        progressPanel.add(lblProgress);
        progressPanel.setVisible(false);

        btnApply.addActionListener(e -> startRename(job));
        btnClose.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(btnApply);

        JPanel left  = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        left.add(progressPanel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        right.add(btnClose);
        right.add(btnApply);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        footer.add(left,  BorderLayout.WEST);
        footer.add(right, BorderLayout.EAST);
        return footer;
    }

    private void startRename(RenameJob job) {
        btnApply.setEnabled(false);
        btnApply.setText("En cours…");
        progressBar.setValue(0);
        progressPanel.setVisible(true);

        job.start(
            // onProgress — appelé sur EDT après chaque fichier
            done -> {
                progressBar.setValue(done);
                progressBar.setString(done + " / " + willRenameCount);
                lblProgress.setText("✓ " + done + " renommé(s)");
            },
            // onDone — appelé sur EDT à la fin
            () -> {
                progressBar.setValue((int) willRenameCount);
                progressBar.setString("Terminé");
                lblProgress.setText("✓ Terminé");
                btnApply.setVisible(false);
                btnClose.setText("Fermer");
                btnClose.putClientProperty("FlatLaf.style", "background: #1a6030");
                getRootPane().setDefaultButton(btnClose);
            }
        );
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static List<PreviewRow> compute(FileTableModel model, int maskIndex) {
        return compute(model, maskIndex, null);
    }

    public static List<PreviewRow> compute(FileTableModel model, int maskIndex, Path destRoot) {
        FileRenamer renamer = new FileRenamer();
        List<PreviewRow> result = new ArrayList<>();

        for (int i = 0; i < model.getRowCount(); i++) {
            FileEntry e = model.get(i);
            if (e.status != FileEntry.Status.TAGGED) continue;
            if (e.currentPath == null) continue;

            Path current = e.currentPath;
            Path root    = destRoot != null ? destRoot
                         : (e.scanRoot != null ? e.scanRoot : current.getParent());
            String curName = current.getFileName().toString();
            String ext     = curName.contains(".") ? curName.substring(curName.lastIndexOf('.')) : "";

            try {
                String newName = renamer.preview(e.activeTags(), maskIndex, ext);
                Path newPath   = root.resolve(newName).normalize();
                if (newName.isBlank()) {
                    result.add(new PreviewRow(e, current.toString(), "—", "—",
                        RowState.ERROR, "Masque vide — tags incomplets ?"));
                } else if (newPath.equals(current)) {
                    result.add(new PreviewRow(e, current.toString(), curName,
                        current.toString(), RowState.ALREADY_OK, ""));
                } else {
                    result.add(new PreviewRow(e, current.toString(), newName,
                        newPath.toString(), RowState.WILL_RENAME, ""));
                }
            } catch (Exception ex) {
                result.add(new PreviewRow(e, curName, "—", "—",
                    RowState.ERROR, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            }
        }
        return result;
    }

    // ── Tableau prévisualisation ───────────────────────────────────────────────

    private JTable buildTable() {
        String[] cols = {"Statut", "Fichier actuel", "Nouveau nom"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        for (PreviewRow r : rows) {
            String badge = switch (r.state()) {
                case WILL_RENAME -> "✎ Renommer";
                case ALREADY_OK  -> "✓ Inchangé";
                case ERROR       -> "✗ Erreur";
            };
            String newName = r.state() == RowState.ERROR ? r.errorMsg() : r.newName();
            model.addRow(new Object[]{badge, r.oldName(), newName});
        }

        JTable t = new JTable(model);
        t.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        t.getColumnModel().getColumn(0).setPreferredWidth(90);
        t.getColumnModel().getColumn(0).setMaxWidth(110);
        t.getColumnModel().getColumn(1).setPreferredWidth(340);
        t.getColumnModel().getColumn(2).setPreferredWidth(340);
        t.setRowHeight(22);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 1));

        t.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = t.rowAtPoint(e.getPoint());
                if (row >= 0 && row < rows.size()) {
                    PreviewRow pr = rows.get(row);
                    t.setToolTipText("<html>" + pr.oldName() + "<br>→ " + pr.newPath() + "</html>");
                }
            }
        });

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable tbl, Object val, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
                if (!sel && row < rows.size()) {
                    setForeground(switch (rows.get(row).state()) {
                        case WILL_RENAME -> new Color(0x81c784);
                        case ALREADY_OK  -> UIManager.getColor("Label.disabledForeground");
                        case ERROR       -> new Color(0xef9a9a);
                    });
                }
                return this;
            }
        };
        for (int c = 0; c < 3; c++) t.getColumnModel().getColumn(c).setCellRenderer(renderer);

        return t;
    }

    private String buildSummaryText(long willRename, long errors, int total) {
        String txt = "<html><b>" + willRename + " fichier(s) seront renommés</b>";
        txt += " sur " + total + " tagués.";
        if (errors > 0) txt += "  <font color='#ef9a9a'>✗ " + errors + " erreur(s)</font>";
        txt += "</html>";
        return txt;
    }
}
