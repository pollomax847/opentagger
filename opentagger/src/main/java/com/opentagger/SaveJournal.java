package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Annuler un enregistrement, comme SongKong (demande de l'utilisateur, 2026-11-07 : « SongKong + Bliss = OpenTagger »).
 *
 * <p>Chaque écriture dans un fichier audio (TagWriter) et chaque déplacement (FileRenamer.moveFile) est noté dans un
 * <b>lot</b> : « Enregistrer tout », « Regrouper par album », une correction… Avant la PREMIÈRE écriture d'un fichier dans
 * le lot, ses étiquettes d'origine sont gardées dans {@code ~/.opentagger/annulation/<lot>/} : seulement les étiquettes
 * pour MP3, FLAC et M4A (voir {@link TagSnapshot}, quelques Ko à quelques Mo avec la pochette), une copie entière pour les
 * autres formats. Annuler remet ces étiquettes autour du son actuel et ramène le fichier à son ancien nom et dossier.
 *
 * <p>Garde-fous : rien n'est remis si le son a changé ; un fichier modifié depuis le lot (par un autre programme ou un
 * enregistrement plus récent) est laissé tel quel, sauf si on force. Le journal ne bloque jamais une écriture : s'il ne
 * peut pas garder les étiquettes, le fichier est marqué « non annulable » et l'écriture se fait quand même.
 *
 * <p>Un lot est ouvert par {@link #begin}. Les écritures faites hors de tout lot (synchro des écoutes, ligne de
 * commande…) vont dans un lot « Autres écritures », renouvelé après 10 minutes sans écriture.
 */
public final class SaveJournal {

    private SaveJournal() {}

    /** Ce qui est gardé pour un fichier. */
    public enum Kind {
        /** Étiquettes d'origine seulement (MP3/FLAC/M4A). */ RAW,
        /** Copie entière du fichier d'origine. */ COPY,
        /** Rien de gardé (fichier trop gros…) : seul le déplacement s'annule. */ NONE,
        /** Seulement déplacé ou renommé, pas réécrit. */ MOVE,
        /** Fichier annexe (pochette, paroles .lrc, portrait) créé ou remplacé à côté des pistes. */ SIDECAR
    }

    public enum UndoState { RESTORED, MODIFIED, FAILED, SKIPPED }

    /** Un fichier touché par un lot. */
    public static final class Entry {
        public final int n;
        Path original;
        volatile Path current;
        Kind kind = Kind.MOVE;
        long prefixLength, audioLength, size, mtime, blobBytes;
        String audioHash, blob, why = "";
        String afterHash;
        boolean afterOk = true, trashed, sidecarExisted;
        String afterWhy = "";
        UndoState undone;
        String undoMessage = "";

        Entry(int n, Path original) { this.n = n; this.original = original; this.current = original; }

        public Path original()      { return original; }
        public Path current()       { return current; }
        public Kind kind()          { return kind; }
        public boolean trashed()    { return trashed; }
        public boolean moved()      { return current != null && !current.equals(original); }
        public UndoState undone()   { return undone; }
        public String undoMessage() { return undoMessage; }
        public boolean sidecarExisted() { return sidecarExisted; }

        /** Peut-on remettre les étiquettes d'avant ? (sinon, seulement le nom et le dossier) */
        public boolean tagsRestorable() {
            return (kind == Kind.RAW && afterOk) || kind == Kind.COPY || kind == Kind.SIDECAR || kind == Kind.MOVE;
        }

        /** Pourquoi ce fichier ne pourra pas être remis entièrement, ou "". */
        public String problem() {
            if (kind == Kind.NONE) return why.isBlank() ? "étiquettes d'avant non gardées" : why;
            if (kind == Kind.RAW && !afterOk) return afterWhy;
            return "";
        }
    }

    public static final class Batch {
        public final String id, label;
        public final Instant started;
        final Path dir;
        final Map<Path, Entry> byCurrent = new HashMap<>();
        final TreeMap<Integer, Entry> entries = new TreeMap<>();
        volatile boolean closed;
        int next = 1;

        Batch(String id, String label, Instant started, Path dir) {
            this.id = id; this.label = label; this.started = started; this.dir = dir;
        }

        public synchronized List<Entry> entries() { return new ArrayList<>(entries.values()); }
        public boolean closed() { return closed; }
    }

