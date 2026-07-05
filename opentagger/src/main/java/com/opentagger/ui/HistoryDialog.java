package com.opentagger.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.MetadataCache.ExportEntry;
import com.opentagger.MetadataCache.HistoryEntry;

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
import java.util.Arrays;
import java.util.List;

/**
 * Dialogue "Historique de taguage" — équivalent de la consultation de la base
 * Derby de Jaikoz (liste des morceaux déjà tagués, avec recherche).
 *
 * Chaque entrée représente un morceau dont le TagInfo final a été mémorisé
 * dans la base SQLite (table tagging_history). Jaikoz accumule des années
 * de taguage dans sa base Derby (726 Mo+) ; ici c'est la même idée mais
 * en SQLite et sans limite de taille.
 */
public class HistoryDialog extends JDialog {

    private static final DateTimeFormatter DF =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private static final String[] COLS =
            {I18n.t("Artiste"), I18n.t("Titre"), I18n.t("Album"), I18n.t("Année"), "MBID", I18n.t("Date taguage")};

    private final MetadataCache cache;
    private final JTextField    tfArtist = new JTextField(18);
    private final JTextField    tfTitle  = new JTextField(18);
    private final JLabel        lblCount = new JLabel();
    private final JTable        table;
    private final DefaultTableModel model;

