package com.opentagger;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lecture de "iTunes Music Library.xml" (le parcours de CETTE classe est en lecture seule — voir
 * ITunesXmlWriter pour l'écriture, volontairement séparée). Objectif principal : récupérer
 * Rating/Play Count/Skip Count/Play Date, des données qui n'existent QUE dans cet export, jamais
 * dans les tags audio eux-mêmes — contrairement au chemin des fichiers, pour lequel l'utilisateur
 * dispose déjà d'un outillage Python dédié
 * (~/Documents/Projets/Modif xml itunes/itunes_path_updater.py), donc pas dupliqué ici.
 *
 * Constat réel (2026-08-16) : sur le fichier réel de l'utilisateur (157 Mo, 402k entrées
 * "Track ID"), la majorité sont des pistes iCloud "Track Type: Remote" sans Location locale — le
 * parseur les ignore silencieusement (ni Rating ni Play Count ni Location exploitable).
 */
public final class ITunesLibraryImporter {

    private ITunesLibraryImporter() {}

    public record ITunesTrack(int trackId, String location, String name, String artist, String album,
                               int rating, int playCount, int skipCount, String playDateUtc) {}

    /**
     * Parcourt le dict "Tracks" en streaming (StAX, pas de DOM complet — inutilement coûteux en
     * mémoire pour n'extraire que quelques champs d'un fichier de cette taille). Ne retient que
     * les pistes ayant une Location ET (Rating>0 OU Play Count>0) — tout le reste (métadonnées
     * déjà couvertes par ailleurs : Name/Artist/Album/Genre…) est ignoré, ce module ne sert QUE
     * l'import de données introuvables ailleurs.
     */
    public static List<ITunesTrack> parse(File xmlFile) throws Exception {
        List<ITunesTrack> out = new ArrayList<>();
        XMLInputFactory factory = XMLInputFactory.newInstance();
        // Sécurité XXE — fichier local de confiance mais bonne pratique systématique.
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

        try (InputStream is = new BufferedInputStream(new FileInputStream(xmlFile), 1 << 20)) {
            XMLStreamReader r = factory.createXMLStreamReader(is);

            int dictDepth = 0;
            int tracksContainerDepth = -1; // profondeur du dict conteneur "Tracks", -1 si hors de sa portée
            boolean justSawTracksKey = false;
            String currentKey = null;

            String location = null, name = "", artist = "", album = "", playDateUtc = "";
            int rating = 0, playCount = 0, skipCount = 0, trackId = 0;

            while (r.hasNext()) {
                int ev = r.next();

                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String local = r.getLocalName();

                    if (local.equals("key")) {
                        currentKey = r.getElementText();
                        justSawTracksKey = "Tracks".equals(currentKey) && tracksContainerDepth < 0;
                        continue;
                    }

                    if (local.equals("dict")) {
                        dictDepth++;
                        if (justSawTracksKey) {
                            tracksContainerDepth = dictDepth; // dict conteneur "Tracks" lui-même
                            justSawTracksKey = false;
                        } else if (tracksContainerDepth > 0 && dictDepth == tracksContainerDepth + 1) {
                            // Entrée dans le dict d'UNE piste (Tracks -> dict -> <TrackID> -> dict)
                            location = null; name = ""; artist = ""; album = ""; playDateUtc = "";
                            rating = 0; playCount = 0; skipCount = 0; trackId = 0;
                        }
                        continue;
                    }

                    // Uniquement à l'intérieur d'un dict de piste — jamais ailleurs (Playlists,
                    // en-tête de bibliothèque…), même clé "Location"/"Rating" possible ailleurs
                    // sans rapport avec une piste.
                    if (tracksContainerDepth > 0 && dictDepth == tracksContainerDepth + 1 && currentKey != null) {
                        switch (currentKey) {
                            case "Track ID"       -> trackId     = parseIntSafe(r.getElementText());
                            case "Location"      -> location    = r.getElementText();
                            case "Name"           -> name        = r.getElementText();
                            case "Artist"         -> artist      = r.getElementText();
                            case "Album"          -> album       = r.getElementText();
                            case "Rating"         -> rating      = parseIntSafe(r.getElementText());
                            case "Play Count"     -> playCount   = parseIntSafe(r.getElementText());
                            case "Skip Count"     -> skipCount   = parseIntSafe(r.getElementText());
                            case "Play Date UTC"  -> playDateUtc = r.getElementText();
                            default -> {}
                        }
                    }

                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("dict")) {
                        if (tracksContainerDepth > 0 && dictDepth == tracksContainerDepth + 1) {
                            // Toute piste avec une Location LOCALE (pas seulement celles déjà notées/
                            // écoutées) — nécessaire pour que la correction de chemin (voir
                            // ITunesXmlWriter) puisse aussi cibler un fichier pas encore noté. Exclut
                            // simplement la masse des pistes iCloud "Track Type: Remote" sans Location,
                            // qui restent la grande majorité des 402k entrées réelles observées.
                            if (location != null) {
                                out.add(new ITunesTrack(trackId, location, name, artist, album,
                                        rating, playCount, skipCount, playDateUtc));
                            }
                        }
                        dictDepth--;
                        if (tracksContainerDepth > 0 && dictDepth < tracksContainerDepth) {
                            tracksContainerDepth = -1; // sorti du dict "Tracks" (ex: vers Playlists)
                        }
                    }
                }
            }
            r.close();
        }
        return out;
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /**
     * Chemin réel probable sur disque depuis une Location plist (décodage pourcentage +
     * substitution de préfixe configurable — Config.itunesXmlPathFrom/To, ex.
     * "C:/Users/xxx/OneDrive/Musiques" → "/mnt/Music"). Approximatif par nature (mêmes limites
     * que le script Python déjà utilisé par l'utilisateur pour ce problème) : l'appelant doit
     * vérifier Files.exists() et prévoir un repli par nom de fichier si besoin.
     */
    public static Path resolveLocalPath(String location) {
        if (location == null || location.isBlank()) return null;
        String s = location;
        if (s.startsWith("file://localhost/")) s = s.substring("file://localhost/".length());
        else if (s.startsWith("file:///"))      s = s.substring("file:///".length());
        else if (s.startsWith("file://"))       s = s.substring("file://".length());
        try { s = URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (Exception ignored) {}

        String from = Config.get().itunesXmlPathFrom();
        String to   = Config.get().itunesXmlPathTo();
        if (!from.isBlank() && s.startsWith(from)) s = to + s.substring(from.length());

        return Paths.get(s);
    }

    /**
     * Résolution avec repli par nom de fichier — constat réel (2026-08-16, bibliothèque de
     * l'utilisateur) : la substitution de préfixe seule ne retrouve qu'une partie des fichiers
     * (dossiers renommés/réorganisés depuis l'export XML) ; {@code byFilename} (nom de fichier en
     * minuscules → tous les chemins connus portant ce nom, typiquement construit depuis
     * MetadataCache.loadScanCacheMap() pour couvrir TOUTE la bibliothèque déjà scannée, pas
     * seulement les fichiers actuellement chargés dans le tableau) permet de retrouver un fichier
     * déplacé — mais UNIQUEMENT si son nom est sans ambiguïté (un seul candidat) : jamais de
     * devinette parmi plusieurs fichiers de même nom, voir la préférence utilisateur "non-
     * destructif sur verdict incertain".
     */
    public static Path resolve(ITunesTrack track, Map<String, List<Path>> byFilename) {
        Path direct = resolveLocalPath(track.location());
        if (direct == null) return null;
        if (Files.exists(direct)) return direct;

        List<Path> candidates = byFilename.get(direct.getFileName().toString().toLowerCase(Locale.ROOT));
        return (candidates != null && candidates.size() == 1) ? candidates.get(0) : null;
    }

    /**
     * Sens inverse de {@link #resolveLocalPath} — reconstruit une valeur "Location" plist depuis
     * un chemin réel sur ce système, pour ITunesXmlWriter (correction de chemin après renommage
     * OpenTagger). Applique la substitution de préfixe TO→FROM (inverse de l'import), ré-encode en
     * pourcentage MINIMALEMENT (espace/crochets/non-ASCII — exactement ce qu'on observe encodé
     * dans les vraies Location du fichier réel de l'utilisateur ; parenthèses/apostrophes/deux-
     * points restent littéraux, comme Apple les écrit). Volontairement PAS de java.net.URLEncoder
     * (encode trop de caractères — parenthèses, apostrophes — que le vrai fichier laisse
     * littéraux ; produirait une Location non représentative même si techniquement décodable).
     * Ne gère PAS l'échappement XML (&amp;/&lt;/&gt;) : cette valeur doit encore être échappée par
     * l'appelant avant insertion dans le texte XML — deux couches distinctes, voir ITunesXmlWriter.
     *
     * SÉCURITÉ : l'appelant DOIT vérifier {@code resolveLocalPath(encodeLocation(p)).equals(p)}
     * avant d'écrire quoi que ce soit — le vrai critère de correction n'est pas de reproduire
     * Apple à l'octet près, mais que la valeur produite se redécode exactement vers le même
     * chemin (voir ITunesXmlWriter.apply()).
     */
    public static String encodeLocation(Path linuxPath) {
        String s = linuxPath.toAbsolutePath().normalize().toString();
        String from = Config.get().itunesXmlPathFrom();
        String to   = Config.get().itunesXmlPathTo();
        if (!to.isBlank() && s.startsWith(to)) s = from + s.substring(to.length());

        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder enc = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c == ' ') { enc.append("%20"); continue; }
            if (c == '[') { enc.append("%5B"); continue; }
            if (c == ']') { enc.append("%5D"); continue; }
            if (c < 0x80) { enc.append((char) c); continue; } // ASCII imprimable — littéral
            enc.append('%').append(String.format("%02X", c)); // octet UTF-8 non-ASCII
        }
        return "file://localhost/" + enc;
    }
}
