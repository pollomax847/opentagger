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
        // Préparer les durées des fichiers via ffprobe — PARALLÉLISÉ (même clé "batch.threads" que
        // le reste de l'appli). Avant : séquentiel, un sous-processus ffprobe par fichier (jusqu'à
        // 10s de timeout CHACUN, voir AudioDuration.probeSeconds) — sur les PENDING/SKIPPED d'une
        // grosse bibliothèque (des dizaines de milliers chez certains utilisateurs), "Matching en
        // cours…" pouvait tourner des heures sans le moindre retour de progression (bug réel
        // signalé, "cela tourné à l'infini"). Le filtre PENDING/SKIPPED (voir MainFrame.
        // openPodcastDialog()) réduit déjà l'ensemble, mais un ensemble encore large reste possible
        // (scan en cours sur 2 To) — la parallélisation reste nécessaire dans tous les cas.
        Map<FileEntry, Integer> fileDurations = new java.util.concurrent.ConcurrentHashMap<>();
        int threads = Math.max(1, Config.get().num("batch.threads", 3));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (FileEntry f : files) {
                futures.add(pool.submit(() -> {
                    String path = (f.currentPath != null ? f.currentPath : f.file.toPath()).toString();
                    fileDurations.put(f, AudioDuration.probeSeconds(path));
                }));
            }
            for (var fut : futures) {
                try { fut.get(); } catch (Exception ignored) {}
            }
        } finally {
            pool.shutdown();
        }

        Set<PodcastEpisode> used = new HashSet<>();

        // Passe 1 : durée — appariement GLOBAL plutôt que glouton fichier par fichier.
        // Avant ce correctif, chaque fichier réclamait son meilleur épisode dans l'ordre de la
        // liste `files`, sans savoir qu'un autre fichier plus tard dans la liste n'avait QUE cet
        // épisode comme candidat valide : un fichier traité en premier pouvait ainsi capter
        // l'épisode d'un autre, privant ce dernier d'une correspondance pourtant possible (ex.
        // fichier A à distance 0 de l'épisode 1 et distance 1 de l'épisode 2, fichier B à distance
        // 0 du SEUL épisode 1 — A traité en premier prend l'épisode 1, B se retrouve sans rien,
        // alors que A↔2 / B↔1 aurait apparié les deux). Ici : toutes les paires (fichier, épisode)
        // valides sont triées par proximité croissante, puis attribuées glouton sur cet ordre
        // global — équivalent à un appariement glouton par poids croissant, pas un algorithme
        // d'appariement optimal exact (Hongrois), mais largement suffisant pour des écarts de
        // quelques secondes sur, au plus, quelques centaines d'épisodes par flux.
        record Candidate(FileEntry file, PodcastEpisode ep, int dist) {}
        List<Candidate> candidates = new ArrayList<>();
        for (FileEntry file : files) {
            int fileDur = fileDurations.getOrDefault(file, -1);
            if (fileDur <= 0) continue;
            for (PodcastEpisode ep : episodes) {
                if (ep.durationSec() <= 0) continue;
                int dist = Math.abs(ep.durationSec() - fileDur);
                if (dist <= DURATION_TOLERANCE_SEC) candidates.add(new Candidate(file, ep, dist));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::dist));

        Map<FileEntry, PodcastEpisode> matchedByFile = new HashMap<>();
        for (Candidate c : candidates) {
            if (matchedByFile.containsKey(c.file()) || used.contains(c.ep())) continue;
            matchedByFile.put(c.file(), c.ep());
            used.add(c.ep());
        }

        List<MatchResult> results = new ArrayList<>();
        for (FileEntry file : files) {
            PodcastEpisode best = matchedByFile.get(file);

            // Passe 2 : titre normalisé (filename vs episode title) — pour les fichiers sans
            // correspondance de durée (durée inconnue, ou aucun épisode restant dans la tolérance).
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
                if (best != null) used.add(best);
            }

            results.add(new MatchResult(file, best, best != null));
        }

        return results;
    }

    static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
