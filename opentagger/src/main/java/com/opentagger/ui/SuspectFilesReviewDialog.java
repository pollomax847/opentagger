package com.opentagger.ui;

import com.opentagger.I18n;

import javax.swing.*;
import java.awt.*;

/**
 * « Revue des fichiers suspects » : UNE fenêtre, deux onglets — les fichiers dont la durée ne colle pas à
 * l'enregistrement MusicBrainz (DurationMismatchReviewPanel) et ceux dont l'audio ne correspond pas aux tags
 * (AudioTagAuditPanel).
 *
 * <p>Fusion demandée par l'utilisateur le 2026-09-20 (« il commence à avoir trop d'outils dans l'appli ») : les deux
 * outils avaient la même forme — une pile de fichiers suspects, à écouter puis re-taguer / corriger à la main — et
 * vivaient dans deux entrées de menu et deux fenêtres. Le menu Rapports perd une entrée par rapport à avant l'ajout de
 * l'audit ; les deux panneaux ont gardé exactement leur comportement.
 */
public class SuspectFilesReviewDialog extends JDialog {

    private static SuspectFilesReviewDialog instance;

    private static final int TAB_DURATION = 0, TAB_AUDIO = 1;

    /** Une seule fenêtre à la fois : ré-ouvre celle qui existe (le panneau d'audit relit un état global). */
    public static void show(MainFrame owner, FileTableModel tableModel) {
        if (instance != null && instance.isDisplayable()) {
            instance.duration.load();
            instance.audit.onReopened();
            instance.selectDefaultTab();
            instance.setVisible(true);
            instance.toFront();
            return;
        }
        instance = new SuspectFilesReviewDialog(owner, tableModel);
        instance.selectDefaultTab();
        instance.setVisible(true);
    }

    private final DurationMismatchReviewPanel duration;
    private final AudioTagAuditPanel          audit;
    private final JTabbedPane                 tabs = new JTabbedPane();

    private SuspectFilesReviewDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Revue des fichiers suspects"), false);
        setSize(1290, 600);
        setMinimumSize(new Dimension(860, 420));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        duration = new DurationMismatchReviewPanel(owner, tableModel, this::dispose);
        audit    = new AudioTagAuditPanel(owner, tableModel, this::dispose);
        tabs.addTab(I18n.t("Durées incohérentes"), duration);
        tabs.addTab(I18n.t("Audio ↔ tags"), audit);
        // Le lot « durées » se relit en mémoire : le rafraîchir à chaque retour sur son onglet est gratuit.
        tabs.addChangeListener(e -> { if (tabs.getSelectedIndex() == TAB_DURATION) duration.load(); });
        getContentPane().add(tabs, BorderLayout.CENTER);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); }
        });
    }

    /** Ouvre directement sur l'audit tant qu'un audit tourne (c'est presque sûrement ce que l'on vient voir),
     *  sinon sur la revue des durées, historiquement première. */
    private void selectDefaultTab() {
        boolean auditRunning = WorkerHub.get().current(WorkerHub.TaskKind.AUDIO_AUDIT).isPresent();
        tabs.setSelectedIndex(auditRunning ? TAB_AUDIO : TAB_DURATION);
    }
}
