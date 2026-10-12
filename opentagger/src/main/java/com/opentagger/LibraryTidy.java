package com.opentagger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Rangement automatique d'un dossier scanné (2026-10-12, demande utilisateur : « pourquoi OpenTagger ne gère pas la
 * gestion des dossiers comme on l'a fait avec les scripts Python ? »). Avant le parcours de chaque dossier de scan, les
 * sous-dossiers presque identiques — même règle que {@link FileRenamer#folderKey} : casse, trait d'union ‐/-, apostrophe
 * ’/', « _ » au lieu d'espace, forme Unicode — sont fusionnés dans la variante la plus propre
 * ({@link FileRenamer#preferredName}). Mesuré sur MyBook le même jour : 2 132 artistes en plusieurs dossiers
 * (Jean-Louis Aubert ×3), 12 710 éléments à déplacer.
 *
 * <p>Uniquement des renommages sur le même disque (instantanés, aucune copie). Jamais d'écrasement : un fichier dont le
 * nom existe déjà dans le dossier gardé reste où il est. Chaque déplacement est noté dans
 * {@code ~/.opentagger/rangement/journal_*.tsv}, au même format que {@code fusion_dossiers.py} (son {@code --annuler}
 * remet tout en place).
 */
public final class LibraryTidy {
    private LibraryTidy() {}

    public record Result(int groups, int moved, int removedDirs, List<Path> leftInPlace) {}

    /** @param onMoved appelé pour chaque dossier/fichier déplacé (ancien chemin, nouveau) — pour mettre à jour le tableau */
    public static Result mergeFolderVariants(Path root, BiConsumer<Path, Path> onMoved, BooleanSupplier cancelled)
            throws IOException {
        Map<String, List<String>> byKey = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                byKey.computeIfAbsent(FileRenamer.folderKey(n), k -> new ArrayList<>()).add(n);
            }
        }
        List<List<String>> groups = new ArrayList<>();
        for (List<String> names : byKey.values()) {
            if (names.size() < 2) continue;
            List<String> dirs = new ArrayList<>();
            for (String n : names) if (Files.isDirectory(root.resolve(n))) dirs.add(n);   // stat seulement ici
            if (dirs.size() > 1) groups.add(dirs);
        }
        Ctx ctx = new Ctx(onMoved);
        if (groups.isEmpty()) return new Result(0, 0, 0, ctx.left);

        Path dir = Paths.get(Config.configDir(), "rangement");
        Files.createDirectories(dir);
        Path journal = dir.resolve("journal_" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date())
                + "_" + root.getFileName() + ".tsv");
        try (Writer w = Files.newBufferedWriter(journal, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ctx.log = w;
            for (List<String> g : groups) {
                if (cancelled.getAsBoolean()) break;
                String keep = g.get(0);
                for (String n : g) keep = FileRenamer.preferredName(keep, n);
                for (String n : g) {
                    if (n.equals(keep)) continue;
                    try { merge(root.resolve(n), root.resolve(keep), ctx); }
                    catch (IOException e) { w.write("ERREUR\t" + root.resolve(n) + "\t" + e.getMessage() + "\n"); }
                }
                w.flush();
            }
            for (Path p : ctx.left) w.write("CONFLIT-LAISSÉ\t" + p + "\t\n");
        }
        if (ctx.moved == 0 && ctx.left.isEmpty()) Files.deleteIfExists(journal);
        return new Result(groups.size(), ctx.moved, ctx.removed, ctx.left);
    }

    private static final class Ctx {
        final BiConsumer<Path, Path> onMoved;
        final List<Path> left = new ArrayList<>();
        Writer log;
        int moved, removed;
        Ctx(BiConsumer<Path, Path> onMoved) { this.onMoved = onMoved; }
    }

    private static void merge(Path src, Path dst, Ctx ctx) throws IOException {
        Map<String, String> dstByKey = new HashMap<>();
        java.util.Set<String> dstNames = new java.util.HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dst)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                dstNames.add(n);
                dstByKey.merge(FileRenamer.folderKey(n), n, FileRenamer::preferredName);
            }
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) { for (Path p : ds) entries.add(p); }
        for (Path s : entries) {
            String name = s.getFileName().toString();
            if (Files.isDirectory(s)) {
                String twin = dstByKey.get(FileRenamer.folderKey(name));
                if (twin != null && Files.isDirectory(dst.resolve(twin))) { merge(s, dst.resolve(twin), ctx); continue; }
                if (dstNames.contains(name)) { ctx.left.add(s); continue; }
            } else if (dstNames.contains(name)) {
                ctx.left.add(s);
                continue;
            }
            Path d = dst.resolve(name);
            try {
                Files.move(s, d);
            } catch (FileAlreadyExistsException e) {
                ctx.left.add(s);
                continue;
            }
            ctx.moved++;
            dstNames.add(name);
            dstByKey.putIfAbsent(FileRenamer.folderKey(name), name);
            ctx.log.write("DÉPLACÉ\t" + s + "\t" + d + "\n");
            if (ctx.onMoved != null) ctx.onMoved.accept(s, d);
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
            if (!ds.iterator().hasNext()) {
                Files.delete(src);
                ctx.removed++;
                ctx.log.write("SUPPRIMÉ-VIDE\t" + src + "\t\n");
            }
        }
    }
}
