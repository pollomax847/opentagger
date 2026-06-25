package com.opentagger.ui;

import com.opentagger.LocalCorrector;
import com.opentagger.MusicBrainzClient;
import com.opentagger.TagWriter;
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
        {"Score", "Artiste", "Artiste Album", "Titre", "Album", "Année", "MBID"};

    private final FileEntry       entry;
    private final FileTableModel  tableModel;
    private final Runnable        onApplied;
    private final MusicBrainzClient mb = new MusicBrainzClient();

    private final JTextField     tfArtist;
    private final JTextField     tfTitle;
    private final JLabel         lblStatus;
    private final JTable         resultsTable;
    private final DefaultTableModel resultsModel;
    private final List<TagInfo>  searchResults = new ArrayList<>();

    public MatchDialog(Frame owner, FileEntry entry, FileTableModel tableModel, Runnable onApplied) {
        super(owner, "Correspondance manuelle — " + entry.filename(), true);
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
            lblStatus.setText(entry.candidates.size() + " candidat(s) du batch — affinez la recherche si besoin");
        } else {
            search();
        }
    }

    // ── Barre de recherche ────────────────────────────────────────────────────

    private JPanel buildSearchBar() {
        JButton btnSearch = new JButton("🔍  Chercher");
        btnSearch.addActionListener(e -> search());
        tfArtist.addActionListener(e -> search());
        tfTitle .addActionListener(e -> search());

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        p.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));
        p.add(new JLabel("Artiste :")); p.add(tfArtist);
        p.add(new JLabel("  Titre :")); p.add(tfTitle);
        p.add(btnSearch);
        p.add(Box.createHorizontalStrut(10));
        p.add(lblStatus);
        return p;
    }

    private JPanel buildFooter() {
        JButton btnApply  = new JButton("✓  Appliquer la sélection");
        JButton btnCancel = new JButton("Annuler");
        btnApply .addActionListener(e -> applySelected());
        btnCancel.addActionListener(e -> dispose());
        btnApply.putClientProperty("FlatLaf.style", "background: #1a6030");

        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        p.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        p.add(new JLabel("<html><i>Double-clic ou Appliquer pour valider le résultat sélectionné</i></html>"));
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
        lblStatus.setText("Recherche en cours…");
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
                    lblStatus.setText(res.isEmpty() ? "Aucun résultat." : res.size() + " résultat(s)");
                    if (!res.isEmpty()) resultsTable.setRowSelectionInterval(0, 0);
                } catch (Exception ex) {
                    lblStatus.setText("Erreur : " + ex.getMessage());
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
            JOptionPane.showMessageDialog(this, "Sélectionnez un résultat dans la liste.",
                    "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = resultsTable.convertRowIndexToModel(row);
        if (modelRow >= searchResults.size()) return;
        TagInfo chosen = searchResults.get(modelRow);

        // Corrections locales
        java.nio.file.Path path = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
        new LocalCorrector().correct(chosen, path);

        entry.result     = chosen;
        entry.status     = FileEntry.Status.TAGGED;
        entry.message    = "Sélectionné manuellement";
        entry.candidates = null;

        try {
            new TagWriter().write(path.toFile(), chosen);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Erreur d'écriture : " + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
            return;
        }

        tableModel.update(entry);
        if (onApplied != null) onApplied.run();
        dispose();
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
