package com.opentagger.ui;

import com.opentagger.*;
import com.opentagger.PodcastMatcher.MatchResult;
import com.opentagger.PodcastRssClient.*;
import com.opentagger.model.*;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Écrit les tags podcast sur les fichiers matchés (SwingWorker).
 */
public class PodcastWorker extends SwingWorker<Void, String> {

    private static final Logger LOG = Logger.getLogger(PodcastWorker.class.getName());

    private final List<MatchResult> matches;
    private final PodcastFeed       feed;
    private final FileTableModel    tableModel;
    private final MetadataCache     cache = new MetadataCache();

    private int tagged = 0, errors = 0;

    public PodcastWorker(List<MatchResult> matches, PodcastFeed feed, FileTableModel tableModel) {
        this.matches    = matches;
        this.feed       = feed;
        this.tableModel = tableModel;
    }

    @Override
    protected Void doInBackground() throws Exception {
        try {
            for (MatchResult mr : matches) {
                if (isCancelled()) break;
                if (!mr.matched()) continue;

                FileEntry   entry   = mr.file();
                PodcastEpisode ep   = mr.episode();
                Path writePath      = entry.currentPath != null ? entry.currentPath : entry.file.toPath();

                publish("Taguage : " + entry.filename());

                TagInfo ti = buildTagInfo(ep);
                try {
                    new TagWriter().write(writePath.toFile(), ti);
                    cache.recordFileTagging(writePath.toString(), "podcast:" + ep.title());

                    entry.result  = ti;
                    entry.status  = FileEntry.Status.TAGGED;
                    entry.message = "";
                    final FileEntry ef = entry;
                    SwingUtilities.invokeLater(() -> tableModel.update(ef));
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
        ti.podcastKeywords    = "";

        ti.score = 100;
        return ti;
    }

    @Override
    protected void process(List<String> chunks) {
        // les logs sont consommés par le dialog via addPropertyChangeListener
    }

    public int getTagged() { return tagged; }
    public int getErrors()  { return errors; }
}
