package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.FileRenamer;
import com.opentagger.I18n;
import com.opentagger.SavePreview;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Aperçu AVANT d'enregistrer (demande de l'utilisateur, 2026-11-07 : « montrer les changements prévus avant d'écrire »,
 * comme Bliss qui constate puis corrige). En haut, chaque fichier qui va être écrit : coché par défaut, ⚠ s'il devient un
 * AUTRE morceau (artiste ou titre sans rapport — c'est là que naissaient les fichiers « à deux chansons »). En bas, pour
 * le fichier choisi, chaque champ « avant → après » et le nouveau nom. Rien n'est écrit tant qu'on n'a pas cliqué
 * « Enregistrer ». Même principe que FixPreviewDialog (corrections), dont il reprend l'allure.
 */
class SavePreviewDialog extends JDialog {

    private static final Color WARN = new Color(255, 214, 153);
    private static final Color WARN_DARK = new Color(122, 74, 0);

    private final List<FileEntry> entries;
    private final List<List<SavePreview.Change>> changes = new ArrayList<>();
    private final boolean[] other;
    private final boolean[] checked;
    private final int maskIndex;
    private List<FileEntry> result;

    /** Les fichiers à enregistrer (ceux restés cochés), ou {@code null} si l'utilisateur annule. */
    static List<FileEntry> choose(Component parent, List<FileEntry> toSave, int maskIndex) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        SavePreviewDialog d = new SavePreviewDialog(owner, toSave, maskIndex);
        d.setVisible(true);
        return d.result;
    }

    private SavePreviewDialog(Window owner, List<FileEntry> toSave, int maskIndex) {
        super(owner, I18n.t("Vérifier avant d'enregistrer"), ModalityType.APPLICATION_MODAL);
        this.entries = toSave;
        this.maskIndex = maskIndex;
        this.other = new boolean[toSave.size()];
        this.checked = new boolean[toSave.size()];
        Arrays.fill(checked, true);
        boolean clear = Config.get().clearExistingTags();
        for (int i = 0; i < toSave.size(); i++) {
            FileEntry e = toSave.get(i);
            changes.add(SavePreview.changes(e.current, e.result, clear));
            other[i] = SavePreview.otherSong(e.current, e.result);
        }

        // ── fichiers ──
        JLabel summary = new JLabel();
        JCheckBox onlyWarn = new JCheckBox(I18n.t("Montrer seulement les fichiers qui deviennent un autre morceau (⚠)"));
        AbstractTableModel files = new AbstractTableModel() {
            final String[] cols = {"", "⚠", I18n.t("Fichier"), I18n.t("Avant"), I18n.t("Après"), I18n.t("Changements"), I18n.t("Identifié par")};
            @Override public int getRowCount() { return entries.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 5 ? Integer.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Object getValueAt(int r, int c) {
                FileEntry e = entries.get(r);
                return switch (c) {
                    case 0 -> checked[r];
                    case 1 -> other[r] ? "⚠" : "";
                    case 2 -> pathOf(e).getFileName().toString();
                    case 3 -> who(e.current);
                    case 4 -> who(e.result);
                    case 5 -> changes.get(r).size();
                    default -> e.result == null ? "" : MainFrame.sourceLabel(e.result.identificationSource);
                };
            }
            @Override public void setValueAt(Object v, int r, int c) {
                if (c == 0) { checked[r] = Boolean.TRUE.equals(v); fireTableCellUpdated(r, c); updateSummary(summary); }
            }
        };
        JTable fileTable = new JTable(files);
        TableRowSorter<AbstractTableModel> sorter = new TableRowSorter<>(files);
        fileTable.setRowSorter(sorter);
        onlyWarn.addActionListener(ev -> sorter.setRowFilter(onlyWarn.isSelected()
                ? new RowFilter<>() { @Override public boolean include(Entry<? extends AbstractTableModel, ? extends Integer> en) { return other[en.getIdentifier()]; } }
                : null));
        fileTable.setRowHeight(22);
        fileTable.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                int m = t.convertRowIndexToModel(row);
                if (!sel) { comp.setBackground(other[m] ? WARN : t.getBackground()); comp.setForeground(other[m] ? WARN_DARK : t.getForeground()); }
                return comp;
            }
        });
        int[] widths = {28, 28, 300, 240, 240, 90, 90};
        for (int i = 0; i < widths.length; i++) fileTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        fileTable.getColumnModel().getColumn(0).setMaxWidth(32);
        fileTable.getColumnModel().getColumn(1).setMaxWidth(32);

        // ── détail du fichier choisi ──
        List<SavePreview.Change> shown = new ArrayList<>();
        AbstractTableModel detail = new AbstractTableModel() {
            final String[] cols = {I18n.t("Champ"), I18n.t("Avant"), I18n.t("Après")};
            @Override public int getRowCount() { return shown.size(); }
            @Override public int getColumnCount() { return 3; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int r, int c) {
                SavePreview.Change ch = shown.get(r);
                return c == 0 ? I18n.t(ch.label()) : c == 1 ? oneLine(ch.before(), I18n.t("(vide)")) : oneLine(ch.after(), I18n.t("(effacé)"));
            }
        };
        JTable detailTable = new JTable(detail) {
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int r = rowAtPoint(e.getPoint()), c = columnAtPoint(e.getPoint());
                if (r < 0 || c < 1) return null;
                String v = c == 1 ? shown.get(r).before() : shown.get(r).after();
                return v == null || v.length() < 60 ? null : "<html><body style='width:480px'>" + escape(v).replace("\n", "<br>") + "</body></html>";
            }
        };
        detailTable.setRowHeight(22);
        detailTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                boolean id = row < shown.size() && shown.get(row).identity();
                if (!sel) { comp.setBackground(id ? WARN : t.getBackground()); comp.setForeground(id ? WARN_DARK : t.getForeground()); }
                return comp;
            }
        });
        detailTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        detailTable.getColumnModel().getColumn(1).setPreferredWidth(420);
        detailTable.getColumnModel().getColumn(2).setPreferredWidth(420);
        JLabel newName = new JLabel(" ");
        newName.setBorder(new EmptyBorder(4, 6, 4, 6));

        fileTable.getSelectionModel().addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            int v = fileTable.getSelectedRow();
            shown.clear();
            if (v >= 0) {
                int m = fileTable.convertRowIndexToModel(v);
                shown.addAll(changes.get(m));
                newName.setText(renameText(entries.get(m), clear));
            } else {
                newName.setText(" ");
            }
            detail.fireTableDataChanged();
        });

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(new JScrollPane(detailTable), BorderLayout.CENTER);
        bottom.add(newName, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(fileTable), bottom);
        split.setResizeWeight(0.55);

        // ── haut et bas ──
        JPanel north = new JPanel(new BorderLayout(0, 4));
        north.setBorder(new EmptyBorder(8, 12, 4, 12));
        north.add(summary, BorderLayout.CENTER);
        north.add(onlyWarn, BorderLayout.SOUTH);

        JCheckBox always = new JCheckBox(I18n.t("Toujours montrer cet aperçu avant d'enregistrer"), Config.get().previewBeforeSave());
        always.addActionListener(ev -> Config.get().set("tagging.preview_before_save", String.valueOf(always.isSelected())));
        JLabel note = new JLabel(I18n.t("<html>La pochette est cherchée au moment de l'enregistrement. Un fichier décoché reste « Identifié » : il sera proposé de nouveau. Tu pourras annuler cet enregistrement : Édition → Annuler un enregistrement.</html>"));
        note.setForeground(UIManager.getColor("Label.disabledForeground"));

        JButton all = new JButton(I18n.t("Tout cocher")), none = new JButton(I18n.t("Tout décocher"));
        JButton noWarn = new JButton(I18n.t("Décocher les ⚠"));
        JButton ok = new JButton(I18n.t("Enregistrer")), cancel = new JButton(I18n.t("Annuler"));
        all.addActionListener(ev -> { Arrays.fill(checked, true); files.fireTableDataChanged(); updateSummary(summary); });
        none.addActionListener(ev -> { Arrays.fill(checked, false); files.fireTableDataChanged(); updateSummary(summary); });
        noWarn.addActionListener(ev -> {
            for (int i = 0; i < checked.length; i++) if (other[i]) checked[i] = false;
            files.fireTableDataChanged();
            updateSummary(summary);
        });
        ok.addActionListener(ev -> {
            List<FileEntry> keep = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) if (checked[i]) keep.add(entries.get(i));
            result = keep;
            dispose();
        });
        cancel.addActionListener(ev -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(all); buttons.add(none); buttons.add(noWarn); buttons.add(ok); buttons.add(cancel);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setBorder(new EmptyBorder(8, 12, 8, 12));
        south.add(note, BorderLayout.NORTH);
        south.add(always, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);

        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        updateSummary(summary);
        setSize(1200, 760);
        setLocationRelativeTo(owner);
        if (!entries.isEmpty()) fileTable.setRowSelectionInterval(0, 0);
    }

    private void updateSummary(JLabel lbl) {
        int n = 0, warn = 0, warnChecked = 0;
        for (int i = 0; i < checked.length; i++) {
            if (checked[i]) n++;
            if (other[i]) { warn++; if (checked[i]) warnChecked++; }
        }
        lbl.setText(I18n.t("<html><b>%d / %d</b> fichier(s) cochés seront écrits sur le disque. "
                + "<b>%d</b> deviennent un autre morceau (⚠, en orange), dont %d cochés.<br>"
                + "Clique sur un fichier pour voir chaque changement. Décoche ce qu'il ne faut pas écrire.</html>",
                n, checked.length, warn, warnChecked));
    }

    private String renameText(FileEntry e, boolean clear) {
        if (maskIndex < 0) return I18n.t("Nom du fichier : inchangé (renommage automatique désactivé)");
        try {
            TagInfo after = SavePreview.effective(e.current, e.result, clear);
            String name = pathOf(e).getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot) : "";
            String target = new FileRenamer().preview(after, maskIndex, ext);
            String root = Config.get().effectiveLibraryRoot();
            return I18n.t("Nouveau nom : %s", (root == null || root.isBlank() ? "" : root + "/") + target);
        } catch (Exception ex) {
            return I18n.t("Nouveau nom : décidé à l'enregistrement");
        }
    }

    private static Path pathOf(FileEntry e) { return e.currentPath != null ? e.currentPath : e.file.toPath(); }

    private static String who(TagInfo t) {
        if (t == null) return "";
        String a = t.artist == null ? "" : t.artist, ti = t.title == null ? "" : t.title;
        return a.isBlank() && ti.isBlank() ? "" : a + " – " + ti;
    }

    private static String oneLine(String s, String ifBlank) {
        if (s == null || s.isBlank()) return ifBlank;
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 140 ? one.substring(0, 140) + "…" : one;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
