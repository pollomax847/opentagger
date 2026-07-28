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
import java.nio.file.Path;
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
 * <p>Comportement différent selon le conteneur, vérifié en direct sur de vrais fichiers avant
 * d'écrire ce code (voir le plan de cette tâche) :
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
 *   <li><b>WavPack</b> (.wv) : tags au niveau du format comme MP3/M4A, aucun flag spécial requis
 *       — vérifié en écriture ET relecture avec un vrai fichier généré par ffmpeg.</li>
 *   <li><b>Monkey's Audio</b> (.ape) : LECTURE SEULE — {@code ffmpeg -encoders}/{@code -muxers} ne
 *       liste aucun encodeur ni muxer APE (seulement un démuxeur, donc lecture possible), contrairement
 *       à tous les autres formats ici. {@link #write} refuse ce format explicitement plutôt que de
 *       laisser échouer une commande ffmpeg vouée à l'échec avec un message confus. Non vérifié
 *       avec un vrai fichier .ape (aucun disponible dans la bibliothèque au moment d'écrire ce
 *       code) — repose sur la lecture d'API ffmpeg (démuxeur présent) seule.</li>
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
        // .wav : jaudiotagger sait le LIRE mais son WavTagWriter plante systématiquement à
        // l'écriture (IllegalArgumentException interne à sa propre classe — "No enum constant
        // FieldKey.IS_INSTRUMENTAL" — sur n'importe quel .wav, même avec un seul champ renseigné,
        // vérifié en direct). Contourné en passant aussi la LECTURE par ffmpeg plutôt que de garder
        // deux chemins différents pour le même format.
        return n.endsWith(".opus") || n.endsWith(".aac") || n.endsWith(".wv") || n.endsWith(".ape")
                || n.endsWith(".wav");
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
            // format.duration est toujours présent dans la réponse -show_format, indépendamment
            // des tags eux-mêmes (un fichier sans AUCUN tag a quand même une durée) — donc lu
            // avant le "tags.isEmpty()" ci-dessous, même raisonnement que MainFrame.readTags().
            double durationSeconds = root.path("format").path("duration").asDouble(0);
            if (durationSeconds > 0) ti.durationSec = (int) Math.round(durationSeconds);

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
            ti.taggedDate       = tag(tags, "OT_TAGGEDDATE");
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
        write(fichier, i, null);
    }

    /**
     * @param coverImage pochette résolue par TagEnrichment (CAA/FanArt/Deezer/Shazam) — actuellement
     *                    IGNORÉE ici (paramètre gardé pour un signature symétrique avec
     *                    TagWriter.write(), pas encore exploitable). Testé en direct, sur de vrais
     *                    fichiers, {@code -map 1:v -disposition:v attached_pic} (technique standard
     *                    qui fonctionne pour FLAC/MP4/MP3) sur les trois formats : Opus/Ogg échoue
     *                    ("Unsupported codec id in stream 1" — le muxer ogg de ce ffmpeg ne supporte
     *                    QUE le théora en vidéo, aucun mécanisme d'image attachée) ; WavPack échoue
     *                    ("This muxer only supports a single WavPack stream") ; AAC/ADTS accepte la
     *                    commande SANS ERREUR ("Stream ... (attached pic)" dans le log) mais la
     *                    pochette est silencieusement perdue à l'écriture — fichier de sortie
     *                    quasi identique en taille, aucune frame APIC ni flux vidéo à la relecture
     *                    (vérifié ffprobe + mid3v2). Aucune de ces trois voies n'est donc exploitable
     *                    avec ffmpeg tel quel ; embarquer une pochette pour ces formats demanderait de
     *                    construire les frames ID3v2/APEv2 à la main, hors scope ici.
     */
    public static void write(File fichier, TagInfo i, Path coverImage) throws Exception {
        String lower = fichier.getName().toLowerCase();
        if (lower.endsWith(".ape")) {
            // Pas un échec ffmpeg à traduire : aucun encodeur/muxer APE n'existe dans ffmpeg,
            // inutile de tenter quoi que ce soit (voir Javadoc de la classe). Message clair plutôt
            // que de laisser filer une erreur ffmpeg confuse sur un flux qu'il ne sait pas écrire.
            throw new Exception("Écriture .ape impossible : ffmpeg ne sait pas encoder/écrire ce"
                + " format (lecture seule). Aucun outil disponible ici pour le faire.");
        }
        boolean isAac = lower.endsWith(".aac");
        String ext = lower.endsWith(".wv")  ? ".wv"
                   : lower.endsWith(".wav") ? ".wav"
                   : isAac                  ? ".aac" : ".opus";
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

        // Parité de champs avec le chemin jaudiotagger natif (TagWriter.writeNative()) : jusqu'ici
        // Opus/AAC/WV n'avaient droit qu'à une quinzaine de champs de base — aucun mood, classique,
        // URL, ID Discogs/Apple Music/Roon, ReplayGain ni métadonnée release. Noms de clé vérifiés
        // empiriquement (pas devinés) en écrivant un TagInfo entièrement rempli via le vrai
        // TagWriter sur un .flac de test et en relisant les commentaires Vorbis bruts — ce sont
        // exactement les noms que produit jaudiotagger pour FLAC/OGG, donc round-trip cohérent avec
        // les autres formats (y compris quelques surprises réelles de la lib, ex. "ORIGINAL YEAR"
        // avec un espace, "PARTNUMBER" sans "_" — pas des fautes de frappe ici, une clé différente
        // casserait juste la relecture cohérente entre formats).
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
        meta(cmd, "MUSICBRAINZ_ALBUMARTISTID",  i.albumArtistMbid);
        meta(cmd, "MUSICBRAINZ_WORKID",         i.workMbid);
        meta(cmd, "OT_TAGGEDDATE",              i.taggedDate);

        // ── Tri ───────────────────────────────────────────────────────────────
        meta(cmd, "TITLESORT",       i.titleSort);
        meta(cmd, "ARTISTSORT",      i.artistSort);
        meta(cmd, "ALBUMSORT",       i.albumSort);
        meta(cmd, "ALBUMARTISTSORT", i.albumArtistSort);
        meta(cmd, "COMPOSERSORT",    i.composerSort);
        meta(cmd, "CONDUCTOR_SORT",  i.conductorSort);
        meta(cmd, "ORCHESTRA_SORT",  i.orchestraSort);
        meta(cmd, "ENSEMBLE_SORT",   i.ensembleSort);
        meta(cmd, "CHOIR_SORT",      i.choirSort);
        meta(cmd, "LYRICIST_SORT",   i.lyricistSort);
        meta(cmd, "PRODUCER_SORT",   i.producerSort);
        meta(cmd, "ARRANGER_SORT",   i.arrangerSort);
        meta(cmd, "MIXER_SORT",      i.mixerSort);
        meta(cmd, "ARTISTS",         i.artists);
        meta(cmd, "ARTISTS_SORT",    i.artistsSort);

        // ── Contributeurs ────────────────────────────────────────────────────
        meta(cmd, "CONDUCTOR", i.conductor);
        meta(cmd, "ORCHESTRA", i.orchestra);
        meta(cmd, "ENSEMBLE",  i.ensemble);
        meta(cmd, "CHOIR",     i.choir);
        meta(cmd, "LYRICIST",  i.lyricist);
        meta(cmd, "PRODUCER",  i.producer);
        meta(cmd, "ARRANGER",  i.arranger);
        meta(cmd, "ENGINEER",  i.engineer);
        meta(cmd, "MIXER",     i.mixer);
        meta(cmd, "DJMIXER",   i.djMixer);

        // ── Classique ────────────────────────────────────────────────────────
        meta(cmd, "WORK",               i.work);
        meta(cmd, "MOVEMENT",           i.movement);
        meta(cmd, "MOVEMENT_NO",        i.movementNo);
        meta(cmd, "MOVEMENT_TOTAL",     i.movementTotal);
        meta(cmd, "TITLE_MOVEMENT",     i.titleMovement);
        meta(cmd, "PART",               i.part);
        meta(cmd, "PART_TYPE",          i.partType);
        meta(cmd, "PARTNUMBER",         i.partNo);
        meta(cmd, "PERIOD",             i.period);
        meta(cmd, "OPUS",               i.opus);
        meta(cmd, "CLASSICAL_CATALOG",  i.classicalCatalog);
        meta(cmd, "CLASSICAL_NICKNAME", i.classicalNickname);
        meta(cmd, "SECTION",            i.section);
        meta(cmd, "OVERALL_WORK",       i.overallWork);
        meta(cmd, "GROUPING",           i.grouping);

        // ── Flags ────────────────────────────────────────────────────────────
        meta(cmd, "IS_CLASSICAL",      i.isClassical);
        meta(cmd, "COMPILATION",       i.isCompilation);
        meta(cmd, "IS_HD",             i.isHD);
        meta(cmd, "LIVE",              i.isLive);
        meta(cmd, "IS_GREATEST_HITS",  i.isGreatestHits);
        meta(cmd, "IS_SOUNDTRACK",     i.isSoundtrack);
        meta(cmd, "IS_INSTRUMENTAL",   i.isInstrumental);

        // ── Audio / tempo / tonalité ─────────────────────────────────────────
        meta(cmd, "FBPM",     i.fbpm);
        meta(cmd, "KEY",      i.initialKey);
        meta(cmd, "LANGUAGE", i.language);

        // ── Humeur (Essentia) ────────────────────────────────────────────────
        meta(cmd, "MOOD",              i.mood);
        meta(cmd, "MOOD_AGGRESSIVE",   i.moodAggressive);
        meta(cmd, "MOOD_ACOUSTIC",     i.moodAcoustic);
        meta(cmd, "MOOD_ELECTRONIC",   i.moodElectronic);
        meta(cmd, "MOOD_HAPPY",        i.moodHappy);
        meta(cmd, "MOOD_PARTY",        i.moodParty);
        meta(cmd, "MOOD_RELAXED",      i.moodRelaxed);
        meta(cmd, "MOOD_SAD",          i.moodSad);
        meta(cmd, "MOOD_VALENCE",      i.moodValence);
        meta(cmd, "MOOD_AROUSAL",      i.moodArousal);
        meta(cmd, "MOOD_DANCEABILITY", i.moodDanceability);
        meta(cmd, "MOOD_INSTRUMENTAL", i.moodInstrumental);

        // ── ReplayGain ───────────────────────────────────────────────────────
        meta(cmd, "REPLAYGAIN_TRACK_GAIN", i.replayGainTrackGain);
        meta(cmd, "REPLAYGAIN_TRACK_PEAK", i.replayGainTrackPeak);
        meta(cmd, "REPLAYGAIN_ALBUM_GAIN", i.replayGainAlbumGain);
        meta(cmd, "REPLAYGAIN_ALBUM_PEAK", i.replayGainAlbumPeak);

        // ── Paroles ──────────────────────────────────────────────────────────
        meta(cmd, "LYRICS",          i.lyrics);
        meta(cmd, "URL_LYRICS_SITE", i.lyricsUrl);

        // ── Rating & tags ────────────────────────────────────────────────────
        meta(cmd, "RATING", i.rating);
        meta(cmd, "TAGS",   i.tags);

        // ── Identifiants ─────────────────────────────────────────────────────
        meta(cmd, "ASIN",               i.amazonId);
        meta(cmd, "ROONALBUMTAG",       i.roonAlbumTag);
        meta(cmd, "ROONTRACKTAG",       i.roonTrackTag);
        meta(cmd, "DISCOGS_RELEASE_ID", i.discogsId);
        meta(cmd, "APPLE_MUSIC_ID",     i.appleMusicId);
        meta(cmd, "ACOUSTID_ID",          i.acoustidId);
        meta(cmd, "ACOUSTID_FINGERPRINT", i.acoustidFingerprint);

        // ── Métadonnées release ──────────────────────────────────────────────
        meta(cmd, "SCRIPT",                 i.script);
        meta(cmd, "COUNTRY",                i.country);
        meta(cmd, "BARCODE",                i.barcode);
        meta(cmd, "CATALOGNUMBER",          i.catalogNo);
        meta(cmd, "MUSICBRAINZ_ALBUMTYPE",  i.releaseType);
        meta(cmd, "ORIGINAL YEAR",          i.originalYear);
        meta(cmd, "LABEL",                  i.label);
        meta(cmd, "MUSICBRAINZ_ALBUMSTATUS", i.releaseStatus);
        meta(cmd, "MEDIA",                  i.media);

        // ── Podcast ──────────────────────────────────────────────────────────
        meta(cmd, "PODCAST_URL", i.podcastUrl);
        meta(cmd, "SEASON",      i.podcastSeason);
        meta(cmd, "EPISODE",     i.podcastEpisode);
        meta(cmd, "EPISODETYPE", i.podcastEpisodeType);
        meta(cmd, "KEYWORDS",    i.podcastKeywords);

        // ── Statistiques d'écoute ────────────────────────────────────────────
        meta(cmd, "LISTENBRAINZ_PLAYCOUNT", i.listenbrainzPlayCount);

        // ── URLs ─────────────────────────────────────────────────────────────
        meta(cmd, "URL_OFFICIAL_ARTIST_SITE",   i.artistOfficialUrl);
        meta(cmd, "URL_WIKIPEDIA_ARTIST_SITE",  i.artistWikipediaUrl);
        meta(cmd, "URL_DISCOGS_ARTIST_SITE",    i.artistDiscogsUrl);
        meta(cmd, "URL_OFFICIAL_RELEASE_SITE",  i.releaseOfficialUrl);
        meta(cmd, "URL_WIKIPEDIA_RELEASE_SITE", i.releaseWikipediaUrl);
        meta(cmd, "URL_DISCOGS_RELEASE_SITE",   i.releaseDiscogsUrl);

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
        if (value != null && !value.isBlank()) { cmd.add("-metadata"); cmd.add(key + "=" + TagWriter.sanitizeArg(value)); }
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
