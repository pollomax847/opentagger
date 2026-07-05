package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.StandardArtwork;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Dialogue de gestion de la pochette d'un fichier audio.
 * Permet de visualiser, remplacer, supprimer et exporter la pochette.
 */
public class CoverArtDialog extends JDialog {

    private static final int PREVIEW_SIZE = 360;

    private final FileEntry entry;
    private final Runnable  onApplied;

    private final JLabel   lblPreview;
    private final JLabel   lblInfo;
    private final JButton  btnApply;

    private byte[]  pendingImageBytes = null;
    private boolean pendingDelete     = false;

    // Statique et partagé : une instance par ouverture de dialogue (l'ancien code) reproduit
    // exactement la fuite HttpClient déjà corrigée ailleurs (commit 71e27ef) — "Gérer la
    // pochette…" est une action de routine, répétée souvent sur une session longue.
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
            .build();

    public CoverArtDialog(Frame owner, FileEntry entry, Runnable onApplied) {
        super(owner, I18n.t("Pochette — %s", entry.filename()), true);
        this.entry     = entry;
        this.onApplied = onApplied;
        setSize(540, 580);
        setMinimumSize(new Dimension(420, 460));
        setLocationRelativeTo(owner);

        lblPreview = new JLabel(I18n.t("Chargement…"), SwingConstants.CENTER);
        lblPreview.setPreferredSize(new Dimension(PREVIEW_SIZE, PREVIEW_SIZE));
        lblPreview.setBorder(new MatteBorder(1, 1, 1, 1, UIManager.getColor("Separator.foreground")));

        lblInfo = new JLabel(" ", SwingConstants.CENTER);
        lblInfo.putClientProperty("FlatLaf.style", "foreground: #888888; font: 11 $defaultFont");

        btnApply = new JButton("✓  " + I18n.t("Appliquer"));
        btnApply.setEnabled(false);
        btnApply.putClientProperty("FlatLaf.style", "background: #1a6030");

        JPanel imgRow = new JPanel(new BorderLayout(10, 0));
        imgRow.add(lblPreview,    BorderLayout.CENTER);
        imgRow.add(buildActions(), BorderLayout.EAST);

        JPanel centerPane = new JPanel(new BorderLayout(0, 4));
        centerPane.setBorder(new EmptyBorder(12, 12, 0, 12));
        centerPane.add(imgRow,  BorderLayout.CENTER);
        centerPane.add(lblInfo, BorderLayout.SOUTH);

        getContentPane().setLayout(new BorderLayout(0, 0));
        getContentPane().add(centerPane,    BorderLayout.CENTER);
        getContentPane().add(buildFooter(), BorderLayout.SOUTH);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        getRootPane().getActionMap().put("close",
                new AbstractAction() { @Override public void actionPerformed(java.awt.event.ActionEvent e) { dispose(); } });

        loadCurrentCover();
    }

    // ── Boutons d'action ──────────────────────────────────────────────────────

