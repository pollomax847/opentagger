package com.opentagger.ui;

import com.opentagger.AudioTranscoder;
import com.opentagger.CdRipper;
import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MusicBrainzClient;
import com.opentagger.model.FileEntry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Import de CD — volet audio (extraction via CdRipper + identification par TOC + vérification de
 * doublons dans la bibliothèque avant extraction) et volet CD de données (simple copie de
 * fichiers). Demande utilisateur (2026-08-21).
 *
 * Les pistes sont extraites dans un dossier de travail (~/.opentagger/cd-import/<horodatage>/), puis
 * RANGÉES dans le dossier de bibliothèque des Réglages (celui où le renommage déplace les audios) :
 * si le disque est identifié, avec les tags de la release, la pochette et le masque de renommage
 * (même chaîne que « Enregistrer tout », {@code TagEnrichment.saveEntry}) ; sinon dans un dossier
 * daté « CD à identifier » de cette même destination, à passer ensuite à l'identification normale.
 * Le dossier de travail n'est jamais le résultat final.
 */
public class CdImportDialog extends JDialog {

    private final MainFrame owner;
    private CdRipper.Toc toc;
    private MusicBrainzClient.ReleaseTracklist identified; // null si non identifié
    /** Vrai si le Disc ID exact du disque a été retrouvé (sinon identification approximative, à vérifier). */
    private volatile boolean exactMatch;
    /** Vrai si le nom du disque vient de l'analyse de l'audio (empreintes) plutôt que de sa table des pistes. */
    private volatile boolean audioMatch;
    /** Raison lisible si l'identification a échoué (réseau, clé AcoustID, lecteur bloqué…), vide sinon. */
    private volatile String identifyError = "";
    /** Écart maximal (secteurs, 75/s) toléré entre la durée du disque et celle d'une release trouvée par durées arrondies. */
    private static final int MAX_FUZZY_SECTOR_DIFF = 3000; // 40 s : le contrôle piste par piste (describesDisc) décide ensuite

    // Colonnes : case, n°, artiste, titre, durée, déjà présent, statut
    private static final int COL_NO = 1, COL_ARTIST = 2, COL_TITLE = 3, COL_DUR = 4, COL_DUP = 5, COL_STATUS = 6;

    /** Sous-titre de l'en-tête (label, pays, nombre de pistes, durée totale, source de l'identification). */
    private final JLabel lblAlbumSub = new JLabel(" ");
    /** Pochette du disque identifié (Cover Art Archive), masquée tant qu'elle n'est pas chargée. */
    private final JLabel lblCover = new JLabel();
    private volatile String coverFor = "";
    private final JLabel lblDest = new JLabel();

    // En-tête album — style proéminent (titre en gras, sous-titre discret), même esprit que la
    // fenêtre de ripping d'iTunes/Asunder qui affiche toujours artiste/album en haut, séparé du
    // texte de statut secondaire en bas de fenêtre. Retour utilisateur (2026-08-23, "améliore l'ui").
    private final JLabel lblAlbumHeader = new JLabel();
    // Carte "aucun CD" affichée tant que rien n'a été détecté, à la place d'un tableau vide qui ne
    // dit rien de ce qu'il faut faire — remplacée par le vrai tableau dès qu'une table des pistes
    // existe (voir showTrackTable()).
    private final JLabel lblEmptyState = new JLabel(
            I18n.t("<html><center>💿<br><br>Insère un CD puis clique sur « Détecter le CD »</center></html>"),
            SwingConstants.CENTER);
    private final CardLayout centerCards = new CardLayout();
    private final JPanel centerPanel = new JPanel(centerCards);
    private static final String CARD_EMPTY = "empty", CARD_TABLE = "table";

    private final JLabel lblStatus = new JLabel();
    // Barre + label dédiés à l'extraction en cours — même esprit que la fenêtre de ripping
    // d'Asunder/iTunes (retour utilisateur, 2026-08-23) : un statut texte seul en bas de fenêtre ne
    // donnait aucune idée de la progression réelle ni de quelle piste précisément était en cours.
    private final JProgressBar progressBar = new JProgressBar(0, 1);
    private final JLabel lblProgress = new JLabel(" ");
    private final DefaultTableModel trackTableModel = new DefaultTableModel(
            new Object[]{"", "#", I18n.t("Artiste"), I18n.t("Titre"), I18n.t("Durée"), I18n.t("Déjà présent ?"), I18n.t("Statut")}, 0) {
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
    };
    private final JTable trackTable = new JTable(trackTableModel);
    private final JComboBox<AudioTranscoder.Format> cbFormat = new JComboBox<>(AudioTranscoder.Format.values());
    private final JButton btnDetect    = new JButton(I18n.t("Détecter le CD"));
    private final JButton btnExtract   = new JButton(I18n.t("Extraire la sélection"));
    private final JButton btnDataDisc  = new JButton(I18n.t("CD de données (copier des fichiers)…"));

