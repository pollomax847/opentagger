package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reconnaissance audio via SongRec (client Shazam open-source, sans clé API).
 * https://github.com/marin-m/SongRec
 *
 * Stratégie multi-offset : essaie début, 1/3, 2/3 du fichier via ffmpeg
 * pour maximiser les chances de reconnaissance (intro silencieuse, DJ mix…).
 */
public class SongRecClient {

    private static final String SONGREC_BIN = "songrec";
    private static final int    SEGMENT_SEC = 15;   // durée du segment envoyé à Shazam
    private final ObjectMapper  mapper      = new ObjectMapper();

    // ThreadLocal (pas un champ d'instance) : SongRecClient est partagé par un seul worker mais
    // pas garanti mono-thread selon l'appelant (voir batch.threads) — chaque thread garde SA
    // propre dernière raison sans risque de course. Avant ce correctif, TOUT échec (timeout
    // ffmpeg, segment illisible, timeout SongRec, aucun match Shazam...) finissait en un simple
    // "return null" indifférencié : aucune trace nulle part (ni log, ni cache, ni journal UI) de
    // LAQUELLE de ces causes s'était produite pour un fichier resté PENDING.
    private static final ThreadLocal<String> LAST_FAILURE_REASON = new ThreadLocal<>();

    /** Raison du dernier échec de {@link #recognize(File)} sur CE thread, ou null si le dernier
     *  appel a réussi (ou si recognize() n'a pas encore été appelé sur ce thread). */
    public static String lastFailureReason() { return LAST_FAILURE_REASON.get(); }

    public TagInfo recognize(File audioFile) throws Exception {
        LAST_FAILURE_REASON.remove();
        // Jusqu'à 3 extractions ffmpeg à des offsets différents = jusqu'à 3 déplacements de tête
        // de lecture séparés sur un disque mécanique — un seul permis pour tout l'appel (pas un
        // par offset) évite de le reprendre/relâcher inutilement 3 fois de suite. Voir DiskIoThrottle.
        java.util.concurrent.Semaphore gate = DiskIoThrottle.acquireFor(audioFile);
        try {
            String bin = Config.get().str("songrec.path", SONGREC_BIN);

            // Récupérer la durée avec ffprobe pour choisir les offsets
            double duration = probeDuration(audioFile);
            int[] offsets;
            if (duration <= 0) {
                offsets = new int[]{0};
            } else if (duration < 60) {
                offsets = new int[]{0};
            } else if (duration < 180) {
                offsets = new int[]{0, (int)(duration / 3)};
            } else {
                offsets = new int[]{0, (int)(duration / 4), (int)(duration / 2)};
            }

            for (int offset : offsets) {
                TagInfo result = recognizeAt(bin, audioFile, offset, duration);
                if (result != null) { LAST_FAILURE_REASON.remove(); return result; }
            }
            if (LAST_FAILURE_REASON.get() == null)
                LAST_FAILURE_REASON.set("aucun résultat sur " + offsets.length + " segment(s) testé(s)");
            return null;
        } finally {
            DiskIoThrottle.release(gate);
        }
    }

