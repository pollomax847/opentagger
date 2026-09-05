package com.opentagger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Écriture CHIRURGICALE, ligne par ligne, dans "iTunes Music Library.xml" — voir
 * ITunesLibraryImporter (lecture) pour le contexte. Demande explicite utilisateur (2026-08-16)
 * après mise en garde sur le risque réel (iTunes régénère ce fichier depuis sa vraie base .itl —
 * une réécriture DOM complète ou une désynchronisation pourrait être silencieusement perdue ou
 * corrompre la référence).
 *
 * JAMAIS de ré-analyse/réécriture complète du document : chaque ligne est recopiée à l'IDENTIQUE
 * sauf les lignes Location/Rating d'une piste présente dans le lot de changements — la structure
 * et le formatage du reste du fichier (des centaines de Mo, des dizaines de milliers de pistes non
 * concernées) restent rigoureusement inchangés, fins de ligne CRLF d'origine préservées.
 *
 * Garanties avant toute écriture réelle :
 *  1. Sauvegarde horodatée du fichier ORIGINAL, créée avant même d'ouvrir le fichier temporaire —
 *     même convention que ~/Documents/Projets/Modif xml itunes/ (script Python déjà utilisé par
 *     l'utilisateur pour ce même fichier).
 *  2. Écriture dans un fichier temporaire, jamais directement sur l'original.
 *  3. Chaque changement de Location est vérifié par ALLER-RETOUR
 *     (resolveLocalPath(encodeLocation(p)) == p) AVANT d'être appliqué — un changement qui ne
 *     repasse pas ce test est ignoré (jamais écrit), voir Result.skippedUnverified.
 *  4. Remplacement de l'original par le temporaire UNIQUEMENT via Files.move ATOMIC_MOVE, et
 *     UNIQUEMENT si le nombre de lignes lues == nombre de lignes écrites (aucune ligne perdue/
 *     ajoutée par erreur de logique).
 *  5. Ne modifie QUE des lignes Rating/Location déjà PRÉSENTES pour une piste — n'insère jamais
 *     une nouvelle ligne dans un dict qui n'a pas encore de champ Rating (structure du dict non
 *     modifiée, uniquement le contenu d'une ligne déjà existante).
 */
public final class ITunesXmlWriter {

    private ITunesXmlWriter() {}

    /** {@code newRatingStars} : 1-5, {@code null} = pas de changement pour ce champ. */
    public record PendingChange(Path newLocation, Integer newRatingStars) {}

    public record Result(int locationChanges, int ratingChanges, int skippedUnverified, File backupFile) {}

    private static final Pattern TRACK_ID_LINE =
            Pattern.compile("^\\s*<key>Track ID</key><integer>(\\d+)</integer>\\s*$");
    private static final Pattern LOCATION_LINE =
            Pattern.compile("^(\\s*<key>Location</key><string>).*(</string>)\\s*$");
    private static final Pattern RATING_LINE =
            Pattern.compile("^(\\s*<key>Rating</key><integer>)\\d+(</integer>)\\s*$");

    public static Result apply(File xmlFile, Map<Integer, PendingChange> changesByTrackId) throws IOException {
        if (changesByTrackId.isEmpty()) return new Result(0, 0, 0, null);

        // Sans préfixe configuré, encodeLocation() renverrait le chemin Linux tel quel (ex.
        // "file://localhost//mnt/Music/…") — un aller-retour OpenTagger→OpenTagger cohérent (voir
        // le test), mais une Location inutilisable par le VRAI iTunes côté Windows, qui ne connaît
        // rien de "/mnt/Music". Toute correction de chemin est donc bloquée tant que
        // itunes.xml_path_from/to ne sont pas renseignés — jamais de Location "correcte pour nous
        // mais fausse pour iTunes" écrite silencieusement.
        boolean pathPrefixConfigured = !Config.get().itunesXmlPathFrom().isBlank()
                                     && !Config.get().itunesXmlPathTo().isBlank();

        String ts = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now());
        File backup = new File(xmlFile.getParentFile(), xmlFile.getName() + ".backup_" + ts);
        Files.copy(xmlFile.toPath(), backup.toPath());

        File tmp = new File(xmlFile.getParentFile(), xmlFile.getName() + ".opentagger_tmp");
        int locationChanges = 0, ratingChanges = 0, skipped = 0;
        long linesIn = 0, linesOut = 0;
        int currentTrackId = -1;

        try (BufferedReader r = Files.newBufferedReader(xmlFile.toPath(), StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                linesIn++;
                Matcher tid = TRACK_ID_LINE.matcher(line);
                if (tid.matches()) currentTrackId = Integer.parseInt(tid.group(1));

                PendingChange pc = changesByTrackId.get(currentTrackId);
                if (pc != null) {
                    Matcher loc = LOCATION_LINE.matcher(line);
                    if (loc.matches() && pc.newLocation() != null) {
                        if (!pathPrefixConfigured) {
                            skipped++; // voir garde ci-dessus — jamais de Location non pertinente pour iTunes
                        } else {
                            String rawLocation = ITunesLibraryImporter.encodeLocation(pc.newLocation());
                            Path verify = ITunesLibraryImporter.resolveLocalPath(rawLocation);
                            if (pc.newLocation().toAbsolutePath().normalize().equals(verify)) {
                                line = loc.group(1) + xmlEscape(rawLocation) + loc.group(2);
                                locationChanges++;
                            } else {
                                skipped++; // aller-retour non vérifié — ligne laissée intacte
                            }
                        }
                    }
                    Matcher rat = RATING_LINE.matcher(line);
                    if (rat.matches() && pc.newRatingStars() != null) {
                        int itunesRating = Math.max(0, Math.min(5, pc.newRatingStars())) * 20;
                        line = rat.group(1) + itunesRating + rat.group(2);
                        ratingChanges++;
                    }
                }

                // CRLF explicite — le fichier réel de l'utilisateur en est entièrement composé
                // (généré côté Windows/iTunes) ; BufferedReader.readLine() les a déjà retirés, un
                // simple '\n' en sortie changerait la terminaison de ligne de TOUT le fichier.
                w.write(line);
                w.write("\r\n");
                linesOut++;
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }

        if (linesIn != linesOut) {
            Files.deleteIfExists(tmp.toPath());
            throw new IOException("Nombre de lignes incohérent (" + linesIn + " lues, " + linesOut
                    + " écrites) — écriture annulée, fichier original intact, sauvegarde conservée : " + backup);
        }

        Files.move(tmp.toPath(), xmlFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Result(locationChanges, ratingChanges, skipped, backup);
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
