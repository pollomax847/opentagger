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

    // Garde de ré-entrance + bouton retenu comme champ (2026-09-06) : rien n'empêchait avant ce
    // correctif de relancer "Rattraper les tags…" par-dessus une exécution déjà en cours — confirmé
    // en direct par thread-dump que l'utilisateur avait ainsi 3 passages concurrents sur les mêmes
    // ~127k fichiers, faute d'un retour visuel clair pendant l'exécution (le titre statique donnait
    // l'impression que "rien ne se passe").
    private final JButton       btnBackfill = new JButton(I18n.t("Rattraper les tags depuis l'historique…"));
    private volatile boolean    backfillRunning = false;

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
        JButton btnPurge     = new JButton(I18n.t("Vider le cache / la base…"));
        JButton btnCleanScan = new JButton(I18n.t("Nettoyer le cache de scan…"));
        JButton btnExport    = new JButton("📤  " + I18n.t("Exporter JSON"));
        JButton btnImport    = new JButton("📥  " + I18n.t("Importer JSON"));
        btnClose    .addActionListener(e -> dispose());
        btnPurge    .addActionListener(e -> confirmClear());
        btnCleanScan.addActionListener(e -> confirmCleanScanCache());
        btnBackfill .addActionListener(e -> confirmBackfillTags());
        btnExport   .addActionListener(e -> exportJson());
        btnImport   .addActionListener(e -> importJson());
        btnExport.setToolTipText(I18n.t("Sauvegarder l'historique dans un fichier JSON (partage/sauvegarde)"));
        btnImport.setToolTipText(I18n.t("Fusionner un fichier JSON d'historique (les entrées existantes ne sont pas écrasées)"));
        btnCleanScan.setToolTipText(I18n.t("Supprime du cache de scan les entrées dont le fichier n'existe plus sur disque (déplacé/supprimé) — peut prendre plusieurs minutes sur une grosse bibliothèque"));
        btnBackfill.setToolTipText(I18n.t("Réécrit sur le disque les tags déjà connus dans cet historique, sans ré-identifier"
                + " (aucun appel réseau) — utile après un correctif qui empêchait certains champs de bien s'enregistrer"));

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
        right.add(btnBackfill);
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

    // ── Vider le cache / la base ───────────────────────────────────────────────

    /**
     * Un seul point d'entrée pour tout vider : cache technique (recherches, pochettes, lecture des tags — régénéré tout seul), historique
     * personnel seul (ce qui a été tagué + corrections, l'ancien « Purger l'historique »), ou TOUT, avec récupération de l'espace disque
     * (la base fait plusieurs Go). Refusé tant qu'un taguage ou un enregistrement tourne : la compaction exige un accès exclusif.
     */
    private void confirmClear() {
        boolean busy = WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()
                || WorkerHub.get().current(WorkerHub.TaskKind.SAVE).isPresent()
                || !WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING).isEmpty();
        if (busy) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Un taguage ou un enregistrement est en cours.\nAttendez la fin (ou arrêtez-le) avant de vider le cache."),
                I18n.t("Vider le cache / la base"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        java.util.Map<String, Long> counts = cache.countRows(true);
        long technical = 0, personal = 0;
        for (String t : MetadataCache.TECHNICAL_TABLES) technical += Math.max(0, counts.getOrDefault(t, 0L));
        for (String t : MetadataCache.PERSONAL_TABLES) personal += Math.max(0, counts.getOrDefault(t, 0L));
        String[] choices = { I18n.t("Cache technique"), I18n.t("Historique seulement"), I18n.t("Tout"), I18n.t("Annuler") };
        int pick = JOptionPane.showOptionDialog(this,
            I18n.t("<html><b>Que voulez-vous vider ?</b><br><br>"
                 + "<b>Cache technique</b> — %d lignes : recherches réseau, pochettes, lecture des tags au scan.<br>"
                 + "Régénéré tout seul, aucune perte de travail (le prochain scan sera plus lent).<br><br>"
                 + "<b>Historique seulement</b> — %d lignes : ce qui a été tagué, corrections, annulations.<br>"
                 + "Non régénérable.<br><br>"
                 + "<b>Tout</b> — les deux, puis récupération de l'espace disque.<br><br>"
                 + "Vos fichiers audio ne sont jamais touchés. Action irréversible.</html>", technical, personal),
            I18n.t("Vider le cache / la base"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, choices, choices[3]);
        if (pick < 0 || pick >= 3) return;
        if (pick >= 1) { // tout ce qui touche à l'historique personnel demande une saisie : pas de clic par réflexe
            String typed = JOptionPane.showInputDialog(this,
                I18n.t("Pour confirmer la suppression de l'historique personnel, tapez SUPPRIMER :"),
                I18n.t("Confirmation"), JOptionPane.WARNING_MESSAGE);
            if (typed == null || !typed.trim().equals("SUPPRIMER")) return;
        }
        if (pick == 1) { confirmPurgeHistoryOnly(); return; }
        final boolean everything = pick == 2;
        setTitle(I18n.t("Historique de taguage — OpenTagger (vidage en cours…)"));
        new SwingWorker<java.util.Map<String, Integer>, Void>() {
            @Override protected java.util.Map<String, Integer> doInBackground() { return cache.clearAll(everything); }
            @Override protected void done() {
                setTitle(I18n.t("Historique de taguage — OpenTagger"));
                try {
                    long n = get().values().stream().filter(v -> v > 0).mapToLong(Integer::longValue).sum();
                    loadAll();
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("%d ligne(s) supprimée(s), espace disque récupéré.", n),
                        I18n.t("Vidage terminé"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("Erreur : %s", ex.getMessage()), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void confirmPurgeHistoryOnly() {
        cache.purgeHistory();
        loadAll();
        setTitle(I18n.t("Historique de taguage — OpenTagger (purgé)"));
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

    private record BackfillResult(int total, int written, int missing, int failed) {}

    /**
     * Réécrit sur le disque, pour chaque fichier connu de file_history, le TagInfo déjà mémorisé
     * dans tagging_history — sans ré-identification (aucun appel réseau, aucune requête
     * MusicBrainz/Discogs/Last.fm/AcoustID/SongRec). Créé le 2026-09-06 pour rattraper le correctif
     * TagWriter.writeTxxx() (voir son commentaire) : avant ce correctif, seul le DERNIER champ TXXX
     * personnalisé écrit par un même enregistrement survivait sur le disque (ReplayGain/Discogs
     * ID/playcounts/OT_TAGGEDDATE/biographie artiste s'écrasaient silencieusement entre eux) — la
     * valeur correcte est cependant restée intacte dans tagging_history (sérialisée en mémoire AVANT
     * l'écriture disque, donc jamais affectée par ce bug), donc récupérable ici sans repasser par
     * une identification complète. Ne couvre que les fichiers déjà passés par "Enregistrer tout"
     * (SaveWorker/VideoRecoveryWorker, les deux seuls appelants de TagEnrichment.saveEntry()) — les
     * fichiers tagués uniquement via le CLI/BatchProcessor n'ont jamais alimenté ces deux tables.
     */
    private void confirmBackfillTags() {
        if (backfillRunning) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Un rattrapage est déjà en cours — patiente jusqu'à la pop-up de résultat."),
                I18n.t("Déjà en cours"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
            I18n.t("Réécrit sur le disque les tags déjà connus dans cet historique (biographie artiste,\n"
                 + "ReplayGain, IDs Discogs/Apple Music, compteurs d'écoute...), pour rattraper un correctif\n"
                 + "qui empêchait certains d'entre eux de bien s'enregistrer sur le fichier lui-même.\n\n"
                 + "Aucune ré-identification, aucun appel réseau — juste une réécriture depuis les données\n"
                 + "déjà connues. Ne couvre que les fichiers passés par \"Enregistrer tout\"."),
            I18n.t("Rattraper les tags depuis l'historique"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) return;

        backfillRunning = true;
        btnBackfill.setEnabled(false);
        setTitle(I18n.t("Historique de taguage — OpenTagger (rattrapage des tags en cours…)"));
        new SwingWorker<BackfillResult, Integer>() {
            @Override protected BackfillResult doInBackground() {
                java.util.Map<String, String> fileHistory = cache.loadFileHistoryMap();
                java.util.Map<String, com.opentagger.model.TagInfo> taggingHistory = cache.loadTaggingHistoryMap();
                com.opentagger.TagWriter writer = new com.opentagger.TagWriter();

                int total = 0, written = 0, missing = 0, failed = 0;
                for (var entry : fileHistory.entrySet()) {
                    total++;
                    com.opentagger.model.TagInfo ti = taggingHistory.get(entry.getValue());
                    if (ti == null) { missing++; continue; }
                    File f = new File(entry.getKey());
                    if (!f.isFile()) { missing++; continue; }
                    try {
                        writer.write(f, ti, null);
                        written++;
                    } catch (Exception ex) {
                        failed++;
                    }
                    // Un lot de ~127k fichiers peut prendre plusieurs minutes — le titre statique
                    // précédent ("rattrapage en cours…") ne changeait jamais, indiscernable d'un
                    // blocage réel (retour utilisateur direct : "rien ne se passe"). Publié tous les
                    // 100 fichiers seulement : appeler publish() par fichier sérialiserait inutilement
                    // sur l'EDT un traitement qui, lui, reste volontairement mono-thread (écritures
                    // disque séquentielles, pas de pool comme les autres pipelines).
                    if (total % 100 == 0) publish(total);
                }
                return new BackfillResult(total, written, missing, failed);
            }
            @Override protected void process(java.util.List<Integer> chunks) {
                int n = chunks.get(chunks.size() - 1);
                setTitle(I18n.t("Historique de taguage — OpenTagger (rattrapage des tags en cours… %d)", n));
            }
            @Override protected void done() {
                backfillRunning = false;
                btnBackfill.setEnabled(true);
                setTitle(I18n.t("Historique de taguage — OpenTagger"));
                try {
                    BackfillResult r = get();
                    JOptionPane.showMessageDialog(HistoryDialog.this,
                        I18n.t("%d fichier(s) réécrit(s) sur %d connu(s) dans l'historique.\n"
                             + "%d introuvable(s)/sans correspondance, %d échec(s).",
                             r.written(), r.total(), r.missing(), r.failed()),
                        I18n.t("Rattrapage terminé"), JOptionPane.INFORMATION_MESSAGE);
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
