package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v22Frame;
import org.jaudiotagger.tag.id3.ID3v22Tag;
import org.jaudiotagger.tag.id3.ID3v23Frame;
import org.jaudiotagger.tag.id3.ID3v23Tag;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.ID3v24Tag;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.field.Mp4TagTextField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTagField;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Écriture des tags audio 100% Java via jaudiotagger.
 *
 * Tous les formats sont gérés nativement :
 *   MP3  → ID3v2.3/v2.4 (frames standard + TXXX pour champs custom)
 *   M4A  → atomes iTunes + freeform ----:com.apple.iTunes: pour champs custom
 *   FLAC → VorbisComment (clés standard)
 *   OGG  → VorbisComment (clés standard)
 *
 * Les 6 champs sans FieldKey (ReplayGain ×4, isInstrumental, discogsId)
 * sont écrits via setCustomField() qui dispatche selon le type de tag.
 */
public class TagWriter {

    public TagInfo write(File fichier, TagInfo info) throws Exception {
        return write(fichier, info, null);
    }

    public TagInfo write(File fichier, TagInfo info, Path coverImage) throws Exception {
        // Réaffectation immédiate (pas seulement le throttle de Config.get()) : on veut le
        // silencing effectif dès CETTE écriture, sans attendre la prochaine fenêtre de 2s.
        Config.silenceJaudiotaggerLogging();
        long savedTimestamp = Config.get().preserveTimestamps() ? fichier.lastModified() : 0;

        // Opus/AAC/WV/APE : jaudiotagger 3.0.1 n'a aucun lecteur pour ces formats (vérifié en
        // décompilant le jar, voir FfmpegTagIO) — inutile de tenter AudioFileIO.read()/writeNative()
        // en sachant qu'ils vont échouer, on part directement sur le contournement ffmpeg. Pas de
        // fusion avec les tags existants ici (juste info.copy()) : cohérent avec le comportement
        // déjà en place juste en dessous quand AudioFileIO.read() échoue pour toute autre raison.
        if (FfmpegTagIO.handles(fichier)) {
            TagInfo copy = info.copy();
            if (!Config.get().saveAcoustidFingerprints()) {
                copy.acoustidId          = "";
                copy.acoustidFingerprint = "";
            }
            // tags.preserved_tags (COMMENT/DISC_NO/DISC_TOTAL/RATING/TAGS...) : jusqu'à ce
            // correctif, jamais appliqué sur ce chemin — FfmpegTagIO.write() réécrit tout
            // (atomes/-map_metadata -1 selon le format), donc sans ça ce réglage n'avait AUCUN
            // effet pour tout fichier Opus/AAC/WV/APE/WAV, contrairement au chemin jaudiotagger
            // natif et aux fallbacks M4A.
            applyPreservedToTagInfo(copy, readPreservedTagsViaFfmpeg(fichier));
            copy.taggedDate = java.time.LocalDate.now().toString();
            FfmpegTagIO.write(fichier, copy, coverImage);
            if (savedTimestamp > 0) fichier.setLastModified(savedTimestamp);
            return copy;
        }

        // M4A : réparation si nécessaire avant lecture/écriture
        repairM4aIfNeeded(fichier);

        TagInfo merged;
        Map<String, String> preserved = Map.of();
        try {
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTag();
            if (tag != null) {
                preserved = readPreservedTags(tag);
                merged = Config.get().clearExistingTags()
                        ? info.copy()
                        : mergeWithExisting(info, tag);
            } else {
                merged = info.copy();
            }
        } catch (Exception e) {
            merged = info.copy();
        }

        if (!Config.get().saveAcoustidFingerprints()) {
            merged.acoustidId          = "";
            merged.acoustidFingerprint = "";
        }
        merged.taggedDate = java.time.LocalDate.now().toString();

        writeNative(fichier, merged, coverImage, preserved, Config.get().clearExistingTags());

        if (savedTimestamp > 0) fichier.setLastModified(savedTimestamp);
        return merged;
    }

    /**
     * Écrit uniquement la pochette (cover art) dans le fichier sans toucher aux autres tags.
     * Utilisé par le rafraîchissement de pochette depuis le Cover Art Archive.
     */
    public void writeCoverOnly(java.io.File fichier, java.nio.file.Path coverImage) throws Exception {
        if (coverImage == null || !java.nio.file.Files.exists(coverImage)) return;
        long savedTimestamp = Config.get().preserveTimestamps() ? fichier.lastModified() : 0;
        repairM4aIfNeeded(fichier);
        AudioFile audio = AudioFileIO.read(fichier);
        Tag tag = audio.getTagOrCreateAndSetDefault();
        // Supprimer les pochettes existantes et ajouter la nouvelle
        tag.deleteArtworkField();
        byte[] imgBytes = java.nio.file.Files.readAllBytes(coverImage);
        String name = coverImage.getFileName().toString().toLowerCase();
        String mime = name.endsWith(".png") ? "image/png" : "image/jpeg";
        org.jaudiotagger.tag.images.Artwork art = org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(coverImage.toFile());
        art.setBinaryData(imgBytes);
        art.setMimeType(mime);
        art.setPictureType(3); // Front cover
        tag.setField(art);
        audio.commit();
        if (savedTimestamp > 0) fichier.setLastModified(savedTimestamp);
    }

    /** Écrit uniquement les champs ReplayGain Track sans toucher aux autres tags. */
    public void writeReplayGain(File fichier, String trackGain, String trackPeak) {
        if ((trackGain == null || trackGain.isBlank())
                && (trackPeak == null || trackPeak.isBlank())) return;
        try {
            repairM4aIfNeeded(fichier);
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTagOrCreateAndSetDefault();
            setCustomField(tag, "REPLAYGAIN_TRACK_GAIN", trackGain);
            setCustomField(tag, "REPLAYGAIN_TRACK_PEAK", trackPeak);
            audio.commit();
        } catch (Exception ignored) {}
    }

    /** Écrit uniquement les champs ReplayGain Album sans toucher aux autres tags. */
    public void writeAlbumReplayGain(File fichier, String albumGain, String albumPeak) {
        if ((albumGain == null || albumGain.isBlank())
                && (albumPeak == null || albumPeak.isBlank())) return;
        try {
            repairM4aIfNeeded(fichier);
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTagOrCreateAndSetDefault();
            setCustomField(tag, "REPLAYGAIN_ALBUM_GAIN", albumGain);
            setCustomField(tag, "REPLAYGAIN_ALBUM_PEAK", albumPeak);
            audio.commit();
        } catch (Exception ignored) {}
    }

    // ── Écriture native jaudiotagger ──────────────────────────────────────────

