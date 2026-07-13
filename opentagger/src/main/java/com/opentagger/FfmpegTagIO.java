package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lecture/écriture des tags pour les formats que jaudiotagger 3.0.1 ne sait PAS lire du tout —
 * confirmé en décompilant le jar (son enum {@code SupportedFileFormat} ne liste ni Opus ni AAC
 * brut) plutôt qu'en le supposant. Contourne via ffmpeg/ffprobe, déjà une dépendance obligatoire
 * de l'appli (voir {@link AudioTranscoder}), avec le même style d'appel processus que
 * {@code TagWriter.writeM4aViaFfmpeg()} (fichier temporaire + remplacement, timeout, drain du
 * flux en parallèle du {@code waitFor}).
 *
 * <p>Deux comportements différents selon le conteneur, vérifiés en direct sur de vrais fichiers
 * avant d'écrire ce code (voir le plan de cette tâche) :
 * <ul>
 *   <li><b>Opus</b> (conteneur Ogg) : les tags sont des commentaires Vorbis, exposés par ffprobe
 *       au niveau du FLUX ({@code streams[0].tags}), pas du format — contrairement à MP3/M4A. Une
 *       simple écriture {@code -metadata clé=valeur} suffit, ffmpeg les place déjà correctement.</li>
 *   <li><b>AAC brut</b> (.aac, flux élémentaire ADTS sans conteneur) : sans rien de plus, ffmpeg
 *       ACCEPTE silencieusement {@code -metadata} à l'écriture mais ne persiste RIEN sur disque —
 *       le muxer ADTS n'a par défaut aucun mécanisme de stockage de métadonnées. Il faut forcer
 *       {@code -write_id3v2 1} (option du muxer ADTS) pour qu'un vrai tag ID3v2 soit écrit en
 *       tête de fichier ; côté lecture, il ressort alors normalement dans {@code format.tags},
 *       comme pour un MP3.</li>
 * </ul>
 *
 * <p>Pas de pochette ici (ni lecture ni écriture) — même choix assumé que
 * {@code TagWriter.writeM4aViaFfmpeg()} ("trop fragile"), cohérent avec ce fallback existant.
 * Les clés MusicBrainz utilisent les noms standards Vorbis-comment (convention Picard :
 * {@code MUSICBRAINZ_TRACKID} = enregistrement, {@code MUSICBRAINZ_ALBUMID} = release...), pas
 * les noms internes de jaudiotagger, pour rester interopérable avec les autres outils qui liraient
 * ces mêmes fichiers.
 */
public final class FfmpegTagIO {