    public record BatchInfo(String id, String label, Instant started, int files, int moved, int undone, long bytes, boolean open) {}

    /** {@code from} : où était le fichier avant l'annulation ; {@code entry.current()} : où il est maintenant. */
    public record UndoResult(Entry entry, UndoState state, String message, Path from) {
        UndoResult(Entry entry, UndoState state, String message) { this(entry, state, message, null); }
        UndoResult withFrom(Path p) { return new UndoResult(entry, state, message, p); }
    }

    // ── Réglages ────────────────────────────────────────────────────────────────────────────────────

    private static volatile Path rootOverride;

    /** Pour les tests : journal ailleurs que dans ~/.opentagger. */
    static void useRootForTests(Path p) { rootOverride = p; }

    public static Path root() {
        Path o = rootOverride;
        if (o != null) return o;
        // Les tests (voir pom.xml, surefire) écrivent leur journal dans target/ : jamais dans le vrai ~/.opentagger.
        String forTests = System.getProperty("opentagger.undo.dir", "");
        return !forTests.isBlank() ? Paths.get(forTests) : Paths.get(Config.configDir(), "annulation");
    }

    static boolean enabled() { return rootOverride != null || Config.get().bool("undo.enabled", true); }

    private static long maxCopyBytes() { return Math.max(0, Config.get().num("undo.max_copy_mb", 300)) * 1024L * 1024L; }
    private static int keepDays()      { return Math.max(1, Config.get().num("undo.keep_days", 60)); }
    private static long maxTotalBytes() { return Math.max(1, Config.get().num("undo.max_gb", 30)) * 1024L * 1024L * 1024L; }

    /** Étiquettes plus grosses que ça (pochettes énormes) : copie entière à la place. */
    private static final long MAX_TAG_BYTES = 64L * 1024 * 1024;
    private static final long MAX_SIDECAR_BYTES = 30L * 1024 * 1024;
    private static final long AUTO_GAP_MS = 10 * 60_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ID = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final AtomicInteger SEQ = new AtomicInteger();

    // ── Lots ────────────────────────────────────────────────────────────────────────────────────────

    private static final ThreadLocal<Batch> LOCAL = new ThreadLocal<>();
    /** Pendant une annulation : les déplacements et écritures qu'elle fait ne sont pas notés. */
    private static final ThreadLocal<Boolean> SUSPENDED = ThreadLocal.withInitial(() -> false);
    private static volatile Batch open;
    private static Batch auto;
    private static long autoLastMs;

    /** Ouvre un lot : toutes les écritures jusqu'à {@link #end} y sont notées. {@code null} si le journal est coupé. */
    public static Batch begin(String label) {
        if (!enabled()) return null;
        Batch b = create(label);
        if (b != null) open = b;
        return b;
    }

    /** Ferme le lot (sans effet sur un lot {@code null} ou déjà fermé). */
    public static void end(Batch b) {
        if (b == null) return;
        synchronized (b) {
            if (b.closed) return;
            b.closed = true;
        }
        append(b, Map.of("t", "fin", "fin", LocalDateTime.now().toString()));
        if (open == b) open = null;
    }

    /** Exécute {@code r} avec {@code b} comme lot du fil courant (fils d'un pool qui travaillent pour ce lot). */
    public static void run(Batch b, Runnable r) {
        Batch prev = LOCAL.get();
        LOCAL.set(b);
        try { r.run(); }
        finally { if (prev == null) LOCAL.remove(); else LOCAL.set(prev); }
    }

    static Batch current() {
        if (SUSPENDED.get() || !enabled()) return null;
        Batch b = LOCAL.get();
        if (b != null && !b.closed) return b;
        b = open;
        if (b != null && !b.closed) return b;
        synchronized (SaveJournal.class) {
            long now = System.currentTimeMillis();
            if (auto == null || auto.closed || now - autoLastMs > AUTO_GAP_MS) {
                if (auto != null) end(auto);
                auto = create(I18n.t("Autres écritures"));
            }
            autoLastMs = now;
            return auto;
        }
    }

