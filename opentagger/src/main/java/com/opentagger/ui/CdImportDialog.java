package com.opentagger.ui;

import com.opentagger.AudioTranscoder;
import com.opentagger.CdRipper;
import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MusicBrainzClient;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Import de CD — volet audio (extraction via CdRipper + identification par TOC + vérification de
 * doublons dans la bibliothèque avant extraction) et volet CD de données (simple copie de
 * fichiers). Demande utilisateur (2026-08-21).
 *
 * Les pistes extraites atterrissent dans un dossier de travail temporaire
 * (~/.opentagger/cd-import/<horodatage>/), PAS directement dans la bibliothèque rangée — une fois
 * l'extraction terminée, {@link MainFrame#importFolder} relance le pipeline normal dessus
 * (identification/renommage/déplacement), exactement comme n'importe quel dossier ouvert
 * manuellement. Choix explicite (2026-08-21) plutôt qu'une identification "figée" au moment de
 * l'extraction : le pipeline normal, piste par piste, reste plus fiable que la correspondance TOC
 * grossière utilisée ici seulement pour PRÉVISUALISER les titres et détecter les doublons.
 */
public class CdImportDialog extends JDialog {

    private final MainFrame owner;
    private CdRipper.Toc toc;
    private MusicBrainzClient.ReleaseTracklist identified; // null si non identifié

    private static final int COL_STATUS = 5;

    // En-tête album — style proéminent (titre en gras, sous-titre discret), même esprit que la
    // fenêtre de ripping d'iTunes/Asunder qui affiche toujours artiste/album en haut, séparé du
    // texte de statut secondaire en bas de fenêtre. Retour utilisateur (2026-08-23, "améliore l'ui").
    private final JLabel lblAlbumHeader = new JLabel();
    // Carte "aucun CD" affichée tant que rien n'a été détecté, à la place d'un tableau vide qui ne
    // dit rien de ce qu'il faut faire — remplacée par le vrai tableau dès qu'une table des pistes
    // existe (voir showTrackTable()).
    private final JLabel lblEmptyState = new JLabel(
            I18n.t("<html><center>💿<br><br>Insère un CD puis clique sur « Détecter le CD »</center></html>"),
            SwingConstants.CENTER);
    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerPanel = new JPanel(centerCards);
    private static final String CARD_EMPTY = "empty", CARD_TABLE = "table";

    private final JLabel lblStatus = new JLabel();
    // Barre + label dédiés à l'extraction en cours — même esprit que la fenêtre de ripping
    // d'Asunder/iTunes (retour utilisateur, 2026-08-23) : un statut texte seul en bas de fenêtre ne
    // donnait aucune idée de la progression réelle ni de quelle piste précisément était en cours.
    private final JProgressBar progressBar = new JProgressBar(0, 1);
    private final JLabel lblProgress = new JLabel(" ");
    private final DefaultTableModel trackTableModel = new DefaultTableModel(
            new Object[]{"", "#", I18n.t("Titre"), I18n.t("Durée"), I18n.t("Déjà présent ?"), I18n.t("Statut")}, 0) {
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
    };
    private final JTable trackTable = new JTable(trackTableModel);
    private final JComboBox<AudioTranscoder.Format> cbFormat = new JComboBox<>(AudioTranscoder.Format.values());
    private final JButton btnDetect    = new JButton(I18n.t("Détecter le CD"));
    private final JButton btnExtract   = new JButton(I18n.t("Extraire la sélection"));
    private final JButton btnDataDisc  = new JButton(I18n.t("CD de données (copier des fichiers)…"));

