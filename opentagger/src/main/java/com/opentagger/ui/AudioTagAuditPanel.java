package com.opentagger.ui;

import com.opentagger.AudioAuditStore;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Vérifier audio ↔ tags" : lance l'audit (AudioTagAuditWorker) sur un lot de fichiers déjà tagués et
 * passe en revue ceux dont l'audio contredit l'artiste ou le titre écrit dans le fichier (voir
 * AudioTagAudit pour la mesure qui l'a motivé).
 *
 * <p>Panneau de l'onglet « Audio ↔ tags » de SuspectFilesReviewDialog (fusionné le 2026-09-20 avec la revue des durées
 * incohérentes : « trop d'outils dans l'appli », retour utilisateur — une seule entrée de menu pour tous les fichiers
 * suspects).
 *
 * <p>JAMAIS de correction automatique : la fenêtre ne fait que proposer — écouter, relancer
 * l'identification par l'audio seul ("Forcer le re-taguage", via MainFrame.forceRetagOn(), même garde
 * WorkerHub/confirmation que partout ailleurs), ouvrir la correspondance manuelle, ou déclarer "l'audio
 * est bon" (le fichier n'est alors plus jamais signalé tant qu'il ne change pas).
 *
 * <p>Découplée du worker : l'audit peut durer des heures et continue fenêtre fermée ; la fenêtre relit
 * simplement la base d'audit (AudioAuditStore) et l'état instantané (AudioTagAuditWorker.snapshot()) toutes
 * les 3 s. Toute lecture disque (état périmé d'un verdict, ouverture de la base) est faite HORS de l'EDT —
 * même règle que le reste de l'appli depuis le gel de scan de 2026-08-30 (File.length() sur l'EDT).
 */
public class AudioTagAuditPanel extends JPanel {

    private static final int SAMPLE_SIZE = 500;
    private static final double SECONDS_PER_FILE_ESTIMATE = 3.0;

    // « Tous » en tête : c'est le seul périmètre qui ne demande RIEN à l'utilisateur. La version précédente
    // proposait « Fichiers sélectionnés » par défaut, sans dire qu'il fallait d'abord tout sélectionner à la main
    // dans la fenêtre principale (retour utilisateur, 2026-09-20 : 17 392 fichiers sélectionnés à la main).
    private static final int SCOPE_ALL = 0, SCOPE_SELECTION = 1, SCOPE_SAMPLE = 2, SCOPE_FOLDER = 3;

    private static final String[] COLS = {
        I18n.t("Verdict"), I18n.t("Fiabilité"), I18n.t("Fichier"), I18n.t("Tag actuel"),
        I18n.t("Ce que dit l'audio (AcoustID)"), I18n.t("Shazam"), I18n.t("Durée")};

    private final MainFrame       owner;
    private final FileTableModel  tableModel;
    private final Runnable        onClose;
    private final DefaultTableModel model;
    private final JTable          table;
    private final JScrollPane     scroll;
    private final List<AudioAuditStore.Row> rows = new ArrayList<>();

    // Éléments = les constantes SCOPE_* ; le libellé (avec compteurs à jour) est calculé par le renderer.
    private final JComboBox<Integer> cbScope = new JComboBox<>(
            new Integer[]{SCOPE_ALL, SCOPE_SELECTION, SCOPE_SAMPLE, SCOPE_FOLDER});
    private final JLabel lblScopeHelp = new JLabel(" ");
    private int taggedCount = 0, selectedCount = 0;
    private final JCheckBox chkRecheck    = new JCheckBox(I18n.t("Re-vérifier même les fichiers déjà audités"));
    private final JCheckBox chkArtistOnly = new JCheckBox(I18n.t("Afficher aussi les différences d'artiste seul (compilations, mix DJ…)"));
    private final JButton   btnStart = new JButton(I18n.t("Lancer l'audit"));
    private final JButton   btnStop  = new JButton(I18n.t("Arrêter l'audit"));
    private final JLabel    lblStatus = new JLabel(" ");
    private final JLabel    lblCounts = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar();
    private final javax.swing.Timer poll;

