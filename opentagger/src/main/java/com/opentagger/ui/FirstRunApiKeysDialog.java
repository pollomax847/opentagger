package com.opentagger.ui;

import com.opentagger.ApiKeyTester;
import com.opentagger.Config;
import com.opentagger.I18n;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Supplier;

/**
 * Fenêtre affichée une seule fois au tout premier démarrage : propose de renseigner les clés API
 * (AcoustID, Discogs, Last.fm, FanArt.tv, AudD) sans avoir à fouiller les Préférences. Toutes les
 * clés sont facultatives — "Plus tard" ferme sans rien enregistrer, et tout reste modifiable dans
 * Préférences → APIs (mêmes clés de configuration que SettingsDialog).
 */
public class FirstRunApiKeysDialog extends JDialog {

    private static final String PROMPTED_KEY = "setup.api_keys_prompted";

    private final JTextField tfAcoustId   = new JTextField(28);
    private final JTextField tfDiscogsKey = new JTextField(28);
    private final JTextField tfDiscogsSec = new JTextField(28);
    private final JTextField tfLastFm     = new JTextField(28);
    private final JTextField tfFanArt     = new JTextField(28);
    private final JTextField tfAudD       = new JTextField(28);

    /** Affiche la fenêtre si c'est le premier démarrage ET qu'aucune clé n'est déjà configurée
     *  (un utilisateur existant qui met à jour l'appli ne doit pas la voir). Dans tous les cas le
     *  drapeau est posé : la fenêtre ne réapparaît jamais d'elle-même. */
    public static void showIfFirstRun(Frame owner) {
        Config cfg = Config.get();
        if (cfg.bool(PROMPTED_KEY, false)) return;
        cfg.set(PROMPTED_KEY, "true");
        if (!cfg.acoustidKey().isBlank() || !cfg.lastfmKey().isBlank() || !cfg.fanartKey().isBlank()
                || !cfg.discogsKey().isBlank() || !cfg.str("audd.api_token", "").isBlank()) return;
        new FirstRunApiKeysDialog(owner).setVisible(true);
    }

    private FirstRunApiKeysDialog(Frame owner) {
        super(owner, I18n.t("Bienvenue dans OpenTagger"), true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(14, 16, 12, 16));

        JLabel intro = new JLabel("<html><body style='width:480px'>"
                + I18n.t("Pour identifier vos fichiers, OpenTagger utilise des services en ligne gratuits qui "
                + "demandent chacun une clé personnelle. Renseignez celles que vous avez (toutes sont "
                + "facultatives) — vous pourrez les modifier plus tard dans Préférences → APIs.")
                + "</body></html>");
        content.add(intro, BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        Object[][] rows = {
            { "AcoustID API Key :",        tfAcoustId,   "https://acoustid.org/new-application",
                (Supplier<ApiKeyTester.Result>) () -> ApiKeyTester.testAcoustId(tfAcoustId.getText().trim()) },
            { "Discogs Consumer Key :",    tfDiscogsKey, "https://www.discogs.com/settings/developers", null },
            { "Discogs Consumer Secret :", tfDiscogsSec, "https://www.discogs.com/settings/developers",
                (Supplier<ApiKeyTester.Result>) () -> ApiKeyTester.testDiscogs(tfDiscogsKey.getText().trim(), tfDiscogsSec.getText().trim()) },
            { "Last.fm API Key :",         tfLastFm,     "https://www.last.fm/api/account/create",
                (Supplier<ApiKeyTester.Result>) () -> ApiKeyTester.testLastFm(tfLastFm.getText().trim()) },
            { "FanArt.tv API Key :",       tfFanArt,     "https://fanart.tv/get-an-api-key/",
                (Supplier<ApiKeyTester.Result>) () -> ApiKeyTester.testFanArt(tfFanArt.getText().trim()) },
            { "AudD API Token :",          tfAudD,       "https://dashboard.audd.io/",
                (Supplier<ApiKeyTester.Result>) () -> ApiKeyTester.testAudD(tfAudD.getText().trim()) },
        };
        for (int i = 0; i < rows.length; i++) {
            @SuppressWarnings("unchecked")
            Supplier<ApiKeyTester.Result> testFn = (Supplier<ApiKeyTester.Result>) rows[i][3];

            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.gridy = i; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 0, 3, 8);
            grid.add(new JLabel(I18n.t((String) rows[i][0])), lc);

            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.gridy = i; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
            fc.insets = new Insets(3, 0, 3, 4);
            grid.add((JComponent) rows[i][1], fc);

            GridBagConstraints bc = new GridBagConstraints();
            bc.gridx = 2; bc.gridy = i; bc.anchor = GridBagConstraints.WEST; bc.insets = new Insets(3, 0, 3, 4);
            grid.add(linkButton((String) rows[i][2]), bc);

            if (testFn != null) {
                GridBagConstraints tc = new GridBagConstraints();
                tc.gridx = 3; tc.gridy = i; tc.anchor = GridBagConstraints.WEST; tc.insets = new Insets(3, 0, 3, 0);
                grid.add(testPanel(testFn), tc);
            }
        }
        content.add(grid, BorderLayout.CENTER);

        JButton later = new JButton(I18n.t("Plus tard"));
        later.addActionListener(e -> dispose());
        JButton save = new JButton(I18n.t("Enregistrer"));
        save.addActionListener(e -> { save(); dispose(); });
        getRootPane().setDefaultButton(save);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(later);
        buttons.add(save);
        content.add(buttons, BorderLayout.SOUTH);

        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
    }

