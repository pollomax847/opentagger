package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.PodcastMatcher.MatchResult;
import com.opentagger.PodcastRssClient.*;
import com.opentagger.model.*;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Écrit les tags podcast sur les fichiers matchés (SwingWorker).
 */
public class PodcastWorker extends SwingWorker<Void, String> {

    private static final Logger LOG = Logger.getLogger(PodcastWorker.class.getName());

    private final List<MatchResult> matches;
    private final PodcastFeed       feed;
    private final FileTableModel    tableModel;
    private final Consumer<String>  onProgress;
    private final Consumer<FileEntry> onUpdate;
    private final MetadataCache     cache = new MetadataCache();

    private int tagged = 0, errors = 0;

    public PodcastWorker(List<MatchResult> matches, PodcastFeed feed, FileTableModel tableModel,
                          Consumer<String> onProgress, Consumer<FileEntry> onUpdate) {
        this.matches    = matches;
        this.feed       = feed;
        this.tableModel = tableModel;
        this.onProgress = onProgress;
        this.onUpdate   = onUpdate;
    }

    @Override
    protected Void doInBackground() throws Exception {
        // Pochette du show téléchargée UNE SEULE FOIS pour tout le feed (même artwork pour tous
        // les épisodes) — le flux expose déjà cette URL (PodcastFeed.artworkUrl, parsée depuis
        // <itunes:image>) mais elle n'était jusqu'ici jamais utilisée : les épisodes de podcast
        // se retrouvaient tagués sans aucune pochette alors que la donnée était disponible.
        java.nio.file.Path showCover = null;
        if (!feed.artworkUrl().isBlank()) {
            try {
                showCover = ImageDownloader.downloadToTempFile(feed.artworkUrl(), cache);
            } catch (Exception ex) {
                LOG.warning("[Podcast] Pochette du show non téléchargée : " + ex.getMessage());
            }
        }
        try {
            for (MatchResult mr : matches) {
                if (isCancelled()) break;
                if (!mr.matched()) continue;

                FileEntry   entry   = mr.file();
                PodcastEpisode ep   = mr.episode();
                Path writePath      = entry.currentPath != null ? entry.currentPath : entry.file.toPath();

                publish("Taguage : " + entry.filename());

                TagInfo ti = buildTagInfo(ep);
                // Script tagger utilisateur : mécanisme générique (s'applique à n'importe quel
                // TagInfo, pas seulement aux résultats d'identification musicale) — même logique
                // partagée que TaggingWorker/BatchProcessor/App/InfoCompleterWorker/
                // AlbumCompletionWorker, absente ici jusqu'à présent. Contrairement au genre/à la
                // pochette (hors-sujet pour un épisode de podcast), rien ne justifie d'exclure
                // celui-ci.
                new TaggerScript().apply(ti);
                try {
                    new TagWriter().write(writePath.toFile(), ti, showCover);
                    // guid RSS si disponible (plus fiable que le titre, qui peut se répéter
                    // d'un épisode à l'autre — ex. un épisode "Q&A" mensuel récurrent).
                    String key = !ep.guid().isBlank() ? ep.guid() : ep.title();
                    cache.recordFileTagging(writePath.toString(), "podcast:" + key);

                    // Organiser selon le masque [Podcast] Show/Season/Date - Titre (index 4) —
                    // jusqu'à ce correctif, "Dossier racine podcasts" était sauvegardé/rechargé
                    // dans les Réglages sans jamais être lu : les épisodes tagués n'étaient
                    // jamais déplacés/organisés, quel que soit ce réglage.
                    java.nio.file.Path finalWritePath = writePath;
                    try {
                        java.nio.file.Path oldParent = writePath.getParent();
                        String podcastRoot = com.opentagger.Config.get().podcastLibraryRoot();
                        String libRoot      = com.opentagger.Config.get().libraryRoot();
                        java.nio.file.Path root =
                            (!podcastRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(podcastRoot)))
                                ? java.nio.file.Paths.get(podcastRoot)
                            : (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                                ? java.nio.file.Paths.get(libRoot)
                                : (entry.scanRoot != null ? entry.scanRoot : oldParent);
                        java.nio.file.Path newPath = new com.opentagger.FileRenamer()
                                .rename(writePath, ti, 4, root);
                        if (newPath != null) {
                            finalWritePath = newPath;
                            if (com.opentagger.Config.get().deleteEmptyDirsAfterRename()) {
                                com.opentagger.FileRenamer.deleteEmptyAncestors(oldParent, root);
                            }
                        }
                    } catch (Exception ignored) {}

                    // Muter entry/tableModel SUR l'EDT, pas ici : ce FileEntry est aussi lu par
                    // le TableRowSorter en direct depuis l'EDT, et une mutation concurrente
                    // pendant qu'un tri est en cours produit "Comparison method violates its
                    // general contract!" (déjà vu 697× en 3 jours, jusqu'ici imputé au seul
                    // TaggingWorker qui a le même défaut).
                    final FileEntry ef = entry;
                    final java.nio.file.Path finalPathForEdt = finalWritePath;
                    SwingUtilities.invokeLater(() -> {
                        ef.result  = ti;
                        ef.status  = FileEntry.Status.TAGGED;
                        ef.message = "";
                        ef.currentPath = finalPathForEdt;
                        tableModel.update(ef);
                        // Manquait au Journal (même trou que AlbumCompletionWorker/AlbumClusterWorker
                        // avant correctif) : ce worker publie du texte (publish(String), voir
                        // process() plus bas) qui n'atteignait jamais MainFrame.appendLog().
                        if (onUpdate != null) onUpdate.accept(ef);
                    });
                    LOG.info("[Podcast] Tagué : " + entry.filename() + " → " + ep.title());
                    tagged++;
                } catch (Exception ex) {
                    publish("  ✗ " + entry.filename() + " : " + ex.getMessage());
                    LOG.warning("[Podcast] Erreur " + entry.filename() + " : " + ex.getMessage());
                    errors++;
                }
            }
        } finally {
            cache.close();
        }
        return null;
    }

