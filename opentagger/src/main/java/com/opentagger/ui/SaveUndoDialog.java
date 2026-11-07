package com.opentagger.ui;

import com.opentagger.ByteFormat;
import com.opentagger.I18n;
import com.opentagger.SaveJournal;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * « Annuler un enregistrement » (Édition) — comme l'annulation de SongKong (demande de l'utilisateur, 2026-11-07). En
 * haut, les enregistrements gardés (Enregistrer tout, corrections, regroupements…), le plus récent d'abord. En bas, les
 * fichiers du lot choisi : où il était, où il est, ce qui sera remis. « Annuler cet enregistrement » remet les tags et
 * l'ancien nom/dossier de tous ses fichiers ; on peut aussi n'en cocher que certains. Voir {@link SaveJournal}.
 */
class SaveUndoDialog extends JDialog {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final Color WARN = new Color(255, 214, 153);
    private static final Color WARN_DARK = new Color(122, 74, 0);
    private static final Color OK = new Color(200, 235, 200);
    private static final Color OK_DARK = new Color(30, 90, 40);

    private final Consumer<List<SaveJournal.UndoResult>> onRestored;
    private List<SaveJournal.BatchInfo> batches = new ArrayList<>();
    private List<SaveJournal.Entry> files = new ArrayList<>();
    private boolean[] checked = new boolean[0];

    private final AbstractTableModel batchModel;
    private final AbstractTableModel fileModel;
    private final JTable batchTable, fileTable;
    private final JLabel status = new JLabel(" ");
    private final JButton undoAll = new JButton(I18n.t("Annuler cet enregistrement"));
    private final JButton undoChecked = new JButton(I18n.t("Annuler les fichiers cochés"));
    private final JButton forget = new JButton(I18n.t("Oublier"));
    private final JButton close = new JButton(I18n.t("Fermer"));
    private boolean busy;

    static void open(Component parent, Consumer<List<SaveJournal.UndoResult>> onRestored) {
        Window owner = parent == null ? null : parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        new SaveUndoDialog(owner, onRestored).setVisible(true);
    }

