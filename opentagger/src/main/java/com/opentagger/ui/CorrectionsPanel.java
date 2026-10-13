package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.ReplayGainAnalyzer;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.SkipReason;
import com.opentagger.model.TagInfo;
import com.opentagger.ui.LibraryChecks.Check;
import com.opentagger.ui.LibraryChecks.Field;
import com.opentagger.ui.LibraryChecks.Fix;
import org.jaudiotagger.tag.FieldKey;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Onglet « À corriger » des Rapports de la bibliothèque — les « Fichiers à éditer » de MediaMonkey (voir
 * {@link LibraryChecks}) : un problème par ligne, combien de fichiers l'ont, combien l'appli sait corriger seule.
 * « Afficher » filtre le tableau principal sur ces fichiers, « Corriger » applique la correction proposée.
 *
 * <p>Remplace l'onglet « Complétude » (2026-10-13) : ses 5 compteurs (titre, artiste, album, année, genre) sont les 5
 * premières lignes ici, et on peut maintenant voir et corriger les fichiers au lieu de seulement les compter.
 *
 * <p>Chaque correction note les anciennes valeurs dans ~/.opentagger/corrections/*.tsv ; « Annuler la dernière
 * correction » les remet, même après un redémarrage. Seuls les champs corrigés sont écrits (TagWriter.writeFields) :
 * un fichier jamais identifié par l'appli le reste. Un fichier identifié mais pas encore enregistré est corrigé en
 * mémoire seulement — l'Enregistrement l'écrira avec le reste.
 */
public class CorrectionsPanel extends JPanel {

    private static final String[] COLS = {I18n.t("Problème"), I18n.t("Fichiers"), I18n.t("Corrigeables"), I18n.t("Correction")};
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String UNDONE = ".annule";

    private final MainFrame         owner;
    private final FileTableModel    tableModel;
    private final Runnable          onClose;
    private final DefaultTableModel model;
    private final JTable            table;
    private final JLabel            lblStatus = new JLabel(" ");
    private final JButton btnShow = new JButton(I18n.t("Afficher dans la liste"));
    private final JButton btnFix  = new JButton(I18n.t("Corriger"));
    private final JButton btnUndo = new JButton(I18n.t("Annuler la dernière correction"));
    private final JButton btnStop = new JButton(I18n.t("Arrêter"));
    private final JButton btnRefresh = new JButton(I18n.t("Rafraîchir"));

    private final List<Check> rows = List.of(Check.values());
    private LibraryChecks.Report<FileEntry> report;
    private boolean analyzing;
    private String afterAnalysis;   // résultat de la dernière correction, gardé à l'écran après la ré-analyse
    private volatile boolean stopRequested;

