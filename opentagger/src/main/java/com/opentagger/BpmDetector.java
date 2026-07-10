package com.opentagger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Détection du BPM par analyse énergétique de l'audio.
 *
 * Algorithme (autocorrélation sur enveloppe d'énergie) :
 *  1. Décode 60s d'audio en PCM mono 8kHz via ffmpeg
 *  2. Calcule l'énergie RMS par fenêtre de 23ms
 *  3. Lisse l'enveloppe d'énergie
 *  4. Calcule l'autocorrélation dans la plage 40-220 BPM
 *  5. Retourne le pic dominant
 *
 * Requiert ffmpeg dans le PATH (même prérequis que fpcalc pour AcoustID).
 */
public class BpmDetector {

    private static final int SAMPLE_RATE  = 8000;      // Hz — bas pour vitesse
    private static final int FRAME_SIZE   = 186;        // ~23ms @ 8kHz
    private static final int MAX_DURATION = 60;         // secondes analysées
    private static final int BPM_MIN      = 40;
    private static final int BPM_MAX      = 220;

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Calcule le BPM d'un fichier audio.
     * @return BPM arrondi, ou -1 si échec / ffmpeg indisponible.
     */
    public int detect(String filePath) {
        // Limite l'accès disque concurrent si ce fichier vit sur un disque mécanique détecté —
        // voir DiskIoThrottle pour le pourquoi (mesuré en direct : disques à 80-98% d'utilisation
        // pendant que le CPU restait très majoritairement idle).
        java.util.concurrent.Semaphore gate = DiskIoThrottle.acquireFor(new File(filePath));
        try {
            float[] energy = extractEnergyEnvelope(filePath);
            if (energy == null || energy.length < 100) return -1;
            return computeBpmByAutocorrelation(energy, SAMPLE_RATE / FRAME_SIZE);
        } finally {
            DiskIoThrottle.release(gate);
        }
    }

    // ── Décodage PCM via ffmpeg ───────────────────────────────────────────────

    private float[] extractEnergyEnvelope(String filePath) {
        String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
        ProcessBuilder pb = new ProcessBuilder(
            ffmpeg, "-hide_banner", "-loglevel", "error",
            "-i", filePath,
            "-ar", String.valueOf(SAMPLE_RATE),
            "-ac", "1",
            "-t", String.valueOf(MAX_DURATION),
            "-f", "s16le",
            "pipe:1"
        );
        pb.redirectErrorStream(false);

        byte[] raw = ProcessUtils.readWithTimeout(pb, 30);
        if (raw == null) {
            System.out.println("[OT] BPM timeout sur " + new File(filePath).getName() + " — ffmpeg tué");
            return null;
        }
        if (raw.length < FRAME_SIZE * 2) return null;
        return computeEnergy(raw);
    }

    private float[] computeEnergy(byte[] pcm) {
        int nFrames = pcm.length / (FRAME_SIZE * 2);
        float[] energy = new float[nFrames];
        ByteBuffer buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);

        for (int f = 0; f < nFrames; f++) {
            double sum = 0;
            for (int s = 0; s < FRAME_SIZE; s++) {
                short sample = buf.getShort();
                sum += (double) sample * sample;
            }
            energy[f] = (float) Math.sqrt(sum / FRAME_SIZE);
        }

        return lowPassFilter(energy);
    }

    // Filtre passe-bas simple pour lisser l'enveloppe
    private float[] lowPassFilter(float[] x) {
        float[] y = new float[x.length];
        float alpha = 0.3f;
        y[0] = x[0];
        for (int i = 1; i < x.length; i++) {
            y[i] = alpha * x[i] + (1 - alpha) * y[i - 1];
        }
        return y;
    }

    // ── Autocorrélation ───────────────────────────────────────────────────────

    /**
     * @param fps frames per second = sampleRate / frameSize
     */
    private int computeBpmByAutocorrelation(float[] energy, double fps) {
        // Plage de délais correspondant à 40-220 BPM
        int lagMin = (int) (60.0 * fps / BPM_MAX);   // petit lag = BPM élevé
        int lagMax = (int) (60.0 * fps / BPM_MIN);   // grand lag = BPM faible
        lagMax = Math.min(lagMax, energy.length / 2);

        if (lagMin >= lagMax) return -1;

        double bestCorr = -1;
        int bestLag     = lagMin;

        for (int lag = lagMin; lag <= lagMax; lag++) {
            double corr = 0;
            int n = energy.length - lag;
            for (int i = 0; i < n; i++) {
                corr += energy[i] * energy[i + lag];
            }
            corr /= n;

            if (corr > bestCorr) {
                bestCorr = corr;
                bestLag  = lag;
            }
        }

        int rawBpm = (int) Math.round(60.0 * fps / bestLag);

        // Doublage/halvage pour rester dans [60-180]
        while (rawBpm < 60 && rawBpm > 0)  rawBpm *= 2;
        while (rawBpm > 180)               rawBpm /= 2;

        return rawBpm;
    }

    // ── Vérification disponibilité ────────────────────────────────────────────

    public static boolean isAvailable() {
        String ffmpeg = Config.get().str("audio.ffmpeg_path", "ffmpeg");
        try {
            Process p = new ProcessBuilder(ffmpeg, "-version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
