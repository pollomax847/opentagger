package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.DiscogsClient;
import com.opentagger.I18n;
import com.opentagger.LastFmClient;
import com.opentagger.LocalCorrector;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.TagEnrichment;
import com.opentagger.TaggerScript;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialogue de correspondance manuelle — permet de rechercher un enregistrement
 * MusicBrainz et de l'appliquer à un fichier dont le score automatique était
 * insuffisant (ou pour corriger un tag auto incorrect).
 */
public class MatchDialog extends JDialog {

    private static final String[] COLS =
        {"Score", I18n.t("Artiste"), I18n.t("Artiste Album"), I18n.t("Titre"), I18n.t("Album"), I18n.t("Année"), "MBID"};

    private final FileEntry       entry;
    private final FileTableModel  tableModel;
    private final Runnable        onApplied;
    private final MusicBrainzClient mb       = new MusicBrainzClient();
    // Même enrichissement genre (Discogs/Last.fm) que les autres pipelines de taguage — sans ça,
    // une correspondance choisie manuellement ici n'avait pas de genre du tout, contrairement au
    // taguage automatique. La pochette n'est plus résolue ici : différée à l'Enregistrement, voir
    // applySelected().
    private final DiscogsClient  discogs  = new DiscogsClient();
    private final LastFmClient   lastFm   = new LastFmClient();
    private final TaggerScript   taggerScript = new TaggerScript();

    private final JTextField     tfArtist;
    private final JTextField     tfTitle;
    private final JLabel         lblStatus;
    private final JTable         resultsTable;
    private final DefaultTableModel resultsModel;
    private final List<TagInfo>  searchResults = new ArrayList<>();
    private JButton              btnApply, btnCancel;

    public MatchDialog(Frame owner, FileEntry entry, FileTableModel tableModel, Runnable onApplied) {
        super(owner, I18n.t("Correspondance manuelle — %s", entry.filename()), true);
        this.entry      = entry;
        this.tableModel = tableModel;
        this.onApplied  = onApplied;
        setSize(860, 520);
        setMinimumSize(new Dimension(700, 380));
        setLocationRelativeTo(owner);

        TagInfo ti = entry.activeTags();
        tfArtist  = new JTextField(ti.artist, 22);
        tfTitle   = new JTextField(ti.title,  28);
        lblStatus = new JLabel(" ");
        lblStatus.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        resultsModel = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        resultsTable = new JTable(resultsModel);
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.setRowHeight(24);
        resultsTable.setShowHorizontalLines(false);
        resultsTable.setIntercellSpacing(new Dimension(0, 0));
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.getTableHeader().setReorderingAllowed(false);

        TableColumnModel cm = resultsTable.getColumnModel();
        cm.getColumn(0).setMaxWidth(60);
        cm.getColumn(0).setMinWidth(50);
        cm.getColumn(1).setPreferredWidth(150);
        cm.getColumn(2).setPreferredWidth(130);
        cm.getColumn(3).setPreferredWidth(180);
        cm.getColumn(4).setPreferredWidth(130);
        cm.getColumn(5).setMaxWidth(54);
        cm.getColumn(6).setPreferredWidth(230);
        cm.getColumn(0).setCellRenderer(new ScoreRenderer());

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(buildSearchBar(), BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        getContentPane().add(buildFooter(),    BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });

        // Pré-charger les candidats du batch ou lancer une recherche
        if (entry.candidates != null && !entry.candidates.isEmpty()) {
            fill(entry.candidates);
            lblStatus.setText(I18n.t("%d candidat(s) du batch — affinez la recherche si besoin", entry.candidates.size()));
        } else {
            search();
        }
    }

    // ── Barre de recherche ────────────────────────────────────────────────────

