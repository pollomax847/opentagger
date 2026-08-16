package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.ITunesLibraryImporter;
import com.opentagger.MetadataCache;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Import (lecture SEULE du fichier XML — jamais d'écriture dessus, voir ITunesLibraryImporter)
 * des notes iTunes vers les tags des fichiers CHARGÉS dans le tableau principal. Demande
 * utilisateur (2026-08-16) après investigation des formats .plist/XML iTunes — Play Count/Skip
 * Count/Play Date n'ont pas d'équivalent standard dans les tags audio (contrairement à Rating,
 * déjà entièrement câblé — voir TagInfo.rating/TagWriter), donc affichés à titre informatif dans
 * le résumé mais jamais écrits dans les fichiers.
 *
 * Ne touche jamais un fichier absent du tableau actuellement chargé, même si l'import le résout
 * sur disque (voir MetadataCache.loadScanCacheMap(), utilisé seulement comme repli de résolution
 * de chemin, pas comme cible d'écriture) — cohérent avec la préférence utilisateur de ne jamais
 * agir sur un fichier que la session en cours n'a pas explicitement chargé/vu.
 */
public class ITunesImportDialog extends JDialog {

    private final MainFrame      owner;
    private final FileTableModel tableModel;

    private final JLabel    lblFile   = new JLabel(I18n.t("Aucun fichier choisi"));
    private final JTextArea summary   = new JTextArea(10, 50);
    private final JButton   btnChoose = new JButton(I18n.t("Choisir le fichier XML iTunes…"));
    private final JButton   btnApply  = new JButton(I18n.t("Appliquer les notes"));

    private Map<FileEntry, String> pendingRatings = new HashMap<>();

    public ITunesImportDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Importer XML iTunes — OpenTagger"), true);
        this.owner      = owner;
        this.tableModel = tableModel;
        setSize(620, 420);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(btnChoose);
        top.add(lblFile);
        add(top, BorderLayout.NORTH);

        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        summary.setText(I18n.t(
                "Lit \"iTunes Music Library.xml\" (jamais modifié) pour en extraire les notes "
              + "(⭐) — Play Count/Skip Count n'ont pas d'équivalent standard dans les tags audio, "
              + "affichés ici à titre informatif seulement.\n\n"
              + "Si les chemins ne se résolvent pas (lecteur Windows dans le XML), configure "
              + "itunes.xml_path_from / itunes.xml_path_to dans les Préférences avant de relancer."));
        add(new JScrollPane(summary), BorderLayout.CENTER);

        btnApply.setEnabled(false);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(btnApply);
        add(bottom, BorderLayout.SOUTH);

        btnChoose.addActionListener(e -> chooseAndImport());
        btnApply.addActionListener(e -> apply());
    }

    private void chooseAndImport() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("iTunes Music Library.xml", "xml"));
        // Chemin mémorisé (Préférences > iTunes) — voir Config.itunesXmlFilePath(), pré-rempli
        // pour ne pas re-choisir le même fichier à chaque import (demande utilisateur 2026-08-16).
        String remembered = com.opentagger.Config.get().itunesXmlFilePath();
        if (!remembered.isBlank()) fc.setSelectedFile(new File(remembered));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File xml = fc.getSelectedFile();
        com.opentagger.Config.get().set("itunes.xml_file_path", xml.getAbsolutePath());
        lblFile.setText(xml.getName());
        btnChoose.setEnabled(false);
        summary.setText(I18n.t("Analyse en cours…"));

        new SwingWorker<String, Void>() {
            private Map<FileEntry, String> ratings = new HashMap<>();
            private int xmlWithRating, xmlWithPlayCount, resolvedOnDisk, appliedToLoaded;

            @Override protected String doInBackground() throws Exception {
                List<ITunesLibraryImporter.ITunesTrack> tracks = ITunesLibraryImporter.parse(xml);

                // Index nom de fichier → chemins connus (toute la bibliothèque déjà scannée, pas
                // seulement le tableau chargé) — repli quand la substitution de préfixe seule ne
                // suffit pas (dossiers réorganisés depuis l'export XML, constaté en réel).
                Map<String, List<Path>> byFilename = new HashMap<>();
                try (MetadataCache cache = new MetadataCache()) {
                    for (String p : cache.loadScanCacheMap().keySet()) {
                        Path path = Paths.get(p);
                        byFilename.computeIfAbsent(
                                path.getFileName().toString().toLowerCase(Locale.ROOT),
                                k -> new ArrayList<>()).add(path);
                    }
                }

                // Index chemin → FileEntry du tableau chargé — seule cible d'écriture autorisée.
                Map<Path, FileEntry> loadedByPath = new HashMap<>();
                for (FileEntry fe : tableModel.allEntries()) {
                    Path p = (fe.currentPath != null ? fe.currentPath : fe.file.toPath())
                            .toAbsolutePath().normalize();
                    loadedByPath.put(p, fe);
                }

                for (ITunesLibraryImporter.ITunesTrack t : tracks) {
                    if (t.rating() > 0) xmlWithRating++;
                    if (t.playCount() > 0) xmlWithPlayCount++;
                    if (t.rating() <= 0) continue;

                    Path resolved = ITunesLibraryImporter.resolve(t, byFilename);
                    if (resolved == null) continue;
                    resolvedOnDisk++;

                    FileEntry fe = loadedByPath.get(resolved.toAbsolutePath().normalize());
                    if (fe == null) continue; // résolu sur disque mais pas chargé dans cette session
                    appliedToLoaded++;
                    // Track ID retenu même si ce fichier n'a pas de note à appliquer maintenant —
                    // permet à un futur renommage de ce fichier de repousser sa Location corrigée
                    // vers le XML (voir ITunesXmlSyncQueue), indépendamment de l'import de note.
                    fe.itunesTrackId = t.trackId();
                    com.opentagger.ITunesXmlSyncQueue.registerKnownPath(resolved, t.trackId());
                    int stars = Math.max(1, Math.min(5, t.rating() / 20));
                    ratings.put(fe, String.valueOf(stars));
                }

                return I18n.t(
                        "%d pistes avec note dans le XML, %d avec un nombre d'écoutes (info seulement).\n"
                      + "%d résolues sur le disque, %d correspondent à un fichier CHARGÉ dans le "
                      + "tableau (seuls ceux-là seront modifiés — charge le dossier concerné si "
                      + "besoin, puis relance l'import).",
                        xmlWithRating, xmlWithPlayCount, resolvedOnDisk, appliedToLoaded);
            }

            @Override protected void done() {
                btnChoose.setEnabled(true);
                try {
                    summary.setText(get());
                    pendingRatings = ratings;
                    btnApply.setEnabled(!ratings.isEmpty());
                } catch (Exception ex) {
                    summary.setText(I18n.t("Échec de l'import : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void apply() {
        int n = pendingRatings.size();
        int ok = JOptionPane.showConfirmDialog(this,
                I18n.t("Appliquer %d note(s) importée(s) ? (annulable ensuite via Ctrl+Z)", n),
                I18n.t("Confirmer"), JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        owner.applyRatingImport(pendingRatings);
        btnApply.setEnabled(false);
        summary.append(I18n.t("\n\n✓ %d note(s) appliquée(s).", n));
    }
}
