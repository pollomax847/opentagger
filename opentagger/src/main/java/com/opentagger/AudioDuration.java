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
            ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
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
