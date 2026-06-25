package com.opentagger.ui;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.util.*;

/**
 * Détecte les fichiers audio en double dans une liste de FileEntry.
 *
 * Trois niveaux de confiance :
 *  - MBID_EXACT    : même Recording MBID → 100 % sûr
 *  - ACOUSTID_EXACT: même AcoustID       → très fiable
 *  - TITLE_HEURISTIC: artiste + titre normalisés → à vérifier
 *
 * Le niveau TITLE_HEURISTIC exige les DEUX (artiste ET titre).
 * Les correspondances "titre seul" sont volontairement exclues pour éviter
 * les faux positifs (ex: deux chansons différentes portant le même nom).
 */
public class DuplicateDetector {

    public enum Confidence {
        MBID_EXACT("MBID", "Identifié par Recording MBID — quasi-certain"),
        ACOUSTID_EXACT("AcoustID", "Identifié par empreinte acoustique — très fiable"),
        TITLE_HEURISTIC("Titre", "Correspondance artiste+titre — à vérifier");

        public final String badge;
        public final String tooltip;
        Confidence(String badge, String tooltip) { this.badge = badge; this.tooltip = tooltip; }
    }

    /** Un groupe de fichiers potentiellement en double. */
    public record DuplicateGroup(List<FileEntry> files, Confidence confidence) {}

    /** Retourne une liste de groupes, chaque groupe contenant ≥ 2 fichiers. */
    public static List<DuplicateGroup> detect(List<FileEntry> entries) {
        Map<String, List<FileEntry>> byMbid     = new LinkedHashMap<>();
        Map<String, List<FileEntry>> byAcoustId = new LinkedHashMap<>();
        Map<String, List<FileEntry>> byTitle    = new LinkedHashMap<>();

        // 1. MBID exact — indépendant des autres critères
        for (FileEntry e : entries) {
            TagInfo ti = e.activeTags();
            if (!ti.recordingMbid.isBlank())
                byMbid.computeIfAbsent(ti.recordingMbid, k -> new ArrayList<>()).add(e);
        }

        // 2. AcoustID exact — indépendant du MBID (un fichier peut avoir les deux)
        for (FileEntry e : entries) {
            TagInfo ti = e.activeTags();
            if (!ti.acoustidId.isBlank())
                byAcoustId.computeIfAbsent(ti.acoustidId, k -> new ArrayList<>()).add(e);
        }

        // 3. Artiste + titre normalisés — seulement si pas déjà groupé par MBID ou AcoustID
        Set<FileEntry> alreadyGrouped = new HashSet<>();
        for (List<FileEntry> g : byMbid.values())     if (g.size() >= 2) alreadyGrouped.addAll(g);
        for (List<FileEntry> g : byAcoustId.values()) if (g.size() >= 2) alreadyGrouped.addAll(g);
        for (FileEntry e : entries) {
            if (alreadyGrouped.contains(e)) continue;
            TagInfo ti = e.activeTags();
            if (!ti.artist.isBlank() && !ti.title.isBlank()) {
                String key = normalize(ti.artist) + "|" + normalize(ti.title);
                byTitle.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        }

        List<DuplicateGroup> groups = new ArrayList<>();
        for (List<FileEntry> g : byMbid.values())
            if (g.size() >= 2) groups.add(new DuplicateGroup(g, Confidence.MBID_EXACT));
        for (List<FileEntry> g : byAcoustId.values())
            if (g.size() >= 2) groups.add(new DuplicateGroup(g, Confidence.ACOUSTID_EXACT));
        for (List<FileEntry> g : byTitle.values())
            if (g.size() >= 2) groups.add(new DuplicateGroup(g, Confidence.TITLE_HEURISTIC));

        return groups;
    }

    /** Label court du groupe pour l'affichage dans la liste. */
    public static String groupLabel(DuplicateGroup group) {
        TagInfo ti = group.files().get(0).activeTags();
        StringBuilder sb = new StringBuilder();
        if (!ti.artist.isBlank()) sb.append(ti.artist).append(" — ");
        if (!ti.title.isBlank())  sb.append(ti.title);
        if (sb.isEmpty()) sb.append("(titre inconnu)");
        sb.append("  [").append(group.confidence().badge).append("]");
        return sb.toString();
    }

    /**
     * Score de qualité d'un fichier.
     * Plus le score est élevé, plus le fichier est de bonne qualité.
     * Critères : priorité format (FLAC > M4A > MP3…), puis taille fichier.
     */
    public static int qualityScore(FileEntry e) {
        File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
        int formatScore = switch (ext(f.getName()).toLowerCase()) {
            case "flac" -> 1_000_000;
            case "alac" -> 900_000;
            case "m4a"  -> 700_000;
            case "ogg"  -> 600_000;
            case "mp3"  -> 500_000;
            case "aac"  -> 400_000;
            case "wma"  -> 300_000;
            default     -> 100_000;
        };
        // Taille en Ko comme proxy de débit binaire (max 999 Ko pour ne pas dépasser int)
        int sizeScore = (int) Math.min(f.length() / 1024, 999);
        return formatScore + sizeScore;
    }

    /** Fichier avec le meilleur score de qualité dans le groupe. */
    public static FileEntry bestInGroup(List<FileEntry> files) {
        return files.stream()
            .max(Comparator.comparingInt(DuplicateDetector::qualityScore))
            .orElse(files.get(0));
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
