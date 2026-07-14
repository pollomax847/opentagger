package com.opentagger;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.function.Consumer;

/**
 * Gestionnaire du binaire fpcalc (Chromaprint).
 *
 * Priorité de recherche :
 *  1. Chemin configuré dans les préférences (audio.fpcalc_path)
 *  2. fpcalc sur le PATH système
 *  3. ~/.opentagger/bin/fpcalc (téléchargé automatiquement)
 *
 * Téléchargement depuis GitHub Releases (v1.5.1) si non trouvé.
 */
public class FpcalcInstaller {

    private static final String VERSION  = "1.5.1";
    private static final String BASE_URL =
        "https://github.com/acoustid/chromaprint/releases/download/v" + VERSION + "/";
    private static final String BIN_DIR  =
        Config.configDir() + java.io.File.separator + "bin";

    // ── Recherche du binaire ──────────────────────────────────────────────────

    /**
     * Retourne le chemin vers fpcalc utilisable, ou null si introuvable.
     * Ne déclenche pas de téléchargement.
     */
    public static String resolve() {
        // 1. Préférence explicite
        String configured = Config.get().str("audio.fpcalc_path", "").trim();
        if (!configured.isBlank() && new File(configured).canExecute()) return configured;

        // 2. PATH système
        if (isOnPath("fpcalc")) return "fpcalc";

        // 3. Répertoire local
        String local = localPath();
        if (new File(local).canExecute()) return local;

        return null;
    }

    public static boolean isAvailable() { return resolve() != null; }

    // ── Téléchargement ────────────────────────────────────────────────────────

    /**
     * Télécharge et installe fpcalc dans ~/.opentagger/bin/.
     * @param progress callback de progression ("Téléchargement… 42%")
     * @return chemin du binaire installé
     */
    public static String download(Consumer<String> progress) throws Exception {
        String filename = archiveName();
        String url      = BASE_URL + filename;
        Path   binDir   = Paths.get(BIN_DIR);
        Files.createDirectories(binDir);

        // Télécharger l'archive
        progress.accept("Connexion à GitHub…");
        HttpClient http = HttpTimeouts.client();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("User-Agent", Config.get().userAgent())
                .timeout(HttpTimeouts.largeDownload()).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " pour " + url);

        Path archivePath = binDir.resolve(filename);
        progress.accept("Téléchargement de fpcalc " + VERSION + "…");
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(archivePath)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }

        // Extraire le binaire
        progress.accept("Extraction…");
        String fpcalcPath = extract(archivePath, binDir);

        // Rendre exécutable (Linux/macOS)
        new File(fpcalcPath).setExecutable(true);
        Files.deleteIfExists(archivePath);

        // Nettoyer les dossiers extraits éventuels
        try (var s = Files.walk(binDir)) {
            s.filter(p -> p.toString().endsWith("fpcalc") || p.toString().endsWith("fpcalc.exe"))
             .filter(p -> !p.equals(Paths.get(fpcalcPath)))
             .forEach(p -> { try { Files.move(p, Paths.get(fpcalcPath), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {} });
        }

        progress.accept("fpcalc installé → " + fpcalcPath);
        return fpcalcPath;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String localPath() {
        String exe = isWindows() ? "fpcalc.exe" : "fpcalc";
        return BIN_DIR + File.separator + exe;
    }

    private static boolean isOnPath(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Timeout 5s : évite le blocage si le binaire est corrompu ou ne répond pas
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return false; }
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static String archiveName() {
        String os   = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        String archTag = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x86_64";

        if (os.contains("win"))
            return "chromaprint-fpcalc-" + VERSION + "-windows-x86_64.zip";
        if (os.contains("mac") || os.contains("darwin"))
            return "chromaprint-fpcalc-" + VERSION + "-macos-x86_64.tar.gz";
        return "chromaprint-fpcalc-" + VERSION + "-linux-" + archTag + ".tar.gz";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String extract(Path archive, Path dest) throws Exception {
        String name = archive.getFileName().toString();
        if (name.endsWith(".zip")) {
            return extractZip(archive, dest);
        } else {
            return extractTarGz(archive, dest);
        }
    }

    private static String extractTarGz(Path archive, Path dest) throws Exception {
        // Windows télécharge du .zip → cette méthode n'est jamais appelée sur Windows
        // Linux/macOS : utiliser tar système
        ProcessBuilder pb = new ProcessBuilder("tar", "xzf", archive.toString(), "-C", dest.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();

        // Chercher fpcalc dans le dossier extrait
        try (var stream = Files.walk(dest)) {
            Path found = stream
                .filter(f -> f.getFileName().toString().equals("fpcalc"))
                .filter(f -> f.toFile().isFile())
                .findFirst()
                .orElseThrow(() -> new IOException("fpcalc introuvable dans l'archive"));
            Path target = dest.resolve("fpcalc");
            if (!found.equals(target)) Files.move(found, target, StandardCopyOption.REPLACE_EXISTING);
            // Nettoyer les dossiers extraits
            try (var s = Files.walk(dest)) {
                s.filter(q -> q.toFile().isDirectory() && !q.equals(dest))
                 .sorted((a, b) -> b.toString().length() - a.toString().length())
                 .forEach(q -> { try { Files.delete(q); } catch (Exception ignored) {} });
            }
            return target.toString();
        }
    }

    private static String extractZip(Path archive, Path dest) throws Exception {
        String fpcalcPath = dest.resolve("fpcalc.exe").toString();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith("fpcalc.exe")) {
                    try (OutputStream out = Files.newOutputStream(Paths.get(fpcalcPath))) {
                        zis.transferTo(out);
                    }
                    break;
                }
            }
        }
        return fpcalcPath;
    }
}