    public CdImportDialog(MainFrame owner) {
        super(owner, I18n.t("Importer un CD — OpenTagger"), true);
        this.owner = owner;
        setSize(760, 480);
        setMinimumSize(new Dimension(560, 320));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        btnDetect.setIcon(new ToolbarIcon(ToolbarIcon.Kind.DISC, new Color(0x90A4AE)));
        toolRow.add(btnDetect);
        toolRow.add(new JLabel(I18n.t("Format :")));
        cbFormat.setSelectedItem(AudioTranscoder.Format.fromId(Config.get().str("transcode.format", "mp3")));
        toolRow.add(cbFormat);
        toolRow.add(btnDataDisc);
        north.add(toolRow);

        // En-tête album — masqué tant qu'aucune identification n'a eu lieu (setAlbumHeader(null)),
        // pour ne pas afficher une ligne vide qui ferait "bouger" la mise en page à chaque état.
        lblAlbumHeader.setBorder(new EmptyBorder(0, 10, 8, 10));
        lblAlbumHeader.putClientProperty("FlatLaf.style", "font: bold 14 $defaultFont");
        north.add(lblAlbumHeader);
        setAlbumHeader(null);

        add(north, BorderLayout.NORTH);

        lblEmptyState.putClientProperty("FlatLaf.style", "foreground: #888888; font: 18 $defaultFont");
        centerPanel.add(lblEmptyState, CARD_EMPTY);

        trackTable.getColumnModel().getColumn(0).setMaxWidth(30);
        trackTable.getColumnModel().getColumn(1).setMaxWidth(40);
        trackTable.getColumnModel().getColumn(1).setCellRenderer(rightAligned(null));
        trackTable.getColumnModel().getColumn(3).setMaxWidth(70);
        trackTable.getColumnModel().getColumn(3).setCellRenderer(rightAligned(null));
        trackTable.getColumnModel().getColumn(4).setMaxWidth(110);
        trackTable.getColumnModel().getColumn(4).setCellRenderer(rightAligned(new Color(0xcc4444)));
        trackTable.getColumnModel().getColumn(COL_STATUS).setCellRenderer(statusRenderer());
        trackTable.setRowHeight(22);
        trackTable.setShowGrid(false);
        trackTable.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(trackTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        centerPanel.add(tableScroll, CARD_TABLE);
        centerCards.show(centerPanel, CARD_EMPTY);

        add(centerPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

        JPanel progressRow = new JPanel(new BorderLayout(8, 0));
        progressBar.setVisible(false); // masquée hors extraction — pas de barre à 0% avant même de commencer
        progressBar.setStringPainted(true);
        lblProgress.setPreferredSize(new Dimension(260, lblProgress.getPreferredSize().height));
        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(lblProgress, BorderLayout.EAST);
        bottom.add(progressRow);

        JPanel statusRow = new JPanel(new BorderLayout());
        lblStatus.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        statusRow.add(lblStatus, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(btnExtract);
        statusRow.add(right, BorderLayout.EAST);
        bottom.add(statusRow);

        add(bottom, BorderLayout.SOUTH);

        btnExtract.setEnabled(false);
        btnDetect.addActionListener(e -> detect());
        btnExtract.addActionListener(e -> extract());
        btnDataDisc.addActionListener(e -> importDataDisc());

        lblStatus.setText(I18n.t("Insère un CD puis clique sur « Détecter le CD »."));
    }

    private void detect() {
        if (!CdRipper.isAvailable()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("cdparanoia introuvable — installez le paquet « cdparanoia » pour lire des CD audio."),
                    I18n.t("Outil manquant"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        btnDetect.setEnabled(false);
        btnExtract.setEnabled(false);
        trackTableModel.setRowCount(0);
        identified = null;
        setAlbumHeader(null);
        centerCards.show(centerPanel, CARD_EMPTY);
        lblStatus.setText(I18n.t("Lecture de la table des pistes…"));
        new SwingWorker<CdRipper.Toc, Void>() {
            @Override protected CdRipper.Toc doInBackground() throws Exception {
                return new CdRipper().queryToc();
            }
            @Override protected void done() {
                btnDetect.setEnabled(true);
                try {
                    toc = get();
                } catch (Exception ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    lblStatus.setText(I18n.t("Échec de lecture : %s", msg));
                    return;
                }
                if (!toc.isAudioDisc()) {
                    lblEmptyState.setText(I18n.t(
                            "<html><center>💿<br><br>Aucune piste audio détectée.<br>"
                          + "CD de données ? Utilise le bouton dédié ci-dessus.</center></html>"));
                    lblStatus.setText(I18n.t(
                            "Aucune piste audio détectée — CD de données ? Utilise le bouton dédié."));
                    return;
                }
                lblStatus.setText(I18n.t("%d piste(s) trouvée(s) — identification…", toc.tracks().size()));
                identify();
            }
        }.execute();
    }

    /** Identification GROSSIÈRE (nombre + durée des pistes, voir MusicBrainzClient.lookupByToc())
     *  — sert uniquement à préremplir les titres affichés et détecter des doublons probables avant
     *  extraction. La vraie identification définitive de chaque fichier reste celle, bien plus
     *  fiable, du pipeline de taguage normal une fois les pistes extraites (voir la Javadoc de
     *  classe). Échec silencieux (identified reste null) : l'extraction reste possible sans titre
     *  connu à l'avance, juste sans le confort de l'aperçu ni la détection de doublon. */
    private void identify() {
        new SwingWorker<MusicBrainzClient.ReleaseTracklist, Void>() {
            @Override protected MusicBrainzClient.ReleaseTracklist doInBackground() {
                try {
                    List<Integer> durations = new ArrayList<>();
                    for (CdRipper.Track t : toc.tracks()) durations.add(t.durationSec());
                    MusicBrainzClient mb = new MusicBrainzClient();
                    List<MusicBrainzClient.DiscIdCandidate> candidates = mb.lookupByToc(durations);

                    int expectedSectors = 150;
                    for (CdRipper.Track t : toc.tracks()) expectedSectors += t.lengthSectors();

                    String bestMbid = null;
                    int bestDiff = Integer.MAX_VALUE;
                    for (var c : candidates) {
                        if (c.trackCount() != toc.tracks().size()) continue;
                        int diff = Math.abs(c.sectors() - expectedSectors);
                        if (diff < bestDiff) { bestDiff = diff; bestMbid = c.releaseMbid(); }
                    }
                    if (bestMbid == null) return null;
                    return mb.lookupRelease(bestMbid);
                } catch (Exception e) {
                    return null;
                }
            }
            @Override protected void done() {
                try { identified = get(); } catch (Exception ignored) {}
                populateTable();
            }
        }.execute();
    }

    private void populateTable() {
        trackTableModel.setRowCount(0);
        for (CdRipper.Track t : toc.tracks()) {
            String title  = I18n.t("(piste %d)", t.number());
            String artist = "";
            if (identified != null) {
                for (var rt : identified.tracks()) {
                    if (rt.trackNo() == t.number()) { title = rt.title(); artist = rt.artist(); break; }
                }
            }
            boolean dup = identified != null && isAlreadyInLibrary(artist, identified.album(), title);
            trackTableModel.addRow(new Object[]{
                    Boolean.TRUE, t.number(), title, formatDuration(t.durationSec()), dup ? I18n.t("oui") : "", ""
            });
        }
        centerCards.show(centerPanel, CARD_TABLE);
        setAlbumHeader(identified);
        lblStatus.setText(identified != null
                ? I18n.t("Identifié : %s – %s", identified.albumArtist(), identified.album())
                : I18n.t("Non identifié — les pistes seront extraites sans titre, "
                       + "à identifier ensuite normalement."));
        btnExtract.setEnabled(true);
    }

    /** Correspondance approximative (album OU titre+artiste) sur les fichiers déjà chargés — pas
     *  une requête base de données, juste un parcours en mémoire (déjà rapide même sur 200k+
     *  entrées, aucune E/S). Suffisant pour avertir "tu as peut-être déjà ça", pas une garantie
     *  absolue — l'utilisateur tranche via la confirmation avant extraction. */
    private boolean isAlreadyInLibrary(String artist, String album, String title) {
        if (album.isBlank() && title.isBlank()) return false;
        for (FileEntry fe : owner.allEntries()) {
            var t = fe.activeTags();
            if (t == null) continue;
            if (!album.isBlank() && album.equalsIgnoreCase(t.album)) return true;
            if (!title.isBlank() && title.equalsIgnoreCase(t.title)
                    && (artist.isBlank() || artist.equalsIgnoreCase(t.artist))) return true;
        }
        return false;
    }

    private static String formatDuration(int sec) {
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    /** Titre en gras + sous-titre discret (année/nb pistes) — vide (masqué) tant qu'aucune
     *  identification n'a abouti, plutôt qu'un espace réservé toujours visible mais vide. */
    private void setAlbumHeader(MusicBrainzClient.ReleaseTracklist rt) {
        if (rt == null) {
            lblAlbumHeader.setText(" ");
            return;
        }
        String year = rt.year() != null && !rt.year().isBlank() ? " (" + rt.year() + ")" : "";
        lblAlbumHeader.setText(I18n.t("<html>%s — <span style='font-weight:normal'>%s</span>%s</html>",
                escapeHtml(rt.albumArtist()), escapeHtml(rt.album()), escapeHtml(year)));
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Renderer texte aligné à droite, couleur optionnelle (ex. rouge pour signaler un doublon) —
     *  utilisé pour les colonnes numériques/courtes (#, Durée, Déjà présent ?). */
    private static DefaultTableCellRenderer rightAligned(Color colorWhenNonBlank) {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                String s = v == null ? "" : v.toString();
                if (colorWhenNonBlank != null) setForeground(!sel && !s.isBlank() ? colorWhenNonBlank : t.getForeground());
                return c;
            }
        };
    }

    /** Renderer coloré pour la colonne Statut pendant l'extraction — vert pour un succès, rouge pour
     *  un échec, couleur neutre pour "en cours" ou avant toute extraction (case vide). Même palette
     *  que le reste de l'appli pour ce genre d'indicateur (voir CompilationMatchDialog, DetailPanel). */
    private static DefaultTableCellRenderer statusRenderer() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                String s = v == null ? "" : v.toString();
                Color fg = t.getForeground();
                if (s.startsWith("✔")) fg = new Color(0x66bb6a);
                else if (s.startsWith("✗")) fg = new Color(0xcc4444);
                else if (s.startsWith("⏳")) fg = new Color(0x90A4AE);
                setForeground(sel ? t.getSelectionForeground() : fg);
                return c;
            }
        };
    }

    /** Un événement de progression publié depuis le thread d'extraction — (ligne du tableau à
     *  mettre à jour, nouveau texte de la colonne Statut, nombre de pistes terminées jusqu'ici). */
    private record RipProgress(int row, String status, int done) {}

    private void extract() {
        List<Integer> selected = new ArrayList<>();
        // row ↔ numéro de piste, pour retrouver la bonne ligne à mettre à jour pendant l'extraction
        // sans dépendre de l'ordre d'itération (le tableau reste dans l'ordre du TOC, mais autant
        // ne pas supposer un rapport 1:1 entre "selected" et les lignes si jamais une piste est
        // décochée entre-temps — impossible ici puisque les cases sont lues une seule fois avant de
        // désactiver la fenêtre, mais la map reste correcte même si ce contrat change plus tard).
        java.util.Map<Integer, Integer> rowByTrackNo = new java.util.HashMap<>();
        boolean anyDup = false;
        for (int i = 0; i < trackTableModel.getRowCount(); i++) {
            int trackNo = (Integer) trackTableModel.getValueAt(i, 1);
            rowByTrackNo.put(trackNo, i);
            if (!Boolean.TRUE.equals(trackTableModel.getValueAt(i, 0))) continue;
            selected.add(trackNo);
            if (I18n.t("oui").equals(trackTableModel.getValueAt(i, 4))) anyDup = true;
        }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.t("Aucune piste cochée."));
            return;
        }
        if (anyDup) {
            int ok = JOptionPane.showConfirmDialog(this,
                    I18n.t("Certaines pistes cochées semblent déjà présentes dans la bibliothèque.\n"
                         + "Extraire quand même (créera potentiellement un doublon) ?"),
                    I18n.t("Doublons détectés"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ok != JOptionPane.YES_OPTION) return;
        }

        AudioTranscoder.Format format = (AudioTranscoder.Format) cbFormat.getSelectedItem();
        int bitrate = Config.get().num("transcode.bitrate_kbps", 320);
        Path stagingDir = Paths.get(System.getProperty("user.home"), ".opentagger", "cd-import",
                String.valueOf(System.currentTimeMillis()));

        btnExtract.setEnabled(false);
        btnDetect.setEnabled(false);
        btnDataDisc.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setMaximum(selected.size());
        progressBar.setString("0 / " + selected.size());
        lblProgress.setText(I18n.t("Piste %d/%d", 0, selected.size()));

        new SwingWorker<int[], RipProgress>() {
            @Override protected int[] doInBackground() {
                CdRipper ripper = new CdRipper();
                AudioTranscoder transcoder = new AudioTranscoder();
                int ok = 0, fail = 0, doneCount = 0;
                for (int trackNo : selected) {
                    int row = rowByTrackNo.getOrDefault(trackNo, -1);
                    publish(new RipProgress(row, I18n.t("⏳ Extraction…"), doneCount));
                    try {
                        Path wav = ripper.ripTrackToWav(trackNo, stagingDir);
                        publish(new RipProgress(row, I18n.t("⏳ Conversion…"), doneCount));
                        // Format cible TOUJOURS ≠ wav (AudioTranscoder.Format n'a pas d'entrée WAV,
                        // ripTrackToWav() produit forcément un .wav) — la conversion s'applique donc
                        // systématiquement ici. Échec de conversion : on garde le WAV brut plutôt que
                        // de perdre la piste (transcode() lève une exception mais ne supprime jamais
                        // la source avant que le fichier produit soit confirmé non vide).
                        try { transcoder.transcode(wav, format, bitrate, true); }
                        catch (Exception ex) { /* garde le WAV si la conversion échoue — jamais rien perdre */ }
                        ok++;
                        doneCount++;
                        publish(new RipProgress(row, I18n.t("✔ Extrait"), doneCount));
                    } catch (Exception ex) {
                        fail++;
                        doneCount++;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        publish(new RipProgress(row, I18n.t("✗ Échec : %s", msg), doneCount));
                    }
                }
                return new int[]{ok, fail};
            }
            @Override protected void process(List<RipProgress> chunks) {
                for (RipProgress p : chunks) {
                    if (p.row() >= 0 && p.row() < trackTableModel.getRowCount())
                        trackTableModel.setValueAt(p.status(), p.row(), COL_STATUS);
                    progressBar.setValue(p.done());
                    progressBar.setString(p.done() + " / " + selected.size());
                    lblProgress.setText(I18n.t("Piste %d/%d", Math.min(p.done() + 1, selected.size()), selected.size()));
                }
            }
            @Override protected void done() {
                int[] r;
                try { r = get(); } catch (Exception ex) { r = new int[]{0, selected.size()}; }
                lblStatus.setText(I18n.t("%d piste(s) extraite(s), %d échec(s).", r[0], r[1]));
                lblProgress.setText(" ");
                progressBar.setVisible(false);
                btnExtract.setEnabled(true);
                btnDetect.setEnabled(true);
                btnDataDisc.setEnabled(true);
                if (r[0] > 0 && Files.isDirectory(stagingDir)) {
                    owner.importFolder(stagingDir.toFile());
                    JOptionPane.showMessageDialog(CdImportDialog.this,
                            I18n.t("%d piste(s) extraite(s) vers %s — chargées dans la bibliothèque "
                                 + "pour identification/renommage normal.", r[0], stagingDir),
                            I18n.t("Extraction terminée"), JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }

    // ── CD de données (copie de fichiers, pas d'extraction audio) ──────────────────────────────

    private void importDataDisc() {
        JFileChooser fcSrc = new JFileChooser();
        fcSrc.setDialogTitle(I18n.t("Sélectionne le dossier monté du CD de données"));
        fcSrc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fcSrc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File source = fcSrc.getSelectedFile();

        JFileChooser fcDest = new JFileChooser();
        fcDest.setDialogTitle(I18n.t("Copier vers…"));
        fcDest.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fcDest.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dest = fcDest.getSelectedFile();

        lblStatus.setText(I18n.t("Copie en cours…"));
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws IOException {
                return copyRecursive(source.toPath(), dest.toPath());
            }
            @Override protected void done() {
                int n;
                try { n = get(); } catch (Exception ex) { n = 0; }
                lblStatus.setText(I18n.t("%d fichier(s) copié(s).", n));
            }
        }.execute();
    }

    private static int copyRecursive(Path src, Path destRoot) throws IOException {
        int[] count = {0};
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path rel = src.relativize(file);
                Path target = destRoot.resolve(rel);
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                count[0]++;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }
}
