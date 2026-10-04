package com.opentagger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture/extraction de CD audio. Lecteur NATIF par défaut, sans outil à installer :
 * <ul>
 *   <li><b>Windows</b> : PowerShell + DeviceIoControl (ressource {@code cd/cd_windows.ps1}) ;</li>
 *   <li><b>Linux</b> : Python 3 + ioctl du pilote cdrom (ressource {@code cd/cd_linux.py}) ;</li>
 *   <li>repli Linux : {@code cdparanoia} s'il est installé (meilleure correction d'erreurs, si l'utilisateur l'a).</li>
 * </ul>
 * Les deux scripts parlent le même protocole de lignes (voir leur en-tête) : un sous-processus, comme ffmpeg/fpcalc
 * ailleurs dans l'appli. La conversion vers le format choisi se fait ensuite via AudioTranscoder.
 *
 * <p>Un CD de données se traite par simple copie de fichiers (voir CdImportDialog), sans passer par ici.
 *
 * <p>Essayé avec un vrai CD audio sous Windows (19 pistes lues, extraction d'une piste en WAV). Sous Linux, seuls
 * l'analyse des réponses et les chemins d'erreur sont testés.
 */
public class CdRipper {

    private static final int CDDA_SECTORS_PER_SEC = 75; // standard CD-DA, invariant

    public record Track(int number, int lengthSectors) {
        public int durationSec() { return lengthSectors / CDDA_SECTORS_PER_SEC; }
    }

    /** tracks vide = pas de piste audio détectée (CD de données pur). */
    public record Toc(List<Track> tracks) {
        public boolean isAudioDisc() { return !tracks.isEmpty(); }

        /** Disc ID MusicBrainz EXACT (SHA-1 du premier/dernier numéro de piste puis des 100 offsets en hexadécimal, base64
         *  adapté : {@code + / =} → {@code . _ -}). Sert à une recherche exacte, là où le lookup par durées arrondies est
         *  approximatif. Vide si le disque n'est pas un CD audio simple (pistes non contiguës, CD mixte...). */
        public String discId() {
            if (tracks.isEmpty() || tracks.size() > 99) return "";
            int first = tracks.get(0).number();
            int last = tracks.get(tracks.size() - 1).number();
            if (first != 1 || last != tracks.size()) return "";
            int[] offsets = new int[100];
            int cursor = 150; // lead-in de 2 s
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).number() != first + i) return "";
                offsets[i + 1] = cursor;
                cursor += tracks.get(i).lengthSectors();
            }
            offsets[0] = cursor; // lead-out
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "%02X%02X", first, last));
            for (int o : offsets) sb.append(String.format(Locale.ROOT, "%08X", o));
            try {
                byte[] sha = java.security.MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.US_ASCII));
                return java.util.Base64.getEncoder().encodeToString(sha).replace('+', '.').replace('/', '_').replace('=', '-');
            } catch (java.security.NoSuchAlgorithmException e) {
                return "";
            }
        }

        /** Somme des secteurs avec le lead-in, telle que la compare MusicBrainz. */
        public int totalSectors() {
            int n = 150;
            for (Track t : tracks) n += t.lengthSectors();
            return n;
        }
    }

    /** Résultat de l'analyse des lignes d'un script natif. */
    record NativeReply(List<Track> tracks, int readErrors, String errorCode, String errorMessage, boolean done) {}

    private final String cdparanoiaPath;

    public CdRipper() {
        this.cdparanoiaPath = Config.get().str("cd.cdparanoia_path", "cdparanoia");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static volatile Boolean nativeOk;

    /** Lecteur natif utilisable : PowerShell (Windows) ou Python 3 (Linux/macOS). */
    static boolean nativeAvailable() {
        Boolean v = nativeOk;
        if (v != null) return v;
        boolean ok;
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "exit 0")
                    : new ProcessBuilder("python3", "--version");
            Process p = pb.redirectErrorStream(true).start();
            try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
            ok = p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { ok = false; }
        nativeOk = ok;
        return ok;
    }

    /** Windows : toujours le lecteur natif. Linux : cdparanoia d'abord quand il est installé (voie éprouvée, avec sa
     *  correction d'erreurs) ; le lecteur natif Python ne sert alors que s'il est absent. */
    static boolean useNative() {
        return nativeAvailable() && (isWindows() || !cdparanoiaAvailable());
    }

    static boolean cdparanoiaAvailable() {
        try {
            Process p = new ProcessBuilder(Config.get().str("cd.cdparanoia_path", "cdparanoia"), "--version")
                    .redirectErrorStream(true).start();
            try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
            return p.waitFor(5, TimeUnit.SECONDS);
            // Pas de contrôle sur exitValue() : certaines versions renvoient un code non nul pour --version.
        } catch (Exception e) { return false; }
    }

    public static boolean isAvailable() {
        return nativeAvailable() || cdparanoiaAvailable();
    }

    // ── Protocole des scripts natifs ────────────────────────────────────────────────────────────────

    /** Analyse les lignes {@code T/P/RIPPED/ERR/DONE} d'un script natif (testable sans lecteur). */
    static NativeReply parseNative(List<String> lines, IntConsumer progress) {
        List<Track> tracks = new ArrayList<>();
        int errors = 0;
        String code = "", msg = "";
        boolean done = false;
        for (String line : lines) {
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "T" -> {
                    if (f.length >= 4 && f[3].trim().equals("1"))          // seulement les pistes AUDIO
                        tracks.add(new Track(Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim())));
                }
                case "P" -> {
                    if (progress != null && f.length >= 3) {
                        int total = Integer.parseInt(f[2].trim());
                        if (total > 0) progress.accept((int) (100L * Integer.parseInt(f[1].trim()) / total));
                    }
                }
                case "RIPPED" -> errors = f.length > 1 ? Integer.parseInt(f[1].trim()) : 0;
                case "ERR" -> { code = f.length > 1 ? f[1] : "READ"; msg = f.length > 2 ? f[2] : ""; }
                case "DONE" -> done = true;
                default -> { /* bruit (avertissement du lanceur) : ignoré */ }
            }
        }
        return new NativeReply(tracks, errors, code, msg, done);
    }

    /** Message lisible pour un code d'erreur du script natif. */
    static String describe(String code, String msg) {
        return switch (code) {
            case "NODRIVE" -> I18n.t("Aucun lecteur de CD détecté.");
            case "NODISC"  -> I18n.t("Aucun disque dans le lecteur (ou disque illisible).");
            case "OPEN"    -> I18n.t("Impossible d'ouvrir le lecteur de CD (%s).", msg);
            case "TOC"     -> I18n.t("Lecture de la table des pistes impossible (%s).", msg);
            default        -> msg.isBlank() ? I18n.t("Erreur de lecture du CD.") : msg;
        };
    }

    private Path scriptFile() throws IOException {
        String res = isWindows() ? "/cd/cd_windows.ps1" : "/cd/cd_linux.py";
        try (InputStream is = CdRipper.class.getResourceAsStream(res)) {
            if (is == null) throw new IOException("ressource introuvable : " + res);
            byte[] body = is.readAllBytes();
            Path f = Files.createTempFile("opentagger_cd", isWindows() ? ".ps1" : ".py");
            if (isWindows()) {      // BOM UTF-8 : PowerShell 5.1 lit sinon le script en ANSI
                byte[] all = new byte[body.length + 3];
                all[0] = (byte) 0xEF; all[1] = (byte) 0xBB; all[2] = (byte) 0xBF;
                System.arraycopy(body, 0, all, 3, body.length);
                body = all;
            }
            Files.write(f, body);
            return f;
        }
    }

    /** Lance le script natif et renvoie ses lignes ; {@code progress} reçoit 0-100 pendant une extraction. */
    private NativeReply runNative(List<String> scriptArgs, IntConsumer progress, int timeoutSec)
            throws IOException, InterruptedException {
        Path script = scriptFile();
        try {
            List<String> cmd = new ArrayList<>();
            if (isWindows()) {
                cmd.addAll(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString()));
                // Les paramètres du script PowerShell sont nommés.
                for (int i = 0; i + 1 < scriptArgs.size(); i += 2) { cmd.add(scriptArgs.get(i)); cmd.add(scriptArgs.get(i + 1)); }
            } else {
                cmd.addAll(List.of("python3", script.toString()));
                cmd.addAll(scriptArgs);
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            List<String> lines = new ArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (lines) { lines.add(line); }
                        if (progress != null && line.startsWith("P\t")) progress.accept(lastPercent(line));
                    }
                } catch (IOException ignored) { /* processus arrêté */ }
            }, "cd-reader");
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("lecture du CD : délai dépassé");
            }
            reader.join(2000);
            synchronized (lines) { return parseNative(new ArrayList<>(lines), null); }
        } finally {
            try { Files.deleteIfExists(script); } catch (IOException ignored) {}
        }
    }

    private static int lastPercent(String pLine) {
        String[] f = pLine.split("\t", -1);
        try {
            int total = Integer.parseInt(f[2].trim());
            return total > 0 ? (int) (100L * Integer.parseInt(f[1].trim()) / total) : 0;
        } catch (RuntimeException e) { return 0; }
    }

    // ── cdparanoia (repli) ──────────────────────────────────────────────────────────────────────────

    // Ligne cdparanoia -Q typique :  "  1.    17296 [03:50.46]        0 [00:00.00]    no   no  2"
    private static final Pattern TRACK_LINE = Pattern.compile("^\\s*(\\d+)\\.\\s+(\\d+)\\s+\\[");

    static Toc parseToc(String output) {
        List<Track> tracks = new ArrayList<>();
        for (String line : output.split("\n", -1)) {
            Matcher m = TRACK_LINE.matcher(line);
            if (m.find()) tracks.add(new Track(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
        }
        return new Toc(tracks);
    }

    private Toc queryTocCdparanoia() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cdparanoiaPath, "-Q");
        pb.redirectErrorStream(true); // cdparanoia écrit la table sur stderr, pas stdout
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor(30, TimeUnit.SECONDS);
        return parseToc(out);
    }

    private Path ripTrackCdparanoia(int trackNumber, Path destDir) throws IOException, InterruptedException {
        Path wav = destDir.resolve(String.format("track%02d.wav", trackNumber));
        ProcessBuilder pb = new ProcessBuilder(
                cdparanoiaPath, String.valueOf(trackNumber), wav.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Journal du sous-processus purgé au fil de l'eau (un pipe plein bloquerait l'extraction).
        try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
        boolean done = p.waitFor(600, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); throw new IOException("cdparanoia : délai dépassé sur la piste " + trackNumber); }
        if (p.exitValue() != 0 || !Files.exists(wav) || Files.size(wav) == 0)
            throw new IOException("Échec d'extraction de la piste " + trackNumber + " (code " + p.exitValue() + ")");
        return wav;
    }

    // ── API publique ────────────────────────────────────────────────────────────────────────────────

    private String device() { return Config.get().str("cd.device", ""); }

    /** Table des pistes du disque inséré — ne lit AUCUNE donnée audio. Toc vide = aucune piste audio (CD de
     *  données). Lève une IOException au message lisible si aucun lecteur/disque n'est accessible. */
    public Toc queryToc() throws IOException, InterruptedException {
        IOException nativeFailure = null;
        if (useNative()) {
            List<String> args = new ArrayList<>();
            if (isWindows()) { args.addAll(List.of("-Mode", "toc")); if (!device().isBlank()) args.addAll(List.of("-Drive", device())); }
            else { args.add("toc"); if (!device().isBlank()) args.add(device()); }
            NativeReply r = runNative(args, null, 60);
            if (!r.errorCode().isEmpty()) {
                nativeFailure = new IOException(describe(r.errorCode(), r.errorMessage()));
            } else if (r.done()) {
                return new Toc(r.tracks());
            } else {
                nativeFailure = new IOException(I18n.t("Erreur de lecture du CD."));
            }
        }
        if (!isWindows() && cdparanoiaAvailable()) return queryTocCdparanoia();   // repli
        if (nativeFailure != null) throw nativeFailure;
        throw new IOException(I18n.t("Aucun lecteur de CD utilisable (PowerShell ou Python 3 requis)."));
    }

    /** Extrait UNE piste vers un WAV brut (44,1 kHz / 16 bits / stéréo). */
    public Path ripTrackToWav(int trackNumber, Path destDir) throws IOException, InterruptedException {
        return ripTrackToWav(trackNumber, destDir, null);
    }

    public Path ripTrackToWav(int trackNumber, Path destDir, IntConsumer progress) throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        if (useNative()) {
            Path wav = destDir.resolve(String.format("track%02d.wav", trackNumber));
            List<String> args = new ArrayList<>();
            if (isWindows()) {
                args.addAll(List.of("-Mode", "rip", "-Track", String.valueOf(trackNumber), "-Out", wav.toAbsolutePath().toString()));
                if (!device().isBlank()) args.addAll(List.of("-Drive", device()));
            } else {
                args.addAll(List.of("rip", String.valueOf(trackNumber), wav.toAbsolutePath().toString()));
                if (!device().isBlank()) args.add(device());
            }
            NativeReply r = runNative(args, progress, 1800);   // 30 min : large marge, relectures comprises
            if (r.errorCode().isEmpty() && r.done() && Files.exists(wav) && Files.size(wav) > 44) {
                if (r.readErrors() > 0)
                    System.out.println("[OT] CD piste " + trackNumber + " : " + r.readErrors() + " secteur(s) illisible(s) remplacé(s) par du silence.");
                return wav;
            }
            if (isWindows() || !cdparanoiaAvailable())
                throw new IOException(r.errorCode().isEmpty()
                        ? "Échec d'extraction de la piste " + trackNumber
                        : describe(r.errorCode(), r.errorMessage()));
            // Linux : repli cdparanoia ci-dessous
        }
        return ripTrackCdparanoia(trackNumber, destDir);
    }
}
