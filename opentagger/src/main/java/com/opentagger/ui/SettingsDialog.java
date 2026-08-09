package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.FileRenamer;
import com.opentagger.FpcalcInstaller;
import com.opentagger.I18n;
import com.opentagger.MusicBrainzOAuth;
import com.opentagger.PostTagCommands;
import com.opentagger.TaggerScript;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Dialogue de configuration — équivalent du panneau Preferences de Jaikoz.
 *
 * Les valeurs sont lues/écrites dans ~/.opentagger/settings.properties
 * (même emplacement que la base cache.db).
 */
public class SettingsDialog extends JDialog {

    private static final String SETTINGS_FILE =
            com.opentagger.Config.configDir() + java.io.File.separator + "settings.properties";

    // ── Onglet APIs ──────────────────────────────────────────────────────────
    private JTextField tfMbUserAgent, tfAcoustIdKey, tfAcoustIdUserToken;
    private JTextField tfDiscogsKey, tfDiscogsSecret;
    private JTextField tfLastFmKey;
    private JTextField tfFanArtKey;
    private JCheckBox  chkLastfmEnabled;
    private JCheckBox  chkLastfmArtistUrls;

    // ── Onglet Matching ──────────────────────────────────────────────────────
    private JSpinner   spMinScore;
    private JSpinner   spTrackMatchThreshold;
    private JCheckBox  chkOnlyOfficial;
    private JCheckBox  chkUseAcoustId;
    // tfPreferredCountry supprimé — remplacé par le sélecteur lstCountriesModel/cmbCountryPicker
    private JSpinner   spResultsLimit;
    private JSpinner   spCacheDays;
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbDiscogsGenreSource;
    private JSpinner   spDiscogsMaxGenres;
    private JSpinner   spLastfmMaxGenres;

    // ── Onglet Renommage ─────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbDefaultMask;
    private JCheckBox  chkAutoRename;
    private JCheckBox  chkDeleteEmptyDirs;
    private JCheckBox  chkFollowLog;
    private JCheckBox  chkUseLibraryRoot;
    private JTextField tfLibraryRoot;
    private JTextField tfPodcastLibraryRoot;
    private JCheckBox  chkMoveSkipped;
    private JTextField tfSkippedFolder;
    private JCheckBox  chkMoveDurationMismatch;
    private JTextField tfDurationMismatchFolder;
    private JCheckBox  chkVideoAutoRecover;
    private JCheckBox  chkSkipSongRecOnConfidentMb;
    private JSpinner   spnSkipSongRecMinScore;

    // ── Onglet Audio ─────────────────────────────────────────────────────────
    private JTextField tfFfmpegPath;
    private JTextField tfEssentiaPath;
    private JCheckBox  chkLyricsEnabled;
    private JCheckBox  chkSaveLrc;

    // ── Onglet Audio (complémentaire) ─────────────────────────────────────────
    private JTextField tfFpcalcPath;
    private JLabel     lblFpcalcStatus;

    // ── Onglet APIs (Shazam / AudD) ──────────────────────────────────────────
    private JTextField tfRapidApiKey;
    private JTextField tfAudDToken;

    // ── Onglet APIs (ListenBrainz) ────────────────────────────────────────────
    private JTextField tfListenBrainzUsername;
    private JSpinner   spListenBrainzMaxTracks;

    // ── Onglet Tags ───────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbId3Version;
    private JCheckBox  chkPreserveTimestamps;
    private JCheckBox  chkClearExistingTags;
    private JCheckBox  chkPreserveImages;
    private JCheckBox  chkPreserveCompilation;
    private JCheckBox  chkTrustExistingMbTags;
    private JCheckBox  chkCorrectPunctuation;
    private JCheckBox  chkRemoveId3v1;
    private JCheckBox  chkSaveAcoustidFingerprints;
    private JCheckBox  chkIgnoreExistingFingerprints;
    private JSpinner   spFpcalcThreads;
    private JSpinner   spBatchThreads;
    private JTextField tfPreservedTags;
    private JCheckBox  chkMbUseGenres;
    private JSpinner   spMbMinGenreUsage;
    private JSpinner   spMbMaxGenres;
    private JTextArea  taGenresFilter;
    private JCheckBox  chkCoverSaveToFile;
    private JCheckBox  chkCoverOverwriteFile;
    private JTextField tfCoverFilename;

    // ── Fournisseurs de pochette — activables/réordonnables (façon Picard) ──────────
    private static final String[] COVER_PROVIDER_IDS = {"caa_release", "caa_release_group", "shazam", "local", "fanart", "deezer"};
    private static final String[] COVER_PROVIDER_LABELS = {
        "Cover Art Archive : parution", "Cover Art Archive : groupe de parution",
        "Shazam (déjà renvoyée par SongRec à l'identification, sans recherche supplémentaire)",
        "Dossier local (folder.jpg / cover.jpg)", "FanArt.tv", "Deezer (recherche texte, sans clé API)"
    };
    private DefaultListModel<String>     lstCoverProvidersModel = new DefaultListModel<>();
    private JList<String>                lstCoverProviders;
    private java.util.List<String>       coverProviderOrder     = new java.util.ArrayList<>();
    private java.util.Map<String,Boolean> coverProviderEnabled  = new java.util.LinkedHashMap<>();

    // ── Onglet Script tagger — liste de scripts nommés, activables individuellement ──
    private JTextArea  taTaggerScript;
    private java.util.List<TaggerScript.ScriptDef> scriptDefs = new java.util.ArrayList<>();
    private DefaultListModel<String> lstScriptsModel = new DefaultListModel<>();
    private JList<String>            lstScripts;
    private int                      currentScriptIndex = -1;
    private JCheckBox                chkScriptsEnabled;
    private java.util.List<String> postTagCommands = new java.util.ArrayList<>();
    private DefaultListModel<String> lstPostTagCommandsModel = new DefaultListModel<>();
    private JList<String>            lstPostTagCommands;

    // ── Onglet Barre d'outils — actions secondaires personnalisables ────────────────
    @SuppressWarnings("unchecked")
    private JComboBox<String>        cmbToolbarActionPicker;
    private DefaultListModel<String> lstToolbarActionsModel = new DefaultListModel<>();
    private JList<String>            lstToolbarActions;
    private java.util.List<String>   toolbarActionIds = new java.util.ArrayList<>();

    // ── Onglet Audio ─────────────────────────────────────────────────────────
    private JCheckBox  chkReplayGainEnabled;
    private JCheckBox  chkCamelotKey;

    // ── Onglet Transcodage ────────────────────────────────────────────────────
    private JCheckBox               chkTranscodeAuto;
    @SuppressWarnings("unchecked")
    private JComboBox<String>       cmbTranscodeFormat;
    private JSpinner                spTranscodeBitrate;
    private JCheckBox               chkTranscodeDeleteSource;
    private JLabel                  lblTranscodeBitrate;
    private JCheckBox               chkMoveUnreadable;

    // ── Onglet Matching — releases préférées + méta ───────────────────────────
    private JCheckBox  chkTranslateArtists;
    // Plusieurs locales, par ordre de priorité (même widget qu'un pays préféré : "+"/"−"/"↑"/"↓")
    // — la plupart des alias de romanisation MusicBrainz sont tagués locale=en peu importe la
    // langue réellement préférée par l'utilisateur, donc une seule langue choisie manquait souvent
    // sa cible (confirmé en direct : zéro traduction réussie avec juste "fr" configuré, malgré des
    // dizaines d'artistes non-latins ayant un alias "en" exploitable). Repli automatique sur "en"
    // déjà en place côté MusicBrainzClient si aucune des langues listées ici ne donne de résultat.
    @SuppressWarnings("unchecked")
    private JComboBox<String>        cmbTranslateLocalePicker;
    private DefaultListModel<String> lstTranslateLocalesModel = new DefaultListModel<>();
    private JList<String>            lstTranslateLocales;
    // Séries de compilations (ex. "Stars 80", "NRJ", "Fun Radio", "RFM") que l'utilisateur veut
    // voir reliées à ses morceaux déjà tagués — voir ui.CompilationClusterWorker. Texte libre (pas
    // de vocabulaire fermé comme les pays/locales ci-dessus), donc un JTextField plutôt qu'un combo.
    private JTextField                tfCompilationSeriesInput;
    private DefaultListModel<String>  lstCompilationSeriesModel = new DefaultListModel<>();
    private JList<String>             lstCompilationSeries;
    // code MB (locale d'alias, ex. "en") — libellé affiché. codeFromLabel() (déjà utilisée pour
    // les pays préférés) extrait le code avant " — " pour la sauvegarde.
    private static final String[][] TRANSLATE_LOCALES = {
        {"en", "Anglais"}, {"fr", "Français"}, {"de", "Allemand"}, {"es", "Espagnol"},
        {"it", "Italien"}, {"pt", "Portugais"}, {"nl", "Néerlandais"}, {"sv", "Suédois"},
        {"pl", "Polonais"}, {"ru", "Russe"}, {"ja", "Japonais"}, {"ko", "Coréen"},
        {"zh", "Chinois"}, {"ar", "Arabe"}, {"tr", "Turc"},
    };
    private JCheckBox  chkPrioritizeIncomplete;
    @SuppressWarnings("unchecked")
    private JComboBox<String>         cmbCountryPicker;
    private DefaultListModel<String>  lstCountriesModel = new DefaultListModel<>();
    private JList<String>             lstPreferredCountries;
    private JTextField tfPreferredFormats;
    private JTextField tfVaName;
    private JCheckBox  chkStandardizeArtists;

    // ── Filtres types de release ─────────────────────────────────────────────
    private static final String[] PRIMARY_TYPES   = {"Album", "Single", "EP", "Broadcast", "Other"};
    private static final String[] SECONDARY_TYPES = {"Compilation", "Live", "Soundtrack", "Greatest Hits", "Remix", "Demo", "DJ-mix", "Mixtape/Street"};
    private JCheckBox[] chkPrimaryTypes   = new JCheckBox[PRIMARY_TYPES.length];
    private JCheckBox[] chkExcludedSecondary = new JCheckBox[SECONDARY_TYPES.length];

    // Codes ISO → Nom complet (pour affichage dans le sélecteur). Noms obtenus dynamiquement via
    // Locale.getDisplayCountry() dans la langue de l'UI plutôt qu'une map français codée en dur —
    // ça élimine la traduction manuelle de 68 entrées ET reste cohérent avec n'importe quelle
    // langue future, pas seulement l'anglais. XW/XE ne sont pas des codes ISO réels (raccourcis
    // MusicBrainz "monde entier"/"Europe") donc traités à part.
    private static final java.util.LinkedHashMap<String,String> ISO_COUNTRIES;
    static {
        ISO_COUNTRIES = new java.util.LinkedHashMap<>();
        String[] codes = {
            "AD","AE","AR","AT","AU","BA","BE","BG","BR","BY","CA","CH","CL","CN","CO","CY","CZ","DE",
            "DK","EC","EE","EG","ES","FI","FR","GB","GR","HR","HU","ID","IE","IL","IN","IS","IT","JP",
            "KR","LT","LU","LV","MA","MK","MT","MX","MY","NL","NO","NZ","PH","PL","PT","RO","RS","RU",
            "SE","SG","SI","SK","TH","TR","TW","UA","US","UY","VE","ZA",
        };
        java.util.Locale display = "en".equals(I18n.lang()) ? java.util.Locale.ENGLISH : java.util.Locale.FRENCH;
        for (String code : codes) {
            String name = new java.util.Locale("", code).getDisplayCountry(display);
            ISO_COUNTRIES.put(code, name.isBlank() ? code : name);
        }
        ISO_COUNTRIES.put("XW", I18n.t("Monde entier"));
        ISO_COUNTRIES.put("XE", I18n.t("Europe"));
    }
    private static String countryLabel(String code) {
        return ISO_COUNTRIES.containsKey(code) ? code + " — " + ISO_COUNTRIES.get(code) : code;
    }
    private static String codeFromLabel(String label) {
        return label.contains(" — ") ? label.substring(0, label.indexOf(" — ")) : label;
    }
    private static String localeLabel(String code) {
        for (String[] entry : TRANSLATE_LOCALES)
            if (entry[0].equalsIgnoreCase(code)) return entry[0] + " — " + I18n.t(entry[1]);
        return code; // locale inconnue de la liste curatée (config éditée à la main) : garder tel quel
    }

    // ── Onglet MusicBrainz OAuth ──────────────────────────────────────────────
    private JLabel     lblMbAccount;
    private JTextField tfMbCollectionId;
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbMbOAuthMode;

    // ── Onglet Démarrage ──────────────────────────────────────────────────────
    private DefaultListModel<String> startupFolderModel;
    private JComboBox<String> cmbLanguage;
    private JCheckBox chkUpdateCheck;
    private JCheckBox chkCloseMinimizes;

    // ─────────────────────────────────────────────────────────────────────────

    private final java.util.function.Consumer<java.io.File[]> onLoadFolders;
    // Rappelée après CHAQUE sauvegarde réussie (OK ou Appliquer — les deux passent par save()),
    // pas seulement à la fermeture du dialogue : laisse l'appelant (MainFrame) resynchroniser en
    // direct tout ce qu'il n'a normalement lu qu'une fois à sa construction (barre d'outils
    // secondaire, masque de renommage par défaut...) sans avoir à redémarrer. Inconditionnelle et
    // pas diffée ici : c'est à l'appelant de savoir ce qui a effectivement changé et d'agir en
    // conséquence (idempotent si rien n'a changé). Nullable : les appelants qui n'en ont pas
    // besoin passent null.
    private final Runnable onPreferencesSaved;

    // ── Recherche de réglages (10 onglets / ~75 réglages — pas de refonte, juste
    //    un accès direct à un champ sans devoir deviner son onglet) ────────────
    private JTabbedPane tabs;
    // Statique (survit à une nouvelle instance de SettingsDialog, pas à un redémarrage de
    // l'appli) : sans ça, rouvrir Préférences retombait systématiquement sur le premier onglet
    // ("Démarrage") même en venant de fermer sur "Script" ou "Renommage" l'instant d'avant —
    // signalé en direct comme gênant pour itérer sur un même onglet (OK/Annuler/simple
    // réouverture, peu importe comment on a quitté le dialogue précédemment).
    private static int lastTabIndex = 0;
    private JTextField  tfSettingsSearch;
    private final java.util.List<SearchTarget> searchIndex = new java.util.ArrayList<>();
    private record SearchTarget(int tabIndex, String text, JComponent component) {}

    public SettingsDialog(Frame owner, java.util.function.Consumer<java.io.File[]> onLoadFolders) {
        this(owner, onLoadFolders, null);
    }