    private TagInfo recognizeAt(String bin, File audioFile, int offsetSec, double duration) throws Exception {
        File segment = audioFile;
        Path tmp     = null;

        // Si offset > 0 ou fichier très long : extraire un segment WAV via ffmpeg
        if (offsetSec > 0 || duration > 120) {
            tmp = Files.createTempFile("ot_shazam_", ".wav");
            try {
                ProcessBuilder ffmpeg = new ProcessBuilder(
                    "ffmpeg", "-y", "-ss", String.valueOf(offsetSec),
                    "-i", audioFile.getAbsolutePath(),
                    "-t", String.valueOf(SEGMENT_SEC),
                    "-ar", "44100", "-ac", "1",
                    "-f", "wav", tmp.toString(),
                    "-loglevel", "quiet"
                );
                // ProcessUtils avec timeout 30s — évite blocage infini sur fichiers corrompus
                ProcessUtils.readWithTimeout(ffmpeg, 30);
                if (!Files.exists(tmp) || Files.size(tmp) < 1000) {
                    LAST_FAILURE_REASON.set("extraction ffmpeg du segment à " + offsetSec
                        + "s vide/trop petite (fichier source illisible à cet offset ?)");
                    return null;
                }
                segment = tmp.toFile();
            } catch (Exception e) {
                LAST_FAILURE_REASON.set("extraction ffmpeg du segment à " + offsetSec
                    + "s en échec : " + e.getMessage());
                return null;
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(bin, "audio-file-to-recognized-song",
                    segment.getAbsolutePath());
            pb.redirectErrorStream(false);
            String json = ProcessUtils.readStringWithTimeout(pb, 30);
            if (json == null || json.isBlank()) {
                LAST_FAILURE_REASON.set("binaire songrec sans réponse à " + offsetSec
                    + "s (timeout 30s ou binaire indisponible)");
                return null;
            }
            if (!json.contains("\"track\"")) {
                LAST_FAILURE_REASON.set("aucun morceau reconnu par Shazam à " + offsetSec + "s");
                return null;
            }
            TagInfo parsed = parse(json);
            if (parsed == null)
                LAST_FAILURE_REASON.set("réponse SongRec à " + offsetSec
                    + "s sans titre/artiste exploitable");
            return parsed;
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
        }
    }

    /** Durée en secondes via ffprobe, -1 si indisponible. */
    private double probeDuration(File f) {
        try {
            // "ffprobe" en dur ignorait audio.ffprobe_path — voir AudioDuration.probeSeconds()
            // pour le même correctif et la raison (chemin ffmpeg/ffprobe personnalisé hors PATH).
            ProcessBuilder pb = new ProcessBuilder(
                Config.get().str("audio.ffprobe_path", "ffprobe"), "-v", "error",
                "-show_entries", "format=duration",
                "-of", "csv=p=0",
                f.getAbsolutePath());
            pb.redirectErrorStream(true);
            String out = ProcessUtils.readStringWithTimeout(pb, 10);
            if (out != null && !out.isBlank()) return Double.parseDouble(out.trim());
        } catch (Exception ignored) {}
        return -1;
    }

    public static boolean isAvailable() {
        // SongRec n'a pas de build Windows officiel → désactivé silencieusement
        if (System.getProperty("os.name","").toLowerCase().contains("win")) return false;
        String bin = Config.get().str("songrec.path", SONGREC_BIN);
        try {
            Process p = new ProcessBuilder(bin, "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    private TagInfo parse(String json) throws Exception {
        JsonNode root  = mapper.readTree(json);
        JsonNode track = root.path("track");
        if (track.isMissingNode() || track.isNull()) return null;

        String title  = track.path("title").asText("").trim();
        String artist = track.path("subtitle").asText("").trim();
        if (title.isBlank() && artist.isBlank()) return null;

        TagInfo ti = new TagInfo();
        ti.title       = title;
        ti.artist      = artist;
        ti.albumArtist = artist;
        ti.score       = 85;

        JsonNode genres = track.path("genres");
        if (!genres.isMissingNode()) {
            String g = genres.path("primary").asText("").trim();
            if (!g.isBlank()) ti.genre = g;
        }

        String isrc = track.path("isrc").asText("").trim();
        if (!isrc.isBlank()) ti.isrc = isrc;

        // ID Apple Music (adamid album) — référence précise, contrairement aux liens Spotify/
        // Deezer/YouTube Music de la réponse Shazam qui ne sont que des requêtes de recherche
        // texte ("spotify:search:...", "...deezer.com/play?query=..."), pas de vrais identifiants
        // de piste : les stocker n'apporterait aucune précision réelle, donc pas repris ici.
        String appleId = track.path("albumadamid").asText("").trim();
        if (!appleId.isBlank()) ti.appleMusicId = appleId;

        // Pochette HD si disponible, sinon la version standard — voir TagEnrichment (fournisseur
        // "shazam") pour l'utilisation : évite de re-chercher une pochette à l'aveugle alors que
        // l'identification vient déjà d'en trouver une.
        JsonNode images = track.path("images");
        String coverUrl = images.path("coverarthq").asText("").trim();
        if (coverUrl.isBlank()) coverUrl = images.path("coverart").asText("").trim();
        if (!coverUrl.isBlank()) ti.shazamCoverUrl = coverUrl;

        for (JsonNode section : track.path("sections")) {
            for (JsonNode meta : section.path("metadata")) {
                String name = meta.path("title").asText("").trim();
                String text = meta.path("text").asText("").trim();
                switch (name) {
                    case "Album"    -> ti.album = text;
                    case "Released" -> ti.year  = text.length() >= 4 ? text.substring(0, 4) : text;
                    // Écrivait auparavant "Label: " + text dans ti.comment (faute d'un champ
                    // dédié) — comment sert à la désambiguïsation MusicBrainz (voir son
                    // commentaire de champ), pas de raison d'y mélanger le label. TagInfo.label
                    // existe maintenant (voir TaggingWorker/MusicBrainzClient) et est affiché/
                    // éditable dans DetailPanel → onglet URLs & IDs.
                    case "Label"    -> { if (ti.label.isBlank()) ti.label = text; }
                }
            }
        }
        return ti;
    }
}
