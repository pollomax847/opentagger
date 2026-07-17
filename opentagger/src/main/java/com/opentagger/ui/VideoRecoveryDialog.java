package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.VideoScanner;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * "Récupérer l'audio des vidéos non reconnues" — choisit un dossier (pré-rempli avec
 * {@code skipped.move_folder} s'il est configuré), y cherche les vidéos via {@link VideoScanner},
 * puis lance {@link VideoRecoveryWorker} sur la liste trouvée. Même structure que
 * {@link PodcastDialog} en plus simple : pas de matching à valider, chaque vidéo est traitée
 * indépendamment (reconnue → convertie/taguée/rangée, vidéo supprimée ; non reconnue → intacte).
 */
public class VideoRecoveryDialog extends JDialog {

    private static final Logger LOG = Logger.getLogger(VideoRecoveryDialog.class.getName());

    private final JTextField   tfFolder    = new JTextField(40);
    private final JButton      btnBrowse   = new JButton(I18n.t("Parcourir…"));
    private final JButton      btnScan     = new JButton(I18n.t("Chercher des vidéos"));
    private final DefaultListModel<File> lstModel = new DefaultListModel<>();
    private final JList<File>  lstVideos   = new JList<>(lstModel);
    private final JScrollPane  scrollList;
    private final JLabel       lblStatus   = new JLabel(" ");
    private final JTextArea    taLog       = new JTextArea(8, 60);
    private final JScrollPane  scrollLog;
    private final JProgressBar progress    = new JProgressBar();
    private final JButton      btnStart    = new JButton(I18n.t("Lancer"));
    private final JButton      btnClose    = new JButton(I18n.t("Fermer"));

    // TaskHandle plutôt qu'un champ VideoRecoveryWorker direct : ce dialogue et
    // MainFrame.autoRecoverVideos() partagent le même TaskKind.VIDEO_RECOVERY sur WorkerHub — avant
    // ce correctif, chacun avait son propre champ indépendant, si bien qu'une récupération lancée
    // depuis ce dialogue puis "laissée tourner" (fermeture sans attendre, cf. doc de classe) pouvait
    // tourner en même temps qu'une seconde déclenchée automatiquement sur un autre dossier.
    private WorkerHub.TaskHandle recoveryHandle;

    public VideoRecoveryDialog(Frame owner) {
        super(owner, I18n.t("Récupérer l'audio des vidéos non reconnues"), true);

        String defaultFolder = Config.get().skippedMoveFolder();
        if (!defaultFolder.isBlank() && new File(defaultFolder).isDirectory()) {
            tfFolder.setText(defaultFolder);
        }

        lstVideos.setVisibleRowCount(8);
        scrollList = new JScrollPane(lstVideos);
        scrollList.setPreferredSize(new Dimension(560, 140));

        taLog.setEditable(false);
        taLog.setLineWrap(true);
        scrollLog = new JScrollPane(taLog);
        scrollLog.setPreferredSize(new Dimension(560, 160));

        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(620, 480));
        setLocationRelativeTo(owner);
        wireEvents();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { onClose(); } });
    }

    // ── Builders UI ───────────────────────────────────────────────────────────

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        root.add(buildFolderPanel(), BorderLayout.NORTH);
        root.add(buildCenterPanel(), BorderLayout.CENTER);
        root.add(buildFooter(),      BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildFolderPanel() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(new JLabel(I18n.t("Dossier : ")));
        row.add(tfFolder);
        row.add(btnBrowse);
        row.add(btnScan);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(row);

        JLabel hint = new JLabel(I18n.t(
            "  Cherche les .webm/.vob/.mpg/.mpeg/.avi/.mkv du dossier. Chaque vidéo reconnue est"
          + " convertie en MP3, taguée, rangée, puis déplacée dans un sous-dossier \"Convertis\" ;"
          + " les non reconnues sont déplacées dans \"Non identifié\"."));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        p.add(hint);
        return p;
    }

    private JPanel buildCenterPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

        JLabel lblFound = new JLabel(I18n.t("Vidéos trouvées"));
        lblFound.putClientProperty("FlatLaf.style", "font: bold 12 $defaultFont");
        p.add(lblFound);
        p.add(Box.createVerticalStrut(4));
        p.add(scrollList);
        p.add(Box.createVerticalStrut(4));
        p.add(lblStatus);

        p.add(Box.createVerticalStrut(10));
        JLabel lblLog = new JLabel(I18n.t("Résultat"));
        lblLog.putClientProperty("FlatLaf.style", "font: bold 12 $defaultFont");
        p.add(lblLog);
        p.add(Box.createVerticalStrut(4));
        p.add(scrollLog);
        return p;
    }

    private JPanel buildFooter() {
        progress.setIndeterminate(false);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(200, 14));

        btnStart.setEnabled(false);
        btnStart.putClientProperty("FlatLaf.style", "background: #1b5e20");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(progress);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnStart);
        right.add(btnClose);

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 0, 0, 0)));
        p.add(left,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ── Événements ────────────────────────────────────────────────────────────

    private void wireEvents() {
        btnBrowse.addActionListener(e -> onBrowse());
        btnScan  .addActionListener(e -> onScan());
        btnStart .addActionListener(e -> onStart());
        btnClose .addActionListener(e -> onClose());
    }

    private void onBrowse() {
        JFileChooser fc = new JFileChooser(
                tfFolder.getText().isBlank() ? null : new File(tfFolder.getText()));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        tfFolder.setText(fc.getSelectedFile().getAbsolutePath());
    }

    private void onScan() {
        String path = tfFolder.getText().trim();
        File dir = new File(path);
        if (path.isBlank() || !dir.isDirectory()) {
            lblStatus.setText(I18n.t("Dossier invalide."));
            return;
        }

        lstModel.clear();
        btnStart.setEnabled(false);
        lblStatus.setText(I18n.t("Recherche en cours…"));
        btnScan.setEnabled(false);

        new SwingWorker<List<File>, Void>() {
            @Override protected List<File> doInBackground() {
                return new VideoScanner().scan(dir);
            }
            @Override protected void done() {
                btnScan.setEnabled(true);
                try {
                    List<File> found = get();
                    found.forEach(lstModel::addElement);
                    lblStatus.setText(found.isEmpty()
                        ? I18n.t("Aucune vidéo trouvée dans ce dossier.")
                        : I18n.t("%d vidéo(s) trouvée(s).", found.size()));
                    btnStart.setEnabled(!found.isEmpty());
                } catch (Exception ex) {
                    lblStatus.setText(I18n.t("Erreur : %s", ex.getMessage()));
                    LOG.warning("[VideoRecovery] Scan : " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void onStart() {
        if (lstModel.isEmpty()) return;
        if (WorkerHub.get().current(WorkerHub.TaskKind.VIDEO_RECOVERY).isPresent()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Une récupération vidéo est déjà en cours (déclenchée automatiquement ou "
                     + "depuis une autre fenêtre)."),
                I18n.t("Récupération vidéo"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        java.util.List<File> videos = java.util.Collections.list(lstModel.elements());
        int ok = JOptionPane.showConfirmDialog(this,
            I18n.t("Identifier %d vidéo(s) ? Les reconnues seront converties/taguées puis déplacées\n"
                 + "dans \"Convertis\" ; les non reconnues seront déplacées dans \"Non identifié\".",
                 videos.size()),
            I18n.t("Confirmer"), JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;

        btnStart.setEnabled(false);
        btnScan.setEnabled(false);
        progress.setIndeterminate(true);
        progress.setVisible(true);
        taLog.setText("");

        Path scanRoot = java.nio.file.Paths.get(tfFolder.getText().trim());
        // onProgress est appelé depuis SwingWorker.process(), déjà garanti sur l'EDT — pas besoin
        // d'un invokeLater supplémentaire ici.
        VideoRecoveryWorker w = new VideoRecoveryWorker(videos, scanRoot, msg -> taLog.append(msg + "\n"));
        w.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                progress.setIndeterminate(false);
                progress.setVisible(false);
                btnScan.setEnabled(true);
                lblStatus.setText(I18n.t("Terminé — %d converti(s), %d non reconnu(s), %d erreur(s).",
                        w.getConverted(), w.getUnrecognized(), w.getErrors()));
                onScan();
            }
        });
        recoveryHandle = WorkerHub.get().submit(WorkerHub.TaskKind.VIDEO_RECOVERY,
                I18n.t("Récupération vidéo : %s", scanRoot.getFileName()), w, w::stopNow);
    }

    private void onClose() {
        // Seulement SI c'est bien l'instance lancée PAR ce dialogue qui tourne encore — pas une
        // récupération sans rapport déclenchée automatiquement ailleurs entre-temps.
        if (recoveryHandle != null && recoveryHandle.isRunning()) recoveryHandle.cancel();
        dispose();
    }
}