    private static Batch create(String label) {
        try {
            prune();
            Instant now = Instant.now();
            String id = ID.format(LocalDateTime.ofInstant(now, ZoneId.systemDefault())) + "-" + String.format("%03d", SEQ.incrementAndGet() % 1000);
            Path dir = root().resolve(id);
            Files.createDirectories(dir);
            Batch b = new Batch(id, label == null ? "" : label, now, dir);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("t", "lot");
            ev.put("id", id);
            ev.put("label", b.label);
            ev.put("debut", now.toString());
            append(b, ev);
            return b;
        } catch (Exception e) {
            log("journal d'annulation indisponible : " + e.getMessage());
            return null;
        }
    }

    // ── Ce que les écrivains signalent ─────────────────────────────────────────────────────────────

    /** Avant d'écrire dans {@code f} : garde ses étiquettes d'origine (une seule fois par lot). Ne lève jamais. */
    public static void beforeWrite(File f) {
        try {
            Batch b = current();
            if (b == null || f == null) return;
            Path p = norm(f.toPath());
            if (p.startsWith(root())) return;
            Entry e;
            synchronized (b) {
                e = b.byCurrent.get(p);
                if (e != null && e.kind != Kind.MOVE) return;   // déjà gardé dans ce lot
                if (e == null) {
                    e = new Entry(b.next++, p);
                    b.byCurrent.put(p, e);
                    b.entries.put(e.n, e);
                }
            }
            capture(b, e, p);
        } catch (Throwable t) {
            log("annulation : étiquettes non gardées pour " + f + " : " + t.getMessage());
        }
    }

    /** Après une écriture dans {@code f} : note son nouvel état (pour reconnaître plus tard une modification). */
    public static void afterWrite(File f) {
        try {
            Batch b = current();
            if (b == null || f == null) return;
            Path p = norm(f.toPath());
            Entry e;
            synchronized (b) { e = b.byCurrent.get(p); }
            if (e == null) return;
            recordAfter(b, e, p);
        } catch (Throwable t) {
            log("annulation : état après écriture non noté pour " + f + " : " + t.getMessage());
        }
    }

