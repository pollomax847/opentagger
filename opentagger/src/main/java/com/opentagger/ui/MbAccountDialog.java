package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MusicBrainzOAuth;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Compte MusicBrainz : connexion, déconnexion et collection. Facultatif — l'identification lit l'API publique sans compte ; la
 * connexion ne sert qu'à CONTRIBUER (tags et notes envoyés à chaque enregistrement, ajout à une collection). Remplace la section
 * « Compte MusicBrainz » des Préférences, qui n'avait rien à y faire.
 */
public class MbAccountDialog extends JDialog {

    private final JLabel lblAccount = new JLabel();
    private final JButton btnConnect = new JButton("🔑  " + I18n.t("Se connecter à MusicBrainz"));
    private final JButton btnLogout = new JButton(I18n.t("Déconnexion"));
    private final JTextField tfCollection = new JTextField(Config.get().mbCollectionId(), 36);

    public MbAccountDialog(Frame owner) {
        super(owner, I18n.t("Compte MusicBrainz"), true);
        setLayout(new BorderLayout(0, 8));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(14, 16, 6, 16));
        addRow(form, 0, I18n.t("Compte connecté :"), lblAccount);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(btnConnect);
        buttons.add(btnLogout);
        addRow(form, 1, "", buttons);
        tfCollection.setToolTipText(I18n.t("MBID de votre collection MusicBrainz (visible dans l'URL de la page de la collection sur "
                + "musicbrainz.org) : les releases taguées y sont ajoutées automatiquement. Vide = désactivé. La collection doit exister."));
        addRow(form, 2, I18n.t("Collection (optionnel) :"), tfCollection);

        JLabel note = new JLabel(I18n.t("<html><body style='width:430px'><i>Facultatif : sans compte, tout fonctionne. Une fois connecté, "
                + "les genres et la note de chaque fichier enregistré sont envoyés à MusicBrainz, où ils sont <b>publics</b>.</i></body></html>"));
        note.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        note.setBorder(new EmptyBorder(0, 16, 4, 16));

        JButton ok = new JButton(I18n.t("Fermer"));
        ok.addActionListener(e -> close());
        getRootPane().setDefaultButton(ok);
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        foot.add(ok);

        JPanel center = new JPanel(new BorderLayout());
        center.add(form, BorderLayout.NORTH);
        center.add(note, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);

        btnConnect.addActionListener(e -> connect());
        btnLogout.addActionListener(e -> { MusicBrainzOAuth.logout(); refresh(); });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { close(); }
        });
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { saveCollection(); }
        });

        refresh();
        fetchUsernameIfMissing();
        pack();
        setMinimumSize(new Dimension(520, getHeight()));
        setLocationRelativeTo(owner);
    }

    private static void addRow(JPanel p, int row, String label, JComponent field) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row; lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(5, 0, 5, 10);
        p.add(new JLabel(label), lc);
        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = row; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1; fc.insets = new Insets(5, 0, 5, 0);
        p.add(field, fc);
    }

    private void close() {
        saveCollection();
        dispose();
    }

    private void saveCollection() {
        String v = tfCollection.getText().trim();
        if (!v.equals(Config.get().mbCollectionId())) Config.get().set("mb.oauth.collection_id", v);
    }

    private void refresh() {
        boolean connected = Config.get().mbConnected();
        lblAccount.setText(connected ? Config.get().mbUsername() : I18n.t("(non connecté)"));
        lblAccount.putClientProperty("FlatLaf.style", connected ? "foreground: #1db954" : "foreground: #888888");
        btnConnect.setEnabled(true);
        btnConnect.setText("🔑  " + I18n.t("Se connecter à MusicBrainz"));
        btnConnect.setVisible(!connected);
        btnLogout.setVisible(connected);
    }

    private void connect() {
        if (Config.get().mbClientId().isBlank() || Config.get().mbClientSecret().isBlank()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Client ID/Secret MusicBrainz manquants dans la configuration de l'application.\n"
                            + "Ce n'est pas quelque chose à saisir manuellement — contactez le développeur ou réinstallez OpenTagger."),
                    I18n.t("Configuration incomplète"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        btnConnect.setEnabled(false);
        btnConnect.setText(I18n.t("Ouverture du navigateur…"));
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return new MusicBrainzOAuth().authorize(); }
            @Override protected void done() {
                try { get(); }
                catch (Exception ex) {
                    String msg = ex.getMessage();
                    if (msg == null && ex.getCause() != null) msg = ex.getCause().getMessage();
                    if (msg == null) msg = ex.getClass().getSimpleName();
                    JOptionPane.showMessageDialog(MbAccountDialog.this, "<html>" + msg.replace("\n", "<br>") + "</html>",
                            I18n.t("Erreur OAuth"), JOptionPane.ERROR_MESSAGE);
                }
                refresh();
            }
        }.execute();
    }

    /** Jeton présent mais nom inconnu : on le redemande à MusicBrainz plutôt que d'afficher « (inconnu) ». */
    private void fetchUsernameIfMissing() {
        if (!Config.get().mbConnected()) return;
        String u = Config.get().mbUsername();
        if (!(u.isBlank() || u.equals("(inconnu)"))) return;
        lblAccount.setText(I18n.t("Récupération du compte…"));
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return new MusicBrainzOAuth().fetchUsername(Config.get().mbToken()); }
            @Override protected void done() {
                try {
                    Config.get().set("mb.oauth.username", get());
                    refresh();
                } catch (Exception ex) {
                    lblAccount.setText(I18n.t("(token invalide — reconnectez-vous)"));
                    lblAccount.putClientProperty("FlatLaf.style", "foreground: #f44336");
                }
            }
        }.execute();
    }

    public static void open(Frame owner) { new MbAccountDialog(owner).setVisible(true); }
}
