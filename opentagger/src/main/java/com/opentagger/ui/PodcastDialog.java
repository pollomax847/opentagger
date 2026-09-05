package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.I18n;
import com.opentagger.PodcastMatcher.MatchResult;
import com.opentagger.PodcastRssClient.*;
import com.opentagger.PodcastSearchClient.PodcastResult;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Logger;

/**
 * Dialog de taguage podcast.
 *
 * Workflow :
 *  1. L'utilisateur tape un nom de podcast ou colle une URL RSS
 *  2. Si nom → recherche iTunes → liste de résultats → sélection
 *  3. Si URL directe → fetch immédiat
 *  4. Matching automatique fichier ↔ épisode
 *  5. Tableau de résultats (matchés en vert, non-matchés en orange)
 *  6. Bouton "Tagger X fichiers"
 */
public class PodcastDialog extends JDialog {

    private static final Logger LOG = Logger.getLogger(PodcastDialog.class.getName());

    private final List<FileEntry> files;
    private final FileTableModel  tableModel;
    private final MainFrame       mainFrame;

    // ── Widgets ───────────────────────────────────────────────────────────────
    private final JTextField      tfSearch   = new JTextField(35);
    private final JButton         btnSearch  = new JButton(I18n.t("Rechercher"));
    private final JList<PodcastResult> lstResults = new JList<>();
    private final JScrollPane     scrollResults;
    private final JLabel          lblFeedStatus = new JLabel(" ");

    private final DefaultTableModel matchModel;
    private final JTable            matchTable;
    private final JScrollPane       scrollMatch;
    private final JLabel            lblMatchStatus = new JLabel(" ");
    private final JButton           btnTag  = new JButton(I18n.t("Tagger 0 fichiers"));
    private final JButton           btnClose= new JButton(I18n.t("Fermer"));
    private final JProgressBar      progress= new JProgressBar();

    // ── État ─────────────────────────────────────────────────────────────────
    private PodcastFeed       currentFeed;
    private List<MatchResult> currentMatches = new ArrayList<>();

    // ── Colonnes du tableau de matching ──────────────────────────────────────
    private static final String[] COLS = {
        I18n.t("Fichier"), I18n.t("Épisode associé"), I18n.t("Durée"), I18n.t("Saison·Ep")};

