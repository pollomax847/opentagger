package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.util.*;

/**
 * Détecte les fichiers audio en double dans une liste de FileEntry.
 *
 * Quatre niveaux de confiance :
 *  - MBID_EXACT       : même Recording MBID → 100 % sûr
 *  - ACOUSTID_EXACT   : même AcoustID (confirmé par la base AcoustID) → très fiable
 *  - FINGERPRINT_EXACT: même empreinte Chromaprint brute → fiable pour les copies
 *                       identiques/même encodage, mais ne détecte pas deux encodages
 *                       différents du même enregistrement (comparaison exacte, pas floue)
 *  - TITLE_HEURISTIC  : artiste + titre normalisés → à vérifier
 *
 * Le niveau TITLE_HEURISTIC exige les DEUX (artiste ET titre).
 * Les correspondances "titre seul" sont volontairement exclues pour éviter
 * les faux positifs (ex: deux chansons différentes portant le même nom).
 *
 * FINGERPRINT_EXACT comble un vrai manque : ACOUSTID_EXACT ne concerne que les fichiers
 * identifiés via une vraie correspondance dans la base AcoustID (acoustidId) — or l'empreinte
 * brute (acoustidFingerprint) est désormais calculée pour quasiment tous les fichiers, quelle
 * que soit la source d'identification (SongRec/texte/AcoustID), donc la majorité des fichiers
 * ne bénéficiait jusqu'ici que du plus faible TITLE_HEURISTIC malgré une empreinte disponible.
 */
public class DuplicateDetector {

    public enum Confidence {
        MBID_EXACT("MBID", I18n.t("Identifié par Recording MBID — quasi-certain")),
        ACOUSTID_EXACT("AcoustID", I18n.t("Identifié par empreinte acoustique (base AcoustID) — très fiable")),
        FINGERPRINT_EXACT(I18n.t("Empreinte"), I18n.t("Même empreinte audio brute — fiable (copies/même encodage)")),
        TITLE_HEURISTIC(I18n.t("Titre"), I18n.t("Correspondance artiste+titre — à vérifier"));

        public final String badge;
        public final String tooltip;
        Confidence(String badge, String tooltip) { this.badge = badge; this.tooltip = tooltip; }
    }

    /** Un groupe de fichiers potentiellement en double. */
    public record DuplicateGroup(List<FileEntry> files, Confidence confidence) {}

    /** Résultat complet d'une passe detect() : les groupes, plus le meilleur fichier de chacun déjà
     *  calculé (voir computeBestMap ci-dessous — évite de refaire ce calcul, coûteux en E/S, sur
     *  l'EDT lors de la construction de DuplicatesDialog). */
    public record DetectionResult(List<DuplicateGroup> groups, Map<DuplicateGroup, FileEntry> bestByGroup) {}

    /** Retourne une liste de groupes, chaque groupe contenant ≥ 2 fichiers. */
    public static List<DuplicateGroup> detect(List<FileEntry> entries) {
        Map<String, List<FileEntry>> byMbid        = new LinkedHashMap<>();
        Map<String, List<FileEntry>> byAcoustId    = new LinkedHashMap<>();
        Map<String, List<FileEntry>> byFingerprint = new LinkedHashMap<>();
        Map<String, List<FileEntry>> byTitle       = new LinkedHashMap<>();

        // 1. MBID exact
        for (FileEntry e : entries) {
            TagInfo ti = e.activeTags();
            if (!ti.recordingMbid.isBlank())
                byMbid.computeIfAbsent(ti.recordingMbid, k -> new ArrayList<>()).add(e);
        }

        // 2. AcoustID exact — seulement si pas déjà groupé par MBID (évite les doublons de groupes)
        Set<FileEntry> mbidGrouped = new HashSet<>();
        for (List<FileEntry> g : byMbid.values()) if (g.size() >= 2) mbidGrouped.addAll(g);
        for (FileEntry e : entries) {
            if (mbidGrouped.contains(e)) continue;
            TagInfo ti = e.activeTags();
            if (!ti.acoustidId.isBlank())
                byAcoustId.computeIfAbsent(ti.acoustidId, k -> new ArrayList<>()).add(e);
        }

        // 3. Empreinte Chromaprint brute exacte — seulement si pas déjà groupé au-dessus.
        // Comparaison exacte (pas de distance de Hamming/similarité floue) : ne détecte que les
        // copies identiques ou le même encodage, pas deux encodages différents du même morceau.
        Set<FileEntry> acoustIdGrouped = new HashSet<>(mbidGrouped);
        for (List<FileEntry> g : byAcoustId.values()) if (g.size() >= 2) acoustIdGrouped.addAll(g);
        for (FileEntry e : entries) {
            if (acoustIdGrouped.contains(e)) continue;
            TagInfo ti = e.activeTags();
            if (!ti.acoustidFingerprint.isBlank())
                byFingerprint.computeIfAbsent(ti.acoustidFingerprint, k -> new ArrayList<>()).add(e);
        }

        // 4. Artiste + titre normalisés — seulement si pas déjà groupé au-dessus
        Set<FileEntry> alreadyGrouped = new HashSet<>(acoustIdGrouped);
        for (List<FileEntry> g : byFingerprint.values()) if (g.size() >= 2) alreadyGrouped.addAll(g);
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
        for (List<FileEntry> g : byFingerprint.values())
            if (g.size() >= 2) groups.add(new DuplicateGroup(g, Confidence.FINGERPRINT_EXACT));
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
        if (sb.isEmpty()) sb.append(I18n.t("(titre inconnu)"));
        sb.append("  [").append(group.confidence().badge).append("]");
        return sb.toString();
    }

