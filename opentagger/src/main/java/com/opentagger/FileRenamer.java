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
                    // Le candidat de collision retombe sur le fichier LUI-MÊME (cas fréquent : deux
                    // copies identiques déjà présentes, l'une déjà au nom canonique, l'autre déjà
                    // au premier suffixe "(2)" — exactement le nom que cette boucle vient de
                    // proposer) — trouvé en direct 2026-08-01 : sans ce contrôle, la boucle ne
                    // remarque jamais qu'elle vient de reproduire le nom actuel du fichier, continue
                    // au suffixe suivant, et "renomme" (2) en (3) — un déplacement réel sur le
                    // disque qui ne corrige rien, incrémente d'un cran de plus à chaque nouvelle
                    // passe, et gonflait le total annoncé par RenamePreviewDialog sans réorganiser
                    // quoi que ce soit. Déjà à la meilleure place possible compte tenu de la
                    // collision : rien à faire, même verdict que le cas canonique de la ligne 157.
                    if (cible.equals(fichier)) return null;
                    if (cible.getParent() != null) Files.createDirectories(cible.getParent());
                } while (Files.exists(cible) && n < 100);
                if (Files.exists(cible))
                    throw new IOException("Impossible de renommer '"
                            + fichier.getFileName() + "' : 98 fichiers en collision existent déjà.");
            }

            moveFile(fichier, cible);
            moveMatchingLrc(fichier, nom, cible);
            moveLocalCoverIfPresent(fichier.getParent(), cible.getParent());
        }
        return cible;
    }

    /**
     * Déplace le fichier ".lrc" associé (même nom de base que l'audio) s'il existe, pour qu'il
     * suive l'audio jusqu'à sa nouvelle destination — sans ça, il reste orphelin dans l'ancien
     * dossier une fois l'audio renommé/déplacé. TagEnrichment.saveEntry() écrit ce fichier AVANT
     * le renommage (lyrics.save_lrc) mais ne le déplace jamais lui-même ; repéré en direct
     * (2026-08-13). Pas de perte silencieuse en cas d'échec ici — deleteEmptyAncestors() (voir son
     * commentaire) refuse déjà de supprimer un dossier contenant encore un ".lrc" non déplacé, donc
     * un échec ne fait "que" laisser le fichier dans l'ancien dossier, jamais le perdre — un échec
     * ne doit donc jamais faire échouer le renommage audio, déjà réussi à ce stade.
     */
    private static void moveMatchingLrc(Path origFichier, String origNom, Path cible) {
        int dot = origNom.lastIndexOf('.');
        if (dot <= 0 || origFichier.getParent() == null || cible.getParent() == null) return;
        Path origLrc = origFichier.getParent().resolve(origNom.substring(0, dot) + ".lrc");
        if (!Files.exists(origLrc)) return;
        String cibleNom = cible.getFileName().toString();
        int cibleDot = cibleNom.lastIndexOf('.');
        String cibleStem = cibleDot > 0 ? cibleNom.substring(0, cibleDot) : cibleNom;
        Path destLrc = cible.getParent().resolve(cibleStem + ".lrc");
        try {
            if (!Files.exists(destLrc)) moveFile(origLrc, destLrc);
        } catch (IOException ignored) {}
    }

    /**
     * Déplace la pochette locale (folder.jpg, cover.jpg…) du dossier d'origine vers le dossier de
     * destination, pour la même raison que {@link #moveMatchingLrc} : sans ça, elle reste dans
     * l'ancien dossier, et {@link #isDeletableLeftover} la traite ensuite comme un simple résidu
     * supprimable (elle l'est presque toujours — CAA/FanArt/Shazam passent AVANT "local" dans
     * {@code cover.provider_order}, donc la pochette locale a déjà été embarquée dans le tag de
     * chaque piste avant ce point la plupart du temps) — mais quand ces fournisseurs échouent tous
     * et que "local" n'a jamais été atteint (ou est désactivé), la pochette locale n'a JAMAIS été
     * embarquée nulle part : la supprimer directement comme résidu la ferait purement et simplement
     * disparaître (pochette perso de l'utilisateur, scan haute résolution, édition différente de
     * celle de MusicBrainz…). Repéré en audit (2026-08-18) sans qu'un cas réel ait encore été
     * signalé — corrigé préventivement plutôt que d'attendre une perte réelle, cohérent avec la
     * préférence utilisateur de rester non-destructif face à un doute. Ne déplace qu'UNE seule fois
     * par dossier (le premier fichier de la piste qui se déplace l'entraîne avec lui) : les pistes
     * suivantes du même dossier trouvent la destination déjà occupée et ne font rien — la copie
     * restée dans l'ancien dossier (si plusieurs pistes y résidaient) retombe alors correctement sur
     * {@link #isDeletableLeftover}, un VRAI doublon puisque la pochette a déjà rejoint sa nouvelle
     * destination.
     */
    private static void moveLocalCoverIfPresent(Path origParent, Path destParent) {
        if (origParent == null || destParent == null || origParent.equals(destParent)) return;
        for (String name : localCoverCandidateNames()) {
            Path src = origParent.resolve(name);
            if (!Files.exists(src)) continue;
            Path dst = destParent.resolve(name);
            try {
                if (!Files.exists(dst)) moveFile(src, dst);
            } catch (IOException ignored) {}
            return; // une seule pochette locale par dossier (même contrat que TagEnrichment.findLocalCover)
        }
    }

    /** Noms de fichiers reconnus comme pochette locale — ensemble fixe (voir
     *  {@link TagEnrichment#LOCAL_COVER_FILENAMES}) plus le nom configuré via {@code cover.filename},
     *  utilisé à la fois pour décider quoi déplacer ({@link #moveLocalCoverIfPresent}) et quoi
     *  considérer comme résidu supprimable après coup ({@link #isDeletableLeftover}) — même liste
     *  dans les deux cas pour ne jamais désynchroniser "ce qu'on déplace" de "ce qu'on peut supprimer". */
    private static List<String> localCoverCandidateNames() {
        List<String> names = new ArrayList<>(TagEnrichment.LOCAL_COVER_FILENAMES);
        String coverBase = Config.get().str("cover.filename", "cover").toLowerCase(Locale.ROOT);
        for (String ext : List.of(".jpg", ".png")) {
            String custom = coverBase + ext;
            if (!names.contains(custom)) names.add(custom);
        }
        return names;
    }

    public Path rename(Path fichier, TagInfo info, int maskIndex) throws IOException {
        return rename(fichier, info, maskIndex, fichier.getParent());
    }

    /** Prévisualise le résultat d'un masque sans déplacer le fichier. */
    public String preview(TagInfo info, int maskIndex, String extension) {
        return evaluate(maskIndex, info) + extension;
    }

    /** Simule la résolution de collision de {@link #rename} SANS toucher au disque — même logique
     *  de suffixe (2), (3)… et même détection "retombe sur le fichier lui-même" (voir son
     *  commentaire), pour que {@code RenamePreviewDialog.compute()} annonce un total fidèle à ce
     *  que rename() fera réellement une fois appliqué (avant ce correctif, l'aperçu comparait
     *  seulement le nom canonique brut et annonçait "sera renommé" pour des doublons qui, en
     *  réalité, ne bougent pas — ou pire, avant le correctif de rename() lui-même, se faisaient
     *  incrémenter en boucle sans jamais se ranger). Pas de verrou (lockFor) ici — une simulation
     *  en lecture seule n'a pas besoin de la garantie anti-course de rename(), et peut légitimement
     *  être périmée d'ici l'Appliquer si d'autres fichiers bougent entre-temps, exactement comme
     *  n'importe quel aperçu. {@code cheminSansExt} : le nom déjà évalué par {@link #preview}
     *  MOINS l'extension — pas ré-évalué ici pour ne pas payer deux fois le coût du moteur
     *  Nashorn (synchronized) sur un aperçu de plusieurs milliers de fichiers.
     *  @return null si rien à faire (déjà au bon nom, ou variante de collision déjà occupée par ce
     *          fichier lui-même) — même contrat que rename(). */
    public static Path previewTarget(Path fichier, Path rootDir, String cheminSansExt, String ext) {
        Path cible = rootDir.resolve(cheminSansExt + ext).normalize();
        if (cible.equals(fichier)) return null;
        if (Files.exists(cible)) {
            int n = 2;
            do {
                cible = rootDir.resolve(cheminSansExt + " (" + n++ + ")" + ext).normalize();
                if (cible.equals(fichier)) return null;
            } while (Files.exists(cible) && n < 100);
            if (Files.exists(cible)) return null; // 98 collisions — rename() lèvera la vraie erreur si on applique quand même
        }
        return cible;
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
        // overallWork prime sur work (mouvement d'une œuvre parente vs pièce autonome) — même
        // logique que ClassicalDisplay.summarize(), voir son commentaire pour le détail des champs.
        engine.put("work",            safe(info.overallWork.isBlank() ? info.work : info.overallWork));
        engine.put("movement",        safe(info.titleMovement.isBlank() ? info.movement : info.titleMovement));
        engine.put("movementno",      safe(info.movementNo));
        engine.put("opus",            safe(info.opus.isBlank() ? info.classicalCatalog : info.opus));
        engine.put("classicalcatalog",safe(info.classicalCatalog));
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
        // Octet nul : un tag corrompu (mauvais encodage, script utilisateur buggé) peut en
        // contenir un ; Path.of()/Paths.get() lève InvalidPathException dessus, non rattrapée
        // ici — voir docs/files/04-COMPARAISON-PICARD-ONETAGGER.md (OneTagger issue #157).
        s = s.replace("\u0000", "");
        String cleaned = s.trim()
                // Normalise l'espacement autour de ':' AVANT sa neutralisation ci-dessous — sinon
                // "Britten : X" et "Britten: X" (même album, juste un espace en trop avant les
                // deux-points selon la source qui a fourni le tag — tag existant vs SongRec vs
                // MusicBrainz) produisent deux segments de chemin différents ("Britten _ X" vs
                // "Britten_ X"), fragmentant un même album en plusieurs dossiers — repéré en direct
                // 2026-08-26 sur "Robert Cohen/Britten : Cello Suites" (3 dossiers pour un seul
                // album).
                .replaceAll("\\s*:\\s*", ": ")
                // "/" ajouté (2026-09-01) : ce sanitizer traite UN SEUL segment de chemin — un "/" de
                // contenu (medley, voir safe() plus bas) y est tout aussi illégitime que "\"/":"/"*.
                .replaceAll("[\\\\/:*?\"<>|]", "_")
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

    /** Neutralise "/" (et "\") AVANT que la valeur n'entre dans le moteur Nashorn — ce champ est du
     *  CONTENU (titre, artiste…), jamais un séparateur de dossier ; ce rôle est réservé aux "/"
     *  littéraux du masque JS lui-même (ex. albumartist + "/" + album + "/" + title). Sans ça, un
     *  titre medley MusicBrainz légitime contenant "/" (ex. "Heroic Ewok / The Fleet Goes Into
     *  Hyperspace (Return of the Jedi)") survit jusqu'à sanitizePath(), qui scinde la chaîne PLEINE
     *  sur "/" sans distinguer un vrai séparateur d'un "/" de contenu — créant un dossier
     *  supplémentaire imprévu par piste concernée. Repéré en direct 2026-09-01 sur "Star Wars
     *  Trilogy: The Original Soundtrack Anthology" (John Williams) : un medley à deux "/" avait même
     *  créé 2 niveaux de dossiers imbriqués. */
    private String safe(String s) {
        return s == null ? "" : s.replace('/', '_').replace('\\', '_');
    }

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
                    // Même bug que celui corrigé dans rename() le 2026-08-01 (voir son commentaire
                    // ligne ~167) : moveToFolder() partage exactement la même boucle de résolution
                    // de collision, et sert au même genre de ré-exécution répétée — un fichier déjà
                    // en quarantaine (SKIPPED/ERROR, ou déplacement durée incohérente) qui se
                    // retrouve rescanné retombe ici avec fichier == sa propre cible candidate ; sans
                    // ce contrôle, Files.exists(cible) reste vrai (c'est le fichier lui-même), la
                    // boucle continue au suffixe suivant et "déplace" (2) en (3) à chaque nouveau
                    // passage, sans jamais rien corriger.
                    if (cible.equals(fichier.toAbsolutePath().normalize())) return null;
                } while (Files.exists(cible) && n < 100);
                if (Files.exists(cible))
                    throw new IOException("Impossible de déplacer '" + nom
                            + "' : 98 fichiers en collision existent déjà.");
            }

            moveFile(fichier, cible);
        }
        return cible;
    }

    /**
     * Renomme un fichier dans SON PROPRE dossier (contrairement à {@link #moveToFolder}, qui
     * change de dossier mais garde le nom) — utilisé pour nettoyer le nom d'un fichier "Non
     * identifié" (retirer un préfixe d'identifiant de catalogue, etc.) sans le déplacer. Mêmes
     * garanties de collision ((2), (3)…) que {@link #rename}/{@link #moveToFolder}.
     * @return le nouveau chemin, ou {@code null} si {@code newStem} donne le même nom qu'actuellement.
     */
    public static Path renameInPlace(Path fichier, String newStem) throws IOException {
        Path parent = fichier.getParent();
        String nom  = fichier.getFileName().toString();
        int dot      = nom.lastIndexOf('.');
        String ext   = dot > 0 ? nom.substring(dot) : "";
        String stem  = dot > 0 ? nom.substring(0, dot) : nom;
        if (newStem.isBlank() || newStem.equals(stem)) return null;

        Path cible = parent.resolve(newStem + ext).normalize();
        if (cible.equals(fichier.toAbsolutePath().normalize())) return null;

        synchronized (lockFor(cible)) {
            if (Files.exists(cible)) {
                int n = 2;
                do {
                    cible = parent.resolve(newStem + " (" + n++ + ")" + ext).normalize();
                    // Même garde que rename()/moveToFolder() (voir leurs commentaires) : si le
                    // candidat de collision retombe sur le fichier lui-même, rien à faire.
                    if (cible.equals(fichier.toAbsolutePath().normalize())) return null;
                } while (Files.exists(cible) && n < 100);
                if (Files.exists(cible))
                    throw new IOException("Impossible de renommer '" + nom
                            + "' : 98 fichiers en collision existent déjà.");
            }

            moveFile(fichier, cible);
        }
        return cible;
    }

    // ── Déplacement de fichier ────────────────────────────────────────────────

    /**
     * Point de passage unique de {@link #rename}/{@link #moveToFolder}/{@link #renameInPlace} —
     * volontairement le seul endroit où brancher {@link PlaylistSync#onFileMoved} plutôt que de le
     * dupliquer aux 3 sites d'appel : garantit qu'un futur 4e appelant de moveFile() hérite de la
     * synchronisation des playlists sans action supplémentaire.
     */
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
        PlaylistSync.onFileMoved(src, dst);
        ITunesXmlSyncQueue.onFileMoved(src, dst);
    }

    // ── Nettoyage des dossiers vides ──────────────────────────────────────────

    /** @return le nombre de dossiers effectivement supprimés (remontée des ancêtres arrêtée dès
     *  qu'un dossier contient autre chose qu'un résidu supprimable — voir {@link #isDeletableLeftover}). */
    public static int deleteEmptyAncestors(Path sourceDir, Path stopAt) {
        int removed = 0;
        Path dir = sourceDir;
        while (dir != null && !dir.equals(stopAt)) {
            if (!Files.isDirectory(dir)) { dir = dir.getParent(); continue; }
            try (Stream<Path> listing = Files.list(dir)) {
                List<Path> entries = listing.toList();
                if (!entries.stream().allMatch(FileRenamer::isDeletableLeftover)) break;
                for (Path leftover : entries) Files.deleteIfExists(leftover);
                Files.delete(dir);
                removed++;
            } catch (IOException e) { break; }
            dir = dir.getParent();
        }
        return removed;
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
        // Une pochette locale n'atteint ce point QUE si moveLocalCoverIfPresent() l'a déjà transférée
        // vers la nouvelle destination (appelé à chaque rename(), avant que ce nettoyage n'intervienne)
        // — ce qui restait ici est donc un doublon réel, jamais la seule copie existante.
        if (localCoverCandidateNames().contains(name)) return true;
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
