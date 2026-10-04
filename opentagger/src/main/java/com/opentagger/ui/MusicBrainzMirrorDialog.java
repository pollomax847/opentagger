package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MusicBrainzMirror;
import com.opentagger.MusicBrainzMirror.Next;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Assistant « Miroir MusicBrainz local » (Outils → MusicBrainz) : UN SEUL bouton, dont l'action suit l'état du poste —
 * installer WSL / installer Docker / démarrer Docker / installer le miroir (toutes les étapes à la suite, puis test et
 * activation automatiques) / revenir à musicbrainz.org. Rien n'est lancé tant que les prérequis ne sont pas réunis.
 * Voir {@link MusicBrainzMirror} pour ce qui est testé ou non.
 */
public class MusicBrainzMirrorDialog extends JDialog {

    private final JTextField tfDir = new JTextField(34);
    private final JCheckBox cbSearch = new JCheckBox(I18n.t("Avec l'index de recherche (recommandé : ~350 Go au lieu de ~100 Go)"), true);
    private final JPanel checksPanel = new JPanel();
    private final JTextArea log = new JTextArea(12, 78);
    private final JProgressBar busy = new JProgressBar();
    private final JLabel lblState = new JLabel(" ");
    private final JButton main = new JButton(" ");
    private final JButton stop = new JButton(I18n.t("Arrêter"));
    private final AtomicReference<Process> current = new AtomicReference<>();
    private volatile boolean running;
    private volatile Next next = Next.INSTALL_MIRROR;

    public static void open(Frame owner) { new MusicBrainzMirrorDialog(owner).setVisible(true); }

    private MusicBrainzMirrorDialog(Frame owner) {
        super(owner, I18n.t("Miroir MusicBrainz local"), false);
        setLayout(new BorderLayout(0, 8));
        ((JComponent) getContentPane()).setBorder(new EmptyBorder(12, 14, 10, 14));

        add(new JLabel("<html>" + I18n.t(
                "Un miroir local supprime la limite de <b>1 requête / 1,1 s</b> de l'API publique. Il s'appuie sur le projet officiel "
              + "<i>musicbrainz-docker</i> (Docker requis ; sous Windows via WSL). L'import prend <b>plusieurs heures</b>.") + "</html>"),
                BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        String saved = Config.get().str("musicbrainz.mirror.dir", "");
        tfDir.setText(saved.isBlank() ? Paths.get(System.getProperty("user.home"), "musicbrainz-docker").toString() : saved);
        JButton browse = new JButton(I18n.t("Parcourir…"));
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(); fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { tfDir.setText(new File(fc.getSelectedFile(), "musicbrainz-docker").getPath()); refresh(); }
        });
        top.add(new JLabel(I18n.t("Dossier du projet :"))); top.add(tfDir); top.add(browse);
        center.add(top);
        JPanel opt = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cbSearch.addActionListener(e -> refresh());
        opt.add(cbSearch);
        center.add(opt);

        checksPanel.setLayout(new BoxLayout(checksPanel, BoxLayout.Y_AXIS));
        checksPanel.setBorder(BorderFactory.createTitledBorder(I18n.t("Prérequis")));
        center.add(checksPanel);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(log);
        sp.setBorder(BorderFactory.createTitledBorder(I18n.t("Journal")));
        center.add(sp);
        busy.setIndeterminate(true); busy.setVisible(false);
        center.add(busy);
        add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.add(lblState, BorderLayout.CENTER);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        main.addActionListener(e -> onMain());
        stop.setVisible(false);
        stop.addActionListener(e -> { Process p = current.get(); if (p != null) p.destroyForcibly(); });
        JButton close = new JButton(I18n.t("Fermer")); close.addActionListener(e -> dispose());
        btns.add(main); btns.add(stop); btns.add(close);
        south.add(btns, BorderLayout.EAST);
        add(south, BorderLayout.SOUTH);

