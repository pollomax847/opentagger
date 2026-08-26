package com.opentagger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lecture/extraction de CD audio via cdparanoia (sous-processus, comme ffmpeg/fpcalc/songrec
 * ailleurs dans l'appli — aucune dépendance JNI/API CD-ROM native). cdparanoia gère lui-même la
 * correction d'erreurs de lecture (jitter, rayures) — préférable à un simple dd du périphérique
 * bloc, qui ne corrige rien.
 *
 * Demande utilisateur (2026-08-21) : import de CD audio (avec identification + vérification de
 * doublons dans la bibliothèque avant extraction) et de CD de données. Cette classe ne couvre que
 * le volet audio — un CD de données se traite par simple copie de fichiers depuis le point de
 * montage (géré par udisks2/gvfs côté OS, voir CdImportDialog), sans passer par cdparanoia.
 *
 * PAS TESTÉ avec un lecteur physique réel au moment de l'écriture (aucun /dev/sr* présent sur la
 * machine où ce code a été développé) — cdparanoia lui-même est installé et son format de sortie
 * -Q est stable et documenté depuis des décennies, mais une vérification avec un vrai disque
 * inséré reste à faire dès qu'un lecteur est disponible.
 */
public class CdRipper {

    private static final int CDDA_SECTORS_PER_SEC = 75; // standard CD-DA, invariant

    public record Track(int number, int lengthSectors) {
        public int durationSec() { return lengthSectors / CDDA_SECTORS_PER_SEC; }
    }

    /** tracks vide = pas de piste audio détectée (CD de données pur, ou lecteur vide/inaccessible). */
    public record Toc(List<Track> tracks) {
        public boolean isAudioDisc() { return !tracks.isEmpty(); }
    }

    private final String cdparanoiaPath;

    public CdRipper() {
        this.cdparanoiaPath = Config.get().str("cd.cdparanoia_path", "cdparanoia");
    }

    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder(Config.get().str("cd.cdparanoia_path", "cdparanoia"), "--version")
                    .redirectErrorStream(true).start();
            try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
            return p.waitFor(5, TimeUnit.SECONDS);
            // Pas de contrôle sur exitValue() : certaines versions de cdparanoia renvoient un code
            // non nul pour --version sans que ce soit un vrai échec — la seule chose qui compte
            // ici est "le binaire existe et répond", pas son code de sortie exact.
        } catch (Exception e) { return false; }
    }

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

    /** Interroge la table des pistes du disque inséré — ne lit AUCUNE donnée audio, juste le TOC
     *  (rapide, quelques secondes). Renvoie un Toc vide si le disque ne contient aucune piste
     *  audio (CD de données pur) ou si aucun lecteur/disque n'est accessible. */
    public Toc queryToc() throws IOException, InterruptedException {
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

    /** Extrait UNE piste vers un fichier WAV brut (44.1kHz/16bit/stéréo, format CDDA natif) —
     *  la conversion vers le format final choisi par l'utilisateur se fait ensuite via
     *  AudioTranscoder (déjà utilisé partout ailleurs dans l'appli pour ça), pas ici. */
    public Path ripTrackToWav(int trackNumber, Path destDir) throws IOException, InterruptedException {
        Files.createDirectories(destDir);
        Path wav = destDir.resolve(String.format("track%02d.wav", trackNumber));
        ProcessBuilder pb = new ProcessBuilder(
                cdparanoiaPath, String.valueOf(trackNumber), wav.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Journal du sous-processus purgé au fil de l'eau — sans ça, un pipe stdout plein peut
        // bloquer cdparanoia en plein milieu d'une extraction de plusieurs minutes (même piège que
        // ProcessUtils.readWithTimeout() ailleurs dans l'appli).
        try (InputStream is = p.getInputStream()) { is.readAllBytes(); }
        boolean done = p.waitFor(600, TimeUnit.SECONDS); // 10 min — large marge, même une piste de 20 min avec relectures d'erreur reste couverte
        if (!done) { p.destroyForcibly(); throw new IOException("cdparanoia : délai dépassé sur la piste " + trackNumber); }
        if (p.exitValue() != 0 || !Files.exists(wav) || Files.size(wav) == 0)
            throw new IOException("Échec d'extraction de la piste " + trackNumber + " (code " + p.exitValue() + ")");
        return wav;
    }
}
