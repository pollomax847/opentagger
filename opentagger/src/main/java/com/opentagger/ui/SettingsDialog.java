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
    private JTextField tfMbUserAgent, tfAcoustIdKey;
    private JTextField tfDiscogsKey, tfDiscogsSecret;
    private JTextField tfLastFmKey;
    private JTextField tfFanArtKey;

    // ── Onglet Matching ──────────────────────────────────────────────────────
    private JSpinner   spMinScore;
    private JCheckBox  chkOnlyOfficial;
    private JTextField tfPreferredCountry;
    private JSpinner   spResultsLimit;
    private JSpinner   spCacheDays;

    // ── Onglet Renommage ─────────────────────────────────────────────────────
    private JSpinner   spDefaultMask;

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

    // ── Onglet MusicBrainz OAuth ──────────────────────────────────────────────
    private JTextField tfMbClientId;
    private JTextField tfMbClientSecret;
    private JLabel     lblMbAccount;

    // ─────────────────────────────────────────────────────────────────────────

    public SettingsDialog(Frame owner) {
        super(owner, "Préférences — OpenTagger", true);
        setSize(560, 480);
        setMinimumSize(new Dimension(480, 420));
        setLocationRelativeTo(owner);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("APIs",         buildApiPanel());
        tabs.addTab("Matching",     buildMatchingPanel());
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

    private JPanel buildApiPanel() {
        tfMbUserAgent   = tf();
        tfAcoustIdKey   = tf();
        tfDiscogsKey    = tf();
        tfDiscogsSecret = tf();
        tfLastFmKey     = tf();
        tfFanArtKey     = tf();
        tfRapidApiKey = tf();
        tfAudDToken   = tf();

        JLabel hint = new JLabel("<html><i>"
                + "Fallback pour fichiers non reconnus par AcoustID — chaîne : Shazam → AudD<br>"
                + "Le genre est ensuite enrichi automatiquement via Discogs et Last.fm.<br>"
                + "Shazam : clé gratuite sur rapidapi.com  |  AudD : clé gratuite sur audd.io</i></html>");
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        hint.setBorder(new EmptyBorder(6, 0, 0, 0));

        JPanel p = form(new String[]{
            "MusicBrainz User-Agent :",
            "AcoustID API Key :",
            "Discogs Consumer Key :",
            "Discogs Consumer Secret :",
            "Last.fm API Key :",
            "FanArt.tv API Key :",
            "RapidAPI Key (Shazam) :",
            "AudD API Token :"
        }, new JComponent[]{
            tfMbUserAgent, tfAcoustIdKey,
            tfDiscogsKey, tfDiscogsSecret,
            tfLastFmKey, tfFanArtKey,
            tfRapidApiKey, tfAudDToken
        }, "Clés d'accès aux services en ligne");
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildMatchingPanel() {
        spMinScore       = new JSpinner(new SpinnerNumberModel(85, 0, 100, 5));
        chkOnlyOfficial  = new JCheckBox("Uniquement les releases officielles");
        tfPreferredCountry = tf();
        tfPreferredCountry.setColumns(4);
        spResultsLimit   = new JSpinner(new SpinnerNumberModel(5, 1, 20, 1));
        spCacheDays      = new JSpinner(new SpinnerNumberModel(30, 1, 365, 7));

        return form(new String[]{
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
    }

    private JPanel buildRenamePanel() {
        spDefaultMask = new JSpinner(new SpinnerNumberModel(3, 0, 35, 1));

        JLabel info = new JLabel("<html><i>0 = AlbumArtist-Album/Track-Title<br>"
                + "3 = AA/Album/AA-Album-Track-Title<br>"
                + "9 = AA/Album/Disc/Track-Title<br>"
                + "34 = [Plex] AA/Album/Track-Title<br>"
                + "35 = [iTunes] AA/Album/Track-Title</i></html>");
        info.setBorder(new EmptyBorder(8, 0, 0, 0));
        info.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel p = form(new String[]{"Masque par défaut (0–35) :"}, new JComponent[]{spDefaultMask},
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

        JPanel p = form(new String[]{
            "Chemin ffmpeg :",
            "Chemin Essentia :",
            "Chemin fpcalc :",
            "Paroles (LyricsOvh) :"
        }, new JComponent[]{
            tfFfmpegPath, tfEssentiaPath, fpcalcRow, chkLyricsEnabled
        }, "Outils audio externes (optionnels)");
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

    private JPanel buildMbOAuthPanel() {
        tfMbClientId     = tf();
        tfMbClientSecret = tf();
        lblMbAccount     = new JLabel();

        JButton btnLogin  = new JButton("🔑  Se connecter à MusicBrainz");
        JButton btnLogout = new JButton("Déconnexion");
        refreshMbStatus(lblMbAccount, btnLogin, btnLogout);
        btnLogin .addActionListener(e -> {
            btnLogin.setEnabled(false); btnLogin.setText("Ouverture du navigateur…");
            new SwingWorker<String, Void>() {
                @Override protected String doInBackground() throws Exception { return new MusicBrainzOAuth().authorize(); }
                @Override protected void done() {
                    try { get(); } catch (Exception ex) {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                            "<html>" + ex.getMessage().replace("\n","<br>") + "</html>",
                            "Erreur OAuth", JOptionPane.ERROR_MESSAGE);
                    }
                    refreshMbStatus(lblMbAccount, btnLogin, btnLogout);
                }
            }.execute();
        });
        btnLogout.addActionListener(e -> { MusicBrainzOAuth.logout(); refreshMbStatus(lblMbAccount, btnLogin, btnLogout); });

        JLabel hint = new JLabel(
            "<html><i>Enregistrez votre application sur<br>" +
            "musicbrainz.org/account/applications/register<br>" +
            "Redirect URI à déclarer : <b>http://localhost</b></i></html>");
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        hint.setBorder(new EmptyBorder(8, 0, 0, 0));

        JPanel inner = new JPanel(new GridBagLayout());
        inner.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), "Compte MusicBrainz (contribution OAuth2)"));
        String[] labels = {"Client ID :", "Client Secret :", "Compte connecté :"};
        JComponent[] fields = {tfMbClientId, tfMbClientSecret, lblMbAccount};
        for (int i = 0; i < labels.length; i++) {
            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(4,10,4,8);
            inner.add(new JLabel(labels[i]), lc);
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1; fc.insets = new Insets(4,0,4,10);
            inner.add(fields[i], fc);
        }
        GridBagConstraints bc = new GridBagConstraints();
        bc.gridx = 1; bc.gridy = 3; bc.anchor = GridBagConstraints.WEST; bc.insets = new Insets(6,0,4,10);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.add(btnLogin); btnRow.add(btnLogout);
        inner.add(btnRow, bc);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBorder(new EmptyBorder(12, 12, 12, 12));
        wrap.add(inner, BorderLayout.NORTH);
        wrap.add(hint,  BorderLayout.CENTER);
        return wrap;
    }

    private void refreshMbStatus(JLabel lbl, JButton btnLogin, JButton btnLogout) {
        boolean connected = Config.get().mbConnected();
        String  username  = Config.get().mbUsername();
        lbl.setText(connected ? username : "(non connecté)");
        lbl.putClientProperty("FlatLaf.style", connected ? "foreground: #1db954" : "foreground: #888888");
        btnLogin .setEnabled(true);
        btnLogin .setText("🔑  Se connecter à MusicBrainz");
        btnLogin .setVisible(!connected);
        btnLogout.setVisible(connected);
    }

    // ── Chargement / sauvegarde ──────────────────────────────────────────────

    private void load() {
        Config cfg = Config.get();
        tfMbUserAgent   .setText(cfg.str("musicbrainz.user_agent",   "OpenTagger/1.0 (bain.paul24@gmail.com)"));
        tfAcoustIdKey   .setText(cfg.str("acoustid.api_key",          ""));
        tfDiscogsKey    .setText(cfg.str("discogs.consumer_key",       ""));
        tfDiscogsSecret .setText(cfg.str("discogs.consumer_secret",    ""));
        tfLastFmKey     .setText(cfg.str("lastfm.api_key",             ""));
        tfFanArtKey     .setText(cfg.str("fanart.api_key",             ""));

        spMinScore      .setValue(cfg.num("autocorrector.min_score",   85));
        chkOnlyOfficial .setSelected(cfg.bool("musicbrainz.only_official", true));
        tfPreferredCountry.setText(cfg.str("musicbrainz.preferred_country", ""));
        spResultsLimit  .setValue(cfg.num("musicbrainz.results_limit", 5));
        spCacheDays     .setValue(cfg.num("musicbrainz.cache_days",    30));

        spDefaultMask   .setValue(cfg.num("rename.default_mask",       3));

        tfFfmpegPath    .setText(cfg.str("audio.ffmpeg_path",          "ffmpeg"));
        tfEssentiaPath  .setText(cfg.str("audio.essentia_path",        "essentia_streaming_extractor_music"));
        tfFpcalcPath    .setText(cfg.str("audio.fpcalc_path",          ""));
        chkLyricsEnabled.setSelected(cfg.bool("lyrics.enabled",        true));
        tfRapidApiKey.setText(cfg.str("rapidapi.key",    ""));
        tfAudDToken  .setText(cfg.str("audd.api_token", ""));

        tfMbClientId    .setText(cfg.mbClientId());
        tfMbClientSecret.setText(cfg.mbClientSecret());
    }

    private void save() {
        Properties p = new Properties();

        p.setProperty("musicbrainz.user_agent",        tfMbUserAgent.getText().trim());
        p.setProperty("acoustid.api_key",               tfAcoustIdKey.getText().trim());
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

        p.setProperty("audio.ffmpeg_path",             tfFfmpegPath.getText().trim());
        p.setProperty("audio.essentia_path",           tfEssentiaPath.getText().trim());
        p.setProperty("audio.fpcalc_path",             tfFpcalcPath.getText().trim());
        p.setProperty("lyrics.enabled",                String.valueOf(chkLyricsEnabled.isSelected()));
        p.setProperty("rapidapi.key",   tfRapidApiKey.getText().trim());
        p.setProperty("audd.api_token", tfAudDToken.getText().trim());

        p.setProperty("mb.oauth.client_id",            tfMbClientId.getText().trim());
        p.setProperty("mb.oauth.client_secret",        tfMbClientSecret.getText().trim());
        // Conserver le token et username existants
        p.setProperty("mb.oauth.token",               Config.get().mbToken());
        p.setProperty("mb.oauth.username",            Config.get().mbUsername());

        try {
            Path dir = Paths.get(System.getProperty("user.home") + "/.opentagger");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(Paths.get(SETTINGS_FILE))) {
                p.store(w, "OpenTagger user settings");
            }
            Config.get().reload(); // Recharge les valeurs en mémoire
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
