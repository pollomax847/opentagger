package com.opentagger.ui;

import com.opentagger.CaaClient;
import com.opentagger.DiscogsClient;
import com.opentagger.FanArtClient;
import com.opentagger.LastFmClient;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.MusicBrainzClient.ReleaseTracklist;
import com.opentagger.MusicBrainzClient.ReleaseTrack;
import com.opentagger.MusicBrainzOAuth;
import com.opentagger.TagEnrichment;
import com.opentagger.TaggerScript;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import javax.swing.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Complète les albums partiellement tagués.
 *
 * Algorithme :
 * 1. Groupe les fichiers TAGGED par releaseMbid.
 * 2. Pour chaque release, récupère la tracklist complète sur MusicBrainz.
 * 3. Identifie les pistes absentes du groupe.
 * 4. Cherche parmi les fichiers SKIPPED/PENDING ceux dont le titre correspond.
 * 5. Re-tague les fichiers trouvés avec les métadonnées exactes de la piste.
 *
 * Parallélisé par release (un thread-pool, même clé de config "batch.threads" que
 * TaggingWorker/BatchProcessor) — avant ça, cette passe traitait un fichier à la fois avec un
 * lookup MB + enrichissement genre/pochette + empreinte AcoustID + écriture (chaîne de repli M4A
 * comprise) + soumission MB + renommage par fichier, entièrement séquentiel ; sur une grosse
 * bibliothèque avec beaucoup d'albums incomplets détectés, ça pouvait bloquer la passe pendant
 * des heures (constaté en direct : ~48 min sans terminer, bloquant toute nouvelle session de
 * taguage via la garde mutuelle-exclusion de MainFrame). MusicBrainzClient et LastFmClient tiennent
 * un état mutable entre appels (déjà documenté pour TaggingWorker) — instance fraîche par tâche ;
 * DiscogsClient/CaaClient/FanArtClient/TaggerScript/MusicBrainzOAuth sont sans état, partagés tels
 * quels. candidateIndex est mutable et partagé entre toutes les releases (un même fichier candidat
 * ne doit être réclamé que par UNE piste) — "trouver + retirer" est donc rendu atomique via
 * synchronized sur la map, pour éviter que deux releases traitées en parallèle ne réclament le même
 * fichier.
 */
public class AlbumCompletionWorker extends SwingWorker<Void, String> {

    private final FileTableModel  tableModel;
    private final Consumer<String>  statusCallback;
    private final Runnable          doneCallback;

    // Genre (Discogs/Last.fm) et pochette (CAA/local/FanArt) — la tracklist MB ne fournit ni
    // l'un ni l'autre, donc "ancre MB fiable" ne dispensait pas de ces enrichissements ; jusqu'ici
    // absents, les fichiers complétés par ce worker sortaient sans genre ni pochette du tout,
    // contrairement aux fichiers tagués par TaggingWorker/InfoCompleterWorker/MatchDialog.
    private final DiscogsClient  discogs = new DiscogsClient();
    private final CaaClient      caa     = new CaaClient();
    private final FanArtClient   fanArt  = new FanArtClient();
    private final TaggerScript   taggerScript = new TaggerScript();
    private final MusicBrainzOAuth mbOauth  = new MusicBrainzOAuth();

    private final AtomicInteger matched  = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final Map<String, String> aliasCache = new ConcurrentHashMap<>();

    public AlbumCompletionWorker(FileTableModel tableModel,
                                 Consumer<String> statusCallback, Runnable doneCallback) {
        this.tableModel     = tableModel;
        this.statusCallback = statusCallback;
        this.doneCallback   = doneCallback;
    }

