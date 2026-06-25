package com.opentagger;

import java.io.OutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Utilitaire pour exécuter des processus externes avec timeout.
 * Évite les blocages infinis sur readAllBytes() si ffmpeg/fpcalc ne termine pas.
 */
public final class ProcessUtils {

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
