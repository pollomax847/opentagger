package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.ITunesLibraryImporter;
import com.opentagger.MetadataCache;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 *
 * Tableau de revue (2026-08-24, retour utilisateur : "pas de tableau, pas d'info sur quoi il y a
 * eu comme maj, est-ce que le fichier a bien été écrit") — remplace l'ancien résumé texte seul par
 * une vraie liste des fichiers concernés, avec un statut d'écriture RÉEL par fichier après
 * application (succès/échec constaté, pas juste "traité").
 */
public class ITunesImportDialog extends JDialog {

    private final MainFrame      owner;
    private final FileTableModel tableModel;

    private static final int COL_STATUS = 2;

    private final JLabel    lblFile    = new JLabel(I18n.t("Aucun fichier choisi"));
    private final JLabel    lblSummary = new JLabel(" ");
    private final JButton   btnChoose  = new JButton(I18n.t("Choisir le fichier XML iTunes…"));
    private final JButton   btnApply   = new JButton(I18n.t("Appliquer les notes"));
    private final DefaultTableModel ratingTableModel = new DefaultTableModel(
            new Object[]{I18n.t("Fichier"), I18n.t("Note"), I18n.t("Statut")}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable ratingTable = new JTable(ratingTableModel);

    /** Ordre parallèle aux lignes du tableau — ratingTableModel n'a pas de référence directe au
     *  FileEntry, seulement son affichage. */
    private final List<FileEntry> rowEntries = new ArrayList<>();
    private Map<FileEntry, String> pendingRatings = new HashMap<>();

    public ITunesImportDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Importer XML iTunes — OpenTagger"), true);
        this.owner      = owner;
        this.tableModel = tableModel;
        setSize(680, 480);
        setMinimumSize(new Dimension(480, 320));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(btnChoose);
        top.add(lblFile);
        north.add(top);
        lblSummary.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));
        lblSummary.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        setSummaryIdle();
        north.add(lblSummary);
        add(north, BorderLayout.NORTH);

        ratingTable.getColumnModel().getColumn(1).setMaxWidth(60);
        ratingTable.getColumnModel().getColumn(1).setCellRenderer(centered());
        ratingTable.getColumnModel().getColumn(COL_STATUS).setMaxWidth(160);
        ratingTable.getColumnModel().getColumn(COL_STATUS).setCellRenderer(statusRenderer());
        ratingTable.setRowHeight(22);
        ratingTable.setShowGrid(false);
        ratingTable.setFillsViewportHeight(true);
        add(new JScrollPane(ratingTable), BorderLayout.CENTER);

        btnApply.setEnabled(false);
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(btnApply);
        add(bottom, BorderLayout.SOUTH);

        btnChoose.addActionListener(e -> chooseAndImport());
        btnApply.addActionListener(e -> apply());
    }

    private void setSummaryIdle() {
        lblSummary.setText(I18n.t(
                "<html>Lit « iTunes Music Library.xml » (jamais modifié) pour en extraire les notes (⭐).<br>"
              + "Si les chemins ne se résolvent pas (lecteur Windows dans le XML), configure "
              + "itunes.xml_path_from / itunes.xml_path_to dans les Préférences avant de relancer.</html>"));
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

        // Repéré en direct (2026-08-17) : un dossier avait été enregistré comme "fichier XML"
        // choisi (FlatLaf permet de valider "Ouvrir" sur un dossier, contrairement au Swing par
        // défaut) — le parsing échouait silencieusement ensuite (aucun retour visible), et le
        // chemin invalide restait mémorisé pour la prochaine fois. Recherche automatique du
        // fichier dedans plutôt que d'échouer sans explication.
        if (xml.isDirectory()) {
            File candidate = new File(xml, "iTunes Music Library.xml");
            if (candidate.isFile()) {
                xml = candidate;
            } else {
                JOptionPane.showMessageDialog(this, I18n.t(
                        "\"%s\" est un dossier, pas le fichier XML lui-même — et aucun "
                      + "\"iTunes Music Library.xml\" n'a été trouvé dedans. Choisis directement "
                      + "le fichier .xml.", xml.getName()),
                        I18n.t("Sélection invalide"), JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        if (!xml.isFile()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Fichier introuvable : %s", xml.getAbsolutePath()),
                    I18n.t("Sélection invalide"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        com.opentagger.Config.get().set("itunes.xml_file_path", xml.getAbsolutePath());
        lblFile.setText(xml.getName());
        btnChoose.setEnabled(false);
        btnApply.setEnabled(false);
        ratingTableModel.setRowCount(0);
        rowEntries.clear();
        lblSummary.setText(I18n.t("Analyse en cours…"));

        final File xmlFinal = xml;
        new SwingWorker<String, Void>() {
            private final Map<FileEntry, String> ratings = new LinkedHashMap<>();
            private int xmlWithRating, xmlWithPlayCount, resolvedOnDisk, appliedToLoaded, matchedByTags;

            @Override protected String doInBackground() throws Exception {
                List<ITunesLibraryImporter.ITunesTrack> tracks = ITunesLibraryImporter.parse(xmlFinal);

                // Index nom de fichier → chemins connus (toute la bibliothèque déjà scannée, pas
                // seulement le tableau chargé) — repli quand la substitution de préfixe seule ne
                // suffit pas (dossiers réorganisés depuis l'export XML, constaté en réel).
                //
                // Cache PARTAGÉ (acquire/release à compteur de références, voir MainFrame) plutôt
                // qu'un chargement direct via cache.loadScanCacheMap() — corrigé le 2026-08-17
                // après avoir constaté en direct une fuite mémoire native de plusieurs Go sur
                // quelques tentatives d'import répétées : charger tout scan_cache (des centaines
                // de milliers de lignes) à chaque appel est EXACTEMENT le motif déjà documenté
                // ailleurs (voir le commentaire de MainFrame.sharedScanCacheMap) comme ayant causé
                // un OutOfMemoryError par le passé pour la même raison.
                Map<String, List<Path>> byFilename = new HashMap<>();
                try (MetadataCache cache = new MetadataCache()) {
                    var scanCacheMap = owner.acquireScanCacheMap(cache);
                    try {
                        for (String p : scanCacheMap.keySet()) {
                            Path path = Paths.get(p);
                            byFilename.computeIfAbsent(
                                    path.getFileName().toString().toLowerCase(Locale.ROOT),
                                    k -> new ArrayList<>()).add(path);
                        }
                    } finally {
                        owner.releaseScanCacheMap();
                    }
                }

                // Index chemin → FileEntry du tableau chargé — seule cible d'écriture autorisée.
                Map<Path, FileEntry> loadedByPath = new HashMap<>();
                // Repli Artiste+Titre — retour utilisateur (2026-08-24, "cela sert à rien") après
                // avoir constaté un taux de correspondance catastrophique (54/14487) : resolve()
                // ne matche QUE par chemin/nom de fichier, qui échoue en masse dès qu'OpenTagger a
                // renommé le fichier depuis l'export XML (le cas de la quasi-totalité de cette
                // bibliothèque après un mois de taguage). Artiste+Titre survit au renommage — c'est
                // justement le contenu que le taguage préserve/corrige, contrairement au nom de
                // fichier. Clé normalisée (minuscules, espaces réduits) construite depuis les tags
                // RÉELS du fichier (activeTags()), pas son nom — index limité au tableau CHARGÉ
                // (seule cible d'écriture autorisée de toute façon, pas la peine d'indexer tout
                // scan_cache pour ça). Un seul candidat exigé (même garde-fou que byFilename dans
                // ITunesLibraryImporter.resolve()) : jamais de devinette parmi plusieurs pistes de
                // même titre.
                Map<String, List<FileEntry>> byArtistTitle = new HashMap<>();
                for (FileEntry fe : tableModel.allEntries()) {
                    Path p = (fe.currentPath != null ? fe.currentPath : fe.file.toPath())
                            .toAbsolutePath().normalize();
                    loadedByPath.put(p, fe);
                    var tags = fe.activeTags();
                    if (tags == null || tags.artist.isBlank() || tags.title.isBlank()) continue;
                    String key = normalizeKey(tags.artist) + "|" + normalizeKey(tags.title);
                    byArtistTitle.computeIfAbsent(key, k -> new ArrayList<>()).add(fe);
                }

                for (ITunesLibraryImporter.ITunesTrack t : tracks) {
                    if (t.rating() > 0) xmlWithRating++;
                    if (t.playCount() > 0) xmlWithPlayCount++;
                    if (t.rating() <= 0) continue;

                    Path resolved = ITunesLibraryImporter.resolve(t, byFilename);
                    if (resolved != null) resolvedOnDisk++;

                    FileEntry fe = resolved != null ? loadedByPath.get(resolved.toAbsolutePath().normalize()) : null;
                    if (fe == null && !t.artist().isBlank() && !t.name().isBlank()) {
                        String key = normalizeKey(t.artist()) + "|" + normalizeKey(t.name());
                        List<FileEntry> candidates = byArtistTitle.get(key);
                        if (candidates != null && candidates.size() == 1) {
                            fe = candidates.get(0);
                            matchedByTags++;
                        }
                    }
                    if (fe == null) continue; // ni chemin, ni artiste+titre : rien de fiable à proposer
                    appliedToLoaded++;
                    // Track ID retenu même si ce fichier n'a pas de note à appliquer maintenant —
                    // permet à un futur renommage de ce fichier de repousser sa Location corrigée
                    // vers le XML (voir ITunesXmlSyncQueue), indépendamment de l'import de note.
                    fe.itunesTrackId = t.trackId();
                    // resolved peut être null ici (match trouvé par repli Artiste+Titre, jamais par
                    // chemin) — registerKnownPath() n'a de sens que pour un VRAI chemin résolu.
                    if (resolved != null) com.opentagger.ITunesXmlSyncQueue.registerKnownPath(resolved, t.trackId());
                    int stars = Math.max(1, Math.min(5, t.rating() / 20));
                    ratings.put(fe, String.valueOf(stars));
                }

                return I18n.t(
                        "<html>%d piste(s) avec note dans le XML, %d avec un nombre d'écoutes (info seulement).<br>"
                      + "%d résolue(s) par chemin/nom de fichier, %d de plus retrouvées par artiste+titre "
                      + "(fichiers renommés depuis l'export XML, où le chemin seul échoue) — "
                      + "<b>%d</b> au total correspondent à un fichier actuellement CHARGÉ dans le tableau "
                      + "principal, seules ces lignes ci-dessous seront modifiées.<br>"
                      + "Pour couvrir davantage de pistes, charge d'autres dossiers de ta bibliothèque puis "
                      + "relance l'import.</html>",
                        xmlWithRating, xmlWithPlayCount, resolvedOnDisk, matchedByTags, appliedToLoaded);
            }

            @Override protected void done() {
                btnChoose.setEnabled(true);
                try {
                    lblSummary.setText(get());
                    pendingRatings = ratings;
                    rowEntries.clear();
                    ratingTableModel.setRowCount(0);
                    for (var en : ratings.entrySet()) {
                        rowEntries.add(en.getKey());
                        ratingTableModel.addRow(new Object[]{
                                en.getKey().filename(), "★".repeat(Integer.parseInt(en.getValue())), ""
                        });
                    }
                    btnApply.setEnabled(!ratings.isEmpty());
                } catch (Exception ex) {
                    // Popup EN PLUS du texte du résumé (2026-08-17, retour utilisateur : un échec
                    // silencieux dans la zone de texte n'était pas assez visible pour être remarqué)
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    lblSummary.setText(I18n.t("Échec de l'import : %s", msg));
                    JOptionPane.showMessageDialog(ITunesImportDialog.this,
                            I18n.t("Échec de l'import : %s", msg),
                            I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
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
        btnApply.setEnabled(false);
        // owner.applyRatingImport() écrit SYNCHRONEMENT sur disque (voir sa Javadoc) — appelé
        // depuis un SwingWorker quand même : sur un lot de dizaines/centaines de fichiers, chaque
        // écriture est une E/S disque réelle, jamais bloquer l'EDT pour ça (même raison que
        // partout ailleurs dans l'appli).
        new SwingWorker<Map<FileEntry, Boolean>, Void>() {
            @Override protected Map<FileEntry, Boolean> doInBackground() {
                return owner.applyRatingImport(pendingRatings);
            }
            @Override protected void done() {
                Map<FileEntry, Boolean> results;
                try { results = get(); } catch (Exception ex) { results = Map.of(); }
                int okCount = 0, failCount = 0;
                for (int i = 0; i < rowEntries.size(); i++) {
                    Boolean written = results.get(rowEntries.get(i));
                    if (written == null) continue; // ne devrait pas arriver, filet de sécurité
                    ratingTableModel.setValueAt(written ? I18n.t("✔ écrit") : I18n.t("✗ échec"), i, COL_STATUS);
                    if (written) okCount++; else failCount++;
                }
                String msg = failCount > 0
                        ? I18n.t("%d note(s) écrite(s), %d échec(s) — voir la colonne Statut.", okCount, failCount)
                        : I18n.t("%d note(s) écrite(s) avec succès.", okCount);
                JOptionPane.showMessageDialog(ITunesImportDialog.this, msg,
                        I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    /** Clé de correspondance Artiste+Titre — minuscules et espaces multiples réduits, pour absorber
     *  les petites variations de formatage sans faire de vrai rapprochement flou (pas de tolérance
     *  aux fautes/accents : un rapprochement trop permissif risquerait d'attribuer la note d'un
     *  morceau à un autre, jamais acceptable pour une modification de tags). */
    private static String normalizeKey(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static DefaultTableCellRenderer centered() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        };
    }

    private static DefaultTableCellRenderer statusRenderer() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setHorizontalAlignment(SwingConstants.CENTER);
                String s = v == null ? "" : v.toString();
                Color fg = t.getForeground();
                if (s.startsWith("✔")) fg = new Color(0x66bb6a);
                else if (s.startsWith("✗")) fg = new Color(0xcc4444);
                setForeground(sel ? t.getSelectionForeground() : fg);
                return c;
            }
        };
    }
}