    public SettingsDialog(Frame owner, java.util.function.Consumer<java.io.File[]> onLoadFolders,
                           Runnable onPreferencesSaved) {
        super(owner, I18n.t("Préférences — OpenTagger"), true);
        this.onLoadFolders      = onLoadFolders;
        this.onPreferencesSaved = onPreferencesSaved;
        setMinimumSize(new Dimension(560, 500));
        setResizable(true);

        tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        // Chaque onglet est enveloppé dans un JScrollPane pour que le contenu soit
        // toujours accessible quelle que soit la taille de la fenêtre
        tabs.addTab(I18n.t("Démarrage"),    scrollWrap(buildStartupPanel()));
        tabs.addTab(I18n.t("APIs"),         scrollWrap(buildApiPanel()));
        tabs.addTab(I18n.t("Matching"),     scrollWrap(buildMatchingPanel()));
        tabs.addTab(I18n.t("Tags"),         scrollWrap(buildTagsPanel()));
        tabs.addTab(I18n.t("Renommage"),    scrollWrap(buildRenamePanel()));
        tabs.addTab(I18n.t("Audio / Transcodage"), scrollWrap(buildAudioPanel()));
        tabs.addTab(I18n.t("Script"),       scrollWrap(buildScriptPanel()));
        tabs.addTab(I18n.t("Barre d'outils"), scrollWrap(buildToolbarPanel()));
        tabs.addTab(I18n.t("MusicBrainz"),  scrollWrap(buildMbOAuthPanel()));
        buildSearchIndex();
        if (lastTabIndex >= 0 && lastTabIndex < tabs.getTabCount()) tabs.setSelectedIndex(lastTabIndex);
        tabs.addChangeListener(e -> lastTabIndex = tabs.getSelectedIndex());

        JButton btnOk     = new JButton(I18n.t("OK"));
        JButton btnCancel = new JButton(I18n.t("Annuler"));
        JButton btnApply  = new JButton(I18n.t("Appliquer"));
        btnOk    .addActionListener(e -> { save(); dispose(); });
        btnCancel.addActionListener(e -> dispose());
        btnApply .addActionListener(e -> save());

        JButton btnResetOptions = new JButton(I18n.t("Réinitialiser les options…"));
        btnResetOptions.addActionListener(e -> resetOptionsToDefault());

        JPanel buttonsRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttonsRight.add(btnApply); buttonsRight.add(btnCancel); buttonsRight.add(btnOk);
        JPanel buttonsLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        buttonsLeft.add(btnResetOptions);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(buttonsLeft,  BorderLayout.WEST);
        buttons.add(buttonsRight, BorderLayout.EAST);
        buttons.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildSearchBar(), BorderLayout.NORTH);
        getContentPane().add(tabs,    BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        load();

        // Adapter la taille à l'écran : 55% de la largeur et 70% de la hauteur, borné
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(Math.max((int)(screen.width  * 0.55), 620), 900);
        int h = Math.min(Math.max((int)(screen.height * 0.70), 520), 800);
        setSize(w, h);
        setLocationRelativeTo(owner);

        getRootPane().setDefaultButton(btnOk);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // ── Construction des onglets ─────────────────────────────────────────────

    private JPanel buildStartupPanel() {
        startupFolderModel = new DefaultListModel<>();
        JList<String> list = new JList<>(startupFolderModel);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFont(list.getFont().deriveFont(12f));
        // Affiche nom du dossier en gras + chemin parent en gris, et signale en rouge un dossier
        // introuvable (ex. disque externe débranché/non monté au démarrage) — jusqu'ici un chemin
        // brut sans indication, aucun moyen de repérer d'un coup d'œil un dossier qui ne sera
        // silencieusement pas chargé au prochain lancement.
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                String path = (String) value;
                java.io.File f = new java.io.File(path);
                boolean missing = !f.isDirectory();
                String name   = f.getName().isBlank() ? path : f.getName();
                String parent = f.getParent() != null ? f.getParent() : "";
                c.setText("<html>" + (missing ? "⚠ " : "") + "<b>" + name + "</b>"
                        + "<font color='" + (isSelected ? "#cccccc" : "#888888") + "'> — " + parent + "</font></html>");
                c.setToolTipText(missing ? I18n.t("%s  (introuvable — disque externe débranché ?)", path) : path);
                if (!isSelected) c.setForeground(missing ? new Color(220, 90, 90) : l.getForeground());
                return c;
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(400, 200));

        JButton btnAdd = new JButton("+ " + I18n.t("Ajouter…"));
        JButton btnRemove = new JButton("− " + I18n.t("Supprimer"));
        btnRemove.setEnabled(false);
        list.addListSelectionListener(e -> btnRemove.setEnabled(!list.isSelectionEmpty()));

        btnAdd.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setMultiSelectionEnabled(true);
            fc.setDialogTitle(I18n.t("Choisir les dossiers à charger au démarrage"));
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                for (java.io.File f : fc.getSelectedFiles())
                    if (!startupFolderModel.contains(f.getAbsolutePath()))
                        startupFolderModel.addElement(f.getAbsolutePath());
            }
        });
        btnRemove.addActionListener(e -> {
            for (String s : list.getSelectedValuesList())
                startupFolderModel.removeElement(s);
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(btnAdd);
        buttons.add(btnRemove);

        JLabel hint = new JLabel(I18n.t("<html><i>Ces dossiers seront chargés automatiquement à chaque démarrage d'OpenTagger. "
                + "⚠ en rouge = dossier introuvable actuellement (disque externe débranché ?).</i></html>"));
        hint.setBorder(new EmptyBorder(8, 0, 4, 0));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel inner = new JPanel(new BorderLayout(0, 6));
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Dossiers chargés au démarrage")));
        inner.add(hint,   BorderLayout.NORTH);
        inner.add(scroll, BorderLayout.CENTER);
        inner.add(buttons, BorderLayout.SOUTH);

        cmbLanguage = new JComboBox<>(new String[]{"Français", "English"});
        JPanel langPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        langPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Langue de l'application / Language")));
        langPanel.add(new JLabel(I18n.t("Langue :")));
        langPanel.add(cmbLanguage);
        JLabel langHint = new JLabel(I18n.t("Redémarrage nécessaire pour appliquer le changement."));
        langHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        langPanel.add(langHint);

        chkUpdateCheck = new JCheckBox(I18n.t("Vérifier les mises à jour automatiquement"));
        JPanel updatePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        updatePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Mises à jour")));
        updatePanel.add(chkUpdateCheck);

        chkCloseMinimizes = new JCheckBox(I18n.t("Le bouton X minimise la fenêtre au lieu de quitter"));
        chkCloseMinimizes.setToolTipText(I18n.t(
            "Le taguage/enregistrement en cours continue en fond ; retrouvez la fenêtre dans la "
            + "barre des tâches. Le menu Édition → Quitter ferme toujours vraiment l'application."));
        JPanel closePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        closePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Fermeture de la fenêtre")));
        closePanel.add(chkCloseMinimizes);

        // La commande après taguage vit désormais dans l'onglet Script (voir buildScriptPanel()),
        // avec les scripts JS — un seul endroit pour "ce qui s'exécute après le taguage", et
        // extension en liste de plusieurs commandes (PostTagCommands) au passage.

        JPanel topPanels = new JPanel();
        topPanels.setLayout(new BoxLayout(topPanels, BoxLayout.Y_AXIS));
        topPanels.add(langPanel);
        topPanels.add(updatePanel);
        topPanels.add(closePanel);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new EmptyBorder(10, 10, 10, 10));
        outer.add(topPanels, BorderLayout.NORTH);
        outer.add(inner, BorderLayout.CENTER);
        return outer;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildApiPanel() {
        tfMbUserAgent      = tf();
        tfAcoustIdKey      = tf();
        tfAcoustIdKey.setToolTipText(I18n.t("Clé d'APPLICATION AcoustID (paramètre \"client\") — à obtenir en "
                + "enregistrant une application sur acoustid.org/new-application. Différente de la clé "
                + "personnelle ci-dessous : ne pas mettre la même valeur dans les deux champs."));
        tfAcoustIdUserToken= tf();
        tfAcoustIdUserToken.setToolTipText(I18n.t("Clé personnelle AcoustID (paramètre \"user\") — depuis votre "
                + "compte sur acoustid.org/api-key. Utilisée uniquement pour attribuer les soumissions "
                + "d'empreintes à votre compte, différente de la clé d'application ci-dessus."));
        tfDiscogsKey       = tf();
        tfDiscogsSecret    = tf();
        tfLastFmKey        = tf();
        tfFanArtKey        = tf();
        tfRapidApiKey      = tf();
        tfAudDToken        = tf();
        tfListenBrainzUsername  = tf();
        spListenBrainzMaxTracks = new JSpinner(new SpinnerNumberModel(1000, 100, 20000, 100));
        spListenBrainzMaxTracks.setToolTipText(I18n.t("Nombre max de pistes à récupérer dans le classement "
                + "ListenBrainz (au-delà, les pistes les moins écoutées ne sont pas synchronisées)."));
        // FanArt.tv est activé/désactivé depuis la liste des fournisseurs de pochette
        // (onglet Tags) — pas de case séparée ici pour éviter deux réglages contradictoires.
        chkLastfmEnabled   = new JCheckBox(I18n.t("Activer l'enrichissement Last.fm"));
        // Requête Last.fm séparée (artist.getInfo) du genre/mood (track.getTopTags) — un appel
        // réseau en plus par fichier pour des champs (URL officielle, lien Wikipedia) que tout le
        // monde ne regarde pas. Décochable indépendamment sans perdre le genre/mood Last.fm.
        chkLastfmArtistUrls = new JCheckBox(I18n.t("Récupérer aussi l'URL officielle + Wikipedia de l'artiste (requête réseau supplémentaire)"));

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Clés d'accès aux services en ligne")));

        Object[][] rows = {
            { "MusicBrainz User-Agent :",   tfMbUserAgent,        null },
            { "AcoustID API Key :",         tfAcoustIdKey,        "https://acoustid.org/new-application" },
            { "AcoustID User Token :",      tfAcoustIdUserToken,  "https://acoustid.org/api-key" },
            { "Discogs Consumer Key :",     tfDiscogsKey,         "https://www.discogs.com/settings/developers" },
            { "Discogs Consumer Secret :",  tfDiscogsSecret,      "https://www.discogs.com/settings/developers" },
            { "Last.fm API Key :",          tfLastFmKey,          "https://www.last.fm/api/account/create" },
            { "FanArt.tv API Key :",        tfFanArtKey,          "https://fanart.tv/get-an-api-key/" },
            { "RapidAPI Key (Shazam) :",    tfRapidApiKey,        "https://rapidapi.com/apidojo/api/shazam" },
            { "AudD API Token :",           tfAudDToken,          "https://dashboard.audd.io/" },
            { "Nom d'utilisateur ListenBrainz :", tfListenBrainzUsername, "https://listenbrainz.org/settings/" },
            { "Pistes max à synchroniser :", spListenBrainzMaxTracks, null },
        };

        for (int i = 0; i < rows.length; i++) {
            String     label  = I18n.t((String) rows[i][0]);
            JComponent field  = (JComponent) rows[i][1];
            String     url    = (String)     rows[i][2];

            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(3, 10, 3, 8);
            inner.add(new JLabel(label), lc);

            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0; fc.insets = new Insets(3, 0, 3, 4);
            inner.add(field, fc);

            GridBagConstraints bc = new GridBagConstraints();
            bc.gridx = 2; bc.gridy = i; bc.anchor = GridBagConstraints.WEST;
            bc.insets = new Insets(3, 0, 3, 8);
            if (url != null) {
                inner.add(apiLinkBtn(url), bc);
            } else {
                inner.add(new JLabel(""), bc);
            }
        }

        // Section enrichissement
        JPanel enrichInner = new JPanel(new GridBagLayout());
        enrichInner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Enrichissement automatique")));
        {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = 0; c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 10, 3, 8); c.gridwidth = 3;
            enrichInner.add(chkLastfmEnabled, c);
        }
        {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = 1; c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(0, 28, 3, 8); c.gridwidth = 3;
            enrichInner.add(chkLastfmArtistUrls, c);
        }
        {
            // Repère explicite pour l'utilisateur — avant, ce lien n'existait que dans un
            // commentaire de code (voir plus haut), pas dans l'UI elle-même : la clé FanArt.tv est
            // ici, mais son activation/priorité est dans un tout autre onglet (Tags), et les
            // réglages de comptage Last.fm sont dans un 3e (Matching) — rien ne les reliait.
            JLabel hint = new JLabel(I18n.t(
                "<html><i>FanArt.tv : activer/désactiver et priorité dans l'onglet Tags → "
                + "Fournisseurs de pochette.<br>Last.fm : nombre de genres max dans l'onglet "
                + "Matching → Sources de genres.</i></html>"));
            hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = 2; c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 10, 4, 8); c.gridwidth = 3;
            enrichInner.add(hint, c);
        }

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(inner,       BorderLayout.CENTER);
        p.add(enrichInner, BorderLayout.SOUTH);
        return p;
    }

    private JButton apiLinkBtn(String url) {
        JButton btn = new JButton("🔗 " + I18n.t("Obtenir"));
        btn.putClientProperty("FlatLaf.style",
            "font: 10 $defaultFont; background: null; arc: 6");
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(url);
        btn.addActionListener(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(SettingsDialog.this,
                    I18n.t("Ouvrez : %s", url), I18n.t("Lien"), JOptionPane.INFORMATION_MESSAGE);
            }
        });
        return btn;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildMatchingPanel() {
        spMinScore       = new JSpinner(new SpinnerNumberModel(85, 0, 100, 5));
        spMinScore.setToolTipText(I18n.t(
            "Niveau de confiance minimum (en %) pour accepter automatiquement une correspondance "
            + "trouvée sur MusicBrainz par recherche texte (titre/artiste). En dessous de ce seuil, "
            + "le résultat est ignoré plutôt que d'appliquer un mauvais match au hasard."));
        // Seuil du score composite pondéré fichier↔piste (TrackMatcher, modèle Picard) — distinct
        // du score MB texte ci-dessus (spMinScore) : celui-ci s'applique quand on choisit la
        // meilleure piste DANS une tracklist déjà connue (album-first, groupement d'albums).
        spTrackMatchThreshold = new JSpinner(new SpinnerNumberModel(40, 0, 100, 5));
        spTrackMatchThreshold.setToolTipText(I18n.t(
            "Score minimum (titre+artiste+durée+n°piste/disque combinés) pour accepter "
            + "l'appariement d'un fichier à une piste d'un album déjà identifié. Même valeur par "
            + "défaut que MusicBrainz Picard (40%)."));
        chkOnlyOfficial  = new JCheckBox(I18n.t("Uniquement les releases officielles"));
        chkOnlyOfficial.setToolTipText(I18n.t(
            "Une \"release\" MusicBrainz = une édition précise d'un album (CD original, réédition, "
            + "promo, bootleg…). Cocher ceci ignore les éditions bootleg/promo/non officielles lors "
            + "de la recherche de correspondance."));
        chkUseAcoustId   = new JCheckBox(I18n.t("Activer l'identification par empreinte AcoustID (plus précis, plus lent)"));
        chkUseAcoustId.setToolTipText(I18n.t(
            "AcoustID identifie un morceau à partir du son lui-même (comme une empreinte digitale "
            + "audio), pas de son nom de fichier — fonctionne même si le fichier n'a aucun tag ou "
            + "un nom bizarre. Plus lent que la recherche par texte, activé automatiquement en dernier "
            + "recours si un fichier reste non identifié."));
        spResultsLimit   = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
        spResultsLimit.setToolTipText(I18n.t(
            "Nombre maximum de résultats demandés à MusicBrainz (la base de données musicale en "
            + "ligne utilisée pour identifier vos morceaux) à chaque recherche."));
        spCacheDays      = new JSpinner(new SpinnerNumberModel(30, 1, 365, 7));
        spCacheDays.setToolTipText(I18n.t(
            "Les réponses de MusicBrainz sont gardées en mémoire locale ce nombre de jours pour "
            + "aller plus vite et éviter de re-demander la même chose. Passé ce délai, la prochaine "
            + "recherche re-vérifie auprès de MusicBrainz au cas où l'info aurait changé."));

        // ── Compromis vitesse : sauter SongRec quand MB texte est déjà confiant ────
        // Déplacé depuis l'onglet Renommage (2026-08-09, retour utilisateur "je ne comprend pas la
        // section songrec") : ce réglage concerne l'IDENTIFICATION du morceau (comme AcoustID
        // juste au-dessus), pas le renommage de fichiers — sa présence dans l'onglet Renommage
        // n'avait aucun rapport avec les autres options qui l'entouraient.
        chkSkipSongRecOnConfidentMb = new JCheckBox(I18n.t(
            "Éviter SongRec quand MusicBrainz (recherche texte) est déjà confiant"));
        chkSkipSongRecOnConfidentMb.setToolTipText(I18n.t(
            "SongRec (reconnaissance audio type Shazam) reste la source principale par défaut — plus "
            + "lent mais fiable même avec des tags faux. En activant ceci : si le fichier a un "
            + "artiste+titre exploitables dans ses tags (pas génériques, pas déduits du nom de "
            + "dossier), une recherche MusicBrainz texte rapide est tentée D'ABORD ; si son score "
            + "dépasse le seuil ci-dessous, SongRec est sauté pour ce fichier. Accélère les "
            + "bibliothèques déjà bien taguées ET réduit le nombre d'appels au service Shazam "
            + "(utile si vous voyez des erreurs \"Too Many Requests\"/blocage dans le journal), au "
            + "prix d'un peu moins de vérification audio sur ces fichiers-là."));
        spnSkipSongRecMinScore = new JSpinner(new SpinnerNumberModel(90, 50, 100, 1));
        JPanel skipSongRecScorePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        skipSongRecScorePanel.add(new JLabel(I18n.t("Score MB minimum pour sauter SongRec :")));
        skipSongRecScorePanel.add(spnSkipSongRecMinScore);
        skipSongRecScorePanel.add(new JLabel("%"));

        cmbDiscogsGenreSource = new JComboBox<>(new String[]{
            I18n.t("Style puis Genre"), I18n.t("Genre puis Style"), I18n.t("Genre uniquement")});
        spDiscogsMaxGenres = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
        spLastfmMaxGenres  = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));

        tfPreferredFormats   = tf();
        tfPreferredFormats.setToolTipText(I18n.t("ex: CD,Digital Media,Vinyl — priorité décroissante"));
        tfVaName             = tf();
        chkStandardizeArtists = new JCheckBox(I18n.t("Utiliser les noms MusicBrainz standardisés (ex: \"The Beatles\" plutôt que \"Beatles, The\")"));
        chkStandardizeArtists.setToolTipText(I18n.t(
            "MusicBrainz = la base de données musicale en ligne (collaborative, gratuite) utilisée "
            + "pour identifier vos morceaux et récupérer leurs infos. Elle connaît le nom \"officiel\" "
            + "de chaque artiste ; cocher ceci l'utilise tel quel plutôt qu'une variante trouvée "
            + "ailleurs (Shazam, nom de fichier…)."));

        // ── Sélecteur de pays ISO ──────────────────────────────────────────────
        // Remplir le combo : "(Sélectionner...)" puis tous les pays triés
        String[] countryEntries = ISO_COUNTRIES.entrySet().stream()
            .map(e -> e.getKey() + " — " + e.getValue())
            .sorted()
            .toArray(String[]::new);
        String[] comboItems = new String[countryEntries.length + 1];
        comboItems[0] = I18n.t("(Sélectionner un pays…)");
        System.arraycopy(countryEntries, 0, comboItems, 1, countryEntries.length);
        cmbCountryPicker = new JComboBox<>(comboItems);

        lstPreferredCountries = new JList<>(lstCountriesModel);
        lstPreferredCountries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstPreferredCountries.setVisibleRowCount(4);
        JScrollPane countriesScroll = new JScrollPane(lstPreferredCountries);
        countriesScroll.setPreferredSize(new Dimension(220, 80));

        JButton btnAddCountry    = new JButton("+");
        JButton btnRemoveCountry = new JButton("−");
        JButton btnUpCountry     = new JButton("↑");
        JButton btnDownCountry   = new JButton("↓");
        for (JButton b : new JButton[]{btnAddCountry, btnRemoveCountry, btnUpCountry, btnDownCountry})
            b.setMargin(new Insets(1, 6, 1, 6));

        btnAddCountry.addActionListener(e -> {
            int idx = cmbCountryPicker.getSelectedIndex();
            if (idx <= 0) return;
            String label = (String) cmbCountryPicker.getSelectedItem();
            String code  = codeFromLabel(label);
            // Éviter les doublons
            for (int i = 0; i < lstCountriesModel.size(); i++)
                if (codeFromLabel(lstCountriesModel.get(i)).equals(code)) return;
            lstCountriesModel.addElement(label);
        });
        btnRemoveCountry.addActionListener(e -> {
            int sel = lstPreferredCountries.getSelectedIndex();
            if (sel >= 0) lstCountriesModel.remove(sel);
        });
        btnUpCountry.addActionListener(e -> {
            int sel = lstPreferredCountries.getSelectedIndex();
            if (sel > 0) { String v = lstCountriesModel.remove(sel); lstCountriesModel.add(sel-1, v); lstPreferredCountries.setSelectedIndex(sel-1); }
        });
        btnDownCountry.addActionListener(e -> {
            int sel = lstPreferredCountries.getSelectedIndex();
            if (sel >= 0 && sel < lstCountriesModel.size()-1) { String v = lstCountriesModel.remove(sel); lstCountriesModel.add(sel+1, v); lstPreferredCountries.setSelectedIndex(sel+1); }
        });

        JPanel countryBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        for (JButton b : new JButton[]{btnAddCountry, btnRemoveCountry, btnUpCountry, btnDownCountry})
            countryBtns.add(b);

        JPanel countryPicker = new JPanel(new BorderLayout(0, 4));
        countryPicker.add(cmbCountryPicker, BorderLayout.NORTH);
        countryPicker.add(countriesScroll,  BorderLayout.CENTER);
        countryPicker.add(countryBtns,      BorderLayout.SOUTH);
        // ── fin sélecteur pays ─────────────────────────────────────────────────

        JPanel matchPanel = form(new String[]{
            "Score minimum (%) :",
            "Seuil d'appariement piste (%) :",
            "Releases officielles seulement :",
            "",
            "Nb résultats MusicBrainz :",
            "Durée cache (jours) :",
            "",
            ""
        }, new JComponent[]{
            spMinScore, spTrackMatchThreshold, chkOnlyOfficial,
            chkUseAcoustId,
            spResultsLimit, spCacheDays,
            chkSkipSongRecOnConfidentMb, skipSongRecScorePanel
        }, "Critères de correspondance MusicBrainz");

        JPanel releasesPanel = form(new String[]{
            "Pays préférés (priorité décroissante) :",
            "Formats préférés (ex: CD,Digital Media) :",
            "Nom Various Artists :",
            ""
        }, new JComponent[]{
            countryPicker, tfPreferredFormats,
            tfVaName, chkStandardizeArtists
        }, "Releases préférées (comme Picard)");

        JPanel genrePanel = form(new String[]{
            "Source genres Discogs :",
            "Max genres Discogs :",
            "Max genres Last.fm :"
        }, new JComponent[]{
            cmbDiscogsGenreSource, spDiscogsMaxGenres, spLastfmMaxGenres
        }, "Sources de genres (Discogs / Last.fm)");

        // MB genres + filtre partagé (s'applique aussi à Discogs/Last.fm, voir GenreFilter)
        chkMbUseGenres    = new JCheckBox(I18n.t("Utiliser les genres proposés par les utilisateurs MusicBrainz"));
        chkMbUseGenres.setToolTipText(I18n.t(
            "MusicBrainz permet à ses utilisateurs de \"voter\" des genres sur chaque morceau (terme "
            + "technique : \"folksonomy\", une étiquette communautaire plutôt qu'une catégorie fixe). "
            + "Ce sont ces votes qu'on utilise ici, en plus des genres Discogs/Last.fm."));
        spMbMinGenreUsage = new JSpinner(new SpinnerNumberModel(50, 1, 500, 10));
        spMbMinGenreUsage.setToolTipText(I18n.t(
            "Nombre minimum de votes qu'un genre doit avoir reçu sur MusicBrainz pour être retenu — "
            + "évite les étiquettes rares ou farfelues ajoutées par une seule personne."));
        spMbMaxGenres     = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
        taGenresFilter    = new JTextArea(4, 20);
        taGenresFilter.setLineWrap(true);
        taGenresFilter.setToolTipText(I18n.t(
            "Un genre à exclure par ligne, précédé d'un tiret (ex: -seen live). S'applique à toutes "
            + "les sources de genres (Discogs, Last.fm, MusicBrainz) pour filtrer les étiquettes "
            + "inutiles ou hors-sujet (ex: \"seen live\", \"favorites\")."));
        JPanel mbGenrePanel = form(new String[]{
            "Genres votés par les utilisateurs MusicBrainz :", "Popularité minimale requise :",
            "Nombre max de genres retenus :", "Genres à exclure (un par ligne, préfixe -) :"
        }, new JComponent[]{
            chkMbUseGenres, spMbMinGenreUsage, spMbMaxGenres, new JScrollPane(taGenresFilter)
        }, "Filtre de genres");

        // ── Translittération artistes ──────────────────────────────────────────
        chkTranslateArtists = new JCheckBox(I18n.t("Convertir les noms d'artiste écrits en alphabet non-latin (ex: japonais, cyrillique)"));
        chkTranslateArtists.setToolTipText(I18n.t(
            "\"Translittérer\" = réécrire un nom dans un autre alphabet en gardant sa prononciation "
            + "(ex: \"BTS\" plutôt que \"방탄소년단\"). Utilise les noms alternatifs déjà connus de "
            + "MusicBrainz pour chaque langue listée ci-dessous, dans l'ordre — la première langue "
            + "qui donne un résultat est utilisée."));
        String[] localeItems = new String[TRANSLATE_LOCALES.length];
        for (int i = 0; i < TRANSLATE_LOCALES.length; i++)
            localeItems[i] = TRANSLATE_LOCALES[i][0] + " — " + I18n.t(TRANSLATE_LOCALES[i][1]);

        // Même widget "+/−/↑/↓" que les pays préférés (countryPicker ci-dessus) : plusieurs
        // langues, essayées dans l'ordre — MusicBrainzClient se rabat automatiquement sur "en"
        // en dernier recours si aucune de la liste ne donne de résultat.
        cmbTranslateLocalePicker = new JComboBox<>(localeItems);
        lstTranslateLocales = new JList<>(lstTranslateLocalesModel);
        lstTranslateLocales.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstTranslateLocales.setVisibleRowCount(4);
        JScrollPane localesScroll = new JScrollPane(lstTranslateLocales);
        localesScroll.setPreferredSize(new Dimension(220, 80));

        JButton btnAddLocale    = new JButton("+");
        JButton btnRemoveLocale = new JButton("−");
        JButton btnUpLocale     = new JButton("↑");
        JButton btnDownLocale   = new JButton("↓");
        for (JButton b : new JButton[]{btnAddLocale, btnRemoveLocale, btnUpLocale, btnDownLocale})
            b.setMargin(new Insets(1, 6, 1, 6));

        btnAddLocale.addActionListener(e -> {
            String label = (String) cmbTranslateLocalePicker.getSelectedItem();
            if (label == null) return;
            String code = codeFromLabel(label);
            for (int i = 0; i < lstTranslateLocalesModel.size(); i++)
                if (codeFromLabel(lstTranslateLocalesModel.get(i)).equals(code)) return; // pas de doublon
            lstTranslateLocalesModel.addElement(label);
        });
        btnRemoveLocale.addActionListener(e -> {
            int sel = lstTranslateLocales.getSelectedIndex();
            if (sel >= 0) lstTranslateLocalesModel.remove(sel);
        });
        btnUpLocale.addActionListener(e -> {
            int sel = lstTranslateLocales.getSelectedIndex();
            if (sel > 0) { String v = lstTranslateLocalesModel.remove(sel); lstTranslateLocalesModel.add(sel-1, v); lstTranslateLocales.setSelectedIndex(sel-1); }
        });
        btnDownLocale.addActionListener(e -> {
            int sel = lstTranslateLocales.getSelectedIndex();
            if (sel >= 0 && sel < lstTranslateLocalesModel.size()-1) { String v = lstTranslateLocalesModel.remove(sel); lstTranslateLocalesModel.add(sel+1, v); lstTranslateLocales.setSelectedIndex(sel+1); }
        });

        JPanel localeBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        for (JButton b : new JButton[]{btnAddLocale, btnRemoveLocale, btnUpLocale, btnDownLocale})
            localeBtns.add(b);

        JPanel localePicker = new JPanel(new BorderLayout(0, 4));
        localePicker.add(cmbTranslateLocalePicker, BorderLayout.NORTH);
        localePicker.add(localesScroll,             BorderLayout.CENTER);
        localePicker.add(localeBtns,                BorderLayout.SOUTH);

        JLabel transHint = new JLabel(I18n.t("<html><i>Exemple : 宇多田ヒカル → Hikaru Utada (locale=en).<br>Essayées dans l'ordre ; repli automatique sur \"en\" si aucune ne trouve d'alias.<br>Nécessite un artistMbid valide.</i></html>"));
        transHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        transHint.setBorder(new EmptyBorder(0, 10, 4, 0));
        JPanel transPanel = new JPanel(new BorderLayout()); transPanel.setBorder(new EmptyBorder(8,8,0,8));
        JPanel transInner = form(new String[]{"", "Locales cibles (priorité décroissante) :"}, new JComponent[]{chkTranslateArtists, localePicker}, "Translittération artistes");
        transInner.add(transHint, BorderLayout.SOUTH);
        transPanel.add(transInner, BorderLayout.CENTER);

        // ── Priorité de traitement ────────────────────────────────────────────
        chkPrioritizeIncomplete = new JCheckBox(I18n.t(
            "Traiter en priorité les fichiers avec beaucoup de tags manquants (titre, artiste, album…)"));
        chkPrioritizeIncomplete.setToolTipText(I18n.t(
            "<html>Quand activé, les fichiers sans titre/artiste/album sont traités <b>avant</b><br>" +
            "ceux qui n'ont que quelques infos à compléter.<br>" +
            "L'ordre d'affichage dans le tableau n'est pas modifié.</html>"));
        JLabel priorityHint = new JLabel(I18n.t(
            "<html><i>Les fichiers sans titre/artiste ont la priorité maximale. " +
            "Ceux avec titre+artiste+album sont traités en dernier.</i></html>"));
        priorityHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        priorityHint.setBorder(new EmptyBorder(0, 10, 4, 0));
        JPanel priorityInner = form(new String[]{""}, new JComponent[]{chkPrioritizeIncomplete},
            "Ordre de traitement");
        priorityInner.add(priorityHint, BorderLayout.SOUTH);

        // ── Filtres types de release ───────────────────────────────────────────
        for (int i = 0; i < PRIMARY_TYPES.length; i++)   chkPrimaryTypes[i]      = new JCheckBox(PRIMARY_TYPES[i]);
        for (int i = 0; i < SECONDARY_TYPES.length; i++) chkExcludedSecondary[i] = new JCheckBox(SECONDARY_TYPES[i]);

        JPanel rowPrimary = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (JCheckBox c : chkPrimaryTypes) rowPrimary.add(c);
        JPanel rowSecondary = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        for (JCheckBox c : chkExcludedSecondary) rowSecondary.add(c);

        JPanel releaseTypePanel = new JPanel(new GridBagLayout());
        releaseTypePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Filtres types de release (MusicBrainz)")));
        GridBagConstraints gtc = new GridBagConstraints();
        gtc.gridx = 0; gtc.gridy = 0; gtc.anchor = GridBagConstraints.WEST;
        gtc.insets = new Insets(4, 10, 2, 8);
        releaseTypePanel.add(new JLabel(I18n.t("Types primaires acceptés :")), gtc);
        gtc.gridy = 1; gtc.insets = new Insets(0, 6, 4, 8);
        releaseTypePanel.add(rowPrimary, gtc);
        gtc.gridy = 2; gtc.insets = new Insets(6, 10, 2, 8);
        releaseTypePanel.add(new JLabel(I18n.t("Types secondaires à exclure :")), gtc);
        gtc.gridy = 3; gtc.insets = new Insets(0, 6, 4, 8);
        releaseTypePanel.add(rowSecondary, gtc);
        JLabel typeHint = new JLabel(I18n.t("<html><i>Vide = aucun filtre. Décochez un type primaire pour l'ignorer.<br>" +
            "Cochez un type secondaire pour exclure ces releases (ex: Compilation, Live).</i></html>"));
        typeHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        typeHint.setBorder(new EmptyBorder(0, 10, 6, 0));
        gtc.gridy = 4; gtc.insets = new Insets(0, 0, 4, 0);
        releaseTypePanel.add(typeHint, gtc);
        JPanel releaseTypeWrap = new JPanel(new BorderLayout());
        releaseTypeWrap.setBorder(new EmptyBorder(8, 8, 0, 8));
        releaseTypeWrap.add(releaseTypePanel, BorderLayout.CENTER);

        // ── Séries de compilations à repérer (Stars 80, NRJ, Fun Radio, RFM…) ──────────────
        tfCompilationSeriesInput = new JTextField();
        lstCompilationSeries = new JList<>(lstCompilationSeriesModel);
        lstCompilationSeries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstCompilationSeries.setVisibleRowCount(4);
        JScrollPane compilationSeriesScroll = new JScrollPane(lstCompilationSeries);
        compilationSeriesScroll.setPreferredSize(new Dimension(220, 80));

        JButton btnAddCompilationSeries    = new JButton("+");
        JButton btnRemoveCompilationSeries = new JButton("−");
        JButton btnUpCompilationSeries     = new JButton("↑");
        JButton btnDownCompilationSeries   = new JButton("↓");
        for (JButton b : new JButton[]{btnAddCompilationSeries, btnRemoveCompilationSeries,
                btnUpCompilationSeries, btnDownCompilationSeries})
            b.setMargin(new Insets(1, 6, 1, 6));

        Runnable addCompilationSeries = () -> {
            String name = tfCompilationSeriesInput.getText().trim();
            if (name.isBlank()) return;
            for (int i = 0; i < lstCompilationSeriesModel.size(); i++)
                if (lstCompilationSeriesModel.get(i).equalsIgnoreCase(name)) {
                    tfCompilationSeriesInput.setText(""); return; // pas de doublon
                }
            lstCompilationSeriesModel.addElement(name);
            tfCompilationSeriesInput.setText("");
        };
        btnAddCompilationSeries.addActionListener(e -> addCompilationSeries.run());
        tfCompilationSeriesInput.addActionListener(e -> addCompilationSeries.run());
        btnRemoveCompilationSeries.addActionListener(e -> {
            int sel = lstCompilationSeries.getSelectedIndex();
            if (sel >= 0) lstCompilationSeriesModel.remove(sel);
        });
        btnUpCompilationSeries.addActionListener(e -> {
            int sel = lstCompilationSeries.getSelectedIndex();
            if (sel > 0) { String v = lstCompilationSeriesModel.remove(sel); lstCompilationSeriesModel.add(sel-1, v); lstCompilationSeries.setSelectedIndex(sel-1); }
        });
        btnDownCompilationSeries.addActionListener(e -> {
            int sel = lstCompilationSeries.getSelectedIndex();
            if (sel >= 0 && sel < lstCompilationSeriesModel.size()-1) { String v = lstCompilationSeriesModel.remove(sel); lstCompilationSeriesModel.add(sel+1, v); lstCompilationSeries.setSelectedIndex(sel+1); }
        });

        JPanel compilationSeriesBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        for (JButton b : new JButton[]{btnAddCompilationSeries, btnRemoveCompilationSeries,
                btnUpCompilationSeries, btnDownCompilationSeries})
            compilationSeriesBtns.add(b);

        JPanel compilationSeriesPicker = new JPanel(new BorderLayout(0, 4));
        compilationSeriesPicker.add(tfCompilationSeriesInput,   BorderLayout.NORTH);
        compilationSeriesPicker.add(compilationSeriesScroll,    BorderLayout.CENTER);
        compilationSeriesPicker.add(compilationSeriesBtns,      BorderLayout.SOUTH);

        JLabel compilationSeriesHint = new JLabel(I18n.t(
            "<html><i>Détection automatique : toute release marquée \"Compilation\" par<br>"
          + "MusicBrainz est déjà repérée sans rien configurer ici. Cette liste est facultative,<br>"
          + "pour ajouter en plus un nom de titre précis (ex. Stars 80, NRJ, Fun Radio, RFM)<br>"
          + "via Outils → Grouper par compilations.</i></html>"));
        compilationSeriesHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        compilationSeriesHint.setBorder(new EmptyBorder(0, 10, 4, 0));

        JPanel compilationSeriesPanel = new JPanel(new BorderLayout());
        compilationSeriesPanel.setBorder(new EmptyBorder(8, 8, 0, 8));
        JPanel compilationSeriesInner = form(new String[]{I18n.t("Séries de compilations à repérer :")},
                new JComponent[]{compilationSeriesPicker}, I18n.t("Compilations connues"));
        compilationSeriesInner.add(compilationSeriesHint, BorderLayout.SOUTH);
        compilationSeriesPanel.add(compilationSeriesInner, BorderLayout.CENTER);

        JPanel combined = new JPanel();
        combined.setLayout(new BoxLayout(combined, BoxLayout.Y_AXIS));
        combined.add(matchPanel);
        combined.add(releasesPanel);
        combined.add(releaseTypeWrap);
        combined.add(compilationSeriesPanel);
        combined.add(genrePanel);
        combined.add(mbGenrePanel);
        combined.add(transPanel);
        combined.add(priorityInner);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(combined, BorderLayout.NORTH);
        return wrap;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildTagsPanel() {
        cmbId3Version              = new JComboBox<>(new String[]{I18n.t("Garder version existante"), I18n.t("ID3v2.3 (compatible)"), I18n.t("ID3v2.4 (standard)")});
        cmbId3Version.setToolTipText(I18n.t(
            "ID3v2 = le format des tags (titre, artiste…) écrits dans un MP3. Deux variantes "
            + "existent : 2.3 (plus ancienne, compatible avec presque tous les lecteurs, même très "
            + "vieux) et 2.4 (plus récente, gère mieux certains caractères spéciaux). En cas de "
            + "doute, laissez \"Garder version existante\"."));
        chkPreserveTimestamps      = new JCheckBox(I18n.t("Préserver la date de modification du fichier"));
        chkPreserveTimestamps.setToolTipText(I18n.t(
            "Écrire des tags modifie normalement la date \"modifié le\" du fichier. Cocher ceci la "
            + "remet à sa valeur d'avant après l'écriture, comme si le fichier n'avait pas bougé."));
        chkClearExistingTags       = new JCheckBox(I18n.t("Effacer les tags existants avant écriture (repartir de zéro)"));
        chkClearExistingTags.setToolTipText(I18n.t(
            "Supprime TOUS les tags déjà présents sur le fichier avant d'écrire les nouveaux — utile "
            + "si d'anciens tags mal renseignés par un autre outil traînent et faussent l'affichage. "
            + "Déconseillé si vous avez des tags personnalisés que vous tenez à garder."));
        chkPreserveImages          = new JCheckBox(I18n.t("Conserver la pochette existante si aucune nouvelle"));
        chkPreserveCompilation     = new JCheckBox(I18n.t("Conserver le nom d'album des compilations (\"Various Artists\")"));
        chkPreserveCompilation.setToolTipText(I18n.t(
            "Pour un album compilé de plusieurs artistes (ex: une compilation radio), garde le titre "
            + "d'album déjà écrit sur le fichier plutôt que de le remplacer par celui trouvé sur "
            + "MusicBrainz — utile quand vous avez déjà organisé vos compilations à votre façon."));
        chkTrustExistingMbTags     = new JCheckBox(I18n.t("Faire confiance à une identification MusicBrainz déjà confirmée sur le fichier"));
        chkTrustExistingMbTags.setToolTipText(I18n.t(
            "Si le fichier a déjà un identifiant MusicBrainz écrit dedans (preuve qu'il a déjà été "
            + "identifié avec certitude, par OpenTagger ou un autre outil), ne pas chercher à "
            + "re-identifier le morceau — juste compléter ce qui manque. Accélère les bibliothèques "
            + "déjà bien taguées."));
        chkCorrectPunctuation      = new JCheckBox(I18n.t("Normaliser la ponctuation \"fantaisie\" en caractères simples (guillemets, tirets, points de suspension)"));
        chkRemoveId3v1             = new JCheckBox(I18n.t("Supprimer l'ancien format de tags ID3v1 des MP3 (128 octets obsolètes, inutiles à côté d'ID3v2)"));
        chkRemoveId3v1.setToolTipText(I18n.t(
            "ID3v1 est l'ancêtre du format de tags actuel (années 90), limité à 128 caractères, "
            + "remplacé depuis longtemps par ID3v2 (voir ci-dessus). Le garder en plus ne sert plus "
            + "à rien et peut semer la confusion avec certains lecteurs."));
        chkSaveAcoustidFingerprints   = new JCheckBox(I18n.t("Enregistrer l'empreinte audio AcoustID dans les tags du fichier"));
        chkSaveAcoustidFingerprints.setToolTipText(I18n.t(
            "AcoustID identifie un morceau à partir du son lui-même (voir l'onglet Matching). "
            + "Sauvegarder cette empreinte dans le fichier permet de la réutiliser plus tard sans "
            + "avoir à la recalculer (recalcul = plus lent, consomme du CPU)."));
        chkIgnoreExistingFingerprints = new JCheckBox(I18n.t("Toujours recalculer l'empreinte AcoustID, même si le fichier en a déjà une"));
        chkIgnoreExistingFingerprints.setToolTipText(I18n.t(
            "Une empreinte déjà présente peut avoir été écrite par un autre outil et être fausse ou "
            + "obsolète (ex: fichier ré-encodé depuis). Cocher ceci ignore l'ancienne et repart d'un "
            + "calcul frais à chaque fois — plus lent, mais plus fiable en cas de doute."));
        spFpcalcThreads            = new JSpinner(new SpinnerNumberModel(2, 1, 8, 1));
        spBatchThreads             = new JSpinner(new SpinnerNumberModel(6, 1, 16, 1));
        spBatchThreads.setToolTipText(I18n.t("Fichiers traités en parallèle pendant le taguage (GUI et CLI/--dossier). "
                + "Le rate-limit MusicBrainz (1 requête/s) reste respecté quel que soit ce réglage — "
                + "augmenter aide surtout les étapes non-MB (BPM, paroles, écriture disque)."));
        int cpuCores = Runtime.getRuntime().availableProcessors();
        JLabel batchThreadsHint = new JLabel(I18n.t(
            "<html><i>%d cœur(s) CPU détecté(s) — au-delà, gain limité : le frein réel devient<br>"
          + "MusicBrainz (1 req/s, indépendant de ce réglage) ou le disque mécanique (déjà<br>"
          + "limité à 1 accès à la fois par disque physique, voir Audio).</i></html>", cpuCores));
        batchThreadsHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        batchThreadsHint.setBorder(new EmptyBorder(0, 10, 4, 0));

        JLabel id3Hint = new JLabel(I18n.t(
            "<html><i>ID3v2.3 : recommandé pour voitures, NAS anciens, Windows Explorer.<br>" +
            "ID3v2.4 : standard actuel, supporte Unicode complet.</i></html>"));
        id3Hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        id3Hint.setBorder(new EmptyBorder(0, 10, 6, 0));

        JPanel tagInner = new JPanel(new GridBagLayout());
        tagInner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Écriture des tags")));
        Object[][] rows = {
            { "Version ID3 (MP3) :", cmbId3Version },
            { null, id3Hint },
            { "", chkPreserveTimestamps   },
            { "", chkClearExistingTags    },
            { "", chkPreserveImages       },
            { "", chkTrustExistingMbTags  },
            { "", chkPreserveCompilation  },
            { "", chkCorrectPunctuation   },
            { "", chkRemoveId3v1          },
        };
        for (int i = 0; i < rows.length; i++) {
            if (rows[i][0] != null) {
                GridBagConstraints lc = new GridBagConstraints();
                lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 10, 3, 8);
                tagInner.add(new JLabel(I18n.t((String) rows[i][0])), lc);
            }
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0; fc.insets = new Insets(3, 0, 3, 10); fc.gridwidth = 2;
            tagInner.add((JComponent) rows[i][1], fc);
        }

        spFpcalcThreads.setToolTipText(I18n.t(
            "Nombre de fichiers dont l'empreinte audio AcoustID est calculée EN MÊME TEMPS. Plus "
            + "haut = plus rapide mais plus de charge CPU ; inutile de dépasser le nombre de cœurs "
            + "de votre processeur."));

        JPanel fpInner = new JPanel(new GridBagLayout());
        fpInner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("AcoustID / Empreinte audio")));
        JComponent[][] fpRows = {
            { new JLabel(""), chkSaveAcoustidFingerprints },
            { new JLabel(""), chkIgnoreExistingFingerprints },
            { new JLabel(I18n.t("Threads fpcalc :")), spFpcalcThreads },
            { new JLabel(I18n.t("Threads de taguage :")), spBatchThreads },
            { new JLabel(""), batchThreadsHint },
        };
        for (int i = 0; i < fpRows.length; i++) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 10, 3, 8);
            fpInner.add(fpRows[i][0], lc);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0; fc.insets = new Insets(3, 0, 3, 10);
            fpInner.add(fpRows[i][1], fc);
        }

        // ── Tags préservés ────────────────────────────────────────────────────────
        tfPreservedTags = tf();
        tfPreservedTags.setToolTipText(I18n.t(
            "Champs qu'OpenTagger ne doit JAMAIS modifier ni effacer, même si une nouvelle valeur "
            + "est trouvée — utile pour une note personnelle (COMMENT) ou une note sur 5 étoiles "
            + "(RATING) que vous avez mise vous-même. Noms séparés par | (ex: RATING|COMMENT), "
            + "vide = rien de préservé."));
        JPanel preserveInner = form(new String[]{"Tags à ne jamais écraser :"},
                new JComponent[]{tfPreservedTags}, "Tags préservés");

        // ── Pochette fichier ──────────────────────────────────────────────────────
        // "Utiliser folder.jpg/cover.jpg local" a déménagé dans la liste des fournisseurs de
        // pochette juste en dessous (case "Dossier local") — plus besoin de case séparée ici.
        chkCoverSaveToFile   = new JCheckBox(I18n.t("Sauvegarder la pochette dans un fichier séparé"));
        chkCoverOverwriteFile= new JCheckBox(I18n.t("Écraser le fichier si déjà existant"));
        tfCoverFilename      = tf();
        JPanel coverFileInner = form(new String[]{
            "", "", "Nom du fichier (sans extension) :"
        }, new JComponent[]{
            chkCoverSaveToFile, chkCoverOverwriteFile, tfCoverFilename
        }, "Pochette en fichier (cover.jpg / folder.jpg)");

        // ── Fournisseurs de pochette : ordre + activation (pas d'ajout/suppression,
        // l'ensemble des 4 fournisseurs est fixe, contrairement aux pays ou aux scripts) ──
        lstCoverProviders = new JList<>(lstCoverProvidersModel);
        lstCoverProviders.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstCoverProviders.setVisibleRowCount(4);
        JScrollPane coverProvidersScroll = new JScrollPane(lstCoverProviders);
        coverProvidersScroll.setPreferredSize(new Dimension(320, 90));

        JButton btnToggleCoverProvider = new JButton(I18n.t("Activer/Désactiver"));
        JButton btnUpCoverProvider     = new JButton("↑");
        JButton btnDownCoverProvider   = new JButton("↓");
        for (JButton b : new JButton[]{btnToggleCoverProvider, btnUpCoverProvider, btnDownCoverProvider})
            b.setMargin(new Insets(1, 6, 1, 6));

        btnToggleCoverProvider.addActionListener(e -> {
            int sel = lstCoverProviders.getSelectedIndex();
            if (sel < 0) return;
            String id = coverProviderOrder.get(sel);
            // coverProviderOrder() n'expurge jamais un id inconnu déjà présent dans un
            // settings.properties édité à la main/corrompu/d'une autre version d'OpenTagger — .get()
            // renvoyait alors null pour un tel id, et !null levait une NullPointerException à
            // l'unboxing. getOrDefault(true) est déjà le pattern utilisé partout ailleurs dans ce
            // fichier pour lire cette même map (voir juste en dessous et save()).
            coverProviderEnabled.put(id, !coverProviderEnabled.getOrDefault(id, true));
            refreshCoverProvidersList();
            lstCoverProviders.setSelectedIndex(sel);
        });
        btnUpCoverProvider.addActionListener(e -> {
            int sel = lstCoverProviders.getSelectedIndex();
            if (sel > 0) {
                String v = coverProviderOrder.remove(sel);
                coverProviderOrder.add(sel - 1, v);
                refreshCoverProvidersList();
                lstCoverProviders.setSelectedIndex(sel - 1);
            }
        });
        btnDownCoverProvider.addActionListener(e -> {
            int sel = lstCoverProviders.getSelectedIndex();
            if (sel >= 0 && sel < coverProviderOrder.size() - 1) {
                String v = coverProviderOrder.remove(sel);
                coverProviderOrder.add(sel + 1, v);
                refreshCoverProvidersList();
                lstCoverProviders.setSelectedIndex(sel + 1);
            }
        });

        JPanel coverProviderBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        for (JButton b : new JButton[]{btnToggleCoverProvider, btnUpCoverProvider, btnDownCoverProvider})
            coverProviderBtns.add(b);

        JPanel coverProvidersInner = new JPanel(new BorderLayout(0, 4));
        coverProvidersInner.add(coverProvidersScroll, BorderLayout.CENTER);
        coverProvidersInner.add(coverProviderBtns,    BorderLayout.SOUTH);
        JPanel coverProvidersBox = new JPanel(new BorderLayout());
        coverProvidersBox.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Fournisseurs de pochette (ordre + activation)")));
        coverProvidersBox.add(coverProvidersInner, BorderLayout.CENTER);
        // ── fin fournisseurs de pochette ─────────────────────────────────────────────

        JPanel combined = new JPanel();
        combined.setLayout(new BoxLayout(combined, BoxLayout.Y_AXIS));

        JPanel pw  = new JPanel(new BorderLayout()); pw.setBorder(new EmptyBorder(8,8,0,8)); pw.add(tagInner,       BorderLayout.CENTER);
        JPanel fw  = new JPanel(new BorderLayout()); fw.setBorder(new EmptyBorder(8,8,0,8)); fw.add(fpInner,        BorderLayout.CENTER);
        JPanel prw = new JPanel(new BorderLayout()); prw.setBorder(new EmptyBorder(8,8,0,8));prw.add(preserveInner, BorderLayout.CENTER);
        JPanel cfw = new JPanel(new BorderLayout()); cfw.setBorder(new EmptyBorder(8,8,0,8));cfw.add(coverFileInner,BorderLayout.CENTER);
        JPanel cpw = new JPanel(new BorderLayout()); cpw.setBorder(new EmptyBorder(8,8,8,8));cpw.add(coverProvidersBox, BorderLayout.CENTER);
        combined.add(pw); combined.add(fw); combined.add(prw); combined.add(cfw); combined.add(cpw);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(combined, BorderLayout.NORTH);
        return wrap;
    }

    private static String labelForCoverProvider(String id) {
        for (int i = 0; i < COVER_PROVIDER_IDS.length; i++)
            if (COVER_PROVIDER_IDS[i].equals(id)) return I18n.t(COVER_PROVIDER_LABELS[i]);
        return id;
    }

    private void refreshCoverProvidersList() {
        lstCoverProvidersModel.clear();
        for (String id : coverProviderOrder) {
            boolean enabled = coverProviderEnabled.getOrDefault(id, true);
            lstCoverProvidersModel.addElement((enabled ? "☑ " : "☐ ") + labelForCoverProvider(id));
        }
    }

    private JPanel buildRenamePanel() {
        // Peupler le dropdown avec les libellés de masques depuis FileRenamer
        FileRenamer tmpRenamer = new FileRenamer();
        int maskCount = tmpRenamer.maskCount();
        String[] maskItems = new String[maskCount];
        for (int i = 0; i < maskCount; i++) {
            maskItems[i] = i + " — " + tmpRenamer.maskLabel(i);
        }
        cmbDefaultMask = new JComboBox<>(maskItems);
        cmbDefaultMask.setMaximumRowCount(maskCount);
        cmbDefaultMask.setToolTipText(I18n.t(
            "Le \"masque\" définit comment un fichier taguée est renommé/rangé — par exemple "
            + "Artiste/Album/NuméroPiste - Titre. Voir les exemples de résultat en bas de cet onglet."));

        chkAutoRename      = new JCheckBox(I18n.t("Renommer/ranger automatiquement les fichiers après le taguage"));
        chkAutoRename.setToolTipText(I18n.t(
            "Décoché : les tags sont écrits mais le fichier garde son nom et son emplacement "
            + "actuels. Coché : le fichier est en plus renommé et rangé selon le masque choisi "
            + "ci-dessous, juste après l'écriture des tags."));
        chkAutoRename.addActionListener(e -> cmbDefaultMask.setEnabled(chkAutoRename.isSelected()));
        chkDeleteEmptyDirs = new JCheckBox(I18n.t("Supprimer les dossiers devenus vides après un déplacement"));
        chkDeleteEmptyDirs.setToolTipText(I18n.t(
            "Quand un renommage déplace un fichier vers un nouveau dossier, l'ancien dossier peut se "
            + "retrouver vide (plus aucun fichier dedans) — cocher ceci le supprime automatiquement "
            + "plutôt que de laisser traîner des dossiers vides dans la bibliothèque."));
        chkFollowLog       = new JCheckBox(I18n.t("Garder le panneau Journal synchronisé sur le fichier en cours de traitement"));
        chkFollowLog.setToolTipText(I18n.t(
            "Fait défiler automatiquement le panneau Journal (en bas de la fenêtre principale) pour "
            + "toujours montrer la dernière ligne concernant le fichier actuellement traité, plutôt "
            + "que de devoir faire défiler vous-même pour suivre la progression."));

        // ── Exemples en temps réel ──────────────────────────────────────────────
        com.opentagger.model.TagInfo ex1 = new com.opentagger.model.TagInfo();
        ex1.artist = "Coldplay"; ex1.albumArtist = "Coldplay";
        ex1.album = "Parachutes"; ex1.title = "Yellow";
        ex1.track = "05"; ex1.year = "2000";

        com.opentagger.model.TagInfo ex2 = new com.opentagger.model.TagInfo();
        ex2.artist = "Daft Punk"; ex2.albumArtist = "Various Artists";
        ex2.album = "100 Club Hits"; ex2.title = "Get Lucky";
        ex2.track = "01"; ex2.year = "2022";
        ex2.isCompilation = "1";

        com.opentagger.model.TagInfo ex3 = new com.opentagger.model.TagInfo();
        ex3.artist = "Pink Floyd"; ex3.albumArtist = "Pink Floyd";
        ex3.album = "The Wall"; ex3.title = "Another Brick in the Wall";
        ex3.track = "06"; ex3.discNo = "01"; ex3.discTotal = "2"; ex3.year = "1979";

        com.opentagger.model.TagInfo ex4 = new com.opentagger.model.TagInfo();
        ex4.artist = "Adele"; ex4.albumArtist = "Adele";
        ex4.album = "25"; ex4.title = "Hello";
        ex4.track = "01"; ex4.year = "2015";

        JTextArea previewArea = new JTextArea(6, 60);
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        previewArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JScrollPane previewScroll = new JScrollPane(previewArea,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        previewScroll.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), I18n.t("Exemples de résultat")));

        Runnable updatePreview = () -> {
            int idx = cmbDefaultMask.getSelectedIndex();
            if (idx < 0) return;
            previewArea.setText(
                I18n.t("Album normal") + "   : " + tmpRenamer.preview(ex1, idx, ".mp3") + "\n" +
                I18n.t("Compilation") + "    : " + tmpRenamer.preview(ex2, idx, ".mp3") + "\n" +
                I18n.t("Multi-disc") + "     : " + tmpRenamer.preview(ex3, idx, ".mp3") + "\n" +
                I18n.t("Album/Single") + "   : " + tmpRenamer.preview(ex4, idx, ".mp3"));
            previewArea.setCaretPosition(0);
        };
        cmbDefaultMask.addActionListener(e -> updatePreview.run());
        updatePreview.run();

        // ── Dossier racine de la bibliothèque ──────────────────────────────────
        chkUseLibraryRoot = new JCheckBox(I18n.t("Utiliser un dossier racine de bibliothèque dédié"));
        tfLibraryRoot = tf();
        tfLibraryRoot.setToolTipText(I18n.t("Dossier racine où tous les fichiers seront déplacés/organisés. Laisser vide = utiliser le dossier scanné."));
        JButton btnBrowseRoot = new JButton("…");
        btnBrowseRoot.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(tfLibraryRoot.getText().isBlank()
                    ? System.getProperty("user.home") : tfLibraryRoot.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                tfLibraryRoot.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel rootPanel = new JPanel(new BorderLayout(4, 0));
        rootPanel.add(tfLibraryRoot, BorderLayout.CENTER);
        rootPanel.add(btnBrowseRoot, BorderLayout.EAST);

        // ── Dossier racine des podcasts ────────────────────────────────────────
        tfPodcastLibraryRoot = tf();
        tfPodcastLibraryRoot.setToolTipText(I18n.t("Dossier racine où les podcasts seront organisés (ex: /nas/Podcasts). Laisser vide = même racine que la musique."));
        JButton btnBrowsePodcast = new JButton("…");
        btnBrowsePodcast.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(tfPodcastLibraryRoot.getText().isBlank()
                    ? System.getProperty("user.home") : tfPodcastLibraryRoot.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                tfPodcastLibraryRoot.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel podcastRootPanel = new JPanel(new BorderLayout(4, 0));
        podcastRootPanel.add(tfPodcastLibraryRoot, BorderLayout.CENTER);
        podcastRootPanel.add(btnBrowsePodcast,     BorderLayout.EAST);

        // ── Dossier des fichiers non tagués (SKIPPED/ERROR) ────────────────────
        chkMoveSkipped = new JCheckBox(I18n.t("Déplacer les fichiers non tagués (ignorés/erreurs) vers ce dossier"));
        tfSkippedFolder = tf();
        tfSkippedFolder.setToolTipText(I18n.t("Dossier où isoler les fichiers non identifiés ou en erreur, hors de la bibliothèque organisée."));
        JButton btnBrowseSkipped = new JButton("…");
        btnBrowseSkipped.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(tfSkippedFolder.getText().isBlank()
                    ? System.getProperty("user.home") : tfSkippedFolder.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                tfSkippedFolder.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel skippedFolderPanel = new JPanel(new BorderLayout(4, 0));
        skippedFolderPanel.add(tfSkippedFolder,   BorderLayout.CENTER);
        skippedFolderPanel.add(btnBrowseSkipped,  BorderLayout.EAST);

        // ── Dossier des fichiers à durée incohérente avec MusicBrainz ──────────
        chkMoveDurationMismatch = new JCheckBox(I18n.t("Déplacer les fichiers à durée incohérente avec MusicBrainz vers ce dossier"));
        chkMoveDurationMismatch.setToolTipText(I18n.t(
            "Un fichier identifié dont la durée diffère fortement (>20s et >20%) de celle déclarée par "
            + "MusicBrainz pour ce titre est probablement un rip tronqué ou un mauvais match — jamais "
            + "enregistré tel quel (\"Enregistrer tout\" l'ignore). Activer ceci le déplace en plus vers "
            + "ce dossier dédié, séparé des fichiers non tagués ci-dessus. Rien n'est jamais supprimé."));
        tfDurationMismatchFolder = tf();
        tfDurationMismatchFolder.setToolTipText(I18n.t("Dossier où isoler les fichiers dont la durée ne correspond pas à MusicBrainz."));
        JButton btnBrowseDurationMismatch = new JButton("…");
        btnBrowseDurationMismatch.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(tfDurationMismatchFolder.getText().isBlank()
                    ? System.getProperty("user.home") : tfDurationMismatchFolder.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
                tfDurationMismatchFolder.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel durationMismatchFolderPanel = new JPanel(new BorderLayout(4, 0));
        durationMismatchFolderPanel.add(tfDurationMismatchFolder,      BorderLayout.CENTER);
        durationMismatchFolderPanel.add(btnBrowseDurationMismatch,     BorderLayout.EAST);

        // ── Récupération vidéo automatique ──────────────────────────────────────
        chkVideoAutoRecover = new JCheckBox(I18n.t(
            "Récupérer automatiquement l'audio des vidéos (.webm/.vob/.mpg/.mpeg/.avi/.mkv) à chaque scan de dossier"));
        chkVideoAutoRecover.setToolTipText(I18n.t(
            "À chaque Ouvrir dossier/Rafraîchir : cherche ces vidéos, tente de les identifier et de "
            + "les convertir en MP3 tagué, sans dialogue à ouvrir. Reconnues → sous-dossier \"Convertis\" ; "
            + "non reconnues → \"Non identifié\". Rien n'est jamais supprimé."));

        JPanel p = form(
            new String[]{"", "Dossier racine bibliothèque :", "Dossier racine podcasts :", "Masque par défaut :",
                    "", "", "", "", "Dossier fichiers non tagués :", "", "Dossier durée incohérente :", ""},
            new JComponent[]{chkUseLibraryRoot, rootPanel, podcastRootPanel, cmbDefaultMask, chkAutoRename, chkDeleteEmptyDirs,
                    chkFollowLog, chkMoveSkipped, skippedFolderPanel, chkMoveDurationMismatch, durationMismatchFolderPanel,
                    chkVideoAutoRecover},
            "Renommage automatique des fichiers");

        // Ajouter l'encart exemples en dessous des cases à cocher
        p.add(previewScroll, BorderLayout.CENTER);

        // Case à cocher qui grise/dégrise son champ dossier associé — pattern Picard
        // move_files → move_files_to (confirmé dans Picard.ini) : décoché = champ + bouton
        // parcourir désactivés, mais la valeur reste affichée (pas effacée) pour ne rien perdre
        // si l'utilisateur recoche ensuite.
        bindGate(chkUseLibraryRoot,      rootPanel);
        bindGate(chkMoveSkipped,         skippedFolderPanel);
        bindGate(chkMoveDurationMismatch, durationMismatchFolderPanel);
        return p;
    }

    /** Active/désactive récursivement {@code fields} (et leurs enfants directs, pour les JPanel
     *  contenant un JTextField + bouton "…") selon l'état de {@code chk} — appelé une fois à la
     *  construction et une fois dans loadSettings() pour l'état initial. Voir buildRenamePanel(). */
    private void bindGate(JCheckBox chk, JComponent... fields) {
        Runnable apply = () -> {
            boolean on = chk.isSelected();
            for (JComponent f : fields) {
                f.setEnabled(on);
                for (Component child : f.getComponents()) child.setEnabled(on);
            }
        };
        chk.addItemListener(e -> apply.run());
        apply.run();
    }

    private JPanel buildAudioPanel() {
        tfFfmpegPath        = tf();
        tfFfmpegPath.setToolTipText(I18n.t(
            "ffmpeg est l'outil qui lit/convertit l'audio en coulisses (transcodage, calcul du "
            + "volume, extraction d'un extrait pour la reconnaissance...). Laisser vide utilise la "
            + "version déjà installée sur votre système ; ne remplir que si vous en avez une copie "
            + "spécifique ailleurs."));
        tfEssentiaPath      = tf();
        tfEssentiaPath.setToolTipText(I18n.t(
            "Essentia est un outil optionnel qui analyse le son pour en déduire des caractéristiques "
            + "avancées (ambiance, dansabilité, genre probable…). Non installé par défaut — laisser "
            + "vide si vous ne savez pas ce que c'est, ça n'empêche rien de fonctionner."));
        tfFpcalcPath        = tf();
        tfFpcalcPath.setToolTipText(I18n.t(
            "fpcalc calcule l'empreinte audio utilisée par AcoustID (voir l'onglet Matching) pour "
            + "identifier un morceau à partir du son. Requis pour qu'AcoustID fonctionne — le bouton "
            + "\"Télécharger fpcalc…\" ci-dessous l'installe automatiquement si absent."));
        chkLyricsEnabled    = new JCheckBox(I18n.t("Activer la récupération des paroles"));
        chkSaveLrc          = new JCheckBox(I18n.t("Écrire un fichier .lrc à côté (paroles synchronisées avec le son, si trouvées)"));
        chkSaveLrc.setToolTipText(I18n.t(
            "Un fichier .lrc contient les paroles avec un horodatage par ligne, pour un affichage "
            + "synchronisé pendant la lecture (comme un karaoké) — supporté par la plupart des "
            + "lecteurs audio modernes. Cherché sur lrclib.net, une base de paroles gratuite."));
        chkReplayGainEnabled= new JCheckBox(I18n.t("Égaliser automatiquement le volume entre morceaux (ReplayGain)"));
        chkReplayGainEnabled.setToolTipText(I18n.t(
            "ReplayGain calcule de combien monter/baisser le volume de chaque morceau pour qu'ils "
            + "sonnent tous à peu près aussi fort en lecture aléatoire (certains albums sont "
            + "enregistrés bien plus fort que d'autres). N'écrase pas le fichier audio lui-même, "
            + "juste une information de volume que le lecteur applique — nécessite ffmpeg, plus lent."));
        chkCamelotKey       = new JCheckBox(I18n.t("Écrire la tonalité au format Camelot (8A/8B…) — utilisé par les DJ pour le mixage harmonique"));
        chkCamelotKey.setToolTipText(I18n.t(
            "La tonalité musicale d'un morceau (do majeur, la mineur…) peut s'écrire en notation "
            + "classique ou en \"roue Camelot\" (un code court comme 8A), un système utilisé par les "
            + "DJ pour repérer facilement quels morceaux s'enchaînent bien harmoniquement. Sans "
            + "rapport avec le mixage/DJing si vous n'en faites pas — laissez décoché sinon."));
        lblFpcalcStatus     = new JLabel();

        refreshFpcalcStatus();

        JButton btnFpcalcDownload = new JButton(I18n.t("Télécharger fpcalc…"));
        btnFpcalcDownload.addActionListener(e -> downloadFpcalc(btnFpcalcDownload));

        JPanel fpcalcRow = new JPanel(new BorderLayout(4, 0));
        fpcalcRow.add(tfFpcalcPath,        BorderLayout.CENTER);
        fpcalcRow.add(btnFpcalcDownload,   BorderLayout.EAST);

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Outils audio externes (optionnels)")));

        Object[][] rows = {
            { "Chemin ffmpeg :",        tfFfmpegPath,       "https://ffmpeg.org/download.html"               },
            { "Chemin Essentia :",       tfEssentiaPath,     null                                             },
            { "Chemin fpcalc :",         fpcalcRow,          "https://acoustid.org/chromaprint"              },
            { "Paroles (LyricsOvh) :",  chkLyricsEnabled,   "https://lyricsovh.docs.apiary.io/"             },
            { "",                       chkSaveLrc,         "https://lrclib.net"                            },
            { "ReplayGain :",           chkReplayGainEnabled, null                                           },
            { "",                       chkCamelotKey,      null                                             },
        };
        for (int i = 0; i < rows.length; i++) {
            String     label = I18n.t((String) rows[i][0]);
            JComponent field = (JComponent) rows[i][1];
            String     url   = (String) rows[i][2];

            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(3, 10, 3, 8);
            inner.add(new JLabel(label), lc);

            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0; fc.insets = new Insets(3, 0, 3, 4);
            inner.add(field, fc);

            GridBagConstraints bc = new GridBagConstraints();
            bc.gridx = 2; bc.gridy = i; bc.anchor = GridBagConstraints.WEST;
            bc.insets = new Insets(3, 0, 3, 8);
            inner.add(url != null ? apiLinkBtn(url) : new JLabel(""), bc);
        }

        // SongRec : ligne dédiée avec lien GitHub
        JPanel songrecRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        songrecRow.add(new JLabel(I18n.t("<html><i>SongRec (Shazam open-source) :</i></html>")));
        songrecRow.add(apiLinkBtn("https://github.com/marin-m/SongRec#installation"));
        GridBagConstraints src = new GridBagConstraints();
        src.gridx = 0; src.gridy = rows.length; src.gridwidth = 3;
        src.anchor = GridBagConstraints.WEST; src.insets = new Insets(4, 8, 2, 8);
        inner.add(songrecRow, src);

        JPanel audioSection = new JPanel(new BorderLayout(0, 4));
        audioSection.add(inner, BorderLayout.CENTER);
        audioSection.add(lblFpcalcStatus, BorderLayout.SOUTH);
        audioSection.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel transcodeSection = buildTranscodeSection();
        transcodeSection.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Onglets "Audio" + "Transcodage" fusionnés (2 des 10 onglets de Préférences, tous deux
        // liés à ffmpeg) — piste identifiée dans une session précédente mais laissée de côté à
        // l'époque, reprise ici sur demande explicite.
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(audioSection);
        p.add(Box.createVerticalStrut(14));
        p.add(transcodeSection);
        return p;
    }

    private void refreshFpcalcStatus() {
        String path = FpcalcInstaller.resolve();
        if (path != null) {
            lblFpcalcStatus.setText("✓ " + I18n.t("fpcalc trouvé : %s", path));
            lblFpcalcStatus.putClientProperty("FlatLaf.style", "foreground: #4caf50; font: 11 $defaultFont");
        } else {
            lblFpcalcStatus.setText("✗ " + I18n.t("fpcalc non trouvé — identification AcoustID désactivée"));
            lblFpcalcStatus.putClientProperty("FlatLaf.style", "foreground: #f44336; font: 11 $defaultFont");
        }
        lblFpcalcStatus.setBorder(new EmptyBorder(6, 0, 0, 0));
    }

    private void downloadFpcalc(JButton btn) {
        btn.setEnabled(false);
        btn.setText(I18n.t("Téléchargement…"));
        new SwingWorker<String, String>() {
            @Override protected String doInBackground() throws Exception {
                return FpcalcInstaller.download(msg -> publish(msg));
            }
            @Override protected void process(java.util.List<String> chunks) {
                lblFpcalcStatus.setText(chunks.get(chunks.size() - 1));
            }
            @Override protected void done() {
                try {
                    String path = get();
                    tfFpcalcPath.setText(path);
                    refreshFpcalcStatus();
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                        I18n.t("fpcalc installé avec succès :\n%s", path), I18n.t("Installation"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                        I18n.t("Erreur : %s", ex.getMessage()), I18n.t("Installation fpcalc"), JOptionPane.ERROR_MESSAGE);
                    refreshFpcalcStatus();
                }
                btn.setEnabled(true);
                btn.setText(I18n.t("Télécharger fpcalc…"));
            }
        }.execute();
    }

    /** Contenu de l'ancien onglet "Transcodage", maintenant empilé sous celui d'"Audio" (voir
     *  buildAudioPanel()) — plus un onglet séparé, juste une 2e carte dans le même onglet. */
    @SuppressWarnings("unchecked")
    private JPanel buildTranscodeSection() {
        chkTranscodeAuto = new JCheckBox(I18n.t("Convertir automatiquement le format audio avant le taguage"));
        chkTranscodeAuto.setToolTipText(I18n.t(
            "\"Transcoder\" = convertir un fichier audio d'un format à un autre (ex: FLAC → MP3). "
            + "Cocher ceci convertit automatiquement chaque fichier vers le format choisi ci-dessous "
            + "avant de le taguer — pratique pour uniformiser une bibliothèque aux formats mélangés."));
        cmbTranscodeFormat = new JComboBox<>(new String[]{"MP3", "FLAC", "AAC (M4A)", "OGG", "OPUS"});
        spTranscodeBitrate = new JSpinner(new SpinnerNumberModel(320, 64, 320, 32));
        chkTranscodeDeleteSource = new JCheckBox(I18n.t("Supprimer le fichier d'origine après conversion réussie"));
        chkTranscodeDeleteSource.setToolTipText(I18n.t(
            "Décoché (par défaut) : garde les deux fichiers (original + converti). Coché : supprime "
            + "l'original une fois la conversion confirmée réussie — irréversible, à activer seulement "
            + "si vous êtes sûr de ne pas vouloir revenir au format d'origine."));
        lblTranscodeBitrate = new JLabel(I18n.t("Débit (kbps) — qualité/taille, ignoré pour le FLAC sans perte :"));

        // Masquer le débit pour les formats sans débit (FLAC)
        cmbTranscodeFormat.addActionListener(e -> {
            boolean hasBitrate = cmbTranscodeFormat.getSelectedIndex() != 1; // 1 = FLAC
            lblTranscodeBitrate.setEnabled(hasBitrate);
            spTranscodeBitrate.setEnabled(hasBitrate);
        });

        // ── Fichiers source illisibles (moov atom manquant, flux corrompu, souvent carrément
        // 0 octet — dégâts collatéraux constatés de l'incident disque plein du 2026-07-15) ────
        // Confirmé par DEUX passes ffmpeg indépendantes avant toute action (voir AudioTranscoder.
        // verifyUnreadable()) — jamais sur la foi d'un seul message d'erreur du transcodage.
        // Envoyés à la corbeille système (récupérable), jamais supprimés définitivement.
        chkMoveUnreadable = new JCheckBox(I18n.t("Envoyer à la corbeille les fichiers confirmés illisibles par ffmpeg"));
        chkMoveUnreadable.setToolTipText(I18n.t(
            "Un fichier que ffmpeg ne parvient même pas à ouvrir (\"moov atom not found\", \"Invalid "
            + "data found\"...), confirmé par une seconde vérification indépendante (décodage seul, "
            + "rien écrit sur le disque), est presque toujours corrompu ou vide (0 octet) — "
            + "irrécupérable en l'état. Envoyé à la corbeille système, jamais supprimé définitivement."));

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Transcodage audio (via ffmpeg)")));

        GridBagConstraints lc = gbc(0, 0); lc.anchor = GridBagConstraints.WEST;
        GridBagConstraints fc = gbc(1, 0); fc.fill = GridBagConstraints.NONE;

        Object[][] rows = {
            { chkTranscodeAuto,         null },
            { new JLabel(I18n.t("Format cible :")), cmbTranscodeFormat },
            { lblTranscodeBitrate,      spTranscodeBitrate },
            { chkTranscodeDeleteSource, null },
            { chkMoveUnreadable,        null },
        };

        for (int i = 0; i < rows.length; i++) {
            JComponent left  = (JComponent) rows[i][0];
            JComponent right = (JComponent) rows[i][1];
            lc.gridy = i; fc.gridy = i;
            if (right == null) {
                lc.gridwidth = 2; inner.add(left, lc); lc.gridwidth = 1;
            } else {
                inner.add(left,  lc);
                inner.add(right, fc);
            }
        }

        JLabel note = new JLabel(I18n.t("<html><i>Note : les tags existants sont préservés. " +
                "Les deux fichiers sont conservés par défaut.</i></html>"));
        note.setBorder(new EmptyBorder(8, 12, 4, 8));
        note.putClientProperty("FlatLaf.style", "foreground: #aaaaaa; font: 11 $defaultFont");

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.add(inner, BorderLayout.NORTH);
        p.add(note,  BorderLayout.CENTER);
        return p;
    }

    private GridBagConstraints gbc(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x; c.gridy = y;
        c.insets = new Insets(4, 10, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    private JPanel buildScriptPanel() {
        // Case globale façon Picard ("Enable Tagger Script(s)", voir Picard.ini
        // enable_tagger_scripts) : coupe tous les scripts activés d'un coup, sans avoir à
        // décocher chacun individuellement — vérifiée dans TaggerScript.apply().
        chkScriptsEnabled = new JCheckBox(I18n.t("Activer les scripts"));
        chkScriptsEnabled.setToolTipText(I18n.t(
                "Décoché, aucun script (même activé individuellement ci-dessous) ne s'exécute."));

        taTaggerScript = new JTextArea(18, 60);
        taTaggerScript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        taTaggerScript.setLineWrap(false);
        taTaggerScript.setTabSize(4);
        JScrollPane scriptScroll = new JScrollPane(taTaggerScript,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        JLabel lblDesc = new JLabel(I18n.t(
            "<html>Le script est exécuté <b>après l'identification et la correction</b> de chaque fichier,<br>" +
            "juste avant l'écriture des tags. Modifiez <code>tags.champ</code> pour transformer les métadonnées.<br><br>" +
            "<b>Champs accessibles :</b> title, artist, albumArtist, album, year, track, trackTotal,<br>" +
            "discNo, discTotal, genre, composer, conductor, lyricist, bpm, mood, language, isrc,<br>" +
            "isClassical, isCompilation, isLive, artistMbid, releaseMbid, recordingMbid, comment</html>"));
        lblDesc.setFont(lblDesc.getFont().deriveFont(11f));
        lblDesc.putClientProperty("FlatLaf.style", "foreground: #888888");
        lblDesc.setBorder(new EmptyBorder(0, 0, 8, 0));

        // ── Exemples prêts à insérer ────────────────────────────────────────────
        String[][] scriptExamples = {
            {
                "Synchroniser albumArtist",
                "// Si albumArtist est vide, copier l'artiste\nif (!tags.albumArtist) tags.albumArtist = tags.artist;"
            },
            {
                "Supprimer (feat. ...)",
                "// Supprimer les mentions feat. du titre\ntags.title = tags.title.replace(/\\s*\\(feat\\..*?\\)/gi, '').trim();\ntags.artist = tags.artist.replace(/\\s*(feat\\.|ft\\.).*$/gi, '').trim();"
            },
            {
                "Marquer compilations",
                "// Forcer isCompilation si l'albumArtist est Various Artists\nif (tags.albumArtist === 'Various Artists') tags.isCompilation = '1';"
            },
            {
                "Nettoyer apostrophes",
                "// Remplacer apostrophes typographiques par apostrophe droite\ntags.title  = tags.title.replace(/[\\u2018\\u2019\\u02BC]/g, \"'\");\ntags.artist = tags.artist.replace(/[\\u2018\\u2019\\u02BC]/g, \"'\");\ntags.album  = tags.album.replace(/[\\u2018\\u2019\\u02BC]/g, \"'\");"
            },
            {
                "Genre Classical auto",
                "// Si compositeur renseigné et genre vide → Classical\nif (tags.composer && !tags.genre) tags.genre = 'Classical';"
            },
            {
                "Supprimer numéro du titre",
                "// Supprimer le numéro de piste en début de titre : '01 - Titre' → 'Titre'\ntags.title = tags.title.replace(/^\\d{1,3}[\\s\\-\\.]+/, '').trim();"
            },
            {
                "Forcer majuscule titre",
                "// Première lettre du titre en majuscule\nif (tags.title.length > 0) tags.title = tags.title.charAt(0).toUpperCase() + tags.title.slice(1);"
            },
            {
                "Année originale seulement",
                "// Garder seulement les 4 chiffres de l'année\nif (tags.year) tags.year = tags.year.replace(/.*?(\\d{4}).*/, '$1');"
            },
        };

        JPanel examplesGrid = new JPanel(new java.awt.GridLayout(0, 1, 0, 2));
        for (String[] ex : scriptExamples) {
            String label = I18n.t(ex[0]);
            String code  = ex[1];
            JPanel row = new JPanel(new BorderLayout(6, 0));
            JButton btnInsert = new JButton("+ " + I18n.t("Insérer"));
            btnInsert.setFont(btnInsert.getFont().deriveFont(10f));
            btnInsert.setMargin(new java.awt.Insets(1, 6, 1, 6));
            btnInsert.setToolTipText(code);
            btnInsert.addActionListener(e -> {
                String current = taTaggerScript.getText();
                String insert  = (current.isBlank() ? "" : "\n\n") + "// ── " + label + " ──\n" + code;
                taTaggerScript.append(insert);
                taTaggerScript.setCaretPosition(taTaggerScript.getText().length());
            });
            JLabel lbl = new JLabel("<html><b>" + label + "</b>&nbsp;&nbsp;<font color='#888888'><code>"
                    + code.replace("\n", " | ").replace("<", "&lt;").substring(0, Math.min(70, code.length())) + "…</code></font></html>");
            lbl.setFont(lbl.getFont().deriveFont(11f));
            row.add(lbl,       BorderLayout.CENTER);
            row.add(btnInsert, BorderLayout.EAST);
            examplesGrid.add(row);
        }

        JScrollPane exScroll = new JScrollPane(examplesGrid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        exScroll.setPreferredSize(new java.awt.Dimension(0, 180));

        JPanel exBox = new JPanel(new BorderLayout());
        exBox.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                I18n.t("Exemples — cliquez « + Insérer » pour ajouter dans le script")));
        exBox.add(exScroll, BorderLayout.CENTER);

        JPanel scriptBox = new JPanel(new BorderLayout(0, 6));
        scriptBox.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Script sélectionné")));
        scriptBox.add(lblDesc, BorderLayout.NORTH);
        scriptBox.add(scriptScroll, BorderLayout.CENTER);

        // ── Liste des scripts (plusieurs scripts nommés, activables individuellement,
        // dans l'esprit des greffons Picard) ────────────────────────────────────────
        lstScripts = new JList<>(lstScriptsModel);
        lstScripts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scriptsListScroll = new JScrollPane(lstScripts);
        scriptsListScroll.setPreferredSize(new Dimension(200, 0));

        JButton btnNewScript    = new JButton("+ " + I18n.t("Nouveau"));
        JButton btnDeleteScript = new JButton("− " + I18n.t("Supprimer"));
        JButton btnToggleScript = new JButton(I18n.t("Activer/Désactiver"));
        JButton btnRenameScript = new JButton(I18n.t("Renommer…"));
        for (JButton b : new JButton[]{btnNewScript, btnDeleteScript, btnToggleScript, btnRenameScript})
            b.setMargin(new Insets(1, 6, 1, 6));

        btnNewScript.addActionListener(e -> {
            flushCurrentScriptEdits();
            String name = JOptionPane.showInputDialog(this, I18n.t("Nom du script :"), I18n.t("Nouveau script"));
            if (name == null || name.isBlank()) return;
            scriptDefs.add(new TaggerScript.ScriptDef(name.trim(), true, ""));
            refreshScriptsList();
            lstScripts.setSelectedIndex(scriptDefs.size() - 1);
        });
        btnDeleteScript.addActionListener(e -> {
            int sel = lstScripts.getSelectedIndex();
            if (sel < 0) return;
            // Un script peut représenter des dizaines de lignes écrites à la main — supprimait
            // avant sans aucune confirmation, contrairement à des suppressions bien moins coûteuses
            // ailleurs (historique de taguage, doublons) qui, elles, avertissent explicitement.
            int choice = JOptionPane.showConfirmDialog(this,
                I18n.t("Supprimer le script « %s » ?\nCette action est irréversible.", scriptDefs.get(sel).name()),
                I18n.t("Confirmer la suppression"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
            scriptDefs.remove(sel);
            currentScriptIndex = -1;
            refreshScriptsList();
            taTaggerScript.setText("");
            taTaggerScript.setEnabled(false);
        });
        btnToggleScript.addActionListener(e -> {
            int sel = lstScripts.getSelectedIndex();
            if (sel < 0) return;
            TaggerScript.ScriptDef d = scriptDefs.get(sel);
            scriptDefs.set(sel, new TaggerScript.ScriptDef(d.name(), !d.enabled(), d.code()));
            refreshScriptsList();
            lstScripts.setSelectedIndex(sel);
        });
        btnRenameScript.addActionListener(e -> {
            int sel = lstScripts.getSelectedIndex();
            if (sel < 0) return;
            TaggerScript.ScriptDef d = scriptDefs.get(sel);
            String name = JOptionPane.showInputDialog(this, I18n.t("Nouveau nom :"), d.name());
            if (name == null || name.isBlank()) return;
            scriptDefs.set(sel, new TaggerScript.ScriptDef(name.trim(), d.enabled(), d.code()));
            refreshScriptsList();
            lstScripts.setSelectedIndex(sel);
        });
        lstScripts.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int sel = lstScripts.getSelectedIndex();
            if (sel == currentScriptIndex) return;
            flushCurrentScriptEdits();
            currentScriptIndex = sel;
            taTaggerScript.setText(sel >= 0 && sel < scriptDefs.size() ? scriptDefs.get(sel).code() : "");
            taTaggerScript.setEnabled(sel >= 0);
        });

        JPanel scriptBtns = new JPanel(new java.awt.GridLayout(0, 1, 3, 3));
        for (JButton b : new JButton[]{btnNewScript, btnDeleteScript, btnToggleScript, btnRenameScript})
            scriptBtns.add(b);

        JPanel scriptsListPanel = new JPanel(new BorderLayout(0, 4));
        scriptsListPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Scripts")));
        scriptsListPanel.add(scriptsListScroll, BorderLayout.CENTER);
        scriptsListPanel.add(scriptBtns, BorderLayout.SOUTH);
        scriptsListPanel.setPreferredSize(new Dimension(220, 0));
        // ── fin liste des scripts ────────────────────────────────────────────────────

        JPanel centerSplit = new JPanel(new BorderLayout(10, 0));
        centerSplit.add(scriptsListPanel, BorderLayout.WEST);
        centerSplit.add(scriptBox,        BorderLayout.CENTER);

        // ── Commande(s) après taguage — déplacée depuis l'onglet Démarrage, étendue en liste
        // (voir PostTagCommands, remplace l'ancien champ texte unique hooks.post_tag_command) ────
        lstPostTagCommands = new JList<>(lstPostTagCommandsModel);
        lstPostTagCommands.setVisibleRowCount(4);
        JScrollPane postTagScroll = new JScrollPane(lstPostTagCommands);

        JButton btnAddCmd    = new JButton("+ " + I18n.t("Ajouter"));
        JButton btnEditCmd   = new JButton(I18n.t("Modifier…"));
        JButton btnRemoveCmd = new JButton("− " + I18n.t("Supprimer"));
        for (JButton b : new JButton[]{btnAddCmd, btnEditCmd, btnRemoveCmd}) b.setMargin(new Insets(1, 6, 1, 6));

        btnAddCmd.addActionListener(e -> {
            String cmd = JOptionPane.showInputDialog(this, I18n.t("Commande shell :"), I18n.t("Nouvelle commande"));
            if (cmd == null || cmd.isBlank()) return;
            postTagCommands.add(cmd.trim());
            lstPostTagCommandsModel.addElement(cmd.trim());
        });
        btnEditCmd.addActionListener(e -> {
            int sel = lstPostTagCommands.getSelectedIndex();
            if (sel < 0) return;
            String cmd = JOptionPane.showInputDialog(this, I18n.t("Commande shell :"), postTagCommands.get(sel));
            if (cmd == null || cmd.isBlank()) return;
            postTagCommands.set(sel, cmd.trim());
            lstPostTagCommandsModel.set(sel, cmd.trim());
        });
        btnRemoveCmd.addActionListener(e -> {
            int sel = lstPostTagCommands.getSelectedIndex();
            if (sel < 0) return;
            postTagCommands.remove(sel);
            lstPostTagCommandsModel.remove(sel);
        });

        JPanel postTagBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JButton b : new JButton[]{btnAddCmd, btnEditCmd, btnRemoveCmd}) postTagBtns.add(b);

        JPanel postTagPanel = new JPanel(new BorderLayout(0, 4));
        postTagPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                I18n.t("Commande(s) après taguage")));
        JLabel postTagHint = new JLabel(I18n.t(
                "<html><i>Exécutées une fois, dans l'ordre, à la fin d'un run de taguage complet " +
                "(pas par fichier).</i></html>"));
        postTagHint.setFont(postTagHint.getFont().deriveFont(11f));
        postTagHint.putClientProperty("FlatLaf.style", "foreground: #888888");
        postTagPanel.add(postTagHint,  BorderLayout.NORTH);
        postTagPanel.add(postTagScroll, BorderLayout.CENTER);
        postTagPanel.add(postTagBtns,  BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(0, 10));
        south.add(exBox,       BorderLayout.NORTH);
        south.add(postTagPanel, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout(0, 10));
        outer.setBorder(new EmptyBorder(10, 10, 10, 10));
        outer.add(chkScriptsEnabled, BorderLayout.NORTH);
        outer.add(centerSplit, BorderLayout.CENTER);
        outer.add(south, BorderLayout.SOUTH);
        return outer;
    }

    /** Reconstruit l'affichage de la liste des scripts depuis scriptDefs (nom + case activé). */
    private void refreshScriptsList() {
        lstScriptsModel.clear();
        for (TaggerScript.ScriptDef d : scriptDefs)
            lstScriptsModel.addElement((d.enabled() ? "☑ " : "☐ ") + d.name());
    }

    /** Sauve le texte actuellement affiché dans le ScriptDef en cours d'édition. */
    private void flushCurrentScriptEdits() {
        if (currentScriptIndex >= 0 && currentScriptIndex < scriptDefs.size()) {
            TaggerScript.ScriptDef d = scriptDefs.get(currentScriptIndex);
            scriptDefs.set(currentScriptIndex,
                    new TaggerScript.ScriptDef(d.name(), d.enabled(), taTaggerScript.getText()));
        }
    }

    private static String labelForToolbarAction(String id) {
        for (String[] info : MainFrame.TOOLBAR_ACTION_INFOS)
            if (info[0].equals(id)) return info[1];
        return id;
    }

    private void refreshToolbarActionsList() {
        lstToolbarActionsModel.clear();
        for (String id : toolbarActionIds) lstToolbarActionsModel.addElement(labelForToolbarAction(id));
    }

    @SuppressWarnings("unchecked")
    private JPanel buildToolbarPanel() {
        JLabel lblDesc = new JLabel(I18n.t(
            "<html>Boutons secondaires affichés dans la barre principale, en plus des boutons fixes<br>" +
            "(Ouvrir dossier, Tout tagger, Tagger la sélection, Enregistrer tout, Annuler).</html>"));
        lblDesc.setFont(lblDesc.getFont().deriveFont(11f));
        lblDesc.putClientProperty("FlatLaf.style", "foreground: #888888");
        lblDesc.setBorder(new EmptyBorder(0, 0, 8, 0));

        String[] pickerItems = new String[MainFrame.TOOLBAR_ACTION_INFOS.size() + 1];
        pickerItems[0] = I18n.t("(Sélectionner une action…)");
        for (int i = 0; i < MainFrame.TOOLBAR_ACTION_INFOS.size(); i++)
            pickerItems[i + 1] = MainFrame.TOOLBAR_ACTION_INFOS.get(i)[1];
        cmbToolbarActionPicker = new JComboBox<>(pickerItems);

        lstToolbarActions = new JList<>(lstToolbarActionsModel);
        lstToolbarActions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstToolbarActions.setVisibleRowCount(8);
        JScrollPane toolbarActionsScroll = new JScrollPane(lstToolbarActions);
        toolbarActionsScroll.setPreferredSize(new Dimension(280, 160));

        JButton btnAddAction    = new JButton("+");
        JButton btnRemoveAction = new JButton("−");
        JButton btnUpAction     = new JButton("↑");
        JButton btnDownAction   = new JButton("↓");
        for (JButton b : new JButton[]{btnAddAction, btnRemoveAction, btnUpAction, btnDownAction})
            b.setMargin(new Insets(1, 6, 1, 6));

        btnAddAction.addActionListener(e -> {
            int idx = cmbToolbarActionPicker.getSelectedIndex();
            if (idx <= 0) return;
            String id = MainFrame.TOOLBAR_ACTION_INFOS.get(idx - 1)[0];
            if (toolbarActionIds.contains(id)) return; // éviter les doublons
            toolbarActionIds.add(id);
            refreshToolbarActionsList();
        });
        btnRemoveAction.addActionListener(e -> {
            int sel = lstToolbarActions.getSelectedIndex();
            if (sel >= 0) { toolbarActionIds.remove(sel); refreshToolbarActionsList(); }
        });
        btnUpAction.addActionListener(e -> {
            int sel = lstToolbarActions.getSelectedIndex();
            if (sel > 0) {
                String v = toolbarActionIds.remove(sel);
                toolbarActionIds.add(sel - 1, v);
                refreshToolbarActionsList();
                lstToolbarActions.setSelectedIndex(sel - 1);
            }
        });
        btnDownAction.addActionListener(e -> {
            int sel = lstToolbarActions.getSelectedIndex();
            if (sel >= 0 && sel < toolbarActionIds.size() - 1) {
                String v = toolbarActionIds.remove(sel);
                toolbarActionIds.add(sel + 1, v);
                refreshToolbarActionsList();
                lstToolbarActions.setSelectedIndex(sel + 1);
            }
        });

        JPanel actionBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        for (JButton b : new JButton[]{btnAddAction, btnRemoveAction, btnUpAction, btnDownAction})
            actionBtns.add(b);

        JPanel picker = new JPanel(new BorderLayout(0, 4));
        picker.add(cmbToolbarActionPicker, BorderLayout.NORTH);
        picker.add(toolbarActionsScroll,   BorderLayout.CENTER);
        picker.add(actionBtns,             BorderLayout.SOUTH);

        JPanel box = new JPanel(new BorderLayout());
        box.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Actions affichées (dans l'ordre)")));
        box.add(picker, BorderLayout.CENTER);

        JPanel outer = new JPanel(new BorderLayout(0, 8));
        outer.setBorder(new EmptyBorder(10, 10, 10, 10));
        outer.add(lblDesc, BorderLayout.NORTH);
        outer.add(box,     BorderLayout.CENTER);
        return outer;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildMbOAuthPanel() {
        lblMbAccount     = new JLabel();
        cmbMbOAuthMode   = new JComboBox<>(new String[]{
            I18n.t("Automatique (recommandé)"), I18n.t("Automatique via navigateur local"), I18n.t("Manuel (copier-coller le code)")});
        cmbMbOAuthMode.setToolTipText(I18n.t(
            "Façon dont OpenTagger récupère l'autorisation après que vous l'ayez donnée dans le "
            + "navigateur, lors du clic sur \"Se connecter à MusicBrainz\" ci-dessous. \"Automatique\" "
            + "fonctionne dans la grande majorité des cas — ne changer que si la connexion échoue "
            + "systématiquement ; \"Manuel\" demande de copier un code affiché par le navigateur."));
        tfMbCollectionId = tf();
        tfMbCollectionId.setToolTipText(I18n.t("MBID de votre collection MusicBrainz (visible dans l'URL de la "
                + "page de la collection sur musicbrainz.org) — les releases taguées y seront ajoutées "
                + "automatiquement. Laisser vide pour désactiver. La collection doit déjà exister."));

        // Client ID/Secret n'est plus affiché ici : c'est l'identité de l'APPLICATION (embarquée,
        // partagée par tous les utilisateurs — voir settings.properties), pas une information
        // propre à chaque utilisateur. Avant ce changement, ces deux champs de texte étaient
        // visibles et modifiables dans ce panneau alors que 99% des utilisateurs n'ont ni besoin
        // ni intérêt à les voir/modifier ; seule l'autorisation personnelle (bouton ci-dessous)
        // les concerne.
        JButton btnAction = new JButton("🔑  " + I18n.t("Se connecter à MusicBrainz"));
        btnAction.setToolTipText(I18n.t("Ouvrir le navigateur pour autoriser OpenTagger à accéder à votre compte"));
        btnAction.putClientProperty("FlatLaf.style", "background: #1a6030");
        JButton btnLogout = new JButton(I18n.t("Déconnexion"));
        refreshMbStatus(lblMbAccount, btnAction, btnLogout);

        btnAction.addActionListener(e -> {
            if (Config.get().mbClientId().isBlank() || Config.get().mbClientSecret().isBlank()) {
                JOptionPane.showMessageDialog(SettingsDialog.this,
                    I18n.t("Client ID/Secret MusicBrainz manquants dans la configuration de l'application.\n"
                    + "Ce n'est pas quelque chose à saisir manuellement — contactez le développeur "
                    + "ou réinstallez OpenTagger."),
                    I18n.t("Configuration incomplète"), JOptionPane.ERROR_MESSAGE);
                return;
            }
            btnAction.setEnabled(false);
            btnAction.setText(I18n.t("Ouverture du navigateur…"));
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception { return new MusicBrainzOAuth().authorize(); }
                @Override protected void done() {
                    try { get(); } catch (Exception ex) {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                            "<html>" + ex.getMessage().replace("\n","<br>") + "</html>",
                            I18n.t("Erreur OAuth"), JOptionPane.ERROR_MESSAGE);
                    }
                    refreshMbStatus(lblMbAccount, btnAction, btnLogout);
                }
            }.execute();
        });
        btnLogout.addActionListener(e -> { MusicBrainzOAuth.logout(); refreshMbStatus(lblMbAccount, btnAction, btnLogout); });

        // Si token présent mais username manquant/inconnu → re-fetch automatique
        if (Config.get().mbConnected()) {
            String u = Config.get().mbUsername();
            if (u.isBlank() || u.equals("(inconnu)")) {
                lblMbAccount.setText(I18n.t("Récupération du compte…"));
                new SwingWorker<String, Void>() {
                    @Override protected String doInBackground() throws Exception {
                        return new MusicBrainzOAuth().fetchUsername(Config.get().mbToken());
                    }
                    @Override protected void done() {
                        try {
                            String name = get();
                            Config.get().set("mb.oauth.username", name);
                            refreshMbStatus(lblMbAccount, btnAction, btnLogout);
                        } catch (Exception ex) {
                            lblMbAccount.setText(I18n.t("(token invalide — reconnectez-vous)"));
                            lblMbAccount.putClientProperty("FlatLaf.style", "foreground: #f44336");
                        }
                    }
                }.execute();
            }
        }

        JLabel hint = new JLabel(I18n.t(
            "<html><i><b>scheme</b> : URL handler système (défaut, Linux/Mac).<br>" +
            "<b>localhost</b> : serveur local port 8484, redirect_uri = http://localhost:8484.<br>" +
            "<b>oob</b> : code affiché dans le navigateur, copier-coller ici.</i></html>"));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        hint.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Compte MusicBrainz (contribution OAuth2)")));
        String[] labels = {"Compte connecté :", "Mode OAuth :", "Collection MB (optionnel) :"};
        JComponent[] fields = {lblMbAccount, cmbMbOAuthMode, tfMbCollectionId};
        for (int i = 0; i < labels.length; i++) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(4,10,4,8);
            inner.add(new JLabel(I18n.t(labels[i])), lc);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1; fc.insets = new Insets(4,0,4,10);
            inner.add(fields[i], fc);
        }
        GridBagConstraints bc = new GridBagConstraints();
        bc.gridx = 1; bc.gridy = labels.length; bc.anchor = GridBagConstraints.WEST; bc.insets = new Insets(6,0,4,10);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.add(btnAction); btnRow.add(btnLogout);
        inner.add(btnRow, bc);

        // `hint` était en BorderLayout.CENTER, qui s'étire pour occuper TOUT l'espace restant du
        // panneau — sur cet onglet, ça laissait un grand vide entre le formulaire et la légende
        // d'aide (visible à l'écran : la légende flottait au milieu d'une zone vide au lieu de
        // suivre directement le formulaire). `inner` et `hint` regroupés dans une même boîte
        // verticale collée en NORTH : l'espace restant va sous les deux, pas entre eux.
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        inner.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(inner);
        top.add(hint);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(new EmptyBorder(12, 12, 12, 12));
        wrap.add(top, BorderLayout.NORTH);
        return wrap;
    }

    private void refreshMbStatus(JLabel lbl, JButton btnAction, JButton btnLogout) {
        boolean connected = Config.get().mbConnected();
        String  username  = Config.get().mbUsername();
        lbl.setText(connected ? username : I18n.t("(non connecté)"));
        lbl.putClientProperty("FlatLaf.style", connected ? "foreground: #1db954" : "foreground: #888888");
        btnAction.setEnabled(true);
        // Après un échec OAuth (identifiants refusés, délai dépassé...), le texte restait bloqué
        // sur "Ouverture du navigateur…" indéfiniment — parfaitement cliquable à nouveau (ligne du
        // dessus) mais visuellement figé sur l'état "en cours", jusqu'à fermer/rouvrir les
        // Préférences. Restauré ici à chaque passage en état non-connecté.
        if (!connected) btnAction.setText("🔑  " + I18n.t("Se connecter à MusicBrainz"));
        btnAction.setVisible(!connected);
        btnLogout.setVisible(connected);
    }

    // ── Chargement / sauvegarde ──────────────────────────────────────────────

    private void load() {
        Config cfg = Config.get();
        cmbLanguage.setSelectedIndex("en".equals(cfg.uiLanguage()) ? 1 : 0);
        chkUpdateCheck.setSelected(cfg.updateCheckEnabled());
        chkCloseMinimizes.setSelected(cfg.closeMinimizesToTaskbar());
        chkScriptsEnabled.setSelected(cfg.scriptsEnabled());
        tfMbUserAgent      .setText(cfg.str("musicbrainz.user_agent",   "OpenTagger/1.0 (bain.paul24@gmail.com)"));
        tfAcoustIdKey      .setText(cfg.str("acoustid.api_key",         ""));
        tfAcoustIdUserToken.setText(cfg.str("acoustid.user_token",      ""));
        tfDiscogsKey       .setText(cfg.str("discogs.consumer_key",     ""));
        tfDiscogsSecret .setText(cfg.str("discogs.consumer_secret",    ""));
        tfLastFmKey     .setText(cfg.str("lastfm.api_key",             ""));
        tfFanArtKey     .setText(cfg.str("fanart.api_key",             ""));

        spMinScore      .setValue(cfg.num("autocorrector.min_score",   85));
        spTrackMatchThreshold.setValue((int) Math.round(cfg.trackMatchingThreshold() * 100));
        chkOnlyOfficial .setSelected(cfg.bool("musicbrainz.only_official", true));
        chkUseAcoustId  .setSelected(cfg.useAcoustId());
        spResultsLimit  .setValue(cfg.num("musicbrainz.results_limit", 5));
        spCacheDays     .setValue(cfg.num("musicbrainz.cache_days",    30));

        chkUseLibraryRoot    .setSelected(cfg.useLibraryRootEnabled());
        tfLibraryRoot        .setText(cfg.str("rename.library_root",  ""));
        tfPodcastLibraryRoot .setText(cfg.str("podcast.library_root", ""));
        cmbDefaultMask    .setSelectedIndex(Math.min(cfg.num("rename.default_mask", 3),
                                                     cmbDefaultMask.getItemCount() - 1));
        chkAutoRename     .setSelected(cfg.bool("rename.auto_enabled",       false));
        chkDeleteEmptyDirs.setSelected(cfg.bool("rename.delete_empty_dirs",  true));
        chkFollowLog      .setSelected(cfg.bool("rename.follow_log",         true));
        cmbDefaultMask    .setEnabled(chkAutoRename.isSelected());
        chkMoveSkipped    .setSelected(cfg.bool("skipped.move_enabled",      false));
        tfSkippedFolder   .setText(cfg.str("skipped.move_folder",           ""));
        chkMoveDurationMismatch.setSelected(cfg.bool("duration_mismatch.move_enabled", false));
        tfDurationMismatchFolder.setText(cfg.str("duration_mismatch.move_folder",          ""));
        chkVideoAutoRecover.setSelected(cfg.videoAutoRecover());
        chkSkipSongRecOnConfidentMb.setSelected(cfg.skipSongRecOnConfidentMb());
        spnSkipSongRecMinScore.setValue(cfg.skipSongRecMinScore());

        startupFolderModel.clear();
        for (String f : cfg.startupFolders())
            if (!f.isBlank()) startupFolderModel.addElement(f);

        tfFfmpegPath    .setText(cfg.str("audio.ffmpeg_path",          "ffmpeg"));
        tfEssentiaPath  .setText(cfg.str("audio.essentia_path",        "essentia_streaming_extractor_music"));
        tfFpcalcPath    .setText(cfg.str("audio.fpcalc_path",          ""));
        chkLyricsEnabled.setSelected(cfg.bool("lyrics.enabled",        true));
        chkSaveLrc      .setSelected(cfg.saveLrcFile());
        tfRapidApiKey.setText(cfg.str("rapidapi.key",    ""));
        tfAudDToken  .setText(cfg.str("audd.api_token", ""));
        tfListenBrainzUsername .setText(cfg.listenbrainzUsername());
        spListenBrainzMaxTracks.setValue(cfg.listenbrainzMaxTracks());

        chkLastfmEnabled    .setSelected(cfg.bool("lastfm.use_tags",       true));
        chkLastfmArtistUrls .setSelected(cfg.bool("lastfm.fetch_artist_urls", true));
        chkReplayGainEnabled.setSelected(cfg.replayGainEnabled());
        chkCamelotKey.setSelected(cfg.writeCamelotKey());

        String genreSrc = cfg.str("discogs.genre_source", "style_then_genre");
        cmbDiscogsGenreSource.setSelectedIndex(
            "genre_then_style".equals(genreSrc) ? 1 : "genre_only".equals(genreSrc) ? 2 : 0);
        spDiscogsMaxGenres.setValue(cfg.num("discogs.max_genres",   3));
        spLastfmMaxGenres .setValue(cfg.num("lastfm.max_genres",    3));

        // ─ Releases préférées ─
        lstCountriesModel.clear();
        String savedCountries = cfg.str("releases.preferred_countries", "");
        if (!savedCountries.isBlank())
            for (String code : savedCountries.split(","))
                if (!code.isBlank()) lstCountriesModel.addElement(countryLabel(code.trim()));
        tfPreferredFormats  .setText(cfg.str("releases.preferred_formats",   ""));
        tfVaName            .setText(cfg.vaName());
        chkStandardizeArtists.setSelected(cfg.standardizeArtists());
        chkTranslateArtists  .setSelected(cfg.translateArtists());

        // ─ Filtres types de release ─
        String allowedPrimStr = cfg.str("releases.allowed_primary_types", "");
        java.util.Set<String> allowedPrimSet = new java.util.HashSet<>();
        if (!allowedPrimStr.isBlank())
            for (String t : allowedPrimStr.split(",")) allowedPrimSet.add(t.trim());
        for (int i = 0; i < PRIMARY_TYPES.length; i++)
            chkPrimaryTypes[i].setSelected(allowedPrimSet.isEmpty() || allowedPrimSet.contains(PRIMARY_TYPES[i]));
        String excludedSecStr = cfg.str("releases.excluded_secondary_types", "");
        java.util.Set<String> excludedSecSet = new java.util.HashSet<>();
        if (!excludedSecStr.isBlank())
            for (String t : excludedSecStr.split(",")) excludedSecSet.add(t.trim());
        for (int i = 0; i < SECONDARY_TYPES.length; i++)
            chkExcludedSecondary[i].setSelected(excludedSecSet.contains(SECONDARY_TYPES[i]));
        lstTranslateLocalesModel.clear();
        for (String code : cfg.translateLocales())
            if (!code.isBlank()) lstTranslateLocalesModel.addElement(localeLabel(code.trim()));
        lstCompilationSeriesModel.clear();
        for (String name : cfg.compilationSeriesNames())
            if (!name.isBlank()) lstCompilationSeriesModel.addElement(name.trim());
        chkPrioritizeIncomplete   .setSelected(cfg.prioritizeIncomplete());

        // ─ Transcodage ─
        chkTranscodeAuto.setSelected(cfg.transcodeAutoBeforeTag());
        String[] fmtIds = {"mp3", "flac", "aac", "ogg", "opus"};
        String savedFmt = cfg.transcodeFormat();
        for (int i = 0; i < fmtIds.length; i++)
            if (fmtIds[i].equalsIgnoreCase(savedFmt)) { cmbTranscodeFormat.setSelectedIndex(i); break; }
        spTranscodeBitrate.setValue(cfg.transcodeBitrate());
        chkTranscodeDeleteSource.setSelected(cfg.transcodeDeleteSource());
        chkMoveUnreadable.setSelected(cfg.bool("transcode.move_unreadable_enabled", false));
        boolean hasBitrate = cmbTranscodeFormat.getSelectedIndex() != 1;
        lblTranscodeBitrate.setEnabled(hasBitrate);
        spTranscodeBitrate.setEnabled(hasBitrate);

        // ─ MB Genres ─
        chkMbUseGenres   .setSelected(cfg.mbUseGenres());
        spMbMinGenreUsage.setValue(cfg.mbMinGenreUsage());
        spMbMaxGenres    .setValue(cfg.mbMaxGenres());
        taGenresFilter   .setText(cfg.mbGenresFilter());

        // ─ Tags (onglet Tags) ─
        tfPreservedTags      .setText(cfg.preservedTags());
        chkCoverSaveToFile   .setSelected(cfg.coverSaveToFile());
        chkCoverOverwriteFile.setSelected(cfg.coverOverwriteFile());
        tfCoverFilename      .setText(cfg.coverFilename());

        // ─ Fournisseurs de pochette : ordre + activation ─
        coverProviderOrder.clear();
        for (String id : cfg.coverProviderOrder())
            if (!id.isBlank() && !coverProviderOrder.contains(id)) coverProviderOrder.add(id);
        for (String id : COVER_PROVIDER_IDS) // rattrape tout id connu absent d'une config existante
            if (!coverProviderOrder.contains(id)) coverProviderOrder.add(id);
        coverProviderEnabled.clear();
        coverProviderEnabled.put("caa_release",       cfg.caaReleaseEnabled());
        coverProviderEnabled.put("caa_release_group", cfg.caaReleaseGroupEnabled());
        coverProviderEnabled.put("shazam", cfg.shazamCoverEnabled());
        coverProviderEnabled.put("local",  cfg.coverSearchLocal());
        coverProviderEnabled.put("fanart", cfg.fanartEnabled());
        coverProviderEnabled.put("deezer", cfg.deezerEnabled());
        refreshCoverProvidersList();

        chkCorrectPunctuation.setSelected(cfg.correctPunctuation());
        chkRemoveId3v1       .setSelected(cfg.removeId3v1());
        String id3v = cfg.id3v2Version();
        cmbId3Version.setSelectedIndex("2.3".equals(id3v) ? 1 : "2.4".equals(id3v) ? 2 : 0);
        chkPreserveTimestamps     .setSelected(cfg.preserveTimestamps());
        chkClearExistingTags      .setSelected(cfg.clearExistingTags());
        chkPreserveImages         .setSelected(cfg.preserveImages());
        chkTrustExistingMbTags    .setSelected(cfg.trustExistingMbTags());
        chkPreserveCompilation    .setSelected(cfg.preserveCompilationAlbum());
        chkSaveAcoustidFingerprints.setSelected(cfg.saveAcoustidFingerprints());
        chkIgnoreExistingFingerprints.setSelected(cfg.ignoreExistingFingerprints());
        spFpcalcThreads           .setValue(cfg.fpcalcThreads());
        spBatchThreads            .setValue(cfg.num("batch.threads", 6));

        String mode = cfg.str("mb.oauth.mode", "scheme");
        cmbMbOAuthMode.setSelectedIndex(
            "localhost".equals(mode) ? 1 : "oob".equals(mode) ? 2 : 0);
        tfMbCollectionId.setText(cfg.mbCollectionId());

        scriptDefs = new java.util.ArrayList<>(TaggerScript.loadScripts());
        currentScriptIndex = -1;
        refreshScriptsList();
        postTagCommands = new java.util.ArrayList<>(PostTagCommands.load());
        lstPostTagCommandsModel.clear();
        for (String c : postTagCommands) lstPostTagCommandsModel.addElement(c);
        if (!scriptDefs.isEmpty()) {
            lstScripts.setSelectedIndex(0);
        } else {
            taTaggerScript.setText("");
            taTaggerScript.setEnabled(false);
        }

        // ─ Barre d'outils : actions secondaires affichées ─
        toolbarActionIds.clear();
        for (String id : cfg.toolbarActions()) {
            String trimmed = id.trim();
            boolean known = false;
            for (String[] info : MainFrame.TOOLBAR_ACTION_INFOS) if (info[0].equals(trimmed)) { known = true; break; }
            if (known && !toolbarActionIds.contains(trimmed)) toolbarActionIds.add(trimmed);
        }
        refreshToolbarActionsList();
    }

    private void save() {
        // Partir du fichier utilisateur existant pour préserver les clés non affichées dans le formulaire
        // (discogs.genre_source, lastfm.max_genres, mb.oauth.mode configurées manuellement, etc.)
        Properties p = new Properties();
        Path userFile = Paths.get(SETTINGS_FILE);
        if (Files.exists(userFile)) {
            try (java.io.InputStream in = Files.newInputStream(userFile)) { p.load(in); }
            catch (IOException ignored) {}
        }


        String newLanguage = cmbLanguage.getSelectedIndex() == 1 ? "en" : "fr";
        p.setProperty("ui.language", newLanguage);
        p.setProperty("update.check_enabled", String.valueOf(chkUpdateCheck.isSelected()));
        p.setProperty("ui.close_minimizes",   String.valueOf(chkCloseMinimizes.isSelected()));
        // hooks.post_tag_command n'est plus jamais écrite ici — remplacée par PostTagCommands
        // (liste), voir save() plus bas et PostTagCommands.load() pour la migration.
        p.setProperty("update.last_check_ms", String.valueOf(Config.get().lastUpdateCheckMs()));

        p.setProperty("musicbrainz.user_agent",        tfMbUserAgent.getText().trim());
        p.setProperty("acoustid.api_key",               tfAcoustIdKey.getText().trim());
        p.setProperty("acoustid.user_token",            tfAcoustIdUserToken.getText().trim());
        p.setProperty("discogs.consumer_key",           tfDiscogsKey.getText().trim());
        p.setProperty("discogs.consumer_secret",        tfDiscogsSecret.getText().trim());
        p.setProperty("lastfm.api_key",                 tfLastFmKey.getText().trim());
        p.setProperty("fanart.api_key",                 tfFanArtKey.getText().trim());

        p.setProperty("autocorrector.min_score",       String.valueOf(spMinScore.getValue()));
        p.setProperty("match.track_matching_threshold",
            String.valueOf(((Integer) spTrackMatchThreshold.getValue()) / 100.0));
        p.setProperty("musicbrainz.only_official",     String.valueOf(chkOnlyOfficial.isSelected()));
        p.setProperty("acoustid.use_acoustid",         String.valueOf(chkUseAcoustId.isSelected()));
        p.setProperty("musicbrainz.results_limit",     String.valueOf(spResultsLimit.getValue()));
        p.setProperty("musicbrainz.cache_days",        String.valueOf(spCacheDays.getValue()));

        p.setProperty("rename.use_library_root", String.valueOf(chkUseLibraryRoot.isSelected()));
        p.setProperty("rename.library_root",   tfLibraryRoot.getText().trim());
        p.setProperty("podcast.library_root",  tfPodcastLibraryRoot.getText().trim());
        p.setProperty("rename.default_mask",           String.valueOf(cmbDefaultMask.getSelectedIndex()));
        p.setProperty("rename.auto_enabled",           String.valueOf(chkAutoRename.isSelected()));
        p.setProperty("rename.delete_empty_dirs",      String.valueOf(chkDeleteEmptyDirs.isSelected()));
        p.setProperty("rename.follow_log",             String.valueOf(chkFollowLog.isSelected()));
        p.setProperty("skipped.move_enabled",          String.valueOf(chkMoveSkipped.isSelected()));
        p.setProperty("skipped.move_folder",           tfSkippedFolder.getText().trim());
        p.setProperty("duration_mismatch.move_enabled", String.valueOf(chkMoveDurationMismatch.isSelected()));
        p.setProperty("duration_mismatch.move_folder",  tfDurationMismatchFolder.getText().trim());
        p.setProperty("video.auto_recover",            String.valueOf(chkVideoAutoRecover.isSelected()));
        p.setProperty("tagging.skip_songrec_on_confident_mb", String.valueOf(chkSkipSongRecOnConfidentMb.isSelected()));
        p.setProperty("tagging.skip_songrec_min_score",       String.valueOf(spnSkipSongRecMinScore.getValue()));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < startupFolderModel.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(startupFolderModel.get(i));
        }
        p.setProperty("startup.folders", sb.toString());

        p.setProperty("audio.ffmpeg_path",             tfFfmpegPath.getText().trim());
        p.setProperty("audio.essentia_path",           tfEssentiaPath.getText().trim());
        p.setProperty("audio.fpcalc_path",             tfFpcalcPath.getText().trim());
        p.setProperty("lyrics.enabled",                String.valueOf(chkLyricsEnabled.isSelected()));
        p.setProperty("lyrics.save_lrc",               String.valueOf(chkSaveLrc.isSelected()));
        p.setProperty("rapidapi.key",   tfRapidApiKey.getText().trim());
        p.setProperty("audd.api_token", tfAudDToken.getText().trim());
        p.setProperty("listenbrainz.username",   tfListenBrainzUsername.getText().trim());
        p.setProperty("listenbrainz.max_tracks", String.valueOf(spListenBrainzMaxTracks.getValue()));

        p.setProperty("lastfm.use_tags",        String.valueOf(chkLastfmEnabled.isSelected()));
        p.setProperty("lastfm.fetch_artist_urls", String.valueOf(chkLastfmArtistUrls.isSelected()));
        p.setProperty("replaygain.enabled",     String.valueOf(chkReplayGainEnabled.isSelected()));
        p.setProperty("audio.camelot_key",      String.valueOf(chkCamelotKey.isSelected()));

        String[] genreSources = {"style_then_genre", "genre_then_style", "genre_only"};
        p.setProperty("discogs.genre_source", genreSources[cmbDiscogsGenreSource.getSelectedIndex()]);
        p.setProperty("discogs.max_genres",   String.valueOf(spDiscogsMaxGenres.getValue()));
        p.setProperty("lastfm.max_genres",    String.valueOf(spLastfmMaxGenres.getValue()));

        // ─ Releases préférées ─
        StringBuilder sbCountries = new StringBuilder();
        for (int i = 0; i < lstCountriesModel.size(); i++) {
            if (i > 0) sbCountries.append(',');
            sbCountries.append(codeFromLabel(lstCountriesModel.get(i)));
        }
        p.setProperty("releases.preferred_countries", sbCountries.toString());
        p.setProperty("releases.preferred_formats",   tfPreferredFormats.getText().trim());

        // Filtres types de release
        java.util.List<String> selPrimary = new java.util.ArrayList<>();
        for (int i = 0; i < PRIMARY_TYPES.length; i++)
            if (chkPrimaryTypes[i].isSelected()) selPrimary.add(PRIMARY_TYPES[i]);
        p.setProperty("releases.allowed_primary_types",
            selPrimary.size() == PRIMARY_TYPES.length ? "" : String.join(",", selPrimary));
        java.util.List<String> selExcluded = new java.util.ArrayList<>();
        for (int i = 0; i < SECONDARY_TYPES.length; i++)
            if (chkExcludedSecondary[i].isSelected()) selExcluded.add(SECONDARY_TYPES[i]);
        p.setProperty("releases.excluded_secondary_types", String.join(",", selExcluded));

        p.setProperty("metadata.va_name",             tfVaName.getText().trim().isEmpty()
                                                      ? "Various Artists" : tfVaName.getText().trim());
        p.setProperty("metadata.standardize_artists",  String.valueOf(chkStandardizeArtists.isSelected()));
        p.setProperty("metadata.translate_artists",    String.valueOf(chkTranslateArtists.isSelected()));
        {
            StringBuilder sbLocales = new StringBuilder();
            for (int i = 0; i < lstTranslateLocalesModel.size(); i++) {
                if (i > 0) sbLocales.append(',');
                sbLocales.append(codeFromLabel(lstTranslateLocalesModel.get(i)));
            }
            // Liste vide (aucune langue ajoutée) → "en" : comportement identique à avant l'ajout
            // du multi-locale plutôt qu'une chaîne vide qui désactiverait silencieusement le repli.
            p.setProperty("metadata.translate_locale", sbLocales.length() > 0 ? sbLocales.toString() : "en");
        }
        {
            StringBuilder sbSeries = new StringBuilder();
            for (int i = 0; i < lstCompilationSeriesModel.size(); i++) {
                if (i > 0) sbSeries.append(',');
                sbSeries.append(lstCompilationSeriesModel.get(i));
            }
            p.setProperty("compilation.series_names", sbSeries.toString());
        }
        p.setProperty("batch.prioritize_incomplete",   String.valueOf(chkPrioritizeIncomplete.isSelected()));

        // ─ MB Genres ─
        p.setProperty("mb.use_genres",       String.valueOf(chkMbUseGenres.isSelected()));
        p.setProperty("mb.min_genre_usage",  String.valueOf(spMbMinGenreUsage.getValue()));
        p.setProperty("mb.max_genres",       String.valueOf(spMbMaxGenres.getValue()));
        p.setProperty("mb.genres_filter",    taGenresFilter.getText());

        // ─ Onglet Tags ─
        p.setProperty("tags.preserved_tags",       tfPreservedTags.getText().trim());

        // ─ Fournisseurs de pochette : ordre + activation ─
        p.setProperty("cover.provider_order",             String.join(",", coverProviderOrder));
        p.setProperty("cover.caa_release_enabled",        String.valueOf(coverProviderEnabled.getOrDefault("caa_release", true)));
        p.setProperty("cover.caa_release_group_enabled",  String.valueOf(coverProviderEnabled.getOrDefault("caa_release_group", true)));
        p.setProperty("shazam.download_cover",            String.valueOf(coverProviderEnabled.getOrDefault("shazam", true)));
        p.setProperty("cover.search_local",               String.valueOf(coverProviderEnabled.getOrDefault("local", true)));
        p.setProperty("fanart.download_cover",            String.valueOf(coverProviderEnabled.getOrDefault("fanart", true)));
        p.setProperty("deezer.enabled",                   String.valueOf(coverProviderEnabled.getOrDefault("deezer", true)));

        p.setProperty("cover.save_to_file",        String.valueOf(chkCoverSaveToFile.isSelected()));
        p.setProperty("cover.overwrite_file",      String.valueOf(chkCoverOverwriteFile.isSelected()));
        p.setProperty("cover.filename",            tfCoverFilename.getText().trim().isEmpty() ? "cover" : tfCoverFilename.getText().trim());
        p.setProperty("tags.correct_punctuation",  String.valueOf(chkCorrectPunctuation.isSelected()));
        p.setProperty("tags.remove_id3v1",         String.valueOf(chkRemoveId3v1.isSelected()));
        String[] id3Versions = {"keep", "2.3", "2.4"};
        p.setProperty("tags.id3v2_version",            id3Versions[cmbId3Version.getSelectedIndex()]);
        p.setProperty("tags.preserve_timestamps",      String.valueOf(chkPreserveTimestamps.isSelected()));
        p.setProperty("tags.clear_existing_tags",      String.valueOf(chkClearExistingTags.isSelected()));
        p.setProperty("tags.preserve_images",          String.valueOf(chkPreserveImages.isSelected()));
        p.setProperty("tags.trust_existing_mb_tags",   String.valueOf(chkTrustExistingMbTags.isSelected()));
        p.setProperty("tags.preserve_compilation",     String.valueOf(chkPreserveCompilation.isSelected()));
        p.setProperty("acoustid.save_fingerprints",    String.valueOf(chkSaveAcoustidFingerprints.isSelected()));
        p.setProperty("acoustid.ignore_existing",      String.valueOf(chkIgnoreExistingFingerprints.isSelected()));
        p.setProperty("acoustid.fpcalc_threads",       String.valueOf(spFpcalcThreads.getValue()));
        p.setProperty("batch.threads",                 String.valueOf(spBatchThreads.getValue()));

        p.setProperty("scripts.enabled", String.valueOf(chkScriptsEnabled.isSelected()));
        flushCurrentScriptEdits();
        TaggerScript.saveScripts(scriptDefs);
        PostTagCommands.save(postTagCommands);

        p.setProperty("toolbar.actions", String.join(",", toolbarActionIds));
        // Marque la migration "refreshFolders" (voir Config.toolbarActions()) comme faite : à
        // partir d'ici, la liste ci-dessus fait foi telle quelle, même si l'utilisateur vient d'en
        // retirer "Rafraîchir" — sans ce flag, l'appel suivant à toolbarActions() (juste après, via
        // onPreferencesSaved()) le réinjectait aussitôt et le bouton ne pouvait jamais disparaître.
        p.setProperty("toolbar.refresh_migrated", "true");

        // ─ Transcodage ─
        String[] fmtIds2 = {"mp3", "flac", "aac", "ogg", "opus"};
        p.setProperty("transcode.auto_before_tag", String.valueOf(chkTranscodeAuto.isSelected()));
        p.setProperty("transcode.format",          fmtIds2[cmbTranscodeFormat.getSelectedIndex()]);
        p.setProperty("transcode.bitrate_kbps",    String.valueOf(spTranscodeBitrate.getValue()));
        p.setProperty("transcode.delete_source",   String.valueOf(chkTranscodeDeleteSource.isSelected()));
        p.setProperty("transcode.move_unreadable_enabled", String.valueOf(chkMoveUnreadable.isSelected()));

        // Conserver le client_id/secret (identité d'application embarquée, plus modifiable
        // depuis ce dialogue — voir buildMbOAuthPanel), le token, refresh_token et username existants
        p.setProperty("mb.oauth.client_id",           Config.get().mbClientId());
        p.setProperty("mb.oauth.client_secret",       Config.get().mbClientSecret());
        p.setProperty("mb.oauth.token",               Config.get().mbToken());
        p.setProperty("mb.oauth.refresh_token",       Config.get().str("mb.oauth.refresh_token", ""));
        p.setProperty("mb.oauth.username",            Config.get().mbUsername());
        String[] oauthModes = {"scheme", "localhost", "oob"};
        p.setProperty("mb.oauth.mode", oauthModes[cmbMbOAuthMode.getSelectedIndex()]);
        p.setProperty("mb.oauth.collection_id",       tfMbCollectionId.getText().trim());

        // Mémoriser les dossiers déjà connus avant la sauvegarde
        java.util.Set<String> alreadyKnown = new java.util.HashSet<>(
                java.util.Arrays.asList(Config.get().startupFolders()));

        try {
            Path dir = Paths.get(com.opentagger.Config.configDir());
            if (!Files.exists(dir)) Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(Paths.get(SETTINGS_FILE))) {
                p.store(w, "OpenTagger user settings");
            }
            Config.get().reload();

            // Charger immédiatement les dossiers nouvellement ajoutés
            if (onLoadFolders != null) {
                java.io.File[] newDirs = java.util.stream.IntStream.range(0, startupFolderModel.size())
                    .mapToObj(startupFolderModel::get)
                    .filter(path -> !alreadyKnown.contains(path))
                    .map(java.io.File::new)
                    .filter(java.io.File::isDirectory)
                    .toArray(java.io.File[]::new);
                if (newDirs.length > 0) onLoadFolders.accept(newDirs);
            }

            // Laisse MainFrame resynchroniser en direct tout ce qu'il ne relit pas normalement
            // après sa construction (barre d'outils secondaire, masque de renommage par défaut...)
            // — voir le commentaire sur onPreferencesSaved. Seul le changement de langue garde un
            // popup dédié : ça touche des textes déjà rendus dans toute l'appli, pas un champ
            // unique qu'on peut simplement relire.
            if (onPreferencesSaved != null) onPreferencesSaved.run();

            if (!newLanguage.equals(I18n.lang())) {
                JOptionPane.showMessageDialog(this,
                        "Redémarrage nécessaire pour appliquer le changement de langue.\n"
                        + "Restart required to apply the language change.",
                        "Langue / Language", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Erreur sauvegarde : %s", ex.getMessage()),
                    I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Clés API/identifiants personnels — jamais effacées par {@link #resetOptionsToDefault()},
     *  contrairement à toutes les autres options du dialogue. */
    private static final String[] PRESERVED_ON_RESET = {
        "musicbrainz.user_agent", "app.contact",
        "acoustid.api_key", "acoustid.user_token",
        "discogs.consumer_key", "discogs.consumer_secret",
        "lastfm.api_key", "fanart.api_key", "rapidapi.key", "audd.api_token",
        "listenbrainz.username",
        "mb.oauth.client_id", "mb.oauth.client_secret", "mb.oauth.token",
        "mb.oauth.refresh_token", "mb.oauth.username", "mb.oauth.collection_id",
    };

    /** Réinitialise TOUTES les options (matching, tags, renommage, audio, script, barre d'outils,
     *  dossiers de démarrage, etc.) aux valeurs par défaut — en ne réécrivant que les clés listées
     *  dans {@link #PRESERVED_ON_RESET}, le fichier utilisateur perd toute autre clé et chaque
     *  réglage retombe sur le même défaut que {@code Config}/le jar bundlé utilisent déjà pour une
     *  toute première installation (mécanisme existant, pas dupliqué ici). Demandé le 2026-07-28 :
     *  l'utilisateur veut pouvoir repartir d'une config propre sans reperdre ses clés API/comptes. */
    private void resetOptionsToDefault() {
        int confirm = JOptionPane.showConfirmDialog(this,
            I18n.t("Réinitialiser TOUTES les options aux valeurs par défaut ?\n\n"
                 + "Cela inclut : dossiers de démarrage, chemin de bibliothèque/renommage, masques, "
                 + "filtres de correspondance, options de tags, transcodage, script, barre d'outils…\n\n"
                 + "Les clés API et identifiants (AcoustID, Discogs, Last.fm, FanArt.tv, AudD, "
                 + "RapidAPI, ListenBrainz, connexion MusicBrainz) sont conservés."),
            I18n.t("Réinitialiser les options"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        Properties fresh = new Properties();
        for (String key : PRESERVED_ON_RESET) {
            String v = Config.get().str(key, "");
            if (!v.isBlank()) fresh.setProperty(key, v);
        }

        try {
            Path dir = Paths.get(Config.configDir());
            if (!Files.exists(dir)) Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(Paths.get(SETTINGS_FILE))) {
                fresh.store(w, "OpenTagger user settings (options réinitialisées le "
                        + java.time.LocalDate.now() + " — clés API/identifiants conservés)");
            }
            Config.get().reload();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Erreur : %s", ex.getMessage()),
                    I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
            return;
        }

        java.awt.Window ownerWindow = getOwner();
        dispose();
        if (onPreferencesSaved != null) onPreferencesSaved.run();
        JOptionPane.showMessageDialog(ownerWindow,
                I18n.t("Options réinitialisées aux valeurs par défaut."),
                I18n.t("Réinitialiser les options"), JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Helpers UI ───────────────────────────────────────────────────────────

    private JTextField tf() { return new JTextField(28); }

    /** Enveloppe un panneau dans un JScrollPane sans bordure — les onglets défilent si besoin. */
    // ── Recherche de réglages ─────────────────────────────────────────────────

    private JPanel buildSearchBar() {
        tfSettingsSearch = new JTextField();
        tfSettingsSearch.putClientProperty("JTextField.placeholderText", I18n.t("Rechercher un réglage…"));
        tfSettingsSearch.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onSettingsSearch(); }
            @Override public void removeUpdate(DocumentEvent e) { onSettingsSearch(); }
            @Override public void changedUpdate(DocumentEvent e) { onSettingsSearch(); }
        });
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(new EmptyBorder(6, 8, 4, 8));
        bar.add(new JLabel("🔎"), BorderLayout.WEST);
        bar.add(tfSettingsSearch, BorderLayout.CENTER);
        return bar;
    }

    /** Indexe une fois (labels, cases à cocher, boutons, titres de section) sur les 10 onglets. */
    private void buildSearchIndex() {
        searchIndex.clear();
        for (int i = 0; i < tabs.getTabCount(); i++)
            indexComponent(tabs.getComponentAt(i), i);
    }

    private void indexComponent(Component c, int tabIndex) {
        if (c instanceof JLabel lbl && lbl.getText() != null && !lbl.getText().isBlank())
            searchIndex.add(new SearchTarget(tabIndex, lbl.getText(), lbl));
        if (c instanceof AbstractButton b && b.getText() != null && !b.getText().isBlank())
            searchIndex.add(new SearchTarget(tabIndex, b.getText(), b));
        if (c instanceof JComponent jc && jc.getBorder() instanceof TitledBorder tb
                && tb.getTitle() != null && !tb.getTitle().isBlank())
            searchIndex.add(new SearchTarget(tabIndex, tb.getTitle(), jc));
        if (c instanceof Container cont)
            for (Component child : cont.getComponents())
                indexComponent(child, tabIndex);
    }

    private void onSettingsSearch() {
        String q = tfSettingsSearch.getText().trim().toLowerCase();
        Color defaultBg = UIManager.getColor("TextField.background");
        if (q.isEmpty()) { tfSettingsSearch.setBackground(defaultBg); return; }

        SearchTarget match = searchIndex.stream()
                .filter(t -> t.text().toLowerCase().contains(q))
                .findFirst().orElse(null);
        if (match == null) {
            tfSettingsSearch.setBackground(new Color(90, 40, 40));
            return;
        }
        tfSettingsSearch.setBackground(defaultBg);
        tabs.setSelectedIndex(match.tabIndex());
        SwingUtilities.invokeLater(() -> {
            JComponent c = match.component();
            c.scrollRectToVisible(new Rectangle(0, 0, c.getWidth(), c.getHeight()));
            flashComponent(c);
        });
    }

    // Un seul flash actif à la fois (voir flashComponent()) — onSettingsSearch() se déclenche à
    // chaque frappe (pas de debounce) ; sans cet état partagé, taper "enreg" puis "enregi" puis
    // "enregis..." qui matchent tous le même réglage en moins de 1200ms capturait le JAUNE du
    // flash en cours comme "couleur d'origine" à restaurer, laissant le composant bloqué en jaune
    // indéfiniment une fois le minuteur du second appel écoulé.
    private JComponent flashingComponent;
    private Color      flashingOriginalBg;
    private boolean    flashingOriginalOpaque;
    private Timer      flashingTimer;

    /** Flash temporaire du fond du composant trouvé, pour le repérer visuellement dans l'onglet. */
    private void flashComponent(JComponent c) {
        if (flashingTimer != null) flashingTimer.stop();
        if (flashingComponent != null && flashingComponent != c) {
            // Un autre composant flashait encore : le restaurer immédiatement avant de basculer.
            flashingComponent.setBackground(flashingOriginalBg);
            flashingComponent.setOpaque(flashingOriginalOpaque);
            flashingComponent.repaint();
        }
        if (flashingComponent != c) {
            // Nouvelle cible : capturer sa VRAIE couleur d'origine, pas un jaune de flash en cours.
            flashingOriginalBg     = c.getBackground();
            flashingOriginalOpaque = c.isOpaque();
            flashingComponent      = c;
        }
        c.setOpaque(true);
        c.setBackground(new Color(255, 213, 79));
        flashingTimer = new Timer(1200, e -> {
            c.setBackground(flashingOriginalBg);
            c.setOpaque(flashingOriginalOpaque);
            c.repaint();
            flashingComponent = null;
            flashingTimer = null;
        });
        flashingTimer.setRepeats(false);
        flashingTimer.start();
    }

    private JScrollPane scrollWrap(JPanel panel) {
        JScrollPane sp = new JScrollPane(panel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(12);
        return sp;
    }

    private JPanel form(String[] labels, JComponent[] fields, String title) {
        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t(title)));

        for (int i = 0; i < labels.length; i++) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(4, 10, 4, 8);
            inner.add(new JLabel(I18n.t(labels[i])), lc);

            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0; fc.insets = new Insets(4, 0, 4, 10);
            inner.add(fields[i], fc);
        }

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(new EmptyBorder(12, 12, 12, 12));
        wrap.add(inner, BorderLayout.NORTH);
        return wrap;
    }
}
