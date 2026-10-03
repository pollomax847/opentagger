package com.opentagger.ui;

import com.opentagger.ITunesCom;
import com.opentagger.I18n;

import javax.swing.*;
import java.awt.*;

/**
 * « Rapports de la bibliothèque » : UNE fenêtre, des onglets — Non identifiés (par cause), Complétude des
 * tags, Compilations restaurées et, sous Windows seulement, iTunes (pistes manquantes / fichiers absents).
 *
 * <p>Fusion du 2026-10-03 (retour utilisateur : « trop d'options gâchent l'application », le sous-menu
 * Rapports en comptait 10) : ces rapports avaient la même forme (un tableau, un pied de page
 * Rafraîchir/Exporter/Fermer) et occupaient chacun une entrée de menu et une fenêtre. Même précédent que
 * {@link SuspectFilesReviewDialog}. Chaque panneau a gardé exactement son comportement.
 */
public class LibraryReportsDialog extends JDialog {

    private static LibraryReportsDialog instance;

    /** Une seule fenêtre à la fois : ré-ouvre celle qui existe (et relit l'état en mémoire). */
    public static void open(MainFrame owner, FileTableModel tableModel) {
        if (instance != null && instance.isDisplayable()) {
            instance.refreshCurrent();
            instance.setVisible(true);
            instance.toFront();
            return;
        }
        instance = new LibraryReportsDialog(owner, tableModel);
        instance.setVisible(true);
    }

    private final JTabbedPane                  tabs = new JTabbedPane();
    private final NonIdentifiedReportPanel     nonIdentified;
    private final CompletenessReportPanel      completeness;
    private final CompilationRestoreReportPanel compilations;

    private LibraryReportsDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Rapports de la bibliothèque"), false);
        setSize(920, 560);
        setMinimumSize(new Dimension(700, 420));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        Runnable close = this::dispose;
        nonIdentified = new NonIdentifiedReportPanel(owner, tableModel, close);
        completeness  = new CompletenessReportPanel(owner, tableModel, close);
        compilations  = new CompilationRestoreReportPanel(close);
        tabs.addTab(I18n.t("Non identifiés"), nonIdentified);
        tabs.addTab(I18n.t("Complétude"), completeness);
        tabs.addTab(I18n.t("Compilations restaurées"), compilations);
        // Interface COM d'iTunes : Windows uniquement — l'onglet n'existe pas ailleurs.
        if (ITunesCom.isWindows()) {
            tabs.addTab("iTunes", new ITunesReportPanel(owner, tableModel, close));
        }
        // Ces trois rapports se relisent en mémoire : les rafraîchir à chaque retour sur leur onglet est gratuit.
        tabs.addChangeListener(e -> refreshCurrent());
        getContentPane().add(tabs, BorderLayout.CENTER);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); }
        });
    }

    private void refreshCurrent() {
        Component c = tabs.getSelectedComponent();
        if (c == nonIdentified) nonIdentified.refresh();
        else if (c == completeness) completeness.refresh();
        else if (c == compilations) compilations.refresh();
        // L'onglet iTunes n'est volontairement jamais relancé tout seul : l'analyse est longue.
    }
}
