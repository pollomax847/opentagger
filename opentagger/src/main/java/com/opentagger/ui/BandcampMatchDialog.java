package com.opentagger.ui;

import com.opentagger.BandcampClient;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Applique un album Bandcamp à des fichiers SÉLECTIONNÉS dans le tableau principal — l'utilisateur
 * trouve l'album lui-même dans son navigateur (recherche automatique bandcamp.com/search
 * impossible, voir BandcampClient) et colle l'URL ici. Position N de la page Bandcamp → N-ième
 * fichier sélectionné (ordre du tableau) : demande une sélection manuelle plutôt que de deviner
 * un ordre depuis les tags/noms de fichiers, cohérent avec l'esprit "usage ponctuel" de cette
 * fonctionnalité.
 */
public class BandcampMatchDialog extends JDialog {

    private final MainFrame owner;

    private final JTextField tfUrl      = new JTextField(40);
    private final JButton    btnFetch   = new JButton(I18n.t("Récupérer"));
    private final JTextArea  summary    = new JTextArea(12, 50);
    private final JButton    btnApply   = new JButton(I18n.t("Appliquer à la sélection"));

    private BandcampClient.BandcampAlbum album;

    public BandcampMatchDialog(MainFrame owner, List<FileEntry> selection) {
        super(owner, I18n.t("Coller une URL Bandcamp — OpenTagger"), true);
        this.owner = owner;
        setSize(640, 460);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        top.add(new JLabel(I18n.t("URL de l'album (ex. https://artiste.bandcamp.com/album/nom) :")), BorderLayout.NORTH);
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.add(tfUrl, BorderLayout.CENTER);
        row.add(btnFetch, BorderLayout.EAST);
        top.add(row, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setText(I18n.t(
                "%d fichier(s) actuellement sélectionné(s) dans le tableau — la piste 1 de "
              + "Bandcamp ira au 1er fichier sélectionné, la piste 2 au 2e, etc. (ordre du "
              + "tableau). Vérifie ta sélection avant de coller l'URL.", selection.size()));
        add(new JScrollPane(summary), BorderLayout.CENTER);

        btnApply.setEnabled(false);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(btnApply);
        add(bottom, BorderLayout.SOUTH);

        btnFetch.addActionListener(e -> fetch(selection));
        btnApply.addActionListener(e -> apply(selection));
    }

    private void fetch(List<FileEntry> selection) {
        String url = tfUrl.getText().trim();
        if (url.isBlank()) return;
        btnFetch.setEnabled(false);
        btnApply.setEnabled(false);
        summary.setText(I18n.t("Récupération…"));

        new SwingWorker<BandcampClient.BandcampAlbum, Void>() {
            @Override protected BandcampClient.BandcampAlbum doInBackground() throws Exception {
                return BandcampClient.fetchAlbum(url);
            }
            @Override protected void done() {
                btnFetch.setEnabled(true);
                try {
                    album = get();
                    if (album == null) {
                        summary.setText(I18n.t("Aucune donnée d'album trouvée sur cette page (pas de JSON-LD MusicAlbum)."));
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append(album.artist()).append(" – ").append(album.title()).append('\n');
                    sb.append(album.tracks().size()).append(I18n.t(" piste(s), ")).append(selection.size())
                      .append(I18n.t(" fichier(s) sélectionné(s)\n\n"));
                    for (var t : album.tracks()) {
                        sb.append(t.position()).append(". ").append(t.title());
                        if (t.durationSec() > 0) sb.append(" (").append(t.durationSec() / 60).append(':')
                                .append(String.format("%02d", t.durationSec() % 60)).append(')');
                        sb.append('\n');
                    }
                    if (!album.credits().isBlank()) sb.append('\n').append(I18n.t("Crédits : ")).append(album.credits());
                    if (album.tracks().size() != selection.size()) {
                        sb.append("\n\n⚠ ").append(I18n.t(
                                "Nombre de pistes différent du nombre de fichiers sélectionnés — "
                              + "seules les positions communes seront appliquées."));
                    }
                    summary.setText(sb.toString());
                    btnApply.setEnabled(!selection.isEmpty());
                } catch (Exception ex) {
                    summary.setText(I18n.t("Échec : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void apply(List<FileEntry> selection) {
        if (album == null) return;
        int n = Math.min(album.tracks().size(), selection.size());
        int ok = JOptionPane.showConfirmDialog(this,
                I18n.t("Appliquer les %d piste(s) correspondantes ? (annulable ensuite via Ctrl+Z)", n),
                I18n.t("Confirmer"), JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        owner.applyBandcampAlbum(album, selection);
        btnApply.setEnabled(false);
        summary.append(I18n.t("\n\n✓ Appliqué."));
    }
}
