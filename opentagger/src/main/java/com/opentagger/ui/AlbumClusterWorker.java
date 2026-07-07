package com.opentagger.ui;

import com.opentagger.AudioDuration;
import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.ReplayGainAnalyzer;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Regroupe les pistes d'un même album (par releaseMbid) pour corriger les numéros de piste/disque
 * et calculer un ReplayGain d'ALBUM cohérent — extrait de l'ancien {@code TaggingWorker.
 * clusterAlbums()} (2026-07-07), qui tournait automatiquement à la fin de CHAQUE passe de taguage,
 * mais seulement sur les fichiers TAGUÉS PAR CETTE PASSE précise (le paramètre {@code entries} de
 * {@code TaggingWorker.doInBackground()}, pas la bibliothèque entière).
 *
 * Problème réel signalé par l'utilisateur ("ça crée beaucoup de conflits") : sur une bibliothèque
 * taguée en plusieurs passes successives (fichiers ajoutés/découverts au fil du temps, comme chez
 * cet utilisateur avec un scan de plusieurs jours sur 2 To), les pistes d'un même album se
 * répartissent souvent entre plusieurs passes distinctes. Chaque passe reclusterait/recalculait le
 * ReplayGain sur un SOUS-ENSEMBLE incomplet de l'album (seulement les pistes tagués PAR CETTE
 * passe-là), réécrivant les mêmes fichiers à chaque nouvelle passe avec des valeurs différentes au
 * fur et à mesure que d'autres pistes du même album se tagueaient plus tard — d'où les réécritures
 * répétées vues dans les logs.
 *
 * Devenu une action MANUELLE séparée (même esprit que AlbumCompletionWorker) qui regroupe TOUTE la
 * bibliothèque déjà taguée en une seule passe — voir {@code MainFrame.clusterAlbums()} pour la
 * garde qui bloque le lancement tant que des fichiers PENDING/PROCESSING restent dans la table.
 *
 * Séquentiel (pas de pool de threads) : contrairement à AlbumCompletionWorker (beaucoup de releases
 * à récupérer sur MB), cette passe ne traite QUE des fichiers déjà TAGUÉS — le nombre de releases à
 * effectivement re-consulter sur MusicBrainz (cache-miss) est généralement faible en pratique, et la
 * complexité d'un pool de threads (candidateIndex partagé, etc.) n'apporte pas grand-chose ici. Peut
 * être reconsidéré si une bibliothèque avec énormément d'albums distincts s'avère lente.
 */
public class AlbumClusterWorker extends SwingWorker<Void, String> {

    private final FileTableModel   tableModel;
    private final Consumer<String> statusCallback;
    private final Runnable         doneCallback;

    private final TagWriter writer = new TagWriter();
    private final boolean rgEnabled = Config.get().replayGainEnabled() && ReplayGainAnalyzer.isAvailable();

    private final AtomicInteger albumsProcessed = new AtomicInteger();
    private final AtomicInteger tracksFixed     = new AtomicInteger();

    private static final int DURATION_TOLERANCE_SEC = 3;

    public AlbumClusterWorker(FileTableModel tableModel,
                               Consumer<String> statusCallback, Runnable doneCallback) {
        this.tableModel     = tableModel;
        this.statusCallback = statusCallback;
        this.doneCallback   = doneCallback;
    }

    @Override
    protected Void doInBackground() throws Exception {
        // allEntries() : TOUTE la bibliothèque, filtre de la table ignoré — sinon un filtre actif
        // au moment du clic laisserait des albums entiers hors de la passe.
        Map<String, List<FileEntry>> groups = new LinkedHashMap<>();
        for (FileEntry e : tableModel.allEntries()) {
            if (e.status == FileEntry.Status.TAGGED && e.result != null && !e.result.releaseMbid.isBlank()) {
                groups.computeIfAbsent(e.result.releaseMbid, k -> new ArrayList<>()).add(e);
            }
        }
        if (groups.isEmpty()) {
            publish(I18n.t("Aucun album identifié parmi les fichiers tagués."));
            return null;
        }

        MetadataCache cache = new MetadataCache();
        try {
            for (Map.Entry<String, List<FileEntry>> group : groups.entrySet()) {
                if (isCancelled()) break;
                List<FileEntry> albumFiles = group.getValue();
                if (albumFiles.size() < 2) continue;
                processAlbum(group.getKey(), albumFiles, cache, new MusicBrainzClient());
            }
        } finally {
            cache.close();
        }
        return null;
    }

