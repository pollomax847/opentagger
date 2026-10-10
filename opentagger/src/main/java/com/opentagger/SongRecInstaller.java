package com.opentagger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Localise (et, sous Windows, installe) le binaire SongRec.
 *
 * SongRec n'a pas de version Windows officielle : l'archive installée ici est une compilation
 * « sans interface » du dépôt marin-m/SongRec (GPL-3.0+), livrée avec ses DLL (glib, libsoup,
 * gettext, openssl) dans {@code bin/} et le module TLS de GIO dans {@code lib/gio/modules/}.
 *
 * Priorité de recherche (identique à FpcalcInstaller) :
 *  1. chemin des Préférences ({@code songrec.path}) ;
 *  2. {@code songrec} sur le PATH ;
 *  3. {@code <dossier de config>/songrec/bin/songrec.exe} (Windows, téléchargé ; {@code %APPDATA%\OpenTagger}).
 */
public final class SongRecInstaller {

    /** Archive Windows x64 : empreinte attendue, pour ne jamais exécuter un fichier altéré. */
    static final String WINDOWS_ZIP_SHA256 = "7E9337CC4158B7E02020AA1513E12ECF0348FF84C7F9DA14973EEB19202E0006";
    static final String DEFAULT_WINDOWS_ZIP_URL =
        "https://github.com/pollomax847/opentagger/releases/download/songrec-windows-1/songrec-windows-x64.zip";

    private static final long CACHE_MS = 30_000;
    private static volatile String cachedPath;
    private static volatile long cachedAt;

    private SongRecInstaller() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    static Path installDir() { return Paths.get(Config.configDir(), "songrec"); }

    static Path localExe() { return installDir().resolve("bin").resolve("songrec.exe"); }

    /** Commande utilisable pour lancer SongRec, ou null s'il est introuvable. Ne télécharge rien. */
    public static String resolve() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) return cachedPath;
        String found = locate();
        cachedPath = found;
        cachedAt = now;
        return found;
    }

    /** Oublie le résultat mémorisé (après une installation ou un changement de réglage). */
    public static void forget() { cachedAt = 0; }

    public static boolean isAvailable() { return resolve() != null; }

    private static String locate() {
        String configured = Config.get().str("songrec.path", "").trim();
        if (!configured.isBlank() && works(configured)) return configured;
        if (works("songrec")) return "songrec";
        if (isWindows() && Files.isRegularFile(localExe()) && works(localExe().toString()))
            return localExe().toString();
        return null;
    }

    private static boolean works(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "--version").redirectErrorStream(true).start();
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    // ── Installation (Windows) ────────────────────────────────────────────────

    /** Télécharge l'archive Windows et l'installe dans ~/.opentagger/songrec. */
    public static String download(Consumer<String> progress) throws Exception {
        if (!isWindows()) throw new IOException("SongRec s'installe par le gestionnaire de paquets sous Linux (voir docs/LINUX.md).");
        String url = Config.get().str("songrec.windows_zip_url", DEFAULT_WINDOWS_ZIP_URL).trim();
        Path dir = Paths.get(Config.configDir());
        Files.createDirectories(dir);
        Path zip = Files.createTempFile(dir, "songrec-", ".zip");
        try {
            progress.accept("Téléchargement de SongRec…");
            HttpClient http = HttpTimeouts.client();
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("User-Agent", Config.get().userAgent())
                    .timeout(HttpTimeouts.largeDownload()).GET().build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " pour " + url);
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(zip)) { in.transferTo(out); }
            return installFromZip(zip, progress, true);
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    /** Installe SongRec depuis une archive déjà présente sur le disque (hors-ligne). */
    public static String installFromZip(Path zip, Consumer<String> progress, boolean verifyHash) throws Exception {
        if (!isWindows()) throw new IOException("Installation réservée à Windows.");
        if (verifyHash) {
            String actual = sha256(zip);
            if (!actual.equalsIgnoreCase(WINDOWS_ZIP_SHA256))
                throw new IOException("Empreinte SHA-256 inattendue pour l'archive SongRec (" + actual
                    + ") — installation refusée.");
        }
        progress.accept("Installation de SongRec…");
        Path target = installDir();
        Path staging = target.resolveSibling("songrec.new");
        deleteTree(staging);
        extract(zip, staging);
        if (!Files.isRegularFile(staging.resolve("bin").resolve("songrec.exe")))
            throw new IOException("songrec.exe absent de l'archive.");
        deleteTree(target);
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        forget();
        String exe = localExe().toString();
        if (!works(exe)) throw new IOException("SongRec installé mais ne démarre pas : " + exe);
        progress.accept("SongRec installé → " + exe);
        return exe;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return HexFormat.of().withUpperCase().formatHex(md.digest());
    }

    static void extract(Path zip, Path dest) throws IOException {
        Files.createDirectories(dest);
        Path root = dest.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                // Compress-Archive (Windows PowerShell 5.1) écrit des « \ » : on accepte les deux séparateurs.
                String name = e.getName().replace('\\', '/');
                Path out = root.resolve(name).normalize();
                if (!out.startsWith(root)) throw new IOException("Entrée d'archive refusée : " + e.getName());
                if (e.isDirectory() || name.endsWith("/")) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) { zis.transferTo(os); }
            }
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            for (Path q : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(q);
        }
    }

    /** Dossier du binaire, pour le journal et les Préférences. */
    public static File home() { return installDir().toFile(); }
}
