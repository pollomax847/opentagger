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
 * Détection automatique, Linux (via /proc/mounts) et Windows (via PowerShell : lettre de lecteur → disque physique → type de
 * média, lu une seule fois) ; no-op silencieux ailleurs, ou si la détection échoue pour n'importe quelle raison — ne doit jamais
 * bloquer un run. Sous Windows, un disque dont le type est « SSD » n'est jamais freiné ; tout autre (HDD, USB, « non spécifié ») l'est.
 * Linux : résout le point de montage du
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
    // Disques dont CE thread tient déjà le permis : un thread qui a pris le permis d'un disque (ex. l'enregistrement d'un fichier) puis
    // relit ce même disque plus bas (empreinte pour comparer deux fichiers) ne doit pas se bloquer lui-même.
    private static final ThreadLocal<java.util.Set<String>> HELD = ThreadLocal.withInitial(java.util.HashSet::new);
    private static final Map<Semaphore, String> DEVICE_OF_GATE = new ConcurrentHashMap<>();
    // Lecteur Windows ("E") → "win-disk-4" si mécanique, "" sinon. Chargé une fois.
    private static volatile Map<Character, String> windowsDrives;

    /**
     * Acquiert un permis avant un accès disque local coûteux (fpcalc/ffmpeg) sur ce fichier — à
     * libérer avec {@link #release}. Retourne null (rien à libérer, aucune limite appliquée) si le
     * disque n'est pas détecté comme mécanique, ou si la détection échoue pour quelque raison que
     * ce soit (hors Linux, montage réseau, /proc ou /sys indisponibles…) : cette méthode ne doit
     * JAMAIS faire échouer ou bloquer indéfiniment un appelant à cause d'un problème de détection.
     */
    public static Semaphore acquireFor(File fichier) {
        try {
            return acquireForDevice(rotationalDeviceOf(fichier));
        } catch (Exception e) {
            return null; // jamais bloquant : pas de permis à libérer, l'appelant continue tel quel
        }
    }

    /** Cœur de {@link #acquireFor}, séparé pour être testable sans disque réel. {@code null} = pas de limite. */
    static Semaphore acquireForDevice(String device) throws InterruptedException {
        if (device == null) return null;
        if (HELD.get().contains(device)) return null; // déjà tenu par ce thread : pas de second permis
        Semaphore gate = GATES.computeIfAbsent(device, d -> new Semaphore(ROTATIONAL_PERMITS));
        gate.acquire();
        DEVICE_OF_GATE.put(gate, device);
        HELD.get().add(device);
        return gate;
    }

    public static void release(Semaphore gate) {
        if (gate == null) return;
        String device = DEVICE_OF_GATE.get(gate);
        if (device != null) HELD.get().remove(device);
        gate.release();
    }

    /** Retourne le nom du disque physique de base (ex. "sdf") si ce fichier vit sur un disque
     *  mécanique détecté, null sinon (SSD/NVMe, montage réseau, ou détection impossible). */
    private static String rotationalDeviceOf(File fichier) throws Exception {
        Path mountsFile = Paths.get("/proc/mounts");
        if (!Files.isReadable(mountsFile)) return windowsDeviceOf(fichier); // pas Linux : Windows, sinon rien

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

    // ── Windows ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private static String windowsDeviceOf(File fichier) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return null;
        if (!com.opentagger.Config.get().bool("disk.throttle_windows", true)) return null;
        String p = fichier.getAbsolutePath();
        if (p.length() < 2 || p.charAt(1) != ':') return null; // chemin réseau (UNC) : pas de limite
        Map<Character, String> drives = windowsDrives;
        if (drives == null) { drives = loadWindowsDrives(); windowsDrives = drives; }
        String dev = drives.get(Character.toUpperCase(p.charAt(0)));
        return dev == null || dev.isEmpty() ? null : dev;
    }

    private static synchronized Map<Character, String> loadWindowsDrives() {
        if (windowsDrives != null) return windowsDrives;
        try {
            // Aucun guillemet double dans le script : Windows les perd en passant la ligne de commande à PowerShell.
            String script = "$d=Get-PhysicalDisk | Select-Object DeviceId,MediaType;"
                    + "Get-Partition | Where-Object { $_.DriveLetter } | ForEach-Object { $n=[string]$_.DiskNumber;"
                    + "$m=($d | Where-Object { $_.DeviceId -eq $n }).MediaType;($_.DriveLetter,$n,$m) -join ';' }";
            Process proc = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true).start();
            String out = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { proc.destroyForcibly(); return Map.of(); }
            return parseWindowsDrives(out);
        } catch (Exception e) {
            return Map.of(); // détection impossible : aucune limite, comme avant
        }
    }

    /** Lignes « lettre;numéro de disque;type de média » → lecteur → identifiant de disque mécanique (les SSD sont omis). */
    static Map<Character, String> parseWindowsDrives(String out) {
        Map<Character, String> map = new ConcurrentHashMap<>();
        for (String line : out.split("\\r?\\n")) {
            String[] f = line.trim().split(";", -1);
            if (f.length < 3 || f[0].length() != 1) continue;
            String media = f[2].trim().toUpperCase(java.util.Locale.ROOT);
            if (media.equals("SSD") || media.equals("SCM")) continue;
            map.put(Character.toUpperCase(f[0].charAt(0)), "win-disk-" + f[1].trim());
        }
        return map;
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