    private void processAlbum(String releaseMbid, List<FileEntry> albumFiles,
                               MetadataCache cache, MusicBrainzClient mb) {
        publish(I18n.t("  cluster: %s fichiers pour release %s", albumFiles.size(), releaseMbid));
        try {
            MusicBrainzClient.ReleaseTracklist tracklist = fetchTracklistCached(cache, releaseMbid, mb);
            if (tracklist == null || tracklist.tracks().isEmpty()) return;
            albumsProcessed.incrementAndGet();

            int maxDisc = tracklist.tracks().stream()
                    .mapToInt(MusicBrainzClient.ReleaseTrack::disc).max().orElse(0);

            for (FileEntry entry : albumFiles) {
                if (isCancelled()) break;
                TagInfo current = entry.result;
                File entryFile = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                MusicBrainzClient.ReleaseTrack matched = findBestTrack(tracklist, current, entryFile);
                if (matched == null) continue;

                // Travailler sur une COPIE : ne jamais muter entry.result directement hors EDT —
                // entry est aussi comparé en direct par le TableRowSorter depuis l'EDT, une
                // mutation concurrente pendant un tri casse le contrat de Comparator (déjà vu
                // 697× en 3 jours dans TaggingWorker avant correctif, voir SafeTableRowSorter).
                TagInfo updated = current.copy();
                boolean changed = false;
                if (matched.trackNo() > 0 && !String.valueOf(matched.trackNo()).equals(updated.track)) {
                    updated.track = String.valueOf(matched.trackNo()); changed = true;
                }
                if (matched.trackTotal() > 0 && !String.valueOf(matched.trackTotal()).equals(updated.trackTotal)) {
                    updated.trackTotal = String.valueOf(matched.trackTotal()); changed = true;
                }
                if (maxDisc > 1 && matched.disc() > 0) {
                    updated.discNo    = String.valueOf(matched.disc());
                    updated.discTotal = String.valueOf(maxDisc);
                    changed = true;
                }
                if (!tracklist.albumArtist().isBlank())     updated.albumArtist     = tracklist.albumArtist();
                if (!tracklist.albumArtistSort().isBlank()) updated.albumArtistSort = tracklist.albumArtistSort();
                if (tracklist.isCompilation())              updated.isCompilation   = "1";

                if (!changed) continue;

                File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                TagInfo written;
                try {
                    written = writer.write(fichier, updated);
                } catch (Exception ex) {
                    publish(I18n.t("  cluster erreur: %s", ex.getMessage()));
                    continue;
                }
                tracksFixed.incrementAndGet();
                publish(I18n.t("  cluster ok: %s → piste %s/%s", fichier.getName(), written.track, written.trackTotal));

                final FileEntry entryFinal = entry;
                final TagInfo   writtenFinal = written;
                SwingUtilities.invokeLater(() -> {
                    entryFinal.result = writtenFinal;
                    tableModel.update(entryFinal);
                });
            }

            // ── ReplayGain d'album (analyse concaténée sur TOUTES les pistes du groupe) ──
            if (rgEnabled) {
                List<String> paths = albumFiles.stream()
                    .map(e -> e.currentPath != null ? e.currentPath.toString() : e.file.getAbsolutePath())
                    .collect(java.util.stream.Collectors.toList());
                publish(I18n.t("  album RG: analyse %s pistes...", paths.size()));
                ReplayGainAnalyzer.RGResult albumRg = ReplayGainAnalyzer.analyzeAlbum(paths);
                if (albumRg != null) {
                    publish(I18n.t("  album RG: gain=%s peak=%s", albumRg.trackGain(), albumRg.trackPeak()));
                    for (FileEntry entry : albumFiles) {
                        File f = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                        writer.writeAlbumReplayGain(f, albumRg.trackGain(), albumRg.trackPeak());
                    }
                }
            }
        } catch (Exception e) {
            publish(I18n.t("  cluster erreur: %s", e.getMessage()));
        }
    }