    private SaveUndoDialog(Window owner, Consumer<List<SaveJournal.UndoResult>> onRestored) {
        super(owner, I18n.t("Annuler un enregistrement"), ModalityType.APPLICATION_MODAL);
        this.onRestored = onRestored;

        batchModel = new AbstractTableModel() {
            final String[] cols = {I18n.t("Quand"), I18n.t("Quoi"), I18n.t("Fichiers"), I18n.t("Déplacés"), I18n.t("Déjà annulés"), I18n.t("Place")};
            @Override public int getRowCount() { return batches.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c >= 2 && c <= 4 ? Integer.class : String.class; }
            @Override public Object getValueAt(int r, int c) {
                SaveJournal.BatchInfo b = batches.get(r);
                return switch (c) {
                    case 0 -> WHEN.format(b.started()) + (b.open() ? " " + I18n.t("(en cours)") : "");
                    case 1 -> b.label();
                    case 2 -> b.files();
                    case 3 -> b.moved();
                    case 4 -> b.undone();
                    default -> ByteFormat.format(b.bytes());
                };
            }
        };
        batchTable = new JTable(batchModel);
        batchTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        batchTable.setRowHeight(22);
        batchTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        batchTable.getColumnModel().getColumn(1).setPreferredWidth(360);
        for (int c = 2; c <= 5; c++) batchTable.getColumnModel().getColumn(c).setPreferredWidth(80);
        batchTable.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) loadFiles(); });

        fileModel = new AbstractTableModel() {
            final String[] cols = {"", I18n.t("Fichier"), I18n.t("Avant (ancienne place)"), I18n.t("Maintenant"), I18n.t("État")};
            @Override public int getRowCount() { return files.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0 && !restored(files.get(r)); }
            @Override public void setValueAt(Object v, int r, int c) {
                if (c == 0) { checked[r] = Boolean.TRUE.equals(v); updateButtons(); }
            }
            @Override public Object getValueAt(int r, int c) {
                SaveJournal.Entry e = files.get(r);
                return switch (c) {
                    case 0 -> checked[r];
                    case 1 -> (e.kind() == SaveJournal.Kind.SIDECAR ? I18n.t("(annexe) ") : "") + e.original().getFileName();
                    case 2 -> String.valueOf(e.original().getParent());
                    case 3 -> e.current() == null ? I18n.t("(corbeille)") : e.moved() ? e.current().toString() : I18n.t("même place");
                    default -> stateText(e);
                };
            }
        };
        fileTable = new JTable(fileModel);
        fileTable.setRowHeight(22);
        fileTable.getColumnModel().getColumn(0).setMaxWidth(30);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(260);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(300);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(260);
        DefaultTableCellRenderer cell = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                setToolTipText(v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v));
                if (!sel) {
                    SaveJournal.Entry e = files.get(t.convertRowIndexToModel(row));
                    // Mêmes couleurs que l'aperçu avant d'enregistrer (fond clair, texte foncé), lisibles dans les deux thèmes.
                    if (restored(e)) { setBackground(OK); setForeground(OK_DARK); }
                    else if (!e.problem().isBlank() || e.undone() == SaveJournal.UndoState.MODIFIED || e.undone() == SaveJournal.UndoState.FAILED) {
                        setBackground(WARN); setForeground(WARN_DARK);
                    } else { setBackground(t.getBackground()); setForeground(t.getForeground()); }
                }
                return this;
            }
        };
        for (int c = 1; c < 5; c++) fileTable.getColumnModel().getColumn(c).setCellRenderer(cell);

        JLabel intro = new JLabel(I18n.t("<html>Chaque enregistrement est gardé ici %d jours. « Annuler » remet les tags "
                + "d'avant et l'ancien nom et dossier des fichiers. Un fichier modifié depuis (par un autre programme ou un "
                + "enregistrement plus récent) n'est remis que si tu le confirmes.</html>", keepDays()));
        intro.setBorder(new EmptyBorder(0, 0, 8, 0));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(batchTable), new JScrollPane(fileTable));
        split.setResizeWeight(0.35);

        undoAll.addActionListener(e -> undo(false));
        undoChecked.addActionListener(e -> undo(true));
        forget.addActionListener(e -> forgetSelected());
        close.addActionListener(e -> dispose());
        JButton all = new JButton(I18n.t("Tout cocher"));
        all.addActionListener(e -> { for (int i = 0; i < checked.length; i++) checked[i] = !restored(files.get(i)); fileModel.fireTableDataChanged(); updateButtons(); });
        JButton none = new JButton(I18n.t("Tout décocher"));
        none.addActionListener(e -> { java.util.Arrays.fill(checked, false); fileModel.fireTableDataChanged(); updateButtons(); });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(all); left.add(none); left.add(forget);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(undoChecked); right.add(undoAll); right.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.add(status, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));
        root.add(intro, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(close);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setSize(1180, 680);
        setLocationRelativeTo(owner);
        reload(null);
    }

    private static int keepDays() { return Math.max(1, com.opentagger.Config.get().num("undo.keep_days", 60)); }

    private static boolean restored(SaveJournal.Entry e) { return e.undone() == SaveJournal.UndoState.RESTORED; }

    private static String stateText(SaveJournal.Entry e) {
        if (e.undone() != null) {
            String what = switch (e.undone()) {
                case RESTORED -> I18n.t("remis comme avant");
                case MODIFIED -> I18n.t("modifié depuis : pas touché");
                case FAILED -> I18n.t("pas remis");
                case SKIPPED -> I18n.t("ignoré");
            };
            return e.undoMessage().isBlank() || e.undone() == SaveJournal.UndoState.RESTORED ? what : what + " — " + e.undoMessage();
        }
        if (e.trashed()) return e.current() == null ? I18n.t("à la corbeille (doublon)") : I18n.t("dans la corbeille de l'appli (doublon)");
        if (!e.problem().isBlank()) return I18n.t("seulement le nom : %s", e.problem());
        return switch (e.kind()) {
            case RAW, COPY -> e.moved() ? I18n.t("tags + nom et dossier") : I18n.t("tags");
            case MOVE -> I18n.t("nom et dossier");
            case SIDECAR -> e.sidecarExisted() ? I18n.t("ancien contenu ou ancienne place") : I18n.t("ajouté : sera mis à la corbeille");
            case NONE -> I18n.t("nom et dossier");
        };
    }

    // ── Chargement ──────────────────────────────────────────────────────────────────────────────

    private void reload(String keepId) {
        status.setText(I18n.t("Lecture des enregistrements gardés…"));
        new SwingWorker<List<SaveJournal.BatchInfo>, Void>() {
            @Override protected List<SaveJournal.BatchInfo> doInBackground() { return SaveJournal.list(); }
            @Override protected void done() {
                try { batches = get(); } catch (Exception ex) { batches = new ArrayList<>(); }
                batchModel.fireTableDataChanged();
                int sel = 0;
                for (int i = 0; keepId != null && i < batches.size(); i++) if (batches.get(i).id().equals(keepId)) sel = i;
                if (!batches.isEmpty()) batchTable.setRowSelectionInterval(sel, sel);
                else { files = new ArrayList<>(); checked = new boolean[0]; fileModel.fireTableDataChanged(); }
                long total = batches.stream().mapToLong(SaveJournal.BatchInfo::bytes).sum();
                status.setText(batches.isEmpty() ? I18n.t("Aucun enregistrement gardé pour l'instant.")
                        : I18n.t("%d enregistrement(s) gardé(s), %s dans %s", batches.size(), ByteFormat.format(total), SaveJournal.root()));
                updateButtons();
            }
        }.execute();
    }

    private SaveJournal.BatchInfo selectedBatch() {
        int r = batchTable.getSelectedRow();
        return r < 0 || r >= batches.size() ? null : batches.get(batchTable.convertRowIndexToModel(r));
    }

    private void loadFiles() {
        SaveJournal.BatchInfo b = selectedBatch();
        files = b == null ? new ArrayList<>() : new ArrayList<>(SaveJournal.entries(b.id()));
        checked = new boolean[files.size()];
        fileModel.fireTableDataChanged();
        updateButtons();
    }

    private void updateButtons() {
        SaveJournal.BatchInfo b = selectedBatch();
        boolean any = false, anyChecked = false;
        for (int i = 0; i < files.size(); i++) {
            if (!restored(files.get(i))) any = true;
            if (checked[i]) anyChecked = true;
        }
        boolean ok = !busy && b != null && !b.open();
        undoAll.setEnabled(ok && any);
        undoChecked.setEnabled(ok && anyChecked);
        forget.setEnabled(!busy && b != null && !b.open());
        undoAll.setToolTipText(b != null && b.open() ? I18n.t("Cet enregistrement est encore en cours.") : null);
    }

    // ── Annuler ─────────────────────────────────────────────────────────────────────────────────

    private void undo(boolean onlyChecked) {
        SaveJournal.BatchInfo b = selectedBatch();
        if (b == null) return;
        List<Integer> ns = new ArrayList<>();
        Set<Path> paths = new HashSet<>();
        for (int i = 0; i < files.size(); i++) {
            SaveJournal.Entry e = files.get(i);
            if (restored(e) || (onlyChecked && !checked[i])) continue;
            ns.add(e.n);
            paths.add(e.original());
            if (e.current() != null) paths.add(e.current());
        }
        if (ns.isEmpty()) return;
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.UNDO_SAVE);
        if (!blockers.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.t("Encore en cours : %s — réessaie quand c'est fini.", String.join(", ", blockers)),
                    getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<SaveJournal.BatchInfo> newer = SaveJournal.newerTouching(b.id(), paths);
        if (!newer.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (SaveJournal.BatchInfo n : newer.subList(0, Math.min(5, newer.size())))
                sb.append("<br>• ").append(WHEN.format(n.started())).append(" — ").append(esc(n.label()));
            int c = JOptionPane.showConfirmDialog(this, I18n.t("<html>Des enregistrements plus récents ont aussi touché ces fichiers :%s"
                            + "<br><br>Annule-les d'abord (ils sont plus haut dans la liste). Sinon, ces fichiers-là seront laissés "
                            + "tels quels.<br><br>Continuer quand même ?</html>", sb),
                    getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (c != JOptionPane.YES_OPTION) return;
        }
        int c = JOptionPane.showConfirmDialog(this, I18n.t("<html>Remettre <b>%d fichier(s)</b> comme avant l'enregistrement du %s ?"
                        + "<br>Les tags d'avant et l'ancien nom et dossier seront remis.</html>", ns.size(), WHEN.format(b.started())),
                getTitle(), JOptionPane.OK_CANCEL_OPTION);
        if (c != JOptionPane.OK_OPTION) return;
        run(b, ns, false);
    }

    private void run(SaveJournal.BatchInfo b, List<Integer> ns, boolean force) {
        busy = true;
        updateButtons();
        SwingWorker<List<SaveJournal.UndoResult>, String> w = new SwingWorker<>() {
            @Override protected List<SaveJournal.UndoResult> doInBackground() {
                return SaveJournal.undo(b.id(), ns, force, msg -> publish(msg));
            }
            @Override protected void process(List<String> chunks) {
                status.setText(I18n.t("Annulation : %s", chunks.get(chunks.size() - 1)));
            }
            @Override protected void done() {
                busy = false;
                List<SaveJournal.UndoResult> res;
                try { res = get(); } catch (Exception ex) { res = List.of(); status.setText(String.valueOf(ex.getMessage())); }
                int ok = 0, modified = 0, failed = 0, skipped = 0;
                List<Integer> modifiedNs = new ArrayList<>();
                String firstError = null;
                for (SaveJournal.UndoResult r : res) {
                    switch (r.state()) {
                        case RESTORED -> ok++;
                        case MODIFIED -> { modified++; modifiedNs.add(r.entry().n); }
                        case FAILED -> { failed++; if (firstError == null) firstError = r.entry().original().getFileName() + " : " + r.message(); }
                        case SKIPPED -> skipped++;
                    }
                }
                if (onRestored != null && ok > 0) onRestored.accept(res);
                String msg = I18n.t("%d fichier(s) remis comme avant", ok)
                        + (modified > 0 ? I18n.t(", %d modifié(s) depuis (pas touchés)", modified) : "")
                        + (failed > 0 ? I18n.t(", %d pas remis (ex. %s)", failed, firstError) : "")
                        + (skipped > 0 ? I18n.t(", %d ignoré(s)", skipped) : "") + ".";
                status.setText(msg);
                System.out.println("[OT] Annulation « " + b.label() + " » : " + msg);
                reload(b.id());
                if (modified > 0 && !force) {
                    // Un peu plus tard : le hub ne libère cette tâche qu'après done(), une 2e passe serait refusée.
                    final int m = modified;
                    javax.swing.Timer later = new javax.swing.Timer(400, ev -> {
                        int c = JOptionPane.showConfirmDialog(SaveUndoDialog.this, I18n.t("<html>%d fichier(s) ont été modifiés depuis cet "
                                        + "enregistrement (par un autre programme ou un enregistrement plus récent).<br>Les remettre quand même "
                                        + "comme avant ? Les changements faits depuis seront perdus.</html>", m),
                                getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                        if (c == JOptionPane.YES_OPTION) SaveUndoDialog.this.run(b, modifiedNs, true);
                    });
                    later.setRepeats(false);
                    later.start();
                }
            }
        };
        try {
            WorkerHub.get().submit(WorkerHub.TaskKind.UNDO_SAVE, I18n.t("Annuler un enregistrement"), w, () -> w.cancel(false));
        } catch (IllegalStateException ex) {
            busy = false;
            updateButtons();
            status.setText(ex.getMessage());
        }
    }

    private void forgetSelected() {
        SaveJournal.BatchInfo b = selectedBatch();
        if (b == null) return;
        int c = JOptionPane.showConfirmDialog(this, I18n.t("<html>Oublier l'enregistrement du %s (« %s ») ?<br>Il ne pourra plus être "
                        + "annulé. Les fichiers de musique ne sont pas touchés ; seule la copie des anciens tags (%s) est effacée.</html>",
                        WHEN.format(b.started()), esc(b.label()), ByteFormat.format(b.bytes())),
                getTitle(), JOptionPane.OK_CANCEL_OPTION);
        if (c != JOptionPane.OK_OPTION) return;
        if (!SaveJournal.forget(b.id())) status.setText(I18n.t("Impossible d'oublier cet enregistrement."));
        reload(null);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
