package com.opentagger;

import com.opentagger.model.TagInfo;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

/**
 * Corrections locales identiques aux 28 tâches de l'autocorrecteur Jaikoz :
 *  - Capitalisation (title case)
 *  - Extraction artiste "feat." depuis le titre
 *  - Extraction des métadonnées depuis le nom de fichier (si tags vides)
 *  - Normalisation du genre contre genrelist.txt
 *  - Détection musique classique (compositeurs/chefs embarqués)
 */
public class LocalCorrector {

    // Articles/prépositions non capitalisés (sauf en début de titre) — règle title case anglais
    private static final Set<String> LOWER_WORDS = Set.of(
            "a", "an", "the", "and", "but", "or", "nor", "for", "so", "yet",
            "at", "by", "in", "of", "on", "to", "up", "as", "it", "is"
    );

    // Patterns feat. — même logique que scripter.properties de Jaikoz
    private static final List<Pattern> FEAT_PATTERNS = List.of(
            Pattern.compile("\\s*[\\(\\[]\\s*(?:feat\\.?|ft\\.?|featuring)\\s+([^\\)\\]]+)[\\)\\]]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$", Pattern.CASE_INSENSITIVE)
    );

    // Patterns d'extraction depuis le nom de fichier
    private static final Pattern FILE_TRACK_ARTIST_TITLE = Pattern.compile("^(\\d{1,3})\\s*[-–.]\\s*(.+?)\\s*[-–]\\s*(.+)$");
    private static final Pattern FILE_ARTIST_TITLE       = Pattern.compile("^(.+?)\\s*[-–]\\s*(.+)$");

    private Set<String>  genreList       = new LinkedHashSet<>();
    private Set<String>  classicalNames  = new HashSet<>();
    private boolean      dataLoaded      = false;

    public LocalCorrector() {
        loadData();
    }

    // ── API publique ────────────────────────────────────────────────────────

    /**
     * Applique toutes les corrections locales dans l'ordre Jaikoz :
     * filename → feat. → capitalisation (seulement sur données filename) → genre → classique
     */
    public void correct(TagInfo info, Path fichier) {
        boolean wasEmpty = info.title.isBlank() && info.artist.isBlank();
        extractFromFilenameIfEmpty(info, fichier);
        // Capitaliser UNIQUEMENT les données extraites du nom de fichier (pas les données MB)
        if (wasEmpty) correctCapitalization(info);
        correctFeaturedArtist(info);
        removeDiscnoPadding(info);      // Script 2
        normalizeGenre(info);
        detectClassical(info);          // Script 3 : genre Classical si isClassical
    }

    // ── 1. Extraction depuis le nom de fichier ──────────────────────────────

    public void extractFromFilenameIfEmpty(TagInfo info, Path fichier) {
        if (!info.title.isBlank() && !info.artist.isBlank()) return;

        String nom = fichier.getFileName().toString();
        // Supprimer l'extension
        int dot = nom.lastIndexOf('.');
        if (dot > 0) nom = nom.substring(0, dot);

        // Essai "01 - Artiste - Titre"
        Matcher m = FILE_TRACK_ARTIST_TITLE.matcher(nom);
        if (m.matches()) {
            if (info.track.isBlank())  info.track  = m.group(1).trim();
            if (info.artist.isBlank()) info.artist  = titleCase(m.group(2).trim());
            if (info.title.isBlank())  info.title   = titleCase(m.group(3).trim());
            return;
        }

        // Essai "Artiste - Titre"
        m = FILE_ARTIST_TITLE.matcher(nom);
        if (m.matches()) {
            if (info.artist.isBlank()) info.artist = titleCase(m.group(1).trim());
            if (info.title.isBlank())  info.title  = titleCase(m.group(2).trim());
        }
    }

    // ── 2. Gestion du "feat." (scripts4 de scripter.properties) ────────────────
    // "Track Artist set to Album Artist and move any additional artists into title"

