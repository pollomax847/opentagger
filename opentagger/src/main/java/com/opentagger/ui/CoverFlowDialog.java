package com.opentagger.ui;

import com.opentagger.AlbumGrouping;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

/**
 * Fenêtre du carrousel Cover Flow — instantané des albums déjà tagués (voir le plan : pas de mise à
 * jour en direct pendant un taguage, "🔄 Rafraîchir" recalcule à la demande). Volontairement
 * indépendant de MainFrame.ViewMode/table.setModel() : reçoit juste un fournisseur de
 * {@code List<FileEntry>} (comme {@code RenamePreviewDialog} reçoit une liste déjà calculée), lit un
 * instantané de la bibliothèque exactement comme {@code detectDuplicates()} le fait déjà ailleurs.
 */
public class CoverFlowDialog extends JDialog {

    private final Supplier<List<FileEntry>> entriesSupplier;
    private final CoverFlowPanel panel = new CoverFlowPanel();
    private final JTextField tfSearch = new JTextField();
    private final JLabel     lblStatus = new JLabel(" ");

    public CoverFlowDialog(Frame owner, Supplier<List<FileEntry>> entriesSupplier) {
        super(owner, I18n.t("Cover Flow"), false); // non-modal, comme RenamePreviewDialog/HistoryDialog
        this.entriesSupplier = entriesSupplier;

        setContentPane(buildContent());
        setSize(820, 560);
        setMinimumSize(new Dimension(520, 380));
        setLocationRelativeTo(owner);

        panel.setSelectionListener(this::onSelectionChanged);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dispose(); } });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { panel.dispose(); }
        });

        refresh();
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        tfSearch.putClientProperty("JTextField.placeholderText", I18n.t("Rechercher un artiste ou un album…"));
        tfSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { panel.filter(tfSearch.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { panel.filter(tfSearch.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { panel.filter(tfSearch.getText()); }
        });
        JButton btnRefresh = new JButton(I18n.t("🔄 Rafraîchir"));
        btnRefresh.addActionListener(e -> refresh());

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(tfSearch,    BorderLayout.CENTER);
        top.add(btnRefresh,  BorderLayout.EAST);

        lblStatus.setHorizontalAlignment(SwingConstants.CENTER);
        lblStatus.putClientProperty("FlatLaf.style", "font: bold 13 $defaultFont");

        root.add(top,         BorderLayout.NORTH);
        root.add(panel,       BorderLayout.CENTER);
        root.add(lblStatus,   BorderLayout.SOUTH);
        return root;
    }

    /** Reprend un instantané complet — appelé à l'ouverture et par "🔄 Rafraîchir". Un seul
     *  FileEntry représentatif par album (le premier rencontré ; la pochette est identique sur
     *  toutes les pistes d'un même album, inutile de trier par piste ici comme AlbumTreeTableModel
     *  le fait pour l'affichage des pistes elles-mêmes). */
    private void refresh() {
        List<FileEntry> entries = entriesSupplier.get();
        LinkedHashMap<String, CoverFlowPanel.AlbumTile> byKey = new LinkedHashMap<>();
        for (FileEntry e : entries) {
            if (e.status != FileEntry.Status.TAGGED) continue;
            String key = AlbumGrouping.key(e);
            if (byKey.containsKey(key)) continue;
            var tags = e.activeTags();
            String artist = !tags.albumArtist.isBlank() ? tags.albumArtist : tags.artist;
            java.io.File file = e.currentPath != null ? e.currentPath.toFile() : e.file;
            byKey.put(key, new CoverFlowPanel.AlbumTile(key, AlbumGrouping.title(e), artist, file));
        }
        List<CoverFlowPanel.AlbumTile> tiles = new ArrayList<>(byKey.values());
        tiles.sort(Comparator.comparing(CoverFlowPanel.AlbumTile::title, String.CASE_INSENSITIVE_ORDER));
        panel.setAlbums(tiles);
        setTitle(I18n.t("Cover Flow — %d album(s)", tiles.size()));
    }

    private void onSelectionChanged(CoverFlowPanel.AlbumTile tile, int index, int total) {
        if (tile == null) { lblStatus.setText(I18n.t("Aucun album tagué à afficher")); return; }
        lblStatus.setText((index + 1) + " / " + total + "   —   " + tile.title());
    }
}
