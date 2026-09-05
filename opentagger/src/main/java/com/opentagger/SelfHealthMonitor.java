package com.opentagger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.opentagger.ui.WorkerHub;

/**
 * Surveillance mémoire de l'appli elle-même, avec redémarrage AUTOMATIQUE et PROPRE avant
 * qu'earlyoom ne tue le processus sans préavis — demande utilisateur (2026-08-18) après une fuite
 * mémoire native (catégorie JVM NMT "Other") qui a résisté à 4 correctifs différents (pool de
 * connexions SQLite, cache scan_cache partagé, MALLOC_ARENA_MAX) sans qu'aucun ne règle la cause
 * racine. Plutôt que de continuer à deviner, transforme un crash imprévisible — qui avait déjà
 * emporté 2 fenêtres VSCode et un processus node avec lui la nuit précédente — en un redémarrage
 * contrôlé : rien à perdre, l'auto-save écrit déjà au fil de l'eau.
 *
 * Lecture directe de /proc/self/status et /proc/meminfo — toujours accessible au PROCESSUS
 * LUI-MÊME même quand des outils externes (jcmd depuis un autre processus) échouent pour des
 * raisons d'espace de noms (constaté en direct cette nuit sur plusieurs PID).
 */
public final class SelfHealthMonitor {

    private SelfHealthMonitor() {}

    // Seuils volontairement bien EN DESSOUS de la zone où earlyoom intervenait en pratique (RSS
    // observé 10-12 Go avant kill, mémoire système disponible sous ~10% déclenchait son SIGTERM)
    // — marge large pour que le redémarrage propre ait toujours le temps de se terminer avant que
    // le système ne devienne critique.
    // Relevé de 8 à 12 Go (2026-08-18) : avec une bibliothèque à 812k entrées scan_cache, chaque
    // redémarrage coûte ~30 min de rechargement complet (voir MetadataCache.loadScanCacheMap()) —
    // à 8 Go, la fuite (toujours pas résolue au niveau code, voir le long historique d'essais dans
    // ce fichier) redéclenchait un nouveau redémarrage après seulement quelques minutes de travail
    // réel, laissant l'appli quasi bloquée en boucle de rechargement sans jamais atteindre une
    // sauvegarde. earlyoom tuait historiquement vers 10-12 Go de RSS — 12 Go laisse encore une
    // marge avant ce point, plus fine qu'avant mais jugée acceptable par l'utilisateur en échange
    // de nettement plus de temps de taguage réel entre deux redémarrages.
    private static final long   RSS_LIMIT_KB          = 12L * 1024 * 1024; // 12 Go
    private static final double MIN_AVAILABLE_PERCENT = 15.0;             // système
    // Jamais avant ce délai après démarrage — filet de sécurité contre une boucle de redémarrages
    // rapides si quelque chose cause un RSS élevé dès le lancement (ne devrait jamais arriver en
    // pratique, mais un correctif qui se retournerait en boucle infinie serait pire que le
    // problème d'origine).
    private static final long   MIN_UPTIME_MS = 5 * 60_000;

    private static final long START_TIME_MS = System.currentTimeMillis();
    private static volatile boolean restarting = false;

    public static void start() {
        javax.swing.Timer t = new javax.swing.Timer(60_000, e -> check());
        t.setRepeats(true);
        t.start();
    }

    private static void check() {
        if (restarting) return;
        if (System.currentTimeMillis() - START_TIME_MS < MIN_UPTIME_MS) return;
        try {
            long rssKb = readOwnRssKb();
            double availPct = readSystemAvailablePercent();
            boolean rssTooHigh = rssKb > 0 && rssKb > RSS_LIMIT_KB;
            boolean systemLow  = availPct >= 0 && availPct < MIN_AVAILABLE_PERCENT;
            if (rssTooHigh || systemLow) {
                String reason = rssTooHigh
                        ? String.format("RSS appli = %.1f Go (limite %.1f Go)",
                                rssKb / 1024.0 / 1024.0, RSS_LIMIT_KB / 1024.0 / 1024.0)
                        : String.format("mémoire système disponible = %.1f%% (seuil %.1f%%)",
                                availPct, MIN_AVAILABLE_PERCENT);
                System.out.println("[OT] ⚠ SelfHealthMonitor : redémarrage préventif — " + reason);
                restart();
            }
        } catch (Exception ignored) {
            // Ne doit jamais perturber l'appli — un souci de lecture /proc n'est pas assez grave
            // pour justifier une action, on retentera au prochain cycle (60s).
        }
    }

    private static long readOwnRssKb() throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8)) {
            if (line.startsWith("VmRSS:")) {
                Matcher m = Pattern.compile("(\\d+)").matcher(line);
                if (m.find()) return Long.parseLong(m.group(1));
            }
        }
        return -1;
    }

    private static double readSystemAvailablePercent() throws IOException {
        long total = -1, available = -1;
        for (String line : Files.readAllLines(Path.of("/proc/meminfo"), StandardCharsets.UTF_8)) {
            if (line.startsWith("MemTotal:"))         total     = extractKb(line);
            else if (line.startsWith("MemAvailable:")) available = extractKb(line);
        }
        if (total <= 0 || available < 0) return -1;
        return 100.0 * available / total;
    }

    private static long extractKb(String line) {
        Matcher m = Pattern.compile("(\\d+)").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /**
     * Relance un processus identique (mêmes arguments JVM, même jar ; variables d'environnement
     * comme MALLOC_ARENA_MAX héritées automatiquement par ProcessBuilder, pas besoin de les
     * répéter) AVANT de quitter le processus actuel — jamais l'inverse, pour ne jamais laisser 0
     * instance active même brièvement. Annule proprement les tâches en cours d'abord (voir
     * WorkerHub) plutôt que de couper la JVM en plein milieu d'une écriture de fichier.
     */
    private static void restart() {
        restarting = true;
        try {
            try { WorkerHub.get().cancelAll(); } catch (Exception ignored) {}
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {} // laisser les écritures en cours se terminer

            List<String> cmd = buildRelaunchCommand();
            System.out.println("[OT] Relance : " + String.join(" ", cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(nextLogFile());
            pb.redirectErrorStream(true);
            pb.start();
        } catch (Exception e) {
            System.out.println("[OT] ⚠ SelfHealthMonitor : échec de la relance, l'appli continue "
                    + "sans redémarrage (mieux qu'un arrêt sans successeur) : " + e);
            restarting = false;
            return;
        }
        // Laisser le nouveau process le temps de démarrer avant de couper celui-ci — évite une
        // fenêtre où plus aucun processus n'est actif pour reprendre le taguage en cours.
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
        System.exit(0);
    }

    private static List<String> buildRelaunchCommand() throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments()); // -Xmx6g, -Xms512m…
        cmd.add("-jar");
        cmd.add(currentJarPath());
        return cmd;
    }

    private static String currentJarPath() throws Exception {
        return new File(SelfHealthMonitor.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getAbsolutePath();
    }

    private static File nextLogFile() {
        // Nom distinct (horodatage) — ne doit jamais écraser le journal du process qui vient de
        // se terminer, utile pour repérer après coup qu'un redémarrage automatique a eu lieu.
        return new File("restart-auto-" + System.currentTimeMillis() + ".log");
    }
}
