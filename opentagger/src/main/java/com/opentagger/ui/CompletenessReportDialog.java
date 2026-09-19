package com.opentagger.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Rapport de complétude agrégé — écart trouvé vs SongKong (Status Report, comparaison 2026-09-18) :
 * {@code TaggingWorker.incompletenessScore()} existait déjà mais UNIQUEMENT pour ordonner la file
 * de traitement (les plus incomplets en premier), jamais exposé à l'utilisateur sous forme de
 * rapport. Mêmes 5 champs et même définition de "champ vide" (isGenericTag, désormais
 * package-privé pour être réutilisé ici tel quel) que cette méthode, pour rester cohérent avec ce
 * que l'appli considère déjà comme "incomplet" ailleurs. Calqué sur NonIdentifiedReportDialog.java
 * (même squelette JTable/export JSON), lecture directe de tableModel.allEntries() sans requête
 * SQLite.
 */
public class CompletenessReportDialog extends JDialog {

    private static final String[] COLS = {I18n.t("Champ"), I18n.t("Renseigné"), I18n.t("Manquant"), "%"};

    private record Field(String label, Predicate<TagInfo> present) {}

    private final FileTableModel    tableModel;
    private final DefaultTableModel model;
    private final JLabel            lblCount = new JLabel();

    public CompletenessReportDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Rapport de complétude — OpenTagger"), false);
        this.tableModel = tableModel;
        setSize(520, 320);
        setMinimumSize(new Dimension(420, 260));
        setLocationRelativeTo(owner);

        model = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        TableColumnModel cm = table.getColumnModel();
        setColWidth(cm, 0, 160, 100, 240); // Champ
        setColWidth(cm, 1, 100, 70,  140); // Renseigné
        setColWidth(cm, 2, 100, 70,  140); // Manquant
        setColWidth(cm, 3, 70,  50,  100); // %

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        load();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // Mêmes 5 champs, dans le même ordre, que TaggingWorker.incompletenessScore().
    private static final Field[] FIELDS = {
        new Field(I18n.t("Titre"),   t -> !t.title.isBlank()  && !TaggingWorker.isGenericTag(t.title)),
        new Field(I18n.t("Artiste"), t -> !t.artist.isBlank() && !TaggingWorker.isGenericTag(t.artist)),
        new Field(I18n.t("Album"),   t -> !t.album.isBlank()  && !TaggingWorker.isGenericTag(t.album)),
        new Field(I18n.t("Année"),   t -> !t.year.isBlank()),
        new Field(I18n.t("Genre"),   t -> !t.genre.isBlank()),
    };

    private void load() {
        int total = 0;
        int[] present = new int[FIELDS.length];
        for (FileEntry e : tableModel.allEntries()) {
            TagInfo t = e.activeTags();
            if (t == null) continue;
            total++;
            for (int i = 0; i < FIELDS.length; i++) if (FIELDS[i].present().test(t)) present[i]++;
        }

        model.setRowCount(0);
        for (int i = 0; i < FIELDS.length; i++) {
            int missing = total - present[i];
            model.addRow(new Object[]{FIELDS[i].label(), present[i], missing, pct(present[i], total)});
        }
        lblCount.setText(I18n.t("%d fichier(s) au total", total));
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

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        p.add(lblCount, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(btnRefresh);
        buttons.add(btnExport);
        buttons.add(btnClose);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void exportJson() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("Exporter le rapport de complétude"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        fc.setSelectedFile(new File("opentagger-completude.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File dest = fc.getSelectedFile();
        if (!dest.getName().endsWith(".json")) dest = new File(dest.getAbsolutePath() + ".json");

        try {
            Map<String, Object> rows = new LinkedHashMap<>();
            for (int r = 0; r < model.getRowCount(); r++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("present", model.getValueAt(r, 1));
                row.put("missing", model.getValueAt(r, 2));
                row.put("pct",     model.getValueAt(r, 3));
                rows.put(String.valueOf(model.getValueAt(r, 0)), row);
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("version",  1);
            wrapper.put("exported", java.time.Instant.now().toString());
            wrapper.put("fields",   rows);
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
