package com.opentagger;

import java.io.OutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Utilitaire pour exécuter des processus externes avec timeout.
 * Évite les blocages infinis sur readAllBytes() si ffmpeg/fpcalc ne termine pas.
 */
public final class ProcessUtils {

    private static final Logger LOG = Logger.getLogger(ProcessUtils.class.getName());
    private static final int DEFAULT_TIMEOUT_SEC = 45;

    private ProcessUtils() {}

    /**
     * Lance le processus, lit stdout, tue le processus après timeoutSec secondes.
     * @return les octets lus, ou null si timeout ou erreur
     */
    public static byte[] readWithTimeout(ProcessBuilder pb, int timeoutSec) {
        Process proc = null;
        try {
            proc = pb.start();
            final Process p = proc;

            // Drainer stderr dans un thread virtuel pour ne pas bloquer le pipe
            Thread.ofVirtual().start(() -> {
                try { p.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                catch (Exception ignored) {}
            });

            // Lire stdout dans un thread virtuel
            AtomicReference<byte[]> result = new AtomicReference<>(new byte[0]);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            Future<?> reader = executor.submit(() -> {
                try { result.set(p.getInputStream().readAllBytes()); }
                catch (Exception ignored) {}
            });
            executor.shutdown();

            boolean done = proc.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                reader.cancel(true);
                return null;
            }
            // Après la fin du processus, le flux est fermé → readAllBytes() se termine rapidement.
            // On attend au maximum timeoutSec supplémentaires pour la lecture (cas des gros fichiers).
            reader.get(timeoutSec, TimeUnit.SECONDS);

            // Code de sortie jamais vérifié avant ce correctif : un ffprobe/fpcalc qui plante en
            // cours de décodage (fichier corrompu) pouvait quand même avoir écrit une sortie
            // partielle sur stdout avant de mourir — impossible alors de distinguer "succès avec
            // sortie courte" de "échec après sortie tronquée" (ex. BpmDetector calculant un BPM
            // à partir d'un fragment audio sans le savoir). Un code non nul est traité comme un
            // échec, exactement comme un timeout — tous les appelants gèrent déjà null ainsi.
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                LOG.fine(() -> "Processus terminé avec code " + exitCode + " : " + pb.command());
                return null;
            }
            return result.get();

        } catch (Exception e) {
            if (proc != null) proc.destroyForcibly();
            return null;
        }
    }

    public static byte[] readWithTimeout(ProcessBuilder pb) {
        return readWithTimeout(pb, DEFAULT_TIMEOUT_SEC);
    }

    /**
     * Variante retournant un String UTF-8, ou "" si timeout.
     */
    public static String readStringWithTimeout(ProcessBuilder pb, int timeoutSec) {
        byte[] bytes = readWithTimeout(pb, timeoutSec);
        return bytes == null ? "" : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
    }
}