    private JPanel buildSearchBar() {
        JButton btnSearch = new JButton("🔍  " + I18n.t("Chercher"));
        btnSearch.addActionListener(e -> search());
        tfArtist.addActionListener(e -> search());
        tfTitle .addActionListener(e -> search());

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        p.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));
        p.add(new JLabel(I18n.t("Artiste :"))); p.add(tfArtist);
        p.add(new JLabel(I18n.t("  Titre :"))); p.add(tfTitle);
        p.add(btnSearch);
        p.add(Box.createHorizontalStrut(10));
        p.add(lblStatus);
        return p;
    }

    private JPanel buildFooter() {
        btnApply  = new JButton("✓  " + I18n.t("Appliquer la sélection"));
        btnCancel = new JButton(I18n.t("Annuler"));
        btnApply .addActionListener(e -> applySelected());
        btnCancel.addActionListener(e -> dispose());
        btnApply.putClientProperty("FlatLaf.style", "background: #1a6030");

        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        p.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        p.add(new JLabel(I18n.t("<html><i>Double-clic ou Appliquer pour valider le résultat sélectionné</i></html>")));
        p.add(Box.createHorizontalStrut(16));
        p.add(btnCancel);
        p.add(btnApply);
        getRootPane().setDefaultButton(btnApply);

        // Double-clic = appliquer directement
        resultsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) applySelected();
            }
        });
        return p;
    }

    // ── Recherche MusicBrainz ─────────────────────────────────────────────────

    private void search() {
        String artist = tfArtist.getText().trim();
        String title  = tfTitle .getText().trim();
        if (artist.isBlank() && title.isBlank()) return;
        lblStatus.setText(I18n.t("Recherche en cours…"));
        resultsModel.setRowCount(0);
        searchResults.clear();

        new SwingWorker<List<TagInfo>, Void>() {
            @Override protected List<TagInfo> doInBackground() throws Exception {
                return mb.searchRecording(artist, title);
            }
            @Override protected void done() {
                try {
                    List<TagInfo> res = get();
                    fill(res);
                    lblStatus.setText(res.isEmpty() ? I18n.t("Aucun résultat.") : I18n.t("%d résultat(s)", res.size()));
                    if (!res.isEmpty()) resultsTable.setRowSelectionInterval(0, 0);
                } catch (Exception ex) {
                    lblStatus.setText(I18n.t("Erreur : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void fill(List<TagInfo> results) {
        searchResults.clear();
        searchResults.addAll(results);
        resultsModel.setRowCount(0);
        for (TagInfo t : results) {
            String mbid = t.recordingMbid.length() > 8
                ? t.recordingMbid.substring(0, 8) + "…"
                : t.recordingMbid;
            resultsModel.addRow(new Object[]{
                t.score + "%", t.artist, t.albumArtist, t.title, t.album, t.year, mbid
            });
        }
    }

    // ── Application ───────────────────────────────────────────────────────────

    private void applySelected() {
        int row = resultsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez un résultat dans la liste."),
                    I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = resultsTable.convertRowIndexToModel(row);
        if (modelRow >= searchResults.size()) return;
        TagInfo chosen = searchResults.get(modelRow);
        java.nio.file.Path path = entry.currentPath != null ? entry.currentPath : entry.file.toPath();

        btnApply.setEnabled(false);
        btnCancel.setEnabled(false);
        resultsTable.setEnabled(false);
        lblStatus.setText(I18n.t("Enrichissement (genre) en cours…"));

        // Genre (Discogs/Last.fm) dans un SwingWorker, pas directement dans ce listener, sinon ça
        // fige l'EDT le temps des requêtes réseau. Plus d'écriture disque ici — façon Picard,
        // l'identification (même une correspondance choisie manuellement) ne touche jamais le
        // fichier : pochette, renommage, cache et soumission MB sont différés à l'Enregistrement
        // (TagEnrichment.saveEntry(), via SaveWorker) — voir son commentaire pour le pourquoi
        // (notamment la pochette, téléchargée dans un fichier temporaire qui ne survivrait pas à
        // un Enregistrer différé).
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                new LocalCorrector().correct(chosen, path);
                taggerScript.apply(chosen);
                MetadataCache cache = new MetadataCache();
                try {
                    TagEnrichment.enrichGenre(chosen, discogs, lastFm, cache);

                    // Empreinte AcoustID : calculée systématiquement après toute identification réussie
                    // (TaggingWorker/BatchProcessor/App le font déjà, comme Picard) — une correspondance
                    // confirmée manuellement est une identification tout aussi réussie. Reste ici : pur
                    // calcul local (fpcalc), ne touche pas le disque du fichier lui-même.
                    if (Config.get().saveAcoustidFingerprints() && chosen.acoustidFingerprint.isBlank()
                            && com.opentagger.FpcalcInstaller.isAvailable()) {
                        try {
                            chosen.acoustidFingerprint = com.opentagger.Fingerprinter.compute(path.toFile()).fingerprint();
                        } catch (Exception ignored) {}
                    }
                    chosen.identificationSource = MetadataCache.SOURCE_MBID;
                } finally {
                    cache.close();
                }
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MatchDialog.this, I18n.t("Erreur : %s", ex.getMessage()),
                            I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                    btnApply.setEnabled(true);
                    btnCancel.setEnabled(true);
                    resultsTable.setEnabled(true);
                    lblStatus.setText(" ");
                    return;
                }

                entry.result      = chosen;
                entry.status      = FileEntry.Status.IDENTIFIED;
                entry.message     = I18n.t("Sélectionné manuellement, pas encore enregistré");
                entry.candidates  = null;

                tableModel.update(entry);
                if (onApplied != null) onApplied.run();
                dispose();
            }
        }.execute();
    }

    // ── Renderer coloré pour le score ─────────────────────────────────────────

    private static class ScoreRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v, sel, foc, row, col);
            setHorizontalAlignment(CENTER);
            if (!sel && v instanceof String s) {
                int sc = 0;
                try { sc = Integer.parseInt(s.replace("%", "")); } catch (NumberFormatException ignored) {}
                if      (sc >= 90) setForeground(new Color(60,  200,  80));
                else if (sc >= 70) setForeground(new Color(220, 160,  30));
                else               setForeground(new Color(220,  70,  50));
            }
            return this;
        }
    }
}
