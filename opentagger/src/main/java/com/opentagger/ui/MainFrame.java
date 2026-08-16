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
    private static final Color CHIP_THROUGHPUT = new Color(0xAB47BC);

    // ── État ─────────────────────────────────────────────────────────────────
    private final FileTableModel tableModel = new FileTableModel();
    private int                     currentMask = Config.get().defaultRenameMask();
    // true dès que l'utilisateur choisit un masque explicitement via chooseMask() — empêche
    // onPreferencesSaved() d'écraser ce choix par le masque par défaut des Préférences.
    private boolean                 maskManuallyChosen = false;

    // ── Composants header ────────────────────────────────────────────────────
    // btnRefresh n'est plus fixe (voir buildHeader()) : peut être null si l'utilisateur l'a retiré
    // de la barre d'outils secondaire personnalisable — tout accès doit être null-safe.
    private JButton    btnTagAll, btnSaveAll, btnCancel, btnTranscode, btnRefresh;
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
    // Filtre supplémentaire, indépendant des chips ci-dessus — activé depuis NonIdentifiedReportDialog
    // (double-clic sur une catégorie), combiné (.and()) avec les filtres texte/statut dans
    // applyFilter(). Réinitialisé par le bouton "Effacer les filtres" et par tout clic sur un chip.
    private com.opentagger.model.SkipReason activeSkipReasonFilter = null;
    private JLabel lblMemory;

    // ── Débit/ETA global (chip informatif, pas un filtre) ────────────────────────────────────
    // Échantillonné dans refreshStats() (déjà appelé partout, déjà throttlé à 300ms) — fenêtre
    // glissante plutôt qu'un calcul depuis le début du run (voir etaText()/runStartMillis
    // ci-dessous, qui restent scopés à UNE opération) : reflète le débit RÉEL et global, cohérent
    // avec ce qu'un utilisateur observe en pratique (plusieurs opérations s'enchaînent/se
    // chevauchent au fil d'une session d'endurance).
    private final java.util.ArrayDeque<long[]> pendingHistory = new java.util.ArrayDeque<>(); // {tsMs, pending}
    private static final long THROUGHPUT_WINDOW_MS = 12 * 60_000; // 12 min
    private JLabel lblThroughput;

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
    // Panneau Journal repliable (voir buildLogPanel()/applyJournalVisibility()) — retour
    // utilisateur (2026-08-10) : le bouton-titre "Journal" reste toujours visible dans son propre
    // header même replié (sinon plus aucun moyen de rouvrir) ; seul journalScroll (le contenu) se
    // masque. withLog/logPanel gardés en champs (pas des variables locales de l'ancien init())
    // pour rester accessibles depuis le toggle.
    private JSplitPane        withLog;
    private JPanel            logPanel;
    private JComponent        journalHeader;
    private JScrollPane       journalScroll;
    private JToggleButton     btnToggleJournal;
    private JCheckBoxMenuItem chkShowJournal;
    private int               savedJournalDividerLocation = -1;
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
    // Voir refreshStats() : chargement paresseux, une seule fois, dès que la table contient au
    // moins une entrée (chemin le plus simple et le plus robuste face aux multiples façons dont la
    // table peut se peupler — CLI --initialDirs, ouverture manuelle de dossier — plutôt que de
    // dépendre d'un unique callback "scan terminé").
    private boolean undoHistoryBound = false;
    private JButton btnUndo, btnRedo;
    private JCheckBoxMenuItem chkForceAcoustId;
    private JCheckBoxMenuItem chkAutoGroupCompilations;
    private JCheckBoxMenuItem chkAutoReidentifyUnmatched;
    private JCheckBoxMenuItem chkAutoTagOnScan;
    private JCheckBoxMenuItem chkAutoSaveEnabled;
    private JCheckBoxMenuItem chkMoveSkippedMenu;
    private JCheckBoxMenuItem chkMoveDurationMismatchMenu;
    private JCheckBoxMenuItem chkUseLibraryRootMenu;

    // ── Barre de statut ───────────────────────────────────────────────────────
    private JLabel       lblStatus;
    // Une barre par type de tâche plutôt qu'un JProgressBar unique partagé — depuis que TAGGING/
    // INFO_COMPLETER (et TAGGING/SAVE) peuvent tourner en parallèle (voir WorkerHub.conflictsWith()),
    // un composant unique se faisait marcher dessus par deux workers concurrents : l'un masquait/
    // réinitialisait la barre pendant que l'autre l'utilisait encore, donnant une barre vide/
    // invisible sans rapport avec l'avancement réel. Repéré en direct (2026-08-14, signalé par
    // l'utilisateur). Une barre dédiée par ProgressSlot élimine le problème à la racine (plus de
    // ressource partagée à protéger) plutôt qu'un compteur de référence sur un composant unique.
    private enum ProgressSlot {
        TAGGING, SAVE, INFO_COMPLETER, TRANSCODE, ALBUM_COMPLETION, ALBUM_CLUSTER,
        COMPILATION_CLUSTER, VIDEO_RECOVERY, LISTENBRAINZ_SYNC, DUPLICATE_DETECT
    }
    private final java.util.Map<ProgressSlot, JProgressBar> progressBars = new java.util.EnumMap<>(ProgressSlot.class);
    private JPanel progressPanel;
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
    // Empêche scheduleAutoSaveFollowUp() de démarrer plusieurs chaînes de minuteries en parallèle
    // (onTaggingDone() ET la chaîne d'Enregistrer elle-même peuvent chacune vouloir la déclencher) —
    // voir le commentaire de scheduleAutoSaveFollowUp() pour le pourquoi de cette relance depuis
    // onTaggingDone().
    private boolean autoSaveWatchPending = false;

    // Accumulateur pour groupByCompilations(true) (déclenchement automatique après CHAQUE vague
    // d'Enregistrer, voir scheduleAutoSaveFollowUp()) — sur une grosse session (plusieurs centaines
    // de fichiers), le suivi automatique relance "Enregistrer tout" en plusieurs vagues successives
    // tant que le taguage continue d'en produire de nouveaux ; chaque vague déclenchait jusqu'ici sa
    // PROPRE CompilationMatchDialog dès qu'elle trouvait des correspondances — jusqu'à 3 popups
    // distincts pour une seule session ressentie comme continue par l'utilisateur (signalé en direct
    // 2026-08-13). Les correspondances de chaque vague s'accumulent ici au lieu d'être affichées
    // tout de suite ; une seule fenêtre les regroupe à la toute fin réelle de la chaîne (voir
    // flushPendingCompilationMatches(), appelée aux deux points où runPostTagCommand() l'est déjà).
    private final List<CompilationClusterWorker.CompilationMatch> pendingCompilationMatches = new ArrayList<>();

    // Budget PARTAGÉ (tous scans confondus, tout le lancement) de relectures forcées pour rattraper
    // les entrées scan_cache sans durée (voir la boucle de scan) — évite qu'une bibliothèque de
    // 150k+ fichiers dont le cache entier précède la colonne Durée transforme un rescan normalement
    // rapide (cache-hit) en relecture complète de tout le disque d'un coup. Se reconstitue à chaque
    // relance de l'appli (pas persisté) ; le reste du cache converge sur plusieurs sessions.
    //
    // 4000 (valeur d'origine) mesuré à l'usage : sur cette bibliothèque, 324 343 entrées sur
    // 676 225 (48 %, le passif complet d'avant la colonne Durée, 2026-07-13) ont encore une durée
    // manquante — à 4000/lancement il aurait fallu ~80 relances pour tout rattraper. Remonté à
    // 50 000 (~7 relances pour converger entièrement) : le pic mémoire observé à l'origine
    // (~5 Go quasi instantané) concernait un rattrapage NON throttlé sur la quasi-totalité du
    // cache d'un coup ; 50 000 reste un sous-ensemble borné, largement dans la marge du tas
    // (-Xmx8g, voir launch.sh/install-opentagger.sh).
    private final java.util.concurrent.atomic.AtomicInteger durationBackfillBudget =
            new java.util.concurrent.atomic.AtomicInteger(50_000);

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

    // Cache PARTAGÉ du scan_cache (voir MetadataCache.loadScanCacheMap) — un seul chargement de
    // toute la table réutilisé par tous les scans démarrés en même temps, plutôt qu'une copie
    // complète par dossier. Constaté en direct : scan_cache non purgé depuis des années
    // (~4,3 Go / plusieurs centaines de milliers de lignes) ; 5 dossiers de démarrage scannés en
    // parallèle chargeaient chacun leur propre copie déballée en HashMap simultanément — assez à
    // lui seul pour épuiser un tas de 5 Go (OutOfMemoryError constaté en direct dès le lancement,
    // avant même de compter les 95k fichiers du scan). Compteur de références : chargé à la
    // première demande, libéré dès que plus aucun scan actif n'en a besoin.
    private final Object scanCacheMapLock = new Object();
    private java.util.Map<String, MetadataCache.ScanCacheEntry> sharedScanCacheMap;
    private int scanCacheMapRefCount = 0;

    private java.util.Map<String, MetadataCache.ScanCacheEntry> acquireScanCacheMap(MetadataCache cache) {
        synchronized (scanCacheMapLock) {
            if (sharedScanCacheMap == null) sharedScanCacheMap = cache.loadScanCacheMap();
            scanCacheMapRefCount++;
            return sharedScanCacheMap;
        }
    }

    private void releaseScanCacheMap() {
        synchronized (scanCacheMapLock) {
            if (--scanCacheMapRefCount <= 0) {
                scanCacheMapRefCount = 0;
                sharedScanCacheMap = null;
            }
        }
    }

    // Surveillance automatique des dossiers chargés (WatchService)
    private com.opentagger.FolderWatcher folderWatcher;

    // ── Entrée publique ───────────────────────────────────────────────────────

    public static void launch(java.io.File[] initialDirs) {
        FlatDarkLaf.setup();
        UIManager.put("Table.alternateRowColor", new Color(45, 47, 52));
        applyModernTheme();
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

    /**
     * Jusqu'ici, FlatDarkLaf.setup() tournait avec ses réglages par défaut — le look "moderne"
     * de l'appli venait entièrement de retouches ponctuelles composant par composant (chips de
     * statut, boutons accentués…), jamais des contrôles Swing standards (JComboBox, JSpinner,
     * JScrollBar, JTabbedPane, cases à cocher) qui gardaient l'angle droit et l'accent bleu par
     * défaut de FlatLaf — d'où l'impression d'ensemble "daté" malgré les retouches locales.
     * Quelques propriétés UIManager globales suffisent à uniformiser TOUT le reste de l'appli
     * (chaque dialogue, y compris ceux jamais retouchés individuellement) sans toucher un seul
     * appel de construction de composant.
     */
    private static void applyModernTheme() {
        // Accent unique = le teal déjà utilisé pour les boutons principaux (ACCENT ci-dessous) —
        // avant ce correctif, coches/radios/curseurs/barres de progression gardaient le bleu par
        // défaut de FlatLaf, en désaccord avec les boutons "Ouvrir dossier"/"Tout tagger" etc.
        UIManager.put("Component.accentColor", ACCENT);
        UIManager.put("Component.focusColor",  new Color(0x4DB6AC, true));

        // FlatLaf mappe par défaut la coche des JCheckBoxMenuItem sur "@buttonArrowColor" — la
        // même teinte grise très discrète (à peine plus sombre que le texte) utilisée pour les
        // petites flèches de ComboBox/Spinner/ScrollBar. Adaptée à une flèche décorative, illisible
        // pour une coche censée dire "activé" au premier coup d'œil (signalé en direct : "impossible
        // de savoir que c'est des cases à cocher"). Couleur d'accent déjà utilisée partout ailleurs
        // dans l'appli pour "sélectionné/actif" — cohérent, et bien plus contrasté sur fond sombre.
        UIManager.put("CheckBoxMenuItem.icon.checkmarkColor", ACCENT);
        UIManager.put("CheckBoxMenuItem.icon.disabledCheckmarkColor", ACCENT.darker());

        // Angles arrondis uniformes (boutons, champs, combos, spinners) — FlatLaf par défaut est
        // presque à angle droit (arc=4), ce qui lit "utilitaire des années 2010" plutôt que
        // "app actuelle" à côté d'un Cover Flow et de chips colorées déjà bien plus travaillés.
        UIManager.put("Component.arc",       10);
        UIManager.put("Button.arc",          10);
        UIManager.put("ProgressBar.arc",     999); // pilule complète, pas juste arrondie
        UIManager.put("CheckBox.arc",        4);

        // Ascenseurs fins et arrondis façon navigateur moderne — ceux de FlatLaf par défaut sont
        // larges et carrés, très visibles sur le grand tableau principal (des dizaines de milliers
        // de lignes chez cet utilisateur, donc un ascenseur omniprésent à l'écran).
        UIManager.put("ScrollBar.width",       11);
        UIManager.put("ScrollBar.thumbArc",    999);
        UIManager.put("ScrollBar.trackArc",    999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 3, 2, 3));
        UIManager.put("ScrollBar.showButtons", false);

        // Séparateurs d'onglets nets (Préférences a 9 onglets à plat, l'historique en a 2) —
        // sans ça, l'onglet actif ne se distingue que par une fine ligne de soulignement,
        // ambigu dès qu'on a plus de 4-5 onglets côte à côte.
        UIManager.put("TabbedPane.showTabSeparators",        true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight",  true);

        // Un peu plus d'air dans les boutons — le texte touchait presque les bords par défaut,
        // perceptible sur les gros boutons de la barre d'outils principale (police en gras 12).
        UIManager.put("Button.margin", new Insets(4, 12, 4, 12));

        // Relief 3D global (retour utilisateur, 2026-08-10) : jusqu'ici seuls headerBtn()/
        // secondaryBtn()/le bouton "Journal" avaient une bordure/fond explicites — chaque nouveau
        // bouton découvert ailleurs (accentBtn "Ouvrir dossier", "Appliquer les modifications" du
        // panneau détail, "Vider" du journal, et tout autre bouton "nu" dans les dialogues restés
        // sur le fond FlatLaf par défaut) semblait plat, un par un, à chaque fois signalé. Réglé ici
        // au niveau du LAF entier plutôt qu'en repérant chaque bouton individuellement : tout futur
        // JButton (y compris dans un dialogue pas encore audité) hérite maintenant du même relief,
        // sans avoir à toucher son code — un putClientProperty("FlatLaf.style", ...) local (comme
        // headerBtn/accentBtn) continue de primer sur ces valeurs par défaut là où il existe déjà.
        UIManager.put("Button.borderWidth",     1);
        UIManager.put("Button.background",      new Color(0x3A3A3E));
        UIManager.put("Button.borderColor",     new Color(0x4A4A4E));
        UIManager.put("Button.hoverBackground", new Color(0x45454A));
        UIManager.put("Button.pressedBackground", new Color(0x2E2E32));
        UIManager.put("ToggleButton.borderWidth",     1);
        UIManager.put("ToggleButton.background",      new Color(0x3A3A3E));
        UIManager.put("ToggleButton.borderColor",     new Color(0x4A4A4E));
        UIManager.put("ToggleButton.hoverBackground", new Color(0x45454A));
        UIManager.put("ToggleButton.pressedBackground", new Color(0x2E2E32));
        UIManager.put("ToggleButton.selectedBackground", ACCENT);
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
                // Vérifier que ce fichier n'est pas déjà dans la table — allEntries() (ici et pour
                // la résolution du scanRoot juste en dessous) : sinon un fichier déjà présent mais
                // masqué par un filtre actif pouvait être réajouté en double.
                //
                // fe.currentPath EN PLUS de fe.file (pas fe.file seul) : fe.file est le chemin
                // D'ORIGINE, figé pour toute la vie de l'entrée (final) — après "Enregistrer tout"
                // avec renommage/déplacement actif (rename.auto_enabled + library_root, un des
                // dossiers surveillés par ce watcher), le fichier déplacé déclenche ENTRY_CREATE à
                // sa NOUVELLE localisation. fe.file.equals(f) comparait alors l'ancien chemin au
                // nouveau — TOUJOURS faux — donc cette garde anti-doublon échouait à chaque coup
                // sur un fichier venant d'être tagué+déplacé : une DEUXIÈME entrée était créée pour
                // le même fichier physique, avec un TagInfo vierge, PENDING, retraitée depuis zéro
                // (perçu par l'utilisateur comme "un fichier déjà sauvegardé repasse Non identifié
                // dans la file d'attente"). Repéré en direct (2026-08-14, signalé par l'utilisateur).
                Path fp = f.toPath();
                for (com.opentagger.model.FileEntry fe : tableModel.allEntries()) {
                    if (fe.file.toPath().equals(fp) || fp.equals(fe.currentPath)) return;
                }
                com.opentagger.model.FileEntry e = new com.opentagger.model.FileEntry(f, new com.opentagger.model.TagInfo());
                // Déterminer la racine : trouver le scanRoot du dossier parent
                Path parent = p.getParent();
                for (com.opentagger.model.FileEntry ex : tableModel.allEntries()) {
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
        logPanel = buildLogPanel();
        logPanel.setPreferredSize(new Dimension(10, 140)); // hauteur initiale du journal
        withLog = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildMainSplit(), logPanel);
        withLog.setResizeWeight(1.0);   // le journal garde sa hauteur, le reste absorbe le redimensionnement
        withLog.setDividerSize(4);
        withLog.setBorder(null);
        add(withLog, BorderLayout.CENTER);
        // Visibilité restaurée APRÈS ajout au conteneur (setDividerLocation exige que le
        // JSplitPane soit déjà affichable/dimensionné pour avoir un effet fiable) — voir
        // applyJournalVisibility(), appelé aussi depuis le menu Affichage.
        SwingUtilities.invokeLater(() -> applyJournalVisibility(PREFS.getBoolean("journal.visible", true)));
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
                            // Nettoyage dans un finally (même correctif que TagEnrichment.saveEntry/
                            // BatchProcessor/App.java) : writeCoverOnly() levant une exception
                            // sautait ce nettoyage, fuite de fichier temporaire à chaque échec.
                            try {
                                writer.writeCoverOnly(e.file, img);
                            } finally {
                                try { java.nio.file.Files.deleteIfExists(img); } catch (Exception ignored) {}
                            }
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
        // allEntries() : sinon "Rafraîchir" ne redécouvre que les dossiers des fichiers visibles.
        java.util.LinkedHashSet<File> roots = new java.util.LinkedHashSet<>();
        for (com.opentagger.model.FileEntry e : tableModel.allEntries()) {
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
    /**
     * JCheckBoxMenuItem qui ne referme pas le menu déroulant au clic — Swing referme n'importe
     * quel menu au clic sur n'importe quel item par défaut (MenuSelectionManager.clearSelectedPath()
     * dans BasicMenuItemUI, avant même l'appel à doClick()), gênant pour cocher plusieurs réglages
     * d'affilée (ex. le sous-menu "Déplacement" ci-dessous) sans devoir rouvrir le menu à chaque
     * case. Technique standard : mémoriser le chemin de sélection pendant que l'item est armé, le
     * ré-appliquer juste après le doClick() qui l'a fait fermer — usage volontairement limité au
     * menu Tagger (voir buildMenuTagger()), pas une refonte globale des menus à cocher de l'appli.
     */
    private static class StayOpenCheckBoxMenuItem extends JCheckBoxMenuItem {
        private static MenuElement[] path;
        StayOpenCheckBoxMenuItem(String text) {
            super(text);
            getModel().addChangeListener(e -> {
                if (getModel().isArmed() && isShowing())
                    path = MenuSelectionManager.defaultManager().getSelectedPath();
            });
        }
        @Override public void doClick(int pressTime) {
            super.doClick(pressTime);
            MenuSelectionManager.defaultManager().setSelectedPath(path);
        }
    }

    private JMenu buildMenuTagger() {
        JMenu m = new JMenu(I18n.t("Tagger"));
        m.setMnemonic('T');
        m.add(mitem(I18n.t("Tout tagger (cochés)"),    "F6",  e -> startTagging(false)));
        m.add(mitem(I18n.t("Tagger la sélection"),     "F7",  e -> startTagging(true)));
        m.add(mitem(I18n.t("Enregistrer tout (cochés)"), "F8", e -> saveAll()));
        m.addSeparator();
        chkForceAcoustId = new StayOpenCheckBoxMenuItem(I18n.t("Forcer AcoustID pour les non identifiés"));
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
        // Fusion 2026-07-16 des anciennes cases "Compléter aussi les fichiers tagués mais
        // incomplets" et "Compléter les albums automatiquement après le taguage" — elles
        // s'enchaînaient déjà l'une après l'autre (voir onTaggingDone()), mais deux cases séparées
        // au nom proche pour une seule intention ("finir le taguage automatiquement") créaient de
        // la confusion. Un seul réglage à 3 états (Config.PostTagCompletion), qui migre une fois
        // depuis les deux anciennes clés pour ne pas changer silencieusement un choix déjà fait.
        JMenu completionMenu = new JMenu(I18n.t("Après le taguage"));
        ButtonGroup completionGroup = new ButtonGroup();
        Config.PostTagCompletion current = Config.get().postTagCompletion();
        JRadioButtonMenuItem rbNone = new JRadioButtonMenuItem(I18n.t("Ne rien compléter automatiquement"));
        JRadioButtonMenuItem rbFields = new JRadioButtonMenuItem(I18n.t("Compléter les champs manquants"));
        JRadioButtonMenuItem rbFieldsAlbums = new JRadioButtonMenuItem(I18n.t("Compléter les champs + rechercher les pistes d'album manquantes"));
        rbNone.setSelected(current == Config.PostTagCompletion.NONE);
        rbFields.setSelected(current == Config.PostTagCompletion.FIELDS);
        rbFieldsAlbums.setSelected(current == Config.PostTagCompletion.FIELDS_AND_ALBUMS);
        rbNone.addActionListener(e -> Config.get().setPostTagCompletion(Config.PostTagCompletion.NONE));
        rbFields.addActionListener(e -> Config.get().setPostTagCompletion(Config.PostTagCompletion.FIELDS));
        rbFieldsAlbums.addActionListener(e -> Config.get().setPostTagCompletion(Config.PostTagCompletion.FIELDS_AND_ALBUMS));
        for (JRadioButtonMenuItem rb : new JRadioButtonMenuItem[]{rbNone, rbFields, rbFieldsAlbums}) {
            completionGroup.add(rb);
            completionMenu.add(rb);
        }
        m.add(completionMenu);
        // Demandé le 2026-07-10, juste après avoir choisi le mode "proactif" (revue manuelle) pour
        // "Grouper par compilations…" plutôt qu'une réécriture automatique — l'utilisateur voulait
        // aussi pouvoir déclencher la RECHERCHE toute seule, sans pour autant perdre la revue avant
        // écriture. Déclenché après "Enregistrer tout" (pas après "Tout tagger" comme les deux
        // cases ci-dessus) : les fichiers ne passent à TAGGED (recordingMbid fiable, condition de
        // CompilationClusterWorker) qu'à l'Enregistrement, jamais juste après l'identification
        // (façon Picard) — voir le hook sur saveWorker plus bas plutôt que onTaggingDone().
        chkAutoGroupCompilations = new StayOpenCheckBoxMenuItem(I18n.t("Grouper par compilations automatiquement après l'enregistrement"));
        chkAutoGroupCompilations.setSelected(Config.get().bool("tagging.auto_group_compilations", false));
        chkAutoGroupCompilations.addActionListener(e ->
                Config.get().set("tagging.auto_group_compilations", String.valueOf(chkAutoGroupCompilations.isSelected())));
        m.add(chkAutoGroupCompilations);

        // Retour utilisateur (2026-08-10) : ne pas être obligé de cliquer sur "Ré-identifier par
        // empreinte audio…" à chaque fois — si activé, se déclenche tout seul juste après CHAQUE
        // "Tout tagger"/"Forcer le re-taguage" sur les seuls fichiers restés "Non identifié" de CE
        // run précis (pas tout l'historique "Sans_correspondance" à chaque fois, voir
        // onTaggingDone()) — la revue groupée (ReidentifyReviewDialog) reste obligatoire, ce
        // réglage automatise seulement le déclenchement, jamais l'application silencieuse.
        chkAutoReidentifyUnmatched = new StayOpenCheckBoxMenuItem(I18n.t("Ré-identifier par empreinte audio automatiquement après chaque taguage"));
        chkAutoReidentifyUnmatched.setSelected(Config.get().bool("tagging.auto_reidentify_unmatched", false));
        // Confirmation demandée le 2026-08-12 seulement à l'ACTIVATION (jamais à la désactivation,
        // qui ne fait que couper un déclenchement automatique, sans risque) : cette option efface
        // le cache et les MBID des fichiers restés "Non identifié" pour forcer une nouvelle
        // tentative (voir reidentifyUnmatched()/resetForReidentification()) — l'utilisateur avait
        // été surpris de voir ce comportement de "Forcer le re-taguage" se déclencher tout seul
        // sans se rappeler avoir explicitement demandé cette conséquence en cochant la case.
        chkAutoReidentifyUnmatched.addActionListener(e -> {
            if (chkAutoReidentifyUnmatched.isSelected()) {
                int confirm = JOptionPane.showConfirmDialog(this,
                    I18n.t("<html>Cette option efface le cache et les identifiants MusicBrainz<br>"
                         + "des fichiers restés « Non identifié » après CHAQUE taguage, pour forcer<br>"
                         + "une nouvelle tentative par empreinte audio (SongRec/AcoustID) — un peu<br>"
                         + "comme « Forcer le re-taguage », mais uniquement sur ces fichiers-là.<br><br>"
                         + "Une fenêtre de revue s'ouvrira à chaque fois pour valider les résultats ;<br>"
                         + "rien n'est jamais appliqué automatiquement sur le disque.<br><br>"
                         + "Activer cette option ?</html>"),
                    I18n.t("Ré-identifier automatiquement"), JOptionPane.YES_NO_OPTION);
                if (confirm != JOptionPane.YES_OPTION) {
                    chkAutoReidentifyUnmatched.setSelected(false);
                    return;
                }
            }
            Config.get().set("tagging.auto_reidentify_unmatched", String.valueOf(chkAutoReidentifyUnmatched.isSelected()));
        });
        m.add(chkAutoReidentifyUnmatched);

        // Demandé le 2026-08-12 : le taguage ne reprend jamais tout seul après un redémarrage (ou
        // l'ajout d'un dossier), obligeant à recliquer "Tagger"/F6 à chaque fois — uniquement
        // l'IDENTIFICATION se déclenche automatiquement ici (fichiers PENDING seulement, voir
        // scheduleAutoTaggingFollowUp()), l'enregistrement sur disque restant par défaut un geste
        // manuel façon Picard (voir FileEntry.Status.IDENTIFIED) — SAUF si chkAutoSaveEnabled
        // ci-dessous est actif (défaut : oui, voir Config.autoSaveEnabled() pour le pourquoi).
        chkAutoTagOnScan = new StayOpenCheckBoxMenuItem(I18n.t("Tagger automatiquement après chaque scan de dossier"));
        chkAutoTagOnScan.setSelected(Config.get().autoTagOnScan());
        chkAutoTagOnScan.addActionListener(e ->
                Config.get().set("tagging.auto_start_on_scan", String.valueOf(chkAutoTagOnScan.isSelected())));
        m.add(chkAutoTagOnScan);

        // Interrupteur pour scheduleAutoSaveFollowUp() (voir son commentaire) — actif par défaut.
        // Quand actif, le bouton "Enregistrer tout" de l'écran principal est masqué (retour
        // utilisateur, 2026-08-15) : redondant/prêtant à confusion si tout s'enregistre déjà tout
        // seul ; réapparaît si l'utilisateur repasse en contrôle manuel.
        chkAutoSaveEnabled = new StayOpenCheckBoxMenuItem(I18n.t("Enregistrer automatiquement après taguage"));
        chkAutoSaveEnabled.setSelected(Config.get().autoSaveEnabled());
        chkAutoSaveEnabled.addActionListener(e -> {
            Config.get().set("tagging.auto_save_enabled", String.valueOf(chkAutoSaveEnabled.isSelected()));
            if (btnSaveAll != null) btnSaveAll.setVisible(!chkAutoSaveEnabled.isSelected());
        });
        m.add(chkAutoSaveEnabled);

        // Accès rapide aux cases de déplacement (voir aussi Préférences → Renommage) — mêmes
        // clés Config des deux côtés, donc toujours synchronisées peu importe où on les bascule ;
        // le dossier cible lui-même (chemin texte) reste configuré dans Préférences, un menu
        // n'étant pas un bon endroit pour saisir un chemin de dossier.
        JMenu moveMenu = new JMenu(I18n.t("Déplacement"));
        chkUseLibraryRootMenu = new StayOpenCheckBoxMenuItem(I18n.t("Utiliser un dossier racine de bibliothèque dédié"));
        chkUseLibraryRootMenu.setSelected(Config.get().useLibraryRootEnabled());
        chkUseLibraryRootMenu.addActionListener(e ->
                Config.get().set("rename.use_library_root", String.valueOf(chkUseLibraryRootMenu.isSelected())));
        moveMenu.add(chkUseLibraryRootMenu);

        chkMoveSkippedMenu = new StayOpenCheckBoxMenuItem(I18n.t("Déplacer les fichiers non tagués (ignorés/erreurs)"));
        chkMoveSkippedMenu.setSelected(Config.get().skippedMoveEnabled());
        chkMoveSkippedMenu.addActionListener(e ->
                Config.get().set("skipped.move_enabled", String.valueOf(chkMoveSkippedMenu.isSelected())));
        moveMenu.add(chkMoveSkippedMenu);

        chkMoveDurationMismatchMenu = new StayOpenCheckBoxMenuItem(I18n.t("Déplacer les fichiers à durée incohérente"));
        chkMoveDurationMismatchMenu.setSelected(Config.get().durationMismatchMoveEnabled());
        chkMoveDurationMismatchMenu.addActionListener(e ->
                Config.get().set("duration_mismatch.move_enabled", String.valueOf(chkMoveDurationMismatchMenu.isSelected())));
        moveMenu.add(chkMoveDurationMismatchMenu);
        m.add(moveMenu);

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
        m.addSeparator();

        // Retour utilisateur (2026-08-10) : pouvoir masquer le panneau Journal pour gagner de la
        // place, en particulier sur un petit écran ou quand on ne surveille pas le journal en
        // continu. Coché par défaut (comportement historique inchangé) ; état retenu d'une session
        // à l'autre — voir applyJournalVisibility() et PREFS "journal.visible".
        chkShowJournal = new JCheckBoxMenuItem(I18n.t("Afficher le journal"), PREFS.getBoolean("journal.visible", true));
        chkShowJournal.addActionListener(e -> applyJournalVisibility(chkShowJournal.isSelected()));
        m.add(chkShowJournal);
        return m;
    }

    /** Affiche/masque le CONTENU du panneau Journal (pas son header — voir buildLogPanel(), le
     *  bouton-titre "Journal" doit rester cliquable même replié) et retient le choix pour la
     *  prochaine ouverture. Repliage : le diviseur est ramené juste sous le header (sa hauteur
     *  propre ne dépend pas du contenu du journal, donc stable), pour ne laisser dépasser que la
     *  barre de titre — pas 0 pur, sinon le bouton lui-même disparaîtrait avec le reste. */
    private void applyJournalVisibility(boolean visible) {
        if (chkShowJournal != null)   chkShowJournal.setSelected(visible);
        if (btnToggleJournal != null) {
            btnToggleJournal.setSelected(visible);
            btnToggleJournal.setText(visible ? I18n.t("▾ Journal") : I18n.t("▸ Journal"));
        }
        PREFS.putBoolean("journal.visible", visible);
        if (visible) {
            journalScroll.setVisible(true);
            if (savedJournalDividerLocation > 0) withLog.setDividerLocation(savedJournalDividerLocation);
        } else {
            if (withLog.getHeight() > 0) savedJournalDividerLocation = withLog.getDividerLocation();
            journalScroll.setVisible(false);
            int headerH = journalHeader.getPreferredSize().height;
            withLog.setDividerLocation(Math.max(0, withLog.getHeight() - headerH - withLog.getDividerSize()));
        }
        logPanel.revalidate();
        withLog.revalidate();
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
        retraitement.add(mitem(I18n.t("Ré-identifier par empreinte audio (Non identifiés)…"), null, e -> reidentifyUnmatched()));
        retraitement.add(mitem(I18n.t("Corriger l'encodage des tags…"), null, e -> fixEncoding()));
        retraitement.add(mitem(I18n.t("Nettoyer les noms (Non identifiés)…"), null, e -> cleanNamesOnly()));
        retraitement.add(mitem(I18n.t("Nettoyer les noms + Ré-identifier (Non identifiés)…"), null, e -> cleanNamesAndReidentifyUnmatched()));
        retraitement.add(mitem(I18n.t("Marquer la sélection comme déjà taggée…"), null, e -> markAsAlreadyTagged()));
        retraitement.add(mitem(I18n.t("Synchroniser ListenBrainz…"), null,    e -> syncListenBrainz()));
        m.add(retraitement);

        JMenu bibliotheque = new JMenu(I18n.t("Bibliothèque"));
        bibliotheque.add(mitem(I18n.t("Tagger comme podcast…"),    null,      e -> openPodcastDialog()));
        bibliotheque.add(mitem(I18n.t("Récupérer l'audio des vidéos non reconnues…"), null, e -> openVideoRecoveryDialog()));
        bibliotheque.add(mitem(I18n.t("Détecter les doublons…"),  null,      e -> detectDuplicates()));
        bibliotheque.add(mitem(I18n.t("Supprimer les fichiers illisibles…"), null, e -> deleteErrorFiles()));
        bibliotheque.add(mitem(I18n.t("Historique de taguage…"),  null,      e -> new HistoryDialog(this).setVisible(true)));
        bibliotheque.add(mitem(I18n.t("Rapport Non identifiés…"), null,
            e -> new NonIdentifiedReportDialog(this, tableModel).setVisible(true)));
        bibliotheque.add(mitem(I18n.t("Importer XML iTunes…"), null,
            e -> new ITunesImportDialog(this, tableModel).setVisible(true)));
        bibliotheque.add(mitem(I18n.t("Écrire les corrections dans le XML iTunes…"), null,
            e -> writeItunesXmlCorrections()));
        bibliotheque.add(mitem(I18n.t("Coller une URL Bandcamp…"), null, e -> openBandcampDialog()));
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
        JButton btnOpen = accentBtn(I18n.t("Ouvrir dossier"), "Ctrl+O", ToolbarIcon.Kind.FOLDER_OPEN);
        btnOpen.addActionListener(e -> openFolder());

        // Fusionné (2026-07-28) : un seul bouton, contextuel — tague la sélection courante du
        // tableau si des lignes sont surlignées (JTable.getSelectedRows()), sinon retombe sur tous
        // les fichiers COCHÉS (comportement historique de "Tout tagger") — même distinction
        // sélection/coché que startTagging(selOnly) gère déjà en interne. F6 (tout)/F7 (sélection)
        // et les entrées de menu correspondantes restent inchangés pour qui veut forcer l'un ou
        // l'autre explicitement ; ce bouton n'est qu'un raccourci visuel pour le cas courant.
        btnTagAll    = headerBtn(I18n.t("Analyser"), I18n.t(
                "Analyser la sélection si des lignes sont surlignées dans le tableau, "
                + "sinon tous les fichiers cochés (F6 = tout, F7 = sélection)"), ToolbarIcon.Kind.TAG);
        btnSaveAll   = headerBtn(I18n.t("Enregistrer tout"), I18n.t("Écrire sur le disque les fichiers identifiés, cochés (F8)"), ToolbarIcon.Kind.SAVE);
        // Masqué par défaut : l'auto-enregistrement (chkAutoSaveEnabled, actif par défaut) s'en
        // charge déjà — bouton redondant tant qu'il l'est. Réapparaît si désactivé dans le menu
        // Tagger. F8 (raccourci clavier) reste fonctionnel dans les deux cas, juste le bouton
        // visuel qui se masque.
        btnSaveAll.setVisible(!Config.get().autoSaveEnabled());
        btnCancel    = headerBtn(I18n.t("Arrêter"), I18n.t("Annuler le traitement en cours"), ToolbarIcon.Kind.STOP);
        btnTagAll.addActionListener(e -> startTagging(table.getSelectedRowCount() > 0));
        btnSaveAll.addActionListener(e -> saveAll());
        btnCancel.addActionListener(e -> stopAll());
        btnCancel.setEnabled(false);

        JPanel actionsPanel = new JPanel(new WrapLayout(FlowLayout.CENTER, 6, 6));
        actionsPanel.setBackground(HEADER_BG);
        actionsPanel.add(btnOpen);
        actionsPanel.add(vSep());
        actionsPanel.add(btnTagAll);
        actionsPanel.add(btnSaveAll);
        actionsPanel.add(btnCancel);

        // Actions secondaires personnalisables (façon Picard, onglet "Barre d'outils des
        // actions") — voir toolbarActionRegistry() / Config.toolbarActions(). Les boutons d'état
        // ci-dessus (Ouvrir/Tout tagger/Tagger la sélection/Enregistrer tout/Annuler) restent
        // fixes : trop couplés à resetBtns()/launchForcedTagging() pour être rendus optionnels
        // sans risque. Dans un panneau à part (secondaryToolbarPanel) plutôt que directement dans
        // actionsPanel : permet de tout reconstruire en un removeAll()/repopulate depuis
        // populateSecondaryToolbar() (rappelée après Préférences) sans toucher aux boutons fixes.
        secondaryToolbarPanel = new JPanel(new WrapLayout(FlowLayout.CENTER, 6, 6));
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
        setFilePathLabel("  " + (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath());
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
            JButton btn = secondaryBtn(action.label(), action.tooltip(), secondaryIconFor(action.id()));
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

    private JButton accentBtn(String text, String tip) { return accentBtn(text, tip, null); }

    // Icône sombre (même teinte que le texte #1E1F22) : les 4 boutons "header" sont tous sur le
    // fond sombre de la barre d'outils, celui-ci seul est sur fond teal plein — une icône claire y
    // serait quasi invisible (faible contraste clair-sur-clair une fois le halo du fond pris en
    // compte), d'où une couleur d'icône dédiée par variante de bouton plutôt qu'une seule globale.
    private JButton accentBtn(String text, String tip, ToolbarIcon.Kind icon) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.putClientProperty("FlatLaf.style",
            "background: #4DB6AC; foreground: #1E1F22; font: bold 12 $defaultFont");
        if (icon != null) { b.setIcon(new ToolbarIcon(icon, new Color(0x1E1F22))); b.setIconTextGap(7); }
        return b;
    }

    private JButton headerBtn(String text, String tip) { return headerBtn(text, tip, null); }

    private JButton headerBtn(String text, String tip, ToolbarIcon.Kind icon) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        // Relief 3D plus marqué (retour utilisateur, 2026-08-10) : le rendu FlatLaf par défaut
        // (bordure/dégradé très subtils) ne se voyait pas assez à son goût — bordure explicite +
        // fond légèrement plus clair que l'arrière-plan pour un bouton net, nettement "en relief".
        b.putClientProperty("FlatLaf.style",
            "foreground: #CFD8DC; borderWidth: 1; borderColor: #4A4A4E; background: #3A3A3E; "
            + "hoverBackground: #45454A; pressedBackground: #2E2E32; arc: 6");
        if (icon != null) { b.setIcon(new ToolbarIcon(icon, new Color(0xCFD8DC))); b.setIconTextGap(7); }
        return b;
    }

    /** Même rôle que headerBtn() mais plus discret (police plus petite, gris atténué) — utilisé
     *  pour les actions secondaires personnalisables (Transcoder, Soumettre AcoustID…) afin de les
     *  distinguer visuellement des 5 actions fixes (Ouvrir/Rafraîchir/Tout tagger/Tagger la
     *  sélection/Annuler), plutôt que d'avoir 8+ boutons de poids visuel identique dans la même
     *  rangée. */
    private JButton secondaryBtn(String text, String tip) { return secondaryBtn(text, tip, null); }

    private JButton secondaryBtn(String text, String tip, ToolbarIcon.Kind icon) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        // Même relief 3D explicite que headerBtn() (voir son commentaire), en plus discret —
        // cohérent avec le rôle "action secondaire" de ce bouton (police déjà plus petite/atténuée).
        b.putClientProperty("FlatLaf.style",
            "foreground: #90A4AE; font: 11 $defaultFont; borderWidth: 1; borderColor: #424246; "
            + "background: #333336; hoverBackground: #3D3D41; pressedBackground: #29292C; arc: 6");
        if (icon != null) { b.setIcon(new ToolbarIcon(icon, new Color(0x90A4AE), 14)); b.setIconTextGap(6); }
        return b;
    }

    /** Associe un id d'action de la barre secondaire (voir toolbarActionRegistry()) à une icône —
     *  seules les actions les plus fréquentes ont une icône dédiée pour l'instant ; les autres
     *  restent en texte seul plutôt que d'improviser un glyphe qui ne représenterait rien. */
    private static ToolbarIcon.Kind secondaryIconFor(String actionId) {
        return switch (actionId) {
            case "refreshFolders"  -> ToolbarIcon.Kind.REFRESH;
            case "transcode"       -> ToolbarIcon.Kind.TRANSCODE;
            case "submitAcoustId"  -> ToolbarIcon.Kind.UPLOAD;
            default -> null;
        };
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
        JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 5));
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
        // Non cliquable (pas de statut à filtrer dessus) — débit/ETA globaux, calculés sur une
        // fenêtre glissante dans refreshStats(), voir throughputText().
        lblThroughput     = infoChip("…", CHIP_THROUGHPUT);

        p.add(new JLabel("  "));
        p.add(lblStatTotal);
        p.add(sep3());
        p.add(lblStatIdentified);
        p.add(lblStatTagged);
        p.add(lblStatSkipped);
        p.add(lblStatError);
        p.add(lblStatPending);
        p.add(sep3());
        p.add(lblThroughput);

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
            activeSkipReasonFilter = null;
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
                activeSkipReasonFilter = null;
                applyFilter();
            }
        });
        styleChip(l, color, false);
        return l;
    }

    /** Variante non cliquable de {@link #statChip} — pas de MouseListener/curseur main, pas de
     *  sémantique active/inactive (toujours rendu dans son style "inactif", il n'y a pas de filtre
     *  associé à activer/désactiver) : réservé aux chips purement informatifs comme le débit/ETA. */
    private JLabel infoChip(String val, Color color) {
        JLabel l = new JLabel(val);
        l.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
        l.setOpaque(true);
        l.setToolTipText(I18n.t("Débit et temps restant estimé, sur les ~12 dernières minutes"));
        styleChip(l, color, false);
        return l;
    }

    /** Applique l'apparence active/inactive d'un chip (fond teinté + bordure épaisse si actif). */
    private void styleChip(JLabel chip, Color color, boolean active) {
        Color translucent = new Color(color.getRed(), color.getGreen(), color.getBlue(), 90);
        chip.setForeground(active ? Color.WHITE : color);
        chip.setBackground(active ? blend(new Color(0x252527), translucent) : new Color(0x252527));
        // Relief 3D (retour utilisateur, 2026-08-10) : la LineBorder colorée seule restait plate,
        // aucun jeu d'ombre/lumière — un BevelBorder (clair en haut/gauche, foncé en bas/droite,
        // dérivé de la couleur du chip) ajoute un vrai relief sans changer la couleur ni la logique
        // active/inactive existantes, juste enveloppé autour d'elles.
        chip.setBorder(new CompoundBorder(
            new LineBorder(color.darker(), active ? 2 : 1, true),
            new CompoundBorder(
                new BevelBorder(BevelBorder.RAISED, color.brighter(), color.darker()),
                new EmptyBorder(1, 5, 1, 5))));
    }

    private JLabel sep3() {
        JLabel l = new JLabel("  ");
        return l;
    }

    private void refreshStats() {
        // Chargement paresseux de l'historique undo persistant (voir UndoManager) — au plus tôt
        // possible sans dépendre d'un callback "scan terminé" spécifique, mais pas avant que la
        // table ait effectivement quelque chose dedans (sinon le resolver ne trouverait jamais
        // rien et on aurait quand même consommé un aller-retour SQLite pour rien à chaque
        // démarrage sans dossier ouvert).
        if (!undoHistoryBound && !tableModel.allEntries().isEmpty()) {
            undoHistoryBound = true;
            java.util.Map<Path, FileEntry> byPath = new java.util.HashMap<>();
            for (FileEntry fe : tableModel.allEntries()) {
                Path p = (fe.currentPath != null ? fe.currentPath : fe.file.toPath()).toAbsolutePath().normalize();
                byPath.put(p, fe);
            }
            undoManager.bindPersistence(p -> byPath.get(p.toAbsolutePath().normalize()));
        }
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

        // Échantillon débit/ETA — voir throughputText(). O(1), aucune passe supplémentaire sur la
        // table (pending déjà calculé ci-dessus).
        long nowMs = System.currentTimeMillis();
        pendingHistory.addLast(new long[]{nowMs, pending});
        while (!pendingHistory.isEmpty() && nowMs - pendingHistory.peekFirst()[0] > THROUGHPUT_WINDOW_MS)
            pendingHistory.removeFirst();
        lblThroughput.setText(throughputText(pending));

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

            // Avant ce correctif, un tableau vide (premier lancement, ou "Vider" cliqué) était un
            // simple rectangle sombre sans aucune indication — rien ne dit à un nouvel utilisateur
            // comment commencer. Dessiné directement dans le JTable (pas un composant séparé) pour
            // rester dans la zone déjà scrollable/redimensionnable sans toucher au reste du layout.
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() > 0) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x6E6E72));
                String line1 = I18n.t("Aucun fichier chargé");
                String line2 = I18n.t("Ctrl+O ou glissez-déposer un dossier ici pour commencer");
                Rectangle vis = getVisibleRect();
                g2.setFont(getFont().deriveFont(Font.BOLD, 15f));
                FontMetrics fm1 = g2.getFontMetrics();
                int y = vis.y + vis.height / 2;
                g2.drawString(line1, vis.x + (vis.width - fm1.stringWidth(line1)) / 2, y - 6);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 12f));
                FontMetrics fm2 = g2.getFontMetrics();
                g2.drawString(line2, vis.x + (vis.width - fm2.stringWidth(line2)) / 2, y + fm2.getHeight());
                g2.dispose();
            }
        };
        configureTable();
        installContextMenu();
        // Propager le TransferHandler de la fenêtre à la table
        table.setTransferHandler(getTransferHandler());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);
        scroll.setMinimumSize(new Dimension(200, 0)); // filet de sécurité : jamais écrasée à ~0px

        // AUTO_RESIZE_OFF (configureTable()) garde les colonnes à largeur fixe pour éviter
        // l'écrasement en fenêtre étroite — mais laisse alors un vide vide à droite de la dernière
        // colonne (Statut) dès que la fenêtre dépasse la somme des largeurs de colonnes, ex. plein
        // écran : signalé par l'utilisateur (2026-08-12). On étire la dernière colonne pour combler
        // cet espace UNIQUEMENT quand il y en a — recalculé à chaque redimensionnement (pas cumulé),
        // donc rétrécit tout aussi bien la colonne si la fenêtre se réduit ensuite, jusqu'à ce que le
        // défilement horizontal reprenne naturellement (comportement normal d'AUTO_RESIZE_OFF).
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) { adjustLastColumnToFillViewport(); }
        });

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
        // AUTO_RESIZE_OFF (au lieu du défaut AUTO_RESIZE_SUBSEQUENT_COLUMNS) : sans ça, une fenêtre
        // réduite en largeur écrase PROGRESSIVEMENT toutes les colonnes vers leurs minimums (colWidth()
        // ci-dessous, ex. Artiste=70px, Genre=50px) avant même de faire apparaître le défilement
        // horizontal du JScrollPane — le texte (Artiste, Album, Titre…) devient illisible plutôt que
        // simplement défiler. OFF garde chaque colonne à sa largeur configurée/sauvegardée en
        // permanence ; le JScrollPane (déjà présent) prend le relais avec une barre horizontale dès
        // que la somme dépasse la largeur visible — signalé par l'utilisateur (2026-08-11) : colonnes
        // tronquées à taille de fenêtre réduite.
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(26);
        // Sans ça, un JTable avec 0 ligne (ou moins de lignes que la hauteur du viewport) ne
        // remplit que sa hauteur de contenu réelle — le grand rectangle vide sous l'en-tête est
        // alors peint par le JScrollPane (fond du viewport), PAS par le JTable lui-même, et le
        // message d'accueil dessiné dans son paintComponent() (voir sa création juste au-dessus)
        // ne s'affiche jamais faute de surface sur laquelle se dessiner.
        table.setFillsViewportHeight(true);
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
        // Année/Piste élargies (62/56, avant 52/44) : leurs en-têtes ("Année", "Piste") étaient
        // tronqués en "Ann…"/"Pi…" par défaut sur un premier lancement — repéré à l'écran, pas
        // seulement en lisant le code (voir capture d'écran de revue d'interface).
        colWidth(cm, 6, 62,  44,  76);   // Année
        colWidth(cm, 7, 120, 50,  220);  // Genre
        colWidth(cm, 8, 56,  36,  70);   // Piste
        colWidth(cm, 9, 130, 80,  220);  // Statut
        colWidth(cm, 10, 56,  40,  90);  // Durée
        // Ré-affichée (2026-08-15, retour utilisateur) après avoir été masquée le temps que le
        // rattrapage en arrière-plan de la durée (bibliothèque scannée avant l'ajout de la colonne)
        // converge — COL_DURATION=10 et son comparateur de tri (juste au-dessus) n'ont jamais cessé
        // d'être à jour côté modèle, seule la visibilité changeait.

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

    /** Étire la dernière colonne visible pour combler l'espace vide à droite du tableau quand la
     *  fenêtre est plus large que la somme des colonnes (AUTO_RESIZE_OFF, voir configureTable()) —
     *  appelée au redimensionnement du viewport. Relève temporairement le maxWidth de la colonne
     *  (colWidth() la borne normalement à 220px pour Statut, bien en-deçà de ce qu'un plein écran
     *  peut laisser comme espace restant) : un peu de vide DANS la colonne reste préférable à une
     *  bande vide et sans nom à sa droite. */
    private void adjustLastColumnToFillViewport() {
        if (table == null) return;
        TableColumnModel cm = table.getColumnModel();
        int n = cm.getColumnCount();
        if (n == 0) return;
        Container vp = table.getParent();
        if (!(vp instanceof JViewport)) return;
        int viewportWidth = vp.getWidth();
        int sumOthers = 0;
        for (int i = 0; i < n - 1; i++) sumOthers += cm.getColumn(i).getWidth();
        TableColumn last = cm.getColumn(n - 1);
        int target = Math.max(last.getMinWidth(), viewportWidth - sumOthers);
        if (target > last.getMaxWidth()) last.setMaxWidth(target);
        if (target != last.getWidth()) {
            last.setPreferredWidth(target);
            last.setWidth(target);
        }
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
                        // allEntries() : sinon un fichier coché mais masqué par un filtre actif
                        // restait coché et se faisait tagger EN PLUS de l'album ciblé (startTagging
                        // parcourt maintenant toute la bibliothèque pour ses candidats .selected).
                        for (FileEntry other : tableModel.allEntries()) {
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

    // Longueur max avant troncature du chemin affiché sous la pochette (lblFilePath) — un JLabel
    // n'ellipse jamais son texte tout seul et sa preferred width (texte complet) pilote celle de
    // TOUT le panneau de détail (BoxLayout.Y_AXIS autour de coverSection/lblFilePath/detailPanel,
    // voir buildDetailPanel()) : un chemin long (bibliothèque à plusieurs niveaux de dossiers)
    // forçait tout le panneau — pochette comprise — à s'élargir/se décaler pour l'accommoder,
    // rognant le texte des champs des onglets Pochette/URLs & IDs tant que la fenêtre n'était pas
    // agrandie à la main. Le chemin complet reste consultable via l'info-bulle au survol.
    private static final int FILE_PATH_LABEL_MAX_CHARS = 60;

    private void setFilePathLabel(String fullText) {
        lblFilePath.setToolTipText(fullText.isBlank() ? null : fullText.strip());
        if (fullText.length() <= FILE_PATH_LABEL_MAX_CHARS) {
            lblFilePath.setText(fullText);
            return;
        }
        // Tronque le DÉBUT (garde la fin — nom de fichier et derniers dossiers, le plus utile
        // visuellement) plutôt que la fin, contrairement à une troncature "..." classique en
        // queue de chaîne.
        lblFilePath.setText("…" + fullText.substring(fullText.length() - FILE_PATH_LABEL_MAX_CHARS + 1));
    }

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
        // En-tête (pochette + chemin + séparateur) FIXE, hors de tout scroll — avant ce correctif,
        // header+onglets étaient empilés dans UN SEUL panneau lui-même mis dans un JScrollPane
        // extérieur, alors que chaque onglet (buildGeneralTab()/buildClassicalTab()/etc., voir
        // scroll()) a DÉJÀ son propre JScrollPane interne. Deux défilements imbriqués : sur un
        // onglet avec beaucoup de champs, descendre dedans faisait aussi sortir la pochette/le
        // chemin de l'écran (défiler le scroll EXTÉRIEUR), obligeant à remonter séparément pour les
        // revoir — repéré par l'utilisateur ("scroller en haut ET en bas"). Pattern web usuel :
        // en-tête "sticky" fixe, seul le contenu de l'onglet actif défile en dessous, indépendamment
        // — obtenu simplement en supprimant le JScrollPane extérieur et en laissant BorderLayout
        // donner tout l'espace restant (NORTH pris par l'en-tête, SOUTH par le footer) au
        // JTabbedPane, dont chaque onglet gère déjà son propre scroll.
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.add(coverSection);
        header.add(lblFilePath);
        header.add(Box.createVerticalStrut(6));
        header.add(accentLine);
        header.add(metaTitle);

        // Masquer la vignette permanente (coverSection, juste au-dessus) pendant que l'onglet
        // Pochette est actif : il affiche déjà la MÊME image, en plus grand — le duplicata
        // donnait une impression brouillonne/redondante (repéré à l'écran). revalidate()+repaint()
        // nécessaires : BoxLayout ne re-remesure pas tout seul un composant masqué via setVisible().
        detailPanel.setOnPochetteTabActive(onPochette -> {
            coverSection.setVisible(!onPochette);
            header.revalidate();
            header.repaint();
        });

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new MatteBorder(0, 1, 0, 0, new Color(0x3A3B3E)));
        panel.add(header,      BorderLayout.NORTH);
        panel.add(detailPanel, BorderLayout.CENTER);
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

    // Dernière sélection effectivement traitée par refreshDetail() — évite de refaire tout le
    // travail (populateMulti() itère TOUS les champs × TOUTES les entrées sélectionnées) quand la
    // même sélection redéclenche un valueChanged SANS avoir réellement changé. Trouvé en direct
    // 2026-07-29 : JTable$SortManager.restoreSelection() (déclenché par un simple
    // fireTableRowsInserted lors d'un scan actif) émet un valueChanged avec isAdjusting=false À LA
    // FIN de sa restauration — le garde `!e.getValueIsAdjusting()` déjà présent sur ce listener ne
    // filtre donc PAS ces appels. Avec une sélection multiple large (ex. "tout sélectionner") et un
    // scan qui met à jour des milliers de lignes, ce recalcul en boucle a gelé l'EDT plus d'une
    // heure (populateMulti() sur des dizaines de milliers d'entrées, des dizaines de fois par
    // seconde). List.equals() suffit ici : FileEntry n'a pas d'equals() custom (voir le commentaire
    // de FileTableModel sur IdentityHashMap), donc c'est une comparaison par référence — exactement
    // ce qu'il faut pour détecter "toujours les mêmes objets, dans le même ordre".
    private List<FileEntry> lastDetailEntries = null;

    private void refreshDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { lastDetailEntries = null; clearDetail(); return; }

        // Résolution unifiée liste/arborescence : une ligne normale résout vers elle-même, une
        // ligne d'en-tête de groupe (vue arborescence) résout vers TOUS ses membres — permet
        // d'éditer un album entier en lot (populateMulti ci-dessous) juste en sélectionnant son
        // en-tête, sans avoir à le déplier.
        List<FileEntry> entries = new ArrayList<>();
        for (int r : rows) entries.addAll(entriesAtViewRow(r));
        if (entries.isEmpty()) { clearDetail(); return; }
        if (entries.equals(lastDetailEntries)) return; // même sélection qu'avant, rien à refaire
        lastDetailEntries = entries;

        if (entries.size() == 1) {
            // Mode fichier unique
            FileEntry e  = entries.get(0);
            TagInfo   ti = e.activeTags();
            setFilePathLabel("  " + (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath());
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

    /**
     * Applique une note importée (voir ITunesImportDialog) à chaque entrée du tableau
     * actuellement chargée — même chemin que applyDetail() (snapshot avant/après, undo persistant,
     * écriture sécurisée) pour qu'une note importée par erreur reste annulable exactement comme
     * une édition manuelle, y compris après redémarrage (voir UndoManager). Ne touche jamais un
     * fichier qui n'est pas actuellement dans ce tableau — voir ITunesImportDialog pour le
     * pourquoi (ne jamais écrire sur un fichier que l'utilisateur n'a pas chargé/vu cette
     * session).
     */
    /** Répercute une note éditée manuellement vers la file XML iTunes (voir ITunesXmlSyncQueue) —
     *  UNIQUEMENT si ce fichier a déjà un Track ID connu (établi par un import XML antérieur, voir
     *  ITunesImportDialog) ; no-op silencieux sinon (immense majorité des fichiers). Ne pousse
     *  jamais rien depuis applyRatingImport() elle-même : la note vient déjà de ce même Track ID,
     *  la repousser serait un aller-retour sans effet. */
    private void queueRatingBackToItunes(FileEntry e, String newRating) {
        if (e.itunesTrackId == null || newRating == null || newRating.isBlank()) return;
        try {
            int stars = Integer.parseInt(newRating.trim());
            if (stars >= 1 && stars <= 5) {
                com.opentagger.ITunesXmlSyncQueue.queueRatingChange(e.itunesTrackId, stars);
            }
        } catch (NumberFormatException ignored) {
            // Valeur brute ID3 (0-255) plutôt que 1-5 étoiles — voir TagEnrichment.parseStars()
            // pour la même ambiguïté ; pas assez fiable pour repousser vers iTunes sans risquer un
            // mauvais nombre d'étoiles, on préfère s'abstenir.
        }
    }

    /**
     * Applique un album Bandcamp (voir BandcampMatchDialog) — position N de l'album → N-ième
     * fichier de {@code selection} (ordre déjà fixé par le tableau au moment de l'appel). Même
     * circuit que les autres écritures manuelles (snapshot/undo persistant/écriture sécurisée).
     */
    public void applyBandcampAlbum(com.opentagger.BandcampClient.BandcampAlbum album, List<FileEntry> selection) {
        int n = Math.min(album.tracks().size(), selection.size());
        if (n == 0) return;
        MetadataCache correctionsCache = new MetadataCache();
        try {
            for (int i = 0; i < n; i++) {
                var track = album.tracks().get(i);
                FileEntry e    = selection.get(i);
                TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                TagInfo   ti   = e.activeTags();
                ti.artist      = album.artist();
                ti.albumArtist = album.artist();
                ti.album       = album.title();
                ti.title       = track.title();
                ti.track       = String.valueOf(track.position());
                ti.trackTotal  = String.valueOf(album.tracks().size());
                // Format réel constaté ("08 Aug 2018 00:00:00 GMT", voir BandcampClient) — l'année
                // n'est PAS dans les 4 derniers caractères ("GMT"), d'où l'extraction par regex
                // plutôt qu'un substring naïf en fin de chaîne.
                java.util.regex.Matcher ym = java.util.regex.Pattern.compile("\\b(\\d{4})\\b")
                        .matcher(album.releaseDate());
                if (ym.find()) ti.year = ym.group(1);
                int mr = tableModel.indexOf(e);
                if (mr >= 0) refreshTableRow(mr, ti);
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti),
                        I18n.t("Bandcamp %s", e.filename()));
                recordFieldCorrections(correctionsCache, e, snap, ti);
                if (writeTagsSafe(e, ti)) markManuallyTagged(e, ti, mr);
            }
            setStatus(I18n.t("Bandcamp appliqué — %d fichier(s)", n));
        } finally {
            correctionsCache.close();
        }
    }

    public void applyRatingImport(java.util.Map<FileEntry, String> newRatings) {
        if (newRatings.isEmpty()) return;
        MetadataCache correctionsCache = new MetadataCache();
        try {
            for (var entry : newRatings.entrySet()) {
                FileEntry e    = entry.getKey();
                TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                TagInfo   ti   = e.activeTags();
                ti.rating = entry.getValue();
                int mr = tableModel.indexOf(e);
                if (mr >= 0) refreshTableRow(mr, ti);
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti),
                        I18n.t("Import note iTunes %s", e.filename()));
                recordFieldCorrections(correctionsCache, e, snap, ti);
                if (writeTagsSafe(e, ti)) markManuallyTagged(e, ti, mr);
            }
            setStatus(I18n.t("Notes iTunes appliquées — %d fichier(s)", newRatings.size()));
        } finally {
            correctionsCache.close();
        }
    }

    /**
     * Applique la file d'attente (voir ITunesXmlSyncQueue) au VRAI fichier XML iTunes — action
     * manuelle explicite uniquement, jamais déclenchée automatiquement (voir ITunesXmlWriter pour
     * les garanties : sauvegarde horodatée systématique, réécriture chirurgicale ligne par ligne,
     * jamais un DOM complet). Demande utilisateur (2026-08-16) après mise en garde sur le risque
     * réel de désynchronisation avec la vraie base iTunes (.itl).
     */
    private void writeItunesXmlCorrections() {
        int pending = com.opentagger.ITunesXmlSyncQueue.pendingCount();
        if (pending == 0) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Aucune correction en attente (renommage ou note sur un fichier importé depuis iTunes)."),
                    I18n.t("XML iTunes"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("iTunes Music Library.xml", "xml"));
        fc.setDialogTitle(I18n.t("Fichier XML iTunes à corriger"));
        // Chemin mémorisé (Préférences > iTunes) — voir Config.itunesXmlFilePath().
        String remembered = Config.get().itunesXmlFilePath();
        if (!remembered.isBlank()) fc.setSelectedFile(new java.io.File(remembered));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File xml = fc.getSelectedFile();
        Config.get().set("itunes.xml_file_path", xml.getAbsolutePath());

        int ok = JOptionPane.showConfirmDialog(this, I18n.t(
                "%d correction(s) en attente vont être écrites dans :\n%s\n\n"
              + "Une sauvegarde horodatée sera créée AVANT toute modification "
              + "(fichier.backup_AAAAMMJJ_HHMMSS, dans le même dossier).\n\n"
              + "Continuer ?", pending, xml.getAbsolutePath()),
                I18n.t("Confirmer l'écriture"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        setStatus(I18n.t("Écriture des corrections dans le XML iTunes…"));
        new SwingWorker<com.opentagger.ITunesXmlWriter.Result, Void>() {
            @Override protected com.opentagger.ITunesXmlWriter.Result doInBackground() throws Exception {
                var changes = com.opentagger.ITunesXmlSyncQueue.snapshotAndClear();
                return com.opentagger.ITunesXmlWriter.apply(xml, changes);
            }
            @Override protected void done() {
                try {
                    var r = get();
                    JOptionPane.showMessageDialog(MainFrame.this, I18n.t(
                            "%d chemin(s) corrigé(s), %d note(s) mise(s) à jour, %d ignoré(s) "
                          + "(vérification aller-retour échouée ou préfixe non configuré).\n\n"
                          + "Sauvegarde : %s",
                            r.locationChanges(), r.ratingChanges(), r.skippedUnverified(),
                            r.backupFile() != null ? r.backupFile().getAbsolutePath() : "—"),
                            I18n.t("XML iTunes mis à jour"), JOptionPane.INFORMATION_MESSAGE);
                    setStatus(I18n.t("XML iTunes mis à jour"));
                } catch (Exception ex) {
                    showError(I18n.t("Échec de l'écriture du XML iTunes : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    /** Sélection actuelle, DANS L'ORDRE du tableau (pas l'ordre de clic) — voir BandcampMatchDialog,
     *  qui applique la piste N de Bandcamp au N-ième élément de cette liste. */
    private void openBandcampDialog() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Sélectionne d'abord les fichiers de l'album dans le tableau, dans l'ordre des pistes."),
                    I18n.t("Bandcamp"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // rows est déjà dans l'ordre VISUEL (ordre d'affichage, ex. trié par n° de piste) — surtout
        // ne pas re-trier par index modèle, ça annulerait un tri actif et casserait l'ordre voulu.
        List<FileEntry> selection = new ArrayList<>();
        for (int viewRow : rows) selection.add(tableModel.get(table.convertRowIndexToModel(viewRow)));
        new BandcampMatchDialog(this, selection).setVisible(true);
    }

    private void applyDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;

        // Traçabilité des corrections manuelles (table MetadataCache.corrections, jusqu'ici
        // écrite par personne — voir recordFieldCorrections) : une seule connexion pour tout
        // l'appel plutôt qu'une par fichier édité en lot.
        MetadataCache correctionsCache = new MetadataCache();
        try {
            if (rows.length == 1) {
                // ── Fichier unique ─────────────────────────────────────────────
                int       mr   = table.convertRowIndexToModel(rows[0]);
                FileEntry e    = tableModel.get(mr);
                TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                TagInfo   ti   = e.activeTags();
                detailPanel.collect(ti);
                refreshTableRow(mr, ti);
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Modifier %s", e.filename()));
                recordFieldCorrections(correctionsCache, e, snap, ti);
                if (!ti.rating.equals(snap.rating)) queueRatingBackToItunes(e, ti.rating);
                // Ne marquer TAGGED qu'APRÈS confirmation d'écriture réussie — sinon un échec
                // d'écriture (permissions, fichier verrouillé...) laissait quand même le statut à
                // TAGGED, jamais annulé (même défaut déjà corrigé dans les autres pipelines).
                if (writeTagsSafe(e, ti)) markManuallyTagged(e, ti, mr);
                setStatus(I18n.t("Tags sauvegardés — %s", e.filename()));
            } else {
                // ── Édition en lot ─────────────────────────────────────────────
                // Écrivait auparavant directement sur l'EDT (une écriture TagWriter par fichier
                // sélectionné, potentiellement des dizaines) — sur une sélection multiple assez
                // large, ou un simple fichier M4A qui déclenche la chaîne de réparation ffmpeg
                // (voir TagWriter), ça gelait l'interface pour toute la durée du lot. La collecte
                // depuis DetailPanel (lecture des champs UI) reste sur l'EDT ; seule l'écriture
                // disque (I/O) est poussée en arrière-plan, comme buildRenameJob().
                record PendingWrite(FileEntry entry, int modelRow, TagInfo snap, TagInfo ti) {}
                List<PendingWrite> pending = new ArrayList<>();
                for (int row : rows) {
                    int       mr   = table.convertRowIndexToModel(row);
                    FileEntry e    = tableModel.get(mr);
                    TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                    TagInfo   ti   = e.activeTags();
                    detailPanel.collect(ti);
                    refreshTableRow(mr, ti);
                    undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Lot %s", e.filename()));
                    if (!ti.rating.equals(snap.rating)) queueRatingBackToItunes(e, ti.rating);
                    pending.add(new PendingWrite(e, mr, snap, ti));
                }
                setStatus(I18n.t("Sauvegarde de %d fichier(s)…", pending.size()));
                // Erreurs accumulées (fichier + raison séparés, pas une seule chaîne concaténée)
                // plutôt que remontées via showError() à chaque échec : un lot de N fichiers en
                // échec (permissions, verrou...) ouvrait N popups modaux JOptionPane consécutifs,
                // chacun bloquant jusqu'à fermeture manuelle — un seul résumé structuré en fin de
                // lot (voir showBatchSaveErrors()) est bien plus lisible qu'un mur de popups ou
                // qu'une seule ligne "fichier : erreur" par échec bout à bout.
                record WriteError(String filename, String reason) {}
                List<WriteError> errors = new ArrayList<>();
                int total = pending.size();
                new SwingWorker<Integer, PendingWrite>() {
                    @Override protected Integer doInBackground() {
                        int saved = 0;
                        for (PendingWrite w : pending) {
                            recordFieldCorrections(correctionsCache, w.entry(), w.snap(), w.ti());
                            String err = writeTags(w.entry(), w.ti());
                            if (err == null) { saved++; publish(w); }
                            else errors.add(new WriteError(w.entry().filename(), err));
                        }
                        return saved;
                    }
                    @Override protected void process(List<PendingWrite> chunks) {
                        for (PendingWrite w : chunks) markManuallyTagged(w.entry(), w.ti(), w.modelRow());
                    }
                    @Override protected void done() {
                        correctionsCache.close();
                        int saved = 0;
                        try { saved = get(); } catch (Exception ignored) {}
                        setStatus(I18n.t("Tags sauvegardés — %d fichier(s).", saved));
                        if (!errors.isEmpty()) {
                            showBatchSaveErrors(saved, total,
                                    errors.stream().map(er -> new String[]{er.filename(), er.reason()}).toList());
                        }
                    }
                }.execute();
                return; // le finally ci-dessous fermerait correctionsCache avant la fin du worker
            }
        } finally {
            if (rows.length == 1) correctionsCache.close();
        }
    }

    /**
     * Journalise chaque champ texte modifié à la main dans la table `corrections` — jusqu'ici
     * créée et écrivable (MetadataCache.recordCorrection) mais jamais appelée nulle part dans le
     * dépôt : la traçabilité des corrections promise par la Javadoc de classe de MetadataCache
     * n'existait pas en pratique. Réflexion sur les champs String publics de TagInfo plutôt qu'une
     * liste à la main : reste correct si de nouveaux champs texte sont ajoutés au modèle.
     */
    private void recordFieldCorrections(MetadataCache cache, FileEntry e, TagInfo before, TagInfo after) {
        String path = (e.currentPath != null ? e.currentPath : e.file.toPath()).toString();
        for (java.lang.reflect.Field f : TagInfo.class.getFields()) {
            if (f.getType() != String.class) continue;
            try {
                String oldVal = (String) f.get(before);
                String newVal = (String) f.get(after);
                if (newVal != null && !newVal.equals(oldVal))
                    cache.recordCorrection(path, f.getName(), oldVal, newVal);
            } catch (IllegalAccessException ignored) {}
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
        String err = writeTags(e, ti);
        if (err != null) { showError(I18n.t("Erreur écriture %s : %s", e.filename(), err)); return false; }
        return true;
    }

    /** Comme {@link #writeTagsSafe} mais sans popup — retourne le message d'erreur (ou null si
     *  succès) pour que l'appelant décide comment le restituer (voir applyDetail(), édition en
     *  lot : les erreurs sont accumulées et résumées en un seul popup plutôt qu'un par fichier). */
    private String writeTags(FileEntry e, TagInfo ti) {
        try {
            File target = e.currentPath != null ? e.currentPath.toFile() : e.file;
            new com.opentagger.TagWriter().write(target, ti);
            return null;
        } catch (Exception ex) {
            return ex.getMessage();
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

    private static String progressSlotLabel(ProgressSlot slot) {
        return switch (slot) {
            case TAGGING             -> I18n.t("Taguage");
            case SAVE                -> I18n.t("Enregistrement");
            case INFO_COMPLETER      -> I18n.t("Complétion");
            case TRANSCODE           -> I18n.t("Transcodage");
            case ALBUM_COMPLETION    -> I18n.t("Complétion albums");
            case ALBUM_CLUSTER       -> I18n.t("Groupement albums");
            case COMPILATION_CLUSTER -> I18n.t("Compilations");
            case VIDEO_RECOVERY      -> I18n.t("Récupération vidéo");
            case LISTENBRAINZ_SYNC   -> I18n.t("ListenBrainz");
            case DUPLICATE_DETECT    -> I18n.t("Doublons");
        };
    }

    /** À appeler au DÉBUT du worker propriétaire de ce slot, à la place de l'ancien
     *  "progress.setValue(0); progress.setString(\"\"); progress.setVisible(true);" — chaque slot a
     *  sa propre barre, donc aucun risque de conflit avec un autre worker concurrent (voir le
     *  commentaire sur ProgressSlot). */
    private void beginProgress(ProgressSlot slot) {
        JProgressBar bar = progressBars.get(slot);
        bar.setValue(0);
        bar.setString("");
        bar.setVisible(true);
        progressPanel.revalidate();
    }

    /** À appeler à la FIN du worker propriétaire de ce slot, à la place de l'ancien
     *  "progress.setVisible(false);". Idempotent (comme l'ancien code) : rien à clamper, deux appels
     *  successifs (ex. stopAll() suivi du listener DONE du worker annulé) sont sans risque. */
    private void endProgress(ProgressSlot slot) {
        progressBars.get(slot).setVisible(false);
        progressPanel.revalidate();
    }

    /** stopAll()/"Arrêter" annule TOUS les workers actifs (WorkerHub.cancelAll()), pas seulement le
     *  taguage — masquer uniquement la barre TAGGING via resetBtns() en laissait d'autres visibles
     *  (Enregistrement, Complétion...) alors que leur worker vient, lui aussi, d'être annulé. */
    private void endAllProgress() {
        for (ProgressSlot slot : ProgressSlot.values()) endProgress(slot);
    }

    private JPanel buildStatusBar() {
        lblStatus = new JLabel(I18n.t(" Prêt"));

        // Une barre par ProgressSlot, empilées verticalement, chacune invisible tant que son
        // worker ne tourne pas (BoxLayout ignore les enfants invisibles — aucune place prise).
        progressPanel = new JPanel();
        progressPanel.setLayout(new BoxLayout(progressPanel, BoxLayout.Y_AXIS));
        progressPanel.setOpaque(false);
        for (ProgressSlot slot : ProgressSlot.values()) {
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setPreferredSize(new Dimension(220, 14));
            bar.setMaximumSize(new Dimension(220, 14));
            bar.setStringPainted(true);
            bar.setVisible(false);
            bar.setToolTipText(progressSlotLabel(slot));
            progressBars.put(slot, bar);
            progressPanel.add(bar);
        }

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
        eastPanel.add(progressPanel);

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
                        case ERROR      -> new Color(230, 90, 90);
                        case SKIPPED    -> new Color(210, 160, 40);
                        case TAGGED     -> new Color(100, 200, 130);
                        case IDENTIFIED -> new Color(85, 153, 255);
                        default         -> UIManager.getColor("List.foreground");
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

        // Bouton-titre (au lieu d'un simple JLabel) : cliquer sur "Journal" replie/déplie le
        // panneau — retour utilisateur (2026-08-10), voulait un déclencheur visuel évident ("un
        // peu d'ombres... en relief"), pas juste une case dans le menu Affichage. JToggleButton
        // (pas JButton) pour son état "enfoncé"/coloré natif FlatLaf quand sélectionné = le
        // changement de couleur demandé, sans peinture personnalisée. Reste TOUJOURS dans le
        // header (jamais masqué avec le contenu, voir applyJournalVisibility()) : sinon, une fois
        // le journal replié, plus aucun moyen de recliquer dessus pour le rouvrir.
        // Style FlatLaf par défaut d'un JToggleButton = déjà un bouton net avec bordure/relief
        // (pas de JLabel plat) — seule surcharge nécessaire : la couleur à l'état "sélectionné"
        // (journal visible), pour le changement de couleur demandé.
        btnToggleJournal = new JToggleButton(I18n.t("▾ Journal"), true);
        btnToggleJournal.setFocusPainted(false);
        btnToggleJournal.putClientProperty("FlatLaf.style",
            "font: bold $defaultFont; arc: 6; selectedBackground: #1a6030; selectedForeground: #ffffff");
        btnToggleJournal.setToolTipText(I18n.t("Afficher/masquer le journal"));
        btnToggleJournal.addActionListener(e -> {
            btnToggleJournal.setText(btnToggleJournal.isSelected() ? I18n.t("▾ Journal") : I18n.t("▸ Journal"));
            applyJournalVisibility(btnToggleJournal.isSelected());
        });
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        headerRight.add(chkLogErrorsOnly);
        headerRight.add(btnClearLog);
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(3, 6, 3, 0));
        header.add(btnToggleJournal, BorderLayout.WEST);
        header.add(headerRight,      BorderLayout.EAST);
        journalHeader = header;

        JScrollPane scroll = new JScrollPane(logList);
        scroll.setBorder(new MatteBorder(1, 0, 0, 0, sep()));
        journalScroll = scroll;

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
    /** Libellé lisible d'une source d'identification (TagInfo.identificationSource / MetadataCache.SOURCE_*). */
    private static String sourceLabel(String src) {
        if (src == null || src.isBlank()) return "";
        return switch (src) {
            case MetadataCache.SOURCE_SONGREC  -> "SongRec";
            case MetadataCache.SOURCE_ACOUSTID -> "AcoustID";
            case MetadataCache.SOURCE_MBID     -> "MusicBrainz";
            case MetadataCache.SOURCE_TEXT     -> I18n.t("texte");
            default -> src;
        };
    }

    /** Résumé lisible d'une identification pour le journal, ex. "Artiste – Titre [Album] (score=90, SongRec)".
     *  Chaîne vide si aucune donnée exploitable (ex. SKIPPED/ERROR sans résultat). */
    private static String identificationSummary(FileEntry entry) {
        TagInfo t = entry.activeTags();
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        if (!t.artist.isBlank() || !t.title.isBlank()) {
            sb.append(t.artist.isBlank() ? "?" : t.artist)
              .append(" – ")
              .append(t.title.isBlank() ? "?" : t.title);
        }
        if (!t.album.isBlank()) sb.append(" [").append(t.album).append("]");
        java.util.List<String> extras = new java.util.ArrayList<>();
        if (t.score > 0) extras.add("score=" + t.score);
        String src = sourceLabel(t.identificationSource);
        if (!src.isBlank()) extras.add(src);
        if (!extras.isEmpty()) sb.append(" (").append(String.join(", ", extras)).append(")");
        return sb.toString();
    }

    // Package-private (pas private) : PodcastDialog est une fenêtre séparée (pas dans MainFrame)
    // qui a besoin d'écrire dans CE journal partagé plutôt que d'en avoir un second — voir son
    // constructeur/onTag().
    void appendLog(FileEntry entry) {
        if (entry.status == FileEntry.Status.PROCESSING) return;
        String statusText = switch (entry.status) {
            case TAGGED     -> "✓ " + I18n.t("Tagué");
            case IDENTIFIED -> "🔍 " + I18n.t("Identifié");
            case SKIPPED    -> "⚠ " + I18n.t("Ignoré");
            case ERROR      -> "✗ " + I18n.t("Erreur");
            default         -> entry.status.toString();
        };
        // Détail artiste/titre/album/score/source — le principal manque signalé sur ce journal
        // ("IDENTIFIED" tout court ne dit rien) ; ajouté pour TAGGED/IDENTIFIED où un résultat
        // existe réellement (SKIPPED/ERROR n'ont normalement rien à résumer, message suffit).
        if (entry.status == FileEntry.Status.TAGGED || entry.status == FileEntry.Status.IDENTIFIED) {
            String summary = identificationSummary(entry);
            if (!summary.isBlank()) statusText += " : " + summary;
        }
        if (entry.message != null && !entry.message.isBlank()) statusText += " — " + entry.message;
        appendLogLine(entry.filename() + " — " + statusText, entry.status, entry);
    }

    /** Bas niveau, pour les pipelines sans FileEntry.Status naturel (transcodage, récupération
     *  vidéo…) — mêmes garanties que appendLog() (couleur, purge, filtre "Erreurs seulement",
     *  localisation par double-clic si entry non-null) sans imposer le vocabulaire TAGGED/
     *  IDENTIFIED/SKIPPED/ERROR de l'identification musicale. */
    private void appendLogLine(String text, FileEntry.Status status, FileEntry entry) {
        LogEntry e = new LogEntry(nowHms(), text, status, entry);
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
        return I18n.t(" · ~%s restantes", formatDuration(remainingMs / 1000));
    }

    /** Chip "⚡ débit/h · ETA" — débit RÉEL et global (pas scopé à une seule opération, contrairement
     *  à etaText() ci-dessus) : mesuré sur la baisse de `pending` (toutes causes confondues —
     *  taguage, sauvegarde, tout ce qui fait progresser la file) sur une fenêtre glissante de
     *  {@link #THROUGHPUT_WINDOW_MS}. "…" tant que la fenêtre est trop jeune/vide ou que `pending`
     *  ne baisse pas encore (ex. juste après l'ajout d'un gros dossier) — mieux qu'un chiffre
     *  absurde (négatif/infini) qui serait pire que pas de chiffre du tout. */
    private String throughputText(int pending) {
        if (pendingHistory.size() < 2) return "⚡ …";
        long[] oldest = pendingHistory.peekFirst();
        long[] newest = pendingHistory.peekLast();
        long dtMs   = newest[0] - oldest[0];
        long delta  = oldest[1] - newest[1]; // positif = pending a baissé = progrès réel
        if (dtMs < 30_000 || delta <= 0) return "⚡ …";
        double perHour = delta * 3_600_000.0 / dtMs;
        String rateStr = I18n.t("%s/h", Math.round(perHour));
        if (pending <= 0) return "⚡ " + rateStr;
        long etaSec = Math.round(pending * 3600.0 / perHour);
        return "⚡ " + rateStr + I18n.t(" · ETA %s", formatDuration(etaSec));
    }

    /** Durée lisible en s/min/h/j — avant : toujours en minutes, illisible sur un run de
     *  plusieurs heures/jours (ex. "8674 min restantes" au lieu de "6j 0h"). */
    private static String formatDuration(long totalSec) {
        totalSec = Math.max(1, totalSec);
        long days    = totalSec / 86400;
        long hours   = (totalSec % 86400) / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (days > 0)    return I18n.t("%dj %dh",   days, hours);
        if (hours > 0)   return I18n.t("%dh %dmin", hours, minutes);
        if (minutes > 0) return I18n.t("%dmin",     minutes);
        return I18n.t("%ds", seconds);
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
        // Ignorer si déjà dans la table — allEntries() : un fichier déjà chargé mais masqué par un
        // filtre actif se faisait sinon réajouter en double (getRowCount()/get(i) ne portent que
        // sur la vue filtrée).
        for (FileEntry fe : tableModel.allEntries()) {
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
                    // Même repli que loadDirectory() sur le marqueur portable OT_TAGGEDDATE/
                    // recordingMbid quand le cache (chemin → tagué) rate un fichier déplacé.
                    boolean wasTagged = cache.loadTaggedPaths().contains(f.getAbsolutePath())
                            || !ti.taggedDate.isBlank() || !ti.recordingMbid.isBlank();
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

        // Snapshot des chemins déjà dans la table (sur EDT, avant démarrage du worker) — allEntries()
        // : sinon un fichier déjà chargé mais masqué par un filtre actif se faisait réajouter en
        // double lors d'un rescan du même dossier.
        final Set<Path> alreadyInTable = new java.util.HashSet<>();
        for (FileEntry fe : tableModel.allEntries()) {
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
                // ── Parcours (phase 1) ET lecture des tags (phase 2) EN PARALLÈLE ───────────
                // Avant : phase 2 (lecture des tags) n'était soumise à scanTagPool qu'une fois
                // phase 1 (parcours complet de l'arborescence) entièrement terminée — sur un
                // disque lent avec des dizaines de milliers de fichiers, l'utilisateur pouvait
                // attendre de longues minutes (36 min constatées en direct via jstack, 2026-07-28)
                // avec un tableau intégralement vide avant qu'un SEUL fichier n'affiche ses vraies
                // infos, même déjà tagué. Restructuré : chaque fichier découvert par le parcours
                // est désormais immédiatement soumis à scanTagPool, SANS attendre la fin du
                // parcours — un thread séparé (tagConsumer, ci-dessous) consomme les résultats de
                // lecture au fur et à mesure qu'ils arrivent, pendant que le parcours continue de
                // découvrir de nouveaux fichiers. Les deux avancent concurremment au lieu de l'un
                // après l'autre.
                List<FileEntry> batch = new ArrayList<>();
                final long[] lastBatchMs = { System.currentTimeMillis() };

                // Cache chargé en tâche de fond, EN PARALLÈLE du parcours — pas avant. Un premier
                // essai le chargeait avant de démarrer le parcours (pour que taggedPaths/
                // scanCacheMap soient prêts dès la première tâche de lecture) mais loadTaggedPaths()
                // s'est révélé bien plus lent que prévu sur une grosse bibliothèque (plusieurs
                // minutes, constaté en direct 2026-07-28 via jstack : bloqué dans
                // NativeDB.step()) — ça retardait le tout premier affichage du parcours d'autant,
                // exactement le problème qu'on essayait de résoudre. Le chargement tourne
                // maintenant sur son propre thread ; seules les tâches de lecture de tags (plus
                // bas, sur scanTagPool) attendent sa fin via .join(), jamais le parcours lui-même.
                MetadataCache cache = new MetadataCache();
                java.util.concurrent.CompletableFuture<Object[]> cacheDataFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> new Object[]{
                        cache.loadTaggedPaths(), acquireScanCacheMap(cache)
                    });
                try {

                // Publication dans l'ORDRE DE FIN RÉEL (ExecutorCompletionService) plutôt que dans
                // l'ordre de soumission : un seul fichier lent (gros FLAC, latence disque externe)
                // ne bloque plus l'affichage de tous les fichiers soumis après lui.
                java.util.concurrent.CompletionService<Object[]> completion =
                    new java.util.concurrent.ExecutorCompletionService<>(scanTagPool);
                java.util.concurrent.atomic.AtomicInteger submitted = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger completedCount = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicInteger tagged = new java.util.concurrent.atomic.AtomicInteger(0);
                java.util.concurrent.atomic.AtomicBoolean walkDone = new java.util.concurrent.atomic.AtomicBoolean(false);

                // Consommateur : publie chaque résultat de lecture dès qu'il est prêt, PENDANT que
                // le parcours (plus bas) continue de soumettre de nouveaux fichiers — ce
                // découplage sur un thread à part est ce qui permet aux deux phases d'avancer en
                // même temps plutôt que séquentiellement.
                Thread tagConsumer = new Thread(() -> {
                    try {
                        while (!(walkDone.get() && completedCount.get() >= submitted.get())) {
                            if (isCancelled()) return;
                            java.util.concurrent.Future<Object[]> f =
                                completion.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (f == null) continue;
                            try {
                                Object[] result = f.get();
                                if (Boolean.TRUE.equals(result[2])) tagged.incrementAndGet();
                                publish(result);
                            } catch (Exception ignored) {
                            } finally {
                                completedCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }, "ScanTagConsumer");
                tagConsumer.setDaemon(true);
                tagConsumer.start();

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
                    FileEntry entry = new FileEntry(f, new com.opentagger.model.TagInfo());
                    entry.scanRoot = root;
                    batch.add(entry);
                    long now = System.currentTimeMillis();
                    if (batch.size() >= 200 || now - lastBatchMs[0] >= 200) {
                        publish(new Object[]{ new ArrayList<>(batch) });
                        batch.clear();
                        lastBatchMs[0] = now;
                    }
                    submitted.incrementAndGet();
                    completion.submit(() -> {
                        final File ff = entry.file;
                        // .join() ne bloque QUE ce thread de scanTagPool, jamais le parcours —
                        // si le chargement du cache (ci-dessus) n'est pas encore fini, cette tâche
                        // (et elle seule) patiente ici, pendant que le parcours continue ailleurs.
                        Object[] cacheData = cacheDataFuture.join();
                        @SuppressWarnings("unchecked")
                        java.util.Set<String> taggedPaths = (java.util.Set<String>) cacheData[0];
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, MetadataCache.ScanCacheEntry> scanCacheMap =
                            (java.util.Map<String, MetadataCache.ScanCacheEntry>) cacheData[1];
                        com.opentagger.model.TagInfo ti;
                        long mtime = ff.lastModified();
                        long size  = ff.length();
                        MetadataCache.ScanCacheEntry cached = scanCacheMap.get(ff.getAbsolutePath());
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
                                try { ti = readTags(ff); }
                                catch (Exception e) { ti = cached.tagInfo(); }
                                cache.putScanCache(ff.getAbsolutePath(), mtime, size, ti);
                            } else {
                                ti = cached.tagInfo();
                            }
                        } else {
                            try { ti = readTags(ff); }
                            catch (Exception e) { ti = new com.opentagger.model.TagInfo(); }
                            cache.putScanCache(ff.getAbsolutePath(), mtime, size, ti);
                        }
                        // Le cache (chemin → tagué) rate un fichier déplacé/réorganisé hors du
                        // pipeline de renommage interne (mergerfs, réorganisation manuelle, outil
                        // externe...) — voir le commentaire de TagEnrichment.enrichAndWrite sur ce
                        // même piège pour le SEUL cas déjà couvert (renommage auto interne). Repli
                        // sur le marqueur portable écrit DANS le fichier lui-même (OT_TAGGEDDATE,
                        // ou recordingMbid déjà présent) — exactement ce pour quoi ti.taggedDate a
                        // été conçu ("indépendant du cache SQLite", voir readTags()) mais qui
                        // n'était jusqu'ici jamais consulté ici. Trouvé en direct 2026-07-28 : un
                        // fichier réellement taggué (OT_TAGGEDDATE présent) réapparaissait "En
                        // attente" après réorganisation du dossier.
                        boolean wasPreviouslyTagged = taggedPaths.contains(ff.getAbsolutePath())
                                || !ti.taggedDate.isBlank() || !ti.recordingMbid.isBlank();
                        return new Object[]{ entry, ti, wasPreviouslyTagged };
                    });
                }, this::isCancelled);
                } finally {
                    phase1Semaphore.release();
                }
                if (!batch.isEmpty()) publish(new Object[]{ new ArrayList<>(batch) });
                walkDone.set(true);
                if (isCancelled()) {
                    tagConsumer.interrupt();
                    try { tagConsumer.join(2000); } catch (InterruptedException ignored) {}
                    return new int[]{0, 0};
                }
                // Parcours terminé : plus aucune insertion en rafale à venir pour ce scan —
                // signaler à l'EDT de réattacher le RowSorter dès maintenant (voir
                // PHASE1_DONE_MARKER) plutôt que d'attendre la fin de toute la lecture des tags,
                // pour que le filtre redevienne utilisable pendant que tagConsumer continue.
                publish(new Object[]{ PHASE1_DONE_MARKER });

                // Enregistrer le dossier pour l'auto-watch, sur un thread à part, sans attendre —
                // FolderWatcher.watch() fait SON PROPRE parcours récursif complet de l'arborescence
                // (un register() par sous-dossier) — indépendant de tagConsumer, aucune raison de
                // le bloquer ni d'en dépendre.
                if (folderWatcher != null) {
                    Path watchRoot = dir.toPath();
                    Thread watchThread = new Thread(() -> folderWatcher.watch(watchRoot), "FolderWatcher-register");
                    watchThread.setDaemon(true);
                    watchThread.start();
                }

                tagConsumer.join();
                // PAS de shutdown() ici : scanTagPool est partagé entre tous les scans, pas
                // propre à celui-ci.
                boolean fullyDrained = !isCancelled();
                // cache reste ouvert tant que des tâches soumises au pool partagé peuvent encore
                // y écrire (putScanCache) — ne fermer qu'une fois certain que tagConsumer a fini
                // de drainer (boucle complète, jamais annulée). Sur annulation, la connexion est
                // laissée ouverte plutôt que risquer une fermeture concurrente avec une tâche en cours.
                if (fullyDrained) cache.close();
                return new int[]{ submitted.get(), tagged.get() };
                } finally {
                    releaseScanCacheMap();
                }
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
                        // Coquille vide (0 octet) : jusqu'à ce correctif, AudioFileIO.read() échouait
                        // silencieusement (catch muet dans readTags()), le fichier atterrissait comme
                        // un PENDING ordinaire — soumis ensuite à l'identification/taguage comme
                        // n'importe quel autre fichier (recherches MB gaspillées sur un contenu qui
                        // n'existe pas, tentative d'écriture risquée sur un fichier déjà mort). Repéré
                        // en direct 2026-07-28 : plusieurs .m4a à 0 octet (voir le correctif de
                        // sauvegarde anticipée dans TagWriter) jamais signalés nulle part au scan.
                        // Priorité absolue sur wasTagged ci-dessous : un fichier vidé APRÈS avoir été
                        // tagué (incident disque plein...) peut encore matcher le cache chemin→tagué,
                        // mais son contenu réel prime sur ce que dit le cache.
                        if (entry.file.length() == 0) {
                            entry.status  = com.opentagger.model.FileEntry.Status.ERROR;
                            entry.message = I18n.t("Fichier vide (0 octet) — corrompu, non identifiable.");
                        } else if (wasTagged) {
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
                    if (Config.get().autoTagOnScan()) scheduleAutoTaggingFollowUp();
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
        // btnTagAll reste désactivé du tout premier instant de la préparation (ligne ~3590) jusqu'à
        // la toute fin du run (resetBtns()) — un signal "déjà en cours" disponible IMMÉDIATEMENT,
        // contrairement à WorkerHub.current(TAGGING) qui ne se remplit qu'après coup (voir
        // continueStartTagging(), le submit() n'arrive qu'une fois le filtrage "Tout tagger" en
        // arrière-plan terminé). Sans ce garde-fou précoce : deux dossiers de startup.folders qui
        // terminent leur scan à quelques centaines de ms d'écart déclenchent chacun
        // scheduleAutoTaggingFollowUp() (voir chkAutoTagOnScan) → startTagging(false), et les DEUX
        // passent le contrôle WorkerHub ci-dessous (encore vide, aucun des deux n'a fini sa
        // préparation) avant qu'aucun n'ait pu s'enregistrer — repéré en direct 2026-08-12, deux
        // lots "Tout tagger" démarrés à 1s d'écart (62633 puis 65629 fichiers) juste après avoir
        // activé l'auto-taguage sur une bibliothèque à 4 dossiers de démarrage.
        if (!btnTagAll.isEnabled()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
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
        if (selOnly) {
            List<FileEntry> toTag = new ArrayList<>();
            for (int r : table.getSelectedRows())
                toTag.add(tableModel.get(table.convertRowIndexToModel(r)));
            continueStartTagging(toTag, 0, true);
            return;
        }

        // "Tout tagger" : le filtrage ci-dessous appelle file.length() par fichier (une E/S disque
        // bloquante) sur potentiellement TOUTE la bibliothèque chargée (100k+ fichiers) — fait ici
        // en arrière-plan pour ne jamais geler l'interface, même si un disque est lent/très
        // sollicité au moment du clic (mergerfs/USB/réseau...). Gel total de l'UI reproduit et
        // confirmé en direct (2026-08-09) via jstack : la pile de l'EDT bloquée montrait exactement
        // File.length() appelé depuis cette boucle. bouton désactivé immédiatement pour empêcher un
        // second clic pendant la préparation (qui lancerait deux lots en parallèle avant que
        // WorkerHub n'ait pu enregistrer le premier).
        btnTagAll.setEnabled(false);
        setStatus(I18n.t("Préparation du lot à tagger…"));
        List<FileEntry> snapshot = new ArrayList<>(tableModel.allEntries());
        new SwingWorker<List<FileEntry>, Void>() {
            int skipped = 0;
            @Override protected List<FileEntry> doInBackground() {
                List<FileEntry> result = new ArrayList<>();
                // allEntries() (snapshot ci-dessus) : "Tout tagger" doit couvrir toute la
                // bibliothèque chargée, pas seulement la vue déjà filtrée (recherche, chip de
                // statut cliqué) — un filtre resté actif sans rapport avec le taguage masquait
                // sinon silencieusement une partie des fichiers cochés, sans le moindre
                // avertissement.
                for (FileEntry e : snapshot) {
                    // IDENTIFIED exclu comme TAGGED : déjà identifié avec succès, juste pas
                    // encore enregistré — le re-identifier ici referait le même travail réseau
                    // pour rien (voir FileEntry.Status.IDENTIFIED). Utilisez "Enregistrer tout"
                    // pour ces fichiers. Coquille vide (0 octet, voir le marquage ERROR fait au
                    // scan ci-dessus) exclue elle aussi : contrairement aux autres ERROR (network,
                    // jaudiotagger...) potentiellement transitoires et donc légitimement retentés
                    // par "Tout tagger", un fichier à 0 octet reste à 0 octet tant que personne ne
                    // remplace son contenu — le retenter en boucle ne ferait que regaspiller des
                    // requêtes MB pour rien à chaque campagne de taguage.
                    if (!e.selected) continue;
                    if (e.status == FileEntry.Status.TAGGED) { skipped++; continue; }
                    if (e.status != FileEntry.Status.IDENTIFIED && e.file.length() > 0) result.add(e);
                }
                return result;
            }
            @Override protected void done() {
                List<FileEntry> toTag;
                try {
                    toTag = get();
                } catch (Exception ex) {
                    btnTagAll.setEnabled(true);
                    setStatus(I18n.t("Erreur pendant la préparation du taguage : %s", ex.getMessage()));
                    return;
                }
                continueStartTagging(toTag, skipped, false);
            }
        }.execute();
    }

    /** Suite de startTagging() une fois le lot filtré (toujours sur l'EDT) — voir son commentaire
     *  pour pourquoi ce filtrage est désormais fait en arrière-plan avant d'arriver ici. */
    private void continueStartTagging(List<FileEntry> toTag, int alreadyTaggedSkipped, boolean selOnly) {
        if (toTag.isEmpty()) {
            btnTagAll.setEnabled(true);
            if (Config.get().postTagCompletion() != Config.PostTagCompletion.NONE) {
                setStatus(I18n.t("Aucun nouveau fichier à taguer — recherche des fichiers incomplets…"));
                autoCompleteIncomplete(() -> setStatus(I18n.t("Complétion terminée.")));
            } else {
                setStatus(I18n.t("Aucun fichier à taguer (tous déjà tagués — utilisez « Forcer le re-taguage » pour les re-traiter)."));
            }
            return;
        }
        // Lot mixte : contrairement au cas ci-dessus (100 % déjà tagués), l'exclusion silencieuse
        // des fichiers déjà TAGGED passait inaperçue ici — journalisée pour rester visible même
        // quand le run démarre normalement sur le reste du lot (ex. pour ré-appliquer un script
        // tagger sur une bibliothèque déjà taguée, voir TaggerScript).
        if (alreadyTaggedSkipped > 0) {
            appendLogLine(I18n.t(
                "%d fichier(s) déjà tagué(s) ignoré(s) dans ce lot — utilisez « Forcer le re-taguage » pour les re-traiter.",
                alreadyTaggedSkipped), FileEntry.Status.SKIPPED, null);
        }

        btnTagAll.setEnabled(false); // no-op si déjà désactivé (lot "Tout tagger" ci-dessus)
        btnCancel.setEnabled(true);
        beginProgress(ProgressSlot.TAGGING);

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
            },
            // Bug trouvé en direct (2026-08-15) : la barre TAGGING ne se mettait à jour que via le
            // "progress" 0-100 de SwingWorker (voir bloc ci-dessous, gardé pour compat mais plus
            // utilisé pour la barre) — sur 144k fichiers, un point de pourcentage = ~1445 fichiers,
            // la barre restait vide des HEURES. Ce callback se déclenche à CHAQUE fichier.
            (doneCount, total) -> SwingUtilities.invokeLater(() -> {
                JProgressBar bar = progressBars.get(ProgressSlot.TAGGING);
                bar.setValue((int) ((doneCount * 100L) / total));
                bar.setString(doneCount + " / " + total + etaText(doneCount, total));
            })
        );
        w.addPropertyChangeListener(evt -> {
            if (SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                onTaggingDone(toTag);
                // "Tout tagger" ne prend qu'un instantané de allEntries() au moment du clic — les
                // fichiers ajoutés ensuite par un scan encore actif (bibliothèque multi-racines,
                // support lent type exFAT/fuseblk) restaient PENDING indéfiniment tant qu'on ne
                // recliquait pas manuellement une fois le scan terminé. Pas pour selOnly (sélection
                // explicite et figée) ni si l'utilisateur a annulé volontairement (bouton Arrêter).
                if (!selOnly && !w.isCancelled()) scheduleAutoTaggingFollowUp();
            }
        });
        WorkerHub.get().submit(WorkerHub.TaskKind.TAGGING, I18n.t("Taguage"), w, w::stopNow);
        // BUG CRITIQUE trouvé en direct (2026-08-15) : scheduleAutoSaveFollowUp() n'était armé que
        // depuis onTaggingDone(), c'est-à-dire seulement quand CE lot complet de "Tout tagger" a
        // fini — or ce lot est un instantané de TOUT allEntries() au moment du clic (voir plus haut),
        // potentiellement des dizaines de milliers de fichiers sur une grosse bibliothèque. Sur un
        // run réel, ce lot a mis plusieurs JOURS à se vider (débit mesuré ~483 fichiers/h pour ~91k
        // restants) : pendant toute cette fenêtre, aucun Enregistrer ne s'est JAMAIS déclenché — des
        // milliers de fichiers IDENTIFIED se sont accumulés en mémoire sans jamais être écrits sur
        // le disque (donc aucune pochette téléchargée non plus, résolue seulement à l'Enregistrement,
        // voir TagEnrichment.resolveCover()) ; tout ce travail aurait été perdu au moindre
        // redémarrage. Armer la chaîne ICI, dès le lancement, pas seulement à la toute fin : SAVE et
        // TAGGING ne se bloquent jamais mutuellement (voir WorkerHub.conflictsWith()), donc rien
        // n'empêchait déjà techniquement de sauvegarder pendant qu'un taguage tourne encore — seul le
        // point de déclenchement automatique manquait.
        if (!selOnly && !autoSaveWatchPending) scheduleAutoSaveFollowUp();
    }

    /**
     * Revérifie périodiquement s'il y a de nouveaux fichiers PENDING à taguer, et relance
     * automatiquement "Tout tagger" dessus — voir le commentaire d'appel dans startTagging().
     *
     * AVANT ce correctif : la boucle s'arrêtait dès que plus aucun scan n'était actif
     * (activeScanWorkers vide), MÊME s'il restait des fichiers PENDING jamais tentés à cet instant
     * précis — trouvé en direct (2026-08-01) : ~4600 fichiers restaient indéfiniment "En attente"
     * après la fin d'un scan, jamais repris automatiquement, tant que l'utilisateur ne recliquait
     * pas lui-même sur "Tout tagger". Le scan et le taguage ne se terminent pas forcément au même
     * instant — un scan fini ne veut pas dire "plus rien à taguer".
     *
     * Ne considère QUE le statut PENDING (pas SKIPPED/ERROR) pour décider de relancer
     * automatiquement — contrairement au filtre de startTagging() (qui, lui, retente aussi
     * SKIPPED/ERROR une fois qu'on tague réellement) : reproposer indéfiniment en boucle des
     * fichiers déjà SKIPPED/ERROR tant qu'un scan tourne encore gaspillerait des requêtes MB/
     * AcoustID sans fin pour des fichiers qui ne passeront pas plus la deuxième fois. Seuls un
     * nouveau scan (qui ramène du PENDING) ou un reclic manuel ("Forcer le re-taguage") les
     * représentent.
     *
     * BUG CORRIGÉ le 2026-08-12 : malgré le paragraphe ci-dessus (déjà présent depuis 2026-08-01),
     * cette méthode appelait quand même startTagging(false) une fois hasPending détecté —
     * startTagging() applique SON PROPRE filtre bien plus large ("tout sauf TAGGED/IDENTIFIED",
     * donc SKIPPED/ERROR compris), recréant exactement le problème que ce paragraphe dit vouloir
     * éviter. Resté sans conséquence visible tant que ce follow-up ne se déclenchait que rarement
     * (uniquement pendant un taguage déjà en cours) — mais chkAutoTagOnScan (2026-08-12) l'appelle
     * désormais après CHAQUE scan de dossier, donc à chaque redémarrage sur une bibliothèque à
     * plusieurs dossiers de démarrage : repéré en direct quand un seul fichier PENDING a suffi à
     * redéclencher un lot de 62029 fichiers englobant tout l'historique SKIPPED/ERROR jamais
     * résolu. Construit maintenant sa propre liste strictement PENDING et appelle
     * continueStartTagging() directement, sans repasser par le filtre large de startTagging().
     */
    private void scheduleAutoTaggingFollowUp() {
        javax.swing.Timer t = new javax.swing.Timer(5000, null);
        t.addActionListener(e -> {
            t.stop();
            if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) return;
            // WorkerHub.submit(TAGGING) lève IllegalStateException si une tâche EN CONFLIT (ex.
            // Transcodage) tourne déjà — startTagging() se protégeait déjà via ce même appel avant
            // de continuer, mais le correctif du 2026-08-12 (appeler continueStartTagging()
            // directement pour éviter le double-lancement, voir plus haut) a supprimé ce garde-fou
            // au passage. Exception NON rattrapée sur l'EDT trouvée en direct (2026-08-13) :
            // bloquait tout taguage automatique sans qu'aucun fichier ne soit jamais traité après un
            // redémarrage, tant qu'une tâche en conflit restait enregistrée — on réessaie plus tard
            // au lieu de planter.
            if (!WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING).isEmpty()) {
                scheduleAutoTaggingFollowUp();
                return;
            }
            if (!btnTagAll.isEnabled()) return; // même garde que startTagging() — évite un lancement concurrent
            List<FileEntry> pending = new ArrayList<>();
            for (FileEntry fe : tableModel.allEntries())
                if (fe.selected && fe.status == FileEntry.Status.PENDING) pending.add(fe);
            if (!pending.isEmpty()) { continueStartTagging(pending, 0, false); return; }
            if (!activeScanWorkers.isEmpty()) scheduleAutoTaggingFollowUp(); // rien de neuf pour l'instant — on réessaiera
        });
        t.setRepeats(false);
        t.start();
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

        // Reset immédiat sur l'EDT — même si le thread tourne encore en arrière-plan.
        // allEntries() : un fichier PROCESSING masqué par un filtre actif restait sinon bloqué
        // dans cet état indéfiniment après "Arrêter", même si le worker sous-jacent est bien annulé.
        for (FileEntry e : tableModel.allEntries()) {
            if (e.status == FileEntry.Status.PROCESSING) {
                e.status  = FileEntry.Status.PENDING;
                e.message = "";
                tableModel.update(e);
            }
        }
        refreshStats();
        setStatus(I18n.t("Arrêté."));
        btnTagAll.setEnabled(true);
        btnCancel.setEnabled(false);
        endAllProgress();
    }

    /** Annule spécifiquement les tâches qui bloquent {@code kind} — PAS cancelAll() (stopAll()
     *  ci-dessus), qui couperait aussi des tâches sans rapport. Utilisé par PodcastDialog.onTag()
     *  (2026-08-15) : "Tagger comme podcast" est bloqué par le taguage principal quasi en
     *  permanence sur une grosse bibliothèque (même fichiers PENDING/SKIPPED des deux côtés), et
     *  cancelAll() aurait aussi arrêté un Enregistrement en cours sans rapport avec ce blocage.
     *  Même nettoyage d'UI que stopAll() (PROCESSING→PENDING, chips, barres, boutons), juste ciblé
     *  sur les tâches réellement en cause plutôt que tout arrêter. */
    public void cancelBlockersFor(WorkerHub.TaskKind kind) {
        for (WorkerHub.TaskHandle h : WorkerHub.get().blockers(kind)) h.cancel();
        for (FileEntry e : tableModel.allEntries()) {
            if (e.status == FileEntry.Status.PROCESSING) {
                e.status  = FileEntry.Status.PENDING;
                e.message = "";
                tableModel.update(e);
            }
        }
        refreshStats();
        resetBtns();
        endAllProgress();
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
        // Un second clic pendant un enregistrement en cours ne l'annule plus (retiré à la demande
        // de l'utilisateur, 2026-07-29 : fonctionnalité jamais demandée, et le bouton "Annuler
        // l'enregistrement" restait parfois affiché même une fois l'enregistrement réellement
        // terminé). Simple garde contre un double lancement concurrent — pas de ré-étiquetage du
        // bouton, pas d'annulation possible depuis ce bouton.
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.SAVE);
        if (running.isPresent()) {
            setStatus(I18n.t("Enregistrement déjà en cours — attendez la fin."));
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

        // allEntries() : les fichiers identifiés mais masqués par un filtre actif n'étaient sinon
        // jamais écrits sur le disque, sans le moindre avertissement.
        List<FileEntry> toSave = new ArrayList<>();
        for (FileEntry e : tableModel.allEntries()) {
            if (e.selected && e.status == FileEntry.Status.IDENTIFIED) toSave.add(e);
        }
        if (toSave.isEmpty()) {
            setStatus(I18n.t("Aucun fichier identifié à enregistrer (utilisez « Tout tagger » d'abord)."));
            return;
        }

        int maskIndex = Config.get().autoRenameEnabled() ? Config.get().defaultRenameMask() : -1;
        beginProgress(ProgressSlot.SAVE);
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
                JProgressBar bar = progressBars.get(ProgressSlot.SAVE);
                bar.setValue(doneCount);
                bar.setString(doneCount + "/" + total + etaText(doneCount, total));
            })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    endProgress(ProgressSlot.SAVE);
                    refreshStats();
                    // Ici, pas après "Tout tagger" : voir le commentaire sur chkAutoGroupCompilations
                    // (buildMenuTagger()) — les fichiers ne deviennent TAGGED (recordingMbid fiable)
                    // qu'à l'Enregistrement. groupByCompilations() garde ses propres gardes
                    // (activeOperations, etc.) ; si quelque chose bloque, la recherche est juste
                    // sautée cette fois-ci, sans forcer.
                    //
                    // Même suivi automatique que "Tout tagger" (scheduleAutoTaggingFollowUp,
                    // même raison) : tant qu'un scan/taguage encore actif continue à produire des
                    // IDENTIFIED, Enregistrer se relance tout seul dessus plutôt que de les laisser
                    // en attente indéfiniment d'un reclic manuel — sinon exactement le piège
                    // rencontré en pratique : un fichier fraîchement identifié par le suivi
                    // automatique du taguage, mais jamais écrit sur le disque faute de reclic sur
                    // Enregistrer. runPostTagCommand() différé jusqu'à la vraie fin de la chaîne
                    // (voir scheduleAutoSaveFollowUp) : sinon un hook pensé pour "une fois à la fin
                    // d'un run complet" (scan Plex...) se déclencherait à chaque relance
                    // intermédiaire sur une grosse bibliothèque.
                    //
                    // Cette suite (stillFeeding + relance/runPostTagCommand) est repoussée APRÈS que
                    // groupByCompilations(true, ...) ait fini d'accumuler ses correspondances (voir
                    // son onDone) — pas exécutée en parallèle du worker encore en cours, sinon la
                    // toute DERNIÈRE vague de la chaîne pouvait déclencher le flush AVANT d'avoir
                    // fini d'y ajouter ses propres résultats. stillFeeding est réévalué à l'intérieur
                    // (pas capturé avant), le groupement pouvant prendre un temps non négligeable
                    // pendant lequel un nouveau scan/taguage a pu démarrer entre-temps.
                    Runnable afterCompilationGrouping = () -> {
                        boolean stillFeeding = !activeScanWorkers.isEmpty()
                                || WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent();
                        if (!w.isCancelled() && stillFeeding) scheduleAutoSaveFollowUp();
                        else runPostTagCommand();
                    };
                    if (chkAutoGroupCompilations.isSelected()) {
                        groupByCompilations(true, afterCompilationGrouping);
                    } else {
                        afterCompilationGrouping.run();
                    }
                });
            }
        });
        // SAVE est le seul TaskKind volontairement compatible avec TAGGING en parallèle (voir
        // WorkerHub.conflictsWith).
        WorkerHub.get().submit(WorkerHub.TaskKind.SAVE, I18n.t("Enregistrement"), w, w::stopNow);
    }

    /**
     * Revérifie périodiquement (tant qu'un scan ou un taguage tourne encore) s'il y a de nouveaux
     * fichiers IDENTIFIED à enregistrer, et relance automatiquement "Enregistrer tout" dessus —
     * même mécanisme que scheduleAutoTaggingFollowUp(), voir son commentaire. Appelle
     * runPostTagCommand() une seule fois, à la toute fin réelle de la chaîne (plus ni scan ni
     * taguage actif), pas à chaque relance intermédiaire.
     */
    private void scheduleAutoSaveFollowUp() {
        // Interrupteur utilisateur (chkAutoSaveEnabled, Config.autoSaveEnabled()) : un seul point de
        // garde ici plutôt que devant chacun des appelants (onTaggingDone, SaveWorker.done,
        // continueStartTagging) — couvre tout point d'entrée présent ET futur. Le bouton "Enregistrer
        // tout" manuel (saveAll()) n'est PAS concerné : il reste toujours utilisable, seule cette
        // chaîne d'auto-relance est coupée.
        if (!Config.get().autoSaveEnabled()) return;
        // Garde anti-doublon : onTaggingDone() ET ce timer lui-même peuvent chacun vouloir
        // (re)lancer cette chaîne — vrai seulement pendant la fenêtre d'attente de 5s, effacé dès
        // que le timer se déclenche (voir plus bas), pas pendant tout le cycle de vie de la chaîne.
        if (autoSaveWatchPending) return;
        autoSaveWatchPending = true;
        javax.swing.Timer t = new javax.swing.Timer(5000, null);
        t.addActionListener(e -> {
            t.stop();
            autoSaveWatchPending = false;
            boolean stillFeeding = !activeScanWorkers.isEmpty()
                    || WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent();
            boolean hasWork = tableModel.allEntries().stream()
                    .anyMatch(fe -> fe.selected && fe.status == FileEntry.Status.IDENTIFIED);
            if (!stillFeeding && !hasWork) { runPostTagCommand(); return; } // vraiment terminé

            if (WorkerHub.get().current(WorkerHub.TaskKind.SAVE).isPresent()) {
                scheduleAutoSaveFollowUp(); // déjà en cours (ailleurs) — revérifier plus tard
                return;
            }
            if (hasWork) {
                saveAll();
                // saveAll() peut refuser en silence (juste un setStatus("Encore en cours…")) si
                // INFO_COMPLETER/ALBUM_COMPLETION tourne — voir WorkerHub.conflictsWith() : ils
                // touchent les mêmes FileEntry qu'Enregistrer, blocage volontaire pour éviter une
                // course, pas un bug. Avec postTagCompletion=FIELDS_AND_ALBUMS (déclenché après
                // CHAQUE fin de "Tout tagger", donc très fréquent avec le suivi automatique), il y a
                // presque toujours l'un des deux actif à un instant donné — sans cette revérification,
                // la chaîne mourait net au premier refus, silencieusement, y compris pour un clic
                // manuel sur "Enregistrer tout" qui semblait alors "ne rien faire" (observé en
                // direct). saveAll() ne pose son propre relais (scheduleAutoSaveFollowUp() sur DONE)
                // que s'il démarre vraiment — SAVE absent de WorkerHub juste après l'appel = refusé,
                // on reprend la main ici.
                if (WorkerHub.get().current(WorkerHub.TaskKind.SAVE).isEmpty()) scheduleAutoSaveFollowUp();
            } else if (stillFeeding) {
                scheduleAutoSaveFollowUp(); // rien à enregistrer pour l'instant, mais ça continue d'arriver
            }
        });
        t.setRepeats(false);
        t.start();
    }

    /**
     * Hook(s) optionnel(s) (PostTagCommands, réglages → Script) exécutés une fois, dans l'ordre,
     * à la fin d'un "Enregistrer tout" — pas par fichier, une bibliothèque de 100k+ fichiers
     * rendrait un hook par fichier ingérable. Comparaison avec OneTagger (docs/files/) :
     * équivalent de son "postCommand", en version fin-de-run plutôt que par-piste pour cette
     * raison de volumétrie, étendu en liste pour en enchaîner plusieurs (ex. scan Plex puis
     * rebalance mergerfs). Fire-and-forget par commande (comme le "Ouvrir le dossier" du menu
     * contextuel juste au-dessus) : on ne bloque pas l'EDT à attendre une commande arbitraire qui
     * peut très bien ne jamais terminer.
     */
    private void runPostTagCommand() {
        // Toujours appelé exactement à la toute fin réelle de la chaîne Enregistrer (voir les deux
        // points d'appel), jamais entre deux vagues intermédiaires — l'endroit naturel pour afficher
        // la revue de compilations accumulée par groupByCompilations(true), voir son commentaire.
        flushPendingCompilationMatches();
        java.util.List<String> cmds = com.opentagger.PostTagCommands.load();
        int launched = 0;
        for (String cmd : cmds) {
            if (cmd == null || cmd.isBlank()) continue;
            try {
                new ProcessBuilder("sh", "-c", cmd)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                launched++;
            } catch (Exception ex) {
                setStatus(I18n.t("Commande post-taguage : échec du lancement — %s", ex.getMessage()));
            }
        }
        if (launched > 0) setStatus(I18n.t("%d commande(s) post-taguage lancée(s).", launched));
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

        // allEntries() : sinon synchronisation incomplète si un filtre est actif.
        List<FileEntry> targets = new ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();
        for (FileEntry e : tableModel.allEntries()) {
            if (e.status != FileEntry.Status.TAGGED) continue;
            String p = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath().toString();
            if (seenPaths.add(p)) targets.add(e);
        }
        if (targets.isEmpty()) {
            setStatus(I18n.t("Aucun fichier tagué à synchroniser."));
            return;
        }

        beginProgress(ProgressSlot.LISTENBRAINZ_SYNC);
        progressBars.get(ProgressSlot.LISTENBRAINZ_SYNC).setIndeterminate(true);
        setStatus(I18n.t("Synchronisation ListenBrainz…"));

        ListenBrainzSyncWorker w = new ListenBrainzSyncWorker(
            targets,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> SwingUtilities.invokeLater(() -> { tableModel.update(entry); appendLog(entry); refreshStats(); })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    progressBars.get(ProgressSlot.LISTENBRAINZ_SYNC).setIndeterminate(false);
                    endProgress(ProgressSlot.LISTENBRAINZ_SYNC);
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
            // allEntries() : "de tout" doit couvrir toute la bibliothèque, pas juste la vue
            // filtrée du moment — sinon un re-taguage "de tout" limité aux fichiers visibles.
            for (FileEntry e : tableModel.allEntries()) {
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
     * "Identify and Fix Any Tags" façon SongKong (retour utilisateur, 2026-08-10, après un tour
     * d'horizon Jaikoz/Picard/SongKong) : reprend tous les fichiers "Non identifié" et tente de les
     * retrouver PAR EMPREINTE AUDIO SEULE (SongRec puis AcoustID, voir FileEntry.forceReidentify),
     * sans tenir compte des tags ou du nom de fichier existants — contrairement à "Forcer le
     * re-taguage" ci-dessus, qui cible les fichiers déjà TAGGED (pour corriger un mauvais tag),
     * cette action cible le cas inverse : les fichiers jamais identifiés du tout. Chaque résultat
     * trouvé passe par ReidentifyReviewDialog avant de rejoindre le flux normal — jamais silencieux.
     */
    private void reidentifyUnmatched() {
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de ré-identifier.",
                    String.join(", ", blockers)));
            return;
        }
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        if (sel.length > 0) {
            for (int r : sel) {
                FileEntry e = tableModel.get(table.convertRowIndexToModel(r));
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        } else {
            // allEntries() : couvre toute la bibliothèque chargée, pas juste la vue filtrée —
            // même raison que partout ailleurs dans ce fichier (forceRetag(), startTagging()...).
            for (FileEntry e : tableModel.allEntries()) {
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        }
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier \"Non identifié\" à ré-identifier.")); return; }

        int confirm = JOptionPane.showConfirmDialog(this,
            I18n.t("<html>Retenter %d fichier(s) \"Non identifié\" par empreinte audio seule<br>"
                 + "(SongRec/AcoustID) — tags et nom de fichier existants ignorés.<br><br>"
                 + "Une fenêtre de revue s'ouvrira ensuite pour confirmer les résultats trouvés,<br>"
                 + "rien n'est appliqué automatiquement.</html>", targets.size()),
            I18n.t("Ré-identifier par empreinte audio"), JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        reidentifyUnmatched(targets);
    }

    /** Cœur partagé entre le déclenchement manuel (menu, avec confirmation) et le déclenchement
     *  automatique après taguage (chkAutoReidentifyUnmatched, sans confirmation — l'utilisateur a
     *  déjà donné son accord une fois pour toutes en cochant la case). La revue groupée
     *  (ReidentifyReviewDialog) reste systématique dans les deux cas — seul le lancement change. */
    private void reidentifyUnmatched(List<FileEntry> targets) {
        for (FileEntry e : targets) e.forceReidentify = true;
        resetForReidentification(targets, () -> launchForcedTagging(targets, true, true));
    }

    /**
     * Détecte et propose de corriger un encodage cassé (mojibake — voir {@link
     * com.opentagger.EncodingFixer}) sur titre/artiste/artiste album/album/commentaire — un texte
     * UTF-8 écrit par un autre outil puis relu en Latin-1 par celui-ci ou un précédent, typique sur
     * une bibliothèque agrégée de sources hétérogènes. Lit le tag RÉEL sur disque (comme
     * {@link #forceRetag}), indépendamment du statut/résultat déjà en mémoire — s'applique aussi
     * bien à des fichiers déjà TAGGED qu'à d'autres. Aucune écriture avant confirmation explicite
     * dans {@link EncodingFixReviewDialog}.
     */
    private void fixEncoding() {
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de corriger l'encodage.",
                    String.join(", ", blockers)));
            return;
        }
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        if (sel.length > 0) {
            for (int r : sel) targets.add(tableModel.get(table.convertRowIndexToModel(r)));
        } else {
            targets.addAll(tableModel.allEntries());
        }
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier à analyser.")); return; }

        setStatus(I18n.t("Recherche d'encodage cassé sur %d fichier(s)…", targets.size()));
        btnTagAll.setEnabled(false);
        new SwingWorker<List<EncodingFixReviewDialog.Candidate>, Void>() {
            @Override protected List<EncodingFixReviewDialog.Candidate> doInBackground() {
                List<EncodingFixReviewDialog.Candidate> found = new ArrayList<>();
                for (FileEntry e : targets) {
                    if (isCancelled()) break;
                    File fichier = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    if (!fichier.exists()) continue;
                    TagInfo before;
                    try { before = readTags(fichier); } catch (Exception ex) { continue; }
                    TagInfo after = before.copy();
                    boolean changed = fixTextField(() -> after.title,       v -> after.title = v);
                    changed = fixTextField(() -> after.artist,      v -> after.artist = v)      || changed;
                    changed = fixTextField(() -> after.albumArtist, v -> after.albumArtist = v) || changed;
                    changed = fixTextField(() -> after.album,       v -> after.album = v)       || changed;
                    changed = fixTextField(() -> after.comment,     v -> after.comment = v)     || changed;
                    if (changed) found.add(new EncodingFixReviewDialog.Candidate(fichier, e, before, after));
                }
                return found;
            }
            @Override protected void done() {
                btnTagAll.setEnabled(true);
                List<EncodingFixReviewDialog.Candidate> found;
                try { found = get(); } catch (Exception ex) { found = List.of(); }
                if (found.isEmpty()) {
                    setStatus(I18n.t("Aucun encodage cassé détecté."));
                    return;
                }
                setStatus(I18n.t("%d fichier(s) avec encodage cassé détecté(s).", found.size()));
                new EncodingFixReviewDialog(MainFrame.this, found, tableModel).setVisible(true);
            }
        }.execute();
    }

    /** Corrige `getter` en place via `setter` si {@link com.opentagger.EncodingFixer#isSuspect}
     *  détecte un encodage cassé — utilisé par {@link #fixEncoding()} pour chaque champ texte
     *  candidat, un à la fois (pattern getter/setter plutôt que réflexion : ces champs sont publics
     *  et peu nombreux, pas besoin de la machinerie de TagWriter.fieldFor()). */
    private static boolean fixTextField(java.util.function.Supplier<String> getter,
                                         java.util.function.Consumer<String> setter) {
        String val = getter.get();
        if (val != null && com.opentagger.EncodingFixer.isSuspect(val)) {
            setter.accept(com.opentagger.EncodingFixer.fix(val));
            return true;
        }
        return false;
    }

    /** Nettoie le nom RÉEL du fichier sur disque des pistes "Non identifié" (retire un préfixe
     *  d'identifiant de catalogue/téléchargement — voir {@link TaggingWorker#stripLeadingNumericPrefix},
     *  la même règle qui corrige déjà l'analyse interne du nom de fichier dans
     *  TaggingWorker.parseFilename() — et un suffixe "-temp-NNNNN" résiduel d'un outil externe,
     *  voir {@link TaggingWorker#stripTrailingTempSuffix}), SANS retenter l'identification.
     *  Séparée de {@link #cleanNamesAndReidentifyUnmatched()} car demandé explicitement le
     *  2026-08-11 : nettoyer le nom seul reste utile même quand on ne veut pas relancer tout de
     *  suite une identification (comparer d'abord le résultat, ou laisser la ré-identification
     *  automatique s'en charger plus tard via chkAutoReidentifyUnmatched). */
    private void cleanNamesOnly() { cleanNames(false); }

    /** Variante qui, une fois le nettoyage terminé, retente aussi l'identification par empreinte
     *  audio (même flux que {@link #reidentifyUnmatched()}) sur les fichiers renommés. Demandé le
     *  2026-08-11 après avoir trouvé qu'un grand nombre de pistes "Non identifié" portent un
     *  identifiant à 4-5 chiffres en tête ("16741 - Dr. Dre - What's The Difference.mp3") qui
     *  pollue déjà l'analyse interne. */
    private void cleanNamesAndReidentifyUnmatched() { cleanNames(true); }

    private void cleanNames(boolean reidentifyAfter) {
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de nettoyer les noms.",
                    String.join(", ", blockers)));
            return;
        }
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        if (sel.length > 0) {
            for (int r : sel) {
                FileEntry e = tableModel.get(table.convertRowIndexToModel(r));
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        } else {
            for (FileEntry e : tableModel.allEntries()) {
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        }
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier \"Non identifié\" à nettoyer.")); return; }

        String msg = reidentifyAfter
            ? I18n.t("<html>Nettoyer le nom de fichier de %d piste(s) \"Non identifié\"<br>"
                 + "(retire un préfixe d'identifiant de catalogue/téléchargement, ex. \"16741 - \")<br>"
                 + "puis retenter l'identification par empreinte audio.<br><br>"
                 + "Renommage sur disque (jamais d'écrasement d'un fichier existant).<br>"
                 + "Une fenêtre de revue s'ouvrira ensuite pour confirmer les résultats trouvés,<br>"
                 + "rien n'est appliqué automatiquement.</html>", targets.size())
            : I18n.t("<html>Nettoyer le nom de fichier de %d piste(s) \"Non identifié\"<br>"
                 + "(retire un préfixe d'identifiant de catalogue/téléchargement, ex. \"16741 - \")<br>"
                 + "sans retenter l'identification.<br><br>"
                 + "Renommage sur disque uniquement (jamais d'écrasement d'un fichier existant).</html>", targets.size());
        int confirm = JOptionPane.showConfirmDialog(this, msg,
            I18n.t(reidentifyAfter ? "Nettoyer les noms + Ré-identifier" : "Nettoyer les noms"),
            JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        if (reidentifyAfter) {
            cleanFilenames(targets, () -> reidentifyUnmatched(targets));
        } else {
            cleanFilenames(targets, () -> {}); // cleanFilenames.done() affiche déjà le compte renommé
        }
    }

    /** Renomme sur disque (sans déplacer) chaque fichier de {@code targets} dont le nom nettoyé
     *  diffère du nom actuel — voir {@link #cleanNamesAndReidentifyUnmatched()}. En arrière-plan
     *  (SwingWorker) : renommage = I/O disque par fichier, même raison que
     *  {@link #resetForReidentification} juste en dessous. */
    private void cleanFilenames(List<FileEntry> targets, Runnable onDone) {
        setStatus(I18n.t("Nettoyage des noms de %d fichier(s)…", targets.size()));
        btnTagAll.setEnabled(false);
        new SwingWorker<Void, Object[]>() {
            int renamed = 0;
            @Override protected Void doInBackground() {
                for (FileEntry e : targets) {
                    java.nio.file.Path cur = e.currentPath != null ? e.currentPath : e.file.toPath();
                    String nom = cur.getFileName().toString();
                    int dot = nom.lastIndexOf('.');
                    String stem = dot > 0 ? nom.substring(0, dot) : nom;
                    String cleaned = TaggingWorker.stripTrailingTempSuffix(stem);
                    cleaned = TaggingWorker.stripTrailingCopyMarker(cleaned);
                    cleaned = TaggingWorker.stripTrailingLongId(cleaned);
                    cleaned = TaggingWorker.stripLeadingNumericPrefix(cleaned);
                    if (cleaned.equals(stem) || cleaned.isBlank()) continue;
                    try {
                        java.nio.file.Path np = FileRenamer.renameInPlace(cur, cleaned);
                        if (np != null) publish(new Object[]{ e, np, nom });
                    } catch (Exception ex) {
                        publish(new Object[]{ e, null, nom + " : " + ex.getMessage() });
                    }
                }
                return null;
            }
            @Override protected void process(List<Object[]> chunk) {
                for (Object[] item : chunk) {
                    FileEntry e = (FileEntry) item[0];
                    java.nio.file.Path np = (java.nio.file.Path) item[1];
                    String oldName = (String) item[2];
                    if (np != null) {
                        e.currentPath = np;
                        tableModel.update(e);
                        renamed++;
                        appendLogLine("  ✎ " + oldName + " → " + np.getFileName(), FileEntry.Status.TAGGED, e);
                    } else {
                        appendLogLine("  ✗ renommage échoué : " + oldName, FileEntry.Status.ERROR, e);
                    }
                }
            }
            @Override protected void done() {
                setStatus(I18n.t("%d fichier(s) renommé(s).", renamed));
                onDone.run();
            }
        }.execute();
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
        btnTagAll.setEnabled(false);
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

    /** Tamponne les fichiers sélectionnés comme "déjà taggués" (marqueur OT_TAGGEDDATE + historique
     *  du cache), SANS relancer aucune identification — pour les audios que l'utilisateur sait déjà
     *  correctement taggués (session OpenTagger antérieure au marqueur OT_TAGGEDDATE, autre outil,
     *  ou fichier déplacé hors du pipeline de renommage interne — voir le repli sur ce marqueur déjà
     *  ajouté dans loadDirectory()/loadSingleFile()) mais que le scan affiche encore "En attente"
     *  faute de correspondance dans le cache SQLite. Réécrit les MÊMES tags déjà lus (entry.current)
     *  avec juste OT_TAGGEDDATE en plus — aucune identification, aucun autre champ modifié. Applique
     *  ENSUITE le même renommage/déplacement (masque + dossier configuré) qu'un taguage normal si
     *  activé dans les Réglages (même bloc que TagEnrichment.saveEntry(), copié ici plutôt
     *  qu'appelé : saveEntry() résout aussi une pochette via appel réseau CAA/FanArt/Deezer/Shazam,
     *  ce qui n'a pas de sens pour un fichier qu'on ne fait que "confirmer déjà bon" — demandé le
     *  2026-07-28 : "si les audios on veut qu'ils soit bien taggué comme il faut par rapport au
     *  masque et déplacement vers le dossier de notre choix").
     */
    private void markAsAlreadyTagged() {
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        if (sel.length == 0) {
            setStatus(I18n.t("Sélectionnez d'abord les fichiers à marquer comme déjà taggués."));
            return;
        }
        List<FileEntry> targets = new ArrayList<>();
        for (int r : sel) targets.add(tableModel.get(table.convertRowIndexToModel(r)));

        int maskIndex = Config.get().autoRenameEnabled() ? Config.get().defaultRenameMask() : -1;
        int confirm = JOptionPane.showConfirmDialog(this,
            I18n.t("Marquer %d fichier(s) comme déjà taggué(s) ?\n\n"
                 + "Les tags déjà présents sur le disque sont réécrits tels quels, avec juste un "
                 + "marqueur de date ajouté (OT_TAGGEDDATE) — aucune identification."
                 + (maskIndex >= 0
                    ? "\nLe renommage/déplacement automatique (masque configuré) sera aussi "
                      + "appliqué, comme pour un enregistrement normal."
                    : "\nLe renommage automatique est désactivé dans les Réglages — le fichier "
                      + "ne sera pas déplacé."),
                 targets.size()),
            I18n.t("Marquer comme déjà taggué"), JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        setStatus(I18n.t("Marquage de %d fichier(s)…", targets.size()));
        record MarkResult(FileEntry entry, boolean ok, String msg, Path newPath) {}
        new SwingWorker<Void, MarkResult>() {
            int done = 0, errors = 0;
            @Override protected Void doInBackground() {
                TagWriter    writer  = new TagWriter();
                FileRenamer  renamer = new FileRenamer();
                MetadataCache cache  = new MetadataCache();
                try {
                    for (FileEntry e : targets) {
                        File fichier = e.currentPath != null ? e.currentPath.toFile() : e.file;
                        try {
                            com.opentagger.model.TagInfo ti = e.current != null ? e.current : readTags(fichier);
                            ti.taggedDate = java.time.LocalDate.now().toString();
                            com.opentagger.model.TagInfo written = writer.write(fichier, ti);

                            String cacheKey = !written.recordingMbid.isBlank()
                                    ? written.recordingMbid
                                    : MetadataCache.syntheticKey(written.artist, written.title);
                            cache.recordFileTagging(fichier.getAbsolutePath(), cacheKey, MetadataCache.SOURCE_EXISTING);

                            Path newPath = null;
                            if (maskIndex >= 0) {
                                Path curPath   = fichier.toPath();
                                Path oldParent = curPath.getParent();
                                String libRoot = Config.get().libraryRoot();
                                Path root = (!libRoot.isBlank() && Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                                        ? java.nio.file.Paths.get(libRoot)
                                        : (e.scanRoot != null ? e.scanRoot : oldParent);
                                newPath = renamer.rename(curPath, written, maskIndex, root);
                                if (newPath != null) {
                                    // Re-classer l'historique sous le nouveau chemin — même piège
                                    // que TagEnrichment.saveEntry(), voir son commentaire.
                                    cache.deleteFileHistory(curPath.toFile().getAbsolutePath());
                                    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), cacheKey, MetadataCache.SOURCE_EXISTING);
                                    if (Config.get().deleteEmptyDirsAfterRename()) {
                                        FileRenamer.deleteEmptyAncestors(oldParent, root);
                                    }
                                }
                            }
                            done++;
                            publish(new MarkResult(e, true, null, newPath));
                        } catch (Exception ex) {
                            errors++;
                            publish(new MarkResult(e, false,
                                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(), null));
                        }
                    }
                } finally { cache.close(); }
                return null;
            }
            @Override protected void process(List<MarkResult> chunk) {
                for (MarkResult r : chunk) {
                    if (r.ok()) {
                        r.entry().status  = FileEntry.Status.TAGGED;
                        r.entry().message = "";
                        if (r.newPath() != null) r.entry().currentPath = r.newPath();
                    } else {
                        r.entry().status  = FileEntry.Status.ERROR;
                        r.entry().message = r.msg();
                    }
                    tableModel.update(r.entry());
                }
            }
            @Override protected void done() {
                refreshStats();
                setStatus(I18n.t("%d fichier(s) marqué(s) comme déjà taggué(s)", done)
                        + (errors > 0 ? I18n.t(", %d erreur(s)", errors) : "") + ".");
            }
        }.execute();
    }

    /** Lance le taguage immédiatement sur les fichiers réinitialisés par forceRetag(). */
    private void launchForcedTagging(List<FileEntry> forcedTargets, boolean useAcoustId) {
        launchForcedTagging(forcedTargets, useAcoustId, false);
    }

    /** openReviewOnDone : utilisé par reidentifyUnmatched() — au lieu du onTaggingDone() normal
     *  (qui déclenche des suites automatiques pensées pour des fichiers déjà TAGGED, comme la
     *  complétion d'albums), ouvre ReidentifyReviewDialog sur les fichiers retrouvés pour
     *  confirmation explicite avant qu'ils ne rejoignent le flux normal (Enregistrer tout). */
    private void launchForcedTagging(List<FileEntry> forcedTargets, boolean useAcoustId, boolean openReviewOnDone) {
        lastStatsRefreshMs = 0;
        final int forcedTotal = forcedTargets.size();
        String runLabel = openReviewOnDone ? I18n.t("Ré-identification par empreinte") : I18n.t("Re-taguage forcé");
        runStartMillis = System.currentTimeMillis();
        logRunStart(runLabel, forcedTotal);
        TaggingWorker w = new TaggingWorker(forcedTargets, useAcoustId,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> {
                tableModel.update(entry);
                table.repaint();
                followProcessing(entry);
                if (entry.status != FileEntry.Status.PROCESSING) appendLog(entry);
            },
            // Même correctif que continueStartTagging() (voir son commentaire, 2026-08-15) : mise à
            // jour de la barre à chaque fichier, plus au pourcentage arrondi (qui ne bougeait quasi
            // jamais sur un gros lot). refreshStats() throttlé à 300ms (lastStatsRefreshMs, déjà
            // réinitialisé plus haut) plutôt qu'à chaque fichier — évite O(n²) sur un gros lot forcé.
            (doneCount, total) -> SwingUtilities.invokeLater(() -> {
                JProgressBar bar = progressBars.get(ProgressSlot.TAGGING);
                bar.setValue((int) ((doneCount * 100L) / total));
                bar.setString(doneCount + " / " + total + etaText(doneCount, total));
                long now = System.currentTimeMillis();
                if (now - lastStatsRefreshMs >= 300) {
                    lastStatsRefreshMs = now;
                    refreshStats();
                }
            }));
        w.addPropertyChangeListener(evt -> {
            if (SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                if (openReviewOnDone) {
                    resetBtns();
                    refreshStats();
                    List<FileEntry> found = new ArrayList<>();
                    for (FileEntry e : forcedTargets)
                        if (e.status == FileEntry.Status.IDENTIFIED) found.add(e);
                    if (found.isEmpty()) {
                        setStatus(I18n.t("Ré-identification terminée — aucun des %d fichier(s) n'a été retrouvé.", forcedTotal));
                    } else {
                        setStatus(I18n.t("%d / %d fichier(s) retrouvé(s) — revue…", found.size(), forcedTotal));
                        new ReidentifyReviewDialog(this, found, tableModel).setVisible(true);
                        refreshStats(); // dialog modal → bloquant, rafraîchir après fermeture
                    }
                } else {
                    onTaggingDone(forcedTargets);
                }
            }
        });
        btnTagAll.setEnabled(false);
        btnCancel.setEnabled(true);
        beginProgress(ProgressSlot.TAGGING);
        WorkerHub.get().submit(WorkerHub.TaskKind.TAGGING, runLabel, w, w::stopNow);
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
        Config.PostTagCompletion mode = Config.get().postTagCompletion();
        boolean autoAlbums      = mode == Config.PostTagCompletion.FIELDS_AND_ALBUMS;
        boolean autoIncomplete  = mode != Config.PostTagCompletion.NONE;
        String suffix = autoIncomplete
                ? I18n.t("  — complétion des fichiers incomplets…")
                : (autoAlbums ? I18n.t("  — complétion albums…") : "");
        setStatus(I18n.t("Terminé — ✓ %d identifié(s) (pas encore enregistré)  ⚠ %d ignoré(s)  ✗ %d erreur(s)", ok, skip, err) + suffix);
        // invokeLater : on est encore dans le property-change listener DONE du TaggingWorker qui
        // vient de finir, appelé AVANT celui de WorkerHub.submit() (posé après, dans submit() lui-
        // même) — WorkerHub n'a donc pas encore retiré TAGGING de active(). Un submit() immédiat
        // ici verrait systématiquement TAGGING comme son propre blocker et lèverait
        // IllegalStateException (déjà vu en pratique : "Passe complète" ne se lançait jamais après
        // un taguage, sans aucun message). Différer d'un tour d'EDT laisse ce firePropertyChange se
        // terminer (tous les listeners, dont celui de WorkerHub) avant de retenter.
        if (autoIncomplete) {
            SwingUtilities.invokeLater(() -> autoCompleteIncomplete(autoAlbums ? this::completeAlbums : () -> {}));
        } else if (autoAlbums) {
            SwingUtilities.invokeLater(this::completeAlbums);
        } else if (chkAutoReidentifyUnmatched != null && chkAutoReidentifyUnmatched.isSelected()) {
            // else if (pas juste if) : évite de soumettre TAGGING (ce déclenchement) en même temps
            // qu'ALBUM_COMPLETION/ALBUM_CLUSTER ci-dessus au même tour d'EDT — les deux sont dans
            // WorkerHub.LIBRARY_WRITE et se bloqueraient mutuellement (submit() lèverait
            // IllegalStateException). Ne cible QUE les fichiers restés "Non identifié" de CE run
            // précis (done), jamais tout l'historique "Sans_correspondance" — sinon chaque taguage
            // re-tenterait aussi tous les échecs des runs précédents, de plus en plus lent au fil
            // du temps.
            List<FileEntry> justSkipped = new ArrayList<>();
            for (FileEntry e : done) if (e.status == FileEntry.Status.SKIPPED) justSkipped.add(e);
            if (!justSkipped.isEmpty()) {
                SwingUtilities.invokeLater(() -> reidentifyUnmatched(justSkipped));
            }
        }
        // Lien manquant entre les deux boucles auto : scheduleAutoTaggingFollowUp() (Tout tagger)
        // et scheduleAutoSaveFollowUp() (Enregistrer tout) sont chacune auto-suffisantes une fois
        // lancées, mais RIEN ne déclenchait jamais la toute première Enregistrer tant que
        // l'utilisateur ne cliquait pas dessus manuellement — observé en direct sur un run réel de
        // 11h+ : le taguage tournait en continu (auto-suivi actif, des milliers de fichiers
        // IDENTIFIED accumulés), mais file_history restait figé, RIEN n'était jamais écrit sur le
        // disque. Ici, systématiquement après un "Tout tagger" (manuel ou auto-relancé), on
        // s'assure qu'une chaîne de surveillance d'Enregistrer tourne aussi.
        if (!autoSaveWatchPending) scheduleAutoSaveFollowUp();
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
        // allEntries() : complétion limitée aux fichiers visibles sinon un filtre actif sans
        // rapport (recherche, chip de statut) ferait ignorer silencieusement le reste.
        for (FileEntry e : tableModel.allEntries()) {
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

        // Même garde que completeAlbums()/startTagging() : sans elle, submit() lève
        // IllegalStateException dès qu'un Enregistrement (ou tout autre LIBRARY_WRITE) tourne
        // encore au moment du déclenchement automatique — silencieusement, sans dialogue (voir
        // WorkerHub.submit()). Ici on saute juste cette passe, sans forcer, comme les autres.
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.INFO_COMPLETER);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — complétion des fichiers incomplets sautée cette fois-ci.",
                    String.join(", ", blockers)));
            onDone.run();
            return;
        }

        beginProgress(ProgressSlot.INFO_COMPLETER);
        progressBars.get(ProgressSlot.INFO_COMPLETER).setMaximum(targets.size());
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
                JProgressBar bar = progressBars.get(ProgressSlot.INFO_COMPLETER);
                bar.setValue(doneCount);
                bar.setString(doneCount + "/" + total + etaText(doneCount, total));
            })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    endProgress(ProgressSlot.INFO_COMPLETER);
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
        // allEntries() : sinon détection faussée si des pistes de l'album sont masquées par un
        // filtre actif (compte de pistes/artistes distincts sous-évalué).
        java.util.Map<String, List<FileEntry>> byAlbum = new java.util.LinkedHashMap<>();
        for (FileEntry e : tableModel.allEntries()) {
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
        btnTagAll.setEnabled(true);
        btnCancel.setEnabled(false);
        endProgress(ProgressSlot.TAGGING);
    }

    private void renameTagged() {
        // ── 1. Calculer l'aperçu (aucun fichier déplacé ici) ─────────────────
        // allEntries() : sinon un filtre actif masquant tous les fichiers tagués visibles
        // faisait croire à tort qu'il n'y en avait aucun dans toute la bibliothèque, alors
        // que compute()/buildRenameJob() (même correctif) les renomment bel et bien.
        long tagged = 0;
        for (FileEntry e : tableModel.allEntries())
            if (e.status == FileEntry.Status.TAGGED) tagged++;
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

            // Publié : FileEntry + description de l'issue (pour le Journal) — ce pipeline n'écrivait
            // JUSQU'ICI aucune trace persistante (ni console ni Journal), contrairement à
            // Enregistrer/Transcoder/Compilations déjà corrigés aujourd'hui pour la même raison :
            // aucun moyen de vérifier après coup ce qui a réellement été renommé/sauté/échoué sur
            // un lot de plusieurs dizaines de milliers de fichiers. Trouvé en direct 2026-07-29
            // ("ne renomme pas les audios, vérifie les logs" — rien à vérifier, le trou était là).
            record RenameLog(FileEntry entry, String text, FileEntry.Status status) {}

            SwingWorker<String, RenameLog> worker = new SwingWorker<>() {
                int renamed = 0, skipped = 0, errors = 0;

                @Override
                protected String doInBackground() {
                    FileRenamer renamer = new FileRenamer();
                    boolean cleanupEmptyDirs = Config.get().deleteEmptyDirsAfterRename();
                    // allEntries() : sinon un fichier tagué masqué par un filtre actif au moment du
                    // clic n'était jamais renommé sur le disque, alors que l'aperçu (voir
                    // RenamePreviewDialog.compute(), même correctif) prétend maintenant l'inclure.
                    for (FileEntry e : tableModel.allEntries()) {
                        // Vérifié à chaque itération (pas seulement avant la boucle) : c'est le
                        // point d'annulation coopératif utilisé par RenamePreviewDialog (bouton
                        // Annuler / fermeture pendant un renommage) — cancel(false) plutôt que
                        // cancel(true) pour ne jamais interrompre un Files.move en plein vol.
                        if (isCancelled()) break;
                        if (e.status != FileEntry.Status.TAGGED) continue;
                        Path oldPath = e.currentPath;
                        Path root = destRoot != null ? destRoot
                                  : (e.scanRoot != null ? e.scanRoot : oldPath.getParent());
                        String oldName = e.filename();
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
                                // deleteFileHistory(oldAbs) manquait ici (déjà fait dans
                                // TagEnrichment.saveEntry()/BatchProcessor/App.java) : sans lui,
                                // l'ancienne entrée chemin→mbid restait vivante en plus de la
                                // nouvelle — si ce même chemin absolu est un jour réutilisé par un
                                // fichier totalement différent (re-rip vers un nom générique...),
                                // findTags() lui ferait confiance à 100% sans revérifier l'audio.
                                String oldAbs = oldPath.toFile().getAbsolutePath();
                                String mbid = cache.getFileTagging(oldAbs);
                                if (mbid != null) {
                                    cache.deleteFileHistory(oldAbs);
                                    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), mbid);
                                }
                                publish(new RenameLog(e, "✓ " + I18n.t("Renommé") + " : " + oldName
                                        + " → " + newPath.getFileName(), FileEntry.Status.TAGGED));
                            } else {
                                skipped++;
                                publish(new RenameLog(e, "⚠ " + I18n.t("Déjà au bon nom") + " : " + oldName,
                                        FileEntry.Status.SKIPPED));
                            }
                        } catch (Exception ex) {
                            errors++;
                            String msg = I18n.t("Renommage : %s", ex.getMessage() != null ? ex.getMessage() : I18n.t("erreur"));
                            SwingUtilities.invokeLater(() -> e.message = msg);
                            publish(new RenameLog(e, "✗ " + I18n.t("Erreur") + " : " + oldName + " — " + msg,
                                    FileEntry.Status.ERROR));
                        }
                    }
                    return I18n.t("Renommage — ✓ %d  déjà OK %d  ✗ %d erreur(s)",
                        renamed, skipped, errors);
                }

                @Override
                protected void process(List<RenameLog> chunks) {
                    done[0] += chunks.size();
                    for (RenameLog r : chunks) {
                        tableModel.update(r.entry());
                        appendLogLine(r.text(), r.status(), r.entry());
                    }
                    onProgress.accept(done[0]);
                }

                @Override
                protected void done() {
                    cache.close();
                    try {
                        String summary = get();
                        setStatus(summary);
                        appendLogLine(summary, FileEntry.Status.TAGGED, null);
                    } catch (Exception ignore) {}
                    onDone.run();
                }
            };
            worker.execute();
            return () -> worker.cancel(false);
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
            // allEntries() : "de tout" doit couvrir toute la bibliothèque, pas juste la vue
            // filtrée du moment — sinon un transcodage "de tout" limité aux fichiers visibles.
            for (FileEntry e : tableModel.allEntries()) {
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
        beginProgress(ProgressSlot.TRANSCODE);

        TranscodeWorker w = new TranscodeWorker(toTranscode, tableModel,
            pr -> {
                setStatus("⏳ " + I18n.t("Transcodage %d / %d", pr.done(), pr.total()));
                // Barre de progression bas-droite jamais mise à jour ici jusqu'ici, alors que
                // done()/total() étaient déjà disponibles (juste utilisés pour le texte de la
                // barre de statut) — même trou que les autres pipelines audités aujourd'hui.
                JProgressBar bar = progressBars.get(ProgressSlot.TRANSCODE);
                bar.setMaximum(Math.max(pr.total(), 1));
                bar.setValue(pr.done());
                bar.setString(pr.done() + " / " + pr.total() + etaText(pr.done(), pr.total()));
                // Absent du Journal jusqu'ici — même trou que les autres pipelines audités
                // aujourd'hui : le résultat par fichier (converti/déjà bon format/erreur)
                // n'existait qu'un instant dans la barre de statut, écrasé au fichier suivant.
                String fname = pr.oldName() != null ? pr.oldName()
                        : pr.entry() != null ? pr.entry().filename() : "?";
                if (pr.error() != null) {
                    appendLogLine("✗ " + I18n.t("Erreur transcodage") + " : " + fname + " — " + pr.error(),
                            FileEntry.Status.ERROR, pr.entry());
                } else if (pr.newPath() != null) {
                    appendLogLine("✓ " + I18n.t("Transcodé") + " : " + fname + " → " + pr.newPath().getFileName(),
                            FileEntry.Status.TAGGED, pr.entry());
                } else {
                    appendLogLine("⚠ " + I18n.t("Déjà au bon format") + " : " + fname,
                            FileEntry.Status.SKIPPED, pr.entry());
                }
            },
            summary -> {
                endProgress(ProgressSlot.TRANSCODE);
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
        beginProgress(ProgressSlot.ALBUM_COMPLETION);
        AlbumCompletionWorker w = new AlbumCompletionWorker(
            tableModel,
            this::setStatus,
            this::appendLog,
            () -> SwingUtilities.invokeLater(() -> {
                endProgress(ProgressSlot.ALBUM_COMPLETION);
                setStatus(I18n.t("Complétion albums terminée."));
            }),
            (done, total) -> SwingUtilities.invokeLater(() -> {
                JProgressBar bar = progressBars.get(ProgressSlot.ALBUM_COMPLETION);
                bar.setMaximum(Math.max(total, 1));
                bar.setValue(done);
                bar.setString(done + " / " + total + etaText(done, total));
            })
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
        beginProgress(ProgressSlot.ALBUM_CLUSTER);
        AlbumClusterWorker w = new AlbumClusterWorker(
            tableModel,
            this::setStatus,
            this::appendLog,
            () -> SwingUtilities.invokeLater(() -> {
                endProgress(ProgressSlot.ALBUM_CLUSTER);
                setStatus(I18n.t("Groupement des albums terminé."));
            }),
            (done, total) -> SwingUtilities.invokeLater(() -> {
                JProgressBar bar = progressBars.get(ProgressSlot.ALBUM_CLUSTER);
                bar.setMaximum(Math.max(total, 1));
                bar.setValue(done);
                bar.setString(done + " / " + total + etaText(done, total));
            })
        );
        WorkerHub.get().submit(WorkerHub.TaskKind.ALBUM_CLUSTER,
                I18n.t("Groupement des albums"), w, () -> w.cancel(true));
    }

    // ── Grouper par compilations (Stars 80, NRJ, Fun Radio, RFM…) ─────────────

    private void groupByCompilations() { groupByCompilations(false, () -> {}); }

    /** Affiche la fenêtre de revue unique accumulée par groupByCompilations(true, ...) (voir son
     *  commentaire) — à appeler uniquement à la toute fin réelle de la chaîne Enregistrer, jamais
     *  entre deux vagues intermédiaires. No-op si rien n'a été accumulé (cas normal quand
     *  chkAutoGroupCompilations est désactivé). */
    private void flushPendingCompilationMatches() {
        if (pendingCompilationMatches.isEmpty()) return;
        new CompilationMatchDialog(this, new ArrayList<>(pendingCompilationMatches), tableModel).setVisible(true);
        pendingCompilationMatches.clear();
    }

    /** onDone : exécuté sur l'EDT une fois cette passe de groupement TERMINÉE (dialogue affiché ou
     *  correspondances accumulées) — permet à l'appelant de repousser sa propre suite (relance
     *  automatique / runPostTagCommand()) après que cette passe ait fini d'accumuler, au lieu de
     *  l'exécuter immédiatement en parallèle du worker encore en cours. Sans ça, la toute DERNIÈRE
     *  vague de la chaîne Enregistrer pouvait déclencher runPostTagCommand() (donc
     *  flushPendingCompilationMatches()) AVANT que son propre CompilationClusterWorker n'ait fini
     *  d'ajouter ses correspondances — perdant ou dédoublant le dernier lot. Repéré en direct
     *  (2026-08-13) via le symptôme inverse (3 popups au lieu d'un). */
    private void groupByCompilations(boolean deferDialog, Runnable onDone) {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.COMPILATION_CLUSTER);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Groupement par compilations annulé."));
            onDone.run();
            return;
        }
        // Blockers PRÉCIS (WorkerHub.conflictsWith), pas activeOperations() (bloquait sur
        // N'IMPORTE QUELLE tâche active, y compris Tagger — alors que cette passe ne lit que des
        // fichiers déjà TAGUÉS, jamais ceux que Tagger traite, ensembles disjoints, voir
        // WorkerHub.conflictsWith()). Cette passe ne s'exclut donc réellement qu'avec les tâches
        // qui ÉCRIVENT sur des fichiers déjà tagués (Enregistrer, Complétion, Transcodage...).
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.COMPILATION_CLUSTER);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de grouper par compilations.", String.join(", ", blockers)));
            onDone.run();
            return;
        }
        // L'exigence "bibliothèque taguée à 100 %" (héritée de clusterAlbums()) est retirée : cette
        // passe filtre déjà elle-même sur status==TAGGED (voir CompilationClusterWorker), donc des
        // fichiers encore PENDING/PROCESSING ailleurs dans la table ne changent rien à sa
        // correction — juste moins de correspondances trouvées pour l'instant, plus au fil du
        // taguage. Bloquer ici empêchait justement de lancer les deux passes ensemble (retour
        // utilisateur : "vérifie si on peut améliorer que les deux tournent ensemble").
        setStatus(I18n.t("Recherche de correspondances de compilations…"));
        beginProgress(ProgressSlot.COMPILATION_CLUSTER);
        CompilationClusterWorker w = new CompilationClusterWorker(
            tableModel,
            this::setStatus,
            s -> appendLogLine(s, FileEntry.Status.IDENTIFIED, null),
            matches -> SwingUtilities.invokeLater(() -> {
                endProgress(ProgressSlot.COMPILATION_CLUSTER);
                if (matches.isEmpty()) {
                    setStatus(I18n.t("Aucune correspondance de compilation trouvée."));
                } else if (deferDialog) {
                    pendingCompilationMatches.addAll(matches);
                    setStatus(I18n.t("%d correspondance(s) de compilation en attente (fin de session).", pendingCompilationMatches.size()));
                } else {
                    new CompilationMatchDialog(this, matches, tableModel).setVisible(true);
                }
                onDone.run();
            }),
            (done, total) -> SwingUtilities.invokeLater(() -> {
                JProgressBar bar = progressBars.get(ProgressSlot.COMPILATION_CLUSTER);
                bar.setMaximum(Math.max(total, 1));
                bar.setValue(done);
                bar.setString(done + " / " + total + etaText(done, total));
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
        // allEntries() : même correctif que renameTagged() — sinon un filtre actif masquant
        // tous les fichiers tagués visibles faisait croire à tort qu'il n'y en avait aucun.
        long tagged = 0;
        for (FileEntry e : tableModel.allEntries())
            if (e.status == FileEntry.Status.TAGGED) tagged++;
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
        // Resynchronise les cases du menu Tagger → Déplacement avec Préférences (même Config
        // sous-jacent des deux côtés, voir buildMenuTagger()) — sans ça, changer la case ici
        // n'aurait affiché l'état à jour qu'au redémarrage de l'appli.
        if (chkUseLibraryRootMenu != null) chkUseLibraryRootMenu.setSelected(Config.get().useLibraryRootEnabled());
        if (chkMoveSkippedMenu != null) chkMoveSkippedMenu.setSelected(Config.get().skippedMoveEnabled());
        if (chkMoveDurationMismatchMenu != null) chkMoveDurationMismatchMenu.setSelected(Config.get().durationMismatchMoveEnabled());
    }

    private void updateMaskLabel() {
        if (lblMask == null) return;
        String name = new FileRenamer().maskLabel(currentMask);
        lblMask.setText("  [#" + currentMask + " " + name + "]  ");
        lblMask.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
    }

    private void setAllSelected(boolean val) {
        // allEntries() : les cases des fichiers masqués par un filtre actif restaient sinon dans
        // leur état précédent malgré le nom "Tout" — setValueAt(row,...) est indexé sur la vue
        // filtrée (FileTableModel.visible), donc muté directement puis rafraîchi via update(),
        // même idiome que partout ailleurs dans ce fichier pour une mutation hors setValueAt.
        for (FileEntry e : tableModel.allEntries()) {
            e.selected = val;
            tableModel.update(e);
        }
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
        AudioFile af = null;
        try {
            af = AudioFileIO.read(f);
        } catch (Exception ignored) {}
        // En-tête audio (durée, débit...) indépendant du tag lui-même, un fichier sans AUCUN tag a
        // quand même une durée — et un fichier dont AudioFileIO.read() plante entièrement (en-tête
        // ID3/atom abîmé mais fichier par ailleurs parfaitement lisible/jouable) mérite quand même
        // qu'on essaie de lui trouver une durée : avant ce correctif, un AudioFileIO.read() en échec
        // sautait purement et simplement la sonde ffprobe ci-dessous, laissant durationSec à 0.
        if (af != null) {
            try {
                if (af.getAudioHeader() != null) ti.durationSec = af.getAudioHeader().getTrackLength();
            } catch (Exception ignored) {}
        }
        // jaudiotagger renvoie parfois 0 pour un .m4a/AAC structurellement valide (constaté en
        // direct : un fichier de 7,8 Mo, flux AAC de 3:56 confirmé par ffprobe, mais
        // getTrackLength()==0 — probablement un souci de parsing des atomes mvhd/mdhd/stts pour
        // certains encodeurs). Grave : durationSec==0 est LE signal utilisé ailleurs pour repérer
        // les fichiers vides/corrompus (voir le commentaire sur ce champ dans TagInfo.java) — un
        // faux 0 fait donc passer un fichier parfaitement bon pour cassé. Contre-vérification via
        // ffprobe (lecture des métadonnées du conteneur seulement, pas un décodage complet — coût
        // négligeable), déclenchée dès que durationSec est encore à 0 à ce stade — que ce soit parce
        // que getTrackLength() a renvoyé 0, ou parce qu'AudioFileIO.read() a échoué plus haut.
        if (ti.durationSec <= 0) {
            int probed = com.opentagger.AudioDuration.probeSeconds(f.getAbsolutePath());
            if (probed > 0) ti.durationSec = probed;
        }
        if (af == null) return ti;
        try {
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

            // ── Marqueur de taguage (portable, indépendant du cache SQLite) ──
            ti.taggedDate         = TagWriter.getCustomField(tag, "OT_TAGGEDDATE");

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

        if (activeSkipReasonFilter != null) {
            com.opentagger.model.SkipReason r = activeSkipReasonFilter;
            pred = pred.and(e -> e.skipReason == r);
        }

        boolean noFilter = text.isBlank() && statusSel == FILTER_ALL && activeSkipReasonFilter == null;
        tableModel.setFilter(noFilter ? null : pred);

        // Mettre à jour les chips de stats pour refléter la vue filtrée
        refreshStats();
    }

    /** Filtre le tableau principal sur une catégorie de non-identification précise — appelé depuis
     *  NonIdentifiedReportDialog (double-clic sur une ligne du rapport). Réinitialise le filtre de
     *  statut (les chips) : la catégorie choisie détermine déjà implicitement SKIPPED ou ERROR, pas
     *  besoin d'un chip actif en plus, qui serait de toute façon ambigu (certaines catégories comme
     *  ERROR_GENERIC/FILE_MISSING sont côté ERROR, les autres côté SKIPPED). */
    public void filterBySkipReason(com.opentagger.model.SkipReason reason) {
        activeStatusFilter = FILTER_ALL;
        activeSkipReasonFilter = reason;
        applyFilter();
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
        // allEntries() : un export "CSV" incomplet si un filtre reste actif au moment du clic
        // (sinon getRowCount()/get(i), qui portent sur la vue déjà filtrée) — le nom de l'action
        // ne suggère aucune restriction à la vue courante.
        if (tableModel.allEntries().isEmpty()) { setStatus(I18n.t("Aucun fichier à exporter.")); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("opentagger_export.csv"));
        fc.setDialogTitle(I18n.t("Exporter en CSV"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        setStatus(I18n.t("Export CSV en cours…"));
        // Copie de la liste sur l'EDT AVANT de passer en arrière-plan : doInBackground() itérait
        // avant un correctif précédent directement sur tableModel (getRowCount()/get(i)) depuis un
        // thread de fond pendant que l'EDT peut concurremment ajouter/retirer des lignes (scan en
        // cours, filtre) — allEntries() est elle-même une vue live (non copiée) sur la liste
        // mutable sous-jacente, donc toujours recopiée ici dans un ArrayList frais pour figer la
        // structure itérée.
        List<FileEntry> snapshot = new ArrayList<>(tableModel.allEntries());
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try (PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
                    // En-tête BOM pour Excel
                    pw.print('﻿');
                    pw.println(I18n.t("Fichier,Artiste,Artiste Album,Titre,Album,Année,Genre,Piste,Disque,Compositeur,Chef,MBID,Statut"));
                    for (FileEntry e : snapshot) {
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
        // allEntries() (compte ET liste réelle juste en dessous) : sinon un fichier tagué masqué
        // par un filtre actif est silencieusement absent de la playlist exportée.
        long tagged = 0;
        for (FileEntry e : tableModel.allEntries())
            if (e.status == FileEntry.Status.TAGGED) tagged++;
        if (tagged == 0) { setStatus(I18n.t("Aucun fichier tagué à exporter.")); return; }

        JFileChooser fc = new JFileChooser();
        String ext = format.equalsIgnoreCase("xspf") ? ".xspf" : ".m3u";
        fc.setSelectedFile(new File("playlist" + ext));
        fc.setDialogTitle(I18n.t("Exporter playlist %s", format.toUpperCase()));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File out = fc.getSelectedFile();
        if (!out.getName().toLowerCase().endsWith(ext))
            out = new File(out.getAbsolutePath() + ext);

        List<FileEntry> all = new ArrayList<>(tableModel.allEntries());

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
        if (tableModel.allEntries().isEmpty()) { setStatus(I18n.t("Chargez d'abord les fichiers audio à tagger.")); return; }
        // Bug réel signalé par l'utilisateur : passer TOUTE la table (TAGGED/IDENTIFIED compris)
        // fait sonder la durée ffprobe (un sous-processus, jusqu'à 10s de timeout CHACUN — voir
        // AudioDuration.probeSeconds) de CHAQUE fichier dans PodcastMatcher.match(), avant même de
        // commencer le matching — sur une bibliothèque de 100k+ fichiers, "Matching en cours…"
        // tournait des heures sans jamais rien afficher (aucun retour de progression). Un épisode
        // de podcast ne peut de toute façon correspondre qu'à un fichier PAS DÉJÀ identifié comme
        // vraie musique — restreindre aux PENDING/SKIPPED (même filtre que AlbumCompletionWorker
        // pour ses candidats) réduit l'ensemble à sonder à ce qui est réellement pertinent.
        //
        // Correctif incomplet à l'époque : PENDING/SKIPPED restait ~80k fichiers sur cette
        // bibliothèque — même problème, en moins pire. Repéré en direct (2026-08-15) : un filtre
        // texte actif dans le tableau principal ("butler", 60 lignes visibles) était totalement
        // ignoré ici (commentaire d'origine : "sinon candidats limités aux fichiers visibles si un
        // filtre est actif" — délibérément ignoré, mauvais choix). Un filtre actif exprime
        // clairement une intention de restreindre — le respecter réduit le lot de 80k à 60 dans ce
        // cas réel. Repli sur allEntries() UNIQUEMENT si aucun filtre n'est actif (comportement
        // d'origine préservé pour ce cas).
        List<com.opentagger.model.FileEntry> pool = new ArrayList<>();
        if (tableModel.isFiltered()) {
            for (int i = 0; i < tableModel.getRowCount(); i++) pool.add(tableModel.get(i));
        } else {
            pool = tableModel.allEntries();
        }
        List<com.opentagger.model.FileEntry> candidates = new ArrayList<>();
        for (FileEntry e : pool) {
            if (e.status == FileEntry.Status.PENDING || e.status == FileEntry.Status.SKIPPED) candidates.add(e);
        }
        if (candidates.isEmpty()) {
            setStatus(I18n.t("Aucun fichier non identifié (PENDING/SKIPPED) à proposer pour un podcast."));
            return;
        }
        new PodcastDialog(this, candidates, tableModel).setVisible(true);
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
                // motif que VideoRecoveryDialog.onStart(). Contrairement au dialogue manuel (qui a
                // son propre JTextArea toujours visible), ce chemin AUTOMATIQUE (déclenché après
                // chaque scan de dossier, sans fenêtre ouverte) ne laissait aucune trace du
                // résultat au-delà de la barre de statut, écrasée au message suivant — ajouté au
                // Journal en plus, avec une couleur déduite du contenu du message.
                beginProgress(ProgressSlot.VIDEO_RECOVERY);
                VideoRecoveryWorker w = new VideoRecoveryWorker(videos, dir.toPath(), msg -> {
                    setStatus(msg);
                    FileEntry.Status st = msg.contains("✔") ? FileEntry.Status.TAGGED
                            : msg.contains("✗") ? FileEntry.Status.ERROR
                            : msg.contains("non reconnu") ? FileEntry.Status.SKIPPED
                            : null;
                    if (st != null) appendLogLine(msg.strip(), st, null);
                }, (done, total) -> SwingUtilities.invokeLater(() -> {
                    JProgressBar bar = progressBars.get(ProgressSlot.VIDEO_RECOVERY);
                    bar.setMaximum(Math.max(total, 1));
                    bar.setValue(done);
                    bar.setString(done + " / " + total + etaText(done, total));
                }));
                w.addPropertyChangeListener(evt -> {
                    if ("state".equals(evt.getPropertyName())
                            && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                        endProgress(ProgressSlot.VIDEO_RECOVERY);
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
        // Blockers PRÉCIS (WorkerHub.conflictsWith), pas activeOperations() — même logique que
        // groupByCompilations() : le vrai risque est la suppression/déplacement fait ensuite par
        // l'utilisateur (DuplicatesDialog) pendant qu'un autre worker écrirait encore sur les mêmes
        // fichiers, pas un scan de dossier ou une synchro ListenBrainz en parallèle.
        java.util.List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.DUPLICATE_DETECT);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de détecter les doublons.", String.join(", ", blockers)));
            return;
        }
        // allEntries() (TOUTE la bibliothèque), pas getRowCount()/get(i) (la vue déjà filtrée par
        // recherche/chip de statut) : sinon un filtre resté actif au moment du clic (recherche en
        // cours, filtre sur un statut) masque silencieusement une partie des fichiers à la
        // détection — un vrai doublon dont une seule copie passe le filtre n'était jamais signalé.
        if (tableModel.allEntries().isEmpty()) { setStatus(I18n.t("Aucun fichier chargé.")); return; }
        List<FileEntry> all = new ArrayList<>(tableModel.allEntries());
        setStatus(I18n.t("Recherche de doublons…"));
        // Poussé en SwingWorker : DuplicateDetector.detect() seul est déjà coûteux sur une grosse
        // bibliothèque (des centaines de milliers de fichiers ici), mais le vrai gel venait de
        // computeBestMap()/qualityScore(), qui ouvre chaque fichier .m4a via jaudiotagger pour
        // départager ALAC/AAC — une E/S disque par fichier, auparavant refaite sur l'EDT à la
        // construction MÊME de DuplicatesDialog (et une seconde fois à chaque clic sur "Sélection
        // intelligente"). Retour utilisateur : "recherche audio en double [...] fige l'application".
        SwingWorker<DuplicateDetector.DetectionResult, Void> w = new SwingWorker<>() {
            @Override protected DuplicateDetector.DetectionResult doInBackground() {
                List<DuplicateDetector.DuplicateGroup> groups = DuplicateDetector.detect(all);
                java.util.Map<DuplicateDetector.DuplicateGroup, FileEntry> bestByGroup = DuplicateDetector.computeBestMap(groups);
                return new DuplicateDetector.DetectionResult(groups, bestByGroup);
            }
            @Override protected void done() {
                if (isCancelled()) return;
                DuplicateDetector.DetectionResult result;
                try {
                    result = get();
                } catch (Exception e) {
                    setStatus(I18n.t("Erreur pendant la détection de doublons : %s", e.getMessage()));
                    return;
                }
                List<DuplicateDetector.DuplicateGroup> groups = result.groups();
                if (groups.isEmpty()) {
                    setStatus(I18n.t("Aucun doublon détecté."));
                    LOG.info("[Doublons] Aucun doublon parmi " + all.size() + " fichiers.");
                    return;
                }
                int total = groups.stream().mapToInt(g -> g.files().size()).sum();
                LOG.info("[Doublons] " + groups.size() + " groupe(s), " + total + " fichier(s) sur " + all.size() + " analysés.");
                for (DuplicateDetector.DuplicateGroup g : groups) {
                    LOG.info("[Doublons] Groupe " + g.confidence().badge + ": " + g.files().stream()
                        .map(e -> (e.currentPath != null ? e.currentPath : e.file.toPath()).getFileName().toString())
                        .collect(java.util.stream.Collectors.joining(" | ")));
                }
                setStatus(I18n.t("%d groupe(s) de doublons, %d fichier(s) concerné(s).", groups.size(), total));
                lastStatsRefreshMs = 0; // réinitialiser le throttle avant ce nouveau run
                // started[0] : ce callback est appelé à CHAQUE fichier (pas juste une fois au
                // début/à la fin comme les autres workers) — beginProgress()/endProgress() ne
                // doivent donc s'exécuter qu'une seule fois chacun sur toute la séquence — sinon
                // beginProgress() réinitialiserait la barre (value=0/string vide) à CHAQUE fichier.
                boolean[] started = {false};
                new DuplicatesDialog(MainFrame.this, groups, result.bestByGroup(), tableModel,
                    (s, status) -> appendLogLine(s, status, null),
                    (done, dtotal) -> SwingUtilities.invokeLater(() -> {
                        if (!started[0]) { started[0] = true; beginProgress(ProgressSlot.DUPLICATE_DETECT); }
                        JProgressBar bar = progressBars.get(ProgressSlot.DUPLICATE_DETECT);
                        bar.setValue(dtotal > 0 ? done * 100 / dtotal : 0);
                        bar.setString(done + " / " + dtotal);
                        // Chips (Total/Identifiés/Tagués/...) pas mis à jour pendant la suppression
                        // avant ce correctif — seulement à la fermeture du dialogue (voir plus bas),
                        // donc figés en plein milieu d'une suppression de 60k+ fichiers. Même
                        // throttle 300ms que le taguage (lastStatsRefreshMs) pour ne pas recalculer
                        // à chaque fichier sur un très gros lot. Retour utilisateur (2026-08-10).
                        long now = System.currentTimeMillis();
                        if (done >= dtotal || now - lastStatsRefreshMs >= 300) {
                            lastStatsRefreshMs = now;
                            refreshStats();
                        }
                        if (done >= dtotal) endProgress(ProgressSlot.DUPLICATE_DETECT);
                    })
                ).setVisible(true);
                refreshStats(); // dialog modal → bloquant, rafraîchir après fermeture
            }
        };
        WorkerHub.get().submit(WorkerHub.TaskKind.DUPLICATE_DETECT,
                I18n.t("Détection de doublons"), w, () -> w.cancel(true));
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
        // allEntries() : sinon les fichiers en erreur masqués par un filtre actif ne sont jamais
        // proposés à ce nettoyage.
        for (FileEntry e : tableModel.allEntries()) {
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
            // Largeur fixée — même correctif que juste au-dessus pour "corrupt" (voir son
            // commentaire) : un nom de fichier inhabituellement long étirerait autrement la boîte
            // de dialogue au lieu d'y faire un retour à la ligne.
            StringBuilder mb = new StringBuilder(I18n.t(
                "<html><body style='width: 480px'><b>%d fichier(s) mal étiqueté(s)</b> — pas corrompus, juste une mauvaise "
                + "extension (contenu réel différent du nom de fichier) :<br><br>", mislabeled.size()));
            int shownM = Math.min(mislabeled.size(), 8);
            for (int i = 0; i < shownM; i++) {
                File f = mislabeled.get(i).currentPath != null
                    ? mislabeled.get(i).currentPath.toFile() : mislabeled.get(i).file;
                mb.append("&nbsp;• ").append(f.getName()).append("<br>");
            }
            if (mislabeled.size() > shownM)
                mb.append(I18n.t("&nbsp;… et %d autre(s)<br>", mislabeled.size() - shownM));
            mb.append(I18n.t("<br>Renommer automatiquement vers l'extension correspondant au contenu "
                + "réel détecté (ffprobe) ?<br><i>Seul le nom change, rien d'autre n'est modifié — "
                + "jamais de suppression.</i></html>"));
            int renameOk = JOptionPane.showConfirmDialog(this, mb.toString(),
                I18n.t("Fichiers mal étiquetés"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (renameOk == JOptionPane.YES_OPTION) {
                int renamed = 0, skipped = 0;
                for (FileEntry e : mislabeled) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    String newExt = com.opentagger.AudioFormatCheck.suggestCorrectExtension(f);
                    if (newExt == null) { skipped++; continue; }
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    String stem = dot > 0 ? name.substring(0, dot) : name;
                    java.nio.file.Path parent = f.toPath().getParent();
                    java.nio.file.Path dest = parent.resolve(stem + "." + newExt);
                    for (int n = 2; java.nio.file.Files.exists(dest) && n < 100; n++)
                        dest = parent.resolve(stem + " (" + n + ")." + newExt);
                    if (java.nio.file.Files.exists(dest)) { skipped++; continue; }
                    try {
                        java.nio.file.Files.move(f.toPath(), dest);
                        com.opentagger.PlaylistSync.onFileMoved(f.toPath(), dest);
                        com.opentagger.ITunesXmlSyncQueue.onFileMoved(f.toPath(), dest);
                        e.currentPath = dest;
                        // result (tags déjà identifiés) survit à l'échec d'écriture d'origine — pas
                        // besoin de tout ré-identifier, juste retenter l'enregistrement sur le
                        // fichier renommé. Pas de result (chemin inattendu) : retombe en PENDING.
                        e.status  = e.result != null ? FileEntry.Status.IDENTIFIED : FileEntry.Status.PENDING;
                        e.message = "";
                        tableModel.update(e);
                        renamed++;
                    } catch (Exception ex) {
                        skipped++;
                    }
                }
                refreshStats();
                setStatus(skipped > 0
                    ? I18n.t("%d fichier(s) renommé(s) — %d non résolu(s) (format non reconnu, à faire manuellement).", renamed, skipped)
                    : I18n.t("%d fichier(s) renommé(s) avec la bonne extension.", renamed));
            }
        }

        if (corrupt.isEmpty()) return;

        // Construire le message de confirmation (uniquement les vraies erreurs de lecture/format)
        // Largeur FIXÉE (style width sur le body) — sans ça, du HTML Swing ne fait jamais de
        // retour à la ligne tout seul : e.message peut être un diagnostic ffmpeg brut d'une seule
        // ligne très longue (options libavdevice/libavfilter, chemins complets...), ce qui étirait
        // toute la boîte de dialogue sur la largeur de l'écran (voire des deux écrans en dual-
        // monitor) au lieu de rester dans une taille raisonnable — repéré à l'écran.
        StringBuilder sb = new StringBuilder(
                I18n.t("<html><body style='width: 480px'>Déplacer <b>%d fichier(s) illisible(s)</b> dans la corbeille ?<br><br>", corrupt.size()));
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
        //
        // SwingWorker plutôt qu'une boucle directe sur l'EDT (comme avant ce correctif) : chaque
        // moveToTrash()/delete() est une opération disque synchrone (écriture de métadonnées +
        // déplacement pour la corbeille système), et avec le repérage désormais fiable des coquilles
        // vides au scan (voir process() plus haut), "corrupt" peut contenir plusieurs milliers
        // d'entrées d'un coup sur une grosse bibliothèque — gelait l'interface entière (aucun
        // répaint) pour toute la durée de l'opération, repéré en direct 2026-07-28 (1734 fichiers).
        setStatus(I18n.t("Suppression de %d fichier(s) illisible(s)…", corrupt.size()));
        new SwingWorker<int[], FileEntry>() {
            @Override protected int[] doInBackground() {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                boolean trashSupported = desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
                int deleted = 0, failDel = 0;
                for (FileEntry e : corrupt) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    boolean moved = trashSupported ? desktop.moveToTrash(f) : f.delete();
                    if (moved) { deleted++; publish(e); } else { failDel++; }
                }
                return new int[]{ deleted, failDel, trashSupported ? 1 : 0 };
            }
            @Override protected void process(List<FileEntry> chunks) {
                for (FileEntry e : chunks) {
                    int idx = tableModel.indexOf(e);
                    if (idx >= 0) tableModel.remove(idx);
                }
            }
            @Override protected void done() {
                int[] r;
                try { r = get(); } catch (Exception ex) { return; }
                String where = r[2] == 1 ? I18n.t("déplacé(s) dans la corbeille") : I18n.t("supprimé(s)");
                String msg = I18n.t("%d fichier(s) illisible(s) %s", r[0], where);
                if (r[1] > 0) msg += I18n.t(", %d échec(s) (permission refusée ?)", r[1]);
                setStatus(msg);
                JOptionPane.showMessageDialog(MainFrame.this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
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
            // allEntries() : "de tout" doit couvrir toute la bibliothèque, pas juste la vue
            // filtrée du moment.
            candidates.addAll(tableModel.allEntries());
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
                        // Largeur fixée — même correctif que deleteErrorFiles() (voir son
                        // commentaire) : r.message() peut être une réponse d'erreur API brute, une
                        // seule ligne potentiellement longue, sans quoi la boîte de dialogue
                        // s'étirerait au lieu de faire un retour à la ligne.
                        StringBuilder sb = new StringBuilder(I18n.t("<html><body style='width: 480px'><b>Erreurs lors de la soumission :</b><br><br>"));
                        for (String e : errors) sb.append("• ").append(e).append("<br>");
                        sb.append("</body></html>");
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
        // writeTagsSafe() (donc showError indirectement) est désormais aussi appelé depuis le
        // thread d'arrière-plan de l'édition en lot (voir applyDetail()) — JOptionPane hors EDT
        // n'est pas garanti thread-safe côté Swing.
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showError(msg));
            return;
        }
        JOptionPane.showMessageDialog(this, msg, I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
    }

    /** Résumé structuré des échecs d'une édition en lot (voir applyDetail()) — une seule ligne
     *  "fichier : message" bout à bout par showError() devenait illisible dès que le message
     *  d'exception était long ou qu'il y avait plusieurs fichiers : impossible de voir d'un coup
     *  d'œil où finit le nom de fichier et où commence la raison. Ici chaque entrée occupe sa
     *  propre ligne visuelle (nom, puis raison en retrait sur la ligne suivante), dans une zone
     *  défilante bornée en taille pour qu'un gros lot n'ouvre pas un popup plus grand que l'écran. */
    private void showBatchSaveErrors(int saved, int total, List<String[]> errors) {
        JLabel header = new JLabel(I18n.t(
                "<html><b>%d / %d fichier(s) sauvegardés</b> — %d en erreur :</html>",
                saved, total, errors.size()));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

        StringBuilder sb = new StringBuilder();
        for (String[] err : errors) {
            sb.append("▸ ").append(err[0]).append('\n')
              .append("    → ").append(err[1]).append("\n\n");
        }
        JTextArea area = new JTextArea(sb.toString().stripTrailing());
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(560, Math.min(320, 40 + errors.size() * 45)));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        JOptionPane.showMessageDialog(this, panel, I18n.t("Erreurs de sauvegarde"), JOptionPane.ERROR_MESSAGE);
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