    /** Vrai pour .m4a ET .mp4 : même conteneur ISO-BMFF, même fragilité de parsing d'arbre
     *  d'atomes côté jaudiotagger (vérifié en direct : un .mp4 audio produit exactement la même
     *  classe d'exception — {@code Mp4AtomTree.buildChildrenOfNode}, "newPosition > limit" — que
     *  les .m4a qui déclenchent la chaîne de repli ci-dessous). Seule l'extension diffère ; jusqu'à
     *  ce correctif, .mp4 ne bénéficiait d'AUCUNE des 3 couches de réparation/repli réservées à
     *  .m4a et échouait donc systématiquement dès que jaudiotagger butait sur ce genre de fichier. */
    private static boolean isM4aFamily(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".m4a") || n.endsWith(".mp4");
    }

    /** Vérification a minima qu'une écriture M4A/MP4 "réussie" (aucune exception) a bien produit un
     *  fichier exploitable, plutôt que de faire confiance à l'absence d'exception — voir writeNative().
     *  AudioFileIO.read() suffit ici : il échoue déjà de façon fiable sur un fichier tronqué/vide
     *  (mêmes classes d'exception que celles gérées par la chaîne de repli juste après). */
    private static boolean isReadableM4a(File f) {
        if (!f.exists() || f.length() == 0) return false;
        try { AudioFileIO.read(f); return true; }
        catch (Exception e) { return false; }
    }

    /**
     * Chaîne de fallback pour M4A/MP4 :
     *  1. jaudiotagger natif          → tous les champs
     *  2. ffmpeg repair + retry       → tous les champs
     *  3. AtomicParsley               → tous les champs (cover, MBIDs, ReplayGain)
     *  4. ffmpeg direct               → tags standards seulement (dernier recours)
     */
    private void writeNative(File fichier, TagInfo i, Path coverImage,
                              Map<String, String> preserved,
                              boolean clearExisting) throws Exception {
        // jaudiotagger réécrit l'arbre d'atomes M4A/MP4 DIRECTEMENT sur le fichier original (pas de
        // temp+rename comme les autres formats, voir writeM4aViaFfmpeg/AtomicParsley plus bas) — si
        // CETTE toute première tentative est interrompue (disque plein, mergerfs qui relocalise le
        // fichier en cours d'écriture via moveonenospc, voir fstab MusicPool), le fichier peut se
        // retrouver tronqué/vide AVANT même qu'une exception Java n'ait la moindre chance d'être
        // levée. Sauvegarder seulement APRÈS avoir capté l'exception (comme avant ce correctif)
        // arrivait déjà trop tard dans ce cas précis : la "sauvegarde" copiait un fichier déjà vide,
        // et restoreBackup() en fin de chaîne restaurait ce même vide — aucune protection réelle.
        // Trouvé en direct 2026-07-28 : plusieurs .m4a à 0 octet dans la bibliothèque, mtimes
        // concordant avec l'incident disque plein déjà identifié (voir mémoire "Runaway yt-dlp
        // playlist export script"). Sauvegarde prise AVANT la première tentative pour M4A/MP4
        // uniquement (coût d'une copie par écriture, inutile pour les autres formats dont le
        // mécanisme jaudiotagger générique est déjà temp+rename, donc déjà sûr par construction).
        Path earlyBackup = isM4aFamily(fichier) ? backupBeforeRepair(fichier) : null;
        try {
            doWriteNative(fichier, i, coverImage, preserved, clearExisting);
            // jaudiotagger n'a rien levé, mais une écriture interrompue en plein milieu (voir
            // ci-dessus) peut malgré tout laisser un résultat tronqué/vide sans exception détectable
            // — vérifié explicitement plutôt que de faire confiance à l'absence d'exception.
            if (earlyBackup != null && !isReadableM4a(fichier)) {
                throw new Exception("écriture jaudiotagger sans exception mais fichier résultant"
                    + " illisible/vide (" + fichier.length() + " octet(s))");
            }
            deleteBackupQuietly(earlyBackup);
            return;
        } catch (Exception e) {
            if (!isM4aFamily(fichier)) throw translateKnownJaudiotaggerBug(fichier, e);
            // Remettre le fichier dans son état d'avant la première tentative avant d'engager la
            // chaîne de repli ci-dessous : sans ça, runFfmpegRepair()/AtomicParsley repartiraient
            // d'un fichier potentiellement déjà tronqué/vide plutôt que de l'original intact.
            if (earlyBackup != null) restoreBackup(fichier, earlyBackup);

            // AudioScanner traite tous les .mp4 comme candidats audio (voir sa Javadoc) — un vrai
            // clip vidéo échoue donc systématiquement ici et n'a de toute façon AUCUNE chance de
            // réussir dans la chaîne de repli ci-dessous (repair/AtomicParsley/ffmpeg direct
            // produisent tous un conteneur audio-only). Sans ce contrôle, chacune des 3 étapes
            // suivantes tentait quand même sa chance avec son propre timeout de 120s — jusqu'à 6
            // minutes brûlées PAR FICHIER PAR TENTATIVE D'ENREGISTREMENT sur une vidéo qui ne sera
            // jamais taguable, constaté en direct (2026-08-08) sur des dizaines de clips musicaux
            // dans le journal ("timeout | timeout | timeout" à répétition). AudioFormatCheck.
            // hasVideoStream() existait déjà pour ce diagnostic mais n'était jusqu'ici appelé qu'
            // après coup (TaggingWorker), jamais avant d'engager cette chaîne coûteuse à l'écriture.
            if (fichier.getName().toLowerCase().endsWith(".mp4") && AudioFormatCheck.hasVideoStream(fichier)) {
                throw new Exception("fichier vidéo (pas audio) — écriture de tags audio impossible, "
                    + "voir la fonctionnalité de récupération vidéo");
            }
        }

        // Deuxième sauvegarde pour la chaîne de repli elle-même (repair ffmpeg/AtomicParsley/ffmpeg
        // direct, chacun risqué à sa façon) — indépendante de earlyBackup ci-dessus, restaurée déjà
        // à ce stade, donc à jour avec l'original intact.
        Path backup = backupBeforeRepair(fichier);
        StringBuilder diag = new StringBuilder();
        try {
            // Fallback 1 : repair structure ffmpeg + retry jaudiotagger
            if (runFfmpegRepair(fichier, diag)) {
                try {
                    doWriteNative(fichier, i, coverImage, preserved, clearExisting);
                    deleteBackupQuietly(backup);
                    return;
                } catch (Exception e2) { diag.append(" | retry jaudiotagger: ").append(e2.getMessage()); }
            }
            // Fallback 2 : AtomicParsley — full support (cover + MBIDs + ReplayGain)
            try {
                writeM4aViaAtomicParsley(fichier, i, coverImage, preserved);
                deleteBackupQuietly(backup);
                return;
            } catch (Exception e3) { diag.append(" | AtomicParsley: ").append(e3.getMessage()); }
            // Fallback 3 : ffmpeg — tags standards seulement (dernier recours)
            writeM4aViaFfmpeg(fichier, i, coverImage, preserved);
            deleteBackupQuietly(backup);
        } catch (Exception finalError) {
            // Toute la chaîne a échoué : restaurer l'original plutôt que de laisser un fichier
            // potentiellement corrompu par la tentative d'écriture jaudiotagger initiale.
            boolean restored = restoreBackup(fichier, backup);
            throw new Exception("Échec écriture M4A (jaudiotagger+repair+AtomicParsley+ffmpeg)"
                + (restored ? " — original restauré" : " — AUCUNE sauvegarde disponible")
                + " : " + finalError.getMessage() + diag, finalError);
        }
    }

    /**
     * jaudiotagger ({@code AudioFileWriter.write()}) crée un fichier temporaire nommé d'après le
     * fichier d'origine (+ ".tmp") DANS LE MÊME DOSSIER ; si ce nom dépasse la limite du système de
     * fichiers, la création du fichier temporaire échoue avec une IOException — que jaudiotagger
     * tente de détecter via {@code ioException.getMessage().equals("File name too long")}
     * (confirmé par décompilation du bytecode de {@code AudioFileWriter.class}, jaudiotagger 3.0.1)
     * SANS vérifier que ce message n'est pas null (arrive par ex. sur certains montages FUSE/exFAT
     * où l'OS ne renvoie pas ce texte exact) — ce qui plante avec un NullPointerException cryptique
     * ("Cannot invoke \"String.equals(Object)\" because the return value of
     * \"java.io.IOException.getMessage()\" is null") au lieu du repli prévu par la bibliothèque.
     * Bug du jar tiers, non corrigeable ici — on se contente de transformer le crash en message
     * actionnable plutôt que de laisser filer le texte cryptique tel quel dans les logs.
     */
    private static Exception translateKnownJaudiotaggerBug(File fichier, Exception e) {
        if (e instanceof NullPointerException && e.getMessage() != null
                && e.getMessage().contains("IOException.getMessage()")) {
            String name = fichier.getName();
            // La cause réelle est perdue par jaudiotagger AVANT même d'atteindre ce code (voir
            // Javadoc ci-dessus) — "trop long" n'est qu'une hypothèse parmi d'autres, plausible
            // seulement si le nom approche vraiment une limite de système de fichiers (même seuil
            // que FileRenamer.MAX_SEGMENT_LENGTH). En dessous, l'affirmer était trompeur : constaté
            // en pratique sur des noms de 40-60 caractères, où la vraie cause est plus probablement
            // un montage FUSE (mergerfs...) qui a routé le fichier temporaire de jaudiotagger (créé
            // à côté de l'original, même dossier) vers une autre partition/branche que l'original —
            // une politique "most free space" (category.create=mfs) peut le faire à tout moment.
            String hint = name.length() > FileRenamer.MAX_SEGMENT_LENGTH
                ? "nom de fichier probablement trop long pour le système de fichiers (\"" + name
                    + "\", " + name.length() + " caractères) — bug connu de jaudiotagger lors de la"
                    + " création d'un fichier temporaire. Raccourcissez le nom du fichier ou son chemin."
                : "cause réelle perdue par un bug connu de jaudiotagger lors de la création de son"
                    + " fichier temporaire (\"" + name + "\", " + name.length() + " caractères — la"
                    + " longueur n'est probablement pas en cause ici). Observé notamment sur des"
                    + " montages réseau/FUSE (mergerfs...) quand le fichier temporaire est placé sur"
                    + " une autre partition que l'original. Réessayez ; si ça persiste sur ce fichier"
                    + " précis, vérifiez l'espace disque et les permissions du dossier.";
            return new IOException("Écriture impossible : " + hint, e);
        }
        // "No audio header found" (CannotReadException jaudiotagger) : trouvé deux fois en une
        // session sur des fichiers dont le contenu réel (M4A/AAC + tag ID3v2 collé devant) ne
        // correspondait pas du tout à l'extension .mp3 — cause réelle jamais mentionnée par le
        // message jaudiotagger tel quel. ffprobe (opportuniste, seulement ici, pas sur le chemin
        // chaud) donne le vrai format ; si RIEN ne cloche entre extension et contenu, on laisse
        // passer le message d'origine tel quel plutôt que d'affirmer une hypothèse non confirmée.
        if (e.getMessage() != null && e.getMessage().contains("No audio header found")) {
            String mismatch = AudioFormatCheck.describeMismatch(fichier);
            if (mismatch != null) {
                return new IOException("Écriture impossible : " + mismatch, e);
            }
        }
        return e;
    }

    /** Copie de sécurité avant toute tentative de réparation/écriture M4A risquée. */
    private static Path backupBeforeRepair(File f) {
        try {
            Path backup = f.toPath().resolveSibling(f.getName() + ".ot-backup");
            Files.copy(f.toPath(), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (Exception e) {
            return null; // pas de filet possible (disque plein, permissions...) — on continue quand même
        }
    }

    /** Restaure la sauvegarde à la place du fichier (potentiellement corrompu). Retourne true si fait. */
    private static boolean restoreBackup(File f, Path backup) {
        if (backup == null) return false;
        try {
            if (!Files.exists(backup)) return false;
            Files.move(backup, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) { return false; }
    }

    private static void deleteBackupQuietly(Path backup) {
        if (backup == null) return;
        try { Files.deleteIfExists(backup); } catch (Exception ignored) {}
    }

    private void doWriteNative(File fichier, TagInfo i, Path coverImage,
                                Map<String, String> preserved,
                                boolean clearExisting) throws Exception {
        // Réglages globaux jaudiotagger relus à chaque écriture (pas seulement au démarrage) pour
        // réagir immédiatement à un changement dans les Préférences, sans redémarrage.
        org.jaudiotagger.tag.TagOptionSingleton opts = org.jaudiotagger.tag.TagOptionSingleton.getInstance();
        opts.setId3v1Save(!Config.get().removeId3v1());
        String id3v2Pref = Config.get().id3v2Version();
        if ("2.3".equals(id3v2Pref)) opts.setID3V2Version(org.jaudiotagger.tag.reference.ID3V2Version.ID3_V23);
        else if ("2.4".equals(id3v2Pref)) opts.setID3V2Version(org.jaudiotagger.tag.reference.ID3V2Version.ID3_V24);

        AudioFile audio = AudioFileIO.read(fichier);

        // "Conserver la pochette existante si aucune nouvelle" : createDefaultTag() (clearExisting)
        // repart d'un tag entièrement vide, perdant la pochette embarquée si on ne la récupère pas
        // AVANT de le remplacer. Sans ce correctif, ce réglage n'avait aucun effet.
        Artwork preservedArt = null;
        if (clearExisting && coverImage == null && Config.get().preserveImages()) {
            try {
                Tag existing = audio.getTag();
                if (existing != null) preservedArt = existing.getFirstArtwork();
            } catch (Exception ignored) {}
        }

        Tag tag;
        if (clearExisting) {
            tag = audio.createDefaultTag();
            audio.setTag(tag);
        } else {
            tag = audio.getTagOrCreateAndSetDefault();
        }

        // Forcer la version ID3v2 configurée (MP3 uniquement) — sans ça, une tag ID3v2.4
        // existante restait v2.4 même si l'utilisateur demande explicitement "ID3v2.3".
        if (tag instanceof AbstractID3v2Tag id3Existing) {
            if ("2.3".equals(id3v2Pref) && !(id3Existing instanceof ID3v23Tag)) {
                tag = new ID3v23Tag(id3Existing);
                audio.setTag(tag);
            } else if ("2.4".equals(id3v2Pref) && !(id3Existing instanceof ID3v24Tag)) {
                tag = new ID3v24Tag(id3Existing);
                audio.setTag(tag);
            }
        }

        // ── Champs standard via FieldKey ─────────────────────────────────────
        sf(tag, FieldKey.TITLE,              i.title);
        sf(tag, FieldKey.ARTIST,             i.artist);
        sf(tag, FieldKey.ALBUM_ARTIST,       i.albumArtist);
        sf(tag, FieldKey.ALBUM,              i.album);
        sf(tag, FieldKey.YEAR,               i.year);
        sf(tag, FieldKey.GENRE,              i.genre);
        sf(tag, FieldKey.TRACK,              i.track);
        sf(tag, FieldKey.TRACK_TOTAL,        i.trackTotal);
        sf(tag, FieldKey.DISC_NO,            i.discNo);
        sf(tag, FieldKey.DISC_TOTAL,         i.discTotal);
        sf(tag, FieldKey.COMMENT,            i.comment);

        // ── Artistes multiples ───────────────────────────────────────────────
        sf(tag, FieldKey.ARTISTS,            i.artists);
        sf(tag, FieldKey.ARTISTS_SORT,       i.artistsSort);

        // ── Tri ──────────────────────────────────────────────────────────────
        sf(tag, FieldKey.TITLE_SORT,         i.titleSort);
        sf(tag, FieldKey.ARTIST_SORT,        i.artistSort);
        sf(tag, FieldKey.ALBUM_SORT,         i.albumSort);
        sf(tag, FieldKey.ALBUM_ARTIST_SORT,  i.albumArtistSort);
        sf(tag, FieldKey.COMPOSER_SORT,      i.composerSort);
        sf(tag, FieldKey.CONDUCTOR_SORT,     i.conductorSort);
        sf(tag, FieldKey.ORCHESTRA_SORT,     i.orchestraSort);
        sf(tag, FieldKey.ENSEMBLE_SORT,      i.ensembleSort);
        sf(tag, FieldKey.CHOIR_SORT,         i.choirSort);
        sf(tag, FieldKey.LYRICIST_SORT,      i.lyricistSort);
        sf(tag, FieldKey.PRODUCER_SORT,      i.producerSort);
        sf(tag, FieldKey.ARRANGER_SORT,      i.arrangerSort);
        sf(tag, FieldKey.MIXER_SORT,         i.mixerSort);

        // ── Compositeurs / contributeurs ─────────────────────────────────────
        sf(tag, FieldKey.COMPOSER,           i.composer);
        sf(tag, FieldKey.CONDUCTOR,          i.conductor);
        sf(tag, FieldKey.ORCHESTRA,          i.orchestra);
        sf(tag, FieldKey.ENSEMBLE,           i.ensemble);
        sf(tag, FieldKey.CHOIR,              i.choir);
        sf(tag, FieldKey.LYRICIST,           i.lyricist);
        sf(tag, FieldKey.PRODUCER,           i.producer);
        sf(tag, FieldKey.ARRANGER,           i.arranger);
        sf(tag, FieldKey.ENGINEER,           i.engineer);
        sf(tag, FieldKey.MIXER,              i.mixer);
        sf(tag, FieldKey.DJMIXER,            i.djMixer);

        // ── Classique ────────────────────────────────────────────────────────
        sf(tag, FieldKey.WORK,               i.work);
        sf(tag, FieldKey.MUSICBRAINZ_WORK_ID, i.workMbid);
        sf(tag, FieldKey.MOVEMENT,           i.movement);
        sf(tag, FieldKey.MOVEMENT_NO,        i.movementNo);
        sf(tag, FieldKey.MOVEMENT_TOTAL,     i.movementTotal);
        sf(tag, FieldKey.TITLE_MOVEMENT,     i.titleMovement);
        sf(tag, FieldKey.PART,               i.part);
        sf(tag, FieldKey.PART_TYPE,          i.partType);
        sf(tag, FieldKey.PART_NUMBER,        i.partNo);
        sf(tag, FieldKey.PERIOD,             i.period);
        sf(tag, FieldKey.OPUS,               i.opus);
        sf(tag, FieldKey.CLASSICAL_CATALOG,  i.classicalCatalog);
        sf(tag, FieldKey.CLASSICAL_NICKNAME, i.classicalNickname);
        sf(tag, FieldKey.SECTION,            i.section);
        sf(tag, FieldKey.OVERALL_WORK,       i.overallWork);
        sf(tag, FieldKey.GROUPING,           i.grouping);

        // ── Flags ────────────────────────────────────────────────────────────
        sf(tag, FieldKey.IS_CLASSICAL,       i.isClassical);
        sf(tag, FieldKey.IS_COMPILATION,     i.isCompilation);
        sf(tag, FieldKey.IS_HD,              i.isHD);
        sf(tag, FieldKey.IS_LIVE,            i.isLive);
        sf(tag, FieldKey.IS_GREATEST_HITS,   i.isGreatestHits);
        sf(tag, FieldKey.IS_SOUNDTRACK,      i.isSoundtrack);
        // isInstrumental → pas de FieldKey → champ custom
        setCustomField(tag, "IS_INSTRUMENTAL", i.isInstrumental);

        // ── Audio / tempo / tonalité ──────────────────────────────────────────
        sf(tag, FieldKey.BPM,                i.bpm);
        sf(tag, FieldKey.FBPM,               i.fbpm);
        sf(tag, FieldKey.KEY,   Config.get().writeCamelotKey() ? toCamelot(i.initialKey) : i.initialKey);
        sf(tag, FieldKey.LANGUAGE,           i.language);

        // ── Humeur (Essentia) ─────────────────────────────────────────────────
        sf(tag, FieldKey.MOOD,               i.mood);
        sf(tag, FieldKey.MOOD_AGGRESSIVE,    i.moodAggressive);
        sf(tag, FieldKey.MOOD_ACOUSTIC,      i.moodAcoustic);
        sf(tag, FieldKey.MOOD_ELECTRONIC,    i.moodElectronic);
        sf(tag, FieldKey.MOOD_HAPPY,         i.moodHappy);
        sf(tag, FieldKey.MOOD_PARTY,         i.moodParty);
        sf(tag, FieldKey.MOOD_RELAXED,       i.moodRelaxed);
        sf(tag, FieldKey.MOOD_SAD,           i.moodSad);
        sf(tag, FieldKey.MOOD_VALENCE,       i.moodValence);
        sf(tag, FieldKey.MOOD_AROUSAL,       i.moodArousal);
        sf(tag, FieldKey.MOOD_DANCEABILITY,  i.moodDanceability);
        sf(tag, FieldKey.MOOD_INSTRUMENTAL,  i.moodInstrumental);

        // ── ReplayGain → pas de FieldKey → champs custom ─────────────────────
        setCustomField(tag, "REPLAYGAIN_TRACK_GAIN", i.replayGainTrackGain);
        setCustomField(tag, "REPLAYGAIN_TRACK_PEAK", i.replayGainTrackPeak);
        setCustomField(tag, "REPLAYGAIN_ALBUM_GAIN", i.replayGainAlbumGain);
        setCustomField(tag, "REPLAYGAIN_ALBUM_PEAK", i.replayGainAlbumPeak);

        // ── Paroles ───────────────────────────────────────────────────────────
        sf(tag, FieldKey.LYRICS,             i.lyrics);
        sf(tag, FieldKey.URL_LYRICS_SITE,    i.lyricsUrl);

        // ── Rating & tags ─────────────────────────────────────────────────────
        sf(tag, FieldKey.RATING,             i.rating);
        sf(tag, FieldKey.TAGS,               i.tags);

        // ── Identifiants ─────────────────────────────────────────────────────
        sf(tag, FieldKey.ISRC,               i.isrc);
        sf(tag, FieldKey.AMAZON_ID,          i.amazonId);
        sf(tag, FieldKey.ACOUSTID_ID,        i.acoustidId);
        sf(tag, FieldKey.ACOUSTID_FINGERPRINT, i.acoustidFingerprint);
        sf(tag, FieldKey.ROONALBUMTAG,       i.roonAlbumTag);
        sf(tag, FieldKey.ROONTRACKTAG,       i.roonTrackTag);
        // discogsId/appleMusicId → pas de FieldKey numérique → champs custom
        setCustomField(tag, "DISCOGS_RELEASE_ID", i.discogsId);
        setCustomField(tag, "APPLE_MUSIC_ID",     i.appleMusicId);

        // ── Métadonnées release ───────────────────────────────────────────────
        sf(tag, FieldKey.SCRIPT,             i.script);
        sf(tag, FieldKey.COUNTRY,            i.country);
        sf(tag, FieldKey.BARCODE,            i.barcode);
        sf(tag, FieldKey.CATALOG_NO,         i.catalogNo);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASE_TYPE, i.releaseType);
        sf(tag, FieldKey.ORIGINAL_YEAR,      i.originalYear);
        // label/releaseStatus/media : champs MusicBrainz systématiquement extraits mais jamais
        // écrits avant ce correctif (comparaison directe avec Picard sur un même fichier).
        sf(tag, FieldKey.RECORD_LABEL,             i.label);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASE_STATUS, i.releaseStatus);
        sf(tag, FieldKey.MEDIA,                    i.media);

        // ── IDs MusicBrainz ───────────────────────────────────────────────────
        sf(tag, FieldKey.MUSICBRAINZ_ARTISTID,         i.artistMbid);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASEARTISTID,  i.albumArtistMbid);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASE_GROUP_ID, i.releaseGroupMbid);
        sf(tag, FieldKey.MUSICBRAINZ_RELEASEID,        i.releaseMbid);
        sf(tag, FieldKey.MUSICBRAINZ_TRACK_ID,         i.recordingMbid);

        // ── Podcast ───────────────────────────────────────────────────────────
        setCustomField(tag, "PODCAST_URL",   i.podcastUrl);
        setCustomField(tag, "SEASON",        i.podcastSeason);
        setCustomField(tag, "EPISODE",       i.podcastEpisode);
        setCustomField(tag, "EPISODETYPE",   i.podcastEpisodeType);
        setCustomField(tag, "KEYWORDS",      i.podcastKeywords);

        // ── Statistiques d'écoute ───────────────────────────────────────────────
        setCustomField(tag, "LISTENBRAINZ_PLAYCOUNT", i.listenbrainzPlayCount);

        // ── Marqueur de taguage (portable, indépendant du cache SQLite) ─────────
        setCustomField(tag, "OT_TAGGEDDATE", i.taggedDate);

        // ── URLs ──────────────────────────────────────────────────────────────
        sf(tag, FieldKey.URL_OFFICIAL_ARTIST_SITE,   i.artistOfficialUrl);
        sf(tag, FieldKey.URL_WIKIPEDIA_ARTIST_SITE,  i.artistWikipediaUrl);
        sf(tag, FieldKey.URL_DISCOGS_ARTIST_SITE,    i.artistDiscogsUrl);
        sf(tag, FieldKey.URL_DISCOGS_RELEASE_SITE,   i.releaseDiscogsUrl);
        sf(tag, FieldKey.URL_OFFICIAL_RELEASE_SITE,  i.releaseOfficialUrl);
        sf(tag, FieldKey.URL_WIKIPEDIA_RELEASE_SITE, i.releaseWikipediaUrl);

        // ── Tags preservés ────────────────────────────────────────────────────
        // Uniquement si le taguage actuel n'a RIEN écrit dans ce champ : sinon, pour un champ que
        // le pipeline renseigne parfois (DISC_NO/DISC_TOTAL/COMMENT — contrairement à RATING par ex.,
        // jamais écrit par le taguage), l'ancienne valeur figée avant clearExistingTags écraserait
        // systématiquement une valeur fraîche et correcte tout juste posée juste au-dessus.
        for (Map.Entry<String, String> e : preserved.entrySet()) {
            try {
                FieldKey key = FieldKey.valueOf(e.getKey().toUpperCase());
                String justWritten = getTagFirst(tag, key);
                if (justWritten == null || justWritten.isBlank()) sf(tag, key, e.getValue());
            } catch (Exception ignored) {}
        }

        // ── Pochette ──────────────────────────────────────────────────────────
        if (coverImage != null) {
            try {
                Artwork art = ArtworkFactory.createArtworkFromFile(coverImage.toFile());
                tag.deleteArtworkField();
                tag.setField(art);
            } catch (Exception ignored) {}
        } else if (preservedArt != null) {
            try { tag.setField(preservedArt); } catch (Exception ignored) {}
        }

        audio.commit();
    }

    // ── Fallback M4A via AtomicParsley ───────────────────────────────────────

    /**
     * Écrit tous les champs M4A via AtomicParsley (fallback 2).
     * Parité quasi complète avec le chemin jaudiotagger natif (doWriteNative) — vérifiée champ par
     * champ contre lui, pas seulement les groupes évidents (tags standards, sort fields, cover
     * art, MusicBrainz IDs, ReplayGain, Acoustid, Discogs, flags, classique, contributeurs,
     * artistes multiples, catalogue/année originale) — via atomes freeform ----:com.apple.iTunes:
     * pour tout ce qui n'a pas de flag AtomicParsley dédié.
     */
    private static void writeM4aViaAtomicParsley(File fichier, TagInfo i, Path coverImage,
                                                  Map<String, String> preserved)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("AtomicParsley");
        cmd.add(fichier.getAbsolutePath());

        // COMMENT/DISC_NO/DISC_TOTAL : seuls champs de tags.preserved_tags concernés par ce
        // fallback pour l'instant (voir effectivePreserved()) — repli sur l'ancienne valeur du
        // fichier si le taguage n'a rien calculé, au lieu de la perdre silencieusement. Avant ce
        // correctif, preserved n'était même pas transmis à ce fallback : tags.preserved_tags
        // n'avait AUCUN effet pour tout fichier M4A retombant ici (DRM iTunes, atomes non
        // standards...), contrairement au chemin jaudiotagger natif.
        String comment    = effectivePreserved(i.comment,    "COMMENT",     preserved);
        String discNo     = effectivePreserved(i.discNo,     "DISC_NO",     preserved);
        String discTotal  = effectivePreserved(i.discTotal,  "DISC_TOTAL",  preserved);

        // Tags standards
        apField(cmd, "--title",       i.title);
        apField(cmd, "--artist",      i.artist);
        apField(cmd, "--albumArtist", i.albumArtist);
        apField(cmd, "--album",       i.album);
        apField(cmd, "--year",        i.year);
        apField(cmd, "--genre",       i.genre);
        apField(cmd, "--composer",    i.composer);
        apField(cmd, "--comment",     comment);
        apField(cmd, "--lyrics",      i.lyrics);
        apField(cmd, "--grouping",    i.grouping);

        if (!i.track.isBlank()) {
            String trk = i.trackTotal.isBlank() ? i.track : i.track + "/" + i.trackTotal;
            apField(cmd, "--tracknum", trk);
        }
        if (!discNo.isBlank()) {
            String dsk = discTotal.isBlank() ? discNo : discNo + "/" + discTotal;
            apField(cmd, "--disk", dsk);
        }
        if (!i.bpm.isBlank()) apField(cmd, "--bpm", i.bpm);
        if (i.isCompilation.equals("1"))
            apField(cmd, "--compilation", "true");

        // Sort fields — AtomicParsley n'a PAS de flag dédié par champ (--sortTitle/--sortArtist/…
        // n'existent pas, confirmé via --longhelp : "unrecognized option" sur exit=1, cause du
        // fallback 2 systématiquement en échec dès qu'un champ de tri était renseigné). Un seul
        // flag --sortOrder <type> <valeur> ; le type pour le titre est "name", pas "title".
        apSortOrder(cmd, "name",        i.titleSort);
        apSortOrder(cmd, "artist",      i.artistSort);
        apSortOrder(cmd, "album",       i.albumSort);
        apSortOrder(cmd, "albumartist", i.albumArtistSort);
        apSortOrder(cmd, "composer",    i.composerSort);
        // --sortOrder n'a que ces 5 types (voir --longhelp) : les 8 autres champs de tri n'ont
        // aucun flag dédié, mêmes atomes freeform que le reste de la parité ci-dessous.
        apFreeform(cmd, "CONDUCTOR_SORT", i.conductorSort);
        apFreeform(cmd, "ORCHESTRA_SORT", i.orchestraSort);
        apFreeform(cmd, "ENSEMBLE_SORT",  i.ensembleSort);
        apFreeform(cmd, "CHOIR_SORT",     i.choirSort);
        apFreeform(cmd, "LYRICIST_SORT",  i.lyricistSort);
        apFreeform(cmd, "PRODUCER_SORT",  i.producerSort);
        apFreeform(cmd, "ARRANGER_SORT",  i.arrangerSort);
        apFreeform(cmd, "MIXER_SORT",     i.mixerSort);

        // Pochette
        if (coverImage != null && coverImage.toFile().exists())
            apField(cmd, "--artwork", coverImage.toAbsolutePath().toString());

        // MusicBrainz IDs
        apFreeform(cmd, "MusicBrainz Track Id",        i.recordingMbid);
        apFreeform(cmd, "MusicBrainz Album Id",         i.releaseMbid);
        apFreeform(cmd, "MusicBrainz Release Group Id", i.releaseGroupMbid);
        apFreeform(cmd, "MusicBrainz Artist Id",        i.artistMbid);
        apFreeform(cmd, "MusicBrainz Album Artist Id",  i.albumArtistMbid);

        // Label/statut/support — mêmes champs que le chemin jaudiotagger normal (voir plus haut
        // dans ce fichier), ajoutés ici aussi pour ne pas diverger sur le fallback M4A.
        apFreeform(cmd, "LABEL",                     i.label);
        apFreeform(cmd, "MusicBrainz Album Status",  i.releaseStatus);
        apFreeform(cmd, "MEDIA",                     i.media);

        // Parité avec le chemin jaudiotagger natif — jusqu'à ce correctif, ~30 champs manquaient
        // ici (mood, pays, script, code-barres, ISRC, langue, note, tags, URLs, classique, empreinte
        // AcoustID) alors qu'ils étaient bien calculés en mémoire par la cascade d'enrichissement :
        // silencieusement perdus pour tout fichier retombant sur ce fallback (ex. M4A protégé par
        // DRM iTunes — jaudiotagger natif y échoue systématiquement, AtomicParsley sait l'écrire).
        // Noms d'atome vérifiés en décompilant Mp4FieldKey.class (mêmes constantes que jaudiotagger
        // utilise lui-même pour M4A en écriture native), pas devinés.
        apFreeform(cmd, "MOOD",              i.mood);
        apFreeform(cmd, "MOOD_AGGRESSIVE",   i.moodAggressive);
        apFreeform(cmd, "MOOD_ACOUSTIC",     i.moodAcoustic);
        apFreeform(cmd, "MOOD_ELECTRONIC",   i.moodElectronic);
        apFreeform(cmd, "MOOD_HAPPY",        i.moodHappy);
        apFreeform(cmd, "MOOD_PARTY",        i.moodParty);
        apFreeform(cmd, "MOOD_RELAXED",      i.moodRelaxed);
        apFreeform(cmd, "MOOD_SAD",          i.moodSad);
        apFreeform(cmd, "MOOD_VALENCE",      i.moodValence);
        apFreeform(cmd, "MOOD_AROUSAL",      i.moodArousal);
        apFreeform(cmd, "MOOD_DANCEABILITY", i.moodDanceability);
        apFreeform(cmd, "MOOD_INSTRUMENTAL", i.moodInstrumental);
        apFreeform(cmd, "COUNTRY",           i.country);
        apFreeform(cmd, "SCRIPT",            i.script);
        apFreeform(cmd, "BARCODE",           i.barcode);
        apFreeform(cmd, "ISRC",              i.isrc);
        apFreeform(cmd, "LANGUAGE",          i.language);
        apFreeform(cmd, "RATING",            i.rating);
        apFreeform(cmd, "TAGS",              i.tags);
        apFreeform(cmd, "ACOUSTID_FINGERPRINT", i.acoustidFingerprint);
        apFreeform(cmd, "CLASSICAL_CATALOG", i.classicalCatalog);
        apFreeform(cmd, "MusicBrainz Album Type", i.releaseType);
        apFreeform(cmd, "URL_OFFICIAL_ARTIST_SITE",   i.artistOfficialUrl);
        apFreeform(cmd, "URL_WIKIPEDIA_ARTIST_SITE",  i.artistWikipediaUrl);
        apFreeform(cmd, "URL_DISCOGS_ARTIST_SITE",    i.artistDiscogsUrl);
        apFreeform(cmd, "URL_OFFICIAL_RELEASE_SITE",  i.releaseOfficialUrl);
        apFreeform(cmd, "URL_WIKIPEDIA_RELEASE_SITE", i.releaseWikipediaUrl);
        apFreeform(cmd, "URL_DISCOGS_RELEASE_SITE",   i.releaseDiscogsUrl);
        apFreeform(cmd, "URL_LYRICS_SITE",            i.lyricsUrl);

        // ReplayGain
        apFreeform(cmd, "REPLAYGAIN_TRACK_GAIN", i.replayGainTrackGain);
        apFreeform(cmd, "REPLAYGAIN_TRACK_PEAK", i.replayGainTrackPeak);
        apFreeform(cmd, "REPLAYGAIN_ALBUM_GAIN", i.replayGainAlbumGain);
        apFreeform(cmd, "REPLAYGAIN_ALBUM_PEAK", i.replayGainAlbumPeak);

        // Custom
        apFreeform(cmd, "IS_INSTRUMENTAL",    i.isInstrumental);
        apFreeform(cmd, "DISCOGS_RELEASE_ID", i.discogsId);
        apFreeform(cmd, "APPLE_MUSIC_ID",     i.appleMusicId);
        apFreeform(cmd, "ACOUSTID_ID",        i.acoustidId);

        // Podcast
        apFreeform(cmd, "PODCAST_URL",  i.podcastUrl);
        apFreeform(cmd, "SEASON",       i.podcastSeason);
        apFreeform(cmd, "EPISODE",      i.podcastEpisode);
        apFreeform(cmd, "EPISODETYPE",  i.podcastEpisodeType);
        apFreeform(cmd, "KEYWORDS",     i.podcastKeywords);

        // Statistiques d'écoute
        apFreeform(cmd, "LISTENBRAINZ_PLAYCOUNT", i.listenbrainzPlayCount);

        // Marqueur de taguage (portable, indépendant du cache SQLite)
        apFreeform(cmd, "OT_TAGGEDDATE", i.taggedDate);

        // Deuxième vague de parité (audit exhaustif comparant les ~119 champs du chemin
        // jaudiotagger natif à ce fallback — la vague précédente, commentaire ci-dessus, avait
        // rattrapé mood/pays/etc. mais en avait laissé ~46 autres de côté sans que rien ne le
        // signale). Artistes multiples, contributeurs (hors composer déjà via --composer),
        // classique (hors classicalCatalog/grouping déjà via --grouping/CLASSICAL_CATALOG),
        // flags (hors isCompilation/isInstrumental déjà gérés), tempo fin/tonalité, IDs restants,
        // catalogue/année originale.
        apFreeform(cmd, "ARTISTS",      i.artists);
        apFreeform(cmd, "ARTISTS_SORT", i.artistsSort);

        apFreeform(cmd, "CONDUCTOR", i.conductor);
        apFreeform(cmd, "ORCHESTRA", i.orchestra);
        apFreeform(cmd, "ENSEMBLE",  i.ensemble);
        apFreeform(cmd, "CHOIR",     i.choir);
        apFreeform(cmd, "LYRICIST",  i.lyricist);
        apFreeform(cmd, "PRODUCER",  i.producer);
        apFreeform(cmd, "ARRANGER",  i.arranger);
        apFreeform(cmd, "ENGINEER",  i.engineer);
        apFreeform(cmd, "MIXER",     i.mixer);
        apFreeform(cmd, "DJMIXER",   i.djMixer);

        apFreeform(cmd, "WORK",                i.work);
        apFreeform(cmd, "MusicBrainz Work Id",  i.workMbid);
        apFreeform(cmd, "MOVEMENT",             i.movement);
        apFreeform(cmd, "MOVEMENT_NO",          i.movementNo);
        apFreeform(cmd, "MOVEMENT_TOTAL",       i.movementTotal);
        apFreeform(cmd, "TITLE_MOVEMENT",       i.titleMovement);
        apFreeform(cmd, "PART",                 i.part);
        apFreeform(cmd, "PART_TYPE",            i.partType);
        apFreeform(cmd, "PART_NUMBER",          i.partNo);
        apFreeform(cmd, "PERIOD",               i.period);
        apFreeform(cmd, "OPUS",                 i.opus);
        apFreeform(cmd, "CLASSICAL_NICKNAME",   i.classicalNickname);
        apFreeform(cmd, "SECTION",              i.section);
        apFreeform(cmd, "OVERALL_WORK",         i.overallWork);

        if ("1".equals(i.isClassical))     apFreeform(cmd, "IS_CLASSICAL",      "1");
        if ("1".equals(i.isHD))            apFreeform(cmd, "IS_HD",             "1");
        if ("1".equals(i.isLive))          apFreeform(cmd, "IS_LIVE",           "1");
        if ("1".equals(i.isGreatestHits))  apFreeform(cmd, "IS_GREATEST_HITS",  "1");
        if ("1".equals(i.isSoundtrack))    apFreeform(cmd, "IS_SOUNDTRACK",     "1");

        apFreeform(cmd, "FBPM", i.fbpm);
        // Même conversion Camelot que le chemin natif (voir doWriteNative) — sinon ce fallback
        // écrirait la tonalité brute même quand l'utilisateur a demandé la notation Camelot.
        apFreeform(cmd, "KEY", Config.get().writeCamelotKey() ? toCamelot(i.initialKey) : i.initialKey);

        apFreeform(cmd, "AMAZON_ID",    i.amazonId);
        apFreeform(cmd, "ROONALBUMTAG", i.roonAlbumTag);
        apFreeform(cmd, "ROONTRACKTAG", i.roonTrackTag);

        apFreeform(cmd, "CATALOG_NO",    i.catalogNo);
        apFreeform(cmd, "ORIGINAL_YEAR", i.originalYear);

        cmd.add("--overWrite");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Process p = pb.start();
        Thread drain = Thread.ofVirtual().start(() -> {
            try { p.getInputStream().transferTo(out); }
            catch (Exception ignored) {}
        });
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); throw new Exception("AtomicParsley timeout"); }
        joinQuietly(drain);
        if (p.exitValue() != 0)
            throw new Exception("AtomicParsley exit=" + p.exitValue() + " — "
                + tailOf(out.toString(java.nio.charset.StandardCharsets.UTF_8), 400));
    }

    private static void apField(List<String> cmd, String flag, String value) {
        if (value != null && !value.isBlank()) {
            cmd.add(flag); cmd.add(sanitizeArg(value));
        }
    }

    private static void apSortOrder(List<String> cmd, String type, String value) {
        if (value != null && !value.isBlank()) {
            cmd.add("--sortOrder"); cmd.add(type); cmd.add(sanitizeArg(value));
        }
    }

    /**
     * Atome reverseDNS ("----", même mécanisme que les champs libres MusicBrainz/ReplayGain de
     * jaudiotagger côté M4A natif) — {@code --freeForm}/{@code --freeFormMeaning}/
     * {@code --freeFormValue} qu'utilisait ce code n'existe PAS du tout dans le build
     * AtomicParsley installé ici (paquet Debian, un fork/réécriture différent de l'AtomicParsley
     * "classique" qui avait ce trio de flags) — confirmé en vidant tout {@code --longhelp}, seule
     * la syntaxe {@code --rDNSatom valeur name=NOM domain=com.apple.iTunes} existe. Contrainte non
     * documentée de CE mécanisme, vérifiée en direct : {@code name=} n'accepte AUCUN espace (rejet
     * silencieux avec juste un avertissement "non-conforming", pas un échec de toute la commande —
     * contrairement à un flag carrément inconnu). Or jaudiotagger lui-même écrit littéralement
     * "MusicBrainz Track Id" (avec espaces, vérifié en décompilant Mp4FieldKey.class) — cette
     * convention à espaces est donc irréconciliable avec cette contrainte pour les 4 champs
     * MusicBrainz : les écrire quand même sous une forme différente (underscores) produirait un
     * atome que jaudiotagger/Picard ne reconnaîtraient jamais en relecture — pire que ne rien
     * écrire (donnée fantôme). Les 13 AUTRES champs custom de cette classe (REPLAYGAIN_*,
     * IS_INSTRUMENTAL, DISCOGS_RELEASE_ID...) sont déjà en SCREAMING_SNAKE_CASE sans espace :
     * ceux-là fonctionnent avec ce mécanisme, contrairement à avant où le flag inexistant faisait
     * échouer TOUTE la commande AtomicParsley dès le premier champ custom rencontré.
     */
    private static void apFreeform(List<String> cmd, String name, String value) {
        if (value == null || value.isBlank()) return;
        cmd.add("--rDNSatom"); cmd.add(sanitizeArg(value));
        cmd.add("name=" + name); cmd.add("domain=com.apple.iTunes");
    }

    // ── Fallback M4A via ffmpeg ───────────────────────────────────────────────

    /**
     * Écrit les tags M4A standards via ffmpeg (fallback quand jaudiotagger échoue).
     * Champs supportés : titre, artiste, artiste album, album, année, genre, piste,
     * disque, commentaire, compositeur, paroles, BPM, grouping.
     * Champs NON supportés : MusicBrainz IDs, ReplayGain, sort fields, moods, pochette.
     */
    private static void writeM4aViaFfmpeg(File fichier, TagInfo i, Path coverImage,
                                           Map<String, String> preserved)
            throws Exception {
        // Sortie dans un fichier temporaire — créé avant la commande pour gérer le cas
        // où ffmpeg ne peut pas créer le fichier lui-même (permissions, espace disque)
        File tmp = File.createTempFile("ot_m4a_", ".m4a", fichier.getParentFile());
        tmp.delete(); // ffmpeg crée le fichier lui-même

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg"); cmd.add("-y");
        cmd.add("-i"); cmd.add(fichier.getAbsolutePath());
        // Pas de second input cover : ffmpeg nécessite -map et -disposition pour ça,
        // trop fragile ; la pochette sera ignorée dans ce fallback.
        cmd.add("-c"); cmd.add("copy");
        cmd.add("-movflags"); cmd.add("+faststart"); // moov avant mdat — garanti lisible
        cmd.add("-map_metadata"); cmd.add("-1"); // effacer tags existants

        // COMMENT/DISC_NO/DISC_TOTAL : -map_metadata -1 ci-dessus efface TOUT, y compris ces
        // champs si le taguage n'a rien calculé pour eux — repli sur l'ancienne valeur du fichier
        // (tags.preserved_tags) au lieu de la perdre silencieusement, même logique que le fallback
        // AtomicParsley juste au-dessus.
        String comment    = effectivePreserved(i.comment,    "COMMENT",     preserved);
        String discNo     = effectivePreserved(i.discNo,     "DISC_NO",     preserved);
        String discTotal  = effectivePreserved(i.discTotal,  "DISC_TOTAL",  preserved);

        // Champs standards ffmpeg → M4A
        ffMeta(cmd, "title",        i.title);
        ffMeta(cmd, "artist",       i.artist);
        ffMeta(cmd, "album_artist", i.albumArtist);
        ffMeta(cmd, "album",        i.album);
        ffMeta(cmd, "date",         i.year);
        ffMeta(cmd, "genre",        i.genre);
        ffMeta(cmd, "composer",     i.composer);
        ffMeta(cmd, "comment",      comment);
        ffMeta(cmd, "lyrics",       i.lyrics);
        ffMeta(cmd, "grouping",     i.grouping);

        // Piste : "numéro/total"
        if (!i.track.isBlank()) {
            String trk = i.trackTotal.isBlank() ? i.track : i.track + "/" + i.trackTotal;
            ffMeta(cmd, "track", trk);
        }
        // Disque : "numéro/total"
        if (!discNo.isBlank()) {
            String dsk = discTotal.isBlank() ? discNo : discNo + "/" + discTotal;
            ffMeta(cmd, "disc", dsk);
        }
        if (!i.bpm.isBlank())           ffMeta(cmd, "bpm", i.bpm);
        if (!i.isCompilation.isBlank()) ffMeta(cmd, "compilation", i.isCompilation);
        if (!i.language.isBlank())      ffMeta(cmd, "language", i.language);

        cmd.add(tmp.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            Process p = pb.start();
            Thread drain = Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(out); }
                catch (Exception ignored) {}
            });
            boolean done = p.waitFor(120, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); tmp.delete(); throw new Exception("timeout"); }
            joinQuietly(drain);
            if (p.exitValue() != 0 || !tmp.exists() || tmp.length() == 0) {
                tmp.delete();
                throw new Exception("ffmpeg exit=" + p.exitValue() + " — "
                    + tailOf(out.toString(java.nio.charset.StandardCharsets.UTF_8), 400));
            }
            Files.move(tmp.toPath(), fichier.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            tmp.delete();
            throw new Exception("ffmpeg M4A fallback: " + e.getMessage());
        }
    }

    private static void ffMeta(List<String> cmd, String key, String value) {
        if (value != null && !value.isBlank()) {
            cmd.add("-metadata"); cmd.add(key + "=" + sanitizeArg(value));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** setField via FieldKey, ignore valeurs vides ou clé non supportée par ce format. */
    private static void sf(Tag tag, FieldKey key, String value) {
        if (value == null || value.isBlank()) return;
        try {
            tag.setField(key, value);
        } catch (Exception ignored) {}
    }

    // Table Camelot Wheel — clé Essentia (lettre + dièse, ex "C#", "C#m") → notation Camelot
    // (ex "3B"/"9A"), utilisée par les DJ pour le mixage harmonique. Essentia (EssentiaClient,
    // tonal.key_key + key_scale) ne produit que des dièses, jamais de bémols — table volontairement
    // limitée à ces 24 clés.
    private static final Map<String, String> CAMELOT = Map.ofEntries(
        Map.entry("C",  "8B"),  Map.entry("C#", "3B"),  Map.entry("D",  "10B"), Map.entry("D#", "5B"),
        Map.entry("E",  "12B"), Map.entry("F",  "7B"),  Map.entry("F#", "2B"),  Map.entry("G",  "9B"),
        Map.entry("G#", "4B"),  Map.entry("A",  "11B"), Map.entry("A#", "6B"),  Map.entry("B",  "1B"),
        Map.entry("Cm", "5A"),  Map.entry("C#m","12A"), Map.entry("Dm", "7A"),  Map.entry("D#m","2A"),
        Map.entry("Em", "9A"),  Map.entry("Fm", "4A"),  Map.entry("F#m","11A"), Map.entry("Gm", "6A"),
        Map.entry("G#m","1A"),  Map.entry("Am", "8A"),  Map.entry("A#m","3A"),  Map.entry("Bm", "10A")
    );

    /** Notation Camelot (8A/8B…) pour une clé au format Essentia ("Cm"/"F#"…). Clé non reconnue
     *  (bémol, format inattendu déjà présent dans un fichier tiers…) → renvoyée telle quelle plutôt
     *  que vidée, pour ne jamais faire disparaître une information déjà là. */
    private static String toCamelot(String key) {
        if (key == null || key.isBlank()) return key;
        return CAMELOT.getOrDefault(key.trim(), key);
    }

    /**
     * Champs sans FieldKey (ReplayGain ×4, IS_INSTRUMENTAL, DISCOGS_RELEASE_ID).
     * Dispatch selon le type de tag :
     *   ID3v2   → frame TXXX avec la description comme nom
     *   M4A     → atome freeform ----:com.apple.iTunes:NOM
     *   Vorbis  → clé plain-text (FLAC/OGG)
     */
    private static void setCustomField(Tag tag, String name, String value) {
        if (value == null || value.isBlank()) return;
        try {
            if (tag instanceof AbstractID3v2Tag id3) {
                writeTxxx(id3, name, value);
            } else if (tag instanceof Mp4Tag mp4) {
                writeMp4Freeform(mp4, name, value);
            } else {
                // FLAC / OGG — VorbisComment plain-text
                tag.addField(new VorbisCommentTagField(name.toUpperCase(), value));
            }
        } catch (Exception ignored) {}
    }

    /** Ajoute (ou remplace) une frame TXXX dans un tag ID3v2. */
    private static void writeTxxx(AbstractID3v2Tag id3, String description, String value) {
        try {
            FrameBodyTXXX body = new FrameBodyTXXX((byte) 0, description, value);
            // Choisir la version de frame selon le tag existant. Un tag ID3v2.2 attend des
            // ID3v22Frame (identifiants 3 caractères, ex. "TXX") — lui donner une ID3v23Frame/
            // ID3v24Frame ("TXXX", 4 caractères) produit une frame illisible à la relecture
            // ("Invalid Frame:TXX is invalid frame"), pour CE champ et tous les autres écrits
            // via setCustomField() sur un fichier ID3v2.2 (REPLAYGAIN_*, DISCOGS_RELEASE_ID,
            // PODCAST_*, etc.) — pas seulement les nouveaux champs.
            if (id3 instanceof ID3v24Tag) {
                ID3v24Frame frame = new ID3v24Frame("TXXX");
                frame.setBody(body);
                id3.setFrame(frame);
            } else if (id3 instanceof ID3v22Tag) {
                ID3v23Frame tmp = new ID3v23Frame("TXXX");
                tmp.setBody(body);
                id3.setFrame(new ID3v22Frame(tmp));
            } else {
                ID3v23Frame frame = new ID3v23Frame("TXXX");
                frame.setBody(body);
                id3.setFrame(frame);
            }
        } catch (Exception ignored) {}
    }

    /** Ajoute un atome freeform ----:com.apple.iTunes:NOM dans un tag M4A. */
    private static void writeMp4Freeform(Mp4Tag mp4, String name, String value) {
        try {
            String atomId = "----:com.apple.iTunes:" + name;
            Mp4TagTextField field = new Mp4TagTextField(atomId, value);
            mp4.addField(field);
        } catch (Exception ignored) {}
    }

    /**
     * Contrepartie lecture de {@link #setCustomField} — même dispatch par type de tag, jamais
     * d'exception propagée (comme {@code getTagFirst}). Public : appelé depuis MainFrame.readTags()
     * pour afficher le marqueur OT_TAGGEDDATE au scan, sans re-tagger le fichier.
     */
    public static String getCustomField(Tag tag, String name) {
        try {
            if (tag instanceof AbstractID3v2Tag id3) {
                for (org.jaudiotagger.tag.TagField field : id3.getFields("TXXX")) {
                    if (field instanceof org.jaudiotagger.tag.id3.AbstractID3v2Frame frame
                            && frame.getBody() instanceof FrameBodyTXXX txxx
                            && name.equalsIgnoreCase(txxx.getDescription())) {
                        return txxx.getText();
                    }
                }
                return "";
            } else if (tag instanceof Mp4Tag) {
                String v = tag.getFirst("----:com.apple.iTunes:" + name);
                return v != null ? v : "";
            } else {
                // FLAC / OGG — VorbisComment plain-text, même convention que setCustomField.
                String v = tag.getFirst(name.toUpperCase());
                return v != null ? v : "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    // ── Réparation M4A/MP4 ───────────────────────────────────────────────────

    /**
     * Répare les M4A/MP4 illisibles par jaudiotagger via ffmpeg -movflags +faststart.
     * Idempotent : si le fichier est déjà lisible ET écrivable, ne fait rien.
     */
    public static boolean repairM4aIfNeeded(File f) {
        if (!isM4aFamily(f)) return false;
        try {
            AudioFileIO.read(f);
            return false; // lecture OK — on tente quand même l'écriture normalement
        } catch (Exception e) {
            // Lecture impossible → forcer la réparation
        }
        return runFfmpegRepair(f, null);
    }

    /** Réparation ffmpeg. Si {@code diag} est fourni, la sortie ffmpeg (stdout+stderr) y est
     *  ajoutée en cas d'échec au lieu d'être jetée — sinon un échec complet de la chaîne de
     *  secours ne laissait aucune trace exploitable dans les logs. */
    private static boolean runFfmpegRepair(File f, StringBuilder diag) {
        // `tmp` déclaré AVANT le try (pas comme sa première ligne) : trouvé en vérifiant des
        // fichiers "ot_fix_*.m4a" orphelins laissés dans la bibliothèque réelle — toute exception
        // survenant après File.createTempFile() mais avant un des tmp.delete() explicites
        // ci-dessous (ex. pb.start() qui échoue, ou p.waitFor() interrompu par "Arrêter" —
        // ClosedByInterruptException, observé le même soir sur un autre fichier) tombait dans le
        // catch générique, qui n'avait pas accès à `tmp` (hors de sa portée) pour le nettoyer.
        File tmp = null;
        try {
            tmp = File.createTempFile("ot_fix_", ".m4a", f.getParentFile());
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", f.getAbsolutePath(),
                "-c", "copy", "-movflags", "+faststart", tmp.getAbsolutePath());
            pb.redirectErrorStream(true);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Process p = pb.start();
            Thread drain = Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(out); }
                catch (Exception ignored) {}
            });
            boolean exited = p.waitFor(120, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly(); tmp.delete();
                if (diag != null) diag.append(" | ffmpeg repair: timeout");
                return false;
            }
            joinQuietly(drain);
            if (p.exitValue() == 0 && tmp.length() > 0) {
                Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            tmp.delete();
            if (diag != null) diag.append(" | ffmpeg repair exit=").append(p.exitValue())
                .append(" ").append(tailOf(out.toString(java.nio.charset.StandardCharsets.UTF_8), 400));
            return false;
        } catch (Exception ex) {
            if (tmp != null) tmp.delete();
            if (diag != null) diag.append(" | ffmpeg repair: ").append(ex.getMessage());
            return false;
        }
    }

    private static void joinQuietly(Thread t) {
        try { t.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** Tronque et aplatit une sortie de process pour un message d'erreur lisible. */
    private static String tailOf(String s, int maxChars) {
        s = s.strip().replaceAll("\\s+", " ");
        return s.length() > maxChars ? "…" + s.substring(s.length() - maxChars) : s;
    }

    /** Retire les caractères de contrôle (dont NUL) qu'un tag scrappé peut contenir : un NUL dans
     *  un argument fait planter ProcessBuilder ("invalid null character in command"). Package-privé
     *  (pas private) : réutilisé tel quel par FfmpegTagIO.meta(), qui construisait ses arguments
     *  ffmpeg sans cette protection — un NUL réel dans un tag (Discogs/Last.fm scrappés, ou tag
     *  préexistant corrompu) faisait planter net l'écriture Opus/AAC/WV/WAV, contrairement au
     *  chemin AtomicParsley qui l'avait déjà. */
    static String sanitizeArg(String value) {
        return value.replaceAll("[\\x00-\\x1F\\x7F]", "");
    }

    // ── Merge et preserved — lecture jaudiotagger ─────────────────────────────

    private TagInfo mergeWithExisting(TagInfo info, Tag tag) {
        TagInfo m = info.copy();
        for (FieldKey key : FieldKey.values()) {
            String existing = getTagFirst(tag, key);
            if (existing == null || existing.isBlank()) continue;
            try {
                Field f = fieldFor(key);
                if (f == null) continue;
                String cur = (String) f.get(m);
                if (cur == null || cur.isBlank()) {
                    if (key == FieldKey.TRACK || key == FieldKey.DISC_NO) {
                        int slash = existing.indexOf('/');
                        existing = slash >= 0 ? existing.substring(0, slash).trim() : existing;
                    }
                    f.set(m, existing);
                }
            } catch (Exception ignored) {}
        }
        return m;
    }

    private Map<String, String> readPreservedTags(Tag tag) {
        String raw = Config.get().str("tags.preserved_tags", "");
        Map<String, String> result = new LinkedHashMap<>();
        if (raw.isBlank()) return result;
        for (String name : raw.split("\\|")) {
            name = name.trim();
            if (name.isBlank()) continue;
            try {
                FieldKey key = FieldKey.valueOf(name.toUpperCase());
                String val = getTagFirst(tag, key);
                // Clé = nom canonique de l'enum (pas la casse tapée par l'utilisateur dans le
                // réglage) — les fallbacks M4A (writeM4aViaAtomicParsley/writeM4aViaFfmpeg) font
                // des lookups directs par ce nom, voir effectivePreserved().
                if (val != null && !val.isBlank()) result.put(key.name(), val);
            } catch (IllegalArgumentException ignored) {}
        }
        return result;
    }

    /** Équivalent de {@link #readPreservedTags(Tag)} pour Opus/AAC/WV/APE/WAV — jaudiotagger n'a
     *  AUCUN lecteur pour ces formats (voir write()), donc pas de Tag jaudiotagger disponible ici ;
     *  on relit l'ancien fichier via FfmpegTagIO.read() lui-même, seule source possible pour savoir
     *  ce qu'il y avait avant que FfmpegTagIO.write() ne l'écrase. */
    private Map<String, String> readPreservedTagsViaFfmpeg(File fichier) {
        String raw = Config.get().str("tags.preserved_tags", "");
        Map<String, String> result = new LinkedHashMap<>();
        if (raw.isBlank()) return result;
        TagInfo old;
        try { old = FfmpegTagIO.read(fichier); } catch (Exception e) { return result; }
        for (String name : raw.split("\\|")) {
            name = name.trim();
            if (name.isBlank()) continue;
            try {
                FieldKey key = FieldKey.valueOf(name.toUpperCase());
                Field f = fieldFor(key);
                if (f == null) continue;
                String val = (String) f.get(old);
                if (val != null && !val.isBlank()) result.put(key.name(), val);
            } catch (Exception ignored) {}
        }
        return result;
    }

    /** Applique en place, sur une TagInfo pas encore écrite, les valeurs préservées d'
     *  {@link #readPreservedTagsViaFfmpeg(File)} — seulement pour les champs que le taguage actuel
     *  a laissés vides, même logique que la boucle "Tags preservés" de doWriteNative(). */
    private void applyPreservedToTagInfo(TagInfo copy, Map<String, String> preserved) {
        for (Map.Entry<String, String> e : preserved.entrySet()) {
            try {
                Field f = fieldFor(FieldKey.valueOf(e.getKey()));
                if (f == null) continue;
                String current = (String) f.get(copy);
                if (current == null || current.isBlank()) f.set(copy, e.getValue());
            } catch (Exception ignored) {}
        }
    }

    private String getTagFirst(Tag tag, FieldKey key) {
        try { return tag.getFirst(key); } catch (Exception e) { return ""; }
    }

    /** Valeur fraîchement calculée par le taguage si non vide, sinon la valeur préservée de
     *  l'ancien tag pour ce champ (voir tags.preserved_tags) — utilisé par les fallbacks M4A
     *  (AtomicParsley/ffmpeg), même logique que la boucle "Tags preservés" de doWriteNative(). */
    private static String effectivePreserved(String current, String fieldKeyName, Map<String, String> preserved) {
        if (current != null && !current.isBlank()) return current;
        String p = preserved.get(fieldKeyName);
        return (p != null && !p.isBlank()) ? p : (current != null ? current : "");
    }

    // ConcurrentHashMap : fieldFor() est appelé depuis mergeWithExisting() sur le chemin par
    // défaut (clearExistingTags=false), et write() tourne en parallèle (SaveWorker/BatchProcessor,
    // plusieurs threads sur un même writer partagé) — un HashMap classique ici s'exposait au même
    // risque de corruption sous computeIfAbsent() concurrent que celui déjà évité pour aliasCache
    // dans TaggingWorker/InfoCompleterWorker/AlbumCompletionWorker/BatchProcessor.
    private static final Map<FieldKey, Field> KEY_TO_FIELD = new ConcurrentHashMap<>();

    private Field fieldFor(FieldKey key) {
        return KEY_TO_FIELD.computeIfAbsent(key, k -> {
            String name = switch (k) {
                case TITLE              -> "title";
                case ARTIST             -> "artist";
                case ALBUM_ARTIST       -> "albumArtist";
                case ALBUM              -> "album";
                case YEAR               -> "year";
                case TRACK              -> "track";
                case TRACK_TOTAL        -> "trackTotal";
                case GENRE              -> "genre";
                case DISC_NO            -> "discNo";
                case DISC_TOTAL         -> "discTotal";
                case COMMENT            -> "comment";
                case TITLE_SORT         -> "titleSort";
                case ARTIST_SORT        -> "artistSort";
                case ALBUM_SORT         -> "albumSort";
                case ALBUM_ARTIST_SORT  -> "albumArtistSort";
                case COMPOSER           -> "composer";
                case COMPOSER_SORT      -> "composerSort";
                case LYRICIST           -> "lyricist";
                case LYRICIST_SORT      -> "lyricistSort";
                case ARRANGER           -> "arranger";
                case ARRANGER_SORT      -> "arrangerSort";
                case CONDUCTOR          -> "conductor";
                case CONDUCTOR_SORT     -> "conductorSort";
                case PRODUCER           -> "producer";
                case PRODUCER_SORT      -> "producerSort";
                case ENGINEER           -> "engineer";
                case MIXER              -> "mixer";
                case MIXER_SORT         -> "mixerSort";
                case DJMIXER            -> "djMixer";
                case ORCHESTRA          -> "orchestra";
                case ORCHESTRA_SORT     -> "orchestraSort";
                case ENSEMBLE           -> "ensemble";
                case ENSEMBLE_SORT      -> "ensembleSort";
                case CHOIR              -> "choir";
                case CHOIR_SORT         -> "choirSort";
                case LANGUAGE           -> "language";
                case ISRC               -> "isrc";
                case BPM                -> "bpm";
                case MOOD               -> "mood";
                case LYRICS             -> "lyrics";
                case MUSICBRAINZ_TRACK_ID         -> "recordingMbid";
                case MUSICBRAINZ_ARTISTID         -> "artistMbid";
                case MUSICBRAINZ_RELEASEID        -> "releaseMbid";
                case MUSICBRAINZ_RELEASE_GROUP_ID -> "releaseGroupMbid";
                case RATING             -> "rating";
                case TAGS               -> "tags";
                default -> null;
            };
            if (name == null) return null;
            try {
                Field f = TagInfo.class.getField(name);
                f.setAccessible(true);
                return f;
            } catch (Exception e) { return null; }
        });
    }
}
