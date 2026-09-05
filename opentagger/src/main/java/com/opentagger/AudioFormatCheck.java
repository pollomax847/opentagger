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

    // Contrairement à EXPECTED (une extension → tokens attendus, pour la détection), celle-ci va
    // dans l'autre sens : un token ffprobe → LA SEULE extension canonique vers laquelle proposer un
    // renommage automatique. Ne couvre QUE les cas non ambigus. La famille MP4 (mov/mp4/m4a/3gp)
    // est délibérément absente d'ici : ffprobe ne distingue pas un .m4a musique d'un .m4b livre
    // audio ou d'un vrai .mp4 vidéo à partir du seul format_name — résolue à part dans
    // suggestCorrectExtension() avec un choix par défaut documenté, pas une correspondance directe.
    private static final Map<String, String> CANONICAL_EXT = Map.of(
        "flac", "flac",
        "wav",  "wav",
        "aiff", "aiff",
        "asf",  "wma"
    );

    /**
     * Extension vers laquelle renommer un fichier mal étiqueté (voir describeMismatch()), déduite
     * du format réel détecté par ffprobe. {@code null} si le format réel n'est pas reconnu avec
     * assez de certitude pour agir seul (mieux vaut ne rien proposer que deviner faux).
     *
     * Cas MP4 (mov/mp4/m4a/3gp) résolu en ".m4a" par défaut : de très loin le cas le plus fréquent
     * dans une bibliothèque musicale (constaté deux fois en session : "Double Je.mp3"/"Belsunce
     * Breakdown.mp3", tous deux de l'AAC/M4A sous un tag ID3 collé devant) — un .m4b (livre audio)
     * ou un .mp4 vidéo mal étiqueté ".mp3" serait de toute façon une coïncidence extrêmement rare.
     */
    public static String suggestCorrectExtension(java.io.File f) {
        String real = detectRealFormat(f);
        if (real == null || real.isBlank()) return null;
        String r = real.toLowerCase();
        if (r.contains("mov") || r.contains("mp4") || r.contains("m4a") || r.contains("3gp")) return "m4a";
        for (Map.Entry<String, String> entry : CANONICAL_EXT.entrySet())
            if (r.contains(entry.getKey())) return entry.getValue();
        return null;
    }

    /**
     * {@code true} si ce fichier contient un flux VIDÉO réel (pas juste audio) — spécifiquement
     * pour lever l'ambiguïté ".mp4" qu'{@link #describeMismatch}/{@link #suggestCorrectExtension}
     * documentent déjà comme non résoluble par le seul format_name (mov/mp4/m4a/3gp est la même
     * famille de conteneur, que le contenu soit un morceau audio pur ou un vrai clip vidéo).
     * AudioScanner traite tous les ".mp4" comme candidats audio, et VideoScanner (utilisé par
     * ui.VideoRecoveryWorker) les exclut délibérément pour la même raison — un vrai .mp4 vidéo
     * passe donc par le pipeline audio normal, y échoue (non identifié / durée incohérente), sans
     * qu'aucun des deux systèmes ne signale à l'utilisateur qu'il s'agit en fait d'une vidéo.
     * Opportuniste comme le reste de cette classe : jamais appelé sur le chemin normal, seulement
     * après un échec déjà survenu (voir TaggingWorker, appelé uniquement pour les ".mp4" en échec).
     */
    public static boolean hasVideoStream(java.io.File f) {
        try {
            List<String> cmd = List.of(Config.get().str("audio.ffprobe_path", "ffprobe"),
                    "-v", "quiet", "-select_streams", "v", "-show_entries", "stream=codec_type",
                    "-of", "csv=p=0", f.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream is = p.getInputStream()) { is.transferTo(out); } catch (Exception ignored) {}
            });
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            try { drain.join(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            return !out.toString(StandardCharsets.UTF_8).strip().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Vrai si ffprobe rapporte une durée exploitable (>= 5s) pour ce fichier — utilisé pour
     * distinguer un fichier réellement vide/cassé (transcode interrompu, téléchargement avorté :
     * header peut-être présent mais aucun contenu derrière) d'un simple format non couvert par
     * {@link #suggestCorrectExtension}. Voir MainFrame.repairMisnamedFiles() — jamais appelé sur le
     * chemin normal, action manuelle uniquement.
     */
    public static boolean hasReadableDuration(java.io.File f) {
        try {
            List<String> cmd = List.of(Config.get().str("audio.ffprobe_path", "ffprobe"),
                    "-v", "quiet", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", f.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream is = p.getInputStream()) { is.transferTo(out); } catch (Exception ignored) {}
            });
            boolean done = p.waitFor(10, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            try { drain.join(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            String result = out.toString(StandardCharsets.UTF_8).strip();
            if (result.isEmpty()) return false;
            double dur = Double.parseDouble(result);
            return dur >= 5.0;
        } catch (Exception e) {
            return false;
        }
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
