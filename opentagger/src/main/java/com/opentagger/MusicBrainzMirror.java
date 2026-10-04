package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Assistant « miroir MusicBrainz local » : prépare et pilote le projet officiel {@code musicbrainz-docker}
 * (github.com/metabrainz/musicbrainz-docker), puis fait pointer OpenTagger dessus. Un miroir supprime la limite de
 * 1 requête / 1,1 s de l'API publique : l'identification n'est plus limitée que par le processeur et le disque.
 *
 * <p>Ce que cette classe fait ELLE-MÊME (testé) : vérifications préalables, génération des commandes, téléchargement du
 * projet (sans git), bascule des réglages OpenTagger (avec retour arrière), test du serveur.
 * Ce qu'elle ne fait qu'ORDONNER à Docker (non testé ici — il faut Docker et 100 à 350 Go) : construction des images,
 * import de la base, chargement des index de recherche.
 *
 * <p>Les commandes sont celles du README officiel, mot pour mot. Sous Windows elles passent par WSL (les scripts
 * {@code admin/*} sont en bash, et le projet précise que Windows n'est pas documenté).
 */
public final class MusicBrainzMirror {

    private MusicBrainzMirror() {}

    public static final String REPO_ZIP    = "https://github.com/metabrainz/musicbrainz-docker/archive/refs/heads/master.zip";
    public static final String DEFAULT_URL = "http://localhost:5000/ws/2";

    /** Exigences annoncées par le README officiel. */
    public static final int DISK_GB_WITH_SEARCH = 350, DISK_GB_NO_SEARCH = 100, RAM_GB_WITH_SEARCH = 16, RAM_GB_NO_SEARCH = 4;

    /** MBID de Queen, artiste stable : sert de requête de test. */
    private static final String QUEEN = "0383dadf-2a4e-4d10-a46a-e9e041da8eb3";

    public record Check(String id, boolean ok, String label, String detail) {}

    /** Une étape du README : commandes bash exécutées dans le dossier du projet. {@code long} : prend des heures. */
    public record Step(String id, String title, List<String> commands, boolean longRunning) {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ── Étapes (README officiel, sans rien inventer) ─────────────────────────────────────────────────

    public static List<Step> steps(boolean withSearchIndexes) { return steps(withSearchIndexes, false); }

    /** Réponse à la question du script de téléchargement de MetaBrainz (« usage commercial ? ») puis une touche pour continuer.
     *  Le script POSE CETTE QUESTION sur l'entrée standard : sans réponse il attend indéfiniment (constaté : 3 h sans un octet
     *  téléchargé). La réponse est celle de l'UTILISATEUR (voir MusicBrainzMirrorDialog), jamais décidée par le programme. */
    static String answers(boolean commercialUse) { return "printf '" + (commercialUse ? "y" : "n") + "\\n\\n' | "; }

    /** Script lancé AVANT l'import : une tentative interrompue (fenêtre fermée en pleine importation) laisse des schémas vides
     *  dans PostgreSQL, et l'import suivant échoue alors sur « schema already exists ». Si le schéma existe mais que la table
     *  des artistes est vide ou absente, seul le volume PostgreSQL est recréé (jamais le dump téléchargé). Une base déjà
     *  remplie n'est pas touchée, ni rien tant qu'un autre import tourne encore (code 1 + message). Sinon code 0. */
    static String prepDbScript() {
        // Un import déjà actif (conteneur « musicbrainz-run ») continue en tâche de fond même après la fermeture de la fenêtre :
        // ses tables sont vides pendant des dizaines de minutes, ce n'est PAS un reste d'interruption. Ne rien toucher.
        return "if [ -n \"$(docker ps -q --filter name=musicbrainz-run)\" ]; then\n"
             + "  echo \"Un import MusicBrainz est déjà en cours dans Docker (il continue même fenêtre fermée) : attendez sa fin, ou arrêtez-le avant de relancer. Rien n'a été modifié.\"\n"
             + "  exit 1\n"
             + "fi\n"
             + "Q() { docker compose exec -T db psql -U musicbrainz -d musicbrainz_db -tAc \"$1\" 2>/dev/null | tr -d '[:space:]'; }\n"
             + "docker compose up -d db >/dev/null 2>&1\n"
             + "i=0; while [ \"$(Q 'select 1')\" != \"1\" ] && [ $i -lt 30 ]; do i=$((i+1)); sleep 2; done\n"
             + "schema=$(Q \"select count(*) from pg_namespace where nspname='musicbrainz'\")\n"
             + "rows=$(Q 'select count(*) from musicbrainz.artist')\n"
             + "if [ \"$schema\" = \"1\" ] && { [ -z \"$rows\" ] || [ \"$rows\" = \"0\" ]; }; then\n"
             + "  echo \"Base incomplète (reste d'une tentative interrompue) : volume PostgreSQL recréé, dump conservé\"\n"
             + "  docker compose down\n"
             + "  docker volume rm \"$(basename \"$PWD\" | tr 'A-Z' 'a-z')_pgdata\"\n"
             + "  docker compose up -d db\n"
             + "else\n"
             + "  echo \"Base saine ou vierge : rien à réinitialiser\"\n"
             + "fi\n"
             + "exit 0\n";
    }

    /** Le script est passé en base64 : aucune citation à protéger entre Windows, wsl.exe et bash. */
    static String prepDbCommand() {
        String b64 = java.util.Base64.getEncoder().encodeToString(prepDbScript().getBytes(StandardCharsets.UTF_8));
        return "echo " + b64 + " | base64 -d | sh";
    }

    /** Étapes du README officiel. {@code commercialUse} : déclaration de l'utilisateur à MetaBrainz. */
    public static List<Step> steps(boolean withSearchIndexes, boolean commercialUse) {
        String ans = answers(commercialUse);
        List<Step> s = new ArrayList<>();
        s.add(new Step("build", "Construire les images Docker", List.of("docker compose build"), false));
        s.add(new Step("createdb", "Télécharger et importer la base MusicBrainz",
                List.of(prepDbCommand(), ans + "docker compose run --rm -T musicbrainz createdb.sh -fetch"), true));
        s.add(new Step("up", "Démarrer le miroir", List.of("docker compose up -d"), false));
        if (withSearchIndexes) {
            s.add(new Step("search", "Télécharger et charger les index de recherche",
                    List.of("docker compose up -d musicbrainz search",
                            ans + "docker compose exec -T search fetch-backup-archives",
                            "docker compose exec -T search load-backup-archives"), true));
        }
        s.add(new Step("status", "Voir l'état des conteneurs", List.of("docker compose ps"), false));
        return s;
    }
    // ── Exécution de commandes (Windows : via WSL) ───────────────────────────────────────────────────

    /** {@code D:\dossier\x} → {@code /mnt/d/dossier/x} (montage WSL par défaut). */
    public static String toWslPath(String windowsPath) {
        String p = windowsPath.replace('\\', '/');
        if (p.length() >= 2 && p.charAt(1) == ':') p = "/mnt/" + Character.toLowerCase(p.charAt(0)) + p.substring(2);
        return p;
    }

    private static String shQuote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** Commande complète à lancer pour exécuter {@code bashCommand} dans {@code projectDir}. */
    public static List<String> shellCommand(String bashCommand, Path projectDir) {
        String dir = isWindows() ? toWslPath(projectDir.toAbsolutePath().toString()) : projectDir.toAbsolutePath().toString();
        String line = "cd " + shQuote(dir) + " && " + bashCommand;
        return isWindows() ? List.of("wsl.exe", "bash", "-lc", line) : List.of("bash", "-lc", line);
    }

    /** Lance une commande et renvoie son code de sortie ; chaque ligne de sortie est transmise à {@code log}. */
    public static int run(String bashCommand, Path projectDir, Consumer<String> log, java.util.concurrent.atomic.AtomicReference<Process> holder)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(shellCommand(bashCommand, projectDir)).redirectErrorStream(true).start();
        // Entrée standard fermée : toute question interactive inattendue reçoit EOF et FAIT ÉCHOUER l'étape (message visible),
        // au lieu de la bloquer sans fin sans rien afficher. Les réponses prévues passent par un « printf … |» dans la commande elle-même.
        try { p.getOutputStream().close(); } catch (IOException ignored) {}
        if (holder != null) holder.set(p);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) if (log != null) log.accept(line.replace("\u0000", ""));
        }
        return p.waitFor();
    }

    private static String capture(List<String> cmd, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).replace("\u0000", "").trim();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            return p.exitValue() == 0 ? out : null;
        } catch (Exception e) { return null; }
    }

    /** Fichier/dossier où Docker écrit ses données (volumes de la base) : sous Windows le disque virtuel de Docker Desktop
     *  (par défaut sur C:, la plupart du temps trop petit pour un miroir) ; sous Linux {@code /var/lib/docker}. {@code null} si introuvable. */
    public static Path dockerDataLocation() {
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null) return null;
            Path vhdx = java.nio.file.Paths.get(local, "Docker", "wsl", "disk", "docker_data.vhdx");
            if (Files.exists(vhdx)) return vhdx;
            // disque déplacé par l'utilisateur : le chercher dans les réglages de Docker Desktop
            String roaming = System.getenv("APPDATA");
            if (roaming != null) {
                Path st = java.nio.file.Paths.get(roaming, "Docker", "settings-store.json");
                try {
                    if (Files.isRegularFile(st)) {
                        JsonNode n = new ObjectMapper().readTree(Files.readString(st, StandardCharsets.UTF_8));
                        for (String key : new String[]{ "CustomWslDistroDir", "DataFolder", "dataFolder" }) {
                            String v = JsonText.of(n.path(key), "");
                            if (!v.isBlank()) return java.nio.file.Paths.get(v);
                        }
                    }
                } catch (Exception ignored) { /* réglages illisibles : on retombe sur « introuvable » */ }
            }
            return Files.exists(java.nio.file.Paths.get(local, "Docker")) ? java.nio.file.Paths.get(local, "Docker") : null;
        }
        Path p = java.nio.file.Paths.get("/var/lib/docker");
        return Files.exists(p) ? p : java.nio.file.Paths.get("/var/lib");
    }

    /** Verdict du contrôle « disque des données Docker » (pur : testable). */
    static Check dockerDataCheck(String where, long freeGb, long needGb) {
        boolean ok = freeGb >= needGb;
        return new Check("dockerdata", ok, "Disque des données Docker",
                where + " — " + freeGb + " Go libres (" + needGb + " Go requis)"
              + (ok ? "" : isWindows()
                    ? " — TROP PETIT : déplacez le disque de Docker (Docker Desktop → Réglages → Resources → Advanced → Disk image location) vers un grand lecteur"
                    : " — placez les données Docker sur un plus grand disque (data-root dans /etc/docker/daemon.json)"));
    }
    // ── Vérifications préalables ─────────────────────────────────────────────────────────────────────

    /** Interprète la sortie de {@code df -k} / espace libre : renvoie des Go (arrondi bas). */
    static long gb(long bytes) { return bytes / (1024L * 1024 * 1024); }

    public static List<Check> checks(Path projectDir, boolean withSearchIndexes) {
        List<Check> out = new ArrayList<>();
        long needDisk = withSearchIndexes ? DISK_GB_WITH_SEARCH : DISK_GB_NO_SEARCH;
        long needRam  = withSearchIndexes ? RAM_GB_WITH_SEARCH : RAM_GB_NO_SEARCH;

        // Disque du dossier du projet
        long free = -1;
        try {
            Path probe = projectDir.toAbsolutePath();
            while (probe != null && !Files.exists(probe)) probe = probe.getParent();
            if (probe != null) free = gb(Files.getFileStore(probe).getUsableSpace());
        } catch (IOException ignored) {}
        out.add(new Check("disk", free >= needDisk, "Espace disque libre",
                free < 0 ? "indéterminé" : free + " Go libres (" + needDisk + " Go requis)"));
        // Disque où Docker écrira RÉELLEMENT les données (distinct du dossier du projet, qui ne contient que des fichiers de configuration)
        try {
            Path dd = dockerDataLocation();
            if (dd != null) {
                Path probe = dd.toAbsolutePath();
                while (probe != null && !Files.exists(probe)) probe = probe.getParent();
                if (probe != null) out.add(dockerDataCheck(probe.getRoot() != null ? probe.getRoot().toString() : probe.toString(), gb(Files.getFileStore(probe).getUsableSpace()), needDisk));
            }
        } catch (IOException ignored) {}

        // Mémoire et processeur
        long ram = -1;
        if (java.lang.management.ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
            ram = gb(os.getTotalMemorySize());
        out.add(new Check("ram", ram >= needRam, "Mémoire vive", ram < 0 ? "indéterminée" : ram + " Go (" + needRam + " Go conseillés)"));
        int threads = Runtime.getRuntime().availableProcessors();
        out.add(new Check("cpu", threads >= 2, "Processeur",   // 16 threads sont CONSEILLÉS avec l'index de recherche, pas exigés : ne pas afficher ✖ pour un poste qui fonctionne
                threads + " threads (" + (withSearchIndexes ? 16 : 2) + " conseillés — fonctionne avec moins, plus lentement)"));

        // Windows : WSL ; puis Docker (dans WSL sous Windows)
        if (isWindows()) {
            String wsl = capture(List.of("wsl.exe", "--status"), 15);
            out.add(new Check("wsl", wsl != null, "WSL (Windows Subsystem for Linux)",
                    wsl != null ? "présent" : "absent — requis sous Windows (les scripts du projet sont en bash)"));
        }
        List<String> dockerVersion = isWindows() ? List.of("wsl.exe", "bash", "-lc", "docker --version") : List.of("bash", "-lc", "docker --version");
        String docker = capture(dockerVersion, 20);
        out.add(new Check("docker", docker != null, "Docker", docker != null ? docker
                : isWindows() && dockerDesktopInstalled() ? "Docker Desktop est installé mais pas encore visible dans WSL — démarrez-le puis activez l'intégration WSL (Réglages → Resources → WSL integration)"
                : "introuvable" + (isWindows() ? " dans WSL — installez Docker Desktop et activez l'intégration WSL pour votre distribution" : " — installez docker et docker compose")));
        List<String> composeVersion = isWindows() ? List.of("wsl.exe", "bash", "-lc", "docker compose version") : List.of("bash", "-lc", "docker compose version");
        String compose = docker == null ? null : capture(composeVersion, 20);
        out.add(new Check("compose", compose != null, "Docker Compose (v2)", compose != null ? compose : "indisponible"));
        List<String> info = isWindows() ? List.of("wsl.exe", "bash", "-lc", "docker info --format '{{.ServerVersion}}'") : List.of("bash", "-lc", "docker info --format '{{.ServerVersion}}'");
        String daemon = docker == null ? null : capture(info, 30);
        out.add(new Check("daemon", daemon != null, "Service Docker démarré", daemon != null ? "version " + daemon : "arrêté ou inaccessible"));
        return out;
    }

    // ── Docker : état, installation, démarrage ───────────────────────────────────────────────────────

    /** Docker répond-il (service démarré) ? Sous Windows, la commande passe par WSL. */
    public static boolean dockerReady() {
        List<String> c = isWindows() ? List.of("wsl.exe", "bash", "-lc", "docker info --format '{{.ServerVersion}}'")
                                     : List.of("bash", "-lc", "docker info --format '{{.ServerVersion}}'");
        return capture(c, 30) != null;
    }

    public static boolean dockerInstalled() {
        List<String> c = isWindows() ? List.of("wsl.exe", "bash", "-lc", "docker --version") : List.of("bash", "-lc", "docker --version");
        return capture(c, 20) != null;
    }

    /** Docker Desktop est-il installé sous Windows (même si son service n'est pas démarré ni visible dans WSL) ? */
    public static boolean dockerDesktopInstalled() {
        if (!isWindows()) return false;
        for (String p : new String[]{ System.getenv("ProgramFiles") + "\\Docker\\Docker\\Docker Desktop.exe",
                                      System.getenv("LOCALAPPDATA") + "\\Programs\\Docker\\Docker\\Docker Desktop.exe" })
            if (p != null && !p.startsWith("null") && Files.isRegularFile(java.nio.file.Paths.get(p))) return true;
        return false;
    }

    /** Code de sortie de winget « aucune mise à jour applicable » (0x8A15002B) : le paquet est DÉJÀ installé — ce n'est pas un échec. */
    public static boolean wingetAlreadyInstalled(int exitCode) { return exitCode == -1978335189; }
    public static boolean wingetAvailable() {
        return isWindows() && capture(List.of("winget.exe", "--version"), 15) != null;
    }

    /** Installation de Docker Desktop par winget (Windows) — demande l'autorisation administrateur (fenêtre Windows). */
    public static List<String> dockerInstallCommand() {
        return List.of("winget.exe", "install", "-e", "--id", "Docker.DockerDesktop",
                "--accept-source-agreements", "--accept-package-agreements");
    }

    /** Lance Docker Desktop (Windows) puis attend jusqu'à {@code timeoutSec} que le service réponde. */
    public static boolean startDockerDesktop(int timeoutSec, Consumer<String> log) throws InterruptedException {
        if (!isWindows()) return dockerReady();
        for (String p : new String[]{ System.getenv("ProgramFiles") + "\\Docker\\Docker\\Docker Desktop.exe",
                                      System.getenv("LOCALAPPDATA") + "\\Programs\\Docker\\Docker\\Docker Desktop.exe" }) {
            if (p != null && Files.isRegularFile(java.nio.file.Paths.get(p))) {
                try { new ProcessBuilder(p).start(); } catch (IOException e) { return false; }
                break;
            }
        }
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < end) {
            if (dockerReady()) return true;
            if (log != null) log.accept("… en attente de Docker Desktop");
            Thread.sleep(5000);
        }
        return false;
    }

    // ── Progression (étapes terminées, pour reprendre où l'on s'est arrêté) ──────────────────────────

    public static java.util.Set<String> doneSteps(Settings s) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String id : s.get("musicbrainz.mirror.done", "").split(",")) if (!id.isBlank()) out.add(id.trim());
        return out;
    }

    public static void markDone(String id, Settings s) {
        java.util.Set<String> d = doneSteps(s);
        d.add(id);
        s.put("musicbrainz.mirror.done", String.join(",", d));
    }

    public static void clearDone(Settings s) { s.put("musicbrainz.mirror.done", ""); }

    /** Ce que le bouton unique doit faire maintenant, d'après l'état des prérequis. */
    public enum Next { INSTALL_WSL, INSTALL_DOCKER, START_DOCKER, INSTALL_MIRROR, REVERT }

    public static Next nextAction(boolean wslOk, boolean dockerInstalled, boolean dockerRunning, boolean active) {
        if (active) return Next.REVERT;
        if (isWindows() && !wslOk) return Next.INSTALL_WSL;
        if (!dockerInstalled) return Next.INSTALL_DOCKER;
        if (!dockerRunning) return Next.START_DOCKER;
        return Next.INSTALL_MIRROR;
    }
    // ── Téléchargement du projet (sans git) ──────────────────────────────────────────────────────────

    public static boolean projectPresent(Path dir) { return Files.isRegularFile(dir.resolve("docker-compose.yml")); }

    /** Télécharge l'archive du dépôt officiel et la décompresse dans {@code dir} (sans le dossier racine de l'archive). */
    public static void fetchProject(Path dir, Consumer<String> log) throws IOException, InterruptedException {
        if (projectPresent(dir)) { if (log != null) log.accept("Projet déjà présent : " + dir); return; }
        Files.createDirectories(dir);
        Path zip = Files.createTempFile("musicbrainz-docker", ".zip");
        try {
            if (log != null) log.accept("Téléchargement : " + REPO_ZIP);
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
            HttpResponse<Path> resp = http.send(HttpRequest.newBuilder(URI.create(REPO_ZIP)).timeout(Duration.ofMinutes(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(zip));
            if (resp.statusCode() != 200) throw new IOException("Téléchargement impossible (HTTP " + resp.statusCode() + ")");
            unzipStrippingRoot(zip, dir);
            if (!projectPresent(dir)) throw new IOException("Archive inattendue : docker-compose.yml introuvable dans " + dir);
            if (log != null) log.accept("Projet installé dans " + dir);
        } finally {
            try { Files.deleteIfExists(zip); } catch (IOException ignored) {}
        }
    }

    /** Décompresse en retirant le premier niveau (« musicbrainz-docker-master/ »). Refuse toute sortie du dossier cible. */
    static void unzipStrippingRoot(Path zip, Path dir) throws IOException {
        Path root = dir.toAbsolutePath().normalize();
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0) continue;                       // entrée à la racine : ignorée
                String rel = name.substring(slash + 1);
                if (rel.isEmpty()) continue;
                Path target = root.resolve(rel).normalize();
                if (!target.startsWith(root)) throw new IOException("Entrée d'archive refusée : " + name);
                if (e.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); Files.copy(z, target, StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    // ── Réglages d'OpenTagger ────────────────────────────────────────────────────────────────────────

    /** Accès aux réglages (les vrais, ou une copie en mémoire pour les tests : jamais le vrai fichier pendant « mvn test »). */
    public interface Settings {
        String get(String key, String def);
        void put(String key, String value);
    }

    public static Settings realSettings() {
        return new Settings() {
            @Override public String get(String k, String d) { return Config.get().str(k, d); }
            @Override public void put(String k, String v) { Config.get().set(k, v); }
        };
    }

    private static int toInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }

    /** Fait pointer OpenTagger sur le miroir (sans limite de débit, plus de threads) ; l'état précédent est conservé. */
    public static void activate(String serverUrl) { activate(serverUrl, realSettings()); }

    public static void activate(String serverUrl, Settings c) {
        if (c.get("musicbrainz.mirror.active", "").isEmpty()) {
            c.put("musicbrainz.mirror.prev_server", c.get("musicbrainz.server", ""));
            c.put("musicbrainz.mirror.prev_rate_ms", c.get("musicbrainz.rate_limit_ms", ""));
            c.put("musicbrainz.mirror.prev_threads", c.get("batch.threads", ""));
        }
        c.put("musicbrainz.server", serverUrl.trim());
        c.put("musicbrainz.rate_limit_ms", "0");
        int threads = Math.max(toInt(c.get("batch.threads", "6"), 6), Math.min(Runtime.getRuntime().availableProcessors(), 12));
        c.put("batch.threads", String.valueOf(threads));
        c.put("musicbrainz.mirror.active", "true");
    }

    /** Revient aux réglages d'avant {@link #activate}. */
    public static void deactivate() { deactivate(realSettings()); }

    public static void deactivate(Settings c) {
        String server = c.get("musicbrainz.mirror.prev_server", "");
        c.put("musicbrainz.server", server.isBlank() ? "https://musicbrainz.org/ws/2" : server);
        String rate = c.get("musicbrainz.mirror.prev_rate_ms", "");
        c.put("musicbrainz.rate_limit_ms", rate.isBlank() ? "1100" : rate);
        String th = c.get("musicbrainz.mirror.prev_threads", "");
        if (!th.isBlank()) c.put("batch.threads", th);
        c.put("musicbrainz.mirror.active", "");
    }

    public static boolean isActive() { return !Config.get().str("musicbrainz.mirror.active", "").isEmpty(); }
    // ── Test du serveur ──────────────────────────────────────────────────────────────────────────────

    public record TestResult(boolean lookupOk, boolean searchOk, long lookupMs, String message) {}

    /** Interroge le serveur : un lookup (artiste connu) et une recherche texte (nécessite l'index de recherche). */
    public static TestResult test(String serverUrl) {
        String base = serverUrl.trim().replaceAll("/+$", "");
        ObjectMapper mapper = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        boolean lookupOk = false, searchOk = false;
        long ms = -1;
        String msg;
        try {
            long t0 = System.currentTimeMillis();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/artist/" + QUEEN + "?fmt=json"))
                    .header("User-Agent", Config.get().userAgent()).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
            ms = System.currentTimeMillis() - t0;
            if (r.statusCode() == 200) {
                JsonNode n = mapper.readTree(r.body());
                lookupOk = "Queen".equals(JsonText.of(n.path("name"), ""));
            }
            msg = lookupOk ? "Lookup OK (" + ms + " ms)" : "Le serveur répond (HTTP " + r.statusCode() + ") mais la base ne contient pas l'artiste de test : import incomplet ?";
        } catch (Exception e) {
            return new TestResult(false, false, -1, "Serveur injoignable : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
        if (lookupOk) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/recording?query="
                        + java.net.URLEncoder.encode("recording:\"Bohemian Rhapsody\" AND artist:Queen", StandardCharsets.UTF_8) + "&limit=1&fmt=json"))
                        .header("User-Agent", Config.get().userAgent()).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                searchOk = r.statusCode() == 200 && mapper.readTree(r.body()).path("recordings").isArray()
                        && !mapper.readTree(r.body()).path("recordings").isEmpty();
            } catch (Exception ignored) { /* index de recherche absent */ }
            msg += searchOk ? " ; recherche texte OK"
                    : " ; recherche texte INDISPONIBLE (index de recherche non chargé) — l'identification par texte échouera, seuls les lookups par identifiant marcheront";
        }
        return new TestResult(lookupOk, searchOk, ms, msg);
    }
}
