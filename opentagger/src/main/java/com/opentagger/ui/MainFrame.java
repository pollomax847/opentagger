package com.opentagger.ui;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    // Source unique : ThemeManager le réapplique sur CHAQUE thème (Component.accentColor), les
    // composants dessinés à la main ci-dessous l'utilisent directement — deux constantes séparées
    // auraient fini par diverger au premier changement de teinte.
    private static final Color ACCENT         = ThemeManager.ACCENT; // teal
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

    /** Accès en lecture seule à la table chargée — utilisé par SettingsDialog pour la détection
     *  automatique de séries de compilations (2026-08-17). */
    public List<FileEntry> allEntries() { return tableModel.allEntries(); }

    /** Retire ces fichiers de la liste affichée (sans toucher au disque) — à appeler sur l'EDT. */
    public void removeFromList(java.util.Set<FileEntry> toRemove) { tableModel.removeEntries(toRemove); }
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
    // Taille totale des fichiers chargés (barre d'état, à gauche de la RAM) — voir refreshStats().
    private JLabel lblLibrarySize;

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
        COMPILATION_CLUSTER, VIDEO_RECOVERY, LISTENBRAINZ_SYNC, LASTFM_SYNC, DUPLICATE_DETECT
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
    // Garde anti-réentrance pour flushPendingCompilationMatches() — sans elle, deux chaînes
    // Enregistrer→Complétion→Compilations qui atteignaient "la fin" à quelques instants d'écart
    // pouvaient chacune ouvrir SA PROPRE fenêtre modale (CompilationMatchDialog.setVisible(true)
    // continue de pomper les autres événements EDT pendant qu'elle bloque, donc un second appel
    // différé pouvait s'exécuter avant que le premier n'ait fini) — repéré en direct 2026-09-01,
    // deux fenêtres de revue ouvertes simultanément.
    private boolean compilationMatchDialogOpen = false;

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

    /** Public (2026-08-17) pour que les dialogues réutilisent ce cache partagé au lieu de
     *  recharger tout scan_cache (des centaines de milliers de lignes) à chaque tentative d'import
     *  — exactement le motif qui avait déjà causé un OutOfMemoryError par le passé (voir commentaire
     *  ci-dessus) et qui provoquait à nouveau une fuite mémoire native constatée en direct ce
     *  soir (plusieurs Go de croissance sur quelques tentatives d'import). */
    public java.util.Map<String, MetadataCache.ScanCacheEntry> acquireScanCacheMap(MetadataCache cache) {
        synchronized (scanCacheMapLock) {
            if (sharedScanCacheMap == null) sharedScanCacheMap = cache.loadScanCacheMap();
            scanCacheMapRefCount++;
            return sharedScanCacheMap;
        }
    }

    public void releaseScanCacheMap() {
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
        // Thème enregistré (ui.theme) + retouches maison — voir ThemeManager, qui remplace le
        // FlatDarkLaf.setup() codé en dur d'avant le 2026-09-07 (aucun choix possible jusque-là).
        ThemeManager.applyStartup();
        SwingUtilities.invokeLater(() ->
            SplashScreen.show(() -> SwingUtilities.invokeLater(() -> {
                MainFrame frame = new MainFrame();
                frame.setVisible(true);
                // Premier démarrage : propose de saisir les clés API (modale, une seule fois).
                FirstRunApiKeysDialog.showIfFirstRun(frame);
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
        com.opentagger.SelfHealthMonitor.start();
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
                // Repli supplémentaire (2026-08-20, retour utilisateur : des fichiers déjà taggués
                // repassaient "en attente" pendant un run à fort débit) : la garde ci-dessus compare
                // fe.currentPath, mais SON écriture (SaveWorker, après le renommage physique déjà
                // effectué sur disque) passe elle aussi par un invokeLater() distinct — sous forte
                // charge (EDT engorgée par des dizaines de threads de taguage), rien ne garantit que
                // CE callback-ci s'exécute APRÈS celui de SaveWorker, même si le renommage physique
                // l'a chronologiquement précédé. Résultat : la garde ne voit pas encore le nouveau
                // currentPath, et une DEUXIÈME entrée vierge se crée pour le même fichier physique
                // tout juste tagué. Plutôt que de comparer des chemins en mémoire qui peuvent être
                // temporairement désynchronisés, on vérifie D'ABORD (en arrière-plan, avant de créer
                // quoi que ce soit) le marqueur écrit DANS le fichier lui-même — même repli déjà
                // fiable utilisé par loadDirectory()/loadSingleFile() pour ce même problème de fond
                // (fichier déplacé hors du pipeline de renommage suivi en mémoire).
                new SwingWorker<com.opentagger.model.TagInfo, Void>() {
                    @Override protected com.opentagger.model.TagInfo doInBackground() { return readTags(f); }
                    @Override protected void done() {
                        com.opentagger.model.TagInfo ti;
                        try { ti = get(); } catch (Exception ex) { ti = new com.opentagger.model.TagInfo(); }
                        boolean alreadyTagged = !ti.taggedDate.isBlank() || !ti.recordingMbid.isBlank();
                        if (alreadyTagged) {
                            // Quasi certainement le fichier qu'une sauvegarde vient de renommer ici
                            // (course décrite ci-dessus) : ne rien créer — l'entrée d'origine verra
                            // son propre currentPath se mettre à jour dès que SaveWorker traite sa
                            // file d'attente EDT, sans qu'un doublon ne soit jamais apparu entre
                            // temps dans l'interface.
                            return;
                        }
                        // Re-vérifier la garde anti-doublon : le temps de cette lecture en arrière-
                        // plan, une autre voie a pu ajouter ce même chemin.
                        for (com.opentagger.model.FileEntry fe : tableModel.allEntries()) {
                            if (fe.file.toPath().equals(fp) || fp.equals(fe.currentPath)) return;
                        }
                        com.opentagger.model.FileEntry e = new com.opentagger.model.FileEntry(f, new com.opentagger.model.TagInfo());
                        // Déterminer la racine : trouver le scanRoot du dossier parent
                        Path parent = p.getParent();
                        for (com.opentagger.model.FileEntry ex : tableModel.allEntries()) {
                            if (ex.scanRoot != null && parent.startsWith(ex.scanRoot)) { e.scanRoot = ex.scanRoot; break; }
                        }
                        e.current = ti;
                        tableModel.add(e);
                        refreshStats();
                        setStatus(I18n.t("Nouveau fichier détecté : %s", f.getName()));
                    }
                }.execute();
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

        autoInstallFpcalcIfMissing();
        startSaveBacklogMeter();
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
                // entryAtViewRow()/entriesAtViewRow() (pas tableModel.remove(table.
                // convertRowIndexToModel(r)), même bug de fond qu'applyDetail()/transcodeFiles() en
                // vue arborescence — voir leurs commentaires) : trouvé en direct 2026-09-01. Suppr
                // sur une sélection en vue Arborescence par album pouvait retirer de la liste un
                // fichier totalement différent de celui affiché comme sélectionné (jamais le fichier
                // réel sur disque ici, juste la ligne — mais quand même le mauvais retrait).
                // Dédoublonné + trié en indices décroissants avant suppression, sinon retirer un
                // index bas décale tous les index plus hauts encore à traiter.
                java.util.Set<FileEntry> toRemove = new java.util.LinkedHashSet<>();
                for (int row : table.getSelectedRows()) toRemove.addAll(entriesAtViewRow(row));
                List<Integer> indices = new ArrayList<>();
                for (FileEntry fe : toRemove) {
                    int idx = tableModel.indexOf(fe);
                    if (idx >= 0) indices.add(idx);
                }
                indices.sort(java.util.Comparator.reverseOrder());
                for (int idx : indices) tableModel.remove(idx);
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
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { transcodeFiles(); }
        });

        undoManager.addListener(this::updateUndoButtons);
    }

    // ── Menu bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.add(buildMenuFichier());
        mb.add(buildMenuEdition());
        mb.add(buildMenuTagger());
        // "Bibliothèque" promue au premier niveau (2026-09-07) : elle contenait à elle seule 3
        // sous-sous-menus (Nettoyage/Rapports/Import-Export) enterrés sous "Outils", soit deux
        // clics avant de voir la moindre action — un poids comparable à "Tagger", pas à une
        // rubrique d'outils divers. Placée entre Tagger et Affichage : les deux menus qui AGISSENT
        // sur le contenu d'abord, les menus "méta" (vue, outils divers) ensuite.
        mb.add(buildMenuBibliotheque());
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
        // Séparateur volontaire (2026-09-07) : "Vider la liste" (juste la vue en mémoire, sans
        // conséquence) et "Vider la corbeille" (suppression définitive, irréversible) commencent
        // par le même mot et n'avaient rien pour les distinguer visuellement malgré des risques
        // opposés.
        m.add(mitem(I18n.t("Vider la corbeille de l'application…"), null, e -> emptyApplicationTrash()));
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

    /** Dernier geste, VOLONTAIREMENT manuel (jamais automatique) — voir TrashHelper.
     *  emptyFallbackTrash() : jusqu'à ce correctif, aucun moyen depuis l'appli de vider
     *  ~/.opentagger/corbeille/, seulement via le gestionnaire de fichiers du système (retour
     *  utilisateur direct, 2026-09-07 : "depuis opentagger... a acun moment tu as mis vider la
     *  corbeille dans fichier"). */
    private void emptyApplicationTrash() {
        com.opentagger.TrashHelper.TrashStats stats = com.opentagger.TrashHelper.fallbackTrashStats();
        if (stats.fileCount() == 0) {
            JOptionPane.showMessageDialog(this,
                I18n.t("La corbeille de l'application est déjà vide."),
                I18n.t("Vider la corbeille"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
            I18n.t("Supprimer DÉFINITIVEMENT les %d fichier(s) (%.1f Mo) de la corbeille de "
                 + "l'application ?\n\nAucun moyen de les récupérer après ça.",
                 stats.fileCount(), stats.totalBytes() / 1_000_000.0),
            I18n.t("Vider la corbeille"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        setStatus(I18n.t("Suppression définitive de la corbeille de l'application…"));
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() {
                try { com.opentagger.TrashHelper.emptyFallbackTrash(); return true; }
                catch (Exception ex) { return false; }
            }
            @Override protected void done() {
                boolean success;
                try { success = get(); } catch (Exception ex) { success = false; }
                String msg = success
                    ? I18n.t("Corbeille de l'application vidée (%d fichier(s), %.1f Mo).",
                             stats.fileCount(), stats.totalBytes() / 1_000_000.0)
                    : I18n.t("Erreur pendant le vidage de la corbeille.");
                setStatus(msg);
                JOptionPane.showMessageDialog(MainFrame.this, msg,
                    I18n.t("Résultat"), success ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.ERROR_MESSAGE);
            }
        }.execute();
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

        // entriesAtViewRow() — pas tableModel.get(table.convertRowIndexToModel(r)), voir le
        // commentaire détaillé de startTagging() pour pourquoi cette dernière forme est fausse dès
        // que la vue arborescence est active (bug repéré en direct 2026-08-24, cette méthode
        // précise étant justement "Rafraîchir tags + pochette (sélection)" — la fonctionnalité que
        // l'utilisateur décrivait comme "corriger tous les fichiers sélectionnés").
        java.util.Set<com.opentagger.model.FileEntry> targetSet = new java.util.LinkedHashSet<>();
        for (int r : rows) targetSet.addAll(entriesAtViewRow(r));
        java.util.List<com.opentagger.model.FileEntry> targets = new java.util.ArrayList<>();
        for (com.opentagger.model.FileEntry e : targetSet) {
            com.opentagger.model.TagInfo ti = e.activeTags();
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
            final com.opentagger.DiscogsClient     discogs  = new com.opentagger.DiscogsClient();
            final com.opentagger.TagWriter         writer   = new com.opentagger.TagWriter();
            // Partagée entre tous les threads du pool ci-dessous : MetadataCache est déjà
            // synchronized (sauf close()/purgeExpired(), pas appelés ici pendant le traitement),
            // même pattern que TaggingWorker/AlbumCompletionWorker. Sert aussi de cache réseau
            // façon Picard pour les pochettes CAA/FanArt.
            final com.opentagger.MetadataCache     cache    = new com.opentagger.MetadataCache();
            final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();

            @Override protected Void doInBackground() throws Exception {
                int threads = Math.max(1, com.opentagger.Config.get().batchThreads());
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

                for (com.opentagger.model.FileEntry e : targets) {
                    if (isCancelled()) break;
                    futures.add(pool.submit(() -> processOne(e, new com.opentagger.MusicBrainzClient())));
                }

                // WorkerHub.awaitAll() au lieu d'une boucle f.get() nue (2026-09-19, audit dédié
                // "blocages silencieux") — voir AlbumCompletionWorker pour le même correctif et son
                // pourquoi complet.
                WorkerHub.awaitAll(pool, futures, WorkerHub.defaultFutureTimeoutSec());
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
                                // Sidecar cover.jpg — jusqu'à ce correctif, cette action ne mettait à
                                // jour QUE le tag embarqué, jamais le fichier à côté (voir la même
                                // logique dans TagEnrichment.saveEntry) : une pochette annexe déjà
                                // fausse restait fausse même après ce "rafraîchissement". REPLACE_
                                // EXISTING volontairement inconditionnel ici (contrairement à
                                // saveEntry, qui ne l'écrit que si absent) — c'est tout le but d'un
                                // rafraîchissement manuel demandé explicitement par l'utilisateur.
                                if (com.opentagger.Config.get().coverSaveToFile()) {
                                    String fname = com.opentagger.Config.get().coverFilename();
                                    String ext = img.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
                                    java.nio.file.Path dest = e.file.toPath().resolveSibling(fname + ext);
                                    java.nio.file.Files.copy(img, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            } finally {
                                com.opentagger.TagEnrichment.discardTemporaryCover(img);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // 3. Photo d'artiste fraîche — jamais couverte par cette action avant ce correctif
                // (2026-09-06), alors que "photo d'artiste erronée qui ne se corrige jamais toute
                // seule" (fichier déjà présent = jamais retéléchargé, voir TagEnrichment.saveEntry)
                // est exactement le problème qui a motivé son ajout ce soir (incident réel : Black
                // Pumas affichant la photo de Robin Williams côté Navidrome). FanArt.tv (MBID précis)
                // en premier, repli Discogs par nom EXACT (voir DiscogsClient.
                // downloadArtistPhotoFallback, abstention sur homonymes) si FanArt n'a rien.
                if (com.opentagger.Config.get().artistPhotoEnabled()) {
                    try {
                        com.opentagger.model.TagInfo forPhoto = new com.opentagger.model.TagInfo();
                        forPhoto.artistMbid = current.artistMbid;
                        forPhoto.artist     = current.artist;
                        forPhoto.albumArtist = current.albumArtist;
                        java.nio.file.Path photo = fanArt.downloadArtistPhoto(forPhoto, cache);
                        if (photo == null) photo = discogs.downloadArtistPhotoFallback(forPhoto, cache);
                        if (photo == null) photo = new com.opentagger.DeezerClient().downloadArtistPhoto(forPhoto, cache);
                        if (photo != null) {
                            try {
                                String fname = com.opentagger.Config.get().artistPhotoFilename();
                                String ext = photo.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
                                java.nio.file.Path track = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath();
                                java.nio.file.Path dest = com.opentagger.TagEnrichment.artistPhotoFolder(track, forPhoto)
                                        .resolve(fname + ext);
                                java.nio.file.Files.copy(photo, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } finally {
                                try { java.nio.file.Files.deleteIfExists(photo); } catch (Exception ignored) {}
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
            // Même correctif que le raccourci clavier Suppr (voir son commentaire) — vue arborescence.
            java.util.Set<FileEntry> toRemove = new java.util.LinkedHashSet<>();
            for (int row : table.getSelectedRows()) toRemove.addAll(entriesAtViewRow(row));
            List<Integer> indices = new ArrayList<>();
            for (FileEntry fe : toRemove) {
                int idx = tableModel.indexOf(fe);
                if (idx >= 0) indices.add(idx);
            }
            indices.sort(java.util.Comparator.reverseOrder());
            for (int idx : indices) tableModel.remove(idx);
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

    /** Compte toutes les 2 s les fichiers identifiés en attente d'enregistrement, pour le frein de TaggingWorker (voir SaveBacklog). */
    private void startSaveBacklogMeter() {
        javax.swing.Timer timer = new javax.swing.Timer(2000, e -> {
            int n = 0;
            for (FileEntry fe : tableModel.allEntries()) if (fe.selected && fe.status == FileEntry.Status.IDENTIFIED) n++;
            SaveBacklog.setUnsaved(n);
        });
        timer.setRepeats(true);
        timer.start();
    }

    /** fpcalc (empreinte AudioID pour AcoustID) est livré avec l'installateur ; s'il manque quand même (copie portable, Linux),
     *  on le télécharge une fois en arrière-plan plutôt que de laisser AcoustID silencieusement inactif. Coupable via
     *  {@code acoustid.auto_install_fpcalc=false}. */
    private void autoInstallFpcalcIfMissing() {
        if (!Config.get().bool("acoustid.auto_install_fpcalc", true)) return;
        Thread t = new Thread(() -> {
            try {
                if (com.opentagger.FpcalcInstaller.isAvailable()) return;
                String path = com.opentagger.FpcalcInstaller.download(msg -> {});
                SwingUtilities.invokeLater(() -> setStatus(I18n.t("fpcalc installé automatiquement : %s", path)));
            } catch (Exception ex) {
                System.out.println("[OT] fpcalc : installation automatique impossible — " + ex.getMessage());
            }
        }, "fpcalc-auto-install");
        t.setDaemon(true);
        t.start();
    }

    // ── Niveau d'automatisation (Manuel / Assisté / Automatique) ────────────────────────────────────────────────────
    private JCheckBoxMenuItem chkAutoPlayCounts;
    private JRadioButtonMenuItem[] rbCompletionItems;
    private final java.util.Map<com.opentagger.AutomationMode, JRadioButtonMenuItem> rbAutomation = new java.util.EnumMap<>(com.opentagger.AutomationMode.class);
    private JMenuItem miAutomationCustom;

    private static com.opentagger.AutomationMode currentAutomationMode() {
        Config c = Config.get();
        c.postTagCompletion(); // migre une fois les anciennes clés avant la lecture brute
        return com.opentagger.AutomationMode.detect(key ->
                "tagging.post_tag_completion".equals(key) ? c.postTagCompletion().name() : c.str(key, ""));
    }

    private void addAutomationModeItems(JMenu options) {
        com.opentagger.AutomationMode now = currentAutomationMode();
        ButtonGroup group = new ButtonGroup();
        String[][] labels = {
            {"MANUAL",    I18n.t("Manuel"),      I18n.t("Rien ne démarre seul : vous lancez le taguage puis l'enregistrement.")},
            {"ASSISTED",  I18n.t("Assisté"),     I18n.t("Le taguage démarre seul après un scan et complète les champs manquants ; vous validez l'enregistrement.")},
            {"AUTOMATIC", I18n.t("Automatique"), I18n.t("Tout s'enchaîne : taguage, enregistrement, albums, compilations, ré-identification, compteurs d'écoute, pochettes et photos manquantes.")}};
        for (String[] l : labels) {
            com.opentagger.AutomationMode mode = com.opentagger.AutomationMode.valueOf(l[0]);
            JRadioButtonMenuItem rb = new JRadioButtonMenuItem(l[1], now == mode);
            rb.setToolTipText(l[2]);
            rb.addActionListener(e -> applyAutomationMode(mode));
            group.add(rb);
            rbAutomation.put(mode, rb);
            options.add(rb);
        }
        miAutomationCustom = new JMenuItem(I18n.t("Personnalisé (réglages « Avancé »)"));
        miAutomationCustom.setEnabled(false);
        miAutomationCustom.setVisible(now == null);
        options.add(miAutomationCustom);
        options.addSeparator();
    }

    private void applyAutomationMode(com.opentagger.AutomationMode mode) {
        if (mode == com.opentagger.AutomationMode.AUTOMATIC) {
            int ok = JOptionPane.showConfirmDialog(this,
                I18n.t("<html>Le mode Automatique enchaîne tout sans rien demander :<br>"
                     + "taguage après chaque scan, enregistrement, recherche des pistes d'album manquantes,<br>"
                     + "regroupement par compilations, ré-identification par empreinte audio<br>"
                     + "(elle efface le cache des fichiers restés « Non identifié »), compteurs d'écoute<br>"
                     + "et pochettes/photos manquantes.<br><br>Passer en mode Automatique ?</html>"),
                I18n.t("Mode Automatique"), JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) { syncAutomationRadios(); return; }
        }
        for (java.util.Map.Entry<String, String> e : mode.settings().entrySet()) Config.get().set(e.getKey(), e.getValue());
        Config.get().set(com.opentagger.AutomationMode.KEY_MODE, mode.name());
        Config c = Config.get();
        chkAutoTagOnScan.setSelected(c.autoTagOnScan());
        chkAutoSaveEnabled.setSelected(c.autoSaveEnabled());
        if (btnSaveAll != null) btnSaveAll.setVisible(!c.autoSaveEnabled());
        chkAutoGroupCompilations.setSelected(c.bool("tagging.auto_group_compilations", false));
        chkAutoReidentifyUnmatched.setSelected(c.bool("tagging.auto_reidentify_unmatched", false));
        chkAutoPlayCounts.setSelected(c.autoSyncPlayCounts());
        if (chkMoveSkippedMenu != null) chkMoveSkippedMenu.setSelected(c.skippedMoveEnabled());
        if (chkMoveDurationMismatchMenu != null) chkMoveDurationMismatchMenu.setSelected(c.durationMismatchMoveEnabled());
        Config.PostTagCompletion pc = c.postTagCompletion();
        for (int i = 0; i < rbCompletionItems.length; i++) rbCompletionItems[i].setSelected(i == pc.ordinal());
        syncAutomationRadios();
        setStatus(I18n.t("Niveau d'automatisation : %s", rbAutomation.get(mode).getText()));
    }

    /** Recale les boutons du niveau sur les réglages réels (un réglage « Avancé » modifié à la main = « Personnalisé »). */
    private void syncAutomationRadios() {
        com.opentagger.AutomationMode now = currentAutomationMode();
        for (java.util.Map.Entry<com.opentagger.AutomationMode, JRadioButtonMenuItem> e : rbAutomation.entrySet())
            e.getValue().setSelected(e.getKey() == now);
        if (miAutomationCustom != null) miAutomationCustom.setVisible(now == null);
        if (now != null) Config.get().set(com.opentagger.AutomationMode.KEY_MODE, now.name());
    }

    private static void watchButtons(JMenu menu, java.awt.event.ActionListener l) {
        for (java.awt.Component c : menu.getMenuComponents()) {
            if (c instanceof JMenu sub) watchButtons(sub, l);
            else if (c instanceof AbstractButton b) b.addActionListener(l);
        }
    }

    private void watchAdvancedForAutomation(JMenu advanced) {
        java.awt.event.ActionListener l = e -> SwingUtilities.invokeLater(this::syncAutomationRadios);
        watchButtons(advanced, l);
        for (JRadioButtonMenuItem rb : rbCompletionItems) rb.addActionListener(l);
    }

    private JMenu buildMenuTagger() {        JMenu m = new JMenu(I18n.t("Tagger"));
        m.setMnemonic('T');
        m.add(mitem(I18n.t("Tout tagger (cochés)"),    "F6",  e -> startTagging(false)));
        m.add(mitem(I18n.t("Tagger la sélection"),     "F7",  e -> startTagging(true)));
        m.add(mitem(I18n.t("Enregistrer tout (cochés)"), "F8", e -> saveAll()));
        m.addSeparator();
        // Les 8 réglages (cases à cocher et sous-menus) étaient mélangés aux actions du menu Tagger :
        // regroupés ici dans UN sous-menu (2026-10-03, « trop d'options gâchent l'application »).
        // Les actions à raccourci clavier (F6/F7/F8, Ctrl+R/G/L/K/T) restent au premier niveau.
        JMenu options = new JMenu(I18n.t("Options de taguage"));
        m.add(options);
        JMenu advanced = new JMenu(I18n.t("Avancé"));
        addAutomationModeItems(options);
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
        options.add(chkForceAcoustId);

        // Basculement rapide avant un gros rattrapage de bibliothèque — voir Config.setExpressMode()
        // pour le détail de ce qui est coupé/restauré et pourquoi.
        StayOpenCheckBoxMenuItem chkExpressMode = new StayOpenCheckBoxMenuItem(
            I18n.t("Mode Express (coupe photo d'artiste / paroles / ReplayGain)"));
        chkExpressMode.setSelected(Config.get().expressModeActive());
        chkExpressMode.setToolTipText(I18n.t(
            "Désactive temporairement les 3 étapes les plus lentes par fichier, pour parcourir vite "
            + "un gros arriéré. L'état précédent de chaque réglage est restauré en repassant en mode "
            + "Complet. Genre/bio/identification ne sont pas affectés."));
        chkExpressMode.addActionListener(e -> {
            boolean on = chkExpressMode.isSelected();
            Config.get().setExpressMode(on);
            setStatus(on
                ? I18n.t("Mode Express activé — photo d'artiste, paroles et ReplayGain désactivés.")
                : I18n.t("Mode Complet restauré — réglages précédents rétablis."));
        });
        options.add(chkExpressMode);

        // Fusion 2026-07-16 des anciennes cases "Compléter aussi les fichiers tagués mais
        // incomplets" et "Compléter les albums automatiquement après le taguage" — elles
        // s'enchaînaient déjà l'une après l'autre (voir onTaggingDone()), mais deux cases séparées
        // au nom proche pour une seule intention ("finir le taguage automatiquement") créaient de
        // la confusion. Un seul réglage à 3 états (Config.PostTagCompletion), qui migre une fois
        // depuis les deux anciennes clés pour ne pas changer silencieusement un choix déjà fait.
        JMenu completionMenu = new JMenu(I18n.t("Après le taguage"));
        ButtonGroup completionGroup = new ButtonGroup();
        Config.PostTagCompletion current = Config.get().postTagCompletion();
        rbCompletionItems = new JRadioButtonMenuItem[3];
        JRadioButtonMenuItem rbNone = new JRadioButtonMenuItem(I18n.t("Ne rien compléter automatiquement"));
        JRadioButtonMenuItem rbFields = new JRadioButtonMenuItem(I18n.t("Compléter les champs manquants"));
        JRadioButtonMenuItem rbFieldsAlbums = new JRadioButtonMenuItem(I18n.t("Compléter les champs + rechercher les pistes d'album manquantes"));
        rbNone.setSelected(current == Config.PostTagCompletion.NONE);
        rbFields.setSelected(current == Config.PostTagCompletion.FIELDS);
        rbFieldsAlbums.setSelected(current == Config.PostTagCompletion.FIELDS_AND_ALBUMS);
        rbNone.addActionListener(e -> Config.get().setPostTagCompletion(Config.PostTagCompletion.NONE));
        rbFields.addActionListener(e -> Config.get().setPostTagCompletion(Config.PostTagCompletion.FIELDS));
        rbFieldsAlbums.addActionListener(e -> Config.get().setPostTagCompletion(Config.PostTagCompletion.FIELDS_AND_ALBUMS));
        rbCompletionItems[0] = rbNone; rbCompletionItems[1] = rbFields; rbCompletionItems[2] = rbFieldsAlbums;
        for (JRadioButtonMenuItem rb : rbCompletionItems) {
            completionGroup.add(rb);
            completionMenu.add(rb);
        }
        advanced.add(completionMenu);
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
        advanced.add(chkAutoGroupCompilations);

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
        advanced.add(chkAutoReidentifyUnmatched);

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
        advanced.add(chkAutoTagOnScan);

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
        advanced.add(chkAutoSaveEnabled);

        // Automatisation de « Outils → Re-traitement → Synchroniser les compteurs d'écoute » (qui reste
        // disponible à la main) : à la fin d'un scan, au plus une fois par 24 h — voir scheduleAutoPlayCountSync().
        chkAutoPlayCounts = new StayOpenCheckBoxMenuItem(
                I18n.t("Synchroniser les compteurs d'écoute automatiquement après un scan (1 fois par jour)"));
        chkAutoPlayCounts.setSelected(Config.get().autoSyncPlayCounts());
        chkAutoPlayCounts.addActionListener(e ->
                Config.get().set("playcounts.auto_sync", String.valueOf(chkAutoPlayCounts.isSelected())));
        advanced.add(chkAutoPlayCounts);
        options.add(advanced);

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
        advanced.add(moveMenu);
        watchAdvancedForAutomation(advanced);

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
        // Fusionné (2026-09-02) : un seul item, transcodeFiles() choisit lui-même sélection vs
        // tout — voir son commentaire. Remplace les deux anciens items "…les fichiers"/"…la
        // sélection" qui appelaient en réalité TOUJOURS la même branche "tout" quel que soit le
        // bouton cliqué.
        m.add(mitem(I18n.t("Transcoder…"),   "Ctrl+T", e -> transcodeFiles()));
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

        // Thème : appliqué à chaud, retenu d'une session à l'autre (ui.theme) — voir ThemeManager.
        m.addSeparator();
        m.add(ThemeManager.buildMenu());
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

        // Correction manuelle : aplati directement dans Outils (2026-09-07, retour utilisateur
        // "trop d'options") — un sous-menu à seulement 2 entrées coûtait un clic sans rien cacher
        // de plus. "Marquer comme déjà taggée" rejoint ce groupe, sorti de "Re-traitement" : seule
        // action de ce sous-menu qui ne corrige RIEN (bookkeeping sur un fichier déjà bon), elle
        // n'avait rien à faire à côté des 4 autres qui réparent une identification cassée.
        m.add(mitem(I18n.t("Correspondance manuelle…"),"Ctrl+M",  e -> openMatchDialog()));
        m.add(mitem(I18n.t("Gérer la pochette…"),      null,      e -> openCoverDialog()));
        m.add(mitem(I18n.t("Marquer la sélection comme déjà taggée…"), null, e -> markAsAlreadyTagged()));
        m.addSeparator();

        JMenu retraitement = new JMenu(I18n.t("Re-traitement"));
        retraitement.add(mitem(I18n.t("Forcer le re-taguage…"),    null,      e -> forceRetag()));
        retraitement.add(mitem(I18n.t("Ré-identifier par empreinte audio (Non identifiés)…"), null, e -> reidentifyUnmatched()));
        retraitement.add(mitem(I18n.t("Essayer Bandcamp (Non identifiés)…"), null, e -> tryBandcampOnUnmatched()));
        retraitement.add(mitem(I18n.t("Nettoyer les noms (Non identifiés)…"), null, e -> cleanNames()));
        retraitement.add(mitem(I18n.t("Compléter les pochettes et photos manquantes"), null, e -> completeArtworkFromMenu()));
        retraitement.add(mitem(I18n.t("Synchroniser les compteurs d'écoute (ListenBrainz + Last.fm)…"), null, e -> syncPlayCounts()));
        m.add(retraitement);

        JMenu musicbrainz = new JMenu("MusicBrainz");
        musicbrainz.add(mitem(I18n.t("Compte MusicBrainz…"), null,         e -> MbAccountDialog.open(this)));
        musicbrainz.add(mitem(I18n.t("Modifier sur MusicBrainz"),null,      e -> openMbEditPage()));
        musicbrainz.add(mitem(I18n.t("Contribuer à MusicBrainz…"), null,   e -> openMbContribute()));
        musicbrainz.add(mitem(I18n.t("Soumettre fingerprint AcoustID"), null, e -> submitAcoustId()));
        m.add(musicbrainz);

        m.addSeparator();
        m.add(mitem(I18n.t("Vérifier les mises à jour…"), null,   e -> checkForUpdates(true)));
        m.add(mitem(I18n.t("À propos d'OpenTagger…"),     null,   e -> showAboutDialog()));
        return m;
    }

    /**
     * Menu "Bibliothèque" — promu au premier niveau (2026-09-07, voir buildMenuBar()) après avoir
     * vécu comme sous-menu d'"Outils". Découpé lui-même en sous-menus (2026-09-02, retour
     * utilisateur "c'est le bordel", réorganisé 2026-09-07) — même motif déjà validé ailleurs (voir
     * buildSubmenuSelection() : "7 items à plat, trop d'un coup") : 16 items à plat avait largement
     * dépassé ce seuil au fil des fonctionnalités ajoutées.
     */
    private JMenu buildMenuBibliotheque() {
        JMenu bibliotheque = new JMenu(I18n.t("Bibliothèque"));
        bibliotheque.setMnemonic('B');

        // "Nettoyage" : uniquement des actions DIRECTES (pas de fenêtre de revue) — voir "Rapports"
        // juste en dessous pour les 5 fenêtres "filtrer une pile à problèmes puis agir dessus", qui
        // vivaient ici de façon incohérente pour 2 d'entre elles avant ce correctif (2026-09-07).
        JMenu nettoyage = new JMenu(I18n.t("Nettoyage"));
        nettoyage.add(mitem(I18n.t("Supprimer les fichiers illisibles…"), null, e -> deleteErrorFiles()));
        nettoyage.add(mitem(I18n.t("Réparer les fichiers audio mal nommés…"), null, e -> repairMisnamedFiles()));
        nettoyage.add(mitem(I18n.t("Nettoyer les dossiers orphelins…"), null, e -> cleanOrphanFolders()));
        nettoyage.add(mitem(I18n.t("Supprimer la sélection (corbeille)…"), null, e -> deleteSelectedFiles()));
        bibliotheque.add(nettoyage);

        // Les 5 fenêtres "filtrer une pile de fichiers à problèmes, agir dessus" regroupées ici
        // (2026-09-07 — Détecter les doublons/Revue des durées incohérentes vivaient jusqu'ici dans
        // "Nettoyage" sans lien avec les 3 autres, malgré la même forme). Le seul item du sous-menu
        // qui n'ouvre PAS de fenêtre — juste un export JSON silencieux — est séparé et renommé pour
        // ne plus se lire à tort comme une 6e fenêtre de revue.
        JMenu rapports = new JMenu(I18n.t("Rapports"));
        rapports.add(mitem(I18n.t("Détecter les doublons…"),  null,      e -> detectDuplicates()));
        rapports.add(mitem(I18n.t("Revue rapide (notation)…"), "ctrl R", e -> openQuickReview()));
        // Une seule entrée, deux onglets (durées incohérentes + audio ↔ tags) : « trop d'outils dans l'appli »
        // (retour utilisateur, 2026-09-20) — voir SuspectFilesReviewDialog.
        rapports.add(mitem(I18n.t("Revue des fichiers suspects…"), null,
            e -> SuspectFilesReviewDialog.show(this, tableModel)));
        rapports.add(mitem(I18n.t("Historique de taguage…"),  null,      e -> new HistoryDialog(this).setVisible(true)));
        // Une seule entrée pour tous les rapports chiffrés (Non identifiés, Complétude, Compilations
        // restaurées) — voir LibraryReportsDialog (2026-10-03, « trop d'options »).
        rapports.add(mitem(I18n.t("Rapports de la bibliothèque…"), null,
            e -> LibraryReportsDialog.open(this, tableModel)));
        rapports.addSeparator();
        rapports.add(mitem(I18n.t("Exporter un rapport JSON (diagnostic)…"), null,
            e -> exportDiagnosticReport()));
        bibliotheque.add(rapports);

        JMenu importExport = new JMenu(I18n.t("Import / Export"));
        importExport.add(mitem(I18n.t("Tagger comme podcast…"),    null,      e -> openPodcastDialog()));
        importExport.add(mitem(I18n.t("Récupérer l'audio des vidéos non reconnues…"), null, e -> openVideoRecoveryDialog()));
        importExport.add(mitem(I18n.t("Importer un CD…"), null, e -> new CdImportDialog(this).setVisible(true)));
        importExport.add(mitem(I18n.t("Coller une URL Bandcamp…"), null, e -> openBandcampDialog()));
        bibliotheque.add(importExport);

        return bibliotheque;
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
        // Le VRAI logo (AppIcon : étiquette + double croche), pas un monogramme dessiné à la main —
        // jusqu'au 2026-09-08 l'en-tête peignait un carré teal avec "OT" dedans pendant qu'AppIcon,
        // déjà utilisée pour l'icône de fenêtre/barre des tâches, existait à côté sans être
        // affichée nulle part dans l'interface. Deux identités visuelles pour la même appli, dont
        // une qui ressemblait à un placeholder (retour utilisateur : "le logo est pas beau").
        // glyph() et non at() : la variante sans carré de fond — dans une barre d'outils, le carré
        // arrondi plein de l'icône de fenêtre se lit comme un autocollant posé par-dessus.
        JLabel logo = new JLabel(new ImageIcon(AppIcon.glyph(30, ACCENT)));
        logo.setBorder(new EmptyBorder(6, 8, 6, 2));
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
        new String[]{"submitAcoustId",     I18n.t("Soumettre fingerprint AcoustID")},
        new String[]{"matchDialog",        I18n.t("Correspondance manuelle")},
        new String[]{"coverDialog",        I18n.t("Gérer la pochette")},
        new String[]{"refreshMeta",        I18n.t("Rafraîchir tags + pochette + photo d'artiste")},
        new String[]{"forceRetag",         I18n.t("Forcer le re-taguage")},
        new String[]{"syncListenBrainz",   I18n.t("Synchroniser ListenBrainz")},
        new String[]{"syncLastFm",         I18n.t("Synchroniser Last.fm")},
        new String[]{"podcastDialog",      I18n.t("Tagger comme podcast")},
        new String[]{"detectDuplicates",   I18n.t("Détecter les doublons")},
        new String[]{"historyDialog",      I18n.t("Historique de taguage")},
        new String[]{"cdImport",           I18n.t("Importer un CD")}
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
                I18n.t("Transcoder la sélection, ou toute la bibliothèque si rien n'est sélectionné (Ctrl+T)"),
                () -> transcodeFiles()));
        list.add(new ToolbarAction("submitAcoustId", I18n.t("Soumettre fingerprint AcoustID"),
                I18n.t("Envoyer les empreintes AcoustID de la sélection, ou de toute la bibliothèque si rien n'est sélectionné"),
                this::submitAcoustId));
        list.add(new ToolbarAction("matchDialog", I18n.t("Correspondance manuelle"),
                I18n.t("Rechercher/choisir manuellement une correspondance MusicBrainz"), this::openMatchDialog));
        list.add(new ToolbarAction("coverDialog", I18n.t("Gérer la pochette"),
                I18n.t("Gérer la pochette du fichier sélectionné"), this::openCoverDialog));
        list.add(new ToolbarAction("refreshMeta", I18n.t("Rafraîchir tags + pochette + photo d'artiste"),
                I18n.t("Rafraîchir les tags, la pochette et la photo d'artiste de la sélection depuis "
                + "MusicBrainz/Discogs (utile pour corriger tout un album sélectionné d'un coup, ou une "
                + "image erronée)"), this::refreshSelectedMeta));
        list.add(new ToolbarAction("forceRetag", I18n.t("Forcer le re-taguage"),
                I18n.t("Remettre en PENDING et re-taguer"), this::forceRetag));
        list.add(new ToolbarAction("syncListenBrainz", I18n.t("Synchroniser ListenBrainz"),
                I18n.t("Récupérer le nombre d'écoutes ListenBrainz pour les fichiers tagués"), this::syncListenBrainz));
        list.add(new ToolbarAction("syncLastFm", I18n.t("Synchroniser Last.fm"),
                I18n.t("Récupérer le nombre d'écoutes Last.fm pour les fichiers tagués"), this::syncLastFm));
        list.add(new ToolbarAction("podcastDialog", I18n.t("Tagger comme podcast"),
                I18n.t("Ouvrir le dialogue de taguage podcast"), this::openPodcastDialog));
        list.add(new ToolbarAction("detectDuplicates", I18n.t("Détecter les doublons"),
                I18n.t("Détecter les fichiers en double"), this::detectDuplicates));
        list.add(new ToolbarAction("historyDialog", I18n.t("Historique de taguage"),
                I18n.t("Ouvrir l'historique de taguage"), () -> new HistoryDialog(this).setVisible(true)));
        list.add(new ToolbarAction("cdImport", I18n.t("Importer un CD"),
                I18n.t("Extraire un CD audio ou copier un CD de données"),
                () -> new CdImportDialog(this).setVisible(true)));
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


    private JButton headerBtn(String text, String tip, ToolbarIcon.Kind icon) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        // Relief 3D plus marqué (retour utilisateur, 2026-08-10) : le rendu FlatLaf par défaut
        // (bordure/dégradé très subtils) ne se voyait pas assez à son goût — bordure explicite +
        // fond légèrement plus clair que l'arrière-plan pour un bouton net, nettement "en relief".
        // Teintes dérivées du THÈME (2026-09-08) et non plus des hex figés d'un thème sombre :
        // depuis l'ajout du sélecteur de thèmes (ThemeManager), un "#3A3A3E" en dur donnait des
        // boutons anthracite sur fond blanc dès qu'on passait sur un thème clair. autoInverse fait
        // éclaircir sur fond sombre et assombrir sur fond clair, avec le même écart relatif.
        b.putClientProperty("FlatLaf.style",
            "foreground: $Label.foreground; borderWidth: 1; borderColor: $Component.borderColor; "
            + "background: lighten($Panel.background,7%,autoInverse); "
            + "hoverBackground: lighten($Panel.background,12%,autoInverse); "
            + "pressedBackground: darken($Panel.background,4%,autoInverse); arc: 6");
        if (icon != null) { b.setIcon(new ToolbarIcon(icon, iconColor())); b.setIconTextGap(7); }
        return b;
    }

    /** Couleur d'icône de barre d'outils, prise sur le thème courant — les icônes sont peintes une
     *  fois à la construction, donc un changement de thème à chaud les laisse à leur teinte
     *  d'origine jusqu'au prochain démarrage (le reste de l'interface, lui, suit immédiatement). */
    private static Color iconColor() {
        Color c = UIManager.getColor("Label.foreground");
        return c != null ? c : new Color(0xCFD8DC);
    }

    /** Même rôle que headerBtn() mais plus discret (police plus petite, gris atténué) — utilisé
     *  pour les actions secondaires personnalisables (Transcoder, Soumettre AcoustID…) afin de les
     *  distinguer visuellement des 5 actions fixes (Ouvrir/Rafraîchir/Tout tagger/Tagger la
     *  sélection/Annuler), plutôt que d'avoir 8+ boutons de poids visuel identique dans la même
     *  rangée. */

    private JButton secondaryBtn(String text, String tip, ToolbarIcon.Kind icon) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        // Même relief 3D explicite que headerBtn() (voir son commentaire), en plus discret —
        // cohérent avec le rôle "action secondaire" de ce bouton (police déjà plus petite/atténuée).
        // Mêmes teintes dérivées du thème que headerBtn() (voir son commentaire), un cran plus
        // discret : texte atténué et fond à peine détaché du panneau.
        b.putClientProperty("FlatLaf.style",
            "foreground: fade($Label.foreground,70%); font: 11 $defaultFont; borderWidth: 1; "
            + "borderColor: fade($Component.borderColor,70%); "
            + "background: lighten($Panel.background,4%,autoInverse); "
            + "hoverBackground: lighten($Panel.background,9%,autoInverse); "
            + "pressedBackground: darken($Panel.background,5%,autoInverse); arc: 6");
        if (icon != null) { b.setIcon(new ToolbarIcon(icon, iconColor(), 14)); b.setIconTextGap(6); }
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
            case "cdImport"        -> ToolbarIcon.Kind.DISC;
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

    private JSeparator vSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 22));
        s.setForeground(new Color(0x3A3B3E));
        return s;
    }

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
        // Icône loupe intégrée au champ (FlatLaf.leadingIcon) plutôt qu'un JLabel séparé à côté —
        // repère visuel immédiat pour "ceci est une recherche", cohérent avec le reste des icônes
        // vectorielles de l'appli. Purement cosmétique, aucun changement de comportement.
        tfFilter.putClientProperty("JTextField.leadingIcon",
                new ToolbarIcon(ToolbarIcon.Kind.SEARCH, new Color(0x90A4AE), 13));
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
        styleChip(l, color, false, false);
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
        styleChip(l, color, false, false);
        return l;
    }

    /**
     * Apparence d'un chip — trois états seulement, volontairement :
     * <ul>
     *   <li><b>normal</b> : chrome entièrement neutre, aucune couleur de catégorie ;</li>
     *   <li><b>alerte</b> : compteur non nul sur Erreurs / Non identifiés → texte coloré, la
     *       couleur redevient un signal qu'on remarque ;</li>
     *   <li><b>actif</b> : filtre appliqué → fond accent plein, une seule teinte dans toute l'appli.</li>
     * </ul>
     *
     * <p>Avant ce correctif (2026-09-08), chaque chip portait SA propre bordure néon plus un relief
     * 3D : sept couleurs saturées de poids identique alignées en haut de l'écran, plus le bouton
     * Journal vert et l'accent teal — l'œil n'avait nulle part où se poser et la couleur ne
     * signalait plus rien puisque tout était coloré. Diagnostic posé en regardant une capture de
     * l'appli après un retour utilisateur qu'il ne parvenait pas à formuler ("le logo est pas beau,
     * qqch me saoule, impossible à t'expliquer").
     *
     * <p>Les teintes viennent du THÈME courant (UIManager) et non plus de gris sombres codés en
     * dur : depuis l'ajout du sélecteur de thèmes (voir ThemeManager), un 0x252527 en dur donnait
     * des pastilles anthracite sur fond blanc en thème clair.
     */
    private void styleChip(JLabel chip, Color color, boolean active, boolean alert) {
        Color panelBg = UIManager.getColor("Panel.background");
        Color borderC = UIManager.getColor("Component.borderColor");
        Color textC   = UIManager.getColor("Label.foreground");
        if (panelBg == null) panelBg = new Color(0x252527);
        if (borderC == null) borderC = new Color(0x3A3A3E);
        if (textC   == null) textC   = Color.LIGHT_GRAY;

        // Fond légèrement détaché du panneau (plus clair en thème sombre, plus foncé en clair) —
        // suffisant pour lire "pastille" sans avoir besoin d'une bordure colorée pour la délimiter.
        boolean dark = com.formdev.flatlaf.FlatLaf.isLafDark();
        Color chipBg = blend(panelBg, new Color(dark ? 255 : 0, dark ? 255 : 0, dark ? 255 : 0, 18));

        chip.setBackground(active ? ACCENT : chipBg);
        chip.setForeground(active ? Color.WHITE : (alert ? color : textC));
        chip.setBorder(new CompoundBorder(
            new LineBorder(active ? ACCENT : borderC, 1, true),
            new EmptyBorder(2, 8, 2, 8)));
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
        long libraryBytes = 0;
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
            libraryBytes += e.sizeBytes;
            switch (e.status) {
                case TAGGED     -> tagged++;
                case IDENTIFIED -> identified++;
                case SKIPPED    -> skipped++;
                case ERROR      -> error++;
                default         -> pending++;
            }
        }
        // Taille totale de la bibliothèque chargée (tout ce qui est en mémoire, indépendamment du
        // filtre actif — comme les chips de statut ci-dessus).
        if (lblLibrarySize != null) {
            int loaded = tableModel.allEntries().size();
            if (loaded == 0) {
                lblLibrarySize.setVisible(false);
            } else {
                lblLibrarySize.setText(I18n.t("Bibliothèque : %s (%d fichiers)",
                        com.opentagger.ByteFormat.format(libraryBytes), loaded));
                lblLibrarySize.setVisible(true);
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

        // Chip actif = celui qui correspond au filtre statut actuellement appliqué. L'état "alerte"
        // (texte coloré) est réservé aux DEUX compteurs qui appellent vraiment une action, et
        // seulement s'ils sont non nuls : une pastille "Erreurs 0" en rouge permanent était du bruit,
        // pas une information.
        styleChip(lblStatTotal,      CHIP_TOTAL,      activeStatusFilter == FILTER_ALL,        false);
        styleChip(lblStatIdentified, CHIP_IDENTIFIED, activeStatusFilter == FILTER_IDENTIFIED, false);
        styleChip(lblStatTagged,     CHIP_TAGGED,     activeStatusFilter == FILTER_TAGGED,     false);
        styleChip(lblStatSkipped,    CHIP_SKIPPED,    activeStatusFilter == FILTER_SKIPPED,    skipped > 0);
        styleChip(lblStatError,      CHIP_ERROR,      activeStatusFilter == FILTER_ERROR,      error > 0);
        styleChip(lblStatPending,    CHIP_PENDING,    activeStatusFilter == FILTER_PENDING,    false);
    }

    // ── Split pane table / détail ─────────────────────────────────────────────

    private JSplitPane buildMainSplit() {
        // ── Table (gauche) ────────────────────────────────────────────────────
        table = new JTable(tableModel) {
            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row < 0) return null;
                // entryAtViewRow() (pas tableModel.getTooltip(convertRowIndexToModel(r)), même bug
                // de fond qu'ailleurs dans cette classe — voir applyDetail()) : en vue arborescence,
                // affichait l'infobulle d'un fichier différent de celui survolé.
                return tableModel.getTooltip(entryAtViewRow(row));
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
    // PENDING/PROCESSING retirés (2026-09-02, retour utilisateur "mode coverflow bizarre") : ces
    // fichiers n'ont par définition jamais été identifiés/tagués, donc quasiment jamais de vraie
    // pochette téléchargée — juste une tuile grise placeholder. Même raisonnement déjà appliqué à
    // SKIPPED/ERROR ailleurs (applyFilter() : "n'afficherait jamais rien d'utile, juste un
    // carrousel vide") — PENDING/PROCESSING ont exactement le même défaut, jamais exclus jusqu'ici.
    // Sur une bibliothèque encore majoritairement en attente, la quasi-totalité du carrousel
    // n'était que des tuiles vides sans intérêt.
    private static boolean isCoverFlowEligible(FileEntry.Status s) {
        return s == FileEntry.Status.TAGGED || s == FileEntry.Status.IDENTIFIED;
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
        // Colonne Note : même raison que durationCompare — "10" ne doit pas trier avant "9", et la
        // valeur peut aller jusqu'à 255 (échelle ID3v2 brute, voir TagInfo.rating) donc l'écart avec
        // une comparaison texte est encore plus marqué que pour la durée.
        java.util.Comparator<String> ratingCompare = (a, b) -> {
            int ia = 0, ib = 0;
            try { ia = (a == null || a.isBlank()) ? 0 : Integer.parseInt(a.trim()); } catch (NumberFormatException ignored) {}
            try { ib = (b == null || b.isBlank()) ? 0 : Integer.parseInt(b.trim()); } catch (NumberFormatException ignored) {}
            return Integer.compare(ia, ib);
        };
        rowSorter.setComparator(FileTableModel.COL_RATING, ratingCompare);

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
        colWidth(cm, 11, 50,  36,  70);  // Note
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
        // setValueIsAdjusting(true) autour de TOUTE la boucle (2026-09-03, gel EDT trouvé en
        // direct pendant un "Forcer le re-taguage" sur toute la bibliothèque avec une grosse
        // sélection active) : sans ça, chaque addRowSelectionInterval() déclenche SON PROPRE
        // ListSelectionEvent avec getValueIsAdjusting()=false, donc le listener de la ligne 557
        // (qui saute volontairement le travail tant que isAdjusting()) fait le plein
        // refreshDetail()/populateMulti() à CHAQUE ligne réajoutée au lieu d'une seule fois à la
        // fin — et comme restoreTableSelection() est rappelée à chaque tick de refreshStats()
        // pendant un gros lot, ce O(N) par appel devenait O(N²) répété des centaines de fois :
        // deux prises jstack à 5s d'écart montraient l'EDT coincé dans exactement cette pile
        // (DetailPanel.setM ← populateMulti ← refreshDetail ← restoreTableSelection ←
        // refreshStats), CPU EDT toujours RUNNABLE mais l'app entièrement figée pour l'utilisateur.
        javax.swing.ListSelectionModel sm = table.getSelectionModel();
        sm.setValueIsAdjusting(true);
        try {
            table.clearSelection();
            for (int vr : viewRows) table.addRowSelectionInterval(vr, vr);
        } finally {
            sm.setValueIsAdjusting(false); // déclenche l'unique refreshDetail() différé
        }
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
        JMenuItem miDelete = new JMenuItem("🗑  " + I18n.t("Supprimer (corbeille)…"));

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
                // showError() (JOptionPane modal) remplacé ici après un vrai blocage en direct
                // (2026-09-22) : le dialogue s'est ouvert avec une géométrie dégénérée (1x1 px,
                // invisible — xwininfo l'a confirmé), tout en restant modal, gelant l'EDT (donc toute
                // l'appli) sans qu'aucun bouton visible ne permette de le fermer ; même une fois sa
                // fenêtre X11 détruite depuis l'extérieur, le thread EDT est resté bloqué dans
                // Dialog.show() — seul un redémarrage a débloqué l'appli. Cause X11/Swing exacte non
                // confirmée (peut-être liée au multi-écran de cette machine), mais l'échec réel ici
                // ("xdg-open" indisponible/erreur) est mineur — jamais la peine de risquer de geler
                // toute l'appli pour ça. setStatus() (nombreux précédents dans ce fichier) ne peut
                // pas produire ce blocage : pas de fenêtre modale, juste une ligne dans la barre d'état.
                System.out.println("[OT] Révéler dans le gestionnaire de fichiers : échec — " + ex.getMessage());
                setStatus(I18n.t("Impossible d'ouvrir le dossier : %s", ex.getMessage()));
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
        miDelete.addActionListener(e -> deleteSelectedFiles());

        JMenuItem miAcoustId = new JMenuItem("🎵  " + I18n.t("Soumettre fingerprint AcoustID"));
        miAcoustId.addActionListener(e -> submitAcoustId());

        JMenuItem miMatch = new JMenuItem("🎯  " + I18n.t("Correspondance manuelle…"));
        miMatch.addActionListener(e -> openMatchDialog());

        JMenuItem miCover = new JMenuItem("🖼  " + I18n.t("Gérer la pochette…"));
        miCover.addActionListener(e -> openCoverDialog());

        JMenuItem miRefreshMeta = new JMenuItem("↺  " + I18n.t("Rafraîchir tags + pochette + photo d'artiste (sélection)"));
        miRefreshMeta.addActionListener(e -> refreshSelectedMeta());
        miRefreshMeta.setToolTipText(I18n.t("Force une nouvelle recherche même si une pochette/photo existe déjà"
                + " (sur le fichier ET dans le tag) — utile pour corriger une image erronée"));

        JMenuItem miMbEdit = new JMenuItem("✏  " + I18n.t("Modifier sur MusicBrainz"));
        miMbEdit.addActionListener(e -> openMbEditPage());

        JMenuItem miMbContrib = new JMenuItem("🤝  " + I18n.t("Contribuer à MusicBrainz…"));
        miMbContrib.addActionListener(e -> openMbContribute());

        menu.add(miTag); menu.add(miRename); menu.addSeparator();
        menu.add(miMatch); menu.add(miCover);
        menu.add(miRefreshMeta); menu.addSeparator();
        menu.add(miMbEdit); menu.add(miMbContrib); menu.addSeparator();
        menu.add(miAcoustId); menu.addSeparator();
        menu.add(miReveal); menu.add(miRemove); menu.add(miDelete);

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
            @Override public void mousePressed(MouseEvent e)  { lastManualTableClickMs = System.currentTimeMillis(); maybeShow(e); }
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
        // Message d'accueil plutôt qu'un espace vide qui ressemble à un panneau cassé/pas encore
        // chargé — retour utilisateur (2026-08-23, "améliore l'ui") : clearDetail() (aucune ligne
        // sélectionnée) laissait toute la colonne droite visuellement vide sans indice de ce qu'il
        // fallait faire.
        lblFilePath = new JLabel(I18n.t("Sélectionnez un fichier pour voir ses détails"));
        lblFilePath.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 11 $defaultFont");
        lblFilePath.setBorder(new EmptyBorder(4, 14, 0, 14));

        // ── Séparateur accentué ──────────────────────────────────────────────
        JPanel accentLine = new JPanel();
        accentLine.setBackground(ACCENT);
        accentLine.setPreferredSize(new Dimension(0, 2));
        accentLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));

        // ── Titre section "MÉTADONNÉES" ───────────────────────────────────────
        // Bandeau pleine largeur, aligné à gauche. Avant ce correctif (2026-09-08) il n'avait ni
        // alignmentX ni maximumSize : java.awt.Component.getAlignmentX() vaut CENTER_ALIGNMENT par
        // défaut, donc BoxLayout.Y_AXIS le CENTRAIT et le réduisait à la largeur du texte — un petit
        // rectangle opaque flottant au milieu, au-dessus des onglets, qu'on lisait comme un artefact
        // détaché plutôt que comme un titre de section (repéré à l'écran sur capture).
        JLabel metaTitle = new JLabel(I18n.t("  MÉTADONNÉES"));
        metaTitle.putClientProperty("FlatLaf.style",
            "foreground: fade($Label.foreground,55%); font: bold 10 $defaultFont; "
            + "background: lighten($Panel.background,4%,autoInverse)");
        metaTitle.setOpaque(true);
        metaTitle.setBorder(new EmptyBorder(5, 0, 5, 0));
        metaTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        metaTitle.setHorizontalAlignment(SwingConstants.LEFT); // pas seulement le composant : le TEXTE dedans
        metaTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, metaTitle.getPreferredSize().height + 10));

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
        // Axe d'alignement UNIFORME (2026-09-08). BoxLayout.Y_AXIS aligne ses enfants les uns par
        // rapport aux autres selon leur alignmentX ; par défaut Component.getAlignmentX() vaut
        // CENTER_ALIGNMENT, donc mélanger un enfant à 0.0 avec des enfants restés à 0.5 place l'axe
        // ailleurs qu'au bord gauche du panneau — le bandeau "MÉTADONNÉES" se retrouvait décalé vers
        // la droite, texte compris, malgré une largeur maximale déjà à MAX_VALUE. Tous les enfants
        // passent donc à LEFT_ALIGNMENT + largeur maximale illimitée : chacun occupe toute la
        // largeur, et ceux qui doivent PARAÎTRE centrés (pochette, message d'accueil) le restent via
        // leur propre centrage interne.
        for (JComponent c : new JComponent[]{coverSection, lblFilePath, accentLine, metaTitle}) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        coverSection.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        lblFilePath.setMaximumSize(new Dimension(Integer.MAX_VALUE, lblFilePath.getPreferredSize().height));
        lblFilePath.setHorizontalAlignment(SwingConstants.CENTER);
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
    // Horodatage du dernier clic MANUEL de l'utilisateur dans le tableau — voir son usage dans
    // followProcessing() juste en dessous. Mis à jour par un mousePressed dédié (jamais déclenché
    // par un setRowSelectionInterval() programmatique, qui ne génère aucun événement souris réel).
    private volatile long lastManualTableClickMs = 0;
    // Fenêtre pendant laquelle le suivi automatique se met en pause après un clic manuel — retour
    // utilisateur (2026-09-13) : suivre parcourait/resélectionnait sans arrêt PENDANT qu'il essayait
    // de cliquer une ligne précise en direct (un run de 40000+ fichiers en cours), rendant impossible
    // toute navigation manuelle dans le tableau tant qu'un taguage tournait. Le suivi reste utile
    // pour la surveillance sans surveillance active (retour visuel qu'on avance) — seul le CONFLIT
    // avec une interaction manuelle récente est corrigé ici, pas le mécanisme lui-même.
    private static final long FOLLOW_PAUSE_AFTER_CLICK_MS = 60_000;

    private void followProcessing(FileEntry entry) {
        if (System.currentTimeMillis() - lastManualTableClickMs < FOLLOW_PAUSE_AFTER_CLICK_MS) return;
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
            // Au-delà de 2000 fichiers, les valeurs « communes » affichées sont calculées sur un échantillon régulier de 500 : l'affichage
            // ne sert qu'à montrer ce qui est identique, l'édition en lot n'applique que les champs REMPLIS par l'utilisateur. Calculer
            // les ~100 champs sur 30 000 fichiers gelait l'interface à chaque rafraîchissement de la sélection.
            List<TagInfo> tags = new ArrayList<>();
            int step = entries.size() > 2000 ? entries.size() / 500 : 1;
            for (int i = 0; i < entries.size(); i += step) tags.add(entries.get(i).activeTags());
            lblFilePath.setText(I18n.t("  %d fichiers sélectionnés — les champs vides ne seront pas modifiés", entries.size()));
            detailPanel.populateMulti(tags);
            lblCoverImg.setIcon(null); lblCoverImg.setText(entries.size() + "");
        }
    }

    private void clearDetail() {
        lblFilePath.setText(I18n.t("Sélectionnez un fichier pour voir ses détails"));
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
                // Autres champs de la page Bandcamp (2026-09-20) — sans écraser une valeur déjà présente.
                ti.bandcampUrl = album.albumUrl();
                if (album.extra() != null) {
                    if (ti.label.isBlank())     ti.label     = album.extra().label();
                    if (ti.tags.isBlank())      ti.tags      = album.extra().keywords();
                    if (ti.copyright.isBlank()) ti.copyright = album.extra().copyright();
                    if (ti.license.isBlank())   ti.license   = album.extra().license();
                }
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
        // entryAtViewRow() (pas tableModel.get(table.convertRowIndexToModel(r)), même bug de fond
        // que startTagging()/refreshSelectedMeta() en vue arborescence) — une ligne d'en-tête de
        // groupe éventuellement sélectionnée résout vers null ici (filtrée), jamais "tous ses
        // membres" (entriesAtViewRow) : cette correspondance Bandcamp est position-à-position avec
        // l'ordre visuel des PISTES, une expansion de groupe casserait cet ordre.
        List<FileEntry> selection = new ArrayList<>();
        for (int viewRow : rows) {
            FileEntry e = entryAtViewRow(viewRow);
            if (e != null) selection.add(e);
        }
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
                // entryAtViewRow() (pas tableModel.get(table.convertRowIndexToModel(r)), même bug
                // de fond que openBandcampDialog()/startTagging() en vue arborescence — voir leurs
                // commentaires) : trouvé en direct 2026-09-01 en auditant les modes d'affichage,
                // JAMAIS corrigé ici jusqu'à présent malgré le même correctif déjà fait ailleurs —
                // en vue Arborescence par album, ce chemin pouvait écrire les tags édités sur le
                // MAUVAIS fichier (les en-têtes de groupe décalent la correspondance ligne de
                // vue → ligne modèle entre albumTreeModel et tableModel).
                FileEntry e = entryAtViewRow(rows[0]);
                if (e == null) return; // ligne d'en-tête de groupe sélectionnée, rien à appliquer
                int       mr   = tableModel.indexOf(e);
                TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                TagInfo   ti   = e.activeTags();
                detailPanel.collect(ti);
                refreshTableRow(mr, ti);
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Modifier %s", e.filename()));
                recordFieldCorrections(correctionsCache, e, snap, ti);
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
                    // Même correctif que la branche fichier unique ci-dessus — voir son commentaire.
                    FileEntry e = entryAtViewRow(row);
                    if (e == null) continue; // ligne d'en-tête de groupe, ignorée
                    int       mr   = tableModel.indexOf(e);
                    TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                    TagInfo   ti   = e.activeTags();
                    detailPanel.collect(ti);
                    refreshTableRow(mr, ti);
                    undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Lot %s", e.filename()));
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

    /** Fenêtre de sélection courante (≥1 ligne), ou message de statut si vide — point d'entrée du
     *  menu "Revue rapide (notation)…". Même garde que applyDetail() (rows.length==0 → rien à
     *  faire) : la revue rapide agit sur la sélection, pas sur toute la bibliothèque, pour laisser
     *  l'utilisateur cibler (ex. "Sélectionner les tagués" avant de lancer la revue). */
    private void openQuickReview() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            setStatus(I18n.t("Sélectionne d'abord les fichiers à noter (aucune sélection)."));
            return;
        }
        List<FileEntry> selected = new ArrayList<>();
        for (int row : rows) {
            FileEntry e = entryAtViewRow(row);
            if (e != null) selected.add(e); // null = ligne d'en-tête de groupe, ignorée
        }
        if (selected.isEmpty()) return;
        new QuickReviewDialog(this, selected).setVisible(true);
    }

    /** Appelé par {@link QuickReviewDialog} à chaque note posée — même séquence que la branche
     *  fichier unique de {@link #applyDetail()} (snapshot undo, écriture immédiate, 
     *  traçabilité des corrections), juste sans passer par DetailPanel puisque la revue rapide ne
     *  touche que le champ note. {@code cache} reste ouvert par l'appelant pour toute la durée de
     *  la session de revue (potentiellement des dizaines de notes en rafale) plutôt que rouvert à
     *  chaque frappe. */
    void quickReviewSave(MetadataCache cache, FileEntry e, String newRating) {
        int mr = tableModel.indexOf(e);
        if (mr < 0) return;
        TagInfo snap = com.opentagger.UndoManager.snapshot(e.activeTags());
        TagInfo ti   = e.activeTags();
        ti.rating = newRating;
        refreshTableRow(mr, ti);
        undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), I18n.t("Note rapide %s", e.filename()));
        recordFieldCorrections(cache, e, snap, ti);
        if (writeTagsSafe(e, ti)) markManuallyTagged(e, ti, mr);
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
        FileEntry entry = entryAtViewRow(row);
        if (entry == null) { setStatus(I18n.t("Sélectionnez un fichier (pas un en-tête de groupe).")); return; }
        new MatchDialog(this, entry, tableModel, this::refreshDetail).setVisible(true);
    }

    // ── Gestion de la pochette ────────────────────────────────────────────────

    private void openCoverDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus(I18n.t("Sélectionnez un fichier.")); return; }
        FileEntry entry = entryAtViewRow(row);
        if (entry == null) { setStatus(I18n.t("Sélectionnez un fichier (pas un en-tête de groupe).")); return; }
        new CoverArtDialog(this, entry, this::refreshDetail).setVisible(true);
    }

    // ── MusicBrainz : modifier + contribuer ──────────────────────────────────

    private void openMbEditPage() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus(I18n.t("Sélectionnez un fichier.")); return; }
        FileEntry mbEditEntry = entryAtViewRow(row);
        if (mbEditEntry == null) { setStatus(I18n.t("Sélectionnez un fichier (pas un en-tête de groupe).")); return; }
        TagInfo ti = mbEditEntry.activeTags();
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
        FileEntry entry = entryAtViewRow(row);
        if (entry == null) { setStatus(I18n.t("Sélectionnez un fichier (pas un en-tête de groupe).")); return; }
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
            case LASTFM_SYNC         -> I18n.t("Last.fm");
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
        // Filet de sécurité pour les chips de statut (Tagués/En attente/...) : refreshStats() n'est
        // par ailleurs appelée QUE de façon réactive et throttlée (300ms) depuis chaque boucle de
        // scan/taguage — si un de ces points d'appel cesse d'être atteint pour une raison quelconque
        // (chemin de code différent selon le worker actif, exception avalée avant d'y arriver...),
        // les chips restent figés indéfiniment alors que le taguage réel continue en arrière-plan,
        // aucune sauvegarde perdue mais l'affichage ment. Retour utilisateur (2026-08-20) : compteurs
        // figés 20 min alors que des ✔ ENREGISTRÉ continuaient d'apparaître dans le journal au même
        // moment — confirme que c'est un problème d'affichage, pas de traitement. Un tick de 3s
        // indépendant de tout worker garantit que l'écart ne dépasse jamais quelques secondes.
        new javax.swing.Timer(3000, e -> refreshStats()).start();

        // Taille réelle (somme des tailles de fichiers) de la bibliothèque chargée, en Go/To. Masqué
        // tant que rien n'est chargé. L'infobulle (détail par format + espace libre du disque) n'est
        // calculée qu'au survol — jamais en tâche de fond.
        lblLibrarySize = new JLabel() {
            @Override public String getToolTipText() { return librarySizeTooltip(); }
        };
        lblLibrarySize.setForeground(new Color(0x90A4AE));
        lblLibrarySize.putClientProperty("FlatLaf.style", "font: 11 $defaultFont");
        lblLibrarySize.setToolTipText("");   // active le mécanisme d'infobulle de Swing
        lblLibrarySize.setVisible(false);

        JPanel eastPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        eastPanel.setOpaque(false);
        eastPanel.add(lblLibrarySize);
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

    /** Infobulle du total « bibliothèque » : détail par format (extension) puis espace libre du
     *  disque du premier fichier chargé. Calculée à la demande sur l'EDT (O(n) sur les entrées déjà
     *  en mémoire, aucun accès disque sauf l'espace libre, protégé par try/catch). */
    private String librarySizeTooltip() {
        java.util.List<FileEntry> all = tableModel.allEntries();
        if (all.isEmpty()) return null;
        java.util.Map<String, long[]> byExt = new java.util.HashMap<>();
        for (FileEntry e : all) {
            String n = e.filename();
            int dot = n.lastIndexOf('.');
            String ext = dot >= 0 ? n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "?";
            long[] a = byExt.computeIfAbsent(ext, k -> new long[2]);
            a[0]++; a[1] += e.sizeBytes;
        }
        java.util.List<java.util.Map.Entry<String, long[]>> rows = new java.util.ArrayList<>(byExt.entrySet());
        rows.sort((x, y) -> Long.compare(y.getValue()[1], x.getValue()[1]));
        StringBuilder sb = new StringBuilder("<html><b>")
            .append(I18n.t("Taille des fichiers chargés dans OpenTagger")).append("</b><br>");
        int shown = 0;
        for (java.util.Map.Entry<String, long[]> r : rows) {
            if (++shown > 8) break;
            sb.append(r.getKey().toUpperCase(java.util.Locale.ROOT)).append(" : ")
              .append(com.opentagger.ByteFormat.format(r.getValue()[1]))
              .append(" (").append(r.getValue()[0]).append(")<br>");
        }
        if (rows.size() > 8) sb.append("…<br>");
        try {
            java.nio.file.Path p = all.get(0).currentPath != null ? all.get(0).currentPath : all.get(0).file.toPath();
            java.nio.file.FileStore fs = java.nio.file.Files.getFileStore(p);
            sb.append("<br>").append(I18n.t("Espace libre sur le disque : %s",
                com.opentagger.ByteFormat.format(fs.getUsableSpace())));
        } catch (Exception ignored) { /* disque réseau injoignable, chemin disparu… : on n'affiche rien */ }
        return sb.append("</html>").toString();
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
        // Hauteur de cellule FIXE + cellule « prototype » : sans elles, la JList remesure TOUTES les lignes (appel du moteur de rendu
        // pour chacune) à chaque ajout — mesuré sur un journal plein (20 000 lignes) : 3,6 s de thread d'affichage par ligne ajoutée,
        // contre 0 ms avec hauteur fixe. Recalculé quand le thème ou la police changent (updateUI).
        logList  = new JList<>(logModel) {
            @Override public void updateUI() { super.updateUI(); applyLogRowMetrics(this); }
        };
        applyLogRowMetrics(logList);
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
                        case ERROR      -> LOG_ERROR;
                        case SKIPPED    -> LOG_WARN;
                        case TAGGED     -> LOG_OK;
                        case IDENTIFIED -> LOG_INFO;
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
        // Accent maison quand déplié, pas un vert vif à part (2026-09-08) : une fois les pastilles
        // de statut repassées en neutre, ce bouton restait la SEULE tache saturée de l'écran, et
        // d'une teinte qui n'existait nulle part ailleurs dans l'appli. $-références FlatLaf plutôt
        // que des hex en dur — le sélecteur de thèmes (ThemeManager) rend tout hex figé faux sur la
        // moitié des thèmes.
        btnToggleJournal.putClientProperty("FlatLaf.style",
            "font: bold $defaultFont; arc: 6; selectedBackground: #4DB6AC; selectedForeground: #1E1F22");
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

    // Couleurs du journal créées UNE fois (le moteur de rendu les réallouait à chaque cellule peinte).
    private static final Color LOG_ERROR = new Color(230, 90, 90), LOG_WARN = new Color(210, 160, 40),
                               LOG_OK = new Color(100, 200, 130), LOG_INFO = new Color(85, 153, 255);

    private static void applyLogRowMetrics(JList<LogEntry> l) {
        if (l.getFont() == null) return;
        l.setFixedCellHeight(l.getFontMetrics(l.getFont()).getHeight() + 4);
        l.setPrototypeCellValue(new LogEntry("00:00:00", "W".repeat(220), FileEntry.Status.PENDING, null));
    }

    /** Vrai si le journal est déjà défilé tout en bas (alors on le laisse suivre les nouvelles lignes). */
    private boolean journalAtBottom() {
        if (journalScroll == null) return true;
        JScrollBar sb = journalScroll.getVerticalScrollBar();
        return sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum() - 4;
    }

    private void followJournalIfNeeded(boolean wasAtBottom) {
        if (wasAtBottom && logModel.size() > 0) logList.ensureIndexIsVisible(logModel.size() - 1);
    }

    /** Ajoute une ligne de séparation au début d'un run (taguage, re-taguage forcé, passe complète). */
    private void logRunStart(String label, int count) {
        LogEntry sep = new LogEntry(nowHms(), I18n.t("── %s : %d fichier(s) ──", label, count),
                FileEntry.Status.PENDING, null);
        final boolean follow = journalAtBottom();
        logHistory.add(sep);
        if (trimLogHistoryIfNeeded()) { followJournalIfNeeded(follow); return; }
        logModel.addElement(sep);
        followJournalIfNeeded(follow);
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
        final boolean follow = journalAtBottom();
        logHistory.add(e);
        if (trimLogHistoryIfNeeded()) { followJournalIfNeeded(follow); return; }
        if (!chkLogErrorsOnly.isSelected() || e.status() == FileEntry.Status.ERROR) {
            logModel.addElement(e);
            followJournalIfNeeded(follow);
        }
    }

    /** Ligne libre dans le Journal pour les opérations de fond (analyse, et à terme chaque ajout /
     *  modification / suppression) — couleur selon {@code status} : PENDING = titre de section,
     *  IDENTIFIED = information (bleu), SKIPPED = avertissement (orange), TAGGED = réussite (vert),
     *  ERROR = échec (rouge). À appeler depuis le thread Swing. */
    void journalLine(String text, FileEntry.Status status) {
        appendLogLine(text, status, null);
    }

    private void rebuildLogModel() {
        java.util.List<LogEntry> shown = new java.util.ArrayList<>(logHistory.size());
        for (LogEntry e : logHistory)
            if (!chkLogErrorsOnly.isSelected() || e.status() == FileEntry.Status.ERROR) shown.add(e);
        logModel.clear();
        logModel.addAll(shown);   // un seul événement de modèle, pas un par ligne
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
    /** Point d'entrée public pour un dossier produit HORS du flux normal (glisser-déposer, Ouvrir
     *  dossier) — utilisé par CdImportDialog pour faire reprendre le pipeline habituel
     *  (identification/renommage/déplacement) sur le dossier de travail où les pistes viennent
     *  d'être extraites, exactement comme n'importe quel autre dossier chargé. */
    public void importFolder(File dir) { loadDirectory(dir); }

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
                // Watchdog de visibilité (2026-09-19, audit dédié "blocages silencieux", même
                // signature que le cas déjà corrigé sur FileRenamer.moveFile) : dossier.listFiles()
                // dans AudioScanner.scanRecursif() est un appel natif SANS AUCUN timeout possible
                // côté Java — un montage USB/réseau qui décroche EN PLEIN milieu du parcours
                // bloquerait ce thread pour toujours, sans la moindre ligne de log, et avec
                // seulement 2 permis sur phase1Semaphore, les AUTRES dossiers de démarrage ne
                // commenceraient jamais à scanner non plus. Ne peut pas annuler le blocage
                // (même limite que le cas FileRenamer), juste le rendre visible.
                final java.util.concurrent.atomic.AtomicBoolean stillScanning =
                        new java.util.concurrent.atomic.AtomicBoolean(true);
                Thread scanWatchdog = new Thread(() -> {
                    try { Thread.sleep(120_000); } catch (InterruptedException ignored) { return; }
                    if (stillScanning.get()) {
                        System.out.println("[OT] ⚠ Scan de \"" + dir + "\" bloqué depuis plus de 120s — "
                                + "probable montage lent/déconnecté (dossier de démarrage) ; les autres "
                                + "dossiers de démarrage peuvent aussi attendre derrière (2 permis max).");
                    }
                }, "scan-watchdog");
                scanWatchdog.setDaemon(true);
                scanWatchdog.start();
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
                        return new Object[]{ entry, ti, wasPreviouslyTagged, size };
                    });
                }, this::isCancelled);
                } finally {
                    stillScanning.set(false);
                    scanWatchdog.interrupt();
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
                        // Taille déjà lue en arrière-plan (ff.length(), voir plus haut) — réutilisée
                        // ici plutôt que rappeler entry.file.length() sur l'EDT : sur MyBook (disque
                        // externe lent, souvent saturé), cet appel bloquant répété une fois par
                        // fichier gelait l'EDT par intermittence pendant toute la durée du scan (menu/
                        // clics sans effet visible pendant des dizaines de minutes) — repéré en direct
                        // 2026-08-30 en cherchant pourquoi "Nettoyer les dossiers orphelins" ne
                        // montrait aucune fenêtre, même correctif que DuplicatesDialog plus tôt cette
                        // session (précalcul en arrière-plan, jamais d'I/O fichier sur l'EDT).
                        long size = chunk.length > 3 && chunk[3] instanceof Long ? (Long) chunk[3] : -1L;
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
                        if (size == 0) {
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
                    // Tags des fichiers lus : synchroniser les compteurs d'écoute (au plus 1 fois / 24 h).
                    if (Config.get().autoSyncPlayCounts()) scheduleAutoPlayCountSync();
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
            // Trace stdout — même angle mort que Headphones/Compilations/Enregistrer (2026-08-31/
            // 09-01) : ces 3 gardes de startTagging() ne parlaient jusqu'ici qu'à la barre de statut
            // GUI, invisible depuis l'extérieur. Ajouté en creusant un retour "Tagger cet album ne
            // fonctionne pas" — hypothèse concrète : sur une session où scan/complétion/groupement
            // tournent presque en continu, ce clic tombe très souvent sur l'un de ces 3 blocages
            // silencieux plutôt que sur un vrai bug de ciblage de fichier.
            System.out.println("[OT] Tagger : sauté — bouton déjà désactivé (taguage en préparation/en cours).");
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
            System.out.println("[OT] Tagger : sauté — un taguage tourne déjà.");
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            System.out.println("[OT] Tagger : sauté — encore en cours : " + String.join(", ", blockers));
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de taguer.", String.join(", ", blockers)));
            return;
        }
        System.out.println("[OT] Tagger : démarrage (selOnly=" + selOnly + ").");
        if (selOnly) {
            // entriesAtViewRow() (pas tableModel.get(table.convertRowIndexToModel(r)) — bug repéré
            // en direct 2026-08-24) : en vue arborescence, table.getModel() est albumTreeModel, pas
            // tableModel — convertRowIndexToModel() renvoie donc un index dans le MAUVAIS modèle,
            // et une ligne d'en-tête de groupe cliquée (aucun FileEntry direct) n'a de toute façon
            // pas d'équivalent dans tableModel. Résultat : sélectionner un groupe entier ("cliquer
            // l'en-tête pour tout sélectionner puis Analyser") ne taguait pas les bons fichiers, ou
            // rien du tout selon les cas — retour utilisateur : "je peux tagger tout l'album en
            // cliquant sur le dossier, ça ne fonctionne pas". entriesAtViewRow() gère déjà
            // correctement les deux vues (voir son usage identique dans installContextMenu()/
            // miRemove) : un en-tête de groupe développe vers TOUS ses membres, une ligne normale
            // vers son seul FileEntry. LinkedHashSet : une même piste peut apparaître dans plusieurs
            // lignes sélectionnées (en-tête + membre déjà sélectionné individuellement) sans se
            // faire tagger deux fois.
            java.util.Set<FileEntry> toTagSet = new java.util.LinkedHashSet<>();
            for (int r : table.getSelectedRows()) toTagSet.addAll(entriesAtViewRow(r));
            List<FileEntry> toTag = new ArrayList<>(toTagSet);
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
            // Trace stdout — même angle mort que Headphones/Compilations (2026-08-31) : ce blocage
            // ne laissait jamais aucune trace en dehors de la barre de statut GUI. Repéré en direct
            // 2026-09-01 : plusieurs heures sans le moindre "✔ ENREGISTRÉ" alors que l'identification
            // et la complétion (auto-relancée en boucle par tagging.post_tag_completion) tournaient
            // sans arrêt — hypothèse concrète que la Complétion automatique (INFO_COMPLETER, même
            // groupe LIBRARY_WRITE que SAVE) bloque en continu scheduleAutoSaveFollowUp() sans que
            // rien ne le signale nulle part hors de l'appli.
            System.out.println("[OT] Enregistrer : sauté — encore en cours : " + String.join(", ", blockers));
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant d'enregistrer.", String.join(", ", blockers)));
            return;
        }

        // allEntries() : les fichiers identifiés mais masqués par un filtre actif n'étaient sinon
        // jamais écrits sur le disque, sans le moindre avertissement.
        List<FileEntry> toSave = new ArrayList<>();
        // Deux lignes sur LE MÊME fichier (chemins identiques à la casse près sous Windows/macOS : rescan, dossier ajouté deux
        // fois) : une seule est écrite — la seconde trouvait le fichier déjà renommé et finissait en « Fichier introuvable ».
        // Les autres lignes recopient le résultat de la première quand l'enregistrement est fini (voir le DONE plus bas), donc
        // aucune ne reste « Identifié » à relancer en boucle.
        java.util.Map<String, FileEntry> firstByPath = new java.util.HashMap<>();
        java.util.Map<FileEntry, List<FileEntry>> sameFile = new java.util.LinkedHashMap<>();
        for (FileEntry e : tableModel.allEntries()) {
            if (!e.selected || e.status != FileEntry.Status.IDENTIFIED) continue;
            String key = com.opentagger.PathIdentity.key(e.currentPath != null ? e.currentPath : e.file.toPath());
            FileEntry first = firstByPath.putIfAbsent(key, e);
            if (first == null) toSave.add(e);
            else sameFile.computeIfAbsent(first, k -> new ArrayList<>()).add(e);
        }
        if (toSave.isEmpty()) {
            System.out.println("[OT] Enregistrer : aucun fichier identifié sélectionné à enregistrer.");
            setStatus(I18n.t("Aucun fichier identifié à enregistrer (utilisez « Tout tagger » d'abord)."));
            return;
        }
        System.out.println("[OT] Enregistrer : " + toSave.size() + " fichier(s) — démarrage.");

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
                    for (java.util.Map.Entry<FileEntry, List<FileEntry>> same : sameFile.entrySet()) {
                        FileEntry first = same.getKey();
                        if (first.status == FileEntry.Status.IDENTIFIED) continue; // pas traité (annulé) : les autres lignes attendent aussi
                        for (FileEntry twin : same.getValue()) {
                            twin.status = first.status;
                            twin.result = first.result;
                            twin.currentPath = first.currentPath;
                            twin.message = I18n.t("Même fichier qu'une autre ligne — enregistré une seule fois");
                            tableModel.update(twin);
                        }
                    }
                    refreshStats();
                    if (!w.isCancelled() && Config.get().bool("artwork.auto_after_save", false)) {
                        for (FileEntry fe : toSave) if (fe.status == FileEntry.Status.TAGGED) pendingArtwork.add(fe);
                    }
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
        // Pochettes/portraits manquants des fichiers enregistrés pendant cette chaîne : une seule passe, ICI (vraie fin de
        // chaîne), car elle écrit dans les fichiers et ne peut pas tourner pendant un taguage ou un enregistrement.
        List<FileEntry> artwork = new ArrayList<>(pendingArtwork);
        pendingArtwork.clear();
        if (!artwork.isEmpty()) {
            completeArtwork(artwork, true, this::runPostTagCommandNow);
            return;
        }
        runPostTagCommandNow();
    }

    /** Fichiers enregistrés dont les pochettes/portraits manquants seront complétés à la fin de la chaîne. */
    private final java.util.Set<FileEntry> pendingArtwork = new java.util.LinkedHashSet<>();

    /**
     * Complète les pochettes (par album) et portraits (par artiste) manquants des fichiers donnés, en arrière-plan.
     * Ne touche pas ce qui existe déjà. Appelé après l'enregistrement si {@code artwork.auto_after_save} est actif,
     * ou à la demande depuis Outils.
     */
    private void completeArtwork(List<FileEntry> targets, boolean auto, Runnable onDone) {
        Runnable done = onDone != null ? onDone : () -> {};
        if (targets.isEmpty()) { done.run(); return; }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.ARTWORK_COMPLETION);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — pochettes et photos manquantes sautées cette fois-ci.",
                    String.join(", ", blockers)));
            done.run();
            return;
        }
        setStatus(I18n.t("Recherche des pochettes et photos manquantes (%d fichier(s))…", targets.size()));
        ArtworkCompletionWorker w = new ArtworkCompletionWorker(targets,
                msg -> SwingUtilities.invokeLater(() -> setStatus(msg)));
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    try {
                        ArtworkCompletionWorker.Summary s = w.get();
                        setStatus(s.nothingToDo()
                                ? I18n.t("Aucune pochette ni photo manquante.")
                                : I18n.t("Pochettes ajoutées : %d (introuvables : %d) — photos d'artistes ajoutées : %d (introuvables : %d)",
                                        s.coversAdded(), s.coversNotFound(), s.photosAdded(), s.photosNotFound()));
                    } catch (Exception ignored) {
                        // annulée ou en échec : rien à résumer
                    }
                    done.run();
                });
            }
        });
        WorkerHub.get().submit(WorkerHub.TaskKind.ARTWORK_COMPLETION, I18n.t("Pochettes et photos manquantes"), w, w::stopNow);
    }

    /** Outils → Re-traitement : sur la sélection, ou sur toute la liste s'il n'y en a pas. */
    private void completeArtworkFromMenu() {
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        java.util.Set<FileEntry> targets = new java.util.LinkedHashSet<>();
        if (sel.length > 0) { for (int r : sel) targets.addAll(entriesAtViewRow(r)); }
        else targets.addAll(tableModel.allEntries());
        targets.removeIf(e -> e.status != FileEntry.Status.TAGGED && e.status != FileEntry.Status.IDENTIFIED);
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier identifié ou tagué à compléter.")); return; }
        completeArtwork(new ArrayList<>(targets), false, null);
    }

    private void runPostTagCommandNow() {
        // Toujours appelé exactement à la toute fin réelle de la chaîne Enregistrer (voir les deux
        // points d'appel), jamais entre deux vagues intermédiaires — l'endroit naturel pour afficher
        // la revue de compilations accumulée par groupByCompilations(true), voir son commentaire.
        flushPendingCompilationMatches();
        java.util.List<String> cmds = com.opentagger.PostTagCommands.load();
        int launched = 0;
        for (String cmd : cmds) {
            if (cmd == null || cmd.isBlank()) continue;
            try {
                com.opentagger.PostTagCommands.shell(cmd)
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

    /** Synchronise le nombre d'écoutes Last.fm — même mécanique que {@link #syncListenBrainz}, voir
     *  son commentaire (LastFmSyncWorker/LastFmClient.fetchTopTrackCounts() à la place). */
    private void syncLastFm() {
        Optional<WorkerHub.TaskHandle> running = WorkerHub.get().current(WorkerHub.TaskKind.LASTFM_SYNC);
        if (running.isPresent()) {
            running.get().cancel();
            setStatus(I18n.t("Synchronisation Last.fm annulée."));
            return;
        }
        if (!WorkerHub.get().blockers(WorkerHub.TaskKind.LASTFM_SYNC).isEmpty()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin avant de synchroniser Last.fm."));
            return;
        }

        if (Config.get().lastfmUsername().isBlank()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Configurez d'abord votre nom d'utilisateur Last.fm dans Préférences → APIs."),
                "Last.fm", JOptionPane.INFORMATION_MESSAGE);
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

        beginProgress(ProgressSlot.LASTFM_SYNC);
        progressBars.get(ProgressSlot.LASTFM_SYNC).setIndeterminate(true);
        setStatus(I18n.t("Synchronisation Last.fm…"));

        LastFmSyncWorker w = new LastFmSyncWorker(
            targets,
            msg -> SwingUtilities.invokeLater(() -> setStatus(msg)),
            entry -> SwingUtilities.invokeLater(() -> { tableModel.update(entry); appendLog(entry); refreshStats(); })
        );
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                SwingUtilities.invokeLater(() -> {
                    progressBars.get(ProgressSlot.LASTFM_SYNC).setIndeterminate(false);
                    endProgress(ProgressSlot.LASTFM_SYNC);
                    refreshStats();
                });
            }
        });
        WorkerHub.get().submit(WorkerHub.TaskKind.LASTFM_SYNC,
                I18n.t("Synchronisation Last.fm"), w, () -> w.cancel(false));
    }

    /** Synchronise les compteurs d'écoute — ListenBrainz et Last.fm fusionnés en une seule action de
     *  menu (2026-09-02, retour utilisateur "trop d'options" : deux entrées quasi identiques, seul
     *  le fournisseur changeait). Lance chacun des deux services dont le nom d'utilisateur est
     *  configuré (silencieusement ignoré sinon — jamais deux popups redondants) ; le message "pas
     *  configuré" ne s'affiche que si AUCUN des deux ne l'est. syncListenBrainz()/syncLastFm() gardent
     *  chacune leur propre WorkerHub.TaskKind et leur propre worker — seul le point d'entrée visible
     *  a été fusionné, pas le mécanisme interne (services et API réellement distincts).
     */
    /**
     * Synchronisation AUTOMATIQUE des compteurs d'écoute, déclenchée à la fin d'un scan de dossier
     * (donc une fois les tags des fichiers audio lus) si la case « Options de taguage » est cochée.
     * Au plus une fois par 24 h (PlayCounts.dueForAutoSync) : le classement en ligne est récupéré en
     * UNE requête par service, mais le refaire à chaque dossier ouvert serait inutile. Silencieuse :
     * aucune fenêtre, rien si aucun service n'est configuré, taguage en cours ou scan encore actif
     * (elle sera retentée au prochain scan). ListenBrainz puis Last.fm s'exécutent l'un APRÈS l'autre :
     * les deux réécrivent les mêmes fichiers, les lancer ensemble risquerait de perdre une mise à jour.
     */
    private void scheduleAutoPlayCountSync() {
        if (!activeScanWorkers.isEmpty()) return;   // attendre la fin de TOUS les scans en cours
        long now = System.currentTimeMillis();
        if (!com.opentagger.PlayCounts.dueForAutoSync(Config.get().lastAutoPlayCountSyncMs(), now)) return;
        boolean hasLb = !Config.get().listenbrainzUsername().isBlank();
        // Last.fm exige en plus une clé API (voir LastFmClient) : sans elle, rien à tenter.
        boolean hasLf = !Config.get().lastfmUsername().isBlank() && !Config.get().lastfmKey().isBlank();
        if (!hasLb && !hasLf) return;
        if (WorkerHub.get().current(WorkerHub.TaskKind.LISTENBRAINZ_SYNC).isPresent()
                || WorkerHub.get().current(WorkerHub.TaskKind.LASTFM_SYNC).isPresent()) return;
        if (!WorkerHub.get().blockers(WorkerHub.TaskKind.LISTENBRAINZ_SYNC).isEmpty()) return; // taguage en cours
        Config.get().setLastAutoPlayCountSyncMs(now);
        System.out.println("[OT] Synchronisation automatique des compteurs d'écoute (fin de scan).");
        if (hasLb) {
            syncListenBrainz();
            if (hasLf) startLastFmAfterListenBrainz();
        } else {
            syncLastFm();
        }
    }


    /** Attend la fin de la synchronisation ListenBrainz puis lance celle de Last.fm (jamais en parallèle). */
    private void startLastFmAfterListenBrainz() {
        javax.swing.Timer t = new javax.swing.Timer(2000, null);
        t.addActionListener(e -> {
            if (WorkerHub.get().current(WorkerHub.TaskKind.LISTENBRAINZ_SYNC).isPresent()) return; // encore en cours
            t.stop();
            if (WorkerHub.get().blockers(WorkerHub.TaskKind.LASTFM_SYNC).isEmpty()) syncLastFm();
        });
        t.start();
    }

    private void syncPlayCounts() {
        boolean hasLb = !Config.get().listenbrainzUsername().isBlank();
        boolean hasLf = !Config.get().lastfmUsername().isBlank();
        if (!hasLb && !hasLf) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Configurez d'abord votre nom d'utilisateur ListenBrainz et/ou Last.fm dans Préférences → APIs."),
                I18n.t("Synchronisation"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (hasLb) syncListenBrainz();
        if (hasLf) syncLastFm();
    }

    private void forceRetag() {
        // Cible : lignes sélectionnées si ≥1, sinon tous les fichiers TAGGED
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        if (sel.length > 0) {
            // entriesAtViewRow() — même correctif que startTagging()/refreshSelectedMeta() (voir
            // leur commentaire) : tableModel.get(table.convertRowIndexToModel(r)) se trompe de
            // fichier (ou explose) dès que la vue arborescence est active.
            java.util.Set<FileEntry> targetSet = new java.util.LinkedHashSet<>();
            for (int r : sel) targetSet.addAll(entriesAtViewRow(r));
            targets.addAll(targetSet);
        } else {
            // allEntries() : "de tout" doit couvrir toute la bibliothèque, pas juste la vue
            // filtrée du moment — sinon un re-taguage "de tout" limité aux fichiers visibles.
            for (FileEntry e : tableModel.allEntries()) {
                if (e.status == FileEntry.Status.TAGGED) targets.add(e);
            }
        }
        forceRetagOn(targets);
    }

    /** Entrées sélectionnées dans le tableau, vue liste OU arborescente (même résolution que forceRetag()).
     *  Package-privé : AudioTagAuditPanel, qui audite "la sélection" sans dépendre de la JTable. */
    List<FileEntry> selectedEntries() {
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        java.util.Set<FileEntry> set = new java.util.LinkedHashSet<>();
        for (int r : sel) set.addAll(entriesAtViewRow(r));
        return new ArrayList<>(set);
    }

    /**
     * Confirme puis lance "Forcer le re-taguage" sur une liste explicite de fichiers — factorisé
     * hors de forceRetag() (2026-09-02) pour que d'autres fenêtres de revue (ex.
     * DurationMismatchReviewPanel, dont le lot "à revérifier"/"probablement cassé" ne proposait
     * jusqu'ici qu'une correspondance manuelle fichier par fichier — impraticable sur plusieurs
     * centaines d'entrées) puissent déclencher le même re-taguage ciblé sans dépendre de la
     * sélection de la fenêtre principale. Package-privé : appelé depuis ui/*.
     */
    void forceRetagOn(List<FileEntry> targets) {
        // Cette action lance elle aussi un TaggingWorker (via launchForcedTagging) — même garde
        // que startTagging()/autoCompleteIncomplete()/completeAlbums(), sinon un taguage déjà actif
        // continuerait de tourner pendant qu'un second démarre par-dessus.
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            // Trace stdout — même angle mort que startTagging()/Enregistrer/Compilations (voir
            // leurs commentaires) : retour utilisateur 2026-09-02 "reforcer taguage ne fonctionne
            // plus", très probablement ce blocage silencieux (un taguage normal tournait déjà au
            // moment du clic), jamais signalé nulle part hors de la barre de statut GUI.
            System.out.println("[OT] Forcer re-taguage : sauté — un taguage tourne déjà.");
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            System.out.println("[OT] Forcer re-taguage : sauté — encore en cours : " + String.join(", ", blockers));
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de forcer le re-taguage.",
                    String.join(", ", blockers)));
            return;
        }
        // Pas de garde sur SAVE : voir le commentaire de saveAll() — forceRetag() ne cible
        // que des fichiers déjà TAGGED, jamais touchés par un Enregistrement en cours (qui ne
        // prend que les IDENTIFIED) — ensembles disjoints, sûr de tourner en même temps.
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier sélectionné à re-taguer.")); return; }

        int confirm = JOptionPane.showConfirmDialog(this,
            I18n.t("%d fichier(s) vont être remis en PENDING et leur cache effacé.\nIls seront re-tagués au prochain lancement du taguage.", targets.size()),
            I18n.t("Forcer le re-taguage"), JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) {
            System.out.println("[OT] Forcer re-taguage : annulé par l'utilisateur (" + targets.size() + " fichier(s) proposé(s)).");
            return;
        }

        System.out.println("[OT] Forcer re-taguage : confirmé — " + targets.size() + " fichier(s).");
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
            // entriesAtViewRow() — même correctif que forceRetag()/startTagging() (voir leur
            // commentaire) : tableModel.get(table.convertRowIndexToModel(r)) se trompe de fichier
            // (ou explose) dès que la vue arborescence est active.
            java.util.Set<FileEntry> targetSet = new java.util.LinkedHashSet<>();
            for (int r : sel) targetSet.addAll(entriesAtViewRow(r));
            for (FileEntry e : targetSet) {
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

    /** Devinette Bandcamp à la demande (FileEntry.bandcampOnly, voir TaggingWorker.
     *  tryBandcampGuess()) — sortie de la cascade automatique le 2026-09-19 (rendement mesuré en
     *  prod : ~0,4%, 4 succès / 919 essais) pour devenir une action ciblée façon MetaGrater de
     *  SongKong : appliquée seulement à la sélection (ou à tous les "Non identifiés" si rien n'est
     *  sélectionné), pas à tout le lot à chaque taguage. Contrairement à reidentifyUnmatched(), pas
     *  de resetForReidentification() : ce mode ne touche ni le cache ni les MBID du fichier (rien à
     *  effacer sur un fichier déjà SKIPPED), juste ses champs en mémoire. */
    private void tryBandcampOnUnmatched() {
        if (WorkerHub.get().current(WorkerHub.TaskKind.TAGGING).isPresent()) {
            setStatus(I18n.t("Taguage en cours — attendez la fin ou cliquez sur Annuler."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.TAGGING);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant d'essayer Bandcamp.",
                    String.join(", ", blockers)));
            return;
        }
        int[] sel = table != null ? table.getSelectedRows() : new int[0];
        List<FileEntry> targets = new ArrayList<>();
        if (sel.length > 0) {
            java.util.Set<FileEntry> targetSet = new java.util.LinkedHashSet<>();
            for (int r : sel) targetSet.addAll(entriesAtViewRow(r));
            for (FileEntry e : targetSet) {
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        } else {
            for (FileEntry e : tableModel.allEntries()) {
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        }
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier \"Non identifié\" à essayer sur Bandcamp.")); return; }

        int confirm = JOptionPane.showConfirmDialog(this,
            I18n.t("<html>Deviner une page Bandcamp pour %d fichier(s) \"Non identifié\" à partir de<br>"
                 + "leurs tags artiste/titre actuels — rendement mesuré faible en pratique (~0,4%%).<br><br>"
                 + "Une fenêtre de revue s'ouvrira ensuite pour confirmer les résultats trouvés,<br>"
                 + "rien n'est appliqué automatiquement.</html>", targets.size()),
            I18n.t("Essayer Bandcamp"), JOptionPane.OK_CANCEL_OPTION);
        if (confirm != JOptionPane.OK_OPTION) return;

        for (FileEntry e : targets) {
            e.status      = FileEntry.Status.PENDING;
            e.message     = "";
            e.result      = null;
            e.candidates  = null;
            e.bandcampOnly = true;
            tableModel.update(e);
        }
        launchForcedTagging(targets, true, true);
    }

    /** Nettoie le nom RÉEL du fichier sur disque des pistes "Non identifié" (retire un préfixe
     *  d'identifiant de catalogue/téléchargement — voir {@link TaggingWorker#stripLeadingNumericPrefix},
     *  la même règle qui corrige déjà l'analyse interne du nom de fichier dans
     *  TaggingWorker.parseFilename() — et un suffixe "-temp-NNNNN" résiduel d'un outil externe,
     *  voir {@link TaggingWorker#stripTrailingTempSuffix}), avec ou sans retenter l'identification
     *  ensuite (choix fait dans la boîte de dialogue elle-même, voir juste en dessous) — les deux
     *  restent utiles séparément : comparer d'abord le résultat du nettoyage seul, ou laisser la
     *  ré-identification automatique s'en charger plus tard via chkAutoReidentifyUnmatched.
     *  Fusionné (2026-09-07, retour utilisateur "trop d'options") : "Nettoyer les noms" et "Nettoyer
     *  les noms + Ré-identifier" étaient deux items de menu pour la même méthode {@code cleanNames}
     *  (ci-dessous), juste un booléen différent — le choix se fait maintenant dans LA boîte de
     *  dialogue elle-même plutôt que d'exiger deux entrées de menu séparées pour la même action.
     *  Le "+ Ré-identifier" existe depuis le 2026-08-11, après avoir trouvé qu'un grand nombre de
     *  pistes "Non identifié" portent un identifiant à 4-5 chiffres en tête ("16741 - Dr. Dre -
     *  What's The Difference.mp3") qui pollue déjà l'analyse interne. */
    private void cleanNames() {
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
            // entriesAtViewRow() — même correctif que forceRetag()/reidentifyUnmatched().
            java.util.Set<FileEntry> targetSet = new java.util.LinkedHashSet<>();
            for (int r : sel) targetSet.addAll(entriesAtViewRow(r));
            for (FileEntry e : targetSet) {
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        } else {
            for (FileEntry e : tableModel.allEntries()) {
                if (e.status == FileEntry.Status.SKIPPED) targets.add(e);
            }
        }
        if (targets.isEmpty()) { setStatus(I18n.t("Aucun fichier \"Non identifié\" à nettoyer.")); return; }

        Object[] options = { I18n.t("Nettoyer seulement"), I18n.t("Nettoyer + Ré-identifier"), I18n.t("Annuler") };
        int choice = JOptionPane.showOptionDialog(this,
            I18n.t("<html>Nettoyer le nom de fichier de %d piste(s) \"Non identifié\"<br>"
                 + "(retire un préfixe d'identifiant de catalogue/téléchargement, ex. \"16741 - \").<br><br>"
                 + "Renommage sur disque uniquement (jamais d'écrasement d'un fichier existant).<br><br>"
                 + "\"Nettoyer + Ré-identifier\" retente ensuite l'identification par empreinte audio<br>"
                 + "sur les fichiers renommés — une fenêtre de revue s'ouvrira pour confirmer les<br>"
                 + "résultats trouvés, rien n'est appliqué automatiquement.</html>", targets.size()),
            I18n.t("Nettoyer les noms"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, options, options[0]);

        if (choice == 0) {
            cleanFilenames(targets, () -> {}); // cleanFilenames.done() affiche déjà le compte renommé
        } else if (choice == 1) {
            cleanFilenames(targets, () -> reidentifyUnmatched(targets));
        }
    }

    /** Renomme sur disque (sans déplacer) chaque fichier de {@code targets} dont le nom nettoyé
     *  diffère du nom actuel — voir {@link #cleanNames()}. En arrière-plan
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
        // Parallélisé + log de progression (2026-09-04, trouvé en direct : sur un lot de 149562
        // fichiers, cette passe restait bloquée en SILENCE (aucun log ici, contrairement à tout le
        // reste du pipeline) sur UN SEUL thread pendant 4h+ sans qu'aucune identification réelle
        // n'ait commencé — confirmé via jstack (aucun thread de taguage actif) et file_history
        // (aucune écriture depuis des heures). Même pool "batch.threads" + même motif "cachePool"
        // que InfoCompleterWorker/TaggingWorker, jusqu'ici la seule passe en lot à tourner sur un
        // thread unique sans jamais logguer sa progression.
        System.out.println("[OT] Réinitialisation : démarrage — " + targets.size() + " fichier(s).");
        new SwingWorker<Void, FileEntry>() {
            private final java.util.concurrent.atomic.AtomicInteger doneCount = new java.util.concurrent.atomic.AtomicInteger();
            @Override protected Void doInBackground() {
                int threads = Math.max(1, Config.get().batchThreads());
                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
                java.util.concurrent.BlockingQueue<MetadataCache> cachePool =
                        new java.util.concurrent.LinkedBlockingQueue<>();
                for (int i = 0; i < threads; i++) cachePool.add(new MetadataCache());
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                try {
                    for (FileEntry e : targets) {
                        futures.add(pool.submit(() -> {
                            MetadataCache cache = cachePool.poll();
                            if (cache == null) cache = new MetadataCache();
                            try {
                                resetOneForReidentification(e, cache);
                            } finally {
                                cachePool.offer(cache);
                                publish(e);
                                int n = doneCount.incrementAndGet();
                                if (n % 500 == 0 || n == targets.size()) {
                                    System.out.println("[OT] Réinitialisation : " + n + " / " + targets.size());
                                }
                            }
                        }));
                    }
                    // WorkerHub.awaitAll() au lieu d'une boucle f.get() nue (2026-09-19, audit
                    // dédié "blocages silencieux") : le correctif du 2026-09-04 juste au-dessus a
                    // ajouté la parallélisation ET le log de progression, mais pas de VRAI timeout
                    // sur cette boucle — un seul item bloqué pouvait donc reproduire exactement le
                    // même blocage silencieux de 4h+ qu'à l'origine, juste sans le symptôme
                    // "aucune activité" (le log de progression aurait quand même stagné au dernier
                    // multiple de 500, mais rien ne l'aurait signalé comme anormal).
                    WorkerHub.awaitAll(pool, futures, WorkerHub.defaultFutureTimeoutSec());
                } finally {
                    for (MetadataCache c : cachePool) c.close();
                }
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
                System.out.println("[OT] Réinitialisation : terminée — " + targets.size() + " fichier(s).");
                refreshStats();
                onDone.run();
            }
        }.execute();
    }

    /** Un fichier de resetForReidentification() — extrait pour tourner en parallèle (voir son
     *  commentaire). N'écrit sur le disque QUE si un champ MusicBrainz était vraiment présent à
     *  effacer — avant ce correctif, af.commit() tournait pour CHAQUE fichier sans condition, donc
     *  une lecture+réécriture complète même sur les fichiers sans aucun MBID à retirer. */
    private void resetOneForReidentification(FileEntry e, MetadataCache cache) {
        File fichier = e.currentPath != null ? e.currentPath.toFile() : e.file;
        // Effacer le cache pour ce fichier
        cache.recordFileTagging(fichier.getAbsolutePath(), null);
        // Effacer les MBIDs du fichier audio pour forcer une nouvelle identification
        // (sinon MUSICBRAINZ_TRACK_ID est relu et peut donner un mauvais résultat en cache)
        try {
            AudioFile af = AudioFileIO.read(fichier);
            Tag tag = af.getTag();
            if (tag != null) {
                boolean changed = false;
                for (FieldKey fk : new FieldKey[]{FieldKey.MUSICBRAINZ_TRACK_ID, FieldKey.MUSICBRAINZ_ARTISTID,
                        FieldKey.MUSICBRAINZ_RELEASEID, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID}) {
                    if (!tag.getFirst(fk).isBlank()) { tag.deleteField(fk); changed = true; }
                }
                if (changed) af.commit();
            }
        } catch (Exception ignored) {}
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
        // entriesAtViewRow() — même correctif que forceRetag()/fixEncoding().
        java.util.Set<FileEntry> targetSet = new java.util.LinkedHashSet<>();
        for (int r : sel) targetSet.addAll(entriesAtViewRow(r));
        List<FileEntry> targets = new ArrayList<>(targetSet);

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
            // country/releaseType/originalYear ajoutés (2026-09-01, rattrapage demandé par
            // l'utilisateur) : voir InfoCompleterWorker.needsMb, même raison — ces 3 champs
            // restaient vides même sur des fichiers par ailleurs déjà complets ici.
            boolean incomplete = ti == null
                || ti.artistMbid.isBlank()   || ti.recordingMbid.isBlank()
                || ti.album.isBlank()        || ti.year.isBlank()
                || ti.genre.isBlank()        || ti.mood.isBlank()
                || ti.bpm.isBlank()          || ti.lyrics.isBlank()
                || ti.country.isBlank()      || ti.releaseType.isBlank() || ti.originalYear.isBlank();
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

        // RenamePreviewDialog.compute() appelle Files.exists() par fichier tagué (détection de
        // collision, voir FileRenamer.previewTarget()) — un aller-retour disque RÉEL par fichier.
        // Appelé jusqu'ici en direct sur l'EDT : sur une bibliothèque de plusieurs centaines de
        // milliers de fichiers tagués et un disque contentionné, ça gelait l'interface entière
        // (aucun répaint) pendant potentiellement des dizaines de minutes — repéré en direct
        // (2026-08-24), pile EDT bloquée dans UnixNativeDispatcher.access0() via ce même appel.
        // Même correctif que "Tout tagger" (voir son commentaire, 2026-08-09) pour exactement la
        // même classe de problème : déplacer le calcul en arrière-plan, garder l'EDT seulement
        // pour l'affichage final.
        setStatus(I18n.t("Préparation de l'aperçu de renommage (%d fichier(s) tagué(s))…", tagged));
        // Snapshot pris ICI, sur l'EDT — jamais tableModel.allEntries() directement dans
        // doInBackground() : un taguage actif en parallèle peut muter la table pendant le calcul
        // (Files.exists() par fichier, potentiellement long), levant un ConcurrentModificationException
        // sur la vue non-modifiable (simple wrapper, pas une copie) — voir RenamePreviewDialog.compute().
        List<FileEntry> snapshotForPreview = new ArrayList<>(tableModel.allEntries());
        new SwingWorker<List<RenamePreviewDialog.PreviewRow>, Void>() {
            @Override protected List<RenamePreviewDialog.PreviewRow> doInBackground() {
                return RenamePreviewDialog.compute(snapshotForPreview, currentMask);
            }
            @Override protected void done() {
                List<RenamePreviewDialog.PreviewRow> preview;
                try { preview = get(); } catch (Exception ex) {
                    setStatus(I18n.t("Échec de la préparation de l'aperçu : %s", ex.getMessage()));
                    return;
                }
                if (preview.isEmpty()) { setStatus(I18n.t("Aucun fichier tagué à renommer.")); return; }
                setStatus(" ");
                // ── 2. Afficher l'aperçu — non-modal avec barre de progression ───────
                RenamePreviewDialog dlg = new RenamePreviewDialog(MainFrame.this, preview, buildRenameJob(currentMask, null));
                dlg.setVisible(true);
            }
        }.execute();
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

    private void transcodeFiles() {
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

        // Construire la liste des fichiers à transcoder — lignes sélectionnées dans le tableau si
        // ≥1, sinon repli sur les cases à cocher (e.selected) de toute la bibliothèque chargée.
        // Fusionné (2026-09-02, retour utilisateur) : ce bouton ignorait TOUJOURS la sélection de
        // lignes (appelé en dur avec selectionOnly=false depuis la barre d'outils/Ctrl+T/menu),
        // balayant quasi toute la bibliothèque à chaque clic puisque FileEntry.selected vaut true
        // par défaut — un item de menu séparé ("Transcoder la sélection…") existait pour l'autre
        // cas, mais ce n'est jamais celui que déclenchait le bouton principal. Même réflexe
        // "sélection sinon tout" que forceRetag()/reidentifyUnmatched() ailleurs dans ce fichier.
        List<FileEntry> toTranscode = new ArrayList<>();
        int[] selRows = table.getSelectedRows();
        if (selRows.length > 0) {
            // entryAtViewRow() (pas tableModel.get(table.convertRowIndexToModel(r)), même bug de
            // fond qu'applyDetail()/openBandcampDialog() en vue arborescence — voir leurs
            // commentaires) : trouvé en direct 2026-09-01 en auditant les modes d'affichage.
            // PARTICULIÈREMENT grave ici — pas juste un mauvais tag écrit, le transcodage
            // REMPLACE/SUPPRIME le fichier source (voir le commentaire juste au-dessus) : en vue
            // Arborescence par album, ce chemin pouvait transcoder/supprimer un fichier totalement
            // différent de celui réellement sélectionné à l'écran. Une ligne d'en-tête de groupe
            // sélectionnée résout vers TOUS ses membres (entriesAtViewRow), cohérent avec "l'album
            // entier" plutôt que silencieusement ignorée — contrairement à applyDetail() où éditer
            // "tout l'album" d'un coup n'aurait pas de sens pour des champs texte. Dédoublonné (Set
            // par identité) : sélectionner à la fois un en-tête de groupe ET une de ses pistes
            // ajouterait sinon cette piste deux fois, transcodant/supprimant son propre résultat.
            java.util.Set<FileEntry> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int row : selRows) {
                for (FileEntry e : entriesAtViewRow(row)) {
                    if (e.currentPath != null && seen.add(e)) toTranscode.add(e);
                }
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

        String confirm = I18n.t(
            "<html>Transcoder <b>%d fichier(s)</b> → <b>%s</b>%s ?<br><br>" +
            "<small>Format configuré dans Préférences → Transcodage.</small></html>",
            toTranscode.size(),
            fmt.id.toUpperCase(),
            fmt.hasBitrate ? " " + bitrate + " kbps" : " (lossless)");

        int r = JOptionPane.showConfirmDialog(this, confirm,
                I18n.t("Transcoder"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

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
        if (pendingCompilationMatches.isEmpty()) {
            System.out.println("[OT] Compilations : fin de chaîne atteinte, rien à revoir (0 correspondance accumulée).");
            return;
        }
        if (compilationMatchDialogOpen) {
            // Ne PAS vider pendingCompilationMatches ici : la fenêtre déjà ouverte finira par se
            // fermer, et tout ce qui s'est accumulé depuis (y compris ce que cet appel voulait
            // montrer) sera proposé à la prochaine occasion — jamais perdu, juste reporté.
            System.out.println("[OT] Compilations : fenêtre de revue déjà ouverte — report (toujours "
                    + pendingCompilationMatches.size() + " en attente).");
            return;
        }
        System.out.println("[OT] Compilations : fin de chaîne atteinte — fenêtre de revue affichée pour "
                + pendingCompilationMatches.size() + " correspondance(s) accumulée(s).");
        List<CompilationClusterWorker.CompilationMatch> toReview = new ArrayList<>(pendingCompilationMatches);
        pendingCompilationMatches.clear();
        compilationMatchDialogOpen = true;
        try {
            new CompilationMatchDialog(this, toReview, tableModel).setVisible(true);
        } finally {
            compilationMatchDialogOpen = false;
        }
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
            // Trace stdout — avant ce correctif, statusCallback/logLine n'écrivaient que dans des
            // composants GUI (barre de statut/panneau Journal), invisibles depuis l'extérieur de
            // l'appli (aucune trace dans le fichier de sortie standard) : impossible de vérifier si
            // "Grouper par compilations automatiquement" se déclenchait vraiment ou était sauté en
            // silence — trouvé en direct 2026-08-31 en cherchant justement à répondre à cette
            // question. Ce chemin-ci (bloqué par une autre passe LIBRARY_WRITE, ex. la Complétion
            // automatique après un Enregistrement qui vient de démarrer) est justement le cas le
            // plus probable de "ne se déclenche jamais" sans que rien ne le signale nulle part.
            System.out.println("[OT] Compilations : passe sautée (encore en cours : "
                    + String.join(", ", blockers) + ")");
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de grouper par compilations.", String.join(", ", blockers)));
            onDone.run();
            return;
        }
        System.out.println("[OT] Compilations : recherche de correspondances démarrée…");
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
            s -> { System.out.println("[OT] Compilations : " + s); appendLogLine(s, FileEntry.Status.IDENTIFIED, null); },
            matches -> SwingUtilities.invokeLater(() -> {
                endProgress(ProgressSlot.COMPILATION_CLUSTER);
                if (matches.isEmpty()) {
                    System.out.println("[OT] Compilations : terminé, aucune correspondance trouvée.");
                    setStatus(I18n.t("Aucune correspondance de compilation trouvée."));
                } else if (deferDialog) {
                    // Dédoublonnage par FileEntry (identité, pas equals()) — sans lui, un fichier
                    // dont la correspondance est déjà en attente (jamais encore écrite sur disque,
                    // donc entry.result ne change pas) ressortait identique à CHAQUE nouvelle vague
                    // de "Grouper par compilations" (une par Enregistrement) et se réajoutait en
                    // double, triple... dans pendingCompilationMatches — la garde "unchanged" de
                    // CompilationClusterWorker.checkEntry() ne compare qu'à ce qui est déjà sur
                    // disque, jamais à ce qui attend encore une revue. Repéré en direct 2026-09-01 :
                    // deux fenêtres de revue ouvertes en même temps, la même piste ("Tracie Spencer -
                    // It's All About You") présente jusqu'à 3 fois dans UNE SEULE fenêtre.
                    java.util.Set<FileEntry> alreadyPending = java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>());
                    for (CompilationClusterWorker.CompilationMatch m : pendingCompilationMatches) alreadyPending.add(m.entry());
                    int before = matches.size();
                    List<CompilationClusterWorker.CompilationMatch> newOnes = matches.stream()
                            .filter(m -> alreadyPending.add(m.entry()))
                            .toList();
                    pendingCompilationMatches.addAll(newOnes);
                    System.out.println("[OT] Compilations : " + newOnes.size() + " correspondance(s) trouvée(s) "
                            + "cette vague" + (newOnes.size() != before ? " (" + (before - newOnes.size()) + " doublon(s) ignoré(s))" : "")
                            + " — " + pendingCompilationMatches.size() + " en attente au total (revue différée en fin de session).");
                    setStatus(I18n.t("%d correspondance(s) de compilation en attente (fin de session).", pendingCompilationMatches.size()));
                } else {
                    System.out.println("[OT] Compilations : " + matches.size() + " correspondance(s) — fenêtre de revue affichée.");
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

        // 3. Aperçu — même correctif que renameTagged() (voir son commentaire) : compute() fait un
        // Files.exists() disque par fichier, jamais en direct sur l'EDT sur une grosse bibliothèque.
        final int maskForPreview = organizeMask;
        final Path destForPreview = organizeDestRoot;
        setStatus(I18n.t("Préparation de l'aperçu d'organisation…"));
        // Snapshot sur l'EDT — même correctif que renameTagged() ci-dessus (voir son commentaire).
        List<FileEntry> snapshotForOrganize = new ArrayList<>(tableModel.allEntries());
        new SwingWorker<List<RenamePreviewDialog.PreviewRow>, Void>() {
            @Override protected List<RenamePreviewDialog.PreviewRow> doInBackground() {
                return RenamePreviewDialog.compute(snapshotForOrganize, maskForPreview, destForPreview);
            }
            @Override protected void done() {
                List<RenamePreviewDialog.PreviewRow> preview;
                try { preview = get(); } catch (Exception ex) {
                    setStatus(I18n.t("Échec de la préparation de l'aperçu : %s", ex.getMessage()));
                    return;
                }
                if (preview.isEmpty()) { setStatus(I18n.t("Aucun fichier à organiser.")); return; }
                setStatus(" ");
                RenamePreviewDialog dlg = new RenamePreviewDialog(MainFrame.this,
                        preview, I18n.t("Organiser en dossiers"), buildRenameJob(maskForPreview, destForPreview));
                dlg.setVisible(true);
            }
        }.execute();
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

    /** Lit les champs d'un fichier audio — voir {@link com.opentagger.TagReader} (extrait le 2026-09-20). */
    TagInfo readTags(File f) {
        return com.opentagger.TagReader.read(f);
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
        // entryAtViewRow() (pas tableModel.get(table.convertRowIndexToModel(r)), même bug de fond
        // qu'applyDetail()/transcodeFiles() en vue arborescence — voir leurs commentaires) : trouvé
        // en direct 2026-09-01. Particulièrement facile à déclencher ici (un simple clic sur un chip
        // de statut) — en vue Arborescence par album, albumTreeModel a PLUS de lignes que tableModel
        // (les en-têtes de groupe s'ajoutent), donc modelRow pouvait dépasser tableModel.getRowCount()
        // et lever une exception, ou pire, renvoyer le statut d'un fichier totalement différent.
        // entryAtViewRow() renvoie null pour un en-tête, filtré naturellement par le null-check.
        // UNE seule mise à jour de la sélection (voir RowSelection) : ligne par ligne, chaque ajout relançait le panneau de détail sur
        // toute la sélection déjà faite — quadratique, interface gelée pendant des heures sur 50 000 fichiers.
        List<Integer> matching = new ArrayList<>();
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            FileEntry e = entryAtViewRow(viewRow);
            if (e != null && set.contains(e.status)) matching.add(viewRow);
        }
        RowSelection.select(table.getSelectionModel(), matching);
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

    /**
     * Rapport agrégé/compact pour diagnostic externe — voir échange du 2026-09-07 : l'utilisateur
     * proposait un enregistreur d'activité exhaustif (tout scan/requête réseau/décision) à me
     * transmettre entre deux sessions plutôt que de coller des extraits de journal à la main.
     * Écarté : un tel enregistrement, sur plusieurs jours, coûterait probablement PLUS cher à relire
     * en totalité qu'il ne ferait gagner, et n'aurait de toute façon pas révélé la plupart des vrais
     * bugs trouvés cette nuit-là (contention de sémaphore, triple exécution concurrente...), qui
     * n'existent QUE dans l'état d'un processus vivant (jstack, requêtes SQL live), jamais dans un
     * journal passif. À la place : UN fichier JSON compact combinant plusieurs vues déjà existantes
     * (rapport Non identifiés, revue durées incohérentes) + quelques agrégats nouveaux (sources
     * d'identification, échantillon d'erreurs réelles) — pensé pour être transmis tel quel.
     */
    private void exportDiagnosticReport() {
        if (tableModel.allEntries().isEmpty()) { setStatus(I18n.t("Aucun fichier à exporter.")); return; }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("Exporter le rapport de session"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON (*.json)", "json"));
        fc.setSelectedFile(new File("opentagger-rapport-session.json"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dest = fc.getSelectedFile();
        if (!dest.getName().endsWith(".json")) dest = new File(dest.getAbsolutePath() + ".json");

        try {
            java.util.List<com.opentagger.model.FileEntry> all = new java.util.ArrayList<>(tableModel.allEntries());

            // ── Totaux (mêmes catégories que la barre de stats) ──────────────────
            java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
            int identified = 0, tagged = 0, notIdentified = 0, errors = 0, pending = 0;
            for (com.opentagger.model.FileEntry e : all) {
                switch (e.status) {
                    case IDENTIFIED -> identified++;
                    case TAGGED     -> tagged++;
                    case ERROR      -> errors++;
                    case SKIPPED    -> notIdentified++;
                    default         -> pending++;
                }
            }
            totals.put("total", all.size());
            totals.put("identified", identified);
            totals.put("tagged", tagged);
            totals.put("not_identified_or_skipped", notIdentified);
            totals.put("errors", errors);
            totals.put("pending", pending);

            // ── Causes de SKIPPED/ERROR (même regroupement que NonIdentifiedReportDialog) ───────
            java.util.Map<com.opentagger.model.SkipReason, Integer> reasonCounts =
                    new java.util.EnumMap<>(com.opentagger.model.SkipReason.class);
            int reasonUnknown = 0;
            for (com.opentagger.model.FileEntry e : all) {
                if (e.status != com.opentagger.model.FileEntry.Status.SKIPPED
                        && e.status != com.opentagger.model.FileEntry.Status.ERROR) continue;
                if (e.skipReason == null) { reasonUnknown++; continue; }
                reasonCounts.merge(e.skipReason, 1, Integer::sum);
            }
            java.util.Map<String, Object> skipReasons = new java.util.LinkedHashMap<>();
            reasonCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(en -> skipReasons.put(en.getKey().name(), en.getValue()));
            if (reasonUnknown > 0) skipReasons.put("UNKNOWN_LEGACY_SESSION", reasonUnknown);

            // ── Durée incohérente : même seuil que DurationMismatchReviewPanel (90s), mais un
            // ÉCHANTILLON des plus gros écarts relatifs plutôt que la liste complète (potentiellement
            // des milliers d'entrées — voir la même discussion sur le coût de relecture). ──────────
            record MismatchRow(String file, int fileSec, int mbSec, double ratio, String title) {}
            java.util.List<MismatchRow> mismatches = new java.util.ArrayList<>();
            int mismatchBroken = 0, mismatchReview = 0;
            for (com.opentagger.model.FileEntry e : all) {
                if (!e.durationMismatch) continue;
                int fileSec = e.current != null ? e.current.durationSec : 0;
                int mbSec = (e.candidates != null && !e.candidates.isEmpty())
                        ? e.candidates.get(0).mbDurationSec : 0;
                boolean broken = fileSec > 0 && fileSec < 90;
                if (broken) mismatchBroken++; else mismatchReview++;
                double ratio = (fileSec > 0 && mbSec > 0)
                        ? Math.max(fileSec, mbSec) / (double) Math.min(fileSec, mbSec) : 0;
                mismatches.add(new MismatchRow(e.filename(), fileSec, mbSec, ratio,
                        e.current != null ? e.current.title : ""));
            }
            mismatches.sort((a, b) -> Double.compare(b.ratio(), a.ratio()));
            java.util.List<Object> mismatchSample = new java.util.ArrayList<>();
            for (MismatchRow m : mismatches.subList(0, Math.min(30, mismatches.size()))) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("file", m.file());
                row.put("file_sec", m.fileSec());
                row.put("mb_sec", m.mbSec());
                row.put("title", m.title());
                mismatchSample.add(row);
            }
            java.util.Map<String, Object> durationMismatch = new java.util.LinkedHashMap<>();
            durationMismatch.put("probably_broken_short", mismatchBroken);
            durationMismatch.put("to_review_manually", mismatchReview);
            durationMismatch.put("worst_gaps_sample", mismatchSample);

            // ── Sources d'identification (fichiers déjà identifiés/tagués) ──────────────────────
            java.util.Map<String, Integer> sources = new java.util.LinkedHashMap<>();
            for (com.opentagger.model.FileEntry e : all) {
                if (e.status != com.opentagger.model.FileEntry.Status.IDENTIFIED
                        && e.status != com.opentagger.model.FileEntry.Status.TAGGED) continue;
                String src = e.activeTags().identificationSource;
                sources.merge(src == null || src.isBlank() ? "unknown" : src, 1, Integer::sum);
            }

            // ── Échantillon des dernières erreurs (message réel, pas juste un compteur) ─────────
            java.util.List<Object> errorSample = new java.util.ArrayList<>();
            for (com.opentagger.model.FileEntry e : all) {
                if (e.status != com.opentagger.model.FileEntry.Status.ERROR) continue;
                if (errorSample.size() >= 20) break;
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("file", e.filename());
                row.put("message", e.message);
                errorSample.add(row);
            }

            java.util.Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
            wrapper.put("version", 1);
            wrapper.put("exported", java.time.Instant.now().toString());
            wrapper.put("app_version", com.opentagger.Config.get().appVersion());
            wrapper.put("totals", totals);
            wrapper.put("skip_reasons", skipReasons);
            wrapper.put("duration_mismatch", durationMismatch);
            wrapper.put("identification_sources", sources);
            wrapper.put("error_sample", errorSample);

            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            java.nio.file.Files.writeString(dest.toPath(), om.writeValueAsString(wrapper));
            setStatus(I18n.t("Rapport de session exporté : %s", dest.getName()));
            JOptionPane.showMessageDialog(this,
                I18n.t("Rapport exporté vers\n%s", dest.getAbsolutePath()),
                I18n.t("Export réussi"), JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Erreur export : %s", ex.getMessage()), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
        }
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
                java.util.Map<FileEntry, Long> sizesByFile = DuplicateDetector.computeSizes(groups);
                return new DuplicateDetector.DetectionResult(groups, bestByGroup, sizesByFile);
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
                new DuplicatesDialog(MainFrame.this, groups, result.bestByGroup(), result.sizesByFile(), tableModel,
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
                // TrashHelper.moveToTrash() — voir sa Javadoc (2026-09-05) : Desktop.moveToTrash()
                // n'est PAS supporté sur cette machine, l'ancien code ici tombait donc dans un
                // f.delete() en silence (suppression définitive malgré le message affiché à
                // l'utilisateur). Point de passage unique qui ne supprime jamais définitivement.
                int deleted = 0, failDel = 0;
                for (FileEntry e : corrupt) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    boolean moved = com.opentagger.TrashHelper.moveToTrash(f);
                    if (moved) { deleted++; publish(e); } else { failDel++; }
                }
                return new int[]{ deleted, failDel };
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
                String msg = I18n.t("%d fichier(s) illisible(s) déplacé(s) dans la corbeille", r[0]);
                if (r[1] > 0) msg += I18n.t(", %d échec(s) (permission refusée ?)", r[1]);
                setStatus(msg);
                JOptionPane.showMessageDialog(MainFrame.this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
            }
        }.execute();
    }

    /**
     * Cherche, dans les dossiers de démarrage, les fichiers audio dont l'extension n'est PAS
     * reconnue par AudioScanner — donc invisibles au scan normal, jamais vus par l'appli. Signature
     * laissée par un import Soulseek/beets interrompu (suffixe aléatoire à la place de l'extension,
     * ex. "Titre.NeJrfI") — trouvé en direct 2026-08-30, ~1800 fichiers dans
     * /mnt/MyBook/itunes/Music/a_classer. Regroupée avec "Supprimer les fichiers illisibles" dans le
     * menu Bibliothèque (demande utilisateur) : même famille de nettoyage, complémentaires (celle-ci
     * trouve des fichiers que le scan n'a même jamais vus, l'autre nettoie ceux déjà vus en échec).
     *
     * Sonde chaque candidat via ffprobe (AudioFormatCheck) : propose de renommer ceux qui sont un
     * vrai format audio reconnu, et de déplacer vers la corbeille (jamais suppression définitive,
     * même sécurité que deleteErrorFiles()) ceux qui sont vides/illisibles — deux confirmations
     * séparées, jamais d'action silencieuse. Le reste (extension inconnue, format non déterminable
     * avec confiance) n'est ni renommé ni supprimé, juste compté dans le résumé.
     */
    private void repairMisnamedFiles() {
        if (WorkerHub.get().current(WorkerHub.TaskKind.MISNAMED_REPAIR).isPresent()) {
            setStatus(I18n.t("Recherche de fichiers mal nommés déjà en cours."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.MISNAMED_REPAIR);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de chercher les fichiers mal nommés.",
                    String.join(", ", blockers)));
            return;
        }

        List<String> roots = new ArrayList<>();
        for (String s : Config.get().startupFolders()) if (s != null && !s.isBlank()) roots.add(s);
        if (roots.isEmpty()) {
            setStatus(I18n.t("Aucun dossier de démarrage configuré (Préférences → Dossiers)."));
            return;
        }

        setStatus(I18n.t("Recherche de fichiers audio mal nommés…"));
        SwingWorker<Void, Void> w = new SwingWorker<Void, Void>() {
            // Même liste qu'AudioScanner (dupliquée : privée là-bas, pas la peine d'exposer un
            // accesseur juste pour cette action opportuniste) — ne re-sonde jamais un fichier déjà
            // visible au scan normal.
            final java.util.Set<String> KNOWN_AUDIO = java.util.Set.of(
                ".mp3", ".flac", ".m4a", ".ogg", ".wav", ".aac", ".opus", ".wma", ".ape", ".wv",
                ".aiff", ".aif", ".mpc", ".mp4", ".dsf", ".dff");
            // Types clairement non-audio rencontrés dans une bibliothèque musicale — jamais sondés
            // (juste du bruit : pochettes, playlists, notices...), pour ne pas gaspiller un appel
            // ffprobe par fichier sur des centaines de milliers d'entrées sans rapport.
            final java.util.Set<String> SKIP = java.util.Set.of(
                ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".txt", ".nfo", ".pdf", ".db",
                ".ini", ".log", ".url", ".m3u", ".m3u8", ".cue", ".json", ".xml", ".sfv", ".lrc",
                ".jpe", ".jpe~", ".ds_store", ".m4p", ".mov", ".avi", ".mkv");

            final List<File> renamable = new ArrayList<>();
            final java.util.Map<File, String> suggestedExt = new java.util.HashMap<>();
            final List<File> emptyOrBroken = new ArrayList<>();
            int scanned = 0;

            @Override protected Void doInBackground() {
                for (String root : roots) walk(new File(root));
                return null;
            }

            void walk(File dir) {
                if (isCancelled()) return;
                File[] children = dir.listFiles();
                if (children == null) return;
                for (File f : children) {
                    if (isCancelled()) return;
                    if (f.isDirectory()) { walk(f); continue; }
                    String name = f.getName();
                    if (name.startsWith(".")) continue;
                    String lower = name.toLowerCase();
                    if (KNOWN_AUDIO.stream().anyMatch(lower::endsWith)) continue;
                    if (SKIP.stream().anyMatch(lower::endsWith)) continue;

                    scanned++;
                    if (scanned % 200 == 0)
                        publish(); // process() ignore le contenu, juste pour rafraîchir le statut
                    if (f.length() == 0) { emptyOrBroken.add(f); continue; }

                    String ext = com.opentagger.AudioFormatCheck.suggestCorrectExtension(f);
                    if (ext != null) {
                        renamable.add(f);
                        suggestedExt.put(f, ext);
                    } else if (!com.opentagger.AudioFormatCheck.hasReadableDuration(f)) {
                        emptyOrBroken.add(f);
                    }
                    // ni renommable ni manifestement vide : extension inconnue non résolue avec
                    // assez de confiance, laissée de côté (pas comptée dans les deux listes
                    // d'action, mais dans "scanned").
                }
            }

            @Override protected void process(List<Void> chunks) {
                setStatus(I18n.t("Recherche de fichiers audio mal nommés… %d examinés (%d à renommer, %d vides/cassés)",
                        scanned, renamable.size(), emptyOrBroken.size()));
            }

            @Override protected void done() {
                if (isCancelled()) { setStatus(I18n.t("Recherche annulée.")); return; }
                try { get(); } catch (Exception ex) {
                    setStatus(I18n.t("Erreur pendant la recherche : %s", ex.getMessage()));
                    return;
                }
                if (renamable.isEmpty() && emptyOrBroken.isEmpty()) {
                    setStatus(I18n.t("%d fichier(s) à extension inconnue examiné(s), aucun fichier audio détecté parmi eux.", scanned));
                    return;
                }

                if (!renamable.isEmpty()) {
                    StringBuilder sb = new StringBuilder(I18n.t(
                        "<html><body style='width: 480px'><b>%d fichier(s) audio à extension non reconnue</b> "
                        + "(suffixe aléatoire à la place de l'extension, ex. import interrompu) :<br><br>", renamable.size()));
                    int shown = Math.min(renamable.size(), 8);
                    for (int i = 0; i < shown; i++)
                        sb.append("&nbsp;• ").append(renamable.get(i).getName()).append("<br>");
                    if (renamable.size() > shown)
                        sb.append(I18n.t("&nbsp;… et %d autre(s)<br>", renamable.size() - shown));
                    sb.append(I18n.t("<br>Renommer automatiquement vers l'extension correspondant au contenu "
                        + "réel détecté (ffprobe) ?<br><i>Seul le nom change, rien d'autre n'est modifié — "
                        + "jamais de suppression.</i></html>"));
                    int renameOk = JOptionPane.showConfirmDialog(MainFrame.this, sb.toString(),
                        I18n.t("Fichiers audio mal nommés"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (renameOk == JOptionPane.YES_OPTION) {
                        int renamed = 0, skipped = 0;
                        for (File f : renamable) {
                            String ext = suggestedExt.get(f);
                            String name = f.getName();
                            java.nio.file.Path parent = f.toPath().getParent();
                            java.nio.file.Path dest = parent.resolve(name + "." + ext);
                            for (int n = 2; java.nio.file.Files.exists(dest) && n < 100; n++)
                                dest = parent.resolve(name + " (" + n + ")." + ext);
                            try {
                                java.nio.file.Files.move(f.toPath(), dest);
                                renamed++;
                            } catch (Exception ex) { skipped++; }
                        }
                        setStatus(I18n.t("%d fichier(s) renommé(s), %d échec(s). Un scan retrouvera ces fichiers.", renamed, skipped));
                    }
                }

                if (!emptyOrBroken.isEmpty()) {
                    StringBuilder sb = new StringBuilder(I18n.t(
                        "<html><body style='width: 480px'><b>%d fichier(s) vide(s) ou illisible(s)</b> "
                        + "(0 octet, ou aucune durée audio détectable — transcode/téléchargement probablement "
                        + "interrompu) :<br><br>", emptyOrBroken.size()));
                    int shown = Math.min(emptyOrBroken.size(), 8);
                    for (int i = 0; i < shown; i++)
                        sb.append("&nbsp;• <font color='#cc4444'>").append(emptyOrBroken.get(i).getName()).append("</font><br>");
                    if (emptyOrBroken.size() > shown)
                        sb.append(I18n.t("&nbsp;… et %d autre(s)<br>", emptyOrBroken.size() - shown));
                    sb.append(I18n.t("<br>Déplacer ces fichiers vers la corbeille ?</html>"));
                    int delOk = JOptionPane.showConfirmDialog(MainFrame.this, sb.toString(),
                        I18n.t("Fichiers vides ou illisibles"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (delOk == JOptionPane.YES_OPTION) {
                        // TrashHelper.moveToTrash() — voir sa Javadoc (2026-09-05) : Desktop.
                        // moveToTrash() n'est PAS supporté sur cette machine, l'ancien code ici
                        // tombait donc dans un f.delete() en silence.
                        int deleted = 0, failDel = 0;
                        for (File f : emptyOrBroken) {
                            boolean moved = com.opentagger.TrashHelper.moveToTrash(f);
                            if (moved) deleted++; else failDel++;
                        }
                        setStatus(I18n.t("%d fichier(s) illisible(s) déplacé(s) dans la corbeille%s", deleted,
                                failDel > 0 ? I18n.t(", %d échec(s)", failDel) : ""));
                    }
                }
            }
        };
        WorkerHub.get().submit(WorkerHub.TaskKind.MISNAMED_REPAIR,
                I18n.t("Recherche de fichiers mal nommés"), w, () -> w.cancel(true));
    }

    /**
     * Cherche les dossiers qui n'ont jamais contenu la moindre piste audio — des coquilles vides
     * ou quasi-vides laissées par un import raté (scene-release, yt-dlp, rip CD interrompu…) : un
     * JSON/log/pochette cassée tout seul dans un dossier, jamais de musique. Trouvé en direct
     * 2026-08-30 : "1_-_Clara_Nunes/Unknown_Album/srr.json" (daté décembre 2025, donc bien antérieur
     * à cette campagne de taguage) et "01 [unknown]/Unknown Album" avec un log dedans.
     *
     * Complémentaire — pas redondant — avec {@link #repairMisnamedFiles()} et le nettoyage
     * automatique {@link com.opentagger.FileRenamer#deleteEmptyAncestors}. Ce dernier ne se
     * déclenche QU'en effet de bord d'un renommage réussi (il regarde le dossier D'OÙ une piste
     * vient d'être déplacée) : un dossier qui n'a jamais hébergé de piste — donc jamais de
     * renommage à son sujet — n'est jamais examiné, aussi longtemps que le scan tourne. Cette
     * fonction fait la passe qui manquait : parcourir tout l'arbre et repérer les branches
     * entières sans aucun audio, plutôt que d'attendre un renommage qui ne viendra jamais.
     *
     * Sécurité : ne classe "résidu jetable" que des fichiers de type manifestement non-musical
     * (json/log/nfo/txt/cue/m3u/db/ini/sfv/srr, pochettes locales, AppleDouble) ou des images de
     * moins de 20 Ko (le cas "pochette vide" signalé). Toute extension inconnue est d'abord
     * sondée via {@link com.opentagger.AudioFormatCheck} exactement comme dans
     * {@link #repairMisnamedFiles()} — si c'est en réalité de l'audio lisible mal étiqueté, le
     * dossier n'est PAS considéré orphelin (laissé à repairMisnamedFiles()/traitement normal), pour
     * ne jamais faire disparaître une vraie piste par erreur. Tout fichier "réel" non reconnu (gros
     * fichier, extension inhabituelle) fait basculer le dossier en "à vérifier" — jamais supprimé
     * automatiquement, juste compté dans le résumé.
     */
    private void cleanOrphanFolders() {
        if (WorkerHub.get().current(WorkerHub.TaskKind.ORPHAN_CLEANUP).isPresent()) {
            setStatus(I18n.t("Recherche de dossiers orphelins déjà en cours."));
            return;
        }
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.ORPHAN_CLEANUP);
        if (!blockers.isEmpty()) {
            setStatus(I18n.t("Encore en cours : %s — attendez la fin avant de chercher les dossiers orphelins.",
                    String.join(", ", blockers)));
            return;
        }

        List<String> roots = new ArrayList<>();
        for (String s : Config.get().startupFolders()) if (s != null && !s.isBlank()) roots.add(s);
        if (roots.isEmpty()) {
            setStatus(I18n.t("Aucun dossier de démarrage configuré (Préférences → Dossiers)."));
            return;
        }

        setStatus(I18n.t("Recherche de dossiers orphelins (sans audio)…"));
        SwingWorker<Void, Void> w = new SwingWorker<Void, Void>() {
            final java.util.Set<String> KNOWN_AUDIO = java.util.Set.of(
                ".mp3", ".flac", ".m4a", ".ogg", ".wav", ".aac", ".opus", ".wma", ".ape", ".wv",
                ".aiff", ".aif", ".mpc", ".mp4", ".dsf", ".dff");
            // Types considérés comme résidu jetable partout dans un dossier déjà avéré sans audio —
            // volontairement plus large que FileRenamer.isDeletableLeftover() (qui ne gère que le
            // cas post-renommage, pochette/log opentagger_*/AppleDouble) : ici on couvre aussi les
            // sous-produits d'imports tiers repérés en direct (json, log générique, nfo, cue, sfv,
            // srr...).
            final java.util.Set<String> RESIDUE_EXT = java.util.Set.of(
                ".json", ".log", ".nfo", ".txt", ".cue", ".m3u", ".m3u8", ".db", ".ini", ".sfv",
                ".srr", ".url", ".lrc", ".xml");
            final java.util.Set<String> IMAGE_EXT = java.util.Set.of(
                ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp");
            static final long SMALL_IMAGE_BYTES = 20_000L;

            final List<OrphanDir> trashCandidates = new ArrayList<>();
            int unknownFolders = 0; // contient du contenu non reconnu, jamais touché automatiquement
            int scanned = 0;

            record OrphanDir(File dir, long fileCount, long totalBytes) {}

            @Override protected Void doInBackground() {
                for (String root : roots) {
                    File r = new File(root);
                    if (!scanDir(r)) {
                        // La racine elle-même est entièrement sans audio (cas limite, rare) —
                        // aucun parent au-dessus d'elle pour la signaler, donc on la traite ici.
                        classify(r);
                    }
                }
                return null;
            }

            /** @return true si {@code dir} (ou l'un de ses descendants) contient au moins un fichier
             *  audio reconnu — dans ce cas, les branches enfants sans audio sont signalées ici même
             *  (elles sont "orphelines" par rapport à ce parent qui, lui, contient de la musique
             *  ailleurs). Si {@code dir} entier n'a aucun audio, ne rien signaler ici : laisser
             *  l'appelant (parent) décider — la branche remonte jusqu'à trouver un ancêtre qui a de
             *  l'audio ailleurs, ou jusqu'à la racine du scan si aucun n'en a. */
            boolean scanDir(File dir) {
                if (isCancelled()) return true; // ne rien signaler en cas d'annulation
                // Config.excludedFolders() (2026-09-18) : jamais parcouru ni signalé, comme le
                // scan de démarrage (AudioScanner.isExcluded()) — traité comme "a de l'audio" pour
                // ne jamais remonter comme candidat orphelin, sans même lister son contenu.
                if (isExcludedPath(dir)) return true;
                File[] children = dir.listFiles();
                if (children == null) return true; // inaccessible : ne jamais y toucher
                scanned++;
                if (scanned % 500 == 0) publish();

                boolean hasAudio = false;
                List<File> subOrphans = new ArrayList<>();
                for (File f : children) {
                    if (isCancelled()) return true;
                    if (f.isDirectory()) {
                        if (scanDir(f)) hasAudio = true; else subOrphans.add(f);
                        continue;
                    }
                    String lower = f.getName().toLowerCase();
                    if (lower.startsWith(".")) continue; // AppleDouble/dotfiles, jamais un signal audio
                    if (KNOWN_AUDIO.stream().anyMatch(lower::endsWith)) { hasAudio = true; continue; }
                    // Extension inconnue et fichier non trivial : sonder comme repairMisnamedFiles()
                    // avant de conclure — coût ffprobe limité, car on n'atteint ce point que pour
                    // des dossiers déjà candidats (peu de fichiers).
                    boolean recognizedResidue = RESIDUE_EXT.stream().anyMatch(lower::endsWith)
                            || f.length() == 0
                            || (IMAGE_EXT.stream().anyMatch(lower::endsWith) && f.length() < SMALL_IMAGE_BYTES);
                    if (!recognizedResidue && f.length() > 0) {
                        if (com.opentagger.AudioFormatCheck.suggestCorrectExtension(f) != null
                                || com.opentagger.AudioFormatCheck.hasReadableDuration(f)) {
                            hasAudio = true; // audio mal étiqueté : laisser repairMisnamedFiles() s'en charger
                        }
                    }
                }

                if (hasAudio) {
                    for (File orphan : subOrphans) classify(orphan);
                    return true;
                }
                return false; // dossier entier (fichiers + sous-dossiers) sans aucun audio
            }

            /** {@code dir} est confirmé sans aucun audio dans toute sa descendance — décide s'il ne
             *  contient que du résidu jetable (candidat corbeille) ou du contenu non reconnu (jamais
             *  touché, juste compté). */
            void classify(File dir) {
                long[] stats = {0, 0}; // {fileCount, totalBytes}
                boolean onlyResidue = collectStats(dir, stats);
                if (onlyResidue) {
                    trashCandidates.add(new OrphanDir(dir, stats[0], stats[1]));
                } else {
                    unknownFolders++;
                }
            }

            boolean collectStats(File dir, long[] stats) {
                File[] children = dir.listFiles();
                if (children == null) return true;
                boolean onlyResidue = true;
                for (File f : children) {
                    if (f.isDirectory()) {
                        if (!collectStats(f, stats)) onlyResidue = false;
                        continue;
                    }
                    stats[0]++;
                    stats[1] += f.length();
                    String lower = f.getName().toLowerCase();
                    if (lower.startsWith(".")) continue;
                    boolean residue = RESIDUE_EXT.stream().anyMatch(lower::endsWith)
                            || f.length() == 0
                            || (IMAGE_EXT.stream().anyMatch(lower::endsWith) && f.length() < SMALL_IMAGE_BYTES);
                    if (!residue) onlyResidue = false;
                }
                return onlyResidue;
            }

            @Override protected void process(List<Void> chunks) {
                setStatus(I18n.t("Recherche de dossiers orphelins… %d dossier(s) examiné(s), %d candidat(s) trouvé(s)",
                        scanned, trashCandidates.size()));
            }

            @Override protected void done() {
                if (isCancelled()) { setStatus(I18n.t("Recherche annulée.")); return; }
                try { get(); } catch (Exception ex) {
                    setStatus(I18n.t("Erreur pendant la recherche : %s", ex.getMessage()));
                    return;
                }
                if (trashCandidates.isEmpty() && unknownFolders == 0) {
                    setStatus(I18n.t("%d dossier(s) examiné(s), aucun dossier orphelin trouvé.", scanned));
                    return;
                }

                if (!trashCandidates.isEmpty()) {
                    long totalBytes = trashCandidates.stream().mapToLong(OrphanDir::totalBytes).sum();
                    StringBuilder sb = new StringBuilder(I18n.t(
                        "<html><body style='width: 480px'><b>%d dossier(s) orphelin(s)</b> — jamais eu la "
                        + "moindre piste audio, ne contiennent que des résidus (json/log/nfo/pochette cassée…), "
                        + "%.1f Mo au total :<br><br>", trashCandidates.size(), totalBytes / 1_000_000.0));
                    int shown = Math.min(trashCandidates.size(), 8);
                    for (int i = 0; i < shown; i++) {
                        OrphanDir od = trashCandidates.get(i);
                        sb.append("&nbsp;• ").append(od.dir().getPath())
                          .append(" (").append(od.fileCount()).append(" fichier(s))<br>");
                    }
                    if (trashCandidates.size() > shown)
                        sb.append(I18n.t("&nbsp;… et %d autre(s)<br>", trashCandidates.size() - shown));
                    if (unknownFolders > 0)
                        sb.append(I18n.t("<br>(+ %d dossier(s) sans audio mais avec du contenu non reconnu, "
                                + "laissés de côté — jamais touchés automatiquement.)<br>", unknownFolders));
                    sb.append(I18n.t("<br>Déplacer ces dossiers vers la corbeille ?</html>"));
                    int ok = JOptionPane.showConfirmDialog(MainFrame.this, sb.toString(),
                        I18n.t("Dossiers orphelins"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (ok == JOptionPane.YES_OPTION) {
                        // TrashHelper.moveDirToTrash() — voir sa Javadoc (2026-09-05) : Desktop.
                        // moveToTrash() n'est PAS supporté sur cette machine, l'ancien code ici
                        // tombait donc dans deleteRecursively() — suppression DÉFINITIVE d'un
                        // dossier entier, en silence, malgré le message affiché à l'utilisateur.
                        // RE-vérification juste avant le déplacement (2026-09-18) : depuis que ce
                        // scan peut tourner PENDANT un lot de taguage (voir WorkerHub.conflictsWith,
                        // exception TAGGING/ORPHAN_CLEANUP), un dossier vu "sans audio" au moment du
                        // scan a pu légitimement en recevoir un entre-temps — surtout ici, où
                        // l'utilisateur a pu laisser la boîte de confirmation ouverte un moment.
                        int deleted = 0, failDel = 0, skippedNowNotEmpty = 0;
                        for (OrphanDir od : trashCandidates) {
                            if (hasAnyAudioNow(od.dir())) { skippedNowNotEmpty++; continue; }
                            boolean moved = com.opentagger.TrashHelper.moveDirToTrash(od.dir());
                            if (moved) deleted++; else failDel++;
                        }
                        String msg = I18n.t("%d dossier(s) orphelin(s) déplacé(s) dans la corbeille%s%s", deleted,
                                failDel > 0 ? I18n.t(", %d échec(s)", failDel) : "",
                                skippedNowNotEmpty > 0
                                    ? I18n.t(", %d ignoré(s) (a reçu de l'audio entre-temps)", skippedNowNotEmpty)
                                    : "");
                        setStatus(msg);
                        // Pop-up de résultat manquante jusqu'à ce correctif (2026-09-06) — seul le
                        // texte de la barre de statut changeait, facilement manqué (retour direct de
                        // l'utilisateur : "pas de pop up pour orphelins... il apparait jamais").
                        // Même idiome que deleteSelectedFiles()/confirmBackfillTags() juste au-dessus.
                        JOptionPane.showMessageDialog(MainFrame.this, msg,
                                I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
                    }
                } else {
                    setStatus(I18n.t("%d dossier(s) examiné(s), aucun candidat corbeille — %d dossier(s) sans "
                            + "audio mais avec du contenu non reconnu (jamais touchés automatiquement).",
                            scanned, unknownFolders));
                }
            }

            boolean isExcludedPath(File dir) {
                String[] excluded = com.opentagger.Config.get().excludedFolders();
                if (excluded.length == 0) return false;
                String path = dir.getAbsolutePath();
                for (String prefix : excluded) {
                    if (!prefix.isBlank() && (path.equals(prefix) || path.startsWith(prefix + File.separator))) return true;
                }
                return false;
            }

            /** Re-scan minimal (juste KNOWN_AUDIO, aucune sonde ffprobe) juste avant de déplacer un
             *  dossier candidat vers la corbeille — voir le commentaire d'appel. Volontairement plus
             *  strict/simple que scanDir() (pas de repli AudioFormatCheck sur extension inconnue) :
             *  ici on cherche juste "un fichier audio a-t-il été déposé depuis le scan", pas à
             *  reclasser finement un résidu ambigu. */
            boolean hasAnyAudioNow(File dir) {
                File[] children = dir.listFiles();
                if (children == null) return false;
                for (File f : children) {
                    if (f.isDirectory()) { if (hasAnyAudioNow(f)) return true; continue; }
                    String lower = f.getName().toLowerCase();
                    if (KNOWN_AUDIO.stream().anyMatch(lower::endsWith)) return true;
                }
                return false;
            }
        };
        WorkerHub.get().submit(WorkerHub.TaskKind.ORPHAN_CLEANUP,
                I18n.t("Recherche de dossiers orphelins"), w, () -> w.cancel(true));
    }

    /** Supprime directement les fichiers sélectionnés (n'importe quel statut, pas seulement les
     *  illisibles comme {@link #deleteErrorFiles()}) — retour utilisateur (2026-08-21) : aucun
     *  moyen de supprimer un audio depuis l'appli elle-même, il fallait repasser par le
     *  gestionnaire de fichiers du système. Même sécurité que deleteErrorFiles()/DuplicatesDialog :
     *  corbeille système plutôt que suppression définitive, confirmation explicite avant, retiré du
     *  tableau seulement après succès réel (pas en optimiste avant confirmation du disque). */
    private void deleteSelectedFiles() {
        if (table == null) return;
        java.util.Set<FileEntry> toDelete = new java.util.LinkedHashSet<>();
        for (int row : table.getSelectedRows()) toDelete.addAll(entriesAtViewRow(row));
        if (toDelete.isEmpty()) {
            setStatus(I18n.t("Aucun fichier sélectionné."));
            return;
        }
        List<FileEntry> targets = new ArrayList<>(toDelete);

        StringBuilder sb = new StringBuilder(
                I18n.t("<html><body style='width: 480px'>Déplacer <b>%d fichier(s)</b> dans la corbeille ?<br><br>", targets.size()));
        int shown = Math.min(targets.size(), 8);
        for (int i = 0; i < shown; i++) {
            File f = targets.get(i).currentPath != null ? targets.get(i).currentPath.toFile() : targets.get(i).file;
            sb.append("&nbsp;• ").append(f.getName()).append("<br>");
        }
        if (targets.size() > shown) sb.append(I18n.t("&nbsp;… et %d autre(s)<br>", targets.size() - shown));
        // Dit où les fichiers vont RÉELLEMENT (voir TrashHelper.destinationDescription()) — "corbeille
        // système" inconditionnel avait fait croire à l'utilisateur que ses fichiers avaient disparu
        // sans trace après une suppression pourtant réussie (2026-09-07, cette machine ne supporte
        // pas la corbeille système).
        sb.append(I18n.t("<br><i>Envoyé dans %s — récupérable, pas une suppression définitive.</i></html>",
                com.opentagger.TrashHelper.destinationDescription()));

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
                I18n.t("Supprimer les fichiers sélectionnés"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        setStatus(I18n.t("Suppression de %d fichier(s)…", targets.size()));
        new SwingWorker<int[], FileEntry>() {
            @Override protected int[] doInBackground() {
                // TrashHelper.moveToTrash() — voir sa Javadoc (2026-09-05, CRITIQUE : retour
                // utilisateur en direct, 175 fichiers "supprimés" via ce site précis introuvables
                // ensuite dans AUCUNE corbeille réelle). Desktop.moveToTrash() n'est PAS supporté
                // sur cette machine, et ce site précis promettait pourtant explicitement à
                // l'utilisateur "récupérable, pas une suppression définitive" (voir le texte de la
                // boîte de confirmation juste au-dessus) — l'ancien code tombait dans f.delete() en
                // silence, contredisant directement cette promesse.
                int deleted = 0, failDel = 0;
                for (FileEntry e : targets) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    boolean moved = com.opentagger.TrashHelper.moveToTrash(f);
                    if (moved) { deleted++; publish(e); } else { failDel++; }
                }
                return new int[]{ deleted, failDel };
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
                String msg = I18n.t("%d fichier(s) déplacé(s) dans la corbeille", r[0]);
                if (r[1] > 0) msg += I18n.t(", %d échec(s) (permission refusée ?)", r[1]);
                setStatus(msg);
                refreshStats();
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
            // entriesAtViewRow() — même correctif que forceRetag()/fixEncoding().
            java.util.Set<FileEntry> candidateSet = new java.util.LinkedHashSet<>();
            for (int r : sel) candidateSet.addAll(entriesAtViewRow(r));
            candidates.addAll(candidateSet);
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
                long skipped = results.stream().filter(com.opentagger.AcoustIdSubmitter.SubmissionResult::skipped).count();
                long ok = results.stream().filter(r -> r.accepted() && !r.skipped()).count();
                long ko = results.size() - ok - skipped;
                return I18n.t("Soumission AcoustID — ✔ %d soumis", ok) +
                       (skipped > 0 ? I18n.t("  ⏭ %d déjà connu(s) d'AcoustID (non soumis)", skipped) : "") +
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
                        // AcoustID renvoie l'identifiant quand l'import est immédiat (statut "imported") :
                        // le ranger dans le TagInfo si le fichier n'en avait pas (SongKong le range dans le tag
                        // "Acoustid Id") — écrit sur disque au prochain enregistrement du fichier.
                        java.util.Map<String, FileEntry> byPath = new java.util.HashMap<>();
                        for (FileEntry e : toSubmit)
                            byPath.put((e.currentPath != null ? e.currentPath.toFile() : e.file).getAbsolutePath(), e);
                        for (var r : results) {
                            if (!r.accepted()) errors.add(r.file().getName() + " : " + r.message());
                            if (!r.acoustId().isBlank()) {
                                FileEntry e = byPath.get(r.file().getAbsolutePath());
                                if (e != null && e.activeTags() != null && e.activeTags().acoustidId.isBlank()) {
                                    e.activeTags().acoustidId = r.acoustId();
                                    tableModel.update(e);
                                }
                            }
                        }
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

    void setStatus(String msg) {
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
        java.util.List<String> ops = new java.util.ArrayList<>();
        for (WorkerHub.TaskHandle h : WorkerHub.get().active()) {
            // L'audit audio ↔ tags (AudioTagAuditWorker) est en LECTURE SEULE, ne tient que sa propre liste de
            // chemins et reprend là où il s'est arrêté : il ne doit ni bloquer "Vider la liste"/"Organiser"/
            // "Grouper" pendant des heures, ni faire demander une confirmation à la fermeture.
            if (h.kind() != WorkerHub.TaskKind.AUDIO_AUDIT) ops.add(h.label());
        }
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