        refresh();
        pack();
        setLocationRelativeTo(owner);
    }

    private Path dir() { return Paths.get(tfDir.getText().trim()); }
    private boolean withSearch() { return cbSearch.isSelected(); }
    private void append(String s) { SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }

    // ── État → libellé du bouton unique ──────────────────────────────────────────────────────────────

    private void refresh() {
        checksPanel.removeAll();
        checksPanel.add(new JLabel(I18n.t("Vérification en cours…")));
        checksPanel.revalidate(); checksPanel.repaint();
        main.setEnabled(false);
        final Path d = dir(); final boolean search = withSearch();
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() {
                List<MusicBrainzMirror.Check> cs = MusicBrainzMirror.checks(d, search);
                boolean wsl = cs.stream().noneMatch(c -> c.id().equals("wsl")) || cs.stream().anyMatch(c -> c.id().equals("wsl") && c.ok());
                boolean installed = cs.stream().anyMatch(c -> c.id().equals("docker") && c.ok()) || MusicBrainzMirror.dockerDesktopInstalled();
                boolean daemon = cs.stream().anyMatch(c -> c.id().equals("daemon") && c.ok());
                return new Object[]{ cs, MusicBrainzMirror.nextAction(wsl, installed, daemon, MusicBrainzMirror.isActive()) };
            }
            @Override @SuppressWarnings("unchecked") protected void done() {
                checksPanel.removeAll();
                try {
                    Object[] r = get();
                    for (MusicBrainzMirror.Check c : (List<MusicBrainzMirror.Check>) r[0]) {
                        JLabel l = new JLabel((c.ok() ? "✔  " : "✖  ") + I18n.t(c.label()) + " — " + c.detail());
                        l.setForeground(c.ok() ? new Color(0x2E9E5B) : new Color(0xD9534F));
                        checksPanel.add(l);
                    }
                    next = (Next) r[1];
                } catch (Exception ex) { checksPanel.add(new JLabel(String.valueOf(ex.getMessage()))); }
                main.setText(switch (next) {
                    case INSTALL_WSL    -> I18n.t("Activer WSL");
                    case INSTALL_DOCKER -> I18n.t("Installer Docker Desktop");
                    case START_DOCKER   -> I18n.t("Démarrer Docker");
                    case INSTALL_MIRROR -> I18n.t("Installer le miroir");
                    case REVERT         -> I18n.t("Revenir à musicbrainz.org");
                });
                main.setEnabled(!running);
                checksPanel.revalidate(); checksPanel.repaint();
                pack();
            }
        }.execute();
    }

    private void onMain() {
        switch (next) {
            case REVERT -> {
                MusicBrainzMirror.deactivate();
                lblState.setText(I18n.t("Réglages d'origine rétablis (musicbrainz.org, 1,1 s)."));
                refresh();
            }
            case INSTALL_WSL -> JOptionPane.showMessageDialog(this, I18n.t(
                    "WSL n'est pas installé. Ouvrez PowerShell en administrateur et lancez :  wsl --install\nPuis redémarrez Windows et rouvrez cet assistant."),
                    getTitle(), JOptionPane.INFORMATION_MESSAGE);
            case INSTALL_DOCKER -> installDocker();
            case START_DOCKER -> {
                if (!MusicBrainzMirror.isWindows()) {   // Linux : le service se démarre avec les droits administrateur, pas depuis l'application
                    JOptionPane.showMessageDialog(this, I18n.t("Docker est installé mais ne répond pas. Dans un terminal :\n  sudo systemctl enable --now docker\n  sudo usermod -aG docker $USER     (puis déconnexion / reconnexion)\nSi la commande « docker info » répond « permission denied », c'est le groupe docker qui manque."),
                            getTitle(), JOptionPane.INFORMATION_MESSAGE);
                    refresh();
                    break;
                }
                runAsync(I18n.t("Démarrage de Docker…"), () -> {
                boolean ok = MusicBrainzMirror.startDockerDesktop(180, this::append);
                append(ok ? "✔ Docker répond." : "✖ Docker ne répond pas encore. Vérifiez dans Docker Desktop : le moteur est démarré, ET Réglages → Resources → WSL integration → activez votre distribution (Ubuntu), puis recliquez.");
                return ok ? 0 : 1;
                });
            }
            case INSTALL_MIRROR -> installMirror();
        }
    }

    private void installDocker() {
        if (!MusicBrainzMirror.isWindows()) {
            JOptionPane.showMessageDialog(this, I18n.t(
                    "Docker est introuvable. Installez-le avec votre gestionnaire de paquets, par exemple :\n"
                  + "  sudo apt install docker.io docker-compose-v2\n  sudo systemctl enable --now docker\nPuis rouvrez cet assistant."),
                    getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!MusicBrainzMirror.wingetAvailable()) {
            JOptionPane.showMessageDialog(this, I18n.t(
                    "winget est introuvable. Téléchargez Docker Desktop sur docker.com, installez-le, activez l'intégration WSL pour votre distribution, puis rouvrez cet assistant."),
                    getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, I18n.t(
                "Installer Docker Desktop avec winget ?\nWindows demandera l'autorisation administrateur ; un redémarrage peut être nécessaire.\n\n"
              + "IMPORTANT : le disque virtuel de Docker est placé par défaut sur C:. Pour un miroir complet (jusqu'à 350 Go), déplacez-le "
              + "vers un lecteur assez grand (Docker Desktop → Réglages → Resources → Advanced → Disk image location)."),
                getTitle(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        runAsync(I18n.t("Installation de Docker Desktop…"), () -> {
            append("$ " + String.join(" ", MusicBrainzMirror.dockerInstallCommand()));
            Process p = new ProcessBuilder(MusicBrainzMirror.dockerInstallCommand()).redirectErrorStream(true).start();
            current.set(p);
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line; while ((line = r.readLine()) != null) append(line);
            }
            int code = p.waitFor();
            if (code == 0 || MusicBrainzMirror.wingetAlreadyInstalled(code)) {
                append(code == 0 ? "✔ Docker Desktop installé." : "✔ Docker Desktop est déjà installé (aucune mise à jour à faire).");
                append("Étape suivante : cliquez sur « Démarrer Docker » (un redémarrage de Windows peut d'abord être demandé).");
                return 0;
            }
            append("✖ L'installation a échoué (code " + code + ").");
            return code;
        });
    }

    // ── Installation complète ────────────────────────────────────────────────────────────────────────

    private void installMirror() {
        // BLOQUANT : si le disque où Docker écrit ses données est trop petit (cas typique : disque virtuel de Docker Desktop sur C:), l'import
        // — des dizaines de Go — remplirait le disque système. Pas de « continuer quand même » pour celui-là.
        var tooSmall = MusicBrainzMirror.checks(dir(), withSearch()).stream().filter(c -> c.id().equals("dockerdata") && !c.ok()).findFirst();
        if (tooSmall.isPresent()) {
            JOptionPane.showMessageDialog(this, I18n.t("Installation bloquée : l'espace pour les données de Docker est insuffisant.") + "\n\n" + tooSmall.get().detail(),
                    getTitle(), JOptionPane.ERROR_MESSAGE);
            return;
        }
        // Avertissements (non bloquants) : disque / mémoire sous les valeurs conseillées
        List<MusicBrainzMirror.Check> warn = MusicBrainzMirror.checks(dir(), withSearch()).stream()
                .filter(c -> !c.ok() && (c.id().equals("disk") || c.id().equals("ram"))).toList();
        if (!warn.isEmpty()) {
            StringBuilder sb = new StringBuilder(I18n.t("Attention :")).append('\n');
            for (var c : warn) sb.append("• ").append(I18n.t(c.label())).append(" — ").append(c.detail()).append('\n');
            sb.append('\n').append(I18n.t("Continuer quand même ?"));
            if (JOptionPane.showConfirmDialog(this, sb.toString(), getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        }
        if (JOptionPane.showConfirmDialog(this, I18n.t(
                "Installer le miroir MusicBrainz ?\nTéléchargement et import de dizaines de Go : plusieurs heures. Vous pouvez arrêter et reprendre plus tard : "
              + "les étapes terminées sont mémorisées."), getTitle(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        // Déclaration exigée par MetaBrainz avant le téléchargement : c'est la RÉPONSE DE L'UTILISATEUR, elle n'est jamais devinée.
        Object[] choices = { I18n.t("Non — usage personnel"), I18n.t("Oui — usage commercial"), I18n.t("Annuler") };
        int decl = JOptionPane.showOptionDialog(this, I18n.t("MetaBrainz fournit ces données et demande une déclaration avant le téléchargement :\n"
                + "« Prévoyez-vous d'utiliser ces données à des fins commerciales ou professionnelles ? »\n\n"
                + "Pour un usage commercial, MetaBrainz demande de devenir soutien financier (https://metabrainz.org/supporters/account-type).\n"
                + "Pour un usage personnel, un compte gratuit et un éventuel don (https://metabrainz.org/donate) sont encouragés."),
                getTitle(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        if (decl != 0 && decl != 1) return;
        final boolean commercial = decl == 1;
        Config.get().set("musicbrainz.mirror.dir", dir().toString());
        Config.get().set("musicbrainz.mirror.commercial", commercial ? "y" : "n");
        final boolean search = withSearch();
        runAsync(I18n.t("Installation du miroir…"), () -> {
            MusicBrainzMirror.Settings st = MusicBrainzMirror.realSettings();
            if (!MusicBrainzMirror.projectPresent(dir())) MusicBrainzMirror.fetchProject(dir(), this::append);
            for (MusicBrainzMirror.Step s : MusicBrainzMirror.steps(search, commercial)) {
                if (s.id().equals("status")) continue;
                if (MusicBrainzMirror.doneSteps(st).contains(s.id())) { append("✔ " + I18n.t(s.title()) + " : déjà fait."); continue; }
                SwingUtilities.invokeLater(() -> lblState.setText(I18n.t(s.title()) + "…"));
                for (String cmd : s.commands()) {
                    append("$ " + cmd);
                    int code = MusicBrainzMirror.run(cmd, dir(), this::append, current);
                    if (code != 0) {
                        append("✖ " + I18n.t(s.title()) + " : code de sortie " + code + " — arrêt. Corrigez la cause puis recliquez : on reprend à cette étape.");
                        return code;
                    }
                }
                MusicBrainzMirror.markDone(s.id(), st);
                append("✔ " + I18n.t(s.title()) + " : terminé.");
            }
            // Test automatique, puis activation si le miroir répond correctement
            MusicBrainzMirror.TestResult r = MusicBrainzMirror.test(MusicBrainzMirror.DEFAULT_URL);
            append((r.lookupOk() ? "✔ " : "✖ ") + r.message());
            if (r.lookupOk() && (r.searchOk() || !search)) {
                MusicBrainzMirror.activate(MusicBrainzMirror.DEFAULT_URL);
                append("✔ OpenTagger utilise maintenant le miroir (sans limite de débit).");
                return 0;
            }
            append("Le miroir n'est pas encore utilisable (l'import ou l'index peut être en cours). Réglages inchangés ; recliquez plus tard.");
            return 2;
        });
    }

    private interface Job { int run() throws Exception; }

    private void runAsync(String what, Job job) {
        if (running) return;
        running = true; busy.setVisible(true); stop.setVisible(true); main.setEnabled(false); lblState.setText(what);
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception { return job.run(); }
            @Override protected void done() {
                running = false; busy.setVisible(false); stop.setVisible(false);
                try { lblState.setText(get() == 0 ? I18n.t("Terminé.") : I18n.t("Arrêté — voir le journal.")); }
                catch (Exception ex) { lblState.setText(I18n.t("Échec — voir le journal.")); append("✖ " + (ex.getCause() != null ? ex.getCause() : ex)); }
                refresh();
            }
        }.execute();
    }
}
