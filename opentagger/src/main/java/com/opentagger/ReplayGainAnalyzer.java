package com.opentagger;

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
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, TimeUnit.SECONDS);
            return out.contains("replaygain");
        } catch (Exception e) { return false; }
    }

    /**
     * Analyse un album complet (concaténation de toutes les pistes via filter_complex).
     * Le gain résultant est le gain album — différent du gain de piste.
     * Retourne null si ffmpeg échoue ou si la liste est vide/singleton.
     */
    public static RGResult analyzeAlbum(java.util.List<String> filePaths) {
        if (filePaths == null || filePaths.size() < 2) return null;
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
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(600, TimeUnit.SECONDS);

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
        } catch (Exception e) { return null; }
    }

    /**
     * Analyse le fichier et retourne gain/peak, ou null si ffmpeg échoue.
     * Exemple de sortie ffmpeg :
     *   [replaygain @ 0x…] track_gain = -4.73 dB
     *   [replaygain @ 0x…] track_peak = 0.983547
     */
    public RGResult analyze(String filePath) {
        try {
            String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg, "-i", filePath, "-af", "replaygain", "-f", "null", "-");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor(120, TimeUnit.SECONDS);

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
        } catch (Exception e) { return null; }
    }
}
