package com.opentagger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Limite les accès disque locaux coûteux (fpcalc, ffmpeg BPM/ReplayGain) PAR DISQUE PHYSIQUE,
 * plutôt que par un seul réglage global de threads.
 *
 * Mesuré en direct sur une vraie session de taguage (iostat pendant un run réel, bibliothèque de
 * plusieurs To sur disques mécaniques USB) : les disques tournaient à 80-98% d'utilisation pendant
 * que le CPU restait à 60%+ idle — la vraie limite n'était pas le nombre de threads Java mais le
 * disque lui-même. Sur un disque mécanique (HDD), plusieurs threads lisant des fichiers DIFFÉRENTS
 * en même temps se traduisent par des déplacements constants de la tête de lecture (accès
 * aléatoire) — nettement plus lent qu'un seul thread à la fois en accès quasi séquentiel. Sur
 * SSD/NVMe, cette notion n'existe pas : l'accès concurrent ne coûte rien de comparable, donc aucun
 * frein n'est appliqué.
 *
 * Détection automatique, Linux uniquement (no-op silencieux ailleurs, ou si la détection échoue
 * pour n'importe quelle raison — ne doit jamais bloquer un run) : résout le point de montage du
 * fichier via /proc/mounts, remonte au disque de base, lit /sys/block/<disque>/queue/rotational.
 * Mis en cache par point de montage — jamais recalculé par fichier, ce qui coûterait aussi cher
 * que ce qu'on essaie d'éviter.
 */
public final class DiskIoThrottle {

    private DiskIoThrottle() {}

    // Volontairement bas : une tête de lecture mécanique est unique, la paralléliser au-delà
    // n'ajoute que des déplacements. Si besoin un jour (disque 7200rpm avec gros cache), ça peut
    // devenir un réglage utilisateur — pas nécessaire pour l'instant, personne n'a encore eu besoin
    // d'affiner au-delà de ce constat simple.
    private static final int ROTATIONAL_PERMITS = 1;

    private static final Map<String, Semaphore> GATES = new ConcurrentHashMap<>();
    // Point de montage → disque mécanique de base ("sdf"), ou null si non-mécanique/inconnu.
    private static final Map<String, String> MOUNT_DEVICE_CACHE = new ConcurrentHashMap<>();

    /**
     * Acquiert un permis avant un accès disque local coûteux (fpcalc/ffmpeg) sur ce fichier — à
     * libérer avec {@link #release}. Retourne null (rien à libérer, aucune limite appliquée) si le
     * disque n'est pas détecté comme mécanique, ou si la détection échoue pour quelque raison que
     * ce soit (hors Linux, montage réseau, /proc ou /sys indisponibles…) : cette méthode ne doit
     * JAMAIS faire échouer ou bloquer indéfiniment un appelant à cause d'un problème de détection.
     */
    public static Semaphore acquireFor(File fichier) {
        try {
            String device = rotationalDeviceOf(fichier);
            if (device == null) return null;
            Semaphore gate = GATES.computeIfAbsent(device, d -> new Semaphore(ROTATIONAL_PERMITS));
            gate.acquire();
            return gate;
        } catch (Exception e) {
            return null; // jamais bloquant : pas de permis à libérer, l'appelant continue tel quel
        }
    }

    public static void release(Semaphore gate) {
        if (gate != null) gate.release();
    }

    /** Retourne le nom du disque physique de base (ex. "sdf") si ce fichier vit sur un disque
     *  mécanique détecté, null sinon (SSD/NVMe, montage réseau, ou détection impossible). */
    private static String rotationalDeviceOf(File fichier) throws Exception {
        Path mountsFile = Paths.get("/proc/mounts");
        if (!Files.isReadable(mountsFile)) return null; // pas Linux, ou /proc inaccessible

        String absPath = fichier.getAbsolutePath();
        String bestMountPoint = null, bestSource = null;
        for (String line : Files.readAllLines(mountsFile)) {
            String[] parts = line.split("\\s+");
            if (parts.length < 3) continue;
            String source = parts[0], point = parts[1];
            boolean matches = absPath.equals(point)
                    || absPath.startsWith(point.endsWith("/") ? point : point + "/");
            if (matches && (bestMountPoint == null || point.length() > bestMountPoint.length())) {
                bestMountPoint = point;
                bestSource     = source;
            }
        }
        if (bestSource == null || !bestSource.startsWith("/dev/")) return null; // réseau, tmpfs...

        String finalSource = bestSource;
        // "" plutôt que null en valeur de cache : ConcurrentHashMap.computeIfAbsent() n'accepte pas
        // de valeur null en retour du mapping function — reconverti en null juste après, avant de
        // remonter à l'appelant (sinon TOUS les fichiers non-mécaniques finiraient regroupés sous
        // la même clé de gate "" et se sérialiseraient entre eux, exactement ce qu'on veut éviter).
        String cached = MOUNT_DEVICE_CACHE.computeIfAbsent(bestMountPoint,
                mp -> isRotational(finalSource) ? baseDeviceName(finalSource) : "");
        return cached.isEmpty() ? null : cached;
    }

    private static boolean isRotational(String devPath) {
        try {
            Path rota = Paths.get("/sys/block/" + baseDeviceName(devPath) + "/queue/rotational");
            return Files.isReadable(rota) && "1".equals(Files.readString(rota).trim());
        } catch (Exception e) {
            return false;
        }
    }

    /** "/dev/sdf2" → "sdf", "/dev/nvme0n1p1" → "nvme0n1". */
    private static String baseDeviceName(String devPath) {
        String name = devPath.replaceFirst("^/dev/", "");
        name = name.replaceFirst("(nvme\\d+n\\d+)p\\d+$", "$1");
        name = name.replaceFirst("^([a-z]+)\\d+$", "$1");
        return name;
    }
}
