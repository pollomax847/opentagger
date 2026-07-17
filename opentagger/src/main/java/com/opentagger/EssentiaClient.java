package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.*;
import java.nio.file.*;
import java.util.logging.Logger;

/**
 * Intégration Essentia (Music Technology Group, UPF Barcelona).
 *
 * Requiert : essentia_streaming_extractor_music dans le PATH.
 * Optionnel : modèles TF Lite dans ~/.essentia/models/ pour les moods.
 *
 * Usage identique à fpcalc pour AcoustID :
 *   essentia_streaming_extractor_music <fichier> <sortie.json>
 *
 * Champs extraits sans modèles ML (standard) :
 *   - rhythm.bpm                    → TagInfo.bpm
 *   - tonal.key_key + key_scale     → TagInfo.initialKey
 *
 * Champs extraits avec modèles ML essentia-tensorflow :
 *   - highlevel.mood_*              → TagInfo.mood*
 *   - highlevel.danceability        → TagInfo.moodDanceability
 *   - highlevel.voice_instrumental  → TagInfo.moodInstrumental
 */
public class EssentiaClient {

    private static final Logger LOG = Logger.getLogger(EssentiaClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String binary;

    public EssentiaClient() {
        this.binary = Config.get().str("audio.essentia_path", "essentia_streaming_extractor_music");
    }

    // ── Analyse d'un fichier ─────────────────────────────────────────────────

    public void analyze(String filePath, TagInfo info) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("opentagger_essentia_", ".json");
            int exit = runEssentia(filePath, tmp.toString());
            if (exit != 0) {
                LOG.fine("Essentia exit " + exit + " pour " + filePath);
                return;
            }
            JsonNode root = MAPPER.readTree(tmp.toFile());
            extractFields(root, info);
        } catch (Exception e) {
            LOG.fine("Essentia erreur : " + e.getMessage());
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }

    private int runEssentia(String inputFile, String outputFile) throws Exception {
        // Timeout 120s pour éviter le blocage infini sur les fichiers corrompus
        ProcessBuilder pb = new ProcessBuilder(binary, inputFile, outputFile);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        Thread drainer = Thread.ofVirtual().start(() -> {
            try { proc.getInputStream().transferTo(OutputStream.nullOutputStream()); }
            catch (Exception ignored) {}
        });
        boolean done = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!done) {
            proc.destroyForcibly();
            LOG.warning("Essentia timeout sur " + inputFile);
            return -1;
        }
        drainer.join(5000);
        return proc.exitValue();
    }

    // ── Extraction des champs depuis le JSON ─────────────────────────────────

    private void extractFields(JsonNode root, TagInfo info) {
        // ── Rythme ────────────────────────────────────────────────────────
        JsonNode rhythm = root.path("rhythm");
        if (!rhythm.isMissingNode()) {
            double bpm = rhythm.path("bpm").asDouble(0);
            if (bpm > 0) {
                info.bpm  = String.valueOf((int) Math.round(bpm));
                info.fbpm = String.format("%.4f", bpm);
            }
        }

        // ── Tonalité ──────────────────────────────────────────────────────
        JsonNode tonal = root.path("tonal");
        if (!tonal.isMissingNode()) {
            String key   = tonal.path("key_key").asText("");
            String scale = tonal.path("key_scale").asText("");
            if (!key.isBlank()) {
                // ex: "C" + "minor" → "Cm", "F#" + "major" → "F#"
                info.initialKey = scale.equalsIgnoreCase("minor") ? key + "m" : key;
            }
        }

        // ── Mood (nécessite essentia-tensorflow + modèles) ─────────────────
        JsonNode hl = root.path("highlevel");
        if (hl.isMissingNode()) return;

        info.moodAggressive  = topClass(hl, "mood_aggressive");
        info.moodAcoustic    = topClass(hl, "mood_acoustic");
        info.moodElectronic  = topClass(hl, "mood_electronic");
        info.moodHappy       = topClass(hl, "mood_happy");
        info.moodParty       = topClass(hl, "mood_party");
        info.moodRelaxed     = topClass(hl, "mood_relaxed");
        info.moodSad         = topClass(hl, "mood_sad");
        info.moodDanceability = topClass(hl, "danceability");
        info.moodInstrumental = topClass(hl, "voice_instrumental");

        // Genre MIREX (broad mood label)
        String mirex = topClass(hl, "moods_mirex");
        if (!mirex.isBlank()) info.mood = mirex;

        // Valence et arousal si disponibles (Essentia 2.1+ avec VA model)
        String valence = topClass(hl, "mood_valence");
        String arousal = topClass(hl, "mood_arousal");
        if (!valence.isBlank()) info.moodValence = valence;
        if (!arousal.isBlank()) info.moodArousal = arousal;
    }

    /**
     * Extrait la classe dominante d'un descripteur highlevel.
     * Format JSON : {"value": "aggressive", "probability": 0.87, ...}
     */
    private String topClass(JsonNode hl, String key) {
        JsonNode node = hl.path(key);
        if (node.isMissingNode()) return "";
        return node.path("value").asText("").trim();
    }

    // ── Disponibilité ────────────────────────────────────────────────────────

    public boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(binary, "--help")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return true; // l'absence de binaire lève une IOException
        } catch (IOException e) {
            return false;
        } catch (Exception e) {
            return true; // s'il tourne = disponible
        }
    }

    public static boolean isOnPath() {
        String binary = Config.get().str("audio.essentia_path", "essentia_streaming_extractor_music");
        try {
            // "which" sur Linux/macOS, "where" sur Windows
            String finder = System.getProperty("os.name","").toLowerCase().contains("win") ? "where" : "which";
            Process p = new ProcessBuilder(finder, binary)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
