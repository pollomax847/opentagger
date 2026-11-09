package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * « opentagger --paroles » : ajoute SEULEMENT les paroles aux morceaux d'un dossier (demande de l'utilisateur,
 * 2026-11-09 : SongKong range et met les pochettes, mais ne cherche pas les paroles).
 *
 * <ul>
 *   <li>texte simple dans le fichier (champ paroles), seulement s'il n'y en a pas déjà ;</li>
 *   <li>paroles synchronisées dans un fichier .lrc à côté du morceau (même nom), seulement s'il n'existe pas ;</li>
 *   <li>rien d'autre n'est touché : titre, artiste, pochette, nom et dossier restent tels quels.</li>
 * </ul>
 * Recherche : lrclib.net en tenant compte de la durée du morceau, puis Lyrics.OVH (voir {@link LyricsClient#lookup}).
 *
 * <p>Journal {@code ~/.opentagger/paroles/journal.jsonl} : chaque morceau cherché y est noté (paroles ajoutées, pas
 * trouvées, instrumental). Relancer la même commande reprend là où elle s'était arrêtée sans refaire les recherches.
 * {@code --annuler} retire ce que la commande a ajouté (si personne ne l'a changé depuis).
 */
public final class LyricsCommand {

    /** Recherche de paroles (lrclib/Lyrics.OVH en vrai, une fausse dans les tests). */
    interface Finder {
        LyricsClient.Found find(String artist, String title, String album, int durationSec, boolean wantPlain);
    }

    enum Outcome { WRITTEN, FOUND, ABSENT, NO_SYNC, INSTRUMENTAL, ALREADY, KNOWN, NO_TAGS, UNSUPPORTED, ERROR, SKIPPED }

    private static final Set<String> DONE = Set.of("ecrit", "absent", "instrumental");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    static final int SIMULATION_LIMIT = 30;

    private final Path stateDir;
    private final Finder finder;
    private final PrintStream out;
    private final boolean simulation;
    private final int threads;
    private final TagWriter writer = new TagWriter();
    private final Map<String, JsonNode> journal = new ConcurrentHashMap<>();
    private final Map<Outcome, AtomicInteger> counts = new EnumMap<>(Outcome.class);
    private final AtomicInteger lrcWritten = new AtomicInteger();
    private final AtomicInteger embedded = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private final AtomicBoolean stop = new AtomicBoolean();
    private BufferedWriter journalOut;
    private int limit = Integer.MAX_VALUE;
    private int total;
    private final AtomicInteger seen = new AtomicInteger();
    private volatile long lastReport = System.currentTimeMillis();

    LyricsCommand(Path stateDir, Finder finder, PrintStream out, boolean simulation, int threads) {
        this.stateDir = stateDir;
        this.finder = finder;
        this.out = out;
        this.simulation = simulation;
        this.threads = Math.max(1, threads);
        for (Outcome o : Outcome.values()) counts.put(o, new AtomicInteger());
    }

    static Path defaultStateDir() {
        String p = System.getProperty("opentagger.paroles.dir");
        return p != null ? Paths.get(p) : Paths.get(System.getProperty("user.home"), ".opentagger", "paroles");
    }

    public static void main(String[] args) {
        App.configureTagLibrary();
        LyricsClient client = new LyricsClient();
        int code = run(args, System.out, client::lookup, defaultStateDir(), 4, true);
        System.out.flush();
        System.exit(code);
    }

    static void usage(PrintStream out) {
        out.println("opentagger --paroles : ajoute seulement les paroles (rien d'autre n'est touché).");
        out.println();
        out.println("  opentagger --paroles <dossier>                 ajoute les paroles à tous les morceaux du dossier");
        out.println("  opentagger --paroles --simulation <dossier>    montre ce qui serait trouvé (30 morceaux), sans rien changer");
        out.println("  opentagger --paroles --limite N <dossier>      s'arrête après N morceaux cherchés");
        out.println("  opentagger --paroles --annuler <dossier>       retire les paroles ajoutées par cette commande");
        out.println("  --malgre-songkong                              travailler pendant SongKong (dossier qu'il ne touche pas)");
        out.println();
        out.println("  Texte simple dans le fichier, paroles synchronisées dans un fichier .lrc à côté (même nom).");
        out.println("  Relancer la même commande reprend là où elle s'était arrêtée (Ctrl+C sans risque).");
        out.println("  Journal : ~/.opentagger/paroles/journal.jsonl");
    }

    /** @param guardSongKong refuser de travailler pendant que songkong-tout tourne (faux dans les tests). */
    static int run(String[] args, PrintStream out, Finder finder, Path stateDir, int threads, boolean guardSongKong) {
        boolean simulation = false, undo = false, ignoreSongKong = false;
        int limit = -1;
        List<File> roots = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--simulation", "-s" -> simulation = true;
                case "--annuler" -> undo = true;
                case "--malgre-songkong" -> ignoreSongKong = true;
                case "--limite" -> {
                    if (i + 1 >= args.length) { out.println("✗ --limite : il manque le nombre."); return 2; }
                    try { limit = Integer.parseInt(args[++i]); }
                    catch (NumberFormatException e) { out.println("✗ --limite : « " + args[i] + " » n'est pas un nombre."); return 2; }
                }
                case "--aide", "-h", "--help" -> { usage(out); return 0; }
                default -> {
                    if (a.startsWith("-")) { out.println("✗ option inconnue : " + a); usage(out); return 2; }
                    File f = new File(a);
                    if (!f.exists()) { out.println("✗ introuvable : " + a); return 2; }
                    roots.add(f.getAbsoluteFile());
                }
            }
        }
        if (roots.isEmpty()) { usage(out); return 2; }
        if (guardSongKong && !simulation && !ignoreSongKong) {
            String busy = songKongRunning();
            if (busy != null) {
                out.println("✗ SongKong travaille encore (songkong-tout) : il réécrit les mêmes fichiers.");
                out.println("  Relancer cette commande quand il a fini (ou ajouter --malgre-songkong pour un dossier qu'il ne touche pas).");
                return 1;
            }
        }
        LyricsCommand cmd = new LyricsCommand(stateDir, finder, out, simulation, simulation ? 1 : threads);
        if (limit > 0) cmd.limit = limit;
        else if (simulation && !undo) cmd.limit = SIMULATION_LIMIT;
        try {
            Files.createDirectories(stateDir);
            try (FileChannel ch = FileChannel.open(stateDir.resolve("verrou"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock = ch.tryLock();
                if (lock == null) { out.println("✗ une autre commande « opentagger --paroles » tourne déjà."); return 1; }
                try {
                    cmd.loadJournal();
                    return undo ? cmd.undo(roots) : cmd.add(roots);
                } finally {
                    lock.release();
                }
            }
        } catch (IOException e) {
            out.println("✗ " + e.getMessage());
            return 1;
        }
    }

    /** Ligne de commande de songkong-tout s'il tourne, sinon null. */
    static String songKongRunning() {
        return ProcessHandle.allProcesses()
                .map(ph -> ph.info().commandLine().orElse(""))
                .filter(c -> c.contains("songkong-tout") || c.contains("songkong_tout.sh"))
                .findFirst().orElse(null);
    }

    // ── Journal ──────────────────────────────────────────────────────────────────────────────────────────────

    private Path journalPath() { return stateDir.resolve("journal.jsonl"); }

    void loadJournal() throws IOException {
        Path p = journalPath();
        if (!Files.isRegularFile(p)) return;
        try (var lines = Files.lines(p, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                try {
                    JsonNode n = JSON.readTree(line);
                    String f = n.path("f").asText("");
                    if (!f.isEmpty()) journal.put(f, n);
                } catch (Exception ignored) {}   // ligne coupée par un arrêt brutal : ignorée
            });
        }
    }

    private synchronized void record(String path, String result, boolean tag, boolean lrc, String lyrics, String synced,
                                     String source) {
        if (simulation) return;
        ObjectNode n = JSON.createObjectNode();
        n.put("f", path);
        n.put("r", result);
        if (tag) { n.put("t", true); n.put("ht", hash(lyrics)); }
        if (lrc) { n.put("l", true); n.put("hl", hash(synced)); }
        if (source != null && !source.isEmpty()) n.put("s", source);
        n.put("d", LocalDateTime.now().withNano(0).toString());
        try {
            if (journalOut == null)
                journalOut = Files.newBufferedWriter(journalPath(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            journalOut.write(JSON.writeValueAsString(n));
            journalOut.newLine();
            journalOut.flush();
        } catch (IOException e) {
            out.println("⚠ journal impossible à écrire : " + e.getMessage());
        }
        journal.put(path, n);
    }

    private synchronized void closeJournal() {
        if (journalOut != null) {
            try { journalOut.close(); } catch (IOException ignored) {}
            journalOut = null;
        }
    }

    static String hash(String s) {
        try {
            String norm = s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').trim();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(norm.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    static Path lrcOf(Path audio) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return audio.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".lrc");
    }

    private static String key(File f) { return f.getAbsoluteFile().toPath().normalize().toString(); }

    // ── Ajouter les paroles ──────────────────────────────────────────────────────────────────────────────────

    int add(List<File> roots) {
        out.println((simulation ? "Simulation (rien ne change) — " : "") + "recherche des morceaux…");
        LinkedHashSet<String> files = new LinkedHashSet<>();
        AudioScanner scanner = new AudioScanner();
        for (File r : roots) {
            if (r.isDirectory()) scanner.scan(r, f -> files.add(key(f)), () -> false, true);
            else if (AudioScanner.hasAudioExtension(r.toPath())) files.add(key(r));
        }
        List<String> todo = new ArrayList<>();
        for (String f : files) {
            JsonNode j = journal.get(f);
            if (j != null && DONE.contains(j.path("r").asText(""))) counts.get(Outcome.KNOWN).incrementAndGet();
            else todo.add(f);
        }
        total = todo.size();
        out.println(files.size() + " morceaux, dont " + counts.get(Outcome.KNOWN).get()
                + " déjà cherchés lors d'une passe précédente : " + total + " à voir."
                + (limit < Integer.MAX_VALUE ? " (arrêt après " + limit + " recherches)" : ""));
        Thread hook = new Thread(() -> {
            if (!stop.get()) out.println("\n↷ arrêté. Ce qui est fait est noté : relancer la même commande pour continuer.");
            closeJournal();
        });
        if (!simulation) Runtime.getRuntime().addShutdownHook(hook);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (String f : todo) pool.submit(() -> {
                Outcome o;
                if (stop.get() || lookups.get() >= limit) o = Outcome.SKIPPED;
                else {
                    try { o = processOne(new File(f)); }
                    catch (Throwable t) { o = Outcome.ERROR; out.println("  ✗ " + f + " : " + t); }
                }
                counts.get(o).incrementAndGet();
                progress();
            });
            pool.shutdown();
            while (!pool.awaitTermination(1, TimeUnit.MINUTES)) { /* attente */ }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        } finally {
            stop.set(true);
            closeJournal();
            if (!simulation) try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}
        }
        summary();
        return 0;
    }

    private void progress() {
        int n = seen.incrementAndGet();
        long now = System.currentTimeMillis();
        if (simulation || (n % 500 != 0 && now - lastReport < 60_000 && n != total)) return;
        lastReport = now;
        out.println("[" + LocalDateTime.now().format(CLOCK) + "] " + n + "/" + total
                + " — paroles ajoutées : " + embedded.get() + ", .lrc : " + lrcWritten.get()
                + ", pas trouvées : " + counts.get(Outcome.ABSENT).get()
                + ", déjà là : " + counts.get(Outcome.ALREADY).get()
                + (counts.get(Outcome.ERROR).get() > 0 ? ", erreurs : " + counts.get(Outcome.ERROR).get() : ""));
    }

    private void summary() {
        out.println();
        out.println(simulation ? "Simulation terminée : rien n'a été changé." : "Terminé.");
        if (simulation) {
            out.println("  paroles trouvées pour " + counts.get(Outcome.FOUND).get() + " morceaux sur "
                    + lookups.get() + " cherchés, pas trouvées : " + counts.get(Outcome.ABSENT).get());
        } else {
            out.println("  paroles ajoutées dans " + embedded.get() + " fichiers, " + lrcWritten.get() + " fichiers .lrc créés");
            out.println("  pas trouvées : " + counts.get(Outcome.ABSENT).get()
                    + ", instrumentaux : " + counts.get(Outcome.INSTRUMENTAL).get());
        }
        if (counts.get(Outcome.NO_SYNC).get() > 0)
            out.println("  paroles déjà dans le fichier, sans version synchronisée trouvée (.lrc) : " + counts.get(Outcome.NO_SYNC).get());
        out.println("  déjà là : " + counts.get(Outcome.ALREADY).get()
                + ", déjà cherchés avant : " + counts.get(Outcome.KNOWN).get()
                + ", sans artiste ou titre : " + counts.get(Outcome.NO_TAGS).get()
                + ", format non pris en charge : " + counts.get(Outcome.UNSUPPORTED).get());
        if (counts.get(Outcome.ERROR).get() > 0)
            out.println("  erreurs : " + counts.get(Outcome.ERROR).get() + " (réseau…) — relancer la même commande pour les retenter");
        if (counts.get(Outcome.SKIPPED).get() > 0)
            out.println("  pas encore vus : " + counts.get(Outcome.SKIPPED).get() + " — relancer la même commande pour continuer");
        if (!simulation && (embedded.get() > 0 || lrcWritten.get() > 0))
            out.println("  Pour tout retirer : opentagger --paroles --annuler <dossier>");
    }

    Outcome processOne(File f) {
        String path = key(f);
        if (!f.isFile()) return Outcome.SKIPPED;   // « Album.mp3/ » : un dossier au nom de fichier audio
        String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (FfmpegTagIO.handles(f) || lower.endsWith(".mpc")) return Outcome.UNSUPPORTED;
        String artist, title, album, existing;
        int duration;
        try {
            Config.silenceJaudiotaggerLogging();
            AudioFile audio = AudioFileIO.read(f);
            Tag tag = audio.getTag();
            artist = tag == null ? "" : tag.getFirst(FieldKey.ARTIST);
            if (artist.isBlank() && tag != null) artist = tag.getFirst(FieldKey.ALBUM_ARTIST);
            title = tag == null ? "" : tag.getFirst(FieldKey.TITLE);
            album = tag == null ? "" : tag.getFirst(FieldKey.ALBUM);
            existing = tag == null ? "" : tag.getFirst(FieldKey.LYRICS);
            duration = (int) Math.round(audio.getAudioHeader().getPreciseTrackLength());
        } catch (Exception e) {
            if (simulation) out.println("  ✗ illisible : " + path);
            return Outcome.UNSUPPORTED;
        }
        Path lrc = lrcOf(f.toPath());
        boolean hasLrc = Files.exists(lrc);
        boolean wantPlain = existing.isBlank();
        if (!wantPlain && hasLrc) return Outcome.ALREADY;
        if (artist.isBlank() || title.isBlank()) return Outcome.NO_TAGS;
        if (lookups.incrementAndGet() > limit) { lookups.decrementAndGet(); return Outcome.SKIPPED; }

        LyricsClient.Found found = finder.find(artist, title, album, duration, wantPlain);
        String who = artist + " — " + title;
        if (found.isError()) {
            out.println("  ⚠ " + who + " : " + found.error() + " (sera retenté)");
            return Outcome.ERROR;
        }
        if (found.instrumental()) {
            if (simulation) out.println("  ∅ " + who + " : instrumental");
            record(path, "instrumental", false, false, null, null, found.source());
            return Outcome.INSTRUMENTAL;
        }
        String plain = wantPlain ? found.plain() : "";
        String synced = hasLrc ? "" : found.synced();
        if (plain.isBlank() && synced.isBlank()) {
            // Paroles déjà dans le fichier, seul le .lrc manquait : ce n'est pas « pas trouvées ».
            if (simulation) out.println(wantPlain ? "  ✗ " + who + " : pas trouvées"
                    : "  · " + who + " : paroles déjà dans le fichier, pas de version synchronisée");
            record(path, "absent", false, false, null, null, null);
            return wantPlain ? Outcome.ABSENT : Outcome.NO_SYNC;
        }
        if (simulation) {
            out.println("  ♪ " + who + " : " + (plain.isBlank() ? "" : "paroles")
                    + (!plain.isBlank() && !synced.isBlank() ? " + " : "")
                    + (synced.isBlank() ? "" : ".lrc synchronisé") + " (" + found.source() + ")");
            return Outcome.FOUND;
        }
        boolean tagOk = false, lrcOk = false;
        if (!plain.isBlank()) {
            try { writer.writeLyricsOnly(f, plain); tagOk = true; embedded.incrementAndGet(); }
            catch (Exception e) { out.println("  ✗ " + path + " : écriture impossible (" + e.getMessage() + ")"); }
        }
        if (!synced.isBlank()) {
            try {
                Files.writeString(lrc, synced.endsWith("\n") ? synced : synced + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                lrcOk = true;
                lrcWritten.incrementAndGet();
            } catch (java.nio.file.FileAlreadyExistsException e) {
                // créé entre-temps par un autre programme : on le laisse
            } catch (IOException e) {
                out.println("  ✗ " + lrc + " : " + e.getMessage());
            }
        }
        if (!tagOk && !lrcOk) return Outcome.ERROR;
        record(path, "ecrit", tagOk, lrcOk, plain, synced, found.source());
        return Outcome.WRITTEN;
    }

    // ── Annuler ──────────────────────────────────────────────────────────────────────────────────────────────

    int undo(List<File> roots) {
        List<String> prefixes = new ArrayList<>();
        for (File r : roots) prefixes.add(key(r));
        int lrcRemoved = 0, tagRemoved = 0, changed = 0, missing = 0, entries = 0;
        for (Map.Entry<String, JsonNode> e : journal.entrySet()) {
            String path = e.getKey();
            JsonNode n = e.getValue();
            if (!"ecrit".equals(n.path("r").asText(""))) continue;
            boolean inside = false;
            for (String p : prefixes) inside |= path.equals(p) || path.startsWith(p + File.separator);
            if (!inside) continue;
            entries++;
            File f = new File(path);
            if (!f.isFile()) { missing++; continue; }
            boolean any = false;
            if (n.path("l").asBoolean(false)) {
                Path lrc = lrcOf(f.toPath());
                try {
                    if (Files.isRegularFile(lrc) && hash(Files.readString(lrc, StandardCharsets.UTF_8)).equals(n.path("hl").asText())) {
                        if (!simulation) Files.delete(lrc);
                        lrcRemoved++;
                        any = true;
                    } else if (Files.exists(lrc)) changed++;
                } catch (IOException ex) {
                    out.println("  ✗ " + lrc + " : " + ex.getMessage());
                }
            }
            if (n.path("t").asBoolean(false)) {
                String current = TagWriter.readLyrics(f);
                if (!current.isBlank() && hash(current).equals(n.path("ht").asText())) {
                    try {
                        if (!simulation) writer.writeLyricsOnly(f, "");
                        tagRemoved++;
                        any = true;
                    } catch (Exception ex) {
                        out.println("  ✗ " + path + " : " + ex.getMessage());
                        continue;
                    }
                } else if (!current.isBlank()) changed++;
            }
            if (any || !simulation) record(path, "annule", false, false, null, null, null);
        }
        closeJournal();
        out.println((simulation ? "Simulation (rien ne change) : " : "Annulé : ") + entries + " morceaux concernés");
        out.println("  paroles retirées des fichiers : " + tagRemoved + ", fichiers .lrc supprimés : " + lrcRemoved);
        if (changed > 0) out.println("  laissés tels quels (modifiés depuis par autre chose) : " + changed);
        if (missing > 0) out.println("  fichiers disparus ou déplacés depuis : " + missing);
        return 0;
    }
}
