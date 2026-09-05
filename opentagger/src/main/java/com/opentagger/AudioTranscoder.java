package com.opentagger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Transcode un fichier audio via ffmpeg.
 * Format cible, débit et conservation de la source sont configurables.
 */
public class AudioTranscoder {

    public enum Format {
        MP3 ("mp3",  "mp3",  "libmp3lame", true),
        FLAC("flac", "flac", "flac",        false),
        AAC ("aac",  "m4a",  "aac",         true),
        OGG ("ogg",  "ogg",  "libvorbis",   true),
        OPUS("opus", "opus", "libopus",      true);

        public final String id, ext, codec;
        public final boolean hasBitrate;

        Format(String id, String ext, String codec, boolean hasBitrate) {
            this.id = id; this.ext = ext; this.codec = codec; this.hasBitrate = hasBitrate;
        }

        public static Format fromId(String id) {
            for (Format f : values()) if (f.id.equalsIgnoreCase(id)) return f;
            return MP3;
        }

        @Override public String toString() { return id.toUpperCase(); }
    }

    private final String ffmpegPath;

    public AudioTranscoder() {
        this.ffmpegPath = Config.get().str("audio.ffmpeg_path", "ffmpeg");
    }

    /**
     * Transcode {@code source} vers {@code format}.
     *
     * @return chemin du fichier produit, ou {@code null} si la source est déjà dans ce format
     * @throws IOException si ffmpeg échoue ou si le fichier produit est vide
     */
    public Path transcode(Path source, Format format, int bitrateKbps, boolean deleteSource)
            throws IOException, InterruptedException {

        String srcName = source.getFileName().toString();
        String srcExt  = srcName.contains(".")
                ? srcName.substring(srcName.lastIndexOf('.') + 1).toLowerCase() : "";

        if (srcExt.equals(format.ext)) return null; // déjà dans le bon format

        String stem = srcName.contains(".")
                ? srcName.substring(0, srcName.lastIndexOf('.')) : srcName;

        Path dest = source.getParent().resolve(stem + "." + format.ext);
        // Éviter d'écraser un fichier existant
        for (int i = 1; Files.exists(dest); i++)
            dest = source.getParent().resolve(stem + "_" + i + "." + format.ext);

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-y");
        cmd.add("-i");  cmd.add(source.toAbsolutePath().toString());
        cmd.add("-codec:a"); cmd.add(format.codec);
        if (format.hasBitrate && bitrateKbps > 0) {
            cmd.add("-b:a"); cmd.add(bitrateKbps + "k");
            if (format == Format.MP3) { cmd.add("-q:a"); cmd.add("0"); }
        }
        cmd.add("-map_metadata"); cmd.add("0"); // conserver les tags existants
        cmd.add("-vn");                          // pas de flux vidéo/pochette (évite erreurs AAC)
        cmd.add(dest.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // Drainer la sortie sur un thread séparé PENDANT que waitFor(timeout) attend : lire tout
        // le flux (bloquant jusqu'à EOF) AVANT d'appeler waitFor rendait ce timeout inopérant —
        // un ffmpeg bloqué (fichier corrompu, montage NAS/MergerFS capricieux) gelait ce thread
        // indéfiniment sans jamais être tué.
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (InputStream is = p.getInputStream()) { is.transferTo(out); }
            catch (Exception ignored) {}
        });

        boolean finished = p.waitFor(5, TimeUnit.MINUTES);
        if (!finished) { p.destroyForcibly(); throw new IOException("Timeout ffmpeg"); }
        try { drain.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        if (p.exitValue() != 0 || !Files.exists(dest) || Files.size(dest) == 0) {
            Files.deleteIfExists(dest);
            String tail = out.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
            if (tail.length() > 400) tail = "…" + tail.substring(tail.length() - 400);
            throw new IOException("Transcodage échoué (code " + p.exitValue() + ") — " + tail);
        }

        if (deleteSource) Files.deleteIfExists(source);

        return dest;
    }

    /** Signatures ffmpeg typiques d'une source qu'il ne parvient même pas à ouvrir/décoder —
     *  corrompue ou téléchargement tronqué, PAS un problème de réglages (codec de sortie manquant,
     *  bitrate invalide, disque plein...). Utilisé à la fois pour classer l'échec initial du
     *  transcodage et pour la contre-vérification indépendante de verifyUnreadable() ci-dessous. */
    public static boolean isUnreadableSourceError(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return m.contains("moov atom not found")
            || m.contains("invalid data found when processing input")
            || m.contains("error opening input")
            || m.contains("could not find codec parameters");
    }

    /**
     * Contre-vérification INDÉPENDANTE avant tout déplacement automatique (voir Config.
     * transcodeMoveUnreadableEnabled()) : un échec de transcodage peut avoir plein d'autres causes
     * que la corruption de la source (codec de sortie manquant, bitrate invalide, sortie déjà
     * existante verrouillée...) — on ne veut jamais isoler un fichier sur la seule foi de CE
     * message d'erreur là. Ici, on redécode tout le flux vers le muxer "null" (aucune écriture sur
     * le disque, ni fichier de sortie ni risque d'écraser quoi que ce soit) et on ne considère le
     * fichier illisible que si CETTE tentative séparée échoue AUSSI avec une signature reconnue.
     */
    public boolean verifyUnreadable(Path source) {
        try {
            List<String> cmd = List.of(ffmpegPath, "-v", "error",
                    "-i", source.toAbsolutePath().toString(), "-f", "null", "-");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream is = p.getInputStream()) { is.transferTo(out); }
                catch (Exception ignored) {}
            });

            boolean finished = p.waitFor(2, TimeUnit.MINUTES);
            if (!finished) { p.destroyForcibly(); return false; }
            try { drain.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            if (p.exitValue() == 0) return false; // ffmpeg a réussi à tout décoder → pas corrompu
            String tail = out.toString(java.nio.charset.StandardCharsets.UTF_8);
            return isUnreadableSourceError(tail);
        } catch (Exception e) {
            return false; // en cas de doute (ffmpeg introuvable, etc.) : NE PAS déplacer
        }
    }
}
