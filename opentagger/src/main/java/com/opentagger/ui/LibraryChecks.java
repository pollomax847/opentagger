package com.opentagger.ui;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.tag.FieldKey;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * « À corriger », sur le modèle des « Fichiers à éditer » de MediaMonkey et de ses scripts de correction (Case Checker,
 * Swap Artist and Title, Auto-increment Track #s, Analyze Volume). Demande utilisateur du 2026-10-13 : « créer un fork
 * de MediaMonkey sur la correction des audios ». Le code de MediaMonkey ne peut pas être repris (licence Ventis : les
 * œuvres dérivées doivent tourner sur un produit Ventis) — ici seulement les mêmes contrôles, écrits pour OpenTagger.
 *
 * <p>Ne lit que les tags déjà en mémoire (aucun accès disque). Une correction n'est proposée que quand les fichiers
 * eux-mêmes la justifient : valeur unanime dans l'album, nom de fichier qui dit l'inverse des tags… Les tags venus de
 * MusicBrainz (recordingMbid) ne sont jamais « corrigés » pour la casse ni inversés : c'est la graphie officielle.
 */
public final class LibraryChecks {

    private LibraryChecks() {}

    /** Champs qu'une correction peut écrire. */
    public enum Field {
        TITLE(FieldKey.TITLE), ARTIST(FieldKey.ARTIST), ALBUM(FieldKey.ALBUM), ALBUM_ARTIST(FieldKey.ALBUM_ARTIST),
        YEAR(FieldKey.YEAR), GENRE(FieldKey.GENRE), TRACK(FieldKey.TRACK), COMPILATION(FieldKey.IS_COMPILATION),
        RG_TRACK_GAIN(null), RG_TRACK_PEAK(null), RG_ALBUM_GAIN(null), RG_ALBUM_PEAK(null),
        /** En mémoire seulement : ce que la correction des pochettes a trouvé (TagInfo.coverInfo). */
        COVER_INFO(null);

        public final FieldKey key;
        Field(FieldKey key) { this.key = key; }

        /** Nom du champ personnel (TXXX / atome libre / Vorbis) pour ceux qui n'ont pas de FieldKey. */
        public String customName() {
            return switch (this) {
                case RG_TRACK_GAIN -> "REPLAYGAIN_TRACK_GAIN"; case RG_TRACK_PEAK -> "REPLAYGAIN_TRACK_PEAK";
                case RG_ALBUM_GAIN -> "REPLAYGAIN_ALBUM_GAIN"; case RG_ALBUM_PEAK -> "REPLAYGAIN_ALBUM_PEAK";
                default -> null;
            };
        }

        public String get(TagInfo t) {
            return switch (this) {
                case TITLE -> nz(t.title); case ARTIST -> nz(t.artist); case ALBUM -> nz(t.album); case ALBUM_ARTIST -> nz(t.albumArtist);
                case YEAR -> t.dateForWrite(); case GENRE -> nz(t.genre); case TRACK -> nz(t.track);
                case COMPILATION -> nz(t.isCompilation); case RG_TRACK_GAIN -> nz(t.replayGainTrackGain);
                case RG_TRACK_PEAK -> nz(t.replayGainTrackPeak); case RG_ALBUM_GAIN -> nz(t.replayGainAlbumGain);
                case RG_ALBUM_PEAK -> nz(t.replayGainAlbumPeak); case COVER_INFO -> nz(t.coverInfo);
            };
        }

        public void set(TagInfo t, String v) {
            switch (this) {
                case TITLE -> t.title = v; case ARTIST -> t.artist = v; case ALBUM -> t.album = v;
                case ALBUM_ARTIST -> t.albumArtist = v; case YEAR -> t.setYearFromRaw(v); case GENRE -> t.genre = v;
                case TRACK -> t.track = v; case COMPILATION -> t.isCompilation = v.isBlank() ? "0" : v;
                case RG_TRACK_GAIN -> t.replayGainTrackGain = v; case RG_TRACK_PEAK -> t.replayGainTrackPeak = v;
                case RG_ALBUM_GAIN -> t.replayGainAlbumGain = v; case RG_ALBUM_PEAK -> t.replayGainAlbumPeak = v;
                case COVER_INFO -> t.coverInfo = v;
            }
        }
    }

    /** Les problèmes, dans l'ordre d'affichage. */
    public enum Check {
        NO_TITLE, NO_ARTIST, NO_ALBUM, NO_YEAR, NO_GENRE, ALBUM_ARTIST_SPLIT, NO_ALBUM_ARTIST, CASE, SWAPPED, NO_TRACK,
        NO_COVER, MULTI_COVER, COVER_MISMATCH, NO_REPLAYGAIN, NO_ALBUM_GAIN, MISSING_FILE
    }

    /** Un fichier vu par l'analyse. {@code ref} = l'objet de l'appelant (FileEntry), rendu tel quel. */
    public record Track<T>(T ref, Path path, TagInfo tags, boolean missing) {
        /** Images modifiables par jaudiotagger (pas Opus/AAC/WV/APE/MPC, voir FfmpegTagIO). */
        boolean imagesWritable() {
            String n = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
            return !n.endsWith(".mpc") && !com.opentagger.FfmpegTagIO.handles(path.toFile());
        }
    }

    /** Une correction proposée pour un fichier. */
    public record Fix<T>(T ref, Path path, Map<Field, String> changes) {}

    public static final class Report<T> {
        private final Map<Check, List<T>> found = new EnumMap<>(Check.class);
        private final Map<Check, List<Fix<T>>> fixes = new EnumMap<>(Check.class);

        private final List<List<T>> albumsWithoutGain = new ArrayList<>();
        private final List<T> coversNotRead = new ArrayList<>();

        public List<T> found(Check c) { return found.getOrDefault(c, List.of()); }
        public List<Fix<T>> fixes(Check c) { return fixes.getOrDefault(c, List.of()); }
        /** Albums (dossier + titre d'album, 2 à 60 pistes) dont au moins une piste n'a pas de volume d'album. */
        public List<List<T>> albumsWithoutGain() { return albumsWithoutGain; }
        /** Fichiers modifiables dont les images n'ont pas encore été lues (scannés avant le 2026-10-14). */
        public List<T> coversNotRead() { return coversNotRead; }

        void add(Check c, T ref) { found.computeIfAbsent(c, k -> new ArrayList<>()).add(ref); }
        void fix(Check c, Track<T> tr, Map<Field, String> changes) {
            if (!changes.isEmpty()) fixes.computeIfAbsent(c, k -> new ArrayList<>()).add(new Fix<>(tr.ref(), tr.path(), changes));
        }
    }

    /** Au-delà de ce nombre d'albums différents, un dossier est un dossier « fourre-tout » (téléchargements…) :
     *  deux « Best Of » d'artistes différents n'y forment pas un album. */
    static final int MAX_ALBUMS_PER_FOLDER = 3;

    private static final Pattern DISC_TRACK = Pattern.compile("^(\\d{1,2})-(\\d{1,3})(?=[\\s._-])");
    private static final Pattern TRACK_PREFIX = Pattern.compile("^(\\d{1,3})(?:\\s*[-.]\\s*|\\s+)(?=\\S)");
    private static final Pattern FEAT_SPLIT = Pattern.compile(
            "(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|vs\\.?|x)\\s+|\\s*[&,;/]\\s*|\\s+et\\s+");

    public static <T> Report<T> analyze(List<Track<T>> tracks, String vaName) {
        Report<T> r = new Report<>();
        Map<Path, Map<String, List<Track<T>>>> byFolder = new HashMap<>();

        for (Track<T> tr : tracks) {
            if (tr.missing()) { r.add(Check.MISSING_FILE, tr.ref()); continue; }
            TagInfo t = tr.tags();
            if (empty(nz(t.title)))  r.add(Check.NO_TITLE, tr.ref());
            if (empty(nz(t.artist))) r.add(Check.NO_ARTIST, tr.ref());
            if (empty(nz(t.album)))  r.add(Check.NO_ALBUM, tr.ref());
            if (nz(t.year).isBlank()) r.add(Check.NO_YEAR, tr.ref());
            if (nz(t.genre).isBlank()) r.add(Check.NO_GENRE, tr.ref());
            if (nz(t.replayGainTrackGain).isBlank()) r.add(Check.NO_REPLAYGAIN, tr.ref());
            String cover = nz(t.coverInfo);
            if (cover.equals("0")) r.add(Check.NO_COVER, tr.ref());
            else if (coverCount(cover) >= 2) r.add(Check.MULTI_COVER, tr.ref());
            else if (cover.isEmpty() && tr.imagesWritable()) r.coversNotRead.add(tr.ref());

            boolean fromMb = !nz(t.recordingMbid).isBlank();
            if (!fromMb) {
                // Titre ET artiste dans la même mauvaise casse (ripper « METALLICA - ENTER SANDMAN », tout en minuscules) :
                // un seul champ stylisé (« GOD DID », « deadmau5 ») est souvent la graphie voulue — on n'y touche pas.
                String ti = nz(t.title), ar = nz(t.artist);
                boolean caps = shouting(ti) && (shouting(ar) || allCaps(ar)), lower = whispering(ti) && whispering(ar);
                if (caps || lower) {
                    Map<Field, String> cs = new EnumMap<>(Field.class);
                    cs.put(Field.TITLE, titleCase(ti));
                    cs.put(Field.ARTIST, titleCase(ar));
                    String al = nz(t.album);
                    if (caps ? shouting(al) || allCaps(al) : whispering(al)) cs.put(Field.ALBUM, titleCase(al));
                    cs.entrySet().removeIf(e -> e.getValue().equals(e.getKey().get(t)));
                    if (!cs.isEmpty()) { r.add(Check.CASE, tr.ref()); r.fix(Check.CASE, tr, cs); }
                }

                if (swapped(t, tr.path())) swap(r, tr);
            }
            if (!empty(nz(t.album)) && tr.path().getParent() != null) {
                byFolder.computeIfAbsent(tr.path().getParent(), k -> new HashMap<>())
                        .computeIfAbsent(key(nz(t.album)), k -> new ArrayList<>()).add(tr);
            }
        }

        for (Map<String, List<Track<T>>> albums : byFolder.values()) {
            if (albums.size() > MAX_ALBUMS_PER_FOLDER) continue;
            for (List<Track<T>> album : albums.values()) {
                if (album.size() >= 2) checkAlbum(r, album, vaName, album.get(0).path().getParent());
            }
        }
        return r;
    }

    private static <T> void checkAlbum(Report<T> r, List<Track<T>> album, String vaName, Path dir) {
        boolean compilationDir = compilationFolder(dir);
        // ── Artiste de l'album différent d'une piste à l'autre : l'album s'éclate dans Navidrome/Plex ──
        Set<String> albumArtists = new HashSet<>();
        for (Track<T> tr : album) albumArtists.add(nz(tr.tags().albumArtist).trim());
        if (albumArtists.size() > 1) {
            for (Track<T> tr : album) r.add(Check.ALBUM_ARTIST_SPLIT, tr.ref());
            String[] target = albumArtistFor(album, vaName, compilationDir, dir);
            if (target != null) {
                for (Track<T> tr : album) {
                    Map<Field, String> cs = new EnumMap<>(Field.class);
                    if (!nz(tr.tags().albumArtist).trim().equals(target[0])) cs.put(Field.ALBUM_ARTIST, target[0]);
                    if (target[1] != null && !"1".equals(nz(tr.tags().isCompilation))) cs.put(Field.COMPILATION, target[1]);
                    r.fix(Check.ALBUM_ARTIST_SPLIT, tr, cs);
                }
            }
        } else if (albumArtists.contains("")) {
            // ── Plusieurs artistes, aucun artiste de l'album ──
            Map<String, String> primaries = primaryArtists(album);
            if (primaries.size() >= 2 || differentArtists(album)) {
                for (Track<T> tr : album) r.add(Check.NO_ALBUM_ARTIST, tr.ref());
                // Various Artists seulement dans un dossier de compilation : « Miles Davis Plays For Lovers » mêle Miles Davis,
                // Miles Davis Quintet, Sextet… sans être une compilation. Sinon, le dossier d'artiste qui porte l'un des noms.
                Path artistDir = dir.getParent();
                String folderKey = artistDir == null || artistDir.getFileName() == null ? "" : key(artistDir.getFileName().toString());
                String folderArtist = primaries.get(folderKey);
                if (folderArtist != null) for (String k : primaries.keySet()) if (!k.contains(folderKey)) folderArtist = null;
                String target = primaries.size() == 1 ? soleCredit(album, primaries.keySet().iterator().next())
                        : compilationDir && primaries.size() >= 3 ? vaName
                        : !compilationDir ? folderArtist : null;
                if (target != null) {
                    for (Track<T> tr : album) {
                        Map<Field, String> cs = new EnumMap<>(Field.class);
                        cs.put(Field.ALBUM_ARTIST, target);
                        if (target.equals(vaName) && !"1".equals(nz(tr.tags().isCompilation))) cs.put(Field.COMPILATION, "1");
                        r.fix(Check.NO_ALBUM_ARTIST, tr, cs);
                    }
                }
            }
        }

        // ── Pochettes différentes dans un même album (seulement parmi les fichiers dont les images ont été lues) ──
        Set<String> hashes = new HashSet<>();
        for (Track<T> tr : album) {
            String c = nz(tr.tags().coverInfo);
            int colon = c.indexOf(':');
            if (colon > 0) hashes.add(c.substring(colon + 1));
        }
        if (hashes.size() >= 2) for (Track<T> tr : album) if (nz(tr.tags().coverInfo).contains(":")) r.add(Check.COVER_MISMATCH, tr.ref());

        // ── Volume d'album (ReplayGain album) : calculé par album entier, comme « Analyser le volume » de MediaMonkey ──
        if (album.size() <= 60) {
            boolean missing = false;
            for (Track<T> tr : album) {
                if (nz(tr.tags().replayGainAlbumGain).isBlank()) { r.add(Check.NO_ALBUM_GAIN, tr.ref()); missing = true; }
            }
            if (missing) {
                List<T> refs = new ArrayList<>();
                for (Track<T> tr : album) refs.add(tr.ref());
                r.albumsWithoutGain.add(refs);
            }
        }

        // ── Année / genre manquants : repris des autres pistes s'ils y sont unanimes ──
        fillFromAlbum(r, album, Check.NO_YEAR, Field.YEAR, t -> nz(t.year).trim());
        fillFromAlbum(r, album, Check.NO_GENRE, Field.GENRE, t -> nz(t.genre).trim());

        // ── Numéro de piste manquant : celui du nom de fichier (« 05 - Titre », « 1-05 Titre ») ──
        Set<String> used = new HashSet<>();
        for (Track<T> tr : album) used.add(stripZeros(nz(tr.tags().track)));
        for (Track<T> tr : album) {
            if (!nz(tr.tags().track).isBlank()) continue;
            r.add(Check.NO_TRACK, tr.ref());
            String n = trackFromFileName(tr.path().getFileName().toString());
            if (n != null && !used.contains(n) && Integer.parseInt(n) <= Math.max(99, album.size() * 2)) {
                used.add(n);
                r.fix(Check.NO_TRACK, tr, new EnumMap<>(Map.of(Field.TRACK, n)));
            }
        }
    }

    /** [artiste de l'album, drapeau compilation ou null], ou null si les fichiers ne permettent pas de trancher. */
    private static <T> String[] albumArtistFor(List<Track<T>> album, String vaName, boolean compilationDir, Path dir) {
        Map<String, Integer> raw = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (Track<T> tr : album) {
            String v = nz(tr.tags().albumArtist).trim();
            if (v.isEmpty()) continue;
            raw.merge(v, 1, Integer::sum);
            keys.add(key(v));
        }
        if (raw.isEmpty()) return null;
        String top = Collections.max(raw.entrySet(), Map.Entry.comparingByValue()).getKey();
        // Même nom écrit autrement (casse, tiret, apostrophe) ou laissé vide sur certaines pistes.
        if (keys.size() == 1) return new String[]{preferredSpelling(album, raw, top), null};
        top = preferredSpelling(album, raw, top);
        // Compilation où chaque piste a reçu son propre interprète comme artiste de l'album.
        boolean copiedFromArtist = true;
        for (Track<T> tr : album) {
            if (!key(nz(tr.tags().albumArtist)).equals(key(nz(tr.tags().artist)))) { copiedFromArtist = false; break; }
        }
        if (copiedFromArtist && compilationDir && primaryArtists(album).size() >= 3) return new String[]{vaName, "1"};
        // Le dossier d'artiste tranche entre deux noms (« Sniper/… » : « Sniper » contre « Tireur isolé » écrit sur la
        // plupart des pistes le 2026-08-23) — sauf si l'un d'eux est Various Artists (compilation éparpillée).
        Path artistDir = dir == null ? null : dir.getParent();
        String folderKey = artistDir == null || artistDir.getFileName() == null ? "" : key(artistDir.getFileName().toString());
        boolean anyVarious = keys.contains(key(vaName));
        if (!anyVarious && keys.contains(folderKey)) {
            Map<String, Integer> sameName = new LinkedHashMap<>();
            raw.forEach((v, n) -> { if (key(v).equals(folderKey)) sameName.put(v, n); });
            String v = Collections.max(sameName.entrySet(), Map.Entry.comparingByValue()).getKey();
            // Un dossier « X feat. Y » vient lui-même d'un mauvais artiste de l'album : il ne prouve rien.
            if (FEAT_SPLIT.split(v, 2).length == 1) return new String[]{preferredSpelling(album, sameName, v), null};
        }
        // Une valeur très majoritaire sur un vrai album (Nova Tunes : 41 « Various Artists », 2 noms isolés).
        int topCount = 0;
        for (Map.Entry<String, Integer> e : raw.entrySet()) if (key(e.getKey()).equals(key(top))) topCount += e.getValue();
        if (album.size() >= 5 && topCount >= 0.8 * album.size())
            return new String[]{top, key(top).equals(key(vaName)) ? "1" : null};
        return null;
    }

    /** La graphie la plus fréquente, sauf si elle est toute en minuscules ou en capitales (« JAMES INGRAM ») et qu'une
     *  autre graphie du même nom ne l'est pas. Les sigles courts (« IAM », « ABBA ») ne comptent pas comme des capitales. */
    private static <T> String preferredSpelling(List<Track<T>> album, Map<String, Integer> raw, String top) {
        if (!badlyCased(top)) return top;
        String best = null;
        for (String v : raw.keySet()) {
            if (key(v).equals(key(top)) && !badlyCased(v) && (best == null || raw.get(v) > raw.get(best))) best = v;
        }
        return best != null ? best : top;
    }

    private static boolean badlyCased(String v) {
        int letters = 0;
        for (char ch : v.toCharArray()) if (Character.isLetter(ch)) letters++;
        return whispering(v) || (allCaps(v) && letters > 4);
    }

    /** Dossier de compilation : « Compilations/… », « VA - … », « Various Artists/… ». */
    static boolean compilationFolder(Path dir) {
        if (dir == null || dir.getFileName() == null) return false;
        if (dir.getFileName().toString().matches("(?i)^(va|v\\.a\\.|various( artists)?)\\s*[-_–].*")) return true;
        for (Path d = dir; d != null && d.getFileName() != null; d = d.getParent()) {
            String n = d.getFileName().toString();
            if (n.equalsIgnoreCase("Compilations") || n.equalsIgnoreCase("Compilation") || n.equalsIgnoreCase("Various Artists"))
                return true;
        }
        return false;
    }

    /** Interprète principal (sans feat./&/,) → graphie la plus fréquente, par clé normalisée. */
    private static <T> Map<String, String> primaryArtists(List<Track<T>> album) {
        Map<String, Map<String, Integer>> byKey = new LinkedHashMap<>();
        for (Track<T> tr : album) {
            String a = nz(tr.tags().artist).trim();
            if (empty(a)) continue;
            String primary = FEAT_SPLIT.split(a, 2)[0].trim();
            if (primary.isEmpty()) primary = a;
            byKey.computeIfAbsent(key(primary), k -> new LinkedHashMap<>()).merge(primary, 1, Integer::sum);
        }
        Map<String, String> out = new LinkedHashMap<>();
        byKey.forEach((k, m) -> out.put(k, Collections.max(m.entrySet(), Map.Entry.comparingByValue()).getKey()));
        return out;
    }

    /** Graphie d'une piste créditée à l'interprète principal SEUL (« Inna » parmi « Inna feat. X »), sinon null. */
    private static <T> String soleCredit(List<Track<T>> album, String primaryKey) {
        for (Track<T> tr : album) if (key(nz(tr.tags().artist)).equals(primaryKey)) return nz(tr.tags().artist).trim();
        return null;
    }

    private static <T> boolean differentArtists(List<Track<T>> album) {
        Set<String> s = new HashSet<>();
        for (Track<T> tr : album) if (!empty(nz(tr.tags().artist))) s.add(key(nz(tr.tags().artist)));
        return s.size() >= 2;
    }

    private static <T> void fillFromAlbum(Report<T> r, List<Track<T>> album, Check c, Field f,
                                          java.util.function.Function<TagInfo, String> value) {
        Map<String, Integer> values = new HashMap<>();
        Map<String, String> written = new HashMap<>();
        boolean anyMissing = false;
        for (Track<T> tr : album) {
            String v = value.apply(tr.tags());
            if (v.isEmpty()) { anyMissing = true; continue; }
            values.merge(v, 1, Integer::sum);
            written.putIfAbsent(v, f.get(tr.tags()));
        }
        if (!anyMissing || values.size() != 1) return;
        String v = written.values().iterator().next();
        for (Track<T> tr : album) {
            if (value.apply(tr.tags()).isEmpty()) r.fix(c, tr, new EnumMap<>(Map.of(f, v)));
        }
    }

    // ── Règles fichier par fichier ────────────────────────────────────────────

    /** Tout en majuscules, sur au moins deux mots : « SWEET DREAMS ». Un mot seul (« ABBA », « HUMBLE. ») peut être voulu. */
    static boolean shouting(String s) {
        if (s == null || s.trim().split("\\s+").length < 2) return false;
        int upper = 0;
        for (char ch : s.toCharArray()) {
            if (Character.isLowerCase(ch)) return false;
            if (Character.isUpperCase(ch)) upper++;
        }
        return upper >= 5;
    }

    /** Tout en minuscules : « sweet dreams », « eurythmics ». */
    static boolean whispering(String s) {
        if (s == null) return false;
        int letters = 0;
        for (char ch : s.toCharArray()) {
            if (Character.isUpperCase(ch)) return false;
            if (Character.isLetter(ch)) letters++;
        }
        return letters >= 4;
    }

    /** Les tags disent l'inverse du nom de fichier « Artiste - Titre » ET l'artiste de l'album confirme que c'est le
     *  « titre » des tags qui est l'artiste. Le nom de fichier seul ne suffit pas : beaucoup de rips sont nommés
     *  « Titre - Artiste » (« 04 - These Foolish Things - Jo Stafford », tags justes), et un dossier d'album porte
     *  souvent le nom d'un titre (« Miles Davis/Enigma/12 - Enigma - Miles Davis »). */
    static boolean swapped(TagInfo t, Path path) {
        if (empty(nz(t.artist)) || empty(nz(t.title))) return false;
        String a = key(nz(t.artist)), ti = key(nz(t.title)), aa = key(nz(t.albumArtist));
        if (a.contains(ti) || ti.contains(a) || !aa.equals(ti)) return false;
        String[] fn = TaggingWorker.parseFilename(path.toFile());
        return a.equals(key(fn[1])) && ti.equals(key(fn[0]));
    }

    private static <T> void swap(Report<T> r, Track<T> tr) {
        r.add(Check.SWAPPED, tr.ref());
        r.fix(Check.SWAPPED, tr, new EnumMap<>(Map.of(Field.ARTIST, nz(tr.tags().title), Field.TITLE, nz(tr.tags().artist))));
    }

    /** Tout en majuscules, même un seul mot (« METALLICA ») — pour l'artiste, quand le titre l'est aussi. */
    static boolean allCaps(String s) {
        int upper = 0;
        for (char ch : nz(s).toCharArray()) {
            if (Character.isLowerCase(ch)) return false;
            if (Character.isUpperCase(ch)) upper++;
        }
        return upper >= 2;
    }

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}'’]*");
    private static final Pattern ROMAN = Pattern.compile("(ii|iii|iv|vi|vii|viii|ix|xi|xii|xiii)");
    private static final Set<String> SMALL = Set.of("a", "an", "the", "and", "but", "or", "nor", "for", "at", "by", "in", "of",
            "on", "to", "as", "vs", "feat", "ft", "x");
    private static final Set<String> UPPER = Set.of("dj", "mc", "ep", "lp", "cd", "dvd", "usa", "uk", "nyc", "tv");

    /** Majuscule à chaque mot, comme le Case Checker de MediaMonkey : aussi après ( [ / - et « », petits mots anglais
     *  en minuscules hors début de phrase, DJ/MC/EP/CD et chiffres romains en capitales. Seulement pour un texte qui était
     *  entièrement dans une mauvaise casse. */
    static String titleCase(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(lower.length());
        Matcher m = WORD.matcher(lower);
        int last = 0;
        while (m.find()) {
            b.append(lower, last, m.start());
            String w = m.group();
            String before = b.toString().stripTrailing();
            boolean phraseStart = before.isEmpty() || "([{/-‐–—:\"«“".indexOf(before.charAt(before.length() - 1)) >= 0;
            if (ROMAN.matcher(w).matches() || UPPER.contains(w)) {
                b.append(w.toUpperCase(Locale.ROOT));
            } else if (!phraseStart && SMALL.contains(w)) {
                b.append(w);
            } else {
                b.appendCodePoint(Character.toUpperCase(w.codePointAt(0))).append(w.substring(Character.charCount(w.codePointAt(0))));
            }
            last = m.end();
        }
        return b.append(lower.substring(last)).toString();
    }

    /** Numéro de piste en tête du nom de fichier, sans zéros : « 05 - Titre » → 5, « 1-05 Titre » → 5. */
    static String trackFromFileName(String name) {
        Matcher m = DISC_TRACK.matcher(name);
        if (m.find()) return positive(m.group(2));
        m = TRACK_PREFIX.matcher(name);
        return m.find() ? positive(m.group(1)) : null;
    }

    private static String positive(String digits) {
        int n = Integer.parseInt(digits);
        return n > 0 ? String.valueOf(n) : null;
    }

    private static String stripZeros(String track) {
        String t = track == null ? "" : track.trim();
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        try { return String.valueOf(Integer.parseInt(t)); } catch (NumberFormatException e) { return t; }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Nombre d'images d'après TagInfo.coverInfo (« 2:1a2b3c4d » → 2), -1 si pas encore lu. */
    static int coverCount(String coverInfo) {
        if (coverInfo == null || coverInfo.isEmpty()) return -1;
        int colon = coverInfo.indexOf(':');
        try { return Integer.parseInt(colon < 0 ? coverInfo : coverInfo.substring(0, colon)); }
        catch (NumberFormatException e) { return -1; }
    }

    private static boolean empty(String s) {
        return s == null || s.isBlank() || TaggingWorker.isGenericTag(s);
    }

    /** Clé de comparaison : sans accents, casse, ponctuation ni espaces (« Jean‐Louis Aubert » = « jean-louis aubert »). */
    static String key(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
