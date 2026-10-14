package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.DiscogsClient;
import com.opentagger.I18n;
import com.opentagger.LastFmClient;
import com.opentagger.LocalCorrector;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.TagEnrichment;
import com.opentagger.TaggerScript;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialogue de correspondance manuelle — permet de rechercher un enregistrement
 * MusicBrainz et de l'appliquer à un fichier dont le score automatique était
 * insuffisant (ou pour corriger un tag auto incorrect).
 *
 * <p>2026-10-14 (demande utilisateur : « plus d'info quand on a un statut autre version, mix ou autre ») : en haut, POURQUOI
 * le fichier est là et quoi faire (autre version, mix long, fichier coupé, audio qui contredit le nom…), le fichier lui-même
 * (durée, taille, dossier, tags actuels, Écouter / Dossier) ; dans la liste, la durée de chaque candidat, l'écart avec le
 * fichier, la version (désambiguïsation MusicBrainz), le type de parution et le pays. Pour « autre version », les autres
 * enregistrements du même morceau sont cherchés d'office et classés du plus proche en durée au plus lointain.
 */
public class MatchDialog extends JDialog {

    private static final String[] COLS = {"Score", I18n.t("Artiste"), I18n.t("Titre"), I18n.t("Version"), I18n.t("Durée"),
            I18n.t("Écart"), I18n.t("Album"), I18n.t("Type"), I18n.t("Année"), I18n.t("Pays")};
    private static final int C_SCORE = 0, C_DUR = 4, C_GAP = 5;

    private final FileEntry       entry;
    private final FileTableModel  tableModel;
    private final Runnable        onApplied;
    private final MusicBrainzClient mb       = new MusicBrainzClient();
    // Même enrichissement genre (Discogs/Last.fm) que les autres pipelines de taguage — sans ça,
    // une correspondance choisie manuellement ici n'avait pas de genre du tout, contrairement au
    // taguage automatique. La pochette n'est plus résolue ici : différée à l'Enregistrement, voir
    // applySelected().
    private final DiscogsClient  discogs  = new DiscogsClient();
    private final LastFmClient   lastFm   = new LastFmClient();
    private final TaggerScript   taggerScript = new TaggerScript();

    private final JTextField     tfArtist;
    private final JTextField     tfTitle;
    private final JLabel         lblStatus;
    private final JTable         resultsTable;
    private final DefaultTableModel resultsModel;
    private final List<TagInfo>  searchResults = new ArrayList<>();
    private final int            fileSec;
    private final Verdict        verdict;
    private JButton              btnApply, btnCancel, btnVersions;

    public MatchDialog(Frame owner, FileEntry entry, FileTableModel tableModel, Runnable onApplied) {
        super(owner, I18n.t("Correspondance manuelle — %s", entry.filename()), true);
        this.entry      = entry;
        this.tableModel = tableModel;
        this.onApplied  = onApplied;
        this.fileSec    = entry.current != null ? entry.current.durationSec : 0;
        this.verdict    = verdictOf(entry);
        setSize(1180, 640);
        setMinimumSize(new Dimension(820, 460));
        setLocationRelativeTo(owner);

        TagInfo ti = entry.activeTags();
        tfArtist  = new JTextField(ti.artist, 22);
        tfTitle   = new JTextField(ti.title,  28);
        lblStatus = new JLabel(" ");
        lblStatus.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        resultsModel = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c == C_SCORE || c == C_DUR || c == C_GAP ? Integer.class : String.class; }
        };
        resultsTable = new JTable(resultsModel) {
            @Override public String getToolTipText(java.awt.event.MouseEvent e) {
                int r = rowAtPoint(e.getPoint());
                if (r < 0) return null;
                int m = convertRowIndexToModel(r);
                return m < searchResults.size() ? tooltip(searchResults.get(m)) : null;
            }
        };
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.setRowHeight(24);
        resultsTable.setShowHorizontalLines(false);
        resultsTable.setIntercellSpacing(new Dimension(0, 0));
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.getTableHeader().setReorderingAllowed(false);

        TableColumnModel cm = resultsTable.getColumnModel();
        int[] widths = {56, 170, 220, 150, 56, 70, 190, 110, 50, 44};
        for (int i = 0; i < widths.length; i++) cm.getColumn(i).setPreferredWidth(widths[i]);
        cm.getColumn(C_SCORE).setMaxWidth(64);
        cm.getColumn(C_DUR).setMaxWidth(70);
        cm.getColumn(C_GAP).setMaxWidth(84);
        cm.getColumn(8).setMaxWidth(60);
        cm.getColumn(9).setMaxWidth(54);
        cm.getColumn(C_SCORE).setCellRenderer(new ScoreRenderer());
        cm.getColumn(C_DUR).setCellRenderer(new DurationRenderer());
        cm.getColumn(C_GAP).setCellRenderer(new GapRenderer());

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        if (verdict != null) north.add(buildVerdictPanel());
        north.add(buildFilePanel());
        north.add(buildSearchBar());

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        getContentPane().add(buildFooter(),    BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });

        // Pré-charger les candidats du batch ou lancer une recherche
        if (entry.candidates != null && !entry.candidates.isEmpty()) {
            fill(entry.candidates);
            lblStatus.setText(I18n.t("%d candidat(s) du batch — affinez la recherche si besoin", entry.candidates.size()));
            if (verdict != null && verdict.kind() == Kind.VERSION) searchVersions();
        } else if (verdict != null && verdict.kind() == Kind.VERSION) {
            searchVersions();
        } else {
            search();
        }
    }

    // ── Pourquoi ce fichier est là ────────────────────────────────────────────

    enum Kind { VERSION, MIX, CUT, CONTRADICTS, SHAZAM_ONLY, GENERIC, LOW_SCORE, GROUP, PODCAST, UNREADABLE, WAITING,
                MISSING, NOT_FOUND, ALREADY }

    record Verdict(Kind kind, String title, String advice, Color color) {}

    private static final Color ORANGE = new Color(220, 150, 40), RED = new Color(210, 70, 60),
            PURPLE = new Color(150, 110, 210), GREY = new Color(130, 130, 130), GREEN = new Color(70, 170, 90);

    /** Le verdict du taguage, reconnu à son motif (SkipReason) et au début de son message. */
    static Verdict verdictOf(FileEntry e) {
        String msg = e.message == null ? "" : e.message;
        com.opentagger.model.SkipReason r = e.skipReason;
        if (e.status == FileEntry.Status.TAGGED || e.status == FileEntry.Status.IDENTIFIED)
            return new Verdict(Kind.ALREADY, I18n.t("Déjà identifié"),
                    I18n.t("La correspondance choisie ici remplacera l'actuelle ; elle sera écrite à l'Enregistrement."), GREEN);
        if (r == com.opentagger.model.SkipReason.DURATION_MISMATCH) {
            if (starts(msg, "Mix ou enregistrement long (%s) — pas un morceau seul"))
                return new Verdict(Kind.MIX, I18n.t("Mix ou enregistrement long"),
                        I18n.t("Plus de 10 minutes et au moins le double du morceau trouvé : mix DJ, concert, face entière… Ce n'est "
                                + "pas un morceau seul. Une émission : Import / Export → « Tagger comme podcast… ». Sinon, tu peux "
                                + "quand même choisir un morceau ci-dessous."), PURPLE);
            if (starts(msg, "Fichier coupé : %s au lieu de %s (« %s ») — à retélécharger")
                    || starts(msg, "Fichier tronqué (%s vs MusicBrainz %s, « %s ») → corbeille (essai)"))
                return new Verdict(Kind.CUT, I18n.t("Fichier coupé"),
                        I18n.t("Le fichier s'arrête bien avant la fin du morceau (colonne Écart) : mieux vaut le retélécharger. Tu "
                                + "peux quand même le taguer, l'écouter le confirme."), RED);
            return new Verdict(Kind.VERSION, I18n.t("Autre version du morceau"),
                    I18n.t("Le morceau est sûrement le bon, pas cette version (radio edit, version longue, remix, live…). Les "
                            + "versions MusicBrainz sont listées de la plus proche en durée à la plus lointaine : prends celle "
                            + "dont l'Écart est le plus petit, en vérifiant la colonne Version."), ORANGE);
        }
        if (r == com.opentagger.model.SkipReason.UNREADABLE)
            return new Verdict(Kind.UNREADABLE, I18n.t("Audio illisible"),
                    I18n.t("Le son ne se décode pas (téléchargement incomplet ?) : aucune méthode ne peut l'identifier, il est à "
                            + "retélécharger. « Écouter » le confirme."), RED);
        if (r == com.opentagger.model.SkipReason.PODCAST_PROBABLE)
            return new Verdict(Kind.PODCAST, I18n.t("Podcast probable"),
                    I18n.t("Nom d'épisode et durée d'émission : Import / Export → « Tagger comme podcast… »."), PURPLE);
        if (r == com.opentagger.model.SkipReason.GROUP_MISMATCH)
            return new Verdict(Kind.GROUP, I18n.t("Contredit le reste de l'album"),
                    I18n.t("Les autres pistes du dossier sont rattachées à une autre édition de l'album : choisis la piste de "
                            + "cette édition-là (colonne Album, info-bulle) pour que l'album ne soit pas éclaté."), ORANGE);
        if (r == com.opentagger.model.SkipReason.NETWORK_ERROR)
            return new Verdict(Kind.WAITING, I18n.t("Pas encore vérifié"),
                    I18n.t("Shazam ou le réseau n'ont pas répondu : le fichier sera retenté tout seul. Tu peux aussi chercher ici."), GREY);
        if (r == com.opentagger.model.SkipReason.FILE_MISSING)
            return new Verdict(Kind.MISSING, I18n.t("Fichier introuvable"), I18n.t("Le fichier n'est plus à cet endroit."), RED);
        if (starts(msg, "AcoustID contredit le nom de fichier et les tags (« %s – %s ») — à vérifier"))
            return new Verdict(Kind.CONTRADICTS, I18n.t("L'empreinte audio contredit le nom du fichier"),
                    I18n.t("L'audio ressemble à un autre morceau que celui annoncé par le nom ou les tags : souvent un mauvais "
                            + "téléchargement. Écoute le fichier avant de choisir."), ORANGE);
        if (starts(msg, "Reconnu par SongRec (« %s – %s ») mais inconnu de MusicBrainz, et ni le nom du fichier ni les tags ne le confirment — à vérifier"))
            return new Verdict(Kind.SHAZAM_ONLY, I18n.t("Reconnu par Shazam, inconnu de MusicBrainz"),
                    I18n.t("Shazam a reconnu le morceau mais MusicBrainz ne le connaît pas sous ce nom. Cherche autrement (artiste "
                            + "seul, titre sans parenthèses) — ou laisse les tags tels quels."), ORANGE);
        if (starts(msg, "Identifié seulement comme « %s » (titre générique) — à vérifier"))
            return new Verdict(Kind.GENERIC, I18n.t("Titre générique"),
                    I18n.t("La seule correspondance s'appelle « Track 01 » ou pareil : inutilisable. Cherche avec le nom du fichier."), ORANGE);
        if (r == com.opentagger.model.SkipReason.LOW_SCORE)
            return new Verdict(Kind.LOW_SCORE, I18n.t("Correspondance pas assez sûre"),
                    I18n.t("Les candidats ressemblent sans être certains : compare la durée, l'album et la version avant de choisir."), ORANGE);
        if (r == com.opentagger.model.SkipReason.NOT_IDENTIFIED || e.status == FileEntry.Status.SKIPPED)
            return new Verdict(Kind.NOT_FOUND, I18n.t("Non identifié"),
                    I18n.t("Ni l'empreinte audio (AcoustID), ni Shazam, ni la recherche par le texte n'ont trouvé ce morceau. "
                            + "Cherche à la main avec l'artiste et le titre."), GREY);
        return null;
    }

    /** Le message commence comme ce modèle (traduit), jusqu'à son premier paramètre. */
    private static boolean starts(String msg, String format) {
        String t = I18n.t(format);
        int cut = t.indexOf('%');
        String prefix = cut < 0 ? t : t.substring(0, cut);
        return !prefix.isBlank() && msg.startsWith(prefix);
    }

    private JPanel buildVerdictPanel() {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBorder(new CompoundBorder(new MatteBorder(0, 5, 1, 0, verdict.color()), new EmptyBorder(8, 12, 8, 12)));
        JLabel title = new JLabel(verdict.title());
        title.putClientProperty("FlatLaf.style", "font: bold +2 $defaultFont");
        title.setForeground(verdict.color());
        String msg = entry.message == null || entry.message.isBlank() ? "" : "<b>" + esc(entry.message) + "</b><br>";
        JLabel body = new JLabel("<html><div style='width:900px'>" + msg + esc(verdict.advice()) + "</div></html>");
        p.add(title, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildFilePanel() {
        java.nio.file.Path path = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
        TagInfo cur = entry.current != null ? entry.current : new TagInfo();
        StringBuilder b = new StringBuilder("<html><b>").append(esc(String.valueOf(path.getFileName()))).append("</b>");
        b.append(" &nbsp;·&nbsp; ").append(fileSec > 0 ? FileTableModel.formatDuration(fileSec) : I18n.t("durée inconnue"));
        if (entry.sizeBytes > 0) b.append(" &nbsp;·&nbsp; ").append(String.format("%.1f Mo", entry.sizeBytes / 1048576.0));
        if (fileSec > 0 && entry.sizeBytes > 0) b.append(" &nbsp;·&nbsp; ").append(entry.sizeBytes * 8 / 1000 / fileSec).append(" kb/s");
        b.append("<br><span style='color:#888888'>").append(esc(String.valueOf(path.getParent()))).append("</span><br>");
        String tags = (cur.artist.isBlank() && cur.title.isBlank()) ? I18n.t("aucun")
                : cur.artist + " – " + cur.title + (cur.album.isBlank() ? "" : "  ·  " + cur.album)
                  + (cur.year.isBlank() ? "" : " (" + cur.year + ")");
        b.append(I18n.t("Tags du fichier : ")).append(esc(tags)).append("</html>");

        JButton listen = new JButton("▶  " + I18n.t("Écouter"));
        JButton folder = new JButton("📂  " + I18n.t("Dossier"));
        listen.addActionListener(e -> open(path.toFile()));
        folder.addActionListener(e -> open(path.getParent() == null ? null : path.getParent().toFile()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(listen);
        buttons.add(folder);

        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                new EmptyBorder(8, 12, 8, 12)));
        p.add(new JLabel(b.toString()), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.EAST);
        return p;
    }

    /** Lecteur / gestionnaire de fichiers du système, hors EDT (un montage lent peut bloquer). Jamais de fenêtre modale en
     *  cas d'échec : une ligne dans le statut (voir MainFrame, gel X11 du 2026-09-22). */
    private void open(java.io.File f) {
        if (f == null) return;
        Thread.startVirtualThread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) Desktop.getDesktop().open(f);
                else new ProcessBuilder("xdg-open", f.getAbsolutePath()).start();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> lblStatus.setText(I18n.t("Impossible d'ouvrir : %s", ex.getMessage())));
            }
        });
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── Barre de recherche ────────────────────────────────────────────────────

    private JPanel buildSearchBar() {
        JButton btnSearch = new JButton("🔍  " + I18n.t("Chercher"));
        btnSearch.addActionListener(e -> search());
        tfArtist.addActionListener(e -> search());
        tfTitle .addActionListener(e -> search());
        btnVersions = new JButton(I18n.t("Toutes les versions"));
        btnVersions.setToolTipText(I18n.t("Tous les enregistrements MusicBrainz de ce titre (sans « remix », « live »… entre "
                + "parenthèses), classés du plus proche en durée au plus lointain"));
        btnVersions.addActionListener(e -> searchVersions());

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        p.setBorder(new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")));
        p.add(new JLabel(I18n.t("Artiste :"))); p.add(tfArtist);
        p.add(new JLabel(I18n.t("  Titre :"))); p.add(tfTitle);
        p.add(btnSearch);
        p.add(btnVersions);
        return p;
    }

    private JPanel buildFooter() {
        btnApply  = new JButton("✓  " + I18n.t("Appliquer la sélection"));
        btnCancel = new JButton(I18n.t("Annuler"));
        btnApply .addActionListener(e -> applySelected());
        btnCancel.addActionListener(e -> dispose());
        btnApply.putClientProperty("FlatLaf.style", "background: #1a6030");

        // Statut à gauche du pied de page : dans la barre de recherche, il passait à la ligne et disparaissait.
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(new JLabel(I18n.t("<html><i>Double-clic : appliquer · survol d'une ligne : détails</i></html>")));
        right.add(Box.createHorizontalStrut(8));
        right.add(btnCancel);
        right.add(btnApply);
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
                new EmptyBorder(8, 12, 8, 8)));
        p.add(lblStatus, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        getRootPane().setDefaultButton(btnApply);

        // Double-clic = appliquer directement
        resultsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) applySelected();
            }
        });
        return p;
    }

    // ── Recherche MusicBrainz ─────────────────────────────────────────────────

    private void search() {
        String artist = tfArtist.getText().trim();
        String title  = tfTitle .getText().trim();
        if (artist.isBlank() && title.isBlank()) return;
        lblStatus.setText(I18n.t("Recherche en cours…"));
        resultsModel.setRowCount(0);
        searchResults.clear();

        new SwingWorker<List<TagInfo>, Void>() {
            @Override protected List<TagInfo> doInBackground() throws Exception {
                return mb.searchRecording(artist, title);
            }
            @Override protected void done() {
                try {
                    List<TagInfo> res = get();
                    fill(res);
                    lblStatus.setText(res.isEmpty() ? I18n.t("Aucun résultat.") : I18n.t("%d résultat(s)", res.size()));
                    if (!res.isEmpty()) resultsTable.setRowSelectionInterval(0, 0);
                } catch (Exception ex) {
                    lblStatus.setText(I18n.t("Erreur : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    /** Tous les enregistrements du même morceau (titre sans ce qui est entre parenthèses), ajoutés à la liste, du plus
     *  proche en durée au plus lointain — comme searchMusicbrainz.js de MediaMonkey, qui classe par durée. */
    private void searchVersions() {
        String artist = tfArtist.getText().trim();
        String title  = TaggingWorker.coreTitle(tfTitle.getText().trim());
        if (title.isBlank()) return;
        lblStatus.setText(I18n.t("Recherche des versions de « %s »…", title));
        btnVersions.setEnabled(false);
        List<TagInfo> already = new ArrayList<>(searchResults);
        new SwingWorker<List<TagInfo>, Void>() {
            @Override protected List<TagInfo> doInBackground() throws Exception {
                return mb.searchRecording(artist, title);
            }
            @Override protected void done() {
                btnVersions.setEnabled(true);
                try {
                    List<TagInfo> merged = new ArrayList<>(already);
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (TagInfo t : merged) seen.add(t.recordingMbid + "|" + t.releaseMbid);
                    int added = 0;
                    for (TagInfo t : get()) if (seen.add(t.recordingMbid + "|" + t.releaseMbid)) { merged.add(t); added++; }
                    if (fileSec > 0) merged.sort(java.util.Comparator.comparingInt(MatchDialog.this::absGap));
                    fill(merged);
                    if (!merged.isEmpty()) resultsTable.setRowSelectionInterval(0, 0);
                    lblStatus.setText(fileSec > 0
                            ? I18n.t("%d version(s) de plus — classées de la plus proche (%s) à la plus lointaine", added,
                                     FileTableModel.formatDuration(fileSec))
                            : I18n.t("%d version(s) de plus", added));
                } catch (Exception ex) {
                    lblStatus.setText(I18n.t("Erreur : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    /** Écart de durée en secondes (candidat − fichier), null si l'une des deux est inconnue. */
    private Integer gap(TagInfo t) {
        return fileSec > 0 && t.mbDurationSec > 0 ? t.mbDurationSec - fileSec : null;
    }

    private int absGap(TagInfo t) {
        Integer g = gap(t);
        return g == null ? Integer.MAX_VALUE : Math.abs(g);
    }

    private void fill(List<TagInfo> results) {
        searchResults.clear();
        searchResults.addAll(results);
        resultsModel.setRowCount(0);
        for (TagInfo t : results) {
            resultsModel.addRow(new Object[]{
                t.score, t.artist, t.title, t.comment, t.mbDurationSec > 0 ? t.mbDurationSec : null, gap(t),
                t.album, releaseType(t.releaseType), t.year, t.country
            });
        }
    }

    /** « album;live » → « Album, Live ». */
    static String releaseType(String types) {
        if (types == null || types.isBlank()) return "";
        List<String> out = new ArrayList<>();
        for (String t : types.split("[;,/]")) {
            String k = t.trim().toLowerCase(java.util.Locale.ROOT);
            if (k.isEmpty()) continue;
            out.add(switch (k) {
                case "album" -> I18n.t("Album");
                case "single" -> I18n.t("Single");
                case "ep" -> "EP";
                case "compilation" -> I18n.t("Compilation");
                case "live" -> I18n.t("Live");
                case "soundtrack" -> I18n.t("BO");
                case "remix" -> I18n.t("Remix");
                case "dj-mix" -> I18n.t("Mix DJ");
                case "mixtape/street" -> I18n.t("Mixtape");
                default -> Character.toUpperCase(k.charAt(0)) + k.substring(1);
            });
        }
        return String.join(", ", out);
    }

    private static String tooltip(TagInfo t) {
        StringBuilder b = new StringBuilder("<html>");
        if (!t.albumArtist.isBlank()) b.append(I18n.t("Artiste de l'album : ")).append(esc(t.albumArtist)).append("<br>");
        if (!t.date.isBlank()) b.append(I18n.t("Date : ")).append(esc(t.date)).append("<br>");
        if (!t.releaseStatus.isBlank()) b.append(I18n.t("Statut : ")).append(esc(t.releaseStatus)).append("<br>");
        if (!t.media.isBlank()) b.append(I18n.t("Support : ")).append(esc(t.media)).append("<br>");
        if (!t.label.isBlank()) b.append(I18n.t("Label : ")).append(esc(t.label)).append("<br>");
        if (!t.track.isBlank()) b.append(I18n.t("Piste : ")).append(esc(t.track))
                .append(t.trackTotal.isBlank() ? "" : " / " + esc(t.trackTotal)).append("<br>");
        b.append("MBID : ").append(esc(t.recordingMbid)).append("</html>");
        return b.toString();
    }

    // ── Application ───────────────────────────────────────────────────────────

    private void applySelected() {
        int row = resultsTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez un résultat dans la liste."),
                    I18n.t("Info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int modelRow = resultsTable.convertRowIndexToModel(row);
        if (modelRow >= searchResults.size()) return;
        TagInfo chosen = searchResults.get(modelRow);
        java.nio.file.Path path = entry.currentPath != null ? entry.currentPath : entry.file.toPath();

        btnApply.setEnabled(false);
        btnCancel.setEnabled(false);
        resultsTable.setEnabled(false);
        lblStatus.setText(I18n.t("Enrichissement (genre) en cours…"));

        // Genre (Discogs/Last.fm) dans un SwingWorker, pas directement dans ce listener, sinon ça
        // fige l'EDT le temps des requêtes réseau. Plus d'écriture disque ici — façon Picard,
        // l'identification (même une correspondance choisie manuellement) ne touche jamais le
        // fichier : pochette, renommage, cache et soumission MB sont différés à l'Enregistrement
        // (TagEnrichment.saveEntry(), via SaveWorker) — voir son commentaire pour le pourquoi
        // (notamment la pochette, téléchargée dans un fichier temporaire qui ne survivrait pas à
        // un Enregistrer différé).
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                new LocalCorrector().correct(chosen, path);
                taggerScript.apply(chosen);
                MetadataCache cache = new MetadataCache();
                try {
                    TagEnrichment.enrichGenre(chosen, discogs, lastFm, cache);
                    TagEnrichment.enrichClassicalWork(chosen, mb, cache);
                    TagEnrichment.enrichArtistInfo(chosen, discogs, lastFm, cache);

                    // Empreinte AcoustID : calculée systématiquement après toute identification réussie
                    // (TaggingWorker/BatchProcessor/App le font déjà, comme Picard) — une correspondance
                    // confirmée manuellement est une identification tout aussi réussie. Reste ici : pur
                    // calcul local (fpcalc), ne touche pas le disque du fichier lui-même.
                    if (Config.get().saveAcoustidFingerprints() && chosen.acoustidFingerprint.isBlank()
                            && com.opentagger.FpcalcInstaller.isAvailable()) {
                        try {
                            chosen.acoustidFingerprint = com.opentagger.Fingerprinter.compute(path.toFile()).fingerprint();
                        } catch (Exception ignored) {}
                    }
                    chosen.identificationSource = MetadataCache.SOURCE_MBID;
                } finally {
                    cache.close();
                }
                return null;
            }

            @Override protected void done() {
                try {
                    get();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(MatchDialog.this, I18n.t("Erreur : %s", ex.getMessage()),
                            I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                    btnApply.setEnabled(true);
                    btnCancel.setEnabled(true);
                    resultsTable.setEnabled(true);
                    lblStatus.setText(" ");
                    return;
                }

                entry.result      = chosen;
                entry.status      = FileEntry.Status.IDENTIFIED;
                entry.message     = I18n.t("Sélectionné manuellement, pas encore enregistré");
                entry.candidates  = null;

                tableModel.update(entry);
                if (onApplied != null) onApplied.run();
                dispose();
            }
        }.execute();
    }

    // ── Rendus : score coloré, durée m:ss, écart coloré ───────────────────────

    private static class ScoreRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v == null ? "" : v + "%", sel, foc, row, col);
            setHorizontalAlignment(CENTER);
            if (!sel && v instanceof Integer sc) {
                if      (sc >= 90) setForeground(new Color(60,  200,  80));
                else if (sc >= 70) setForeground(new Color(220, 160,  30));
                else               setForeground(new Color(220,  70,  50));
            }
            return this;
        }
    }

    private static class DurationRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, v instanceof Integer s ? FileTableModel.formatDuration(s) : "", sel, foc, row, col);
            setHorizontalAlignment(CENTER);
            return this;
        }
    }

    /** Écart avec la durée du fichier : vert jusqu'à 10 s, orange jusqu'à 30 s, rouge au-delà. */
    private static class GapRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            String text = "";
            if (v instanceof Integer g) {
                int a = Math.abs(g);
                text = a == 0 ? "=" : (g > 0 ? "+" : "−") + FileTableModel.formatDuration(a);
            }
            super.getTableCellRendererComponent(t, text, sel, foc, row, col);
            setHorizontalAlignment(CENTER);
            if (!sel && v instanceof Integer g) {
                int a = Math.abs(g);
                setForeground(a <= 10 ? new Color(60, 200, 80) : a <= 30 ? new Color(220, 160, 30) : new Color(220, 70, 50));
            }
            return this;
        }
    }
}
