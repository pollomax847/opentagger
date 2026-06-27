package com.opentagger.ui;

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

    private final FileTableModel        tableModel;
    private final List<DuplicateGroup>  groups;
    /** Correspondance parallèle : allBoxes[i] ↔ allEntries[i] */
    private final List<JCheckBox>  allBoxes   = new ArrayList<>();
    private final List<FileEntry>  allEntries = new ArrayList<>();
    private JCheckBox chkCleanDirs;

    public DuplicatesDialog(Frame owner, List<DuplicateGroup> groups, FileTableModel tableModel) {
        super(owner, "Doublons détectés — " + groups.size() + " groupe(s)", true);
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

        JLabel intro = new JLabel("<html><b>Cochez les fichiers à SUPPRIMER.</b> "
            + "Le meilleur fichier de chaque groupe est mis en évidence — ne le cochez pas.</html>");
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
            case MBID_EXACT     -> new Color(0x1b5e20); // vert foncé
            case ACOUSTID_EXACT -> new Color(0x0d47a1); // bleu foncé
            case TITLE_HEURISTIC-> new Color(0x6d4c41); // marron
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
                ? SZ.format(sizeKb / 1024.0) + " Mo"
                : (sizeKb >= 0 ? sizeKb + " Ko" : "?");

            String path = f.getAbsolutePath();

            JLabel lblPath = new JLabel(path);
            lblPath.putClientProperty("FlatLaf.style",
                isBest ? "font: bold 11 $defaultFont; foreground: #a5d6a7"
                       : "font: 11 $defaultFont");
            lblPath.setToolTipText(path);

            JLabel lblMeta = new JLabel(ext + "  " + sz);
            lblMeta.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

            JLabel lblKeep = isBest
                ? new JLabel("★ Recommandé")
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
        JButton btnSmart  = new JButton("Sélection intelligente");
        JButton btnNone   = new JButton("Tout décocher");
        JButton btnDelete = new JButton("Déplacer dans la corbeille…");
        JButton btnClose  = new JButton("Fermer");
        btnDelete.putClientProperty("FlatLaf.style", "background: #8b1a1a");

        chkCleanDirs = new JCheckBox("Supprimer les dossiers vides après");
        chkCleanDirs.setSelected(true);
        chkCleanDirs.putClientProperty("FlatLaf.style", "font: 11 $defaultFont");

        btnSmart.setToolTipText("Coche automatiquement les fichiers de moindre qualité dans chaque groupe");
        btnSmart .addActionListener(e -> smartSelect());
        btnNone  .addActionListener(e -> allBoxes.forEach(cb -> cb.setSelected(false)));
        btnDelete.addActionListener(e -> deleteSelected());
        btnClose .addActionListener(e -> dispose());

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));

        int totalFiles = groups.stream().mapToInt(g -> g.files().size()).sum();
        JLabel info = new JLabel("  " + groups.size() + " groupe(s), " + totalFiles + " fichier(s)");
        info.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(info);
        left.add(Box.createHorizontalStrut(16));
        left.add(chkCleanDirs);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnSmart);
        right.add(btnNone);
        right.add(Box.createHorizontalStrut(12));
        right.add(btnDelete);
        right.add(btnClose);
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
                "Tous les fichiers ont le même score de qualité.\nCochez manuellement les fichiers à supprimer.",
                "Sélection intelligente", JOptionPane.INFORMATION_MESSAGE);
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
                "Aucun fichier coché.\nUtilisez \"Sélection intelligente\" ou cochez manuellement.",
                "Info", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Construire un résumé lisible
        StringBuilder sb = new StringBuilder("<html>Supprimer définitivement <b>");
        sb.append(toDelete.size()).append(" fichier(s)</b> du disque ?<br><br>");
        int shown = Math.min(toDelete.size(), 6);
        for (int i = 0; i < shown; i++) {
            File f = toDelete.get(i).currentPath != null
                ? toDelete.get(i).currentPath.toFile() : toDelete.get(i).file;
            sb.append("&nbsp;• ").append(f.getName()).append("<br>");
        }
        if (toDelete.size() > shown) sb.append("&nbsp;… et ").append(toDelete.size() - shown).append(" autre(s)");
        sb.append("</html>");

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
            "Confirmer la suppression", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        boolean trashSupported = desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH);

        int deleted = 0, errors = 0;
        List<File> deletedParents = new ArrayList<>();
        for (FileEntry e : toDelete) {
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            int modelIdx = tableModel.indexOf(e);
            boolean moved = trashSupported ? desktop.moveToTrash(f) : f.delete();
            if (moved) {
                if (modelIdx >= 0) tableModel.remove(modelIdx);
                if (chkCleanDirs.isSelected() && f.getParentFile() != null)
                    deletedParents.add(f.getParentFile());
                deleted++;
            } else {
                errors++;
            }
        }

        // Nettoyer les dossiers vides remontés depuis les parents des fichiers supprimés
        int dirsRemoved = 0;
        if (chkCleanDirs.isSelected()) {
            for (File dir : deletedParents) dirsRemoved += cleanEmptyAncestors(dir);
        }

        String where = trashSupported ? "déplacé(s) dans la corbeille" : "supprimé(s)";
        String msg = deleted + " fichier(s) " + where
            + (dirsRemoved > 0 ? ", " + dirsRemoved + " dossier(s) vide(s) supprimé(s)" : "")
            + (errors > 0 ? ", " + errors + " erreur(s)" : "") + ".";
        JOptionPane.showMessageDialog(this, msg, "Résultat", JOptionPane.INFORMATION_MESSAGE);
        dispose();
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
