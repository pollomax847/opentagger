package com.opentagger.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.opentagger.AudioScanner;
import com.opentagger.Config;
import com.opentagger.FileRenamer;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MainFrame extends JFrame {

    // ── Palette ──────────────────────────────────────────────────────────────
    private static final Color COL_TAGGED     = new Color(40,  180, 100, 55);
    private static final Color COL_ERROR      = new Color(220, 60,  60,  55);
    private static final Color COL_PROCESSING = new Color(60,  130, 255, 55);
    private static final Color COL_SKIPPED    = new Color(200, 150, 30,  45);
    private static final Color ACCENT         = new Color(0x4DB6AC); // teal
    private static final Color HEADER_BG      = new Color(0x1E1F22);

    // ── État ─────────────────────────────────────────────────────────────────
    private final FileTableModel tableModel = new FileTableModel();
    private TaggingWorker        worker;
    private int                  currentMask = Config.get().defaultRenameMask();

    // ── Composants header ────────────────────────────────────────────────────
    private JButton    btnTagAll, btnTagSel, btnCancel;
    private JCheckBox  chkAcoustId;
    private JLabel     lblMask;

    // ── Stats live ───────────────────────────────────────────────────────────
    private JLabel     lblStatTotal, lblStatTagged, lblStatSkipped,
                       lblStatError, lblStatPending;

    // ── Table + tri/filtre ────────────────────────────────────────────────────
    private JTable                          table;
    private TableRowSorter<FileTableModel>  rowSorter;
    private JTextField                      tfFilter;
    private JComboBox<String>               cbFilterField;
    private JComboBox<String>               cbFilterStatus;

    // ── Panneau de détail ─────────────────────────────────────────────────────
    private DetailPanel detailPanel;
    private JLabel      lblFilePath;
    private JLabel      lblCoverImg;

    // ── Undo / Redo ───────────────────────────────────────────────────────────
    private final com.opentagger.UndoManager undoManager = new com.opentagger.UndoManager();
    private JButton btnUndo, btnRedo;

    // ── Barre de statut ───────────────────────────────────────────────────────
    private JLabel       lblStatus;
    private JProgressBar progress;

    // ── Entrée publique ───────────────────────────────────────────────────────

    public static void launch() {
        FlatDarkLaf.setup();
        UIManager.put("Table.alternateRowColor", new Color(45, 47, 52));
        SwingUtilities.invokeLater(() ->
            SplashScreen.show(() -> SwingUtilities.invokeLater(() -> new MainFrame().setVisible(true)))
        );
    }

    public MainFrame() {
        super("OpenTagger  " + Config.get().str("app.version", "0.1.0"));
        buildUI();
        installDragDrop();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Construction
    // ═══════════════════════════════════════════════════════════════════════════

    private void buildUI() {
        setIconImages(AppIcon.all());
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1340, 820);
        setMinimumSize(new Dimension(960, 600));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(0, 0));

        setJMenuBar(buildMenuBar());

        // ── Header + stats + filtre ─────────────────────────────────────────
        JPanel topArea = new JPanel(new BorderLayout(0, 0));
        topArea.add(buildHeader(),    BorderLayout.NORTH);
        topArea.add(buildStatsStrip(), BorderLayout.CENTER);
        topArea.add(buildFilterBar(), BorderLayout.SOUTH);
        add(topArea, BorderLayout.NORTH);

        // ── Split horizontal : table gauche | détail droit ──────────────────
        add(buildMainSplit(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshDetail();
        });

        // ── Undo/Redo clavier ─────────────────────────────────────────────
        var rootMap   = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actionMap = getRootPane().getActionMap();
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "undo");
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "redo");
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo");
        actionMap.put("undo", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { performUndo(); }
        });
        actionMap.put("redo", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { performRedo(); }
        });

        // F5 = tagger tous les fichiers cochés
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F5, 0), "tagAll");
        actionMap.put("tagAll", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { startTagging(false); }
        });

        // F6 = tagger la sélection
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F6, 0), "tagSel");
        actionMap.put("tagSel", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { startTagging(true); }
        });

        // Suppr = retirer les lignes sélectionnées de la liste
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "removeRows");
        actionMap.put("removeRows", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table == null) return;
                int[] rows = table.getSelectedRows();
                for (int i = rows.length - 1; i >= 0; i--)
                    tableModel.remove(table.convertRowIndexToModel(rows[i]));
            }
        });

        // Ctrl+F = focus sur le filtre
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "focusFilter");
        actionMap.put("focusFilter", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (tfFilter != null) { tfFilter.requestFocusInWindow(); tfFilter.selectAll(); }
            }
        });

        // Ctrl+M = correspondance manuelle
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "matchDialog");
        actionMap.put("matchDialog", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openMatchDialog(); }
        });

        // Ctrl+O = ouvrir dossier
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_O,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "openFolder");
        actionMap.put("openFolder", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { openFolder(); }
        });

        // Ctrl+E = export CSV
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_E,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "exportCsv");
        actionMap.put("exportCsv", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { exportCsv(); }
        });

        // Ctrl+R = renommer
        rootMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "rename");
        actionMap.put("rename", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { renameTagged(); }
        });

        undoManager.addListener(this::updateUndoButtons);
    }

    // ── Menu bar ─────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar mb = new JMenuBar();
        mb.add(buildMenuFichier());
        mb.add(buildMenuEdition());
        mb.add(buildMenuTagger());
        mb.add(buildMenuOutils());
        return mb;
    }

    private JMenu buildMenuFichier() {
        JMenu m = new JMenu("Fichier");
        m.setMnemonic('F');
        m.add(mitem("Ouvrir un dossier…",     "Ctrl+O",  e -> openFolder()));
        m.addSeparator();
        m.add(mitem("Exporter CSV…",           "Ctrl+E",  e -> exportCsv()));
        m.addSeparator();
        m.add(mitem("Quitter",                 null,      e -> System.exit(0)));
        return m;
    }

    private JMenu buildMenuEdition() {
        JMenu m = new JMenu("Édition");
        m.setMnemonic('E');
        // Références gardées pour enable/disable dynamique
        JMenuItem miUndo = mitem("Annuler",    "Ctrl+Z",  e -> performUndo());
        JMenuItem miRedo = mitem("Rétablir",   "Ctrl+Y",  e -> performRedo());
        m.add(miUndo); m.add(miRedo);
        m.addSeparator();
        m.add(mitem("Tout cocher",             null,      e -> setAllSelected(true)));
        m.add(mitem("Tout décocher",           null,      e -> setAllSelected(false)));
        m.add(mitem("Retirer la sélection",    null,  e -> {
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--)
                tableModel.remove(table.convertRowIndexToModel(rows[i]));
        }));
        m.addSeparator();
        m.add(mitem("Sélectionner les non identifiés", null, e -> selectByStatus(FileEntry.Status.SKIPPED)));
        m.add(mitem("Sélectionner les erreurs",        null, e -> selectByStatus(FileEntry.Status.ERROR)));
        m.add(mitem("Sélectionner les en attente",     null, e -> selectByStatus(FileEntry.Status.PENDING)));
        m.addSeparator();
        m.add(mitem("Préférences…",            "Ctrl+Virgule", e -> new SettingsDialog(this).setVisible(true)));
        return m;
    }

    private JMenu buildMenuTagger() {
        JMenu m = new JMenu("Tagger");
        m.setMnemonic('T');
        m.add(mitem("Tout tagger (cochés)",    "F5",  e -> startTagging(false)));
        m.add(mitem("Tagger la sélection",     "F6",  e -> startTagging(true)));
        m.addSeparator();
        m.add(mitem("Arrêter",                 null,  e -> cancelTagging()));
        m.addSeparator();
        m.add(mitem("Renommer les fichiers tagués", "Ctrl+R", e -> renameTagged()));
        m.add(mitem("Choisir le masque…",      null,      e -> chooseMask()));
        return m;
    }

    private JMenu buildMenuOutils() {
        JMenu m = new JMenu("Outils");
        m.setMnemonic('O');
        m.add(mitem("Correspondance manuelle…","Ctrl+M",  e -> openMatchDialog()));
        m.add(mitem("Gérer la pochette…",      null,      e -> openCoverDialog()));
        m.addSeparator();
        m.add(mitem("Détecter les doublons…",  null,      e -> detectDuplicates()));
        m.add(mitem("Supprimer les fichiers illisibles…", null, e -> deleteErrorFiles()));
        m.add(mitem("Historique de taguage…",  null,      e -> new HistoryDialog(this).setVisible(true)));
        m.addSeparator();
        m.add(mitem("Modifier sur MusicBrainz",null,      e -> openMbEditPage()));
        m.add(mitem("Contribuer à MusicBrainz…", null,   e -> openMbContribute()));
        m.add(mitem("Soumettre fingerprint AcoustID", null, e -> submitAcoustId()));
        return m;
    }

    private JMenuItem mitem(String label, String shortcut, java.awt.event.ActionListener al) {
        JMenuItem mi = new JMenuItem(label);
        mi.addActionListener(al);
        if (shortcut != null) {
            KeyStroke ks = KeyStroke.getKeyStroke(shortcut.replace("Ctrl+", "control ").replace("Virgule", "COMMA"));
            if (ks != null) mi.setAccelerator(ks);
        }
        return mi;
    }

    // ── Barre d'outils (actions principales seulement) ───────────────────────

    private JPanel buildHeader() {
        JPanel bar = new JPanel(new BorderLayout(0, 0));
        bar.setBackground(HEADER_BG);
        bar.setBorder(new EmptyBorder(0, 0, 0, 0));

        // ── Branding gauche ──────────────────────────────────────────────────
        JLabel logo = new JLabel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT);
                g2.fillRoundRect(6, 6, 32, 32, 8, 8);
                g2.setColor(HEADER_BG);
                g2.setFont(new Font("SansSerif", Font.BOLD, 14));
                FontMetrics fm = g2.getFontMetrics();
                String s = "OT";
                g2.drawString(s, 6 + (32 - fm.stringWidth(s))/2, 6 + (32 + fm.getAscent() - fm.getDescent())/2);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() { return new Dimension(44, 44); }
        };
        JLabel appName = new JLabel("OpenTagger");
        appName.setForeground(new Color(0xE0E0E0));
        appName.putClientProperty("FlatLaf.style", "font: bold 15 $defaultFont");
        appName.setBorder(new EmptyBorder(0, 0, 0, 18));

        JPanel brandPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        brandPanel.setBackground(HEADER_BG);
        brandPanel.setBorder(new EmptyBorder(4, 8, 4, 0));
        brandPanel.add(logo);
        brandPanel.add(appName);

        // ── Actions centre ───────────────────────────────────────────────────
        JButton btnOpen = accentBtn("Ouvrir dossier", "Ctrl+O");
        btnOpen.addActionListener(e -> openFolder());

        btnTagAll = headerBtn("Tout tagger", "Tagger tous les fichiers cochés (F5)");
        btnTagSel = headerBtn("Tagger la sélection", "Tagger les lignes sélectionnées (F6)");
        btnCancel = headerBtn("Arrêter", "Annuler le traitement en cours");
        btnTagAll.addActionListener(e -> startTagging(false));
        btnTagSel.addActionListener(e -> startTagging(true));
        btnCancel.addActionListener(e -> cancelTagging());
        btnCancel.setEnabled(false);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 6));
        actionsPanel.setBackground(HEADER_BG);
        actionsPanel.add(btnOpen);
        actionsPanel.add(vSep());
        actionsPanel.add(btnTagAll);
        actionsPanel.add(btnTagSel);
        actionsPanel.add(btnCancel);

        // ── Droite : AcoustID + masque + undo/redo ───────────────────────────
        chkAcoustId = new JCheckBox("AcoustID");
        chkAcoustId.setBackground(HEADER_BG);
        chkAcoustId.setForeground(new Color(0xB0BEC5));
        chkAcoustId.setToolTipText("Identifier par empreinte audio (plus précis, plus lent)");

        lblMask = new JLabel();
        lblMask.setForeground(new Color(0x78909C));
        updateMaskLabel();

        btnUndo = iconBtn("↩", "Annuler (Ctrl+Z)");
        btnRedo = iconBtn("↪", "Rétablir (Ctrl+Y)");
        btnUndo.addActionListener(e -> performUndo());
        btnRedo.addActionListener(e -> performRedo());
        btnUndo.setEnabled(false);
        btnRedo.setEnabled(false);

        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        rightPanel.setBackground(HEADER_BG);
        rightPanel.add(chkAcoustId);
        rightPanel.add(lblMask);
        rightPanel.add(vSep());
        rightPanel.add(btnUndo);
        rightPanel.add(btnRedo);

        bar.add(brandPanel,  BorderLayout.WEST);
        bar.add(actionsPanel, BorderLayout.CENTER);
        bar.add(rightPanel,  BorderLayout.EAST);

        // Ligne de séparation basse
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(HEADER_BG);
        wrapper.add(bar, BorderLayout.CENTER);
        JPanel sep = new JPanel();
        sep.setBackground(ACCENT.darker().darker());
        sep.setPreferredSize(new Dimension(0, 2));
        wrapper.add(sep, BorderLayout.SOUTH);
        return wrapper;
    }

    private JButton accentBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.putClientProperty("FlatLaf.style",
            "background: #4DB6AC; foreground: #1E1F22; font: bold 12 $defaultFont");
        return b;
    }

    private JButton headerBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.putClientProperty("FlatLaf.style", "foreground: #CFD8DC");
        return b;
    }

    private JButton iconBtn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(34, 28));
        return b;
    }

    private JButton btn(String text, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.setFocusPainted(false);
        return b;
    }

    private JSeparator vSep() {
        JSeparator s = new JSeparator(JSeparator.VERTICAL);
        s.setPreferredSize(new Dimension(1, 22));
        s.setForeground(new Color(0x3A3B3E));
        return s;
    }

    private Dimension dim(int w) { return new Dimension(w, 0); }

    // ── Bande de statistiques live ────────────────────────────────────────────

    private JPanel buildStatsStrip() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        p.setBackground(new Color(0x252527));
        p.setBorder(new MatteBorder(0, 0, 1, 0, new Color(0x3A3B3E)));

        lblStatTotal   = statChip("Total",        "0",  new Color(0x78909C));
        lblStatTagged  = statChip("Tagués",        "0",  new Color(0x4CAF50));
        lblStatSkipped = statChip("Non identifiés","0",  new Color(0xFFA726));
        lblStatError   = statChip("Erreurs",       "0",  new Color(0xEF5350));
        lblStatPending = statChip("En attente",    "0",  new Color(0x90A4AE));

        p.add(new JLabel("  "));
        p.add(lblStatTotal);
        p.add(sep3());
        p.add(lblStatTagged);
        p.add(lblStatSkipped);
        p.add(lblStatError);
        p.add(lblStatPending);
        return p;
    }

    private JLabel statChip(String label, String val, Color color) {
        JLabel l = new JLabel(label + "  " + val);
        l.setForeground(color);
        l.putClientProperty("FlatLaf.style", "font: bold 11 $defaultFont");
        l.setBorder(new CompoundBorder(
            new LineBorder(color.darker(), 1, true),
            new EmptyBorder(2, 7, 2, 7)));
        return l;
    }

    private JLabel sep3() {
        JLabel l = new JLabel("  ");
        return l;
    }

    private void refreshStats() {
        int total = 0, tagged = 0, skipped = 0, error = 0, pending = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            total++;
            switch (tableModel.get(i).status) {
                case TAGGED     -> tagged++;
                case SKIPPED    -> skipped++;
                case ERROR      -> error++;
                default         -> pending++;
            }
        }
        lblStatTotal  .setText("Total  "         + total);
        lblStatTagged .setText("Tagués  "        + tagged);
        lblStatSkipped.setText("Non identifiés  " + skipped);
        lblStatError  .setText("Erreurs  "       + error);
        lblStatPending.setText("En attente  "    + pending);
    }

    // ── Split pane table / détail ─────────────────────────────────────────────

    private JSplitPane buildMainSplit() {
        // ── Table (gauche) ────────────────────────────────────────────────────
        table = new JTable(tableModel);
        configureTable();
        installContextMenu();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(null);

        // ── Panneau détail (droite) ───────────────────────────────────────────
        JPanel detail = buildDetailPanel();
        detail.setPreferredSize(new Dimension(360, 0));
        detail.setMinimumSize(new Dimension(280, 0));

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, detail);
        sp.setResizeWeight(0.68);
        sp.setDividerSize(4);
        sp.setBorder(null);
        return sp;
    }

    // ── Configuration de la table ─────────────────────────────────────────────

    private void configureTable() {
        table.setRowHeight(26);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        rowSorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(rowSorter);
        table.getTableHeader().setReorderingAllowed(false);

        TableColumnModel cm = table.getColumnModel();
        colWidth(cm, 0, 30,  28,  30);   // ☑
        colWidth(cm, 1, 200, 110, 400);  // Fichier
        colWidth(cm, 2, 150, 70,  280);  // Artiste
        colWidth(cm, 3, 150, 70,  280);  // Artiste album
        colWidth(cm, 4, 180, 80,  340);  // Titre
        colWidth(cm, 5, 150, 60,  280);  // Album
        colWidth(cm, 6, 52,  36,  68);   // Année
        colWidth(cm, 7, 120, 50,  220);  // Genre
        colWidth(cm, 8, 44,  28,  60);   // Piste
        colWidth(cm, 9, 130, 80,  220);  // Statut

        // Renderer coloré
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    int mr = t.convertRowIndexToModel(row);
                    c.setBackground(rowBg(tableModel.get(mr).status, row));
                }
                return c;
            }
        };
        renderer.setBorder(new EmptyBorder(0, 7, 0, 7));

        for (int i = 1; i < cm.getColumnCount(); i++)
            cm.getColumn(i).setCellRenderer(renderer);
    }

    private Color rowBg(FileEntry.Status s, int row) {
        boolean alt = (row % 2 == 1);
        Color base  = UIManager.getColor(alt ? "Table.alternateRowColor" : "Table.background");
        if (base == null) base = new Color(43, 45, 48);
        return switch (s) {
            case TAGGED     -> blend(base, COL_TAGGED);
            case ERROR      -> blend(base, COL_ERROR);
            case PROCESSING -> blend(base, COL_PROCESSING);
            case SKIPPED    -> blend(base, COL_SKIPPED);
            default         -> base;
        };
    }

    private Color blend(Color bg, Color over) {
        float a = over.getAlpha() / 255f;
        return new Color(
            clamp((int)(bg.getRed()   * (1-a) + over.getRed()   * a)),
            clamp((int)(bg.getGreen() * (1-a) + over.getGreen() * a)),
            clamp((int)(bg.getBlue()  * (1-a) + over.getBlue()  * a)));
    }

    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private void colWidth(TableColumnModel cm, int i, int p, int mn, int mx) {
        TableColumn c = cm.getColumn(i);
        c.setPreferredWidth(p); c.setMinWidth(mn); c.setMaxWidth(mx);
    }

    // ── Menu contextuel ───────────────────────────────────────────────────────

    private void installContextMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem miTag    = new JMenuItem("⚡  Tagger ce fichier");
        JMenuItem miRename = new JMenuItem("✏  Renommer ce fichier");
        JMenuItem miReveal = new JMenuItem("📁  Ouvrir le dossier parent");
        JMenuItem miRemove = new JMenuItem("✗  Retirer de la liste");

        miTag.addActionListener(e -> startTagging(true));
        miRename.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) return;
            FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
            if (entry.status == FileEntry.Status.TAGGED) {
                try {
                    Path np = new FileRenamer().rename(
                            entry.file.toPath(), entry.activeTags(), currentMask);
                    if (np != null) setStatus("Renommé → " + np.getFileName());
                } catch (Exception ex) { showError(ex.getMessage()); }
            } else { setStatus("Ce fichier n'a pas encore été tagué."); }
        });
        miReveal.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                try { Desktop.getDesktop().open(
                        tableModel.get(table.convertRowIndexToModel(row)).file.getParentFile()); }
                catch (Exception ignored) {}
            }
        });
        miRemove.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            // Supprimer de bas en haut pour garder les indices valides
            for (int i = rows.length - 1; i >= 0; i--) {
                int mr = table.convertRowIndexToModel(rows[i]);
                tableModel.remove(mr);
            }
        });

        JMenuItem miAcoustId = new JMenuItem("🎵  Soumettre fingerprint AcoustID");
        miAcoustId.addActionListener(e -> submitAcoustId());

        JMenuItem miMatch = new JMenuItem("🎯  Correspondance manuelle…");
        miMatch.addActionListener(e -> openMatchDialog());

        JMenuItem miCover = new JMenuItem("🖼  Gérer la pochette…");
        miCover.addActionListener(e -> openCoverDialog());

        JMenuItem miMbEdit = new JMenuItem("✏  Modifier sur MusicBrainz");
        miMbEdit.addActionListener(e -> openMbEditPage());

        JMenuItem miMbContrib = new JMenuItem("🤝  Contribuer à MusicBrainz…");
        miMbContrib.addActionListener(e -> openMbContribute());

        menu.add(miTag); menu.add(miRename); menu.addSeparator();
        menu.add(miMatch); menu.add(miCover); menu.addSeparator();
        menu.add(miMbEdit); menu.add(miMbContrib); menu.addSeparator();
        menu.add(miAcoustId); menu.addSeparator();
        menu.add(miReveal); menu.add(miRemove);

        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybeShow(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row))
                    table.setRowSelectionInterval(row, row);
                menu.show(table, e.getX(), e.getY());
            }
        });
    }

    // ── Panneau de détail ─────────────────────────────────────────────────────

    private JPanel buildDetailPanel() {
        // ── Pochette (cliquable, centrée en haut) ────────────────────────────
        lblCoverImg = new JLabel("—", SwingConstants.CENTER);
        lblCoverImg.setPreferredSize(new Dimension(140, 140));
        lblCoverImg.setMinimumSize (new Dimension(140, 140));
        lblCoverImg.setMaximumSize (new Dimension(140, 140));
        lblCoverImg.setBorder(new LineBorder(ACCENT.darker(), 1));
        lblCoverImg.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblCoverImg.setToolTipText("Double-clic pour gérer la pochette");
        lblCoverImg.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 11 $defaultFont");
        lblCoverImg.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openCoverDialog();
            }
        });

        // Titre de section "Pochette"
        JLabel coverTitle = new JLabel("POCHETTE");
        coverTitle.putClientProperty("FlatLaf.style",
            "foreground: #546E7A; font: bold 10 $defaultFont");

        JPanel coverSection = new JPanel();
        coverSection.setLayout(new BoxLayout(coverSection, BoxLayout.Y_AXIS));
        coverSection.setBorder(new EmptyBorder(14, 14, 8, 14));
        coverTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        lblCoverImg.setAlignmentX(Component.CENTER_ALIGNMENT);
        coverSection.add(coverTitle);
        coverSection.add(Box.createVerticalStrut(4));
        coverSection.add(lblCoverImg);

        // ── Chemin du fichier ────────────────────────────────────────────────
        lblFilePath = new JLabel(" ");
        lblFilePath.putClientProperty("FlatLaf.style", "foreground: #546E7A; font: 11 $defaultFont");
        lblFilePath.setBorder(new EmptyBorder(4, 14, 0, 14));

        // ── Séparateur accentué ──────────────────────────────────────────────
        JPanel accentLine = new JPanel();
        accentLine.setBackground(ACCENT);
        accentLine.setPreferredSize(new Dimension(0, 2));
        accentLine.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));

        // ── Titre section "MÉTADONNÉES" ───────────────────────────────────────
        JLabel metaTitle = new JLabel("  MÉTADONNÉES");
        metaTitle.putClientProperty("FlatLaf.style",
            "foreground: #546E7A; font: bold 10 $defaultFont; background: #252527");
        metaTitle.setOpaque(true);
        metaTitle.setBorder(new EmptyBorder(5, 0, 5, 0));

        // ── DetailPanel (champs de tags) ─────────────────────────────────────
        detailPanel = new DetailPanel();
        detailPanel.setOnApply(this::applyDetail);

        // ── Assemblage ───────────────────────────────────────────────────────
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.add(coverSection);
        content.add(lblFilePath);
        content.add(Box.createVerticalStrut(6));
        content.add(accentLine);
        content.add(metaTitle);
        content.add(detailPanel);

        JScrollPane scroll = new JScrollPane(content,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(12);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new MatteBorder(0, 1, 0, 0, new Color(0x3A3B3E)));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void refreshDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) { clearDetail(); return; }

        if (rows.length == 1) {
            // Mode fichier unique
            FileEntry e  = tableModel.get(table.convertRowIndexToModel(rows[0]));
            TagInfo   ti = e.activeTags();
            lblFilePath.setText("  " + (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath());
            detailPanel.populate(ti);
            if (e.result != null) detailPanel.highlightChanges(e.current);
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            loadCoverThumb(f);
        } else {
            // Mode multi-sélection — édition en lot
            List<TagInfo> tags = new ArrayList<>();
            for (int r : rows) tags.add(tableModel.get(table.convertRowIndexToModel(r)).activeTags());
            lblFilePath.setText("  " + rows.length + " fichiers sélectionnés — les champs vides ne seront pas modifiés");
            detailPanel.populateMulti(tags);
            lblCoverImg.setIcon(null); lblCoverImg.setText(rows.length + "");
        }
    }

    private void clearDetail() {
        lblFilePath.setText(" ");
        detailPanel.clear();
        lblCoverImg.setIcon(null); lblCoverImg.setText("—");
    }

    private void loadCoverThumb(File f) {
        lblCoverImg.setIcon(null); lblCoverImg.setText("…");
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() {
                try {
                    AudioFile af = AudioFileIO.read(f);
                    Tag tag = af.getTag();
                    if (tag == null) return null;
                    Artwork art = tag.getFirstArtwork();
                    if (art == null) return null;
                    byte[] data = art.getBinaryData();
                    try (InputStream in = new java.io.ByteArrayInputStream(data)) {
                        BufferedImage img = ImageIO.read(in);
                        if (img == null) return null;
                        Image scaled = img.getScaledInstance(86, 86, Image.SCALE_SMOOTH);
                        return new ImageIcon(scaled);
                    }
                } catch (Exception ignore) { return null; }
            }
            @Override protected void done() {
                try {
                    ImageIcon icon = get();
                    if (icon != null) { lblCoverImg.setIcon(icon); lblCoverImg.setText(""); }
                    else              { lblCoverImg.setIcon(null);  lblCoverImg.setText("—"); }
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    private void applyDetail() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) return;

        if (rows.length == 1) {
            // ── Fichier unique ─────────────────────────────────────────────
            int       mr   = table.convertRowIndexToModel(rows[0]);
            FileEntry e    = tableModel.get(mr);
            TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
            TagInfo   ti   = e.activeTags();
            detailPanel.collect(ti);
            refreshTableRow(mr, ti);
            undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), "Modifier " + e.filename());
            writeTagsSafe(e, ti);
            setStatus("Tags sauvegardés — " + e.filename());
        } else {
            // ── Édition en lot ─────────────────────────────────────────────
            // collect() en mode multi : n'écrase que les champs non vides
            int saved = 0;
            for (int row : rows) {
                int       mr   = table.convertRowIndexToModel(row);
                FileEntry e    = tableModel.get(mr);
                TagInfo   snap = com.opentagger.UndoManager.snapshot(e.activeTags());
                TagInfo   ti   = e.activeTags();
                detailPanel.collect(ti);       // multiMode → écrase seulement les champs non vides
                refreshTableRow(mr, ti);
                undoManager.push(e, snap, com.opentagger.UndoManager.snapshot(ti), "Lot " + e.filename());
                writeTagsSafe(e, ti);
                saved++;
            }
            setStatus("Tags sauvegardés — " + saved + " fichier(s).");
        }
    }

    // ── Correspondance manuelle ───────────────────────────────────────────────

    private void openMatchDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new MatchDialog(this, entry, tableModel, this::refreshDetail).setVisible(true);
    }

    // ── Gestion de la pochette ────────────────────────────────────────────────

    private void openCoverDialog() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new CoverArtDialog(this, entry, this::refreshDetail).setVisible(true);
    }

    // ── MusicBrainz : modifier + contribuer ──────────────────────────────────

    private void openMbEditPage() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        TagInfo ti = tableModel.get(table.convertRowIndexToModel(row)).activeTags();
        String mbid = ti.recordingMbid;
        try {
            String url = mbid.isBlank()
                ? "https://musicbrainz.org/search?query=" +
                    java.net.URLEncoder.encode(ti.artist + " " + ti.title, java.nio.charset.StandardCharsets.UTF_8) +
                    "&type=recording"
                : "https://musicbrainz.org/recording/" + mbid;
            Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception ex) { showError("Impossible d'ouvrir le navigateur : " + ex.getMessage()); }
    }

    private void openMbContribute() {
        int row = table.getSelectedRow();
        if (row < 0) { setStatus("Sélectionnez un fichier."); return; }
        FileEntry entry = tableModel.get(table.convertRowIndexToModel(row));
        new MbContributeDialog(this, entry).setVisible(true);
    }

    private void refreshTableRow(int mr, TagInfo ti) {
        tableModel.setValueAt(ti.artist,      mr, FileTableModel.COL_ARTIST);
        tableModel.setValueAt(ti.albumArtist, mr, FileTableModel.COL_ALBUM_ARTIST);
        tableModel.setValueAt(ti.title,       mr, FileTableModel.COL_TITLE);
        tableModel.setValueAt(ti.album,       mr, FileTableModel.COL_ALBUM);
        tableModel.setValueAt(ti.year,        mr, FileTableModel.COL_YEAR);
        tableModel.setValueAt(ti.genre,       mr, FileTableModel.COL_GENRE);
        tableModel.setValueAt(ti.track,       mr, FileTableModel.COL_TRACK);
    }

    private void writeTagsSafe(FileEntry e, TagInfo ti) {
        try {
            File target = e.currentPath != null ? e.currentPath.toFile() : e.file;
            new com.opentagger.TagWriter().write(target, ti);
        } catch (Exception ex) {
            showError("Erreur écriture " + e.filename() + " : " + ex.getMessage());
        }
    }

    private void performUndo() {
        FileEntry e = undoManager.undo();
        if (e != null) { tableModel.update(e); refreshDetail(); setStatus("Annulé : " + undoManager.undoDescription()); }
    }

    private void performRedo() {
        FileEntry e = undoManager.redo();
        if (e != null) { tableModel.update(e); refreshDetail(); setStatus("Rétabli : " + undoManager.redoDescription()); }
    }

    private void updateUndoButtons() {
        if (btnUndo == null) return;
        btnUndo.setEnabled(undoManager.canUndo());
        btnRedo.setEnabled(undoManager.canRedo());
        String ud = undoManager.undoDescription();
        String rd = undoManager.redoDescription();
        btnUndo.setToolTipText(undoManager.canUndo() ? "Annuler : " + ud + " (Ctrl+Z)" : "Rien à annuler");
        btnRedo.setToolTipText(undoManager.canRedo() ? "Rétablir : " + rd + " (Ctrl+Y)" : "Rien à rétablir");
    }

    // ── Barre de statut ───────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        lblStatus = new JLabel(" Prêt");
        progress  = new JProgressBar(0, 100);
        progress.setPreferredSize(new Dimension(220, 14));
        progress.setStringPainted(true);
        progress.setVisible(false);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(new CompoundBorder(
            new MatteBorder(1, 0, 0, 0, sep()),
            new EmptyBorder(5, 12, 5, 12)));
        bar.add(lblStatus, BorderLayout.WEST);
        bar.add(progress,  BorderLayout.EAST);
        return bar;
    }

    // ── Drag & Drop d'un dossier ──────────────────────────────────────────────

    private void installDragDrop() {
        setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport ts) {
                return ts.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport ts) {
                try {
                    List<File> dropped = (List<File>) ts.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    // Accepte plusieurs dossiers (ou fichiers audio) droppés en même temps
                    for (File f : dropped) {
                        if (f.isDirectory()) loadDirectory(f, true);
                        else if (f.isFile())  loadDirectory(f.getParentFile(), true);
                    }
                    return true;
                } catch (Exception e) { return false; }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Actions
    // ═══════════════════════════════════════════════════════════════════════════

    private void openFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle("Choisir un ou plusieurs dossiers de musique");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] dirs = fc.getSelectedFiles();
        if (dirs == null || dirs.length == 0) dirs = new File[]{fc.getSelectedFile()};
        // Premier dossier : remplace la liste ; suivants : s'ajoutent
        boolean replace = true;
        for (File d : dirs) {
            loadDirectory(d, replace);
            replace = false;
        }
    }

    /**
     * Charge un dossier (récursivement) dans la table.
     * @param append  false = remplace la liste, true = ajoute à la liste existante
     */
    private void loadDirectory(File dir, boolean append) {
        if (dir == null || !dir.isDirectory()) return;
        setStatus("Scan de " + dir.getName() + "...");
        final Path root = dir.toPath();
        new SwingWorker<List<FileEntry>, Void>() {
            @Override protected List<FileEntry> doInBackground() throws Exception {
                List<File> files = new AudioScanner().scan(dir);
                List<FileEntry> list = new ArrayList<>(files.size());
                for (File f : files) {
                    FileEntry entry = new FileEntry(f, readTags(f));
                    entry.scanRoot = root;
                    list.add(entry);
                }
                return list;
            }
            @Override protected void done() {
                try {
                    List<FileEntry> list = get();
                    if (append) tableModel.addAll(list);
                    else        tableModel.replaceAll(list);
                    setStatus(tableModel.getRowCount() + " fichier(s) au total — dernier ajout : " + dir.getName());
                    refreshStats();
                } catch (Exception ex) { showError(ex.getMessage()); }
            }
        }.execute();
    }

    private void startTagging(boolean selOnly) {
        List<FileEntry> toTag = new ArrayList<>();
        if (selOnly) {
            for (int r : table.getSelectedRows())
                toTag.add(tableModel.get(table.convertRowIndexToModel(r)));
        } else {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                if (e.selected) toTag.add(e);
            }
        }
        if (toTag.isEmpty()) { setStatus("Aucun fichier sélectionné."); return; }

        btnTagAll.setEnabled(false); btnTagSel.setEnabled(false);
        btnCancel.setEnabled(true);
        progress.setValue(0); progress.setVisible(true);

        worker = new TaggingWorker(toTag, chkAcoustId.isSelected(), -1,
            msg -> setStatus(msg),
            entry -> {
                tableModel.update(entry);
                table.repaint();
                int sel = table.getSelectedRow();
                if (sel >= 0 && tableModel.get(table.convertRowIndexToModel(sel)) == entry)
                    refreshDetail();
            }
        );
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName()))
                progress.setValue((Integer) evt.getNewValue());
            if (SwingWorker.StateValue.DONE.equals(evt.getNewValue()))
                onTaggingDone(toTag);
        });
        worker.execute();
    }

    private void cancelTagging() {
        if (worker != null) worker.cancel(true);
        setStatus("Arrêté."); resetBtns();
    }

    private void onTaggingDone(List<FileEntry> done) {
        long ok   = done.stream().filter(e -> e.status == FileEntry.Status.TAGGED).count();
        long skip = done.stream().filter(e -> e.status == FileEntry.Status.SKIPPED).count();
        long err  = done.stream().filter(e -> e.status == FileEntry.Status.ERROR).count();
        setStatus(String.format("Terminé — ✓ %d tagué(s)  ⚠ %d ignoré(s)  ✗ %d erreur(s)", ok, skip, err));
        resetBtns();
        refreshStats();
    }

    private void resetBtns() {
        btnTagAll.setEnabled(true); btnTagSel.setEnabled(true);
        btnCancel.setEnabled(false); progress.setVisible(false);
    }

    private void renameTagged() {
        // ── 1. Calculer l'aperçu (aucun fichier déplacé ici) ─────────────────
        long tagged = 0;
        for (int i = 0; i < tableModel.getRowCount(); i++)
            if (tableModel.get(i).status == FileEntry.Status.TAGGED) tagged++;
        if (tagged == 0) { setStatus("Aucun fichier tagué à renommer."); return; }

        List<RenamePreviewDialog.PreviewRow> preview = RenamePreviewDialog.compute(tableModel, currentMask);
        if (preview.isEmpty()) { setStatus("Aucun fichier tagué à renommer."); return; }

        // ── 2. Afficher l'aperçu et attendre confirmation ─────────────────────
        RenamePreviewDialog dlg = new RenamePreviewDialog(this, preview, this::doRenameTagged);
        dlg.setVisible(true); // bloquant (modal)
    }

    private void doRenameTagged() {
        FileRenamer renamer    = new FileRenamer();
        int         renamed    = 0;
        int         skipped    = 0;
        int         errors     = 0;

        // Collecter les dossiers sources pour le nettoyage ultérieur
        List<Path> sourceDirs = new ArrayList<>();

        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status != FileEntry.Status.TAGGED) continue;

            Path root = e.scanRoot != null ? e.scanRoot : e.currentPath.getParent();
            try {
                Path oldPath = e.currentPath;
                Path newPath = renamer.rename(e.currentPath, e.activeTags(), currentMask, root);
                if (newPath != null) {
                    sourceDirs.add(oldPath.getParent());
                    e.currentPath = newPath;
                    renamed++;
                    tableModel.update(e);
                } else {
                    skipped++; // déjà au bon endroit
                }
            } catch (Exception ex) {
                errors++;
                e.message = "Renommage : " + ex.getMessage();
                tableModel.update(e);
            }
        }

        // Supprimer les dossiers vides laissés par les déplacements
        Set<Path> roots = new LinkedHashSet<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.scanRoot != null) roots.add(e.scanRoot);
        }
        int emptyCleaned = 0;
        for (Path src : sourceDirs) {
            for (Path r : roots) {
                try {
                    long before = countDirs(r);
                    FileRenamer.deleteEmptyAncestors(src, r);
                    emptyCleaned += (int)(before - countDirs(r));
                } catch (Exception ignore) {}
            }
        }

        String msg = String.format("Renommage — ✓ %d  déjà OK %d  ✗ %d erreur(s)", renamed, skipped, errors);
        if (emptyCleaned > 0) msg += "  |  🗑 " + emptyCleaned + " dossier(s) vide(s) supprimé(s)";
        setStatus(msg);
    }

    private long countDirs(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isDirectory).count();
        } catch (Exception e) { return 0; }
    }

    private void chooseMask() {
        FileRenamer renamer = new FileRenamer();
        int n = renamer.maskCount();
        String[] labels = new String[n];
        for (int i = 0; i < n; i++)
            labels[i] = String.format("[%2d] %s", i, renamer.maskLabel(i));
        String chosen = (String) JOptionPane.showInputDialog(this,
            "Masque de renommage actif :", "Masques disponibles",
            JOptionPane.PLAIN_MESSAGE, null, labels, labels[Math.min(currentMask, n-1)]);
        if (chosen == null) return;
        for (int i = 0; i < labels.length; i++)
            if (labels[i].equals(chosen)) { currentMask = i; break; }
        updateMaskLabel();
        setStatus("Masque actif : " + renamer.maskLabel(currentMask));
    }

    private void updateMaskLabel() {
        if (lblMask == null) return;
        String name = new FileRenamer().maskLabel(currentMask);
        lblMask.setText("  [#" + currentMask + " " + name + "]  ");
        lblMask.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");
    }

    private void setAllSelected(boolean val) {
        for (int i = 0; i < tableModel.getRowCount(); i++)
            tableModel.setValueAt(val, i, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utilitaires
    // ═══════════════════════════════════════════════════════════════════════════

    /** Lit les 90+ champs d'un fichier audio — même couverture que TagWriter. */
    private TagInfo readTags(File f) {
        TagInfo ti = new TagInfo();
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            if (tag == null) return ti;

            // ── Standard ──────────────────────────────────────────────────
            ti.title          = g(tag, FieldKey.TITLE);
            ti.artist         = g(tag, FieldKey.ARTIST);
            ti.albumArtist    = g(tag, FieldKey.ALBUM_ARTIST);
            ti.album          = g(tag, FieldKey.ALBUM);
            ti.year           = g(tag, FieldKey.YEAR);
            ti.track          = g(tag, FieldKey.TRACK);
            ti.trackTotal     = g(tag, FieldKey.TRACK_TOTAL);
            ti.genre          = g(tag, FieldKey.GENRE);
            ti.discNo         = g(tag, FieldKey.DISC_NO);
            ti.discTotal      = g(tag, FieldKey.DISC_TOTAL);
            ti.comment        = g(tag, FieldKey.COMMENT);

            // ── Tri ───────────────────────────────────────────────────────
            ti.titleSort      = g(tag, FieldKey.TITLE_SORT);
            ti.artistSort     = g(tag, FieldKey.ARTIST_SORT);
            ti.albumSort      = g(tag, FieldKey.ALBUM_SORT);
            ti.albumArtistSort= g(tag, FieldKey.ALBUM_ARTIST_SORT);
            ti.composerSort   = g(tag, FieldKey.COMPOSER_SORT);
            ti.conductorSort  = g(tag, FieldKey.CONDUCTOR_SORT);
            ti.orchestraSort  = g(tag, FieldKey.ORCHESTRA_SORT);
            ti.ensembleSort   = g(tag, FieldKey.ENSEMBLE_SORT);
            ti.choirSort      = g(tag, FieldKey.CHOIR_SORT);
            ti.lyricistSort   = g(tag, FieldKey.LYRICIST_SORT);
            ti.producerSort   = g(tag, FieldKey.PRODUCER_SORT);
            ti.arrangerSort   = g(tag, FieldKey.ARRANGER_SORT);

            // ── Contributeurs ─────────────────────────────────────────────
            ti.composer       = g(tag, FieldKey.COMPOSER);
            ti.conductor      = g(tag, FieldKey.CONDUCTOR);
            ti.orchestra      = g(tag, FieldKey.ORCHESTRA);
            ti.ensemble       = g(tag, FieldKey.ENSEMBLE);
            ti.choir          = g(tag, FieldKey.CHOIR);
            ti.lyricist       = g(tag, FieldKey.LYRICIST);
            ti.producer       = g(tag, FieldKey.PRODUCER);
            ti.arranger       = g(tag, FieldKey.ARRANGER);
            ti.engineer       = g(tag, FieldKey.ENGINEER);
            ti.mixer          = g(tag, FieldKey.MIXER);
            ti.djMixer        = g(tag, FieldKey.DJMIXER);

            // ── Classique ─────────────────────────────────────────────────
            ti.work           = g(tag, FieldKey.WORK);
            ti.workMbid       = g(tag, FieldKey.MUSICBRAINZ_WORK_ID);
            ti.movement       = g(tag, FieldKey.MOVEMENT);
            ti.movementNo     = g(tag, FieldKey.MOVEMENT_NO);
            ti.movementTotal  = g(tag, FieldKey.MOVEMENT_TOTAL);
            ti.titleMovement  = g(tag, FieldKey.TITLE_MOVEMENT);
            ti.part           = g(tag, FieldKey.PART);
            ti.partType       = g(tag, FieldKey.PART_TYPE);
            ti.partNo         = g(tag, FieldKey.PART_NUMBER);
            ti.period         = g(tag, FieldKey.PERIOD);
            ti.opus           = g(tag, FieldKey.OPUS);
            ti.classicalCatalog   = g(tag, FieldKey.CLASSICAL_CATALOG);
            ti.classicalNickname  = g(tag, FieldKey.CLASSICAL_NICKNAME);
            ti.section        = g(tag, FieldKey.SECTION);
            ti.overallWork    = g(tag, FieldKey.OVERALL_WORK);
            ti.grouping       = g(tag, FieldKey.GROUPING);

            // ── Flags ─────────────────────────────────────────────────────
            String isCl = g(tag, FieldKey.IS_CLASSICAL);
            if ("1".equals(isCl) || "true".equalsIgnoreCase(isCl)) ti.isClassical = "1";
            String isCo = g(tag, FieldKey.IS_COMPILATION);
            if ("1".equals(isCo) || "true".equalsIgnoreCase(isCo)) ti.isCompilation = "1";

            // ── Audio ─────────────────────────────────────────────────────
            ti.bpm            = g(tag, FieldKey.BPM);
            ti.initialKey     = g(tag, FieldKey.KEY);
            ti.language       = g(tag, FieldKey.LANGUAGE);

            // ── Paroles ───────────────────────────────────────────────────
            ti.lyrics         = g(tag, FieldKey.LYRICS);
            ti.lyricsUrl      = g(tag, FieldKey.URL_LYRICS_SITE);

            // ── Rating / Tags ─────────────────────────────────────────────
            ti.rating         = g(tag, FieldKey.RATING);
            ti.tags           = g(tag, FieldKey.TAGS);

            // ── Mood ──────────────────────────────────────────────────────
            ti.mood               = g(tag, FieldKey.MOOD);
            ti.moodAggressive     = g(tag, FieldKey.MOOD_AGGRESSIVE);
            ti.moodAcoustic       = g(tag, FieldKey.MOOD_ACOUSTIC);
            ti.moodElectronic     = g(tag, FieldKey.MOOD_ELECTRONIC);
            ti.moodHappy          = g(tag, FieldKey.MOOD_HAPPY);
            ti.moodParty          = g(tag, FieldKey.MOOD_PARTY);
            ti.moodRelaxed        = g(tag, FieldKey.MOOD_RELAXED);
            ti.moodSad            = g(tag, FieldKey.MOOD_SAD);
            ti.moodValence        = g(tag, FieldKey.MOOD_VALENCE);
            ti.moodArousal        = g(tag, FieldKey.MOOD_AROUSAL);
            ti.moodDanceability   = g(tag, FieldKey.MOOD_DANCEABILITY);
            ti.moodInstrumental   = g(tag, FieldKey.MOOD_INSTRUMENTAL);

            // ── URLs ──────────────────────────────────────────────────────
            ti.artistOfficialUrl  = g(tag, FieldKey.URL_OFFICIAL_ARTIST_SITE);
            ti.artistWikipediaUrl = g(tag, FieldKey.URL_WIKIPEDIA_ARTIST_SITE);
            ti.artistDiscogsUrl   = g(tag, FieldKey.URL_DISCOGS_ARTIST_SITE);
            ti.releaseOfficialUrl = g(tag, FieldKey.URL_OFFICIAL_RELEASE_SITE);
            ti.releaseWikipediaUrl= g(tag, FieldKey.URL_WIKIPEDIA_RELEASE_SITE);
            ti.releaseDiscogsUrl  = g(tag, FieldKey.URL_DISCOGS_RELEASE_SITE);

            // ── IDs ───────────────────────────────────────────────────────
            ti.isrc               = g(tag, FieldKey.ISRC);
            ti.amazonId           = g(tag, FieldKey.AMAZON_ID);
            ti.roonAlbumTag       = g(tag, FieldKey.ROONALBUMTAG);
            ti.roonTrackTag       = g(tag, FieldKey.ROONTRACKTAG);
            ti.acoustidId         = g(tag, FieldKey.ACOUSTID_ID);
            ti.acoustidFingerprint= g(tag, FieldKey.ACOUSTID_FINGERPRINT);

            // ── IDs MusicBrainz ───────────────────────────────────────────
            ti.artistMbid         = g(tag, FieldKey.MUSICBRAINZ_ARTISTID);
            ti.releaseMbid        = g(tag, FieldKey.MUSICBRAINZ_RELEASEID);
            ti.recordingMbid      = g(tag, FieldKey.MUSICBRAINZ_TRACK_ID);
            ti.releaseGroupMbid   = g(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);

        } catch (Exception ignored) {}
        return ti;
    }

    /** getFirst avec protection NPE et chaîne vide par défaut. */
    private String g(Tag tag, FieldKey key) {
        try { String v = tag.getFirst(key); return v != null ? v : ""; }
        catch (Exception e) { return ""; }
    }

    // ── Barre de filtrage rapide ──────────────────────────────────────────────

    private JPanel buildFilterBar() {
        tfFilter      = new JTextField(20);
        cbFilterField = new JComboBox<>(new String[]{
            "Tous les champs", "Artiste", "Artiste album", "Titre",
            "Album", "Année", "Genre", "Piste"});
        cbFilterStatus = new JComboBox<>(new String[]{
            "Tous les statuts", "⏳ En attente", "✓ Tagués", "⚠ Non identifiés", "✗ Erreurs"});

        tfFilter.putClientProperty("JTextField.placeholderText", "Rechercher…");
        JButton btnClear = new JButton("✕");
        btnClear.setFont(btnClear.getFont().deriveFont(10f));
        btnClear.setToolTipText("Effacer les filtres");

        tfFilter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(); }
        });
        cbFilterField .addActionListener(e -> applyFilter());
        cbFilterStatus.addActionListener(e -> applyFilter());
        btnClear.addActionListener(e -> {
            tfFilter.setText("");
            cbFilterStatus.setSelectedIndex(0);
            applyFilter();
        });

        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setBorder(new MatteBorder(0, 0, 1, 0, sep()));
        p.add(new JLabel("Filtre :"));
        p.add(tfFilter);
        p.add(cbFilterField);
        p.add(new JSeparator(JSeparator.VERTICAL));
        p.add(new JLabel("Statut :"));
        p.add(cbFilterStatus);
        p.add(btnClear);
        return p;
    }

    private void applyFilter() {
        String text   = tfFilter.getText().trim();
        int statusSel = cbFilterStatus.getSelectedIndex(); // 0=tous,1=pending,2=tagged,3=skipped,4=error

        RowFilter<Object, Object> textFilter   = null;
        RowFilter<Object, Object> statusFilter = null;

        // ── Filtre texte ──────────────────────────────────────────────────
        if (!text.isBlank()) {
            String escaped = Pattern.quote(text);
            int sel = cbFilterField.getSelectedIndex();
            if (sel == 0) {
                List<RowFilter<Object, Object>> cols = new ArrayList<>();
                for (int c = 2; c <= 8; c++) cols.add(RowFilter.regexFilter("(?i)" + escaped, c));
                textFilter = RowFilter.orFilter(cols);
            } else {
                textFilter = RowFilter.regexFilter("(?i)" + escaped, sel + 1);
            }
        }

        // ── Filtre statut (colonne 9 = COL_STATUS) ────────────────────────
        if (statusSel > 0) {
            String pat = switch (statusSel) {
                case 1 -> "^—$";            // PENDING
                case 2 -> "^✓";             // TAGGED
                case 3 -> "^⚠";             // SKIPPED
                case 4 -> "^✗";             // ERROR
                default -> null;
            };
            if (pat != null) statusFilter = RowFilter.regexFilter(pat, 9);
        }

        // ── Combiner ─────────────────────────────────────────────────────
        if (textFilter == null && statusFilter == null) {
            rowSorter.setRowFilter(null);
        } else if (textFilter == null) {
            rowSorter.setRowFilter(statusFilter);
        } else if (statusFilter == null) {
            rowSorter.setRowFilter(textFilter);
        } else {
            rowSorter.setRowFilter(RowFilter.andFilter(List.of(textFilter, statusFilter)));
        }
    }

    // ── Sélection par statut ──────────────────────────────────────────────────

    private void selectByStatus(FileEntry.Status... statuses) {
        Set<FileEntry.Status> set = Set.of(statuses);
        table.clearSelection();
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            FileEntry e  = tableModel.get(modelRow);
            if (set.contains(e.status))
                table.addRowSelectionInterval(viewRow, viewRow);
        }
        int n = table.getSelectedRowCount();
        String label = statuses[0] == FileEntry.Status.SKIPPED ? "non identifié(s)"
                     : statuses[0] == FileEntry.Status.ERROR   ? "en erreur"
                     : "en attente";
        setStatus(n > 0 ? n + " fichier(s) " + label + " sélectionné(s)."
                        : "Aucun fichier " + label + " dans la liste.");
        // Aussi basculer le filtre visuel pour les voir clairement
        if (n > 0) {
            cbFilterStatus.setSelectedIndex(
                statuses[0] == FileEntry.Status.SKIPPED ? 3
              : statuses[0] == FileEntry.Status.ERROR   ? 4 : 1);
        }
    }

    // ── Export CSV ───────────────────────────────────────────��────────────────

    private void exportCsv() {
        if (tableModel.getRowCount() == 0) { setStatus("Aucun fichier à exporter."); return; }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("opentagger_export.csv"));
        fc.setDialogTitle("Exporter en CSV");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File out = fc.getSelectedFile();
        setStatus("Export CSV en cours…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try (PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
                    // En-tête BOM pour Excel
                    pw.print('﻿');
                    pw.println("Fichier,Artiste,Artiste Album,Titre,Album,Année,Genre,Piste,Disque,Compositeur,Chef,MBID,Statut");
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        FileEntry e = tableModel.get(i);
                        TagInfo  ti = e.activeTags();
                        pw.println(csv(e.filename()) + "," + csv(ti.artist) + "," + csv(ti.albumArtist)
                            + "," + csv(ti.title) + "," + csv(ti.album) + "," + csv(ti.year)
                            + "," + csv(ti.genre) + "," + csv(ti.track) + "," + csv(ti.discNo)
                            + "," + csv(ti.composer) + "," + csv(ti.conductor)
                            + "," + csv(ti.recordingMbid) + "," + csv(e.status.name()));
                    }
                }
                return null;
            }
            @Override protected void done() {
                try { get(); setStatus("CSV exporté → " + out.getName()); }
                catch (Exception ex) { showError("Export CSV : " + ex.getMessage()); }
            }
        }.execute();
    }

    private static String csv(String s) {
        if (s == null) return "";
        s = s.replace("\"", "\"\"");
        return (s.contains(",") || s.contains("\"") || s.contains("\n")) ? "\"" + s + "\"" : s;
    }

    // ── Détection de doublons ─────────────────────────────────────────────────

    private void detectDuplicates() {
        if (tableModel.getRowCount() == 0) { setStatus("Aucun fichier chargé."); return; }
        List<FileEntry> all = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) all.add(tableModel.get(i));
        List<DuplicateDetector.DuplicateGroup> groups = DuplicateDetector.detect(all);
        if (groups.isEmpty()) { setStatus("Aucun doublon détecté."); return; }
        int total = groups.stream().mapToInt(g -> g.files().size()).sum();
        setStatus(groups.size() + " groupe(s) de doublons, " + total + " fichier(s) concerné(s).");
        new DuplicatesDialog(this, groups, tableModel).setVisible(true);
    }

    private void deleteErrorFiles() {
        List<FileEntry> errors = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status == FileEntry.Status.ERROR) errors.add(e);
        }

        if (errors.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Aucun fichier illisible dans la liste.",
                "Fichiers illisibles", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Construire le message de confirmation
        StringBuilder sb = new StringBuilder("<html>Supprimer définitivement <b>");
        sb.append(errors.size()).append(" fichier(s) illisible(s)</b> du disque ?<br><br>");
        int shown = Math.min(errors.size(), 8);
        for (int i = 0; i < shown; i++) {
            FileEntry e = errors.get(i);
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            sb.append("&nbsp;• <font color='#cc4444'>").append(f.getName()).append("</font>");
            if (e.message != null && !e.message.isBlank())
                sb.append(" <i>(").append(e.message).append(")</i>");
            sb.append("<br>");
        }
        if (errors.size() > shown)
            sb.append("&nbsp;… et ").append(errors.size() - shown).append(" autre(s)<br>");
        sb.append("<br><i>Ces fichiers sont corrompus ou dans un format non supporté.</i></html>");

        int ok = JOptionPane.showConfirmDialog(this, sb.toString(),
            "Supprimer les fichiers illisibles", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;

        int deleted = 0, failDel = 0;
        for (FileEntry e : errors) {
            File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
            int idx = tableModel.indexOf(e);
            if (f.delete()) {
                if (idx >= 0) tableModel.remove(idx);
                deleted++;
            } else {
                failDel++;
            }
        }
        String msg = deleted + " fichier(s) illisible(s) supprimé(s)";
        if (failDel > 0) msg += ", " + failDel + " échec(s) (permission refusée ?)";
        setStatus(msg);
        JOptionPane.showMessageDialog(this, msg, "Résultat", JOptionPane.INFORMATION_MESSAGE);
    }

    private void submitAcoustId() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        FileEntry e = tableModel.get(table.convertRowIndexToModel(row));
        if (e.status != FileEntry.Status.TAGGED) {
            setStatus("Soumettre AcoustID : ce fichier doit d'abord être tagué.");
            return;
        }
        if (!com.opentagger.AcoustIdSubmitter.isAvailable()) {
            showError("fpcalc introuvable — installez chromaprint pour soumettre une empreinte.");
            return;
        }
        setStatus("Soumission AcoustID en cours…");
        File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
        TagInfo tags = e.activeTags();
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return new com.opentagger.AcoustIdSubmitter().submit(f, tags);
            }
            @Override protected void done() {
                try { setStatus("AcoustID : " + get()); }
                catch (Exception ex) { showError("Soumission AcoustID : " + ex.getMessage()); }
            }
        }.execute();
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lblStatus.setText("  " + msg));
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Erreur", JOptionPane.ERROR_MESSAGE);
    }

    private Color sep() {
        Color c = UIManager.getColor("Separator.foreground");
        return c != null ? c : new Color(80, 80, 80);
    }
}
