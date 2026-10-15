package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.ReplayGainAnalyzer;
import com.opentagger.MetadataCache;
import com.opentagger.TagEnrichment;
import com.opentagger.TagReader;
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
import java.io.File;
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
 * <p>Chaque correction note les anciennes valeurs dans ~/.opentagger/corrections/*.tsv (et les images retirées à côté,
 * dans *_images/) ; « Annuler la dernière correction » les remet, même après un redémarrage. Seuls les champs corrigés sont écrits (TagWriter.writeFields) :
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
        // Lignes sans fichier atténuées, nombres en couleur, texte complet au survol (la colonne Correction est souvent
        // coupée) — sans ça, 16 lignes de poids égal dont la moitié à 0 noyaient les vrais problèmes.
        javax.swing.table.DefaultTableCellRenderer cell = new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                Object shownValue = v instanceof Integer n ? MainFrame.number(n) : v;
                super.getTableCellRendererComponent(t, shownValue, sel, foc, row, col);
                int m = t.convertRowIndexToModel(row);
                Object found = model.getValueAt(m, 1), fixable = model.getValueAt(m, 2);
                boolean empty = Integer.valueOf(0).equals(found) && Integer.valueOf(0).equals(fixable);
                setHorizontalAlignment(v instanceof Integer ? RIGHT : LEFT);
                setToolTipText(v instanceof String str && !str.isBlank() ? str : null);
                if (!sel) {
                    Color base = UIManager.getColor("Label.foreground");
                    if (empty) setForeground(UIManager.getColor("Label.disabledForeground"));
                    else if (col == 1 && v instanceof Integer n && n > 0) setForeground(new Color(0xE0A040));
                    else if (col == 2 && v instanceof Integer n && n > 0) setForeground(new Color(0x66BB6A));
                    else setForeground(base);
                }
                setFont(getFont().deriveFont(!empty && col == 0 ? Font.BOLD : Font.PLAIN));
                return this;
            }
        };
        for (int i = 0; i < COLS.length; i++) table.getColumnModel().getColumn(i).setCellRenderer(cell);
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
        for (Check c : rows) model.addRow(new Object[]{label(c), r.found(c).size(), fixable(c).size(), howFixed(c, r)});
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
        btnShow.setEnabled(idle && c != null && !shown(c).isEmpty());
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
            case NO_COVER -> I18n.t("Sans pochette");
            case MULTI_COVER -> I18n.t("Plusieurs images dans le fichier (Fix Album Art)");
            case COVER_MISMATCH -> I18n.t("Pochettes différentes dans un même album");
            case NO_REPLAYGAIN -> I18n.t("Volume non analysé (ReplayGain)");
            case NO_ALBUM_GAIN -> I18n.t("Volume d'album non analysé (ReplayGain album)");
            case MISSING_FILE -> I18n.t("Fichier introuvable sur le disque");
        };
    }

    private static String howFixed(Check c, LibraryChecks.Report<FileEntry> r) {
        int notRead = r == null ? 0 : r.coversNotRead().size();
        return switch (c) {
            case NO_TITLE, NO_ARTIST, NO_ALBUM -> I18n.t("à identifier : Afficher, tout sélectionner, « Tagger la sélection »");
            case NO_YEAR -> I18n.t("année reprise des autres pistes de l'album quand elles sont d'accord");
            case NO_GENRE -> I18n.t("genre repris des autres pistes de l'album quand elles sont d'accord");
            case ALBUM_ARTIST_SPLIT -> I18n.t("même artiste de l'album partout (graphie majoritaire, Various Artists pour une compilation)");
            case NO_ALBUM_ARTIST -> I18n.t("Various Artists (3 interprètes ou plus) ou l'interprète principal");
            case CASE -> I18n.t("Majuscule à chaque mot (jamais sur des tags venus de MusicBrainz)");
            case SWAPPED -> I18n.t("remis dans l'ordre du nom de fichier « Artiste - Titre »");
            case NO_TRACK -> I18n.t("numéro pris dans le nom du fichier (« 05 - … »)");
            case NO_COVER -> notRead > 0
                    ? I18n.t("cover.jpg / folder.jpg du dossier, sinon recherche en ligne — parmi les fichiers déjà lus (%d pas encore)", notRead)
                    : I18n.t("cover.jpg / folder.jpg du dossier, sinon recherche en ligne (fichiers identifiés)");
            case MULTI_COVER -> notRead > 0
                    ? I18n.t("garde la pochette de face, retire les autres (gardées pour annuler) — lit aussi les %d fichier(s) "
                            + "jamais vérifiés (≈ 1 s chacun sur MyBook, « Arrêter » quand tu veux, ça reprend où ça s'est arrêté)", notRead)
                    : I18n.t("garde la pochette de face, retire les autres (gardées pour annuler)");
            case COVER_MISMATCH -> notRead > 0
                    ? I18n.t("à vérifier à l'œil — %d fichier(s) pas encore lus : « Corriger » sur la ligne Fix Album Art les lit", notRead)
                    : I18n.t("à vérifier à l'œil (Afficher)");
            case NO_REPLAYGAIN -> I18n.t("analyse du volume, fichier par fichier (long)");
            case NO_ALBUM_GAIN -> I18n.t("analyse du volume de chaque album entier (long)");
            case MISSING_FILE -> I18n.t("retirés de la liste (rien n'est supprimé)");
        };
    }

    // ── Afficher ──────────────────────────────────────────────────────────────

    /** Fichiers que « Afficher » montre : ceux qui ont le problème (et, pour Fix Album Art, ceux jamais vérifiés). */
    private List<FileEntry> shown(Check c) {
        if (report == null) return List.of();
        if (c != Check.MULTI_COVER) return report.found(c);
        List<FileEntry> all = new ArrayList<>(report.found(c));
        all.addAll(report.coversNotRead());
        return all;
    }

    private void showFiles() {
        Check c = selected();
        if (c == null || shown(c).isEmpty()) return;
        owner.filterByEntries(label(c), new HashSet<>(shown(c)));
        onClose.run();
    }

    // ── Corriger ──────────────────────────────────────────────────────────────

    /** Fichiers (ou albums) que « Corriger » traiterait pour ce problème. */
    private List<?> fixable(Check c) {
        if (report == null) return List.of();
        return switch (c) {
            case MISSING_FILE -> report.found(c);
            // Un fichier en attente aura son volume calculé par le taguage lui-même.
            case NO_REPLAYGAIN -> report.found(c).stream().filter(e -> e.status == FileEntry.Status.TAGGED
                    || e.status == FileEntry.Status.SKIPPED || e.status == FileEntry.Status.ERROR).toList();
            case NO_ALBUM_GAIN -> report.found(c);
            case NO_COVER -> report.found(c).stream().filter(e -> imagesWritable(pathOf(e))).toList();
            case MULTI_COVER -> shown(c).stream().filter(e -> imagesWritable(pathOf(e))).toList();
            case COVER_MISMATCH -> List.of();
            default -> report.fixes(c);
        };
    }

    private static Path pathOf(FileEntry e) { return e.currentPath != null ? e.currentPath : e.file.toPath(); }

    private static boolean imagesWritable(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        return !n.endsWith(".mpc") && !com.opentagger.FfmpegTagIO.handles(p.toFile());
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
        boolean needsFfmpeg = c == Check.NO_REPLAYGAIN || c == Check.NO_ALBUM_GAIN;
        if (needsFfmpeg && !ReplayGainAnalyzer.isAvailable()) {
            JOptionPane.showMessageDialog(this, I18n.t("ffmpeg (filtre replaygain) est introuvable : impossible d'analyser le volume."),
                    label(c), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!canRun()) return;
        TagWriter writer = new TagWriter();
        List<Task> tasks = new ArrayList<>();
        List<FileEntry> online = Collections.synchronizedList(new ArrayList<>());
        Runnable after = () -> {};
        int busy = 0;
        switch (c) {
            case NO_REPLAYGAIN, MULTI_COVER, NO_COVER -> {
                String msg = I18n.t("<html><b>%s</b> : traiter %d fichier(s) ?<br>%s — « Arrêter » à tout moment, "
                        + "« Annuler la dernière correction » ensuite.</html>", label(c), n, howFixed(c, report));
                if (JOptionPane.showConfirmDialog(this, msg, I18n.t("Corriger"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                for (Object o : fixable(c)) {
                    FileEntry e = (FileEntry) o;
                    if (busy(e)) { busy++; continue; }
                    Path p = pathOf(e);
                    tasks.add(new Task(p.getFileName().toString(),
                            c == Check.NO_REPLAYGAIN ? j -> trackGain(j, writer, e, p)
                            : c == Check.MULTI_COVER ? j -> fixAlbumArt(j, writer, e, p)
                            : j -> coverFromFolder(j, writer, e, p, online)));
                }
                // Une fois la passe vraiment terminée pour le WorkerHub (il la retire juste après done()) : sinon la
                // recherche de pochettes, qui écrit aussi dans les fichiers, serait refusée comme « encore en cours ».
                if (c == Check.NO_COVER) after = () -> {
                    List<FileEntry> identified = new ArrayList<>(online);
                    identified.removeIf(e -> e.status != FileEntry.Status.TAGGED && e.status != FileEntry.Status.IDENTIFIED);
                    if (identified.isEmpty()) return;
                    javax.swing.Timer t = new javax.swing.Timer(1000, ev -> owner.completeArtworkFor(identified));
                    t.setRepeats(false);
                    t.start();
                };
            }
            case NO_ALBUM_GAIN -> {
                List<List<FileEntry>> albums = report.albumsWithoutGain();
                String msg = I18n.t("<html><b>%s</b> : analyser %d album(s) ?<br>%s — « Arrêter » à tout moment.</html>",
                        label(c), albums.size(), howFixed(c, report));
                if (JOptionPane.showConfirmDialog(this, msg, I18n.t("Corriger"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
                for (List<FileEntry> album : albums) {
                    if (album.stream().anyMatch(this::busy)) { busy += album.size(); continue; }
                    Path dir = pathOf(album.get(0)).getParent();
                    tasks.add(new Task(dir == null ? "?" : String.valueOf(dir.getFileName()), j -> albumGain(j, writer, album)));
                }
            }
            default -> {
                // Comme le Case Checker de MediaMonkey : tout est montré « avant → après » et décochable avant d'écrire.
                List<Fix<FileEntry>> chosen = FixPreviewDialog.choose(this, label(c), howFixed(c, report), report.fixes(c),
                        CorrectionsPanel::tagsOf);
                if (chosen == null || chosen.isEmpty() || !canRun()) return;
                for (Fix<FileEntry> f : chosen) {
                    if (busy(f.ref())) { busy++; continue; }
                    tasks.add(fieldTask(writer, f.ref(), pathOf(f.ref()), f.changes()));
                }
            }
        }
        run(label(c), tasks, busy, newJournal(c.name().toLowerCase(Locale.ROOT)), after);
    }

    /** En cours de traitement par le taguage, ou retiré de la liste depuis l'analyse : laissé pour plus tard. */
    private boolean busy(FileEntry e) {
        return e.status == FileEntry.Status.PROCESSING || tableModel.indexOf(e) < 0;
    }

    /** Lance les corrections, ou explique pourquoi pas (une autre passe écrit déjà dans les fichiers). */
    private boolean canRun() {
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.LIBRARY_FIX);
        if (blockers.isEmpty()) return true;
        JOptionPane.showMessageDialog(this, I18n.t("Encore en cours : %s — réessaie quand c'est fini.", String.join(", ", blockers)),
                I18n.t("Corriger"), JOptionPane.INFORMATION_MESSAGE);
        return false;
    }

    // ── Les tâches (en arrière-plan) ──────────────────────────────────────────

    /** Une étape de fond : écrit ce qu'il faut, le note au journal, rend ce qui change en mémoire (par fichier). */
    @FunctionalInterface private interface Step { Map<FileEntry, Map<Field, String>> run(Journal j) throws Exception; }
    private record Task(String name, Step step) {}

    /** Champs simples. Fichier identifié pas encore enregistré : en mémoire seulement (l'Enregistrement l'écrira). Fichier
     *  plus dans la liste (annulation après redémarrage) : écrit directement. */
    private static Task fieldTask(TagWriter writer, FileEntry e, Path p, Map<Field, String> changes) {
        return new Task(p.getFileName().toString(), j -> {
            boolean memory = e != null && e.status == FileEntry.Status.IDENTIFIED;
            TagInfo before = e == null ? null : tagsOf(e);
            if (!memory) write(writer, p, changes);
            for (Map.Entry<Field, String> ch : changes.entrySet())
                j.line(p, ch.getKey().name(), before == null ? "" : ch.getKey().get(before), ch.getValue(), memory);
            return e == null ? Map.of() : Map.of(e, changes);
        });
    }

    private static Map<FileEntry, Map<Field, String>> trackGain(Journal j, TagWriter writer, FileEntry e, Path p) throws Exception {
        ReplayGainAnalyzer.RGResult res = new ReplayGainAnalyzer().analyze(p.toString());
        if (res == null) throw new Exception(I18n.t("analyse impossible"));
        Map<Field, String> ch = new EnumMap<>(Field.class);
        ch.put(Field.RG_TRACK_GAIN, res.trackGain());
        ch.put(Field.RG_TRACK_PEAK, res.trackPeak());
        boolean memory = e.status == FileEntry.Status.IDENTIFIED;
        if (!memory) write(writer, p, ch);
        for (Map.Entry<Field, String> c : ch.entrySet()) j.line(p, c.getKey().name(), "", c.getValue(), memory);
        return Map.of(e, ch);
    }

    private static Map<FileEntry, Map<Field, String>> albumGain(Journal j, TagWriter writer, List<FileEntry> album) throws Exception {
        List<String> paths = new ArrayList<>();
        for (FileEntry e : album) paths.add(pathOf(e).toString());
        ReplayGainAnalyzer.RGResult res = ReplayGainAnalyzer.analyzeAlbum(paths);
        if (res == null) throw new Exception(I18n.t("analyse impossible"));
        Map<FileEntry, Map<Field, String>> out = new IdentityHashMap<>();
        for (FileEntry e : album) {
            Map<Field, String> ch = new EnumMap<>(Field.class);
            ch.put(Field.RG_ALBUM_GAIN, res.trackGain());
            ch.put(Field.RG_ALBUM_PEAK, res.trackPeak());
            Path p = pathOf(e);
            boolean memory = e.status == FileEntry.Status.IDENTIFIED;
            try {
                if (!memory) write(writer, p, ch);
            } catch (Exception ex) {
                continue;   // Opus/AAC… : volume d'album non inscriptible champ par champ, les autres pistes l'ont quand même
            }
            for (Map.Entry<Field, String> c : ch.entrySet()) j.line(p, c.getKey().name(), "", c.getValue(), memory);
            out.put(e, ch);
        }
        return out;
    }

    /** Fix Album Art (voir TagWriter.fixAlbumArt) ; lit au passage les images d'un fichier jamais vérifié. */
    private static Map<FileEntry, Map<Field, String>> fixAlbumArt(Journal j, TagWriter writer, FileEntry e, Path p) throws Exception {
        TagWriter.AlbumArtFix r = writer.fixAlbumArt(p.toFile());
        for (TagWriter.Image im : r.removed())
            j.line(p, "COVER_REMOVED", j.saveImage(im) + "|" + im.pictureType() + "|" + nz(im.mime()) + "|" + clean(nz(im.description())), "", false);
        j.remember(e, p, r.coverInfo());
        return Map.of(e, Map.of(Field.COVER_INFO, r.coverInfo()));
    }

    /** Sans pochette : celle du dossier (cover.jpg, folder.jpg…) si elle existe ; sinon le fichier rejoint la recherche
     *  en ligne lancée à la fin (Compléter les pochettes, pour les fichiers identifiés). Jamais supprimée du dossier. */
    private static Map<FileEntry, Map<Field, String>> coverFromFolder(Journal j, TagWriter writer, FileEntry e, Path p,
                                                                     List<FileEntry> online) throws Exception {
        Path local = TagEnrichment.findLocalCover(p.getParent() == null ? null : p.getParent().toFile());
        if (local == null) { online.add(e); return Map.of(); }
        writer.writeCoverOnly(p.toFile(), local);
        String info = "1:" + TagReader.crc(Files.readAllBytes(local));
        j.line(p, "COVER_ADDED", "", local.getFileName().toString(), false);
        j.remember(e, p, info);
        return Map.of(e, Map.of(Field.COVER_INFO, info));
    }

    private static void write(TagWriter writer, Path path, Map<Field, String> changes) throws Exception {
        Map<FieldKey, String> std = new EnumMap<>(FieldKey.class);
        Map<String, String> custom = new LinkedHashMap<>();
        for (Map.Entry<Field, String> ch : changes.entrySet()) {
            if (ch.getKey().key != null) std.put(ch.getKey().key, ch.getValue());
            else if (ch.getKey().customName() != null) custom.put(ch.getKey().customName(), ch.getValue());
        }
        if (!std.isEmpty() || !custom.isEmpty()) writer.writeFields(path.toFile(), std, custom);
    }

    /** Journal d'une passe : une ligne TSV par champ (chemin, champ, avant, après, fichier|mémoire), les images retirées
     *  dans le dossier voisin *_images/, et ce qu'on a appris des images, gardé dans le cache du scan. */
    private static final class Journal implements AutoCloseable {
        private final Path file;
        private final java.io.BufferedWriter out;
        private MetadataCache cache;
        private int images;

        Journal(Path file) throws IOException {
            this.file = file;
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        void line(Path p, String field, String old, String nw, boolean memory) throws IOException {
            out.write(String.join("\t", p.toString(), field, clean(old), clean(nw), memory ? "mémoire" : "fichier"));
            out.newLine();
            out.flush();
        }

        String saveImage(TagWriter.Image im) throws IOException {
            Path dir = imagesDir(file);
            Files.createDirectories(dir);
            String ext = nz(im.mime()).contains("png") ? ".png" : ".jpg";
            String name = (++images) + ext;
            Files.write(dir.resolve(name), im.data());
            return name;
        }

        /** Les images lues sont gardées dans le cache du scan : le prochain démarrage n'aura pas à les relire. Seulement si
         *  les tags en mémoire sont ceux du disque (fichier pas ré-identifié dans cette session). */
        void remember(FileEntry e, Path p, String coverInfo) {
            if (e == null || e.result != null || e.current == null) return;
            try {
                if (cache == null) cache = new MetadataCache();
                TagInfo t = e.current.copy();
                t.coverInfo = coverInfo;
                File f = p.toFile();
                cache.putScanCache(f.getAbsolutePath(), f.lastModified(), f.length(), t);
            } catch (Exception ignored) { /* simple accélération du prochain démarrage */ }
        }

        @Override public void close() throws IOException {
            out.close();
            if (cache != null) cache.close();
        }
    }

    private static Path imagesDir(Path journal) {
        return journal.resolveSibling(journal.getFileName().toString().replace(".tsv", "_images"));
    }

    private void run(String what, List<Task> tasks, int skipped, Path journalFile, Runnable after) {
        if (tasks.isEmpty()) {
            lblStatus.setText(I18n.t("%s : rien à faire maintenant (%d fichier(s) en cours de traitement).", what, skipped));
            return;
        }
        stopRequested = false;
        btnStop.setVisible(true);
        updateButtons();
        SwingWorker<Void, Map<FileEntry, Map<Field, String>>> worker = new SwingWorker<>() {
            final List<String> errors = Collections.synchronizedList(new ArrayList<>());
            int done;

            @Override protected Void doInBackground() throws Exception {
                try (Journal j = new Journal(journalFile)) {
                    for (Task t : tasks) {
                        if (stopRequested || isCancelled()) break;
                        try {
                            publish(t.step().run(j));
                        } catch (Exception ex) {
                            errors.add(t.name() + " : " + ex.getMessage());
                        }
                    }
                }
                return null;
            }

            @Override protected void process(List<Map<FileEntry, Map<Field, String>>> chunk) {
                for (Map<FileEntry, Map<Field, String>> m : chunk) {
                    m.forEach(CorrectionsPanel.this::apply);
                    done++;
                }
                lblStatus.setText(I18n.t("%s : %d / %d…", what, done, tasks.size()));
            }

            @Override protected void done() {
                btnStop.setVisible(false);
                String msg = I18n.t("%s : %d traité(s)", what, done)
                        + (skipped > 0 ? I18n.t(", %d en cours de traitement laissé(s) pour plus tard", skipped) : "")
                        + (errors.isEmpty() ? "." : I18n.t(", %d erreur(s) — ex. %s", errors.size(), errors.get(0)));
                System.out.println("[OT] À corriger — " + msg + " Journal : " + journalFile);
                try {
                    if (Files.size(journalFile) == 0) Files.deleteIfExists(journalFile);
                } catch (IOException ignored) {}
                afterAnalysis = msg;
                refresh();
                after.run();
            }
        };
        WorkerHub.get().submit(WorkerHub.TaskKind.LIBRARY_FIX, I18n.t("Corrections (%s)", what), worker, () -> stopRequested = true);
        lblStatus.setText(I18n.t("%s : 0 / %d…", what, tasks.size()));
    }

    /** Sur l'EDT : la correction écrite (ou à écrire à l'Enregistrement) devient aussi la valeur affichée. */
    private void apply(FileEntry e, Map<Field, String> changes) {
        for (TagInfo t : new TagInfo[]{e.current, e.result}) {
            if (t == null) continue;
            for (Map.Entry<Field, String> ch : changes.entrySet()) ch.getKey().set(t, ch.getValue());
        }
        tableModel.update(e);
    }

    private static String clean(String s) { return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('|', '/'); }
    private static String nz(String s) { return s == null ? "" : s; }

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

        Map<String, FileEntry> byPath = new HashMap<>();
        for (FileEntry e : tableModel.allEntries()) byPath.put(pathOf(e).toString(), e);
        // Par fichier, dernier changement annulé en premier.
        Map<String, Map<Field, String>> fields = new LinkedHashMap<>();
        Map<String, List<TagWriter.Image>> removedImages = new LinkedHashMap<>();
        Set<String> addedCover = new LinkedHashSet<>();
        Map<String, Boolean> memory = new HashMap<>();
        Path images = imagesDir(journal);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] f = lines.get(i);
            memory.put(f[0], "mémoire".equals(f[4]));
            switch (f[1]) {
                case "COVER_ADDED" -> addedCover.add(f[0]);
                case "COVER_REMOVED" -> {
                    String[] im = f[2].split("\\|", -1);
                    try {
                        removedImages.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new TagWriter.Image(
                                Files.readAllBytes(images.resolve(im[0])), im.length > 2 ? im[2] : "",
                                im.length > 1 ? Integer.parseInt(im[1]) : 0, im.length > 3 ? im[3] : ""));
                    } catch (Exception ignored) { /* image introuvable : rien à remettre */ }
                }
                default -> {
                    try { fields.computeIfAbsent(f[0], k -> new EnumMap<>(Field.class)).put(Field.valueOf(f[1]), f[2]); }
                    catch (IllegalArgumentException ignored) { /* champ inconnu */ }
                }
            }
        }
        TagWriter writer = new TagWriter();
        List<Task> tasks = new ArrayList<>();
        for (Map.Entry<String, Map<Field, String>> r : fields.entrySet()) {
            FileEntry e = byPath.get(r.getKey());
            // Fichier plus dans la liste (redémarrage, dossier retiré) : réécrit directement, sauf une correction restée
            // en mémoire, qui n'a jamais touché le disque.
            if (e != null || !memory.get(r.getKey())) tasks.add(fieldTask(writer, e, Paths.get(r.getKey()), r.getValue()));
        }
        for (Map.Entry<String, List<TagWriter.Image>> r : removedImages.entrySet()) {
            Path p = Paths.get(r.getKey());
            FileEntry e = byPath.get(r.getKey());
            tasks.add(new Task(p.getFileName().toString(), j -> {
                writer.addImages(p.toFile(), r.getValue());
                return e == null ? Map.of() : Map.of(e, Map.of(Field.COVER_INFO, ""));   // à relire
            }));
        }
        for (String path : addedCover) {
            Path p = Paths.get(path);
            FileEntry e = byPath.get(path);
            tasks.add(new Task(p.getFileName().toString(), j -> {
                writer.removeImages(p.toFile());
                return e == null ? Map.of() : Map.of(e, Map.of(Field.COVER_INFO, "0"));
            }));
        }
        if (!canRun()) return;
        markUndone(journal);
        run(I18n.t("Annulation"), tasks, 0, journalDir().resolve(journal.getFileName().toString()
                .replace(".tsv", UNDONE + "_retour.tsv")), () -> {});
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
