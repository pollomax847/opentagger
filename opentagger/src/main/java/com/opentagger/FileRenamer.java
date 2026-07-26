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

    // Limite le nombre de copies CROSS-DEVICE simultanées (voir moveFile()) — un rename same-device
    // (cas courant, quasi-instantané) n'est jamais concerné. Trouvé en production : la bibliothèque
    // cible et les dossiers scannés sont souvent sur des disques mécaniques DIFFÉRENTS (confirmé sur
    // la machine de l'utilisateur : /home, /mnt/ssd, /mnt/MyBook et /mnt/MyBook/Music sont 4 disques
    // physiques distincts, tous rotationnels) — plusieurs threads d'identification en parallèle
    // (batch.threads) qui finissent tous par écrire sur LE MÊME disque de destination se gênent
    // mutuellement (une tête de disque mécanique ne peut être qu'à un endroit à la fois), au lieu de
    // s'accélérer. Défaut 1 (sérialise complètement les copies cross-device) ; réglable via
    // rename.max_concurrent_cross_device_moves si l'utilisateur a une destination plus rapide (SSD,
    // NAS avec plusieurs disques...).
    private static final java.util.concurrent.Semaphore CROSS_DEVICE_COPY_LIMIT =
            new java.util.concurrent.Semaphore(
                    Math.max(1, Config.get().num("rename.max_concurrent_cross_device_moves", 1)));

    // Un verrou par nom de destination CONTESTÉ (pas un verrou global — voir lockFor()) couvrant
    // "vérifier que la cible est libre" + "déplacer" dans rename()/moveToFolder() : sans lui, deux
    // threads du pool batch (batch.threads) visant le même nom de destination (deux fichiers
    // résolvant au même masque) passent TOUS LES DEUX le test Files.exists() avant qu'aucun n'ait
    // bougé son fichier, puis Files.move avec ATOMIC_MOVE (sans REPLACE_EXISTING) écrase quand même
    // silencieusement la destination sur Linux — vérifié empiriquement : rename(2) POSIX remplace
    // toujours la cible, REPLACE_EXISTING ou pas. Le thread qui finit en second gagne, le fichier de
    // l'autre disparaît sans exception. Verrouiller uniquement sur le chemin de base contesté (pas
    // globalement) évite de sérialiser tout le renommage batch pour des fichiers qui ne se disputent
    // jamais le même nom.
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> TARGET_LOCKS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Object lockFor(Path baseTarget) {
        return TARGET_LOCKS.computeIfAbsent(baseTarget.toString(), k -> new Object());
    }

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
        Path userFile = Paths.get(Config.configDir() + java.io.File.separator + "renamemask.properties");
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

        synchronized (lockFor(cible)) {
            if (cible.getParent() != null) Files.createDirectories(cible.getParent());

            // Résolution de collision
            if (Files.exists(cible)) {
                int n = 2;
                do {
                    cible = rootDir.resolve(chemin + " (" + n++ + ")" + ext).normalize();
                    if (cible.getParent() != null) Files.createDirectories(cible.getParent());
                } while (Files.exists(cible) && n < 100);
                if (Files.exists(cible))
                    throw new IOException("Impossible de renommer '"
                            + fichier.getFileName() + "' : 98 fichiers en collision existent déjà.");
            }

            moveFile(fichier, cible);
        }
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
        engine.put("mbreleaseid",        safe(info.releaseMbid));
        engine.put("mbrecordingid",      safe(info.recordingMbid));
        engine.put("mbreleaseartistid",  safe(info.artistMbid));
        engine.put("mbartistid",         safe(info.artistMbid));
        engine.put("mbreleasegroupid",   safe(info.releaseGroupMbid));
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

    // Limite volontairement généreuse mais sûre pour un segment de chemin (dossier ou nom de
    // fichier avant extension) — jaudiotagger (AudioFileWriter.write()) crée un fichier temporaire
    // nommé d'après le nom de fichier d'origine + ".tmp" DANS LE MÊME DOSSIER ; au-delà de la
    // limite du système de fichiers (255 octets sur ext4, souvent moins sur exFAT/NTFS/CIFS via
    // FUSE), cette création échoue et jaudiotagger plante avec un NullPointerException cryptique au
    // lieu de son repli prévu (voir TagWriter.translateKnownJaudiotaggerBug() pour le détail du bug
    // tiers) — un titre/artiste/album réel dépasse très rarement 180 caractères, donc tronquer ici
    // n'affecte en pratique que les cas déjà à risque.
    static final int MAX_SEGMENT_LENGTH = 180; // package-privé : réutilisé par TagWriter.translateKnownJaudiotaggerBug()

    private String sanitize(String s) {
        if (s == null || s.isBlank()) return "";
        // Retirer l'extension audio si le tag la contient (ex: title="Song.mp3")
        s = AUDIO_EXT.matcher(s.trim()).replaceAll("");
        String cleaned = s.trim()
                .replaceAll("[\\\\:*?\"<>|]", "_")
                .replaceAll("\\.{2,}", ".")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > MAX_SEGMENT_LENGTH
                ? cleaned.substring(0, MAX_SEGMENT_LENGTH).trim() : cleaned;
    }

    private String pad(String track) {
        if (track == null || track.isBlank()) return "";
        try { return String.format("%02d", Integer.parseInt(track.trim())); }
        catch (NumberFormatException e) { return track.trim(); }
    }

    private String safe(String s) { return s == null ? "" : s; }

    /**
     * Déplace un fichier "brut" (sans masque, nom d'origine conservé) vers un dossier — utilisé
     * pour isoler les fichiers non tagués (SKIPPED/ERROR) hors de la bibliothèque organisée.
     * Mêmes garanties de collision ((2), (3)…) et de déplacement cross-device que {@link #rename}.
     */
    public static Path moveToFolder(Path fichier, Path targetFolder) throws IOException {
        Files.createDirectories(targetFolder);
        String nom  = fichier.getFileName().toString();
        int dot      = nom.lastIndexOf('.');
        String ext   = dot > 0 ? nom.substring(dot) : "";
        String stem  = dot > 0 ? nom.substring(0, dot) : nom;

        Path cible = targetFolder.resolve(nom).normalize();
        if (cible.equals(fichier.toAbsolutePath().normalize())) return null;

        synchronized (lockFor(cible)) {
            if (Files.exists(cible)) {
                int n = 2;
                do {
                    cible = targetFolder.resolve(stem + " (" + n++ + ")" + ext).normalize();
                } while (Files.exists(cible) && n < 100);
                if (Files.exists(cible))
                    throw new IOException("Impossible de déplacer '" + nom
                            + "' : 98 fichiers en collision existent déjà.");
            }

            moveFile(fichier, cible);
        }
        return cible;
    }

    // ── Déplacement de fichier ────────────────────────────────────────────────

    private static void moveFile(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // NAS (NFS/SMB) ou disque cross-device : fallback copy+delete sécurisé. Coûteux en I/O
            // disque (contrairement au rename same-device ci-dessus, quasi-instantané) — throttlé
            // via CROSS_DEVICE_COPY_LIMIT (voir son commentaire) pour éviter que plusieurs threads
            // ne fassent toutes converger leurs copies en même temps sur un même disque mécanique
            // de destination.
            try {
                CROSS_DEVICE_COPY_LIMIT.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Déplacement cross-device interrompu : " + src, ie);
            }
            try {
                long srcSize = Files.size(src);
                // PAS de COPY_ATTRIBUTES : sur un point de montage FUSE (ex. pool mergerfs monté
                // avec user_id/group_id figés), tenter de préserver dates/permissions/propriétaire
                // peut échouer avec EPERM ("Opération non permise") même quand la copie des DONNÉES
                // elle-même réussirait sans problème — confirmé en reproduisant l'appel exact hors
                // de l'appli sur le pool réel de l'utilisateur (même FileSystemException, même
                // message, que Files.copy retire COPY_ATTRIBUTES et l'échec disparaît). Ces
                // attributs filesystem n'ont de toute façon aucune importance pour un déplacement de
                // bibliothèque : seuls les tags audio (déjà écrits séparément) comptent.
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                long dstSize = Files.size(dst);
                if (dstSize != srcSize) {
                    Files.deleteIfExists(dst);
                    throw new IOException("Copie incomplète (src=" + srcSize
                            + " dst=" + dstSize + ") — fichier source conservé : " + src);
                }
                Files.delete(src);
            } finally {
                CROSS_DEVICE_COPY_LIMIT.release();
            }
        }
    }

    // ── Nettoyage des dossiers vides ──────────────────────────────────────────

    public static void deleteEmptyAncestors(Path sourceDir, Path stopAt) {
        Path dir = sourceDir;
        while (dir != null && !dir.equals(stopAt)) {
            if (!Files.isDirectory(dir)) { dir = dir.getParent(); continue; }
            try (Stream<Path> listing = Files.list(dir)) {
                List<Path> entries = listing.toList();
                if (!entries.stream().allMatch(FileRenamer::isDeletableLeftover)) break;
                for (Path leftover : entries) Files.deleteIfExists(leftover);
                Files.delete(dir);
            } catch (IOException e) { break; }
            dir = dir.getParent();
        }
    }

    /** Vrai si {@code p} est un résidu que le taguage peut avoir laissé derrière lui une fois
     *  toutes les pistes déplacées — pochette locale (folder.jpg, cover.jpg…, y compris le nom
     *  configuré via cover.filename), journal de session ({@code opentagger_*.log}, voir
     *  CorrectionLog), ou résidu AppleDouble macOS ({@code ._nomdufichier}, un doublon de métadonnées
     *  Finder sans rapport avec l'audio réel — laissé derrière par AudioScanner, qui ignore déjà
     *  tout fichier commençant par un point, donc jamais déplacé avec la piste d'origine) — jamais
     *  un sous-dossier ni un fichier réellement inconnu, pour ne jamais supprimer un dossier qui
     *  contient encore quelque chose de réel. */
    private static boolean isDeletableLeftover(Path p) {
        if (Files.isDirectory(p)) return false;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (TagEnrichment.LOCAL_COVER_FILENAMES.contains(name)) return true;
        String coverBase = Config.get().str("cover.filename", "cover").toLowerCase(Locale.ROOT);
        if (name.equals(coverBase + ".jpg") || name.equals(coverBase + ".png")) return true;
        if (name.startsWith("opentagger_") && name.endsWith(".log")) return true;
        return name.startsWith("._");
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