    @Override
    protected Void doInBackground() throws Exception {
        // ── 1. Collecter les releases depuis les fichiers TAGGED ─────────────
        // releaseMbid → {recordingMbid → FileEntry}
        Map<String, Map<String, FileEntry>> releaseGroups = new LinkedHashMap<>();

        List<FileEntry> candidates = new ArrayList<>(); // SKIPPED/PENDING à compléter

        // Ouvrir le cache ici pour vérifier la source d'identification des ancres
        MetadataCache cacheForSrc = new MetadataCache();
        try {
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                FileEntry e = tableModel.get(i);
                if (e.status == FileEntry.Status.TAGGED) {
                    // N'utiliser comme ancre de release que les fichiers identifiés par source FIABLE.
                    // SOURCE_TEXT = recherche texte = releaseMbid potentiellement faux → faux positifs.
                    String path   = (e.currentPath != null ? e.currentPath : e.file.toPath()).toString();
                    String source = cacheForSrc.getFileTaggingSource(path);
                    if (MetadataCache.SOURCE_TEXT.equals(source) || source == null || source.isBlank()) continue;

                    TagInfo ref    = e.result != null ? e.result : e.current;
                    String rMbid   = ref != null ? ref.releaseMbid   : "";
                    String recMbid = ref != null ? ref.recordingMbid : "";
                    if (!rMbid.isBlank() && !recMbid.isBlank()) {
                        releaseGroups
                            .computeIfAbsent(rMbid, k -> new LinkedHashMap<>())
                            .put(recMbid, e);
                    }
                } else if (e.status == FileEntry.Status.SKIPPED || e.status == FileEntry.Status.PENDING) {
                    candidates.add(e);
                }
            }
        } finally {
            cacheForSrc.close();
        }

        if (releaseGroups.isEmpty()) {
            publish("Aucun album identifié parmi les fichiers tagués.");
            return null;
        }
        if (candidates.isEmpty()) {
            publish("Aucun fichier SKIPPED/PENDING à compléter.");
            return null;
        }

        publish(String.format("Analyse de %d album(s) — %d fichier(s) à récupérer possible(s)…",
                releaseGroups.size(), candidates.size()));

        // Index titre normalisé → FileEntry pour les candidats (on lit le titre intégré dans le
        // fichier). Partagé entre toutes les tâches parallèles ci-dessous — accès protégé par
        // synchronized (voir processRelease).
        Map<String, FileEntry> candidateIndex = new LinkedHashMap<>();
        for (FileEntry e : candidates) {
            if (isCancelled()) break;
            java.nio.file.Path p = e.currentPath != null ? e.currentPath : e.file.toPath();
            if (!java.nio.file.Files.exists(p)) continue; // fichier introuvable, ignorer
            String t = readEmbeddedTitle(p.toFile());
            if (t.isBlank()) t = filenameTitle(p.getFileName().toString());
            String key = normalize(t);
            if (!key.isBlank()) candidateIndex.put(key, e);
        }

