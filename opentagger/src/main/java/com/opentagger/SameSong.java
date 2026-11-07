package com.opentagger;

import com.opentagger.model.TagInfo;

/**
 * « Est-ce bien la même chanson ? » — une seule règle pour tous les endroits qui RECOPIENT dans un fichier des champs
 * venus d'ailleurs (résultat de recherche, ancien tag du fichier).
 *
 * <p>Créée le 2026-11-07 après l'enquête sur les fichiers « à deux chansons » de la bibliothèque : par exemple
 * « Taylor Swift – Style » (titre, artiste, album justes) qui portait l'artiste de l'album « 2pac », le tri « 2Pac »,
 * le numéro de piste 14/17 et les paroles de « Thug Style » de 2Pac. Deux façons d'en arriver là :
 * <ul>
 *   <li>une recherche texte MusicBrainz dont on prenait le premier résultat sur la seule note de MusicBrainz — note
 *       RELATIVE, presque toujours 100 pour le premier résultat même quand il s'agit d'un autre morceau — puis dont
 *       on recopiait ~25 champs vides (voir InfoCompleterWorker) ;</li>
 *   <li>une ré-identification qui ne réécrit que l'artiste et le titre : tout le reste de l'ancienne identité
 *       (artiste de l'album, tris, paroles, ISRC, identifiants, numéros…) reste dans le fichier (voir
 *       TagWriter.mergeWithExisting).</li>
 * </ul>
 */
public final class SameSong {

    private SameSong() {}

    /** Seuils pour ACCEPTER un résultat trouvé par le texte : même artiste ET même titre. « Style » contre
     *  « Thug Style » vaut 0,71 : refusé. Les variantes « (Remastered) », « - Live »… valent 1 (voir
     *  AudioTagAudit.titleSimilarity). */
    static final double MATCH_ARTIST = 0.75;
    static final double MATCH_TITLE  = 0.80;

    /** En dessous de ces seuils, les nouveaux tags décrivent un AUTRE morceau que les anciens. */
    static final double CHANGE_ARTIST = 0.60;
    static final double CHANGE_TITLE  = 0.60;

    /** Artiste et titre exploitables (ni vides ni génériques) : sans eux, rien ne peut être vérifié. */
    public static boolean verifiable(String artist, String title) {
        return usableArtist(artist) && usableTitle(title);
    }

    /** Le résultat trouvé (candArtist, candTitle) est-il bien le morceau que le fichier dit être ? Faux dès que le
     *  fichier ne dit pas clairement qui il est : un résultat qu'on ne peut pas vérifier n'est jamais recopié. */
    public static boolean matches(String fileArtist, String fileTitle, String candArtist, String candTitle) {
        if (!verifiable(fileArtist, fileTitle) || !verifiable(candArtist, candTitle)) return false;
        return AudioTagAudit.artistSimilarity(fileArtist, candArtist) >= MATCH_ARTIST
            && AudioTagAudit.titleSimilarity(fileTitle, candTitle) >= MATCH_TITLE;
    }

    /** Les nouveaux tags décrivent-ils un AUTRE morceau que les anciens ? Chaque critère (artiste, titre) ne compte
     *  que si les deux côtés sont exploitables ; s'il n'y a rien à comparer, réponse non (on ne jette rien). */
    public static boolean otherSong(String oldArtist, String oldTitle, String newArtist, String newTitle) {
        if (usableArtist(oldArtist) && usableArtist(newArtist)
                && AudioTagAudit.artistSimilarity(oldArtist, newArtist) < CHANGE_ARTIST) return true;
        return usableTitle(oldTitle) && usableTitle(newTitle)
                && AudioTagAudit.titleSimilarity(oldTitle, newTitle) < CHANGE_TITLE;
    }

    private static boolean usableArtist(String s) {
        return s != null && !s.isBlank() && !TagInfo.isGenericIdentityValue(s) && !AudioTagAudit.isGenericArtist(s);
    }

    private static boolean usableTitle(String s) {
        return s != null && !s.isBlank() && !TagInfo.isGenericIdentityValue(s);
    }
}