    @Override
    protected void process(List<String> chunks) {
        if (!chunks.isEmpty()) statusCallback.accept(chunks.get(chunks.size() - 1));
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            statusCallback.accept(I18n.t(
                "Groupement albums — %d album(s) traité(s), %d piste(s) corrigée(s)",
                albumsProcessed.get(), tracksFixed.get()));
        }
        if (doneCallback != null) doneCallback.run();
    }

    // ── Helpers (repris de l'ancien TaggingWorker.clusterAlbums()) ────────────────────────

    /** Même mécanisme de cache que AlbumCompletionWorker.fetchTracklist() / TaggingWorker.
     *  fetchTracklistCached() — délibérément une 3e copie plutôt qu'un partage : les trois
     *  workers ont des cycles de vie et des champs cache/mb différents, factoriser n'apporterait
     *  pas grand-chose de plus qu'une indirection ici. */
    private MusicBrainzClient.ReleaseTracklist fetchTracklistCached(
            MetadataCache cache, String relMbid, MusicBrainzClient mb) {
        String cacheKey = "release:" + relMbid;
        try {
            String cached = cache.getLookup(cacheKey);
            if (cached != null) {
                MusicBrainzClient.ReleaseTracklist tl = mb.parseReleaseFromCache(cached);
                if (tl != null) return tl;
            }
            MusicBrainzClient.ReleaseTracklist tl = mb.lookupRelease(relMbid);
            if (tl != null) {
                String raw = mb.lastRawJson();
                if (!raw.isBlank()) cache.putLookup(cacheKey, raw);
            }
            return tl;
        } catch (Exception e) {
            return null;
        }
    }

    private MusicBrainzClient.ReleaseTrack findBestTrack(MusicBrainzClient.ReleaseTracklist tracklist,
                                                          TagInfo result, File audioFile) {
        // 1. Correspondance par recordingMbid (100% fiable)
        if (!result.recordingMbid.isBlank()) {
            for (var t : tracklist.tracks())
                if (result.recordingMbid.equals(t.recordingMbid())) return t;
        }
        String titleLow = result.title.toLowerCase().trim();

        // 2. Correspondance par numéro de piste (+ disque si connu). Sur une release multi-disques,
        // si le disque n'est pas connu et que plusieurs disques ont ce numéro de piste, NE PAS
        // deviner — laisser les étapes suivantes (durée, titre) trancher.
        if (!result.track.isBlank()) {
            try {
                int n = Integer.parseInt(result.track.trim());
                Integer d = result.discNo.isBlank() ? null : Integer.parseInt(result.discNo.trim());
                List<MusicBrainzClient.ReleaseTrack> sameNumber = new ArrayList<>();
                for (var t : tracklist.tracks()) {
                    if (t.trackNo() != n) continue;
                    if (d != null && t.disc() != 0 && t.disc() != d) continue;
                    sameNumber.add(t);
                }
                if (sameNumber.size() == 1) return sameNumber.get(0);
            } catch (NumberFormatException ignored) {}
        }

        // 3. Correspondance par durée du fichier
        int fileDurSec = audioFile != null ? AudioDuration.probeSeconds(audioFile.getAbsolutePath()) : -1;
        if (fileDurSec > 0) {
            List<MusicBrainzClient.ReleaseTrack> withinTolerance = new ArrayList<>();
            for (var t : tracklist.tracks()) {
                if (t.lengthMs() <= 0) continue;
                if (Math.abs(t.lengthMs() / 1000 - fileDurSec) <= DURATION_TOLERANCE_SEC) withinTolerance.add(t);
            }
            if (withinTolerance.size() == 1) return withinTolerance.get(0);
            if (withinTolerance.size() > 1 && !titleLow.isBlank()) {
                MusicBrainzClient.ReleaseTrack best = null;
                int bestScore = 0;
                for (var t : withinTolerance) {
                    int sim = titleSimilarity(titleLow, t.title().toLowerCase().trim());
                    if (sim > bestScore) { bestScore = sim; best = t; }
                }
                if (best != null) return best;
            }
        }

        // 4. Correspondance par similarité de titre (dernier recours, toute la tracklist)
        if (titleLow.isBlank()) return null;
        MusicBrainzClient.ReleaseTrack best = null;
        int bestScore = 0;
        for (var t : tracklist.tracks()) {
            int sim = titleSimilarity(titleLow, t.title().toLowerCase().trim());
            if (sim > bestScore && sim >= 70) { bestScore = sim; best = t; }
        }
        return best;
    }

    private static int titleSimilarity(String a, String b) {
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) return 90;
        Set<String> ta = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> tb = new HashSet<>(Arrays.asList(b.split("\\s+")));
        long common = ta.stream().filter(tb::contains).count();
        int total = ta.size() + tb.size();
        return total == 0 ? 0 : (int)(common * 2 * 100 / total);
    }
}
