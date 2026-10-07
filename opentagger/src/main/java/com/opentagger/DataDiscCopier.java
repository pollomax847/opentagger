package com.opentagger;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Copie d'un CD de données (souvenirs, photos, documents…) vers un dossier — SANS JAMAIS écraser quoi que ce soit : un fichier déjà présent et
 * identique est ignoré, un fichier du même nom mais différent est copié sous « nom (2).ext ». La version précédente remplaçait silencieusement
 * les fichiers de même nom du dossier de destination.
 */
public final class DataDiscCopier {
    private DataDiscCopier() {}

    /** @param copied copiés tels quels ; @param identical déjà présents et identiques (ignorés) ; @param renamed copiés sous un autre nom (conflit) */
    public record Result(int copied, int identical, int renamed, int failed) {
        public int total() { return copied + identical + renamed + failed; }
    }

    private static final Set<String> DATA_FS = Set.of("cdfs", "udf", "iso9660", "iso9660 (rock ridge)");

    /** Racine d'un CD de données inséré (système de fichiers CDFS/UDF lisible), ou {@code null}. Windows : lecteurs « racine » ; ailleurs, non détecté. */
    public static Path findDataDiscRoot() {
        // Linux/macOS : un CD de données est monté sous /media, /run/media, /Volumes… ; on le repère par le type de son système de fichiers.
        try {
            for (java.nio.file.FileStore store : java.nio.file.FileSystems.getDefault().getFileStores()) {
                String type = store.type();
                if (type == null || !DATA_FS.contains(type.toLowerCase(java.util.Locale.ROOT))) continue;
                Path mount = parseStoreMount(store.toString());
                if (mount != null && Files.isDirectory(mount) && Files.isReadable(mount)) return mount;
            }
        } catch (RuntimeException ignored) { /* pas de liste de volumes : on essaie les racines ci-dessous */ }
        for (java.io.File root : java.io.File.listRoots()) {
            try {
                String type = Files.getFileStore(root.toPath()).type();
                if (type != null && DATA_FS.contains(type.toLowerCase(java.util.Locale.ROOT)) && Files.isReadable(root.toPath())) return root.toPath();
            } catch (IOException | RuntimeException ignored) { /* lecteur vide ou illisible */ }
        }
        return null;
    }

    /** Chemin de montage dans la description d'un volume Unix (« /media/paul/SOUVENIRS (/dev/sr0) ») ; {@code null} si ce n'en est pas une (Windows : « Étiquette (I:) »). */
    static Path parseStoreMount(String description) {
        if (description == null) return null;
        int cut = description.lastIndexOf(" (");
        String p = (cut > 0 ? description.substring(0, cut) : description).trim();
        if (!p.startsWith("/")) return null;
        try { return java.nio.file.Paths.get(p); } catch (java.nio.file.InvalidPathException e) { return null; }
    }

    /** Nom de fichier libre dans {@code dir} : « nom.ext », sinon « nom (2).ext », « nom (3).ext »… */
    static Path freeName(Path dir, String fileName) {
        Path p = dir.resolve(fileName);
        if (!Files.exists(p)) return p;
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName, ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 2; i < 10_000; i++) {
            p = dir.resolve(stem + " (" + i + ")" + ext);
            if (!Files.exists(p)) return p;
        }
        return dir.resolve(stem + " (" + System.nanoTime() + ")" + ext);
    }

    public enum Outcome { COPIED, IDENTICAL, RENAMED }

    /** Résultat d'UN fichier : ce qui s'est passé et où il se trouve maintenant. */
    public record One(Outcome outcome, Path target) {}

    /** Copie un fichier du disque (chemin relatif conservé) sans jamais rien écraser. */
    public static One copyOne(Path srcRoot, Path file, Path destRoot) throws IOException {
        Path rel = srcRoot.relativize(file);
        Path target = destRoot.resolve(rel);
        Path dir = target.getParent();
        Files.createDirectories(dir != null ? dir : destRoot);
        if (Files.exists(target)) {
            if (Files.size(target) == Files.size(file) && Files.mismatch(target, file) == -1L) return new One(Outcome.IDENTICAL, target);
            Path other = freeName(target.getParent(), target.getFileName().toString());
            Files.copy(file, other);
            return new One(Outcome.RENAMED, other);
        }
        Files.copy(file, target);
        return new One(Outcome.COPIED, target);
    }

    /** Les fichiers du disque (récursivement), triés par chemin, pour les afficher dans le tableau avant de copier. */
    public static java.util.List<Path> listFiles(Path root, int cap) {
        java.util.List<Path> out = new java.util.ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { out.add(f); return out.size() >= cap ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { /* liste partielle */ }
        out.sort(java.util.Comparator.comparing(p -> p.toString().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    public static Result copy(Path srcRoot, Path destRoot) throws IOException {
        AtomicInteger copied = new AtomicInteger(), identical = new AtomicInteger(), renamed = new AtomicInteger(), failed = new AtomicInteger();
        Files.walkFileTree(srcRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Path rel = srcRoot.relativize(file);
                    Path dir = destRoot.resolve(rel).getParent();
                    Files.createDirectories(dir != null ? dir : destRoot);
                    Path target = destRoot.resolve(rel);
                    if (Files.exists(target)) {
                        if (Files.size(target) == Files.size(file) && Files.mismatch(target, file) == -1L) { identical.incrementAndGet(); return FileVisitResult.CONTINUE; }
                        target = freeName(target.getParent(), target.getFileName().toString());
                        Files.copy(file, target);
                        renamed.incrementAndGet();
                    } else {
                        Files.copy(file, target);
                        copied.incrementAndGet();
                    }
                } catch (IOException e) {
                    failed.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException exc) { failed.incrementAndGet(); return FileVisitResult.CONTINUE; }
        });
        return new Result(copied.get(), identical.get(), renamed.get(), failed.get());
    }

    /** Nombre de fichiers et taille totale (octets) d'un dossier, pour le montrer avant de copier. */
    public static long[] measure(Path root) {
        long[] r = {0, 0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) { r[0]++; r[1] += a.size(); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        } catch (IOException ignored) { /* mesure partielle */ }
        return r;
    }
}
