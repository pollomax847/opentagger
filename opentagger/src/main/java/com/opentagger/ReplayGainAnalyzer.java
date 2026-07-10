package com.opentagger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calcule le ReplayGain d'une piste via le filtre «replaygain» de ffmpeg.
 * Aucune dépendance supplémentaire — réutilise ffmpeg déjà requis pour BPM.
 */
public class ReplayGainAnalyzer {

    private static final Pattern PAT_GAIN = Pattern.compile("track_gain\\s*=\\s*([+-]?\\d+\\.\\d+)");
    private static final Pattern PAT_PEAK = Pattern.compile("track_peak\\s*=\\s*(\\d+\\.\\d+)");

    public record RGResult(String trackGain, String trackPeak) {}

    public static boolean isAvailable() {
        try {
            String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
            ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-filters");
            pb.redirectErrorStream(true);
            String out = runCaptured(pb, 5);
            return out != null && out.contains("replaygain");
        } catch (Exception e) { return false; }
    }

    /**
     * Analyse un album complet (concaténation de toutes les pistes via filter_complex).
     * Le gain résultant est le gain album — différent du gain de piste.
     * Retourne null si ffmpeg échoue ou si la liste est vide/singleton.
     */
    public static RGResult analyzeAlbum(java.util.List<String> filePaths) {
        if (filePaths == null || filePaths.size() < 2) return null;
        // Cette analyse lit l'intégralité de TOUTES les pistes de l'album concaténées — la plus
        // grosse lecture disque du lot. Un seul permis pris sur la première piste : elles vivent
        // normalement toutes sur le même disque physique (même dossier d'album), voir
        // DiskIoThrottle pour le pourquoi de la limite.
        java.util.concurrent.Semaphore gate = DiskIoThrottle.acquireFor(new java.io.File(filePaths.get(0)));
        try {
            String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(ffmpeg);
            for (String fp : filePaths) { cmd.add("-i"); cmd.add(fp); }
            // [0:a][1:a]…concat=n=N:v=0:a=1,replaygain
            StringBuilder fc = new StringBuilder();
            for (int i = 0; i < filePaths.size(); i++) fc.append("[").append(i).append(":a]");
            fc.append("concat=n=").append(filePaths.size()).append(":v=0:a=1,replaygain");
            cmd.add("-filter_complex"); cmd.add(fc.toString());
            cmd.add("-f"); cmd.add("null"); cmd.add("-");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            String output = runCaptured(pb, 600);
            if (output == null) return null; // timeout — process tué, pas de résultat exploitable

            return parseOutput(output);
        } catch (Exception e) { return null; } finally {
            DiskIoThrottle.release(gate);
        }
    }

    /**
     * Analyse le fichier et retourne gain/peak, ou null si ffmpeg échoue.
     * Exemple de sortie ffmpeg :
     *   [replaygain @ 0x…] track_gain = -4.73 dB
     *   [replaygain @ 0x…] track_peak = 0.983547
     */
    public RGResult analyze(String filePath) {
        // Lit l'intégralité du fichier (nécessaire pour un ReplayGain correct, pas de raccourci
        // possible) — la plus grosse lecture disque par piste du pipeline. Voir DiskIoThrottle.
        java.util.concurrent.Semaphore gate = DiskIoThrottle.acquireFor(new java.io.File(filePath));
        try {
            String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg, "-i", filePath, "-af", "replaygain", "-f", "null", "-");
            pb.redirectErrorStream(true);
            String output = runCaptured(pb, 120);
            if (output == null) return null;

            return parseOutput(output);
        } catch (Exception e) { return null; } finally {
            DiskIoThrottle.release(gate);
        }
    }

    private static RGResult parseOutput(String output) {
        String gain = "", peak = "";
        for (String line : output.split("\\n")) {
            if (line.contains("track_gain")) {
                Matcher m = PAT_GAIN.matcher(line);
                if (m.find()) gain = m.group(1) + " dB";
            }
            if (line.contains("track_peak")) {
                Matcher m = PAT_PEAK.matcher(line);
                if (m.find()) peak = m.group(1);
            }
        }
        return (gain.isBlank() && peak.isBlank()) ? null : new RGResult(gain, peak);
    }

    /**
     * Démarre le process et draine sa sortie sur un thread séparé PENDANT que waitFor(timeout)
     * attend — l'ancien code lisait toute la sortie (bloquant jusqu'à EOF) AVANT d'appeler
     * waitFor(timeout), ce qui rendait ce timeout inopérant : un ffmpeg bloqué (fichier corrompu,
     * montage NAS/MergerFS capricieux) gelait le thread appelant indéfiniment et geler tout le
     * lot en cours côté TaggingWorker (celui-ci tourne en séquentiel, un seul fichier bloqué
     * suffit à tout arrêter). Retourne null si le délai est dépassé (process tué).
     */
    private static String runCaptured(ProcessBuilder pb, long timeoutSeconds) throws Exception {
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try { p.getInputStream().transferTo(out); }
            catch (Exception ignored) {}
        });
        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return null; }
        try { drain.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
