package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * SwingWorker qui traite les fichiers sélectionnés en arrière-plan
 * et notifie l'UI après chaque fichier via publish/process.
 *
 * Pipeline complet :
 *  1. Cache SQLite   → résultat instantané si déjà connu
 *  2. AcoustID       → fingerprint (optionnel)
 *  3. MusicBrainz    → recherche par texte
 *  4. Shazam         → fallback si non identifié par AcoustID/MB (nécessite clé RapidAPI)
 *  5. LocalCorrector → corrections scripts Jaikoz (5 scripts)
 *  6. Discogs        → genres
 *  7. Last.fm        → genres (fallback)
 *  8. BPM            → détection locale (ffmpeg)
 *  9. Essentia       → mood/key (optionnel, si binaire installé)
 * 10. FanArt.tv      → pochette
 * 11. TagWriter      → écriture tags
 * 11. FileRenamer    → renommage (optionnel)
 */
public class TaggingWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>     entries;
    private final int                 maskIndex;
    private final boolean             useAcoustId;
    private final Consumer<String>    onProgress;
    private final Consumer<FileEntry> onUpdate;

    private final MusicBrainzClient  mb        = new MusicBrainzClient();
    private final AcoustIdClient     acoustId  = new AcoustIdClient();
    private final DiscogsClient      discogs   = new DiscogsClient();
    private final LastFmClient       lastFm    = new LastFmClient();
    private final FanArtClient       fanArt    = new FanArtClient();
    private final LocalCorrector     corrector = new LocalCorrector();
    private final TagWriter          writer    = new TagWriter();
    private final FileRenamer        renamer   = new FileRenamer();
    private final MetadataCache      cache     = new MetadataCache();
    private final BpmDetector        bpmDet    = new BpmDetector();
    private final EssentiaClient     essentia  = new EssentiaClient();
    private final LyricsClient       lyrics    = new LyricsClient();

    private final boolean bpmEnabled      = BpmDetector.isAvailable();
    private final boolean essentiaEnabled = EssentiaClient.isOnPath();

    public TaggingWorker(List<FileEntry> entries, boolean useAcoustId, int maskIndex,
                         Consumer<String> onProgress, Consumer<FileEntry> onUpdate) {
        this.entries     = entries;
        this.useAcoustId = useAcoustId;
        this.maskIndex   = maskIndex;
        this.onProgress  = onProgress;
        this.onUpdate    = onUpdate;
    }

    @Override
    protected Void doInBackground() {
        int total = entries.size();
        int done  = 0;

        for (FileEntry entry : entries) {
            if (isCancelled()) break;

            entry.status = FileEntry.Status.PROCESSING;
            publish(entry);
            onProgress.accept(String.format("Traitement : %s (%d/%d)", entry.filename(), done + 1, total));

            processEntry(entry);

            setProgress((++done * 100) / total);
            publish(entry);

            // Rate-limit MusicBrainz (1 req/s max)
            if (done < total) sleep(1100);
        }

        cache.purgeExpired();
        cache.close();
        return null;
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        for (FileEntry e : chunks) onUpdate.accept(e);
    }

    private void processEntry(FileEntry entry) {
        try {
            // Toujours travailler sur le chemin actuel (peut avoir changé après un rename)
            File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
            List<TagInfo> results = findTags(fichier);

            int seuil = Config.get().minScoreAuto();

            if (results.isEmpty() || results.get(0).score < seuil) {
                // ── Chaîne Shazam → AudD ─────────────────────────────────
                List<TagInfo> chain = new AudioRecognitionChain().recognize(fichier);
                if (!chain.isEmpty()) results = chain;
            }

            if (results.isEmpty()) {
                entry.status  = FileEntry.Status.SKIPPED;
                entry.message = "Non identifié";
                return;
            }

            TagInfo best = results.get(0);

            if (best.score < seuil) {
                entry.candidates = results; // conservés pour sélection manuelle
                entry.status  = FileEntry.Status.SKIPPED;
                entry.message = "Score " + best.score + "% < " + seuil + "% — " + results.size() + " candidat(s)";
                return;
            }

            // ── Corrections locales (5 scripts Jaikoz) ────────────────────
            corrector.correct(best, entry.file.toPath());

            // ── Genres ────────────────────────────────────────────────────
            if (best.genre.isBlank()) { try { discogs.enrichGenres(best); } catch (Exception ignored) {} }
            if (best.genre.isBlank()) { try { lastFm.enrichGenres(best);  } catch (Exception ignored) {} }

            // ── BPM (ffmpeg — si dispo) ───────────────────────────────────
            if (bpmEnabled && best.bpm.isBlank()) {
                int bpm = bpmDet.detect(fichier.getAbsolutePath());
                if (bpm > 0) best.bpm = String.valueOf(bpm);
            }

            // ── Essentia — mood + key ─────────────────────────────────────
            if (essentiaEnabled) {
                essentia.analyze(fichier.getAbsolutePath(), best);
            }

            // ── Paroles ───────────────────────────────────────────────────
            try { lyrics.enrich(best); } catch (Exception ignored) {}

            // ── Pochette FanArt.tv ────────────────────────────────────────
            Path cover = null;
            if (!best.artistMbid.isBlank()) {
                try { cover = fanArt.downloadCover(best); } catch (Exception ignored) {}
            }

            // ── Écriture des tags ─────────────────────────────────────────
            writer.write(fichier, best, cover);

            // ── Renommage optionnel ───────────────────────────────────────
            if (maskIndex >= 0) {
                try {
                    Path curPath = fichier.toPath();
                    Path root    = entry.scanRoot != null ? entry.scanRoot : curPath.getParent();
                    Path newPath = renamer.rename(curPath, best, maskIndex, root);
                    if (newPath != null) {
                        Path oldParent    = fichier.toPath().getParent();
                        entry.currentPath = newPath;
                        FileRenamer.deleteEmptyAncestors(oldParent, root);
                        entry.message = "→ " + newPath.getFileName();
                    }
                } catch (Exception ignored) {}
            }

            entry.result = best;
            entry.status = FileEntry.Status.TAGGED;

            // ── Sauvegarder dans l'historique personnel (Derby DB équivalent) ─
            if (!best.recordingMbid.isBlank()) cache.saveTaggingHistory(best);
            cache.recordFileTagging(fichier.getAbsolutePath(), best.recordingMbid);

        } catch (Exception ex) {
            entry.status  = FileEntry.Status.ERROR;
            entry.message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        }
    }

    // ── Résolution des tags — avec cache SQLite ───────────────────────────────

    private List<TagInfo> findTags(File fichier) throws Exception {
        // 0. Historique personnel — ce fichier a-t-il déjà été tagué par OpenTagger ?
        //    Retour instantané sans réseau : c'est le cœur de la "mémoire" personnelle.
        String knownMbid = cache.getFileTagging(fichier.getAbsolutePath());
        if (knownMbid != null) {
            TagInfo hist = cache.getTaggingHistory(knownMbid);
            if (hist != null) { hist.score = 100; return List.of(hist); }
        }

        // 1. MB Recording ID déjà présent → lookup direct (rapide + précis)
        String existingMbid = readTag(fichier, FieldKey.MUSICBRAINZ_TRACK_ID);
        if (!existingMbid.isBlank()) {
            // Vérifier le cache d'abord
            String cached = cache.getLookup(existingMbid);
            if (cached != null) {
                TagInfo t = mb.parseFromCacheLookup(cached);
                if (t != null) { t.score = 100; return List.of(t); }
            }
            // Sinon lookup réseau
            TagInfo t = mb.lookupRecording(existingMbid);
            if (t != null) {
                cache.putLookup(existingMbid, mb.lastRawJson());
                t.score = 100;
                return List.of(t);
            }
        }

        // 2. AcoustID (fingerprint)
        if (useAcoustId) {
            List<TagInfo> r = acoustId.identify(fichier);
            if (!r.isEmpty()) return r;
        }

        // 3. Fallback sur les tags texte existants
        String artist = readTag(fichier, FieldKey.ARTIST);
        String title  = readTag(fichier, FieldKey.TITLE);
        if (artist.isBlank() && title.isBlank()) return List.of();

        // 4. Cache SQLite
        String hash   = MetadataCache.queryHash(artist, title);
        String cached = cache.getRecordingSearch(hash);
        if (cached != null) {
            return mb.parseFromCache(cached);
        }

        // 5. MusicBrainz — réseau
        List<TagInfo> results = mb.searchRecording(artist, title);
        if (!results.isEmpty()) {
            cache.putRecordingSearch(hash, mb.lastRawJson());
        }
        return results;
    }

    private String readTag(File f, FieldKey key) {
        try {
            var af = AudioFileIO.read(f);
            Tag tag = af.getTag();
            return tag != null ? tag.getFirst(key) : "";
        } catch (Exception e) { return ""; }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