    private FfmpegTagIO() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static boolean handles(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".opus") || n.endsWith(".aac");
    }

    private static String ffmpegPath()  { return Config.get().str("audio.ffmpeg_path",  "ffmpeg");  }
    private static String ffprobePath() { return Config.get().str("audio.ffprobe_path", "ffprobe"); }

    // ── Lecture ──────────────────────────────────────────────────────────────

    public static TagInfo read(File f) {
        TagInfo ti = new TagInfo();
        try {
            List<String> cmd = List.of(ffprobePath(), "-v", "quiet", "-print_format", "json",
                    "-show_format", "-show_streams", f.getAbsolutePath());
            String json = runCapture(cmd, 20);
            JsonNode root = MAPPER.readTree(json);

            // Fusionne format.tags (AAC/ID3v2) ET streams[0].tags (Opus/Vorbis comments) — les
            // deux ne sont jamais renseignés en même temps pour un même fichier (voir Javadoc de
            // la classe), donc pas de risque de collision entre les deux sources.
            Map<String, String> tags = new HashMap<>();
            collectTags(root.path("format").path("tags"), tags);
            for (JsonNode s : root.path("streams")) collectTags(s.path("tags"), tags);
            if (tags.isEmpty()) return ti;

            ti.title       = tag(tags, "title");
            ti.artist      = tag(tags, "artist");
            ti.albumArtist = tag(tags, "album_artist");
            ti.album       = tag(tags, "album");
            String date    = tag(tags, "date");
            ti.year        = date.length() >= 4 ? date.substring(0, 4) : date;
            ti.genre       = tag(tags, "genre");
            ti.comment     = tag(tags, "comment");
            ti.composer    = tag(tags, "composer");
            ti.isrc        = tag(tags, "ISRC");
            ti.bpm         = tag(tags, "BPM");

            String[] trk = splitTotal(tag(tags, "track"));
            ti.track = trk[0]; ti.trackTotal = trk[1];
            String[] dsk = splitTotal(tag(tags, "disc"));
            ti.discNo = dsk[0]; ti.discTotal = dsk[1];

            // Noms standards Vorbis-comment (convention Picard), pas les noms FieldKey internes
            // de jaudiotagger — ce sont ces clés-là qu'écrit write() ci-dessous, et celles que
            // tout autre tagger correctement écrit sur ce type de fichier utiliserait aussi.
            ti.artistMbid       = tag(tags, "MUSICBRAINZ_ARTISTID");
            ti.releaseMbid      = tag(tags, "MUSICBRAINZ_ALBUMID");
            ti.recordingMbid    = tag(tags, "MUSICBRAINZ_TRACKID");
            ti.releaseGroupMbid = tag(tags, "MUSICBRAINZ_RELEASEGROUPID");
        } catch (Exception ignored) {}
        return ti;
    }

    private static void collectTags(JsonNode tagsNode, Map<String, String> out) {
        if (!tagsNode.isObject()) return;
        var it = tagsNode.fields();
        while (it.hasNext()) {
            var e = it.next();
            out.put(e.getKey().toUpperCase(), e.getValue().asText(""));
        }
    }

    private static String tag(Map<String, String> tags, String key) {
        String v = tags.get(key.toUpperCase());
        return v != null ? v : "";
    }

    /** "4/12" → {"4","12"} ; "4" → {"4",""} ; "" → {"",""}. */
    private static String[] splitTotal(String combined) {
        if (combined.isBlank()) return new String[]{"", ""};
        int i = combined.indexOf('/');
        return i < 0 ? new String[]{combined.trim(), ""}
                     : new String[]{combined.substring(0, i).trim(), combined.substring(i + 1).trim()};
    }

    // ── Écriture ─────────────────────────────────────────────────────────────

    public static void write(File fichier, TagInfo i) throws Exception {
        boolean isAac = fichier.getName().toLowerCase().endsWith(".aac");
        String ext = isAac ? ".aac" : ".opus";
        File tmp = File.createTempFile("ot_ffio_", ext, fichier.getParentFile());
        tmp.delete(); // ffmpeg crée le fichier lui-même

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath()); cmd.add("-y");
        cmd.add("-i"); cmd.add(fichier.getAbsolutePath());
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-map_metadata"); cmd.add("-1"); // efface les tags existants, comme writeM4aViaFfmpeg()
        // Sans ce flag, le muxer ADTS accepte -metadata sans erreur mais ne persiste RIEN sur
        // disque (confirmé en direct avant d'écrire ce code, voir Javadoc de la classe) — pas
        // nécessaire/sans effet pour Opus (Ogg gère nativement les commentaires Vorbis).
        if (isAac) { cmd.add("-write_id3v2"); cmd.add("1"); }

        meta(cmd, "title",        i.title);
        meta(cmd, "artist",       i.artist);
        meta(cmd, "album_artist", i.albumArtist);
        meta(cmd, "album",        i.album);
        meta(cmd, "date",         i.year);
        meta(cmd, "genre",        i.genre);
        meta(cmd, "composer",     i.composer);
        meta(cmd, "comment",      i.comment);
        meta(cmd, "ISRC",         i.isrc);
        meta(cmd, "BPM",          i.bpm);
        meta(cmd, "MUSICBRAINZ_ARTISTID",       i.artistMbid);
        meta(cmd, "MUSICBRAINZ_ALBUMID",        i.releaseMbid);
        meta(cmd, "MUSICBRAINZ_TRACKID",        i.recordingMbid);
        meta(cmd, "MUSICBRAINZ_RELEASEGROUPID", i.releaseGroupMbid);

        if (!i.track.isBlank())
            meta(cmd, "track", i.trackTotal.isBlank() ? i.track : i.track + "/" + i.trackTotal);
        if (!i.discNo.isBlank())
            meta(cmd, "disc", i.discTotal.isBlank() ? i.discNo : i.discNo + "/" + i.discTotal);

        cmd.add(tmp.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Process p = pb.start();
            Thread drain = Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(out); } catch (Exception ignored) {}
            });
            // Pas un simple -metadata (quasi instantané) : -c copy remuxe le flux entier, donc le
            // temps dépend de la taille du fichier (mesuré ~6s pour 110 Mo en pratique) — délai
            // large pour couvrir un fichier volumineux sur un montage lent (mergerfs...).
            boolean done = p.waitFor(180, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); tmp.delete(); throw new IOException("timeout ffmpeg"); }
            try { drain.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (p.exitValue() != 0 || !tmp.exists() || tmp.length() == 0) {
                tmp.delete();
                String tail = out.toString(StandardCharsets.UTF_8).strip();
                if (tail.length() > 400) tail = "…" + tail.substring(tail.length() - 400);
                throw new IOException("ffmpeg exit=" + p.exitValue() + " — " + tail);
            }
            Files.move(tmp.toPath(), fichier.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            tmp.delete();
            throw new Exception("Écriture " + ext + " via ffmpeg : " + e.getMessage(), e);
        }
    }

    private static void meta(List<String> cmd, String key, String value) {
        if (value != null && !value.isBlank()) { cmd.add("-metadata"); cmd.add(key + "=" + value); }
    }

    private static String runCapture(List<String> cmd, int timeoutSec) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (InputStream is = p.getInputStream()) { is.transferTo(out); } catch (Exception ignored) {}
        });
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); throw new IOException("timeout ffprobe"); }
        try { drain.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
