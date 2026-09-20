package com.opentagger;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Reconstruction COMPLÈTE d'un export XML iTunes depuis l'état actuel d'OpenTagger — "comme si
 * c'était iTunes qui écrivait dedans" (demande utilisateur 2026-09-19) : chaque fichier audio géré
 * par OpenTagger devient une entrée &lt;dict&gt; (ajouts ET modifications ET renommages, simplement
 * parce que tout est régénéré depuis l'état présent à chaque fois) ; un fichier disparu du disque
 * ("vide"/supprimé) est naturellement absent du prochain export, sans logique de suppression dédiée.
 *
 * DÉLIBÉRÉMENT un fichier SÉPARÉ de celui qu'utilise {@link ITunesXmlWriter} (voir sa Javadoc) —
 * PAS "iTunes Music Library.xml" lui-même. L'utilisateur a confirmé qu'un vrai iTunes tourne encore
 * quelque part et régénère CE fichier depuis sa propre base .itl : y écrire une bibliothèque entière
 * générée par OpenTagger serait écrasé au prochain export iTunes (perte silencieuse), ou pire,
 * entrerait en conflit si les deux écrivent au même moment. Ce module produit donc son propre
 * fichier XML plist, valide et lisible par tout outil qui sait lire ce format (Navidrome, scripts,
 * futur import), sans jamais toucher au fichier géré par le vrai iTunes.
 *
 * Contrairement à {@link ITunesXmlWriter} (édition chirurgicale ligne par ligne d'un fichier
 * existant), celui-ci écrit tout le document depuis zéro — seule approche réaliste pour gérer
 * ajouts/suppressions à cette échelle (300k+ fichiers) sans jamais avoir à insérer/retirer un bloc
 * &lt;dict&gt; au milieu d'un fichier de centaines de Mo. Streaming (BufferedWriter, une piste à la
 * fois), jamais de DOM/chaîne complète en mémoire.
 *
 * Track ID/Persistent ID : réutilise le Track ID CONNU (voir ITunesXmlSyncQueue.knownTrackIdByPath,
 * alimenté par un import depuis le vrai iTunes) quand il existe, pour rester cohérent d'un export à
 * l'autre pour ces pistes-là ; sinon génère un ID stable dérivé du hash du chemin absolu (collision
 * détectée et résolue par incrément — voir usedIds), offset loin des plages réelles d'iTunes
 * (constatées jusqu'à ~400k) pour ne jamais se confondre avec un vrai Track ID importé.
 */
public final class ITunesLibraryExporter {

    private ITunesLibraryExporter() {}

    private static final int GENERATED_ID_BASE = 50_000_000;

    public record Result(int written, int skippedMissingFile) {}

    /** {@code onProgress} : appelé tous les 2000 fichiers environ, avec le nombre déjà écrit —
     *  reconstruction complète sur 300k+ fichiers, sans retour la barre resterait indéterminée
     *  plusieurs minutes sans aucun signe de vie (même angle mort déjà corrigé ailleurs dans ce
     *  projet pour d'autres passes longues, voir WorkerHub/endurance monitoring). */
    public static Result export(File outFile, List<FileEntry> entries, IntConsumer onProgress) throws IOException {
        File tmp = new File(outFile.getParentFile(), outFile.getName() + ".opentagger_tmp");
        int written = 0, skipped = 0;
        Set<Integer> usedIds = new HashSet<>();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

        try (BufferedWriter w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            w.write("<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\" "
                  + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
            w.write("<plist version=\"1.0\">\n<dict>\n");
            w.write("\t<key>Major Version</key><integer>1</integer>\n");
            w.write("\t<key>Minor Version</key><integer>1</integer>\n");
            w.write("\t<key>Application Version</key><string>OpenTagger</string>\n");
            w.write("\t<key>Date</key><date>" + dateFmt.format(Instant.now()) + "</date>\n");
            w.write("\t<key>Tracks</key>\n\t<dict>\n");

            for (FileEntry entry : entries) {
                File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                if (fichier == null || !fichier.isFile()) { skipped++; continue; }

                TagInfo ti = entry.activeTags();
                if (ti == null) { skipped++; continue; }

                Path abs = fichier.toPath().toAbsolutePath().normalize();
                int trackId = com.opentagger.ITunesXmlSyncQueue.knownTrackId(abs);
                if (trackId <= 0) trackId = stableId(abs, usedIds);
                usedIds.add(trackId);

                writeTrack(w, trackId, abs, ti, dateFmt);
                written++;
                if (written % 2000 == 0 && onProgress != null) onProgress.accept(written);
            }

            w.write("\t</dict>\n</dict>\n</plist>\n");
        } catch (IOException e) {
            Files.deleteIfExists(tmp.toPath());
            throw e;
        }

        Files.move(tmp.toPath(), outFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Result(written, skipped);
    }

    private static void writeTrack(BufferedWriter w, int trackId, Path abs, TagInfo ti,
                                    DateTimeFormatter dateFmt) throws IOException {
        w.write("\t\t<key>" + trackId + "</key>\n\t\t<dict>\n");
        writeInt(w, "Track ID", trackId);
        writeStr(w, "Persistent ID", persistentId(abs));
        writeStr(w, "Track Type", "File");

        try {
            long size = Files.size(abs);
            writeInt(w, "Size", size);
        } catch (IOException ignored) {}

        if (ti.durationSec > 0) writeInt(w, "Total Time", ti.durationSec * 1000L);
        if (!ti.track.isBlank())      writeIntStr(w, "Track Number", ti.track);
        if (!ti.trackTotal.isBlank()) writeIntStr(w, "Track Count",  ti.trackTotal);
        if (!ti.discNo.isBlank())     writeIntStr(w, "Disc Number",  ti.discNo);
        if (!ti.discTotal.isBlank())  writeIntStr(w, "Disc Count",   ti.discTotal);
        if (!ti.year.isBlank())       writeIntStr(w, "Year", ti.year.length() >= 4 ? ti.year.substring(0, 4) : ti.year);

        try {
            Instant mtime = Files.getLastModifiedTime(abs).toInstant();
            writeDate(w, "Date Modified", mtime, dateFmt);
            writeDate(w, "Date Added",    mtime, dateFmt);
        } catch (IOException ignored) {}

        if (!ti.rating.isBlank()) {
            try {
                int stars = Integer.parseInt(ti.rating.trim());
                if (stars >= 1 && stars <= 5) writeInt(w, "Rating", stars * 20);
            } catch (NumberFormatException ignored) {}
        }
        if ("1".equals(ti.isCompilation)) w.write("\t\t\t<key>Compilation</key><true/>\n");

        writeStr(w, "Name",         ti.title);
        writeStr(w, "Artist",       ti.artist);
        writeStr(w, "Album Artist", ti.albumArtist);
        writeStr(w, "Album",        ti.album);
        writeStr(w, "Genre",        ti.genre);
        writeStr(w, "Composer",     ti.composer);
        writeStr(w, "Kind",         kindFor(abs));
        writeStr(w, "Location",     ITunesLibraryImporter.encodeLocation(abs));

        w.write("\t\t</dict>\n");
    }

    private static void writeStr(BufferedWriter w, String key, String value) throws IOException {
        if (value == null || value.isBlank()) return;
        w.write("\t\t\t<key>" + key + "</key><string>" + xmlEscape(value) + "</string>\n");
    }

    private static void writeInt(BufferedWriter w, String key, long value) throws IOException {
        w.write("\t\t\t<key>" + key + "</key><integer>" + value + "</integer>\n");
    }

    /** Champ numérique dont la source est déjà une chaîne (ex. TagInfo.track peut contenir "3/12") —
     *  ne garde que la partie numérique avant un éventuel "/", ignore silencieusement si rien
     *  d'exploitable plutôt que d'écrire une valeur invalide dans un &lt;integer&gt;. */
    private static void writeIntStr(BufferedWriter w, String key, String raw) throws IOException {
        String digits = raw.split("/")[0].trim();
        try { writeInt(w, key, Long.parseLong(digits)); } catch (NumberFormatException ignored) {}
    }

    private static void writeDate(BufferedWriter w, String key, Instant instant, DateTimeFormatter fmt)
            throws IOException {
        w.write("\t\t\t<key>" + key + "</key><date>" + fmt.format(instant) + "</date>\n");
    }

    private static String kindFor(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp3"))  return "Fichier audio MP3";
        if (name.endsWith(".flac")) return "Fichier audio FLAC";
        if (name.endsWith(".m4a"))  return "Fichier audio AAC";
        if (name.endsWith(".ogg"))  return "Fichier audio Ogg Vorbis";
        if (name.endsWith(".wav"))  return "Fichier audio WAV";
        return null;
    }

    /** ID stable dérivé du hash du chemin, avec résolution de collision par incrément — voir la
     *  Javadoc de classe pour le choix de GENERATED_ID_BASE. */
    private static int stableId(Path abs, Set<Integer> usedIds) {
        int h = GENERATED_ID_BASE + (abs.toString().hashCode() & 0x0FFFFFFF);
        while (usedIds.contains(h)) h++;
        return h;
    }

    /** 16 caractères hexadécimaux, même format que les vraies "Persistent ID" observées (ex.
     *  "DCC57C3C59492176") — dérivé d'un hash 64 bits du chemin, collision négligeable à cette
     *  échelle (contrairement au Track ID 32 bits, jamais vérifié/corrigé ici). */
    private static String persistentId(Path abs) {
        long h = 1125899906842597L; // FNV offset basis, 64 bits
        for (byte b : abs.toString().getBytes(StandardCharsets.UTF_8)) {
            h = (h * 1099511628211L) ^ (b & 0xFF);
        }
        return String.format("%016X", h);
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
