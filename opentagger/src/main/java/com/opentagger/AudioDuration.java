package com.opentagger;

/**
 * Durée réelle d'un fichier audio via ffprobe — partagé entre le matching de
 * podcasts (durée ± 5 s) et le matching de pistes d'album (durée ± 3 s).
 */
public final class AudioDuration {

    private AudioDuration() {}

    /** Durée en secondes (arrondie), ou -1 si indisponible/ffprobe absent. */
    public static int probeSeconds(String path) {
        try {
            // "ffprobe" en dur ignorait audio.ffprobe_path (voir FfmpegTagIO.ffprobePath()/
            // AudioFormatCheck) : un utilisateur ayant dû configurer un chemin ffmpeg/ffprobe
            // personnalisé (binaire hors PATH) se retrouvait ici avec un ffprobe introuvable,
            // dégradation silencieuse (-1, juste un fallback vers le matching par titre).
            ProcessBuilder pb = new ProcessBuilder(
                Config.get().str("audio.ffprobe_path", "ffprobe"), "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                path);
            pb.redirectErrorStream(true);
            String out = ProcessUtils.readStringWithTimeout(pb, 10);
            if (out != null && !out.isBlank())
                return (int) Math.round(Double.parseDouble(out.trim()));
        } catch (Exception ignored) {}
        return -1;
    }
}
