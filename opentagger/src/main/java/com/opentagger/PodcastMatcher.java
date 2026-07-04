package com.opentagger;

import com.opentagger.PodcastRssClient.PodcastEpisode;
import com.opentagger.model.FileEntry;

import java.util.*;

/**
 * Associe chaque FileEntry à un PodcastEpisode.
 *
 * Stratégie :
 *  1. Par durée audio (ffprobe vs itunes:duration) — tolérance ±5 secondes
 *  2. Par titre normalisé (artiste + titre) si la durée est inconnue ou ambiguë
 */
public class PodcastMatcher {

    public record MatchResult(FileEntry file, PodcastEpisode episode, boolean matched) {}

    private static final int DURATION_TOLERANCE_SEC = 5;

    public static List<MatchResult> match(List<FileEntry> files, List<PodcastEpisode> episodes) {
        // Préparer les durées des fichiers via ffprobe
        Map<FileEntry, Integer> fileDurations = new LinkedHashMap<>();
        for (FileEntry f : files) {
            String path = (f.currentPath != null ? f.currentPath : f.file.toPath()).toString();
            fileDurations.put(f, AudioDuration.probeSeconds(path));
        }

        Set<PodcastEpisode> used = new HashSet<>();
        List<MatchResult> results = new ArrayList<>();

        for (FileEntry file : files) {
            int fileDur = fileDurations.getOrDefault(file, -1);
            PodcastEpisode best = null;

            // Passe 1 : durée
            if (fileDur > 0) {
                best = episodes.stream()
                    .filter(ep -> !used.contains(ep))
                    .filter(ep -> ep.durationSec() > 0)
                    .filter(ep -> Math.abs(ep.durationSec() - fileDur) <= DURATION_TOLERANCE_SEC)
                    .min(Comparator.comparingInt(ep -> Math.abs(ep.durationSec() - fileDur)))
                    .orElse(null);
            }

            // Passe 2 : titre normalisé (filename vs episode title)
            if (best == null) {
                String normFile = normalize(file.filename());
                best = episodes.stream()
                    .filter(ep -> !used.contains(ep))
                    .filter(ep -> !ep.title().isBlank())
                    .filter(ep -> normalize(ep.title()).length() > 3)
                    .filter(ep -> normFile.contains(normalize(ep.title()))
                               || normalize(ep.title()).contains(normFile))
                    .findFirst()
                    .orElse(null);
            }

            if (best != null) used.add(best);
            results.add(new MatchResult(file, best, best != null));
        }

        return results;
    }

    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
