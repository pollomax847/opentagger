package com.opentagger;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Garde les playlists sidecar (.m3u/.m3u8/.pls) synchronisées avec les fichiers audio qu'OpenTagger
 * renomme/déplace (FileRenamer.rename()) — même logique que moveMatchingLrc() juste au-dessus dans
 * ce fichier, mais pour une référence à l'intérieur d'un fichier qui en liste plusieurs, plutôt que
 * pour un sidecar 1-pour-1 qu'on déplace tel quel.
 *
 * Constat réel sur la bibliothèque de l'utilisateur (2026-08-16) : plusieurs .pls référencent déjà
 * des noms de fichiers obsolètes (ex. mojibake jamais recorrigé : "Gar�on.flac" alors que le fichier
 * réel est "Garçon.flac") — ces playlists ne sont jamais mises à jour après un renommage, elles
 * pourrissent silencieusement au fil des passes de correction. Objectif : que le prochain
 * renommage qui touche un fichier référencé corrige la playlist du même coup.
 *
 * Ne touche jamais une entrée qui ne correspond pas EXACTEMENT au fichier renommé par OpenTagger —
 * jamais de suppression de ligne, jamais de réécriture "au mieux" d'une entrée déjà cassée pour une
 * autre raison (fichier manquant depuis toujours, etc.) : seule la ligne qui pointait vers l'ancien
 * chemin est réécrite vers le nouveau.
 */
public final class PlaylistSync {

    private PlaylistSync() {}

    private static final Set<String> EXTENSIONS = Set.of(".m3u", ".m3u8", ".pls");

    // .pls : "FileN=chemin" — le chemin va jusqu'à la fin de ligne.
    private static final Pattern PLS_ENTRY = Pattern.compile("^(File\\d+=)(.*)$");

    /** Appelé juste après un Files.move() réussi dans FileRenamer.rename() — jamais d'exception
     *  levée vers l'appelant, une playlist qu'on ne parvient pas à mettre à jour ne doit jamais
     *  faire échouer le renommage du fichier audio lui-même (même philosophie que moveMatchingLrc,
     *  fonctionnalité secondaire non bloquante). */
    public static void onFileMoved(Path oldAudio, Path newAudio) {
        if (!Config.get().syncPlaylistsOnRename()) return;
        try {
            Path oldDir = oldAudio.getParent();
            if (oldDir == null || !Files.isDirectory(oldDir)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(oldDir)) {
                for (Path p : ds) {
                    if (isPlaylist(p)) updateOne(p, oldAudio, newAudio);
                }
            }
        } catch (Exception ignored) {
            // Non bloquant par conception — voir commentaire de classe.
        }
    }

    private static boolean isPlaylist(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot)) && Files.isRegularFile(p);
    }

    private static void updateOne(Path playlist, Path oldAudio, Path newAudio) {
        try {
            Charset cs = detectCharset(playlist);
            List<String> lines = Files.readAllLines(playlist, cs);
            String oldName = oldAudio.getFileName().toString();
            String oldAbs  = oldAudio.toAbsolutePath().normalize().toString();
            String replacement = replacementRef(playlist, newAudio);

            boolean changed = false;
            List<String> out = new ArrayList<>(lines.size());
            for (String line : lines) {
                String updated = replaceReference(line, oldName, oldAbs, replacement);
                if (!updated.equals(line)) changed = true;
                out.add(updated);
            }
            if (changed) Files.write(playlist, out, cs);
        } catch (Exception ignored) {
            // Non bloquant par conception — voir commentaire de classe.
        }
    }

    /** Chemin à écrire pour la nouvelle référence : relatif depuis le dossier de la playlist quand
     *  c'est possible (style très majoritairement utilisé par les .m3u/.pls réels de la
     *  bibliothèque), sinon absolu en repli (racines différentes — ex. déplacement vers un autre
     *  disque). */
    private static String replacementRef(Path playlist, Path newAudio) {
        Path dir = playlist.toAbsolutePath().getParent();
        Path audio = newAudio.toAbsolutePath().normalize();
        if (dir != null) {
            try {
                return dir.relativize(audio).toString();
            } catch (IllegalArgumentException ignored) {
                // Racines différentes (autre disque) — repli absolu ci-dessous.
            }
        }
        return audio.toString();
    }

    private static String replaceReference(String line, String oldName, String oldAbs, String replacement) {
        Matcher plsM = PLS_ENTRY.matcher(line);
        if (plsM.matches()) {
            String ref = plsM.group(2).trim();
            return matches(ref, oldName, oldAbs) ? plsM.group(1) + replacement : line;
        }
        // .m3u/.m3u8 : toute ligne non vide et non-commentaire (#EXTM3U, #EXTINF...) est un chemin.
        if (!line.startsWith("#")) {
            String ref = line.trim();
            if (!ref.isEmpty() && matches(ref, oldName, oldAbs)) return replacement;
        }
        return line;
    }

    /** Comparaison sur le nom de fichier seul (insensible au style de chemin : relatif, absolu,
     *  séparateurs windows) plutôt qu'une égalité stricte — les playlists mélangent les deux styles
     *  selon l'outil qui les a écrites (constat réel : mélange .pls/.m3u/.nfo dans les mêmes
     *  dossiers, générés par des outils différents au fil des années). */
    private static boolean matches(String ref, String oldName, String oldAbs) {
        String refName = ref.replace('\\', '/');
        int slash = refName.lastIndexOf('/');
        if (slash >= 0) refName = refName.substring(slash + 1);
        return refName.equalsIgnoreCase(oldName) || ref.equalsIgnoreCase(oldAbs);
    }

    /** .pls/.m3u anciens sont souvent en Latin-1/CP1252 sans BOM (cas réel observé : "Gar�on.flac"
     *  en relisant un .pls réel en UTF-8) — on relit d'abord en UTF-8 ; un caractère de
     *  remplacement U+FFFD dans le résultat trahit un mauvais décodage, on retente en
     *  windows-1252. Réécrit dans le même charset détecté pour ne pas perturber le reste du
     *  fichier au-delà de la ligne réellement modifiée. */
    private static Charset detectCharset(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        String asUtf8 = new String(raw, StandardCharsets.UTF_8);
        return asUtf8.indexOf('�') >= 0 ? Charset.forName("windows-1252") : StandardCharsets.UTF_8;
    }
}
