package com.opentagger;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Logique PURE (sans iTunes, sans Swing) du « Rapport iTunes » : lecture du flux produit par le
 * script de scan (voir {@link ITunesCom}) et comparaison avec les fichiers chargés dans OpenTagger.
 * Séparée pour être testable sans iTunes. Strictement en lecture : rien ici n'écrit nulle part.
 */
public final class ITunesReport {

    private ITunesReport() {}

    /** Piste iTunes de type « fichier » dont le fichier n'est plus là (chemin éventuellement vide :
     *  iTunes ne renvoie plus l'emplacement d'une piste dont le fichier a disparu). */
    public record DeadTrack(String artist, String title, String album, String location) {}

    public record ScanResult(int totalTracks, int fileTracks, int errors,
                             Set<String> locations, List<DeadTrack> dead, List<String> diagnostics) {}

    /** Forme comparable d'un chemin Windows : absolu, normalisé, insensible à la casse. */
    public static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim();
        if (p.isEmpty()) return "";
        try {
            return Paths.get(p).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        } catch (InvalidPathException e) {
            return p.toLowerCase(Locale.ROOT);
        }
    }

    /** Fichiers {@code loaded} dont le chemin n'apparaît dans aucune piste iTunes. */
    public static List<Path> notInITunes(Collection<Path> loaded, Set<String> itunesLocationsNormalized) {
        List<Path> out = new ArrayList<>();
        for (Path p : loaded) {
            if (p == null) continue;
            if (!itunesLocationsNormalized.contains(normalize(p.toString()))) out.add(p);
        }
        return out;
    }

    /** Accumule les lignes du flux du script : {@code N<TAB>total}, {@code L<TAB>chemin},
     *  {@code D<TAB>chemin<TAB>artiste<TAB>titre<TAB>album}, {@code E<TAB>n°<TAB>message},
     *  {@code P<TAB>i<TAB>n} (progression), {@code DONE}. Toute autre ligne est gardée comme
     *  diagnostic (les premières seulement). */
    public static final class Builder {
        private int total, fileTracks, errors;
        private final Set<String> locations = new HashSet<>();
        private final List<DeadTrack> dead = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();
        private boolean done;

        /** @return {@code {i, n}} si la ligne est une progression, sinon {@code null}. */
        public int[] accept(String line) {
            if (line == null || line.isEmpty()) return null;
            String[] f = line.split("\t", -1);
            switch (f[0]) {
                case "N" -> { total = parseInt(f, 1); }
                case "L" -> {
                    fileTracks++;
                    if (f.length > 1 && !f[1].isEmpty()) locations.add(normalize(f[1]));
                }
                case "D" -> dead.add(new DeadTrack(at(f, 2), at(f, 3), at(f, 4), at(f, 1)));
                case "E" -> { errors++; if (diagnostics.size() < 5) diagnostics.add("piste " + at(f, 1) + " : " + at(f, 2)); }
                case "P" -> { return new int[]{parseInt(f, 1), parseInt(f, 2)}; }
                case "DONE" -> done = true;
                default -> { if (diagnostics.size() < 5) diagnostics.add(line); }
            }
            return null;
        }

        public boolean isDone() { return done; }

        public ScanResult build() {
            return new ScanResult(total, fileTracks, errors, locations, dead, diagnostics);
        }

        private static String at(String[] f, int i) { return i < f.length ? f[i] : ""; }
        private static int parseInt(String[] f, int i) {
            try { return Integer.parseInt(at(f, i).trim()); } catch (NumberFormatException e) { return 0; }
        }
    }
}
