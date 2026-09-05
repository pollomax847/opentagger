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

    private static final String[] CORR_COLS =
            {I18n.t("Fichier"), I18n.t("Champ"), I18n.t("Ancienne valeur"), I18n.t("Nouvelle valeur"), I18n.t("Date")};

    private final MetadataCache cache;
    private final JTextField    tfArtist = new JTextField(18);
    private final JTextField    tfTitle  = new JTextField(18);
    private final JLabel        lblCount = new JLabel();
    private final JTable        table;
    private final DefaultTableModel model;

    private final JLabel        lblCorrCount = new JLabel();
    private final JTable        corrTable;
    private final DefaultTableModel corrModel;

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

        corrModel = new DefaultTableModel(CORR_COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        corrTable = new JTable(corrModel);
        corrTable.setAutoCreateRowSorter(true);
        corrTable.setRowHeight(24);
        corrTable.setShowHorizontalLines(false);
        corrTable.setIntercellSpacing(new Dimension(0, 0));
        corrTable.getTableHeader().setReorderingAllowed(false);
        TableColumnModel corrCm = corrTable.getColumnModel();
        setColWidth(corrCm, 0, 320, 120, 600); // Fichier
        setColWidth(corrCm, 1, 120, 80,  200); // Champ
        setColWidth(corrCm, 2, 180, 80,  360); // Ancienne valeur
        setColWidth(corrCm, 3, 180, 80,  360); // Nouvelle valeur
        setColWidth(corrCm, 4, 120, 90,  160); // Date

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(I18n.t("Historique de taguage"), new JScrollPane(table));
        tabs.addTab(I18n.t("Corrections manuelles"), buildCorrectionsPanel());

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(buildHeader(), BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        // Chargement initial
        loadAll();
        loadCorrections();

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
        JButton btnClose     = new JButton(I18n.t("Fermer"));
        JButton btnPurge     = new JButton(I18n.t("Purger l'historique…"));
        JButton btnCleanScan = new JButton(I18n.t("Nettoyer le cache de scan…"));
        JButton btnExport    = new JButton("📤  " + I18n.t("Exporter JSON"));
        JButton btnImport    = new JButton("📥  " + I18n.t("Importer JSON"));
        btnClose    .addActionListener(e -> dispose());
        btnPurge    .addActionListener(e -> confirmPurge());
        btnCleanScan.addActionListener(e -> confirmCleanScanCache());
        btnExport   .addActionListener(e -> exportJson());
        btnImport   .addActionListener(e -> importJson());
        btnExport.setToolTipText(I18n.t("Sauvegarder l'historique dans un fichier JSON (partage/sauvegarde)"));
        btnImport.setToolTipText(I18n.t("Fusionner un fichier JSON d'historique (les entrées existantes ne sont pas écrasées)"));
        btnCleanScan.setToolTipText(I18n.t("Supprime du cache de scan les entrées dont le fichier n'existe plus sur disque (déplacé/supprimé) — peut prendre plusieurs minutes sur une grosse bibliothèque"));

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        // Même correctif de cohérence que DuplicatesDialog : Fermer avant l'action principale,
        // pas après (les 5 autres dialogues de l'appli placent tous l'action de
        // confirmation/principale à l'extrême droite).
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnImport);
        right.add(btnExport);
        right.add(Box.createHorizontalStrut(8));
        right.add(btnClose);
        right.add(btnCleanScan);
        right.add(btnPurge);
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
        queryAsync("", "");
    }

    private void search() {
        queryAsync(tfArtist.getText(), tfTitle.getText());
    }

    /** cache.queryHistory() trie tagging_history par date — coûteux sur un gros historique jamais
     *  purgé (voir l'index idx_hist_ts ajouté pour ce cas). Lancé en tâche de fond dans tous les
     *  cas : ouvrir ce dialogue ne doit jamais pouvoir geler l'EDT (donc TOUTE l'appli, pas
     *  seulement cette fenêtre) si la base est temporairement lente (écritures concurrentes d'un
     *  scan en cours, disque externe, etc.).
     */
    private void queryAsync(String artistFilter, String titleFilter) {
        lblCount.setText(I18n.t("Recherche…"));
        new SwingWorker<List<HistoryEntry>, Void>() {
            @Override protected List<HistoryEntry> doInBackground() {
                return cache.queryHistory(artistFilter, titleFilter);
            }
            @Override protected void done() {
                try { fill(get()); }
                catch (Exception ex) { lblCount.setText(I18n.t("Erreur : %s", ex.getMessage())); }
            }
        }.execute();
    }

    private void fill(List<HistoryEntry> entries) {
        model.setRowCount(0);
        for (HistoryEntry e : entries) {
            String date = DF.format(Instant.ofEpochMilli(e.ts()));
            model.addRow(new Object[]{e.artist(), e.title(), e.album(), e.year(), e.mbid(), date});
        }
        lblCount.setText(I18n.t("%d résultat(s)", entries.size()));
    }

    // ── Onglet Corrections manuelles ──────────────────────────────────────────
    // Lecteur de la table `corrections` (voir MainFrame.recordFieldCorrections) : avant ce
    // correctif, cette table était écrite mais jamais consultée nulle part dans l'appli.

    private JPanel buildCorrectionsPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        JButton btnRefresh = new JButton(I18n.t("Rafraîchir"));
        btnRefresh.addActionListener(e -> loadCorrections());
        top.add(btnRefresh);
        top.add(lblCorrCount);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(corrTable), BorderLayout.CENTER);
        return p;
    }

    private void loadCorrections() {
        lblCorrCount.setText(I18n.t("Chargement…"));
        new SwingWorker<List<MetadataCache.CorrectionEntry>, Void>() {
            @Override protected List<MetadataCache.CorrectionEntry> doInBackground() {
                return cache.queryCorrections(2000);
            }
            @Override protected void done() {
                try {
                    List<MetadataCache.CorrectionEntry> entries = get();
                    corrModel.setRowCount(0);
                    for (MetadataCache.CorrectionEntry ce : entries) {
                        String date = DF.format(Instant.ofEpochMilli(ce.ts()));
                        corrModel.addRow(new Object[]{ce.path(), ce.field(), ce.oldValue(), ce.newValue(), date});
                    }
                    lblCorrCount.setText(I18n.t("%d correction(s)", entries.size()));
                } catch (Exception ex) {
                    lblCorrCount.setText(I18n.t("Erreur : %s", ex.getMessage()));
                }
            }
        }.execute();
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

    /** Voir MetadataCache.purgeStaleScanCache() : nettoyage du cache de scan (pas l'historique
     *  personnel ci-dessus), régénérable au prochain scan — coûteux, lancé en tâche de fond. */
    private void confirmCleanScanCache() {
        int choice = JOptionPane.showConfirmDialog(this,
            I18n.t("Supprimer du cache de scan les entrées dont le fichier n'existe plus sur disque ?\n"
                 + "Sans effet sur vos fichiers ni sur l'historique de taguage — juste un nettoyage\n"
                 + "de cache technique, régénéré automatiquement au prochain scan.\n"
                 + "Peut prendre plusieurs minutes sur une grosse bibliothèque."),
            I18n.t("Nettoyer le cache de scan"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        setTitle(I18n.t("Historique de taguage — OpenTagger (nettoyage du cache de scan en cours…)"));
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() {
                int removed = cache.purgeStaleScanCache();
                if (removed > 0) cache.vacuum();
                return removed;
            }
            @Override protected void done() {
                setTitle(I18n.t("Historique de taguage — OpenTagger"));
                try {
                    int n = get();
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("%d entrée(s) obsolète(s) supprimée(s) du cache de scan.", n),
                        I18n.t("Nettoyage terminé"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("Erreur : %s", ex.getMessage()), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setColWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        cm.getColumn(i).setPreferredWidth(p);
        cm.getColumn(i).setMinWidth(mn);
        cm.getColumn(i).setMaxWidth(mx);
    }
}
