package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import com.opentagger.ui.LibraryChecks.Field;
import com.opentagger.ui.LibraryChecks.Fix;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Aperçu avant correction, comme le Case Checker de MediaMonkey : chaque fichier avec ses changements « avant → après »,
 * tous cochés, on décoche ce qu'il ne faut pas toucher. Rien n'est écrit tant qu'on n'a pas cliqué « Corriger ».
 */
class FixPreviewDialog extends JDialog {

    private final List<Fix<FileEntry>> fixes;
    private final boolean[] checked;
    private List<Fix<FileEntry>> result;

    /** Les corrections gardées, ou null si annulé. */
    static List<Fix<FileEntry>> choose(Component parent, String title, String how, List<Fix<FileEntry>> fixes,
                                       Function<FileEntry, TagInfo> tagsOf) {
        FixPreviewDialog d = new FixPreviewDialog(SwingUtilities.getWindowAncestor(parent), title, how, fixes, tagsOf);
        d.setVisible(true);
        return d.result;
    }

    private FixPreviewDialog(Window owner, String title, String how, List<Fix<FileEntry>> fixes,
                             Function<FileEntry, TagInfo> tagsOf) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        this.fixes = fixes;
        this.checked = new boolean[fixes.size()];
        java.util.Arrays.fill(checked, true);
        String[] changes = new String[fixes.size()];
        for (int i = 0; i < fixes.size(); i++) changes[i] = describe(fixes.get(i), tagsOf);

        JLabel lbl = new JLabel();
        Runnable count = () -> {
            int n = 0;
            for (boolean b : checked) if (b) n++;
            lbl.setText(I18n.t("<html><b>%d / %d</b> fichier(s) cochés — %s<br>Décoche ceux qu'il ne faut pas toucher.</html>",
                    n, fixes.size(), how));
        };
        AbstractTableModel model = new AbstractTableModel() {
            @Override public int getRowCount() { return fixes.size(); }
            @Override public int getColumnCount() { return 3; }
            @Override public String getColumnName(int c) {
                return c == 0 ? "" : c == 1 ? I18n.t("Fichier") : I18n.t("Avant → après");
            }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Object getValueAt(int r, int c) {
                return c == 0 ? checked[r] : c == 1 ? fixes.get(r).path().getFileName().toString() : changes[r];
            }
            @Override public void setValueAt(Object v, int r, int c) {
                if (c == 0) { checked[r] = Boolean.TRUE.equals(v); fireTableCellUpdated(r, c); count.run(); }
            }
        };
        JTable table = new JTable(model) {
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int r = rowAtPoint(e.getPoint());
                return r < 0 ? null : fixes.get(convertRowIndexToModel(r)).path().toString();
            }
        };
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(32);
        table.getColumnModel().getColumn(1).setPreferredWidth(330);
        table.getColumnModel().getColumn(2).setPreferredWidth(620);

        JButton all = new JButton(I18n.t("Tout cocher")), none = new JButton(I18n.t("Tout décocher"));
        JButton ok = new JButton(I18n.t("Corriger")), cancel = new JButton(I18n.t("Annuler"));
        all.addActionListener(e -> { java.util.Arrays.fill(checked, true); model.fireTableDataChanged(); count.run(); });
        none.addActionListener(e -> { java.util.Arrays.fill(checked, false); model.fireTableDataChanged(); count.run(); });
        ok.addActionListener(e -> {
            List<Fix<FileEntry>> keep = new ArrayList<>();
            for (int i = 0; i < fixes.size(); i++) if (checked[i]) keep.add(fixes.get(i));
            result = keep;
            dispose();
        });
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(all); buttons.add(none); buttons.add(ok); buttons.add(cancel);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setBorder(new EmptyBorder(8, 12, 8, 12));
        south.add(lbl, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        count.run();
        setSize(1080, 620);
        setLocationRelativeTo(owner);
    }

    private static String describe(Fix<FileEntry> f, Function<FileEntry, TagInfo> tagsOf) {
        TagInfo before = f.ref() == null ? null : tagsOf.apply(f.ref());
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Field, String> ch : f.changes().entrySet()) {
            if (b.length() > 0) b.append("   ·   ");
            String old = before == null ? "" : ch.getKey().get(before);
            b.append(label(ch.getKey())).append(" : « ").append(old).append(" » → « ").append(ch.getValue()).append(" »");
        }
        return b.toString();
    }

    static String label(Field f) {
        return switch (f) {
            case TITLE -> I18n.t("Titre");
            case ARTIST -> I18n.t("Artiste");
            case ALBUM -> I18n.t("Album");
            case ALBUM_ARTIST -> I18n.t("Artiste de l'album");
            case YEAR -> I18n.t("Année");
            case GENRE -> I18n.t("Genre");
            case TRACK -> I18n.t("Piste");
            case COMPILATION -> I18n.t("Compilation");
            case RG_TRACK_GAIN -> "ReplayGain";
            case RG_TRACK_PEAK -> I18n.t("Crête");
        };
    }
}
