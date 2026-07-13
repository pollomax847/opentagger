package com.opentagger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Détecte un décalage entre l'extension d'un fichier et son contenu audio réel via ffprobe —
 * trouvé deux fois en une session ("04 Double Je.mp3"/"21 Belsunce Breakdown.mp3", en réalité de
 * l'AAC/M4A avec juste un tag ID3v2 collé devant) : jaudiotagger échoue alors avec un message
 * cryptique ("No audio header found within ...") qui ne dit jamais QUEL est le vrai problème.
 * Opportuniste seulement — jamais appelé sur le chemin normal de lecture/écriture (un ffprobe par
 * fichier serait bien trop coûteux sur une bibliothèque de centaines de milliers de fichiers) ;
 * uniquement en diagnostic APRÈS un échec jaudiotagger déjà survenu, voir
 * TagWriter.translateKnownJaudiotaggerBug().
 */
public final class AudioFormatCheck {

    private AudioFormatCheck() {}

    // Jetons attendus dans le "format_name" ffprobe (souvent une liste type "mov,mp4,m4a,3gp,3g2,
    // mj2") pour chaque extension gérée par AudioScanner — une extension absente de cette table
    // n'est simplement jamais vérifiée (pas d'erreur), pas la peine de couvrir les cas rares.
    private static final Map<String, List<String>> EXPECTED = Map.ofEntries(
        Map.entry("mp3",  List.of("mp3")),
        Map.entry("m4a",  List.of("mov", "mp4", "m4a", "3gp")),
        Map.entry("m4b",  List.of("mov", "mp4", "m4a", "3gp")),
        Map.entry("mp4",  List.of("mov", "mp4", "m4a", "3gp")),
        Map.entry("flac", List.of("flac")),
        Map.entry("ogg",  List.of("ogg")),
        Map.entry("opus", List.of("ogg")),
        Map.entry("wav",  List.of("wav")),
        Map.entry("aiff", List.of("aiff")),
        Map.entry("aif",  List.of("aiff")),
        Map.entry("wma",  List.of("asf")),
        Map.entry("aac",  List.of("aac", "adts"))
    );

    /**
     * {@code null} si le format semble cohérent avec l'extension (ou si indéterminable — jamais de
     * faux positif alarmant sur un doute), sinon un message prêt à afficher décrivant le vrai
     * format détecté.
     */
    public static String describeMismatch(java.io.File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase();
        List<String> expected = EXPECTED.get(ext);
        if (expected == null) return null;

        String real = detectRealFormat(f);
        if (real == null || real.isBlank()) return null;
        String realLower = real.toLowerCase();
        for (String token : expected) if (realLower.contains(token)) return null; // cohérent

        return I18n.t("le contenu réel (%s) ne correspond pas à l'extension \".%s\" — probablement "
                + "renommé/converti par erreur à un moment donné.", real, ext);
    }

    private static String detectRealFormat(java.io.File f) {
        try {
            List<String> cmd = List.of(Config.get().str("audio.ffprobe_path", "ffprobe"),
                    "-v", "quiet", "-show_entries", "format=format_name",
                    "-of", "default=noprint_wrappers=1:nokey=1", f.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream is = p.getInputStream()) { is.transferTo(out); } catch (Exception ignored) {}
            });
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return null; }
            try { drain.join(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            String result = out.toString(StandardCharsets.UTF_8).strip();
            return result.isBlank() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }
}