    private JPanel buildActions() {
        JButton btnLocal  = new JButton("📂  " + I18n.t("Choisir image locale…"));
        JButton btnCaa    = new JButton("🌐  Cover Art Archive (MB)");
        JButton btnDelete = new JButton("🗑  " + I18n.t("Supprimer la pochette"));
        JButton btnFolder = new JButton("💾  " + I18n.t("Sauver en folder.jpg"));

        btnLocal .addActionListener(e -> chooseLocal());
        btnCaa   .addActionListener(e -> downloadCaa());
        btnDelete.addActionListener(e -> markDelete());
        btnFolder.addActionListener(e -> saveToFolder());

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(0, 8, 0, 0));
        for (JButton b : new JButton[]{btnLocal, btnCaa, btnDelete, btnFolder}) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(224, 36));
            p.add(b);
            p.add(Box.createVerticalStrut(6));
        }
        p.add(Box.createVerticalGlue());
        return p;
    }

    private JPanel buildFooter() {
        JButton btnClose = new JButton(I18n.t("Fermer"));
        btnClose.addActionListener(e -> dispose());
        btnApply.addActionListener(e -> applyChanges());

        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        p.setBorder(new MatteBorder(1, 0, 0, 0, UIManager.getColor("Separator.foreground")));
        p.add(btnClose);
        p.add(btnApply);
        return p;
    }

    // ── Chargement de la pochette actuelle ────────────────────────────────────

    private void loadCurrentCover() {
        File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
        new SwingWorker<ImageIcon, Void>() {
            @Override protected ImageIcon doInBackground() throws Exception {
                AudioFile af  = AudioFileIO.read(f);
                Tag       tag = af.getTag();
                if (tag == null) return null;
                Artwork art = tag.getFirstArtwork();
                if (art == null) return null;
                byte[] data = art.getBinaryData();
                if (data == null || data.length == 0) return null;
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
                return img != null ? scaledIcon(img) : null;
            }
            @Override protected void done() {
                try {
                    ImageIcon icon = get();
                    if (icon != null) { lblPreview.setIcon(icon); lblPreview.setText(""); }
                    else              { lblPreview.setIcon(null);  lblPreview.setText(I18n.t("Pas de pochette")); }
                } catch (Exception ignored) { lblPreview.setText(I18n.t("Erreur de lecture")); }
            }
        }.execute();
    }

    // ── Choisir une image locale ──────────────────────────────────────────────

    private void chooseLocal() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter(
                "Images (JPG, PNG, WEBP)", "jpg", "jpeg", "png", "webp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File img = fc.getSelectedFile();
        try {
            showPreview(Files.readAllBytes(img.toPath()),
                    img.getName() + " — " + I18n.t("%d Ko", img.length() / 1024));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, I18n.t("Impossible de lire l'image : %s", ex.getMessage()),
                    I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Téléchargement Cover Art Archive (MusicBrainz) ────────────────────────

    private void downloadCaa() {
        String mbid = entry.activeTags().releaseMbid;
        if (mbid == null || mbid.isBlank()) {
            JOptionPane.showMessageDialog(this,
                    I18n.t("Aucun Release MBID disponible.\nTaguez d'abord ce fichier pour obtenir un MBID."),
                    "Cover Art Archive", JOptionPane.WARNING_MESSAGE);
            return;
        }
        lblInfo.setText(I18n.t("Téléchargement depuis Cover Art Archive…"));
        new SwingWorker<byte[], Void>() {
            @Override protected byte[] doInBackground() throws Exception {
                String url = "https://coverartarchive.org/release/" + mbid + "/front-500";
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", Config.get().userAgent())
                        .GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200)
                    throw new Exception("HTTP " + resp.statusCode());
                return resp.body();
            }
            @Override protected void done() {
                try {
                    byte[] data = get();
                    showPreview(data, "Cover Art Archive — " + I18n.t("%d Ko", data.length / 1024));
                } catch (Exception ex) {
                    lblInfo.setText(I18n.t("Erreur : %s", ex.getMessage()));
                }
            }
        }.execute();
    }

    // ── Suppression ───────────────────────────────────────────────────────────

    private void markDelete() {
        pendingDelete     = true;
        pendingImageBytes = null;
        lblPreview.setIcon(null);
        lblPreview.setText(I18n.t("Pochette supprimée à l'application"));
        lblInfo.setText(I18n.t("La pochette sera supprimée du fichier."));
        btnApply.setEnabled(true);
    }

    // ── Sauver en folder.jpg ──────────────────────────────────────────────────

    private void saveToFolder() {
        byte[] data = pendingImageBytes;
        if (data == null) {
            // Utiliser la pochette déjà dans le fichier
            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            try {
                AudioFile af  = AudioFileIO.read(f);
                Tag       tag = af.getTag();
                if (tag == null || tag.getFirstArtwork() == null) {
                    lblInfo.setText(I18n.t("Pas de pochette à sauvegarder."));
                    return;
                }
                data = tag.getFirstArtwork().getBinaryData();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, I18n.t("Erreur lecture : %s", ex.getMessage()),
                        I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        try {
            File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            // Détecter PNG par les bytes magiques (0x89 PNG) pour choisir la bonne extension
            String fname = (data.length >= 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P')
                    ? "folder.png" : "folder.jpg";
            Path dest = f.toPath().getParent().resolve(fname);
            Files.write(dest, data);
            lblInfo.setText(I18n.t("Sauvegardé → %s", dest.getFileName()));
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, I18n.t("Erreur sauvegarde : %s", ex.getMessage()),
                    I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Application des changements au fichier ────────────────────────────────

    private void applyChanges() {
        File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
        try {
            // Réparation M4A si nécessaire avant lecture/écriture, comme tous les autres
            // chemins d'écriture (TagWriter.write/writeCoverOnly) — sans ça, un M4A cassé
            // qui se répare normalement au tagging échoue silencieusement ici.
            TagWriter.repairM4aIfNeeded(f);
            AudioFile af  = AudioFileIO.read(f);
            Tag       tag = af.getTagOrCreateDefault();

            if (pendingDelete) {
                tag.deleteArtworkField();
            } else if (pendingImageBytes != null) {
                tag.deleteArtworkField();
                StandardArtwork art = new StandardArtwork();
                art.setBinaryData(pendingImageBytes);
                art.setMimeType(detectMime(pendingImageBytes));
                art.setPictureType(3); // Front Cover
                tag.setField(art);
            }
            af.commit();
            if (onApplied != null) onApplied.run();
            dispose();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, I18n.t("Erreur d'écriture : %s", ex.getMessage()),
                    I18n.t("Erreur"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void showPreview(byte[] bytes, String info) {
        pendingImageBytes = bytes;
        pendingDelete     = false;
        lblInfo.setText(info);
        btnApply.setEnabled(true);
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img != null) { lblPreview.setIcon(scaledIcon(img)); lblPreview.setText(""); }
            else             { lblPreview.setText(I18n.t("Format image non reconnu")); }
        } catch (Exception ignored) { lblPreview.setText(I18n.t("Erreur aperçu")); }
    }

    private ImageIcon scaledIcon(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w <= 0 || h <= 0) return new ImageIcon(img);
        double scale = Math.min((double) PREVIEW_SIZE / w, (double) PREVIEW_SIZE / h);
        int nw = (int)(w * scale), nh = (int)(h * scale);
        return new ImageIcon(img.getScaledInstance(Math.max(1, nw), Math.max(1, nh), Image.SCALE_SMOOTH));
    }

    private String detectMime(byte[] b) {
        if (b.length >= 4 && b[0] == (byte)0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')
            return "image/png";
        if (b.length >= 2 && b[0] == (byte)0xFF && b[1] == (byte)0xD8)
            return "image/jpeg";
        return "image/jpeg";
    }
}
