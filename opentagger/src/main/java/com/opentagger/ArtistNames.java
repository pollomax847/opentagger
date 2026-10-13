package com.opentagger;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Noms d'artistes à corriger AVANT toute écriture de tags (2026-10-13). Trouvé dans la bibliothèque, tout écrit par
 * OpenTagger lui-même : 266 fichiers avec un nom de chaîne YouTube comme artiste (« AmerieVEVO » ×63, « Kyo - Topic »
 * ×45…), et 2 402 pistes de mix DJ / compilations où le DJ remplaçait le vrai interprète (« 02 - Alphaville - Big in
 * Japan » tagué artiste « Aeroplane », A State of Trance d'Armin van Buuren…). Le nettoyage « - Topic » existait déjà
 * dans TaggingWorker, mais seulement sur le chemin d'identification principal — appliqué ici au point d'écriture
 * unique (TagWriter), il couvre aussi les repli « tags existants », la passe complète et les éditions par lot.
 */
public final class ArtistNames {
    private ArtistNames() {}

    /** « Kyo - Topic » (chaîne auto-générée) et « AmerieVEVO » / « Amerie VEVO » (chaîne officielle) → l'artiste. */
    private static final Pattern YOUTUBE_SUFFIX = Pattern.compile("(?i)(\\s*-\\s*topic|(?<=[\\p{L}\\p{N}])\\s?vevo)$");

    public static String stripYoutubeChannel(String artist) {
        if (artist == null) return null;
        String s = YOUTUBE_SUFFIX.matcher(artist.trim()).replaceAll("").trim();
        return s.isEmpty() ? artist : s;
    }

    /** « 02 - Alphaville - Big in Japan » → interprète « Alphaville », titre « Big in Japan ». */
    private static final Pattern NUMBERED_PERFORMER = Pattern.compile("^\\s*\\d{1,3}\\s*[-.]\\s*(.+?)\\s+-\\s+(.+)$");

    /**
     * Piste de compilation / mix DJ taguée avec l'artiste de l'ALBUM (le DJ) alors que le nom du fichier donne un autre
     * interprète : rend l'interprète du nom de fichier comme artiste de la piste (l'artiste de l'album reste le DJ).
     * Seulement si le titre du nom de fichier est bien celui du tag (sinon le nom de fichier ne prouve rien).
     * @return true si l'artiste a été corrigé
     */
    public static boolean restorePerformerFromFileName(TagInfo t, File file) {
        if (t == null || file == null || t.artist == null || t.artist.isBlank()) return false;
        if (t.albumArtist == null || t.albumArtist.isBlank()) return false;
        if (!key(t.artist).equals(key(t.albumArtist)) || isVarious(t.albumArtist)) return false;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        Matcher m = NUMBERED_PERFORMER.matcher(dot > 0 ? name.substring(0, dot) : name);
        if (!m.matches()) return false;
        String performer = m.group(1).trim().replace('_', ' ');
        String fileTitle = m.group(2).replaceAll("\\s*\\(\\d+\\)$", "").trim();
        String kp = key(performer), ka = key(t.artist);
        if (kp.isEmpty() || kp.equals(ka) || kp.contains(ka) || ka.contains(kp) || isVarious(performer)) return false;
        if (t.title == null || t.title.isBlank()) return false;
        if (TrackMatcher.titleSimilarity(fold(fileTitle), fold(t.title)) < 0.8) return false;
        t.artist = performer;
        if (t.artists != null && key(t.artists).equals(ka)) t.artists = performer;
        return true;
    }

    /** Toutes les corrections d'artiste, à appeler juste avant d'écrire les tags d'un fichier. */
    public static void clean(TagInfo t, File file) {
        if (t == null) return;
        t.artist = stripYoutubeChannel(t.artist);
        t.albumArtist = stripYoutubeChannel(t.albumArtist);
        if (t.artists != null) t.artists = stripYoutubeChannel(t.artists);
        boolean compilation = "1".equals(t.isCompilation) || looksLikeCompilationFolder(file);
        if (compilation) restorePerformerFromFileName(t, file);
    }

    /** Dossier « Compilations/… » ou dossier dont le nom de fichier porte l'interprète (format compilation). */
    static boolean looksLikeCompilationFolder(File file) {
        if (file == null) return false;
        for (File d = file.getParentFile(); d != null; d = d.getParentFile()) {
            if (d.getName().equalsIgnoreCase("Compilations")) return true;
        }
        return false;
    }

    private static boolean isVarious(String s) {
        String k = key(s);
        return k.equals("variousartists") || k.equals("various") || k.equals("artistesdivers") || k.equals("va");
    }

    private static String fold(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    private static String key(String s) { return fold(s).replaceAll("[^a-z0-9]", ""); }
}