    public CorrectionsPanel(MainFrame owner, FileTableModel tableModel, Runnable onClose) {
        this.owner      = owner;
        this.tableModel = tableModel;
        this.onClose    = onClose;

        model = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c == 1 || c == 2 ? Integer.class : String.class; }
        };
        table = new JTable(model);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableColumnModel cm = table.getColumnModel();
        cm.getColumn(0).setPreferredWidth(300);
        cm.getColumn(1).setPreferredWidth(80);  cm.getColumn(1).setMaxWidth(110);
        cm.getColumn(2).setPreferredWidth(95);  cm.getColumn(2).setMaxWidth(120);
        cm.getColumn(3).setPreferredWidth(420);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) showFiles(); }
        });

        btnShow.addActionListener(e -> showFiles());
        btnFix.addActionListener(e -> fix());
        btnUndo.addActionListener(e -> undoLast());
        btnStop.addActionListener(e -> stopRequested = true);
        btnRefresh.addActionListener(e -> refresh());
        btnStop.setVisible(false);
        btnUndo.setToolTipText(I18n.t("Remet les valeurs d'avant la dernière correction (notées dans %s)", journalDir()));

        setLayout(new BorderLayout(0, 0));
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        refresh();
    }

    /** Relit l'état en mémoire (appelé quand on revient sur l'onglet). */
    void refresh() {
        if (analyzing || btnStop.isVisible()) return;
        analyzing = true;
        lblStatus.setText(I18n.t("Analyse de la bibliothèque…"));
        updateButtons();
        List<LibraryChecks.Track<FileEntry>> tracks = snapshot();
        String va = Config.get().vaName();
        Thread.ofVirtual().start(() -> {
            LibraryChecks.Report<FileEntry> r;
            try { r = LibraryChecks.analyze(tracks, va); }
            catch (Exception ex) { r = null; }
            LibraryChecks.Report<FileEntry> done = r;
            SwingUtilities.invokeLater(() -> showReport(done, tracks.size()));
        });
    }

    /** Sur l'EDT : ce que l'analyse lit, figé maintenant (les tags affichés, ceux qu'une correction modifiera). */
    private List<LibraryChecks.Track<FileEntry>> snapshot() {
        List<LibraryChecks.Track<FileEntry>> out = new ArrayList<>();
        for (FileEntry e : tableModel.allEntries()) {
            TagInfo t = tagsOf(e);
            if (t == null) continue;
            Path p = e.currentPath != null ? e.currentPath : e.file.toPath();
            out.add(new LibraryChecks.Track<>(e, p, t, e.skipReason == SkipReason.FILE_MISSING));
        }
        return out;
    }

    /** Les tags du fichier tels qu'ils sont (ou seront, pour un identifié pas encore enregistré). Pour un fichier non
     *  identifié, {@code result} peut contenir une proposition écartée : ce sont les tags du disque qui comptent. */
    private static TagInfo tagsOf(FileEntry e) {
        return e.status == FileEntry.Status.TAGGED || e.status == FileEntry.Status.IDENTIFIED ? e.activeTags() : e.current;
    }

    private void showReport(LibraryChecks.Report<FileEntry> r, int total) {
        analyzing = false;
        report = r;
        int sel = table.getSelectedRow();
        model.setRowCount(0);
        if (r == null) {
            lblStatus.setText(I18n.t("L'analyse a échoué."));
            updateButtons();
            return;
        }
        for (Check c : rows) model.addRow(new Object[]{label(c), r.found(c).size(), fixable(c).size(), howFixed(c)});
        if (sel >= 0 && sel < model.getRowCount()) table.setRowSelectionInterval(sel, sel);
        lblStatus.setText(afterAnalysis != null ? afterAnalysis
                : I18n.t("%d fichier(s) analysé(s). Double-clic sur une ligne pour voir ses fichiers dans la liste.", total));
        afterAnalysis = null;
        updateButtons();
    }

    private Check selected() {
        int r = table.getSelectedRow();
        return r < 0 ? null : rows.get(table.convertRowIndexToModel(r));
    }

    private void updateButtons() {
        Check c = selected();
        boolean idle = !analyzing && !btnStop.isVisible() && report != null;
        btnShow.setEnabled(idle && c != null && !report.found(c).isEmpty());
        btnFix.setEnabled(idle && c != null && !fixable(c).isEmpty());
        btnUndo.setEnabled(!analyzing && !btnStop.isVisible() && lastJournal() != null);
        btnRefresh.setEnabled(!analyzing && !btnStop.isVisible());
    }

    // ── Textes ────────────────────────────────────────────────────────────────

    static String label(Check c) {
        return switch (c) {
            case NO_TITLE -> I18n.t("Sans titre");
            case NO_ARTIST -> I18n.t("Sans artiste");
            case NO_ALBUM -> I18n.t("Sans album");
            case NO_YEAR -> I18n.t("Sans année");
            case NO_GENRE -> I18n.t("Sans genre");
            case ALBUM_ARTIST_SPLIT -> I18n.t("Album éclaté : artiste de l'album différent selon les pistes");
            case NO_ALBUM_ARTIST -> I18n.t("Plusieurs artistes, pas d'artiste de l'album");
            case CASE -> I18n.t("Tout en majuscules ou en minuscules");
            case SWAPPED -> I18n.t("Artiste et titre inversés");
            case NO_TRACK -> I18n.t("Numéro de piste manquant");
            case NO_REPLAYGAIN -> I18n.t("Volume non analysé (ReplayGain)");
            case MISSING_FILE -> I18n.t("Fichier introuvable sur le disque");
        };
    }

    private static String howFixed(Check c) {
        return switch (c) {
            case NO_TITLE, NO_ARTIST, NO_ALBUM -> I18n.t("à identifier : Afficher, tout sélectionner, « Tagger la sélection »");
            case NO_YEAR -> I18n.t("année reprise des autres pistes de l'album quand elles sont d'accord");
            case NO_GENRE -> I18n.t("genre repris des autres pistes de l'album quand elles sont d'accord");
            case ALBUM_ARTIST_SPLIT -> I18n.t("même artiste de l'album partout (graphie majoritaire, Various Artists pour une compilation)");
            case NO_ALBUM_ARTIST -> I18n.t("Various Artists (3 interprètes ou plus) ou l'interprète principal");
            case CASE -> I18n.t("Majuscule à chaque mot (jamais sur des tags venus de MusicBrainz)");
            case SWAPPED -> I18n.t("remis dans l'ordre du nom de fichier « Artiste - Titre »");
            case NO_TRACK -> I18n.t("numéro pris dans le nom du fichier (« 05 - … »)");
            case NO_REPLAYGAIN -> I18n.t("analyse du volume, fichier par fichier (long)");
            case MISSING_FILE -> I18n.t("retirés de la liste (rien n'est supprimé)");
        };
    }

    // ── Afficher ──────────────────────────────────────────────────────────────

    private void showFiles() {
        Check c = selected();
        if (c == null || report == null || report.found(c).isEmpty()) return;
        owner.filterByEntries(label(c), new HashSet<>(report.found(c)));
        onClose.run();
    }

    // ── Corriger ──────────────────────────────────────────────────────────────

    /** Fichiers que « Corriger » traiterait pour ce problème. */
    private List<?> fixable(Check c) {
        if (report == null) return List.of();
        return switch (c) {
            case MISSING_FILE -> report.found(c);
            // Un fichier en attente aura son volume calculé par le taguage lui-même.
            case NO_REPLAYGAIN -> report.found(c).stream().filter(e -> e.status == FileEntry.Status.TAGGED
                    || e.status == FileEntry.Status.SKIPPED || e.status == FileEntry.Status.ERROR).toList();
            default -> report.fixes(c);
        };
    }

    private void fix() {
        Check c = selected();
        if (c == null) return;
        int n = fixable(c).size();
        if (n == 0) return;
        if (c == Check.MISSING_FILE) {
            Set<FileEntry> gone = Collections.newSetFromMap(new IdentityHashMap<>());
            gone.addAll(report.found(c));
            tableModel.removeEntries(gone);
            lblStatus.setText(I18n.t("%d fichier(s) introuvable(s) retiré(s) de la liste.", gone.size()));
            refresh();
            return;
        }
        if (c == Check.NO_REPLAYGAIN && !ReplayGainAnalyzer.isAvailable()) {
            JOptionPane.showMessageDialog(this, I18n.t("ffmpeg (filtre replaygain) est introuvable : impossible d'analyser le volume."),
                    label(c), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!canRun()) return;
        List<Fix<FileEntry>> fixes = new ArrayList<>();
        if (c == Check.NO_REPLAYGAIN) {
            String msg = I18n.t("<html><b>%s</b> : analyser %d fichier(s) ?<br>%s — « Arrêter » à tout moment.</html>",
                    label(c), n, howFixed(c));
            if (JOptionPane.showConfirmDialog(this, msg, I18n.t("Corriger"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            for (Object o : fixable(c)) {
                FileEntry e = (FileEntry) o;
                fixes.add(new Fix<>(e, e.currentPath != null ? e.currentPath : e.file.toPath(), Map.of()));
            }
        } else {
            // Comme le Case Checker de MediaMonkey : tout est montré « avant → après » et décochable avant d'écrire.
            List<Fix<FileEntry>> chosen = FixPreviewDialog.choose(this, label(c), howFixed(c), report.fixes(c), CorrectionsPanel::tagsOf);
            if (chosen == null || chosen.isEmpty() || !canRun()) return;
            fixes.addAll(chosen);
        }
        run(label(c), fixes, c == Check.NO_REPLAYGAIN, newJournal(c.name().toLowerCase(Locale.ROOT)));
    }

    /** Lance les corrections, ou explique pourquoi pas (une autre passe écrit déjà dans les fichiers). */
    private boolean canRun() {
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.LIBRARY_FIX);
        if (blockers.isEmpty()) return true;
        JOptionPane.showMessageDialog(this, I18n.t("Encore en cours : %s — réessaie quand c'est fini.", String.join(", ", blockers)),
                I18n.t("Corriger"), JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    /** Une ligne du journal : chemin, champ, ancienne valeur, nouvelle valeur, « mémoire » si rien n'a été écrit. */
    private record Done(FileEntry entry, Map<Field, String> changes) {}

    private void run(String what, List<Fix<FileEntry>> fixes, boolean replayGain, Path journal) {
        stopRequested = false;
        btnStop.setVisible(true);
        updateButtons();
        int total = fixes.size();
        // Sur l'EDT : statut et chemin ACTUELS (un fichier a pu être renommé ou passer en traitement depuis l'analyse).
        record Job(Fix<FileEntry> fix, Path path, boolean memoryOnly) {}
        List<Job> jobs = new ArrayList<>();
        int skipped = 0;
        for (Fix<FileEntry> f : fixes) {
            FileEntry e = f.ref();
            if (e == null) { jobs.add(new Job(f, f.path(), false)); continue; }   // plus dans la liste : écrit directement
            if (e.status == FileEntry.Status.PROCESSING || tableModel.indexOf(e) < 0) { skipped++; continue; }
            jobs.add(new Job(f, e.currentPath != null ? e.currentPath : e.file.toPath(), e.status == FileEntry.Status.IDENTIFIED));
        }
        int skippedFinal = skipped;
        SwingWorker<Integer, Done> worker = new SwingWorker<>() {
            final List<String> errors = new ArrayList<>();
            int done;

            @Override protected Integer doInBackground() throws Exception {
                TagWriter writer = new TagWriter();
                ReplayGainAnalyzer rg = replayGain ? new ReplayGainAnalyzer() : null;
                try (var out = Files.newBufferedWriter(journal, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    for (Job j : jobs) {
                        if (stopRequested || isCancelled()) break;
                        Map<Field, String> changes = j.fix().changes();
                        try {
                            if (replayGain) {
                                ReplayGainAnalyzer.RGResult res = rg.analyze(j.path().toString());
                                if (res == null) { errors.add(j.path().getFileName() + " : " + I18n.t("analyse impossible")); continue; }
                                changes = new EnumMap<>(Field.class);
                                changes.put(Field.RG_TRACK_GAIN, res.trackGain());
                                changes.put(Field.RG_TRACK_PEAK, res.trackPeak());
                            }
                            TagInfo before = j.fix().ref() == null ? null : tagsOf(j.fix().ref());
                            if (!j.memoryOnly()) write(writer, j.path(), changes);
                            for (Map.Entry<Field, String> ch : changes.entrySet()) {
                                out.write(String.join("\t", j.path().toString(), ch.getKey().name(),
                                        clean(before == null ? "" : ch.getKey().get(before)), clean(ch.getValue()),
                                        j.memoryOnly() ? "mémoire" : "fichier"));
                                out.newLine();
                            }
                            out.flush();
                            publish(new Done(j.fix().ref(), changes));
                        } catch (Exception ex) {
                            errors.add(j.path().getFileName() + " : " + ex.getMessage());
                        }
                    }
                }
                return done;
            }

            @Override protected void process(List<Done> chunk) {
                for (Done d : chunk) {
                    if (d.entry() != null) apply(d.entry(), d.changes());
                    done++;
                }
                lblStatus.setText(I18n.t("%s : %d / %d…", what, done, jobs.size()));
            }

            @Override protected void done() {
                btnStop.setVisible(false);
                String msg = I18n.t("%s : %d fichier(s) corrigé(s)", what, done)
                        + (skippedFinal > 0 ? I18n.t(", %d en cours de traitement laissé(s) pour plus tard", skippedFinal) : "")
                        + (errors.isEmpty() ? "." : I18n.t(", %d erreur(s) — ex. %s", errors.size(), errors.get(0)));
                System.out.println("[OT] À corriger — " + msg + " Journal : " + journal);
                if (done == 0) try { Files.deleteIfExists(journal); } catch (IOException ignored) {}
                afterAnalysis = msg;
                refresh();
            }
        };
        WorkerHub.get().submit(WorkerHub.TaskKind.LIBRARY_FIX, I18n.t("Corrections (%s)", what), worker, () -> stopRequested = true);
        lblStatus.setText(I18n.t("%s : 0 / %d…", what, total - skipped));
    }

    private static void write(TagWriter writer, Path path, Map<Field, String> changes) throws Exception {
        Map<FieldKey, String> std = new EnumMap<>(FieldKey.class);
        Map<String, String> custom = new LinkedHashMap<>();
        for (Map.Entry<Field, String> ch : changes.entrySet()) {
            if (ch.getKey().key != null) std.put(ch.getKey().key, ch.getValue());
            else custom.put(ch.getKey().customName(), ch.getValue());
        }
        writer.writeFields(path.toFile(), std, custom);
    }

    /** Sur l'EDT : la correction écrite (ou à écrire à l'Enregistrement) devient aussi la valeur affichée. */
    private void apply(FileEntry e, Map<Field, String> changes) {
        for (TagInfo t : new TagInfo[]{e.current, e.result}) {
            if (t == null) continue;
            for (Map.Entry<Field, String> ch : changes.entrySet()) ch.getKey().set(t, ch.getValue());
        }
        tableModel.update(e);
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' '); }

    // ── Journal et annulation ─────────────────────────────────────────────────

    static Path journalDir() { return Paths.get(Config.configDir(), "corrections"); }

    private static Path newJournal(String what) {
        try { Files.createDirectories(journalDir()); } catch (IOException ignored) {}
        return journalDir().resolve("correction_" + LocalDateTime.now().format(TS) + "_" + what + ".tsv");
    }

    /** Le journal le plus récent pas encore annulé, ou null. */
    private static Path lastJournal() {
        try (var s = Files.list(journalDir())) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tsv") && !p.getFileName().toString().contains(UNDONE))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void undoLast() {
        Path journal = lastJournal();
        if (journal == null) return;
        List<String[]> lines = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                String[] f = l.split("\t", -1);
                if (f.length >= 5 && !f[1].startsWith("RG_")) lines.add(f);   // un volume calculé n'a rien à « remettre »
            }
        } catch (IOException ex) {
            lblStatus.setText(I18n.t("Journal illisible : %s", ex.getMessage()));
            return;
        }
        if (lines.isEmpty()) {
            markUndone(journal);
            updateButtons();
            lblStatus.setText(I18n.t("Rien à remettre dans %s (volume analysé uniquement).", journal.getFileName()));
            return;
        }
        String msg = I18n.t("<html>Remettre les anciennes valeurs de %d champ(s) ?<br>%s</html>", lines.size(), journal.getFileName());
        if (JOptionPane.showConfirmDialog(this, msg, I18n.t("Annuler la dernière correction"), JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;

        // Regroupe par fichier, dernier changement annulé en premier.
        Map<String, FileEntry> byPath = new HashMap<>();
        for (FileEntry e : tableModel.allEntries()) {
            byPath.put((e.currentPath != null ? e.currentPath : e.file.toPath()).toString(), e);
        }
        Map<String, Map<Field, String>> restore = new LinkedHashMap<>();
        Map<String, Boolean> memory = new HashMap<>();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] f = lines.get(i);
            try {
                restore.computeIfAbsent(f[0], k -> new EnumMap<>(Field.class)).put(Field.valueOf(f[1]), f[2]);
                memory.put(f[0], "mémoire".equals(f[4]));
            } catch (IllegalArgumentException ignored) { /* champ inconnu */ }
        }
        List<Fix<FileEntry>> fixes = new ArrayList<>();
        for (Map.Entry<String, Map<Field, String>> r : restore.entrySet()) {
            FileEntry e = byPath.get(r.getKey());
            // Fichier plus dans la liste (redémarrage, dossier retiré) : réécrit directement, sauf une correction restée
            // en mémoire, qui n'a jamais touché le disque.
            if (e != null || !memory.get(r.getKey())) fixes.add(new Fix<>(e, Paths.get(r.getKey()), r.getValue()));
        }
        if (!canRun()) return;
        markUndone(journal);
        run(I18n.t("Annulation"), fixes, false, journalDir().resolve(journal.getFileName().toString()
                .replace(".tsv", UNDONE + "_retour.tsv")));
    }

    private static void markUndone(Path journal) {
        try {
            Files.move(journal, journal.resolveSibling(journal.getFileName().toString().replace(".tsv", UNDONE + ".tsv")));
        } catch (IOException ignored) {}
    }

    private JPanel buildFooter() {
        JButton btnClose = new JButton(I18n.t("Fermer"));
        btnClose.addActionListener(e -> onClose.run());
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        p.add(lblStatus, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(btnShow);
        buttons.add(btnFix);
        buttons.add(btnStop);
        buttons.add(btnUndo);
        buttons.add(btnRefresh);
        buttons.add(btnClose);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }
}
