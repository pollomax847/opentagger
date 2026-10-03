package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.ITunesCom;
import com.opentagger.ITunesReport;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * « Rapport iTunes » (Windows uniquement) — étape 1 d'une intégration façon Tune Sweeper : compare la
 * VRAIE bibliothèque iTunes (interface COM, voir {@link ITunesCom}) avec les fichiers chargés dans
 * OpenTagger. Deux listes : les pistes iTunes dont le fichier a disparu, et les fichiers chargés
 * qu'iTunes ne connaît pas. STRICTEMENT EN LECTURE SEULE : rien n'est modifié dans iTunes.
 */
public class ITunesReportPanel extends JPanel {

    private record Outcome(ITunesReport.ScanResult scan, List<Path> notInITunes, int loadedCount) {}

    private final FileTableModel tableModel;
    private final Runnable onClose;
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private final JButton btnStart  = new JButton(I18n.t("Lancer l'analyse"));
    private final JButton btnCancel = new JButton(I18n.t("Annuler"));
    private final JButton btnExport = new JButton(I18n.t("Exporter en CSV…"));
    private final JProgressBar bar  = new JProgressBar(0, 100);
    private final JLabel lblStatus  = new JLabel(" ");
    private final JLabel lblSummary = new JLabel(" ");
    private final JTabbedPane tabs  = new JTabbedPane();

    private final DefaultTableModel deadModel = nonEditable(
            I18n.t("Artiste"), I18n.t("Titre"), I18n.t("Album"), I18n.t("Chemin enregistré dans iTunes"));
    private final DefaultTableModel missingModel = nonEditable(I18n.t("Fichier"), I18n.t("Dossier"));
    private SwingWorker<Outcome, Integer> worker;

