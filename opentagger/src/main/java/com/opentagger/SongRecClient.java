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

    // ── Limite Shazam (429) — 2026-09-26, retour utilisateur « les fichiers non identifiés ne sont
    // pas reconnus par SongRec » : vérifié en direct, Shazam répondait « 429 Too Many Requests » à
    // CHAQUE appel depuis ~09 h (même sur un morceau reconnu une heure plus tôt), songrec restait
    // alors bloqué jusqu'au timeout sans rien imprimer sur stdout, et le fichier finissait « Non
    // identifié » (puis déplacé vers Sans_correspondance) alors que Shazam n'avait jamais vraiment
    // répondu. Désormais : 429 (lu sur stderr) ou 3 non-réponses d'affilée → SongRec en PAUSE
    // (1 min, doublée à chaque récidive jusqu'à 15 min) ; pendant la pause, aucun appel, et le
    // fichier est marqué « limité » (wasThrottled) pour être retenté plus tard, pas classé.
    private static volatile long pausedUntilMs = 0;
    // Rythme de songrec-rename (2026-10-13, demande utilisateur : « pourquoi on ne fait pas comme lui ? ») : 1 s après un
    // morceau reconnu, 4 s après un échec, pas de budget. La pause ne sert plus qu'au vrai blocage de Shazam (429) et
    // reste courte : 1 min, doublée seulement si le blocage continue, 15 min au plus (avant : 15 min → 2 h).
    static final long FIRST_PAUSE_MS = 60_000L;
    private static volatile long pauseMs = FIRST_PAUSE_MS;
    private static volatile boolean lastWasMatch = true;
    private static final java.util.concurrent.atomic.AtomicInteger NO_ANSWER_STREAK =
        new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<Boolean> THROTTLED = new ThreadLocal<>();
    private static volatile long lastCallMs = 0;
    private static final java.util.concurrent.atomic.AtomicInteger ANSWERED_STREAK =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Vrai si le dernier recognize() de CE thread n'a pas pu interroger Shazam (limite 429/pause). */
    public static boolean wasThrottled() { return Boolean.TRUE.equals(THROTTLED.get()); }
    public static void resetThrottled() { THROTTLED.remove(); }
    public static boolean isPaused() { return System.currentTimeMillis() < pausedUntil(); }
    public static long pausedUntil() { return Math.max(pausedUntilMs, budgetUntilMs); }

    // ── Budget d'appels (2026-10-10) ──────────────────────────────────────────────────────────────────────────────────
    // Journal réel : sur 884 fichiers, Shazam a répondu 429 après ~10 min de SongRec, et la pause réactive ci-dessus n'intervient
    // qu'APRÈS la limite. On borne donc proactivement le nombre d'appels sur une fenêtre glissante de 10 min
    // (songrec.max_calls_per_10min ; 0 = illimité, le défaut depuis le 2026-10-13 — rythme de songrec-rename). Passé ce budget, les fichiers restants sont
    // « retentés plus tard » (même chemin que la limite 429), sans toucher Shazam : le lot continue avec les autres méthodes.
    private static final long BUDGET_WINDOW_MS = 10 * 60_000L;
    private static final java.util.ArrayDeque<Long> CALL_TIMES = new java.util.ArrayDeque<>();
    private static volatile long budgetUntilMs = 0;

    /** Réserve un appel Shazam. @return 0 si autorisé, sinon l'instant où la fenêtre se libère. */
    static synchronized long reserveCall(long now, int maxPerWindow) {
        if (maxPerWindow <= 0) return 0;
        while (!CALL_TIMES.isEmpty() && now - CALL_TIMES.peekFirst() >= BUDGET_WINDOW_MS) CALL_TIMES.pollFirst();
        if (CALL_TIMES.size() >= maxPerWindow) {
            budgetUntilMs = CALL_TIMES.peekFirst() + BUDGET_WINDOW_MS;
            return budgetUntilMs;
        }
        CALL_TIMES.addLast(now);
        return 0;
    }

    /** Écart entre deux fichiers « retentés plus tard » : le rythme que Shazam accepte (budget, ou espacement
     *  minimal s'il est plus lent), pour ~2 appels par fichier. */
    public static long retrySpacingMs() {
        int max = Config.get().num("songrec.max_calls_per_10min", 0);
        long perCall = Math.max(max > 0 ? BUDGET_WINDOW_MS / max : 0, delayAfterMissMs());
        return 2 * perCall;
    }

    static long delayAfterMatchMs() { return Config.get().num("songrec.delay_after_match_ms", 1000); }
    static long delayAfterMissMs()  { return Config.get().num("songrec.delay_after_miss_ms", 4000); }

    /** Attente avant le prochain appel : comme songrec-rename, 1 s après un morceau reconnu, 4 s après un échec. */
    static long waitBeforeNextCall(long now, long lastCall, boolean lastMatched, long afterMatch, long afterMiss) {
        return Math.max(0, lastCall + (lastMatched ? afterMatch : afterMiss) - now);
    }

    /** Pour les tests : oublie l'historique d'appels. */
    static synchronized void resetBudget() { CALL_TIMES.clear(); budgetUntilMs = 0; }

    private static synchronized void pause(String why) {
        long now = System.currentTimeMillis();
        if (now < pausedUntilMs) return; // déjà en pause (autre thread)
        pausedUntilMs = now + pauseMs;
        ANSWERED_STREAK.set(0);
        System.out.println("[OT] ⏸ SongRec en pause " + (pauseMs >= 60_000 ? (pauseMs / 60_000) + " min" : (pauseMs / 1000) + " s")
            + " (" + why + ") — fichiers concernés retentés ensuite, pas classés « Non identifié ».");
        pauseMs = Math.min(pauseMs * 2, 15 * 60_000L);
    }

    private static void answered() {
        NO_ANSWER_STREAK.set(0);
        if (ANSWERED_STREAK.incrementAndGet() >= 50) {
            ANSWERED_STREAK.set(0);
            pauseMs = FIRST_PAUSE_MS;
        }
    }

    public TagInfo recognize(File audioFile) throws Exception {
        LAST_FAILURE_REASON.remove();
        THROTTLED.remove();
        // Coquille (ex. « __1-01 05 Cake by the Ocean 3.mp3 », 1 348 octets : un en-tête ID3 et
        // rien d'autre) : songrec y restait bloqué jusqu'au timeout, comptant à tort comme une
        // non-réponse de Shazam.
        long len = audioFile.length();
        if (len < 64 * 1024) {
            LAST_FAILURE_REASON.set("fichier trop petit pour contenir de l'audio (" + len + " octets)");
            return null;
        }
        String bin = SongRecInstaller.resolve();
        if (bin == null) {
            LAST_FAILURE_REASON.set("SongRec introuvable (Préférences → Audio)");
            return null;
        }
        // Comme songrec-rename (2026-10-13, demande utilisateur) : UNE demande à Shazam par fichier, et une vraie file
        // d'attente. Avant : jusqu'à 4 demandes par fichier (3 extraits + un second extrait de confirmation), donc Shazam
        // bloquait 2 à 4 fois plus vite ; et pendant un blocage chaque fichier était sauté (« Pas encore vérifié par
        // Shazam — retenté plus tard », des centaines de lignes en deux minutes). Désormais le fichier ATTEND la fin du
        // blocage puis est envoyé, l'un après l'autre (SHAZAM_GATE). Extrait pris au tiers des morceaux longs (passe
        // les intros), le fichier entier pour les courts — comme songrec-rename qui donne le fichier entier.
        double duration = probeDuration(audioFile);
        int offset = duration > 120 ? (int) (duration / 3) : 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            waitWhilePaused();
            THROTTLED.remove();
            TagInfo result = recognizeAt(bin, audioFile, offset, duration);
            if (!wasThrottled()) {
                if (result == null && LAST_FAILURE_REASON.get() == null)
                    LAST_FAILURE_REASON.set("aucun morceau reconnu par Shazam");
                return result;
            }
        }
        return null;   // toujours bloqué après 6 essais : marqué « limité », retenté plus tard par TaggingWorker
    }

    /** Attend la fin d'un blocage Shazam (pause ou budget) au lieu de sauter le fichier. */
    private static void waitWhilePaused() throws InterruptedException {
        long now;
        while ((now = System.currentTimeMillis()) < pausedUntil())
            Thread.sleep(Math.min(1000, pausedUntil() - now));
    }

    private TagInfo recognizeAt(String bin, File audioFile, int offsetSec, double duration) throws Exception {
        File segment = audioFile;
        Path tmp     = null;

        // Si offset > 0 ou fichier très long : extraire un segment WAV via ffmpeg — SEULE portion
        // de recognizeAt() qui touche réellement le disque source (le fichier temporaire produit
        // vit sous /tmp, et le binaire songrec appelé plus bas ne lit plus que lui + le réseau) :
        // le permis DiskIoThrottle est donc pris et rendu ICI seulement, pas autour de l'appel
        // songrec qui suit (voir le commentaire de recognize() ci-dessus).
        if (offsetSec > 0 || duration > 120 || !decodesNatively(audioFile)) {
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
            Path errFile = null;
            try {
                if (isPaused()) {
                    THROTTLED.set(true);
                    LAST_FAILURE_REASON.set("SongRec en pause (Shazam limite les requêtes)");
                    return null;
                }
                long budgetFree = reserveCall(System.currentTimeMillis(), Config.get().num("songrec.max_calls_per_10min", 0));
                if (budgetFree > 0) {
                    THROTTLED.set(true);
                    LAST_FAILURE_REASON.set("budget d'appels Shazam atteint (" + Config.get().num("songrec.max_calls_per_10min", 0)
                        + " / 10 min) — reprise à " + new java.text.SimpleDateFormat("HH:mm").format(new java.util.Date(budgetFree)));
                    return null;
                }
                // Espacement entre deux appels Shazam : celui de songrec-rename (voir lastWasMatch).
                long wait = waitBeforeNextCall(System.currentTimeMillis(), lastCallMs, lastWasMatch,
                        delayAfterMatchMs(), delayAfterMissMs());
                if (wait > 0) Thread.sleep(wait);
                ProcessBuilder pb = new ProcessBuilder(bin, "audio-file-to-recognized-song",
                        segment.getAbsolutePath());
                pb.redirectErrorStream(false);
                errFile = Files.createTempFile("ot_songrec_err_", ".txt");
                pb.redirectError(errFile.toFile());
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
                if (json == null || json.isBlank() || !json.contains("{")) {
                    String err = "";
                    try { err = Files.readString(errFile); } catch (Exception ignored) {}
                    if (err.contains("429") || err.contains("Too Many Requests")) {
                        THROTTLED.set(true);
                        pause("Shazam : 429 Too Many Requests");
                        LAST_FAILURE_REASON.set("Shazam limite les requêtes (429) à " + offsetSec + "s");
                        return null;
                    }
                    lastWasMatch = false;
                    if (NO_ANSWER_STREAK.incrementAndGet() >= 3) {
                        THROTTLED.set(true);
                        pause("3 appels sans réponse d'affilée");
                    }
                    LAST_FAILURE_REASON.set("binaire songrec sans réponse à " + offsetSec
                        + "s (timeout 8s ou binaire indisponible)");
                    return null;
                }
                answered();
                lastWasMatch = json.contains("\"track\"");
                if (!lastWasMatch) {
                    LAST_FAILURE_REASON.set("aucun morceau reconnu par Shazam à " + offsetSec + "s");
                    return null;
                }
                TagInfo parsed = parse(json);
                if (parsed == null)
                    LAST_FAILURE_REASON.set("réponse SongRec à " + offsetSec
                        + "s sans titre/artiste exploitable");
                return parsed;
            } finally {
                // Compté à partir de la FIN de l'appel, comme songrec-rename (il attend 1 s / 4 s après la réponse) :
                // compté depuis le début, un appel de 3 s suivi d'un autre 1 s après son départ = 2× plus de requêtes,
                // et Shazam a bloqué l'adresse plus d'1 h 30 le 13/10 au soir (pauses de 15 min enchaînées).
                lastCallMs = System.currentTimeMillis();
                SHAZAM_GATE.release();
                if (errFile != null) { try { Files.deleteIfExists(errFile); } catch (IOException ignored) {} }
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

    /** Vrai si un binaire SongRec utilisable existe : celui des Préférences, celui du PATH, ou — sous
     *  Windows — celui que l'application installe elle-même (voir {@link SongRecInstaller}). */
    public static boolean isAvailable() {
        return SongRecInstaller.isAvailable();
    }

    /** Formats que SongRec décode seul ; tout le reste passe d'abord par ffmpeg (m4a/aac/wma/opus…). */
    private static boolean decodesNatively(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg");
    }

    private TagInfo parse(String json) throws Exception {
        JsonNode root  = mapper.readTree(json);
        JsonNode track = root.path("track");
        if (track.isMissingNode() || track.isNull()) return null;

        String title  = JsonText.of(track.path("title"), "").trim();
        String artist = JsonText.of(track.path("subtitle"), "").trim();
        if (title.isBlank() && artist.isBlank()) return null;

        TagInfo ti = new TagInfo();
        ti.title       = title;
        ti.artist      = artist;
        ti.albumArtist = artist;
        ti.score       = 85;

        JsonNode genres = track.path("genres");
        if (!genres.isMissingNode()) {
            String g = JsonText.of(genres.path("primary"), "").trim();
            if (!g.isBlank()) ti.genre = g;
        }

        String isrc = JsonText.of(track.path("isrc"), "").trim();
        if (!isrc.isBlank()) ti.isrc = isrc;

        // ID Apple Music (adamid album) — référence précise, contrairement aux liens Spotify/
        // Deezer/YouTube Music de la réponse Shazam qui ne sont que des requêtes de recherche
        // texte ("spotify:search:...", "...deezer.com/play?query=..."), pas de vrais identifiants
        // de piste : les stocker n'apporterait aucune précision réelle, donc pas repris ici.
        String appleId = JsonText.of(track.path("albumadamid"), "").trim();
        if (!appleId.isBlank()) ti.appleMusicId = appleId;

        // Pochette HD si disponible, sinon la version standard — voir TagEnrichment (fournisseur
        // "shazam") pour l'utilisation : évite de re-chercher une pochette à l'aveugle alors que
        // l'identification vient déjà d'en trouver une.
        JsonNode images = track.path("images");
        String coverUrl = JsonText.of(images.path("coverarthq"), "").trim();
        if (coverUrl.isBlank()) coverUrl = JsonText.of(images.path("coverart"), "").trim();
        if (!coverUrl.isBlank()) ti.shazamCoverUrl = coverUrl;

        for (JsonNode section : track.path("sections")) {
            for (JsonNode meta : section.path("metadata")) {
                String name = JsonText.of(meta.path("title"), "").trim();
                String text = JsonText.of(meta.path("text"), "").trim();
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
