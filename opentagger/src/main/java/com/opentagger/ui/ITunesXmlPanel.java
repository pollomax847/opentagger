package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.ITunesXmlSyncQueue;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Onglet « Fichier XML » de {@link ITunesToolDialog} : regroupe les trois actions sur le fichier
 * « iTunes Music Library.xml » qui occupaient chacune une entrée du menu Import / Export (importer,
 * écrire les corrections, reconstruire un export complet). Chaque bouton lance exactement la même
 * action qu'avant — rien n'est modifié dans ces fonctions.
 */
public class ITunesXmlPanel extends JPanel {

    private final JLabel lblPending = new JLabel(" ");

    public ITunesXmlPanel(MainFrame owner, FileTableModel tableModel) {
        setLayout(new BorderLayout(0, 10));
        setBorder(new EmptyBorder(14, 16, 12, 16));

        JLabel info = new JLabel("<html>" + I18n.t(
                "Le fichier XML d'iTunes n'est qu'un export : iTunes le régénère depuis sa vraie base. "
              + "Le modifier ne change donc pas la bibliothèque réelle d'iTunes (pour cela, utilisez l'onglet "
              + "« Analyse de la bibliothèque », sous Windows).") + "</html>");
        add(info, BorderLayout.NORTH);

        JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.add(section(I18n.t("Importer un XML iTunes…"),
                I18n.t("Charge dans OpenTagger les fichiers d'une bibliothèque iTunes à partir de son fichier XML."),
                () -> new ITunesImportDialog(owner, tableModel).setVisible(true), null));
        sections.add(Box.createVerticalStrut(10));
        sections.add(section(I18n.t("Écrire les corrections dans le XML…"),
                I18n.t("Reporte dans le fichier XML les renommages et les notes faits dans OpenTagger sur des "
                     + "fichiers importés depuis iTunes. Une sauvegarde horodatée est créée avant toute écriture."),
                owner::writeItunesXmlCorrections, lblPending));
        sections.add(Box.createVerticalStrut(10));
        sections.add(section(I18n.t("Reconstruire un export XML complet…"),
                I18n.t("Régénère un export XML complet depuis l'état actuel d'OpenTagger, dans un fichier séparé "
                     + "(jamais celui qu'iTunes régénère lui-même)."),
                owner::exportFullItunesXml, null));
        add(sections, BorderLayout.CENTER);
        refresh();
    }

    /** Relit le nombre de corrections en attente (appelé quand on revient sur l'onglet). */
    void refresh() {
        int pending = ITunesXmlSyncQueue.pendingCount();
        lblPending.setText(pending == 0
                ? I18n.t("Aucune correction en attente.")
                : I18n.t("%d correction(s) en attente.", pending));
    }

    private static JPanel section(String title, String description, Runnable action, JLabel extra) {
        JButton btn = new JButton(title);
        btn.addActionListener(e -> action.run());
        JLabel desc = new JLabel("<html><body style='width:560px'>" + description + "</body></html>");
        desc.putClientProperty("FlatLaf.style", "foreground: #9AA5B1");
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), new EmptyBorder(10, 12, 10, 12)));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        top.setOpaque(false);
        top.add(btn);
        if (extra != null) { top.add(Box.createHorizontalStrut(12)); top.add(extra); }
        p.add(top, BorderLayout.NORTH);
        p.add(desc, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }
}
