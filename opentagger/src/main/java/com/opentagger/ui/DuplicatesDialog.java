package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.ui.DuplicateDetector.DuplicateGroup;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialogue de gestion des doublons.
 *
 * Améliorations vs. l'ancienne version :
 *  - Badge de confiance (MBID / AcoustID / Titre) par groupe
 *  - Affichage du format, taille et indication "Recommandé à conserver"
 *  - Aucune case pré-cochée par défaut (l'utilisateur décide)
 *  - Bouton "Sélection intelligente" : coche les fichiers de moindre qualité
 *    dans chaque groupe et laisse le meilleur décoché
 *  - Le bouton "Tout cocher" est remplacé par ce bouton intelligent
 */
public class DuplicatesDialog extends JDialog {

    private static final DecimalFormat SZ = new DecimalFormat("0.0");
    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(DuplicatesDialog.class.getName());

    private final FileTableModel        tableModel;
    private final List<DuplicateGroup>  groups;
    /** Correspondance parallèle : allBoxes[i] ↔ allEntries[i] */
    private final List<JCheckBox>  allBoxes   = new ArrayList<>();
    private final List<FileEntry>  allEntries = new ArrayList<>();
    private JCheckBox chkCleanDirs;

    public DuplicatesDialog(Frame owner, List<DuplicateGroup> groups, FileTableModel tableModel) {
        super(owner, I18n.t("Doublons détectés — %d groupe(s)", groups.size()), true);
        this.groups     = groups;
        this.tableModel = tableModel;
        setSize(900, 600);
        setMinimumSize(new Dimension(660, 400));
        setLocationRelativeTo(owner);

        JPanel content = buildContent();
        JScrollPane scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll,        BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });
    }

    // ── Construction du contenu ────────────────────────────────────────────────

    private JPanel buildContent() {
        JPanel outer = new JPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));
        outer.setBorder(new EmptyBorder(10, 14, 10, 14));

        JLabel intro = new JLabel(I18n.t("<html><b>Cochez les fichiers à SUPPRIMER.</b> "
            + "Le meilleur fichier de chaque groupe est mis en évidence — ne le cochez pas.</html>"));
        intro.putClientProperty("FlatLaf.style", "foreground: #cc7744; font: 11 $defaultFont");
        intro.setBorder(new EmptyBorder(0, 0, 10, 0));
        outer.add(intro);

        for (DuplicateGroup group : groups) {
            outer.add(buildGroup(group));
            outer.add(Box.createVerticalStrut(12));
        }
        return outer;
    }

    private JPanel buildGroup(DuplicateGroup group) {
        FileEntry best   = DuplicateDetector.bestInGroup(group.files());
        String    label  = DuplicateDetector.groupLabel(group);
        Color     badgeColor = switch (group.confidence()) {
            case MBID_EXACT        -> new Color(0x1b5e20); // vert foncé
            case ACOUSTID_EXACT    -> new Color(0x0d47a1); // bleu foncé
            case FINGERPRINT_EXACT -> new Color(0x4527a0); // violet foncé
            case TITLE_HEURISTIC   -> new Color(0x6d4c41); // marron
        };

        // Badge de confiance
        JLabel badge = new JLabel("  " + group.confidence().badge + "  ");
        badge.setOpaque(true);
        badge.setBackground(badgeColor);
        badge.setForeground(Color.WHITE);
        badge.putClientProperty("FlatLaf.style", "font: bold 10 $defaultFont");
        badge.setBorder(new EmptyBorder(2, 4, 2, 4));
        badge.setToolTipText(group.confidence().tooltip);

        JLabel title = new JLabel(label);
        title.putClientProperty("FlatLaf.style", "font: bold 12 $defaultFont");

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        header.add(badge);
        header.add(title);

        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setBorder(new CompoundBorder(
            new MatteBorder(1, 1, 1, 1, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 10, 8, 10)));
        box.add(header, BorderLayout.NORTH);

        JPanel rows = new JPanel(new GridBagLayout());
        int rowIdx = 0;
        for (FileEntry e : group.files()) {
            boolean isBest = (e == best);
            JCheckBox cb = new JCheckBox();
            cb.setSelected(false); // jamais pré-coché
            allBoxes.add(cb);
            allEntries.add(e);

            File   f      = e.currentPath != null ? e.currentPath.toFile() : e.file;
            long   sizeKb = f.exists() ? f.length() / 1024 : -1;
            String ext    = ext(f.getName()).toUpperCase();
            String sz     = sizeKb >= 1024
                ? I18n.t("%s Mo", SZ.format(sizeKb / 1024.0))
                : (sizeKb >= 0 ? I18n.t("%d Ko", sizeKb) : "?");

            String path = f.getAbsolutePath();

            JLabel lblPath = new JLabel(path);
            lblPath.putClientProperty("FlatLaf.style",
                isBest ? "font: bold 11 $defaultFont; foreground: #a5d6a7"
                       : "font: 11 $defaultFont");
            lblPath.setToolTipText(path);

            JLabel lblMeta = new JLabel(ext + "  " + sz);
            lblMeta.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

            JLabel lblKeep = isBest
                ? new JLabel("★ " + I18n.t("Recommandé"))
                : new JLabel("");
            if (isBest) {
                lblKeep.putClientProperty("FlatLaf.style", "foreground: #a5d6a7; font: bold 10 $defaultFont");
            }

            GridBagConstraints gc = new GridBagConstraints();
            gc.gridy = rowIdx++; gc.insets = new Insets(3, 0, 3, 6);

            gc.gridx = 0; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
            rows.add(cb, gc);
            gc.gridx = 1; gc.weightx = 1; gc.fill = GridBagConstraints.HORIZONTAL;
            rows.add(lblPath, gc);
            gc.gridx = 2; gc.weightx = 0; gc.fill = GridBagConstraints.NONE;
            rows.add(lblMeta, gc);
            gc.gridx = 3;
            rows.add(lblKeep, gc);
        }
        box.add(rows, BorderLayout.CENTER);
        return box;
    }

    // ── Footer ─────────────────────────────────────────────────────────────────

    private JPanel buildFooter() {
        JButton btnSmart  = new JButton(I18n.t("Sélection intelligente"));
        JButton btnNone   = new JButton(I18n.t("Tout décocher"));
        // Alternative non-destructive à la corbeille, même principe que VideoRecoveryWorker
        // (dossier "Convertis/" à côté de l'original) — retour utilisateur : garder le doublon
        // visible/parcourable dans un sous-dossier "Doublons/" plutôt que planqué dans la
        // corbeille système, sans jamais rien supprimer.
        JButton btnMoveDup = new JButton(I18n.t("Déplacer vers Doublons/…"));
        JButton btnDelete  = new JButton(I18n.t("Déplacer dans la corbeille…"));
        JButton btnClose   = new JButton(I18n.t("Fermer"));
        btnDelete.putClientProperty("FlatLaf.style", "background: #8b1a1a");

        chkCleanDirs = new JCheckBox(I18n.t("Supprimer les dossiers vides après"));
        chkCleanDirs.setSelected(true);
        chkCleanDirs.putClientProperty("FlatLaf.style", "font: 11 $defaultFont");

        btnSmart.setToolTipText(I18n.t("Coche automatiquement les fichiers de moindre qualité dans chaque groupe"));
        btnSmart  .addActionListener(e -> smartSelect());
        btnNone   .addActionListener(e -> allBoxes.forEach(cb -> cb.setSelected(false)));
        btnMoveDup.addActionListener(e -> moveToDoublonsSelected());
        btnDelete .addActionListener(e -> deleteSelected());
        btnClose  .addActionListener(e -> dispose());

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));

        int totalFiles = groups.stream().mapToInt(g -> g.files().size()).sum();
        JLabel info = new JLabel("  " + I18n.t("%d groupe(s), %d fichier(s)", groups.size(), totalFiles));
        info.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(info);
        left.add(Box.createHorizontalStrut(16));
        left.add(chkCleanDirs);

        // Fermer AVANT l'action principale (pas après) : les 5 autres dialogues de l'appli
        // (MatchDialog, CoverArtDialog, etc.) placent tous l'action de confirmation/principale à
        // l'extrême droite, avec Annuler/Fermer juste à sa gauche — l'ordre inverse ici était une
        // incohérence risquant un clic sur le mauvais bouton (juste à côté d'une action rouge).
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnSmart);
        right.add(btnNone);
        right.add(Box.createHorizontalStrut(12));
        right.add(btnClose);
        right.add(btnMoveDup);
        right.add(btnDelete);
        p.add(left,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ── Sélection intelligente ────────────────────────────────────────────────

    private void smartSelect() {
        // D'abord tout décocher
        allBoxes.forEach(cb -> cb.setSelected(false));

        // Pour chaque groupe : cocher tous SAUF le meilleur
        int idx = 0;
        for (DuplicateGroup group : groups) {
            FileEntry best = DuplicateDetector.bestInGroup(group.files());
            for (FileEntry e : group.files()) {
                if (e != best) allBoxes.get(idx).setSelected(true);
                idx++;
            }
        }

        long cocheCount = allBoxes.stream().filter(JCheckBox::isSelected).count();
        if (cocheCount == 0) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Tous les fichiers ont le même score de qualité.\nCochez manuellement les fichiers à supprimer."),
                I18n.t("Sélection intelligente"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    private void deleteSelected() {
        List<FileEntry> toDelete = new ArrayList<>();
        for (int i = 0; i < allBoxes.size(); i++) {
            if (allBoxes.get(i).isSelected()) toDelete.add(allEntries.get(i));
        }

        if (toDelete.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Aucun fichier coché.\nUtilisez \"Sélection intelligente\" ou cochez manuellement."),
                I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Construire un résumé lisible
        StringBuilder sb = new StringBuilder(
            I18n.t("<html>Supprimer définitivement <b>%d fichier(s)</b> du disque ?<br><br>", toDelete.size()));
        int shown = Math.min(toDelete.size(), 6);
        for (int i = 0; i < shown; i++) {
            File f = toDelete.get(i).currentPath != null
                ? toDelete.get(i).currentPath.toFile() : toDelete.get(i).file;
            sb.append("&nbsp;• ").append(f.getName()).append("<br>");
        }
        if (toDelete.size() > shown)
            sb.append("&nbsp;").append(I18n.t("… et %d autre(s)", toDelete.size() - shown));
        sb.append("</html>");

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
            I18n.t("Confirmer la suppression"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        // desktop.moveToTrash(...)/suppression + nettoyage des dossiers vides sont des opérations
        // disque potentiellement lentes (beaucoup de fichiers, stockage réseau/NAS) — tout ça
        // tournait directement sur l'EDT et gelait l'interface le temps du traitement complet.
        // Poussé dans un SwingWorker ; seules les mises à jour de tableModel restent sur l'EDT
        // (via publish/process) pour ne pas rouvrir la course avec le TableRowSorter.
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        new SwingWorker<Void, FileEntry>() {
            int deleted = 0, errors = 0, dirsRemoved = 0;
            boolean trashSupported;

            @Override protected Void doInBackground() {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                trashSupported = desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);
                List<File> deletedParents = new ArrayList<>();
                for (FileEntry e : toDelete) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    boolean moved = trashSupported ? desktop.moveToTrash(f) : f.delete();
                    if (moved) {
                        publish(e);
                        if (chkCleanDirs.isSelected() && f.getParentFile() != null)
                            deletedParents.add(f.getParentFile());
                        deleted++;
                    } else {
                        errors++;
                    }
                }
                // Nettoyer les dossiers vides remontés depuis les parents des fichiers supprimés
                if (chkCleanDirs.isSelected())
                    for (File dir : deletedParents) dirsRemoved += cleanEmptyAncestors(dir);
                return null;
            }

            @Override protected void process(List<FileEntry> chunks) {
                for (FileEntry e : chunks) {
                    int modelIdx = tableModel.indexOf(e);
                    if (modelIdx >= 0) tableModel.remove(modelIdx);
                }
            }

            @Override protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                String where = trashSupported ? I18n.t("déplacé(s) dans la corbeille") : I18n.t("supprimé(s)");
                String msg = I18n.t("%d fichier(s) %s", deleted, where)
                    + (dirsRemoved > 0 ? I18n.t(", %d dossier(s) vide(s) supprimé(s)", dirsRemoved) : "")
                    + (errors > 0 ? I18n.t(", %d erreur(s)", errors) : "") + ".";
                LOG.info("[Doublons] " + msg);
                JOptionPane.showMessageDialog(DuplicatesDialog.this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        }.execute();
    }

    /**
     * Déplace les fichiers cochés dans un sous-dossier "Doublons" à côté de CHAQUE fichier
     * d'origine (pas un dossier global unique) — même principe que
     * {@code VideoRecoveryWorker.moveToSubfolder()} ("Convertis/") : un groupe de doublons peut
     * avoir ses exemplaires dispersés dans des dossiers sans rapport (ex. "Wolves" trouvé à la
     * fois dans "Selena Gomez/Unknown Album/" et dans une compilation), donc un unique dossier
     * "Doublons" à la racine mélangerait des albums entiers sans contexte. Jamais de suppression :
     * alternative non-destructive à "Déplacer dans la corbeille…", retour utilisateur explicite.
     */
    private void moveToDoublonsSelected() {
        List<FileEntry> toMove = new ArrayList<>();
        for (int i = 0; i < allBoxes.size(); i++) {
            if (allBoxes.get(i).isSelected()) toMove.add(allEntries.get(i));
        }

        if (toMove.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Aucun fichier coché.\nUtilisez \"Sélection intelligente\" ou cochez manuellement."),
                I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        StringBuilder sb = new StringBuilder(
            I18n.t("<html>Déplacer <b>%d fichier(s)</b> vers un sous-dossier \"Doublons\" (à côté de chacun) ?<br><br>", toMove.size()));
        int shown = Math.min(toMove.size(), 6);
        for (int i = 0; i < shown; i++) {
            File f = toMove.get(i).currentPath != null
                ? toMove.get(i).currentPath.toFile() : toMove.get(i).file;
            sb.append("&nbsp;• ").append(f.getName()).append("<br>");
        }
        if (toMove.size() > shown)
            sb.append("&nbsp;").append(I18n.t("… et %d autre(s)", toMove.size() - shown));
        sb.append("</html>");

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
            I18n.t("Confirmer le déplacement"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        // Même raison qu'en suppression (deleteSelected()) : déplacements potentiellement lents
        // (NAS/réseau, beaucoup de fichiers) — poussé en SwingWorker, tableModel muté seulement
        // via publish/process sur l'EDT.
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        new SwingWorker<Void, FileEntry>() {
            int moved = 0, errors = 0, dirsRemoved = 0;

            @Override protected Void doInBackground() {
                List<File> oldParents = new ArrayList<>();
                for (FileEntry e : toMove) {
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    try {
                        java.nio.file.Path srcPath = f.toPath();
                        java.nio.file.Path targetDir = srcPath.getParent().resolve("Doublons");
                        java.nio.file.Files.createDirectories(targetDir);
                        String name = f.getName();
                        java.nio.file.Path dest = targetDir.resolve(name);
                        String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                        String ext  = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                        for (int i = 1; java.nio.file.Files.exists(dest); i++)
                            dest = targetDir.resolve(stem + "_" + i + ext);
                        java.nio.file.Files.move(srcPath, dest);
                        e.currentPath = dest;
                        publish(e);
                        if (chkCleanDirs.isSelected() && f.getParentFile() != null)
                            oldParents.add(f.getParentFile());
                        moved++;
                    } catch (Exception ex) {
                        errors++;
                    }
                }
                if (chkCleanDirs.isSelected())
                    for (File dir : oldParents) dirsRemoved += cleanEmptyAncestors(dir);
                return null;
            }

            @Override protected void process(List<FileEntry> chunks) {
                for (FileEntry e : chunks) {
                    int modelIdx = tableModel.indexOf(e);
                    if (modelIdx >= 0) tableModel.remove(modelIdx);
                }
            }

            @Override protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                String msg = I18n.t("%d fichier(s) déplacé(s) vers Doublons/", moved)
                    + (dirsRemoved > 0 ? I18n.t(", %d dossier(s) vide(s) supprimé(s)", dirsRemoved) : "")
                    + (errors > 0 ? I18n.t(", %d erreur(s)", errors) : "") + ".";
                LOG.info("[Doublons] " + msg);
                JOptionPane.showMessageDialog(DuplicatesDialog.this, msg, I18n.t("Résultat"), JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        }.execute();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Remonte les dossiers parents et supprime ceux qui sont vides. Retourne le nombre supprimé. */
    private static int cleanEmptyAncestors(File dir) {
        int count = 0;
        while (dir != null && dir.isDirectory()) {
            String[] contents = dir.list();
            if (contents != null && contents.length == 0) {
                if (dir.delete()) count++;
                else break;
                dir = dir.getParentFile();
            } else {
                break;
            }
        }
        return count;
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1) : "";
    }
}