    /**
     * Score de qualité d'un fichier.
     * Plus le score est élevé, plus le fichier est de bonne qualité.
     * Critères : priorité format (FLAC > M4A > MP3…), puis débit binaire réel.
     */
    public static int qualityScore(FileEntry e) {
        File f = e.currentPath != null ? e.currentPath.toFile() : e.file;
        String extension = ext(f.getName()).toLowerCase();
        // ALAC (Apple Lossless) est TOUJOURS stocké avec l'extension .m4a sur disque — il n'existe
        // pas d'extension ".alac" en pratique, donc le cas "alac" ci-dessous ne pouvait jamais
        // matcher un vrai fichier. Un fichier ALAC sans perte et un fichier AAC avec perte
        // partageant la même extension .m4a recevaient donc le même score de format (700 000),
        // différenciés seulement par le débit — risque concret de recommander/pré-cocher pour
        // suppression le master sans perte au profit d'une copie compressée simplement plus
        // grosse en taille. Sondé via jaudiotagger (déjà utilisé partout ailleurs dans le projet)
        // pour lire le vrai type d'encodage ; repli silencieux sur le score .m4a par défaut si la
        // lecture échoue (fichier verrouillé/corrompu) ou si c'est bien de l'AAC.
        if ("m4a".equals(extension) && isActuallyAlac(f)) extension = "alac";
        int formatScore = switch (extension) {
            case "flac" -> 1_000_000;
            case "alac" -> 900_000;
            case "m4a"  -> 700_000;
            case "ogg"  -> 600_000;
            case "mp3"  -> 500_000;
            case "aac"  -> 400_000;
            case "wma"  -> 300_000;
            default     -> 100_000;
        };
        return formatScore + bitrateScore(e, f);
    }

    /**
     * Débit binaire estimé en kbps (taille × 8 / durée), plafonné à 999 pour ne pas dépasser le
     * budget du format ci-dessus. Remplace l'ancien proxy "taille de fichier / 1024 Ko, plafonné
     * à 999" : la quasi-totalité des MP3 réels (plusieurs Mo) saturait déjà ce plafond, rendant le
     * départage inopérant au-delà — et la taille brute confondait de toute façon durée et qualité
     * (un morceau plus long paraissait "meilleur" sans l'être). Repli sur l'ancien proxy si la
     * durée est inconnue (0) plutôt que de retourner un score nul qui écraserait tout classement.
     */
    private static int bitrateScore(FileEntry e, File f) {
        int durationSec = e.activeTags().durationSec;
        if (durationSec > 0) {
            long kbps = (f.length() * 8L) / 1024L / durationSec;
            return (int) Math.min(kbps, 999);
        }
        return (int) Math.min(f.length() / 1024, 999);
    }

    /** Fichier avec le meilleur score de qualité dans le groupe. */
    public static FileEntry bestInGroup(List<FileEntry> files) {
        return files.stream()
            .max(Comparator.comparingInt(DuplicateDetector::qualityScore))
            .orElse(files.get(0));
    }

    /** Précalcule bestInGroup() pour chaque groupe en une seule passe — à appeler UNIQUEMENT en
     *  arrière-plan (doInBackground d'un SwingWorker), jamais sur l'EDT : qualityScore() ouvre et
     *  parse chaque fichier .m4a via jaudiotagger (isActuallyAlac) pour départager ALAC/AAC, une
     *  vraie E/S disque par fichier. Sur une grosse bibliothèque avec beaucoup de groupes/M4A, ce
     *  calcul répété (une fois à l'ouverture du dialogue, une fois de plus à chaque clic sur
     *  "Sélection intelligente") gelait l'appli entière — retour utilisateur ("recherche audio en
     *  double [...] fige l'application"). Calculé une seule fois ici et réutilisé ensuite par
     *  DuplicatesDialog (buildGroup ET smartSelect) au lieu de rappeler bestInGroup(). */
    public static Map<DuplicateGroup, FileEntry> computeBestMap(List<DuplicateGroup> groups) {
        Map<DuplicateGroup, FileEntry> map = new LinkedHashMap<>();
        for (DuplicateGroup g : groups) map.put(g, bestInGroup(g.files()));
        return map;
    }

    private static boolean isActuallyAlac(File f) {
        try {
            org.jaudiotagger.audio.AudioFile af = org.jaudiotagger.audio.AudioFileIO.read(f);
            String encoding = af.getAudioHeader().getEncodingType();
            return encoding != null && encoding.toUpperCase().contains("ALAC");
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalize(String s) {
        // [^a-z0-9] ne matche que l'ASCII pur — un accent ("é") ne matche ni a-z ni 0-9, donc
        // [^a-z0-9] le désigne comme "à supprimer" et la lettre ENTIÈRE disparaît au lieu d'être
        // ramenée à sa forme sans accent : "Céline Dion" → "clinedion" mais "Celine Dion" →
        // "celinedion", deux chaînes différentes pour un doublon bien réel (variation
        // d'accentuation très courante : Céline Dion, Beyoncé, Mötley Crüe, Björk...), jamais
        // détecté par le niveau TITLE_HEURISTIC. Décomposition Unicode NFD (sépare la lettre de sa
        // marque d'accent) puis suppression des seules marques combinantes, en gardant la lettre
        // de base — "Céline"/"Celine" donnent maintenant tous deux "celine".
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        n = n.replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String ext(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot + 1) : "";
    }
}
