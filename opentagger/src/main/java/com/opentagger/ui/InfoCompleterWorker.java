package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Passe complète style Jaikoz — enrichit les champs manquants de fichiers déjà identifiés.
 *
 * Contrairement à TaggingWorker, ne re-identifie pas le morceau (on connaît déjà
 * artiste + titre). Complète uniquement les champs vides :
 *   - album / année  → MB search (titre exact, puis titre nettoyé, puis artiste seul)
 *   - artistMbid     → MB artist search
 *   - genre          → Discogs puis Last.fm
 *   - mood           → Last.fm
 *   - BPM            → ffmpeg
 *   - paroles        → LyricsClient
 *   - pochette       → repérée ici (embarquée ou non), résolue seulement à l'Enregistrement
 *                      (TagEnrichment.saveEntry(), via SaveWorker — façon Picard, voir
 *                      completeEntry())
 *
 * Ne touche plus le disque : un fichier complété repasse en IDENTIFIED (même s'il était déjà
 * TAGGED) pour attendre un "Enregistrer tout" — voir completeEntry().
 *
 * Parallélisé (un thread-pool, même clé de config "batch.threads" que TaggingWorker/
 * BatchProcessor/AlbumCompletionWorker) — avant ça, cette passe traitait un fichier à la fois
 * malgré exactement le même profil d'appels bloquants (MB, Discogs/Last.fm, BPM ffmpeg, paroles)
 * que TaggingWorker avant sa propre parallélisation. MusicBrainzClient et LastFmClient tiennent
 * un état mutable entre appels (même règle déjà établie ailleurs) — instance fraîche par tâche ;
 * Discogs/Lyrics/BpmDetector sont sans état, et TaggerScript/MetadataCache sont protégés en
 * interne par leurs propres synchronized — tous restent des champs partagés, comme dans
 * TaggingWorker.
 */
