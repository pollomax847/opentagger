package com.opentagger.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opentagger.CompilationRestoreLog;
import com.opentagger.I18n;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rapport des "préservations de compilation" (voir TaggingWorker.findTags(), CompilationRestoreLog)
 * — audit demandé (2026-08-17) après avoir restreint cette restauration à SOURCE_TEXT uniquement :
 * même restreinte, elle fait confiance à d'anciens tags jamais revérifiés, donc autant garder une
 * trace consultable de chaque cas plutôt que de le faire silencieusement.
 */
public class CompilationRestoreReportDialog extends JDialog {

    private static final String[] COLS = {
        I18n.t("Heure"), I18n.t("Fichier"), I18n.t("Album restauré"), I18n.t("Artiste album restauré"),
        I18n.t("Album MB écarté"), I18n.t("Artiste MB écarté")
    };
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final DefaultTableModel model;
    private final JLabel            lblCount = new JLabel();

    public CompilationRestoreReportDialog(MainFrame owner) {
        super(owner, I18n.t("Compilations restaurées — OpenTagger"), false);
        setSize(820, 420);
        setMinimumSize(new Dimension(500, 280));
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
        setColWidth(cm, 0, 80,  70,  100); // Heure
        setColWidth(cm, 1, 240, 140, 500); // Fichier
        setColWidth(cm, 2, 160, 100, 300); // Album restauré
        setColWidth(cm, 3, 140, 100, 260); // Artiste restauré
        setColWidth(cm, 4, 160, 100, 300); // Album écarté
        setColWidth(cm, 5, 140, 100, 260); // Artiste écarté

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        load();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    private void load() {
        model.setRowCount(0);
        for (var e : CompilationRestoreLog.all()) {
            model.addRow(new Object[]{
                TIME_FMT.format(Instant.ofEpochMilli(e.ts())),
                new File(e.path()).getName(),
                e.restoredAlbum(), e.restoredAlbumArtist(),
                e.discardedAlbum(), e.discardedAlbumArtist()
            });
        }
        lblCount.setText(I18n.t("%d restauration(s) cette session", CompilationRestoreLog.all().size()));
    }

    private JPanel buildFooter() {
        JButton btnRefresh = new JButton(I18n.t("Rafraîchir"));
        JButton btnClose   = new JButton(I18n.t("Fermer"));
        JButton btnExport  = new JButton("📤  " + I18n.t("Exporter JSON"));
        btnRefresh.addActionListener(e -> load());
        btnClose  .addActionListener(e -> dispose());
        btnExport .addActionListener(e -> exportJson());
        btnExport.setToolTipText(I18n.t("Sauvegarder ce rapport dans un fichier JSON"));

        // Empilé, pas côte à côte — même correctif que DurationMismatchReviewDialog (2026-09-01,
        // voir son commentaire) : un texte WEST trop long pouvait pousser les boutons EAST hors des
        // limites visibles de la fenêtre, sans le moindre signalement.
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        JLabel hint = new JLabel(I18n.t("Chaque ligne : un ancien tag a été restauré au lieu du résultat MusicBrainz frais"));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(lblCount, BorderLayout.NORTH);
        top.add(hint,     BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(btnRefresh);
        buttons.add(btnExport);
        buttons.add(btnClose);
        p.add(top,     BorderLayout.NORTH);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    private void exportJson() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("Exporter le rapport Compilations restaurées"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        fc.setSelectedFile(new File("opentagger-compilations-restaurees.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File dest = fc.getSelectedFile();
        if (!dest.getName().endsWith(".json")) dest = new File(dest.getAbsolutePath() + ".json");

        try {
            var list = new java.util.ArrayList<Map<String, Object>>();
            for (var e : CompilationRestoreLog.all()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", e.path());
                row.put("source", e.source());
                row.put("restoredAlbum", e.restoredAlbum());
                row.put("restoredAlbumArtist", e.restoredAlbumArtist());
                row.put("discardedAlbum", e.discardedAlbum());
                row.put("discardedAlbumArtist", e.discardedAlbumArtist());
                row.put("ts", Instant.ofEpochMilli(e.ts()).toString());
                list.add(row);
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("version",  1);
            wrapper.put("exported", Instant.now().toString());
            wrapper.put("restorations", list);
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

    private static void setColWidth(TableColumnModel cm, int idx, int pref, int min, int max) {
        TableColumn c = cm.getColumn(idx);
        c.setPreferredWidth(pref);
        c.setMinWidth(min);
        c.setMaxWidth(max);
    }
}
