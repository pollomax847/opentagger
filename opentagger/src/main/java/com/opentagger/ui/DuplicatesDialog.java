package com.opentagger.ui;

import com.opentagger.FileRenamer;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.ui.DuplicateDetector.DuplicateGroup;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** Meilleur fichier de chaque groupe, précalculé en arrière-plan avant l'ouverture du dialogue
     *  (voir DuplicateDetector.computeBestMap) — ne JAMAIS rappeler bestInGroup() depuis l'EDT ici,
     *  c'est justement ce qui gelait l'appli (E/S disque par fichier .m4a, répétée par groupe). */
    private final Map<DuplicateGroup, FileEntry> bestByGroup;
    /** Taille de chaque fichier (-1 si absent), déjà calculée en arrière-plan (voir
     *  DuplicateDetector.computeSizes()) — ne JAMAIS appeler File.exists()/length() ici, c'est
     *  justement ce qui gelait l'appli pendant des heures (des milliers d'E/S disque directement
     *  dans le constructeur, donc sur l'EDT) — repéré en direct 2026-08-29. */
    private final Map<FileEntry, Long> sizesByFile;
    /** Correspondance parallèle : allBoxes[i] ↔ allEntries[i] (seulement les groupes affichés). */
    private final List<JCheckBox>  allBoxes   = new ArrayList<>();
    private final List<FileEntry>  allEntries = new ArrayList<>();
    /** Sélection de TOUS les fichiers, affichés ou non (au-delà de MAX_SHOWN groupes, seule la liste est tronquée :
     *  « Sélection intelligente » et la mise à la corbeille portent sur tous les groupes). */
    private final Map<FileEntry, Boolean> selected = new java.util.IdentityHashMap<>();
    /** Copie gardée du groupe de chaque fichier — pour ne jamais retirer une copie dont la gardée aurait disparu. */
    private final Map<FileEntry, FileEntry> keeperOf = new java.util.IdentityHashMap<>();
    private final Map<FileEntry, DuplicateGroup> groupOfEntry = new java.util.IdentityHashMap<>();
    private final int songsOnSeveralAlbums;
    private static final int MAX_SHOWN = 300;
    private JCheckBox chkCleanDirs;
    /** Journal principal (panneau du bas) et barre de progression (bas-droite) de MainFrame —
     *  avant ce correctif, suppression/déplacement de doublons n'écrivaient RIEN dans le journal
     *  visible pendant l'opération (seulement un résumé dans le log fichier via LOG.info(), jamais
     *  affiché à l'écran) et n'avançaient aucune barre de progression, contrairement à
     *  renommage/déplacement/groupement par compilations qui font tous les deux. Retour
     *  utilisateur (2026-08-10). Appelés directement depuis doInBackground() (pas via publish()) —
     *  même convention déjà utilisée par CompilationClusterWorker pour logLine. */
    private final java.util.function.BiConsumer<String, FileEntry.Status> journalLine;
    private final java.util.function.BiConsumer<Integer, Integer> onProgress;

    /** Pied de page : les boutons, ou l'avancement d'un retrait/déplacement en cours (2026-10-14, retour utilisateur : « on
     *  n'a pas de visu sur la suppression en cours, je ne sais pas si je peux fermer la fenêtre »). */
    private final CardLayout footerCards = new CardLayout();
    private final JPanel     footer      = new JPanel(footerCards);
    private final JLabel       runTitle = new JLabel(" ");
    private final JProgressBar runBar   = new JProgressBar(0, 100);
    private final JLabel       runFile  = new JLabel(" ");
    private final JLabel       runHint  = new JLabel(" ");
    private final JButton      runStop  = new JButton(I18n.t("Arrêter"));
    private final JButton      runClose = new JButton(I18n.t("Fermer la fenêtre"));
    private volatile boolean   stopRun;
    /** Un seul retrait/déplacement à la fois, même fenêtre fermée puis rouverte : le 2026-10-15, deux retraits lancés
     *  coup sur coup (aucun signe que le premier tournait) ont traité les mêmes fichiers en parallèle — 702 « erreurs »
     *  qui n'étaient que des fichiers déjà déplacés par l'autre passe. */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING = new java.util.concurrent.atomic.AtomicBoolean();

    /** Vrai pendant un retrait ou un déplacement de copies (voir MainFrame.detectDuplicates). */
    public static boolean isRunning() { return RUNNING.get(); }

    public DuplicatesDialog(Frame owner, List<DuplicateGroup> groups,
                             Map<DuplicateGroup, FileEntry> bestByGroup, Map<FileEntry, Long> sizesByFile,
                             FileTableModel tableModel,
                             java.util.function.BiConsumer<String, FileEntry.Status> journalLine,
                             java.util.function.BiConsumer<Integer, Integer> onProgress) {
        this(owner, groups, bestByGroup, sizesByFile, tableModel, journalLine, onProgress, 0);
    }

    public DuplicatesDialog(Frame owner, List<DuplicateGroup> groups,
                             Map<DuplicateGroup, FileEntry> bestByGroup, Map<FileEntry, Long> sizesByFile,
                             FileTableModel tableModel,
                             java.util.function.BiConsumer<String, FileEntry.Status> journalLine,
                             java.util.function.BiConsumer<Integer, Integer> onProgress, int songsOnSeveralAlbums) {
        super(owner, I18n.t("Doublons détectés — %d groupe(s)", groups.size()), true);
        this.songsOnSeveralAlbums = songsOnSeveralAlbums;
        for (DuplicateGroup g : groups) {
            FileEntry best = bestByGroup.get(g);
            for (FileEntry e : g.files()) { selected.put(e, false); keeperOf.put(e, best); groupOfEntry.put(e, g); }
        }
        this.groups      = groups;
        this.bestByGroup = bestByGroup;
        this.sizesByFile = sizesByFile;
        this.tableModel  = tableModel;
        this.journalLine = journalLine;
        this.onProgress  = onProgress;
        setSize(1100, 680);
        setMinimumSize(new Dimension(660, 400));
        setLocationRelativeTo(owner);

        if (groups.stream().anyMatch(g -> g.scope() != null)) smartSelectAll();   // vraies copies : pré-cochées
        JPanel content = buildContent();
        JScrollPane scroll = new JScrollPane(content);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll,        BorderLayout.CENTER);
        footer.add(buildFooter(), "actions");
        footer.add(buildRunPanel(), "run");
        getContentPane().add(footer, BorderLayout.SOUTH);

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

        boolean copies = groups.stream().anyMatch(g -> g.scope() != null);
        JLabel intro = new JLabel(copies
            ? I18n.t("<html><b>Copies en trop déjà cochées</b> : dans chaque groupe, la copie à garder (★) est la "
                + "meilleure qualité, puis celle au nom propre et dans le dossier le plus complet. Seuls deux cas sont "
                + "listés : le même morceau plusieurs fois <b>dans le même dossier</b>, et la même piste du même album "
                + "<b>rangée dans deux dossiers</b>.%s</html>",
                songsOnSeveralAlbums > 0 ? I18n.t("<br>%d chanson(s) présente(s) sur plusieurs albums différents "
                        + "(compilations…) ne sont pas listées : ce ne sont pas des doublons.", songsOnSeveralAlbums) : "")
            : I18n.t("<html><b>Cochez les fichiers à SUPPRIMER.</b> "
                + "Le meilleur fichier de chaque groupe est mis en évidence — ne le cochez pas.</html>"));
        intro.putClientProperty("FlatLaf.style", "foreground: #cc7744; font: 11 $defaultFont");
        intro.setBorder(new EmptyBorder(0, 0, 10, 0));
        // Texte replié sur une largeur fixe et tout aligné à gauche : sur une seule ligne, l'introduction débordait à
        // droite (coupée, barre de défilement horizontale) — visible sur la capture du 2026-10-14.
        intro.setText(intro.getText().replaceFirst("^<html>", "<html><div style='width:820px'>").replaceFirst("</html>$", "</div></html>"));
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);
        outer.add(intro);

        int shown = 0;
        for (DuplicateGroup group : groups) {
            if (shown++ >= MAX_SHOWN) break;
            JPanel box = buildGroup(group);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));   // pas étiré en hauteur
            outer.add(box);
            outer.add(Box.createVerticalStrut(12));
        }
        if (groups.size() > MAX_SHOWN) {
            JLabel more = new JLabel(I18n.t("… et %d autre(s) groupe(s), non affichés mais traités de la même façon "
                    + "par les boutons ci-dessous.", groups.size() - MAX_SHOWN));
            more.putClientProperty("FlatLaf.style", "foreground: #888888; font: italic 11 $defaultFont");
            more.setAlignmentX(Component.LEFT_ALIGNMENT);
            outer.add(more);
        }
        return outer;
    }

    private JPanel buildGroup(DuplicateGroup group) {
        FileEntry best   = bestByGroup.get(group);
        String    label  = DuplicateDetector.groupLabel(group);
        Color     badgeColor = switch (group.confidence()) {
            case MBID_EXACT        -> new Color(0x1b5e20); // vert foncé
            case ACOUSTID_EXACT    -> new Color(0x0d47a1); // bleu foncé
            case FINGERPRINT_EXACT -> new Color(0x4527a0); // violet foncé
            case TITLE_HEURISTIC   -> new Color(0x6d4c41); // marron
        };

        // Badge : où est l'autre copie (nouvelle détection), sinon niveau de confiance
        JLabel badge = new JLabel("  " + (group.scope() != null ? group.scope().badge : group.confidence().badge) + "  ");
        badge.setOpaque(true);
        badge.setBackground(badgeColor);
        badge.setForeground(Color.WHITE);
        badge.putClientProperty("FlatLaf.style", "font: bold 10 $defaultFont");
        badge.setBorder(new EmptyBorder(2, 4, 2, 4));
        badge.setToolTipText(group.scope() != null
                ? group.scope().tooltip + " — " + group.confidence().tooltip : group.confidence().tooltip);

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
            cb.setSelected(Boolean.TRUE.equals(selected.get(e)));
            cb.addItemListener(ev -> selected.put(e, cb.isSelected()));
            allBoxes.add(cb);
            allEntries.add(e);

            File   f      = e.currentPath != null ? e.currentPath.toFile() : e.file;
            Long   sizeB  = sizesByFile.get(e);
            long   sizeKb = (sizeB != null && sizeB >= 0) ? sizeB / 1024 : -1;
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
        JButton btnDelete  = new JButton(I18n.t("Retirer les copies cochées…"));
        btnDelete.setToolTipText(I18n.t("Vers la corbeille de l'appli, en gardant leur dossier d'origine (journal + "
                + "script remettre_en_place.py) — rien n'est supprimé définitivement"));
        JButton btnClose   = new JButton(I18n.t("Fermer"));
        btnDelete.putClientProperty("FlatLaf.style", "background: #8b1a1a");

        chkCleanDirs = new JCheckBox(I18n.t("Supprimer les dossiers vides après"));
        chkCleanDirs.setSelected(true);
        chkCleanDirs.putClientProperty("FlatLaf.style", "font: 11 $defaultFont");

        btnSmart.setToolTipText(I18n.t("Coche automatiquement les fichiers de moindre qualité dans chaque groupe"));
        btnSmart  .addActionListener(e -> smartSelect());
        btnNone   .addActionListener(e -> { selected.replaceAll((k, v) -> false); allBoxes.forEach(cb -> cb.setSelected(false)); });
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

    private JPanel buildRunPanel() {
        runTitle.putClientProperty("FlatLaf.style", "font: bold +1 $defaultFont");
        runBar.setStringPainted(true);
        runFile.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        runHint.putClientProperty("FlatLaf.style", "foreground: #888888; font: italic 11 $defaultFont");
        runHint.setText(I18n.t("Tu peux fermer cette fenêtre : le travail continue en arrière-plan, l'avancement reste "
                + "visible en bas à droite de la fenêtre principale et le résultat s'écrit dans le journal."));
        runStop.setToolTipText(I18n.t("S'arrête après le fichier en cours — ce qui est déjà fait reste fait (et peut être remis en place)"));
        runStop.addActionListener(e -> { stopRun = true; runStop.setEnabled(false); runStop.setText(I18n.t("Arrêt…")); });
        runClose.addActionListener(e -> dispose());

        JPanel text = new JPanel(new GridLayout(0, 1, 0, 4));
        text.add(runTitle);
        text.add(runBar);
        text.add(runFile);
        text.add(runHint);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(runStop);
        buttons.add(runClose);
        JPanel p = new JPanel(new BorderLayout(12, 0));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(10, 14, 10, 14)));
        p.add(text, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    /** Une action sur un fichier coché : vrai si faite. Lève une exception pour une erreur à montrer. */
    /** Une action sur un fichier coché : null si faite, sinon la raison pour laquelle le fichier est LAISSÉ (pas une
     *  erreur : déjà retiré, renommé depuis la recherche…). Une vraie erreur lève une exception. */
    @FunctionalInterface private interface FileOp { String apply(FileEntry e, File f) throws Exception; }

    /**
     * Retrait ou déplacement des copies cochées, avec l'avancement DANS la fenêtre (barre, compte, fichier en cours, Go) et
     * dans la barre de la fenêtre principale. La fenêtre peut être fermée pendant le travail : il continue, le résultat
     * s'écrit dans le journal. « Arrêter » s'arrête après le fichier en cours. Thread virtuel, pas le pool Swing
     * (10 threads, voir WorkerHub). Les fichiers traités sont retirés du tableau en une fois à la fin.
     */
    private void runBatch(String title, String doneLabel, List<FileEntry> files, FileOp op) {
        if (!RUNNING.compareAndSet(false, true)) {
            JOptionPane.showMessageDialog(this, I18n.t("Un retrait de doublons est déjà en cours (barre en bas à droite de la "
                    + "fenêtre principale) — attends qu'il se termine."), I18n.t("Doublons"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        stopRun = false;
        runStop.setEnabled(true);
        runStop.setText(I18n.t("Arrêter"));
        runStop.setVisible(true);
        runClose.setText(I18n.t("Fermer la fenêtre"));
        runHint.setVisible(true);
        runTitle.setText(title);
        runBar.setValue(0);
        runBar.setString("0 / " + files.size());
        footerCards.show(footer, "run");

        SwingWorker<Void, FileEntry> worker = new SwingWorker<>() {
            final java.util.concurrent.atomic.AtomicInteger processed = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
            int ok = 0, left = 0, errors = 0, dirsRemoved = 0;
            volatile String current = "";
            long lastShown;

            @Override protected Void doInBackground() {
                Map<File, Path> parents = new LinkedHashMap<>();
                for (FileEntry e : files) {
                    if (stopRun) break;
                    File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
                    current = f.getName();
                    try {
                        String why = op.apply(e, f);
                        if (why == null) {
                            publish(e);
                            Long b = sizesByFile.get(e);
                            if (b != null && b > 0) bytes.addAndGet(b);
                            if (journalLine != null) journalLine.accept(I18n.t("  ✔ %s — %s", f.getName(), doneLabel), FileEntry.Status.TAGGED);
                            if (chkCleanDirs.isSelected() && f.getParentFile() != null) parents.put(f.getParentFile(), e.scanRoot);
                            ok++;
                        } else {
                            if (journalLine != null) journalLine.accept(I18n.t("  ↷ %s — laissé : %s", f.getName(), why), FileEntry.Status.SKIPPED);
                            left++;
                        }
                    } catch (Exception ex) {
                        if (journalLine != null) journalLine.accept(I18n.t("  ✗ %s → échec : %s", f.getName(), ex.getMessage()),
                                FileEntry.Status.ERROR);
                        errors++;
                    }
                    int n = processed.incrementAndGet();
                    if (onProgress != null) onProgress.accept(n, files.size());
                    long now = System.currentTimeMillis();
                    if (now - lastShown >= 150 || n == files.size()) { lastShown = now; SwingUtilities.invokeLater(this::show); }
                }
                // Nettoyer les dossiers vides remontés depuis les parents des fichiers traités — même logique que
                // FileRenamer après un renommage (cover.jpg/log/résidu AppleDouble laissés derrière comptent comme
                // "vide", voir isDeletableLeftover).
                if (chkCleanDirs.isSelected()) {
                    current = I18n.t("nettoyage des dossiers vides…");
                    SwingUtilities.invokeLater(this::show);
                    for (Map.Entry<File, Path> pe : parents.entrySet())
                        dirsRemoved += FileRenamer.deleteEmptyAncestors(pe.getKey().toPath(), pe.getValue());
                }
                return null;
            }

            private void show() {
                int n = processed.get();
                runBar.setValue(files.isEmpty() ? 100 : (int) (n * 100L / files.size()));
                runBar.setString(I18n.t("%d / %d  ·  %s Go", n, files.size(), SZ.format(bytes.get() / 1e9)));
                runFile.setText(I18n.t("En cours : %s", current));
            }

            // Retrait du tableau en UNE fois à la fin (removeEntries) : indexOf()+remove() par fichier coûtait un
            // parcours complet du tableau à chaque copie — des dizaines de milliers ici.
            final java.util.Set<FileEntry> done = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            @Override protected void process(List<FileEntry> chunks) { done.addAll(chunks); }

            @Override protected void done() {
                RUNNING.set(false);
                if (!done.isEmpty()) tableModel.removeEntries(done);
                if (onProgress != null) onProgress.accept(files.size(), files.size());   // ferme la barre de la fenêtre principale
                String msg = I18n.t("%d / %d fichier(s) — %s (%s Go)", ok, files.size(), doneLabel, SZ.format(bytes.get() / 1e9))
                    + (dirsRemoved > 0 ? I18n.t(", %d dossier(s) vide(s) supprimé(s)", dirsRemoved) : "")
                    + (left > 0 ? I18n.t(", %d laissé(s) (déjà retirés ou déplacés depuis la recherche)", left) : "")
                    + (errors > 0 ? I18n.t(", %d erreur(s)", errors) : "")
                    + (stopRun ? I18n.t(" — arrêté avant la fin") : "") + ".";
                LOG.info("[Doublons] " + msg);
                if (journalLine != null) journalLine.accept(I18n.t("Doublons : %s", msg), errors > 0 ? FileEntry.Status.ERROR : FileEntry.Status.TAGGED);
                // Résultat dans la fenêtre si elle est encore ouverte (plus de fenêtre surgissante bloquante).
                show();
                runTitle.setText((stopRun ? "■ " : "✔ ") + title + (stopRun ? I18n.t(" — arrêté") : I18n.t(" — terminé")));
                runFile.setText(msg);
                runHint.setVisible(false);
                runStop.setVisible(false);
                runClose.setText(I18n.t("Fermer"));
            }
        };
        Thread.ofVirtual().name("doublons").start(worker);
    }

    // ── Sélection intelligente ────────────────────────────────────────────────

    /** Tous les groupes (affichés ou non) : tout sauf la copie gardée. */
    private void smartSelectAll() {
        for (DuplicateGroup group : groups) {
            FileEntry best = bestByGroup.get(group);
            for (FileEntry e : group.files()) selected.put(e, e != best);
        }
    }

    private void smartSelect() {
        smartSelectAll();
        for (int i = 0; i < allBoxes.size(); i++) allBoxes.get(i).setSelected(Boolean.TRUE.equals(selected.get(allEntries.get(i))));

        long cocheCount = selected.values().stream().filter(Boolean::booleanValue).count();
        if (cocheCount == 0) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Tous les fichiers ont le même score de qualité.\nCochez manuellement les fichiers à supprimer."),
                I18n.t("Sélection intelligente"), JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    private List<FileEntry> checked() {
        List<FileEntry> out = new ArrayList<>();
        for (DuplicateGroup g : groups) for (FileEntry e : g.files()) if (Boolean.TRUE.equals(selected.get(e))) out.add(e);
        return out;
    }

    private void deleteSelected() {
        List<FileEntry> toDelete = checked();

        if (toDelete.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18n.t("Aucun fichier coché.\nUtilisez \"Sélection intelligente\" ou cochez manuellement."),
                I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Construire un résumé lisible — l'ancien texte annonçait « Supprimer définitivement » alors que les
        // fichiers ont toujours été déplacés dans la corbeille de l'appli (récupérables).
        long bytes = 0;
        for (FileEntry e : toDelete) { Long b = sizesByFile.get(e); if (b != null && b > 0) bytes += b; }
        StringBuilder sb = new StringBuilder(
            I18n.t("<html>Retirer <b>%d fichier(s)</b> (%s Go) vers la corbeille de l'appli ?<br>"
                + "<i>Leur dossier d'origine est conservé ; un journal et remettre_en_place.py permettent de tout "
                + "remettre.</i><br><br>", toDelete.size(), SZ.format(bytes / 1e9)));
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

        // TrashHelper.moveToTrash() — voir sa Javadoc (2026-09-05) : Desktop.moveToTrash() n'est PAS supporté sur cette
        // machine, l'ancien code tombait dans un f.delete() en silence. Point de passage unique qui ne supprime jamais
        // définitivement ; les vraies copies gardent leur chemin d'origine dans le lot (remettre_en_place.py).
        String lot = "doublons-" + java.time.LocalDate.now();
        runBatch(I18n.t("Retrait de %d copie(s) vers la corbeille de l'appli", toDelete.size()), I18n.t("corbeille"), toDelete, (e, f) -> {
            // Les fichiers ont pu bouger depuis la recherche (Enregistrement qui renomme, autre retrait…) : on le dit
            // au lieu d'un « échec » qui fait croire à un problème — le 2026-10-15, toutes les « erreurs » étaient ça.
            if (!f.isFile()) return I18n.t("déjà retiré, ou renommé depuis la recherche");
            FileEntry keep = keeperOf.get(e);
            DuplicateGroup g = groupOf(e);
            if (g != null && g.scope() != null && keep != null && keep != e) {
                File kf = keep.currentPath != null ? keep.currentPath.toFile() : keep.file;
                if (!kf.isFile()) return I18n.t("la copie gardée a changé de place depuis la recherche — gardé par sécurité");
                if (com.opentagger.TrashHelper.moveToTrashKeepingPath(f, lot, g.scope().name(), kf) != null) return null;
                throw new Exception(I18n.t("déplacement vers la corbeille impossible (détail dans %s/journal.tsv)", lot));
            }
            if (com.opentagger.TrashHelper.moveToTrash(f)) return null;
            throw new Exception(I18n.t("déplacement vers la corbeille impossible"));
        });
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
    private DuplicateGroup groupOf(FileEntry e) { return groupOfEntry.get(e); }

    private void moveToDoublonsSelected() {
        List<FileEntry> toMove = checked();

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

        runBatch(I18n.t("Déplacement de %d copie(s) vers Doublons/", toMove.size()), I18n.t("Doublons/"), toMove, (e, f) -> {
            if (!f.isFile()) return I18n.t("déjà déplacé, ou renommé depuis la recherche");
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
            com.opentagger.PlaylistSync.onFileMoved(srcPath, dest);
            e.currentPath = dest;
            return null;
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1) : "";
    }
}