    /** La réparation M4A va réécrire tout le fichier (son compris) : on en garde une copie entière tant qu'il est intact. */
    public static void keepWholeCopy(File f) {
        try {
            Batch b = current();
            if (b == null || f == null) return;
            Path p = norm(f.toPath());
            Entry e;
            synchronized (b) { e = b.byCurrent.get(p); }
            if (e == null || e.kind == Kind.COPY || e.kind == Kind.SIDECAR || !Files.isRegularFile(p)) return;
            long size = Files.size(p);
            if (size > maxCopyBytes()) return;
            String name = e.n + ".copie";
            Files.copy(p, b.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            String old = e.blob;
            e.kind = Kind.COPY;
            e.blob = name;
            e.blobBytes = size;
            if (old != null && !old.equals(name)) Files.deleteIfExists(b.dir.resolve(old));
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("t", "copie");
            ev.put("n", e.n);
            ev.put("blob", name);
            ev.put("octets", size);
            append(b, ev);
        } catch (Throwable t) {
            log("annulation : copie avant réparation impossible pour " + f + " : " + t.getMessage());
        }
    }

    /** Un fichier vient d'être déplacé ou renommé (appelé par FileRenamer.moveFile). */
    public static void moved(Path src, Path dst) {
        try {
            Batch b = current();
            if (b == null || src == null || dst == null) return;
            Path s = norm(src), d = norm(dst);
            if (d.startsWith(root()) || s.startsWith(root())) return;
            boolean toTrash = d.startsWith(norm(Paths.get(Config.configDir(), "corbeille")));
            Entry e;
            synchronized (b) {
                e = b.byCurrent.remove(s);
                if (e == null) {
                    if (toTrash) return;    // la corbeille de l'appli a son propre journal
                    e = new Entry(b.next++, s);
                    e.kind = AudioScanner.hasAudioExtension(s) ? Kind.MOVE : Kind.SIDECAR;
                    e.sidecarExisted = true;   // un annexe qui existait déjà et qu'on a seulement déplacé
                    b.entries.put(e.n, e);
                }
                e.current = d;
                if (toTrash) e.trashed = true;
                b.byCurrent.put(d, e);
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("t", "deplace");
            ev.put("n", e.n);
            ev.put("de", s.toString());
            ev.put("vers", d.toString());
            ev.put("sorte", e.kind.name());
            if (toTrash) ev.put("corbeille", true);
            append(b, ev);
        } catch (Throwable t) {
            log("annulation : déplacement non noté " + src + " → " + dst + " : " + t.getMessage());
        }
    }

    /** {@code p} vient de partir à la corbeille du système (doublon) : il ne pourra pas être remis d'ici. */
    public static void trashed(Path p) {
        try {
            Batch b = current();
            if (b == null || p == null) return;
            Path s = norm(p);
            Entry e;
            synchronized (b) {
                e = b.byCurrent.remove(s);
                if (e == null) return;
                e.trashed = true;
                e.current = null;
            }
            append(b, Map.of("t", "corbeille", "n", e.n, "chemin", s.toString()));
        } catch (Throwable t) {
            log("annulation : corbeille non notée pour " + p + " : " + t.getMessage());
        }
    }

    /** Avant de créer ou remplacer un fichier annexe (pochette, .lrc, portrait) : garde l'ancien s'il existe. */
    public static void beforeSidecar(Path p) {
        try {
            Batch b = current();
            if (b == null || p == null) return;
            Path s = norm(p);
            Entry e;
            synchronized (b) {
                if (b.byCurrent.containsKey(s)) return;
                e = new Entry(b.next++, s);
                e.kind = Kind.SIDECAR;
                b.byCurrent.put(s, e);
                b.entries.put(e.n, e);
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("t", "annexe");
            ev.put("n", e.n);
            ev.put("chemin", s.toString());
            if (Files.isRegularFile(s) && Files.size(s) <= MAX_SIDECAR_BYTES) {
                String name = e.n + ".annexe";
                Files.copy(s, b.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                e.sidecarExisted = true;
                e.blob = name;
                e.blobBytes = Files.size(s);
                e.mtime = Files.getLastModifiedTime(s).toMillis();
                ev.put("blob", name);
                ev.put("octets", e.blobBytes);
                ev.put("date", e.mtime);
            } else if (Files.exists(s)) {
                e.sidecarExisted = true;   // trop gros pour être gardé : il ne sera ni remis ni retiré
                ev.put("existait", true);
            }
            append(b, ev);
        } catch (Throwable t) {
            log("annulation : fichier annexe non noté " + p + " : " + t.getMessage());
        }
    }

    // ── Capture et état après ──────────────────────────────────────────────────────────────────────

    private static void capture(Batch b, Entry e, Path p) throws IOException {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("t", "avant");
        ev.put("n", e.n);
        ev.put("chemin", p.toString());
        if (!Files.isRegularFile(p)) {
            e.kind = Kind.NONE;
            e.why = "fichier absent avant l'écriture";
        } else {
            e.size = Files.size(p);
            e.mtime = Files.getLastModifiedTime(p).toMillis();
            TagSnapshot.Layout l = TagSnapshot.layout(p);
            if (l != null && l.tagBytes() <= MAX_TAG_BYTES) {
                String name = e.n + ".etiquettes";
                Path blob = b.dir.resolve(name);
                Files.deleteIfExists(blob);
                TagSnapshot.copyTags(p, l, blob);
                e.kind = Kind.RAW;
                e.blob = name;
                e.blobBytes = Files.size(blob);
                e.prefixLength = l.audioStart();
                e.audioLength = l.audioLength();
                e.audioHash = TagSnapshot.audioHash(p, l);
            } else if (e.size <= maxCopyBytes()) {
                String name = e.n + ".copie";
                Files.copy(p, b.dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                e.kind = Kind.COPY;
                e.blob = name;
                e.blobBytes = e.size;
            } else {
                e.kind = Kind.NONE;
                e.why = "fichier trop gros pour en garder une copie";
            }
        }
        ev.put("sorte", e.kind.name());
        ev.put("taille", e.size);
        ev.put("date", e.mtime);
        if (e.blob != null) { ev.put("blob", e.blob); ev.put("octets", e.blobBytes); }
        if (e.kind == Kind.RAW) {
            ev.put("debutSon", e.prefixLength);
            ev.put("tailleSon", e.audioLength);
            ev.put("empreinteSon", e.audioHash);
        }
        if (!e.why.isBlank()) ev.put("pourquoi", e.why);
        append(b, ev);
    }

    private static void recordAfter(Batch b, Entry e, Path p) throws IOException {
        String hash = null, why = "";
        boolean ok = true;
        if (!Files.isRegularFile(p)) {
            ok = false;
            why = "fichier absent après l'écriture";
        } else if (e.kind == Kind.RAW) {
            TagSnapshot.Layout l = TagSnapshot.layout(p);
            if (l == null) {
                ok = false;
                why = "fichier illisible après l'écriture : étiquettes d'avant impossibles à remettre";
            } else if (l.audioLength() != e.audioLength || !TagSnapshot.audioHash(p, l).equals(e.audioHash)) {
                ok = false;
                why = "le son a été réécrit (réparation du fichier) : étiquettes d'avant impossibles à remettre";
            } else {
                hash = TagSnapshot.tagHash(p, l);
            }
        } else {
            hash = TagSnapshot.fileHash(p);
        }
        e.afterHash = hash;
        e.afterOk = ok;
        e.afterWhy = why;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("t", "apres");
        ev.put("n", e.n);
        ev.put("chemin", p.toString());
        if (hash != null) ev.put("empreinte", hash);
        ev.put("ok", ok);
        if (!why.isBlank()) ev.put("pourquoi", why);
        append(b, ev);
    }

    // ── Lecture des lots ───────────────────────────────────────────────────────────────────────────

    /** Les lots gardés, du plus récent au plus ancien. */
    public static List<BatchInfo> list() {
        List<BatchInfo> out = new ArrayList<>();
        Path r = root();
        if (!Files.isDirectory(r)) return out;
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(r)) {
            for (Path d : ds) if (Files.isRegularFile(d.resolve("journal.jsonl"))) dirs.add(d);
        } catch (IOException e) {
            return out;
        }
        dirs.sort(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed());
        for (Path d : dirs) {
            Batch b = load(d.getFileName().toString());
            if (b == null) continue;
            List<Entry> es = b.entries();
            if (es.isEmpty()) continue;
            int moved = 0, undone = 0;
            for (Entry e : es) {
                if (e.moved()) moved++;
                if (e.undone == UndoState.RESTORED) undone++;
            }
            out.add(new BatchInfo(b.id, b.label, b.started, es.size(), moved, undone, dirSize(d), isLive(b.id)));
        }
        return out;
    }

    private static boolean isLive(String id) {
        Batch o = open, a;
        synchronized (SaveJournal.class) { a = auto; }
        return (o != null && !o.closed && o.id.equals(id)) || (a != null && !a.closed && a.id.equals(id));
    }

    /** Relit un lot depuis son journal, ou {@code null}. */
    public static Batch load(String id) {
        Path dir = root().resolve(id);
        Path j = dir.resolve("journal.jsonl");
        if (!Files.isRegularFile(j)) return null;
        List<String> lines;
        try { lines = Files.readAllLines(j, StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
        String label = "";
        Instant started = Instant.EPOCH;
        Map<Integer, Entry> es = new TreeMap<>();
        boolean closed = false;
        for (String line : lines) {
            if (line.isBlank()) continue;
            Map<?, ?> ev;
            try { ev = MAPPER.readValue(line, Map.class); }
            catch (Exception ex) { continue; }   // ligne coupée par un arrêt brutal
            String t = String.valueOf(ev.get("t"));
            if (t.equals("lot")) {
                label = str(ev.get("label"));
                try { started = Instant.parse(str(ev.get("debut"))); } catch (Exception ignored) {}
                continue;
            }
            if (t.equals("fin")) { closed = true; continue; }
            int n = num(ev.get("n"));
            if (n <= 0) continue;
            Entry e = es.get(n);
            switch (t) {
                case "avant" -> {
                    if (e == null) es.put(n, e = new Entry(n, Paths.get(str(ev.get("chemin")))));
                    e.kind = Kind.valueOf(str(ev.get("sorte")));
                    e.size = lng(ev.get("taille"));
                    e.mtime = lng(ev.get("date"));
                    e.blob = ev.get("blob") == null ? null : str(ev.get("blob"));
                    e.blobBytes = lng(ev.get("octets"));
                    e.prefixLength = lng(ev.get("debutSon"));
                    e.audioLength = lng(ev.get("tailleSon"));
                    e.audioHash = ev.get("empreinteSon") == null ? null : str(ev.get("empreinteSon"));
                    e.why = str(ev.get("pourquoi"));
                }
                case "apres" -> {
                    if (e == null) continue;
                    e.afterHash = ev.get("empreinte") == null ? null : str(ev.get("empreinte"));
                    e.afterOk = !Boolean.FALSE.equals(ev.get("ok"));
                    e.afterWhy = str(ev.get("pourquoi"));
                }
                case "copie" -> {
                    if (e == null) continue;
                    e.kind = Kind.COPY;
                    e.blob = str(ev.get("blob"));
                    e.blobBytes = lng(ev.get("octets"));
                }
                case "deplace" -> {
                    if (e == null) {
                        es.put(n, e = new Entry(n, Paths.get(str(ev.get("de")))));
                        e.kind = ev.get("sorte") == null ? Kind.MOVE : Kind.valueOf(str(ev.get("sorte")));
                        if (e.kind == Kind.SIDECAR) e.sidecarExisted = true;
                    }
                    e.current = Paths.get(str(ev.get("vers")));
                    if (Boolean.TRUE.equals(ev.get("corbeille"))) e.trashed = true;
                }
                case "corbeille" -> {
                    if (e == null) continue;
                    e.trashed = true;
                    e.current = null;
                }
                case "annexe" -> {
                    if (e == null) es.put(n, e = new Entry(n, Paths.get(str(ev.get("chemin")))));
                    e.kind = Kind.SIDECAR;
                    e.blob = ev.get("blob") == null ? null : str(ev.get("blob"));
                    e.blobBytes = lng(ev.get("octets"));
                    e.mtime = lng(ev.get("date"));
                    e.sidecarExisted = e.blob != null || Boolean.TRUE.equals(ev.get("existait"));
                }
                case "annule" -> {
                    if (e == null) continue;
                    e.undone = UndoState.valueOf(str(ev.get("etat")));
                    e.undoMessage = str(ev.get("message"));
                    if (ev.get("maintenant") != null) e.current = Paths.get(str(ev.get("maintenant")));
                }
                default -> {}
            }
        }
        Batch b = new Batch(id, label, started, dir);
        b.entries.putAll(es);
        b.next = es.isEmpty() ? 1 : es.keySet().stream().max(Integer::compare).get() + 1;
        b.closed = closed;
        for (Entry e : es.values()) if (e.current != null) b.byCurrent.put(e.current, e);
        return b;
    }

    /** Efface le journal d'un lot (on ne pourra plus l'annuler). Les fichiers de musique ne sont pas touchés. */
    public static boolean forget(String id) {
        if (id == null || id.isBlank() || id.contains("/") || id.contains("..") || isLive(id)) return false;
        return deleteTree(root().resolve(id));
    }

    // ── Annuler ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Remet les fichiers {@code which} (numéros, {@code null} = tous) du lot comme avant, du dernier touché au premier.
     * {@code force} : remettre aussi les fichiers modifiés depuis le lot.
     */
    public static List<UndoResult> undo(String id, Collection<Integer> which, boolean force, Consumer<String> progress) {
        List<UndoResult> out = new ArrayList<>();
        synchronized (SaveJournal.class) {
            if (auto != null && auto.id.equals(id)) { end(auto); auto = null; }
        }
        Batch open0 = open;
        if (open0 != null && open0.id.equals(id) && !open0.closed) {
            return out;   // lot encore en cours d'écriture
        }
        Batch b = load(id);
        if (b == null) return out;
        List<Entry> es = new ArrayList<>(b.entries.values());
        es.sort(Comparator.comparingInt((Entry e) -> e.n).reversed());
        boolean prev = SUSPENDED.get();
        SUSPENDED.set(true);
        try {
            int i = 0;
            for (Entry e : es) {
                if (which != null && !which.contains(e.n)) continue;
                if (e.undone == UndoState.RESTORED) continue;
                i++;
                if (progress != null) progress.accept(i + " — " + (e.current != null ? e.current.getFileName() : e.original.getFileName()));
                Path from = e.current;
                UndoResult r;
                try {
                    r = undoOne(b, e, force);
                } catch (Exception ex) {
                    r = new UndoResult(e, UndoState.FAILED, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                }
                r = r.withFrom(from);
                e.undone = r.state();
                e.undoMessage = r.message();
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("t", "annule");
                ev.put("n", e.n);
                ev.put("etat", r.state().name());
                ev.put("message", r.message());
                if (e.current != null) ev.put("maintenant", e.current.toString());
                appendTo(b.dir, ev);
                out.add(r);
            }
        } finally {
            SUSPENDED.set(prev);
        }
        return out;
    }

    private static UndoResult undoOne(Batch b, Entry e, boolean force) throws IOException {
        if (e.trashed && e.current == null)
            return new UndoResult(e, UndoState.SKIPPED, "envoyé à la corbeille comme doublon : à reprendre dans la corbeille");
        Path cur = e.current;
        if (cur == null || !Files.exists(cur)) {
            if (cur != null && Files.exists(e.original) && (e.kind == Kind.MOVE || e.kind == Kind.SIDECAR))
                return done(e, UndoState.RESTORED, "déjà à son ancienne place", e.original);
            return new UndoResult(e, UndoState.FAILED, "introuvable : " + cur);
        }
        String note = "";
        switch (e.kind) {
            case RAW -> {
                if (!e.afterOk) return new UndoResult(e, UndoState.FAILED, e.afterWhy);
                TagSnapshot.Layout l = TagSnapshot.layout(cur);
                if (l == null) return new UndoResult(e, UndoState.FAILED, "fichier illisible");
                if (l.audioLength() != e.audioLength || !TagSnapshot.audioHash(cur, l).equals(e.audioHash))
                    return new UndoResult(e, UndoState.FAILED, "le son du fichier a changé depuis : rien n'est remis");
                if (!force && e.afterHash != null && !TagSnapshot.tagHash(cur, l).equals(e.afterHash))
                    return new UndoResult(e, UndoState.MODIFIED, "tags modifiés depuis cet enregistrement");
                Path blob = b.dir.resolve(e.blob);
                Path tmp = temp(cur);
                try {
                    TagSnapshot.rebuild(cur, l, blob, e.prefixLength, tmp);
                    if (Files.size(tmp) != e.size) throw new IOException("taille reconstruite différente de l'originale");
                    Files.setLastModifiedTime(tmp, FileTime.fromMillis(e.mtime));
                    replace(tmp, cur);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
            case COPY -> {
                if (!force && e.afterHash != null && !TagSnapshot.fileHash(cur).equals(e.afterHash))
                    return new UndoResult(e, UndoState.MODIFIED, "modifié depuis cet enregistrement");
                restoreCopy(b.dir.resolve(e.blob), cur, e.mtime);
            }
            case SIDECAR -> {
                if (e.blob != null) {
                    if (!force && e.afterHash != null && !TagSnapshot.fileHash(cur).equals(e.afterHash))
                        return new UndoResult(e, UndoState.MODIFIED, "modifié depuis cet enregistrement");
                    restoreCopy(b.dir.resolve(e.blob), cur, e.mtime);
                } else if (!e.sidecarExisted) {
                    if (!force && e.afterHash != null && !TagSnapshot.fileHash(cur).equals(e.afterHash))
                        return new UndoResult(e, UndoState.MODIFIED, "modifié depuis cet enregistrement");
                    if (!TrashHelper.moveToTrash(cur.toFile()))
                        return new UndoResult(e, UndoState.FAILED, "impossible de le mettre à la corbeille");
                    e.current = null;
                    return new UndoResult(e, UndoState.RESTORED, "fichier ajouté par l'enregistrement : mis à la corbeille");
                }
            }
            case NONE -> note = " (étiquettes d'avant non gardées : " + e.problem() + ")";
            case MOVE -> {}
        }
        if (cur.equals(e.original)) return done(e, UndoState.RESTORED, "remis comme avant" + note, cur);
        if (Files.exists(e.original))
            return done(e, UndoState.FAILED, "un autre fichier occupe l'ancien emplacement : laissé à " + cur
                    + (e.kind == Kind.MOVE ? "" : " (tags remis)"), cur);
        if (e.original.getParent() != null) Files.createDirectories(e.original.getParent());
        FileRenamer.moveFile(cur, e.original);
        if (Config.get().deleteEmptyDirsAfterRename() && cur.getParent() != null) {
            Path stop = commonAncestor(cur.getParent(), e.original.getParent());
            if (stop != null) {
                try { FileRenamer.deleteEmptyAncestors(cur.getParent(), stop); } catch (Exception ignored) {}
            }
        }
        return done(e, UndoState.RESTORED, "remis comme avant, à son ancienne place" + note, e.original);
    }

    private static UndoResult done(Entry e, UndoState s, String msg, Path now) {
        e.current = now;
        return new UndoResult(e, s, msg);
    }

    private static void restoreCopy(Path blob, Path cur, long mtime) throws IOException {
        if (!Files.isRegularFile(blob)) throw new IOException("copie d'avant introuvable dans le journal");
        Path tmp = temp(cur);
        try {
            Files.copy(blob, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (mtime > 0) Files.setLastModifiedTime(tmp, FileTime.fromMillis(mtime));
            replace(tmp, cur);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Path temp(Path cur) {
        return cur.resolveSibling("." + cur.getFileName() + ".ot-annulation");
    }

    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path commonAncestor(Path a, Path b) {
        if (a == null || b == null) return null;
        Path x = a.toAbsolutePath().normalize();
        Path y = b.toAbsolutePath().normalize();
        while (x != null && !y.startsWith(x)) x = x.getParent();
        return x;
    }

    // ── Entretien ──────────────────────────────────────────────────────────────────────────────────

    /** Oublie les lots trop vieux, puis les plus anciens tant que le tout dépasse la place permise. */
    static void prune() {
        Path r = root();
        if (!Files.isDirectory(r)) return;
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(r)) {
            for (Path d : ds) if (Files.isDirectory(d)) dirs.add(d);
        } catch (IOException e) {
            return;
        }
        dirs.sort(Comparator.comparing(d -> d.getFileName().toString()));
        Instant limit = Instant.now().minusSeconds(keepDays() * 86_400L);
        long total = 0;
        Map<Path, Long> sizes = new LinkedHashMap<>();
        for (Path d : dirs) {
            long s = dirSize(d);
            sizes.put(d, s);
            total += s;
        }
        for (Path d : dirs) {
            String id = d.getFileName().toString();
            if (isLive(id)) continue;
            boolean old;
            try { old = Files.getLastModifiedTime(d.resolve("journal.jsonl")).toInstant().isBefore(limit); }
            catch (IOException e) { old = true; }
            if (old || total > maxTotalBytes()) {
                total -= sizes.get(d);
                deleteTree(d);
            }
        }
    }

    private static long dirSize(Path d) {
        long s = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path f : ds) if (Files.isRegularFile(f)) s += Files.size(f);
        } catch (IOException ignored) {}
        return s;
    }

    private static boolean deleteTree(Path d) {
        if (!d.toAbsolutePath().normalize().startsWith(root().toAbsolutePath().normalize())) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path f : ds) Files.deleteIfExists(f);
        } catch (IOException e) {
            return false;
        }
        try { Files.deleteIfExists(d); return true; }
        catch (IOException e) { return false; }
    }

    // ── Outils ─────────────────────────────────────────────────────────────────────────────────────

    private static void append(Batch b, Map<String, ?> ev) {
        if (b == null) return;
        appendTo(b.dir, ev);
    }

    private static final Object WRITE_LOCK = new Object();

    private static void appendTo(Path dir, Map<String, ?> ev) {
        try {
            String line = MAPPER.writeValueAsString(ev) + "\n";
            synchronized (WRITE_LOCK) {
                Files.writeString(dir.resolve("journal.jsonl"), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ex) {
            log("annulation : journal non écrit : " + ex.getMessage());
        }
    }

    private static Path norm(Path p) { return p.toAbsolutePath().normalize(); }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private static int num(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static long lng(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    private static void log(String msg) {
        System.out.println("[OT] " + msg);
    }

    /** Pour les tests : oublie le lot automatique en mémoire. */
    static void resetForTests() {
        synchronized (SaveJournal.class) { auto = null; }
        open = null;
        LOCAL.remove();
    }

    /** Pour l'interface : les fichiers d'un lot, chacun avec son état. */
    public static List<Entry> entries(String id) {
        Batch b = load(id);
        return b == null ? List.of() : b.entries();
    }

    /** Lots qui touchent aussi un des fichiers {@code paths}, plus récents que {@code id} et pas encore annulés. */
    public static List<BatchInfo> newerTouching(String id, Set<Path> paths) {
        List<BatchInfo> out = new ArrayList<>();
        for (BatchInfo bi : list()) {
            if (bi.id().compareTo(id) <= 0) continue;
            Batch b = load(bi.id());
            if (b == null) continue;
            for (Entry e : b.entries()) {
                if (e.undone == UndoState.RESTORED) continue;
                if (paths.contains(e.original) || (e.current != null && paths.contains(e.current))) { out.add(bi); break; }
            }
        }
        return out;
    }
}