    private TagInfo buildTagInfo(PodcastEpisode ep) {
        TagInfo ti = new TagInfo();
        // Champs show
        ti.album       = feed.showTitle();
        ti.albumArtist = feed.author();
        ti.genre       = "Podcast";

        // Champs épisode
        ti.title   = ep.title();
        ti.artist  = ep.author().isBlank() ? feed.author() : ep.author();
        ti.comment = ep.description().length() > 500
                     ? ep.description().substring(0, 500) + "…"
                     : ep.description();
        ti.year    = ep.pubDate().length() >= 4 ? ep.pubDate().substring(0, 4) : ep.pubDate();

        if (ep.episodeNumber() > 0) ti.track = String.valueOf(ep.episodeNumber());
        if (ep.season()        > 0) ti.discNo = String.valueOf(ep.season());

        // Champs podcast custom
        ti.podcastUrl         = feed.feedUrl();
        ti.podcastEpisode     = ep.episodeNumber() > 0 ? String.valueOf(ep.episodeNumber()) : "";
        ti.podcastSeason      = ep.season()        > 0 ? String.valueOf(ep.season())        : "";
        ti.podcastEpisodeType = ep.episodeType();
        ti.podcastKeywords    = ep.keywords();

        ti.score = 100;
        return ti;
    }

    @Override
    protected void process(List<String> chunks) {
        // Correctif : cette méthode était un no-op avec un commentaire affirmant à tort que le
        // dialogue consommait la progression via addPropertyChangeListener — ce listener ne
        // regardait que "state"==DONE, jamais les chunks. La progression par fichier était donc
        // silencieusement perdue (même schéma que AlbumClusterWorker.process()).
        if (!chunks.isEmpty()) onProgress.accept(chunks.get(chunks.size() - 1));
    }

    public int getTagged() { return tagged; }
    public int getErrors()  { return errors; }
}
