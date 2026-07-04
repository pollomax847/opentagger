package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.concurrent.Semaphore;

/**
 * Calcule l'empreinte acoustique d'un fichier via fpcalc — partagé entre AcoustIdClient
 * (identification) et AcoustIdSubmitter (soumission), qui dupliquaient la même logique.
 */
public final class Fingerprinter {

    private Fingerprinter() {}

    public record Result(String fingerprint, String duration) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Limite le nombre d'invocations fpcalc CONCURRENTES (mode batch multi-thread) au réglage
    // "Threads fpcalc" des Préférences.
    private static volatile Semaphore gate;
    private static volatile int gatePermits = -1;

    private static Semaphore gate() {
        int wanted = Math.max(1, Config.get().fpcalcThreads());
        if (gate == null || gatePermits != wanted) {
            synchronized (Fingerprinter.class) {
                if (gate == null || gatePermits != wanted) {
                    gate = new Semaphore(wanted);
                    gatePermits = wanted;
                }
            }
        }
        return gate;
    }

    /** Calcule l'empreinte + durée (secondes) via fpcalc, ou null si indisponible/échec. */
    public static Result compute(File fichier) throws Exception {
        String fpcalc = FpcalcInstaller.resolve();
        if (fpcalc == null) throw new Exception("fpcalc introuvable — installez-le via Préférences → Audio");

        // Comme Picard : -json pour parsing fiable, -length 120 pour analyser seulement 2 min (plus rapide)
        ProcessBuilder pb = new ProcessBuilder(fpcalc, "-json", "-length", "120", fichier.getAbsolutePath())
                .redirectErrorStream(false);
        String output;
        Semaphore permit = gate();
        permit.acquire();
        try {
            output = ProcessUtils.readStringWithTimeout(pb, 60);
        } finally {
            permit.release();
        }
        if (output == null || output.isBlank())
            throw new Exception("fpcalc timeout ou sortie vide pour " + fichier.getName());

        JsonNode json = MAPPER.readTree(output);
        String fingerprint = json.path("fingerprint").asText("");
        int duration = (int) json.path("duration").asDouble(0);
        if (fingerprint.isBlank() || duration == 0)
            throw new Exception("fpcalc : fingerprint ou durée manquant pour " + fichier.getName());
        return new Result(fingerprint, String.valueOf(duration));
    }
}
