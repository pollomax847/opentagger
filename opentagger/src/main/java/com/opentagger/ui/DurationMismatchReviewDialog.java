package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Passe en revue le tas de fichiers "durée incohérente" (déplacés vers
 * {@code duration_mismatch.move_folder} par TaggingWorker/TagEnrichment — voir
 * FileEntry.isDurationMismatch()) — demande utilisateur directe (2026-08-31), après avoir remarqué
 * qu'un fichier bien réel (David Guetta - Baby When the Light (UK Mix), 6:51) se trouvait dans ce
 * tas, doute légitime sur le taux de faux positifs.
 *
 * Constat qui motive le tri par SENS de l'écart (voir FileEntry.isDurationMismatch()) : un fichier
 * PLUS COURT que la référence MusicBrainz (diagnostic serré : >20s ET >20% d'écart) est presque
 * toujours un vrai extrait/téléchargement partiel — un rip cassé ne produit jamais plus de données
 * que prévu. Un fichier PLUS LONG (diagnostic plus tolérant : >30s ET >50% d'écart) est bien plus
 * souvent un problème de CANDIDAT MusicBrainz — le bon mix/remix (ex. "UK mix", 6:51) n'a simplement
 * pas été proposé parmi les résultats de recherche, et un mix plus court (édition radio, etc.) a été
 * comparé à tort. D'où le seuil de tri ci-dessous : ce n'est pas un jugement automatique fiable
 * (juste une heuristique de tri), donc AUCUNE suppression automatique n'est jamais proposée pour le
 * lot "à revérifier" — seule une revue manuelle (réutilise {@link MatchDialog}, la même fenêtre de
 * correspondance manuelle que partout ailleurs dans l'appli) peut confirmer ou infirmer.
 *
 * Ne rescanne PAS le disque : lit directement {@code tableModel.allEntries()}, donc ne montre que
 * les fichiers déjà connus du tableau principal (typiquement déjà vrai en pratique — le dossier de
 * déplacement fait normalement partie de {@code startup.folders}). Si rien n'apparaît, c'est que le
 * scan de bibliothèque n'a pas encore atteint ce dossier cette session — pas une erreur, juste un
 * "revenir plus tard".
 */
public class DurationMismatchReviewDialog extends JDialog {

    // En dessous de ce seuil, un fichier plus court que prévu est un extrait/téléchargement cassé
    // avec une confiance suffisante pour proposer une suppression groupée — voir le commentaire de
    // classe pour le raisonnement complet. Au-dessus, toujours proposé en revue manuelle seulement.
    private static final int SHORT_THRESHOLD_SEC = 90;

    private static final String[] COLS =
        {I18n.t("Catégorie"), I18n.t("Fichier"), I18n.t("Durée"), I18n.t("Artiste (tag actuel)"),
         I18n.t("Titre (tag actuel)"), I18n.t("Détail")};

    private final MainFrame        owner;
    private final FileTableModel   tableModel;
    private final JTable           table;
    private final DefaultTableModel model;
    private final JLabel           lblCount = new JLabel();
    private final List<FileEntry>  rowEntries = new ArrayList<>();

    public DurationMismatchReviewDialog(MainFrame owner, FileTableModel tableModel) {
        super(owner, I18n.t("Durées incohérentes — revue"), false);
        this.owner      = owner;
        this.tableModel = tableModel;
        setSize(900, 480);
        setMinimumSize(new Dimension(640, 320));
        setLocationRelativeTo(owner);

        model = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        TableColumnModel cm = table.getColumnModel();
        setColWidth(cm, 0, 130, 100, 160); // Catégorie
        setColWidth(cm, 1, 220, 120, 500); // Fichier
        setColWidth(cm, 2, 70,  60,  90);  // Durée
        setColWidth(cm, 3, 140, 80,  260); // Artiste
        setColWidth(cm, 4, 160, 80,  300); // Titre
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) return;
                openMatchDialog(table.convertRowIndexToModel(row));
            }
        });

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        load();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    private void load() {
        String folder = Config.get().durationMismatchMoveFolder();
        Path root = folder == null || folder.isBlank() ? null : Paths.get(folder).toAbsolutePath();

        model.setRowCount(0);
        rowEntries.clear();
        int shortCount = 0, longCount = 0;
        if (root != null) {
            for (FileEntry e : tableModel.allEntries()) {
                Path p = (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath();
                if (!p.startsWith(root)) continue;
                int dur = e.current != null ? e.current.durationSec : 0;
                boolean probablyBroken = dur > 0 && dur < SHORT_THRESHOLD_SEC;
                String cat = probablyBroken ? I18n.t("Probablement cassé") : I18n.t("À revérifier");
                if (probablyBroken) shortCount++; else longCount++;
                rowEntries.add(e);
                model.addRow(new Object[]{
                    cat, p.getFileName().toString(), FileTableModel.formatDuration(dur),
                    e.current != null ? e.current.artist : "",
                    e.current != null ? e.current.title  : "",
                    e.message != null ? e.message : ""
                });
            }
        }
        lblCount.setText(root == null
            ? I18n.t("Aucun dossier de déplacement configuré (Préférences → Durée incohérente).")
            : I18n.t("%d fichier(s) — %d probablement cassé(s), %d à revérifier manuellement",
                    rowEntries.size(), shortCount, longCount));
    }

    private void openMatchDialog(int modelRow) {
        if (modelRow < 0 || modelRow >= rowEntries.size()) return;
        FileEntry entry = rowEntries.get(modelRow);
        new MatchDialog(owner, entry, tableModel, () -> {
            // MatchDialog applique déjà entry.status=IDENTIFIED + tableModel.update(entry) — un
            // Enregistrer normal depuis la fenêtre principale écrira le tag et ressortira le
            // fichier de ce dossier. Ici, juste rafraîchir l'affichage (nouvel artiste/titre choisi
            // visible immédiatement) et rappeler la marche à suivre.
            load();
            owner.setStatus(I18n.t("Correspondance choisie pour %s — utilisez Enregistrer dans la "
                    + "fenêtre principale pour l'écrire sur le fichier.", entry.filename()));
        }).setVisible(true);
    }

    private JPanel buildFooter() {
        JButton btnMatch   = new JButton(I18n.t("Réidentifier…"));
        // Ajouté 2026-09-02 (retour utilisateur : "je peux pas rescanner", plusieurs centaines de
        // lignes dans ce lot) — jusqu'ici la seule façon de corriger une entrée était "Réidentifier…"
        // (correspondance manuelle, UNE ligne à la fois) : impraticable à cette échelle. Route vers
        // le même "Forcer le re-taguage" que la fenêtre principale (SongRec/AcoustID réels, pas une
        // recherche manuelle) via MainFrame.forceRetagOn() — voir son commentaire.
        JButton btnForce   = new JButton(I18n.t("Forcer le re-taguage…"));
        JButton btnTrash   = new JButton(I18n.t("Envoyer la sélection à la corbeille"));
        JButton btnRefresh = new JButton(I18n.t("Rafraîchir"));
        JButton btnClose   = new JButton(I18n.t("Fermer"));
        btnMatch.setToolTipText(I18n.t("Ouvre la correspondance manuelle (double-clic sur une ligne fait la même chose)"));
        btnForce.setToolTipText(I18n.t("Relance une identification complète (SongRec/AcoustID) sur la sélection, ou sur tout le lot si rien n'est sélectionné"));
        btnTrash.setToolTipText(I18n.t("Déplace les fichiers sélectionnés vers la corbeille système — jamais de suppression définitive"));
        btnMatch.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez une ligne.")); return; }
            openMatchDialog(table.convertRowIndexToModel(row));
        });
        btnForce.addActionListener(e -> forceRetagSelection());
        btnTrash.addActionListener(e -> trashSelected());
        btnRefresh.addActionListener(e -> load());
        btnClose.addActionListener(e -> dispose());

        // Empilé (texte au-dessus, boutons dans leur propre rangée en dessous) plutôt que côte à
        // côte (WEST=texte / EAST=boutons) — trouvé en direct 2026-09-01 (capture d'écran
        // utilisateur) : un texte d'aide un peu long dans WEST poussait la rangée de boutons EAST
        // hors des limites visibles de la fenêtre (taille fixe via setSize(), jamais de pack()/
        // redimensionnement automatique) — "Réidentifier…", premier bouton de la rangée, disparaissait
        // purement et simplement, sans qu'aucune erreur ni redimensionnement ne le signale. Cette
        // disposition garantit que la largeur du texte n'affecte plus jamais la visibilité des boutons,
        // quelle que soit sa longueur.
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        JLabel hint = new JLabel(I18n.t("Double-clic : correspondance manuelle — jamais de suppression automatique du lot \"à revérifier\""));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(lblCount, BorderLayout.NORTH);
        top.add(hint,     BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(btnMatch);
        buttons.add(btnForce);
        buttons.add(btnTrash);
        buttons.add(btnRefresh);
        buttons.add(btnClose);
        p.add(top,     BorderLayout.NORTH);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    /** Sélection si ≥1 ligne, sinon tout le lot affiché — même sémantique "sélection ou tout" que
     *  forceRetag() côté fenêtre principale. La confirmation et le lancement du taguage vivent dans
     *  MainFrame.forceRetagOn() (garde WorkerHub, dialogue de confirmation, log [OT]) : cette
     *  méthode ne fait que rassembler la liste à partir de rowEntries/model, pas dupliquer cette
     *  logique. Ferme la fenêtre après lancement : les lignes visées vont repasser en PENDING, donc
     *  ce tableau devient obsolète immédiatement — mieux vaut suivre la progression dans la fenêtre
     *  principale que garder une revue périmée ouverte. */
    private void forceRetagSelection() {
        int[] viewRows = table.getSelectedRows();
        List<FileEntry> targets = new ArrayList<>();
        if (viewRows.length > 0) {
            for (int vr : viewRows) {
                int mr = table.convertRowIndexToModel(vr);
                if (mr >= 0 && mr < rowEntries.size()) targets.add(rowEntries.get(mr));
            }
        } else {
            targets.addAll(rowEntries);
        }
        if (targets.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.t("Aucun fichier à re-taguer."));
            return;
        }
        owner.forceRetagOn(targets);
        dispose();
    }

    private void trashSelected() {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez au moins une ligne."));
            return;
        }
        List<FileEntry> toTrash = new ArrayList<>();
        int notBroken = 0;
        for (int vr : viewRows) {
            int mr = table.convertRowIndexToModel(vr);
            if (mr < 0 || mr >= rowEntries.size()) continue;
            if (!I18n.t("Probablement cassé").equals(model.getValueAt(mr, 0))) notBroken++;
            toTrash.add(rowEntries.get(mr));
        }
        StringBuilder msg = new StringBuilder(I18n.t(
            "Déplacer %d fichier(s) vers la corbeille ?", toTrash.size()));
        if (notBroken > 0)
            msg.append("\n").append(I18n.t(
                "⚠ %d d'entre eux sont classés \"à revérifier\", pas \"probablement cassé\" — "
                + "assure-toi de ne pas supprimer un faux positif (mix/remix légitime non reconnu).",
                notBroken));
        int ok = JOptionPane.showConfirmDialog(this, msg.toString(),
                I18n.t("Confirmer la suppression"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        boolean trashSupported = desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
        int deleted = 0, failDel = 0;
        for (FileEntry entry : toTrash) {
            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            boolean moved = trashSupported ? desktop.moveToTrash(f) : f.delete();
            if (moved) {
                deleted++;
                int idx = tableModel.indexOf(entry);
                if (idx >= 0) tableModel.remove(idx);
            } else {
                failDel++;
            }
        }
        String where = trashSupported ? I18n.t("déplacé(s) dans la corbeille") : I18n.t("supprimé(s)");
        owner.setStatus(I18n.t("%d fichier(s) %s%s", deleted, where,
                failDel > 0 ? I18n.t(", %d échec(s)", failDel) : ""));
        load();
    }

    private void setColWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        cm.getColumn(i).setPreferredWidth(p);
        cm.getColumn(i).setMinWidth(mn);
        cm.getColumn(i).setMaxWidth(mx);
    }
}
