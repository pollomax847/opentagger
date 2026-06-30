package com.opentagger.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.opentagger.AudioScanner;
import com.opentagger.Config;
import com.opentagger.FileRenamer;
import com.opentagger.MetadataCache;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MainFrame extends JFrame {

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(MainFrame.class.getName());

    // ── Palette ──────────────────────────────────────────────────────────────
    private static final Color COL_TAGGED     = new Color(40,  180, 100, 55);
    private static final Color COL_ERROR      = new Color(220, 60,  60,  55);
    private static final Color COL_PROCESSING = new Color(60,  130, 255, 55);
    private static final Color COL_SKIPPED    = new Color(200, 150, 30,  45);
    private static final Color ACCENT         = new Color(0x4DB6AC); // teal
    private static final Color HEADER_BG      = new Color(0x1E1F22);

    // ── État ─────────────────────────────────────────────────────────────────
    private final FileTableModel tableModel = new FileTableModel();
    private TaggingWorker           worker;
    private AlbumCompletionWorker   completionWorker;
    private InfoCompleterWorker     infoCompleter;
    private int                     currentMask = Config.get().defaultRenameMask();

    // ── Composants header ────────────────────────────────────────────────────
    private JButton    btnTagAll, btnTagSel, btnCancel, btnTranscode, btnRefresh;
    private JCheckBox  chkAcoustId;
    private JLabel     lblMask;

    // ── Stats live ───────────────────────────────────────────────────────────
    private JLabel     lblStatTotal, lblStatTagged, lblStatSkipped,
                       lblStatError, lblStatPending;

    // ── Table + tri/filtre ────────────────────────────────────────────────────
    private JTable                          table;
    private TableRowSorter<FileTableModel>  rowSorter;
    private JTextField                      tfFilter;
    private JComboBox<String>               cbFilterField;
    private JComboBox<String>               cbFilterStatus;

    // ── Panneau de détail ─────────────────────────────────────────────────────
    private DetailPanel detailPanel;
    private JLabel      lblFilePath;
    private JLabel      lblCoverImg;

    // ── Undo / Redo ───────────────────────────────────────────────────────────
    private final com.opentagger.UndoManager undoManager = new com.opentagger.UndoManager();
    private JButton btnUndo, btnRedo;

    // ── Barre de statut ───────────────────────────────────────────────────────
    private JLabel       lblStatus;
    private JProgressBar progress;

    // ── Throttle stats (évite O(n²) sur 100k+ fichiers) ──────────────────────
    private volatile long lastStatsRefreshMs = 0;

    // ── Bandeau de chargement dossiers (Jaikoz-style) ─────────────────────────
    private final JPanel scanBanner  = new JPanel();
    private final JPanel scanEntries = new JPanel();
    private int          activeScanCount = 0;
    // Workers de scan actifs — permettent l'annulation
    private final java.util.List<SwingWorker<?,?>> activeScanWorkers = new java.util.ArrayList<>();

    // Surveillance automatique des dossiers chargés (WatchService)
    private com.opentagger.FolderWatcher folderWatcher;

    // ── Entrée publique ───────────────────────────────────────────────────────

    public static void launch(java.io.File[] initialDirs) {
        FlatDarkLaf.setup();
        UIManager.put("Table.alternateRowColor", new Color(45, 47, 52));
        SwingUtilities.invokeLater(() ->
            SplashScreen.show(() -> SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
                if (initialDirs != null && initialDirs.length > 0)
                    frame.loadFiles(initialDirs);
            }))
        );
    }

    public MainFrame() {
        super("OpenTagger  " + Config.get().str("app.version", "0.1.0"));
        // installDragDrop doit précéder buildUI : buildMainSplit() appelle
        // table.setTransferHandler(getTransferHandler()) — sans handler préalable
        // la table reçoit null et le drag-drop ne fonctionne pas sur la zone principale.
        installDragDrop();
        buildUI();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Construction
    // ═══════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        setIconImages(AppIcon.all());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 600));
        restoreWindowGeometry();  // taille/position sauvegardées, ou 85% écran par défaut
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                saveWindowGeometry();
                if (folderWatcher != null) try { folderWatcher.close(); } catch (Exception ignored) {}
            }
        });

        // Initialiser le FolderWatcher (auto-détection des nouveaux fichiers)
        try {
            folderWatcher = new com.opentagger.FolderWatcher(p -> SwingUtilities.invokeLater(() -> {
                java.io.File f = p.toFile();
                // Vérifier que ce fichier n'est pas déjà dans la table
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    if (tableModel.get(i).file.equals(f)) return;
                }
                com.opentagger.model.FileEntry e = new com.opentagger.model.FileEntry(f, new com.opentagger.model.TagInfo());
                // Déterminer la racine : trouver le scanRoot du dossier parent
                Path parent = p.getParent();
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    com.opentagger.model.FileEntry ex = tableModel.get(i);
                    if (ex.scanRoot != null && parent.startsWith(ex.scanRoot)) { e.scanRoot = ex.scanRoot; break; }
                }
                tableModel.add(e);
                // Lire les tags en arrière-plan
                new SwingWorker<com.opentagger.model.TagInfo, Void>() {
                    @Override protected com.opentagger.model.TagInfo doInBackground() { return readTags(f); }
                    @Override protected void done() {
                        try { e.current = get(); tableModel.update(e); refreshStats(); } catch (Exception ignored) {}
                    }
                }.execute();
                setStatus("Nouveau fichier détecté : " + f.getName());
            }));
            folderWatcher.start();
        } catch (Exception ex) {
            LOG.warning("FolderWatcher non disponible : " + ex.getMessage());
        }
        setLayout(new BorderLayout(0, 0));

        setJMenuBar(buildMenuBar());

        // ── Header + stats + filtre ─────────────────────────────────────────
        JPanel topArea = new JPanel(new BorderLayout(0, 0));
        topArea.add(buildHeader(),    BorderLayout.NORTH);
        topArea.add(buildStatsStrip(), BorderLayout.CENTER);
        JPanel filterAndScan = new JPanel(new BorderLayout(0, 0));
        filterAndScan.add(buildFilterBar(),  BorderLayout.NORTH);
        filterAndScan.add(buildScanBanner(), BorderLayout.SOUTH);
        topArea.add(filterAndScan, BorderLayout.SOUTH);
        add(topArea, BorderLayout.NORTH);

        // ── Split horizontal : table gauche | détail droit ──────────────────
        add(buildMainSplit(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshDetail();
        });

        // ── Undo/Redo clavier ─────────────────────────────────────────────
        var rootMap   = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actionMap = getRootPane().getActionMap();
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "undo");
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "redo");
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo");
        actionMap.put("undo", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { performUndo(); }
        });
        actionMap.put("redo", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { performRedo(); }
        });

        // F5 = rafraîchir les dossiers chargés
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F5, 0), "refresh");
        actionMap.put("refresh", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { refreshFolders(); }
        });

        // F6 = tagger tous les fichiers cochés
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F6, 0), "tagAll");
        actionMap.put("tagAll", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { startTagging(false); }
        });

        // F7 = tagger la sélection
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F7, 0), "tagSel");
        actionMap.put("tagSel", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { startTagging(true); }
        });

        // Suppr = retirer les lignes sélectionnées de la liste
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "removeRows");
        actionMap.put("removeRows", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table == null) return;
                int[] rows = table.getSelectedRows();
                for (int i = rows.length - 1; i >= 0; i--)
                    tableModel.remove(table.convertRowIndexToModel(rows[i]));
            }
        });

        // Ctrl+F = focus sur le filtre
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focusFilter");
        actionMap.put("focusFilter", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (tfFilter != null) { tfFilter.requestFocusInWindow(); tfFilter.selectAll(); }
            }
        });

        // Ctrl+M = correspondance manuelle
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "matchDialog");
        actionMap.put("matchDialog", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openMatchDialog(); }
        });

        // Ctrl+O = ouvrir dossier
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "openFolder");
        actionMap.put("openFolder", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openFolder(); }
        });

        // Ctrl+E = export CSV
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "exportCsv");
        actionMap.put("exportCsv", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { exportCsv(); }
        });

        // Ctrl+R = renommer
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "rename");
        actionMap.put("rename", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { renameTagged(); }
        });

        // Ctrl+L = compléter les albums
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_L,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "completeAlbums");
        actionMap.put("completeAlbums", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { completeAlbums(); }
        });

        // Ctrl+T = transcoder
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "transcode");
        actionMap.put("transcode", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { transcodeFiles(false); }
        });

        undoManager.addListener(this::updateUndoButtons);
    }

    // ── Menu bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.add(buildMenuFichier());
        mb.add(buildMenuEdition());
        mb.add(buildMenuTagger());
        mb.add(buildMenuOutils());
        return mb;
    }

    private JMenu buildMenuFichier() {
        JMenu m = new JMenu("Fichier");
        m.setMnemonic('F');
        m.add(mitem("Ouvrir un dossier…",       "Ctrl+O",  e -> openFolder()));
        m.add(mitem("↺ Rafraîchir les dossiers","F5",       e -> refreshFolders()));
        m.add(mitem("Vider la liste",            "Ctrl+W",  e -> clearFileList()));
        m.addSeparator();
        m.add(mitem("Exporter CSV…",            "Ctrl+E",  e -> exportCsv()));
        m.add(mitem("Exporter playlist M3U…",  null,      e -> exportPlaylist("m3u")));
        m.add(mitem("Exporter playlist XSPF…", null,      e -> exportPlaylist("xspf")));
        m.addSeparator();
        m.add(mitem("Quitter",                 null,      e -> System.exit(0)));
        return m;
    }

    private void clearFileList() {
        if (worker != null && !worker.isDone()) {
            JOptionPane.showMessageDialog(this, "Arrêtez le taguage avant de vider la liste.",
                "Taguage en cours", JOptionPane.WARNING_MESSAGE);
            return;
        }
        tableModel.clear();
        if (folderWatcher != null) folderWatcher.clearAll();
        btnRefresh.setEnabled(false);
        refreshStats();
        setStatus("Liste vidée.");
    }

    /**
     * Rescanne tous les dossiers racines déjà chargés pour détecter les nouveaux fichiers.
     * Les fichiers déjà présents dans la table sont ignorés (pas de doublons).
     * Les fichiers supprimés du disque restent dans la table (pas de suppression automatique).
     */
    /**
     * Rafraîchit les métadonnées et la pochette de la sélection en utilisant
     * les MBIDs déjà présents dans les fichiers, sans ré-identification complète.
     *  - recordingMbid → lookup MusicBrainz pour tags frais
     *  - releaseMbid   → Cover Art Archive pour pochette fraîche
     */
    private void refreshSelectedMeta() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { setStatus("Sélectionnez d'abord des fichiers."); return; }

        java.util.List<com.opentagger.model.FileEntry> targets = new java.util.ArrayList<>();
        for (int r : rows) {
            com.opentagger.model.FileEntry e = tableModel.get(table.convertRowIndexToModel(r));
            com.opentagger.model.TagInfo   ti = e.activeTags();
            if (!ti.recordingMbid.isBlank() || !ti.releaseMbid.isBlank()) targets.add(e);
        }
        if (targets.isEmpty()) {
            setStatus("Aucun fichier sélectionné n'a de MBID — faites d'abord un taguage.");
            return;
        }

        setStatus("Rafraîchissement de " + targets.size() + " fichier(s)…");

        new SwingWorker<Void, com.opentagger.model.FileEntry>() {
            final com.opentagger.MusicBrainzClient mbClient = new com.opentagger.MusicBrainzClient();
            final com.opentagger.CaaClient         caa      = new com.opentagger.CaaClient();
            final com.opentagger.TagWriter         writer   = new com.opentagger.TagWriter();
            int done = 0;

            @Override protected Void doInBackground() throws Exception {
                for (com.opentagger.model.FileEntry e : targets) {
                    com.opentagger.model.TagInfo current = e.activeTags();

                    // 1. Tags frais depuis MB via recordingMbid
                    if (!current.recordingMbid.isBlank()) {
                        try {
                            com.opentagger.model.TagInfo fresh = mbClient.lookupRecording(current.recordingMbid);
                            if (fresh != null) {
                                // Ne mettre à jour que les champs clés — ne pas écraser les données manuelles
                                if (!fresh.title.isBlank())       current.title       = fresh.title;
                                if (!fresh.artist.isBlank())      current.artist      = fresh.artist;
                                if (!fresh.albumArtist.isBlank()) current.albumArtist = fresh.albumArtist;
                                if (!fresh.album.isBlank())       current.album       = fresh.album;
                                if (!fresh.year.isBlank())        current.year        = fresh.year;
                                if (!fresh.track.isBlank())       current.track       = fresh.track;
                                if (!fresh.trackTotal.isBlank())  current.trackTotal  = fresh.trackTotal;
                                if (!fresh.releaseMbid.isBlank()) current.releaseMbid = fresh.releaseMbid;
                                if (!fresh.releaseGroupMbid.isBlank()) current.releaseGroupMbid = fresh.releaseGroupMbid;
                                e.result = current;
                                e.status = com.opentagger.model.FileEntry.Status.TAGGED;
                            }
                        } catch (Exception ignored) {}
                        try { Thread.sleep(1100); } catch (InterruptedException ie) { break; } // MB rate-limit
                    }

                    // 2. Pochette fraîche depuis CAA via releaseMbid
                    if (!current.releaseMbid.isBlank()) {
                        try {
                            java.nio.file.Path img = caa.downloadFront(current);
                            if (img != null) {
                                writer.writeCoverOnly(e.file, img);
                                java.nio.file.Files.deleteIfExists(img);
                            }
                        } catch (Exception ignored) {}
                    }

                    done++;
                    publish(e);
                }
                return null;
            }

            @Override protected void process(java.util.List<com.opentagger.model.FileEntry> chunks) {
                for (com.opentagger.model.FileEntry e : chunks) tableModel.update(e);
                setStatus("Rafraîchissement : " + done + "/" + targets.size() + "…");
            }

            @Override protected void done() {
                refreshStats();
                setStatus("Rafraîchissement terminé — " + done + " fichier(s) mis à jour.");
            }
        }.execute();
    }

    private void refreshFolders() {
        // Collecter les racines uniques de tous les fichiers chargés
        java.util.LinkedHashSet<File> roots = new java.util.LinkedHashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            com.opentagger.model.FileEntry e = tableModel.get(i);
            if (e.scanRoot != null) roots.add(e.scanRoot.toFile());
            else if (e.file != null) roots.add(e.file.getParentFile());
        }
        if (roots.isEmpty()) {
            setStatus("Aucun dossier chargé à rafraîchir.");
            return;
        }
        setStatus("Rafraîchissement de " + roots.size() + " dossier(s)…");
        for (File root : roots) loadDirectory(root);
    }

    private JMenu buildMenuEdition() {
        JMenu m = new JMenu("Édition");
        m.setMnemonic('E');
        // Références gardées pour enable/disable dynamique
        JMenuItem miUndo = mitem("Annuler",    "Ctrl+Z",  e -> performUndo());
        JMenuItem miRedo = mitem("Rétablir",   "Ctrl+Y",  e -> performRedo());
        m.add(miUndo); m.add(miRedo);
        m.addSeparator();
        m.add(mitem("Tout cocher",             null,      e -> setAllSelected(true)));
        m.add(mitem("Tout décocher",           null,      e -> setAllSelected(false)));
        m.add(mitem("Retirer la sélection",    null,  e -> {
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--)
                tableModel.remove(table.convertRowIndexToModel(rows[i]));
        }));
        m.addSeparator();
        m.add(mitem("Sélectionner les non identifiés", null, e -> selectByStatus(FileEntry.Status.SKIPPED)));
        m.add(mitem("Sélectionner les erreurs",        null, e -> selectByStatus(FileEntry.Status.ERROR)));
        m.add(mitem("Sélectionner les en attente",     null, e -> selectByStatus(FileEntry.Status.PENDING)));
        m.addSeparator();
        m.add(mitem("Préférences…",            "Ctrl+Virgule", e -> new SettingsDialog(this, this::loadFiles).setVisible(true)));
        return m;
    }

    private JMenu buildMenuTagger() {
        JMenu m = new JMenu("Tagger");
        m.setMnemonic('T');
        m.add(mitem("Tout tagger (cochés)",    "F6",  e -> startTagging(false)));
        m.add(mitem("Tagger la sélection",     "F7",  e -> startTagging(true)));
        m.addSeparator();
        m.add(mitem("Arrêter",                 null,  e -> cancelTagging()));
        m.addSeparator();
        m.add(mitem("Renommer les fichiers tagués", "Ctrl+R", e -> renameTagged()));
        m.add(mitem("Organiser en dossiers…",  "Ctrl+G", e -> organizeFiles()));
        m.add(mitem("Choisir le masque…",      null,      e -> chooseMask()));
        m.addSeparator();
        m.add(mitem("Compléter les albums…",   "Ctrl+L", e -> completeAlbums()));
        m.addSeparator();
        m.add(mitem("Transcoder les fichiers…",   "Ctrl+T", e -> transcodeFiles(false)));
        m.add(mitem("Transcoder la sélection…",   null,     e -> transcodeFiles(true)));
        return m;
    }

    private JMenu buildMenuOutils() {
        JMenu m = new JMenu("Outils");
        m.setMnemonic('O');
        m.add(mitem("Correspondance manuelle…","Ctrl+M",  e -> openMatchDialog()));
        m.add(mitem("Gérer la pochette…",      null,      e -> openCoverDialog()));
        m.addSeparator();
        m.add(mitem("Forcer le re-taguage…",    null,      e -> forceRetag()));
        m.add(mitem("Passe complète…",          "Ctrl+P",  e -> completeAllInfo()));
        m.addSeparator();
        m.add(mitem("Détecter les doublons…",  null,      e -> detectDuplicates()));
        m.add(mitem("Supprimer les fichiers illisibles…", null, e -> deleteErrorFiles()));
        m.add(mitem("Historique de taguage…",  null,      e -> new HistoryDialog(this).setVisible(true)));
        m.addSeparator();
        m.add(mitem("Modifier sur MusicBrainz",null,      e -> openMbEditPage()));
        m.add(mitem("Contribuer à MusicBrainz…", null,   e -> openMbContribute()));
        m.add(mitem("Soumettre fingerprint AcoustID", null, e -> submitAcoustId()));
        return m;
    }

    private JMenuItem mitem(String label, String shortcut, java.awt.event.ActionListener al) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(al);
        if (shortcut != null) {
            KeyStroke ks = KeyStroke.getKeyStroke(shortcut.replace("Ctrl+", "control ").replace("Virgule", "COMMA"));
            if (ks != null) mi.setAccelerator(ks);
        }
        return mi;
    }

    // ── Barre d'outils (actions principales seulement) ───────────────────────

    private JPanel buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBackground(HEADER_BG);
        bar.setBorder(new EmptyBorder(0, 0, 0, 0));

        // ── Branding gauche ──────────────────────────────────────────────────
        JLabel logo = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT);
                g2.fillRoundRect(6, 6, 32, 32, 8, 8);
                g2.setColor(HEADER_BG);
                g2.setFont(new Font("SansSerif", Font.BOLD, 14));
                FontMetrics fm = g2.getFontMetrics();
                String s = "OT";
                g2.drawString(s, 6 + (32 - fm.stringWidth(s))/2, 6 + (32 + fm.getAscent() - fm.getDescent())/2);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() { return new Dimension(44, 44); }
        };
        JLabel appName = new JLabel("OpenTagger");
        appName.setForeground(new Color(0xE0E0E0));
        appName.putClientProperty("FlatLaf.style", "font: bold 15 $defaultFont");
        appName.setBorder(new EmptyBorder(0, 0, 0, 18));

        JPanel brandPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        brandPanel.setBackground(HEADER_BG);
        brandPanel.setBorder(new EmptyBorder(4, 8, 4, 0));
        brandPanel.add(logo);
        brandPanel.add(appName);

        // ── Actions centre ───────────────────────────────────────────────────
        JButton btnOpen = accentBtn("Ouvrir dossier", "Ctrl+O");
        btnOpen.addActionListener(e -> openFolder());

        btnRefresh   = headerBtn("↺ Rafraîchir", "Rescanner les dossiers chargés pour détecter les nouveaux fichiers (F5)");
        btnTagAll    = headerBtn("Tout tagger", "Tagger tous les fichiers cochés (F6)");
        btnTagSel    = headerBtn("Tagger la sélection", "Tagger les lignes sélectionnées (F7)");
        btnCancel    = headerBtn("Arrêter", "Annuler le traitement en cours");
        btnTranscode = headerBtn("Transcoder", "Transcoder les fichiers sélectionnés (Ctrl+T)");
        btnRefresh.addActionListener(e -> refreshFolders());
        btnTagAll.addActionListener(e -> startTagging(false));
        btnTagSel.addActionListener(e -> startTagging(true));
        btnCancel.addActionListener(e -> cancelTagging());
        btnTranscode.addActionListener(e -> transcodeFiles(false));
        btnCancel.setEnabled(false);
        btnRefresh.setEnabled(false);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        actionsPanel.setBackground(HEADER_BG);
        actionsPanel.add(btnOpen);
        actionsPanel.add(btnRefresh);
        actionsPanel.add(vSep());
        actionsPanel.add(btnTagAll);
        actionsPanel.add(btnTagSel);
        actionsPanel.add(btnCancel);
        actionsPanel.add(vSep());
        actionsPanel.add(btnTranscode);

        // ── Droite : undo/redo (boutons conservés pour updateUndoButtons) ────
        btnUndo = iconBtn("↩", "Annuler (Ctrl+Z)");
        btnRedo = iconBtn("↪", "Rétablir (Ctrl+Y)");
        btnUndo.addActionListener(e -> performUndo());
        btnRedo.addActionListener(e -> performRedo());
        btnUndo.setEnabled(false);
        btnRedo.setEnabled(false);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        rightPanel.setBackground(HEADER_BG);

        bar.add(brandPanel,  BorderLayout.WEST);
        bar.add(actionsPanel, BorderLayout.CENTER);
        bar.add(rightPanel,  BorderLayout.EAST);

        // Ligne de séparation basse
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(HEADER_BG);
        wrapper.add(bar, BorderLayout.CENTER);
        JPanel sep = new JPanel();
        sep.setBackground(ACCENT.darker().darker());
        sep.setPreferredSize(new Dimension(0, 2));
        wrapper.add(sep, BorderLayout.SOUTH);
        return wrapper;
    }

    private JButton accentBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.putClientProperty("FlatLaf.style",
            "background: #4DB6AC; foreground: #1E1F22; font: bold 12 $defaultFont");
        return b;
    }

    private JButton headerBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.putClientProperty("FlatLaf.style", "foreground: #CFD8DC");
        return b;
    }

    private JButton iconBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(34, 28));
        return b;
    }

    private JButton btn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        return b;
    }

    private JSeparator vSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 22));
        s.setForeground(new Color(0x3A3B3E));
        return s;
    }

    private Dimension dim(int w) { return new Dimension(w, 0); }

    // ── Bande de statistiques live ────────────────────────────────────────────

    private JPanel buildStatsStrip() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        p.setBackground(new Color(0x252527));
        p.setBorder(new MatteBorder(0, 0, 1, 0, new Color(0x3A3B3E)));

        lblStatTotal   = statChip("Total",        "0",  new Color(0x78909C));
        lblStatTagged  = statChip("Tagués",        "0",  new Color(0x4CAF50));
        lblStatSkipped = statChip("Non identifiés","0",  new Color(0xFFA726));
        lblStatError   = statChip("Erreurs",       "0",  new Color(0xEF5350));
        lblStatPending = statChip("En attente",    "0",  new Color(0x90A4AE));

        p.add(new JLabel("  "));
        p.add(lblStatTotal);
        p.add(sep3());
        p.add(lblStatTagged);
        p.add(lblStatSkipped);
        p.add(lblStatError);
        p.add(lblStatPending);
        return p;
    }

    private JLabel statChip(String label, String val, Color color) {
        JLabel l = new JLabel(label + "  " + val);
        l.setForeground(color);
        l.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
        l.setBorder(new CompoundBorder(
            new LineBorder(color.darker(), 1, true),
            new EmptyBorder(2, 7, 2, 7)));
        return l;
    }

    private JLabel sep3() {
        JLabel l = new JLabel("  ");
        return l;
    }

    private void refreshStats() {
        int total = 0, tagged = 0, skipped = 0, error = 0, pending = 0;
        // Compter depuis la VUE filtrée (table.getRowCount) plutôt que le modèle
        // pour que les chips reflètent toujours ce que l'utilisateur voit.
        int viewRows = (table != null) ? table.getRowCount() : tableModel.getRowCount();
        for (int viewRow = 0; viewRow < viewRows; viewRow++) {
            int modelRow = (table != null)
                    ? table.convertRowIndexToModel(viewRow) : viewRow;
            total++;
            switch (tableModel.get(modelRow).status) {
                case TAGGED     -> tagged++;
                case SKIPPED    -> skipped++;
                case ERROR      -> error++;
                default         -> pending++;
            }
        }
        // Si un filtre est actif, afficher "N / total_modèle"
        int modelTotal = tableModel.getRowCount();
        String totalText = (table != null && rowSorter.getRowFilter() != null && total != modelTotal)
                ? total + " / " + modelTotal : String.valueOf(total);
        lblStatTotal  .setText("Total  "          + totalText);
        lblStatTagged .setText("Tagués  "         + tagged);
        lblStatSkipped.setText("Non identifiés  " + skipped);
        lblStatError  .setText("Erreurs  "        + error);
        lblStatPending.setText("En attente  "     + pending);
    }

    // ── Split pane table / détail ─────────────────────────────────────────────

    private JSplitPane buildMainSplit() {
        // ── Table (gauche) ────────────────────────────────────────────────────
        table = new JTable(tableModel) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) return null;
                int mr = convertRowIndexToModel(row);
                return tableModel.getTooltip(mr);
            }
        };
        configureTable();
        installContextMenu();
        // Propager le TransferHandler de la fenêtre à la table
        table.setTransferHandler(getTransferHandler());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);

        // ── Panneau détail (droite) ───────────────────────────────────────────
        JPanel detail = buildDetailPanel();
        detail.setPreferredSize(new Dimension(360, 0));
        detail.setMinimumSize(new Dimension(280, 0));

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, detail);
        sp.setResizeWeight(0.68);
        sp.setDividerSize(4);
        sp.setBorder(null);
        return sp;
    }

    // ── Configuration de la table ─────────────────────────────────────────────

    private void configureTable() {
        table.setRowHeight(26);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        rowSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.getTableHeader().setReorderingAllowed(false);

        TableColumnModel cm = table.getColumnModel();
        colWidth(cm, 0, 30,  28,  30);   // ☑
        colWidth(cm, 1, 200, 110, 400);  // Fichier
        colWidth(cm, 2, 150, 70,  280);  // Artiste
        colWidth(cm, 3, 150, 70,  280);  // Artiste album
        colWidth(cm, 4, 180, 80,  340);  // Titre
        colWidth(cm, 5, 150, 60,  280);  // Album
        colWidth(cm, 6, 52,  36,  68);   // Année
        colWidth(cm, 7, 120, 50,  220);  // Genre
        colWidth(cm, 8, 44,  28,  60);   // Piste
        colWidth(cm, 9, 130, 80,  220);  // Statut

        // Renderer coloré
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    int mr = t.convertRowIndexToModel(row);
                    c.setBackground(rowBg(tableModel.get(mr).status, row));
                }
                return c;
            }
        };
        renderer.setBorder(new EmptyBorder(0, 7, 0, 7));

        for (int i = 1; i < cm.getColumnCount(); i++)
            cm.getColumn(i).setCellRenderer(renderer);
    }

    private Color rowBg(FileEntry.Status s, int row) {
        boolean alt = (row % 2 == 1);
        Color base  = UIManager.getColor(alt ? "Table.alternateRowColor" : "Table.background");
        if (base == null) base = new Color(43, 45, 48);
        return switch (s) {
            case TAGGED     -> blend(base, COL_TAGGED);
            case ERROR      -> blend(base, COL_ERROR);
            case PROCESSING -> blend(base, COL_PROCESSING);
            case SKIPPED    -> blend(base, COL_SKIPPED);
            default         -> base;
        };
    }

    private Color blend(Color bg, Color over) {
        float a = over.getAlpha() / 255f;
        return new Color(
            clamp((int)(bg.getRed()   * (1-a) + over.getRed()   * a)),
            clamp((int)(bg.getGreen() * (1-a) + over.getGreen() * a)),
            clamp((int)(bg.getBlue()  * (1-a) + over.getBlue()  * a)));
    }

    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private void colWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        TableColumn c = cm.getColumn(i);
        c.setPreferredWidth(p); c.setMinWidth(mn); c.setMaxWidth(mx);
    }

    // ── Menu contextuel ───────────────────────────────────────────────────────

    private void installContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem miTag    = new JMenuItem("⚡  Tagger ce fichier");
        JMenuItem miRename = new JMenuItem("✏  Renommer ce fichier");
        JMenuItem miReveal = new JMenuItem("📁  Ouvrir le dossier parent");
        JMenuItem miRemove = new JMenuItem("✗  Retirer de la liste");

        miTag.addActionListener(e -> startTagging(true));
        miRename.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
            if (entry.status == FileEntry.Status.TAGGED) {
                try {
                    Path np = new FileRenamer().rename(
                            entry.file.toPath(), entry.activeTags(), currentMask);
                    if (np != null) setStatus("Renommé → " + np.getFileName());
                } catch (Exception ex) { showError(ex.getMessage()); }
            } else { setStatus("Ce fichier n'a pas encore été tagué."); }
        });
        miReveal.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                try { Desktop.getDesktop().open(
                        tableModel.get(table.convertRowIndexToModel(row)).file.getParentFile()); }
                catch (Exception ignored) {}
            }
        });
        miRemove.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            // Supprimer de bas en haut pour garder les indices valides
            for (int i = rows.length - 1; i >= 0; i--) {
                int mr = table.convertRowIndexToModel(rows[i]);
                tableModel.remove(mr);
            }
        });

        JMenuItem miAcoustId = new JMenuItem("🎵  Soumettre fingerprint AcoustID");
        miAcoustId.addActionListener(e -> submitAcoustId());

        JMenuItem miMatch = new JMenuItem("🎯  Correspondance manuelle…");
        miMatch.addActionListener(e -> openMatchDialog());

        JMenuItem miCover = new JMenuItem("🖼  Gérer la pochette…");
        miCover.addActionListener(e -> openCoverDialog());

        JMenuItem miRefreshMeta = new JMenuItem("↺  Rafraîchir tags + pochette (sélection)");
        miRefreshMeta.addActionListener(e -> refreshSelectedMeta());

        JMenuItem miMbEdit = new JMenuItem("✏  Modifier sur MusicBrainz");
        miMbEdit.addActionListener(e -> openMbEditPage());

        JMenuItem miMbContrib = new JMenuItem("🤝  Contribuer à MusicBrainz…");
        miMbContrib.addActionListener(e -> openMbContribute());

        menu.add(miTag); menu.add(miRename); menu.addSeparator();
        menu.add(miMatch); menu.add(miCover);
        menu.add(miRefreshMeta); menu.addSeparator();
        menu.add(miMbEdit); menu.add(miMbContrib); menu.addSeparator();
        menu.add(miAcoustId); menu.addSeparator();
        menu.add(miReveal); menu.add(miRemove);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row))
                    table.setRowSelectionInterval(row, row);
                menu.show(table, e.getX(), e.getY());
            }
        });
    }

    // ── Panneau de détail ─────────────────────────────────────────────────────

    private JPanel buildDetailPanel() {
        // ── Pochette (cliquable, centrée en haut) ────────────────────────────
        lblCoverImg = new JLabel("—", SwingConstants.CENTER);
        lblCoverImg.setPreferredSize(new Dimension(140, 140));
        lblCoverImg.setMinimumSize (new Dimension(140, 140));
        lblCoverImg.setMaximumSize (new Dimension(140, 140));
        lblCoverImg.setBorder(new LineBorder(ACCENT.darker(), 1));
        lblCoverImg.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblCoverImg.setToolTipText("Double-clic pour gérer la pochette");
        lblCoverImg.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 11 $defaultFont");
        lblCoverImg.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openCoverDialog();
            }
        });

        // Titre de section "Pochette"
        JLabel coverTitle = new JLabel("POCHETTE");
        coverTitle.putClientProperty("FlatLaf.style",
            "foreground: #546E7A; font: bold 10 $defaultFont");

        JPanel coverSection = new JPanel();
        coverSection.setLayout(new BoxLayout(coverSection, BoxLayout.Y_AXIS));
        coverSection.setBorder(new EmptyBorder(14, 14, 8, 14));
        coverTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblCoverImg.setAlignmentX(Component.CENTER_ALIGNMENT);
        coverSection.add(coverTitle);
        coverSection.add(Box.createVerticalStrut(4));
        coverSection.add(lblCoverImg);

        // ── Chemin du fichier ────────────────────────────────────────────────
        lblFilePath = new JLabel(" ");
        lblFilePath.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 11 $defaultFont");
        lblFilePath.setBorder(new EmptyBorder(4, 14, 0, 14));

        // ── Séparateur accentué ──────────────────────────────────────────────
        JPanel accentLine = new JPanel();
        accentLine.setBackground(ACCENT);
        accentLine.setPreferredSize(new Dimension(0, 2));
        accentLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));

        // ── Titre section "MÉTADONNÉES" ───────────────────────────────────────
        JLabel metaTitle = new JLabel("  MÉTADONNÉES");
        metaTitle.putClientProperty("FlatLaf.style",
            "foreground: #546E7A; font: bold 10 $defaultFont; background: #252527");
        metaTitle.setOpaque(true);
        metaTitle.setBorder(new EmptyBorder(5, 0, 5, 0));

        // ── DetailPanel (champs de tags) ─────────────────────────────────────
        detailPanel = new DetailPanel();
        detailPanel.setOnApply(this::applyDetail);
        detailPanel.setOnCoverClick(this::openCoverDialog);

        // ── Assemblage ───────────────────────────────────────────────────────
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(coverSection);
        content.add(lblFilePath);
        content.add(Box.createVerticalStrut(6));
        content.add(accentLine);
        content.add(metaTitle);
        content.add(detailPanel);

        JScrollPane scroll = new JScrollPane(content,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(12);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new MatteBorder(0, 1, 0, 0, new Color(0x3A3B3E)));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void refreshDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { clearDetail(); return; }

        if (rows.length == 1) {
            // Mode fichier unique
            FileEntry e  = tableModel.get(table.convertRowIndexToModel(rows[0]));
            TagInfo   ti = e.activeTags();
            lblFilePath.setText("  " + (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath());
            detailPanel.populate(ti);
            if (e.result != null) detailPanel.highlightChanges(e.current);
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            loadCoverThumb(f);
        } else {
            // Mode multi-sélection — édition en lot
            List<TagInfo> tags = new ArrayList<>();
            for (int r : rows) tags.add(tableModel.get(table.convertRowIndexToModel(r)).activeTags());
            lblFilePath.setText("  " + rows.length + " fichiers sélectionnés — les champs vides ne seront pas modifiés");
            detailPanel.populateMulti(tags);
            lblCoverImg.setIcon(null); lblCoverImg.setText(rows.length + "");
        }
    }

    private void clearDetail() {
        lblFilePath.setText(" ");
        detailPanel.clear();
        lblCoverImg.setIcon(null); lblCoverImg.setText("—");
    }

    private void loadCoverThumb(File f) {
        lblCoverImg.setIcon(null); lblCoverImg.setText("…");
        detailPanel.clearCover();
        new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() {
                try {
                    AudioFile af = AudioFileIO.read(f);
                    Tag tag = af.getTag();
                    if (tag == null) return null;
                    Artwork art = tag.getFirstArtwork();
                    if (art == null) return null;
                    byte[] data = art.getBinaryData();
                    try (InputStream in = new java.io.ByteArrayInputStream(data)) {
                        return ImageIO.read(in);
                    }
                } catch (Exception ignore) { return null; }
            }
            @Override protected void done() {
                try {
                    BufferedImage img = get();
                    if (img != null) {
                        // Miniature 86×86 pour le haut du panneau
                        Image small = img.getScaledInstance(86, 86, Image.SCALE_SMOOTH);
                        lblCoverImg.setIcon(new ImageIcon(small));
                        lblCoverImg.setText("");
                        // Grande prévisualisation dans l'onglet Pochette
                        detailPanel.setCoverIcon(img);
                    } else {
                        lblCoverImg.setIcon(null);
                        lblCoverImg.setText("—");
                        detailPanel.clearCover();
                    }
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void applyDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;

        if (rows.length == 1) {
            // ── Fichier unique ─────────────────────────────────────────────
            int       mr   = table.convertRowIndexToModel(rows[0]);
            FileEntry e    = tableModel.get(mr);
            TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
            TagInfo   ti   = e.activeTags();
            detailPanel.collect(ti);
            refreshTableRow(mr, ti);
            markManuallyTagged(e, ti, mr);
            undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), "Modifier " + e.filename());
            writeTagsSafe(e, ti);
            setStatus("Tags sauvegardés — " + e.filename());
        } else {
            // ── Édition en lot ─────────────────────────────────────────────
            int saved = 0;
            for (int row : rows) {
                int       mr   = table.convertRowIndexToModel(row);
                FileEntry e    = tableModel.get(mr);
                TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                TagInfo   ti   = e.activeTags();
                detailPanel.collect(ti);
                refreshTableRow(mr, ti);
                markManuallyTagged(e, ti, mr);
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), "Lot " + e.filename());
                writeTagsSafe(e, ti);
                saved++;
            }
            setStatus("Tags sauvegardés — " + saved + " fichier(s).");
        }
    }

    // ── Correspondance manuelle ───────────────────────────────────────────────

    private void openMatchDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new MatchDialog(this, entry, tableModel, this::refreshDetail).setVisible(true);
    }

    // ── Gestion de la pochette ────────────────────────────────────────────────

    private void openCoverDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new CoverArtDialog(this, entry, this::refreshDetail).setVisible(true);
    }

    // ── MusicBrainz : modifier + contribuer ──────────────────────────────────

    private void openMbEditPage() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        TagInfo ti = tableModel.get(table.convertRowIndexToModel(row)).activeTags();
        String mbid = ti.recordingMbid;
        try {
            String url = mbid.isBlank()
                ? "https://musicbrainz.org/search?query=" +
                    java.net.URLEncoder.encode(ti.artist + " " + ti.title, java.nio.charset.StandardCharsets.UTF_8) +
                    "&type=recording"
                : "https://musicbrainz.org/recording/" + mbid;
            Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) { showError("Impossible d'ouvrir le navigateur : " + ex.getMessage()); }
    }

    private void openMbContribute() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new MbContributeDialog(this, entry).setVisible(true);
    }

    private void refreshTableRow(int mr, TagInfo ti) {
        tableModel.setValueAt(ti.artist,      mr, FileTableModel.COL_ARTIST);
        tableModel.setValueAt(ti.albumArtist, mr, FileTableModel.COL_ALBUM_ARTIST);
        tableModel.setValueAt(ti.title,       mr, FileTableModel.COL_TITLE);
        tableModel.setValueAt(ti.album,       mr, FileTableModel.COL_ALBUM);
        tableModel.setValueAt(ti.year,        mr, FileTableModel.COL_YEAR);
        tableModel.setValueAt(ti.genre,       mr, FileTableModel.COL_GENRE);
        tableModel.setValueAt(ti.track,       mr, FileTableModel.COL_TRACK);
    }

    /**
     * Après une sauvegarde manuelle, met le statut à TAGGED si au moins
     * artiste ou titre sont renseignés — ce qui met à jour le filtre statut
     * en temps réel sans redémarrage.
     */
    private void markManuallyTagged(FileEntry e, TagInfo ti, int mr) {
        if (e.status != FileEntry.Status.TAGGED
                && (!ti.artist.isBlank() || !ti.title.isBlank())) {
            e.status  = FileEntry.Status.TAGGED;
            e.message = "";
            tableModel.update(e);   // fire fireTableRowsUpdated → RowSorter re-évalue le filtre
        }
    }

    private void writeTagsSafe(FileEntry e, TagInfo ti) {
        try {
            File target = e.currentPath != null ? e.currentPath.toFile() : e.file;
            new com.opentagger.TagWriter().write(target, ti);
        } catch (Exception ex) {
            showError("Erreur écriture " + e.filename() + " : " + ex.getMessage());
        }
    }

    private void performUndo() {
        // Capturer la description AVANT undo() pour afficher ce qui vient d'être annulé,
        // pas la prochaine action disponible dans la pile.
        String desc = undoManager.undoDescription();
        FileEntry e = undoManager.undo();
        if (e != null) { tableModel.update(e); refreshDetail(); setStatus("Annulé : " + desc); }
    }

    private void performRedo() {
        String desc = undoManager.redoDescription();
        FileEntry e = undoManager.redo();
        if (e != null) { tableModel.update(e); refreshDetail(); setStatus("Rétabli : " + desc); }
    }

    private void updateUndoButtons() {
        if (btnUndo == null) return;
        btnUndo.setEnabled(undoManager.canUndo());
        btnRedo.setEnabled(undoManager.canRedo());
        String ud = undoManager.undoDescription();
        String rd = undoManager.redoDescription();
        btnUndo.setToolTipText(undoManager.canUndo() ? "Annuler : " + ud + " (Ctrl+Z)" : "Rien à annuler");
        btnRedo.setToolTipText(undoManager.canRedo() ? "Rétablir : " + rd + " (Ctrl+Y)" : "Rien à rétablir");
    }

    // ── Barre de statut ───────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        lblStatus = new JLabel(" Prêt");
        progress  = new JProgressBar(0, 100);
        progress.setPreferredSize(new Dimension(220, 14));
        progress.setStringPainted(true);
        progress.setVisible(false);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, sep()),
            new EmptyBorder(5, 12, 5, 12)));
        bar.add(lblStatus, BorderLayout.WEST);
        bar.add(progress,  BorderLayout.EAST);
        return bar;
    }

    // ── Bandeau de scan dossiers (Jaikoz-style) ──────────────────────────────

    private JPanel buildScanBanner() {
        scanEntries.setLayout(new BoxLayout(scanEntries, BoxLayout.Y_AXIS));
        scanEntries.setOpaque(false);
        scanBanner.setLayout(new BorderLayout());
        scanBanner.add(scanEntries, BorderLayout.CENTER);
        scanBanner.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, sep()));
        scanBanner.setVisible(false);
        return scanBanner;
    }

    /** Ajoute une entrée de scan dans le bandeau. Retourne le panneau pour mise à jour ultérieure. */
    private JPanel addScanEntry(String dirName) {
        activeScanCount++;

        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 3));
        row.setOpaque(false);

        JLabel spinner = new JLabel("⠋");
        spinner.setForeground(new Color(0x5599FF));
        spinner.setFont(spinner.getFont().deriveFont(12f));

        JLabel lbl = new JLabel(dirName + " — scan en cours…");
        lbl.setForeground(UIManager.getColor("Label.foreground"));
        lbl.setFont(lbl.getFont().deriveFont(11f));

        JButton btnStop = new JButton("✕");
        btnStop.setToolTipText("Arrêter ce scan");
        btnStop.setFont(btnStop.getFont().deriveFont(9f));
        btnStop.setMargin(new java.awt.Insets(1,4,1,4));
        btnStop.setFocusable(false);
        btnStop.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));

        row.add(spinner);
        row.add(lbl);
        row.add(btnStop);

        String[] frames = {"⠋","⠙","⠹","⠸","⠼","⠴","⠦","⠧","⠇","⠏"};
        int[] fi = {0};
        Timer anim = new Timer(80, e -> { fi[0] = (fi[0]+1) % frames.length; spinner.setText(frames[fi[0]]); });
        anim.start();
        row.putClientProperty("anim",    anim);
        row.putClientProperty("lbl",     lbl);
        row.putClientProperty("btnStop", btnStop);

        SwingUtilities.invokeLater(() -> {
            scanEntries.add(row);
            scanBanner.setVisible(true);
            scanBanner.revalidate();
            scanBanner.repaint();
        });
        return row;
    }

    /** Met à jour l'entrée de scan à la fin du chargement et la supprime après 3 s. */
    private void completeScanEntry(JPanel row, String dirName, int total, long tagged, Exception error) {
        activeScanCount--;
        Timer anim = (Timer) row.getClientProperty("anim");
        if (anim != null) anim.stop();
        JLabel lbl = (JLabel) row.getClientProperty("lbl");
        JLabel spinner = (JLabel) row.getComponent(0);

        if (error != null) {
            spinner.setText("✗"); spinner.setForeground(new Color(0xDD4444));
            if (lbl != null) lbl.setText(dirName + " — erreur : " + error.getMessage());
        } else {
            spinner.setText("✓"); spinner.setForeground(new Color(0x4DB6AC));
            if (lbl != null) lbl.setText(dirName + " — " + total + " fichier(s), " + tagged + " déjà tagué(s)");
        }
        scanBanner.revalidate();
        // Disparaît après 4 s
        new Timer(4000, e -> {
            ((Timer)e.getSource()).stop();
            scanEntries.remove(row);
            if (scanEntries.getComponentCount() == 0) scanBanner.setVisible(false);
            scanBanner.revalidate();
            scanBanner.repaint();
        }) {{ setRepeats(false); }}.start();
    }

    // ── Drag & Drop d'un dossier ──────────────────────────────────────────────

    private void installDragDrop() {
        TransferHandler th = new TransferHandler() {
            @Override public boolean canImport(TransferSupport ts) {
                return ts.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport ts) {
                try {
                    List<File> dropped = (List<File>) ts.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    loadFiles(dropped.toArray(new File[0]));
                    return true;
                } catch (Exception e) { return false; }
            }
        };
        setTransferHandler(th);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Actions
    // ═══════════════════════════════════════════════════════════════════════════

    private void openFolder() {
        JFileChooser fc = new JFileChooser();
        // FILES_AND_DIRECTORIES : seul mode qui permet la multi-sélection sur Linux
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Choisir un ou plusieurs dossiers / fichiers audio");
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String n = f.getName().toLowerCase();
                return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a")
                    || n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".aac")
                    || n.endsWith(".wma") || n.endsWith(".aiff");
            }
            @Override public String getDescription() { return "Dossiers et fichiers audio"; }
        });
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        // Sur Linux, getSelectedFiles() peut manquer le dernier élément sélectionné
        // (celui affiché dans le champ "Nom du fichier"). On fusionne les deux.
        java.util.LinkedHashSet<File> all = new java.util.LinkedHashSet<>();
        File[] multi = fc.getSelectedFiles();
        if (multi != null) for (File f : multi) if (f != null) all.add(f);
        File single = fc.getSelectedFile();
        if (single != null) all.add(single);
        if (all.isEmpty()) return;
        loadFiles(all.toArray(new File[0]));
    }

    void loadFiles(File[] selected) {
        // Toujours accumuler (comme Jaikoz) — utiliser "Vider la liste" Ctrl+W pour repartir à zéro
        for (File f : selected) {
            if (f == null) continue;
            if (f.isDirectory()) loadDirectory(f);
            else if (f.isFile()) loadDirectory(f.getParentFile());
        }
    }

    /** Charge un dossier (récursivement) dans la table — toujours en mode ajout. */
    private void loadDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        final String dirName = dir.getName();
        final Path   root    = dir.toPath();
        final JPanel scanRow = addScanEntry(dirName);
        setStatus("Scan de " + dirName + "…");

        // Snapshot des chemins déjà dans la table (sur EDT, avant démarrage du worker)
        final Set<Path> alreadyInTable = new java.util.HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry fe = tableModel.get(i);
            alreadyInTable.add((fe.currentPath != null ? fe.currentPath : fe.file.toPath()).toAbsolutePath());
        }

        // Publié type :
        //   Object[]{ List<FileEntry> }                  → phase 1 : ajouter les entrées vides
        //   Object[]{ FileEntry, TagInfo, Boolean }      → phase 2 : mettre à jour les tags
        SwingWorker<int[], Object[]> scanWorker = new SwingWorker<>() {

            // Entrées ajoutées par CE scan — permet le rollback si annulé
            final java.util.Set<FileEntry> addedByThisScan = new java.util.LinkedHashSet<>();

            @Override protected int[] doInBackground() throws Exception {
                // ── Phase 1 : lister les fichiers (filesystem seulement, ~instant) ──
                List<File> files = new AudioScanner().scan(dir);
                List<FileEntry> newEntries = new ArrayList<>(files.size());
                for (File f : files) {
                    if (isCancelled()) return new int[]{0, 0};
                    if (alreadyInTable.contains(f.toPath().toAbsolutePath())) continue;
                    FileEntry e = new FileEntry(f, new com.opentagger.model.TagInfo());
                    e.scanRoot = root;
                    newEntries.add(e);
                }
                if (isCancelled()) return new int[]{0, 0};

                // Enregistrer le dossier pour l'auto-watch (hors EDT — walkFileTree peut être long)
                if (folderWatcher != null) folderWatcher.watch(dir.toPath());

                @SuppressWarnings("unchecked")
                Object[] phase1 = new Object[]{ new ArrayList<>(newEntries) };
                publish(phase1);  // → table peuplée instantanément avec noms seuls

                // ── Cache : juste les chemins → mbid (pas de TagInfo en RAM) ─────
                // Optimisation mémoire : on ne charge pas toute la tagging_history en heap.
                // Les fichiers déjà tagués sont marqués TAGGED ; leurs tags viennent de entry.current
                // (déjà écrits dans le fichier), ce qui est identique à ce qu'on afficherait.
                MetadataCache cache = new MetadataCache();
                java.util.Set<String> taggedPaths = cache.loadTaggedPaths();
                cache.close();

                // ── Phase 2 : lecture des tags (parallèle — N threads I/O) ─────────
                // On soumet tous les readTags en parallèle, puis on parcourt les futures
                // dans l'ordre pour publish() sur le SwingWorker (thread-safe car chaque
                // readTags() crée ses propres objets JAudioTagger indépendants).
                int threads = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
                java.util.concurrent.ExecutorService tagPool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);

                // Soumettre toutes les tâches de lecture avant de collecter les résultats
                java.util.List<java.util.concurrent.Future<com.opentagger.model.TagInfo>> tagFutures =
                    new java.util.ArrayList<>(newEntries.size());
                for (FileEntry entry : newEntries) {
                    final File f = entry.file;
                    tagFutures.add(tagPool.submit(() -> readTags(f)));
                }
                tagPool.shutdown();

                int tagged = 0;
                for (int i = 0; i < newEntries.size(); i++) {
                    if (isCancelled()) { tagPool.shutdownNow(); break; }
                    FileEntry entry = newEntries.get(i);
                    com.opentagger.model.TagInfo ti;
                    try { ti = tagFutures.get(i).get(); }
                    catch (Exception e) { ti = new com.opentagger.model.TagInfo(); }
                    boolean wasPreviouslyTagged = taggedPaths.contains(entry.file.getAbsolutePath());
                    if (wasPreviouslyTagged) tagged++;
                    publish(new Object[]{ entry, ti, wasPreviouslyTagged });
                }
                return new int[]{ newEntries.size(), tagged };
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void process(List<Object[]> chunks) {
                for (Object[] chunk : chunks) {
                    if (chunk[0] instanceof List) {
                        // Phase 1 : ajouter toutes les entrées vides d'un coup
                        List<FileEntry> batch = (List<FileEntry>) chunk[0];
                        tableModel.addAll(batch);
                        addedByThisScan.addAll(batch);
                        if (tableModel.getRowCount() > 0) btnRefresh.setEnabled(true);
                    } else {
                        // Phase 2 : appliquer les tags lus sur EDT (thread-safe)
                        FileEntry entry    = (FileEntry) chunk[0];
                        com.opentagger.model.TagInfo ti = (com.opentagger.model.TagInfo) chunk[1];
                        boolean wasTagged  = Boolean.TRUE.equals(chunk[2]);
                        entry.current = ti;
                        if (wasTagged) {
                            // Les tags corrects sont DÉJÀ dans le fichier (entry.current)
                            // Pas besoin de charger un TagInfo depuis l'historique en mémoire
                            entry.status  = com.opentagger.model.FileEntry.Status.TAGGED;
                            entry.message = "";
                        }
                        tableModel.update(entry);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastStatsRefreshMs >= 300) { lastStatsRefreshMs = now; refreshStats(); }
            }

            @Override protected void done() {
                activeScanWorkers.remove(this);
                JButton btnStop = (JButton) scanRow.getClientProperty("btnStop");
                if (btnStop != null) btnStop.setEnabled(false);
                if (isCancelled()) {
                    // Rollback : retirer toutes les entrées ajoutées par ce scan
                    tableModel.removeEntries(addedByThisScan);
                    if (tableModel.getRowCount() == 0) btnRefresh.setEnabled(false);
                    refreshStats();
                    completeScanEntry(scanRow, dirName, 0, 0, null);
                    setStatus("Scan annulé.");
                    return;
                }
                try {
                    int[] r = get();
                    detectLocalCompilations();
                    setStatus(tableModel.getRowCount() + " fichier(s) — " + r[1] + " déjà tagué(s)");
                    refreshStats();
                    completeScanEntry(scanRow, dirName, r[0], r[1], null);
                } catch (Exception ex) {
                    completeScanEntry(scanRow, dirName, 0, 0, ex);
                    showError(ex.getMessage());
                }
            }
        };

        // Lier le bouton Stop au worker
        JButton btnStop = (JButton) scanRow.getClientProperty("btnStop");
        if (btnStop != null) btnStop.addActionListener(e -> {
            scanWorker.cancel(false);
            btnStop.setEnabled(false);
            JLabel lbl2 = (JLabel) scanRow.getClientProperty("lbl");
            if (lbl2 != null) lbl2.setText(dirName + " — annulation…");
        });

        activeScanWorkers.add(scanWorker);
        scanWorker.execute();
    }

    private void startTagging(boolean selOnly) {
        if (worker != null && !worker.isDone()) {
            setStatus("Taguage en cours — attendez la fin ou cliquez sur Annuler.");
            return;
        }
        List<FileEntry> toTag = new ArrayList<>();
        if (selOnly) {
            for (int r : table.getSelectedRows())
                toTag.add(tableModel.get(table.convertRowIndexToModel(r)));
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                if (e.selected && e.status != FileEntry.Status.TAGGED) toTag.add(e);
            }
        }
        if (toTag.isEmpty()) {
            setStatus("Aucun fichier à taguer (tous déjà tagués — utilisez « Forcer le re-taguage » pour les re-traiter).");
            return;
        }

        btnTagAll.setEnabled(false); btnTagSel.setEnabled(false);
        btnCancel.setEnabled(true);
        progress.setValue(0); progress.setVisible(true);

        int autoMask = Config.get().autoRenameEnabled() ? Config.get().defaultRenameMask() : -1;
        lastStatsRefreshMs = 0; // réinitialiser le throttle à chaque nouveau taguage
        final int totalFiles = toTag.size();
        worker = new TaggingWorker(toTag, Config.get().useAcoustId(), autoMask,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> {
                tableModel.update(entry);
                table.repaint();
                if (entry.status == FileEntry.Status.PROCESSING) {
                    int modelRow = tableModel.indexOf(entry);
                    if (modelRow >= 0) {
                        int viewRow = table.convertRowIndexToView(modelRow);
                        if (viewRow >= 0)
                            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                    }
                }
                int sel = table.getSelectedRow();
                if (sel >= 0 && tableModel.get(table.convertRowIndexToModel(sel)) == entry)
                    refreshDetail();
                // Throttle : rafraîchir les chips au plus toutes les 300ms
                long now = System.currentTimeMillis();
                if (now - lastStatsRefreshMs >= 300) {
                    lastStatsRefreshMs = now;
                    refreshStats();
                }
            }
        );
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int pct = (Integer) evt.getNewValue();
                progress.setValue(pct);
                int fileDone = (int) Math.round(pct * totalFiles / 100.0);
                progress.setString(fileDone + " / " + totalFiles);
                // Refresher les chips à chaque % de progression (≤101 appels au total)
                // plutôt qu'à chaque fichier — évite O(n²) sur 100k+ fichiers.
                refreshStats();
            }
            if (SwingWorker.StateValue.DONE.equals(evt.getNewValue()))
                onTaggingDone(toTag);
        });
        worker.execute();
    }

    private void cancelTagging() {
        if (worker != null) worker.cancel(true);
        // Reset immédiat sur l'EDT — même si le thread tourne encore en arrière-plan
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status == FileEntry.Status.PROCESSING) {
                e.status  = FileEntry.Status.PENDING;
                e.message = "";
                tableModel.update(e);
            }
        }
        refreshStats();
        setStatus("Arrêté."); resetBtns();
    }

    // ── Passe complète (compléter les infos manquantes) ──────────────────────

    private void completeAllInfo() {
        if (infoCompleter != null && !infoCompleter.isDone()) {
            infoCompleter.cancel(false);
            setStatus("Passe complète annulée.");
            return;
        }
        if (worker != null && !worker.isDone()) {
            setStatus("Taguage en cours — attendez la fin avant de lancer la passe complète.");
            return;
        }

        // Cible : sélection si ≥1, sinon tous les TAGGED avec champs manquants
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        if (sel.length > 0) {
            for (int r : sel) {
                FileEntry e = tableModel.get(table.convertRowIndexToModel(r));
                if (e.status == FileEntry.Status.TAGGED) {
                    String p = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath().toString();
                    if (seenPaths.add(p)) targets.add(e);
                }
            }
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                if (e.status != FileEntry.Status.TAGGED) continue;
                String p = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath().toString();
                if (!seenPaths.add(p)) continue;
                TagInfo ti = e.result;
                boolean incomplete = ti == null
                    || ti.artistMbid.isBlank() || ti.album.isBlank()
                    || ti.year.isBlank()        || ti.genre.isBlank()
                    || ti.mood.isBlank()         || ti.bpm.isBlank()
                    || ti.lyrics.isBlank();
                if (incomplete) targets.add(e);
            }
        }

        if (targets.isEmpty()) {
            setStatus("Tous les fichiers tagués sont déjà complets.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
            targets.size() + " fichier(s) ont des infos manquantes.\n"
            + "La passe complète va chercher album, année, pochette, genre, mood, BPM et paroles.\n"
            + "Durée estimée : " + (targets.size() * 2) + "–" + (targets.size() * 4) + " secondes.",
            "Passe complète", JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        progress.setVisible(true);
        progress.setMaximum(targets.size());
        progress.setValue(0);

        infoCompleter = new InfoCompleterWorker(
            targets,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> SwingUtilities.invokeLater(() -> { tableModel.update(entry); refreshStats(); }),
            (done, total) -> SwingUtilities.invokeLater(() -> {
                progress.setValue(done);
                progress.setString(done + "/" + total);
            })
        );
        infoCompleter.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    progress.setVisible(false);
                    setStatus("Passe complète terminée — " + targets.size() + " fichier(s) traités.");
                    refreshStats();
                });
            }
        });
        infoCompleter.execute();
    }

    private void forceRetag() {
        // Cible : lignes sélectionnées si ≥1, sinon tous les fichiers TAGGED
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        if (sel.length > 0) {
            for (int r : sel) targets.add(tableModel.get(table.convertRowIndexToModel(r)));
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                if (e.status == FileEntry.Status.TAGGED) targets.add(e);
            }
        }
        if (targets.isEmpty()) { setStatus("Aucun fichier sélectionné à re-taguer."); return; }

        int confirm = JOptionPane.showConfirmDialog(this,
            targets.size() + " fichier(s) vont être remis en PENDING et leur cache effacé.\n"
            + "Ils seront re-tagués au prochain lancement du taguage.",
            "Forcer le re-taguage", JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        MetadataCache cache = new MetadataCache();
        try {
            for (FileEntry e : targets) {
                java.io.File fichier = e.currentPath != null ? e.currentPath.toFile() : e.file;
                // Effacer le cache pour ce fichier
                cache.recordFileTagging(fichier.getAbsolutePath(), null);
                // Effacer les MBIDs du fichier audio pour forcer une nouvelle identification
                // (sinon MUSICBRAINZ_TRACK_ID est relu et peut donner un mauvais résultat en cache)
                try {
                    org.jaudiotagger.audio.AudioFile af = org.jaudiotagger.audio.AudioFileIO.read(fichier);
                    org.jaudiotagger.tag.Tag tag = af.getTag();
                    if (tag != null) {
                        tag.deleteField(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_TRACK_ID);
                        tag.deleteField(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_ARTISTID);
                        tag.deleteField(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_RELEASEID);
                        tag.deleteField(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);
                        af.commit();
                    }
                } catch (Exception ignored) {}
                // Réinitialiser le statut
                e.status   = FileEntry.Status.PENDING;
                e.message  = "";
                e.result   = null;
                e.candidates = null;
                tableModel.update(e);
            }
        } finally { cache.close(); }
        refreshStats();
        setStatus(targets.size() + " fichier(s) remis en attente — relancez le taguage.");
    }

    private void onTaggingDone(List<FileEntry> done) {
        long ok   = done.stream().filter(e -> e.status == FileEntry.Status.TAGGED).count();
        long skip = done.stream().filter(e -> e.status == FileEntry.Status.SKIPPED).count();
        long err  = done.stream().filter(e -> e.status == FileEntry.Status.ERROR).count();
        setStatus(String.format("Terminé — ✓ %d tagué(s)  ⚠ %d ignoré(s)  ✗ %d erreur(s)  — complétion albums…",
                ok, skip, err));
        resetBtns();
        detectLocalCompilations();
        refreshStats();
        // Lancer la complétion albums automatiquement après chaque session de tagging
        completeAlbums();
    }

    /**
     * Heuristique locale : regroupe les fichiers par (dossier + album) et marque
     * isCompilation = "1" quand ≥ 3 artistes distincts partagent le même album.
     * Complète la détection MB (qui couvre les cas où MB renvoie "Various Artists"
     * ou le type release-group "Compilation"), mais n'écrase pas les flags déjà posés.
     */
    private void detectLocalCompilations() {
        // Grouper par (dossier parent, nom d'album normalisé)
        java.util.Map<String, List<FileEntry>> byAlbum = new java.util.LinkedHashMap<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.current == null) continue;
            java.nio.file.Path dir = (e.currentPath != null ? e.currentPath : e.file.toPath()).getParent();
            String album = e.current.album.trim().toLowerCase();
            String key   = dir.toString() + "\0" + album;
            byAlbum.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
        }

        int marked = 0;
        for (List<FileEntry> group : byAlbum.values()) {
            if (group.size() < 3) continue; // trop peu de fichiers pour conclure

            // Si déjà marqué compilation dans au moins un fichier du groupe → ignorer
            boolean alreadyKnown = group.stream().anyMatch(e ->
                "1".equals(e.current.isCompilation)
                || (e.result != null && "1".equals(e.result.isCompilation)));
            if (alreadyKnown) continue;

            // Compter les artistes distincts non génériques
            java.util.Set<String> artists = new java.util.HashSet<>();
            for (FileEntry e : group) {
                // Préférer l'artiste du résultat MB si disponible
                String a = (e.result != null && !e.result.artist.isBlank())
                        ? e.result.artist : e.current.artist;
                a = a.trim().toLowerCase();
                if (!a.isBlank() && !isGenericLocalArtist(a)) artists.add(a);
            }

            // Seuil : ≥ 3 artistes distincts = compilation probable
            if (artists.size() >= 3) {
                for (FileEntry e : group) {
                    e.current.isCompilation = "1";
                    if (e.result != null) e.result.isCompilation = "1";
                    tableModel.update(e);
                    marked++;
                }
            }
        }

        if (marked > 0)
            setStatus("Compilation locale détectée : " + marked + " fichier(s) marqués.");
    }

    private static boolean isGenericLocalArtist(String a) {
        return a.isEmpty()
            || a.equals("various") || a.equals("various artists") || a.equals("va")
            || a.equals("unknown") || a.equals("unknown artist") || a.equals("no artist")
            || a.equals("artiste inconnu") || a.equals("artiste") || a.equals("artist")
            || a.matches("piste \\d+") || a.matches("track \\d+")
            || a.length() <= 2;
    }

    private void resetBtns() {
        btnTagAll.setEnabled(true); btnTagSel.setEnabled(true);
        btnCancel.setEnabled(false); progress.setVisible(false);
    }

    private void renameTagged() {
        // ── 1. Calculer l'aperçu (aucun fichier déplacé ici) ─────────────────
        long tagged = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++)
            if (tableModel.get(i).status == FileEntry.Status.TAGGED) tagged++;
        if (tagged == 0) { setStatus("Aucun fichier tagué à renommer."); return; }

        List<RenamePreviewDialog.PreviewRow> preview = RenamePreviewDialog.compute(tableModel, currentMask);
        if (preview.isEmpty()) { setStatus("Aucun fichier tagué à renommer."); return; }

        // ── 2. Afficher l'aperçu — non-modal avec barre de progression ───────
        RenamePreviewDialog dlg = new RenamePreviewDialog(this, preview, buildRenameJob(currentMask, null));
        dlg.setVisible(true);
    }

    private RenamePreviewDialog.RenameJob buildRenameJob(int maskIndex, Path destRoot) {
        return (onProgress, onDone) -> {
            int[] done = {0};
            List<Path> sourceDirs = new ArrayList<>();
            com.opentagger.MetadataCache cache = new com.opentagger.MetadataCache();

            new SwingWorker<String, FileEntry>() {
                int renamed = 0, skipped = 0, errors = 0;

                @Override
                protected String doInBackground() {
                    FileRenamer renamer = new FileRenamer();
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        FileEntry e = tableModel.get(i);
                        if (e.status != FileEntry.Status.TAGGED) continue;
                        Path oldPath = e.currentPath;
                        Path root = destRoot != null ? destRoot
                                  : (e.scanRoot != null ? e.scanRoot : oldPath.getParent());
                        try {
                            Path newPath = renamer.rename(e.currentPath, e.activeTags(), maskIndex, root);
                            if (newPath != null) {
                                sourceDirs.add(oldPath.getParent());
                                e.currentPath = newPath;
                                renamed++;
                                String mbid = cache.getFileTagging(oldPath.toFile().getAbsolutePath());
                                if (mbid != null)
                                    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), mbid);
                            } else {
                                skipped++;
                            }
                        } catch (Exception ex) {
                            errors++;
                            e.message = "Renommage : " + (ex.getMessage() != null ? ex.getMessage() : "erreur");
                        }
                        publish(e);
                    }
                    return String.format("Renommage — ✓ %d  déjà OK %d  ✗ %d erreur(s)",
                        renamed, skipped, errors);
                }

                @Override
                protected void process(List<FileEntry> chunks) {
                    done[0] += chunks.size();
                    for (FileEntry e : chunks) tableModel.update(e);
                    onProgress.accept(done[0]);
                }

                @Override
                protected void done() {
                    cache.close();
                    // Nettoyer dossiers vides
                    Set<Path> roots = new LinkedHashSet<>();
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        FileEntry e = tableModel.get(i);
                        if (e.scanRoot != null) roots.add(e.scanRoot);
                    }
                    for (Path src : sourceDirs)
                        for (Path r : roots)
                            try { FileRenamer.deleteEmptyAncestors(src, r); } catch (Exception ignore) {}
                    try { setStatus(get()); } catch (Exception ignore) {}
                    onDone.run();
                }
            }.execute();
        };
    }

    // ── Transcodage audio ─────────────────────────────────────────────────────

    private TranscodeWorker transcodeWorker;

    private void transcodeFiles(boolean selectionOnly) {
        if (transcodeWorker != null && !transcodeWorker.isDone()) {
            JOptionPane.showMessageDialog(this, "Un transcodage est déjà en cours.",
                    "En cours", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Construire la liste des fichiers à transcoder
        List<FileEntry> toTranscode = new ArrayList<>();
        if (selectionOnly) {
            for (int row : table.getSelectedRows()) {
                int mi = table.convertRowIndexToModel(row);
                FileEntry e = tableModel.get(mi);
                if (e.currentPath != null) toTranscode.add(e);
            }
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                if (e.selected && e.currentPath != null) toTranscode.add(e);
            }
        }
        if (toTranscode.isEmpty()) {
            setStatus("Aucun fichier à transcoder."); return;
        }

        com.opentagger.Config cfg = com.opentagger.Config.get();
        com.opentagger.AudioTranscoder.Format fmt =
                com.opentagger.AudioTranscoder.Format.fromId(cfg.transcodeFormat());
        int bitrate  = cfg.transcodeBitrate();
        boolean del  = cfg.transcodeDeleteSource();

        String confirm = String.format(
            "<html>Transcoder <b>%d fichier(s)</b> → <b>%s</b>%s ?<br><br>" +
            "<small>Format configuré dans Préférences → Transcodage.</small></html>",
            toTranscode.size(),
            fmt.id.toUpperCase(),
            fmt.hasBitrate ? " " + bitrate + " kbps" : " (lossless)");

        int r = JOptionPane.showConfirmDialog(this, confirm,
                "Transcoder", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        int[] done = {0};
        btnTranscode.setEnabled(false);
        setStatus("⏳ Transcodage… 0 / " + toTranscode.size());

        transcodeWorker = new TranscodeWorker(toTranscode, tableModel,
            pr -> setStatus("⏳ Transcodage " + pr.done() + " / " + pr.total()),
            () -> {
                String summary;
                try { summary = transcodeWorker.get(); } catch (Exception ex) { summary = "Transcodage terminé"; }
                setStatus(summary);
                btnTranscode.setEnabled(true);
            }
        );
        transcodeWorker.execute();
    }

    // ── Compléter les albums ──────────────────────────────────────────────────

    private void completeAlbums() {
        if (completionWorker != null && !completionWorker.isDone()) {
            completionWorker.cancel(true);
            setStatus("Complétion annulée.");
            return;
        }
        setStatus("Complétion des albums en cours…");
        completionWorker = new AlbumCompletionWorker(
            tableModel,
            new com.opentagger.MusicBrainzClient(),
            this::setStatus,
            () -> SwingUtilities.invokeLater(() -> setStatus("Complétion albums terminée."))
        );
        completionWorker.execute();
    }

    // ── Organiser en dossiers ─────────────────────────────────────────────────

    private Path organizeDestRoot;
    private int  organizeMask = 3; // défaut : AlbumArtist/Album/Track - Artist - Title

    private void organizeFiles() {
        long tagged = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++)
            if (tableModel.get(i).status == FileEntry.Status.TAGGED) tagged++;
        if (tagged == 0) { setStatus("Aucun fichier tagué à organiser."); return; }

        // 1. Choisir le dossier de destination
        JFileChooser fc = new JFileChooser(organizeDestRoot != null
                ? organizeDestRoot.toFile()
                : javax.swing.filechooser.FileSystemView.getFileSystemView().getDefaultDirectory());
        fc.setDialogTitle("Dossier de destination de la bibliothèque musicale");
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setApproveButtonText("Choisir ce dossier");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        organizeDestRoot = fc.getSelectedFile().toPath();

        // 2. Choisir le masque (filtré : masques contenant "/" donc avec sous-dossiers)
        FileRenamer renamer = new FileRenamer();
        List<String> folderLabels = new ArrayList<>();
        List<Integer> folderIndexes = new ArrayList<>();
        for (int i = 0; i < renamer.maskCount(); i++) {
            if (renamer.maskExpression(i).contains("/") || renamer.maskExpression(i).contains("{album}")) {
                folderLabels.add(String.format("[%2d] %s", i, renamer.maskLabel(i)));
                folderIndexes.add(i);
            }
        }
        if (folderLabels.isEmpty()) { setStatus("Aucun masque avec sous-dossiers disponible."); return; }

        int defaultIdx = folderIndexes.indexOf(organizeMask);
        if (defaultIdx < 0) defaultIdx = 0;
        String[] labelsArr = folderLabels.toArray(new String[0]);
        String chosen = (String) JOptionPane.showInputDialog(this,
                "Structure de dossiers :\n(destination : " + organizeDestRoot + ")",
                "Organiser en dossiers",
                JOptionPane.PLAIN_MESSAGE, null, labelsArr, labelsArr[defaultIdx]);
        if (chosen == null) return;
        for (int i = 0; i < labelsArr.length; i++)
            if (labelsArr[i].equals(chosen)) { organizeMask = folderIndexes.get(i); break; }

        // 3. Aperçu
        List<RenamePreviewDialog.PreviewRow> preview =
                RenamePreviewDialog.compute(tableModel, organizeMask, organizeDestRoot);
        if (preview.isEmpty()) { setStatus("Aucun fichier à organiser."); return; }

        RenamePreviewDialog dlg = new RenamePreviewDialog(this,
                preview, "Organiser en dossiers", buildRenameJob(organizeMask, organizeDestRoot));
        dlg.setVisible(true);
    }

    private void chooseMask() {
        FileRenamer renamer = new FileRenamer();
        int n = renamer.maskCount();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++)
            labels[i] = String.format("[%2d] %s", i, renamer.maskLabel(i));
        String chosen = (String) JOptionPane.showInputDialog(this,
            "Masque de renommage actif :", "Masques disponibles",
            JOptionPane.PLAIN_MESSAGE, null, labels, labels[Math.min(currentMask, n-1)]);
        if (chosen == null) return;
        for (int i = 0; i < labels.length; i++)
            if (labels[i].equals(chosen)) { currentMask = i; break; }
        updateMaskLabel();
        setStatus("Masque actif : " + renamer.maskLabel(currentMask));
    }

    private void updateMaskLabel() {
        if (lblMask == null) return;
        String name = new FileRenamer().maskLabel(currentMask);
        lblMask.setText("  [#" + currentMask + " " + name + "]  ");
        lblMask.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
    }

    private void setAllSelected(boolean val) {
        for (int i = 0; i < tableModel.getRowCount(); i++)
            tableModel.setValueAt(val, i, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitaires
    // ═══════════════════════════════════════════════════════════════════════════

    /** Lit les 90+ champs d'un fichier audio — même couverture que TagWriter. */
    private TagInfo readTags(File f) {
        TagInfo ti = new TagInfo();
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            if (tag == null) return ti;

            // ── Standard ──────────────────────────────────────────────────
            ti.title          = g(tag, FieldKey.TITLE);
            ti.artist         = g(tag, FieldKey.ARTIST);
            ti.albumArtist    = g(tag, FieldKey.ALBUM_ARTIST);
            ti.album          = g(tag, FieldKey.ALBUM);
            ti.year           = g(tag, FieldKey.YEAR);
            ti.track          = g(tag, FieldKey.TRACK);
            ti.trackTotal     = g(tag, FieldKey.TRACK_TOTAL);
            ti.genre          = g(tag, FieldKey.GENRE);
            ti.discNo         = g(tag, FieldKey.DISC_NO);
            ti.discTotal      = g(tag, FieldKey.DISC_TOTAL);
            ti.comment        = g(tag, FieldKey.COMMENT);

            // ── Tri ───────────────────────────────────────────────────────
            ti.titleSort      = g(tag, FieldKey.TITLE_SORT);
            ti.artistSort     = g(tag, FieldKey.ARTIST_SORT);
            ti.albumSort      = g(tag, FieldKey.ALBUM_SORT);
            ti.albumArtistSort= g(tag, FieldKey.ALBUM_ARTIST_SORT);
            ti.composerSort   = g(tag, FieldKey.COMPOSER_SORT);
            ti.conductorSort  = g(tag, FieldKey.CONDUCTOR_SORT);
            ti.orchestraSort  = g(tag, FieldKey.ORCHESTRA_SORT);
            ti.ensembleSort   = g(tag, FieldKey.ENSEMBLE_SORT);
            ti.choirSort      = g(tag, FieldKey.CHOIR_SORT);
            ti.lyricistSort   = g(tag, FieldKey.LYRICIST_SORT);
            ti.producerSort   = g(tag, FieldKey.PRODUCER_SORT);
            ti.arrangerSort   = g(tag, FieldKey.ARRANGER_SORT);

            // ── Contributeurs ─────────────────────────────────────────────
            ti.composer       = g(tag, FieldKey.COMPOSER);
            ti.conductor      = g(tag, FieldKey.CONDUCTOR);
            ti.orchestra      = g(tag, FieldKey.ORCHESTRA);
            ti.ensemble       = g(tag, FieldKey.ENSEMBLE);
            ti.choir          = g(tag, FieldKey.CHOIR);
            ti.lyricist       = g(tag, FieldKey.LYRICIST);
            ti.producer       = g(tag, FieldKey.PRODUCER);
            ti.arranger       = g(tag, FieldKey.ARRANGER);
            ti.engineer       = g(tag, FieldKey.ENGINEER);
            ti.mixer          = g(tag, FieldKey.MIXER);
            ti.djMixer        = g(tag, FieldKey.DJMIXER);

            // ── Classique ─────────────────────────────────────────────────
            ti.work           = g(tag, FieldKey.WORK);
            ti.workMbid       = g(tag, FieldKey.MUSICBRAINZ_WORK_ID);
            ti.movement       = g(tag, FieldKey.MOVEMENT);
            ti.movementNo     = g(tag, FieldKey.MOVEMENT_NO);
            ti.movementTotal  = g(tag, FieldKey.MOVEMENT_TOTAL);
            ti.titleMovement  = g(tag, FieldKey.TITLE_MOVEMENT);
            ti.part           = g(tag, FieldKey.PART);
            ti.partType       = g(tag, FieldKey.PART_TYPE);
            ti.partNo         = g(tag, FieldKey.PART_NUMBER);
            ti.period         = g(tag, FieldKey.PERIOD);
            ti.opus           = g(tag, FieldKey.OPUS);
            ti.classicalCatalog   = g(tag, FieldKey.CLASSICAL_CATALOG);
            ti.classicalNickname  = g(tag, FieldKey.CLASSICAL_NICKNAME);
            ti.section        = g(tag, FieldKey.SECTION);
            ti.overallWork    = g(tag, FieldKey.OVERALL_WORK);
            ti.grouping       = g(tag, FieldKey.GROUPING);

            // ── Flags ─────────────────────────────────────────────────────
            String isCl = g(tag, FieldKey.IS_CLASSICAL);
            if ("1".equals(isCl) || "true".equalsIgnoreCase(isCl)) ti.isClassical = "1";
            String isCo = g(tag, FieldKey.IS_COMPILATION);
            if ("1".equals(isCo) || "true".equalsIgnoreCase(isCo)) ti.isCompilation = "1";

            // ── Audio ─────────────────────────────────────────────────────
            ti.bpm            = g(tag, FieldKey.BPM);
            ti.initialKey     = g(tag, FieldKey.KEY);
            ti.language       = g(tag, FieldKey.LANGUAGE);

            // ── Paroles ───────────────────────────────────────────────────
            ti.lyrics         = g(tag, FieldKey.LYRICS);
            ti.lyricsUrl      = g(tag, FieldKey.URL_LYRICS_SITE);

            // ── Rating / Tags ─────────────────────────────────────────────
            ti.rating         = g(tag, FieldKey.RATING);
            ti.tags           = g(tag, FieldKey.TAGS);

            // ── Mood ──────────────────────────────────────────────────────
            ti.mood               = g(tag, FieldKey.MOOD);
            ti.moodAggressive     = g(tag, FieldKey.MOOD_AGGRESSIVE);
            ti.moodAcoustic       = g(tag, FieldKey.MOOD_ACOUSTIC);
            ti.moodElectronic     = g(tag, FieldKey.MOOD_ELECTRONIC);
            ti.moodHappy          = g(tag, FieldKey.MOOD_HAPPY);
            ti.moodParty          = g(tag, FieldKey.MOOD_PARTY);
            ti.moodRelaxed        = g(tag, FieldKey.MOOD_RELAXED);
            ti.moodSad            = g(tag, FieldKey.MOOD_SAD);
            ti.moodValence        = g(tag, FieldKey.MOOD_VALENCE);
            ti.moodArousal        = g(tag, FieldKey.MOOD_AROUSAL);
            ti.moodDanceability   = g(tag, FieldKey.MOOD_DANCEABILITY);
            ti.moodInstrumental   = g(tag, FieldKey.MOOD_INSTRUMENTAL);

            // ── URLs ──────────────────────────────────────────────────────
            ti.artistOfficialUrl  = g(tag, FieldKey.URL_OFFICIAL_ARTIST_SITE);
            ti.artistWikipediaUrl = g(tag, FieldKey.URL_WIKIPEDIA_ARTIST_SITE);
            ti.artistDiscogsUrl   = g(tag, FieldKey.URL_DISCOGS_ARTIST_SITE);
            ti.releaseOfficialUrl = g(tag, FieldKey.URL_OFFICIAL_RELEASE_SITE);
            ti.releaseWikipediaUrl= g(tag, FieldKey.URL_WIKIPEDIA_RELEASE_SITE);
            ti.releaseDiscogsUrl  = g(tag, FieldKey.URL_DISCOGS_RELEASE_SITE);

            // ── IDs ───────────────────────────────────────────────────────
            ti.isrc               = g(tag, FieldKey.ISRC);
            ti.amazonId           = g(tag, FieldKey.AMAZON_ID);
            ti.roonAlbumTag       = g(tag, FieldKey.ROONALBUMTAG);
            ti.roonTrackTag       = g(tag, FieldKey.ROONTRACKTAG);
            ti.acoustidId         = g(tag, FieldKey.ACOUSTID_ID);
            ti.acoustidFingerprint= g(tag, FieldKey.ACOUSTID_FINGERPRINT);

            // ── IDs MusicBrainz ───────────────────────────────────────────
            ti.artistMbid         = g(tag, FieldKey.MUSICBRAINZ_ARTISTID);
            ti.releaseMbid        = g(tag, FieldKey.MUSICBRAINZ_RELEASEID);
            ti.recordingMbid      = g(tag, FieldKey.MUSICBRAINZ_TRACK_ID);
            ti.releaseGroupMbid   = g(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);

        } catch (Exception ignored) {}
        return ti;
    }

    /** getFirst avec protection NPE et chaîne vide par défaut. */
    private String g(Tag tag, FieldKey key) {
        try { String v = tag.getFirst(key); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }

    // ── Barre de filtrage rapide ──────────────────────────────────────────────

    private JPanel buildFilterBar() {
        tfFilter      = new JTextField(20);
        cbFilterField = new JComboBox<>(new String[]{
            "Tous les champs", "Artiste", "Artiste album", "Titre",
            "Album", "Année", "Genre", "Piste"});
        cbFilterStatus = new JComboBox<>(new String[]{
            "Tous les statuts", "⏳ En attente", "✓ Tagués", "⚠ Non identifiés", "✗ Erreurs"});

        tfFilter.putClientProperty("JTextField.placeholderText", "Rechercher…");
        JButton btnClear = new JButton("✕");
        btnClear.setFont(btnClear.getFont().deriveFont(10f));
        btnClear.setToolTipText("Effacer les filtres");

        tfFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        cbFilterField .addActionListener(e -> applyFilter());
        cbFilterStatus.addActionListener(e -> applyFilter());
        btnClear.addActionListener(e -> {
            tfFilter.setText("");
            cbFilterStatus.setSelectedIndex(0);
            applyFilter();
        });

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setBorder(new MatteBorder(0, 0, 1, 0, sep()));
        p.add(new JLabel("Filtre :"));
        p.add(tfFilter);
        p.add(cbFilterField);
        p.add(new JSeparator(JSeparator.VERTICAL));
        p.add(new JLabel("Statut :"));
        p.add(cbFilterStatus);
        p.add(btnClear);
        return p;
    }

    private void applyFilter() {
        String text   = tfFilter.getText().trim();
        int statusSel = cbFilterStatus.getSelectedIndex(); // 0=tous,1=pending,2=tagged,3=skipped,4=error

        RowFilter<Object, Object> textFilter   = null;
        RowFilter<Object, Object> statusFilter = null;

        // ── Filtre texte ──────────────────────────────────────────────────
        if (!text.isBlank()) {
            String escaped = Pattern.quote(text);
            int sel = cbFilterField.getSelectedIndex();
            if (sel == 0) {
                List<RowFilter<Object, Object>> cols = new ArrayList<>();
                for (int c = 2; c <= 8; c++) cols.add(RowFilter.regexFilter("(?i)" + escaped, c));
                textFilter = RowFilter.orFilter(cols);
            } else {
                textFilter = RowFilter.regexFilter("(?i)" + escaped, sel + 1);
            }
        }

        // ── Filtre statut (colonne 9 = COL_STATUS) ────────────────────────
        if (statusSel > 0) {
            String pat = switch (statusSel) {
                case 1 -> "^—$|^⏳";         // PENDING + PROCESSING (reste visible)
                case 2 -> "^✓";             // TAGGED
                case 3 -> "^⚠";             // SKIPPED
                case 4 -> "^✗";             // ERROR
                default -> null;
            };
            if (pat != null) statusFilter = RowFilter.regexFilter(pat, 9);
        }

        // ── Combiner ─────────────────────────────────────────────────────
        if (textFilter == null && statusFilter == null) {
            rowSorter.setRowFilter(null);
        } else if (textFilter == null) {
            rowSorter.setRowFilter(statusFilter);
        } else if (statusFilter == null) {
            rowSorter.setRowFilter(textFilter);
        } else {
            rowSorter.setRowFilter(RowFilter.andFilter(List.of(textFilter, statusFilter)));
        }

        // Mettre à jour les chips de stats pour refléter la vue filtrée
        refreshStats();
    }

    // ── Sélection par statut ──────────────────────────────────────────────────

    private void selectByStatus(FileEntry.Status... statuses) {
        Set<FileEntry.Status> set = Set.of(statuses);
        table.clearSelection();
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            FileEntry e  = tableModel.get(modelRow);
            if (set.contains(e.status))
                table.addRowSelectionInterval(viewRow, viewRow);
        }
        int n = table.getSelectedRowCount();
        String label = statuses[0] == FileEntry.Status.SKIPPED ? "non identifié(s)"
                     : statuses[0] == FileEntry.Status.ERROR   ? "en erreur"
                     : "en attente";
        setStatus(n > 0 ? n + " fichier(s) " + label + " sélectionné(s)."
                        : "Aucun fichier " + label + " dans la liste.");
        // Aussi basculer le filtre visuel pour les voir clairement
        if (n > 0) {
            cbFilterStatus.setSelectedIndex(
                statuses[0] == FileEntry.Status.SKIPPED ? 3
              : statuses[0] == FileEntry.Status.ERROR   ? 4 : 1);
        }
    }

    // ── Export CSV ───────────────────────────────────────────��────────────────

    private void exportCsv() {
        if (tableModel.getRowCount() == 0) { setStatus("Aucun fichier à exporter."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("opentagger_export.csv"));
        fc.setDialogTitle("Exporter en CSV");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        setStatus("Export CSV en cours…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try (PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
                    // En-tête BOM pour Excel
                    pw.print('﻿');
                    pw.println("Fichier,Artiste,Artiste Album,Titre,Album,Année,Genre,Piste,Disque,Compositeur,Chef,MBID,Statut");
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        FileEntry e = tableModel.get(i);
                        TagInfo  ti = e.activeTags();
                        pw.println(csv(e.filename()) + "," + csv(ti.artist) + "," + csv(ti.albumArtist)
                            + "," + csv(ti.title) + "," + csv(ti.album) + "," + csv(ti.year)
                            + "," + csv(ti.genre) + "," + csv(ti.track) + "," + csv(ti.discNo)
                            + "," + csv(ti.composer) + "," + csv(ti.conductor)
                            + "," + csv(ti.recordingMbid) + "," + csv(e.status.name()));
                    }
                }
                return null;
            }
            @Override protected void done() {
                try { get(); setStatus("CSV exporté → " + out.getName()); }
                catch (Exception ex) { showError("Export CSV : " + ex.getMessage()); }
            }
        }.execute();
    }

    private static String csv(String s) {
        if (s == null) return "";
        s = s.replace("\"", "\"\"");
        return (s.contains(",") || s.contains("\"") || s.contains("\n")) ? "\"" + s + "\"" : s;
    }

    // ── Export playlist ───────────────────────────────────────────────────────

    private void exportPlaylist(String format) {
        long tagged = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++)
            if (tableModel.get(i).status == FileEntry.Status.TAGGED) tagged++;
        if (tagged == 0) { setStatus("Aucun fichier tagué à exporter."); return; }

        JFileChooser fc = new JFileChooser();
        String ext = format.equalsIgnoreCase("xspf") ? ".xspf" : ".m3u";
        fc.setSelectedFile(new File("playlist" + ext));
        fc.setDialogTitle("Exporter playlist " + format.toUpperCase());
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(ext))
            out = new File(out.getAbsolutePath() + ext);

        List<FileEntry> all = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));

        final File outFinal = out;
        setStatus("Export " + format.toUpperCase() + " en cours…");
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                return format.equalsIgnoreCase("xspf")
                    ? com.opentagger.PlaylistExporter.exportXspf(all, outFinal)
                    : com.opentagger.PlaylistExporter.exportM3u(all, outFinal);
            }
            @Override protected void done() {
                try {
                    int n = get();
                    setStatus(format.toUpperCase() + " exporté — " + n + " piste(s) → " + outFinal.getName());
                } catch (Exception ex) {
                    showError("Export " + format.toUpperCase() + " : " + ex.getMessage());
                }
            }
        }.execute();
    }

    // ── Détection de doublons ─────────────────────────────────────────────────

    private void detectDuplicates() {
        if (tableModel.getRowCount() == 0) { setStatus("Aucun fichier chargé."); return; }
        List<FileEntry> all = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));
        List<DuplicateDetector.DuplicateGroup> groups = DuplicateDetector.detect(all);
        if (groups.isEmpty()) { setStatus("Aucun doublon détecté."); return; }
        int total = groups.stream().mapToInt(g -> g.files().size()).sum();
        setStatus(groups.size() + " groupe(s) de doublons, " + total + " fichier(s) concerné(s).");
        new DuplicatesDialog(this, groups, tableModel).setVisible(true);
        refreshStats(); // dialog modal → bloquant, rafraîchir après fermeture
    }

    private void deleteErrorFiles() {
        List<FileEntry> errors = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status == FileEntry.Status.ERROR) errors.add(e);
        }

        if (errors.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Aucun fichier illisible dans la liste.",
                "Fichiers illisibles", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Construire le message de confirmation
        StringBuilder sb = new StringBuilder("<html>Supprimer définitivement <b>");
        sb.append(errors.size()).append(" fichier(s) illisible(s)</b> du disque ?<br><br>");
        int shown = Math.min(errors.size(), 8);
        for (int i = 0; i < shown; i++) {
            FileEntry e = errors.get(i);
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            sb.append("&nbsp;• <font color='#cc4444'>").append(f.getName()).append("</font>");
            if (e.message != null && !e.message.isBlank())
                sb.append(" <i>(").append(e.message).append(")</i>");
            sb.append("<br>");
        }
        if (errors.size() > shown)
            sb.append("&nbsp;… et ").append(errors.size() - shown).append(" autre(s)<br>");
        sb.append("<br><i>Ces fichiers sont corrompus ou dans un format non supporté.</i></html>");

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
            "Supprimer les fichiers illisibles", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        int deleted = 0, failDel = 0;
        for (FileEntry e : errors) {
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            int idx = tableModel.indexOf(e);
            if (f.delete()) {
                if (idx >= 0) tableModel.remove(idx);
                deleted++;
            } else {
                failDel++;
            }
        }
        String msg = deleted + " fichier(s) illisible(s) supprimé(s)";
        if (failDel > 0) msg += ", " + failDel + " échec(s) (permission refusée ?)";
        setStatus(msg);
        JOptionPane.showMessageDialog(this, msg, "Résultat", JOptionPane.INFORMATION_MESSAGE);
    }

    private void submitAcoustId() {
        if (!com.opentagger.AcoustIdSubmitter.isAvailable()) {
            showError("fpcalc introuvable — installez chromaprint pour soumettre une empreinte.");
            return;
        }

        // Collecter les fichiers candidats (sélection, ou tous les TAGGED si rien sélectionné)
        int[] sel = table.getSelectedRows();
        List<FileEntry> candidates = new ArrayList<>();
        if (sel.length > 0) {
            for (int r : sel)
                candidates.add(tableModel.get(table.convertRowIndexToModel(r)));
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++)
                candidates.add(tableModel.get(i));
        }

        // Filtrer : seulement les TAGGED avec un recordingMbid (soumission utile)
        List<FileEntry> toSubmit = new ArrayList<>();
        int skippedNotTagged = 0, skippedNoMbid = 0;
        for (FileEntry e : candidates) {
            if (e.status != FileEntry.Status.TAGGED) { skippedNotTagged++; continue; }
            TagInfo ti = e.activeTags();
            if (ti == null || ti.recordingMbid.isBlank()) { skippedNoMbid++; continue; }
            toSubmit.add(e);
        }

        if (toSubmit.isEmpty()) {
            String msg = "Aucun fichier éligible à soumettre.";
            if (skippedNotTagged > 0) msg += " (" + skippedNotTagged + " non tagué(s) ignoré(s))";
            if (skippedNoMbid   > 0) msg += " (" + skippedNoMbid   + " sans MBID ignoré(s))";
            setStatus(msg);
            return;
        }

        int total = toSubmit.size();
        String info = total + " fichier(s) à soumettre";
        if (skippedNotTagged > 0) info += ", " + skippedNotTagged + " non tagué(s) ignoré(s)";
        if (skippedNoMbid   > 0) info += ", " + skippedNoMbid   + " sans MBID ignoré(s)";
        setStatus("Soumission AcoustID : " + info + "…");

        new SwingWorker<String, String>() {
            @Override protected String doInBackground() {
                com.opentagger.AcoustIdSubmitter sub = new com.opentagger.AcoustIdSubmitter();
                int ok = 0, failed = 0;
                for (FileEntry e : toSubmit) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    try {
                        sub.submit(f, e.activeTags());
                        ok++;
                        publish("  ✔ " + f.getName());
                    } catch (Exception ex) {
                        failed++;
                        publish("  ✗ " + f.getName() + " : " + ex.getMessage());
                    }
                }
                return "Soumission terminée — ✔ " + ok + " envoyé(s)" + (failed > 0 ? "  ✗ " + failed + " erreur(s)" : "");
            }
            @Override protected void process(List<String> chunks) {
                chunks.forEach(System.out::println);
            }
            @Override protected void done() {
                try { setStatus(get()); }
                catch (Exception ex) { setStatus("Soumission AcoustID : erreur inattendue"); }
            }
        }.execute();
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lblStatus.setText("  " + msg));
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Erreur", JOptionPane.ERROR_MESSAGE);
    }

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userNodeForPackage(MainFrame.class);

    private void restoreWindowGeometry() {
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int defW = Math.max(960, (int)(screen.width  * 0.85));
        int defH = Math.max(600, (int)(screen.height * 0.85));
        int w    = PREFS.getInt("win.w", defW);
        int h    = PREFS.getInt("win.h", defH);
        int x    = PREFS.getInt("win.x", (screen.width  - w) / 2);
        int y    = PREFS.getInt("win.y", (screen.height - h) / 2);
        // Vérifier que la fenêtre est bien sur un écran visible
        java.awt.Rectangle screenBounds = new java.awt.Rectangle(screen);
        if (!screenBounds.intersects(new java.awt.Rectangle(x, y, w, h))) {
            x = (screen.width - w) / 2;
            y = (screen.height - h) / 2;
        }
        setBounds(x, y, w, h);
        if (PREFS.getBoolean("win.max", false))
            setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
    }

    private void saveWindowGeometry() {
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) != 0;
        PREFS.putBoolean("win.max", max);
        if (!max) {
            PREFS.putInt("win.w", getWidth());
            PREFS.putInt("win.h", getHeight());
            PREFS.putInt("win.x", getX());
            PREFS.putInt("win.y", getY());
        }
        try { PREFS.flush(); } catch (Exception ignored) {}
    }

    private Color sep() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(80, 80, 80);
    }
}
