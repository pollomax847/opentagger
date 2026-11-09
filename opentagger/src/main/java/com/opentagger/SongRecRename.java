package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * songrec-rename, intégré à OpenTagger (2026-11-07, demande de l'utilisateur : « que songrec-rename soit un outil
 * intégré à OpenTagger, pour supprimer cadence proprement »). Portage fidèle de
 * {@code cadence/utils/songrec_rename_cli.py} : mêmes options, mêmes messages, même journal (repris tel quel, la passe
 * en cours continue où elle en était).
 *
 * <pre>
 * songrec-rename [-r] [-i] [-m RACINE] [-d [--delete]] [--keep-playlists DOSSIER] [-s] [-n] [--retry-failed] [chemin]
 * songrec-rename --merge-artists -m RACINE [-d [--delete]] [--keep-playlists DOSSIER] [-n]
 * </pre>
 * <ul>
 *   <li>-r : sous-dossiers compris ;</li>
 *   <li>-i : écrire titre, artiste, album, année, genre. Si le morceau reconnu n'est pas celui des anciens tags, ceux-ci
 *       sont d'abord effacés (artiste de l'album, paroles, numéros, identifiants…) ; seuls restent ceux du son (BPM,
 *       ReplayGain, empreinte, note, commentaire, pochette) ;</li>
 *   <li>-m RACINE : ranger en {@code Artiste/Album (Année)/Artiste - Album - NN - Titre.ext} sous RACINE ; un artiste
 *       déjà présent sous une autre graphie ou en collaboration va dans son dossier existant ;</li>
 *   <li>-d : écarter les vrais doublons (même artiste, même titre ET même son, empreinte Chromaprint), en gardant la
 *       meilleure qualité ; les écartés vont en quarantaine ({@code _doublons_songrec} à côté de la bibliothèque),
 *       ou sont supprimés avec --delete ;</li>
 *   <li>--keep-playlists DOSSIER : ne jamais toucher aux morceaux cités par les .m3u/.m3u8 de DOSSIER (freyr) ;</li>
 *   <li>--merge-artists : fusionner les dossiers d'artiste éclatés sous RACINE, puis quitter ;</li>
 *   <li>--retry-failed, -s (déjà rangés ignorés), -n (simulation).</li>
 * </ul>
 * Reconnaissance : SongRec (Shazam) par {@link SongRecClient}, sinon AcoustID ({@link AcoustIdClient}, avec la clé des
 * Préférences), sinon les tags existants. Chaque écriture et chaque déplacement passent par le journal d'annulation
 * d'OpenTagger ({@link SaveJournal}) : Édition → Annuler un enregistrement.
 *
 * <p>Avec -r et (-m ou -d), chaque fichier traité est noté dans {@code ~/.opentagger/songrec-rename/} (l'ancien
 * journal de {@code ~/.local/state/songrec-rename/} y est déplacé à la première passe) : une passe interrompue reprend
 * où elle en était, sans rappeler Shazam.
 */
public final class SongRecRename {

    // ── Constantes (mêmes valeurs que l'outil Python) ─────────────────────────────────────────────────────────────

