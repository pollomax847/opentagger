package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Cherche, parmi les pistes déjà tagués (album/artiste studio d'origine), celles dont
 * l'enregistrement existe AUSSI sur une compilation radio française que l'utilisateur possède
 * réellement (ex. "Stars 80", NRJ, Fun Radio, RFM) — un même enregistrement MusicBrainz existe
 * souvent sur PLUSIEURS releases (l'album original ET une ou plusieurs compilations), mais le
 * pipeline d'identification normal n'en retient qu'une seule ("meilleure" release, via
 * {@code MusicBrainzClient.findBestRelease}), jamais la compilation spécifique de l'utilisateur.
 * Demandé le 2026-07-10 après avoir remarqué que ses morceaux de compilations radio se
 * retrouvaient tagués avec l'album studio d'origine.
 *
 * PROACTIF, pas automatique (choix explicite de l'utilisateur, AskUserQuestion) : cette passe ne
 * fait QUE chercher des correspondances et les remonter à {@code doneCallback} sous forme de
 * {@link CompilationMatch} — elle n'écrit RIEN sur le disque ni ne mute aucun {@code FileEntry}.
 * L'écriture réelle se fait ensuite dans {@link CompilationMatchDialog}, après revue et sélection
 * explicite par l'utilisateur (une correspondance de nom de série peut se tromper — un ancien
 * prototype de cet outil écrivait directement et a été révisé après ce constat).
 *
 * Vérifie, pour chaque piste déjà taguée, si son {@code recordingMbid} existe aussi sur une release
 * dont le release-group est marqué "Compilation" par MusicBrainz lui-même (secondary-type officiel,
 * déjà remonté dans {@link MusicBrainzClient.RecordingRelease#secondaryTypes()}) — détection
 * automatique, sans dépendre d'une liste figée. Les noms de série configurés par l'utilisateur
 * ({@link Config#compilationSeriesNames()}, ex. "Stars 80", "NRJ") restent utilisables en plus (par
 * substring sur le titre), pour les rares cas où MusicBrainz ne marquerait pas le secondary-type,
 * mais ne sont plus requis : liste vide = fonctionne quand même, uniquement via le tag MB. Demandé
 * le 2026-07-28 après remarque que la liste manuelle ne couvrait pas les compilations déjà possédées
 * mais absentes de la liste ("ça reste figé"). Séquentiel, même raison que {@link AlbumClusterWorker}
 * (peu de cache-miss réel en pratique sur une bibliothèque déjà taguée) dont cette classe reprend la
 * structure générale.
 */
public class CompilationClusterWorker extends SwingWorker<List<CompilationClusterWorker.CompilationMatch>, String> {

    /** Une correspondance trouvée, pas encore appliquée — voir {@link CompilationMatchDialog}. */
    public record CompilationMatch(FileEntry entry, TagInfo updated, String matchedTitle) {}

    private final FileTableModel   tableModel;
    private final Consumer<String> statusCallback;
    private final Consumer<String> logLine;
    private final Consumer<List<CompilationMatch>> doneCallback;

    /** logLine : ajoute une ligne PERSISTANTE au panneau Journal (contrairement à statusCallback,
     *  écrasé à chaque nouveau message) — avant ce correctif, cette passe n'avait aucune trace
     *  consultable après coup, chaque correspondance/erreur clignotait une fraction de seconde
     *  dans la barre de statut puis disparaissait, écrasée par la suivante (retour utilisateur :
     *  "recherche compilation n'a pas de journal"). */
    public CompilationClusterWorker(FileTableModel tableModel, Consumer<String> statusCallback,
                                     Consumer<String> logLine,
                                     Consumer<List<CompilationMatch>> doneCallback) {
        this.tableModel     = tableModel;
        this.statusCallback = statusCallback;
        this.logLine        = logLine;
        this.doneCallback   = doneCallback;
    }

    @Override
    protected List<CompilationMatch> doInBackground() throws Exception {
        String[] seriesNames = Config.get().compilationSeriesNames();
        List<CompilationMatch> matches = new ArrayList<>();
        MetadataCache cache = new MetadataCache();
        try {
            for (FileEntry entry : tableModel.allEntries()) {
                if (isCancelled()) break;
                if (entry.status != FileEntry.Status.TAGGED || entry.result == null
                        || entry.result.recordingMbid.isBlank()) continue;
                CompilationMatch match = checkEntry(entry, seriesNames, cache, new MusicBrainzClient());
                if (match != null) matches.add(match);
            }
        } finally {
            cache.close();
        }
        return matches;
    }

    private CompilationMatch checkEntry(FileEntry entry, String[] seriesNames,
                                         MetadataCache cache, MusicBrainzClient mb) {
        String recordingMbid = entry.result.recordingMbid;
        try {
            List<MusicBrainzClient.RecordingRelease> releases =
                    fetchRecordingReleasesCached(cache, recordingMbid, mb);
            MusicBrainzClient.RecordingRelease match = findSeriesMatch(releases, seriesNames);
            if (match == null) return null;

            MusicBrainzClient.ReleaseTracklist tracklist =
                    fetchTracklistCached(cache, match.releaseId(), mb);

            TagInfo updated = entry.result.copy();
            updated.album         = match.releaseTitle();
            updated.albumArtist   = Config.get().vaName();
            updated.isCompilation = "1";
            updated.releaseMbid   = match.releaseId();

            if (tracklist != null) {
                int maxDisc = tracklist.tracks().stream()
                        .mapToInt(MusicBrainzClient.ReleaseTrack::disc).max().orElse(0);
                for (var t : tracklist.tracks()) {
                    if (!recordingMbid.equals(t.recordingMbid())) continue;
                    if (t.trackNo() > 0)    updated.track      = String.valueOf(t.trackNo());
                    if (t.trackTotal() > 0) updated.trackTotal = String.valueOf(t.trackTotal());
                    if (maxDisc > 1 && t.disc() > 0) {
                        updated.discNo    = String.valueOf(t.disc());
                        updated.discTotal = String.valueOf(maxDisc);
                    }
                    break;
                }
            }

            String foundMsg = I18n.t("  trouvé : %s → %s", entry.filename(), match.releaseTitle());
            publish(foundMsg);
            if (logLine != null) logLine.accept(foundMsg);
            return new CompilationMatch(entry, updated, match.releaseTitle());
        } catch (Exception e) {
            String errMsg = I18n.t("  compilation erreur (%s) : %s", entry.filename(), e.getMessage());
            publish(errMsg);
            if (logLine != null) logLine.accept(errMsg);
            return null;
        }
    }

    /** Cherche la première release qui est soit marquée "Compilation" par MusicBrainz lui-même
     *  (secondary-type officiel du release-group — détection automatique, aucune liste requise),
     *  soit dont le titre (ou celui de son release-group) contient, insensible à la casse, un des
     *  noms de série éventuellement configurés par l'utilisateur (complément optionnel). */
    private static MusicBrainzClient.RecordingRelease findSeriesMatch(
            List<MusicBrainzClient.RecordingRelease> releases, String[] seriesNames) {
        for (MusicBrainzClient.RecordingRelease r : releases) {
            if (r.secondaryTypes() != null
                    && r.secondaryTypes().stream().anyMatch(t -> t.equalsIgnoreCase("Compilation"))) {
                return r;
            }
            String title = r.releaseTitle().toLowerCase(Locale.ROOT);
            String groupTitle = r.releaseGroupTitle().toLowerCase(Locale.ROOT);
            for (String series : seriesNames) {
                String s = series.trim().toLowerCase(Locale.ROOT);
                if (s.isBlank()) continue;
                if (title.contains(s) || groupTitle.contains(s)) return r;
            }
        }
        return null;
    }

    @Override
    protected void process(List<String> chunks) {
        if (!chunks.isEmpty()) statusCallback.accept(chunks.get(chunks.size() - 1));
    }

    @Override
    protected void done() {
        List<CompilationMatch> matches = List.of();
        if (!isCancelled()) {
            try { matches = get(); } catch (Exception ignored) {}
            String summary = I18n.t("Grouper par compilations — %d correspondance(s) trouvée(s).", matches.size());
            statusCallback.accept(summary);
            if (logLine != null) logLine.accept(summary);
        }
        if (doneCallback != null) doneCallback.accept(matches);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Même motif que AlbumClusterWorker.fetchTracklistCached() — copie délibérée plutôt que
     *  partagée, cf. le commentaire de classe de AlbumClusterWorker sur ce choix. */
    private static MusicBrainzClient.ReleaseTracklist fetchTracklistCached(
            MetadataCache cache, String releaseMbid, MusicBrainzClient mb) {
        String cacheKey = "release:" + releaseMbid;
        try {
            String cached = cache.getLookup(cacheKey);
            if (cached != null) {
                MusicBrainzClient.ReleaseTracklist tl = mb.parseReleaseFromCache(cached);
                if (tl != null) return tl;
            }
            MusicBrainzClient.ReleaseTracklist tl = mb.lookupRelease(releaseMbid);
            if (tl != null) {
                String raw = mb.lastRawJson();
                if (!raw.isBlank()) cache.putLookup(cacheKey, raw);
            }
            return tl;
        } catch (Exception e) {
            return null;
        }
    }

    /** Même motif que fetchTracklistCached() ci-dessus — évite de repayer le rate-limit MB (1,1s
     *  entre requêtes) pour un recording déjà vérifié lors d'une exécution précédente, ex. après
     *  ajout d'une nouvelle série dans les Réglages sans avoir touché aux autres pistes. */
    private static List<MusicBrainzClient.RecordingRelease> fetchRecordingReleasesCached(
            MetadataCache cache, String recordingMbid, MusicBrainzClient mb) throws Exception {
        String cacheKey = "recording-releases:" + recordingMbid;
        String cached = cache.getLookup(cacheKey);
        if (cached != null) {
            List<MusicBrainzClient.RecordingRelease> parsed = mb.parseRecordingReleasesFromCache(cached);
            if (!parsed.isEmpty()) return parsed;
        }
        List<MusicBrainzClient.RecordingRelease> releases = mb.lookupRecordingReleases(recordingMbid);
        if (!releases.isEmpty()) {
            String raw = mb.lastRawJson();
            if (!raw.isBlank()) cache.putLookup(cacheKey, raw);
        }
        return releases;
    }
}
