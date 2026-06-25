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

/**
 * Dialogue d'aperçu du renommage avant application.
 *
 * Affiche un tableau "Chemin actuel → Nouveau chemin" avec code couleur :
 *  - Vert  : fichier qui sera renommé/déplacé
 *  - Gris  : déjà au bon endroit, aucun changement
 *  - Rouge : erreur de calcul du nouveau nom
 *
 * Le bouton "Appliquer" n'est actif que si au moins un fichier sera modifié.
 */
public class RenamePreviewDialog extends JDialog {

    public enum RowState { WILL_RENAME, ALREADY_OK, ERROR }

    public record PreviewRow(FileEntry entry, String oldName, String newName,
                             String newPath, RowState state, String errorMsg) {}

    private final List<PreviewRow>  rows;
    private final Runnable          onApply;
    private final JLabel            lblSummary;

    public RenamePreviewDialog(Frame owner, List<PreviewRow> rows, Runnable onApply) {
        this(owner, rows, "Aperçu du renommage", onApply);
    }

    public RenamePreviewDialog(Frame owner, List<PreviewRow> rows, String title, Runnable onApply) {
        super(owner, title, true);
        this.rows    = rows;
        this.onApply = onApply;
        setSize(900, 560);
        setMinimumSize(new Dimension(640, 360));
        setLocationRelativeTo(owner);

        long willRename = rows.stream().filter(r -> r.state() == RowState.WILL_RENAME).count();
        long errors     = rows.stream().filter(r -> r.state() == RowState.ERROR).count();
        lblSummary = new JLabel(buildSummaryText(willRename, errors, rows.size()));
        lblSummary.setBorder(new EmptyBorder(8, 12, 8, 12));

        JTable previewTable = buildTable();
        JScrollPane scroll  = new JScrollPane(previewTable);
        scroll.setBorder(null);

        JButton btnApply  = new JButton("Appliquer (" + willRename + " renommage(s))");
        JButton btnCancel = new JButton("Annuler");
        btnApply.setEnabled(willRename > 0);
        if (willRename > 0)
            btnApply.putClientProperty("FlatLaf.style", "background: #1a6030");

        btnApply.addActionListener(e -> { onApply.run(); dispose(); });
        btnCancel.addActionListener(e -> dispose());

        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        footer.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        footer.add(btnCancel);
        footer.add(btnApply);

        getRootPane().setDefaultButton(btnApply);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(lblSummary, BorderLayout.NORTH);
        getContentPane().add(scroll,     BorderLayout.CENTER);
        getContentPane().add(footer,     BorderLayout.SOUTH);
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Calcule l'aperçu pour tous les fichiers TAGGED de la liste.
     * Utilise FileRenamer.preview() — aucun fichier n'est déplacé.
     */
    public static List<PreviewRow> compute(FileTableModel model, int maskIndex) {
        return compute(model, maskIndex, null);
    }

    /**
     * Variante avec destRoot explicite (pour "Organiser en dossiers").
     * Si destRoot == null, utilise le scanRoot de chaque fichier.
     */
    public static List<PreviewRow> compute(FileTableModel model, int maskIndex, Path destRoot) {
        FileRenamer renamer = new FileRenamer();
        List<PreviewRow> result = new ArrayList<>();

        for (int i = 0; i < model.getRowCount(); i++) {
            FileEntry e = model.get(i);
            if (e.status != FileEntry.Status.TAGGED) continue;
            if (e.currentPath == null) continue;

            Path current  = e.currentPath;
            Path root     = destRoot != null ? destRoot
                          : (e.scanRoot != null ? e.scanRoot : current.getParent());
            String curName = current.getFileName().toString();
            String ext     = curName.contains(".") ? curName.substring(curName.lastIndexOf('.')) : "";

            try {
                String newName = renamer.preview(e.activeTags(), maskIndex, ext);
                Path newPath = root.resolve(newName).normalize();
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

    // ── Construction du tableau ───────────────────────────────────────────────

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

        // Tooltip sur la cellule = chemin complet
        t.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = t.rowAtPoint(e.getPoint());
                if (row >= 0 && row < rows.size()) {
                    PreviewRow pr = rows.get(row);
                    t.setToolTipText("<html>" + pr.oldName() + "<br>→ " + pr.newPath() + "</html>");
                }
            }
        });

        // Renderer coloré par état
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable tbl, Object val, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
                if (!sel && row < rows.size()) {
                    setForeground(switch (rows.get(row).state()) {
                        case WILL_RENAME -> new Color(0x81c784);  // vert clair
                        case ALREADY_OK  -> UIManager.getColor("Label.disabledForeground");
                        case ERROR       -> new Color(0xef9a9a);  // rouge clair
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