    static final Set<String> AUDIO_EXTENSIONS = Set.of(".mp3", ".flac", ".wav", ".aif", ".aiff", ".ogg", ".m4a",
            ".dsf", ".wv", ".ape", ".opus", ".alac");
    static final Set<String> LOSSLESS_EXTENSIONS = Set.of(".flac", ".wav", ".aif", ".aiff", ".dsf", ".wv", ".ape", ".alac");
    static final List<String> SIDECAR_EXTENSIONS = List.of(".lrc");
    static final Set<String> LEFTOVER_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".lrc", ".txt",
            ".nfo", ".m3u", ".m3u8", ".cue", ".log", ".sfv", ".ini", ".db", ".url");
    /** Mesuré sur la bibliothèque le 2026-11-01 : même enregistrement ≥ 0,93, versions ou morceaux différents ≤ 0,57. */
    static final double SAME_SOUND_MIN = 0.85;
    static final double SAME_SOUND_MAX_DRIFT = 3.0;
    static final int FINGERPRINT_MAX_SHIFT = 48;
    static final int FINGERPRINT_MIN_ITEMS = 40;
    static final String NAVIDROME_LIBRARY_PREFIX = "../_mybook/";
    static final double KEEP_BOTH_BITRATE_RATIO = 1.25;
    static final int MIN_LEAD_TRACKS = 5;
    static final double SAME_NAME_MIN = 0.80;
    static final double OTHER_NAME_MAX = 0.60;

    private static final Set<String> BOGUS_TOKENS = Set.of("-artiste-", "-titre-", "musique", "unknown album",
            "unknown artist", "inconnu");

    private static final Pattern FEATURING = Pattern.compile("(?iU)[\\s(\\[]+(?:feat|ft|featuring)\\b.*$");
    private static final Pattern COLLAB = Pattern.compile("(?iU)\\s*[,;]\\s*|\\s+(?:&|\\+|x|vs\\.?|and|et|with)\\s+");
    static final Pattern NOT_AN_ARTIST = Pattern.compile("(?iU)\\d+|(?:cd|dis[ck])\\W*\\d+|compilations?|various artists|va"
            + "|unknown(?: artist)?|inconnu|divers|singles|downloads|music|musique|non-album|a classer");
    private static final Pattern VERSION_TAIL = Pattern.compile("(?U)\\s+-\\s+.*$");
    private static final Pattern BRACKETS = Pattern.compile("(?U)\\s*[(\\[][^)\\]]*[)\\]]");
    private static final Pattern CHANNEL = Pattern.compile("(?iU)\\s*-\\s*topic$|vevo$");

    private static final ObjectMapper JSON = new ObjectMapper();

    // ── Ce que la reconnaissance donne d'un fichier ───────────────────────────────────────────────────────────────

    /** Identité d'un fichier (le dict « info » de l'outil Python). */
    static final class Info {
        String title = "", artist = "", album = "", year = "", genre = "", track = "";

        Info() {}

        Info(String artist, String title) { this.artist = nz(artist); this.title = nz(title); }

        static Info of(TagInfo t) {
            Info i = new Info(t.artist, t.title);
            i.album = nz(t.album);
            i.year = nz(t.year).length() >= 4 ? t.year.substring(0, 4) : nz(t.year);
            i.genre = nz(t.genre);
            return i;
        }
    }

    /** Source de reconnaissance (interface pour les tests : Shazam et AcoustID ne sont jamais appelés par eux). */
    interface Recognizer {
        /** Identité, ou {@code null} si rien n'est reconnu. */
        Info recognize(File f) throws Exception;

        /** Vrai si le dernier « rien » venait d'un blocage (Shazam limite les appels), pas d'une vraie absence. */
        default boolean lastWasThrottled() { return false; }
    }

    static Recognizer shazam() {
        SongRecClient client = new SongRecClient();
        return new Recognizer() {
            @Override public Info recognize(File f) throws Exception {
                TagInfo t = client.recognize(f);
                return t == null || nz(t.title).isBlank() || nz(t.artist).isBlank() ? null : Info.of(t);
            }
            @Override public boolean lastWasThrottled() { return SongRecClient.wasThrottled(); }
        };
    }

    static Recognizer acoustid() {
        AcoustIdClient client = new AcoustIdClient();
        return f -> {
            if (Config.get().acoustidKey().isBlank()) return null;
            for (TagInfo t : client.identify(f)) {
                if (!nz(t.title).isBlank() && !nz(t.artist).isBlank()) return Info.of(t);
            }
            return null;
        };
    }

    // ── Petits outils de noms (identiques à l'outil Python) ──────────────────────────────────────────────────────

    static String nz(String s) { return s == null ? "" : s; }

    static boolean isBogusTag(String value) {
        String lower = nz(value).toLowerCase(Locale.ROOT);
        for (String t : BOGUS_TOKENS) if (lower.contains(t)) return true;
        return lower.equals("00") || lower.isEmpty();
    }

    /** Extension audio : la sienne si connue ; devinée d'après les premiers octets seulement quand le nom n'a pas de
     *  vraie extension (« _mp3 (105) ») ; "" si ce n'est pas de l'audio. */
    static String audioExt(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (AUDIO_EXTENSIONS.contains(ext)) return ext;
        if (ext.matches("\\.[a-z0-9]{1,5}")) return "";
        byte[] head = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(p)) {
            n = in.readNBytes(head, 0, 12);
        } catch (IOException e) {
            return "";
        }
        if (n >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return ".mp3";
        if (n >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) return ".mp3";
        if (n >= 4 && head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') return ".flac";
        if (n >= 4 && head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') return ".ogg";
        if (n >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') return ".m4a";
        if (n >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E') return ".wav";
        return "";
    }

    static String sanitize(String name) {
        String s = nz(name).replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "");
        return s.replaceAll("(?U)\\s+", " ").strip();
    }

    /** Tronque {@code stem} pour que {@code stem+ext} tienne en 255 octets UTF-8, sans couper un caractère. */
    static String fitFilenameBytes(String stem, String ext) {
        int budget = 255 - ext.getBytes(StandardCharsets.UTF_8).length;
        byte[] b = stem.getBytes(StandardCharsets.UTF_8);
        if (b.length <= budget) return stem;
        for (int len = Math.max(0, budget); len > 0; len--) {
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(b, 0, len)).toString().stripTrailing();
            } catch (CharacterCodingException ignored) {
                // caractère coupé : un octet de moins
            }
        }
        return "";
    }

    private static String typography(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '‐', '‑', '‒', '–', '—' -> b.append('-');
                case '‘', '’' -> b.append('\'');
                case '⁄', '∕' -> { }
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /** Forme propre d'un nom de dossier : sans « _ », sans typographie, sans point final (interdit sous Windows). */
    static String cleanFolderName(String name) {
        String s = typography(Normalizer.normalize(nz(name), Normalizer.Form.NFC)).replace('_', ' ');
        return sanitize(s).replaceAll("[ .]+$", "");
    }

    /** Clé de comparaison : ni casse, ni ponctuation, ni accents, ni « The » initial, ni « & »/« and »/« et ». */
    static String folderKey(String name) {
        String text = Normalizer.normalize(nz(name).replace('_', ' '), Normalizer.Form.NFKD);
        text = text.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace("ß", "ss").strip();
        text = text.replaceFirst("^the\\s+(?!the$)", "");
        text = text.replaceAll("(?U)(?<=\\w)\\s+(?:and|et)\\s+(?=\\w)", " ");
        return text.replaceAll("(?U)[\\W_]+", "");
    }

    /** « X feat. Y » → « X ». */
    static String stripFeaturing(String artist) {
        String s = FEATURING.matcher(nz(artist)).replaceAll("").strip();
        return s.isEmpty() ? nz(artist) : s;
    }

    /** Premier nom d'une collaboration : « X, Y & Z » → « X ». */
    static String leadArtist(String artist) {
        return COLLAB.split(stripFeaturing(artist), 2)[0].strip();
    }

    /** Faux pour « david guetta » et « DAVID GUETTA », vrai pour « David Guetta » et les sigles courts (« ABBA »). */
    static boolean wellCased(String name) {
        int cased = 0, upper = 0;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            if (Character.isUpperCase(cp)) { upper++; cased++; }
            else if (Character.isLowerCase(cp)) cased++;
            i += Character.charCount(cp);
        }
        return cased == 0 || (upper > 0 && (upper < cased || cased <= 5));
    }

    // ── Même morceau ou autre morceau ? (même règle que l'outil Python, ratio de difflib) ────────────────────────

    /** {@code difflib.SequenceMatcher(None, a, b).ratio()} (sans « junk » : les clés sont courtes). */
    static double ratio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) return 1.0;
        return 2.0 * matches(a, 0, a.length(), b, 0, b.length()) / total;
    }

    private static int matches(String a, int alo, int ahi, String b, int blo, int bhi) {
        int besti = alo, bestj = blo, bestSize = 0;
        int[] prev = new int[bhi - blo + 1];
        for (int i = alo; i < ahi; i++) {
            int[] cur = new int[bhi - blo + 1];
            for (int j = blo; j < bhi; j++) {
                if (a.charAt(i) == b.charAt(j)) {
                    int k = prev[j - blo] + 1;
                    cur[j - blo + 1] = k;
                    if (k > bestSize) { besti = i - k + 1; bestj = j - k + 1; bestSize = k; }
                }
            }
            prev = cur;
        }
        if (bestSize == 0) return 0;
        return bestSize + matches(a, alo, besti, b, blo, bestj) + matches(a, besti + bestSize, ahi, b, bestj + bestSize, bhi);
    }

    static Set<String> artistKeys(String artist) {
        String a = CHANNEL.matcher(nz(artist).strip()).replaceAll("");
        List<String> names = new ArrayList<>();
        names.add(a);
        names.add(stripFeaturing(a));
        names.addAll(List.of(COLLAB.split(stripFeaturing(a))));
        Matcher feat = FEATURING.matcher(a);
        if (feat.find()) {
            String guests = feat.group().replaceFirst("(?iU)^[\\s(\\[]*(?:feat|ft|featuring)\\.?\\s*", "");
            names.addAll(List.of(COLLAB.split(guests.replaceAll("^[ )\\]]+|[ )\\]]+$", ""))));
        }
        Set<String> keys = new HashSet<>();
        for (String n : names) { String k = folderKey(n); if (!k.isEmpty()) keys.add(k); }
        return keys;
    }

    static String titleKey(String title) {
        return folderKey(VERSION_TAIL.matcher(BRACKETS.matcher(nz(title)).replaceAll(" ")).replaceAll(""));
    }

    static boolean usableArtist(String artist) {
        return !nz(artist).isEmpty() && !isBogusTag(artist) && !NOT_AN_ARTIST.matcher(artist.strip()).matches();
    }

    static boolean usableTitle(String title) {
        return !nz(title).isEmpty() && !isBogusTag(title) && !titleKey(title).isEmpty();
    }

    static double artistScore(String a, String b) {
        Set<String> ka = artistKeys(a), kb = artistKeys(b);
        for (String k : ka) if (kb.contains(k)) return 1.0;
        double best = 0;
        for (String x : ka) for (String y : kb) best = Math.max(best, ratio(x, y));
        return best;
    }

    /** Les deux identités nomment-elles clairement le même morceau ? */
    static boolean memeMorceau(Info old, Info nu) {
        if (!(usableArtist(old.artist) && usableArtist(nu.artist) && usableTitle(old.title) && usableTitle(nu.title)))
            return false;
        return artistScore(old.artist, nu.artist) >= SAME_NAME_MIN
                && ratio(titleKey(old.title), titleKey(nu.title)) >= SAME_NAME_MIN;
    }

    /** Deux morceaux clairement différents ? Chaque critère ne compte que s'il est exploitable des deux côtés. */
    static boolean autreMorceau(Info old, Info nu) {
        if (usableArtist(old.artist) && usableArtist(nu.artist) && artistScore(old.artist, nu.artist) < OTHER_NAME_MAX)
            return true;
        return usableTitle(old.title) && usableTitle(nu.title) && ratio(titleKey(old.title), titleKey(nu.title)) < OTHER_NAME_MAX;
    }

    /** Numéro de piste des anciens tags, seulement s'ils décrivent le même morceau que celui reconnu. */
    static String trackFromOldTags(Info existing, Info info) {
        return existing != null && !existing.track.isEmpty() && memeMorceau(existing, info) ? existing.track : "";
    }

    static String trackFromFilename(Path p) {
        Matcher m = Pattern.compile("^(\\d{1,3})\\b").matcher(stem(p));
        if (m.find()) return String.format("%02d", Integer.parseInt(m.group(1)));
        return "";
    }

    // ── Lecture des tags existants ────────────────────────────────────────────────────────────────────────────────

    static AudioFile readAudio(Path p) throws Exception {
        Config.silenceJaudiotaggerLogging();
        String ext = audioExt(p);
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!ext.isEmpty() && !name.endsWith(ext)) return AudioFileIO.readAs(p.toFile(), ext.substring(1));
        return AudioFileIO.read(p.toFile());
    }

    /** Titre, artiste, album, année, piste (sur 2 chiffres) des tags actuels ; {@code null} sans titre ni artiste valables. */
    static Info readExistingTags(Path p) {
        try {
            String title, artist, album, date, track;
            if (FfmpegTagIO.handles(p.toFile())) {
                TagInfo t = FfmpegTagIO.read(p.toFile());
                if (t == null) return null;
                title = nz(t.title); artist = nz(t.artist); album = nz(t.album); date = nz(t.year); track = nz(t.track);
            } else {
                Tag tag = readAudio(p).getTag();
                if (tag == null) return null;
                title = nz(tag.getFirst(FieldKey.TITLE)); artist = nz(tag.getFirst(FieldKey.ARTIST));
                album = nz(tag.getFirst(FieldKey.ALBUM)); date = nz(tag.getFirst(FieldKey.YEAR));
                track = nz(tag.getFirst(FieldKey.TRACK));
            }
            title = title.strip(); artist = artist.strip(); album = album.strip(); date = date.strip();
            String t = track.contains("/") ? track.substring(0, track.indexOf('/')).strip() : track.strip();
            try { t = t.isEmpty() ? "" : String.format("%02d", Integer.parseInt(t)); }
            catch (NumberFormatException e) { t = ""; }
            if (title.isEmpty() || artist.isEmpty() || isBogusTag(title) || isBogusTag(artist)) return null;
            Info i = new Info(artist, title);
            i.album = isBogusTag(album) ? "" : album;
            i.year = date.length() >= 4 ? date.substring(0, 4) : date;
            i.track = t;
            return i;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Les options ───────────────────────────────────────────────────────────────────────────────────────────────

    static final class Options {
        String path = ".";
        boolean recursive, tag, dedupe, delete, mergeArtists, retryFailed, skipOrganized, dryRun;
        String move, keepPlaylists;

        static Options parse(String[] args) {
            Options o = new Options();
            boolean pathSeen = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-r", "--recursive" -> o.recursive = true;
                    case "-i", "--id3" -> o.tag = true;
                    case "-d", "--dedupe" -> o.dedupe = true;
                    case "--delete" -> o.delete = true;
                    case "--merge-artists" -> o.mergeArtists = true;
                    case "--retry-failed" -> o.retryFailed = true;
                    case "-s", "--skip-organized" -> o.skipOrganized = true;
                    case "-n", "--dry-run" -> o.dryRun = true;
                    case "-m", "--move" -> o.move = value(args, ++i, a);
                    case "--keep-playlists" -> o.keepPlaylists = value(args, ++i, a);
                    case "-h", "--help" -> throw new IllegalArgumentException(USAGE);
                    default -> {
                        if (a.startsWith("-") && a.length() > 2 && !a.startsWith("--") && a.chars().skip(1).allMatch(c -> "ridsn".indexOf(c) >= 0)) {
                            for (char c : a.substring(1).toCharArray()) {
                                switch (c) {
                                    case 'r' -> o.recursive = true;
                                    case 'i' -> o.tag = true;
                                    case 'd' -> o.dedupe = true;
                                    case 's' -> o.skipOrganized = true;
                                    case 'n' -> o.dryRun = true;
                                    default -> { }
                                }
                            }
                        } else if (a.startsWith("-")) {
                            throw new IllegalArgumentException("option inconnue : " + a + "\n" + USAGE);
                        } else if (!pathSeen) {
                            o.path = a;
                            pathSeen = true;
                        } else {
                            throw new IllegalArgumentException("un seul chemin à traiter\n" + USAGE);
                        }
                    }
                }
            }
            return o;
        }

        private static String value(String[] args, int i, String opt) {
            if (i >= args.length) throw new IllegalArgumentException(opt + " attend une valeur\n" + USAGE);
            return args[i];
        }
    }

    static final String USAGE = """
            Usage :
              songrec-rename [-r] [-i] [-m RACINE] [-d [--delete]] [--keep-playlists DOSSIER] [-s] [-n] [--retry-failed] [chemin]
              songrec-rename --merge-artists -m RACINE [-d [--delete]] [--keep-playlists DOSSIER] [-n]
            Reconnaît chaque morceau par Shazam (SongRec), le renomme « Artiste - Titre » ou le range sous RACINE
            (Artiste/Album (Année)/Artiste - Album - NN - Titre.ext), écrit ses tags (-i) et écarte les vrais doublons (-d).
            -n simule sans rien changer. Annulable dans OpenTagger : Édition → Annuler un enregistrement.""";

    // ── Le journal de passe (reprise sans rappeler Shazam) ────────────────────────────────────────────────────────

    static Path stateDir() {
        String forTests = System.getProperty("opentagger.songrec.state", "");
        return forTests.isBlank() ? Paths.get(Config.configDir(), "songrec-rename") : Paths.get(forTests);
    }

    /** Ancien dossier de l'outil Python : son journal est repris (déplacé) à la première passe. */
    static Path oldStateDir() {
        String xdg = System.getenv("XDG_STATE_HOME");
        return (xdg == null || xdg.isBlank() ? Paths.get(System.getProperty("user.home"), ".local", "state") : Paths.get(xdg))
                .resolve("songrec-rename");
    }

    static String journalName(String root) {
        return root.replaceAll("(?U)\\W+", "_").replaceAll("^_+|_+$", "") + ".jsonl";
    }

    final class Journal implements AutoCloseable {
        final Path path;
        final boolean dryRun;
        final Map<String, Map<String, Object>> done = new LinkedHashMap<>();
        private BufferedWriter writer;
        private FileChannel channel;
        private FileLock lock;

        Journal(String root, boolean dryRun, boolean retryFailed) throws IOException {
            Path state = stateDir();
            Files.createDirectories(state);
            this.path = state.resolve(journalName(root));
            this.dryRun = dryRun;
            Path old = oldStateDir().resolve(journalName(root));
            if (!Files.exists(path) && Files.isRegularFile(old) && System.getProperty("opentagger.songrec.state", "").isBlank()) {
                Files.move(old, path);
                out.println("Journal de l'ancien songrec-rename repris : " + path);
            }
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Map<String, Object> rec;
                    try {
                        @SuppressWarnings("unchecked") Map<String, Object> m = JSON.readValue(line, LinkedHashMap.class);
                        rec = m;
                    } catch (Exception e) {
                        continue;   // dernière ligne coupée par un arrêt brutal
                    }
                    if (rec.containsKey("doublon")) done.remove(String.valueOf(rec.get("doublon")));
                    else if (rec.containsKey("fichier") && !(retryFailed && Boolean.TRUE.equals(rec.get("echec"))))
                        done.put(String.valueOf(rec.get("fichier")), rec);
                }
            }
            if (!dryRun) {
                channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new IllegalStateException("une autre passe tourne déjà sur " + root);
                }
                writer = new BufferedWriter(java.nio.channels.Channels.newWriter(channel, StandardCharsets.UTF_8));
            }
        }

        void write(Map<String, Object> record) {
            if (dryRun) return;
            try {
                writer.write(JSON.writeValueAsString(record));
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                out.println("  ⚠ journal non écrit : " + e.getMessage());
            }
        }

        @Override public void close() {
            try { if (writer != null) writer.close(); } catch (IOException ignored) {}
            try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) {}
            try { if (channel != null) channel.close(); } catch (IOException ignored) {}
        }
    }

    static Map<String, Object> rec(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── Les dossiers d'artistes, quelle que soit leur graphie ─────────────────────────────────────────────────────

    final class FolderIndex {
        final Path parent;
        final Set<String> pinned;
        final Map<String, List<String>> variants = new HashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private final Map<String, FolderIndex> children = new HashMap<>();

        FolderIndex(Path parent, Set<String> pinned) {
            this.parent = parent;
            this.pinned = pinned;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent)) {
                for (Path e : ds) {
                    String name = e.getFileName().toString();
                    String key = folderKey(name);
                    if (!key.isEmpty() && Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS))
                        variants.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
                }
            } catch (IOException ignored) {
                // dossier absent : vide
            }
        }

        int audioCount(String name) {
            return counts.computeIfAbsent(name, n -> {
                try (Stream<Path> s = Files.walk(parent.resolve(n))) {
                    return (int) s.filter(Files::isRegularFile).filter(f -> AUDIO_EXTENSIONS.contains(suffix(f))).count();
                } catch (Exception e) {
                    return 0;
                }
            });
        }

        /** Dossier existant pour {@code name} (la meilleure graphie s'il y en a plusieurs), ou {@code null}. */
        String find(String name) {
            String key = folderKey(name);
            List<String> names = key.isEmpty() ? null : variants.get(key);
            if (names == null || names.isEmpty()) return null;
            if (names.size() == 1) return names.get(0);
            for (String c : names) if (pinned.contains(c)) return c;
            String best = null;
            int[] bestScore = null;
            for (String n : names) {
                String clean = cleanFolderName(n);
                int[] score = {wellCased(clean) ? 1 : 0, audioCount(n), n.equals(clean) ? 1 : 0};
                if (best == null || java.util.Arrays.compare(score, bestScore) > 0) { best = n; bestScore = score; }
            }
            return best;
        }

        String add(String name) {
            String n = fitFilenameBytes(cleanFolderName(name), "").replaceAll("[ .]+$", "");
            if (n.isEmpty()) n = "Inconnu";
            variants.computeIfAbsent(folderKey(n), k -> new ArrayList<>()).add(n);
            return n;
        }

        String resolve(String name) {
            String f = find(name);
            return f != null ? f : add(name);
        }

        /** Dossier d'un artiste ; une collaboration va chez son premier artiste s'il a déjà une vraie discographie ici. */
        String artistFolder(String artist) {
            String name = stripFeaturing(cleanFolderName(artist));
            String full = find(name);
            String lead = leadArtist(name);
            boolean collab = !folderKey(lead).equals(folderKey(name)) && !NOT_AN_ARTIST.matcher(lead).matches();
            String leadFolder = collab ? find(lead) : null;
            if (leadFolder != null && audioCount(leadFolder) >= Math.max(MIN_LEAD_TRACKS, full != null ? audioCount(full) : 0))
                return leadFolder;
            return full != null ? full : add(name);
        }

        FolderIndex child(String name) {
            return children.computeIfAbsent(name, n -> new FolderIndex(parent.resolve(n), Set.of()));
        }
    }

    // ── Les doublons (même artiste, même titre ET même son) ──────────────────────────────────────────────────────

    record Quality(double length, boolean lossless, long bitrate, long size) {
        int compareRank(Quality o) {
            int c = Boolean.compare(lossless, o.lossless);
            if (c != 0) return c;
            c = Long.compare(bitrate, o.bitrate);
            return c != 0 ? c : Long.compare(size, o.size);
        }
    }

    static Quality audioQuality(Path p) {
        try {
            long size = Files.size(p);
            double length = 0;
            long bitrate = 0;
            boolean lossless = LOSSLESS_EXTENSIONS.contains(audioExt(p));
            try {
                AudioHeader h = readAudio(p).getAudioHeader();
                length = h.getPreciseTrackLength();
                bitrate = h.getBitRateAsNumber();
                lossless = lossless || h.isLossless();
            } catch (Exception e) {
                length = AudioDuration.probeSeconds(p.toString());
                if (length <= 0) return null;
                bitrate = (long) (size * 8 / 1000 / length);
            }
            return length > 0 ? new Quality(length, lossless, bitrate, size) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private final Map<String, int[]> fingerprints = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> e) { return size() > 512; }
    };

    int[] fingerprint(Path p) {
        return fingerprints.computeIfAbsent(p.toString(), k -> {
            try { return Fingerprinter.computeRaw(p.toFile()).ints(); }
            catch (Exception e) { return new int[0]; }
        });
    }

    /** Vrai si les deux fichiers sont le même enregistrement. Dans le doute (empreinte impossible) : faux. */
    boolean sameSound(Path a, Path b) {
        return sameSound(fingerprint(a), fingerprint(b));
    }

    static boolean sameSound(int[] first, int[] second) {
        if (Math.min(first.length, second.length) < FINGERPRINT_MIN_ITEMS) return false;
        for (int shift = -FINGERPRINT_MAX_SHIFT; shift <= FINGERPRINT_MAX_SHIFT; shift++) {
            int xs = Math.max(shift, 0), ys = Math.max(-shift, 0);
            int compared = Math.min(first.length - xs, second.length - ys);
            if (compared < FINGERPRINT_MIN_ITEMS) continue;
            long differing = 0;
            for (int i = 0; i < compared; i++) differing += Integer.bitCount(first[xs + i] ^ second[ys + i]);
            if (1.0 - differing / (32.0 * compared) >= SAME_SOUND_MIN) return true;
        }
        return false;
    }

    final class Duplicates {
        final Path root;
        final Set<String> protectedPaths;
        final boolean delete, dryRun;
        final Journal journal;
        final Map<String, List<String>> known = new HashMap<>();
        int removed;
        long freed;
        final Path quarantine;

        Duplicates(Path root, boolean delete, boolean dryRun, Journal journal, Set<String> protectedPaths) {
            this.root = root;
            this.protectedPaths = protectedPaths;
            this.delete = delete;
            this.dryRun = dryRun;
            this.journal = journal;
            boolean sameDisk;
            try {
                sameDisk = root.getParent() != null
                        && Files.getAttribute(root.getParent(), "unix:dev").equals(Files.getAttribute(root, "unix:dev"));
            } catch (Exception e) {
                sameDisk = false;
            }
            this.quarantine = sameDisk ? root.getParent().resolve("_doublons_songrec") : root.resolve(".doublons");
        }

        String key(Info info) {
            return folderKey(leadArtist(stripFeaturing(cleanFolderName(info.artist)))) + "\u0000" + folderKey(info.title);
        }

        void register(Path p, Info info) {
            known.computeIfAbsent(key(info), k -> new ArrayList<>()).add(p.toString());
        }

        /** Compare {@code p} aux fichiers déjà vus du même morceau. Faux s'il vient d'être écarté. */
        boolean check(Path p, Info info) throws IOException {
            List<String> candidates = known.getOrDefault(key(info), new ArrayList<>());
            for (String other : new ArrayList<>(candidates)) {
                Info tags = readExistingTags(Paths.get(other));   // à lire avant qu'il ne disparaisse
                Path loser = settle(p, Paths.get(other));
                if (p.equals(loser)) return false;
                if (loser != null) {
                    candidates.remove(other);
                    // Shazam ne donne pas toujours l'album : le fichier gardé reprend celui de la copie écartée
                    if (tags != null && !tags.album.isEmpty() && info.album.isEmpty()) {
                        info.album = tags.album;
                        info.year = tags.year;
                        info.track = tags.track;
                    }
                }
            }
            return true;
        }

        /** Si {@code a} et {@code b} ont le même son, écarte le moins bon ({@code a} à qualité égale) et le renvoie. */
        Path settle(Path a, Path b) throws IOException {
            if (!(Files.exists(a) && Files.exists(b)) || Files.isSameFile(a, b)) return null;
            Quality qa = audioQuality(a), qb = audioQuality(b);
            if (qa == null || qb == null || Math.abs(qa.length() - qb.length()) > SAME_SOUND_MAX_DRIFT) return null;
            if (!sameSound(a, b)) return null;
            Path loser, kept;
            if (qa.compareRank(qb) <= 0) { loser = a; kept = b; } else { loser = b; kept = a; }
            boolean pa = protectedPaths.contains(a.toString()), pb = protectedPaths.contains(b.toString());
            if (pa || pb) {
                if (pa && pb) return null;
                if (pa) { loser = b; kept = a; } else { loser = a; kept = b; }
                Quality rl = loser == b ? qb : qa, rk = loser == b ? qa : qb;
                if ((rl.lossless() && !rk.lossless()) || rl.bitrate() > rk.bitrate() * KEEP_BOTH_BITRATE_RATIO) return null;
            }
            drop(loser, kept);
            return loser;
        }

        void drop(Path p, Path kept) throws IOException {
            long size = Files.size(p);
            out.println("  ✂ doublon écarté : " + p + "\n    gardé : " + kept);
            removed++;
            freed += size;
            if (dryRun) return;
            for (String ext : SIDECAR_EXTENSIONS) {
                Path lyrics = withSuffix(p, ext), keptLyrics = withSuffix(kept, ext);
                if (Files.exists(lyrics) && Files.exists(keptLyrics)) remove(lyrics);
                else if (Files.exists(lyrics)) FileRenamer.moveFile(lyrics, keptLyrics);
            }
            remove(p);
            if (journal != null) journal.write(rec("doublon", p.toString(), "garde", kept.toString(), "octets", size));
        }

        void remove(Path p) throws IOException {
            if (delete) {
                Files.delete(p);
                return;
            }
            Path relative = p.startsWith(root) ? root.relativize(p) : p.getFileName();
            Path target = quarantine;
            for (int i = 0; i < relative.getNameCount() - 1; i++) {
                String part = relative.getName(i).toString();
                String clean = part.replaceAll("[ .]+$", "");
                target = target.resolve(clean.isEmpty() ? part : clean);   // NTFS refuse un nom finissant par un point
            }
            target = freePath(target.resolve(relative.getFileName().toString()));
            Files.createDirectories(target.getParent());
            FileRenamer.moveFile(p, target);
        }
    }

    // ── Fichiers, playlists, dossiers ─────────────────────────────────────────────────────────────────────────────

    static String suffix(Path p) {
        String n = p.getFileName() == null ? "" : p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    static String stem(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static Path withSuffix(Path p, String ext) {
        return p.resolveSibling(stem(p) + ext);
    }

    /** {@code p}, ou « nom (2).ext », « nom (3).ext »… si la place est prise. */
    static Path freePath(Path p) {
        Path candidate = p;
        String ext = p.getFileName().toString().contains(".") ? p.getFileName().toString().substring(p.getFileName().toString().lastIndexOf('.')) : "";
        for (int n = 2; Files.exists(candidate); n++) candidate = p.resolveSibling(stem(p) + " (" + n + ")" + ext);
        return candidate;
    }

    /** Déplace un audio avec ses paroles (.lrc du même nom). */
    static void moveFile(Path source, Path dest) throws IOException {
        List<Path[]> companions = new ArrayList<>();
        for (String ext : SIDECAR_EXTENSIONS) companions.add(new Path[]{withSuffix(source, ext), withSuffix(dest, ext)});
        FileRenamer.moveFile(source, dest);
        for (Path[] c : companions) if (Files.exists(c[0]) && !Files.exists(c[1])) FileRenamer.moveFile(c[0], c[1]);
    }

    /** Comme {@code urllib.parse.unquote} : seulement les %XX (un « + » reste un « + »). */
    static String unquote(String s) {
        if (s.indexOf('%') < 0) return s;
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        StringBuilder outStr = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                bytes.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                if (bytes.size() > 0) { outStr.append(bytes.toString(StandardCharsets.UTF_8)); bytes.reset(); }
                outStr.append(c);
            }
        }
        if (bytes.size() > 0) outStr.append(bytes.toString(StandardCharsets.UTF_8));
        return outStr.toString();
    }

    private static boolean isHex(char c) { return Character.digit(c, 16) >= 0; }

    /** Fichiers cités par les .m3u/.m3u8 de {@code folder}, en chemins absolus ; un « ../_toshiba/ » mène à un autre
     *  disque et n'est pas dans la bibliothèque traitée. */
    static Set<String> playlistFiles(Path folder, String library) throws IOException {
        Set<String> files = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path playlist : ds) {
                String suf = suffix(playlist);
                if (!suf.equals(".m3u") && !suf.equals(".m3u8")) continue;
                String text = new String(Files.readAllBytes(playlist), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    line = unquote(line);
                    if (line.startsWith("../") && !line.startsWith(NAVIDROME_LIBRARY_PREFIX)) continue;
                    if (line.startsWith(NAVIDROME_LIBRARY_PREFIX)) line = line.substring(NAVIDROME_LIBRARY_PREFIX.length());
                    files.add(Paths.get(library).resolve(line).normalize().toString());
                }
            }
        }
        return files;
    }

    /** Remet au chemin attendu les morceaux de playlists renommés par une passe précédente (un seul candidat). */
    int restorePlaylistFiles(Set<String> files, String library, boolean dryRun) throws IOException {
        int restored = 0;
        for (String e : new TreeSet<>(files)) {
            if (!e.startsWith(library + File.separator) || Files.exists(Paths.get(e))) continue;
            Path expected = Paths.get(e);
            String title = folderKey(stem(expected).replaceFirst("^\\d+\\s+", ""));
            List<Path> siblings = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(expected.getParent())) {
                for (Path f : ds) siblings.add(f);
            } catch (IOException ex) {
                continue;
            }
            siblings.sort(PATH_ORDER);
            List<Path> candidates = new ArrayList<>();
            for (Path f : siblings) {
                if (suffix(f).equals(suffix(expected)) && !files.contains(f.toString()) && !title.isEmpty()
                        && folderKey(stem(f)).contains(title)) candidates.add(f);
            }
            if (candidates.size() == 1) {
                out.println("  ↺ " + candidates.get(0).getFileName() + "  →  " + expected.getFileName());
                restored++;
                if (!dryRun) moveFile(candidates.get(0), expected);
            }
        }
        return restored;
    }

    /** Tags d'un fichier déjà renommé « Artiste - Titre » et tagué par une passe précédente, sinon {@code null}. */
    static Info earlierIdentification(Path p) {
        Info tags = readExistingTags(p);
        if (tags != null && folderKey(stem(p)).equals(folderKey(tags.artist + " - " + tags.title))) return tags;
        return null;
    }

    /** Remonte de {@code folder} jusqu'à {@code top} (exclu) en retirant les dossiers vides ; avec {@code dupes}, un
     *  dossier qui ne contient plus que des restes sans audio (pochette, paroles orphelines…) part aussi. */
    void pruneFolders(Path folder, Path top, Duplicates dupes) {
        try {
            while (folder != null && folder.startsWith(top) && !folder.equals(top)) {
                List<Path> entries = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
                    for (Path e : ds) entries.add(e);
                }
                List<Path> leftovers = new ArrayList<>();
                for (Path e : entries)
                    if (Files.isRegularFile(e, LinkOption.NOFOLLOW_LINKS) && LEFTOVER_EXTENSIONS.contains(suffix(e))) leftovers.add(e);
                if (leftovers.size() < entries.size() || (!entries.isEmpty() && dupes == null)) return;
                for (Path e : leftovers) dupes.remove(e);
                Files.delete(folder);
                folder = folder.getParent();
            }
        } catch (IOException ignored) {
            // dossier occupé ou disparu : on s'arrête là
        }
    }

    /** Ordre de {@code sorted()} sur des Path Python : nom par nom. */
    static final Comparator<Path> PATH_ORDER = (a, b) -> {
        int n = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < n; i++) {
            int c = a.getName(i).toString().compareTo(b.getName(i).toString());
            if (c != 0) return c;
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    };

    static List<Path> collectFiles(Path path, boolean recursive) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(path)) {
            if (!audioExt(path).isEmpty()) files.add(path);
        } else if (Files.isDirectory(path)) {
            try (Stream<Path> s = recursive ? Files.walk(path) : Files.list(path)) {
                s.filter(p -> !p.equals(path)).filter(p -> Files.isRegularFile(p)).filter(p -> !audioExt(p).isEmpty())
                        .forEach(files::add);
            }
            files.sort(PATH_ORDER);
        }
        return files;
    }

    static boolean isOrganized(Path p, Path root) {
        return p.startsWith(root) && root.relativize(p).getNameCount() >= 3;
    }

    // ── Fusion des dossiers d'artiste ─────────────────────────────────────────────────────────────────────────────

    void mergeFile(Path source, Path dest, Duplicates dupes, boolean dryRun, Journal journal) throws IOException {
        if (!Files.exists(source)) return;   // .lrc déjà parti avec son audio
        if (Files.exists(dest)) {
            if (!AUDIO_EXTENSIONS.contains(suffix(source))) {
                if (dupes != null && Files.mismatch(source, dest) == -1L) {
                    if (!dryRun) dupes.remove(source);
                    return;
                }
            } else if (dupes != null) {
                Path loser = dupes.settle(source, dest);
                if (source.equals(loser)) return;
            }
        }
        if (dryRun) return;
        Path target = freePath(dest);
        moveFile(source, target);
        if (journal != null) journal.write(rec("fusion", source.toString(), "vers", target.toString()));
    }

    void mergeTree(Path source, Path dest, Duplicates dupes, boolean dryRun, Journal journal) throws IOException {
        if (!Files.exists(dest)) {
            if (!dryRun) {
                Files.move(source, dest);
                SaveJournal.moved(source, dest);
                if (journal != null) journal.write(rec("fusion", source.toString(), "vers", dest.toString()));
            }
            return;
        }
        FolderIndex albums = new FolderIndex(dest, Set.of());
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(source)) {
            for (Path e : ds) entries.add(e);
        }
        entries.sort(Comparator.comparing(e -> e.getFileName().toString()));
        for (Path e : entries) {
            String name = e.getFileName().toString();
            if (Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS)) {
                String found = albums.find(name);
                String clean = name.replaceAll("[ .]+$", "");
                mergeTree(e, dest.resolve(found != null ? found : clean.isEmpty() ? name : clean), dupes, dryRun, journal);
            } else {
                mergeFile(e, dest.resolve(name), dupes, dryRun, journal);
            }
        }
        if (!dryRun) {
            try { Files.delete(source); } catch (IOException ignored) {}
        }
    }

    int[] mergeArtistFolders(FolderIndex index, Duplicates dupes, boolean dryRun, Journal journal) {
        Path root = index.parent;
        int merged = 0, failed = 0;
        List<String> names = new ArrayList<>();
        for (List<String> v : index.variants.values()) names.addAll(v);
        names.sort(null);
        for (String name : names) {
            if (index.pinned.contains(name) || NOT_AN_ARTIST.matcher(cleanFolderName(name)).matches()) continue;
            String target = index.artistFolder(name);
            if (!index.pinned.contains(target)) target = cleanFolderName(target);
            if (target.equals(name)) continue;
            out.println(name + "  →  " + target);
            try {
                mergeTree(root.resolve(name), root.resolve(target), dupes, dryRun, journal);
                merged++;
            } catch (IOException e) {
                out.println("  ⚠ erreur : " + e.getMessage());
                failed++;
            }
        }
        return new int[]{merged, failed};
    }

    // ── Rangement et renommage ────────────────────────────────────────────────────────────────────────────────────

    /** Range sous {@code root}/{Artiste}/{Album} ({Année})/{Artiste} - {Album} - NN - {Titre}.ext (sans album :
     *  {Artiste}/Singles/{Artiste} - NN - {Titre}.ext). Renvoie le nouveau chemin, ou {@code null}. */
    Path moveToLidarr(Path p, Info info, Path root, boolean dryRun, FolderIndex folders, Duplicates dupes, Path top) throws IOException {
        String artist = folders.artistFolder(info.artist);
        String title = sanitize(info.title);
        String album = sanitize(info.album);
        String ext = audioExt(p);
        String track = !info.track.isEmpty() ? info.track : trackFromFilename(p);
        if (track.isEmpty()) track = "00";
        FolderIndex albums = folders.child(artist);
        String artistName = cleanFolderName(artist);
        String albumFolder, stemName;
        if (!album.isEmpty()) {
            albumFolder = albums.resolve(info.year.isEmpty() ? album : album + " (" + info.year + ")");
            stemName = artistName + " - " + album + " - " + track + " - " + title;
        } else {
            albumFolder = albums.resolve("Singles");
            stemName = artistName + " - " + track + " - " + title;
        }
        Path destDir = root.resolve(artist).resolve(albumFolder);
        Path dest = destDir.resolve(fitFilenameBytes(stemName, ext) + ext);
        if (dest.equals(p)) {
            out.println("  = déjà à la bonne place");
            return null;
        }
        if (Files.exists(dest)) {
            Path loser = dupes != null ? dupes.settle(p, dest) : null;
            if (p.equals(loser)) return null;
            if (loser == null) {
                out.println("  ⚠ conflit : " + dest + " existe déjà, déplacement ignoré");
                return null;
            }
        }
        out.println("  → " + dest);
        if (dryRun) {
            out.println("  [dry-run, pas de déplacement]");
            return null;
        }
        Files.createDirectories(destDir);
        moveFile(p, dest);
        pruneFolders(p.getParent(), top != null ? top : root, dupes);
        return dest;
    }

    /** Renomme en « Artiste - Titre.ext ». */
    Path doRename(Path p, Info info, boolean dryRun, Duplicates dupes) throws IOException {
        String ext = audioExt(p);
        String newName = fitFilenameBytes(sanitize(info.artist) + " - " + sanitize(info.title), ext) + ext;
        Path newPath = p.resolveSibling(newName);
        if (newPath.equals(p)) {
            out.println("  = nom déjà correct");
            return null;
        }
        if (Files.exists(newPath)) {
            Path loser = dupes != null ? dupes.settle(p, newPath) : null;
            if (p.equals(loser)) return null;
            if (loser == null) {
                out.println("  ⚠ conflit : " + newName + " existe déjà, ignoré");
                return null;
            }
        }
        out.println("  " + p.getFileName() + "\n  → " + newName);
        if (dryRun) {
            out.println("  [dry-run, pas de renommage]");
            return null;
        }
        moveFile(p, newPath);
        return newPath;
    }

    /** Écrit titre, artiste, album, année, genre ; efface d'abord l'ancienne identité si c'est un autre morceau. */
    boolean writeTags(Path p, Info info, boolean dryRun) {
        Info old = readExistingTags(p);
        boolean other = old != null && autreMorceau(old, info);
        if (dryRun) {
            if (other) out.println("  [dry-run] autre morceau que « " + old.artist + " — " + old.title + " » : anciens tags effacés");
            out.println("  [dry-run] tags : " + describe(info, " / "));
            return true;
        }
        try {
            TagInfo t = new TagInfo();
            t.title = info.title;
            t.artist = info.artist;
            t.album = info.album;
            t.year = info.year;
            t.genre = info.genre;
            int n = new TagWriter().writeIdentityOnly(p.toFile(), audioExt(p), t, other);
            if (other) out.println("  ✂ autre morceau que « " + old.artist + " — " + old.title + " » : " + n + " ancien(s) tag(s) effacé(s)");
            out.println("  ♬ " + describe(info, " — "));
            return true;
        } catch (Exception e) {
            out.println("  ⚠ erreur tags : " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return false;
        }
    }

    private static String describe(Info info, String sep) {
        List<String> parts = new ArrayList<>();
        parts.add(info.artist + sep + info.title);
        if (!info.album.isEmpty()) parts.add("album : " + info.album);
        if (!info.year.isEmpty()) parts.add("année : " + info.year);
        if (!info.genre.isEmpty()) parts.add("genre : " + info.genre);
        return String.join(", ", parts);
    }

    // ── La passe ──────────────────────────────────────────────────────────────────────────────────────────────────

    final PrintStream out;
    private final Recognizer primary, fallback;
    /** Attente quand Shazam bloque encore après les essais de SongRecClient (avant de réessayer le même fichier). */
    long throttleWaitMs = 10 * 60_000L;

    SongRecRename(PrintStream out, Recognizer primary, Recognizer fallback) {
        this.out = out;
        this.primary = primary;
        this.fallback = fallback;
    }

    int run(Options a) throws Exception {
        Path path = Paths.get(a.path).toAbsolutePath().normalize();
        if (!Files.exists(path)) { out.println("Erreur : chemin introuvable : " + path); return 1; }
        if (a.move != null && !Files.isDirectory(Paths.get(a.move))) { out.println("Erreur : racine introuvable : " + a.move); return 1; }
        if (a.mergeArtists && a.move == null) { out.println("Erreur : --merge-artists nécessite -m RACINE"); return 2; }
        if (a.delete && !a.dedupe) { out.println("Erreur : --delete nécessite -d"); return 2; }
        if (a.keepPlaylists != null && !Files.isDirectory(Paths.get(a.keepPlaylists))) {
            out.println("Erreur : dossier de playlists introuvable : " + a.keepPlaylists);
            return 2;
        }
        if (a.dedupe && FpcalcInstaller.resolve() == null) {
            out.println("Erreur : fpcalc introuvable (paquet libchromaprint-tools), -d ne peut pas comparer le son.");
            return 1;
        }
        Path root = a.move != null ? Paths.get(a.move).toAbsolutePath().normalize() : null;
        String library = root != null ? root.toString() : (Files.isDirectory(path) ? path.toString() : path.getParent().toString());
        Journal journal = null;
        if (a.mergeArtists || (a.recursive && (a.move != null || a.dedupe))) journal = new Journal(library, a.dryRun, a.retryFailed);
        // Un lot d'annulation pour toute la passe : Édition → Annuler un enregistrement, dans OpenTagger.
        SaveJournal.Batch undo = a.dryRun ? null : SaveJournal.begin("songrec-rename : " + path);
        Thread hook = new Thread(() -> SaveJournal.end(undo));
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return pass(a, path, root, library, journal);
        } finally {
            SaveJournal.end(undo);
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) {}
            if (journal != null) journal.close();
        }
    }

    private int pass(Options a, Path path, Path root, String library, Journal journal) throws Exception {
        Set<String> protectedPaths = a.keepPlaylists != null ? playlistFiles(Paths.get(a.keepPlaylists), library) : Set.of();
        if (!protectedPaths.isEmpty()) {
            out.println(protectedPaths.size() + " morceau(x) de playlists protégé(s).");
            int restored = restorePlaylistFiles(protectedPaths, library, a.dryRun);
            if (restored > 0) out.println(restored + " morceau(x) de playlists remis à leur nom.");
        }
        Duplicates dupes = a.dedupe ? new Duplicates(Paths.get(library), a.delete, a.dryRun, journal, protectedPaths) : null;
        Set<String> pinned = new HashSet<>();
        for (String p : protectedPaths) {
            if (p.startsWith(library + File.separator)) pinned.add(p.substring(library.length() + 1).split(Pattern.quote(File.separator))[0]);
        }
        FolderIndex folders = root != null ? new FolderIndex(root, pinned) : null;

        if (a.mergeArtists) {
            int[] r = mergeArtistFolders(folders, dupes, a.dryRun, journal);
            List<String> parts = new ArrayList<>(List.of(r[0] + " dossier(s) fusionné(s) ou renommé(s)", r[1] + " erreur(s)"));
            if (dupes != null) parts.add(dupes.removed + " doublon(s) écarté(s), " + String.format(Locale.FRANCE, "%.1f", dupes.freed / 1e9) + " Go");
            out.println("\nTerminé : " + String.join(", ", parts) + ".");
            return r[1] > 0 ? 1 : 0;
        }

        List<Path> files = collectFiles(path, a.recursive);
        if (dupes != null) {
            Duplicates d = dupes;
            files.removeIf(f -> f.startsWith(d.quarantine));
            if (journal != null) {
                for (Map<String, Object> rec : journal.done.values()) {
                    Object artiste = rec.get("artiste");
                    if (artiste != null && !String.valueOf(artiste).isEmpty())
                        dupes.register(Paths.get(String.valueOf(rec.get("fichier"))), new Info(String.valueOf(artiste), String.valueOf(rec.get("titre"))));
                }
            }
        }
        if (files.isEmpty()) {
            out.println("Aucun fichier audio trouvé.");
            return 0;
        }
        int total = files.size();
        List<String> mode = new ArrayList<>();
        if (a.dryRun) mode.add("dry-run");
        if (a.tag) mode.add("tags ID3");
        if (a.move != null) mode.add("→ Lidarr:" + a.move);
        if (dupes != null) mode.add(a.delete ? "suppression définitive des doublons" : "doublons → " + dupes.quarantine);
        out.println(total + " fichier(s) audio trouvé(s)." + (mode.isEmpty() ? "" : "  [" + String.join(", ", mode) + "]"));
        out.println();

        int renamed = 0, tagged = 0, moved = 0, skipped = 0, failed = 0;
        Path top = root != null && path.startsWith(root) ? root : (Files.isDirectory(path) ? path : path.getParent());

        for (int i = 0; i < total; i++) {
            Path filepath = files.get(i);
            out.println("[" + (i + 1) + "/" + total + "] " + filepath.getFileName());
            if (journal != null && journal.done.containsKey(filepath.toString())) {
                out.println("  ↷ déjà traité");
                skipped++;
                continue;
            }
            if (!Files.exists(filepath)) {
                out.println("  ↷ n'existe plus (écarté ou déplacé pendant la passe)");
                skipped++;
                continue;
            }
            if (protectedPaths.contains(filepath.toString())) {
                out.println("  ↷ dans une playlist, laissé à sa place");
                skipped++;
                Info tags = dupes != null ? readExistingTags(filepath) : null;
                if (tags != null) {
                    dupes.check(filepath, tags);
                    dupes.register(filepath, tags);
                }
                continue;
            }
            if (a.skipOrganized && root != null && isOrganized(filepath, root)) {
                out.println("  ↷ déjà organisé, ignoré");
                skipped++;
                Info tags = dupes != null ? readExistingTags(filepath) : null;
                if (tags != null) dupes.register(filepath, tags);
                continue;
            }

            Info info = journal != null ? earlierIdentification(filepath) : null;
            String source = "passe précédente";
            boolean askedShazam = info == null;
            if (askedShazam) {
                info = recognizeWaitingForShazam(filepath);
                if (info == THROTTLED) {
                    out.println("  ↷ Shazam bloque toujours : laissé pour la prochaine passe");
                    skipped++;
                    continue;
                }
                source = "SongRec";
                if (info == null) {
                    info = safely(fallback, filepath);   // repli 1 : AcoustID / MusicBrainz
                    if (info != null) source = "AcoustID";
                }
                if (info == null) {
                    info = readExistingTags(filepath);  // repli 2 : tags existants valables
                    if (info != null) source = "tags existants";
                } else if (info.track.isEmpty()) {
                    // Numéro de piste des tags existants, seulement s'ils décrivent le même morceau
                    String track = trackFromOldTags(readExistingTags(filepath), info);
                    if (!track.isEmpty()) info.track = track;
                }
            }

            if (info == null) {
                out.println("  ✗ non reconnu (ni SongRec ni tags)");
                failed++;
                if (journal != null) journal.write(rec("fichier", filepath.toString(), "echec", true));
                continue;
            }
            out.println("  ♪ " + info.artist + " — " + info.title + (info.album.isEmpty() ? "" : " (" + info.album + ")")
                    + (info.year.isEmpty() ? "" : " [" + info.year + "]") + "  [" + source + "]");
            // Une erreur sur un fichier (nom refusé par le disque…) n'arrête pas une passe de plusieurs jours
            try {
                Path origin = filepath;
                if (dupes == null || dupes.check(filepath, info)) {
                    if (root != null) {
                        Path result = moveToLidarr(filepath, info, root, a.dryRun, folders, dupes, top);
                        if (result != null) { moved++; filepath = result; } else skipped++;
                    } else {
                        Path result = doRename(filepath, info, a.dryRun, dupes);
                        if (result != null) { renamed++; filepath = result; } else skipped++;
                    }
                    if (Files.exists(filepath)) {   // sinon il vient d'être écarté au profit du fichier déjà à cette place
                        if (a.tag && askedShazam && writeTags(filepath, info, a.dryRun)) tagged++;
                        if (dupes != null) dupes.register(filepath, info);
                        if (journal != null) journal.write(rec("fichier", filepath.toString(), "origine", origin.toString(),
                                "artiste", info.artist, "titre", info.title, "source", source));
                    }
                }
            } catch (IOException e) {
                out.println("  ⚠ erreur : " + e.getMessage());
                failed++;
            }
        }

        List<String> parts = new ArrayList<>();
        parts.add(root != null ? moved + " déplacé(s)" : renamed + " renommé(s)");
        if (a.tag) parts.add(tagged + " tag(s) écrits");
        if (dupes != null) parts.add(dupes.removed + " doublon(s) écarté(s), " + String.format(Locale.FRANCE, "%.1f", dupes.freed / 1e9) + " Go");
        parts.add(skipped + " ignoré(s)");
        parts.add(failed + " non reconnu(s)");
        out.println("\nTerminé : " + String.join(", ", parts) + ".");
        if (dupes != null && dupes.removed > 0 && !a.delete && !a.dryRun) out.println("Quarantaine, à vider une fois vérifiée : " + dupes.quarantine);
        if (journal != null && !a.dryRun) out.println("Journal : " + journal.path);
        if (!a.dryRun) out.println("Annulable dans OpenTagger : Édition → Annuler un enregistrement.");
        return 0;
    }

    /** Marqueur : Shazam bloque encore après plusieurs longues attentes. */
    private static final Info THROTTLED = new Info();

    private Info recognizeWaitingForShazam(Path p) {
        for (int round = 0; round < 6; round++) {
            Info info = safely(primary, p);
            if (info != null || !primary.lastWasThrottled()) return info;
            out.println("  ⏸ Shazam limite les appels : nouvel essai dans " + throttleWaitMs / 60_000 + " min");
            try { Thread.sleep(throttleWaitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return THROTTLED; }
        }
        return THROTTLED;
    }

    private Info safely(Recognizer r, Path p) {
        if (r == null) return null;
        try { return r.recognize(p.toFile()); }
        catch (Exception e) { return null; }
    }

    // ── Point d'entrée : java -cp opentagger.jar com.opentagger.SongRecRename … ──────────────────────────────────

    public static void main(String[] args) {
        App.configureTagLibrary();
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            out.println(e.getMessage());
            System.exit(2);
            return;
        }
        int code;
        try {
            code = new SongRecRename(out, shazam(), acoustid()).run(o);
        } catch (IllegalStateException e) {
            out.println("Erreur : " + e.getMessage());
            code = 1;
        } catch (Exception e) {
            out.println("Erreur : " + e);
            code = 1;
        }
        out.flush();
        System.exit(code);
    }

    /** Pour les tests : liste des champs d'un tag (identifiant de chaque champ). */
    static List<String> fieldIds(Tag tag) {
        List<String> ids = new ArrayList<>();
        for (Iterator<org.jaudiotagger.tag.TagField> it = tag.getFields(); it.hasNext(); ) ids.add(it.next().getId());
        return ids;
    }
}