    public PodcastDialog(MainFrame owner, List<FileEntry> files, FileTableModel tableModel) {
        super(owner, I18n.t("Tagger comme podcast"), true);
        this.files      = files;
        this.tableModel = tableModel;
        this.mainFrame  = owner;

        matchModel = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        matchTable = new JTable(matchModel);
        matchTable.setRowHeight(22);
        matchTable.getColumnModel().getColumn(0).setPreferredWidth(200);
        matchTable.getColumnModel().getColumn(1).setPreferredWidth(280);
        matchTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        matchTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        matchTable.setDefaultRenderer(Object.class, new MatchCellRenderer());

        scrollResults = new JScrollPane(lstResults);
        scrollResults.setPreferredSize(new Dimension(600, 130));
        scrollResults.setVisible(false);

        scrollMatch = new JScrollPane(matchTable);
        scrollMatch.setPreferredSize(new Dimension(660, 250));
        scrollMatch.setVisible(false);

        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(700, 380));
        setLocationRelativeTo(owner);
        wireEvents();

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { dispose(); } });
    }

    /** Arrête le retry en file d'attente (queueTagWriteWhenFree()) s'il est encore actif — sinon un
     *  Timer resterait à tourner toutes les 10s après la fermeture du dialogue, tentant d'écrire
     *  sur des matches dont l'utilisateur a explicitement fermé la fenêtre. */
    @Override
    public void dispose() {
        if (queuedRetryTimer != null) queuedRetryTimer.stop();
        super.dispose();
    }

    // ── Builders UI ───────────────────────────────────────────────────────────

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        root.add(buildSearchPanel(), BorderLayout.NORTH);
        root.add(buildCenterPanel(), BorderLayout.CENTER);
        root.add(buildFooter(),      BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildSearchPanel() {
        JLabel lbl = new JLabel(I18n.t("Nom du podcast ou URL RSS : "));
        lbl.putClientProperty("FlatLaf.style", "font: bold 12 $defaultFont");

        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        searchRow.add(lbl);
        searchRow.add(tfSearch);
        searchRow.add(btnSearch);

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(searchRow);

        JLabel hint = new JLabel(I18n.t("  Tapez un nom pour chercher dans iTunes, ou collez directement l'URL RSS."));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        p.add(hint);
        p.add(Box.createVerticalStrut(8));

        // Liste résultats (cachée jusqu'à la première recherche)
        lstResults.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstResults.setVisibleRowCount(5);
        p.add(scrollResults);

        lblFeedStatus.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        p.add(lblFeedStatus);
        p.add(Box.createVerticalStrut(6));

        p.setBorder(new CompoundBorder(
            new MatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(0, 0, 8, 0)));
        return p;
    }

    private JPanel buildCenterPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 0, 0, 0));

        JLabel lblTitle = new JLabel(I18n.t("Correspondances fichiers ↔ épisodes"));
        lblTitle.putClientProperty("FlatLaf.style", "font: bold 12 $defaultFont");
        p.add(lblTitle);
        p.add(Box.createVerticalStrut(4));
        p.add(scrollMatch);

        lblMatchStatus.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        p.add(Box.createVerticalStrut(4));
        p.add(lblMatchStatus);
        return p;
    }

    private JPanel buildFooter() {
        progress.setIndeterminate(false);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(200, 14));

        btnTag.setEnabled(false);
        btnTag.putClientProperty("FlatLaf.style", "background: #1b5e20");

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.add(progress);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(btnTag);
        right.add(btnClose);

        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 0, 0, 0)));
        p.add(left,  BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ── Événements ────────────────────────────────────────────────────────────

    private void wireEvents() {
        btnSearch.addActionListener(e -> onSearch());
        btnClose .addActionListener(e -> dispose());
        btnTag   .addActionListener(e -> onTag());

        tfSearch.addActionListener(e -> onSearch());

        lstResults.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) onResultSelected();
            }
        });
        lstResults.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onResultSelected();
        });
    }

    private void onSearch() {
        String input = tfSearch.getText().trim();
        if (input.isBlank()) return;

        if (input.startsWith("http://") || input.startsWith("https://")) {
            fetchFeed(input);
        } else {
            doItunesSearch(input);
        }
    }

    private void doItunesSearch(String query) {
        setBusy(true, I18n.t("Recherche en cours…"));
        new SwingWorker<List<PodcastResult>, Void>() {
            @Override protected List<PodcastResult> doInBackground() throws Exception {
                return PodcastSearchClient.search(query);
            }
            @Override protected void done() {
                setBusy(false, "");
                try {
                    List<PodcastResult> results = get();
                    if (results.isEmpty()) {
                        lblFeedStatus.setText(I18n.t("Aucun résultat pour \"%s\".", query));
                        scrollResults.setVisible(false);
                    } else {
                        DefaultListModel<PodcastResult> model = new DefaultListModel<>();
                        results.forEach(model::addElement);
                        lstResults.setModel(model);
                        scrollResults.setVisible(true);
                        lblFeedStatus.setText(I18n.t("%d résultat(s) — double-cliquez pour sélectionner.", results.size()));
                    }
                    pack();
                } catch (Exception ex) {
                    lblFeedStatus.setText(I18n.t("Erreur : %s", ex.getMessage()));
                    LOG.warning("[Podcast] Recherche iTunes : " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void onResultSelected() {
        PodcastResult sel = lstResults.getSelectedValue();
        if (sel == null) return;
        scrollResults.setVisible(false);
        tfSearch.setText(sel.feedUrl());
        fetchFeed(sel.feedUrl());
    }

    private void fetchFeed(String url) {
        setBusy(true, I18n.t("Récupération du flux RSS…"));
        new SwingWorker<PodcastFeed, Void>() {
            @Override protected PodcastFeed doInBackground() throws Exception {
                return PodcastRssClient.fetch(url);
            }
            @Override protected void done() {
                setBusy(false, "");
                try {
                    currentFeed = get();
                    lblFeedStatus.setText(I18n.t("\"%s\" — %d épisode(s) dans le flux.",
                        currentFeed.showTitle(), currentFeed.episodes().size()));
                    runMatching();
                } catch (Exception ex) {
                    lblFeedStatus.setText(I18n.t("Erreur flux : %s", ex.getMessage()));
                    LOG.warning("[Podcast] Fetch RSS : " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void runMatching() {
        setBusy(true, I18n.t("Matching en cours…"));
        new SwingWorker<List<MatchResult>, Void>() {
            @Override protected List<MatchResult> doInBackground() {
                return PodcastMatcher.match(files, currentFeed.episodes());
            }
            @Override protected void done() {
                setBusy(false, "");
                try {
                    currentMatches = get();
                    populateMatchTable(currentMatches);
                } catch (Exception ex) {
                    lblMatchStatus.setText(I18n.t("Erreur matching : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void populateMatchTable(List<MatchResult> matches) {
        matchModel.setRowCount(0);
        int matched = 0;
        for (MatchResult mr : matches) {
            String episodeName = mr.matched()
                ? formatEpisodeLabel(mr.episode())
                : I18n.t("(non matché — gérer manuellement)");
            String duration = mr.matched() && mr.episode().durationSec() > 0
                ? formatDuration(mr.episode().durationSec())
                : "—";
            String seasonEp = mr.matched()
                ? formatSeasonEp(mr.episode())
                : "";
            matchModel.addRow(new Object[]{mr.file().filename(), episodeName, duration, seasonEp});
            if (mr.matched()) matched++;
        }

        int unmatched = matches.size() - matched;
        String status = I18n.t("%d matché(s) automatiquement", matched);
        if (unmatched > 0) status += I18n.t(", %d non-matché(s) — à gérer manuellement", unmatched);
        lblMatchStatus.setText(status);

        btnTag.setText(I18n.t("Tagger %d fichier(s)", matched));
        btnTag.setEnabled(matched > 0);
        scrollMatch.setVisible(true);
        pack();
    }

    // Retry en file d'attente (bouton "Non" ci-dessous) — champ pour pouvoir l'arrêter proprement
    // si le dialogue est fermé avant qu'il ne se déclenche (voir wireEvents()/windowClosing).
    private javax.swing.Timer queuedRetryTimer;

    private void onTag() {
        if (currentFeed == null || currentMatches.isEmpty()) return;

        long toTag = currentMatches.stream().filter(MatchResult::matched).count();
        int ok = JOptionPane.showConfirmDialog(this,
            I18n.t("Écrire les tags podcast sur %d fichier(s) ?\nShow : %s",
                toTag, currentFeed.showTitle()),
            I18n.t("Confirmer le taguage"), JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;

        // Bug trouvé en direct (2026-08-15) : WorkerHub.submit() lève IllegalStateException si un
        // taguage (ou autre tâche LIBRARY_WRITE conflictuelle) tourne déjà — appelé plus bas SANS
        // vérifier blockerLabels() avant, contrairement à MainFrame.saveAll()/startTagging() qui
        // suivent tous les deux ce garde-fou. Le conflit lui-même est légitime (mêmes fichiers
        // PENDING/SKIPPED que le taguage principal peut traiter au même instant) — pas à supprimer,
        // juste à ne plus planter dessus. Retour utilisateur (même jour) : proposer un vrai choix
        // plutôt qu'un simple message d'échec — arrêter le taguage principal maintenant pour écrire
        // tout de suite, ou mettre en file d'attente pour écrire automatiquement dès que le taguage
        // principal se libère (le taguage sur cette bibliothèque dure des jours, sans ça l'écriture
        // n'aurait jamais d'occasion réelle de partir).
        List<String> blockers = WorkerHub.get().blockerLabels(WorkerHub.TaskKind.PODCAST_TAG);
        if (!blockers.isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                I18n.t("Bloqué par : %s.\n\nArrêter cette tâche maintenant et écrire les tags "
                     + "podcast tout de suite (Oui), ou mettre en file d'attente pour écrire "
                     + "automatiquement dès qu'elle se termine (Non) ?", String.join(", ", blockers)),
                I18n.t("Tâche en cours"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.CLOSED_OPTION) return;
            if (choice == JOptionPane.YES_OPTION) {
                mainFrame.cancelBlockersFor(WorkerHub.TaskKind.PODCAST_TAG);
                doTagWrite();
            } else {
                queueTagWriteWhenFree();
            }
            return;
        }

        doTagWrite();
    }

    /** Réessaie toutes les 10s tant qu'un blocker existe, écrit dès que ça se libère. Arrêté par
     *  windowClosing (voir wireEvents()) si l'utilisateur ferme le dialogue avant. */
    private void queueTagWriteWhenFree() {
        setBusy(true, I18n.t("En file d'attente — écriture dès que possible…"));
        btnTag.setEnabled(false);
        if (queuedRetryTimer != null) queuedRetryTimer.stop();
        queuedRetryTimer = new javax.swing.Timer(10_000, e -> {
            if (!WorkerHub.get().blockerLabels(WorkerHub.TaskKind.PODCAST_TAG).isEmpty()) return;
            queuedRetryTimer.stop();
            doTagWrite();
        });
        queuedRetryTimer.start();
    }

    private void doTagWrite() {
        btnTag.setEnabled(false);
        setBusy(true, I18n.t("Taguage en cours…"));

        PodcastWorker worker = new PodcastWorker(currentMatches, currentFeed, tableModel,
                msg -> lblFeedStatus.setText(msg), mainFrame::appendLog);
        worker.addPropertyChangeListener(evt -> {
            if ("state".equals(evt.getPropertyName())
                    && SwingWorker.StateValue.DONE.equals(evt.getNewValue())) {
                setBusy(false, "");
                String errSuffix = worker.getErrors() > 0
                    ? I18n.t(", %d erreur(s)", worker.getErrors())
                    : "";
                String msg = I18n.t("%d fichier(s) tagué(s)%s.", worker.getTagged(), errSuffix);
                LOG.info("[Podcast] " + msg);
                JOptionPane.showMessageDialog(this, msg, I18n.t("Taguage terminé"),
                        JOptionPane.INFORMATION_MESSAGE);
                dispose();
            }
        });
        // Ce dialogue n'a pas son propre champ worker : jusqu'ici MainFrame (stopAll()/
        // confirmQuit()) n'avait donc aucune visibilité sur un taguage podcast en cours. Passer
        // par le hub partagé règle ça sans que MainFrame ait besoin de connaître PodcastDialog.
        WorkerHub.get().submit(WorkerHub.TaskKind.PODCAST_TAG,
                I18n.t("Taguage podcast : %s", currentFeed.showTitle()), worker, () -> worker.cancel(true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setBusy(boolean busy, String msg) {
        progress.setIndeterminate(busy);
        progress.setVisible(busy);
        lblFeedStatus.setText(msg.isBlank() ? " " : msg);
        btnSearch.setEnabled(!busy);
    }

    private static String formatEpisodeLabel(PodcastEpisode ep) {
        StringBuilder sb = new StringBuilder(ep.title());
        String se = formatSeasonEp(ep);
        if (!se.isBlank()) sb.append("  ").append(se);
        if (!ep.pubDate().isBlank()) sb.append("  (").append(ep.pubDate()).append(")");
        return sb.toString();
    }

    private static String formatSeasonEp(PodcastEpisode ep) {
        if (ep.season() > 0 && ep.episodeNumber() > 0)
            return "S" + ep.season() + "E" + ep.episodeNumber();
        if (ep.episodeNumber() > 0)
            return "Ep." + ep.episodeNumber();
        return "";
    }

    private static String formatDuration(int sec) {
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0
            ? String.format("%d:%02d:%02d", h, m, s)
            : String.format("%d:%02d", m, s);
    }

    // ── Renderer couleur ──────────────────────────────────────────────────────

    private class MatchCellRenderer extends DefaultTableCellRenderer {
        private static final Color GREEN_BG  = new Color(0x1b3a1b);
        private static final Color ORANGE_BG = new Color(0x3a2a00);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean selected, boolean focused, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, col);
            if (row < currentMatches.size()) {
                boolean matched = currentMatches.get(row).matched();
                if (!selected) setBackground(matched ? GREEN_BG : ORANGE_BG);
            }
            return this;
        }
    }
}