    /** Onglet de {@link LibraryReportsDialog} (Windows uniquement). */
    public ITunesReportPanel(MainFrame owner, FileTableModel tableModel, Runnable onClose) {
        this.tableModel = tableModel;
        this.onClose    = onClose;

        JPanel content = this;
        setLayout(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(12, 14, 10, 14));

        JLabel info = new JLabel("<html>" + I18n.t(
                "<b>Lecture seule</b> : cette analyse ne modifie rien dans iTunes. Elle interroge votre "
              + "bibliothèque iTunes réelle (comme Tune Sweeper) et la compare aux fichiers actuellement "
              + "chargés dans OpenTagger. Compter environ 20 à 30 ms par piste (40 à 50 minutes "
              + "pour 100 000 pistes) ; iTunes doit rester ouvert.") + "</html>");
        content.add(info, BorderLayout.NORTH);

        tabs.addTab(I18n.t("Pistes manquantes"), new JScrollPane(new JTable(deadModel)));
        tabs.addTab(I18n.t("Fichiers absents d'iTunes"), new JScrollPane(new JTable(missingModel)));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(lblSummary, BorderLayout.NORTH);
        center.add(tabs, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        bar.setStringPainted(true);
        JPanel south = new JPanel(new BorderLayout(8, 4));
        JPanel progress = new JPanel(new BorderLayout(6, 0));
        progress.add(bar, BorderLayout.CENTER);
        progress.add(lblStatus, BorderLayout.SOUTH);
        south.add(progress, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnCancel.setEnabled(false);
        btnExport.setEnabled(false);
        buttons.add(btnStart);
        buttons.add(btnCancel);
        buttons.add(btnExport);
        JButton close = new JButton(I18n.t("Fermer"));
        close.addActionListener(e -> onClose.run());
        buttons.add(close);
        south.add(buttons, BorderLayout.EAST);
        content.add(south, BorderLayout.SOUTH);

        btnStart.addActionListener(e -> start());
        btnCancel.addActionListener(e -> { cancel.set(true); lblStatus.setText(I18n.t("Annulation…")); });
        btnExport.addActionListener(e -> exportCsv());
    }

    /** Quand la fenêtre du hub se ferme, ses composants sont détachés : on arrête alors l'analyse
     *  en cours (le processus PowerShell est tué par ITunesCom.scan). Changer d'onglet ne passe
     *  PAS par ici — l'analyse continue en arrière-plan. */
    @Override public void removeNotify() {
        cancel.set(true);
        super.removeNotify();
    }

    private static DefaultTableModel nonEditable(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private void start() {
        if (!ITunesCom.isAvailable()) {
            JOptionPane.showMessageDialog(this, I18n.t(
                    "iTunes (interface COM) est introuvable sur cet ordinateur. Cette fonction nécessite iTunes sous Windows."),
                    I18n.t("Rapport iTunes"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Instantané des chemins chargés, pris sur l'EDT (la liste appartient au modèle de la table).
        List<Path> loaded = new ArrayList<>();
        for (FileEntry e : tableModel.allEntries()) {
            loaded.add(e.currentPath != null ? e.currentPath : e.file.toPath());
        }
        cancel.set(false);
        btnStart.setEnabled(false);
        btnCancel.setEnabled(true);
        btnExport.setEnabled(false);
        deadModel.setRowCount(0);
        missingModel.setRowCount(0);
        lblSummary.setText(" ");
        bar.setValue(0);
        lblStatus.setText(I18n.t("Analyse de la bibliothèque iTunes en cours…"));

        worker = new SwingWorker<>() {
            @Override protected Outcome doInBackground() throws Exception {
                ITunesReport.ScanResult r = ITunesCom.scan(p -> publish(p), cancel);
                return new Outcome(r, ITunesReport.notInITunes(loaded, r.locations()), loaded.size());
            }
            @Override protected void process(List<Integer> chunks) {
                int p = chunks.get(chunks.size() - 1);
                bar.setValue(p);
                lblStatus.setText(I18n.t("Analyse de la bibliothèque iTunes… %d %%", p));
            }
            @Override protected void done() {
                btnStart.setEnabled(true);
                btnCancel.setEnabled(false);
                try {
                    show(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    lblStatus.setText(c.getMessage() != null ? c.getMessage() : c.toString());
                    bar.setValue(0);
                }
            }
        };
        worker.execute();
    }

    private void show(Outcome o) {
        ITunesReport.ScanResult r = o.scan();
        for (ITunesReport.DeadTrack d : r.dead()) {
            deadModel.addRow(new Object[]{d.artist(), d.title(), d.album(),
                    d.location().isEmpty() ? I18n.t("(fichier introuvable — chemin non fourni par iTunes)") : d.location()});
        }
        for (Path p : o.notInITunes()) {
            Path parent = p.getParent();
            missingModel.addRow(new Object[]{p.getFileName() != null ? p.getFileName().toString() : p.toString(),
                    parent != null ? parent.toString() : ""});
        }
        tabs.setTitleAt(0, I18n.t("Pistes manquantes (%d)", r.dead().size()));
        tabs.setTitleAt(1, I18n.t("Fichiers absents d'iTunes (%d)", o.notInITunes().size()));
        String loadedNote = o.loadedCount() == 0
                ? "  " + I18n.t("Aucun fichier chargé dans OpenTagger : chargez votre dossier musique pour comparer.")
                : "";
        lblSummary.setText(I18n.t("iTunes : %d pistes, dont %d fichiers locaux — %d à fichier manquant — "
                + "%d fichiers chargés sur %d absents d'iTunes.",
                r.totalTracks(), r.fileTracks(), r.dead().size(), o.notInITunes().size(), o.loadedCount())
                + loadedNote);
        lblStatus.setText(r.errors() > 0
                ? I18n.t("Terminé — %d piste(s) illisible(s) ignorée(s).", r.errors())
                : I18n.t("Terminé."));
        bar.setValue(100);
        btnExport.setEnabled(true);
    }

    private void exportCsv() {
        boolean dead = tabs.getSelectedIndex() == 0;
        DefaultTableModel m = dead ? deadModel : missingModel;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(dead ? "itunes_pistes_manquantes.csv" : "itunes_fichiers_absents.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (Writer w = Files.newBufferedWriter(fc.getSelectedFile().toPath(), StandardCharsets.UTF_8)) {
            w.write('﻿'); // BOM : Excel lit correctement les accents
            StringBuilder h = new StringBuilder();
            for (int c = 0; c < m.getColumnCount(); c++) h.append(c > 0 ? ";" : "").append(csv(m.getColumnName(c)));
            w.write(h + "\r\n");
            for (int r = 0; r < m.getRowCount(); r++) {
                StringBuilder row = new StringBuilder();
                for (int c = 0; c < m.getColumnCount(); c++) row.append(c > 0 ? ";" : "").append(csv(String.valueOf(m.getValueAt(r, c))));
                w.write(row + "\r\n");
            }
            lblStatus.setText(I18n.t("Exporté : %s", fc.getSelectedFile().getAbsolutePath()));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), I18n.t("Rapport iTunes"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csv(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }
}