        // ── 2. Pour chaque release (en parallèle), récupérer la tracklist et compléter
        MetadataCache cache = new MetadataCache();
        try {
            int threads = Math.max(1, com.opentagger.Config.get().num("batch.threads", 3));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (Map.Entry<String, Map<String, FileEntry>> group : releaseGroups.entrySet()) {
                if (isCancelled()) break;
                String relMbid = group.getKey();
                Map<String, FileEntry> found = group.getValue();
                futures.add(pool.submit(() -> processRelease(
                        relMbid, found, cache, candidateIndex, new MusicBrainzClient(), new LastFmClient())));
            }

            pool.shutdown();
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            cache.close();
        }
        return null;
    }

    /** Traite une release entière : tracklist + toutes ses pistes manquantes. Appelé en parallèle,
     *  un thread par release, depuis le pool créé dans doInBackground(). */
    private void processRelease(String relMbid, Map<String, FileEntry> found, MetadataCache cache,
                                 Map<String, FileEntry> candidateIndex,
                                 MusicBrainzClient mb, LastFmClient lastFm) {
        if (isCancelled()) return;

        ReleaseTracklist tl = fetchTracklist(cache, relMbid, mb);
        if (tl == null) return;
        releases.incrementAndGet();

        publish(String.format("Album : %s (%d piste(s) trouvée(s) / %d au total)",
                tl.album(), found.size(), tl.tracks().size()));

        for (ReleaseTrack track : tl.tracks()) {
            if (isCancelled()) break;
            if (found.containsKey(track.recordingMbid())) continue; // déjà là

            // Chercher dans les candidats par titre, et le réclamer immédiatement : "trouver +
            // retirer" doit être atomique, sinon deux releases traitées en parallèle peuvent
            // réclamer le même fichier candidat.
            FileEntry hit;
            synchronized (candidateIndex) {
                hit = findCandidate(candidateIndex, track.title());
                if (hit == null) continue;
                candidateIndex.values().remove(hit);
            }

            // Construire le TagInfo complet
            TagInfo ti = new TagInfo();
            ti.title           = track.title();
            ti.artist          = track.artist();
            ti.albumArtist     = tl.albumArtist();
            ti.albumArtistSort = tl.albumArtistSort();
            ti.album           = tl.album();
            ti.year            = tl.year();
            ti.track           = track.trackNo() > 0 ? String.valueOf(track.trackNo()) : "";
            ti.trackTotal      = track.trackTotal() > 0 ? String.valueOf(track.trackTotal()) : "";
            ti.discNo          = track.disc()  > 0 ? String.valueOf(track.disc())  : "";
            ti.releaseMbid     = tl.releaseMbid();
            ti.releaseGroupMbid= tl.releaseGroupMbid();
            ti.recordingMbid   = track.recordingMbid();
            ti.artistMbid      = track.artistMbid();
            ti.isCompilation   = tl.isCompilation() ? "1" : "";
            ti.score           = 100;

            // Translittération artiste (si nom non-Latin et option activée) — même logique
            // partagée que TaggingWorker.
            TagEnrichment.translateArtist(ti, mb, aliasCache);

            java.nio.file.Path writePath = hit.currentPath != null ? hit.currentPath : hit.file.toPath();

            // Script tagger utilisateur — même logique partagée que TaggingWorker/BatchProcessor/
            // App/InfoCompleterWorker.
            taggerScript.apply(ti);

            // Genre (Discogs/Last.fm) et pochette (CAA/local/FanArt) — la tracklist MB
            // n'en fournit ni l'un ni l'autre, il faut les chercher comme les autres pipelines.
            TagEnrichment.enrichGenre(ti, discogs, lastFm);
            java.nio.file.Path cover = TagEnrichment.resolveCover(ti, writePath.toFile(), caa, fanArt);

            // Empreinte AcoustID : calculée systématiquement après toute identification
            // réussie (TaggingWorker/BatchProcessor/App/MatchDialog le font déjà, comme
            // Picard) — un fichier retrouvé via la tracklist d'album est identifié tout
            // aussi sûrement (score 100 ci-dessus).
            if (com.opentagger.Config.get().saveAcoustidFingerprints() && ti.acoustidFingerprint.isBlank()
                    && com.opentagger.FpcalcInstaller.isAvailable()) {
                try {
                    ti.acoustidFingerprint = com.opentagger.Fingerprinter.compute(writePath.toFile()).fingerprint();
                } catch (Exception ignored) {}
            }

            // Écrire les tags
            try {
                new TagWriter().write(writePath.toFile(), ti, cover);
                cache.recordFileTagging(writePath.toString(), track.recordingMbid());
                cache.saveTaggingHistory(ti);
                // Soumission MB (tags genre/mood + rating, si OAuth configuré) — même
                // logique partagée que TaggingWorker/InfoCompleterWorker.
                TagEnrichment.submitToMusicBrainz(mbOauth, ti, this::publish);

                // Renommage automatique — sans ça, les fichiers tagués par "Compléter les
                // albums" étaient les seuls à ne jamais passer par FileRenamer même quand
                // "renommage auto" est activé (même défaut que dans albumFirstPass).
                java.nio.file.Path finalWritePath = writePath;
                if (com.opentagger.Config.get().autoRenameEnabled()) {
                    try {
                        int maskIdx = com.opentagger.Config.get().defaultRenameMask();
                        java.nio.file.Path oldParent = writePath.getParent();
                        String libRoot = com.opentagger.Config.get().libraryRoot();
                        java.nio.file.Path root =
                            (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                                ? java.nio.file.Paths.get(libRoot)
                                : (hit.scanRoot != null ? hit.scanRoot : oldParent);
                        java.nio.file.Path newPath = new com.opentagger.FileRenamer()
                                .rename(writePath, ti, maskIdx, root);
                        if (newPath != null) {
                            finalWritePath = newPath;
                            if (com.opentagger.Config.get().deleteEmptyDirsAfterRename()) {
                                com.opentagger.FileRenamer.deleteEmptyAncestors(oldParent, root);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Muter hit/tableModel SUR l'EDT : ce FileEntry est aussi comparé en
                // direct par le TableRowSorter depuis l'EDT, et une mutation concurrente
                // pendant un tri casse le contrat de Comparator (déjà vu 697× en 3 jours).
                final FileEntry hitFinal = hit;
                final java.nio.file.Path finalPathForEdt = finalWritePath;
                SwingUtilities.invokeLater(() -> {
                    hitFinal.result  = ti;
                    hitFinal.status  = FileEntry.Status.TAGGED;
                    hitFinal.message = "";
                    hitFinal.currentPath = finalPathForEdt;
                    tableModel.update(hitFinal);
                });

                publish(String.format("  ✓ %s → piste %d \"%s\"",
                        hit.filename(), track.trackNo(), track.title()));
                matched.incrementAndGet();
            } catch (Exception ex) {
                publish("  ✗ " + hit.filename() + " : " + ex.getMessage());
            }
        }
    }

    @Override
    protected void process(List<String> chunks) {
        if (!chunks.isEmpty()) statusCallback.accept(chunks.get(chunks.size() - 1));
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            statusCallback.accept(String.format(
                "Complétion albums — %d album(s) analysé(s), %d piste(s) récupérée(s)",
                releases.get(), matched.get()));
        }
        if (doneCallback != null) doneCallback.run();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReleaseTracklist fetchTracklist(MetadataCache cache, String relMbid, MusicBrainzClient mb) {
        String cacheKey = "release:" + relMbid;
        try {
            String cached = cache.getLookup(cacheKey);
            if (cached != null) {
                ReleaseTracklist tl = mb.parseReleaseFromCache(cached);
                if (tl != null) return tl;
            }
            ReleaseTracklist tl = mb.lookupRelease(relMbid);
            if (tl != null) {
                String raw = mb.lastRawJson();
                if (!raw.isBlank()) cache.putLookup(cacheKey, raw);
            }
            return tl;
        } catch (Exception e) {
            publish("  ⚠ Impossible de récupérer la tracklist : " + e.getMessage());
            return null;
        }
    }

    /** Doit être appelé avec le verrou sur candidateIndex déjà tenu par l'appelant. */
    private FileEntry findCandidate(Map<String, FileEntry> index, String trackTitle) {
        String norm = normalize(trackTitle);
        if (norm.isBlank()) return null;

        // 1. Correspondance exacte
        FileEntry hit = index.get(norm);
        if (hit != null) return hit;

        // 2. Inclusion (le candidat contient le titre de la piste ou l'inverse)
        // Garde de longueur minimale : évite les faux positifs avec des mots très courts
        for (Map.Entry<String, FileEntry> e : index.entrySet()) {
            String k = e.getKey();
            if ((k.contains(norm) && norm.length() >= 12) ||
                (norm.contains(k) && k.length() >= 12)) return e.getValue();
        }

        // 3. Similarité par mots partagés (≥ 70%)
        String[] trackWords = norm.split("\\s+");
        if (trackWords.length < 2) return null;
        int best = 0;
        FileEntry bestEntry = null;
        for (Map.Entry<String, FileEntry> e : index.entrySet()) {
            int shared = countSharedWords(trackWords, e.getKey().split("\\s+"));
            if (shared > best) { best = shared; bestEntry = e.getValue(); }
        }
        if (bestEntry != null && best >= Math.max(1, (int)(trackWords.length * 0.7))) {
            return bestEntry;
        }
        return null;
    }

    private int countSharedWords(String[] a, String[] b) {
        Set<String> setB = new HashSet<>(Arrays.asList(b));
        int count = 0;
        for (String w : a) if (setB.contains(w)) count++;
        return count;
    }

    static String normalize(String s) {
        if (s == null) return "";
        s = s.toLowerCase();
        // Retirer "(feat. X)", "[feat. X]", "(remix)", etc.
        s = s.replaceAll("\\s*\\(feat\\.?.*?\\)", "");
        s = s.replaceAll("\\s*\\[feat\\.?.*?\\]", "");
        s = s.replaceAll("\\s*\\(.*?(?:remix|edit|version|mix|radio|extended|live|karaoke).*?\\)", "");
        // Retirer caractères spéciaux
        s = s.replaceAll("[^a-z0-9 ]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private String readEmbeddedTitle(File f) {
        try {
            var af  = AudioFileIO.read(f);
            Tag tag = af.getTag();
            if (tag == null) return "";
            String v = tag.getFirst(FieldKey.TITLE);
            return v != null ? v.trim() : "";
        } catch (Exception e) { return ""; }
    }

    private String filenameTitle(String filename) {
        // Retire l'extension
        int dot = filename.lastIndexOf('.');
        String name = dot > 0 ? filename.substring(0, dot) : filename;
        // Retire le numéro de piste en tête : "03 - " ou "03."
        name = name.replaceAll("^\\d{1,3}[\\s.\\-_]+", "");
        // Retire "Artiste - " en tête si présent
        name = name.replaceAll("^[^-]+ - ", "");
        return name.trim();
    }
}