    private volatile boolean loading = false;
    private boolean wasRunning = false;
    private boolean loadedOnce = false;

    // Dernier relevé de la base (EDT uniquement) + résultat de la vérification "fichier inchangé" par (chemin, audit).
    private List<AudioAuditStore.Row> lastDbRows = List.of();
    private Map<String, Integer>      lastCounts = Map.of();
    private String                    lastError  = null;
    private final Map<String, Boolean> currentByKey = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean verifying = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile int verifyRemaining = 0;

    /** @param onClose ferme la fenêtre hôte (SuspectFilesReviewDialog). */
    public AudioTagAuditPanel(MainFrame owner, FileTableModel tableModel, Runnable onClose) {
        super(new BorderLayout(0, 0));
        this.owner      = owner;
        this.tableModel = tableModel;
        this.onClose    = onClose;

        model = new DefaultTableModel(COLS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model) {
            @Override public String getToolTipText(MouseEvent e) {
                int vr = rowAtPoint(e.getPoint());
                if (vr < 0) return null;
                int mr = convertRowIndexToModel(vr);
                return mr >= 0 && mr < rows.size() ? rows.get(mr).path() : null;
            }
        };
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.getTableHeader().setReorderingAllowed(false);
        TableColumnModel cm = table.getColumnModel();
        setColWidth(cm, 0, 130, 100, 190);   // Verdict
        setColWidth(cm, 1, 170, 120, 220);   // Fiabilité
        setColWidth(cm, 2, 230, 120, 520);   // Fichier
        setColWidth(cm, 3, 220, 100, 400);   // Tag actuel
        setColWidth(cm, 4, 240, 100, 420);   // Ce que dit l'audio
        setColWidth(cm, 5, 190, 80, 340);    // Shazam
        setColWidth(cm, 6, 90, 70, 120);     // Durée
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                int vr = table.rowAtPoint(e.getPoint());
                if (vr >= 0) openMatchDialog(table.convertRowIndexToModel(vr));
            }
        });

        add(buildHeader(), BorderLayout.NORTH);
        scroll = new JScrollPane(table);
        add(scroll, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        cbScope.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                return super.getListCellRendererComponent(l, scopeLabel(v instanceof Integer n ? n : -1), i, sel, foc);
            }
        });
        cbScope.addActionListener(e -> describeScope());
        cbScope.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { refreshScopeCounts(false); }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        refreshScopeCounts(true);

        chkArtistOnly.addActionListener(e -> reload());
        btnStart.addActionListener(e -> startAudit());
        btnStop.addActionListener(e -> stopAudit());

        poll = new javax.swing.Timer(3000, e -> tick());
        poll.setInitialDelay(0);
        poll.start();
    }

    /** Arrêt du sondage à la fermeture de la fenêtre hôte (le panneau quitte alors la hiérarchie) — l'audit
     *  lui-même n'est PAS arrêté : il continue en tâche de fond et ses verdicts sont en base. */
    @Override public void removeNotify() {
        poll.stop();
        super.removeNotify();
    }

    /** Appelé par la fenêtre hôte quand elle est ré-affichée : compteurs et choix de périmètre à jour. */
    void onReopened() {
        refreshScopeCounts(true);
        reload();
    }

    // ── Mise en page ────────────────────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        scopeRow.add(new JLabel(I18n.t("Auditer :")));
        scopeRow.add(cbScope);
        scopeRow.add(chkRecheck);
        scopeRow.add(btnStart);
        scopeRow.add(btnStop);
        btnStop.setEnabled(false);
        btnStart.setToolTipText(I18n.t("Compare l'audio (empreinte AcoustID, puis Shazam pour les cas graves) aux tags "
                + "écrits dans chaque fichier. Lecture seule : aucun fichier n'est modifié."));

        bar.setStringPainted(true);
        bar.setVisible(false);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBorder(new EmptyBorder(8, 12, 6, 12));
        lblScopeHelp.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        JPanel lines = new JPanel(new GridLayout(0, 1, 0, 2));
        lines.add(lblScopeHelp);
        lines.add(lblStatus);
        lines.add(lblCounts);
        p.add(scopeRow, BorderLayout.NORTH);
        p.add(lines,    BorderLayout.CENTER);
        p.add(bar,      BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildFooter() {
        JButton btnListen = new JButton(I18n.t("Écouter"));
        JButton btnForce  = new JButton(I18n.t("Forcer le re-taguage…"));
        JButton btnOk     = new JButton(I18n.t("L'audio est bon"));
        JButton btnMatch  = new JButton(I18n.t("Correspondance manuelle…"));
        JButton btnSelect = new JButton(I18n.t("Sélectionner les confirmés"));
        JButton btnReload = new JButton(I18n.t("Rafraîchir"));
        JButton btnClose  = new JButton(I18n.t("Fermer"));
        btnListen.setToolTipText(I18n.t("Ouvre le fichier dans le lecteur par défaut du système"));
        btnForce.setToolTipText(I18n.t("Relance l'identification par l'audio seul (les tags actuels sont ignorés) sur les lignes sélectionnées"));
        btnOk.setToolTipText(I18n.t("Le fichier est correct tel quel : il ne sera plus signalé tant qu'il ne change pas"));
        btnMatch.setToolTipText(I18n.t("Ouvre la correspondance manuelle (double-clic sur une ligne fait la même chose)"));
        btnSelect.setToolTipText(I18n.t("Sélectionne les lignes où AcoustID ET Shazam s'accordent sur ce que contient réellement le fichier"));

        btnListen.addActionListener(e -> listen());
        btnForce.addActionListener(e -> forceRetagSelection());
        btnOk.addActionListener(e -> markSelectionOk());
        btnMatch.addActionListener(e -> {
            int vr = table.getSelectedRow();
            if (vr < 0) { JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez une ligne.")); return; }
            openMatchDialog(table.convertRowIndexToModel(vr));
        });
        btnSelect.addActionListener(e -> selectConfirmed());
        btnReload.addActionListener(e -> { currentByKey.clear(); reload(); });
        btnClose.addActionListener(e -> onClose.run());

        // Texte au-dessus, boutons dans leur propre rangée (voir le commentaire équivalent de
        // DurationMismatchReviewPanel.buildFooter : un texte long à côté des boutons les poussait hors fenêtre).
        JLabel hint = new JLabel(I18n.t("Rien n'est corrigé automatiquement. « Forcer le re-taguage » ré-identifie par l'audio seul ; "
                + "en cas de doute, écoutez d'abord. Ctrl+A : tout sélectionner. Durée = fichier / audio identifié."));
        hint.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        for (JButton b : new JButton[]{btnListen, btnSelect, btnForce, btnMatch, btnOk, btnReload, btnClose}) buttons.add(b);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")),
            new EmptyBorder(8, 12, 8, 12)));
        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.add(chkArtistOnly, BorderLayout.NORTH);
        top.add(hint,          BorderLayout.SOUTH);
        p.add(top,     BorderLayout.NORTH);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    // ── Cycle d'interrogation ───────────────────────────────────────────────────────────────────────

    /** Toutes les 3 s : état de l'audit + relecture des suspects tant que l'audit tourne (et une dernière
     *  fois quand il vient de finir). Ne touche le disque que via reload(), en arrière-plan. */
    private void tick() {
        if (!isShowing()) return;          // autre onglet / fenêtre fermée : rien à rafraîchir
        boolean running = WorkerHub.get().current(WorkerHub.TaskKind.AUDIO_AUDIT).isPresent();
        AudioTagAuditWorker.Snapshot s = AudioTagAuditWorker.snapshot();

        btnStart.setEnabled(!running);
        btnStop.setEnabled(running);
        cbScope.setEnabled(!running);
        chkRecheck.setEnabled(!running);
        bar.setVisible(running);

        if (running) {
            bar.setMaximum(Math.max(1, s.total()));
            bar.setValue(Math.min(s.done(), Math.max(1, s.total())));
            bar.setString(s.done() + " / " + s.total());
            lblStatus.setText((s.notice().isEmpty() ? "" : "⏸ " + s.notice() + " — ")
                    + I18n.t("Audit en cours : %d/%d — %d suspect(s), %d invérifiable(s), %d erreur(s)%s%s",
                    s.done(), s.total(), s.suspects(), s.unverifiable(), s.errors(),
                    s.cached() > 0 ? I18n.t(", %d déjà audité(s) ignoré(s)", s.cached()) : "",
                    s.current().isEmpty() ? "" : " — " + s.current()));
        } else if (!s.notice().isEmpty()) {
            lblStatus.setText(s.notice());
        } else if (s.total() > 0) {
            lblStatus.setText(I18n.t("Dernier audit : %d/%d traité(s) — %d OK, %d suspect(s), %d invérifiable(s), %d erreur(s)",
                    s.done(), s.total(), s.ok(), s.suspects(), s.unverifiable(), s.errors()));
        } else {
            lblStatus.setText(I18n.t("Aucun audit lancé depuis le démarrage — les verdicts précédents restent listés ci-dessous."));
        }

        if (running || wasRunning) reload();
        else if (!loadedOnce && !loading) reload();
        wasRunning = running;
    }

    /**
     * Relit les suspects depuis la base d'audit (rapide : quelques ms, hors EDT) et les affiche AU PLUS VITE ;
     * la vérification "le fichier a-t-il changé depuis ?" (voir {@link #verifyInBackground}) vient ensuite,
     * séparément. Séparer les deux est indispensable : un stat sur la bibliothèque coûte 350-500 ms en charge
     * sur cette machine (mesuré 2026-09-20 : 10 fichiers = 4 s), donc vérifier des milliers de suspects avant
     * d'afficher quoi que ce soit gèlerait la fenêtre pendant des minutes.
     */
    private void reload() {
        if (loading) return;
        loading = true;
        final boolean includeArtistOnly = chkArtistOnly.isSelected();
        Thread.startVirtualThread(() -> {
            List<AudioAuditStore.Row> fromDb = List.of();
            Map<String, Integer> counts = Map.of();
            String error = null;
            try (AudioAuditStore store = AudioAuditStore.open()) {
                fromDb = store.suspects(includeArtistOnly);
                counts = store.countsByVerdict();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<AudioAuditStore.Row> fr = fromDb;
            final Map<String, Integer> fc = counts;
            final String fe = error;
            SwingUtilities.invokeLater(() -> {
                try {
                    lastDbRows  = fr;
                    lastCounts  = fc;
                    lastError   = fe;
                    refilter();
                    verifyInBackground();
                } finally {
                    loading = false;
                }
            });
        });
    }

    /** Clé de vérification d'une ligne : un ré-audit (ts différent) invalide l'ancienne vérification. */
    private static String verifyKey(AudioAuditStore.Row r) { return r.path() + "|" + r.ts(); }

    /** Réaffiche lastDbRows sans les lignes déjà PROUVÉES périmées (fichier modifié/renommé/supprimé) ; celles
     *  pas encore vérifiées restent affichées (optimistes) le temps que verifyInBackground() les tranche. */
    private void refilter() {
        List<AudioAuditStore.Row> visible = new ArrayList<>();
        for (AudioAuditStore.Row r : lastDbRows) {
            Boolean cur = currentByKey.get(verifyKey(r));
            if (cur == null || cur) visible.add(r);
        }
        fill(visible, lastCounts, lastError);
    }

    /**
     * Tranche, hors EDT et UNE SEULE FOIS par (fichier, audit), si chaque suspect affiché correspond toujours à
     * l'état actuel du fichier. Un fichier corrigé entre-temps (re-taguage, édition manuelle) a un autre
     * mtime/taille : sa ligne disparaît d'elle-même, sans aucune action de nettoyage. Le résultat est gardé pour
     * toute la vie de la fenêtre ; « Rafraîchir » le vide pour forcer une revérification complète.
     */
    private void verifyInBackground() {
        List<AudioAuditStore.Row> todo = new ArrayList<>();
        for (AudioAuditStore.Row r : lastDbRows) if (!currentByKey.containsKey(verifyKey(r))) todo.add(r);
        if (todo.isEmpty() || !verifying.compareAndSet(false, true)) return;
        verifyRemaining = todo.size();
        Thread.startVirtualThread(() -> {
            int sinceRefresh = 0;
            try {
                for (AudioAuditStore.Row r : todo) {
                    if (!isDisplayable()) return;
                    long[] st = AudioAuditStore.statOf(new File(r.path()));
                    boolean cur = st != null && AudioAuditStore.isCurrent(r, st[0], st[1]);
                    currentByKey.put(verifyKey(r), cur);
                    verifyRemaining--;
                    // Rafraîchir l'affichage dès qu'une ligne est prouvée périmée (elle doit disparaître), et de
                    // toute façon toutes les 10 lignes pour le compteur de progression.
                    if (!cur || ++sinceRefresh >= 10) {
                        sinceRefresh = 0;
                        SwingUtilities.invokeLater(this::refilter);
                    }
                }
            } finally {
                verifying.set(false);
                verifyRemaining = 0;
                SwingUtilities.invokeLater(() -> {
                    if (!isDisplayable()) return;      // fenêtre fermée : ne pas relancer (sinon boucle de threads)
                    refilter();
                    // Des lignes ont pu arriver pendant la vérification (audit en cours) : les traiter aussi.
                    verifyInBackground();
                });
            }
        });
    }

    private void fill(List<AudioAuditStore.Row> list, Map<String, Integer> counts, String error) {
        // Conserver la sélection à travers un rafraîchissement automatique (toutes les 3 s pendant l'audit),
        // sinon un clic de l'utilisateur serait perdu au tick suivant.
        // Reconstruction seulement si les lignes ont réellement changé (Row est un record : equals() compare tous les
        // champs) — sans cela, le tick de 3 s pendant un audit renvoyait le défilement en haut à chaque fois.
        if (!list.equals(rows)) {
            java.util.Set<String> selected = new java.util.HashSet<>();
            for (int vr : table.getSelectedRows()) {
                int mr = table.convertRowIndexToModel(vr);
                if (mr >= 0 && mr < rows.size()) selected.add(rows.get(mr).path());
            }
            final Point scrollPos = scroll.getViewport().getViewPosition();
            rows.clear();
            rows.addAll(list);
            model.setRowCount(0);
            for (AudioAuditStore.Row r : rows) {
                model.addRow(new Object[]{
                    verdictLabel(r), reliabilityLabel(r), shortPath(r.path()),
                    r.tagArtist() + " — " + r.tagTitle(),
                    r.acArtist() + " — " + r.acTitle(),
                    r.srTitle().isEmpty() ? "—" : r.srArtist() + " — " + r.srTitle(),
                    dur(r.fileDurationSec()) + " / " + (r.acDurationSec() > 0 ? dur(r.acDurationSec()) : "?")});
            }
            if (!selected.isEmpty()) {
                for (int mr = 0; mr < rows.size(); mr++) {
                    if (selected.contains(rows.get(mr).path())) {
                        int vr = table.convertRowIndexToView(mr);
                        if (vr >= 0) table.addRowSelectionInterval(vr, vr);
                    }
                }
            }
            SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(scrollPos));
        }
        loadedOnce = true;
        if (error != null) {
            lblCounts.setText(I18n.t("Base d'audit inaccessible : %s", error));
            return;
        }
        int ok = counts.getOrDefault("OK", 0), unv = counts.getOrDefault("UNVERIFIABLE", 0),
            err = counts.getOrDefault("ERROR", 0);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        long confirmed = rows.stream().filter(r -> "AGREE".equals(r.shazam())).count();
        lblCounts.setText(total == 0
            ? I18n.t("Aucun fichier audité pour l'instant.")
            : I18n.t("%d audité(s) : %d OK, %d invérifiable(s), %d illisible(s) — "
                    + "%d suspect(s) listé(s), dont %d confirmé(s) par 2 moteurs",
                    total, ok, unv, err, rows.size(), confirmed)
            + (verifyRemaining > 0 ? I18n.t(" — vérif. des fichiers (%d)…", verifyRemaining) : ""));
    }

    // ── Périmètre ───────────────────────────────────────────────────────────────────────────────────

    private String scopeLabel(int scope) {
        return switch (scope) {
            case SCOPE_ALL       -> I18n.t("Tous les fichiers tagués du tableau (%d) — rien à sélectionner", taggedCount);
            case SCOPE_SELECTION -> I18n.t("Seulement les fichiers sélectionnés dans le tableau principal (%d)", selectedCount);
            case SCOPE_SAMPLE    -> I18n.t("Échantillon aléatoire de %d fichiers tagués", SAMPLE_SIZE);
            case SCOPE_FOLDER    -> I18n.t("Un dossier du tableau…");
            default -> "";
        };
    }

    /** Une phrase qui dit exactement ce que le périmètre choisi exige (ou n'exige pas) de l'utilisateur. */
    private void describeScope() {
        lblScopeHelp.setText(switch (cbScope.getSelectedIndex()) {
            case SCOPE_ALL       -> I18n.t("Aucune sélection à faire : l'audit prend tous les fichiers tagués chargés dans le tableau principal.");
            case SCOPE_SELECTION -> I18n.t("Sélectionnez d'abord des fichiers dans la fenêtre principale (Ctrl+A pour tout sélectionner), puis lancez.");
            case SCOPE_SAMPLE    -> I18n.t("Aucune sélection à faire : %d fichiers au hasard pour estimer vite la part de fichiers faux (~%d min).",
                                            SAMPLE_SIZE, Math.round(SAMPLE_SIZE * SECONDS_PER_FILE_ESTIMATE / 60.0));
            case SCOPE_FOLDER    -> I18n.t("Vous choisissez un dossier ; seuls ses fichiers déjà chargés dans le tableau sont audités.");
            default -> " ";
        });
    }

    /** Recompte (EDT, mémoire seulement) les fichiers tagués et la sélection du tableau principal. Avec
     *  {@code pickDefault} : choisit la sélection si l'utilisateur en a fait une (intention claire), sinon « Tous ». */
    private void refreshScopeCounts(boolean pickDefault) {
        int tagged = 0;
        for (FileEntry e : tableModel.allEntries()) if (e.status == FileEntry.Status.TAGGED) tagged++;
        taggedCount   = tagged;
        selectedCount = owner.selectedEntries().size();
        if (pickDefault && cbScope.isEnabled()) cbScope.setSelectedIndex(selectedCount > 0 ? SCOPE_SELECTION : SCOPE_ALL);
        describeScope();
        cbScope.repaint();
    }

    // ── Lancement ───────────────────────────────────────────────────────────────────────────────────

    private void startAudit() {
        if (!WorkerHub.get().blockerLabels(WorkerHub.TaskKind.AUDIO_AUDIT).isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.t("Un audit est déjà en cours."));
            return;
        }
        List<FileEntry> entries = new ArrayList<>();
        switch (cbScope.getSelectedIndex()) {
            case SCOPE_SELECTION -> entries.addAll(owner.selectedEntries());
            case SCOPE_ALL, SCOPE_SAMPLE -> {
                for (FileEntry e : tableModel.allEntries()) if (e.status == FileEntry.Status.TAGGED) entries.add(e);
                Collections.shuffle(entries);          // échantillon représentatif dès les premiers fichiers
                if (cbScope.getSelectedIndex() == SCOPE_SAMPLE && entries.size() > SAMPLE_SIZE)
                    entries = new ArrayList<>(entries.subList(0, SAMPLE_SIZE));
            }
            case SCOPE_FOLDER -> {
                JFileChooser fc = new JFileChooser();
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fc.setDialogTitle(I18n.t("Dossier à auditer (fichiers tagués déjà chargés dans le tableau)"));
                if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                java.nio.file.Path root = fc.getSelectedFile().toPath().toAbsolutePath();
                for (FileEntry e : tableModel.allEntries()) {
                    if (e.status == FileEntry.Status.TAGGED && pathOf(e).startsWith(root)) entries.add(e);
                }
            }
            default -> { }
        }
        if (entries.isEmpty()) {
            refreshScopeCounts(false);
            JOptionPane.showMessageDialog(this, switch (cbScope.getSelectedIndex()) {
                case SCOPE_SELECTION -> I18n.t("Aucun fichier n'est sélectionné dans la fenêtre principale.\n\n"
                        + "Sélectionnez-en (Ctrl+A pour tout sélectionner), ou choisissez « Tous les fichiers tagués du tableau » "
                        + "— aucune sélection nécessaire.");
                case SCOPE_FOLDER -> I18n.t("Aucun fichier tagué de ce dossier n'est chargé dans le tableau principal.");
                default -> I18n.t("Le tableau principal ne contient aucun fichier tagué pour l'instant.\n\n"
                        + "Le scan de démarrage n'a peut-être pas fini de charger la bibliothèque : attendez qu'il ait terminé.");
            });
            return;
        }
        if (entries.size() > 200) {
            long minutes = Math.round(entries.size() * SECONDS_PER_FILE_ESTIMATE / 60.0);
            int ok = JOptionPane.showConfirmDialog(this,
                I18n.t("Auditer %d fichier(s) ?\n\nDurée estimée : ~%dh%02d (≈%d s par fichier, pour ménager AcoustID et les disques).\n"
                     + "L'audit tourne en tâche de fond, ne modifie AUCUN fichier, et reprend là où il s'est arrêté "
                     + "si vous l'interrompez (les fichiers déjà audités sont ignorés).",
                     entries.size(), minutes / 60, minutes % 60, (int) SECONDS_PER_FILE_ESTIMATE),
                I18n.t("Vérifier audio ↔ tags"), JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
        }
        List<File> files = new ArrayList<>(entries.size());
        for (FileEntry e : entries) files.add(pathOf(e).toFile());

        AudioTagAuditWorker worker = new AudioTagAuditWorker(files, chkRecheck.isSelected());
        WorkerHub.get().submit(WorkerHub.TaskKind.AUDIO_AUDIT, I18n.t("Audit audio ↔ tags"), worker, worker::requestStop);
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        lblStatus.setText(I18n.t("Audit lancé sur %d fichier(s)…", files.size()));
    }

    private void stopAudit() {
        WorkerHub.get().current(WorkerHub.TaskKind.AUDIO_AUDIT).ifPresent(WorkerHub.TaskHandle::cancel);
        btnStop.setEnabled(false);
    }

    // ── Actions sur les suspects ────────────────────────────────────────────────────────────────────

    private List<AudioAuditStore.Row> selectedRows() {
        List<AudioAuditStore.Row> out = new ArrayList<>();
        for (int vr : table.getSelectedRows()) {
            int mr = table.convertRowIndexToModel(vr);
            if (mr >= 0 && mr < rows.size()) out.add(rows.get(mr));
        }
        return out;
    }

    private Map<String, FileEntry> entriesByPath() {
        Map<String, FileEntry> map = new HashMap<>();
        for (FileEntry e : tableModel.allEntries()) map.put(pathOf(e).toString(), e);
        return map;
    }

    private void listen() {
        List<AudioAuditStore.Row> sel = selectedRows();
        if (sel.isEmpty()) { JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez une ligne.")); return; }
        File f = new File(sel.get(0).path());
        // Hors EDT : Desktop.open()/xdg-open peuvent bloquer sur un montage lent.
        Thread.startVirtualThread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(f);
                } else {
                    new ProcessBuilder("xdg-open", f.getAbsolutePath()).start();
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    I18n.t("Impossible d'ouvrir le fichier : %s", ex.getMessage())));
            }
        });
    }

    private void forceRetagSelection() {
        List<AudioAuditStore.Row> sel = selectedRows();
        if (sel.isEmpty()) { JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez au moins une ligne (Ctrl+A : toutes ; « Sélectionner les confirmés » : celles confirmées par deux moteurs).")); return; }
        Map<String, FileEntry> byPath = entriesByPath();
        List<FileEntry> targets = new ArrayList<>();
        int missing = 0;
        for (AudioAuditStore.Row r : sel) {
            FileEntry e = byPath.get(r.path());
            if (e == null) missing++; else targets.add(e);
        }
        if (missing > 0)
            JOptionPane.showMessageDialog(this, I18n.t("%d fichier(s) ne sont plus dans le tableau principal (déplacés, renommés ou "
                    + "retirés) et sont ignorés.", missing));
        if (targets.isEmpty()) return;
        owner.forceRetagOn(targets);
    }

    private void markSelectionOk() {
        List<AudioAuditStore.Row> sel = selectedRows();
        if (sel.isEmpty()) { JOptionPane.showMessageDialog(this, I18n.t("Sélectionnez au moins une ligne (Ctrl+A : toutes ; « Sélectionner les confirmés » : celles confirmées par deux moteurs).")); return; }
        Thread.startVirtualThread(() -> {
            try (AudioAuditStore store = AudioAuditStore.open()) {
                for (AudioAuditStore.Row r : sel) store.markUserOk(r.path());
            } catch (Exception ex) {
                System.out.println("[OT] Audit audio ↔ tags : « l'audio est bon » non enregistré : " + ex.getMessage());
            }
            SwingUtilities.invokeLater(this::reload);
        });
    }

    private void selectConfirmed() {
        table.clearSelection();
        for (int mr = 0; mr < rows.size(); mr++) {
            if (!"AGREE".equals(rows.get(mr).shazam())) continue;
            int vr = table.convertRowIndexToView(mr);
            if (vr >= 0) table.addRowSelectionInterval(vr, vr);
        }
    }

    private void openMatchDialog(int modelRow) {
        if (modelRow < 0 || modelRow >= rows.size()) return;
        FileEntry entry = entriesByPath().get(rows.get(modelRow).path());
        if (entry == null) {
            JOptionPane.showMessageDialog(this, I18n.t("Ce fichier n'est plus dans le tableau principal."));
            return;
        }
        new MatchDialog(owner, entry, tableModel, () -> owner.setStatus(I18n.t(
            "Correspondance choisie pour %s — utilisez Enregistrer dans la fenêtre principale pour l'écrire sur le fichier.",
            entry.filename()))).setVisible(true);
    }

    // ── Présentation ────────────────────────────────────────────────────────────────────────────────

    private static java.nio.file.Path pathOf(FileEntry e) {
        return (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath();
    }

    private static String verdictLabel(AudioAuditStore.Row r) {
        return switch (r.verdict()) {
            case "OTHER_TRACK" -> I18n.t("Autre morceau");
            case "TITLE_DIFF"  -> I18n.t("Titre différent");
            case "ARTIST_DIFF" -> I18n.t("Artiste différent");
            default -> r.verdict();
        };
    }

    private static String reliabilityLabel(AudioAuditStore.Row r) {
        return switch (r.shazam()) {
            case "AGREE" -> I18n.t("✔ 2 moteurs d'accord");
            case "OTHER" -> I18n.t("⚠ moteurs divergents");
            case "SILENT", "NA" -> I18n.t("AcoustID seul");
            default -> I18n.t("AcoustID");
        };
    }

    private static String shortPath(String path) {
        File f = new File(path);
        File parent = f.getParentFile();
        return (parent != null ? parent.getName() + "/" : "") + f.getName();
    }

    private static String dur(int sec) { return FileTableModel.formatDuration(sec); }

    private static void setColWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        cm.getColumn(i).setPreferredWidth(p);
        cm.getColumn(i).setMinWidth(mn);
        cm.getColumn(i).setMaxWidth(mx);
    }
}
