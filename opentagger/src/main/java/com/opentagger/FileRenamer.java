package com.opentagger;

import com.opentagger.model.TagInfo;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Gestion du renommage de fichiers audio avec les 36 masques Jaikoz.
 *
 * Les masques sont stockés au format Jaikoz original dans renamemask.properties :
 *   filenameMasks0=Libellé\:expression_javascript
 *
 * L'expression est évaluée via Nashorn (même moteur que Jaikoz) avec toutes les
 * fonctions utilitaires de Jaikoz (ifnotempty, addClassical, pad, etc.).
 */
public class FileRenamer {

    // ── Données des masques ───────────────────────────────────────────────────
    private final List<String> labels      = new ArrayList<>();
    private final List<String> expressions = new ArrayList<>();

    // ── Moteur JavaScript (Nashorn — même lib que Jaikoz) ────────────────────
    private ScriptEngine engine;
    private String       utilFunctions;

    public FileRenamer() {
        loadMasks();
        initEngine();
    }

    // ── Chargement des masques ────────────────────────────────────────────────

    private void loadMasks() {
        Properties props = new Properties();

        // 1. Ressources embarquées dans le JAR
        try (InputStream in = getClass().getResourceAsStream("/renamemask.properties")) {
            if (in != null) props.load(in);
        } catch (IOException e) { /* ignore */ }

        // 2. Surcharge utilisateur ~/.opentagger/renamemask.properties
        Path userFile = Paths.get(System.getProperty("user.home") + "/.opentagger/renamemask.properties");
        if (Files.exists(userFile)) {
            try (InputStream in = Files.newInputStream(userFile)) { props.load(in); }
            catch (IOException e) { /* ignore */ }
        }

        // 3. Collecter les fonctions utilitaires JS
        StringBuilder sb = new StringBuilder();
        List<String> funcKeys = new ArrayList<>();
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith("javascriptFunctions")) funcKeys.add(k);
        }
        funcKeys.sort(Comparator.comparingInt(k -> {
            try { return Integer.parseInt(k.replace("javascriptFunctions", "")); }
            catch (NumberFormatException e) { return 99; }
        }));
        for (String k : funcKeys) {
            String val = props.getProperty(k, "");
            int colon = val.indexOf(':');
            if (colon >= 0) sb.append(val.substring(colon + 1)).append('\n');
        }
        utilFunctions = sb.toString();

        // 4. Collecter les masques filenameMasks0, filenameMasks1, ...
        List<String> maskKeys = new ArrayList<>();
        for (String k : props.stringPropertyNames()) {
            if (k.startsWith("filenameMasks")) maskKeys.add(k);
        }
        maskKeys.sort(Comparator.comparingInt(k -> {
            try { return Integer.parseInt(k.replace("filenameMasks", "")); }
            catch (NumberFormatException e) { return 99; }
        }));

        for (String k : maskKeys) {
            String val = props.getProperty(k, "");
            int colon  = val.indexOf(':');
            if (colon >= 0) {
                labels.add(val.substring(0, colon).trim());
                expressions.add(val.substring(colon + 1).trim());
            }
        }
    }

    // ── Initialisation Nashorn ────────────────────────────────────────────────

    private void initEngine() {
        try {
            ScriptEngineManager mgr = new ScriptEngineManager();
            engine = mgr.getEngineByName("nashorn");
            if (engine == null) engine = mgr.getEngineByName("js"); // GraalVM fallback
            if (engine != null && !utilFunctions.isBlank()) {
                engine.eval(utilFunctions);
            }
        } catch (Exception e) {
            engine = null; // fallback en mode simple
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    public int    maskCount()        { return labels.size(); }
    public String maskLabel(int i)   { return i < labels.size()      ? labels.get(i)      : "Masque " + i; }
    public String maskExpression(int i) { return i < expressions.size() ? expressions.get(i) : ""; }

    /**
     * Déplace le fichier selon le masque, relatif à rootDir.
     * Gère les collisions avec le suffixe (2), (3)…
     */
    public Path rename(Path fichier, TagInfo info, int maskIndex, Path rootDir) throws IOException {
        if (rootDir == null) rootDir = fichier.getParent();

        String nom = fichier.getFileName().toString();
        String ext = nom.contains(".") ? nom.substring(nom.lastIndexOf('.')) : "";

        String chemin = evaluate(maskIndex, info);
        if (chemin.isBlank()) return null;

        Path cible = rootDir.resolve(chemin + ext).normalize();
        if (cible.equals(fichier)) return null;

        if (cible.getParent() != null) Files.createDirectories(cible.getParent());

        // Résolution de collision
        if (Files.exists(cible)) {
            int n = 2;
            do {
                cible = rootDir.resolve(chemin + " (" + n++ + ")" + ext).normalize();
                if (cible.getParent() != null) Files.createDirectories(cible.getParent());
            } while (Files.exists(cible) && n < 100);
        }

        moveFile(fichier, cible);
        return cible;
    }

    public Path rename(Path fichier, TagInfo info, int maskIndex) throws IOException {
        return rename(fichier, info, maskIndex, fichier.getParent());
    }

    /** Prévisualise le résultat d'un masque sans déplacer le fichier. */
    public String preview(TagInfo info, int maskIndex, String extension) {
        return evaluate(maskIndex, info) + extension;
    }

    // ── Évaluation JavaScript (Nashorn) ───────────────────────────────────────

    private synchronized String evaluate(int maskIndex, TagInfo info) {
        if (maskIndex < 0 || maskIndex >= expressions.size()) return "";
        String expr = expressions.get(maskIndex);

        if (engine != null) {
            return evalWithNashorn(expr, info);
        } else {
            return evalSimple(expr, info); // fallback si Nashorn indisponible
        }
    }

    private String evalWithNashorn(String expr, TagInfo info) {
        try {
            bindVariables(info);
            Object result = engine.eval(expr);
            String path = result != null ? result.toString() : "";
            return sanitizePath(path);
        } catch (Exception e) {
            return evalSimple(expr, info);
        }
    }

    private void bindVariables(TagInfo info) {
        // Variables nommées exactement comme dans les masques Jaikoz
        engine.put("title",           safe(info.title));
        engine.put("artist",          safe(info.artist));
        engine.put("albumartist",     safe(info.albumArtist.isEmpty() ? info.artist : info.albumArtist));
        engine.put("album",           safe(info.album));
        engine.put("trackno",         safe(info.track));
        engine.put("tracktotal",      safe(info.trackTotal));
        engine.put("discno",          safe(info.discNo));
        engine.put("albumyear",       safe(info.year));
        engine.put("genre",           safe(info.genre));
        engine.put("composer",        safe(info.composer));
        engine.put("conductor",       safe(info.conductor));
        engine.put("artistsort",      safe(info.artistSort));
        engine.put("albumartistsort", safe(info.albumArtistSort));
        engine.put("mb_comment",      safe(info.comment));
        engine.put("mbreleaseid",     safe(info.releaseMbid));
        engine.put("mbrecordingid",   safe(info.recordingMbid));
        engine.put("mbreleaseartistid", safe(info.releaseGroupMbid));
        engine.put("isclassical",     info.isClassical);
        engine.put("iscompilation",   info.isCompilation);
        engine.put("ishd",            info.isHD);
        engine.put("isgreatesthits",  "0");

        // disctotal comme nombre pour que ifmultidisc(value) fonctionne
        int dt = 0;
        try { dt = Integer.parseInt(info.discTotal); } catch (NumberFormatException e) {}
        engine.put("disctotal", dt);

        // Tableaux multi-valeurs (vides pour l'instant — future Phase 5)
        engine.put("artists_index",       new String[]{});
        engine.put("albumartists_index",  new String[]{});
        engine.put("composersort_index",  new String[]{});
        engine.put("mb_track_title",      safe(info.title));
        engine.put("discogs_title",       "");

        // Sort names utilisés dans les masques avancés
        engine.put("albumartistssort",   safe(info.albumArtistSort));
        engine.put("artistssort",        safe(info.artistSort));
        engine.put("albumartists",       safe(info.albumArtist));
        engine.put("albumartistsort",    safe(info.albumArtistSort));
        engine.put("artists",            safe(info.artist));
    }

    // ── Fallback sans JS — simple substitution ────────────────────────────────

    private String evalSimple(String expr, TagInfo info) {
        // Fallback minimal : extraire le pattern {ARTIST}/{ALBUM}/{TRACK} - {TITLE}
        String albumArtistOrArtist = info.albumArtist.isEmpty() ? info.artist : info.albumArtist;
        return sanitizePath(
            sanitize(albumArtistOrArtist) + "/" +
            sanitize(info.album) + "/" +
            pad(info.track) + " - " + sanitize(info.title)
        );
    }

    // ── Utilitaires chemin ────────────────────────────────────────────────────

    private String sanitizePath(String path) {
        if (path == null || path.isBlank()) return "_";
        // Sanitize chaque segment séparément
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            String s = sanitize(part);
            if (!s.isBlank()) {
                if (sb.length() > 0) sb.append('/');
                sb.append(s);
            }
        }
        return sb.toString().replaceAll("/+", "/").replaceAll("^/|/$", "");
    }

    private static final java.util.regex.Pattern AUDIO_EXT =
            java.util.regex.Pattern.compile("(?i)\\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|aiff|ape|wv)$");

    private String sanitize(String s) {
        if (s == null || s.isBlank()) return "";
        // Retirer l'extension audio si le tag la contient (ex: title="Song.mp3")
        s = AUDIO_EXT.matcher(s.trim()).replaceAll("");
        return s.trim()
                .replaceAll("[\\\\:*?\"<>|]", "_")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String pad(String track) {
        if (track == null || track.isBlank()) return "";
        try { return String.format("%02d", Integer.parseInt(track.trim())); }
        catch (NumberFormatException e) { return track.trim(); }
    }

    private String safe(String s) { return s == null ? "" : s; }

    // ── Déplacement de fichier ────────────────────────────────────────────────

    private static void moveFile(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(src);
        }
    }

    // ── Nettoyage des dossiers vides ──────────────────────────────────────────

    public static void deleteEmptyAncestors(Path sourceDir, Path stopAt) {
        Path dir = sourceDir;
        while (dir != null && !dir.equals(stopAt)) {
            if (!Files.isDirectory(dir)) { dir = dir.getParent(); continue; }
            try (Stream<Path> entries = Files.list(dir)) {
                if (entries.findAny().isPresent()) break;
                Files.delete(dir);
            } catch (IOException e) { break; }
            dir = dir.getParent();
        }
    }

    // ── CLI ───────────────────────────────────────────────────────────────────

    public void printMaskList() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Masques de renommage disponibles            ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        for (int i = 0; i < maskCount(); i++) {
            System.out.printf("║  [%2d] %s%n", i, maskLabel(i));
            System.out.println("║");
        }
        System.out.println("║  [  ] [Entrée] Ne pas renommer               ║");
        System.out.println("╚══════════════════════════════════════════════╝");
    }
}