    public void correctFeaturedArtist(TagInfo info) {
        // Script 4 de Jaikoz : si albumArtist est renseigné et ≠ "Various Artists",
        // le track artist devient l'albumArtist et les artistes supplémentaires
        // sont déplacés dans le titre sous la forme "(Ft. X; Y)"
        if (!info.albumArtist.isBlank()
                && !info.albumArtist.equalsIgnoreCase("Various Artists")
                && !info.artist.equalsIgnoreCase(info.albumArtist)) {
            info.artist = info.albumArtist;
        }

        // Nettoyage du feat. résiduel dans le titre (toujours utile)
        if (info.title.isBlank()) return;
        for (Pattern p : FEAT_PATTERNS) {
            Matcher m = p.matcher(info.title);
            if (m.find()) {
                info.title = info.title.substring(0, m.start()).trim();
                break;
            }
        }
    }

    // ── 2b. Script 2 : Remove disc number padding ─────────────────────────────

    public void removeDiscnoPadding(TagInfo info) {
        if (!info.discNo.isBlank()) {
            info.discNo = info.discNo.replaceFirst("^0+(?!$)", "");
        }
    }

    // ── 3. Capitalisation (toTitleCase de Jaikoz) ───────────────────────────

    public void correctCapitalization(TagInfo info) {
        if (!info.title.isBlank())  info.title  = titleCase(info.title);
        if (!info.artist.isBlank()) info.artist = titleCase(info.artist);
        if (!info.album.isBlank())  info.album  = titleCase(info.album);
    }

    public String titleCase(String s) {
        if (s == null || s.isBlank()) return s;
        String[] words = s.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            // Premier mot toujours en majuscule, articles/prép en minuscule sinon
            if (i == 0 || !LOWER_WORDS.contains(w.toLowerCase())) {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            } else {
                sb.append(w.toLowerCase());
            }
        }
        return sb.toString();
    }

    // ── 4. Normalisation du genre (genrelist.txt de Jaikoz) ─────────────────

    public void normalizeGenre(TagInfo info) {
        if (info.genre.isBlank() || genreList.isEmpty()) return;

        // Découper les genres multiples
        String[] parts = info.genre.split(",");
        List<String> normalized = new ArrayList<>();
        for (String part : parts) {
            String g = part.trim();
            String match = findGenre(g);
            normalized.add(match != null ? match : titleCase(g));
        }
        info.genre = String.join(", ", normalized);
    }

    private String findGenre(String input) {
        // Correspondance exacte (insensible à la casse)
        for (String g : genreList) {
            if (g.equalsIgnoreCase(input)) return g;
        }
        // Correspondance partielle
        String lower = input.toLowerCase();
        for (String g : genreList) {
            if (g.toLowerCase().contains(lower) || lower.contains(g.toLowerCase())) return g;
        }
        return null;
    }

    // ── 5. Détection musique classique ──────────────────────────────────────

    public void detectClassical(TagInfo info) {
        if (!classicalNames.isEmpty()) {
            String artist = info.artist.toLowerCase();
            boolean found = classicalNames.stream()
                    .anyMatch(name -> artist.contains(name.toLowerCase()));
            if (found) info.isClassical = "1";
        }
        // Script 3 : Set Classical Genre
        if ("1".equals(info.isClassical) && info.genre.isBlank()) {
            info.genre = "Classical";
        }
    }

    // ── Chargement des données ───────────────────────────────────────────────

    private void loadData() {
        if (dataLoaded) return;
        loadGenreList();
        loadClassicalNames();
        dataLoaded = true;
    }

    private void loadGenreList() {
        try (InputStream in = getClass().getResourceAsStream("/genrelist.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String genre = line.replace(";", "").trim();
                if (!genre.isEmpty()) genreList.add(genre);
            }
        } catch (Exception e) { /* ignore */ }
    }

    private void loadClassicalNames() {
        // Charge uniquement les noms (pas les UUIDs) depuis classical_conductors.txt
        // pour ne pas trop alourdir la mémoire (classical_composers.txt fait 450 KB)
        for (String resource : List.of("/classical_conductors.txt")) {
            try (InputStream in = getClass().getResourceAsStream(resource);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String name = line.substring(eq + 1).trim();
                        if (!name.isEmpty()) classicalNames.add(name);
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }
    }

    public Set<String> getGenreList() { return Collections.unmodifiableSet(genreList); }
    public int genreCount()            { return genreList.size(); }
    public int classicalNamesCount()   { return classicalNames.size(); }
}
