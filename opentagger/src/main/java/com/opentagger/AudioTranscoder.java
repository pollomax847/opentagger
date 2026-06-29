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

        try (InputStream is = p.getInputStream()) {
            is.transferTo(OutputStream.nullOutputStream());
        }

        boolean finished = p.waitFor(5, TimeUnit.MINUTES);
        if (!finished) { p.destroyForcibly(); throw new IOException("Timeout ffmpeg"); }

        if (p.exitValue() != 0 || !Files.exists(dest) || Files.size(dest) == 0) {
            Files.deleteIfExists(dest);
            throw new IOException("Transcodage échoué (code " + p.exitValue() + ")");
        }

        if (deleteSource) Files.deleteIfExists(source);

        return dest;
    }
}
