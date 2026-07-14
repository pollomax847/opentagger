package com.opentagger.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.opentagger.AlbumGrouping;
import com.opentagger.AudioScanner;
import com.opentagger.Config;
import com.opentagger.FileRenamer;
import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.TagWriter;
import com.opentagger.VideoScanner;
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
import java.util.Optional;
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
    // Identifié en mémoire mais pas encore écrit sur le disque (voir FileEntry.Status.IDENTIFIED)
    // — teinte bleue distincte du vert de TAGGED, pour rester visuellement entre "en attente" et
    // "tagué" (même esprit que COL_PROCESSING, mais persistant plutôt que transitoire).
    private static final Color COL_IDENTIFIED = new Color(60,  140, 220, 45);
    private static final Color ACCENT         = new Color(0x4DB6AC); // teal
    private static final Color HEADER_BG      = new Color(0x1E1F22);
    // Couleurs des chips de statut (bande de stats cliquable) — mêmes constantes utilisées à la
    // construction (buildStatsStrip()) et à chaque restylage actif/inactif (refreshStats()).
    private static final Color CHIP_TOTAL      = new Color(0x78909C);
    private static final Color CHIP_TAGGED     = new Color(0x4CAF50);
    private static final Color CHIP_IDENTIFIED = new Color(0x42A5F5);
    private static final Color CHIP_SKIPPED    = new Color(0xFFA726);
    private static final Color CHIP_ERROR      = new Color(0xEF5350);
    private static final Color CHIP_PENDING    = new Color(0x90A4AE);

    // ── État ─────────────────────────────────────────────────────────────────
    private final FileTableModel tableModel = new FileTableModel();
    private int                     currentMask = Config.get().defaultRenameMask();
    // true dès que l'utilisateur choisit un masque explicitement via chooseMask() — empêche
    // onPreferencesSaved() d'écraser ce choix par le masque par défaut des Préférences.
    private boolean                 maskManuallyChosen = false;

    // ── Composants header ────────────────────────────────────────────────────
    // btnRefresh n'est plus fixe (voir buildHeader()) : peut être null si l'utilisateur l'a retiré
    // de la barre d'outils secondaire personnalisable — tout accès doit être null-safe.
    private JButton    btnTagAll, btnTagSel, btnSaveAll, btnCancel, btnTranscode, btnRefresh;
    // Conteneur des boutons secondaires personnalisables — voir populateSecondaryToolbar().
    private JPanel     secondaryToolbarPanel;
    private JCheckBox  chkAcoustId;
    private JLabel     lblMask;

    // ── Stats live (chips cliquables = filtre statut, remplace l'ancien menu déroulant) ───
    private JLabel     lblStatTotal, lblStatTagged, lblStatIdentified, lblStatSkipped,
                       lblStatError, lblStatPending;
    private static final int FILTER_ALL = 0, FILTER_PENDING = 1, FILTER_TAGGED = 2,
                              FILTER_SKIPPED = 3, FILTER_ERROR = 4, FILTER_IDENTIFIED = 5;
    private int activeStatusFilter = FILTER_ALL;
    private JLabel lblMemory;

    // ── Table + tri/filtre ────────────────────────────────────────────────────
    private JTable                          table;
    private TableRowSorter<FileTableModel>  rowSorter;
    private JTextField                      tfFilter;
    private JComboBox<String>               cbFilterField;

    // ── Vue arborescence par album / Cover Flow (alternatives à la liste plate) ───────────────
    // Voir AlbumTreeTableModel — coût nul tant que viewMode == FLAT (pas de listener enregistré).
    private enum ViewMode { FLAT, GROUPED, COVER_FLOW }
    private ViewMode viewMode = ViewMode.FLAT;
    private AlbumTreeTableModel albumTreeModel;
    private GroupSortRowSorter  groupRowSorter;
    // Menu "Affichage" (pas sur l'écran principal, voir buildMenuAffichage()) — retour utilisateur.
    private JRadioButtonMenuItem rbViewFlat, rbViewGrouped, rbViewCoverFlow;
    private JMenuItem            miExpandAllGroups, miCollapseAllGroups;
    // Cover Flow intégré à la place du tableau (CardLayout, voir buildMainSplit()) — pas une
    // fenêtre séparée : retour utilisateur explicite, la première version (CoverFlowDialog,
    // supprimée) dupliquait inutilement le panneau de détail déjà visible à droite.
    private CoverFlowPanel coverFlowPanel;
    private JPanel          leftCards;
    private CardLayout      leftCardLayout;
    private final java.util.Map<String, FileEntry> coverFlowRepresentative = new java.util.HashMap<>();
    // Liste des pistes de l'album actuellement centré, sous le carrousel — retour utilisateur :
    // Cover Flow n'affichait que la pochette + le détail d'UNE piste représentative, sans jamais
    // montrer les autres pistes de l'album. DefaultTableModel simple (pas FileTableModel) : lecture
    // seule, quelques lignes, aucun besoin du filtrage/tri/sélection-checkbox de la table complète.
    private JTable                 coverFlowTrackTable;
    private DefaultTableModel      coverFlowTrackModel;
    private List<FileEntry>        coverFlowTrackEntries = new ArrayList<>();
    // Profondeur de "chargement en masse" en cours (voir beginBulkTableUpdate()) — plusieurs
    // scans de dossiers peuvent tourner en même temps (activeScanWorkers), donc compteur plutôt
    // qu'un simple booléen.
    private final java.util.concurrent.atomic.AtomicInteger bulkLoadDepth =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // ── Panneau de détail ─────────────────────────────────────────────────────
    private DetailPanel detailPanel;
    private JLabel      lblFilePath;
    private JLabel      lblCoverImg;

    // ── Undo / Redo ───────────────────────────────────────────────────────────
    private final com.opentagger.UndoManager undoManager = new com.opentagger.UndoManager();
    private JButton btnUndo, btnRedo;
    private JCheckBoxMenuItem chkForceAcoustId, chkCompleteIncomplete, chkAutoCompleteAlbums;
    private JCheckBoxMenuItem chkAutoGroupCompilations;

    // ── Barre de statut ───────────────────────────────────────────────────────
    private JLabel       lblStatus;
    private JProgressBar progress;
    private long          runStartMillis;

    // ── Journal (persiste les résultats par fichier pendant/après un run) ────
    // Cap nécessaire : une session de plusieurs jours sur une bibliothèque de 100k+ fichiers
    // (plusieurs passes complètes) accumulerait sinon des centaines de milliers d'entrées.
    private static final int MAX_LOG_ENTRIES = 20_000;
    private final java.util.List<LogEntry>   logHistory = new java.util.ArrayList<>();
    private DefaultListModel<LogEntry>       logModel;
    private JList<LogEntry>                  logList;
    private JCheckBox                        chkLogErrorsOnly;

    // file : null pour une ligne séparatrice de run (logRunStart()), le FileEntry concerné sinon
    // — permet de retrouver la ligne dans le tableau (double-clic, voir installLogListMouse()).
    private record LogEntry(String time, String text, FileEntry.Status status, FileEntry file) {}

    // ── Throttle stats (évite O(n²) sur 100k+ fichiers) ──────────────────────
    private volatile long lastStatsRefreshMs = 0;

    // ── Bandeau de chargement dossiers (Jaikoz-style) ─────────────────────────
    private final JPanel scanBanner  = new JPanel();
    private final JPanel scanEntries = new JPanel();
    private int          activeScanCount = 0;
    // Résumé repliable affiché dès que plusieurs scans tournent en même temps (ex : plusieurs
    // dossiers de démarrage scannés en parallèle) — évite d'empiler une ligne par dossier.
    private JPanel  scanSummaryRow;
    private JLabel  lblScanSummary;
    private boolean scanDetailsExpanded = false;
    // Workers de scan actifs — permettent l'annulation
    private final java.util.List<SwingWorker<?,?>> activeScanWorkers = new java.util.ArrayList<>();

    // Budget PARTAGÉ (tous scans confondus, tout le lancement) de relectures forcées pour rattraper
    // les entrées scan_cache sans durée (voir la boucle de scan) — évite qu'une bibliothèque de
    // 150k+ fichiers dont le cache entier précède la colonne Durée transforme un rescan normalement
    // rapide (cache-hit) en relecture complète de tout le disque d'un coup. Se reconstitue à chaque
    // relance de l'appli (pas persisté) ; le reste du cache converge sur plusieurs sessions.
    private final java.util.concurrent.atomic.AtomicInteger durationBackfillBudget =
            new java.util.concurrent.atomic.AtomicInteger(4000);

    // Pool PARTAGÉ pour la lecture de tags pendant un scan de dossier (phase 2) — un par scan
    // (16 threads, cores*2) causait un vrai blocage constaté en direct : plusieurs dossiers
    // ajoutés en peu de temps faisaient tourner PLUSIEURS pools de 16 threads EN MÊME TEMPS,
    // plus celui d'AlbumCompletionWorker, jusqu'à 32+ threads martelant simultanément le même
    // disque externe/USB — sur un disque mécanique/USB unique (pas du réseau ni du SSD), une telle
    // concurrence cause un thrashing sévère (le disque saute constamment d'un fichier à l'autre)
    // au lieu d'aider : l'appli restait figée des heures, aucune progression réelle. Un seul pool
    // partagé, borné bas, quel que soit le nombre de scans lancés en parallèle par l'utilisateur.
    private final java.util.concurrent.ExecutorService scanTagPool =
        java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "scan-tag-reader");
            t.setDaemon(true);
            return t;
        });

    // Même leçon que scanTagPool ci-dessus, appliquée à la PHASE 1 (listage récursif de dossiers,
    // AudioScanner.scanRecursif) — jusque-là totalement non bridée : avec plusieurs dossiers de
    // démarrage configurés (6 chez cet utilisateur, dont 2 sur le MÊME disque externe
    // /mnt/MyBook), chaque loadDirectory() lance sa propre marche récursive en parallèle, bridée
    // seulement par le pool interne par défaut de SwingWorker — jusqu'à plusieurs scans à la fois
    // martelant le même disque en listFiles(). Conséquence concrète observée en direct : la phase 1
    // pouvait rester active de longues minutes, ce qui gardait aussi le RowSorter détaché tout ce
    // temps (voir beginBulkTableUpdate()) — le filtre semblait "ne plus marcher du tout". Un
    // sémaphore à 2 permis borne le nombre de marches récursives simultanées, libéré avant la phase
    // 2 (déjà bridée séparément par scanTagPool) pour ne pas la retarder inutilement.
    private final java.util.concurrent.Semaphore phase1Semaphore = new java.util.concurrent.Semaphore(2);

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
                frame.checkForUpdates(false);
            }))
        );
    }

    public MainFrame() {
        super("OpenTagger  " + Config.get().appVersion());
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
        // DO_NOTHING_ON_CLOSE (pas EXIT_ON_CLOSE) : EXIT_ON_CLOSE appelle System.exit() sans
        // condition après windowClosing(), impossible à annuler même si l'utilisateur choisit
        // "Attendre" dans confirmQuit() — quitApp() gère la fermeture réelle lui-même.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(960, 600));
        restoreWindowGeometry();  // taille/position sauvegardées, ou 85% écran par défaut
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                // Bouton X = minimiser (pas quitter) par défaut — un taguage/enregistrement peut
                // durer des heures sur une grosse bibliothèque, pas de raison de l'interrompre juste
                // parce que la fenêtre est fermée. Pas de java.awt.SystemTray ici : vérifié en
                // direct (SystemTray.isSupported() == false) sur Cinnamon/X11 moderne, qui utilise
                // StatusNotifierItem plutôt que le protocole XEmbed dont dépend cette API — la
                // fenêtre réduite reste accessible depuis la barre des tâches (grouped-window-list),
                // qui elle fonctionne indépendamment du support systray. Le menu Édition → Quitter
                // (quitApp(), avec sa confirmation si une opération est en cours) reste le seul vrai
                // moyen de fermer l'application, inchangé.
                if (Config.get().closeMinimizesToTaskbar()) {
                    setState(Frame.ICONIFIED);
                } else {
                    quitApp();
                }
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
                setStatus(I18n.t("Nouveau fichier détecté : %s", f.getName()));
            }));
            folderWatcher.start();
        } catch (Exception ex) {
            LOG.warning("FolderWatcher non disponible : " + ex.getMessage());
        }
        setLayout(new BorderLayout(0, 0));

        setJMenuBar(buildMenuBar());

        // ── Header + stats/filtre (fusionnés : chips cliquables + recherche) ────
        JPanel topArea = new JPanel(new BorderLayout(0, 0));
        topArea.add(buildHeader(),    BorderLayout.NORTH);
        topArea.add(buildStatsStrip(), BorderLayout.CENTER);
        topArea.add(buildScanBanner(), BorderLayout.SOUTH);
        add(topArea, BorderLayout.NORTH);

        // ── Split horizontal : table gauche | détail droit ──────────────────
        // ── Split vertical : (table|détail) en haut, journal en bas ─────────
        JPanel logPanel = buildLogPanel();
        logPanel.setPreferredSize(new Dimension(10, 140)); // hauteur initiale du journal
        JSplitPane withLog = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildMainSplit(), logPanel);
        withLog.setResizeWeight(1.0);   // le journal garde sa hauteur, le reste absorbe le redimensionnement
        withLog.setDividerSize(4);
        withLog.setBorder(null);
        add(withLog, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshDetail();
        });

        // Vue (liste/arborescence/Cover Flow) choisie à la dernière session — après
        // buildMainSplit() (table/coverFlowPanel doivent exister) ; ViewMode.FLAT (déjà la valeur
        // par défaut du champ) si jamais enregistré.
        int savedViewMode = PREFS.getInt("view.mode", 0);
        if (savedViewMode == 1) setViewMode(ViewMode.GROUPED);
        else if (savedViewMode == 2) setViewMode(ViewMode.COVER_FLOW);

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
        mb.add(buildMenuAffichage());
        mb.add(buildMenuOutils());
        return mb;
    }

    private JMenu buildMenuFichier() {
        JMenu m = new JMenu(I18n.t("Fichier"));
        m.setMnemonic('F');
        m.add(mitem(I18n.t("Ouvrir un dossier…"),       "Ctrl+O",  e -> openFolder()));
        m.add(mitem("↺ " + I18n.t("Rafraîchir les dossiers"),"F5",       e -> refreshFolders()));
        m.add(mitem(I18n.t("Vider la liste"),            "Ctrl+W",  e -> clearFileList()));
        m.addSeparator();
        m.add(mitem(I18n.t("Exporter CSV…"),            "Ctrl+E",  e -> exportCsv()));
        m.add(mitem(I18n.t("Exporter playlist M3U…"),  null,      e -> exportPlaylist("m3u")));
        m.add(mitem(I18n.t("Exporter playlist XSPF…"), null,      e -> exportPlaylist("xspf")));
        m.addSeparator();
        m.add(mitem(I18n.t("Quitter"),                 null,      e -> quitApp()));
        return m;
    }

    private void clearFileList() {
        // Ne vérifiait que "worker" (Taguage) — même trou que transcodeFiles()/startTagging()
        // avant leur correctif : vider la table PENDANT qu'un scan/complétion/transcodage/passe
        // complète tourne encore laisse ce worker continuer à écrire sur des fichiers qui ne sont
        // plus dans la liste, ou (pour un scan actif) réinsérer des entrées dans une table qu'on
        // vient de vider sous ses pieds.
        java.util.List<String> ops = activeOperations();
        if (!ops.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Encore en cours : %s — attendez la fin avant de vider la liste.", String.join(", ", ops)),
                I18n.t("Opération en cours"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        tableModel.clear();
        if (folderWatcher != null) folderWatcher.clearAll();
        if (btnRefresh != null) btnRefresh.setEnabled(false);
        refreshStats();
        setStatus(I18n.t("Liste vidée."));
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
        if (rows.length == 0) { setStatus(I18n.t("Sélectionnez d'abord des fichiers.")); return; }

        java.util.List<com.opentagger.model.FileEntry> targets = new java.util.ArrayList<>();
        for (int r : rows) {
            com.opentagger.model.FileEntry e = tableModel.get(table.convertRowIndexToModel(r));
            com.opentagger.model.TagInfo   ti = e.activeTags();
            if (!ti.recordingMbid.isBlank() || !ti.releaseMbid.isBlank()) targets.add(e);
        }
        if (targets.isEmpty()) {
            setStatus(I18n.t("Aucun fichier sélectionné n'a de MBID — faites d'abord un taguage."));
            return;
        }

        setStatus(I18n.t("Rafraîchissement de %d fichier(s)…", targets.size()));

        // Parallélisé (même clé "batch.threads" que TaggingWorker/BatchProcessor/
        // AlbumCompletionWorker/InfoCompleterWorker) — cette action traitait un fichier à la fois
        // malgré le même profil d'appels bloquants (lookup MB, pochette CAA/FanArt) que les autres
        // pipelines déjà parallélisés. MusicBrainzClient tient un état mutable entre appels (même
        // règle déjà établie ailleurs) — instance fraîche par tâche ; CaaClient/FanArtClient/
        // TagWriter sont sans état, partagés tels quels.
        new SwingWorker<Void, com.opentagger.model.FileEntry>() {
            final com.opentagger.CaaClient         caa      = new com.opentagger.CaaClient();
            final com.opentagger.FanArtClient      fanArt   = new com.opentagger.FanArtClient();
            final com.opentagger.DeezerClient      deezer   = new com.opentagger.DeezerClient();
            final com.opentagger.TagWriter         writer   = new com.opentagger.TagWriter();
            // Partagée entre tous les threads du pool ci-dessous : MetadataCache est déjà
            // synchronized (sauf close()/purgeExpired(), pas appelés ici pendant le traitement),
            // même pattern que TaggingWorker/AlbumCompletionWorker. Sert aussi de cache réseau
            // façon Picard pour les pochettes CAA/FanArt.
            final com.opentagger.MetadataCache     cache    = new com.opentagger.MetadataCache();
            final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();

            @Override protected Void doInBackground() throws Exception {
                int threads = Math.max(1, com.opentagger.Config.get().num("batch.threads", 3));
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

                for (com.opentagger.model.FileEntry e : targets) {
                    if (isCancelled()) break;
                    futures.add(pool.submit(() -> processOne(e, new com.opentagger.MusicBrainzClient())));
                }

                pool.shutdown();
                for (java.util.concurrent.Future<?> f : futures) {
                    try { f.get(); } catch (Exception ignored) {}
                }
                cache.close();
                return null;
            }

            private void processOne(com.opentagger.model.FileEntry e, com.opentagger.MusicBrainzClient mbClient) {
                if (isCancelled()) return;
                com.opentagger.model.TagInfo current = e.activeTags();
                // Ne PAS muter `current`/`e` ici : c'est l'objet live affiché et trié par le
                // TableRowSorter sur l'EDT. Les valeurs fraîches sont calculées sur ce thread
                // (lectures réseau) puis appliquées d'un coup sur l'EDT plus bas — sinon même
                // défaut que le crash de tri déjà vu 697× en 3 jours (TaggingWorker).
                String releaseMbidForCaa      = current.releaseMbid;
                String releaseGroupMbidForCaa = current.releaseGroupMbid;

                // 1. Tags frais depuis MB via recordingMbid
                if (!current.recordingMbid.isBlank()) {
                    try {
                        com.opentagger.model.TagInfo fresh = mbClient.lookupRecording(current.recordingMbid);
                        if (fresh != null) {
                            if (!fresh.releaseMbid.isBlank())      releaseMbidForCaa      = fresh.releaseMbid;
                            if (!fresh.releaseGroupMbid.isBlank()) releaseGroupMbidForCaa = fresh.releaseGroupMbid;
                            SwingUtilities.invokeLater(() -> {
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
                            });
                        }
                    } catch (Exception ignored) {}
                }

                // 2. Pochette fraîche via la cascade de fournisseurs configurée (valeurs
                // locales : pas besoin d'attendre que la mutation ci-dessus soit passée sur
                // l'EDT). Passe par TagEnrichment.resolveCover comme les autres pipelines —
                // corrige un bypass complet de la config (CaaClient appelé en direct, aucun
                // fournisseur autre que CAA release n'avait jamais sa chance ici).
                if (!releaseMbidForCaa.isBlank() || !releaseGroupMbidForCaa.isBlank()) {
                    try {
                        com.opentagger.model.TagInfo forCover = new com.opentagger.model.TagInfo();
                        forCover.releaseMbid      = releaseMbidForCaa;
                        forCover.releaseGroupMbid = releaseGroupMbidForCaa;
                        forCover.artistMbid       = current.artistMbid;
                        forCover.artist           = current.artist;
                        forCover.album            = current.album;
                        java.nio.file.Path img = com.opentagger.TagEnrichment.resolveCover(
                                forCover, e.file, caa, fanArt, deezer, cache);
                        if (img != null) {
                            writer.writeCoverOnly(e.file, img);
                            java.nio.file.Files.deleteIfExists(img);
                        }
                    } catch (Exception ignored) {}
                }

                done.incrementAndGet();
                publish(e);
            }

            @Override protected void process(java.util.List<com.opentagger.model.FileEntry> chunks) {
                for (com.opentagger.model.FileEntry e : chunks) tableModel.update(e);
                setStatus(I18n.t("Rafraîchissement : %d/%d…", done.get(), targets.size()));
            }

            @Override protected void done() {
                refreshStats();
                setStatus(I18n.t("Rafraîchissement terminé — %d fichier(s) mis à jour.", done.get()));
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
            setStatus(I18n.t("Aucun dossier chargé à rafraîchir."));
            return;
        }
        setStatus(I18n.t("Rafraîchissement de %d dossier(s)…", roots.size()));
        for (File root : roots) loadDirectory(root);
    }

    private JMenu buildMenuEdition() {
        JMenu m = new JMenu(I18n.t("Édition"));
        m.setMnemonic('E');
        // Références gardées pour enable/disable dynamique
        // "Annuler la dernière action" plutôt que le simple "Annuler" : ce dernier est aussi
        // utilisé comme texte de bouton "Cancel" ailleurs (MatchDialog/MbContributeDialog/
        // SettingsDialog) — même mot français, deux traductions différentes selon le contexte
        // (Undo vs Cancel), donc il faut deux clés distinctes dans le dictionnaire de traduction.
        JMenuItem miUndo = mitem(I18n.t("Annuler la dernière action"), "Ctrl+Z",  e -> performUndo());
        JMenuItem miRedo = mitem(I18n.t("Rétablir"),   "Ctrl+Y",  e -> performRedo());
        m.add(miUndo); m.add(miRedo);
        m.addSeparator();
        // Cases ☑ (FileEntry.selected, ce que "Tout tagger" traite) — concept différent de la
        // SÉLECTION de lignes (surbrillance table) regroupée ci-dessous dans son propre sous-menu.
        m.add(mitem(I18n.t("Tout cocher"),             null,      e -> setAllSelected(true)));
        m.add(mitem(I18n.t("Tout décocher"),           null,      e -> setAllSelected(false)));
        m.addSeparator();
        m.add(buildSubmenuSelection());
        m.addSeparator();
        m.add(mitem(I18n.t("Préférences…"),            "Ctrl+Virgule",
                e -> new SettingsDialog(this, this::loadFiles, this::onPreferencesSaved).setVisible(true)));
        return m;
    }

    /**
     * Regroupe les actions de SÉLECTION de lignes (surbrillance table, pas les cases ☑ — voir
     * buildMenuEdition()) — même motif déjà validé pour "Outils" : 7 items à plat, trop d'un coup.
     * "Retirer la sélection" était groupée avec les cases ☑ dans l'ancienne version ; elle agit en
     * réalité sur la SÉLECTION de lignes, sa place logique est ici.
     */
    private JMenu buildSubmenuSelection() {
        JMenu sel = new JMenu(I18n.t("Sélection"));
        sel.add(mitem(I18n.t("Tout sélectionner"),       "Ctrl+A", e -> {
            if (table.getRowCount() > 0) table.setRowSelectionInterval(0, table.getRowCount() - 1);
        }));
        sel.add(mitem(I18n.t("Désélectionner tout"),     null,      e -> table.clearSelection()));
        sel.add(mitem(I18n.t("Retirer la sélection"),    null,  e -> {
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--)
                tableModel.remove(table.convertRowIndexToModel(rows[i]));
        }));
        sel.addSeparator();
        sel.add(mitem(I18n.t("Sélectionner les identifiés (pas encore enregistrés)"), null, e -> selectByStatus(FileEntry.Status.IDENTIFIED)));
        sel.add(mitem(I18n.t("Sélectionner les tagués"),         null, e -> selectByStatus(FileEntry.Status.TAGGED)));
        sel.add(mitem(I18n.t("Sélectionner les non identifiés"), null, e -> selectByStatus(FileEntry.Status.SKIPPED)));
        sel.add(mitem(I18n.t("Sélectionner les erreurs"),        null, e -> selectByStatus(FileEntry.Status.ERROR)));
        sel.add(mitem(I18n.t("Sélectionner les en attente"),     null, e -> selectByStatus(FileEntry.Status.PENDING)));
        return sel;
    }

    /**
     * "Rattraper les non identifiés (AcoustID forcé)" et "Passe complète" ont été fusionnées ici en
     * options à cocher plutôt que des actions séparées (retour utilisateur : trop d'actions
     * distinctes pour une même intention "tagger mes fichiers"). "Tout tagger" traite déjà tout ce
     * qui n'est pas TAGGED (donc déjà les non-identifiés) — la case ci-dessous ne fait que forcer
     * AcoustID pour cette exécution au lieu du réglage des Préférences. La case "compléter aussi"
     * enchaîne après le taguage (ou immédiatement si rien de nouveau à taguer) une passe qui
     * comble les champs manquants des fichiers déjà tagués, sans jamais les réidentifier — même
     * portée que l'ancienne "Passe complète", juste sans son propre bouton. Volontairement PAS
     * fusionné : "Forcer le re-taguage" (efface cache+MBID de fichiers déjà tagués avec succès —
     * une case à cocher oubliée cochée risquerait de redétruire l'identification de toute une
     * bibliothèque au prochain "Tout tagger" ; reste une action séparée avec sa confirmation).
     */
    private JMenu buildMenuTagger() {
        JMenu m = new JMenu(I18n.t("Tagger"));
        m.setMnemonic('T');
        m.add(mitem(I18n.t("Tout tagger (cochés)"),    "F6",  e -> startTagging(false)));
        m.add(mitem(I18n.t("Tagger la sélection"),     "F7",  e -> startTagging(true)));
        m.add(mitem(I18n.t("Enregistrer tout (cochés)"), "F8", e -> saveAll()));
        m.addSeparator();
        chkForceAcoustId = new JCheckBoxMenuItem(I18n.t("Forcer AcoustID pour les non identifiés"));
        chkForceAcoustId.setSelected(Config.get().bool("tagging.force_acoustid_ui", false));
        chkForceAcoustId.addActionListener(e -> {
            Config.get().set("tagging.force_acoustid_ui", String.valueOf(chkForceAcoustId.isSelected()));
            if (chkForceAcoustId.isSelected() && !com.opentagger.AcoustIdSubmitter.isAvailable()) {
                JOptionPane.showMessageDialog(this,
                    I18n.t("fpcalc introuvable — installez chromaprint pour utiliser AcoustID."),
                    I18n.t("Configuration requise"), JOptionPane.WARNING_MESSAGE);
            } else if (chkForceAcoustId.isSelected() && Config.get().acoustidKey().isBlank()) {
                JOptionPane.showMessageDialog(this,
                    I18n.t("Clé AcoustID non configurée (Préférences → APIs → AcoustID API Key)."),
                    I18n.t("Configuration requise"), JOptionPane.WARNING_MESSAGE);
            }
        });
        m.add(chkForceAcoustId);
        chkCompleteIncomplete = new JCheckBoxMenuItem(I18n.t("Compléter aussi les fichiers tagués mais incomplets"));
        chkCompleteIncomplete.setSelected(Config.get().bool("tagging.auto_complete_incomplete", false));
        chkCompleteIncomplete.addActionListener(e ->
                Config.get().set("tagging.auto_complete_incomplete", String.valueOf(chkCompleteIncomplete.isSelected())));
        m.add(chkCompleteIncomplete);
        // Retour utilisateur : "Compléter les albums" se relançait automatiquement après CHAQUE
        // taguage sans aucun moyen de le désactiver — surprenant sur un run non attendu. Devenu
        // opt-in, off par défaut (symétrique de chkCompleteIncomplete ci-dessus).
        chkAutoCompleteAlbums = new JCheckBoxMenuItem(I18n.t("Compléter les albums automatiquement après le taguage"));
        chkAutoCompleteAlbums.setSelected(Config.get().bool("tagging.auto_complete_albums", false));
        chkAutoCompleteAlbums.addActionListener(e ->
                Config.get().set("tagging.auto_complete_albums", String.valueOf(chkAutoCompleteAlbums.isSelected())));
        m.add(chkAutoCompleteAlbums);
        // Demandé le 2026-07-10, juste après avoir choisi le mode "proactif" (revue manuelle) pour
        // "Grouper par compilations…" plutôt qu'une réécriture automatique — l'utilisateur voulait
        // aussi pouvoir déclencher la RECHERCHE toute seule, sans pour autant perdre la revue avant
        // écriture. Déclenché après "Enregistrer tout" (pas après "Tout tagger" comme les deux
        // cases ci-dessus) : les fichiers ne passent à TAGGED (recordingMbid fiable, condition de
        // CompilationClusterWorker) qu'à l'Enregistrement, jamais juste après l'identification
        // (façon Picard) — voir le hook sur saveWorker plus bas plutôt que onTaggingDone().
        chkAutoGroupCompilations = new JCheckBoxMenuItem(I18n.t("Grouper par compilations automatiquement après l'enregistrement"));
        chkAutoGroupCompilations.setSelected(Config.get().bool("tagging.auto_group_compilations", false));
        chkAutoGroupCompilations.addActionListener(e ->
                Config.get().set("tagging.auto_group_compilations", String.valueOf(chkAutoGroupCompilations.isSelected())));
        m.add(chkAutoGroupCompilations);
        m.addSeparator();
        m.add(mitem(I18n.t("Arrêter"),                 null,  e -> stopAll()));
        m.addSeparator();
        m.add(mitem(I18n.t("Renommer les fichiers tagués"), "Ctrl+R", e -> renameTagged()));
        m.add(mitem(I18n.t("Organiser en dossiers…"),  "Ctrl+G", e -> organizeFiles()));
        m.add(mitem(I18n.t("Choisir le masque…"),      null,      e -> chooseMask()));
        m.addSeparator();
        m.add(mitem(I18n.t("Compléter les albums…"),   "Ctrl+L", e -> completeAlbums()));
        m.add(mitem(I18n.t("Grouper les albums…"),     "Ctrl+K", e -> clusterAlbums()));
        m.add(mitem(I18n.t("Grouper par compilations…"), null,   e -> groupByCompilations()));
        m.addSeparator();
        m.add(mitem(I18n.t("Transcoder les fichiers…"),   "Ctrl+T", e -> transcodeFiles(false)));
        m.add(mitem(I18n.t("Transcoder la sélection…"),   null,     e -> transcodeFiles(true)));
        return m;
    }

    /**
     * Mode d'affichage de la bibliothèque (Liste/Arborescence par album/Cover Flow) — retour
     * utilisateur explicite : pas de combo/boutons sur l'écran principal, regroupé ici à la place.
     * rbViewFlat/rbViewGrouped/miExpandAllGroups/miCollapseAllGroups sont synchronisés depuis
     * setViewMode() (voir syncViewModeControl()), donc restent corrects même si la vue change par
     * un autre chemin que ce menu (aucun aujourd'hui, mais évite une divergence future).
     */
    private JMenu buildMenuAffichage() {
        JMenu m = new JMenu(I18n.t("Affichage"));
        m.setMnemonic('A');

        ButtonGroup grp = new ButtonGroup();
        rbViewFlat      = new JRadioButtonMenuItem(I18n.t("Liste"), viewMode == ViewMode.FLAT);
        rbViewGrouped   = new JRadioButtonMenuItem(I18n.t("Arborescence par album"), viewMode == ViewMode.GROUPED);
        rbViewCoverFlow = new JRadioButtonMenuItem(I18n.t("Cover Flow (bêta)"), viewMode == ViewMode.COVER_FLOW);
        grp.add(rbViewFlat);
        grp.add(rbViewGrouped);
        grp.add(rbViewCoverFlow);
        rbViewFlat.addActionListener(e -> setViewMode(ViewMode.FLAT));
        rbViewGrouped.addActionListener(e -> setViewMode(ViewMode.GROUPED));
        rbViewCoverFlow.addActionListener(e -> setViewMode(ViewMode.COVER_FLOW));
        m.add(rbViewFlat);
        m.add(rbViewGrouped);
        m.add(rbViewCoverFlow);
        m.addSeparator();

        miExpandAllGroups   = mitem(I18n.t("Tout déplier"),  null, e -> { if (albumTreeModel != null) albumTreeModel.expandAll(); });
        miCollapseAllGroups = mitem(I18n.t("Tout replier"),  null, e -> { if (albumTreeModel != null) albumTreeModel.collapseAll(); });
        miExpandAllGroups.setEnabled(viewMode == ViewMode.GROUPED);
        miCollapseAllGroups.setEnabled(viewMode == ViewMode.GROUPED);
        m.add(miExpandAllGroups);
        m.add(miCollapseAllGroups);
        return m;
    }

    /**
     * Regroupé en sous-menus (au lieu de 12 entrées à plat) — retour utilisateur : trop d'actions
     * visibles d'un coup dans "Outils". Aucune action supprimée/renommée, juste réorganisée par
     * thème : correction manuelle, re-traitement, bibliothèque, MusicBrainz.
     */
    private JMenu buildMenuOutils() {
        JMenu m = new JMenu(I18n.t("Outils"));
        m.setMnemonic('O');

        JMenu correction = new JMenu(I18n.t("Correction manuelle"));
        correction.add(mitem(I18n.t("Correspondance manuelle…"),"Ctrl+M",  e -> openMatchDialog()));
        correction.add(mitem(I18n.t("Gérer la pochette…"),      null,      e -> openCoverDialog()));
        m.add(correction);

        JMenu retraitement = new JMenu(I18n.t("Re-traitement"));
        retraitement.add(mitem(I18n.t("Forcer le re-taguage…"),    null,      e -> forceRetag()));
        retraitement.add(mitem(I18n.t("Synchroniser ListenBrainz…"), null,    e -> syncListenBrainz()));
        m.add(retraitement);

        JMenu bibliotheque = new JMenu(I18n.t("Bibliothèque"));
        bibliotheque.add(mitem(I18n.t("Tagger comme podcast…"),    null,      e -> openPodcastDialog()));
        bibliotheque.add(mitem(I18n.t("Récupérer l'audio des vidéos non reconnues…"), null, e -> openVideoRecoveryDialog()));
        bibliotheque.add(mitem(I18n.t("Détecter les doublons…"),  null,      e -> detectDuplicates()));
        bibliotheque.add(mitem(I18n.t("Supprimer les fichiers illisibles…"), null, e -> deleteErrorFiles()));
        bibliotheque.add(mitem(I18n.t("Historique de taguage…"),  null,      e -> new HistoryDialog(this).setVisible(true)));
        m.add(bibliotheque);

        JMenu musicbrainz = new JMenu("MusicBrainz");
        musicbrainz.add(mitem(I18n.t("Modifier sur MusicBrainz"),null,      e -> openMbEditPage()));
        musicbrainz.add(mitem(I18n.t("Contribuer à MusicBrainz…"), null,   e -> openMbContribute()));
        musicbrainz.add(mitem(I18n.t("Soumettre fingerprint AcoustID"), null, e -> submitAcoustId()));
        m.add(musicbrainz);

        m.addSeparator();
        m.add(mitem(I18n.t("Vérifier les mises à jour…"), null,   e -> checkForUpdates(true)));
        m.add(mitem(I18n.t("À propos d'OpenTagger…"),     null,   e -> showAboutDialog()));
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
        JButton btnOpen = accentBtn(I18n.t("Ouvrir dossier"), "Ctrl+O");
        btnOpen.addActionListener(e -> openFolder());

        btnTagAll    = headerBtn(I18n.t("Tout tagger"), I18n.t("Tagger tous les fichiers cochés (F6)"));
        btnTagSel    = headerBtn(I18n.t("Tagger la sélection"), I18n.t("Tagger les lignes sélectionnées (F7)"));
        btnSaveAll   = headerBtn(I18n.t("Enregistrer tout"), I18n.t("Écrire sur le disque les fichiers identifiés, cochés (F8)"));
        btnCancel    = headerBtn(I18n.t("Arrêter"), I18n.t("Annuler le traitement en cours"));
        btnTagAll.addActionListener(e -> startTagging(false));
        btnTagSel.addActionListener(e -> startTagging(true));
        btnSaveAll.addActionListener(e -> saveAll());
        btnCancel.addActionListener(e -> stopAll());
        btnCancel.setEnabled(false);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        actionsPanel.setBackground(HEADER_BG);
        actionsPanel.add(btnOpen);
        actionsPanel.add(vSep());
        actionsPanel.add(btnTagAll);
        actionsPanel.add(btnTagSel);
        actionsPanel.add(btnSaveAll);
        actionsPanel.add(btnCancel);

        // Actions secondaires personnalisables (façon Picard, onglet "Barre d'outils des
        // actions") — voir toolbarActionRegistry() / Config.toolbarActions(). Les boutons d'état
        // ci-dessus (Ouvrir/Tout tagger/Tagger la sélection/Enregistrer tout/Annuler) restent
        // fixes : trop couplés à resetBtns()/launchForcedTagging() pour être rendus optionnels
        // sans risque. Dans un panneau à part (secondaryToolbarPanel) plutôt que directement dans
        // actionsPanel : permet de tout reconstruire en un removeAll()/repopulate depuis
        // populateSecondaryToolbar() (rappelée après Préférences) sans toucher aux boutons fixes.
        secondaryToolbarPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        secondaryToolbarPanel.setBackground(HEADER_BG);
        actionsPanel.add(secondaryToolbarPanel);
        populateSecondaryToolbar();

        // ── Droite : undo/redo (boutons conservés pour updateUndoButtons) ────
        btnUndo = iconBtn("↩", I18n.t("Annuler (Ctrl+Z)"));
        btnRedo = iconBtn("↪", I18n.t("Rétablir (Ctrl+Y)"));
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

    /** Une action pouvant apparaître dans la barre d'outils secondaire (voir Config.toolbarActions). */
    record ToolbarAction(String id, String label, String tooltip, Runnable handler) {}

    /**
     * id + libellé seuls (sans handler) — exposé en statique pour que SettingsDialog puisse
     * lister les actions disponibles sans instancier MainFrame. Doit rester synchronisé avec
     * {@link #toolbarActionRegistry()} (mêmes ids/libellés).
     */
    public static final java.util.List<String[]> TOOLBAR_ACTION_INFOS = java.util.List.of(
        new String[]{"refreshFolders",     I18n.t("Rafraîchir")},
        new String[]{"transcode",          I18n.t("Transcoder")},
        new String[]{"submitAcoustId",     I18n.t("Soumettre AcoustID")},
        new String[]{"matchDialog",        I18n.t("Correspondance manuelle")},
        new String[]{"coverDialog",        I18n.t("Gérer la pochette")},
        new String[]{"refreshMeta",        I18n.t("Rafraîchir tags + pochette")},
        new String[]{"forceRetag",         I18n.t("Forcer le re-taguage")},
        new String[]{"syncListenBrainz",   I18n.t("Synchroniser ListenBrainz")},
        new String[]{"podcastDialog",      I18n.t("Tagger comme podcast")},
        new String[]{"detectDuplicates",   I18n.t("Détecter les doublons")},
        new String[]{"historyDialog",      I18n.t("Historique de taguage")}
    );

    /**
     * Registre des actions disponibles pour la barre d'outils secondaire personnalisable —
     * réutilise les méthodes déjà câblées ailleurs (menu Outils, clic-droit), aucune logique
     * dupliquée. Défaut (`Config.DEFAULT_TOOLBAR_ACTIONS`) = comportement identique à avant
     * l'ajout de cette fonctionnalité (transcode + submitAcoustId), PLUS refreshFolders (demande
     * explicite : "Rafraîchir" est devenu optionnel/déplaçable, en échange de "Enregistrer tout"
     * qui est devenu un bouton fixe de l'en-tête — voir buildHeader()).
     */
    private java.util.List<ToolbarAction> toolbarActionRegistry() {
        java.util.List<ToolbarAction> list = new java.util.ArrayList<>();
        list.add(new ToolbarAction("refreshFolders", I18n.t("Rafraîchir"),
                I18n.t("Rescanner les dossiers chargés pour détecter les nouveaux fichiers (F5)"),
                this::refreshFolders));
        list.add(new ToolbarAction("transcode", I18n.t("Transcoder"),
                I18n.t("Transcoder les fichiers sélectionnés (Ctrl+T)"), () -> transcodeFiles(false)));
        list.add(new ToolbarAction("submitAcoustId", I18n.t("Soumettre AcoustID"),
                I18n.t("Envoyer les empreintes AcoustID de la sélection, ou de toute la bibliothèque si rien n'est sélectionné"),
                this::submitAcoustId));
        list.add(new ToolbarAction("matchDialog", I18n.t("Correspondance manuelle"),
                I18n.t("Rechercher/choisir manuellement une correspondance MusicBrainz"), this::openMatchDialog));
        list.add(new ToolbarAction("coverDialog", I18n.t("Gérer la pochette"),
                I18n.t("Gérer la pochette du fichier sélectionné"), this::openCoverDialog));
        list.add(new ToolbarAction("refreshMeta", I18n.t("Rafraîchir tags + pochette"),
                I18n.t("Rafraîchir les tags et la pochette de la sélection depuis MusicBrainz (utile pour "
                + "corriger tout un album sélectionné d'un coup)"), this::refreshSelectedMeta));
        list.add(new ToolbarAction("forceRetag", I18n.t("Forcer le re-taguage"),
                I18n.t("Remettre en PENDING et re-taguer"), this::forceRetag));
        list.add(new ToolbarAction("syncListenBrainz", I18n.t("Synchroniser ListenBrainz"),
                I18n.t("Récupérer le nombre d'écoutes ListenBrainz pour les fichiers tagués"), this::syncListenBrainz));
        list.add(new ToolbarAction("podcastDialog", I18n.t("Tagger comme podcast"),
                I18n.t("Ouvrir le dialogue de taguage podcast"), this::openPodcastDialog));
        list.add(new ToolbarAction("detectDuplicates", I18n.t("Détecter les doublons"),
                I18n.t("Détecter les fichiers en double"), this::detectDuplicates));
        list.add(new ToolbarAction("historyDialog", I18n.t("Historique de taguage"),
                I18n.t("Ouvrir l'historique de taguage"), () -> new HistoryDialog(this).setVisible(true)));
        return list;
    }

    /**
     * Instantané des albums déjà TAGUÉS (pochette garantie déjà embarquée à ce stade, voir
     * TagEnrichment.saveEntry() — aucun coût réseau). Pas de mise à jour en direct pendant un
     * taguage en cours — reconstruit à chaque passage en mode Cover Flow (setViewMode()), et sur
     * demande via le bouton "🔄 Rafraîchir" du panneau lui-même.
     * <p>Un seul FileEntry représentant par album (le premier rencontré — la pochette est
     * identique sur toutes les pistes d'un même album) ; {@code coverFlowRepresentative} garde le
     * lien groupKey → FileEntry pour alimenter le panneau de détail à droite quand la sélection
     * change dans le carrousel (voir onCoverFlowSelectionChanged()), exactement comme une ligne de
     * la table normale le ferait.
     */
    private void refreshCoverFlowAlbums() {
        java.util.LinkedHashMap<String, CoverFlowPanel.AlbumTile> byKey = new java.util.LinkedHashMap<>();
        coverFlowRepresentative.clear();
        for (FileEntry e : tableModel.allEntries()) {
            if (!isCoverFlowEligible(e.status)) continue;
            String key = AlbumGrouping.key(e);
            if (byKey.containsKey(key)) continue;
            TagInfo tags = e.activeTags();
            String artist = !tags.albumArtist.isBlank() ? tags.albumArtist : tags.artist;
            File file = e.currentPath != null ? e.currentPath.toFile() : e.file;
            byKey.put(key, new CoverFlowPanel.AlbumTile(key, AlbumGrouping.title(e), artist, file));
            coverFlowRepresentative.put(key, e);
        }
        List<CoverFlowPanel.AlbumTile> tiles = new ArrayList<>(byKey.values());
        tiles.sort(java.util.Comparator.comparing(CoverFlowPanel.AlbumTile::title, String.CASE_INSENSITIVE_ORDER));
        coverFlowPanel.setAlbums(tiles);
    }

    /** Fait suivre le panneau de détail (droite) à l'album actuellement centré dans le carrousel —
     *  même principe que la sélection d'une ligne dans la table normale. */
    private void onCoverFlowSelectionChanged(CoverFlowPanel.AlbumTile tile, int index, int total) {
        refreshCoverFlowTrackList(tile);
        if (tile == null) { clearDetail(); return; }
        FileEntry e = coverFlowRepresentative.get(tile.groupKey());
        if (e == null) { clearDetail(); return; }
        TagInfo ti = e.activeTags();
        lblFilePath.setText("  " + (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath());
        detailPanel.populate(ti);
        loadCoverThumb(e.currentPath != null ? e.currentPath.toFile() : e.file);
        setStatus(I18n.t("Cover Flow — %d / %d — %s", index + 1, total, tile.title()));
    }

    private ToolbarAction findToolbarAction(String id) {
        for (ToolbarAction a : toolbarActionRegistry()) if (a.id().equals(id)) return a;
        return null;
    }

    /** (Re)construit les boutons de la barre d'outils secondaire depuis Config.toolbarActions() —
     *  appelé une fois à la construction de la fenêtre (buildHeader()) et à nouveau après chaque
     *  Préférences sauvegardées où la liste a changé (voir le callback passé à SettingsDialog),
     *  pour que l'ajout/retrait d'un bouton soit visible immédiatement, sans redémarrage. */
    private void populateSecondaryToolbar() {
        secondaryToolbarPanel.removeAll();
        btnRefresh = null;
        btnTranscode = null; // ré-évalués ci-dessous — peuvent disparaître si retirés des Préférences
        String[] secondary = Config.get().toolbarActions();
        if (secondary.length > 0) secondaryToolbarPanel.add(vSep());
        for (String id : secondary) {
            ToolbarAction action = findToolbarAction(id.trim());
            if (action == null) continue;
            JButton btn = secondaryBtn(action.label(), action.tooltip());
            btn.addActionListener(e -> action.handler().run());
            if ("transcode".equals(action.id())) btnTranscode = btn; // conservé : lu par transcodeFiles()
            if ("refreshFolders".equals(action.id())) {
                btnRefresh = btn; // conservé : lu par plusieurs call sites (enable/disable selon contenu)
                btnRefresh.setEnabled(!tableModel.allEntries().isEmpty());
            }
            secondaryToolbarPanel.add(btn);
        }
        secondaryToolbarPanel.revalidate();
        secondaryToolbarPanel.repaint();
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

    /** Même rôle que headerBtn() mais plus discret (police plus petite, gris atténué) — utilisé
     *  pour les actions secondaires personnalisables (Transcoder, Soumettre AcoustID…) afin de les
     *  distinguer visuellement des 5 actions fixes (Ouvrir/Rafraîchir/Tout tagger/Tagger la
     *  sélection/Annuler), plutôt que d'avoir 8+ boutons de poids visuel identique dans la même
     *  rangée. */
    private JButton secondaryBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.putClientProperty("FlatLaf.style", "foreground: #90A4AE; font: 11 $defaultFont");
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

        // Chips cliquables : cliquer filtre directement par statut (remplace l'ancien menu
        // déroulant "Statut :" — même filtre au final (applyFilter()/RowFilter sur la colonne
        // statut), juste déclenché en cliquant le chip coloré plutôt qu'un JComboBox séparé.
        // "Total" réinitialise (montre tout) ; les autres basculent (recliquer désactive).
        lblStatTotal      = statChip(I18n.t("Total"),        "0",  CHIP_TOTAL,      FILTER_ALL);
        // "Identifiés" (IDENTIFIED, façon Picard : pas encore écrit sur le disque, voir
        // FileEntry.Status.IDENTIFIED) — à ne pas confondre avec "Non identifiés" (SKIPPED,
        // ci-dessous) qui désigne l'échec total d'identification.
        lblStatIdentified = statChip(I18n.t("Identifiés"),   "0",  CHIP_IDENTIFIED, FILTER_IDENTIFIED);
        lblStatTagged     = statChip(I18n.t("Tagués"),        "0",  CHIP_TAGGED,  FILTER_TAGGED);
        lblStatSkipped    = statChip(I18n.t("Non identifiés"),"0",  CHIP_SKIPPED, FILTER_SKIPPED);
        lblStatError      = statChip(I18n.t("Erreurs"),       "0",  CHIP_ERROR,   FILTER_ERROR);
        lblStatPending    = statChip(I18n.t("En attente"),    "0",  CHIP_PENDING, FILTER_PENDING);

        p.add(new JLabel("  "));
        p.add(lblStatTotal);
        p.add(sep3());
        p.add(lblStatIdentified);
        p.add(lblStatTagged);
        p.add(lblStatSkipped);
        p.add(lblStatError);
        p.add(lblStatPending);

        // Recherche + champ ciblé — regroupés ici avec les chips plutôt que sur une 2e ligne
        // séparée (l'ancienne "barre de filtre" faisait doublon visuel avec les chips juste
        // au-dessus). Le filtre "Statut :" (JComboBox) a disparu, remplacé par les chips.
        p.add(new JSeparator(JSeparator.VERTICAL));
        tfFilter = new JTextField(16);
        tfFilter.putClientProperty("JTextField.placeholderText", I18n.t("Rechercher…"));
        cbFilterField = new JComboBox<>(new String[]{
            I18n.t("Tous les champs"), I18n.t("Artiste"), I18n.t("Artiste album"), I18n.t("Titre"),
            I18n.t("Album"), I18n.t("Année"), I18n.t("Genre"), I18n.t("Piste")});
        JButton btnClearFilter = new JButton("✕");
        btnClearFilter.setFont(btnClearFilter.getFont().deriveFont(10f));
        btnClearFilter.setToolTipText(I18n.t("Effacer les filtres"));

        tfFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        cbFilterField.addActionListener(e -> applyFilter());
        btnClearFilter.addActionListener(e -> {
            tfFilter.setText("");
            activeStatusFilter = FILTER_ALL;
            applyFilter();
        });

        p.add(tfFilter);
        p.add(cbFilterField);
        p.add(btnClearFilter);
        // Bascule de vue (Liste/Arborescence/Cover Flow) déplacée dans le menu "Affichage" —
        // retour utilisateur explicite : pas sur l'écran principal au milieu des chips/filtre,
        // voir buildMenuAffichage().
        return p;
    }

    private JLabel statChip(String label, String val, Color color, int filterIndex) {
        JLabel l = new JLabel(label + "  " + val);
        l.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
        l.setOpaque(true);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setToolTipText(filterIndex == FILTER_ALL
            ? I18n.t("Cliquer pour tout afficher")
            : I18n.t("Cliquer pour filtrer sur ce statut (recliquer pour désactiver)"));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                activeStatusFilter = (filterIndex == FILTER_ALL) ? FILTER_ALL
                    : (activeStatusFilter == filterIndex ? FILTER_ALL : filterIndex);
                applyFilter();
            }
        });
        styleChip(l, color, false);
        return l;
    }

    /** Applique l'apparence active/inactive d'un chip (fond teinté + bordure épaisse si actif). */
    private void styleChip(JLabel chip, Color color, boolean active) {
        Color translucent = new Color(color.getRed(), color.getGreen(), color.getBlue(), 90);
        chip.setForeground(active ? Color.WHITE : color);
        chip.setBackground(active ? blend(new Color(0x252527), translucent) : new Color(0x252527));
        chip.setBorder(new CompoundBorder(
            new LineBorder(color.darker(), active ? 2 : 1, true),
            new EmptyBorder(2, 7, 2, 7)));
    }

    private JLabel sep3() {
        JLabel l = new JLabel("  ");
        return l;
    }

    private void refreshStats() {
        // Purge d'abord les entrées qui ont cessé de correspondre au filtre actif depuis leur
        // dernier update() (voir FileTableModel.dirty) — refreshStats() est déjà appelé à
        // intervalle régulier (throttlé) dans toutes les boucles de scan/taguage, point de purge
        // naturel sans bookkeeping de throttle supplémentaire ici.
        tableModel.rebuildVisibleIfDirty();
        // Même throttle (300ms, ce même point d'appel) pour la vue arborescence — voir
        // AlbumTreeTableModel : ne fait rien si cette vue n'est pas active ou si rien n'a changé
        // depuis le dernier appel. fireTableDataChanged() efface la sélection de la JTable, d'où la
        // sauvegarde/restauration autour de l'appel (le modèle lui-même ne connaît pas la JTable).
        if (viewMode == ViewMode.GROUPED && albumTreeModel != null && albumTreeModel.isDirty()) {
            java.util.Set<FileEntry> sel = captureTableSelection();
            albumTreeModel.flushIfDirty();
            restoreTableSelection(sel);
        }
        int tagged = 0, identified = 0, skipped = 0, error = 0, pending = 0;
        // Chips de statut (hors "Total") : TOUJOURS le vrai compte global par statut, sur
        // tableModel.allEntries() (jamais affecté par le filtre, contrairement à getRowCount()/
        // get() qui portent sur la vue déjà filtrée) — sinon activer un filtre sur UN statut fait
        // mécaniquement tomber TOUS LES AUTRES chips à 0 par construction (retour utilisateur :
        // contre-intuitif, "pourquoi ça n'affiche pas les 102 tagués juste parce que je suis
        // filtré sur Identifiés ?" — pas un bug de comptage, un choix d'affichage qui ne
        // correspondait pas à l'attendu). Coût O(n) identique à avant (déjà throttlé à 300ms),
        // et plus simple : allEntries() est une liste plate, aucun souci d'en-têtes de groupe
        // contrairement à l'ancien commentaire sur la vue arborescence. Seuls le TABLEAU et le
        // second nombre du chip "Total" ci-dessous continuent de refléter le filtre actif.
        for (FileEntry e : tableModel.allEntries()) {
            switch (e.status) {
                case TAGGED     -> tagged++;
                case IDENTIFIED -> identified++;
                case SKIPPED    -> skipped++;
                case ERROR      -> error++;
                default         -> pending++;
            }
        }
        // "Total" reste lié au filtre actif (lignes visibles / total réel) — c'est le seul chip
        // dont le rôle est justement de montrer l'effet du filtre.
        int total = tableModel.getRowCount();
        int modelTotal = tableModel.totalCount();
        String totalText = (tableModel.isFiltered() && total != modelTotal)
                ? total + " / " + modelTotal : String.valueOf(total);
        lblStatTotal     .setText(I18n.t("Total  %s", totalText));
        lblStatIdentified.setText(I18n.t("Identifiés  %d", identified));
        lblStatTagged    .setText(I18n.t("Tagués  %d", tagged));
        lblStatSkipped   .setText(I18n.t("Non identifiés  %d", skipped));
        lblStatError     .setText(I18n.t("Erreurs  %d", error));
        lblStatPending   .setText(I18n.t("En attente  %d", pending));

        // Chip actif = celui qui correspond au filtre statut actuellement appliqué.
        styleChip(lblStatTotal,      CHIP_TOTAL,      activeStatusFilter == FILTER_ALL);
        styleChip(lblStatIdentified, CHIP_IDENTIFIED, activeStatusFilter == FILTER_IDENTIFIED);
        styleChip(lblStatTagged,     CHIP_TAGGED,     activeStatusFilter == FILTER_TAGGED);
        styleChip(lblStatSkipped,    CHIP_SKIPPED,    activeStatusFilter == FILTER_SKIPPED);
        styleChip(lblStatError,      CHIP_ERROR,      activeStatusFilter == FILTER_ERROR);
        styleChip(lblStatPending,    CHIP_PENDING,    activeStatusFilter == FILTER_PENDING);
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
        scroll.setMinimumSize(new Dimension(200, 0)); // filet de sécurité : jamais écrasée à ~0px

        // ── Cover Flow (alternative à la table, même emplacement) ────────────
        coverFlowPanel = new CoverFlowPanel();
        coverFlowPanel.setSelectionListener(this::onCoverFlowSelectionChanged);

        JPanel coverFlowWithTracks = buildCoverFlowWithTrackList();

        leftCardLayout = new CardLayout();
        leftCards = new JPanel(leftCardLayout);
        leftCards.setMinimumSize(new Dimension(200, 0));
        leftCards.add(scroll,             "table");
        leftCards.add(coverFlowWithTracks, "coverflow");

        // ── Panneau détail (droite) ───────────────────────────────────────────
        JPanel detail = buildDetailPanel();
        detail.setPreferredSize(new Dimension(360, 0));
        detail.setMinimumSize(new Dimension(280, 0));

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCards, detail);
        sp.setResizeWeight(0.68);
        sp.setDividerSize(4);
        sp.setBorder(null);
        // Position initiale explicite : resizeWeight ne gouverne que la redistribution lors des
        // redimensionnements SUIVANTS, pas le tout premier calcul de position du diviseur — sans
        // ça, imbriquer ce split à l'intérieur du split vertical du journal (ajouté récemment)
        // pouvait occasionnellement placer le diviseur tout à gauche au premier affichage
        // (table quasi invisible, panneau de détail prenant presque toute la largeur).
        sp.setDividerLocation(0.68);
        return sp;
    }

    /** Carrousel Cover Flow + liste des pistes de l'album centré, sous le carrousel — même
     *  principe que le Cover Flow original d'iTunes. Voir refreshCoverFlowTrackList(). */
    private JPanel buildCoverFlowWithTrackList() {
        coverFlowTrackModel = new DefaultTableModel(
                new Object[]{I18n.t("Piste"), I18n.t("Titre"), I18n.t("Artiste"), I18n.t("Statut"), I18n.t("Durée")}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        coverFlowTrackTable = new JTable(coverFlowTrackModel);
        coverFlowTrackTable.setRowHeight(22);
        coverFlowTrackTable.setShowHorizontalLines(false);
        coverFlowTrackTable.setIntercellSpacing(new Dimension(0, 0));
        coverFlowTrackTable.getTableHeader().setReorderingAllowed(false);
        coverFlowTrackTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableColumnModel cm = coverFlowTrackTable.getColumnModel();
        cm.getColumn(0).setMaxWidth(50);
        cm.getColumn(0).setMinWidth(40);
        cm.getColumn(4).setMaxWidth(56);
        cm.getColumn(4).setMinWidth(48);
        cm.getColumn(2).setPreferredWidth(160);
        cm.getColumn(3).setMaxWidth(90);
        cm.getColumn(3).setMinWidth(90);

        // Double-clic = quitter Cover Flow pour révéler cette piste dans la vue liste, comme le
        // reste de l'appli (aucune raison de dupliquer ici les actions d'édition/contexte déjà
        // disponibles sur la table principale — cette liste ne sert qu'à naviguer).
        coverFlowTrackTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = coverFlowTrackTable.rowAtPoint(e.getPoint());
                if (row < 0 || row >= coverFlowTrackEntries.size()) return;
                FileEntry entry = coverFlowTrackEntries.get(row);
                setViewMode(ViewMode.FLAT);
                followProcessing(entry);
            }
        });

        JScrollPane trackScroll = new JScrollPane(coverFlowTrackTable);
        trackScroll.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        // Hauteur fixe raisonnable (≈8 lignes visibles) : le carrousel doit rester l'élément
        // dominant de cette vue, la liste n'est qu'un complément de navigation en dessous.
        trackScroll.setPreferredSize(new Dimension(0, 190));

        JPanel p = new JPanel(new BorderLayout());
        p.add(coverFlowPanel, BorderLayout.CENTER);
        p.add(trackScroll,    BorderLayout.SOUTH);
        return p;
    }

    /** Repeuple la liste des pistes sous le carrousel pour l'album {@code tile} — appelé depuis
     *  onCoverFlowSelectionChanged() à chaque changement d'album centré. Triées comme la vue
     *  arborescence (disque puis piste), pas comme MusicBrainz peut les avoir renvoyées. */
    private void refreshCoverFlowTrackList(CoverFlowPanel.AlbumTile tile) {
        coverFlowTrackModel.setRowCount(0);
        coverFlowTrackEntries = new ArrayList<>();
        if (tile == null) return;
        List<FileEntry> members = new ArrayList<>();
        for (FileEntry e : tableModel.allEntries()) {
            if (!isCoverFlowEligible(e.status)) continue;
            if (AlbumGrouping.key(e).equals(tile.groupKey())) members.add(e);
        }
        members.sort(java.util.Comparator
                .<FileEntry>comparingInt(e -> leadingIntOrDefault(e.activeTags().discNo, 1))
                .thenComparingInt(e -> leadingIntOrDefault(e.activeTags().track, Integer.MAX_VALUE))
                .thenComparing(FileEntry::filename, String.CASE_INSENSITIVE_ORDER));
        for (FileEntry e : members) {
            TagInfo ti = e.activeTags();
            coverFlowTrackModel.addRow(new Object[]{ti.track, ti.title, ti.artist, statusLabel(e.status),
                    FileTableModel.formatDuration(ti.durationSec)});
            coverFlowTrackEntries.add(e);
        }
    }

    /** Même logique que AlbumTreeTableModel.leadingInt() (privée là-bas) — piste/disque non
     *  numérique ou vide en dernier, pas en premier. */
    private static int leadingIntOrDefault(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return fallback;
        try { return Integer.parseInt(s.substring(0, i)); } catch (NumberFormatException ex) { return fallback; }
    }

    /** Statuts affichables dans Cover Flow (carrousel + liste des pistes) — retour utilisateur :
     *  élargi de "Tagué" seul à aussi "Identifié"/"En attente". Volontairement PAS "Non identifié"
     *  ni "Erreur" : ces deux-là n'ont structurellement pas de pochette/album exploitable (voir
     *  applyFilter(), qui bascule automatiquement vers la vue Liste quand l'un des deux devient le
     *  filtre actif pendant que Cover Flow est affiché — rester dessus n'y montrerait jamais rien). */
    private static boolean isCoverFlowEligible(FileEntry.Status s) {
        return s == FileEntry.Status.TAGGED || s == FileEntry.Status.IDENTIFIED
            || s == FileEntry.Status.PENDING || s == FileEntry.Status.PROCESSING;
    }

    private static String statusLabel(FileEntry.Status s) {
        return switch (s) {
            case TAGGED     -> I18n.t("Tagué");
            case IDENTIFIED -> I18n.t("Identifié");
            case SKIPPED    -> I18n.t("Non identifié");
            case ERROR      -> I18n.t("Erreur");
            default         -> I18n.t("En attente");
        };
    }

    // ── Configuration de la table ─────────────────────────────────────────────

    private void configureTable() {
        table.setRowHeight(26);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // Sans ça, chaque table.setModel(...) lors d'une bascule liste/arborescence (voir
        // setViewMode()) reconstruirait les colonnes depuis zéro (JTable.
        // createDefaultColumnsFromModel()) et perdrait silencieusement le renderer coloré ci-dessous
        // ainsi que les largeurs restaurées depuis PREFS — les deux modèles exposent le même
        // contrat de colonnes (FileTableModel.COL_SEL..COL_DURATION), donc les colonnes/renderers/
        // largeurs déjà configurés ici restent valables tels quels dans les deux sens.
        table.setAutoCreateColumnsFromModel(false);
        rowSorter = new SafeTableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.getTableHeader().setReorderingAllowed(false);
        // Comparateur texte RAPIDE pour toutes les colonnes String — le comparateur PAR DÉFAUT de
        // TableRowSorter pour une colonne String utilise Collator.getInstance() (comparaison
        // sensible à la locale/aux accents), dont le coût explose sur une grosse bibliothèque :
        // confirmé en direct via jstack sur la bibliothèque réelle de l'utilisateur (285k lignes)
        // — un tri de colonne restait bloqué plusieurs minutes dans Collator.compare(), l'EDT
        // (donc toute l'interface) totalement figé pendant ce temps. Perd la finesse linguistique
        // de l'ordre des accents, mais String.compareToIgnoreCase() est des ordres de grandeur
        // plus rapide (pas de normalisation Unicode ni de règles de collation) — largement
        // préférable à un gel de plusieurs minutes au moindre clic sur un en-tête de colonne.
        java.util.Comparator<String> fastTextCompare = (a, b) ->
            (a != null ? a : "").compareToIgnoreCase(b != null ? b : "");
        for (int col = 1; col <= 9; col++) rowSorter.setComparator(col, fastTextCompare);
        // Colonne Durée : PAS fastTextCompare (compare le texte affiché "m:ss" tel quel — "10:00"
        // se retrouverait AVANT "9:00", '1' < '9' en comparaison de caractères) ni le Collator par
        // défaut (même gel documenté juste au-dessus). Reparse la valeur affichée en secondes pour
        // un tri numériquement correct, aussi rapide que fastTextCompare (pas de Collator).
        java.util.Comparator<String> durationCompare = (a, b) ->
            Integer.compare(parseDurationString(a), parseDurationString(b));
        rowSorter.setComparator(FileTableModel.COL_DURATION, durationCompare);

        // Largeurs par défaut élargies (colonnes 1-5) — les valeurs d'origine tronquaient
        // fréquemment "Artiste Album" ("Various Artists"…) et "Album" (titres de compilation
        // longs, ex. "The Ultimate Music Collection") sur une vraie bibliothèque. N'affecte que
        // les nouvelles installs / colonnes jamais redimensionnées manuellement — colWidth() lit
        // d'abord une largeur sauvegardée (PREFS) si l'utilisateur l'a déjà ajustée lui-même.
        TableColumnModel cm = table.getColumnModel();
        colWidth(cm, 0, 30,  28,  30);   // ☑
        colWidth(cm, 1, 220, 110, 420);  // Fichier
        colWidth(cm, 2, 170, 70,  320);  // Artiste
        colWidth(cm, 3, 190, 70,  340);  // Artiste album
        colWidth(cm, 4, 200, 80,  380);  // Titre
        colWidth(cm, 5, 200, 60,  360);  // Album
        colWidth(cm, 6, 52,  36,  68);   // Année
        colWidth(cm, 7, 120, 50,  220);  // Genre
        colWidth(cm, 8, 44,  28,  60);   // Piste
        colWidth(cm, 9, 130, 80,  220);  // Statut
        colWidth(cm, 10, 56,  40,  90);  // Durée

        // Renderer coloré — mode-aware : en vue arborescence (viewMode == GROUPED), une ligne
        // d'en-tête de groupe n'a pas de FileEntry.Status unique (voir AlbumTreeTableModel.
        // statusOfMemberRow(), qui renvoie null pour ces lignes) — stylée en gras/fond distinct
        // plutôt que teintée par statut, même technique que RenamePreviewDialog pour ses en-têtes.
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                int mr = t.convertRowIndexToModel(row);
                if (viewMode == ViewMode.GROUPED) {
                    boolean header = albumTreeModel.isHeaderRow(mr);
                    c.setFont(c.getFont().deriveFont(header ? Font.BOLD : Font.PLAIN));
                    if (!sel) {
                        c.setBackground(header ? t.getBackground().darker()
                                : rowBg(albumTreeModel.statusOfMemberRow(mr), row));
                    }
                } else if (!sel) {
                    c.setBackground(rowBg(tableModel.get(mr).status, row));
                }
                return c;
            }
        };
        renderer.setBorder(new EmptyBorder(0, 7, 0, 7));

        for (int i = 1; i < cm.getColumnCount(); i++)
            cm.getColumn(i).setCellRenderer(renderer);
    }

    /**
     * Détache temporairement le RowSorter pendant un chargement en masse (scan de dossier).
     * Sans ça, chaque lot de {@code fireTableRowsInserted} (voir {@code FileTableModel.addAll})
     * redéclenche un tri complet O(n log n) de TOUTE la table sur l'EDT — {@code sortsOnUpdates}
     * (déjà à false) ne protège que les MISES À JOUR de lignes, pas les INSERTIONS, qui passent
     * toujours par {@code DefaultRowSorter.rowsInserted()}. Sur une bibliothèque de plusieurs
     * centaines de milliers de fichiers (2 To réels), ce coût grandit à chaque nouveau lot ajouté
     * pendant le scan — confirmé en direct via jstack : l'EDT restait bloqué dans
     * {@code DefaultRowSorter.sort()} pendant qu'un scan tournait, gelant toute l'interface bien
     * que le travail de fond progressait normalement. Compteur de profondeur (pas un simple
     * booléen) car plusieurs scans peuvent tourner en même temps (voir {@code activeScanWorkers})
     * — ne réattacher qu'une fois TOUS terminés, pour un seul tri final au lieu d'un par lot.
     * Doit être appelé sur l'EDT.
     *
     * <p>Ne touche le RowSorter que si la vue liste est active : en vue arborescence, {@code table}
     * n'a de toute façon aucun RowSorter attaché (voir {@link #setViewMode}, pas de tri par clic de
     * colonne pour cette vue) — le sujet est ici SafeTableRowSorter&lt;FileTableModel&gt;, qui ne
     * correspond qu'au modèle plat ; le réattacher pendant que {@code table.getModel() ==
     * albumTreeModel} serait un décalage de type silencieux en plus d'être fonctionnellement faux.
     * La vue arborescence a son propre mécanisme d'absorption des rafales, voir
     * AlbumTreeTableModel (dirty + flushIfDirty() throttlé par refreshStats()).
     */
    private void beginBulkTableUpdate() {
        if (bulkLoadDepth.getAndIncrement() == 0 && viewMode == ViewMode.FLAT) table.setRowSorter(null);
    }

    /** Contrepartie de {@link #beginBulkTableUpdate()}. Doit être appelé sur l'EDT. */
    private void endBulkTableUpdate() {
        if (bulkLoadDepth.decrementAndGet() == 0 && viewMode == ViewMode.FLAT) table.setRowSorter(rowSorter);
    }

    // Marqueurs publiés par loadDirectory() : START dès que ce scan obtient réellement son permis
    // phase1Semaphore (pas seulement mis en file), DONE dès que sa phase 1 se termine — signalent
    // à process() quand détacher/réattacher le RowSorter, sans compter les scans encore en attente.
    private static final Object PHASE1_START_MARKER = new Object();
    private static final Object PHASE1_DONE_MARKER  = new Object();

    // ── Résolution ligne de vue → FileEntry, indépendante du modèle attaché ───
    // Centralise la différence entre les deux modèles que `table` peut porter (tableModel en vue
    // liste, albumTreeModel en vue arborescence — voir setViewMode()) : tout le reste de la classe
    // (menu contextuel, panneau de détail, followProcessing...) passe par ici plutôt que d'appeler
    // tableModel.get(...) directement, ce qui donnerait une entrée fausse/une exception dès que la
    // vue arborescence est active (ses lignes de vue ne correspondent plus 1:1 aux lignes de
    // tableModel — des lignes d'en-tête de groupe s'intercalent).

    /** Ligne de vue (après tri en liste / groupement en arborescence) → son FileEntry, ou null si
     *  c'est une ligne d'en-tête de groupe (vue arborescence uniquement). */
    private FileEntry entryAtViewRow(int viewRow) {
        if (viewRow < 0) return null;
        int mr = table.convertRowIndexToModel(viewRow);
        if (viewMode == ViewMode.GROUPED) return albumTreeModel.fileEntryAt(mr);
        return mr >= 0 && mr < tableModel.getRowCount() ? tableModel.get(mr) : null;
    }

    /** Comme {@link #entryAtViewRow}, mais une ligne d'en-tête de groupe résout vers TOUS ses
     *  membres (édition en lot / actions "tout l'album") plutôt que null. */
    private List<FileEntry> entriesAtViewRow(int viewRow) {
        if (viewRow < 0) return List.of();
        int mr = table.convertRowIndexToModel(viewRow);
        if (viewMode == ViewMode.GROUPED) return albumTreeModel.membersAt(mr);
        return mr >= 0 && mr < tableModel.getRowCount() ? List.of(tableModel.get(mr)) : List.of();
    }

    private boolean isHeaderViewRow(int viewRow) {
        if (viewMode != ViewMode.GROUPED || viewRow < 0) return false;
        return albumTreeModel.isHeaderRow(table.convertRowIndexToModel(viewRow));
    }

    /** Sélection courante par identité de FileEntry — à utiliser autour d'un appel qui reconstruit
     *  le modèle attaché (fireTableDataChanged(), qui efface la sélection de la JTable), voir
     *  refreshStats()/setViewMode(). */
    private java.util.Set<FileEntry> captureTableSelection() {
        java.util.Set<FileEntry> sel = new java.util.LinkedHashSet<>();
        for (int r : table.getSelectedRows()) sel.addAll(entriesAtViewRow(r));
        return sel;
    }

    private void restoreTableSelection(java.util.Set<FileEntry> sel) {
        if (sel.isEmpty()) return;
        List<Integer> viewRows = new ArrayList<>();
        for (FileEntry e : sel) {
            int mr = viewMode == ViewMode.GROUPED ? albumTreeModel.viewRowOf(e) : tableModel.indexOf(e);
            if (mr < 0) continue;
            int vr = table.convertRowIndexToView(mr);
            if (vr >= 0) viewRows.add(vr);
        }
        if (viewRows.isEmpty()) return;
        table.clearSelection();
        for (int vr : viewRows) table.addRowSelectionInterval(vr, vr);
    }

    /**
     * Bascule entre la vue liste plate ({@code tableModel}) et la vue arborescence par album
     * ({@code albumTreeModel}, construite paresseusement) — voir AlbumTreeTableModel pour le détail
     * du regroupement. {@code table.setAutoCreateColumnsFromModel(false)} (voir configureTable())
     * garantit que les colonnes/renderers/largeurs déjà configurés survivent au changement de
     * modèle. {@code groupRowSorter} (GroupSortRowSorter) n'y trie jamais les LIGNES lui-même — sert
     * uniquement à choisir l'ordre des GROUPES via clic d'en-tête, voir sa Javadoc ; seules les
     * colonnes ayant une valeur représentative au niveau d'un album (Fichier/Artiste/Artiste album/
     * Album/Année) sont rendues triables, Piste/Statut/case à cocher n'ont pas de sens à ce niveau.
     */
    private void setViewMode(ViewMode mode) {
        if (mode == viewMode) { syncViewModeControl(); return; }
        ViewMode previous = viewMode;
        // Pas de sélection à sauvegarder/restaurer côté table si on vient de Cover Flow (elle
        // n'a pas bougé pendant qu'elle était cachée derrière le CardLayout).
        java.util.Set<FileEntry> sel = previous != ViewMode.COVER_FLOW ? captureTableSelection() : java.util.Set.of();
        viewMode = mode;

        // Détache toujours la vue arborescence quand on la quitte, Cover Flow y compris — coût nul
        // hors mode GROUPED (voir AlbumTreeTableModel, aucun listener enregistré si non attaché).
        if (previous == ViewMode.GROUPED && mode != ViewMode.GROUPED && albumTreeModel != null) {
            albumTreeModel.detach();
        }

        if (mode == ViewMode.COVER_FLOW) {
            refreshCoverFlowAlbums();
            leftCardLayout.show(leftCards, "coverflow");
        } else {
            if (mode == ViewMode.GROUPED) {
                if (albumTreeModel == null) albumTreeModel = new AlbumTreeTableModel(tableModel);
                albumTreeModel.attach();
                if (groupRowSorter == null) {
                    groupRowSorter = new GroupSortRowSorter(albumTreeModel);
                    for (int c = 0; c < tableModel.getColumnCount(); c++) {
                        boolean sortable = c == FileTableModel.COL_FILE || c == FileTableModel.COL_ARTIST
                                || c == FileTableModel.COL_ALBUM_ARTIST || c == FileTableModel.COL_ALBUM
                                || c == FileTableModel.COL_YEAR || c == FileTableModel.COL_DURATION;
                        groupRowSorter.setSortable(c, sortable);
                    }
                }
                table.setRowSorter(groupRowSorter);
                table.setModel(albumTreeModel);
            } else {
                table.setModel(tableModel);
                table.setRowSorter(rowSorter);
            }
            leftCardLayout.show(leftCards, "table");
            restoreTableSelection(sel);
        }

        syncViewModeControl();
        if (miExpandAllGroups != null) {
            miExpandAllGroups.setEnabled(mode == ViewMode.GROUPED);
            miCollapseAllGroups.setEnabled(mode == ViewMode.GROUPED);
        }
        PREFS.putInt("view.mode", switch (mode) { case GROUPED -> 1; case COVER_FLOW -> 2; default -> 0; });
        refreshStats();
    }

    private void syncViewModeControl() {
        if (rbViewFlat == null) return;
        JRadioButtonMenuItem target = switch (viewMode) {
            case GROUPED    -> rbViewGrouped;
            case COVER_FLOW -> rbViewCoverFlow;
            default         -> rbViewFlat;
        };
        target.setSelected(true);
    }

    private Color rowBg(FileEntry.Status s, int row) {
        boolean alt = (row % 2 == 1);
        Color base  = UIManager.getColor(alt ? "Table.alternateRowColor" : "Table.background");
        if (base == null) base = new Color(43, 45, 48);
        return switch (s) {
            case TAGGED     -> blend(base, COL_TAGGED);
            case IDENTIFIED -> blend(base, COL_IDENTIFIED);
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
        int saved = PREFS.getInt("col." + i + ".w", p);
        c.setMinWidth(mn); c.setMaxWidth(mx);
        c.setPreferredWidth(Math.max(mn, Math.min(mx, saved)));
    }

    /** "3:32" → 212 (secondes) — pour trier numériquement la colonne Durée. "" ou illisible → 0,
     *  jamais d'exception (comparateur de tri, ne doit jamais planter au clic sur l'en-tête). */
    private static int parseDurationString(String s) {
        if (s == null || s.isBlank()) return 0;
        int i = s.indexOf(':');
        if (i < 0) return 0;
        try {
            int m = Integer.parseInt(s.substring(0, i));
            int sec = Integer.parseInt(s.substring(i + 1));
            return m * 60 + sec;
        } catch (NumberFormatException e) { return 0; }
    }

    /** Mémorise la largeur courante de chaque colonne (appelé à la fermeture, comme la géométrie fenêtre). */
    private void saveColumnWidths() {
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++)
            PREFS.putInt("col." + i + ".w", cm.getColumn(i).getWidth());
    }

    // ── Menu contextuel ───────────────────────────────────────────────────────

    private void installContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem miTag    = new JMenuItem("⚡  " + I18n.t("Tagger ce fichier"));
        JMenuItem miRename = new JMenuItem("✏  " + I18n.t("Renommer ce fichier"));
        JMenuItem miReveal = new JMenuItem("📁  " + I18n.t("Ouvrir le dossier parent"));
        JMenuItem miRemove = new JMenuItem("✗  " + I18n.t("Retirer de la liste"));

        miTag.addActionListener(e -> startTagging(true));
        miRename.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            FileEntry entry = entryAtViewRow(row);
            if (entry == null) return;
            if (entry.status == FileEntry.Status.TAGGED) {
                try {
                    // entry.file est le chemin D'ORIGINE au chargement : si le fichier a déjà été
                    // déplacé une première fois (auto-renommage pendant le taguage), entry.file
                    // pointe vers un chemin qui n'existe plus, et cet appel échouait toujours.
                    // Même résolution de racine que TaggingWorker (bibliothèque configurée en
                    // priorité, sinon dossier scanné) pour un comportement cohérent partout.
                    Path curPath   = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
                    Path oldParent = curPath.getParent();
                    String libRoot = Config.get().libraryRoot();
                    Path root = (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                            ? java.nio.file.Paths.get(libRoot)
                            : (entry.scanRoot != null ? entry.scanRoot : oldParent);
                    Path np = new FileRenamer().rename(curPath, entry.activeTags(), currentMask, root);
                    if (np != null) {
                        entry.currentPath = np;
                        if (Config.get().deleteEmptyDirsAfterRename()) {
                            FileRenamer.deleteEmptyAncestors(oldParent, root);
                        }
                        tableModel.update(entry);
                        setStatus(I18n.t("Renommé → %s", np.getFileName()));
                    } else {
                        setStatus(I18n.t("Déjà au bon emplacement — rien à renommer."));
                    }
                } catch (Exception ex) { showError(ex.getMessage()); }
            } else { setStatus(I18n.t("Ce fichier n'a pas encore été tagué.")); }
        });
        miReveal.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            FileEntry revealEntry = entryAtViewRow(row);
            if (revealEntry == null) return;
            // entry.file est le chemin D'ORIGINE au chargement (champ final, ne change jamais) ;
            // entry.currentPath suit le fichier après un renommage/déplacement. Utiliser file ici
            // ouvrait l'ANCIEN dossier — potentiellement vide et supprimé depuis — dès qu'un
            // fichier avait déjà été renommé une fois (même défaut que "Renommer ce fichier").
            File dir = (revealEntry.currentPath != null ? revealEntry.currentPath.toFile() : revealEntry.file)
                    .getParentFile();
            if (dir == null) return;
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win"))        pb = new ProcessBuilder("explorer.exe", dir.getAbsolutePath());
                else if (os.contains("mac"))   pb = new ProcessBuilder("open", dir.getAbsolutePath());
                else                           pb = new ProcessBuilder("xdg-open", dir.getAbsolutePath());
                pb.start();
            } catch (Exception ex) {
                showError(I18n.t("Impossible d'ouvrir le dossier : %s", ex.getMessage()));
            }
        });
        miRemove.addActionListener(e -> {
            // Par référence (Set, retrait par identité) plutôt que par index de ligne : en vue
            // arborescence, les lignes de vue sélectionnées ne correspondent plus 1:1 aux lignes de
            // tableModel (lignes d'en-tête intercalées) — entriesAtViewRow() gère la traduction,
            // tableModel.removeEntries() (déjà utilisé pour le rollback d'un scan annulé) fonctionne
            // identiquement dans les deux vues sans avoir à raisonner sur des indices du tout.
            java.util.Set<FileEntry> toRemove = new java.util.LinkedHashSet<>();
            for (int row : table.getSelectedRows()) toRemove.addAll(entriesAtViewRow(row));
            tableModel.removeEntries(toRemove);
        });

        JMenuItem miAcoustId = new JMenuItem("🎵  " + I18n.t("Soumettre fingerprint AcoustID"));
        miAcoustId.addActionListener(e -> submitAcoustId());

        JMenuItem miMatch = new JMenuItem("🎯  " + I18n.t("Correspondance manuelle…"));
        miMatch.addActionListener(e -> openMatchDialog());

        JMenuItem miCover = new JMenuItem("🖼  " + I18n.t("Gérer la pochette…"));
        miCover.addActionListener(e -> openCoverDialog());

        JMenuItem miRefreshMeta = new JMenuItem("↺  " + I18n.t("Rafraîchir tags + pochette (sélection)"));
        miRefreshMeta.addActionListener(e -> refreshSelectedMeta());

        JMenuItem miMbEdit = new JMenuItem("✏  " + I18n.t("Modifier sur MusicBrainz"));
        miMbEdit.addActionListener(e -> openMbEditPage());

        JMenuItem miMbContrib = new JMenuItem("🤝  " + I18n.t("Contribuer à MusicBrainz…"));
        miMbContrib.addActionListener(e -> openMbContribute());

        menu.add(miTag); menu.add(miRename); menu.addSeparator();
        menu.add(miMatch); menu.add(miCover);
        menu.add(miRefreshMeta); menu.addSeparator();
        menu.add(miMbEdit); menu.add(miMbContrib); menu.addSeparator();
        menu.add(miAcoustId); menu.addSeparator();
        menu.add(miReveal); menu.add(miRemove);

        // Menu réduit pour une ligne d'en-tête de groupe (vue arborescence) — les actions
        // intrinsèquement mono-fichier ci-dessus (Renommer ce fichier, Correspondance manuelle,
        // Soumettre AcoustID...) ne sont pas proposées ici : déplier le groupe et clic-droit sur une
        // piste donne le menu complet ci-dessus, inchangé.
        JPopupMenu headerMenu = new JPopupMenu();
        JMenuItem miSelectAlbum   = new JMenuItem(I18n.t("Tout sélectionner (album)"));
        JMenuItem miDeselectAlbum = new JMenuItem(I18n.t("Tout désélectionner (album)"));
        JMenuItem miTagAlbum      = new JMenuItem("⚡  " + I18n.t("Tagger cet album"));
        JMenuItem miRevealAlbum   = new JMenuItem("📁  " + I18n.t("Ouvrir le dossier parent"));
        headerMenu.add(miSelectAlbum); headerMenu.add(miDeselectAlbum);
        headerMenu.addSeparator();
        headerMenu.add(miTagAlbum); headerMenu.add(miRevealAlbum);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

            // Plier/déplier CE groupe précis en cliquant dessus — pas seulement via "Tout
            // déplier"/"Tout replier" (les seuls déclencheurs qui existaient jusqu'ici :
            // AlbumTreeTableModel.toggleCollapsed() était déjà écrit mais jamais appelé nulle part,
            // un vrai oubli de câblage). Exclu sur la colonne case à cocher (COL_SEL) pour ne pas
            // interférer avec son propre clic-pour-cocher/décocher tout le groupe.
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1 || e.isPopupTrigger()) return;
                if (viewMode != ViewMode.GROUPED) return;
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || col == FileTableModel.COL_SEL) return;
                int modelRow = table.convertRowIndexToModel(row);
                if (albumTreeModel.isHeaderRow(modelRow)) albumTreeModel.toggleCollapsed(modelRow);
            }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row))
                    table.setRowSelectionInterval(row, row);
                if (isHeaderViewRow(row)) {
                    List<FileEntry> members = entriesAtViewRow(row);
                    // Ré-enregistrer les listeners à chaque clic-droit (au lieu d'un seul, fixé à la
                    // construction) : `members` dépend du groupe cliqué, différent à chaque fois —
                    // sans ce nettoyage, les anciens listeners s'empileraient et agiraient sur les
                    // membres d'un groupe précédent.
                    for (var l : miSelectAlbum.getActionListeners()) miSelectAlbum.removeActionListener(l);
                    for (var l : miDeselectAlbum.getActionListeners()) miDeselectAlbum.removeActionListener(l);
                    for (var l : miTagAlbum.getActionListeners()) miTagAlbum.removeActionListener(l);
                    for (var l : miRevealAlbum.getActionListeners()) miRevealAlbum.removeActionListener(l);
                    miSelectAlbum.addActionListener(ev -> {
                        for (FileEntry m : members) { m.selected = true; tableModel.update(m); }
                    });
                    miDeselectAlbum.addActionListener(ev -> {
                        for (FileEntry m : members) { m.selected = false; tableModel.update(m); }
                    });
                    miTagAlbum.addActionListener(ev -> {
                        for (int r = 0; r < tableModel.getRowCount(); r++) {
                            FileEntry other = tableModel.get(r);
                            if (other.selected) { other.selected = false; tableModel.update(other); }
                        }
                        for (FileEntry m : members) { m.selected = true; tableModel.update(m); }
                        startTagging(false);
                    });
                    // "Ouvrir le dossier parent" seulement si tous les membres partagent le même
                    // dossier — un groupe replié sur un dossier (pas de tag album) le fait toujours
                    // par construction ; un vrai album peut avoir ses pistes réparties (multi-CD,
                    // branches mergerfs différentes), auquel cas l'action n'a pas de cible unique.
                    Path commonParent = members.isEmpty() ? null
                            : (members.get(0).currentPath != null ? members.get(0).currentPath : members.get(0).file.toPath()).getParent();
                    boolean uniform = commonParent != null && members.stream().allMatch(m -> {
                        Path p = (m.currentPath != null ? m.currentPath : m.file.toPath()).getParent();
                        return commonParent.equals(p);
                    });
                    miRevealAlbum.setEnabled(uniform);
                    if (uniform) {
                        miRevealAlbum.addActionListener(ev -> {
                            try {
                                String os = System.getProperty("os.name", "").toLowerCase();
                                ProcessBuilder pb;
                                if (os.contains("win"))      pb = new ProcessBuilder("explorer.exe", commonParent.toString());
                                else if (os.contains("mac")) pb = new ProcessBuilder("open", commonParent.toString());
                                else                          pb = new ProcessBuilder("xdg-open", commonParent.toString());
                                pb.start();
                            } catch (Exception ex) {
                                showError(I18n.t("Impossible d'ouvrir le dossier : %s", ex.getMessage()));
                            }
                        });
                    }
                    headerMenu.show(table, e.getX(), e.getY());
                } else {
                    menu.show(table, e.getX(), e.getY());
                }
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
        lblCoverImg.setToolTipText(I18n.t("Double-clic pour gérer la pochette"));
        lblCoverImg.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 11 $defaultFont");
        lblCoverImg.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openCoverDialog();
            }
        });

        // Titre de section "Pochette"
        JLabel coverTitle = new JLabel(I18n.t("POCHETTE"));
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
        JLabel metaTitle = new JLabel(I18n.t("  MÉTADONNÉES"));
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
        panel.add(detailPanel.buildFooter(), BorderLayout.SOUTH);
        return panel;
    }

    /**
     * Fait suivre visuellement la table (scroll + sélection) au fichier en cours de traitement
     * pendant un run — sans ça, l'UI restait statique pendant tout un taguage, notamment pour les
     * fichiers tagués via l'album-first pass qui ne passent jamais par le statut PROCESSING (donc
     * ne déclenchaient jamais l'ancien scroll, qui n'était de toute façon jamais couplé à une
     * sélection : le panneau de détail ne suivait donc jamais rien, même sur le chemin normal).
     * setRowSelectionInterval déclenche le ListSelectionListener existant → refreshDetail()
     * automatiquement, mais un appel explicite ici documente l'intention et reste sans risque
     * (idempotent).
     */
    private void followProcessing(FileEntry entry) {
        int viewRow;
        if (viewMode == ViewMode.GROUPED) {
            // Ligne de son groupe si déplié, sinon la ligne d'en-tête du groupe — jamais forcé
            // déplié (voir AlbumTreeTableModel.viewRowOf() et la note de conception : un groupe
            // replié ne s'ouvre jamais tout seul pendant un taguage en direct). -1 possible si le
            // dernier flushIfDirty() n'a pas encore rattrapé cette entrée (throttlé à 300ms) — dans
            // ce cas on renonce à suivre pour ce tick plutôt que de forcer un aplatissement hors
            // throttle, "suivre" restant une aide visuelle best-effort, pas une garantie stricte.
            int modelRow = albumTreeModel.viewRowOf(entry);
            if (modelRow < 0) return;
            viewRow = table.convertRowIndexToView(modelRow);
        } else {
            int modelRow = tableModel.indexOf(entry);
            if (modelRow < 0) return;
            viewRow = table.convertRowIndexToView(modelRow);
        }
        if (viewRow < 0) return;
        table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
        table.setRowSelectionInterval(viewRow, viewRow);
        refreshDetail();
    }

    private void refreshDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { clearDetail(); return; }

        // Résolution unifiée liste/arborescence : une ligne normale résout vers elle-même, une
        // ligne d'en-tête de groupe (vue arborescence) résout vers TOUS ses membres — permet
        // d'éditer un album entier en lot (populateMulti ci-dessous) juste en sélectionnant son
        // en-tête, sans avoir à le déplier.
        List<FileEntry> entries = new ArrayList<>();
        for (int r : rows) entries.addAll(entriesAtViewRow(r));
        if (entries.isEmpty()) { clearDetail(); return; }

        if (entries.size() == 1) {
            // Mode fichier unique
            FileEntry e  = entries.get(0);
            TagInfo   ti = e.activeTags();
            lblFilePath.setText("  " + (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath());
            detailPanel.populate(ti);
            if (e.result != null) detailPanel.highlightChanges(e.current);
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            loadCoverThumb(f);
        } else {
            // Mode multi-sélection — édition en lot
            List<TagInfo> tags = new ArrayList<>();
            for (FileEntry e : entries) tags.add(e.activeTags());
            lblFilePath.setText(I18n.t("  %d fichiers sélectionnés — les champs vides ne seront pas modifiés", entries.size()));
            detailPanel.populateMulti(tags);
            lblCoverImg.setIcon(null); lblCoverImg.setText(entries.size() + "");
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
            undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Modifier %s", e.filename()));
            // Ne marquer TAGGED qu'APRÈS confirmation d'écriture réussie — sinon un échec
            // d'écriture (permissions, fichier verrouillé...) laissait quand même le statut à
            // TAGGED, jamais annulé (même défaut déjà corrigé dans les autres pipelines).
            if (writeTagsSafe(e, ti)) markManuallyTagged(e, ti, mr);
            setStatus(I18n.t("Tags sauvegardés — %s", e.filename()));
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
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Lot %s", e.filename()));
                if (writeTagsSafe(e, ti)) markManuallyTagged(e, ti, mr);
                saved++;
            }
            setStatus(I18n.t("Tags sauvegardés — %d fichier(s).", saved));
        }
    }

    // ── Correspondance manuelle ───────────────────────────────────────────────

    private void openMatchDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus(I18n.t("Sélectionnez un fichier.")); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new MatchDialog(this, entry, tableModel, this::refreshDetail).setVisible(true);
    }

    // ── Gestion de la pochette ────────────────────────────────────────────────

    private void openCoverDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus(I18n.t("Sélectionnez un fichier.")); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new CoverArtDialog(this, entry, this::refreshDetail).setVisible(true);
    }

    // ── MusicBrainz : modifier + contribuer ──────────────────────────────────

    private void openMbEditPage() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus(I18n.t("Sélectionnez un fichier.")); return; }
        TagInfo ti = tableModel.get(table.convertRowIndexToModel(row)).activeTags();
        String mbid = ti.recordingMbid;
        try {
            String url = mbid.isBlank()
                ? "https://musicbrainz.org/search?query=" +
                    java.net.URLEncoder.encode(ti.artist + " " + ti.title, java.nio.charset.StandardCharsets.UTF_8) +
                    "&type=recording"
                : "https://musicbrainz.org/recording/" + mbid;
            Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) { showError(I18n.t("Impossible d'ouvrir le navigateur : %s", ex.getMessage())); }
    }

    private void openMbContribute() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus(I18n.t("Sélectionnez un fichier.")); return; }
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

    private boolean writeTagsSafe(FileEntry e, TagInfo ti) {
        try {
            File target = e.currentPath != null ? e.currentPath.toFile() : e.file;
            new com.opentagger.TagWriter().write(target, ti);
            return true;
        } catch (Exception ex) {
            showError(I18n.t("Erreur écriture %s : %s", e.filename(), ex.getMessage()));
            return false;
        }
    }

    private void performUndo() {
        // Capturer la description AVANT undo() pour afficher ce qui vient d'être annulé,
        // pas la prochaine action disponible dans la pile.
        String desc = undoManager.undoDescription();
        FileEntry e = undoManager.undo();
        if (e != null) {
            tableModel.update(e); refreshDetail();
            // undo() ne restaurait avant que l'objet TagInfo en mémoire : l'affichage montrait
            // les anciennes valeurs mais le fichier sur disque gardait les tags "annulés", qui
            // réapparaissaient silencieusement au prochain F5/redémarrage. On réécrit donc ici
            // exactement comme applyDetail() le fait pour une édition normale.
            writeTagsSafe(e, e.activeTags());
            setStatus(I18n.t("Annulé : %s", desc));
        }
    }

    private void performRedo() {
        String desc = undoManager.redoDescription();
        FileEntry e = undoManager.redo();
        if (e != null) {
            tableModel.update(e); refreshDetail();
            writeTagsSafe(e, e.activeTags());
            setStatus(I18n.t("Rétabli : %s", desc));
        }
    }

    private void updateUndoButtons() {
        if (btnUndo == null) return;
        btnUndo.setEnabled(undoManager.canUndo());
        btnRedo.setEnabled(undoManager.canRedo());
        String ud = undoManager.undoDescription();
        String rd = undoManager.redoDescription();
        btnUndo.setToolTipText(undoManager.canUndo() ? I18n.t("Annuler : %s (Ctrl+Z)", ud) : I18n.t("Rien à annuler"));
        btnRedo.setToolTipText(undoManager.canRedo() ? I18n.t("Rétablir : %s (Ctrl+Y)", rd) : I18n.t("Rien à rétablir"));
    }

    // ── Barre de statut ───────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        lblStatus = new JLabel(I18n.t(" Prêt"));
        progress  = new JProgressBar(0, 100);
        progress.setPreferredSize(new Dimension(220, 14));
        progress.setStringPainted(true);
        progress.setVisible(false);

        // Indicateur RAM en bas à droite — utile pour surveiller une session de plusieurs jours
        // sur une grosse bibliothèque (demandé explicitement par l'utilisateur).
        lblMemory = new JLabel();
        lblMemory.setForeground(new Color(0x90A4AE));
        lblMemory.putClientProperty("FlatLaf.style", "font: 11 $defaultFont");
        updateMemoryLabel();
        new javax.swing.Timer(2000, e -> updateMemoryLabel()).start();

        JPanel eastPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        eastPanel.setOpaque(false);
        eastPanel.add(lblMemory);
        eastPanel.add(progress);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, sep()),
            new EmptyBorder(5, 12, 5, 12)));
        bar.add(lblStatus,  BorderLayout.WEST);
        bar.add(eastPanel,  BorderLayout.EAST);
        return bar;
    }

    /** Mémoire JVM réellement utilisée / plafond -Xmx — rafraîchi périodiquement (Timer EDT). */
    private void updateMemoryLabel() {
        Runtime rt = Runtime.getRuntime();
        long usedMo = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMo  = rt.maxMemory() / (1024 * 1024);
        lblMemory.setText(I18n.t("RAM : %d / %d Mo", usedMo, maxMo));
    }

    // ── Journal (résultats par fichier, accumulé pendant/après un run) ──────

    /**
     * Contrairement à lblStatus (écrasé à chaque fichier), ce journal accumule chaque résultat
     * final (TAGGED/SKIPPED/ERROR) et reste visible après la fin du run — évite d'avoir à filtrer
     * la colonne Statut après coup ou à recoller des logs console pour diagnostiquer un problème.
     */
    private JPanel buildLogPanel() {
        logModel = new DefaultListModel<>();
        logList  = new JList<>(logModel);
        logList.setVisibleRowCount(6);
        logList.setToolTipText(I18n.t("Double-clic : localiser dans le tableau — Ctrl+C : copier"));
        logList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int idx,
                    boolean sel, boolean foc) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, idx, sel, foc);
                LogEntry e = (LogEntry) v;
                c.setText("[" + e.time() + "] " + e.text());
                if (!sel) {
                    Color fg = switch (e.status()) {
                        case ERROR   -> new Color(230, 90, 90);
                        case SKIPPED -> new Color(210, 160, 40);
                        case TAGGED  -> new Color(100, 200, 130);
                        default      -> UIManager.getColor("List.foreground");
                    };
                    c.setForeground(fg);
                }
                return c;
            }
        });

        // Double-clic : localiser le fichier dans le tableau principal — chaque ligne (sauf les
        // séparateurs de run) correspond 1:1 à un FileEntry déjà affiché ; avant, aucun moyen de
        // relier une ligne du journal à sa ligne dans la table sans chercher le nom à la main.
        logList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int idx = logList.locationToIndex(e.getPoint());
                if (idx < 0) return;
                LogEntry le = logModel.getElementAt(idx);
                if (le.file() != null) followProcessing(le.file());
            }
        });
        // Ctrl+C : copier les lignes sélectionnées — JList ne le fait pas nativement (contrairement
        // à un composant texte), gênant pour un panneau dont le rôle principal est de diagnostiquer
        // des erreurs qu'on veut souvent coller ailleurs.
        logList.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("control C"), "copyLogSelection");
        logList.getActionMap().put("copyLogSelection", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                java.util.List<LogEntry> sel = logList.getSelectedValuesList();
                if (sel.isEmpty()) return;
                String text = sel.stream()
                    .map(le -> "[" + le.time() + "] " + le.text())
                    .collect(java.util.stream.Collectors.joining("\n"));
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            }
        });

        chkLogErrorsOnly = new JCheckBox(I18n.t("Erreurs seulement"));
        chkLogErrorsOnly.addActionListener(e -> rebuildLogModel());
        JButton btnClearLog = new JButton(I18n.t("Vider"));
        btnClearLog.addActionListener(e -> { logHistory.clear(); logModel.clear(); });

        JLabel lblTitle = new JLabel(I18n.t("  Journal"));
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD));
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        headerRight.add(chkLogErrorsOnly);
        headerRight.add(btnClearLog);
        JPanel header = new JPanel(new BorderLayout());
        header.add(lblTitle,     BorderLayout.WEST);
        header.add(headerRight,  BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(logList);
        scroll.setBorder(new MatteBorder(1, 0, 0, 0, sep()));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Ajoute une ligne de séparation au début d'un run (taguage, re-taguage forcé, passe complète). */
    private void logRunStart(String label, int count) {
        LogEntry sep = new LogEntry(nowHms(), I18n.t("── %s : %d fichier(s) ──", label, count),
                FileEntry.Status.PENDING, null);
        logHistory.add(sep);
        if (trimLogHistoryIfNeeded()) return;
        logModel.addElement(sep);
    }

    /** Ajoute le résultat final d'un fichier (ignore les mises à jour PROCESSING transitoires). */
    private void appendLog(FileEntry entry) {
        if (entry.status == FileEntry.Status.PROCESSING) return;
        String statusText = switch (entry.status) {
            case TAGGED  -> "✓ " + I18n.t("Tagué");
            case SKIPPED -> "⚠ " + I18n.t("Ignoré");
            case ERROR   -> "✗ " + I18n.t("Erreur");
            default      -> entry.status.toString();
        };
        if (entry.message != null && !entry.message.isBlank()) statusText += " — " + entry.message;
        LogEntry e = new LogEntry(nowHms(), entry.filename() + " — " + statusText, entry.status, entry);
        logHistory.add(e);
        if (trimLogHistoryIfNeeded()) return;
        if (!chkLogErrorsOnly.isSelected() || e.status() == FileEntry.Status.ERROR)
            logModel.addElement(e);
    }

    private void rebuildLogModel() {
        logModel.clear();
        for (LogEntry e : logHistory)
            if (!chkLogErrorsOnly.isSelected() || e.status() == FileEntry.Status.ERROR)
                logModel.addElement(e);
    }

    /** Purge par lots les plus anciennes entrées au-delà de {@link #MAX_LOG_ENTRIES} (jamais une
     *  à la fois — amortit le coût de resynchronisation de logModel sur des milliers d'ajouts).
     *  Retourne true si une purge a eu lieu (logModel déjà resynchronisé via rebuildLogModel() ;
     *  l'appelant ne doit alors pas ajouter sa propre entrée une 2e fois). */
    private boolean trimLogHistoryIfNeeded() {
        if (logHistory.size() <= MAX_LOG_ENTRIES) return false;
        int toRemove = logHistory.size() - (MAX_LOG_ENTRIES * 3 / 4);
        logHistory.subList(0, toRemove).clear();
        rebuildLogModel();
        return true;
    }

    private static String nowHms() {
        return java.time.LocalTime.now().toString().substring(0, 8);
    }

    /** Texte ETA à ajouter à la barre de progression, ou "" avant que le taux soit mesurable. */
    private String etaText(int doneCount, int totalCount) {
        if (doneCount <= 0 || doneCount >= totalCount) return "";
        long elapsedMs = System.currentTimeMillis() - runStartMillis;
        if (elapsedMs <= 0) return "";
        long remainingMs = elapsedMs * (totalCount - doneCount) / doneCount;
        long remainingSec = remainingMs / 1000;
        if (remainingSec < 60) return I18n.t(" · ~%d s restantes", Math.max(1, remainingSec));
        return I18n.t(" · ~%d min restantes", (remainingSec / 60 + 1));
    }

    // ── Bandeau de scan dossiers (Jaikoz-style) ──────────────────────────────

    private JPanel buildScanBanner() {
        scanEntries.setLayout(new BoxLayout(scanEntries, BoxLayout.Y_AXIS));
        scanEntries.setOpaque(false);

        lblScanSummary = new JLabel();
        lblScanSummary.setForeground(new Color(0x5599FF));
        lblScanSummary.setFont(lblScanSummary.getFont().deriveFont(11f));
        lblScanSummary.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblScanSummary.setToolTipText(I18n.t("Cliquer pour afficher/masquer le détail par dossier"));
        lblScanSummary.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                scanDetailsExpanded = !scanDetailsExpanded;
                updateScanSummaryVisibility();
            }
        });
        scanSummaryRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        scanSummaryRow.setOpaque(false);
        scanSummaryRow.add(lblScanSummary);
        scanSummaryRow.setVisible(false);

        scanBanner.setLayout(new BorderLayout());
        scanBanner.add(scanSummaryRow, BorderLayout.NORTH);
        scanBanner.add(scanEntries,    BorderLayout.CENTER);
        scanBanner.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, sep()));
        scanBanner.setVisible(false);
        return scanBanner;
    }

    /** Regroupe les lignes de scan individuelles sous un résumé repliable dès qu'il y en a PLUS
     *  D'UNE (plusieurs dossiers de démarrage scannés en parallèle, par ex.) — évite d'empiler
     *  une ligne par dossier comme avant. Avec un seul scan actif : comportement inchangé (ligne
     *  directe, pas de résumé). */
    private void updateScanSummaryVisibility() {
        if (activeScanCount > 1) {
            lblScanSummary.setText((scanDetailsExpanded ? "▾ " : "▸ ")
                + I18n.t("%d dossiers en cours de scan…", activeScanCount));
            scanSummaryRow.setVisible(true);
            scanEntries.setVisible(scanDetailsExpanded);
        } else {
            scanSummaryRow.setVisible(false);
            scanEntries.setVisible(true);
        }
        scanBanner.revalidate();
        scanBanner.repaint();
    }

    /** Ajoute une entrée de scan dans le bandeau. Retourne le panneau pour mise à jour ultérieure. */
    private JPanel addScanEntry(String dirName) {
        activeScanCount++;

        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 3));
        row.setOpaque(false);

        JLabel spinner = new JLabel("⠋");
        spinner.setForeground(new Color(0x5599FF));
        spinner.setFont(spinner.getFont().deriveFont(12f));

        JLabel lbl = new JLabel(I18n.t("%s — scan en cours…", dirName));
        lbl.setForeground(UIManager.getColor("Label.foreground"));
        lbl.setFont(lbl.getFont().deriveFont(11f));

        JButton btnStop = new JButton("✕");
        btnStop.setToolTipText(I18n.t("Arrêter ce scan"));
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
            updateScanSummaryVisibility();
            scanBanner.revalidate();
            scanBanner.repaint();
        });
        return row;
    }

    /** Met à jour l'entrée de scan à la fin du chargement et la supprime après 3 s. */
    private void completeScanEntry(JPanel row, String dirName, int total, long tagged, Exception error) {
        activeScanCount--;
        updateScanSummaryVisibility();
        Timer anim = (Timer) row.getClientProperty("anim");
        if (anim != null) anim.stop();
        JLabel lbl = (JLabel) row.getClientProperty("lbl");
        JLabel spinner = (JLabel) row.getComponent(0);

        if (error != null) {
            spinner.setText("✗"); spinner.setForeground(new Color(0xDD4444));
            if (lbl != null) lbl.setText(I18n.t("%s — erreur : %s", dirName, error.getMessage()));
        } else {
            spinner.setText("✓"); spinner.setForeground(new Color(0x4DB6AC));
            if (lbl != null) lbl.setText(I18n.t("%s — %d fichier(s), %d déjà tagué(s)", dirName, total, tagged));
        }
        scanBanner.revalidate();
        // Disparaît après 4 s
        new Timer(4000, e -> {
            ((Timer)e.getSource()).stop();
            scanEntries.remove(row);
            if (scanEntries.getComponentCount() == 0) scanBanner.setVisible(false);
            updateScanSummaryVisibility();
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
        fc.setDialogTitle(I18n.t("Choisir un ou plusieurs dossiers / fichiers audio"));
        fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String n = f.getName().toLowerCase();
                return n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".m4a")
                    || n.endsWith(".ogg") || n.endsWith(".wav") || n.endsWith(".aac")
                    || n.endsWith(".wma") || n.endsWith(".aiff");
            }
            @Override public String getDescription() { return I18n.t("Dossiers et fichiers audio"); }
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
            else if (f.isFile()) loadSingleFile(f);
        }
    }

    /** Charge un fichier audio unique directement (clic-droit OS, drag & drop sur un fichier seul). */
    private void loadSingleFile(File f) {
        if (f == null || !f.isFile()) return;
        Path filePath = f.toPath().toAbsolutePath();
        // Ignorer si déjà dans la table
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry fe = tableModel.get(i);
            if (filePath.equals((fe.currentPath != null ? fe.currentPath : fe.file.toPath()).toAbsolutePath())) return;
        }
        FileEntry entry = new FileEntry(f, new com.opentagger.model.TagInfo());
        entry.scanRoot = f.getParentFile().toPath();
        tableModel.add(entry);
        if (btnRefresh != null) btnRefresh.setEnabled(true);

        // Lire les tags en arrière-plan. entry est déjà affiché/trié par le tableau (ajouté
        // ci-dessus) : on calcule ici mais on ne mute entry QUE dans done() (EDT), sinon même
        // course avec le TableRowSorter que le crash déjà vu 697× en 3 jours (TaggingWorker).
        record LoadResult(com.opentagger.model.TagInfo tags, boolean wasTagged) {}
        new SwingWorker<LoadResult, Void>() {
            @Override protected LoadResult doInBackground() {
                try {
                    com.opentagger.model.TagInfo ti = readTags(f);
                    MetadataCache cache = new MetadataCache();
                    boolean wasTagged = cache.loadTaggedPaths().contains(f.getAbsolutePath());
                    cache.close();
                    return new LoadResult(ti, wasTagged);
                } catch (Exception e) { return new LoadResult(new com.opentagger.model.TagInfo(), false); }
            }
            @Override protected void done() {
                try {
                    LoadResult r = get();
                    entry.current = r.tags();
                    if (r.wasTagged()) entry.status = FileEntry.Status.TAGGED;
                } catch (Exception ignored) {}
                tableModel.update(entry);
                refreshStats();
            }
        }.execute();
    }

    /** Charge un dossier (récursivement) dans la table — toujours en mode ajout. */
    private void loadDirectory(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        final String dirName = dir.getName();
        final Path   root    = dir.toPath();
        final JPanel scanRow = addScanEntry(dirName);
        setStatus(I18n.t("Scan de %s…", dirName));
        // beginBulkTableUpdate() n'est PLUS appelé ici : avec phase1Semaphore (2 marches
        // récursives max à la fois), un dossier de démarrage encore en FILE D'ATTENTE derrière
        // les 2 premiers ne doit pas empêcher le RowSorter de se rattacher pour autant — seul un
        // scan qui a VRAIMENT commencé sa phase 1 doit compter (voir PHASE1_START_MARKER).

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
            // Le RowSorter ne doit rester détaché QUE pendant la phase 1 (fireTableRowsInserted en
            // rafale — la source du gel). La phase 2 n'appelle que tableModel.update() (mise à jour,
            // pas insertion), déjà bon marché (sortsOnUpdates=false) — le garder détaché jusqu'à la
            // toute fin de done() bloquait aussi le FILTRE (chips cliquables/recherche) pendant toute
            // la durée de la phase 2, potentiellement très longue sur 2 To (bug réel signalé par
            // l'utilisateur : cliquer un chip de statut pendant un scan n'avait aucun effet visible).
            // bulkStarted : begin() n'a été appelé que si ce scan a vraiment dépassé la file
            // d'attente de phase1Semaphore (voir PHASE1_START_MARKER) — sinon end() ne doit rien
            // faire (jamais commencé). bulkEnded évite un end() en double (marqueur fin-de-phase-1
            // + filet de sécurité dans done()).
            boolean bulkStarted = false;
            boolean bulkEnded   = false;
            private void endBulkOnce() {
                if (!bulkEnded) { bulkEnded = true; if (bulkStarted) endBulkTableUpdate(); }
            }

            @Override protected int[] doInBackground() throws Exception {
                // ── Phase 1 : lister les fichiers, EN FLUX ──────────────────────────
                // Avant : AudioScanner().scan(dir) parcourait toute l'arborescence et ne renvoyait
                // qu'une fois terminé — sur une grosse bibliothèque (disque externe, dizaines de
                // milliers de fichiers dans des milliers de sous-dossiers), ce parcours seul (avant
                // même la lecture des tags) pouvait prendre un temps notable pendant lequel RIEN
                // n'apparaissait dans le tableau. Publier par lots au fur et à mesure de la
                // découverte fait apparaître les premiers fichiers en continu plutôt qu'en un seul
                // bloc à la fin.
                List<FileEntry> newEntries = new ArrayList<>();
                List<FileEntry> batch = new ArrayList<>();
                final long[] lastBatchMs = { System.currentTimeMillis() };
                // Au plus 2 marches récursives à la fois, tous dossiers de démarrage confondus —
                // voir phase1Semaphore. Bloque CE thread de fond (pas l'EDT) jusqu'à son tour.
                phase1Semaphore.acquire();
                // Détacher le RowSorter seulement maintenant que ce scan a VRAIMENT son permis —
                // pas pendant qu'il patientait en file, ce qui aurait inutilement prolongé la
                // période où le filtre est indisponible pour les autres scans déjà en cours.
                publish(new Object[]{ PHASE1_START_MARKER });
                try {
                new AudioScanner().scan(dir, f -> {
                    if (isCancelled()) return;
                    if (alreadyInTable.contains(f.toPath().toAbsolutePath())) return;
                    FileEntry e = new FileEntry(f, new com.opentagger.model.TagInfo());
                    e.scanRoot = root;
                    newEntries.add(e);
                    batch.add(e);
                    long now = System.currentTimeMillis();
                    if (batch.size() >= 200 || now - lastBatchMs[0] >= 200) {
                        publish(new Object[]{ new ArrayList<>(batch) });
                        batch.clear();
                        lastBatchMs[0] = now;
                    }
                }, this::isCancelled);
                } finally {
                    phase1Semaphore.release();
                }
                if (!batch.isEmpty()) publish(new Object[]{ new ArrayList<>(batch) });
                if (isCancelled()) return new int[]{0, 0};
                // Phase 1 terminée : plus aucune insertion en rafale à venir pour ce scan — signaler
                // à l'EDT de réattacher le RowSorter dès maintenant (voir PHASE1_DONE_MARKER) plutôt
                // que d'attendre la fin de toute la phase 2, pour que le filtre redevienne utilisable
                // pendant la lecture des tags (qui peut prendre longtemps sur 2 To).
                publish(new Object[]{ PHASE1_DONE_MARKER });

                // Enregistrer le dossier pour l'auto-watch (hors EDT — walkFileTree peut être long)
                if (folderWatcher != null) folderWatcher.watch(dir.toPath());

                // ── Cache : juste les chemins → mbid (pas de TagInfo en RAM) ─────
                // Optimisation mémoire : on ne charge pas toute la tagging_history en heap.
                // Les fichiers déjà tagués sont marqués TAGGED ; leurs tags viennent de entry.current
                // (déjà écrits dans le fichier), ce qui est identique à ce qu'on afficherait.
                MetadataCache cache = new MetadataCache();
                java.util.Set<String> taggedPaths = cache.loadTaggedPaths();
                // Chargé une fois (pas un SELECT par fichier) : path → tags déjà lus lors d'un
                // scan précédent + l'empreinte mtime/size de l'époque. Sur une bibliothèque de
                // 100k+ fichiers relancée régulièrement (session de plusieurs jours), la quasi-
                // totalité des fichiers n'ont pas changé depuis le dernier scan — inutile de
                // refaire un AudioFileIO.read() coûteux pour chacun.
                java.util.Map<String, MetadataCache.ScanCacheEntry> scanCacheMap = cache.loadScanCacheMap();

                // ── Phase 2 : lecture des tags (parallèle, pool PARTAGÉ — voir scanTagPool) ──
                // Publication dans l'ORDRE DE FIN RÉEL (ExecutorCompletionService) plutôt que dans
                // l'ordre de soumission : un seul fichier lent (gros FLAC, latence disque externe)
                // ne bloque plus l'affichage de tous les fichiers soumis après lui.
                java.util.concurrent.CompletionService<Object[]> completion =
                    new java.util.concurrent.ExecutorCompletionService<>(scanTagPool);

                for (FileEntry entry : newEntries) {
                    final File f = entry.file;
                    completion.submit(() -> {
                        com.opentagger.model.TagInfo ti;
                        long mtime = f.lastModified();
                        long size  = f.length();
                        MetadataCache.ScanCacheEntry cached = scanCacheMap.get(f.getAbsolutePath());
                        if (cached != null && cached.mtime() == mtime && cached.size() == size) {
                            // Inchangé depuis le dernier scan (même mtime + taille) : on réutilise
                            // les tags déjà lus plutôt que de rouvrir le fichier.
                            //
                            // Exception étroite : durationSec manquant (entrées scan_cache écrites
                            // avant l'ajout de la colonne Durée, 2026-07-13) déclenche une relecture
                            // complète, MAIS throttlée (durationBackfillBudget) — une tentative
                            // sans throttle sur une bibliothèque de 150k+ fichiers a fait grimper le
                            // tas à ~5 Go quasi instantanément (quasiment TOUT le cache existant
                            // précède cette colonne, donc quasiment tout devenait un "cache miss"
                            // d'un coup, seul FfmpegTagIO/readTags() étant déjà corrigés). Limité à
                            // un nombre fixe de rattrapages par scan pour lisser le coût sur
                            // plusieurs sessions plutôt qu'une seule rafale ; les fichiers non
                            // rattrapés cette fois-ci le seront aux scans suivants, jusqu'à ce que
                            // le cache entier ait convergé.
                            if (cached.tagInfo().durationSec <= 0 && durationBackfillBudget.getAndDecrement() > 0) {
                                try { ti = readTags(f); }
                                catch (Exception e) { ti = cached.tagInfo(); }
                                cache.putScanCache(f.getAbsolutePath(), mtime, size, ti);
                            } else {
                                ti = cached.tagInfo();
                            }
                        } else {
                            try { ti = readTags(f); }
                            catch (Exception e) { ti = new com.opentagger.model.TagInfo(); }
                            cache.putScanCache(f.getAbsolutePath(), mtime, size, ti);
                        }
                        boolean wasPreviouslyTagged = taggedPaths.contains(f.getAbsolutePath());
                        return new Object[]{ entry, ti, wasPreviouslyTagged };
                    });
                }
                // PAS de shutdown() ici : scanTagPool est partagé entre tous les scans, pas
                // propre à celui-ci. Idem à l'annulation — abandonner la queue plutôt que tuer un
                // pool utilisé par d'éventuels autres scans en cours.

                int tagged = 0;
                boolean fullyDrained = true;
                for (int i = 0; i < newEntries.size(); i++) {
                    if (isCancelled()) { fullyDrained = false; break; }
                    Object[] result;
                    try { result = completion.take().get(); }
                    catch (Exception e) { continue; }
                    if (Boolean.TRUE.equals(result[2])) tagged++;
                    publish(result);
                }
                // cache reste ouvert tant que des tâches soumises au pool partagé peuvent encore
                // y écrire (putScanCache) — ne fermer qu'une fois certain que le pool est vidé
                // (boucle complète, jamais annulée). Sur annulation, la connexion est laissée
                // ouverte plutôt que risquer une fermeture concurrente avec une tâche en cours.
                if (fullyDrained) cache.close();
                return new int[]{ newEntries.size(), tagged };
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void process(List<Object[]> chunks) {
                for (Object[] chunk : chunks) {
                    if (chunk[0] == PHASE1_START_MARKER) {
                        bulkStarted = true;
                        beginBulkTableUpdate();
                    } else if (chunk[0] == PHASE1_DONE_MARKER) {
                        endBulkOnce();
                    } else if (chunk[0] instanceof List) {
                        // Phase 1 : ajouter toutes les entrées vides d'un coup
                        List<FileEntry> batch = (List<FileEntry>) chunk[0];
                        tableModel.addAll(batch);
                        addedByThisScan.addAll(batch);
                        if (tableModel.getRowCount() > 0 && btnRefresh != null) btnRefresh.setEnabled(true);
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
                    if (tableModel.getRowCount() == 0 && btnRefresh != null) btnRefresh.setEnabled(false);
                    refreshStats();
                    completeScanEntry(scanRow, dirName, 0, 0, null);
                    setStatus(I18n.t("Scan annulé."));
                    endBulkOnce(); // filet de sécurité si annulé avant le marqueur fin-de-phase-1
                    return;
                }
                try {
                    int[] r = get();
                    detectLocalCompilations();
                    setStatus(I18n.t("%d fichier(s) — %d déjà tagué(s)", tableModel.getRowCount(), r[1]));
                    refreshStats();
                    completeScanEntry(scanRow, dirName, r[0], r[1], null);
                    if (Config.get().videoAutoRecover()) autoRecoverVideos(dir);
                } catch (Exception ex) {
                    completeScanEntry(scanRow, dirName, 0, 0, ex);
                    showError(ex.getMessage());
                } finally {
                    endBulkOnce(); // filet de sécurité si le marqueur fin-de-phase-1 n'est jamais arrivé
                }
            }
        };

        // Lier le bouton Stop au worker
        JButton btnStop = (JButton) scanRow.getClientProperty("btnStop");
        if (btnStop != null) btnStop.addActionListener(e -> {
            scanWorker.cancel(false);
            btnStop.setEnabled(false);
            JLabel lbl2 = (JLabel) scanRow.getClientProperty("lbl");
            if (lbl2 != null) lbl2.setText(I18n.t("%s — annulation…", dirName));
        });

        activeScanWorkers.add(scanWorker);
        scanWorker.execute();
    }

    private void startTagging(boolean selOnly) {
        // blockerLabels(TAGGING) couvre d'un coup complétion/passe complète/transcodage/groupement
        // (course jstack confirmée entre Taguage et Transcodage/Complétion, d'où ces gardes à
        // l'origine) — voir WorkerHub.conflictsWith(). Pas de garde sur l'Enregistrement : Tagger
        // et Enregistrer touchent des ensembles de fichiers disjoints par
        // construction (Tagger exclut déjà IDENTIFIED/TAGGED de sa cible, Enregistrer ne prend que
        // les IDENTIFIED et ne les
        // revisite jamais après), donc sûrs de tourner en même temps. Important sur une
        // bibliothèque dont le taguage dure des heures/jours : sans ça, impossible d'enregistrer
        // quoi que ce soit avant la toute fin du run.
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de taguer.", String.join(", ", blockers)));
            return;
        }
        List<FileEntry> toTag = new ArrayList<>();
        if (selOnly) {
            for (int r : table.getSelectedRows())
                toTag.add(tableModel.get(table.convertRowIndexToModel(r)));
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                // IDENTIFIED exclu comme TAGGED : déjà identifié avec succès, juste pas encore
                // enregistré — le re-identifier ici referait le même travail réseau pour rien
                // (voir FileEntry.Status.IDENTIFIED). Utilisez "Enregistrer tout" pour ces fichiers.
                if (e.selected && e.status != FileEntry.Status.TAGGED
                        && e.status != FileEntry.Status.IDENTIFIED) toTag.add(e);
            }
        }
        if (toTag.isEmpty()) {
            if (chkCompleteIncomplete.isSelected()) {
                setStatus(I18n.t("Aucun nouveau fichier à taguer — recherche des fichiers incomplets…"));
                autoCompleteIncomplete(() -> setStatus(I18n.t("Complétion terminée.")));
            } else {
                setStatus(I18n.t("Aucun fichier à taguer (tous déjà tagués — utilisez « Forcer le re-taguage » pour les re-traiter)."));
            }
            return;
        }

        btnTagAll.setEnabled(false); btnTagSel.setEnabled(false);
        btnCancel.setEnabled(true);
        progress.setValue(0); progress.setVisible(true);

        lastStatsRefreshMs = 0; // réinitialiser le throttle à chaque nouveau taguage
        final int totalFiles = toTag.size();
        boolean useAcoustId = chkForceAcoustId.isSelected() || Config.get().useAcoustId();
        runStartMillis = System.currentTimeMillis();
        logRunStart(I18n.t("Taguage"), totalFiles);
        TaggingWorker w = new TaggingWorker(toTag, useAcoustId,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> {
                tableModel.update(entry);
                table.repaint();
                followProcessing(entry);
                if (entry.status != FileEntry.Status.PROCESSING) appendLog(entry);
                // Throttle : rafraîchir les chips au plus toutes les 300ms
                long now = System.currentTimeMillis();
                if (now - lastStatsRefreshMs >= 300) {
                    lastStatsRefreshMs = now;
                    refreshStats();
                }
            }
        );
        w.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int pct = (Integer) evt.getNewValue();
                progress.setValue(pct);
                int fileDone = (int) Math.round(pct * totalFiles / 100.0);
                progress.setString(fileDone + " / " + totalFiles + etaText(fileDone, totalFiles));
                // Refresher les chips à chaque % de progression (≤101 appels au total)
                // plutôt qu'à chaque fichier — évite O(n²) sur 100k+ fichiers.
                refreshStats();
            }
            if (SwingWorker.StateValue.DONE.equals(evt.getNewValue()))
                onTaggingDone(toTag);
        });
        WorkerHub.get().submit(WorkerHub.TaskKind.TAGGING, I18n.t("Taguage"), w, w::stopNow);
    }

    /**
     * Arrête TOUTE opération de fond en cours, pas seulement le taguage — demande explicite :
     * le bouton/menu "Arrêter" doit aussi stopper une complétion d'albums, une passe complète,
     * un groupement d'albums, un enregistrement ou un transcodage en cours. Chaque worker garde
     * son propre mécanisme d'arrêt (stopNow()/cancel(), voir WorkerHub.TaskHandle.cancel()) ;
     * WorkerHub.cancelAll() les appelle tous d'un coup, plus besoin d'énumérer les champs ici.
     */
    private void stopAll() {
        WorkerHub.get().cancelAll();

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
        setStatus(I18n.t("Arrêté.")); resetBtns();
    }

    // ── Enregistrement ("Enregistrer tout") ───────────────────────────────────

    /**
     * Écrit réellement sur le disque tout ce que l'identification (TaggingWorker/
     * AlbumCompletionWorker/InfoCompleterWorker/MatchDialog) a laissé en mémoire (statut
     * IDENTIFIED) — contrepartie de "Tout tagger", qui depuis la séparation Identifier/
     * Enregistrer ne fait plus qu'identifier sans jamais toucher le disque. Voir
     * SaveWorker/TagEnrichment.saveEntry() pour ce que fait effectivement l'enregistrement
     * (pochette, tags, renommage, cache/historique, soumissions MusicBrainz/AcoustID).
     *
     * Même bascule annuler/lancer qu'completeAlbums() : recliquer pendant que ça tourne annule.
     */
    private void saveAll() {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.SAVE);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Enregistrement annulé."));
            return;
        }
        // PAS de garde sur TAGGING ici, volontairement — voir startTagging()/forceRetag() pour le
        // miroir de ce choix : Tagger et Enregistrer touchent des ensembles de fichiers disjoints
        // par construction (Tagger exclut déjà IDENTIFIED/TAGGED de sa cible ; Enregistrer ne
        // prend que les IDENTIFIED et ne les revisite jamais après avoir été identifiés) — sûrs de
        // tourner en même temps, y compris pendant un taguage qui dure des heures/jours
        // (bibliothèque volumineuse) où bloquer Enregistrer jusqu'à la fin du run ferait courir un
        // vrai risque de tout perdre (rien n'est sur le disque tant que non enregistré) en cas de
        // plantage/fermeture prématurée. MetadataCache (SQLite) est déjà configuré pour l'accès
        // concurrent multi-connexions (WAL + busy_timeout). C'est exactement l'exception encodée
        // dans WorkerHub.conflictsWith() pour SAVE — les AUTRES tâches LIBRARY_WRITE restent
        // bloquantes (AlbumCompletionWorker/InfoCompleterWorker touchent aussi des fichiers
        // IDENTIFIED ; Transcodage/regroupements déplacent/réécrivent des fichiers en cours
        // d'enregistrement).
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.SAVE);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant d'enregistrer.", String.join(", ", blockers)));
            return;
        }

        List<FileEntry> toSave = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.selected && e.status == FileEntry.Status.IDENTIFIED) toSave.add(e);
        }
        if (toSave.isEmpty()) {
            setStatus(I18n.t("Aucun fichier identifié à enregistrer (utilisez « Tout tagger » d'abord)."));
            return;
        }

        int maskIndex = Config.get().autoRenameEnabled() ? Config.get().defaultRenameMask() : -1;
        progress.setValue(0); progress.setVisible(true);
        runStartMillis = System.currentTimeMillis();
        logRunStart(I18n.t("Enregistrement"), toSave.size());
        setStatus(I18n.t("Enregistrement de %d fichier(s)…", toSave.size()));

        SaveWorker w = new SaveWorker(toSave, maskIndex,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> SwingUtilities.invokeLater(() -> {
                tableModel.update(entry);
                followProcessing(entry);
                appendLog(entry);
                refreshStats();
            }),
            (doneCount, total) -> SwingUtilities.invokeLater(() -> {
                progress.setValue(doneCount);
                progress.setString(doneCount + "/" + total + etaText(doneCount, total));
            })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    progress.setVisible(false);
                    refreshStats();
                    // Ici, pas après "Tout tagger" : voir le commentaire sur chkAutoGroupCompilations
                    // (buildMenuTagger()) — les fichiers ne deviennent TAGGED (recordingMbid fiable)
                    // qu'à l'Enregistrement. groupByCompilations() garde ses propres gardes
                    // (activeOperations, etc.) ; si quelque chose bloque, la recherche est juste
                    // sautée cette fois-ci, sans forcer.
                    if (chkAutoGroupCompilations.isSelected()) groupByCompilations();
                });
            }
        });
        // SAVE est le seul TaskKind volontairement compatible avec TAGGING en parallèle (voir
        // WorkerHub.conflictsWith).
        WorkerHub.get().submit(WorkerHub.TaskKind.SAVE, I18n.t("Enregistrement"), w, w::stopNow);
    }

    /**
     * Vérifie s'il existe une version plus récente d'OpenTagger, publiée sur le dépôt public
     * séparé opentagger-releases (voir UpdateChecker) — dédié aux binaires, sans lien avec
     * l'historique du dépôt source. Appel automatique au démarrage : silencieux, respecte
     * updateCheckEnabled() et un intervalle minimal de 24h (l'API GitHub non authentifiée est
     * limitée à 60 requêtes/heure par IP). Appel manuel (menu) : toujours vérifié, résultat
     * toujours affiché, y compris "déjà à jour".
     */
    /** Absent jusqu'ici — aucun endroit dans l'appli pour voir la version ou trouver le dépôt sans
     *  passer par un terminal. Réutilise le même idiome que SettingsDialog.apiLinkBtn() (bouton
     *  stylé en lien + Desktop.browse(), avec repli en boîte de dialogue si non supporté). */
    private void showAboutDialog() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(4, 4, 4, 4));

        JLabel title = new JLabel("OpenTagger");
        title.putClientProperty("FlatLaf.style", "font: bold 18 $defaultFont");
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel version = new JLabel(I18n.t("Version %s", Config.get().appVersion()));
        version.putClientProperty("FlatLaf.style", "foreground: #90A4AE");
        version.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel desc = new JLabel(I18n.t(
            "<html>Tagger audio automatique open-source —<br>alternative libre à Jaikoz.</html>"));
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        desc.setBorder(new EmptyBorder(10, 0, 12, 0));

        panel.add(title);
        panel.add(version);
        panel.add(desc);
        panel.add(aboutLinkRow(I18n.t("Code source :"), "https://github.com/pollomax847/opentagger"));
        panel.add(aboutLinkRow(I18n.t("Téléchargements :"), "https://github.com/pollomax847/opentagger-releases"));

        JOptionPane.showMessageDialog(this, panel, I18n.t("À propos d'OpenTagger"), JOptionPane.PLAIN_MESSAGE);
    }

    private JPanel aboutLinkRow(String label, String url) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel(label));
        JButton link = new JButton("🔗 " + url);
        link.putClientProperty("FlatLaf.style", "font: 11 $defaultFont; background: null; arc: 6");
        link.setBorderPainted(false);
        link.setFocusPainted(false);
        link.setContentAreaFilled(false);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addActionListener(e -> {
            try { Desktop.getDesktop().browse(java.net.URI.create(url)); }
            catch (Exception ex) {
                JOptionPane.showMessageDialog(this, I18n.t("Ouvrez : %s", url), I18n.t("Lien"), JOptionPane.INFORMATION_MESSAGE);
            }
        });
        row.add(link);
        return row;
    }

    private void checkForUpdates(boolean manual) {
        if (!manual) {
            if (!Config.get().updateCheckEnabled()) return;
            long since = System.currentTimeMillis() - Config.get().lastUpdateCheckMs();
            if (since < 24L * 3600 * 1000) return;
        }

        new SwingWorker<com.opentagger.UpdateChecker.UpdateInfo, Void>() {
            @Override protected com.opentagger.UpdateChecker.UpdateInfo doInBackground() throws Exception {
                Config.get().setLastUpdateCheckMs(System.currentTimeMillis());
                return new com.opentagger.UpdateChecker().checkLatest();
            }
            @Override protected void done() {
                try {
                    com.opentagger.UpdateChecker.UpdateInfo info = get();
                    if (info == null) {
                        if (manual) setStatus(I18n.t("OpenTagger est déjà à jour (v%s).", Config.get().appVersion()));
                        return;
                    }
                    promptInstallUpdate(info);
                } catch (Exception ex) {
                    if (manual) setStatus(I18n.t("Vérification des mises à jour échouée : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void promptInstallUpdate(com.opentagger.UpdateChecker.UpdateInfo info) {
        int r = JOptionPane.showConfirmDialog(this,
            I18n.t("Nouvelle version v%s disponible.\n\n%s\n\nTélécharger et installer ?",
                    info.version(), info.releaseNotes()),
            I18n.t("Mise à jour disponible"), JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (r != JOptionPane.YES_OPTION) return;

        setStatus(I18n.t("Téléchargement de la mise à jour…"));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                com.opentagger.UpdateChecker checker = new com.opentagger.UpdateChecker();
                java.nio.file.Path temp = checker.download(info.downloadUrl());
                checker.applyUpdate(temp);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    // Même risque qu'un quitApp() classique (voir confirmQuit()) : redémarrer
                    // maintenant tue la JVM en plein milieu d'une opération de fond éventuellement
                    // active — avertir avec la même liste plutôt que de redémarrer en silence.
                    java.util.List<String> ops = activeOperations();
                    int rr = ops.isEmpty()
                        ? JOptionPane.showConfirmDialog(MainFrame.this,
                            I18n.t("Mise à jour installée — redémarrer maintenant ?"),
                            I18n.t("Mise à jour installée"), JOptionPane.YES_NO_OPTION)
                        : JOptionPane.showConfirmDialog(MainFrame.this,
                            I18n.t("<html>Mise à jour installée.<br><br>Encore en cours : <b>%s</b> — "
                                 + "redémarrer maintenant interrompra cette opération.<br><br>"
                                 + "Redémarrer quand même ?</html>", String.join(", ", ops)),
                            I18n.t("Mise à jour installée"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (rr == JOptionPane.YES_OPTION) {
                        try { com.opentagger.UpdateChecker.restartApp(); }
                        catch (Exception ex) { showError(I18n.t("Redémarrage échoué : %s", ex.getMessage())); }
                    } else {
                        setStatus(I18n.t("Mise à jour installée — sera appliquée au prochain lancement."));
                    }
                } catch (Exception ex) {
                    showError(I18n.t("Mise à jour échouée : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    /**
     * Synchronise le nombre d'écoutes ListenBrainz sur les fichiers déjà tagués du tableau — un
     * seul appel réseau couvre tout le lot (voir ListenBrainzSyncWorker), contrairement aux autres
     * actions ci-dessus qui font un appel par fichier. Action manuelle uniquement.
     */
    private void syncListenBrainz() {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.LISTENBRAINZ_SYNC);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Synchronisation ListenBrainz annulée."));
            return;
        }
        if (!WorkerHub.get().blockers(WorkerHub.TaskKind.LISTENBRAINZ_SYNC).isEmpty()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin avant de synchroniser ListenBrainz."));
            return;
        }

        if (Config.get().listenbrainzUsername().isBlank()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Configurez d'abord votre nom d'utilisateur ListenBrainz dans Préférences → APIs."),
                "ListenBrainz", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        List<FileEntry> targets = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status != FileEntry.Status.TAGGED) continue;
            String p = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath().toString();
            if (seenPaths.add(p)) targets.add(e);
        }
        if (targets.isEmpty()) {
            setStatus(I18n.t("Aucun fichier tagué à synchroniser."));
            return;
        }

        progress.setVisible(true);
        progress.setIndeterminate(true);
        setStatus(I18n.t("Synchronisation ListenBrainz…"));

        ListenBrainzSyncWorker w = new ListenBrainzSyncWorker(
            targets,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> SwingUtilities.invokeLater(() -> { tableModel.update(entry); refreshStats(); })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    progress.setIndeterminate(false);
                    progress.setVisible(false);
                    refreshStats();
                });
            }
        });
        WorkerHub.get().submit(WorkerHub.TaskKind.LISTENBRAINZ_SYNC,
                I18n.t("Synchronisation ListenBrainz"), w, () -> w.cancel(false));
    }

    private void forceRetag() {
        // Cette action lance elle aussi un TaggingWorker (via launchForcedTagging) — même garde
        // que startTagging()/autoCompleteIncomplete()/completeAlbums(), sinon un taguage déjà actif
        // continuerait de tourner pendant qu'un second démarre par-dessus.
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de forcer le re-taguage.",
                    String.join(", ", blockers)));
            return;
        }
        // Pas de garde sur SAVE : voir le commentaire de saveAll() — forceRetag() ne cible
        // que des fichiers déjà TAGGED, jamais touchés par un Enregistrement en cours (qui ne
        // prend que les IDENTIFIED) — ensembles disjoints, sûr de tourner en même temps.
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
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier sélectionné à re-taguer.")); return; }

        int confirm = JOptionPane.showConfirmDialog(this,
            I18n.t("%d fichier(s) vont être remis en PENDING et leur cache effacé.\nIls seront re-tagués au prochain lancement du taguage.", targets.size()),
            I18n.t("Forcer le re-taguage"), JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        resetForReidentification(targets, () -> launchForcedTagging(targets, Config.get().useAcoustId()));
    }

    /**
     * Réinitialise une liste de fichiers pour forcer une nouvelle identification : vide leur
     * entrée de cache, supprime les tags MBID sur le fichier disque, remet le statut à PENDING.
     *
     * Effacer cache + MBIDs disque = un AudioFileIO.read/commit PAR FICHIER. Fait en arrière-plan
     * (SwingWorker) — synchrone sur l'EDT, ça gelait toute l'interface le temps de retraiter toute
     * une bibliothèque. Les mutations de FileEntry/tableModel restent sur l'EDT (via
     * publish/process) pour ne pas rouvrir la course avec le TableRowSorter.
     */
    private void resetForReidentification(List<FileEntry> targets, Runnable onDone) {
        setStatus(I18n.t("Réinitialisation de %d fichier(s)…", targets.size()));
        btnTagAll.setEnabled(false); btnTagSel.setEnabled(false);
        new SwingWorker<Void, FileEntry>() {
            @Override protected Void doInBackground() {
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
                        publish(e);
                    }
                } finally { cache.close(); }
                return null;
            }
            @Override protected void process(List<FileEntry> chunk) {
                // Sur l'EDT : réinitialiser le statut et forcer la ré-identification
                // (bypass cache + MB tags existants).
                for (FileEntry e : chunk) {
                    e.status           = FileEntry.Status.PENDING;
                    e.message          = "";
                    e.result           = null;
                    e.candidates       = null;
                    e.forceReidentify  = true;
                    tableModel.update(e);
                }
            }
            @Override protected void done() {
                refreshStats();
                onDone.run();
            }
        }.execute();
    }

    /** Lance le taguage immédiatement sur les fichiers réinitialisés par forceRetag(). */
    private void launchForcedTagging(List<FileEntry> forcedTargets, boolean useAcoustId) {
        lastStatsRefreshMs = 0;
        final int forcedTotal = forcedTargets.size();
        runStartMillis = System.currentTimeMillis();
        logRunStart(I18n.t("Re-taguage forcé"), forcedTotal);
        TaggingWorker w = new TaggingWorker(forcedTargets, useAcoustId,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> {
                tableModel.update(entry);
                table.repaint();
                followProcessing(entry);
                if (entry.status != FileEntry.Status.PROCESSING) appendLog(entry);
            });
        w.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int pct = (Integer) evt.getNewValue();
                progress.setValue(pct);
                int fileDone = (int) Math.round(pct * forcedTotal / 100.0);
                progress.setString(fileDone + " / " + forcedTotal + etaText(fileDone, forcedTotal));
                refreshStats();
            }
            if (SwingWorker.StateValue.DONE.equals(evt.getNewValue()))
                onTaggingDone(forcedTargets);
        });
        btnTagAll.setEnabled(false); btnTagSel.setEnabled(false);
        btnCancel.setEnabled(true);
        progress.setValue(0); progress.setVisible(true);
        WorkerHub.get().submit(WorkerHub.TaskKind.TAGGING, I18n.t("Re-taguage forcé"), w, w::stopNow);
    }

    private void onTaggingDone(List<FileEntry> done) {
        // IDENTIFIED, pas TAGGED : "Tout tagger" n'écrit plus rien sur le disque (façon Picard),
        // voir FileEntry.Status.IDENTIFIED — TAGGED ne sera atteint qu'après "Enregistrer tout".
        long ok   = done.stream().filter(e -> e.status == FileEntry.Status.IDENTIFIED).count();
        long skip = done.stream().filter(e -> e.status == FileEntry.Status.SKIPPED).count();
        long err  = done.stream().filter(e -> e.status == FileEntry.Status.ERROR).count();
        resetBtns();
        detectLocalCompilations();
        refreshStats();
        // Complétion des albums / des fichiers incomplets : reposent toutes deux sur des fichiers
        // déjà TAGGED (anchors MB fiables pour l'une, "déjà identifiés" pour l'autre) — juste après
        // l'identification, rien n'est encore TAGGED (tout reste IDENTIFIED jusqu'à
        // "Enregistrer tout"), donc ces passes ne trouveraient réellement du travail que sur des
        // fichiers déjà enregistrés lors d'une session précédente. Le déclenchement reste ici
        // (comportement historique, inchangé) plutôt que déplacé après Enregistrer : au-delà du
        // périmètre de la séparation Identifier/Enregistrer demandée.
        boolean autoAlbums      = chkAutoCompleteAlbums.isSelected();
        boolean autoIncomplete  = chkCompleteIncomplete.isSelected();
        String suffix = autoIncomplete
                ? I18n.t("  — complétion des fichiers incomplets…")
                : (autoAlbums ? I18n.t("  — complétion albums…") : "");
        setStatus(I18n.t("Terminé — ✓ %d identifié(s) (pas encore enregistré)  ⚠ %d ignoré(s)  ✗ %d erreur(s)", ok, skip, err) + suffix);
        if (autoIncomplete) {
            autoCompleteIncomplete(autoAlbums ? this::completeAlbums : () -> {});
        } else if (autoAlbums) {
            completeAlbums();
        }
    }

    /**
     * Comble les champs manquants (album/année/genre/mood/BPM/paroles/pochette) des fichiers déjà
     * identifiés (TAGGED ou IDENTIFIED — voir InfoCompleterWorker) mais incomplets, sans jamais les
     * réidentifier — même portée que l'ancienne action "Passe complète…", mais déclenchée
     * automatiquement (case à cocher du menu Tagger) plutôt que par un bouton séparé, et donc sans
     * la boîte de confirmation manuelle (l'utilisateur a déjà donné son accord en cochant la case).
     */
    private void autoCompleteIncomplete(Runnable onDone) {
        List<FileEntry> targets = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            // IDENTIFIED inclus comme TAGGED : porte déjà les mêmes données d'identification
            // complètes (juste pas encore écrites sur le disque) — inutile d'attendre un
            // "Enregistrer tout" pour pouvoir compléter les champs manquants en mémoire.
            if (e.status != FileEntry.Status.TAGGED && e.status != FileEntry.Status.IDENTIFIED) continue;
            String p = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath().toString();
            if (!seenPaths.add(p)) continue;
            TagInfo ti = e.result;
            // recordingMbid ajouté séparément d'artistMbid : c'est le champ que teste
            // buildSuggestions() pour le badge "⚠ MBID d'enregistrement manquant" (le plus fréquent
            // en pratique — tout fichier identifié via SongRec/texte sans confirmation MB exacte),
            // mais il n'était testé nulle part ici. Un fichier avec artistMbid déjà rempli (identifié
            // par un autre biais que la recherche MB directe) mais recordingMbid vide n'était donc
            // jamais mis en file pour cette passe, malgré son ⚠ visible dans le journal.
            boolean incomplete = ti == null
                || ti.artistMbid.isBlank()   || ti.recordingMbid.isBlank()
                || ti.album.isBlank()        || ti.year.isBlank()
                || ti.genre.isBlank()        || ti.mood.isBlank()
                || ti.bpm.isBlank()          || ti.lyrics.isBlank();
            if (incomplete) targets.add(e);
        }
        if (targets.isEmpty()) { onDone.run(); return; }

        progress.setVisible(true);
        progress.setMaximum(targets.size());
        progress.setValue(0);
        runStartMillis = System.currentTimeMillis();
        logRunStart(I18n.t("Passe complète"), targets.size());
        setStatus(I18n.t("Complétion de %d fichier(s) incomplet(s)…", targets.size()));

        InfoCompleterWorker w = new InfoCompleterWorker(
            targets,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> SwingUtilities.invokeLater(() -> {
                tableModel.update(entry);
                followProcessing(entry);
                appendLog(entry);
                refreshStats();
            }),
            (doneCount, total) -> SwingUtilities.invokeLater(() -> {
                progress.setValue(doneCount);
                progress.setString(doneCount + "/" + total + etaText(doneCount, total));
            })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    progress.setVisible(false);
                    refreshStats();
                    onDone.run();
                });
            }
        });
        WorkerHub.get().submit(WorkerHub.TaskKind.INFO_COMPLETER, I18n.t("Passe complète"), w, w::stopNow);
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
            setStatus(I18n.t("Compilation locale détectée : %d fichier(s) marqués.", marked));
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
        if (tagged == 0) { setStatus(I18n.t("Aucun fichier tagué à renommer.")); return; }

        List<RenamePreviewDialog.PreviewRow> preview = RenamePreviewDialog.compute(tableModel, currentMask);
        if (preview.isEmpty()) { setStatus(I18n.t("Aucun fichier tagué à renommer.")); return; }

        // ── 2. Afficher l'aperçu — non-modal avec barre de progression ───────
        RenamePreviewDialog dlg = new RenamePreviewDialog(this, preview, buildRenameJob(currentMask, null));
        dlg.setVisible(true);
    }

    private RenamePreviewDialog.RenameJob buildRenameJob(int maskIndex, Path destRoot) {
        return (onProgress, onDone) -> {
            int[] done = {0};
            com.opentagger.MetadataCache cache = new com.opentagger.MetadataCache();

            new SwingWorker<String, FileEntry>() {
                int renamed = 0, skipped = 0, errors = 0;

                @Override
                protected String doInBackground() {
                    FileRenamer renamer = new FileRenamer();
                    boolean cleanupEmptyDirs = Config.get().deleteEmptyDirsAfterRename();
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        FileEntry e = tableModel.get(i);
                        if (e.status != FileEntry.Status.TAGGED) continue;
                        Path oldPath = e.currentPath;
                        Path root = destRoot != null ? destRoot
                                  : (e.scanRoot != null ? e.scanRoot : oldPath.getParent());
                        try {
                            Path newPath = renamer.rename(e.currentPath, e.activeTags(), maskIndex, root);
                            if (newPath != null) {
                                // Nettoyage immédiat, dans ce thread d'arrière-plan, avec la racine
                                // qui correspond réellement à ce fichier — jamais sur l'EDT, jamais
                                // testé contre les racines des AUTRES fichiers (ça forçait
                                // deleteEmptyAncestors à remonter jusqu'à "/" en listant chaque
                                // dossier au passage dès que la racine ne correspondait pas, ce qui
                                // gelait l'appli entière sur une grosse bibliothèque multi-racines).
                                if (cleanupEmptyDirs)
                                    try { FileRenamer.deleteEmptyAncestors(oldPath.getParent(), root); } catch (Exception ignore) {}
                                // e.currentPath est lu par le TableRowSorter sur l'EDT ; on le mute
                                // là-bas, pas ici (même défaut que le crash déjà vu 697× en 3 jours).
                                final Path finalNewPath = newPath;
                                SwingUtilities.invokeLater(() -> e.currentPath = finalNewPath);
                                renamed++;
                                String mbid = cache.getFileTagging(oldPath.toFile().getAbsolutePath());
                                if (mbid != null)
                                    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), mbid);
                            } else {
                                skipped++;
                            }
                        } catch (Exception ex) {
                            errors++;
                            String msg = I18n.t("Renommage : %s", ex.getMessage() != null ? ex.getMessage() : I18n.t("erreur"));
                            SwingUtilities.invokeLater(() -> e.message = msg);
                        }
                        publish(e);
                    }
                    return I18n.t("Renommage — ✓ %d  déjà OK %d  ✗ %d erreur(s)",
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
                    try { setStatus(get()); } catch (Exception ignore) {}
                    onDone.run();
                }
            }.execute();
        };
    }

    // ── Transcodage audio ─────────────────────────────────────────────────────

    private void transcodeFiles(boolean selectionOnly) {
        if (WorkerHub.get().current(WorkerHub.TaskKind.TRANSCODE).isPresent()) {
            JOptionPane.showMessageDialog(this, I18n.t("Un transcodage est déjà en cours."),
                    I18n.t("En cours"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Garde trouvée en direct (jstack a montré TranscodeWorker et AlbumCompletionWorker
        // tourner EN MÊME TEMPS, sans protection) : le transcodage remplace/supprime des fichiers
        // sur lesquels un taguage/complétion/enregistrement en cours pourrait écrire au même
        // moment — voir WorkerHub.conflictsWith() (TRANSCODE fait partie de LIBRARY_WRITE).
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TRANSCODE);
        if (!blockers.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("%s — attendez la fin avant de transcoder.", String.join(", ", blockers)),
                    I18n.t("En cours"), JOptionPane.WARNING_MESSAGE);
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
            setStatus(I18n.t("Aucun fichier à transcoder.")); return;
        }

        com.opentagger.Config cfg = com.opentagger.Config.get();
        com.opentagger.AudioTranscoder.Format fmt =
                com.opentagger.AudioTranscoder.Format.fromId(cfg.transcodeFormat());
        int bitrate  = cfg.transcodeBitrate();
        boolean del  = cfg.transcodeDeleteSource();

        String confirm = I18n.t(
            "<html>Transcoder <b>%d fichier(s)</b> → <b>%s</b>%s ?<br><br>" +
            "<small>Format configuré dans Préférences → Transcodage.</small></html>",
            toTranscode.size(),
            fmt.id.toUpperCase(),
            fmt.hasBitrate ? " " + bitrate + " kbps" : " (lossless)");

        int r = JOptionPane.showConfirmDialog(this, confirm,
                I18n.t("Transcoder"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        int[] done = {0};
        if (btnTranscode != null) btnTranscode.setEnabled(false);
        setStatus("⏳ " + I18n.t("Transcodage… 0 / %d", toTranscode.size()));

        TranscodeWorker w = new TranscodeWorker(toTranscode, tableModel,
            pr -> setStatus("⏳ " + I18n.t("Transcodage %d / %d", pr.done(), pr.total())),
            summary -> {
                setStatus(summary);
                if (btnTranscode != null) btnTranscode.setEnabled(true);
            }
        );
        WorkerHub.get().submit(WorkerHub.TaskKind.TRANSCODE, I18n.t("Transcodage"), w, w::stopNow);
    }

    // ── Compléter les albums ──────────────────────────────────────────────────

    private void completeAlbums() {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.ALBUM_COMPLETION);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Complétion annulée."));
            return;
        }
        // Même garde que startTagging()/autoCompleteIncomplete() : sans elle, ce worker et un
        // TaggingWorker/InfoCompleterWorker en cours écrivent en même temps dans MetadataCache (connexions
        // SQLite distinctes) et mutent les mêmes FileEntry/TagInfo affichés par le tableau.
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.ALBUM_COMPLETION);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de compléter les albums.",
                    String.join(", ", blockers)));
            return;
        }
        setStatus(I18n.t("Complétion des albums en cours…"));
        AlbumCompletionWorker w = new AlbumCompletionWorker(
            tableModel,
            this::setStatus,
            () -> SwingUtilities.invokeLater(() -> setStatus(I18n.t("Complétion albums terminée.")))
        );
        WorkerHub.get().submit(WorkerHub.TaskKind.ALBUM_COMPLETION,
                I18n.t("Complétion des albums"), w, w::stopNow);
    }

    // ── Groupement des albums (ReplayGain d'album, n° piste/disque) ────────────

    private void clusterAlbums() {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.ALBUM_CLUSTER);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Groupement annulé."));
            return;
        }
        java.util.List<String> ops = activeOperations();
        if (!ops.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de grouper les albums.", String.join(", ", ops)));
            return;
        }
        // Condition explicitement demandée par l'utilisateur : cette passe ne doit se lancer que
        // sur une bibliothèque taguée à 100%, jamais sur un sous-ensemble encore en cours de
        // taguage — sinon elle recalcule un ReplayGain d'album / réordonne des pistes sur un
        // groupe incomplet, puis les réécrit à nouveau à la passe suivante quand le reste de
        // l'album se tague (c'est exactement le "beaucoup de conflits" signalé).
        long unfinished = tableModel.allEntries().stream()
                .filter(e -> e.status == FileEntry.Status.PENDING || e.status == FileEntry.Status.PROCESSING)
                .count();
        if (unfinished > 0) {
            setStatus(I18n.t("%d fichier(s) pas encore tagué(s) — la bibliothèque doit être taguée à 100%% avant de grouper les albums.", unfinished));
            return;
        }
        setStatus(I18n.t("Groupement des albums en cours…"));
        AlbumClusterWorker w = new AlbumClusterWorker(
            tableModel,
            this::setStatus,
            () -> SwingUtilities.invokeLater(() -> setStatus(I18n.t("Groupement des albums terminé.")))
        );
        WorkerHub.get().submit(WorkerHub.TaskKind.ALBUM_CLUSTER,
                I18n.t("Groupement des albums"), w, () -> w.cancel(true));
    }

    // ── Grouper par compilations (Stars 80, NRJ, Fun Radio, RFM…) ─────────────

    private void groupByCompilations() {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.COMPILATION_CLUSTER);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Groupement par compilations annulé."));
            return;
        }
        java.util.List<String> ops = activeOperations();
        if (!ops.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de grouper par compilations.", String.join(", ", ops)));
            return;
        }
        // Même garde que clusterAlbums() et pour la même raison : ne traiter qu'une bibliothèque
        // taguée à 100%, jamais un sous-ensemble encore en cours de taguage.
        long unfinished = tableModel.allEntries().stream()
                .filter(e -> e.status == FileEntry.Status.PENDING || e.status == FileEntry.Status.PROCESSING)
                .count();
        if (unfinished > 0) {
            setStatus(I18n.t("%d fichier(s) pas encore tagué(s) — la bibliothèque doit être taguée à 100%% avant de grouper par compilations.", unfinished));
            return;
        }
        setStatus(I18n.t("Recherche de correspondances de compilations…"));
        CompilationClusterWorker w = new CompilationClusterWorker(
            tableModel,
            this::setStatus,
            matches -> SwingUtilities.invokeLater(() -> {
                if (matches.isEmpty()) {
                    setStatus(I18n.t("Aucune correspondance de compilation trouvée."));
                } else {
                    new CompilationMatchDialog(this, matches, tableModel).setVisible(true);
                }
            })
        );
        // Cette migration comble au passage le seul vrai trou de comportement qui existait ici :
        // stopAll()/confirmQuit() ignoraient cette passe jusqu'ici — voir WorkerHub, LIBRARY_WRITE.
        WorkerHub.get().submit(WorkerHub.TaskKind.COMPILATION_CLUSTER,
                I18n.t("Groupement par compilations"), w, () -> w.cancel(true));
    }

    // ── Organiser en dossiers ─────────────────────────────────────────────────

    private Path organizeDestRoot;
    private int  organizeMask = 3; // défaut : AlbumArtist/Album/Track - Artist - Title

    private void organizeFiles() {
        // Déplace des fichiers sur disque — même risque de course qu'un transcodage si un autre
        // worker (taguage/complétion/passe complète/transcodage/scan) touche encore ces fichiers.
        java.util.List<String> ops = activeOperations();
        if (!ops.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant d'organiser les fichiers.", String.join(", ", ops)));
            return;
        }
        long tagged = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++)
            if (tableModel.get(i).status == FileEntry.Status.TAGGED) tagged++;
        if (tagged == 0) { setStatus(I18n.t("Aucun fichier tagué à organiser.")); return; }

        // 1. Choisir le dossier de destination
        JFileChooser fc = new JFileChooser(organizeDestRoot != null
                ? organizeDestRoot.toFile()
                : javax.swing.filechooser.FileSystemView.getFileSystemView().getDefaultDirectory());
        fc.setDialogTitle(I18n.t("Dossier de destination de la bibliothèque musicale"));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setApproveButtonText(I18n.t("Choisir ce dossier"));
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
        if (folderLabels.isEmpty()) { setStatus(I18n.t("Aucun masque avec sous-dossiers disponible.")); return; }

        int defaultIdx = folderIndexes.indexOf(organizeMask);
        if (defaultIdx < 0) defaultIdx = 0;
        String[] labelsArr = folderLabels.toArray(new String[0]);
        String chosen = (String) JOptionPane.showInputDialog(this,
                I18n.t("Structure de dossiers :\n(destination : %s)", organizeDestRoot),
                I18n.t("Organiser en dossiers"),
                JOptionPane.PLAIN_MESSAGE, null, labelsArr, labelsArr[defaultIdx]);
        if (chosen == null) return;
        for (int i = 0; i < labelsArr.length; i++)
            if (labelsArr[i].equals(chosen)) { organizeMask = folderIndexes.get(i); break; }

        // 3. Aperçu
        List<RenamePreviewDialog.PreviewRow> preview =
                RenamePreviewDialog.compute(tableModel, organizeMask, organizeDestRoot);
        if (preview.isEmpty()) { setStatus(I18n.t("Aucun fichier à organiser.")); return; }

        RenamePreviewDialog dlg = new RenamePreviewDialog(this,
                preview, I18n.t("Organiser en dossiers"), buildRenameJob(organizeMask, organizeDestRoot));
        dlg.setVisible(true);
    }

    private void chooseMask() {
        FileRenamer renamer = new FileRenamer();
        int n = renamer.maskCount();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++)
            labels[i] = String.format("[%2d] %s", i, renamer.maskLabel(i));
        String chosen = (String) JOptionPane.showInputDialog(this,
            I18n.t("Masque de renommage actif :"), I18n.t("Masques disponibles"),
            JOptionPane.PLAIN_MESSAGE, null, labels, labels[Math.min(currentMask, n-1)]);
        if (chosen == null) return;
        for (int i = 0; i < labels.length; i++)
            if (labels[i].equals(chosen)) { currentMask = i; break; }
        maskManuallyChosen = true;
        updateMaskLabel();
        setStatus(I18n.t("Masque actif : %s", renamer.maskLabel(currentMask)));
    }

    /**
     * Rappelé après CHAQUE sauvegarde des Préférences (OK ou Appliquer, voir SettingsDialog.save())
     * — pas seulement à la fermeture de l'appli. Resynchronise ce que buildHeader()/le constructeur
     * ne lisent normalement qu'une fois : la barre d'outils secondaire et le masque de renommage
     * par défaut (seulement si l'utilisateur ne l'a jamais choisi manuellement via chooseMask() —
     * sinon on écraserait un choix explicite fait en cours de session).
     */
    private void onPreferencesSaved() {
        populateSecondaryToolbar();
        if (!maskManuallyChosen) {
            int fresh = Config.get().defaultRenameMask();
            if (fresh != currentMask) {
                currentMask = fresh;
                updateMaskLabel();
            }
        }
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
        // Opus/AAC/WV/APE : jaudiotagger ne sait pas les lire du tout (voir FfmpegTagIO) — inutile
        // de tenter AudioFileIO.read() en sachant qu'il va échouer.
        if (com.opentagger.FfmpegTagIO.handles(f)) return com.opentagger.FfmpegTagIO.read(f);
        TagInfo ti = new TagInfo();
        try {
            AudioFile af = AudioFileIO.read(f);
            // Avant le "tag == null" ci-dessous : l'en-tête audio (durée, débit...) est
            // indépendant du tag lui-même, un fichier sans AUCUN tag a quand même une durée.
            try {
                if (af.getAudioHeader() != null) ti.durationSec = af.getAudioHeader().getTrackLength();
            } catch (Exception ignored) {}
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

    // ── Filtrage rapide (recherche + chips de statut cliquables dans buildStatsStrip()) ──────

    private void applyFilter() {
        // Cover Flow ne montre jamais les statuts Non identifié/Erreur (voir isCoverFlowEligible())
        // — y rester filtré dessus n'afficherait jamais rien d'utile, juste un carrousel vide.
        // Retour utilisateur : bascule automatiquement vers la vue Liste dans ce cas précis, où le
        // filtre reste au moins exploitable (lignes réelles, pas un écran noir).
        if (viewMode == ViewMode.COVER_FLOW
                && (activeStatusFilter == FILTER_SKIPPED || activeStatusFilter == FILTER_ERROR)) {
            setViewMode(ViewMode.FLAT);
        }

        String text   = tfFilter.getText().trim();
        int statusSel = activeStatusFilter; // 0=tous,1=pending,2=tagged,3=skipped,4=error
        int fieldSel  = cbFilterField.getSelectedIndex(); // 0=tous les champs, 1..7=colonne précise

        // Filtre appliqué directement dans FileTableModel (pas via RowSorter.setRowFilter) — voir
        // le commentaire en tête de FileTableModel : un RowFilter actif sur le RowSorter rend
        // chaque insertion ~45x plus lente (mesuré), ce qui gelait le filtre pendant un scan actif.
        // Ici, la recherche texte lit directement les champs (activeTags()), pas les colonnes
        // rendues — mêmes champs que l'ancien filtre (2 à 8 = Artiste→Piste, jamais le nom de
        // fichier), juste sans passer par une regex sur le texte affiché.
        java.util.function.Predicate<FileEntry> pred = e -> true;

        if (!text.isBlank()) {
            String needle = text.toLowerCase();
            pred = pred.and(e -> {
                TagInfo ti = e.activeTags();
                if (fieldSel == 0) {
                    return containsIgnoreCase(ti.artist, needle) || containsIgnoreCase(ti.albumArtist, needle)
                        || containsIgnoreCase(ti.title, needle)  || containsIgnoreCase(ti.album, needle)
                        || containsIgnoreCase(ti.year, needle)   || containsIgnoreCase(ti.genre, needle)
                        || containsIgnoreCase(ti.track, needle);
                }
                String field = switch (fieldSel) {
                    case 1 -> ti.artist;
                    case 2 -> ti.albumArtist;
                    case 3 -> ti.title;
                    case 4 -> ti.album;
                    case 5 -> ti.year;
                    case 6 -> ti.genre;
                    case 7 -> ti.track;
                    default -> "";
                };
                return containsIgnoreCase(field, needle);
            });
        }

        if (statusSel != FILTER_ALL) {
            java.util.function.Predicate<FileEntry> statusPred = switch (statusSel) {
                case FILTER_PENDING    -> e -> e.status == FileEntry.Status.PENDING || e.status == FileEntry.Status.PROCESSING;
                case FILTER_IDENTIFIED -> e -> e.status == FileEntry.Status.IDENTIFIED;
                case FILTER_TAGGED     -> e -> e.status == FileEntry.Status.TAGGED;
                case FILTER_SKIPPED    -> e -> e.status == FileEntry.Status.SKIPPED;
                case FILTER_ERROR      -> e -> e.status == FileEntry.Status.ERROR;
                default             -> e -> true;
            };
            pred = pred.and(statusPred);
        }

        boolean noFilter = text.isBlank() && statusSel == FILTER_ALL;
        tableModel.setFilter(noFilter ? null : pred);

        // Mettre à jour les chips de stats pour refléter la vue filtrée
        refreshStats();
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase().contains(needleLower);
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
        String label = switch (statuses[0]) {
            case TAGGED     -> I18n.t("tagué(s)");
            case IDENTIFIED -> I18n.t("identifié(s), pas encore enregistré(s)");
            case SKIPPED    -> I18n.t("non identifié(s)");
            case ERROR      -> I18n.t("en erreur");
            default         -> I18n.t("en attente");
        };
        setStatus(n > 0 ? I18n.t("%d fichier(s) %s sélectionné(s).", n, label)
                        : I18n.t("Aucun fichier %s dans la liste.", label));
        // Aussi basculer le filtre visuel (chip) pour les voir clairement
        if (n > 0) {
            activeStatusFilter = switch (statuses[0]) {
                case TAGGED     -> FILTER_TAGGED;
                case IDENTIFIED -> FILTER_IDENTIFIED;
                case SKIPPED    -> FILTER_SKIPPED;
                case ERROR      -> FILTER_ERROR;
                default         -> FILTER_PENDING;
            };
            applyFilter();
        }
    }

    // ── Export CSV ───────────────────────────────────────────��────────────────

    private void exportCsv() {
        if (tableModel.getRowCount() == 0) { setStatus(I18n.t("Aucun fichier à exporter.")); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("opentagger_export.csv"));
        fc.setDialogTitle(I18n.t("Exporter en CSV"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        setStatus(I18n.t("Export CSV en cours…"));
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try (PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
                    // En-tête BOM pour Excel
                    pw.print('﻿');
                    pw.println(I18n.t("Fichier,Artiste,Artiste Album,Titre,Album,Année,Genre,Piste,Disque,Compositeur,Chef,MBID,Statut"));
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
                try { get(); setStatus(I18n.t("CSV exporté → %s", out.getName())); }
                catch (Exception ex) { showError(I18n.t("Export CSV : %s", ex.getMessage())); }
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
        if (tagged == 0) { setStatus(I18n.t("Aucun fichier tagué à exporter.")); return; }

        JFileChooser fc = new JFileChooser();
        String ext = format.equalsIgnoreCase("xspf") ? ".xspf" : ".m3u";
        fc.setSelectedFile(new File("playlist" + ext));
        fc.setDialogTitle(I18n.t("Exporter playlist %s", format.toUpperCase()));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(ext))
            out = new File(out.getAbsolutePath() + ext);

        List<FileEntry> all = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));

        final File outFinal = out;
        setStatus(I18n.t("Export %s en cours…", format.toUpperCase()));
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                return format.equalsIgnoreCase("xspf")
                    ? com.opentagger.PlaylistExporter.exportXspf(all, outFinal)
                    : com.opentagger.PlaylistExporter.exportM3u(all, outFinal);
            }
            @Override protected void done() {
                try {
                    int n = get();
                    setStatus(I18n.t("%s exporté — %d piste(s) → %s", format.toUpperCase(), n, outFinal.getName()));
                } catch (Exception ex) {
                    showError(I18n.t("Export %s : %s", format.toUpperCase(), ex.getMessage()));
                }
            }
        }.execute();
    }

    // ── Podcast ───────────────────────────────────────────────────────────────

    private void openPodcastDialog() {
        if (tableModel.getRowCount() == 0) { setStatus(I18n.t("Chargez d'abord les fichiers audio à tagger.")); return; }
        List<com.opentagger.model.FileEntry> all = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));
        new PodcastDialog(this, all, tableModel).setVisible(true);
        refreshStats();
    }

    // ── Récupération audio de vidéos non reconnues ────────────────────────────

    private void openVideoRecoveryDialog() {
        new VideoRecoveryDialog(this).setVisible(true);
    }

    /**
     * Version automatique du dialogue ci-dessus — déclenchée après chaque scan de dossier réussi
     * (Ouvrir dossier ET Rafraîchir, qui passent tous les deux par loadDirectory()) plutôt que
     * d'obliger l'utilisateur à pointer manuellement le même dossier dans un dialogue séparé à
     * chaque fois (retour explicite : trop pénible à gérer soi-même). Silencieuse en cas d'absence
     * de vidéos (cas normal, ne doit pas interrompre le flux) ; sinon tourne en fond, comme
     * complétionAlbums()/autoCompleteIncomplete(), avec la progression dans la barre de statut.
     * Un seul passage à la fois : si une passe précédente tourne encore (dossier volumineux, ou
     * plusieurs dossiers ouverts coup sur coup), on ne la relance pas par-dessus — VideoScanner
     * exclut déjà "Convertis"/"Non identifié" de sa récursion, donc un prochain scan (rafraîchir,
     * ou la fin du scan suivant) retrouvera de toute façon tout ce qui reste à traiter.
     */
    private void autoRecoverVideos(File dir) {
        if (WorkerHub.get().current(WorkerHub.TaskKind.VIDEO_RECOVERY).isPresent()) return;
        new SwingWorker<List<File>, Void>() {
            @Override protected List<File> doInBackground() { return new VideoScanner().scan(dir); }
            @Override protected void done() {
                List<File> videos;
                try { videos = get(); } catch (Exception ex) { return; }
                if (videos.isEmpty()) return;
                // Le scan ci-dessus prend du temps : revérifier juste avant de soumettre, au cas
                // où une récupération aurait démarré entre-temps (dialogue manuel, ou un autre
                // dossier ouvert coup sur coup) — sinon submit() lèverait IllegalStateException.
                if (WorkerHub.get().current(WorkerHub.TaskKind.VIDEO_RECOVERY).isPresent()) return;
                // onProgress appelé depuis SwingWorker.process(), déjà garanti sur l'EDT — même
                // motif que VideoRecoveryDialog.onStart().
                VideoRecoveryWorker w = new VideoRecoveryWorker(videos, dir.toPath(), MainFrame.this::setStatus);
                w.addPropertyChangeListener(evt -> {
                    if ("state".equals(evt.getPropertyName())
                            && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                        setStatus(I18n.t("Vidéos (%s) — %d converti(s), %d non reconnu(s), %d erreur(s).",
                                dir.getName(), w.getConverted(), w.getUnrecognized(), w.getErrors()));
                    }
                });
                // Même TaskKind que VideoRecoveryDialog.onStart() : les deux points de lancement
                // (auto ici, manuel via le dialogue) partagent désormais un seul suivi — avant,
                // deux champs indépendants permettaient à une double instance de tourner en même
                // temps (dialogue fermé sans attendre + nouveau dossier ouvert aussitôt).
                WorkerHub.get().submit(WorkerHub.TaskKind.VIDEO_RECOVERY,
                        I18n.t("Récupération vidéo : %s", dir.getName()), w, w::stopNow);
            }
        }.execute();
    }

    // ── Détection de doublons ─────────────────────────────────────────────────

    private void detectDuplicates() {
        // La détection elle-même ne fait que lire — le vrai risque est la suppression définitive
        // (DuplicatesDialog.deleteSelected()) pendant qu'un autre worker écrit encore sur les mêmes
        // fichiers. Le dialogue étant modal, vérifier ICI (avant l'ouverture) suffit : aucune
        // nouvelle opération ne peut démarrer depuis MainFrame tant qu'il reste ouvert.
        java.util.List<String> ops = activeOperations();
        if (!ops.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de détecter les doublons.", String.join(", ", ops)));
            return;
        }
        if (tableModel.getRowCount() == 0) { setStatus(I18n.t("Aucun fichier chargé.")); return; }
        List<FileEntry> all = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));
        List<DuplicateDetector.DuplicateGroup> groups = DuplicateDetector.detect(all);
        if (groups.isEmpty()) { setStatus(I18n.t("Aucun doublon détecté.")); LOG.info("[Doublons] Aucun doublon parmi " + all.size() + " fichiers."); return; }
        int total = groups.stream().mapToInt(g -> g.files().size()).sum();
        LOG.info("[Doublons] " + groups.size() + " groupe(s), " + total + " fichier(s) sur " + all.size() + " analysés.");
        for (DuplicateDetector.DuplicateGroup g : groups) {
            LOG.info("[Doublons] Groupe " + g.confidence().badge + ": " + g.files().stream()
                .map(e -> (e.currentPath != null ? e.currentPath : e.file.toPath()).getFileName().toString())
                .collect(java.util.stream.Collectors.joining(" | ")));
        }
        setStatus(I18n.t("%d groupe(s) de doublons, %d fichier(s) concerné(s).", groups.size(), total));
        new DuplicatesDialog(this, groups, tableModel).setVisible(true);
        refreshStats(); // dialog modal → bloquant, rafraîchir après fermeture
    }

    private void deleteErrorFiles() {
        // "Fichier introuvable" ne veut PAS dire fichier corrompu : c'était surtout, avant le
        // correctif du scan des fichiers temporaires ot_m4a_*/ot_fix_*, l'entrée fantôme d'un
        // fichier déjà reparti — il n'y a rien à "supprimer du disque", juste une ligne fantôme
        // à retirer du tableau. On sépare donc ce cas des vraies erreurs de lecture/format, pour
        // ne pas dire "ces fichiers sont corrompus" à propos de fichiers qui n'ont jamais existé
        // sous ce statut, et pour ne pas proposer une suppression disque qui n'a pas de sens ici.
        //
        // 3e catégorie "mislabeled" ajoutée après un vrai cas trouvé en vérifiant : "Belsunce
        // Breakdown.mp3"/"Sexy Love.mp3" etc. ne sont PAS corrompus, juste étiquetées avec la
        // mauvaise extension (contenu M4A/AAC réel sous ".mp3", voir AudioFormatCheck) — sans
        // cette distinction, ce bouton les aurait supprimées DÉFINITIVEMENT alors qu'il suffit de
        // les renommer. Détectées via le marqueur de message posé par
        // TagWriter.translateKnownJaudiotaggerBug() plutôt que de rappeler AudioFormatCheck ici
        // (déjà exécuté une fois pour produire ce message, pas la peine de relancer ffprobe).
        List<FileEntry> missing    = new ArrayList<>();
        List<FileEntry> mislabeled = new ArrayList<>();
        List<FileEntry> corrupt    = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status != FileEntry.Status.ERROR) continue;
            if ("Fichier introuvable".equals(e.message)) missing.add(e);
            else if (e.message != null && e.message.contains("ne correspond pas à l'extension")) mislabeled.add(e);
            else corrupt.add(e);
        }

        if (missing.isEmpty() && mislabeled.isEmpty() && corrupt.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Aucun fichier illisible dans la liste."),
                I18n.t("Fichiers illisibles"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (!missing.isEmpty()) {
            int ok = JOptionPane.showConfirmDialog(this,
                I18n.t("%d fichier(s) introuvable(s) sur le disque (déjà déplacés/supprimés) "
                + "vont être retirés de la liste.\nAucun fichier ne sera supprimé — ce ne sont que des lignes fantômes.", missing.size()),
                I18n.t("Fichiers introuvables"), JOptionPane.OK_CANCEL_OPTION);
            if (ok == JOptionPane.OK_OPTION) {
                for (FileEntry e : missing) {
                    int idx = tableModel.indexOf(e);
                    if (idx >= 0) tableModel.remove(idx);
                }
                setStatus(I18n.t("%d ligne(s) fantôme(s) retirée(s) de la liste.", missing.size()));
            }
        }

        if (!mislabeled.isEmpty()) {
            StringBuilder mb = new StringBuilder(I18n.t(
                "<html><b>%d fichier(s) écarté(s) de la suppression</b> — pas corrompus, juste "
                + "une mauvaise extension (contenu réel différent du nom de fichier) :<br><br>", mislabeled.size()));
            int shownM = Math.min(mislabeled.size(), 8);
            for (int i = 0; i < shownM; i++) {
                File f = mislabeled.get(i).currentPath != null
                    ? mislabeled.get(i).currentPath.toFile() : mislabeled.get(i).file;
                mb.append("&nbsp;• ").append(f.getName()).append("<br>");
            }
            if (mislabeled.size() > shownM)
                mb.append(I18n.t("&nbsp;… et %d autre(s)<br>", mislabeled.size() - shownM));
            mb.append(I18n.t("<br><i>À renommer manuellement avec la bonne extension, pas à supprimer.</i></html>"));
            JOptionPane.showMessageDialog(this, mb.toString(),
                I18n.t("Fichiers mal étiquetés (non supprimés)"), JOptionPane.INFORMATION_MESSAGE);
        }

        if (corrupt.isEmpty()) return;

        // Construire le message de confirmation (uniquement les vraies erreurs de lecture/format)
        StringBuilder sb = new StringBuilder(
                I18n.t("<html>Déplacer <b>%d fichier(s) illisible(s)</b> dans la corbeille ?<br><br>", corrupt.size()));
        int shown = Math.min(corrupt.size(), 8);
        for (int i = 0; i < shown; i++) {
            FileEntry e = corrupt.get(i);
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            sb.append("&nbsp;• <font color='#cc4444'>").append(f.getName()).append("</font>");
            if (e.message != null && !e.message.isBlank())
                sb.append(" <i>(").append(e.message).append(")</i>");
            sb.append("<br>");
        }
        if (corrupt.size() > shown)
            sb.append(I18n.t("&nbsp;… et %d autre(s)<br>", corrupt.size() - shown));
        sb.append(I18n.t("<br><i>Ces fichiers sont corrompus ou dans un format non supporté.</i></html>"));

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
            I18n.t("Supprimer les fichiers illisibles"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        // Corbeille système plutôt que File.delete() définitif (même changement que DuplicatesDialog
        // avant elle, même raison : un faux positif dans "corrupt" — ex. un futur format non encore
        // couvert par AudioFormatCheck — ne doit jamais être irrécupérable en un clic).
        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        boolean trashSupported = desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
        int deleted = 0, failDel = 0;
        for (FileEntry e : corrupt) {
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            int idx = tableModel.indexOf(e);
            boolean moved = trashSupported ? desktop.moveToTrash(f) : f.delete();
            if (moved) {
                if (idx >= 0) tableModel.remove(idx);
                deleted++;
            } else {
                failDel++;
            }
        }
        String where = trashSupported ? I18n.t("déplacé(s) dans la corbeille") : I18n.t("supprimé(s)");
        String msg = I18n.t("%d fichier(s) illisible(s) %s", deleted, where);
        if (failDel > 0) msg += I18n.t(", %d échec(s) (permission refusée ?)", failDel);
        setStatus(msg);
        JOptionPane.showMessageDialog(this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void submitAcoustId() {
        if (!com.opentagger.AcoustIdSubmitter.isAvailable()) {
            showError(I18n.t("fpcalc introuvable — installez chromaprint pour soumettre une empreinte."));
            return;
        }

        // Pré-vérification du token utilisateur — évite de lancer le worker pour rien
        String userToken = Config.get().str("acoustid.user_token", "").trim();
        if (userToken.isBlank()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("<html><b>Token utilisateur AcoustID manquant.</b><br><br>" +
                "1. Connectez-vous sur <tt>https://acoustid.org/api-key</tt><br>" +
                "2. Copiez votre clé utilisateur<br>" +
                "3. Collez-la dans <b>Préférences → APIs → AcoustID User Token</b></html>"),
                I18n.t("Configuration requise"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Collecter les fichiers candidats (sélection, ou tous si rien sélectionné)
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
            com.opentagger.model.TagInfo ti = e.activeTags();
            if (ti == null || ti.recordingMbid.isBlank()) { skippedNoMbid++; continue; }
            toSubmit.add(e);
        }

        if (toSubmit.isEmpty()) {
            String msg = I18n.t("Aucun fichier éligible à soumettre.");
            if (skippedNotTagged > 0) msg += I18n.t(" (%d non tagué(s) ignoré(s))", skippedNotTagged);
            if (skippedNoMbid   > 0) msg += I18n.t(" (%d sans MBID ignoré(s))", skippedNoMbid);
            setStatus(msg);
            return;
        }

        int total = toSubmit.size();
        String info = I18n.t("%d fichier(s) à soumettre", total);
        if (skippedNotTagged > 0) info += I18n.t(", %d non tagué(s) ignoré(s)", skippedNotTagged);
        if (skippedNoMbid   > 0) info += I18n.t(", %d sans MBID ignoré(s)", skippedNoMbid);
        setStatus(I18n.t("Soumission AcoustID : %s…", info));

        new SwingWorker<String, String>() {
            private List<com.opentagger.AcoustIdSubmitter.SubmissionResult> results;

            @Override protected String doInBackground() throws Exception {
                com.opentagger.AcoustIdSubmitter sub = new com.opentagger.AcoustIdSubmitter();
                List<File> files = new ArrayList<>();
                List<com.opentagger.model.TagInfo> tagsList = new ArrayList<>();
                for (FileEntry e : toSubmit) {
                    files.add(e.currentPath != null ? e.currentPath.toFile() : e.file);
                    tagsList.add(e.activeTags());
                }
                // Une seule requête HTTP pour tout le lot (format batch AcoustID), au lieu
                // d'une requête par fichier — voir AcoustIdSubmitter.submitBatch.
                results = sub.submitBatch(files, tagsList, this::publish);
                long ok = results.stream().filter(com.opentagger.AcoustIdSubmitter.SubmissionResult::accepted).count();
                long ko = results.size() - ok;
                return I18n.t("Soumission AcoustID — ✔ %d accepté(s)", ok) +
                       (ko > 0 ? I18n.t("  ✗ %d erreur(s)", ko) : "");
            }
            @Override protected void process(List<String> chunks) {
                setStatus(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    setStatus(get());
                    List<String> errors = new ArrayList<>();
                    if (results != null) {
                        for (var r : results)
                            if (!r.accepted()) errors.add(r.file().getName() + " : " + r.message());
                    }
                    if (!errors.isEmpty()) {
                        StringBuilder sb = new StringBuilder(I18n.t("<html><b>Erreurs lors de la soumission :</b><br><br>"));
                        for (String e : errors) sb.append("• ").append(e).append("<br>");
                        sb.append("</html>");
                        JOptionPane.showMessageDialog(MainFrame.this,
                            sb.toString(), I18n.t("Soumission AcoustID"), JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ex) {
                    setStatus(I18n.t("Soumission AcoustID : erreur inattendue — %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lblStatus.setText("  " + msg));
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
    }

    private static final java.util.prefs.Preferences PREFS =
            java.util.prefs.Preferences.userNodeForPackage(MainFrame.class);

    private void restoreWindowGeometry() {
        // Bornes du moniteur PRINCIPAL — PAS Toolkit.getScreenSize(), qui sur un poste multi-écran
        // (X11 notamment) renvoie la taille du BUREAU VIRTUEL COMBINÉ de tous les moniteurs. Sur
        // cette machine, 2 écrans côte à côte (2128×1197 + 1920×1080) donnent un bureau combiné de
        // 4048×1197 — calculer "85% de l'écran" là-dessus produit une fenêtre ~2× trop large,
        // ou centrée à cheval sur les deux écrans, au lieu d'être dimensionnée pour UN moniteur.
        java.awt.Rectangle primary = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        int defW = Math.max(960, (int)(primary.width  * 0.85));
        int defH = Math.max(600, (int)(primary.height * 0.85));
        int w    = PREFS.getInt("win.w", defW);
        int h    = PREFS.getInt("win.h", defH);
        int x, y;
        // Une taille sauvegardée sur un écran/moniteur plus PETIT restait minuscule pour toujours
        // sur un écran plus grand — le code ne validait que la POSITION (visible ou non), jamais
        // la taille elle-même. Si nettement plus petite que ce que donnerait le calcul par défaut
        // sur le moniteur principal ACTUEL, on la considère issue d'un autre écran : on ignore
        // aussi la position sauvegardée (calculée pour cette ancienne taille) et on recentre à
        // neuf sur le moniteur principal avec les dimensions par défaut.
        if (w < defW * 0.5 || h < defH * 0.5) {
            w = defW; h = defH;
            x = primary.x + (primary.width  - w) / 2;
            y = primary.y + (primary.height - h) / 2;
        } else {
            x = PREFS.getInt("win.x", primary.x + (primary.width  - w) / 2);
            y = PREFS.getInt("win.y", primary.y + (primary.height - h) / 2);
        }
        // Vérifier que la fenêtre est bien visible sur AU MOINS UN moniteur connecté — ici le
        // bureau virtuel combiné est le bon référentiel (une position sauvegardée sur un moniteur
        // secondaire reste légitime), contrairement au calcul de taille par défaut ci-dessus.
        java.awt.Rectangle virtualDesktop = new java.awt.Rectangle();
        for (java.awt.GraphicsDevice gd : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
            virtualDesktop = virtualDesktop.union(gd.getDefaultConfiguration().getBounds());
        if (!virtualDesktop.intersects(new java.awt.Rectangle(x, y, w, h))) {
            x = primary.x + (primary.width  - w) / 2;
            y = primary.y + (primary.height - h) / 2;
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

    // ── Fermeture de l'application ────────────────────────────────────────────

    /** Liste les opérations de fond actuellement actives (nom affichable), vide si rien ne tourne.
     *  Couvre les passes longues qui ont déjà des gardes d'exclusion mutuelle entre elles ailleurs
     *  dans cette classe (voir startTagging()/completeAlbums()/transcodeFiles()) — les mêmes
     *  champs, donc aucun risque de diverger de ce que ces gardes considèrent déjà "en cours". */
    private java.util.List<String> activeOperations() {
        java.util.List<String> ops = new java.util.ArrayList<>(WorkerHub.get().activeLabels());
        if (!activeScanWorkers.isEmpty()) ops.add(I18n.t("Scan de dossier"));
        return ops;
    }

    /** Si du travail de fond tourne encore, demande à l'utilisateur s'il veut attendre ou quitter
     *  quand même. Retourne true si l'appli doit vraiment se fermer (rien en cours, ou choix
     *  explicite de quitter quand même), false pour annuler la fermeture (choix d'attendre). */
    private boolean confirmQuit() {
        java.util.List<String> ops = activeOperations();
        if (ops.isEmpty()) return true;
        Object[] options = { I18n.t("Attendre la fin"), I18n.t("Quitter quand même") };
        int choice = JOptionPane.showOptionDialog(this,
            I18n.t("<html>Encore en cours : <b>%s</b>.<br><br>"
                 + "Quitter maintenant peut interrompre une écriture de fichier en plein milieu "
                 + "ou perdre la progression en cours.</html>", String.join(", ", ops)),
            I18n.t("Opération en cours — vraiment quitter ?"),
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
            null, options, options[0]); // "Attendre" par défaut (choix le plus sûr)
        return choice == 1; // seul "Quitter quand même" (index 1) confirme la fermeture
    }

    /** Point d'entrée UNIQUE pour quitter — utilisé par le bouton X de la fenêtre ET le menu
     *  "Quitter", pour que les deux se comportent pareil (l'ancien menu appelait System.exit(0)
     *  directement, sans confirmation ET sans sauvegarder géométrie/largeurs de colonnes). */
    private void quitApp() {
        if (!confirmQuit()) return;
        saveWindowGeometry();
        saveColumnWidths();
        if (folderWatcher != null) try { folderWatcher.close(); } catch (Exception ignored) {}
        // Arrête le pool de décodage de vignettes de Cover Flow (threads démons — le JVM les
        // tuerait de toute façon à System.exit(), mais un arrêt propre reste plus correct).
        if (coverFlowPanel != null) coverFlowPanel.dispose();
        System.exit(0);
    }

    private Color sep() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(80, 80, 80);
    }
}
