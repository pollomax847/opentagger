package com.opentagger.ui;

import com.opentagger.FileRenamer;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Un groupe d'aperçu — un album (artiste album + album) ou, à défaut de tags, un dossier. */
    private static final class Group {
        final String title;
        final List<PreviewRow> items = new ArrayList<>();
        boolean collapsed;
        Group(String title) { this.title = title; }
    }

    /** Une ligne affichée dans le tableau : soit un en-tête de groupe, soit un fichier. */
    private record DisplayRow(Group group, PreviewRow row) {
        boolean isHeader() { return group != null; }
    }

    private final List<PreviewRow>  rows;
    private final long              willRenameCount;
    private final List<Group>       groups       = new ArrayList<>();
    private final List<DisplayRow>  visibleRows  = new ArrayList<>();

    private JTable            table;
    private AbstractTableModel tableModel;

    // Composants footer
    private JButton      btnApply;
    private JButton      btnClose;
    private JProgressBar progressBar;
    private JLabel       lblProgress;
    private JPanel       progressPanel;

    public RenamePreviewDialog(Frame owner, List<PreviewRow> rows, RenameJob job) {
        this(owner, rows, I18n.t("Aperçu du renommage"), job);
    }

    public RenamePreviewDialog(Frame owner, List<PreviewRow> rows, String title, RenameJob job) {
        super(owner, title, false); // non-modal → fermeture libre
        this.rows            = rows;
        this.willRenameCount = rows.stream().filter(r -> r.state() == RowState.WILL_RENAME).count();
        buildGroups();

        setSize(900, 560);
        setMinimumSize(new Dimension(640, 360));
        setLocationRelativeTo(owner);

        long errors = rows.stream().filter(r -> r.state() == RowState.ERROR).count();
        JLabel lblSummary = new JLabel(buildSummaryText(willRenameCount, errors, rows.size()));
        lblSummary.setBorder(new EmptyBorder(8, 12, 4, 12));

        JScrollPane scroll = new JScrollPane(buildTable());
        scroll.setBorder(null);

        JPanel north = new JPanel(new BorderLayout());
        north.add(lblSummary,      BorderLayout.NORTH);
        north.add(buildGroupBar(), BorderLayout.SOUTH);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(north,  BorderLayout.NORTH);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getContentPane().add(buildFooter(job), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // ── Footer avec barre de progression ──────────────────────────────────────

    private JPanel buildFooter(RenameJob job) {
        btnApply = new JButton(I18n.t("Appliquer (%d renommage(s))", willRenameCount));
        btnClose = new JButton(I18n.t("Fermer"));
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
        btnApply.setText(I18n.t("En cours…"));
        progressBar.setValue(0);
        progressPanel.setVisible(true);

        job.start(
            // onProgress — appelé sur EDT après chaque fichier
            done -> {
                progressBar.setValue(done);
                progressBar.setString(done + " / " + willRenameCount);
                lblProgress.setText(I18n.t("✓ %d renommé(s)", done));
            },
            // onDone — appelé sur EDT à la fin
            () -> {
                progressBar.setValue((int) willRenameCount);
                progressBar.setString(I18n.t("Terminé"));
                lblProgress.setText(I18n.t("✓ Terminé"));
                btnApply.setVisible(false);
                btnClose.setText(I18n.t("Fermer"));
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
                        RowState.ERROR, I18n.t("Masque vide — tags incomplets ?")));
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

    // ── Regroupement par album ───────────────────────────────────────────────

    /** Voir {@link com.opentagger.AlbumGrouping} — logique partagée avec
     *  {@code AlbumTreeTableModel}/{@code CoverFlowDialog}, extraite pour ne plus être dupliquée. */
    private String groupKey(PreviewRow r) { return com.opentagger.AlbumGrouping.key(r.entry()); }

    private String groupTitle(PreviewRow r) { return com.opentagger.AlbumGrouping.title(r.entry()); }

    private String groupSummary(Group g) {
        long rename = g.items.stream().filter(x -> x.state() == RowState.WILL_RENAME).count();
        long errors = g.items.stream().filter(x -> x.state() == RowState.ERROR).count();
        long ok     = g.items.size() - rename - errors;
        List<String> parts = new ArrayList<>();
        if (rename > 0) parts.add(I18n.t("%d à renommer", rename));
        if (errors > 0) parts.add(I18n.t("%d erreur(s)", errors));
        if (ok     > 0) parts.add(I18n.t("%d inchangé(s)", ok));
        return I18n.t("%d fichier(s)", g.items.size()) + " — " + String.join(", ", parts);
    }

    private void buildGroups() {
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (PreviewRow r : rows)
            byKey.computeIfAbsent(groupKey(r), k -> new Group(groupTitle(r))).items.add(r);
        // Replié par défaut si tout l'album est déjà en place — rien à vérifier ; déplié dès
        // qu'il y a au moins un renommage ou une erreur à examiner.
        for (Group g : byKey.values())
            g.collapsed = g.items.stream().allMatch(x -> x.state() == RowState.ALREADY_OK);
        groups.addAll(byKey.values());
    }

    private void rebuildVisibleRows() {
        visibleRows.clear();
        for (Group g : groups) {
            visibleRows.add(new DisplayRow(g, null));
            if (!g.collapsed) for (PreviewRow r : g.items) visibleRows.add(new DisplayRow(null, r));
        }
    }

    private void refreshTable() {
        rebuildVisibleRows();
        tableModel.fireTableDataChanged();
    }

    private JPanel buildGroupBar() {
        JButton btnExpandAll   = new JButton(I18n.t("Tout déplier"));
        JButton btnCollapseAll = new JButton(I18n.t("Tout replier"));
        btnExpandAll.addActionListener(e -> { groups.forEach(g -> g.collapsed = false); refreshTable(); });
        btnCollapseAll.addActionListener(e -> { groups.forEach(g -> g.collapsed = true);  refreshTable(); });

        JLabel lblCount = new JLabel(I18n.t("%d album(s)/dossier(s)", groups.size()));
        lblCount.putClientProperty("FlatLaf.style", "foreground: #aaaaaa; font: 11 $defaultFont");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.setBorder(new EmptyBorder(0, 8, 6, 0));
        bar.add(btnExpandAll);
        bar.add(btnCollapseAll);
        bar.add(lblCount);
        return bar;
    }

    // ── Tableau prévisualisation ───────────────────────────────────────────────

    private JTable buildTable() {
        rebuildVisibleRows();
        String[] cols = {I18n.t("Statut"), I18n.t("Fichier actuel"), I18n.t("Nouveau nom")};

        tableModel = new AbstractTableModel() {
            @Override public int getRowCount()    { return visibleRows.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int row, int col) {
                DisplayRow dr = visibleRows.get(row);
                if (dr.isHeader()) {
                    Group g = dr.group();
                    return switch (col) {
                        case 0  -> g.collapsed ? "▸" : "▾";
                        case 1  -> g.title;
                        default -> groupSummary(g);
                    };
                }
                PreviewRow r = dr.row();
                return switch (col) {
                    case 0 -> switch (r.state()) {
                        case WILL_RENAME -> I18n.t("✎ Renommer");
                        case ALREADY_OK  -> I18n.t("✓ Inchangé");
                        case ERROR       -> I18n.t("✗ Erreur");
                    };
                    case 1  -> "    " + r.oldName();
                    default -> r.state() == RowState.ERROR ? r.errorMsg() : r.newName();
                };
            }
        };

        JTable t = new JTable(tableModel);
        table = t;
        t.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        t.getColumnModel().getColumn(0).setPreferredWidth(90);
        t.getColumnModel().getColumn(0).setMaxWidth(110);
        t.getColumnModel().getColumn(1).setPreferredWidth(340);
        t.getColumnModel().getColumn(2).setPreferredWidth(340);
        t.setRowHeight(22);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 1));

        t.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                int row = t.rowAtPoint(e.getPoint());
                if (row < 0 || row >= visibleRows.size()) return;
                DisplayRow dr = visibleRows.get(row);
                if (dr.isHeader()) {
                    dr.group().collapsed = !dr.group().collapsed;
                    refreshTable();
                }
            }
        });

        t.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = t.rowAtPoint(e.getPoint());
                if (row < 0 || row >= visibleRows.size()) return;
                DisplayRow dr = visibleRows.get(row);
                t.setToolTipText(dr.isHeader() ? dr.group().title
                        : "<html>" + dr.row().oldName() + "<br>→ " + dr.row().newPath() + "</html>");
            }
        });

        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable tbl, Object val, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(tbl, val, sel, focus, row, col);
                if (row < 0 || row >= visibleRows.size()) return this;
                DisplayRow dr = visibleRows.get(row);
                if (dr.isHeader()) {
                    setFont(getFont().deriveFont(Font.BOLD));
                    if (!sel) {
                        setBackground(tbl.getBackground().darker());
                        setForeground(UIManager.getColor("Label.foreground"));
                    }
                } else {
                    setFont(getFont().deriveFont(Font.PLAIN));
                    if (!sel) {
                        setBackground(tbl.getBackground());
                        setForeground(switch (dr.row().state()) {
                            case WILL_RENAME -> new Color(0x81c784);
                            case ALREADY_OK  -> UIManager.getColor("Label.disabledForeground");
                            case ERROR       -> new Color(0xef9a9a);
                        });
                    }
                }
                return this;
            }
        };
        for (int c = 0; c < 3; c++) t.getColumnModel().getColumn(c).setCellRenderer(renderer);

        return t;
    }

    private String buildSummaryText(long willRename, long errors, int total) {
        String txt = "<html><b>" + I18n.t("%d fichier(s) seront renommés", willRename) + "</b>";
        txt += " " + I18n.t("sur %d tagués.", total);
        if (errors > 0) txt += "  <font color='#ef9a9a'>" + I18n.t("✗ %d erreur(s)", errors) + "</font>";
        txt += "</html>";
        return txt;
    }
}