    public HistoryDialog(Frame owner) {
        super(owner, I18n.t("Historique de taguage — OpenTagger"), false);
        setSize(960, 620);
        setMinimumSize(new Dimension(720, 440));
        setLocationRelativeTo(owner);

        cache = new MetadataCache();

        // Fermer le cache SQLite même si l'utilisateur clique sur la croix système
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { cache.close(); }
        });

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
        setColWidth(cm, 0, 160, 80, 280);   // Artiste
        setColWidth(cm, 1, 200, 80, 360);   // Titre
        setColWidth(cm, 2, 160, 80, 280);   // Album
        setColWidth(cm, 3, 50,  36, 64);    // Année
        setColWidth(cm, 4, 240, 120, 400);  // MBID
        setColWidth(cm, 5, 120, 90, 160);   // Date

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(buildHeader(), BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        // Chargement initial
        loadAll();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // ── Barre de recherche ────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        p.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));

        JButton btnSearch = new JButton(I18n.t("Filtrer"));
        JButton btnReset  = new JButton(I18n.t("Tout afficher"));
        btnSearch.addActionListener(e -> search());
        btnReset .addActionListener(e -> loadAll());
        tfArtist.addActionListener(e -> search());
        tfTitle .addActionListener(e -> search());

        p.add(new JLabel(I18n.t("Artiste :")));    p.add(tfArtist);
        p.add(new JLabel(I18n.t("  Titre :")));   p.add(tfTitle);
        p.add(btnSearch);
        p.add(btnReset);
        p.add(Box.createHorizontalStrut(20));
        p.add(lblCount);
        return p;
    }

    // ── Pied de page ─────────────────────────────────────────────────────────

    private JPanel buildFooter() {
        JButton btnClose   = new JButton(I18n.t("Fermer"));
        JButton btnPurge   = new JButton(I18n.t("Purger l'historique…"));
        JButton btnExport  = new JButton("📤  " + I18n.t("Exporter JSON"));
        JButton btnImport  = new JButton("📥  " + I18n.t("Importer JSON"));
        btnClose .addActionListener(e -> dispose());
        btnPurge .addActionListener(e -> confirmPurge());
        btnExport.addActionListener(e -> exportJson());
        btnImport.addActionListener(e -> importJson());
        btnExport.setToolTipText(I18n.t("Sauvegarder l'historique dans un fichier JSON (partage/sauvegarde)"));
        btnImport.setToolTipText(I18n.t("Fusionner un fichier JSON d'historique (les entrées existantes ne sont pas écrasées)"));

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnImport);
        right.add(btnExport);
        right.add(Box.createHorizontalStrut(8));
        right.add(btnPurge);
        right.add(btnClose);
        p.add(buildStats(), BorderLayout.WEST);
        p.add(right,        BorderLayout.EAST);
        return p;
    }

    private JLabel buildStats() {
        JLabel lbl = new JLabel();
        int total = cache.historyCount();
        lbl.setText("  " + I18n.t("Total dans la base : %d morceau(x)", total));
        lbl.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        return lbl;
    }

    // ── Chargement / filtrage ─────────────────────────────────────────────────

    private void loadAll() {
        tfArtist.setText(""); tfTitle.setText("");
        fill(cache.queryHistory("", ""));
    }

    private void search() {
        fill(cache.queryHistory(tfArtist.getText(), tfTitle.getText()));
    }

    private void fill(List<HistoryEntry> entries) {
        model.setRowCount(0);
        for (HistoryEntry e : entries) {
            String date = DF.format(Instant.ofEpochMilli(e.ts()));
            model.addRow(new Object[]{e.artist(), e.title(), e.album(), e.year(), e.mbid(), date});
        }
        lblCount.setText(I18n.t("%d résultat(s)", entries.size()));
    }

    // ── Export JSON ───────────────────────────────────────────────────────────

    private void exportJson() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("Exporter l'historique de taguage"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        fc.setSelectedFile(new File("opentagger-history.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File dest = fc.getSelectedFile();
        if (!dest.getName().endsWith(".json")) dest = new File(dest.getAbsolutePath() + ".json");
        final File finalDest = dest;

        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                List<ExportEntry> entries = cache.exportHistory();
                ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
                // Wrapper avec métadonnées
                java.util.Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
                wrapper.put("version",  1);
                wrapper.put("exported", java.time.Instant.now().toString());
                wrapper.put("entries",  entries);
                String json = om.writeValueAsString(wrapper);
                Files.writeString(finalDest.toPath(), json);
                return entries.size();
            }
            @Override protected void done() {
                try {
                    int n = get();
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("%d entrée(s) exportée(s) vers\n%s", n, finalDest.getAbsolutePath()),
                        I18n.t("Export réussi"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("Erreur export : %s", ex.getMessage()), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // ── Import JSON ───────────────────────────────────────────────────────────

    private void importJson() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("Importer un historique JSON"));
        fc.setFileFilter(new FileNameExtensionFilter("JSON (*.json)", "json"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File src = fc.getSelectedFile();
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                String json = Files.readString(src.toPath());
                ObjectMapper om = new ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = om.readTree(json);
                com.fasterxml.jackson.databind.JsonNode arr  = root.isArray() ? root : root.get("entries");
                if (arr == null || !arr.isArray())
                    throw new Exception(I18n.t("Format invalide — clé \"entries\" introuvable."));
                List<ExportEntry> entries = Arrays.asList(
                    om.treeToValue(arr, ExportEntry[].class));
                return cache.importHistory(entries);
            }
            @Override protected void done() {
                try {
                    int n = get();
                    loadAll();
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("%d nouvelle(s) entrée(s) importée(s)\n(les entrées déjà présentes sont conservées).", n),
                        I18n.t("Import réussi"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("Erreur import : %s", ex.getMessage()), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    // ── Purge ─────────────────────────────────────────────────────────────────

    private void confirmPurge() {
        int total = cache.historyCount();
        int choice = JOptionPane.showConfirmDialog(this,
            I18n.t("Supprimer les %d entrée(s) de l'historique ?\nCette action est irréversible.", total),
            I18n.t("Purger l'historique"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            cache.purgeHistory();
            loadAll();
            setTitle(I18n.t("Historique de taguage — OpenTagger (purgé)"));
        }
    }

    private void setColWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        cm.getColumn(i).setPreferredWidth(p);
        cm.getColumn(i).setMinWidth(mn);
        cm.getColumn(i).setMaxWidth(mx);
    }
}
