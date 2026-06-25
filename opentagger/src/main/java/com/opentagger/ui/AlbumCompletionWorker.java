package com.opentagger.ui;

import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.MusicBrainzClient.ReleaseTracklist;
import com.opentagger.MusicBrainzClient.ReleaseTrack;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import javax.swing.*;
import java.io.File;
import java.util.*;
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
 */
public class AlbumCompletionWorker extends SwingWorker<Void, String> {

    private final FileTableModel  tableModel;
    private final MusicBrainzClient mb;
    private final Consumer<String>  statusCallback;
    private final Runnable          doneCallback;

    private int matched  = 0;
    private int releases = 0;

    public AlbumCompletionWorker(FileTableModel tableModel, MusicBrainzClient mb,
                                 Consumer<String> statusCallback, Runnable doneCallback) {
        this.tableModel     = tableModel;
        this.mb             = mb;
        this.statusCallback = statusCallback;
        this.doneCallback   = doneCallback;
    }

    @Override
    protected Void doInBackground() throws Exception {
        // ── 1. Collecter les releases depuis les fichiers TAGGED ─────────────
        // releaseMbid → {recordingMbid → FileEntry}
        Map<String, Map<String, FileEntry>> releaseGroups = new LinkedHashMap<>();

        List<FileEntry> candidates = new ArrayList<>(); // SKIPPED/PENDING à compléter

        for (int i = 0; i < tableModel.getRowCount(); i++) {
            FileEntry e = tableModel.get(i);
            if (e.status == FileEntry.Status.TAGGED) {
                String rMbid = e.result != null ? e.result.releaseMbid : "";
                if (!rMbid.isBlank()) {
                    releaseGroups
                        .computeIfAbsent(rMbid, k -> new LinkedHashMap<>())
                        .put(e.result.recordingMbid, e);
                }
            } else if (e.status == FileEntry.Status.SKIPPED || e.status == FileEntry.Status.PENDING) {
                candidates.add(e);
            }
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

        // Index titre normalisé → FileEntry pour les candidats
        // (on lit le titre intégré dans le fichier)
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

        // ── 2. Pour chaque release, récupérer la tracklist et trouver les manquants
        MetadataCache cache = new MetadataCache();
        try {
            for (Map.Entry<String, Map<String, FileEntry>> group : releaseGroups.entrySet()) {
                if (isCancelled()) break;

                String  relMbid  = group.getKey();
                Map<String, FileEntry> found = group.getValue();

                ReleaseTracklist tl = fetchTracklist(cache, relMbid);
                if (tl == null) continue;
                releases++;

                publish(String.format("Album : %s (%d piste(s) trouvée(s) / %d au total)",
                        tl.album(), found.size(), tl.tracks().size()));

                for (ReleaseTrack track : tl.tracks()) {
                    if (isCancelled()) break;
                    if (found.containsKey(track.recordingMbid())) continue; // déjà là

                    // Chercher dans les candidats par titre
                    FileEntry hit = findCandidate(candidateIndex, track.title());
                    if (hit == null) continue;

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
                    ti.isCompilation   = tl.isCompilation() ? "1" : "";
                    ti.score           = 100;

                    // Écrire les tags
                    try {
                        java.nio.file.Path writePath = hit.currentPath != null ? hit.currentPath : hit.file.toPath();
                        new TagWriter().write(writePath.toFile(), ti);
                        cache.recordFileTagging(writePath.toString(), track.recordingMbid());
                        cache.saveTaggingHistory(ti);

                        hit.result  = ti;
                        hit.status  = FileEntry.Status.TAGGED;
                        hit.message = "";
                        final FileEntry hitFinal = hit;
                        SwingUtilities.invokeLater(() -> tableModel.update(hitFinal));

                        publish(String.format("  ✓ %s → piste %d \"%s\"",
                                hit.filename(), track.trackNo(), track.title()));
                        matched++;

                        // Retirer du pool de candidats
                        candidateIndex.values().remove(hit);
                    } catch (Exception ex) {
                        publish("  ✗ " + hit.filename() + " : " + ex.getMessage());
                    }
                }

                // Respect du rate-limit MB (1 req/s)
                Thread.sleep(1100);
            }
        } finally {
            cache.close();
        }
        return null;
    }

    @Override
    protected void process(List<String> chunks) {
        if (!chunks.isEmpty()) statusCallback.accept(chunks.get(chunks.size() - 1));
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            statusCallback.accept(String.format(
                "Complétion albums — %d album(s) analysé(s), %d piste(s) récupérée(s)", releases, matched));
        }
        if (doneCallback != null) doneCallback.run();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReleaseTracklist fetchTracklist(MetadataCache cache, String relMbid) {
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
