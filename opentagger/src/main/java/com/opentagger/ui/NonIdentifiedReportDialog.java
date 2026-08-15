package com.opentagger.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.SkipReason;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rapport "Non identifiés" par cause — répond à un besoin concret rencontré en direct cette nuit
 * (identifier combien de fichiers restent bloqués pour chaque raison nécessitait jusqu'ici de
 * grep les logs à la main). Calqué sur HistoryDialog.java (JTable + DefaultTableModel non éditable,
 * export JSON) mais lit directement le tableau principal en mémoire (tableModel.allEntries()) —
 * pas de requête SQLite, les causes (FileEntry.skipReason) ne sont jamais persistées, cohérent avec
 * le reste de la barre de stats qui se réinitialise déjà au redémarrage.
 */
public class NonIdentifiedReportDialog extends JDialog {

    private static final String[] COLS = {I18n.t("Cause"), I18n.t("Nombre"), "%"};

    private final MainFrame         owner;
    private final FileTableModel    tableModel;
    private final JTable            table;
    private final DefaultTableModel model;
    private final JLabel            lblCount = new JLabel();

    public NonIdentifiedReportDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Rapport Non identifiés — OpenTagger"), false);
        this.owner      = owner;
        this.tableModel = tableModel;
        setSize(560, 420);
        setMinimumSize(new Dimension(420, 280));
        setLocationRelativeTo(owner);

        model = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        TableColumnModel cm = table.getColumnModel();
        setColWidth(cm, 0, 260, 140, 400); // Cause
        setColWidth(cm, 1, 90,  60,  120); // Nombre
        setColWidth(cm, 2, 70,  50,  100); // %
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) return;
                int modelRow = table.convertRowIndexToModel(row);
                SkipReason reason = rowReasons.get(modelRow);
                if (reason == null) return;
                owner.filterBySkipReason(reason);
                dispose();
            }
        });

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        load();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // Ligne de table (index modèle) → SkipReason correspondant, pour le double-clic — reconstruit à
    // chaque load() puisque l'ordre/le nombre de lignes peut changer.
    private final Map<Integer, SkipReason> rowReasons = new java.util.HashMap<>();

    private static String label(SkipReason r) {
        return switch (r) {
            case FILE_MISSING     -> I18n.t("Fichier introuvable");
            case NOT_IDENTIFIED   -> I18n.t("Aucune méthode n'a rien trouvé");
            case LOW_SCORE        -> I18n.t("Score trop bas");
            case DURATION_MISMATCH -> I18n.t("Durée incohérente avec MusicBrainz");
            case ERROR_GENERIC    -> I18n.t("Erreur (transcodage, fichier corrompu…)");
        };
    }

    private void load() {
        Map<SkipReason, Integer> counts = new EnumMap<>(SkipReason.class);
        int unknown = 0, total = 0;
        for (FileEntry e : tableModel.allEntries()) {
            if (e.status != FileEntry.Status.SKIPPED && e.status != FileEntry.Status.ERROR) continue;
            total++;
            if (e.skipReason == null) { unknown++; continue; }
            counts.merge(e.skipReason, 1, Integer::sum);
        }

        model.setRowCount(0);
        rowReasons.clear();
        // Tri par nombre décroissant — le plus gros contributeur en premier, plus utile à l'œil
        // qu'un ordre d'enum arbitraire.
        final int finalTotal = total;
        counts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .forEach(en -> {
                int r = model.getRowCount();
                model.addRow(new Object[]{label(en.getKey()), en.getValue(),
                        pct(en.getValue(), finalTotal)});
                rowReasons.put(r, en.getKey());
            });
        if (unknown > 0) {
            model.addRow(new Object[]{I18n.t("Inconnu (session antérieure)"), unknown, pct(unknown, total)});
        }
        lblCount.setText(I18n.t("%d fichier(s) non identifié(s)/en erreur au total", total));
    }

    private static String pct(int n, int total) {
        return total == 0 ? "0%" : Math.round(100.0 * n / total) + "%";
    }

    private JPanel buildFooter() {
        JButton btnRefresh = new JButton(I18n.t("Rafraîchir"));
        JButton btnClose    = new JButton(I18n.t("Fermer"));
        JButton btnExport   = new JButton("📤  " + I18n.t("Exporter JSON"));
        btnRefresh.addActionListener(e -> load());
        btnClose  .addActionListener(e -> dispose());
        btnExport .addActionListener(e -> exportJson());
        btnExport.setToolTipText(I18n.t("Sauvegarder ce rapport dans un fichier JSON"));

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        JLabel hint = new JLabel(I18n.t("  Double-clic sur une ligne : filtrer le tableau principal"));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        JPanel west = new JPanel(new GridLayout(2, 1));
        west.add(lblCount);
        west.add(hint);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnRefresh);
        right.add(btnExport);
        right.add(btnClose);
        p.add(west,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    private void exportJson() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("Exporter le rapport Non identifiés"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        fc.setSelectedFile(new File("opentagger-non-identifies.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File dest = fc.getSelectedFile();
        if (!dest.getName().endsWith(".json")) dest = new File(dest.getAbsolutePath() + ".json");

        try {
            Map<String, Object> rows = new LinkedHashMap<>();
            for (int r = 0; r < model.getRowCount(); r++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("count", model.getValueAt(r, 1));
                row.put("pct",   model.getValueAt(r, 2));
                rows.put(String.valueOf(model.getValueAt(r, 0)), row);
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("version",  1);
            wrapper.put("exported", java.time.Instant.now().toString());
            wrapper.put("reasons",  rows);
            ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(dest.toPath(), om.writeValueAsString(wrapper));
            JOptionPane.showMessageDialog(this,
                I18n.t("Rapport exporté vers\n%s", dest.getAbsolutePath()),
                I18n.t("Export réussi"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Erreur export : %s", ex.getMessage()), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setColWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        cm.getColumn(i).setPreferredWidth(p);
        cm.getColumn(i).setMinWidth(mn);
        cm.getColumn(i).setMaxWidth(mx);
    }
}
