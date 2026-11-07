package com.opentagger;

import com.opentagger.model.TagInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ce qu'un enregistrement va changer dans un fichier, champ par champ (« avant → après ») — pour MONTRER les changements
 * prévus avant d'écrire (demande de l'utilisateur, 2026-11-07 : comme Bliss, qui constate puis corrige). Calcul pur, sans
 * disque : « avant » = tags lus au scan, « après » = ce que TagWriter écrira, avec la même règle de fusion
 * (tags.clear_existing_tags, et rien de l'ancienne identité quand c'est un autre morceau — voir SameSong).
 * L'affichage est dans ui.SavePreviewDialog.
 */
public final class SavePreview {

    private SavePreview() {}

    /** Un champ qui change. {@code identity} : titre ou artiste (le fichier devient un autre morceau si l'un change beaucoup). */
    public record Change(String field, String label, String before, String after, boolean identity) {}

    /** Champs internes, jamais écrits tels quels ou sans intérêt pour la relecture. */
    private static final Set<String> SKIP = Set.of("identificationSource", "shazamCoverUrl", "coverInfo", "syncedLyrics",
            "taggedDate", "acoustidFingerprint", "dateForWrite");
    /** Mêmes champs « du son » que TagWriter.SOUND_FIELDS/SOUND_PROPS : gardés même quand le morceau change. */
    private static final Set<String> SOUND = Set.of("bpm", "fbpm", "initialKey", "rating", "comment", "acoustidId",
            "acoustidFingerprint", "replayGainTrackGain", "replayGainTrackPeak", "isHD");
    private static final Set<String> FLAGS = Set.of("isClassical", "isCompilation", "isHD", "isLive", "isGreatestHits",
            "isSoundtrack", "isInstrumental");

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        String[][] l = {
            {"title", "Titre"}, {"artist", "Artiste"}, {"album", "Album"}, {"albumArtist", "Artiste de l'album"},
            {"year", "Année"}, {"date", "Date"}, {"originalYear", "Année d'origine"}, {"originalDate", "Date d'origine"},
            {"genre", "Genre"}, {"track", "N° de piste"}, {"trackTotal", "Nombre de pistes"}, {"discNo", "Disque"},
            {"discTotal", "Nombre de disques"}, {"composer", "Compositeur"}, {"lyrics", "Paroles"}, {"comment", "Commentaire"},
            {"isrc", "ISRC"}, {"label", "Label"}, {"catalogNo", "N° de catalogue"}, {"barcode", "Code-barres"},
            {"country", "Pays"}, {"releaseType", "Type de parution"}, {"releaseStatus", "Statut de parution"},
            {"media", "Support"}, {"bpm", "BPM"}, {"initialKey", "Tonalité"}, {"mood", "Humeur"}, {"tags", "Mots-clés"},
            {"rating", "Note"}, {"artistSort", "Tri de l'artiste"}, {"albumArtistSort", "Tri de l'artiste de l'album"},
            {"titleSort", "Tri du titre"}, {"albumSort", "Tri de l'album"}, {"artists", "Artistes"},
            {"recordingMbid", "ID MusicBrainz du morceau"}, {"releaseMbid", "ID MusicBrainz de l'album"},
            {"releaseGroupMbid", "ID MusicBrainz du groupe de parutions"}, {"artistMbid", "ID MusicBrainz de l'artiste"},
            {"albumArtistMbid", "ID MusicBrainz de l'artiste de l'album"}, {"releaseTrackMbid", "ID MusicBrainz de la piste"},
            {"isCompilation", "Compilation"}, {"isLive", "En public"}, {"isSoundtrack", "Bande originale"},
            {"replayGainTrackGain", "ReplayGain (piste)"}, {"replayGainAlbumGain", "ReplayGain (album)"},
            {"discSubtitle", "Sous-titre du disque"}, {"script", "Écriture"}, {"language", "Langue"},
        };
        for (String[] p : l) LABELS.put(p[0], p[1]);
    }

    /** Libellé français d'un champ (celui de la table des champs si elle en a un, sinon son nom). */
    public static String label(String field) {
        String l = LABELS.get(field);
        if (l != null) return l;
        for (TagFieldRegistry.Entry e : TagFieldRegistry.ALL)
            if (e.prop().equals(field) && e.label() != null) return e.label().replaceAll("\\s*:\\s*$", "");
        return field;
    }

    /** Ce qui sera réellement écrit : {@code planned} seul si les anciens tags sont effacés, sinon fusion « l'ancien seulement
     *  si le nouveau est vide » — sauf pour un autre morceau, où seuls les champs du son restent. */
    public static TagInfo effective(TagInfo before, TagInfo planned, boolean clearExisting) {
        if (planned == null) return null;
        if (before == null || clearExisting) return planned;
        TagInfo m = planned.copy();
        boolean other = SameSong.otherSong(before.artist, before.title, planned.artist, planned.title);
        for (Field f : stringFields()) {
            if (other && !SOUND.contains(f.getName())) continue;
            String nu = get(f, m), old = get(f, before);
            if (blank(nu) && !blank(old)) set(f, m, old);
        }
        return m;
    }

    /** Les champs qui changent entre {@code before} (tags actuels) et ce qui sera écrit. Liste vide = rien ne change. */
    public static List<Change> changes(TagInfo before, TagInfo planned, boolean clearExisting) {
        List<Change> out = new ArrayList<>();
        TagInfo after = effective(before, planned, clearExisting);
        if (after == null) return out;
        for (Field f : stringFields()) {
            String name = f.getName();
            if (SKIP.contains(name)) continue;
            String a = before == null ? "" : norm(name, get(f, before)), b = norm(name, get(f, after));
            if (a.equals(b)) continue;
            out.add(new Change(name, label(name), a, b, name.equals("title") || name.equals("artist")));
        }
        return out;
    }

    /** Le fichier deviendrait-il un AUTRE morceau (artiste ou titre sans rapport) ? */
    public static boolean otherSong(TagInfo before, TagInfo planned) {
        return before != null && planned != null && SameSong.otherSong(before.artist, before.title, planned.artist, planned.title);
    }

    // ── outils ─────────────────────────────────────────────────────────────────────────────────────

    private static List<Field> FIELDS;

    private static synchronized List<Field> stringFields() {
        if (FIELDS == null) {
            List<Field> fs = new ArrayList<>();
            for (Field f : TagInfo.class.getFields())
                if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) fs.add(f);
            FIELDS = fs;
        }
        return FIELDS;
    }

    /** Valeur comparable : espaces en trop retirés, « 0 » = vide pour les drapeaux, « 3/12 » = « 3 » pour les numéros. */
    private static String norm(String field, String v) {
        String s = v == null ? "" : v.trim();
        if (FLAGS.contains(field)) return "1".equals(s) || "true".equalsIgnoreCase(s) ? "1" : "";
        if ((field.equals("track") || field.equals("discNo")) && s.contains("/")) s = s.substring(0, s.indexOf('/')).trim();
        if ((field.equals("track") || field.equals("discNo")) && s.matches("0+\\d+")) s = s.replaceFirst("^0+", "");
        return s;
    }

    private static String get(Field f, TagInfo t) {
        try { Object o = f.get(t); return o == null ? "" : (String) o; } catch (IllegalAccessException e) { return ""; }
    }

    private static void set(Field f, TagInfo t, String v) {
        try { f.set(t, v); } catch (IllegalAccessException ignored) {}
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
