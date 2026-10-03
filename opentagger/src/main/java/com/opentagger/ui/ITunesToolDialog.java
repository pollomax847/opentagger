package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.ITunesCom;

import javax.swing.*;
import java.awt.*;

/**
 * « iTunes » : UN seul outil, une seule entrée de menu (Import / Export), pour tout ce qui touche à iTunes.
 * Deux onglets : « Analyse de la bibliothèque » (la VRAIE bibliothèque iTunes par son interface COM, comme
 * Tune Sweeper — Windows uniquement, l'onglet n'existe pas ailleurs) et « Fichier XML » (importer,
 * écrire les corrections, reconstruire un export — Windows et Linux).
 *
 * <p>Regroupement du 2026-10-03 (retour utilisateur : « l'outil iTunes doit être tout regroupé », et
 * « trop d'options gâchent l'application ») : trois entrées « XML iTunes » du menu Import / Export et un
 * rapport iTunes dans le menu Rapports se retrouvent ici. Les prochaines actions sur la vraie
 * bibliothèque (réparer un lien, ajouter des pistes…) viendront dans l'onglet d'analyse.
 */
public class ITunesToolDialog extends JDialog {

    private static ITunesToolDialog instance;

    /** Une seule fenêtre à la fois : ré-ouvre celle qui existe (une analyse peut y être en cours). */
    public static void open(MainFrame owner, FileTableModel tableModel) {
        if (instance != null && instance.isDisplayable()) {
            instance.xml.refresh();
            instance.setVisible(true);
            instance.toFront();
            return;
        }
        instance = new ITunesToolDialog(owner, tableModel);
        instance.setVisible(true);
    }

    private final ITunesXmlPanel xml;

    private ITunesToolDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, "iTunes", false);
        setSize(920, 580);
        setMinimumSize(new Dimension(700, 440));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        Runnable close = this::dispose;
        xml = new ITunesXmlPanel(owner, tableModel);
        JTabbedPane tabs = new JTabbedPane();
        // Interface COM d'iTunes : Windows uniquement — l'onglet n'existe tout simplement pas sous Linux.
        if (ITunesCom.isWindows()) {
            tabs.addTab(I18n.t("Analyse de la bibliothèque"), new ITunesReportPanel(owner, tableModel, close));
        }
        tabs.addTab(I18n.t("Fichier XML"), xml);
        tabs.addChangeListener(e -> { if (tabs.getSelectedComponent() == xml) xml.refresh(); });
        getContentPane().add(tabs, BorderLayout.CENTER);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); }
        });
    }
}
