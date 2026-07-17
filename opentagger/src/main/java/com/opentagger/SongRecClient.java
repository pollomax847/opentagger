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

    public TagInfo recognize(File audioFile) throws Exception {
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
                if (result != null) return result;
            }
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
                if (!Files.exists(tmp) || Files.size(tmp) < 1000) return null;
                segment = tmp.toFile();
            } catch (Exception e) {
                return null;
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(bin, "audio-file-to-recognized-song",
                    segment.getAbsolutePath());
            pb.redirectErrorStream(false);
            String json = ProcessUtils.readStringWithTimeout(pb, 30);
            if (json == null || json.isBlank() || !json.contains("\"track\"")) return null;
            return parse(json);
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (IOException ignored) {} }
        }
    }

    /** Durée en secondes via ffprobe, -1 si indisponible. */
    private double probeDuration(File f) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffprobe", "-v", "error",
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
                    case "Label"    -> { if (ti.comment.isBlank()) ti.comment = "Label: " + text; }
                }
            }
        }
        return ti;
    }
}
