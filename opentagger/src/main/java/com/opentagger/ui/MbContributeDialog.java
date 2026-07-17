package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MusicBrainzOAuth;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dialogue "Contribuer à MusicBrainz" — soumet des user-tags et un rating
 * pour un enregistrement, via l'API MB authentifiée (OAuth2).
 */
public class MbContributeDialog extends JDialog {

    private final FileEntry       entry;
    private final TagInfo         ti;

    private final JLabel          lblConnected;
    private final JButton         btnLogin;
    private final JButton         btnLogout;

    private final List<JCheckBox> genreCbs  = new ArrayList<>();
    private final List<JButton>   starBtns  = new ArrayList<>();
    private       JButton         btnContrib;
    private final JTextField      tfCustomTag;
    private final JLabel          lblStars;
    private       int             stars = 0;

    public MbContributeDialog(Frame owner, FileEntry entry) {
        super(owner, I18n.t("Contribuer à MusicBrainz"), true);
        this.entry = entry;
        this.ti    = entry.activeTags();
        setSize(560, 540);
        setMinimumSize(new Dimension(460, 440));
        setLocationRelativeTo(owner);

        lblConnected = new JLabel();
        btnLogin     = new JButton("🔑  " + I18n.t("Connexion OAuth…"));
        btnLogout    = new JButton(I18n.t("Déconnexion"));
        tfCustomTag  = new JTextField(20);
        tfCustomTag.putClientProperty("JTextField.placeholderText", I18n.t("ex: jazz, live, 80s, cover"));
        lblStars = new JLabel(I18n.t("(non noté)"), SwingConstants.LEFT);
        lblStars.putClientProperty("FlatLaf.style", "foreground: #f0c040");

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(buildHeader(), BorderLayout.NORTH);
        getContentPane().add(buildForm(),   BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        refreshLoginState();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // ── En-tête ───────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel info = new JPanel(new GridBagLayout());
        info.setBorder(new EmptyBorder(10, 14, 6, 14));
        addInfoRow(info, 0, "Artiste :", ti.artist.isBlank()       ? "—" : ti.artist);
        addInfoRow(info, 1, "Titre :",   ti.title.isBlank()        ? "—" : ti.title);
        addInfoRow(info, 2, "Album :",   ti.album.isBlank()        ? "—" : ti.album);
        addInfoRow(info, 3, "MBID :",    ti.recordingMbid.isBlank() ? I18n.t("(non identifié)") : ti.recordingMbid);

        JButton btnOpen = new JButton("🌐  " + I18n.t("Voir sur musicbrainz.org"));
        btnOpen.setEnabled(!ti.recordingMbid.isBlank());
        btnOpen.addActionListener(e -> openInBrowser());
        btnLogin .addActionListener(e -> doLogin());
        btnLogout.addActionListener(e -> doLogout());

        JPanel account = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        account.add(new JLabel(I18n.t("Compte MB : ")));
        account.add(lblConnected);
        account.add(btnLogin);
        account.add(btnLogout);
        account.add(Box.createHorizontalStrut(14));
        account.add(btnOpen);

        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));
        p.add(info,    BorderLayout.CENTER);
        p.add(account, BorderLayout.SOUTH);
        return p;
    }

    private void addInfoRow(JPanel p, int row, String label, String value) {
        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = row; lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(2, 0, 2, 10);
        p.add(new JLabel(I18n.t(label)), lc);
        GridBagConstraints vc = new GridBagConstraints();
        vc.gridx = 1; vc.gridy = row; vc.weightx = 1; vc.fill = GridBagConstraints.HORIZONTAL;
        vc.insets = new Insets(2, 0, 2, 0);
        JLabel lbl = new JLabel(value);
        if (label.equals("MBID :"))
            lbl.putClientProperty("FlatLaf.style", "font: 11 $monospacedFont; foreground: #888888");
        p.add(lbl, vc);
    }

    // ── Formulaire ────────────────────────────────────────────────────────────

    private JPanel buildForm() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBorder(new EmptyBorder(12, 14, 0, 14));
        p.add(buildTagsPanel(),   BorderLayout.CENTER);
        p.add(buildRatingPanel(), BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildTagsPanel() {
        JPanel outer = new JPanel(new BorderLayout(0, 8));
        outer.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("User Tags à soumettre (publics sur MusicBrainz)")));

        JPanel cbPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        if (!ti.genre.isBlank()) {
            Arrays.stream(ti.genre.split("[,/;]"))
                    .map(String::trim).filter(s -> !s.isBlank()).distinct()
                    .forEach(g -> {
                        JCheckBox cb = new JCheckBox(g, true);
                        genreCbs.add(cb);
                        cbPanel.add(cb);
                    });
        }
        if (genreCbs.isEmpty())
            cbPanel.add(new JLabel(I18n.t("<html><i>Aucun genre dans les tags de ce fichier.</i></html>")));

        JPanel customRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        customRow.add(new JLabel(I18n.t("Tag(s) libre(s) :")));
        customRow.add(tfCustomTag);

        outer.add(cbPanel,    BorderLayout.CENTER);
        outer.add(customRow,  BorderLayout.SOUTH);
        return outer;
    }

    private JPanel buildRatingPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(), I18n.t("Rating utilisateur")));

        for (int i = 1; i <= 5; i++) {
            final int s = i;
            JButton b = new JButton("★");
            b.setMargin(new Insets(0, 6, 0, 6));
            b.setFocusPainted(false);
            String plural = s > 1 ? "s" : "";
            b.setToolTipText(I18n.t("%d étoile%s  →  %d / 100 sur MB", s, plural, s * 20));
            b.addActionListener(e -> { stars = s; updateStars(); });
            starBtns.add(b);
            p.add(b);
        }
        JButton btnClear = new JButton("✕");
        btnClear.setMargin(new Insets(0, 4, 0, 4));
        btnClear.setFocusPainted(false);
        btnClear.setToolTipText(I18n.t("Effacer le rating"));
        btnClear.addActionListener(e -> { stars = 0; updateStars(); });
        p.add(btnClear);
        p.add(Box.createHorizontalStrut(8));
        p.add(lblStars);
        return p;
    }

    private void updateStars() {
        if (stars == 0) lblStars.setText(I18n.t("(non noté)"));
        else lblStars.setText("★".repeat(stars) + "☆".repeat(5 - stars) + "  (" + (stars * 20) + "/100)");
        for (int i = 0; i < starBtns.size(); i++) {
            JButton b = starBtns.get(i);
            boolean active = (i + 1) <= stars;
            b.putClientProperty("FlatLaf.style",
                active ? "foreground: #f0c040; font: bold $buttonFont"
                       : "foreground: #555555; font: $buttonFont");
            b.repaint();
        }
    }

    // ── Pied de page ─────────────────────────────────────────────────────────

    private JPanel buildFooter() {
        btnContrib = new JButton("🤝  " + I18n.t("Contribuer"));
        JButton btnCancel = new JButton(I18n.t("Annuler"));
        btnContrib.putClientProperty("FlatLaf.style", "background: #1a6030");
        btnContrib.addActionListener(e -> contribute());
        btnCancel .addActionListener(e -> dispose());
        getRootPane().setDefaultButton(btnContrib);

        JLabel note = new JLabel(
            I18n.t("<html><i>User-tags et rating sont publics sur MusicBrainz.</i></html>"));
        note.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnCancel); right.add(btnContrib);

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 14, 8, 14)));
        p.add(note,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ── OAuth ─────────────────────────────────────────────────────────────────

    private void doLogin() {
        btnLogin.setEnabled(false);
        btnLogin.setText(I18n.t("Ouverture du navigateur…"));
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return new MusicBrainzOAuth().authorize();
            }
            @Override protected void done() {
                try {
                    get();
                    refreshLoginState();
                } catch (Exception ex) {
                    String msg = ex.getMessage();
                    if (msg == null && ex.getCause() != null) msg = ex.getCause().getMessage();
                    if (msg == null) msg = ex.getClass().getSimpleName();
                    JOptionPane.showMessageDialog(MbContributeDialog.this,
                        "<html>" + msg.replace("\n", "<br>") + "</html>",
                        I18n.t("Erreur de connexion"), JOptionPane.ERROR_MESSAGE);
                    refreshLoginState();
                }
            }
        }.execute();
    }

    private void doLogout() {
        MusicBrainzOAuth.logout();
        refreshLoginState();
    }

    private void refreshLoginState() {
        boolean connected = Config.get().mbConnected();
        String  username  = Config.get().mbUsername();
        lblConnected.setText(connected ? username : I18n.t("(non connecté)"));
        lblConnected.putClientProperty("FlatLaf.style",
            connected ? "foreground: #1db954" : "foreground: #888888");
        btnLogin .setVisible(!connected);
        btnLogin .setEnabled(true);
        btnLogin .setText("🔑  " + I18n.t("Connexion OAuth…"));
        btnLogout.setVisible(connected);
    }

    private void openInBrowser() {
        try { Desktop.getDesktop().browse(
            URI.create("https://musicbrainz.org/recording/" + ti.recordingMbid));
        } catch (Exception ignored) {}
    }

    // ── Contribution ─────────────────────────────────────────────────────────

    private void contribute() {
        if (!Config.get().mbConnected()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Connectez-vous à MusicBrainz avant de contribuer."),
                I18n.t("Non connecté"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (ti.recordingMbid.isBlank()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Ce fichier n'a pas de Recording MBID — taguez-le d'abord."),
                I18n.t("MBID manquant"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> tags = new ArrayList<>();
        for (JCheckBox cb : genreCbs) if (cb.isSelected()) tags.add(cb.getText().toLowerCase());
        String custom = tfCustomTag.getText().trim();
        if (!custom.isBlank())
            Arrays.stream(custom.split("[,;]")).map(String::trim).map(String::toLowerCase)
                    .filter(s -> !s.isBlank()).forEach(tags::add);

        // Déduplication : évite de soumettre le même tag deux fois si coché ET tapé
        List<String> dedupTags = tags.stream().distinct().toList();

        if (dedupTags.isEmpty() && stars == 0) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Aucun tag ni rating à soumettre."), I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        final List<String> finalTags  = dedupTags;
        final int          finalStars = stars;

        btnContrib.setEnabled(false);
        btnContrib.setText(I18n.t("Envoi en cours…"));

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                MusicBrainzOAuth oauth = new MusicBrainzOAuth();
                String token = Config.get().mbToken();
                StringBuilder sb = new StringBuilder();
                if (!finalTags.isEmpty()) {
                    oauth.submitUserTags(ti.recordingMbid, finalTags, token);
                    sb.append(I18n.t("%d tag(s) soumis : %s",
                        finalTags.size(), String.join(", ", finalTags)));
                }
                if (finalStars > 0) {
                    oauth.submitRating(ti.recordingMbid, finalStars, token);
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(I18n.t("Rating : %d★  (%d/100)", finalStars, finalStars * 20));
                }
                return sb.toString();
            }
            @Override protected void done() {
                btnContrib.setEnabled(true);
                btnContrib.setText("🤝  " + I18n.t("Contribuer"));
                try {
                    String summary = get();
                    JOptionPane.showMessageDialog(MbContributeDialog.this,
                        I18n.t("Contribution envoyée avec succès !\n\n%s", summary),
                        I18n.t("Succès"), JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception ex) {
                    String msg = ex.getMessage();
                    if (msg == null) msg = ex.getClass().getSimpleName();
                    JOptionPane.showMessageDialog(MbContributeDialog.this,
                        I18n.t("Erreur : %s", msg), I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
