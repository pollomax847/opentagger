package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.FpcalcInstaller;
import com.opentagger.MusicBrainzOAuth;

import javax.swing.*;
import javax.swing.border.*;
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
            System.getProperty("user.home") + "/.opentagger/settings.properties";

    // ── Onglet APIs ──────────────────────────────────────────────────────────
    private JTextField tfMbUserAgent, tfAcoustIdKey, tfAcoustIdUserToken;
    private JTextField tfDiscogsKey, tfDiscogsSecret;
    private JTextField tfLastFmKey;
    private JTextField tfFanArtKey;
    private JCheckBox  chkFanartEnabled;
    private JCheckBox  chkLastfmEnabled;

    // ── Onglet Matching ──────────────────────────────────────────────────────
    private JSpinner   spMinScore;
    private JCheckBox  chkOnlyOfficial;
    private JTextField tfPreferredCountry;
    private JSpinner   spResultsLimit;
    private JSpinner   spCacheDays;
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbDiscogsGenreSource;
    private JSpinner   spDiscogsMaxGenres;
    private JSpinner   spLastfmMaxGenres;

    // ── Onglet Renommage ─────────────────────────────────────────────────────
    private JSpinner   spDefaultMask;
    private JCheckBox  chkAutoRename;

    // ── Onglet Audio ─────────────────────────────────────────────────────────
    private JTextField tfFfmpegPath;
    private JTextField tfEssentiaPath;
    private JCheckBox  chkLyricsEnabled;

    // ── Onglet Audio (complémentaire) ─────────────────────────────────────────
    private JTextField tfFpcalcPath;
    private JLabel     lblFpcalcStatus;

    // ── Onglet APIs (Shazam / AudD) ──────────────────────────────────────────
    private JTextField tfRapidApiKey;
    private JTextField tfAudDToken;

    // ── Onglet Tags ───────────────────────────────────────────────────────────
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbId3Version;
    private JCheckBox  chkPreserveTimestamps;
    private JCheckBox  chkClearExistingTags;
    private JCheckBox  chkPreserveImages;
    private JCheckBox  chkSaveAcoustidFingerprints;
    private JCheckBox  chkIgnoreExistingFingerprints;
    private JSpinner   spFpcalcThreads;

    // ── Onglet Matching — releases préférées + méta ───────────────────────────
    private JTextField tfPreferredCountries;
    private JTextField tfPreferredFormats;
    private JTextField tfVaName;
    private JCheckBox  chkStandardizeArtists;

    // ── Onglet MusicBrainz OAuth ──────────────────────────────────────────────
    private JTextField tfMbClientId;
    private JTextField tfMbClientSecret;
    private JLabel     lblMbAccount;
    @SuppressWarnings("unchecked")
    private JComboBox<String> cmbMbOAuthMode;

    // ── Onglet Démarrage ──────────────────────────────────────────────────────
    private DefaultListModel<String> startupFolderModel;

    // ─────────────────────────────────────────────────────────────────────────

    private final java.util.function.Consumer<java.io.File[]> onLoadFolders;

    public SettingsDialog(Frame owner, java.util.function.Consumer<java.io.File[]> onLoadFolders) {
        super(owner, "Préférences — OpenTagger", true);
        this.onLoadFolders = onLoadFolders;
        setSize(560, 480);
        setMinimumSize(new Dimension(480, 420));
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Démarrage",    buildStartupPanel());
        tabs.addTab("APIs",         buildApiPanel());
        tabs.addTab("Matching",     buildMatchingPanel());
        tabs.addTab("Tags",         buildTagsPanel());
        tabs.addTab("Renommage",    buildRenamePanel());
        tabs.addTab("Audio",        buildAudioPanel());
        tabs.addTab("MusicBrainz",  buildMbOAuthPanel());

        JButton btnOk     = new JButton("OK");
        JButton btnCancel = new JButton("Annuler");
        JButton btnApply  = new JButton("Appliquer");
        btnOk    .addActionListener(e -> { save(); dispose(); });
        btnCancel.addActionListener(e -> dispose());
        btnApply .addActionListener(e -> save());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(btnApply); buttons.add(btnCancel); buttons.add(btnOk);
        buttons.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabs,    BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        load();
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
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(400, 200));

        JButton btnAdd = new JButton("+ Ajouter…");
        JButton btnRemove = new JButton("− Supprimer");
        btnRemove.setEnabled(false);
        list.addListSelectionListener(e -> btnRemove.setEnabled(!list.isSelectionEmpty()));

        btnAdd.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setMultiSelectionEnabled(true);
            fc.setDialogTitle("Choisir les dossiers à charger au démarrage");
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

        JLabel hint = new JLabel("<html><i>Ces dossiers seront chargés automatiquement à chaque démarrage d'OpenTagger.</i></html>");
        hint.setBorder(new EmptyBorder(8, 0, 4, 0));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel inner = new JPanel(new BorderLayout(0, 6));
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Dossiers chargés au démarrage"));
        inner.add(hint,   BorderLayout.NORTH);
        inner.add(scroll, BorderLayout.CENTER);
        inner.add(buttons, BorderLayout.SOUTH);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(new EmptyBorder(10, 10, 10, 10));
        outer.add(inner, BorderLayout.CENTER);
        return outer;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildApiPanel() {
        tfMbUserAgent      = tf();
        tfAcoustIdKey      = tf();
        tfAcoustIdUserToken= tf();
        tfDiscogsKey       = tf();
        tfDiscogsSecret    = tf();
        tfLastFmKey        = tf();
        tfFanArtKey        = tf();
        tfRapidApiKey      = tf();
        tfAudDToken        = tf();
        chkFanartEnabled   = new JCheckBox("Activer le téléchargement FanArt");
        chkLastfmEnabled   = new JCheckBox("Activer l'enrichissement Last.fm");

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Clés d'accès aux services en ligne"));

        Object[][] rows = {
            { "MusicBrainz User-Agent :",   tfMbUserAgent,        null },
            { "AcoustID API Key :",         tfAcoustIdKey,        "https://acoustid.org/api-key" },
            { "AcoustID User Token :",      tfAcoustIdUserToken,  "https://acoustid.org/api-key" },
            { "Discogs Consumer Key :",     tfDiscogsKey,         "https://www.discogs.com/settings/developers" },
            { "Discogs Consumer Secret :",  tfDiscogsSecret,      "https://www.discogs.com/settings/developers" },
            { "Last.fm API Key :",          tfLastFmKey,          "https://www.last.fm/api/account/create" },
            { "FanArt.tv API Key :",        tfFanArtKey,          "https://fanart.tv/get-an-api-key/" },
            { "RapidAPI Key (Shazam) :",    tfRapidApiKey,        "https://rapidapi.com/apidojo/api/shazam" },
            { "AudD API Token :",           tfAudDToken,          "https://dashboard.audd.io/" },
        };

        for (int i = 0; i < rows.length; i++) {
            String     label  = (String)     rows[i][0];
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
                BorderFactory.createEtchedBorder(), "Enrichissement automatique"));
        for (int i = 0; i < 2; i++) {
            JCheckBox chk = (i == 0) ? chkFanartEnabled : chkLastfmEnabled;
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = i; c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 10, 3, 8); c.gridwidth = 3;
            enrichInner.add(chk, c);
        }

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(inner,       BorderLayout.CENTER);
        p.add(enrichInner, BorderLayout.SOUTH);
        return p;
    }

    private JButton apiLinkBtn(String url) {
        JButton btn = new JButton("🔗 Obtenir");
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
                    "Ouvrez : " + url, "Lien", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        return btn;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildMatchingPanel() {
        spMinScore       = new JSpinner(new SpinnerNumberModel(85, 0, 100, 5));
        chkOnlyOfficial  = new JCheckBox("Uniquement les releases officielles");
        tfPreferredCountry = tf();
        tfPreferredCountry.setColumns(4);
        spResultsLimit   = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
        spCacheDays      = new JSpinner(new SpinnerNumberModel(30, 1, 365, 7));

        cmbDiscogsGenreSource = new JComboBox<>(new String[]{
            "Style puis Genre", "Genre puis Style", "Genre uniquement"});
        spDiscogsMaxGenres = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
        spLastfmMaxGenres  = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));

        tfPreferredCountries = tf();
        tfPreferredCountries.setToolTipText("Codes ISO séparés par virgule, ex: FR,DE,US — priorité décroissante");
        tfPreferredFormats   = tf();
        tfPreferredFormats.setToolTipText("ex: CD,Digital Media,Vinyl — priorité décroissante");
        tfVaName             = tf();
        chkStandardizeArtists = new JCheckBox("Utiliser les noms MB standardisés (ex: The Beatles vs Beatles, The)");

        JPanel matchPanel = form(new String[]{
            "Score minimum (%) :",
            "Releases officielles seulement :",
            "Pays préféré (code ISO, ex: FR) :",
            "Nb résultats MusicBrainz :",
            "Durée cache (jours) :"
        }, new JComponent[]{
            spMinScore, chkOnlyOfficial,
            tfPreferredCountry, spResultsLimit,
            spCacheDays
        }, "Critères de correspondance MusicBrainz");

        JPanel releasesPanel = form(new String[]{
            "Pays préférés (ex: FR,DE,US) :",
            "Formats préférés (ex: CD,Digital Media) :",
            "Nom Various Artists :",
            ""
        }, new JComponent[]{
            tfPreferredCountries, tfPreferredFormats,
            tfVaName, chkStandardizeArtists
        }, "Releases préférées (comme Picard)");

        JPanel genrePanel = form(new String[]{
            "Source genres Discogs :",
            "Max genres Discogs :",
            "Max genres Last.fm :"
        }, new JComponent[]{
            cmbDiscogsGenreSource, spDiscogsMaxGenres, spLastfmMaxGenres
        }, "Sources de genres (Discogs / Last.fm)");

        JPanel combined = new JPanel();
        combined.setLayout(new BoxLayout(combined, BoxLayout.Y_AXIS));
        combined.add(matchPanel);
        combined.add(releasesPanel);
        combined.add(genrePanel);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(combined, BorderLayout.NORTH);
        return wrap;
    }

    @SuppressWarnings("unchecked")
    private JPanel buildTagsPanel() {
        cmbId3Version              = new JComboBox<>(new String[]{"Garder version existante", "ID3v2.3 (compatible)", "ID3v2.4 (standard)"});
        chkPreserveTimestamps      = new JCheckBox("Préserver la date de modification du fichier");
        chkClearExistingTags       = new JCheckBox("Effacer les tags existants avant écriture (repartir de zéro)");
        chkPreserveImages          = new JCheckBox("Conserver la pochette existante si aucune nouvelle");
        chkSaveAcoustidFingerprints   = new JCheckBox("Sauvegarder l'empreinte AcoustID dans les tags");
        chkIgnoreExistingFingerprints = new JCheckBox("Forcer le re-fingerprint (même si AcoustID déjà présent)");
        spFpcalcThreads            = new JSpinner(new SpinnerNumberModel(2, 1, 8, 1));

        JLabel id3Hint = new JLabel(
            "<html><i>ID3v2.3 : recommandé pour voitures, NAS anciens, Windows Explorer.<br>" +
            "ID3v2.4 : standard actuel, supporte Unicode complet.</i></html>");
        id3Hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        id3Hint.setBorder(new EmptyBorder(0, 10, 6, 0));

        JPanel tagInner = new JPanel(new GridBagLayout());
        tagInner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Écriture des tags"));
        Object[][] rows = {
            { "Version ID3 (MP3) :", cmbId3Version },
            { null, id3Hint },
            { "", chkPreserveTimestamps },
            { "", chkClearExistingTags  },
            { "", chkPreserveImages     },
        };
        for (int i = 0; i < rows.length; i++) {
            if (rows[i][0] != null) {
                GridBagConstraints lc = new GridBagConstraints();
                lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 10, 3, 8);
                tagInner.add(new JLabel((String) rows[i][0]), lc);
            }
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL;
            fc.weightx = 1.0; fc.insets = new Insets(3, 0, 3, 10); fc.gridwidth = 2;
            tagInner.add((JComponent) rows[i][1], fc);
        }

        JPanel fpInner = new JPanel(new GridBagLayout());
        fpInner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "AcoustID / Fingerprint"));
        JComponent[][] fpRows = {
            { new JLabel(""), chkSaveAcoustidFingerprints },
            { new JLabel(""), chkIgnoreExistingFingerprints },
            { new JLabel("Threads fpcalc :"), spFpcalcThreads },
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

        JPanel combined = new JPanel();
        combined.setLayout(new BoxLayout(combined, BoxLayout.Y_AXIS));

        JPanel pw = new JPanel(new BorderLayout()); pw.setBorder(new EmptyBorder(8,8,0,8)); pw.add(tagInner, BorderLayout.CENTER);
        JPanel fw = new JPanel(new BorderLayout()); fw.setBorder(new EmptyBorder(8,8,8,8)); fw.add(fpInner,  BorderLayout.CENTER);
        combined.add(pw); combined.add(fw);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(combined, BorderLayout.NORTH);
        return wrap;
    }

    private JPanel buildRenamePanel() {
        spDefaultMask  = new JSpinner(new SpinnerNumberModel(3, 0, 35, 1));
        chkAutoRename  = new JCheckBox("Renommer automatiquement après le taguage");

        // Activer/désactiver le spinner selon la checkbox
        chkAutoRename.addActionListener(e -> spDefaultMask.setEnabled(chkAutoRename.isSelected()));

        JLabel info = new JLabel("<html><i>0 = AlbumArtist-Album/Track-Title<br>"
                + "3 = AA/Album/AA-Album-Track-Title<br>"
                + "9 = AA/Album/Disc/Track-Title<br>"
                + "34 = [Plex] AA/Album/Track-Title<br>"
                + "35 = [iTunes] AA/Album/Track-Title</i></html>");
        info.setBorder(new EmptyBorder(8, 0, 0, 0));
        info.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel p = form(
            new String[]{"Masque par défaut (0–35) :", ""},
            new JComponent[]{spDefaultMask, chkAutoRename},
            "Renommage automatique des fichiers");
        p.add(info, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildAudioPanel() {
        tfFfmpegPath     = tf();
        tfEssentiaPath   = tf();
        tfFpcalcPath     = tf();
        chkLyricsEnabled = new JCheckBox("Activer la récupération des paroles");
        lblFpcalcStatus  = new JLabel();

        refreshFpcalcStatus();

        JButton btnFpcalcDownload = new JButton("Télécharger fpcalc…");
        btnFpcalcDownload.addActionListener(e -> downloadFpcalc(btnFpcalcDownload));

        JPanel fpcalcRow = new JPanel(new BorderLayout(4, 0));
        fpcalcRow.add(tfFpcalcPath,        BorderLayout.CENTER);
        fpcalcRow.add(btnFpcalcDownload,   BorderLayout.EAST);

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Outils audio externes (optionnels)"));

        Object[][] rows = {
            { "Chemin ffmpeg :",        tfFfmpegPath,    "https://ffmpeg.org/download.html"                   },
            { "Chemin Essentia :",       tfEssentiaPath,  null                                                 },
            { "Chemin fpcalc :",         fpcalcRow,       "https://acoustid.org/chromaprint"                  },
            { "Paroles (LyricsOvh) :",  chkLyricsEnabled,"https://lyricsovh.docs.apiary.io/"                 },
        };
        for (int i = 0; i < rows.length; i++) {
            String     label = (String) rows[i][0];
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
        songrecRow.add(new JLabel("<html><i>SongRec (Shazam open-source) :</i></html>"));
        songrecRow.add(apiLinkBtn("https://github.com/marin-m/SongRec#installation"));
        GridBagConstraints src = new GridBagConstraints();
        src.gridx = 0; src.gridy = rows.length; src.gridwidth = 3;
        src.anchor = GridBagConstraints.WEST; src.insets = new Insets(4, 8, 2, 8);
        inner.add(songrecRow, src);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        p.add(inner, BorderLayout.CENTER);
        p.add(lblFpcalcStatus, BorderLayout.SOUTH);
        return p;
    }

    private void refreshFpcalcStatus() {
        String path = FpcalcInstaller.resolve();
        if (path != null) {
            lblFpcalcStatus.setText("✓ fpcalc trouvé : " + path);
            lblFpcalcStatus.putClientProperty("FlatLaf.style", "foreground: #4caf50; font: 11 $defaultFont");
        } else {
            lblFpcalcStatus.setText("✗ fpcalc non trouvé — identification AcoustID désactivée");
            lblFpcalcStatus.putClientProperty("FlatLaf.style", "foreground: #f44336; font: 11 $defaultFont");
        }
        lblFpcalcStatus.setBorder(new EmptyBorder(6, 0, 0, 0));
    }

    private void downloadFpcalc(JButton btn) {
        btn.setEnabled(false);
        btn.setText("Téléchargement…");
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
                        "fpcalc installé avec succès :\n" + path, "Installation", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Erreur : " + ex.getMessage(), "Installation fpcalc", JOptionPane.ERROR_MESSAGE);
                    refreshFpcalcStatus();
                }
                btn.setEnabled(true);
                btn.setText("Télécharger fpcalc…");
            }
        }.execute();
    }

    @SuppressWarnings("unchecked")
    private JPanel buildMbOAuthPanel() {
        tfMbClientId     = tf();
        tfMbClientSecret = tf();
        lblMbAccount     = new JLabel();
        cmbMbOAuthMode   = new JComboBox<>(new String[]{
            "scheme (URL handler)", "localhost (port 8484)", "oob (copier-coller)"});

        // Bouton unique qui change selon l'état : "Obtenir ID/Secret" → "Se connecter"
        JButton btnAction = new JButton();
        JButton btnLogout = new JButton("Déconnexion");
        refreshMbStatus(lblMbAccount, btnAction, btnLogout);

        Runnable updateBtn = () -> {
            if (Config.get().mbConnected()) return;
            boolean hasCredentials = !tfMbClientId.getText().trim().isBlank()
                                  && !tfMbClientSecret.getText().trim().isBlank();
            if (hasCredentials) {
                btnAction.setText("🔑  Se connecter à MusicBrainz");
                btnAction.setToolTipText("Ouvrir le navigateur pour autoriser OpenTagger");
                btnAction.putClientProperty("FlatLaf.style", "background: #1a6030");
            } else {
                btnAction.setText("🌐  Obtenir l'ID et le Secret");
                btnAction.setToolTipText("Ouvre musicbrainz.org pour enregistrer l'application");
                btnAction.putClientProperty("FlatLaf.style", "");
            }
            btnAction.setEnabled(true);
            btnAction.repaint();
        };

        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { updateBtn.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { updateBtn.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateBtn.run(); }
        };
        tfMbClientId    .getDocument().addDocumentListener(dl);
        tfMbClientSecret.getDocument().addDocumentListener(dl);

        btnAction.addActionListener(e -> {
            boolean hasCredentials = !tfMbClientId.getText().trim().isBlank()
                                  && !tfMbClientSecret.getText().trim().isBlank();
            if (!hasCredentials) {
                // Ouvrir la page d'enregistrement MusicBrainz
                try {
                    java.awt.Desktop.getDesktop().browse(
                        java.net.URI.create("https://musicbrainz.org/account/applications/register"));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Ouvrez : https://musicbrainz.org/account/applications/register\n" +
                        "Redirect URI : http://localhost",
                        "Enregistrement MusicBrainz", JOptionPane.INFORMATION_MESSAGE);
                }
                return;
            }
            // Credentials remplis → lancer OAuth
            Config.get().set("mb.oauth.client_id",     tfMbClientId.getText().trim());
            Config.get().set("mb.oauth.client_secret", tfMbClientSecret.getText().trim());
            btnAction.setEnabled(false);
            btnAction.setText("Ouverture du navigateur…");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception { return new MusicBrainzOAuth().authorize(); }
                @Override protected void done() {
                    try { get(); } catch (Exception ex) {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                            "<html>" + ex.getMessage().replace("\n","<br>") + "</html>",
                            "Erreur OAuth", JOptionPane.ERROR_MESSAGE);
                    }
                    refreshMbStatus(lblMbAccount, btnAction, btnLogout);
                }
            }.execute();
        });
        btnLogout.addActionListener(e -> { MusicBrainzOAuth.logout(); refreshMbStatus(lblMbAccount, btnAction, btnLogout); updateBtn.run(); });
        updateBtn.run();

        // Si token présent mais username manquant/inconnu → re-fetch automatique
        if (Config.get().mbConnected()) {
            String u = Config.get().mbUsername();
            if (u.isBlank() || u.equals("(inconnu)")) {
                lblMbAccount.setText("Récupération du compte…");
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
                            lblMbAccount.setText("(token invalide — reconnectez-vous)");
                            lblMbAccount.putClientProperty("FlatLaf.style", "foreground: #f44336");
                        }
                    }
                }.execute();
            }
        }

        JLabel hint = new JLabel(
            "<html><i><b>scheme</b> : URL handler système (défaut, Linux/Mac).<br>" +
            "<b>localhost</b> : serveur local port 8484, redirect_uri = http://localhost:8484.<br>" +
            "<b>oob</b> : code affiché dans le navigateur, copier-coller ici.</i></html>");
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        hint.setBorder(new EmptyBorder(4, 0, 0, 0));

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Compte MusicBrainz (contribution OAuth2)"));
        String[] labels = {"Client ID :", "Client Secret :", "Compte connecté :", "Mode OAuth :"};
        JComponent[] fields = {tfMbClientId, tfMbClientSecret, lblMbAccount, cmbMbOAuthMode};
        for (int i = 0; i < labels.length; i++) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(4,10,4,8);
            inner.add(new JLabel(labels[i]), lc);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1; fc.insets = new Insets(4,0,4,10);
            inner.add(fields[i], fc);
        }
        GridBagConstraints bc = new GridBagConstraints();
        bc.gridx = 1; bc.gridy = 4; bc.anchor = GridBagConstraints.WEST; bc.insets = new Insets(6,0,4,10);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.add(btnAction); btnRow.add(btnLogout);
        inner.add(btnRow, bc);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(new EmptyBorder(12, 12, 12, 12));
        wrap.add(inner, BorderLayout.NORTH);
        wrap.add(hint,  BorderLayout.CENTER);
        return wrap;
    }

    private void refreshMbStatus(JLabel lbl, JButton btnAction, JButton btnLogout) {
        boolean connected = Config.get().mbConnected();
        String  username  = Config.get().mbUsername();
        lbl.setText(connected ? username : "(non connecté)");
        lbl.putClientProperty("FlatLaf.style", connected ? "foreground: #1db954" : "foreground: #888888");
        btnAction.setEnabled(true);
        btnAction.setVisible(!connected);
        btnLogout.setVisible(connected);
    }

    // ── Chargement / sauvegarde ──────────────────────────────────────────────

    private void load() {
        Config cfg = Config.get();
        tfMbUserAgent      .setText(cfg.str("musicbrainz.user_agent",   "OpenTagger/1.0 (bain.paul24@gmail.com)"));
        tfAcoustIdKey      .setText(cfg.str("acoustid.api_key",         ""));
        tfAcoustIdUserToken.setText(cfg.str("acoustid.user_token",      ""));
        tfDiscogsKey       .setText(cfg.str("discogs.consumer_key",     ""));
        tfDiscogsSecret .setText(cfg.str("discogs.consumer_secret",    ""));
        tfLastFmKey     .setText(cfg.str("lastfm.api_key",             ""));
        tfFanArtKey     .setText(cfg.str("fanart.api_key",             ""));

        spMinScore      .setValue(cfg.num("autocorrector.min_score",   85));
        chkOnlyOfficial .setSelected(cfg.bool("musicbrainz.only_official", true));
        tfPreferredCountry.setText(cfg.str("musicbrainz.preferred_country", ""));
        spResultsLimit  .setValue(cfg.num("musicbrainz.results_limit", 5));
        spCacheDays     .setValue(cfg.num("musicbrainz.cache_days",    30));

        spDefaultMask   .setValue(cfg.num("rename.default_mask",       3));
        chkAutoRename   .setSelected(cfg.bool("rename.auto_enabled",   false));
        spDefaultMask   .setEnabled(chkAutoRename.isSelected());

        startupFolderModel.clear();
        for (String f : cfg.startupFolders())
            if (!f.isBlank()) startupFolderModel.addElement(f);

        tfFfmpegPath    .setText(cfg.str("audio.ffmpeg_path",          "ffmpeg"));
        tfEssentiaPath  .setText(cfg.str("audio.essentia_path",        "essentia_streaming_extractor_music"));
        tfFpcalcPath    .setText(cfg.str("audio.fpcalc_path",          ""));
        chkLyricsEnabled.setSelected(cfg.bool("lyrics.enabled",        true));
        tfRapidApiKey.setText(cfg.str("rapidapi.key",    ""));
        tfAudDToken  .setText(cfg.str("audd.api_token", ""));

        chkFanartEnabled.setSelected(cfg.bool("fanart.download_cover", true));
        chkLastfmEnabled.setSelected(cfg.bool("lastfm.use_tags",       true));

        String genreSrc = cfg.str("discogs.genre_source", "style_then_genre");
        cmbDiscogsGenreSource.setSelectedIndex(
            "genre_then_style".equals(genreSrc) ? 1 : "genre_only".equals(genreSrc) ? 2 : 0);
        spDiscogsMaxGenres.setValue(cfg.num("discogs.max_genres",   3));
        spLastfmMaxGenres .setValue(cfg.num("lastfm.max_genres",    3));

        // ─ Releases préférées ─
        tfPreferredCountries.setText(cfg.str("releases.preferred_countries", ""));
        tfPreferredFormats  .setText(cfg.str("releases.preferred_formats",   ""));
        tfVaName            .setText(cfg.vaName());
        chkStandardizeArtists.setSelected(cfg.standardizeArtists());

        // ─ Tags (onglet Tags) ─
        String id3v = cfg.id3v2Version();
        cmbId3Version.setSelectedIndex("2.3".equals(id3v) ? 1 : "2.4".equals(id3v) ? 2 : 0);
        chkPreserveTimestamps     .setSelected(cfg.preserveTimestamps());
        chkClearExistingTags      .setSelected(cfg.clearExistingTags());
        chkPreserveImages         .setSelected(cfg.preserveImages());
        chkSaveAcoustidFingerprints.setSelected(cfg.saveAcoustidFingerprints());
        chkIgnoreExistingFingerprints.setSelected(cfg.ignoreExistingFingerprints());
        spFpcalcThreads           .setValue(cfg.fpcalcThreads());

        tfMbClientId    .setText(cfg.mbClientId());
        tfMbClientSecret.setText(cfg.mbClientSecret());
        String mode = cfg.str("mb.oauth.mode", "scheme");
        cmbMbOAuthMode.setSelectedIndex(
            "localhost".equals(mode) ? 1 : "oob".equals(mode) ? 2 : 0);
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

        p.setProperty("musicbrainz.user_agent",        tfMbUserAgent.getText().trim());
        p.setProperty("acoustid.api_key",               tfAcoustIdKey.getText().trim());
        p.setProperty("acoustid.user_token",            tfAcoustIdUserToken.getText().trim());
        p.setProperty("discogs.consumer_key",           tfDiscogsKey.getText().trim());
        p.setProperty("discogs.consumer_secret",        tfDiscogsSecret.getText().trim());
        p.setProperty("lastfm.api_key",                 tfLastFmKey.getText().trim());
        p.setProperty("fanart.api_key",                 tfFanArtKey.getText().trim());

        p.setProperty("autocorrector.min_score",       String.valueOf(spMinScore.getValue()));
        p.setProperty("musicbrainz.only_official",     String.valueOf(chkOnlyOfficial.isSelected()));
        p.setProperty("musicbrainz.preferred_country", tfPreferredCountry.getText().trim());
        p.setProperty("musicbrainz.results_limit",     String.valueOf(spResultsLimit.getValue()));
        p.setProperty("musicbrainz.cache_days",        String.valueOf(spCacheDays.getValue()));

        p.setProperty("rename.default_mask",           String.valueOf(spDefaultMask.getValue()));
        p.setProperty("rename.auto_enabled",           String.valueOf(chkAutoRename.isSelected()));

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
        p.setProperty("rapidapi.key",   tfRapidApiKey.getText().trim());
        p.setProperty("audd.api_token", tfAudDToken.getText().trim());

        p.setProperty("fanart.download_cover", String.valueOf(chkFanartEnabled.isSelected()));
        p.setProperty("lastfm.use_tags",       String.valueOf(chkLastfmEnabled.isSelected()));

        String[] genreSources = {"style_then_genre", "genre_then_style", "genre_only"};
        p.setProperty("discogs.genre_source", genreSources[cmbDiscogsGenreSource.getSelectedIndex()]);
        p.setProperty("discogs.max_genres",   String.valueOf(spDiscogsMaxGenres.getValue()));
        p.setProperty("lastfm.max_genres",    String.valueOf(spLastfmMaxGenres.getValue()));

        // ─ Releases préférées ─
        p.setProperty("releases.preferred_countries", tfPreferredCountries.getText().trim());
        p.setProperty("releases.preferred_formats",   tfPreferredFormats.getText().trim());
        p.setProperty("metadata.va_name",             tfVaName.getText().trim().isEmpty()
                                                      ? "Various Artists" : tfVaName.getText().trim());
        p.setProperty("metadata.standardize_artists", String.valueOf(chkStandardizeArtists.isSelected()));

        // ─ Onglet Tags ─
        String[] id3Versions = {"keep", "2.3", "2.4"};
        p.setProperty("tags.id3v2_version",            id3Versions[cmbId3Version.getSelectedIndex()]);
        p.setProperty("tags.preserve_timestamps",      String.valueOf(chkPreserveTimestamps.isSelected()));
        p.setProperty("tags.clear_existing_tags",      String.valueOf(chkClearExistingTags.isSelected()));
        p.setProperty("tags.preserve_images",          String.valueOf(chkPreserveImages.isSelected()));
        p.setProperty("acoustid.save_fingerprints",    String.valueOf(chkSaveAcoustidFingerprints.isSelected()));
        p.setProperty("acoustid.ignore_existing",      String.valueOf(chkIgnoreExistingFingerprints.isSelected()));
        p.setProperty("acoustid.fpcalc_threads",       String.valueOf(spFpcalcThreads.getValue()));

        p.setProperty("mb.oauth.client_id",            tfMbClientId.getText().trim());
        p.setProperty("mb.oauth.client_secret",        tfMbClientSecret.getText().trim());
        // Conserver le token et username existants
        p.setProperty("mb.oauth.token",               Config.get().mbToken());
        p.setProperty("mb.oauth.username",            Config.get().mbUsername());
        String[] oauthModes = {"scheme", "localhost", "oob"};
        p.setProperty("mb.oauth.mode", oauthModes[cmbMbOAuthMode.getSelectedIndex()]);

        // Mémoriser les dossiers déjà connus avant la sauvegarde
        java.util.Set<String> alreadyKnown = new java.util.HashSet<>(
                java.util.Arrays.asList(Config.get().startupFolders()));

        try {
            Path dir = Paths.get(System.getProperty("user.home") + "/.opentagger");
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
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "Erreur sauvegarde : " + ex.getMessage(),
                    "Erreur", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Helpers UI ───────────────────────────────────────────────────────────

    private JTextField tf() { return new JTextField(28); }

    private JPanel form(String[] labels, JComponent[] fields, String title) {
        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), title));

        for (int i = 0; i < labels.length; i++) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST;
            lc.insets = new Insets(4, 10, 4, 8);
            inner.add(new JLabel(labels[i]), lc);

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