public class InfoCompleterWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>        entries;
    private final Consumer<String>       onProgress;
    private final Consumer<FileEntry>    onUpdate;
    private final BiConsumer<Integer,Integer> onCount; // (done, total)

    private final DiscogsClient     discogs = new DiscogsClient();
    private final LocalCorrector    corrector = new LocalCorrector();
    private final TaggerScript      taggerScript = new TaggerScript();
    private final java.util.Map<String, String> aliasCache = new ConcurrentHashMap<>();
    private final LyricsClient      lyrics  = new LyricsClient();
    private final BpmDetector       bpmDet  = new BpmDetector();

    private final boolean bpmEnabled   = BpmDetector.isAvailable();

    private final AtomicInteger doneCount = new AtomicInteger();

    // Champ plutôt que variable locale de doInBackground() — même raison que TaggingWorker.pool/
    // AlbumCompletionWorker.pool (voir leurs commentaires) : sans ça, stopNow() n'interromprait
    // que le thread de doInBackground(), pas les tâches déjà soumises au pool.
    private volatile ExecutorService pool;

    public InfoCompleterWorker(List<FileEntry> entries,
                               Consumer<String> onProgress,
                               Consumer<FileEntry> onUpdate,
                               BiConsumer<Integer,Integer> onCount) {
        this.entries    = entries;
        this.onProgress = onProgress;
        this.onUpdate   = onUpdate;
        this.onCount    = onCount;
    }

    /** À appeler à la place de cancel(true) directement (SwingWorker.cancel() est final) — voir
     *  TaggingWorker.stopNow(), même raison et même correctif. */
    public void stopNow() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        cancel(true);
    }

    @Override
    protected Void doInBackground() throws Exception {
        int total = entries.size();

        int threads = Math.max(1, Config.get().num("batch.threads", 3));
        pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            if (isCancelled()) break;
            final FileEntry entry  = entries.get(i);
            final int       fileIdx = i + 1;
            // MetadataCache ajoutée aux instances fraîches par tâche le 2026-07-29 (même
            // correctif que TaggingWorker/SaveWorker) : ses méthodes sont toutes synchronized sur
            // l'instance (Connection JDBC unique, pas thread-safe) — la partager entre threads via
            // un champ `cache` sérialisait tout le monde au moindre accès cache. Chaque tâche ouvre
            // et ferme la sienne (try-with-resources).
            futures.add(pool.submit(() -> {
                try (MetadataCache taskCache = new MetadataCache()) {
                    processOne(entry, fileIdx, total, new MusicBrainzClient(), new LastFmClient(), taskCache);
                }
            }));
        }

        pool.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Traite un fichier. Appelé en parallèle, une tâche par fichier, depuis le pool créé dans
     *  doInBackground(). */
    private void processOne(FileEntry entry, int fileIdx, int total,
                             MusicBrainzClient mb, LastFmClient lastFm, MetadataCache cache) {
        if (isCancelled()) return;

        File fichier = entry.currentPath != null
                ? entry.currentPath.toFile()
                : entry.file;

        onProgress.accept(I18n.t("[%s/%s] %s", fileIdx, total, fichier.getName()));

        if (!fichier.exists()) {
            log(I18n.t("▶ SKIP %s (fichier introuvable)", fichier.getName()));
            onCount.accept(doneCount.incrementAndGet(), total);
            publish(entry);
            return;
        }

        CURRENT_FILE.set(fichier.getName());
        log(I18n.t("▶ COMPLÉTER %s", fichier.getName()));

        try {
            completeEntry(entry, fichier, mb, lastFm, cache);
        } catch (Exception ex) {
            log(I18n.t("  ✗ erreur: %s", ex.getMessage()));
        }

        onCount.accept(doneCount.incrementAndGet(), total);
        publish(entry);
    }

    private void completeEntry(FileEntry entry, File fichier, MusicBrainzClient mb, LastFmClient lastFm,
                                MetadataCache cache) throws Exception {
        // Lire le TagInfo actuel depuis e.result ou depuis le fichier
        TagInfo ti = entry.result != null ? entry.result : readTagsFromFile(fichier);
        if (ti == null || (ti.artist.isBlank() && ti.title.isBlank())) {
            log(I18n.t("  ignoré (artiste+titre vides)"));
            return;
        }

        boolean changed = false;

        // ── 1. MusicBrainz : album, année, IDs ────────────────────────────
        // Condition allégée : déclencher uniquement si données essentielles manquantes.
        // recordingMbid inclus : sans lui, un fichier avec artistMbid/album/year déjà remplis
        // (identifié par SongRec/texte sans jamais avoir été confirmé par une recherche MB) ne
        // relançait jamais cette recherche, alors que c'est justement recordingMbid.isBlank() qui
        // déclenche le badge "⚠ MBID d'enregistrement manquant" dans TaggingWorker.buildSuggestions.
        boolean needsMb = ti.album.isBlank() || ti.year.isBlank()
                || ti.artistMbid.isBlank()   || ti.recordingMbid.isBlank();
        if (needsMb) {
            TagInfo mbr = null;

            // Lookup direct si recordingMbid connu → plus rapide et précis que text search
            if (!ti.recordingMbid.isBlank()) {
                log(I18n.t("  MB lookup: %s", ti.recordingMbid));
                TagInfo full = mb.lookupRecording(ti.recordingMbid);
                if (full != null && !full.title.isBlank()) {
                    mbr = full;
                    log(I18n.t("  MB lookup→ %s – %s [%s %s]", full.artist, full.title, full.album, full.year));
                }
            }

            // Fallback : recherche texte si lookup échoue ou MBID absent
            if (mbr == null) {
                log(I18n.t("  MB search: '%s' / '%s'", ti.artist, ti.title));
                mbr = mbLookup(mb, ti.artist, ti.title);
                if (mbr == null && ti.title.contains("(")) {
                    String clean = ti.title.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
                    log(I18n.t("  MB search (nettoyé): '%s'", clean));
                    mbr = mbLookup(mb, ti.artist, clean);
                }
                if (mbr == null && !ti.title.isBlank()) {
                    // Dernier essai avec titre tronqué — seuil plus élevé (90%)
                    // pour éviter d'accepter un enregistrement différent partageant les 2 premiers mots
                    String[] words = ti.title.split("\\s+");
                    if (words.length > 1) {
                        String short1 = words[0] + " " + words[1];
                        TagInfo candidate = mbLookupStrict(mb, ti.artist, short1, 90);
                        if (candidate != null && titlesSimilar(ti.title, candidate.title)) {
                            mbr = candidate;
                            log(I18n.t("  MB search (titre court): '%s'", short1));
                        }
                    }
                }
            }

            if (mbr != null) {
                log(I18n.t("  MB trouvé: %s – %s [%s %s]", mbr.artist, mbr.title, mbr.album, mbr.year));
                // Un seul résumé listant les champs réellement remplis plutôt que 20 fillBlank()
                // muets — sans ça, un fichier qui repassait "✓ complété" ne disait jamais QUOI
                // avait été trouvé, seulement l'enregistrement MB apparié (retour utilisateur).
                List<String> filled = new ArrayList<>();
                if (fillBlank(ti, "album",             mbr.album))            filled.add("album");
                if (fillBlank(ti, "year",              mbr.year))             filled.add("year");
                if (fillBlank(ti, "track",             mbr.track))            filled.add("track");
                if (fillBlank(ti, "trackTotal",        mbr.trackTotal))       filled.add("trackTotal");
                if (fillBlank(ti, "discNo",            mbr.discNo))           filled.add("discNo");
                if (fillBlank(ti, "albumArtist",       mbr.albumArtist))      filled.add("albumArtist");
                if (fillBlank(ti, "albumArtistSort",   mbr.albumArtistSort))  filled.add("albumArtistSort");
                if (fillBlank(ti, "artistSort",        mbr.artistSort))       filled.add("artistSort");
                if (fillBlank(ti, "artistMbid",        mbr.artistMbid))       filled.add("artistMbid");
                if (fillBlank(ti, "releaseMbid",       mbr.releaseMbid))      filled.add("releaseMbid");
                if (fillBlank(ti, "releaseGroupMbid",  mbr.releaseGroupMbid)) filled.add("releaseGroupMbid");
                if (fillBlank(ti, "recordingMbid",     mbr.recordingMbid))    filled.add("recordingMbid");
                if (fillBlank(ti, "work",              mbr.work))             filled.add("work");
                if (fillBlank(ti, "workMbid",          mbr.workMbid))         filled.add("workMbid");
                if (fillBlank(ti, "isrc",              mbr.isrc))             filled.add("isrc");
                if (fillBlank(ti, "language",          mbr.language))        filled.add("language");
                if (fillBlank(ti, "script",            mbr.script))           filled.add("script");
                if (fillBlank(ti, "country",           mbr.country))          filled.add("country");
                if (fillBlank(ti, "releaseType",       mbr.releaseType))      filled.add("releaseType");
                if (fillBlank(ti, "originalYear",      mbr.originalYear))     filled.add("originalYear");
                if (fillBlank(ti, "artists",           mbr.artists))          filled.add("artists");
                if (fillBlank(ti, "artistsSort",       mbr.artistsSort))      filled.add("artistsSort");
                if (!filled.isEmpty()) { log(I18n.t("  ✎ rempli: %s", String.join(", ", filled))); changed = true; }
            } else if (ti.artistMbid.isBlank()) {
                // Fallback minimal : artistMbid pour la pochette
                log(I18n.t("  MB artist search: '%s'", ti.artist));
                String amid = mb.searchArtistMbid(ti.artist);
                if (!amid.isBlank()) {
                    ti.artistMbid = amid;
                    log(I18n.t("  artistMbid←MB: %s", amid));
                    changed = true;
                }
            }
        }

        // ── 2. Genre ──────────────────────────────────────────────────────
        String genreBefore = ti.genre;
        TagEnrichment.enrichGenre(ti, discogs, lastFm, cache);
        if (!ti.genre.equals(genreBefore)) { log(I18n.t("  genre=%s", ti.genre)); changed = true; }

        // ── 2b. Opus/Catalogue/Mouvement/Œuvre globale (classique) ─────────
        // detectClassical() (remplit ti.isClassical, la case "Musique classique" du panneau) était
        // absent de ce pipeline — TaggingWorker/BatchProcessor/MatchDialog/App.java l'appellent via
        // LocalCorrector.correct(), mais InfoCompleterWorker n'a jamais eu de LocalCorrector du
        // tout. enrichClassicalWork() (juste en dessous) contourne déjà le problème pour les DONNÉES
        // (basé sur la présence d'un Work MB, pas sur isClassical), mais la case à cocher elle-même
        // pouvait rester décochée à tort si aucun Work MB n'était trouvé.
        String classicalBefore = ti.isClassical + "|" + ti.opus + "|" + ti.classicalCatalog + "|" + ti.movementNo + "|" + ti.overallWork;
        corrector.detectClassical(ti);
        TagEnrichment.enrichClassicalWork(ti, mb, cache);
        String classicalAfter  = ti.isClassical + "|" + ti.opus + "|" + ti.classicalCatalog + "|" + ti.movementNo + "|" + ti.overallWork;
        if (!classicalAfter.equals(classicalBefore)) { log(I18n.t("  œuvre=%s opus=%s", ti.overallWork, ti.opus)); changed = true; }

        // ── 3. Mood ───────────────────────────────────────────────────────
        if (ti.mood.isBlank()) {
            try { lastFm.enrichMood(ti, cache); if (!ti.mood.isBlank()) { log(I18n.t("  mood←lastfm=%s", ti.mood)); changed = true; } } catch (Exception ignored) {}
        }

        // ── 3b. Translittération artiste (si nom non-Latin et option activée) ───
        String artistBeforeTranslit = ti.artist;
        TagEnrichment.translateArtist(ti, mb, aliasCache);
        if (!ti.artist.equals(artistBeforeTranslit)) {
            log(I18n.t("  translit: %s → %s", artistBeforeTranslit, ti.artist));
            changed = true;
        }

        // ── 4. BPM ────────────────────────────────────────────────────────
        if (ti.bpm.isBlank() && bpmEnabled) {
            int bpm = bpmDet.detect(fichier.getAbsolutePath());
            if (bpm > 0) { ti.bpm = String.valueOf(bpm); log(I18n.t("  bpm=%s", bpm)); changed = true; }
        }

        // ── 5. Paroles ─────────────────────────────────────────────────────
        if (ti.lyrics.isBlank()) {
            try {
                String before = ti.lyrics;
                lyrics.enrich(ti);
                if (!ti.lyrics.equals(before)) { log(I18n.t("  lyrics trouvées")); changed = true; }
            } catch (Exception ignored) {}
        }

        // ── 6. Pochette : vérifier seulement si une pochette est déjà embarquée (lecture locale,
        // pas de réseau) — la résolution réelle (CAA/local/fanart) est différée à
        // l'Enregistrement (TagEnrichment.saveEntry(), via SaveWorker), façon Picard : elle
        // télécharge dans un fichier temporaire qui ne survivrait pas à un Enregistrer différé
        // (potentiellement des heures plus tard, voire une autre session). `coverMissing` sert
        // seulement à décider si ce fichier mérite d'être proposé à l'enregistrement même quand
        // aucun champ texte n'a changé (avant, une pochette trouvée suffisait à elle seule à
        // déclencher l'écriture).
        boolean coverMissing = !hasCoverInFile(fichier);

        // ── 7. Script tagger utilisateur (avant le test de changement : un script
        // activé est une modification intentionnelle même si non détectable par diff) ──
        if (taggerScript.apply(ti)) changed = true;

        // ── 8. Plus d'écriture ici — façon Picard, l'identification (et cette passe de
        // complétion, qui en est une variante) ne touche jamais le disque. Un fichier déjà
        // TAGGED redevient IDENTIFIED (à nouveau "non enregistré") si de nouvelles informations
        // ont été trouvées, jusqu'à ce qu'un futur "Enregistrer tout" les écrive — cache,
        // renommage et soumission MB attendent tous TagEnrichment.saveEntry(), appelé plus tard
        // par SaveWorker.
        if (changed || coverMissing) {
            final TagInfo finalTi = ti;
            // Muter entry SUR l'EDT, pas ici : ce FileEntry est aussi lu par le TableRowSorter
            // en direct depuis l'EDT, et une mutation concurrente pendant un tri casse le
            // contrat de Comparator (déjà vu 697× en 3 jours dans TaggingWorker — même défaut).
            SwingUtilities.invokeLater(() -> {
                entry.result  = finalTi;
                entry.status  = FileEntry.Status.IDENTIFIED;
                entry.message = "";
            });
            // Distingue le cas où RIEN de textuel n'a été trouvé mais coverMissing a quand même
            // déclenché le passage en IDENTIFIED — "✓ complété" y était trompeur (rien n'a été
            // rempli, voir retour utilisateur), le fichier attend juste une pochette potentielle
            // à l'Enregistrement (voir commentaire de coverMissing ci-dessus).
            log(changed
                ? I18n.t("  ✓ complété, en attente d'enregistrement")
                : I18n.t("  ○ pochette manquante (recherchée à l'enregistrement) — rien d'autre à compléter"));
        } else {
            log(I18n.t("  — déjà complet, rien à faire"));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Cherche dans MB et retourne le meilleur résultat si score ≥ 70, sinon null. */
    private TagInfo mbLookup(MusicBrainzClient mb, String artist, String title) {
        return mbLookupStrict(mb, artist, title, 70);
    }

    /** Cherche dans MB et retourne le meilleur résultat si score ≥ minScore, sinon null. */
    private TagInfo mbLookupStrict(MusicBrainzClient mb, String artist, String title, int minScore) {
        try {
            List<TagInfo> results = mb.searchRecording(artist, title);
            if (!results.isEmpty() && results.get(0).score >= minScore)
                return results.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    /** Retourne true si deux titres partagent ≥ 60% de leurs mots (après normalisation). */
    private static boolean titlesSimilar(String a, String b) {
        if (a == null || b == null) return false;
        String[] wa = a.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        String[] wb = b.toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+");
        if (wa.length == 0 || wb.length == 0) return false;
        java.util.Set<String> setB = new java.util.HashSet<>(java.util.Arrays.asList(wb));
        int shared = 0;
        for (String w : wa) if (setB.contains(w)) shared++;
        return shared >= Math.max(1, (int)(wa.length * 0.6));
    }

    /** Remplit un champ vide. Retourne true si modifié. */
    private boolean fillBlank(TagInfo ti, String field, String value) {
        if (value == null || value.isBlank()) return false;
        try {
            var f = TagInfo.class.getField(field);
            String cur = (String) f.get(ti);
            if (cur == null || cur.isBlank()) { f.set(ti, value); return true; }
        } catch (Exception ignored) {}
        return false;
    }

    /** Lit les tags essentiels depuis le fichier (artist, title, album, year, mbids…). */
    private TagInfo readTagsFromFile(File f) {
        // Opus/AAC/WV/APE : jaudiotagger ne sait pas les lire du tout (voir FfmpegTagIO).
        if (com.opentagger.FfmpegTagIO.handles(f)) return com.opentagger.FfmpegTagIO.read(f);
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            if (tag == null) return null;
            TagInfo ti = new TagInfo();
            // isGenericIdentityValue() : ne jamais faire confiance à un artiste/titre/album déjà
            // cassé sur le fichier ("1", "Unknown Artist"...) — sans ce garde, une valeur poubelle
            // déjà présente se recopiait telle quelle à chaque complétion, indéfiniment (bug réel
            // trouvé le 2026-08-13 : TPE1=ARTIST="1" sur un fichier par ailleurs bien identifié).
            String rawArtist      = tag.getFirst(FieldKey.ARTIST);
            String rawTitle       = tag.getFirst(FieldKey.TITLE);
            String rawAlbum       = tag.getFirst(FieldKey.ALBUM);
            String rawAlbumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST);
            ti.artist          = TagInfo.isGenericIdentityValue(rawArtist)      ? "" : rawArtist;
            ti.title           = TagInfo.isGenericIdentityValue(rawTitle)       ? "" : rawTitle;
            ti.album           = TagInfo.isGenericIdentityValue(rawAlbum)       ? "" : rawAlbum;
            ti.albumArtist     = TagInfo.isGenericIdentityValue(rawAlbumArtist) ? "" : rawAlbumArtist;
            ti.year            = tag.getFirst(FieldKey.YEAR);
            ti.track           = tag.getFirst(FieldKey.TRACK);
            ti.genre           = tag.getFirst(FieldKey.GENRE);
            ti.bpm             = tag.getFirst(FieldKey.BPM);
            ti.mood            = tag.getFirst(FieldKey.MOOD);
            ti.lyrics          = tag.getFirst(FieldKey.LYRICS);
            ti.trackTotal       = tag.getFirst(FieldKey.TRACK_TOTAL);
            ti.discNo           = tag.getFirst(FieldKey.DISC_NO);
            ti.discTotal        = tag.getFirst(FieldKey.DISC_TOTAL);
            ti.artistSort       = tag.getFirst(FieldKey.ARTIST_SORT);
            ti.albumArtistSort  = tag.getFirst(FieldKey.ALBUM_ARTIST_SORT);
            ti.isrc             = tag.getFirst(FieldKey.ISRC);
            ti.language         = tag.getFirst(FieldKey.LANGUAGE);
            ti.script           = tag.getFirst(FieldKey.SCRIPT);
            ti.artistMbid       = tag.getFirst(FieldKey.MUSICBRAINZ_ARTISTID);
            ti.releaseMbid      = tag.getFirst(FieldKey.MUSICBRAINZ_RELEASEID);
            ti.recordingMbid    = tag.getFirst(FieldKey.MUSICBRAINZ_TRACK_ID);
            ti.releaseGroupMbid = tag.getFirst(FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID);
            // Nettoyer les nulls
            var cls = TagInfo.class;
            for (var fld : cls.getFields()) {
                if (fld.getType() == String.class && fld.get(ti) == null)
                    fld.set(ti, "");
            }
            return ti;
        } catch (Exception e) { return null; }
    }

    /** Vérifie si le fichier a déjà une pochette embarquée. */
    private boolean hasCoverInFile(File f) {
        try {
            AudioFile af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            return tag != null && tag.getFirstArtwork() != null;
        } catch (Exception e) { return false; }
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
    }

    // Même raison que TaggingWorker.CURRENT_FILE (préfixe de traçabilité par fichier malgré
    // plusieurs threads parallèles partageant ce même log() static) — ajouté ici aussi le
    // 2026-08-12 en repérant un cas suspect ("Or Songwriting Process...mp3", un titre de podcast,
    // suivi d'une ligne "MB trouvé: James Blunt..." impossible à rattacher avec certitude à CE
    // fichier précis sans préfixe, à cause de l'entrelacement des threads).
    private static final ThreadLocal<String> CURRENT_FILE = new ThreadLocal<>();

    private static void log(String msg) {
        String ctx = CURRENT_FILE.get();
        String prefix = ctx != null ? "[" + ctx + "] " : "";
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + prefix + msg);
        System.out.flush();
    }
}