    public CdImportDialog(MainFrame owner) {
        super(owner, I18n.t("Importer un CD — OpenTagger"), true);
        this.owner = owner;
        setSize(880, 600);
        setMinimumSize(new Dimension(620, 400));
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(8, 8));

        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));

        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        btnDetect.setIcon(new ToolbarIcon(ToolbarIcon.Kind.DISC, new Color(0x90A4AE)));
        toolRow.add(btnDetect);
        toolRow.add(new JLabel(I18n.t("Format :")));
        cbFormat.setSelectedItem(AudioTranscoder.Format.fromId(Config.get().str("transcode.format", "mp3")));
        toolRow.add(cbFormat);
        toolRow.add(btnDataDisc);
        north.add(toolRow);

        // En-tête album — masqué tant qu'aucune identification n'a eu lieu (setAlbumHeader(null)),
        // pour ne pas afficher une ligne vide qui ferait "bouger" la mise en page à chaque état.
        lblAlbumHeader.putClientProperty("FlatLaf.style", "font: bold 16 $defaultFont");
        lblAlbumSub.putClientProperty("FlatLaf.style", "foreground: #8a8a8a");
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        titles.add(lblAlbumHeader);
        titles.add(Box.createVerticalStrut(4));
        titles.add(lblAlbumSub);
        lblCover.setPreferredSize(new Dimension(96, 96));
        lblCover.setHorizontalAlignment(SwingConstants.CENTER);
        lblCover.setVisible(false);
        JPanel headerRow = new JPanel(new BorderLayout(12, 0));
        headerRow.setBorder(new EmptyBorder(0, 10, 4, 10));
        headerRow.add(lblCover, BorderLayout.WEST);
        headerRow.add(titles, BorderLayout.CENTER);
        north.add(headerRow);
        lblDest.setBorder(new EmptyBorder(0, 10, 8, 10));
        lblDest.putClientProperty("FlatLaf.style", "foreground: #8a8a8a");
        north.add(lblDest);
        // Plusieurs versions possibles du disque (comme MediaMonkey) : liste visible seulement quand il y a un choix.
        proposalRow.setBorder(new EmptyBorder(0, 10, 8, 10));
        proposalRow.add(new JLabel(I18n.t("Version du disque :")), BorderLayout.WEST);
        proposalRow.add(cbProposals, BorderLayout.CENTER);
        proposalRow.setVisible(false);
        cbProposals.addActionListener(e -> {
            if (!fillingProposals && cbProposals.getSelectedItem() instanceof Proposal p) {
                btnDetect.setEnabled(false);
                applyProposal(p);
            }
        });
        north.add(proposalRow);
        refreshDestLabel();
        setAlbumHeader(null);

        add(north, BorderLayout.NORTH);

        lblEmptyState.putClientProperty("FlatLaf.style", "foreground: #888888; font: 18 $defaultFont");
        centerPanel.add(lblEmptyState, CARD_EMPTY);

        trackTable.getColumnModel().getColumn(0).setMaxWidth(30);
        trackTable.getColumnModel().getColumn(COL_NO).setMaxWidth(40);
        trackTable.getColumnModel().getColumn(COL_NO).setCellRenderer(rightAligned(null));
        trackTable.getColumnModel().getColumn(COL_ARTIST).setPreferredWidth(170);
        trackTable.getColumnModel().getColumn(COL_TITLE).setPreferredWidth(260);
        trackTable.getColumnModel().getColumn(COL_DUR).setMaxWidth(70);
        trackTable.getColumnModel().getColumn(COL_DUR).setCellRenderer(rightAligned(null));
        trackTable.getColumnModel().getColumn(COL_DUP).setMaxWidth(110);
        trackTable.getColumnModel().getColumn(COL_DUP).setCellRenderer(rightAligned(new Color(0xcc4444)));
        trackTable.getColumnModel().getColumn(COL_STATUS).setCellRenderer(statusRenderer());
        trackTable.setRowHeight(22);
        trackTable.setShowGrid(false);
        trackTable.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(trackTable);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        centerPanel.add(tableScroll, CARD_TABLE);
        centerCards.show(centerPanel, CARD_EMPTY);

        add(centerPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

        JPanel progressRow = new JPanel(new BorderLayout(8, 0));
        progressBar.setVisible(false); // masquée hors extraction — pas de barre à 0% avant même de commencer
        progressBar.setStringPainted(true);
        lblProgress.setPreferredSize(new Dimension(260, lblProgress.getPreferredSize().height));
        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(lblProgress, BorderLayout.EAST);
        bottom.add(progressRow);

        JPanel statusRow = new JPanel(new BorderLayout());
        lblStatus.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        statusRow.add(lblStatus, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(btnExtract);
        statusRow.add(right, BorderLayout.EAST);
        bottom.add(statusRow);

        add(bottom, BorderLayout.SOUTH);

        btnExtract.setEnabled(false);
        btnDetect.addActionListener(e -> detect());
        btnExtract.addActionListener(e -> extract());
        btnDataDisc.addActionListener(e -> importDataDisc());

        lblStatus.setText(I18n.t("Insère un CD puis clique sur « Détecter le CD »."));
    }

    private void detect() {
        if (!CdRipper.isAvailable()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Lecture de CD impossible : PowerShell (Windows) ou Python 3 (Linux) est introuvable. "
                         + "Sous Linux, installer « python3 » (ou « cdparanoia ») suffit."),
                    I18n.t("Outil manquant"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        btnDetect.setEnabled(false);
        btnExtract.setEnabled(false);
        trackTableModel.setRowCount(0);
        identified = null;
        exactMatch = false;
        audioMatch = false;
        identifyError = "";
        diskMatches = java.util.Map.of();
        proposals = List.of();
        fillProposalChoices(List.of());
        setAlbumHeader(null);
        centerCards.show(centerPanel, CARD_EMPTY);
        lblStatus.setText(I18n.t("Lecture de la table des pistes…"));
        new SwingWorker<CdRipper.Toc, Void>() {
            @Override protected CdRipper.Toc doInBackground() throws Exception {
                return new CdRipper().queryToc();
            }
            @Override protected void done() {
                // Le bouton reste DÉSACTIVÉ pendant l'identification (qui lit aussi le lecteur) : un second clic lançait une
                // seconde lecture en parallèle et bloquait le lecteur. Il est réactivé quand tout est fini (ou en cas d'échec).
                try {
                    toc = get();
                } catch (Exception ex) {
                    btnDetect.setEnabled(true);
                    String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                    lblStatus.setText(I18n.t("Échec de lecture : %s", msg));
                    return;
                }
                if (!toc.isAudioDisc()) {
                    btnDetect.setEnabled(true);
                    // Pas de piste audio : c'est peut-être un CD de DONNÉES (souvenirs, photos…). Plutôt que de renvoyer l'utilisateur vers un autre
                    // bouton, on regarde si un disque de données est lisible et on propose directement de copier ses fichiers.
                    Path dataRoot = com.opentagger.DataDiscCopier.findDataDiscRoot();
                    if (dataRoot != null) { offerDataDiscCopy(dataRoot); return; }
                    lblEmptyState.setText(I18n.t(
                            "<html><center>💿<br><br>Aucune piste audio détectée.<br>"
                          + "CD de données ? Utilise le bouton dédié ci-dessus.</center></html>"));
                    lblStatus.setText(I18n.t(
                            "Aucune piste audio détectée — CD de données ? Utilise le bouton dédié."));
                    return;
                }
                lblStatus.setText(I18n.t("%d piste(s) trouvée(s) — identification…", toc.tracks().size()));
                identify();
            }
        }.execute();
    }

    /** Identification GROSSIÈRE (nombre + durée des pistes, voir MusicBrainzClient.lookupByToc())
     *  — sert uniquement à préremplir les titres affichés et détecter des doublons probables avant
     *  extraction. La vraie identification définitive de chaque fichier reste celle, bien plus
     *  fiable, du pipeline de taguage normal une fois les pistes extraites (voir la Javadoc de
     *  classe). Échec silencieux (identified reste null) : l'extraction reste possible sans titre
     *  connu à l'avance, juste sans le confort de l'aperçu ni la détection de doublon. */
    private void identify() {
        new SwingWorker<List<Proposal>, String>() {
            @Override protected void process(List<String> messages) {
                if (!messages.isEmpty()) lblStatus.setText(messages.get(messages.size() - 1));
            }
            @Override protected List<Proposal> doInBackground() {
                // Plusieurs versions possibles, comme MediaMonkey : on les rassemble toutes, l'utilisateur choisit.
                List<Proposal> out = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                try {
                    MusicBrainzClient mb = new MusicBrainzClient();
                    // 1) Disc ID EXACT, calculé depuis les secteurs réels du disque : même disque pressé = même identifiant.
                    List<MusicBrainzClient.DiscIdCandidate> exact = mb.lookupByDiscId(toc.discId());
                    int taken = 0;
                    for (var c : exact) {
                        if (c.trackCount() != toc.tracks().size() || taken >= MAX_PROPOSALS_PER_SOURCE) continue;
                        if (seen.add(c.releaseMbid())) { out.add(new Proposal(mb.lookupRelease(c.releaseMbid()), "exact")); taken++; }
                    }

                    // 2) Repli approximatif (durées arrondies à la seconde) : accepté seulement si l'écart de durée totale reste
                    //    faible. Sans ce seuil, n'importe quelle release de même nombre de pistes était prise pour le disque.
                    List<Integer> durations = new ArrayList<>();
                    for (CdRipper.Track t : toc.tracks()) durations.add(t.durationSec());
                    List<MusicBrainzClient.DiscIdCandidate> candidates = mb.lookupByToc(durations);

                    int expectedSectors = toc.totalSectors();
                    List<MusicBrainzClient.DiscIdCandidate> near = new ArrayList<>();
                    for (var c : candidates)
                        if (c.trackCount() == toc.tracks().size() && Math.abs(c.sectors() - expectedSectors) <= MAX_FUZZY_SECTOR_DIFF) near.add(c);
                    near.sort(java.util.Comparator.comparingInt(c -> Math.abs(c.sectors() - expectedSectors)));
                    int approx = 0;
                    for (var c : near) {
                        if (approx >= MAX_PROPOSALS_PER_SOURCE || !seen.add(c.releaseMbid())) continue;
                        // Une durée totale proche et le même nombre de pistes ne prouvent rien (un CD de 80 min en a vite autant) :
                        // on vérifie que presque chaque piste a bien la durée annoncée avant de PROPOSER cette version.
                        var candidate = mb.lookupRelease(c.releaseMbid());
                        List<Integer> releaseSec = new ArrayList<>();
                        for (var rt : candidate.tracks()) releaseSec.add(rt.lengthMs() / 1000);
                        if (com.opentagger.CdAudioIdentifier.describesDisc(durations, releaseSec)) { out.add(new Proposal(candidate, "approx")); approx++; }
                    }

                    // 2b) GnuDB (CDDB) : base collaborative de CD, utile pour les pressions absentes de MusicBrainz. UNE requête puis au plus
                    //     MAX lectures, jamais en rafale (règles de GnuDB : un humain qui change de disque, pas un robot) ; seulement si
                    //     MusicBrainz n'a rien proposé, et coupable avec gnudb.enabled=false.
                    if (out.isEmpty() && Config.get().bool("gnudb.enabled", true)) {
                        try {
                            publish(I18n.t("Recherche du disque dans GnuDB…"));
                            com.opentagger.GnuDbClient g = new com.opentagger.GnuDbClient();
                            int n = 0;
                            for (var m : g.query(toc)) {
                                if (n >= MAX_PROPOSALS_PER_SOURCE) break;
                                var rt = com.opentagger.GnuDbClient.toTracklist(g.read(m), toc);
                                if (rt == null) continue;
                                StringBuilder key = new StringBuilder("gnudb:").append(norm(rt.albumArtist())).append('|').append(norm(rt.album()));
                                for (var t : rt.tracks()) key.append('|').append(norm(t.title()));
                                if (seen.add(key.toString())) { out.add(new Proposal(rt, "gnudb")); n++; }
                            }
                        } catch (Exception e) {
                            System.err.println("[OT] CD : GnuDB indisponible : " + e.getMessage());
                        }
                    }

                    // 3) Disque inconnu par son sommaire (CD gravé, parution absente de MusicBrainz) : on retrouve son nom par
                    //    l'AUDIO — quelques pistes extraites, identifiées par empreinte, puis la release commune à plusieurs.
                    if (out.isEmpty()) {
                        for (var rt : com.opentagger.CdAudioIdentifier.identifyAll(toc, new CdRipper(), mb,
                                new com.opentagger.AcoustIdClient(), this::publish, MAX_PROPOSALS_PER_SOURCE))
                            if (seen.add(rt.releaseMbid())) out.add(new Proposal(rt, "audio"));
                    }
                } catch (Exception e) {
                    identifyError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    System.err.println("[OT] CD : identification impossible : " + e);
                }
                return out;
            }
            @Override protected void done() {
                List<Proposal> list = List.of();
                try { list = get(); }
                catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    identifyError = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
                    System.err.println("[OT] CD : identification impossible : " + c);
                }
                proposals = list;
                fillProposalChoices(list);
                applyProposal(list.isEmpty() ? null : list.get(0));
            }
        }.execute();
    }

    /** Une version possible du disque : la release et d'où elle vient (« exact » : Disc ID, « approx » : durées vérifiées piste par
     *  piste, « audio » : empreintes des pistes). */
    private record Proposal(MusicBrainzClient.ReleaseTracklist rt, String source) {
        @Override public String toString() {
            String year = rt.year() != null && !rt.year().isBlank() ? " (" + rt.year() + ")" : "";
            String src = switch (source) {
                case "exact" -> I18n.t("disque reconnu exactement");
                case "audio" -> I18n.t("identifié par l'audio");
                case "gnudb" -> I18n.t("base GnuDB (CDDB)");
                default -> I18n.t("durées concordantes");
            };
            String extra = (rt.label() != null && !rt.label().isBlank() ? rt.label() + ", " : "")
                    + (rt.country() != null && !rt.country().isBlank() ? rt.country() + ", " : "");
            return rt.albumArtist() + " — " + rt.album() + year + "   ·   " + extra + src;
        }
    }

    private static final int MAX_PROPOSALS_PER_SOURCE = 3;
    private volatile List<Proposal> proposals = List.of();
    private volatile boolean gnudbMatch;
    private final JComboBox<Proposal> cbProposals = new JComboBox<>();
    private final JPanel proposalRow = new JPanel(new BorderLayout(8, 0));
    private boolean fillingProposals;

    /** Remplit la liste des versions : visible seulement s'il y a un choix à faire. */
    private void fillProposalChoices(List<Proposal> list) {
        fillingProposals = true;
        try {
            cbProposals.removeAllItems();
            for (Proposal p : list) cbProposals.addItem(p);
            if (!list.isEmpty()) cbProposals.setSelectedIndex(0);
        } finally {
            fillingProposals = false;
        }
        proposalRow.setVisible(list.size() > 1);
    }

    /** Applique la version choisie (ou aucune) : album affiché, pistes déjà présentes dans la bibliothèque, tableau. */
    private void applyProposal(Proposal p) {
        identified = p == null ? null : p.rt();
        exactMatch = p != null && "exact".equals(p.source());
        audioMatch = p != null && "audio".equals(p.source());
        gnudbMatch = p != null && "gnudb".equals(p.source());
        diskMatches = java.util.Map.of();
        btnExtract.setEnabled(false);
        // Pistes déjà présentes SUR LE DISQUE (dossier de bibliothèque) : fait en arrière-plan, la liste peut être longue.
        final Path lib = libraryRoot();
        if (identified == null || lib == null) {
            btnDetect.setEnabled(true);
            populateTable();
            return;
        }
        lblStatus.setText(I18n.t("Recherche de l'album dans la bibliothèque…"));
        final MusicBrainzClient.ReleaseTracklist rt = identified;
        // Instantané de ce que l'app connaît déjà (liste chargée = sa base) : copié ici, sur l'EDT, sans calcul — le tri se fait ensuite en
        // arrière-plan. Plus de parcours du dossier racine : sur des milliers de dossiers d'artistes et un disque USB, c'était la lenteur.
        final List<Known> known = snapshotKnown();
        new SwingWorker<java.util.Map<Integer, List<Path>>, Void>() {
            @Override protected java.util.Map<Integer, List<Path>> doInBackground() { return findExisting(lib, known, rt); }
            @Override protected void done() {
                if (identified != rt) return; // une autre version a été choisie entre-temps
                try { diskMatches = get(); } catch (Exception ex) { diskMatches = java.util.Map.of(); }
                btnDetect.setEnabled(true); // fin de TOUTE lecture du lecteur : on peut relancer
                populateTable();
            }
        }.execute();
    }

    private void populateTable() {
        trackTableModel.setRowCount(0);
        for (CdRipper.Track t : toc.tracks()) {
            String title  = I18n.t("(piste %d)", t.number());
            String artist = "";
            if (identified != null) {
                for (var rt : identified.tracks()) {
                    if (rt.trackNo() == t.number()) { title = rt.title(); artist = rt.artist(); break; }
                }
            }
            boolean dup = identified != null && diskMatches.containsKey(t.number());
            trackTableModel.addRow(new Object[]{
                    Boolean.TRUE, t.number(), artist, title, formatDuration(t.durationSec()), dup ? I18n.t("oui") : "", ""
            });
        }
        centerCards.show(centerPanel, CARD_TABLE);
        setAlbumHeader(identified);
        lblStatus.setText(identified != null
                ? (exactMatch
                    ? I18n.t("Identifié (disque exact) : %s – %s", identified.albumArtist(), identified.album())
                    : audioMatch
                        ? I18n.t("Identifié par l'audio : %s – %s", identified.albumArtist(), identified.album())
                    : gnudbMatch
                        ? I18n.t("Trouvé dans GnuDB, à vérifier : %s – %s", identified.albumArtist(), identified.album())
                        : I18n.t("Identifié approximativement, à vérifier : %s – %s", identified.albumArtist(), identified.album()))
                : I18n.t("Non identifié — les pistes seront extraites sans titre, "
                       + "à identifier ensuite normalement.")
                    + (identifyError.isBlank() ? "" : " (" + identifyError + ")"));
        btnExtract.setEnabled(true);
    }

    /** Correspondance approximative (album OU titre+artiste) sur les fichiers déjà chargés — pas
     *  une requête base de données, juste un parcours en mémoire (déjà rapide même sur 200k+
     *  entrées, aucune E/S). Suffisant pour avertir "tu as peut-être déjà ça", pas une garantie
     *  absolue — l'utilisateur tranche via la confirmation avant extraction. */
    /** Pistes de ce disque déjà présentes SUR LE DISQUE, dans le dossier de bibliothèque (numéro de piste → fichiers). Ne dépend
     *  ni de ce que l'app a déjà chargé dans sa liste, ni de la fin d'un scan. */
    private volatile java.util.Map<Integer, List<Path>> diskMatches = java.util.Map.of();

    private static final java.util.Set<String> AUDIO_EXT = java.util.Set.of(
            ".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma", ".ape", ".wv", ".aiff", ".aif");

    /** Ce que l'app sait d'un fichier déjà dans sa liste (copie légère, sans normalisation). */
    record Known(Path path, String artist, String albumArtist, String album, String title, int track) {}

    private List<Known> snapshotKnown() {
        List<FileEntry> all = owner.allEntries();
        List<Known> out = new ArrayList<>(all.size());
        for (FileEntry fe : all) {
            var t = fe.activeTags();
            if (t == null || t.album == null || t.album.isBlank()) continue;
            Path p = fe.currentPath != null ? fe.currentPath : fe.file.toPath();
            out.add(new Known(p, t.artist, t.albumArtist, t.album, t.title, trackNumber(t.track)));
        }
        return out;
    }

    /** Pistes du disque déjà connues de l'app : même album (artiste de l'album ou artiste + titre d'album normalisés), la piste étant
     *  retrouvée par son titre ou, à défaut, par son numéro. Instantané : aucun accès disque. */
    static java.util.Map<Integer, List<Path>> findKnown(List<Known> known, MusicBrainzClient.ReleaseTracklist rt) {
        java.util.Map<Integer, List<Path>> out = new java.util.HashMap<>();
        if (known == null || rt == null) return out;
        String artistN = norm(rt.albumArtist()), albumN = norm(rt.album());
        java.util.Map<String, MusicBrainzClient.ReleaseTrack> byTitle = new java.util.HashMap<>();
        for (var t : rt.tracks()) byTitle.putIfAbsent(norm(t.title()), t);
        boolean anyArtist = isVariousArtists(artistN) || rt.isCompilation();
        java.util.Map<String, String> normCache = new java.util.HashMap<>(); // le même album/dossier revient pour toutes ses pistes
        java.util.function.Function<String, String> n = s -> normCache.computeIfAbsent(s == null ? "" : s, CdImportDialog::norm);
        for (Known k : known) {
            // Album reconnu par son tag OU par le nom de son dossier : des fichiers sans tag d'album (ou taggés autrement) rangés dans
            // « Just Hits France » sont bien cet album. Les titres se comparent sans accents ni casse (« Je N'ai Que Mon Âme »).
            boolean byFolder = !albumN.isBlank() && albumN.equals(n.apply(parentFolderName(k.path())));
            boolean byAlbum = !albumN.isBlank() && albumN.equals(n.apply(k.album()));
            String titleN = n.apply(k.title());
            MusicBrainzClient.ReleaseTrack byName = byTitle.get(titleN);
            Integer no = null;
            if (byAlbum || byFolder) {
                if (!anyArtist && !byFolder) {
                    String ka = n.apply(!isBlank(k.albumArtist()) ? k.albumArtist() : k.artist());
                    if (!artistN.isBlank() && !ka.isBlank() && !artistN.equals(ka) && !artistN.equals(n.apply(k.artist()))) continue;
                }
                if (byName != null) no = byName.trackNo();
                if (no == null) { var viaFile = byTitle.get(titleOfFileName(k.path().getFileName().toString())); if (viaFile != null) no = viaFile.trackNo(); }
                if (no == null && k.track() > 0) no = k.track();
            } else if (byName != null && sameArtist(byName.artist(), k.artist())) {
                no = byName.trackNo();       // même titre et même artiste ailleurs dans la bibliothèque (autre album, autre dossier)
            }
            if (no != null) out.computeIfAbsent(no, x -> new ArrayList<>()).add(k.path());
        }
        return out;
    }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String parentFolderName(Path p) {
        Path par = p == null ? null : p.getParent();
        return par == null || par.getFileName() == null ? "" : par.getFileName().toString();
    }

    private static boolean isVariousArtists(String normArtist) {
        return normArtist.equals("various") || normArtist.equals("various artists") || normArtist.equals("va")
                || normArtist.equals("divers") || normArtist.equals("artistes varies") || normArtist.equals("compilation");
    }

    /** Dossier attendu {@code bibliothèque/Artiste/Album} atteint DIRECTEMENT par son nom (sans lister la racine) — complète {@link #findKnown}
     *  pour un album présent sur le disque mais pas (encore) dans la liste. */
    static java.util.Map<Integer, List<Path>> findOnDiskDirect(Path library, MusicBrainzClient.ReleaseTracklist rt) {
        java.util.Map<Integer, List<Path>> out = new java.util.HashMap<>();
        if (library == null || rt == null || !Files.isDirectory(library)) return out;
        Path albumDir;
        try { albumDir = library.resolve(safeName(rt.albumArtist())).resolve(safeName(rt.album())); }
        catch (java.nio.file.InvalidPathException e) { return out; }
        if (!Files.isDirectory(albumDir)) return out;
        try (var files = Files.walk(albumDir, 2)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (!Files.isRegularFile(f) || AUDIO_EXT.stream().noneMatch(name::endsWith)) continue;
                String stem = titleOfFileName(f.getFileName().toString());
                for (var t : rt.tracks())
                    if (!stem.isBlank() && stem.equals(norm(t.title()))) out.computeIfAbsent(t.trackNo(), k -> new ArrayList<>()).add(f);
            }
        } catch (IOException | java.io.UncheckedIOException ignored) { /* illisible : on ne signale rien */ }
        return out;
    }

    private static String safeName(String s) { return s == null ? "" : s.replaceAll("[<>:\"/\\\\|?*]", "_").trim(); }

    /** Tout ce qui existe déjà pour ce disque : la liste de l'app d'abord, puis le dossier attendu. Fusionné sans doublon. */
    static java.util.Map<Integer, List<Path>> findExisting(Path library, List<Known> known, MusicBrainzClient.ReleaseTracklist rt) {
        java.util.Map<Integer, List<Path>> out = findKnown(known, rt);
        for (var e : findOnDiskDirect(library, rt).entrySet()) {
            List<Path> l = out.computeIfAbsent(e.getKey(), x -> new ArrayList<>());
            for (Path p : e.getValue()) if (!l.contains(p)) l.add(p);
        }
        return out;
    }

    /** Minuscules, sans accents ni ponctuation, espaces réduits : « Christophe Maé », « christophe mae » et « Christophe Mae » se
     *  valent. */
    static String norm(String s) {
        if (s == null) return "";
        String d = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Titre d'un nom de fichier de bibliothèque : sans extension, sans « 01 - » en tête ni « (2) » de copie en fin. */
    static String titleOfFileName(String fileName) {
        String stem = fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        stem = stem.replaceFirst("^\\d+\\s*[-._ ]+\\s*", "").replaceFirst("\\s*\\(\\d+\\)\\s*$", "");
        return norm(stem);
    }

    /** Cherche dans {@code library} les pistes de {@code rt} : dossiers dont le nom correspond à l'artiste puis à l'album, fichiers
     *  dont le titre correspond à celui de la piste. Indépendant du masque de renommage. */
    static java.util.Map<Integer, List<Path>> findOnDisk(Path library, MusicBrainzClient.ReleaseTracklist rt) {
        java.util.Map<Integer, List<Path>> out = new java.util.HashMap<>();
        if (library == null || rt == null || !Files.isDirectory(library)) return out;
        String artistN = norm(rt.albumArtist()), albumN = norm(rt.album());
        if (artistN.isBlank() || albumN.isBlank()) return out;
        try (var artists = Files.list(library)) {
            for (Path artistDir : (Iterable<Path>) artists::iterator) {
                if (!Files.isDirectory(artistDir) || !norm(artistDir.getFileName().toString()).equals(artistN)) continue;
                try (var albums = Files.list(artistDir)) {
                    for (Path albumDir : (Iterable<Path>) albums::iterator) {
                        if (!Files.isDirectory(albumDir) || !norm(albumDir.getFileName().toString()).equals(albumN)) continue;
                        try (var files = Files.walk(albumDir, 2)) {
                            for (Path f : (Iterable<Path>) files::iterator) {
                                String name = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                                if (!Files.isRegularFile(f) || AUDIO_EXT.stream().noneMatch(name::endsWith)) continue;
                                String stem = titleOfFileName(f.getFileName().toString());
                                for (var t : rt.tracks())
                                    if (!stem.isBlank() && stem.equals(norm(t.title())))
                                        out.computeIfAbsent(t.trackNo(), k -> new ArrayList<>()).add(f);
                            }
                        }
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException ignored) { /* lecture impossible : on ne signale rien plutôt que de se tromper */ }
        return out;
    }

    /** Fichiers de la bibliothèque qui sont CETTE piste : même titre (et même artiste s'il est connu), ou même album et même
     *  numéro de piste. Plus strict que {@link #isAlreadyInLibrary} (qui s'arrête à « même album ») : sert à choisir ce qu'on
     *  remplace, jamais plus que la piste extraite. */
    private List<FileEntry> libraryMatchesForTrack(String artist, String album, String title, int trackNo) {
        List<FileEntry> out = new ArrayList<>();
        for (FileEntry fe : owner.allEntries()) {
            var t = fe.activeTags();
            if (t == null) continue;
            boolean sameTitle = !title.isBlank() && norm(title).equals(norm(t.title))
                    && (artist.isBlank() || sameArtist(artist, t.artist));
            boolean sameSlot = !album.isBlank() && norm(album).equals(norm(t.album)) && trackNumber(t.track) == trackNo;
            if (sameTitle || sameSlot) out.add(fe);
        }
        return out;
    }

    /** Tags de la piste {@code trackNo} d'après la release identifiée (artiste et titre de la piste, infos de parution, MBID). */
    private com.opentagger.model.TagInfo tagsFor(int trackNo) {
        for (var rt : identified.tracks()) {
            if (rt.trackNo() != trackNo) continue;
            com.opentagger.model.TagInfo ti = new com.opentagger.model.TagInfo();
            ti.artist = rt.artist();
            ti.title = rt.title();
            ti.score = 100;
            TaggingWorker.applyTrackOfRelease(ti, identified, rt);
            return ti;
        }
        throw new IllegalStateException("piste " + trackNo + " absente de la release");
    }

    private static int trackNumber(String s) {
        if (s == null) return -1;
        String digits = s.trim().split("[/\\s]")[0];
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return -1; }
    }

    private boolean isAlreadyInLibrary(String artist, String album, String title) {
        if (album.isBlank() && title.isBlank()) return false;
        for (FileEntry fe : owner.allEntries()) {
            var t = fe.activeTags();
            if (t == null) continue;
            if (!album.isBlank() && norm(album).equals(norm(t.album))) return true;
            if (!title.isBlank() && norm(title).equals(norm(t.title))
                    && (artist.isBlank() || sameArtist(artist, t.artist))) return true;
        }
        return false;
    }

    /** Même artiste à la casse, aux accents et à la ponctuation près ; « Various » (compilation) ne contredit personne. */
    static boolean sameArtist(String a, String b) {
        String x = norm(a), y = norm(b);
        return x.equals(y) || isVariousArtists(x) || isVariousArtists(y);
    }

    private static String formatDuration(int sec) {
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    /** Titre en gras + sous-titre discret (année/nb pistes) — vide (masqué) tant qu'aucune
     *  identification n'a abouti, plutôt qu'un espace réservé toujours visible mais vide. */
    private void setAlbumHeader(MusicBrainzClient.ReleaseTracklist rt) {
        if (rt == null) {
            lblAlbumHeader.setText(" ");
            lblAlbumSub.setText(" ");
            coverFor = "";
            lblCover.setIcon(null);
            lblCover.setVisible(false);
            return;
        }
        String year = rt.year() != null && !rt.year().isBlank() ? " (" + rt.year() + ")" : "";
        lblAlbumHeader.setText(I18n.t("<html>%s — <span style='font-weight:normal'>%s</span>%s</html>",
                escapeHtml(rt.albumArtist()), escapeHtml(rt.album()), escapeHtml(year)));
        List<String> bits = new ArrayList<>();
        if (rt.label() != null && !rt.label().isBlank()) bits.add(rt.label());
        if (rt.country() != null && !rt.country().isBlank()) bits.add(rt.country());
        bits.add(I18n.t("%d pistes", toc != null ? toc.tracks().size() : rt.tracks().size()));
        if (toc != null) {
            int total = 0;
            for (CdRipper.Track t : toc.tracks()) total += t.lengthSectors() / 75;
            bits.add(I18n.t("%d min %02d s", total / 60, total % 60));
        }
        bits.add(exactMatch ? I18n.t("disque reconnu exactement")
                : audioMatch ? I18n.t("identifié par l'audio")
                : gnudbMatch ? I18n.t("base GnuDB (CDDB), à vérifier")
                : I18n.t("identification approximative, à vérifier"));
        lblAlbumSub.setText(String.join("  ·  ", bits));
        loadCover(rt.releaseMbid());
    }

    /** Pochette de la release (Cover Art Archive, miniature) — chargée en arrière-plan, absente si introuvable. */
    private void loadCover(String releaseMbid) {
        coverFor = releaseMbid == null ? "" : releaseMbid;
        lblCover.setIcon(null);
        lblCover.setVisible(false);
        if (coverFor.isBlank()) return;
        final String wanted = coverFor;
        new SwingWorker<Image, Void>() {
            @Override protected Image doInBackground() throws Exception {
                var client = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(java.time.Duration.ofSeconds(10)).build();
                var rsp = client.send(java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create("https://coverartarchive.org/release/" + wanted + "/front-250"))
                        .timeout(java.time.Duration.ofSeconds(20)).header("User-Agent", "OpenTagger").build(),
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (rsp.statusCode() != 200) return null;
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(rsp.body()));
                return img == null ? null : img.getScaledInstance(96, 96, Image.SCALE_SMOOTH);
            }
            @Override protected void done() {
                try {
                    Image img = get();
                    if (img != null && wanted.equals(coverFor)) { lblCover.setIcon(new ImageIcon(img)); lblCover.setVisible(true); }
                } catch (Exception ignored) { /* pas de pochette : l'en-tête reste sans image */ }
            }
        }.execute();
    }

    /** Dossier de bibliothèque des réglages (celui où le renommage déplace les audios), ou null s'il n'y en a pas. */
    private static Path libraryRoot() {
        String root = Config.get().libraryRoot();
        if (Config.get().useLibraryRootEnabled() && root != null && !root.isBlank()) return Paths.get(root);
        return null;
    }

    private void refreshDestLabel() {
        Path lib = libraryRoot();
        lblDest.setText(lib != null
                ? I18n.t("Destination : %s  (dossier de bibliothèque des Réglages)", lib)
                : I18n.t("Destination : à choisir au moment d'extraire (aucun dossier de bibliothèque dans les Réglages)"));
    }

    private static String escapeHtml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Renderer texte aligné à droite, couleur optionnelle (ex. rouge pour signaler un doublon) —
     *  utilisé pour les colonnes numériques/courtes (#, Durée, Déjà présent ?). */
    private static DefaultTableCellRenderer rightAligned(Color colorWhenNonBlank) {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                String s = v == null ? "" : v.toString();
                if (colorWhenNonBlank != null) setForeground(!sel && !s.isBlank() ? colorWhenNonBlank : t.getForeground());
                return c;
            }
        };
    }

    /** Renderer coloré pour la colonne Statut pendant l'extraction — vert pour un succès, rouge pour
     *  un échec, couleur neutre pour "en cours" ou avant toute extraction (case vide). Même palette
     *  que le reste de l'appli pour ce genre d'indicateur (voir CompilationMatchDialog, DetailPanel). */
    private static DefaultTableCellRenderer statusRenderer() {
        return new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                    boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                String s = v == null ? "" : v.toString();
                Color fg = t.getForeground();
                if (s.startsWith("✔")) fg = new Color(0x66bb6a);
                else if (s.startsWith("✗")) fg = new Color(0xcc4444);
                else if (s.startsWith("⏳")) fg = new Color(0x90A4AE);
                setForeground(sel ? t.getSelectionForeground() : fg);
                return c;
            }
        };
    }

    /** Un événement de progression publié depuis le thread d'extraction — (ligne du tableau à
     *  mettre à jour, nouveau texte de la colonne Statut, nombre de pistes terminées jusqu'ici). */
    private record RipProgress(int row, String status, int done) {}

    private void extract() {
        List<Integer> selected = new ArrayList<>();
        // row ↔ numéro de piste, pour retrouver la bonne ligne à mettre à jour pendant l'extraction
        // sans dépendre de l'ordre d'itération (le tableau reste dans l'ordre du TOC, mais autant
        // ne pas supposer un rapport 1:1 entre "selected" et les lignes si jamais une piste est
        // décochée entre-temps — impossible ici puisque les cases sont lues une seule fois avant de
        // désactiver la fenêtre, mais la map reste correcte même si ce contrat change plus tard).
        java.util.Map<Integer, Integer> rowByTrackNo = new java.util.HashMap<>();
        List<Integer> dupTracks = new ArrayList<>();
        for (int i = 0; i < trackTableModel.getRowCount(); i++) {
            int trackNo = (Integer) trackTableModel.getValueAt(i, COL_NO);
            rowByTrackNo.put(trackNo, i);
            if (!Boolean.TRUE.equals(trackTableModel.getValueAt(i, 0))) continue;
            selected.add(trackNo);
            if (I18n.t("oui").equals(trackTableModel.getValueAt(i, COL_DUP))) dupTracks.add(trackNo);
        }
        if (selected.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18n.t("Aucune piste cochée."));
            return;
        }
        // Pistes de la bibliothèque à mettre à la corbeille APRÈS extraction réussie de leur remplaçante (choix « Écraser »).
        java.util.Map<Integer, List<Path>> toReplace = new java.util.HashMap<>();
        if (!dupTracks.isEmpty()) {
            // Écraser n'est proposé que si le disque est reconnu EXACTEMENT (Disc ID) : sur un nom approximatif, un mauvais
            // album pourrait désigner de vrais fichiers. L'ancien fichier part à la corbeille (récupérable), jamais supprimé.
            String replace = I18n.t("Écraser");
            String skip = I18n.t("Non, ignorer ces pistes");
            String both = I18n.t("Garder les deux");
            String cancel = I18n.t("Annuler");
            Object[] options = exactMatch ? new Object[]{replace, skip, both, cancel} : new Object[]{skip, both, cancel};
            int picked = JOptionPane.showOptionDialog(this,
                    I18n.t("%d piste(s) cochée(s) sur %d sont déjà dans la bibliothèque.\n%s",
                            dupTracks.size(), selected.size(),
                            exactMatch
                                ? I18n.t("Écraser : l'ancien fichier va à la corbeille une fois la piste du CD extraite.")
                                : I18n.t("Disque reconnu de façon approximative : « Écraser » n'est pas proposé.")),
                    I18n.t("Déjà dans la bibliothèque"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                    options, options[0]);
            if (picked < 0) return; // fenêtre fermée
            String choice = (String) options[picked];
            if (choice.equals(cancel)) return;
            if (choice.equals(skip)) {
                selected.removeAll(dupTracks);
                if (selected.isEmpty()) {
                    JOptionPane.showMessageDialog(this, I18n.t("Toutes les pistes cochées sont déjà présentes : rien à extraire."));
                    return;
                }
            } else if (choice.equals(replace)) {
                for (int trackNo : dupTracks) {
                    String title = String.valueOf(trackTableModel.getValueAt(rowByTrackNo.get(trackNo), COL_TITLE));
                    String artist = "";
                    if (identified != null) for (var rt : identified.tracks()) if (rt.trackNo() == trackNo) { artist = rt.artist(); break; }
                    // Fichiers de la liste de l'app ET fichiers trouvés sur le disque dans la bibliothèque, sans doublon de chemin.
                    java.util.LinkedHashSet<Path> paths = new java.util.LinkedHashSet<>();
                    for (FileEntry fe : libraryMatchesForTrack(artist, identified == null ? "" : identified.album(), title, trackNo))
                        paths.add(fe.currentPath != null ? fe.currentPath : fe.file.toPath());
                    paths.addAll(diskMatches.getOrDefault(trackNo, List.of()));
                    if (!paths.isEmpty()) toReplace.put(trackNo, new ArrayList<>(paths));
                }
            }
        }
        final java.util.Set<Path> trashed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger trashFailed = new java.util.concurrent.atomic.AtomicInteger();

        AudioTranscoder.Format format = (AudioTranscoder.Format) cbFormat.getSelectedItem();
        int bitrate = Config.get().num("transcode.bitrate_kbps", 320);
        Path stagingDir = Paths.get(System.getProperty("user.home"), ".opentagger", "cd-import",
                String.valueOf(System.currentTimeMillis()));

        // Destination : le dossier de bibliothèque des Réglages (celui où le renommage déplace les audios). Sans bibliothèque
        // configurée, on la demande ici une fois, plutôt que de laisser les fichiers dans un dossier de travail caché.
        final Path library = libraryRoot();
        Path chosen = library;
        if (chosen == null) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle(I18n.t("Où ranger les pistes extraites ?"));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            chosen = fc.getSelectedFile().toPath();
        }
        final Path destRoot = chosen;
        // Les pistes ne reçoivent leurs tags depuis la release que si elle décrit bien CE disque (même nombre de pistes) :
        // sur une édition à plusieurs disques, le numéro de piste du CD ne désigne plus à coup sûr la bonne piste.
        final boolean tagFromRelease = identified != null && identified.tracks().size() == toc.tracks().size();
        final java.util.List<Path> savedPaths = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.List<Path> unsortedPaths = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicInteger moved = new java.util.concurrent.atomic.AtomicInteger();
        final Path unsortedDir = destRoot.resolve(I18n.t("CD à identifier"))
                .resolve(new java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss").format(new java.util.Date()));

        btnExtract.setEnabled(false);
        btnDetect.setEnabled(false);
        btnDataDisc.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setValue(0);
        progressBar.setMaximum(selected.size());
        progressBar.setString("0 / " + selected.size());
        lblProgress.setText(I18n.t("Piste %d/%d", 0, selected.size()));

        new SwingWorker<int[], RipProgress>() {
            @Override protected int[] doInBackground() {
                CdRipper ripper = new CdRipper();
                AudioTranscoder transcoder = new AudioTranscoder();
                // Même chaîne que « Enregistrer tout » : tags, pochette, renommage dans la bibliothèque selon le masque des Réglages.
                com.opentagger.CaaClient caa = new com.opentagger.CaaClient();
                com.opentagger.FanArtClient fanArt = new com.opentagger.FanArtClient();
                com.opentagger.DeezerClient deezer = new com.opentagger.DeezerClient();
                com.opentagger.DiscogsClient discogs = new com.opentagger.DiscogsClient();
                com.opentagger.TagWriter writer = new com.opentagger.TagWriter();
                com.opentagger.FileRenamer renamer = new com.opentagger.FileRenamer();
                com.opentagger.MusicBrainzOAuth mbOauth = new com.opentagger.MusicBrainzOAuth();
                com.opentagger.MetadataCache cache = new com.opentagger.MetadataCache();
                int ok = 0, fail = 0, doneCount = 0;
                try {
                for (int trackNo : selected) {
                    int row = rowByTrackNo.getOrDefault(trackNo, -1);
                    publish(new RipProgress(row, I18n.t("⏳ Extraction…"), doneCount));
                    try {
                        Path wav = ripper.ripTrackToWav(trackNo, stagingDir);
                        publish(new RipProgress(row, I18n.t("⏳ Conversion…"), doneCount));
                        // Format cible TOUJOURS ≠ wav (AudioTranscoder.Format n'a pas d'entrée WAV,
                        // ripTrackToWav() produit forcément un .wav) — la conversion s'applique donc
                        // systématiquement ici. Échec de conversion : on garde le WAV brut plutôt que
                        // de perdre la piste (transcode() lève une exception mais ne supprime jamais
                        // la source avant que le fichier produit soit confirmé non vide).
                        Path audio = wav;
                        try { audio = transcoder.transcode(wav, format, bitrate, true); }
                        catch (Exception ex) { /* garde le WAV si la conversion échoue — jamais rien perdre */ }

                        // Rangement. Disque identifié : tags de la release + pochette + déplacement dans la bibliothèque (masque des
                        // Réglages). Sinon (ou si l'enregistrement échoue) : dossier daté « CD à identifier » DANS la destination,
                        // jamais laissé dans le dossier de travail caché.
                        String finalStatus = I18n.t("✔ Extrait");
                        boolean placed = false;
                        if (tagFromRelease && !audio.toString().toLowerCase().endsWith(".wav")) {
                            publish(new RipProgress(row, I18n.t("⏳ Tags et rangement…"), doneCount));
                            try {
                                var res = com.opentagger.TagEnrichment.saveEntry(audio.toFile(), tagsFor(trackNo), caa, fanArt, deezer,
                                        discogs, writer, renamer, cache, mbOauth, destRoot, Config.get().defaultRenameMask(), null);
                                if (res.finalPath() != null) savedPaths.add(res.finalPath());
                                placed = true;
                                finalStatus = res.durationMismatchMoved()
                                        ? I18n.t("✔ Rangé — durée différente de MusicBrainz, à vérifier")
                                        : res.renameError() != null ? I18n.t("✔ Tags écrits (renommage échoué)") : I18n.t("✔ Rangé");
                            } catch (Exception ex) {
                                finalStatus = I18n.t("✔ Extrait (tags non écrits : %s)", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                            }
                        }
                        if (!placed) {
                            try {
                                Files.createDirectories(unsortedDir);
                                unsortedPaths.add(com.opentagger.FileRenamer.moveToFolder(audio, unsortedDir));
                            } catch (Exception ex) {
                                unsortedPaths.add(audio); // reste dans le dossier de travail : signalé à la fin
                            }
                        }
                        // « Écraser » : la nouvelle piste existe (extraction réussie), l'ancienne va maintenant à la corbeille.
                        // Jamais avant : si l'extraction échoue, la bibliothèque reste intacte.
                        for (Path old : toReplace.getOrDefault(trackNo, List.of())) {
                            if (!Files.exists(old)) continue;
                            if (com.opentagger.TrashHelper.moveToTrash(old.toFile())) trashed.add(old);
                            else trashFailed.incrementAndGet();
                        }
                        ok++;
                        doneCount++;
                        publish(new RipProgress(row, finalStatus, doneCount));
                    } catch (Exception ex) {
                        fail++;
                        doneCount++;
                        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                        publish(new RipProgress(row, I18n.t("✗ Échec : %s", msg), doneCount));
                    }
                }
                // Disque INCONNU (aucune release proposée) : les pistes sont maintenant des fichiers sans nom. On les identifie une par une par
                // leur empreinte audio (compilation gravée : chaque piste vient d'un album différent) et on range celles qui sont reconnues
                // sous leur vrai nom ; les autres restent dans « CD à identifier ». Désactivable : cd.identify_unknown_tracks=false.
                if (!tagFromRelease && !unsortedPaths.isEmpty() && Config.get().bool("cd.identify_unknown_tracks", true)) {
                    final int dc = doneCount;
                    try {
                        identifyUnsortedByAudio(unsortedPaths, savedPaths, destRoot, caa, fanArt, deezer, discogs, writer, renamer, cache, mbOauth,
                                msg -> publish(new RipProgress(-1, msg, dc)));
                    } catch (Exception ex) {
                        System.err.println("[OT] CD : identification des pistes inconnues impossible : " + ex);
                    }
                }
                } finally {
                    try { cache.close(); } catch (Exception ignored) {}
                }
                return new int[]{ok, fail};
            }
            @Override protected void process(List<RipProgress> chunks) {
                for (RipProgress p : chunks) {
                    if (p.row() >= 0 && p.row() < trackTableModel.getRowCount())
                        trackTableModel.setValueAt(p.status(), p.row(), COL_STATUS);
                    progressBar.setValue(p.done());
                    progressBar.setString(p.done() + " / " + selected.size());
                    lblProgress.setText(I18n.t("Piste %d/%d", Math.min(p.done() + 1, selected.size()), selected.size()));
                }
            }
            @Override protected void done() {
                int[] r;
                try { r = get(); } catch (Exception ex) { r = new int[]{0, selected.size()}; }
                lblStatus.setText(I18n.t("%d piste(s) extraite(s), %d échec(s).", r[0], r[1]));
                if (!trashed.isEmpty()) {
                    // retirées de la liste affichée (si elles y figuraient) : leurs fichiers sont à la corbeille
                    java.util.Set<FileEntry> gone = new java.util.LinkedHashSet<>();
                    for (FileEntry fe : owner.allEntries())
                        if (trashed.contains(fe.currentPath != null ? fe.currentPath : fe.file.toPath())) gone.add(fe);
                    if (!gone.isEmpty()) owner.removeFromList(gone);
                    lblStatus.setText(lblStatus.getText() + " " + I18n.t("%d ancien(s) fichier(s) mis à la corbeille.", trashed.size())
                            + (trashFailed.get() > 0 ? " " + I18n.t("%d non remplacé(s) (corbeille impossible).", trashFailed.get()) : ""));
                }
                lblProgress.setText(" ");
                progressBar.setVisible(false);
                btnExtract.setEnabled(true);
                btnDetect.setEnabled(true);
                btnDataDisc.setEnabled(true);
                // Toutes les pistes sont extraites : le disque n'a plus de raison de rester dans le lecteur (réglage cd.eject_after, actif par
                // défaut). Pas d'éjection s'il y a eu des échecs : on pourrait vouloir relancer l'extraction.
                if (r[0] > 0 && r[1] == 0 && Config.get().bool("cd.eject_after", true)) {
                    lblStatus.setText(lblStatus.getText() + "  " + I18n.t("Éjection du disque…"));
                    Thread ej = new Thread(() -> new CdRipper().eject(), "cd-eject");
                    ej.setDaemon(true);
                    ej.start();
                }
                if (r[0] > 0) {
                    // Charger dans la liste les dossiers où les pistes ont atterri (le chargement ignore ce qui y est déjà).
                    java.util.Set<Path> folders = new java.util.LinkedHashSet<>();
                    for (Path p : savedPaths) if (p.getParent() != null) folders.add(p.getParent());
                    boolean unsorted = false;
                    for (Path p : unsortedPaths) if (p.getParent() != null) { folders.add(p.getParent()); unsorted = true; }
                    for (Path f : folders) owner.importFolder(f.toFile());
                    try { Files.deleteIfExists(stagingDir); } catch (IOException ignored) {} // seulement s'il est vide
                    Path shown = !savedPaths.isEmpty() && savedPaths.get(0).getParent() != null
                            ? savedPaths.get(0).getParent() : (unsorted ? unsortedDir : destRoot);
                    String text = I18n.t("%d piste(s) extraite(s) — rangées dans %s", r[0], shown)
                            + (unsorted ? "\n\n" + I18n.t("Les pistes sans tags sont dans « %s » : lancez l'identification sur ce dossier.", unsortedDir) : "");
                    Object[] btns = {I18n.t("Ouvrir le dossier"), I18n.t("OK")};
                    int pick = JOptionPane.showOptionDialog(CdImportDialog.this, text, I18n.t("Extraction terminée"),
                            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, btns, btns[1]);
                    if (pick == 0) {
                        try { Desktop.getDesktop().open(shown.toFile()); } catch (Exception ignored) {}
                    }
                }
            }
        }.execute();
    }

    /**
     * Identifie par empreinte audio les pistes extraites d'un disque inconnu et range celles qui sont reconnues (tags + pochette +
     * renommage selon le masque, comme « Enregistrer tout »). Une piste non reconnue, ou reconnue seulement sous un titre générique, reste
     * dans le dossier daté. Passe ciblée : elle ne passe ni par la file du taguage général ni par son frein d'enregistrement.
     */
    private void identifyUnsortedByAudio(List<Path> unsorted, List<Path> saved, Path destRoot,
                                         com.opentagger.CaaClient caa, com.opentagger.FanArtClient fanArt, com.opentagger.DeezerClient deezer,
                                         com.opentagger.DiscogsClient discogs, com.opentagger.TagWriter writer, com.opentagger.FileRenamer renamer,
                                         com.opentagger.MetadataCache cache, com.opentagger.MusicBrainzOAuth mbOauth,
                                         java.util.function.Consumer<String> status) throws Exception {
        List<FileEntry> entries = new ArrayList<>();
        for (Path p : unsorted) {
            java.io.File f = p.toFile();
            if (!f.isFile() || f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".wav")) continue;
            entries.add(new FileEntry(f, com.opentagger.TagReader.read(f)));
        }
        if (entries.isEmpty()) return;
        status.accept(I18n.t("Identification des pistes par l'audio (%d)…", entries.size()));
        TaggingWorker w = new TaggingWorker(entries, true, s -> {}, e -> {});
        w.setBypassBacklog(true);
        w.execute();
        w.get();
        SwingUtilities.invokeAndWait(() -> { }); // les résultats sont posés sur l'EDT : on attend que la file soit vidée
        int placedCount = 0;
        for (FileEntry e : entries) {
            if (e.status != FileEntry.Status.IDENTIFIED || e.result == null) continue;
            try {
                var res = com.opentagger.TagEnrichment.saveEntry(e.file, e.result, caa, fanArt, deezer, discogs, writer, renamer, cache,
                        mbOauth, destRoot, Config.get().defaultRenameMask(), null);
                if (res.finalPath() != null) {
                    saved.add(res.finalPath());
                    unsorted.remove(e.file.toPath());
                    placedCount++;
                }
            } catch (Exception ex) {
                System.err.println("[OT] CD : piste " + e.file.getName() + " reconnue mais non rangée : " + ex.getMessage());
            }
        }
        status.accept(I18n.t("%d piste(s) reconnue(s) par l'audio et rangée(s).", placedCount));
    }

    // ── CD de données (copie de fichiers, pas d'extraction audio) ──────────────────────────────

    /** Disque de données détecté : montre ce qu'il contient et propose de le copier dans un NOUVEAU dossier de la bibliothèque (jamais en vrac
     *  dans la racine, jamais en écrasant). */
    private void offerDataDiscCopy(Path dataRoot) {
        long[] m = com.opentagger.DataDiscCopier.measure(dataRoot);
        String label = "";
        try { label = java.nio.file.Files.getFileStore(dataRoot).name(); } catch (Exception ignored) {}
        String folderName = (label == null || label.isBlank() ? "CD de données" : "CD " + label.trim()).replaceAll("[<>:\"/\\\\|?*]", "_");
        Path lib = libraryRoot();
        Path suggested = (lib != null ? lib : java.nio.file.Paths.get(System.getProperty("user.home"))).resolve("CD de données").resolve(folderName);
        lblStatus.setText(I18n.t("CD de données détecté : %d fichier(s), %d Mo.", m[0], m[1] / (1024 * 1024)));
        Object[] opts = {I18n.t("Copier ici"), I18n.t("Choisir un autre dossier…"), I18n.t("Annuler")};
        int pick = JOptionPane.showOptionDialog(this,
                I18n.t("<html>Ce disque est un <b>CD de données</b> (%d fichier(s), %d Mo).<br><br>Copier ses fichiers dans :<br><b>%s</b><br><br>"
                     + "Rien n'est jamais écrasé : un fichier déjà présent et identique est ignoré, un fichier différent du même nom est copié sous « nom (2) ».</html>",
                        m[0], m[1] / (1024 * 1024), suggested),
                I18n.t("CD de données"), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (pick == 0) { copyDataDisc(dataRoot, suggested); return; }
        if (pick == 1) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle(I18n.t("Copier vers…"));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) copyDataDisc(dataRoot, fc.getSelectedFile().toPath());
        }
    }

    private void copyDataDisc(Path source, Path dest) {
        lblStatus.setText(I18n.t("Copie en cours…"));
        btnDetect.setEnabled(false);
        btnDataDisc.setEnabled(false);
        new SwingWorker<com.opentagger.DataDiscCopier.Result, Void>() {
            @Override protected com.opentagger.DataDiscCopier.Result doInBackground() throws IOException {
                return com.opentagger.DataDiscCopier.copy(source, dest);
            }
            @Override protected void done() {
                btnDetect.setEnabled(true);
                btnDataDisc.setEnabled(true);
                try {
                    var r = get();
                    lblStatus.setText(I18n.t("%d fichier(s) copié(s), %d déjà présent(s), %d renommé(s) pour ne rien écraser, %d échec(s).",
                            r.copied(), r.identical(), r.renamed(), r.failed()));
                    if (r.failed() == 0 && Config.get().bool("cd.eject_after", true)) {
                        Thread ej = new Thread(() -> new CdRipper().eject(), "cd-eject");
                        ej.setDaemon(true);
                        ej.start();
                    }
                    Object[] btns = {I18n.t("Ouvrir le dossier"), I18n.t("OK")};
                    int pick = JOptionPane.showOptionDialog(CdImportDialog.this,
                            I18n.t("Copie terminée dans :\n%s", dest), I18n.t("CD de données"),
                            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, btns, btns[1]);
                    if (pick == 0) { try { Desktop.getDesktop().open(dest.toFile()); } catch (Exception ignored) {} }
                } catch (Exception ex) {
                    lblStatus.setText(I18n.t("Échec de la copie : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    private void importDataDisc() {
        JFileChooser fcSrc = new JFileChooser();
        fcSrc.setDialogTitle(I18n.t("Sélectionne le dossier monté du CD de données"));
        fcSrc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fcSrc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File source = fcSrc.getSelectedFile();

        JFileChooser fcDest = new JFileChooser();
        fcDest.setDialogTitle(I18n.t("Copier vers…"));
        fcDest.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fcDest.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File dest = fcDest.getSelectedFile();

        lblStatus.setText(I18n.t("Copie en cours…"));
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws IOException {
                var r = com.opentagger.DataDiscCopier.copy(source.toPath(), dest.toPath()); // jamais d'écrasement (voir DataDiscCopier)
                return r.copied() + r.renamed();
            }
            @Override protected void done() {
                int n;
                try { n = get(); } catch (Exception ex) { n = 0; }
                lblStatus.setText(I18n.t("%d fichier(s) copié(s).", n));
            }
        }.execute();
    }

    private static int copyRecursive(Path src, Path destRoot) throws IOException {
        int[] count = {0};
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path rel = src.relativize(file);
                Path target = destRoot.resolve(rel);
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                count[0]++;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }
}
