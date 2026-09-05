package com.opentagger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

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

    // Limite dédiée aux appels RÉSEAU à Shazam (indépendante de DiskIoThrottle, qui ne régule que
    // le disque) — ajoutée après un vrai incident (2026-08-09) : en cessant de tenir le permis
    // disque pendant l'appel réseau (voir recognize()/recognizeAt() plus bas, correctif du même
    // jour visant justement à ne plus bloquer les autres threads pour rien pendant l'attente
    // Shazam), les batch.threads threads (6 par défaut) se sont mis à taper Shazam VRAIMENT en même
    // temps — jusque-là accidentellement sérialisés par l'ancien bug. Reproduit en direct : un seul
    // appel manuel à `songrec audio-file-to-recognized-song` renvoyait déjà "429 Too Many Requests
    // / Your IP has been rate-limited", et le journal live montrait TOUS les appels SongRec récents
    // en échec avec des délais de 50-150s (mise en file/contention) au lieu d'un échec rapide.
    // 1 par défaut (configurable via songrec.max_concurrent) : Shazam limite par débit de requêtes,
    // pas par nombre de threads Java, donc un seul appel à la fois reste le choix le plus sûr.
    private static final java.util.concurrent.Semaphore SHAZAM_GATE =
        new java.util.concurrent.Semaphore(Math.max(1, Config.get().num("songrec.max_concurrent", 1)));

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
        // Le permis DiskIoThrottle n'est PLUS pris ici pour tout l'appel : il est acquis/libéré à
        // l'intérieur de recognizeAt(), autour de la seule extraction ffmpeg (vraie E/S disque sur
        // le fichier source). Avant ce correctif, le même permis restait tenu pendant TOUT l'appel
        // réseau au binaire songrec (reconnaissance Shazam, plusieurs secondes, aucun rapport avec
        // le disque) — jusqu'à 4 allers-retours réseau (2 offsets × 1re passe + confirmation) tenant
        // le seul permis du disque mécanique concerné, immobilisant les 5 AUTRES threads du pool
        // (batch.threads=6) qui n'attendaient, eux, qu'une E/S disque réellement rapide. Constaté en
        // direct (2026-08-07) via jstack : 6 threads bloqués depuis 14h sur LE MÊME Semaphore, CPU
        // quasi nul, alors que iostat montrait le disque à 13-32% d'utilisation seulement (pas
        // 80-98% comme dans le scénario ayant motivé DiskIoThrottle à l'origine) — la vraie attente
        // était le réseau (Shazam), pas la tête de lecture.
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

        TagInfo first = null;
        int firstOffset = -1;
        for (int offset : offsets) {
            TagInfo result = recognizeAt(bin, audioFile, offset, duration);
            if (result != null) { first = result; firstOffset = offset; break; }
        }
        if (first == null) {
            if (LAST_FAILURE_REASON.get() == null)
                LAST_FAILURE_REASON.set("aucun résultat sur " + offsets.length + " segment(s) testé(s)");
            return null;
        }

        // Deuxième vérification, sur un extrait DIFFÉRENT du même fichier — trouvée nécessaire
        // en direct (2026-08-01) : un fichier nommé "Rasputin" (durée réelle 4:28, cohérente
        // avec le vrai Rasputin de Boney M) a été identifié par SongRec comme "Some L.A.
        // N****z" de Dr. Dre — une empreinte de 15 secondes suffit parfois à matcher le
        // mauvais morceau (rythme/sample partagé), et ce faux positif n'avait aucune fiche
        // MusicBrainz avec durée pour être rattrapé par isDurationMismatch. Un vrai morceau
        // correctement reconnu se reconfirme presque toujours sur un second extrait pris
        // ailleurs dans le fichier ; un faux positif isolé, beaucoup plus rarement. N'annule
        // QUE si le second extrait pointe vers un AUTRE morceau (désaccord net) — un second
        // extrait sans résultat (silence, parole, générique...) ne prouve rien et ne doit pas
        // invalider une identification par ailleurs correcte.
        int secondOffset = -1;
        for (int o : offsets) if (o != firstOffset) { secondOffset = o; break; }
        if (secondOffset < 0 && duration > 30) {
            int mid = (int) (duration / 2);
            if (Math.abs(mid - firstOffset) > 5) secondOffset = mid;
        }
        if (secondOffset >= 0) {
            TagInfo second = recognizeAt(bin, audioFile, secondOffset, duration);
            if (second != null && !sameTrack(first, second)) {
                LAST_FAILURE_REASON.set("confirmation contredite : \"" + first.artist + " - " + first.title
                    + "\" (" + firstOffset + "s) vs \"" + second.artist + " - " + second.title
                    + "\" (" + secondOffset + "s) — rejeté par prudence");
                return null;
            }
        }

        LAST_FAILURE_REASON.remove();
        return first;
    }

    /** Même morceau à la ponctuation/casse près, et en ignorant un qualificatif entre parenthèses
     *  en fin de titre (Shazam renvoie parfois "(Explicit)"/"(Radio Edit)" de façon inconsistante
     *  d'un extrait à l'autre du même vrai morceau — voir le même traitement dans TaggingWorker
     *  pour le "titre nettoyé"). */
    private static boolean sameTrack(TagInfo a, TagInfo b) {
        return normalize(a.artist).equals(normalize(b.artist))
            && normalizeTitle(a.title).equals(normalizeTitle(b.title));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeTitle(String s) {
        return normalize(s).replaceAll("\\s*\\([^)]*\\)\\s*$", "");
    }

    private TagInfo recognizeAt(String bin, File audioFile, int offsetSec, double duration) throws Exception {
        File segment = audioFile;
        Path tmp     = null;

        // Si offset > 0 ou fichier très long : extraire un segment WAV via ffmpeg — SEULE portion
        // de recognizeAt() qui touche réellement le disque source (le fichier temporaire produit
        // vit sous /tmp, et le binaire songrec appelé plus bas ne lit plus que lui + le réseau) :
        // le permis DiskIoThrottle est donc pris et rendu ICI seulement, pas autour de l'appel
        // songrec qui suit (voir le commentaire de recognize() ci-dessus).
        if (offsetSec > 0 || duration > 120) {
            tmp = Files.createTempFile("ot_shazam_", ".wav");
            java.util.concurrent.Semaphore gate = DiskIoThrottle.acquireFor(audioFile);
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
            } finally {
                DiskIoThrottle.release(gate);
            }
        }

        // Si aucune extraction n'a eu lieu (fichier court, offset 0), segment == audioFile : le
        // binaire songrec va alors lire directement le fichier source sur le disque mécanique
        // (contrairement au cas ci-dessus où il ne lit plus qu'un fichier temporaire sous /tmp) —
        // un permis est donc repris ici, MAIS seulement pour ce cas précis, pas pour l'appel réseau
        // qui suit dans le cas général (voir recognize()).
        java.util.concurrent.Semaphore songrecGate = tmp == null ? DiskIoThrottle.acquireFor(audioFile) : null;
        try {
            // SHAZAM_GATE encadre l'appel réseau lui-même (voir son commentaire de déclaration) —
            // acquis ICI, juste avant le seul point de tout recognizeAt() qui parle vraiment au
            // réseau, jamais autour de l'extraction ffmpeg au-dessus (purement locale/disque).
            SHAZAM_GATE.acquire();
            try {
                ProcessBuilder pb = new ProcessBuilder(bin, "audio-file-to-recognized-song",
                        segment.getAbsolutePath());
                pb.redirectErrorStream(false);
                // 8s (15s, puis 30s auparavant) : un appel Shazam normal répond en 1-5s, ce plafond
                // n'est qu'un filet de sécurité contre un vrai blocage, jamais censé être atteint en
                // usage courant. Recalibré une première fois le même jour que SHAZAM_GATE
                // (2026-08-10) — avec un seul appel Shazam autorisé à la fois, ce plafond pénalise
                // TOUTE la chaîne d'identification derrière lui à chaque fois qu'il est atteint
                // (jusqu'à 4 appels par fichier non reconnu, voir recognize()). Rebaissé une seconde
                // fois (2026-08-15, avec le passage d'AcoustID avant SongRec dans TaggingWorker) :
                // mesuré en direct sur 1441 appels réels cette nuit-là, la moyenne était de 51s avec
                // le plafond à 15s — largement dominée par les tentatives qui n'aboutissent pas et
                // consomment le plafond en entier plutôt que par de vraies réponses lentes.
                String json = ProcessUtils.readStringWithTimeout(pb, 8);
                if (json == null || json.isBlank()) {
                    LAST_FAILURE_REASON.set("binaire songrec sans réponse à " + offsetSec
                        + "s (timeout 8s ou binaire indisponible)");
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
                SHAZAM_GATE.release();
            }
        } finally {
            DiskIoThrottle.release(songrecGate);
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
                    case "Album"    -> { if (!isNoAlbumPlaceholder(text)) ti.album = text; }
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

    // Shazam renvoie parfois "Sans correspondance"/"Sans Correspondence" (mélange FR/EN, casse
    // incohérente selon l'appel) littéralement comme VALEUR du champ metadata "Album" — pas une
    // erreur réseau/JSON (le parsing réussit), juste leur propre texte de repli quand le morceau
    // n'appartient à aucun album canonique connu d'eux (single, ou juste absent de leur base). Sans
    // ce filtre, ti.album prenait ce texte tel quel comme si c'était un vrai titre d'album — repéré
    // en direct (2026-08-12) : des dizaines de morceaux d'artistes totalement différents (Cerrone,
    // Taio Cruz, Pat Martino, Uniting Nations…), tous par ailleurs correctement identifiés (artiste/
    // titre corrects), partageant ce même "album" absurde — jusqu'à finir renommés dans un dossier
    // ".../<Artiste>/Sans Correspondence/..." par le masque de renommage. Ni ce code Java ni le
    // binaire songrec (vérifié : `strings songrec | grep -i correspondanc` ne trouve rien) ne
    // produisent ce texte — il vient bien des serveurs Shazam eux-mêmes.
    private static boolean isNoAlbumPlaceholder(String text) {
        if (text == null || text.isBlank()) return false;
        String norm = text.trim().toLowerCase(Locale.ROOT);
        return norm.equals("sans correspondance") || norm.equals("sans correspondence")
            || norm.equals("no correspondence")   || norm.equals("no match");
    }
}