    private void save() {
        Config cfg = Config.get();
        putIfFilled(cfg, "acoustid.api_key",         tfAcoustId);
        putIfFilled(cfg, "discogs.consumer_key",     tfDiscogsKey);
        putIfFilled(cfg, "discogs.consumer_secret",  tfDiscogsSec);
        putIfFilled(cfg, "lastfm.api_key",           tfLastFm);
        putIfFilled(cfg, "fanart.api_key",           tfFanArt);
        putIfFilled(cfg, "audd.api_token",           tfAudD);
    }

    private static void putIfFilled(Config cfg, String key, JTextField field) {
        String v = field.getText().trim();
        if (!v.isEmpty()) cfg.set(key, v);
    }

    private JButton linkButton(String url) {
        JButton btn = new JButton("🔗 " + I18n.t("Obtenir"));
        btn.putClientProperty("FlatLaf.style", "font: 10 $defaultFont; background: null; arc: 6");
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(url);
        btn.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, I18n.t("Ouvrez : %s", url),
                        I18n.t("Lien"), JOptionPane.INFORMATION_MESSAGE);
            }
        });
        return btn;
    }

    private JPanel testPanel(Supplier<ApiKeyTester.Result> testFn) {
        JButton btn = new JButton(I18n.t("Tester"));
        btn.putClientProperty("FlatLaf.style", "font: 10 $defaultFont; arc: 6");
        JLabel status = new JLabel();
        status.putClientProperty("FlatLaf.style", "font: 10 $defaultFont");
        btn.addActionListener(e -> {
            btn.setEnabled(false);
            status.setText(I18n.t("Test en cours…"));
            status.setToolTipText(null);
            new SwingWorker<ApiKeyTester.Result, Void>() {
                @Override protected ApiKeyTester.Result doInBackground() { return testFn.get(); }
                @Override protected void done() {
                    btn.setEnabled(true);
                    ApiKeyTester.Result r;
                    try { r = get(); } catch (Exception ex) { r = new ApiKeyTester.Result(false, ex.getMessage()); }
                    status.setText(r.ok() ? "✓ " + I18n.t("OK") : "✗ " + I18n.t("Échec"));
                    status.setToolTipText(r.message());
                    status.putClientProperty("FlatLaf.style",
                        "font: 10 $defaultFont; foreground: " + (r.ok() ? "#4caf50" : "#f44336"));
                }
            }.execute();
        });
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        p.add(btn);
        p.add(status);
        return p;
    }
}
