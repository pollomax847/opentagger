package com.opentagger;

import com.opentagger.model.TagInfo;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Étapes d'enrichissement/bookkeeping partagées entre les pipelines de tagging
 * (App CLI, BatchProcessor, TaggingWorker, InfoCompleterWorker, MatchDialog), pour
 * éviter que chacun recode sa propre version et qu'un correctif appliqué à l'un ne
 * se propage pas aux autres.
 *
 * Les clients (DiscogsClient/LastFmClient/CaaClient/FanArtClient) sont passés en
 * paramètre plutôt qu'instanciés ici : certains appelants tournent dans un pool de
 * threads et doivent garder le contrôle du partage/fraîcheur des instances
 * (voir le commentaire sur LastFmClient dans BatchProcessor — cette classe garde un
 * résultat en cache dans un champ d'instance et ne doit jamais être partagée entre
 * threads concurrents).
 */
public final class TagEnrichment {

    private TagEnrichment() {}

    /** Cascade de genre : Discogs puis Last.fm en repli, uniquement si absent. */
    public static void enrichGenre(TagInfo ti, DiscogsClient discogs, LastFmClient lastFm, MetadataCache cache) {
        if (ti.genre.isBlank()) {
            try { discogs.enrichGenres(ti, cache); } catch (Exception ignored) {}
        }
        if (ti.genre.isBlank()) {
            try { lastFm.enrichGenres(ti, cache); } catch (Exception ignored) {}
        }
    }

    /**
     * Opus/Catalogue/Mouvement/Œuvre globale pour une piste classique, via le Work MB déjà lié à
     * l'enregistrement (voir {@link MusicBrainzClient#resolveClassicalWork}). Gate sur
     * {@code ti.workMbid} non vide plutôt que sur {@code ti.isClassical} : ce dernier vient d'une
     * heuristique locale (liste de noms de compositeurs, {@link LocalCorrector#detectClassical})
     * qui n'est pas câblée dans tous les pipelines (InfoCompleterWorker/AlbumCompletionWorker
     * notamment — même divergence que d'habitude) — un Work MB lié est un signal structurel fiable
     * qui ne dépend d'aucune heuristique ni de son câblage. Ne fait jamais planter l'appelant
     * (réseau, comme enrichGenre ci-dessus).
     */
    public static void enrichClassicalWork(TagInfo ti, MusicBrainzClient mb) {
        if (ti.workMbid.isBlank()) return;
        try { mb.resolveClassicalWork(ti); } catch (Exception ignored) {}
    }

    /**
     * Cascade de pochette : essaie chaque fournisseur activé, dans l'ordre configuré
     * ({@code cover.provider_order} — façon Picard, liste de fournisseurs
     * activables/réordonnables), jusqu'au premier succès. Fournisseurs connus :
     * {@code caa_release}, {@code caa_release_group}, {@code shazam} (URL déjà renvoyée par
     * SongRec au moment de l'identification, voir TagInfo.shazamCoverUrl — aucune recherche
     * supplémentaire, juste un téléchargement), {@code local}, {@code fanart},
     * {@code deezer} (dernier recours texte artiste+album, aucun MBID requis — voir DeezerClient,
     * utile notamment pour les fichiers identifiés par SongRec/AudD/texte sans confirmation
     * MusicBrainz, pour lesquels CAA/FanArt ne peuvent structurellement rien renvoyer).
     */
    public static Path resolveCover(TagInfo ti, File audioFile, CaaClient caa, FanArtClient fanArt,
                                     DeezerClient deezer, MetadataCache cache) {
        for (String provider : Config.get().coverProviderOrder()) {
            Path cover = tryProvider(provider.trim(), ti, audioFile, caa, fanArt, deezer, cache);
            if (cover != null) return cover;
        }
        return null;
    }

    private static Path tryProvider(String provider, TagInfo ti, File audioFile,
                                     CaaClient caa, FanArtClient fanArt, DeezerClient deezer,
                                     MetadataCache cache) {
        try {
            switch (provider) {
                case "caa_release":
                    if (Config.get().caaReleaseEnabled() && !ti.releaseMbid.isBlank())
                        return caa.downloadFromRelease(ti, cache);
                    return null;
                case "caa_release_group":
                    if (Config.get().caaReleaseGroupEnabled() && !ti.releaseGroupMbid.isBlank())
                        return caa.downloadFromReleaseGroup(ti, cache);
                    return null;
                case "shazam":
                    if (Config.get().shazamCoverEnabled() && !ti.shazamCoverUrl.isBlank())
                        return ImageDownloader.downloadToTempFile(ti.shazamCoverUrl, cache);
                    return null;
                case "local":
                    if (Config.get().coverSearchLocal())
                        return findLocalCover(audioFile.getParentFile());
                    return null;
                case "fanart":
                    if (Config.get().fanartEnabled() && !ti.artistMbid.isBlank())
                        return fanArt.downloadCover(ti, cache);
                    return null;
                case "deezer":
                    if (Config.get().deezerEnabled() && !ti.artist.isBlank() && !ti.album.isBlank())
                        return deezer.downloadCover(ti, cache);
                    return null;
                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Noms de fichiers de pochette locale reconnus (aussi utilisé par
     *  {@link FileRenamer#deleteEmptyAncestors} pour savoir quels fichiers restants sont de simples
     *  résidus, pas du contenu réel, quand il nettoie les dossiers vidés de leurs pistes). */
    public static final Set<String> LOCAL_COVER_FILENAMES = Set.of(
            "folder.jpg", "cover.jpg", "front.jpg", "albumart.jpg", "album.jpg",
            "folder.png", "cover.png", "front.png");

    /** Cherche une pochette dans le dossier : folder.jpg, cover.jpg, front.jpg… */
    public static Path findLocalCover(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        for (String name : LOCAL_COVER_FILENAMES) {
            File f = new File(dir, name);
            if (f.exists() && f.length() > 512) return f.toPath();
        }
        return null;
    }

    // ── Identification vidéo (clips téléchargés dans un conteneur non-audio) ──────────────────

    /**
     * Cascade d'identification indépendante pour un fichier vidéo (webm/vob/mpg/avi/mkv…) —
     * SongRec (Shazam) → AcoustID → AudD, même ordre documenté que TaggingWorker.findTags().
     * Écrite à neuf plutôt que réutilisée : findTags() est privée, ~400 lignes, et couplée à des
     * hypothèses inapplicables ici (tags déjà présents sur le fichier, ThreadLocal de session).
     * fpcalc/ffmpeg (dans SongRecClient/AcoustIdClient/AudDClient) n'ont aucune vérification
     * d'extension côté entrée : le fichier vidéo peut leur être passé directement.
     *
     * @return le résultat reconnu, ou {@code null} si rien n'est reconnu — signal pour ne PAS
     *         toucher la vidéo d'origine.
     */
    public static TagInfo identifyFromAudio(File fichier, SongRecClient songRec, AcoustIdClient acoustId,
                                             AudDClient audd, MusicBrainzClient mb,
                                             java.util.function.Consumer<String> log) throws Exception {
        if (SongRecClient.isAvailable()) {
            log.accept("  SongRec...");
            TagInfo sr = songRec.recognize(fichier);
            if (sr != null && !sr.artist.isBlank() && !sr.title.isBlank()) {
                TagInfo result = enrichViaMusicBrainz(sr, mb, log);
                result.identificationSource = MetadataCache.SOURCE_SONGREC;
                return result;
            }
        }

        if (!Config.get().acoustidKey().isBlank()) {
            log.accept("  AcoustID...");
            List<TagInfo> r = acoustId.identify(fichier);
            if (!r.isEmpty()) {
                TagInfo result = r.get(0);
                result.identificationSource = MetadataCache.SOURCE_ACOUSTID;
                return result;
            }
        }

        if (AudDClient.isAvailable()) {
            log.accept("  AudD...");
            TagInfo ad = audd.recognize(fichier);
            if (ad != null && !ad.artist.isBlank() && !ad.title.isBlank()) {
                return enrichViaMusicBrainz(ad, mb, log);
            }
        }

        return null;
    }

    /** Complète un résultat SongRec/AudD brut via une recherche MusicBrainz (MBID, album, piste,
     *  disque…) si elle confirme avec un score ≥ 50 — même seuil que la cascade existante de
     *  TaggingWorker — sinon garde le résultat brut avec score 85. */
    private static TagInfo enrichViaMusicBrainz(TagInfo raw, MusicBrainzClient mb,
                                                 java.util.function.Consumer<String> log) throws Exception {
        List<TagInfo> mbResults = mb.searchRecording(raw.artist, raw.title);
        if (!mbResults.isEmpty() && mbResults.get(0).score >= 50) {
            TagInfo best = mbResults.get(0);
            if (best.album.isBlank()   && !raw.album.isBlank())   best.album   = raw.album;
            if (best.year.isBlank()    && !raw.year.isBlank())    best.year    = raw.year;
            if (best.genre.isBlank()   && !raw.genre.isBlank())   best.genre   = raw.genre;
            if (best.comment.isBlank() && !raw.comment.isBlank()) best.comment = raw.comment;
            best.score = 90;
            log.accept("  → " + best.artist + " – " + best.title + " [" + best.album + "]");
            return best;
        }
        raw.score = 85;
        log.accept("  → " + raw.artist + " – " + raw.title + " (MB non confirmé)");
        return raw;
    }

    /** Enregistre un tagging réussi dans le cache pour éviter les re-lookups. */
    public static void recordSuccess(MetadataCache cache, File fichier, TagInfo written) {
        cache.saveTaggingHistory(written);
        cache.recordFileTagging(fichier.getAbsolutePath(), written.recordingMbid);
    }

    /** Résultat de {@link #saveEntry}. {@code cover} : pochette réellement résolue (ou null si
     *  aucune trouvée) — les appelants qui construisent des suggestions à l'utilisateur (ex.
     *  "Pochette non trouvée") ne peuvent le savoir qu'ICI, pas pendant l'identification. */
    public record SaveResult(TagInfo written, Path cover, Path finalPath, String renameError) {}

    /**
     * Étape "Enregistrer" partagée (façon Picard : le disque n'est touché qu'ici, jamais pendant
     * l'identification) — écrit les tags, renomme si demandé, enregistre le cache/historique,
     * soumet à MusicBrainz. Consolide ce qui était dupliqué (avec des variantes divergentes, voir
     * les notes de session) dans TaggingWorker/AlbumCompletionWorker/InfoCompleterWorker/
     * MatchDialog — notamment le repli par clé synthétique quand recordingMbid est vide, que
     * l'ancien {@link #recordSuccess} n'avait jamais (contrairement à 3 des 4 copies inline qu'il
     * était censé remplacer).
     *
     * La pochette est résolue ICI, pas pendant l'identification : {@code resolveCover} télécharge
     * dans un fichier TEMPORAIRE (CAA/FanArt) — le résoudre pendant l'identification puis ne
     * l'utiliser que plus tard (potentiellement des heures après, voire une autre session une fois
     * Identifier/Enregistrer découplés) risquerait un fichier temporaire déjà nettoyé par l'OS entre
     * temps. Tout le reste de l'enrichissement (genre, BPM, empreinte, paroles, translittération)
     * ne touche que des champs texte de TagInfo, sans souci de durée de vie — ça reste dans la
     * phase Identifier comme avant.
     *
     * @param scanRoot   racine du dossier scanné (repli si aucune bibliothèque configurée), peut
     *                   être null — même résolution de racine que tous les appelants historiques
     *                   (bibliothèque configurée > scanRoot > dossier parent du fichier)
     * @param maskIndex  index du masque de renommage, ou toute valeur < 0 pour ne pas renommer
     *                   (le caller décide : soit directement -1, soit
     *                   {@code Config.get().autoRenameEnabled() ? Config.get().defaultRenameMask() : -1})
     * @param log        callback optionnel pour les messages de soumission MusicBrainz (peut être null)
     */
    public static SaveResult saveEntry(File fichier, TagInfo ti, CaaClient caa, FanArtClient fanArt,
                                        DeezerClient deezer, TagWriter writer, FileRenamer renamer,
                                        MetadataCache cache, MusicBrainzOAuth mbOauth, Path scanRoot,
                                        int maskIndex, java.util.function.Consumer<String> log) throws Exception {
        Path cover = resolveCover(ti, fichier, caa, fanArt, deezer, cache);
        TagInfo written = writer.write(fichier, ti, cover);

        // Copie de la pochette en fichier séparé (cover.jpg à côté de la piste) — même logique
        // que l'ancien bloc inline de TaggingWorker.processEntry(), déplacée ici car elle dépend
        // de `cover`, résolu seulement maintenant (voir plus haut).
        if (cover != null && Config.get().bool("cover.save_to_file", false)) {
            try {
                String fname = Config.get().str("cover.filename", "cover");
                String ext   = cover.getFileName().toString().toLowerCase().endsWith(".png") ? ".png" : ".jpg";
                Path dest = fichier.toPath().resolveSibling(fname + ext);
                if (!java.nio.file.Files.exists(dest) || Config.get().bool("cover.overwrite_file", false))
                    java.nio.file.Files.copy(cover, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}
        }

        // La pochette temporaire (CAA/FanArt/Deezer/Shazam) est déjà embarquée dans le fichier
        // audio (writer.write ci-dessus) et copiée en sidecar si demandé (juste au-dessus) — rien
        // ne la relit après ce point (les appelants ne testent que res.cover() == null). La
        // laisser traîner accumulait un fichier temporaire par piste sur toute une bibliothèque.
        if (cover != null) {
            try { java.nio.file.Files.deleteIfExists(cover); } catch (Exception ignored) {}
        }

        // Paroles synchronisées (.lrc à côté de l'audio, même basename) — même logique/opt-in que
        // la pochette fichier juste au-dessus. Sidecar plutôt qu'un tag embarqué : voir la Javadoc
        // de TagInfo.syncedLyrics pour le pourquoi (support lecteur bien plus large, notamment
        // Plexamp). Écrase sans condition d'"overwrite" séparée (contrairement à la pochette) :
        // un .lrc existant vient forcément d'un enregistrement précédent de CE MÊME fichier, pas
        // d'une source externe à préserver.
        if (!written.syncedLyrics.isBlank() && Config.get().saveLrcFile()) {
            try {
                String name = fichier.getName();
                String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                Path lrcPath = fichier.toPath().resolveSibling(stem + ".lrc");
                java.nio.file.Files.writeString(lrcPath, written.syncedLyrics);
            } catch (Exception ignored) {}
        }

        String cacheKey = !written.recordingMbid.isBlank()
                ? written.recordingMbid : MetadataCache.syntheticKey(written.artist, written.title);
        String source = written.identificationSource.isBlank()
                ? MetadataCache.SOURCE_MBID : written.identificationSource;
        cache.saveTaggingHistory(written, cacheKey);
        cache.recordFileTagging(fichier.getAbsolutePath(), cacheKey, source);

        // Soumission AcoustID différée jusqu'ici : avant la séparation Identifier/Enregistrer,
        // TaggingWorker.processEntry() la déclenchait juste après writer.write(), conditionnée à
        // lastFindTagsSource (ThreadLocal, perdu à la fin de l'appel) — désormais persistée dans
        // TagInfo.identificationSource pour survivre à l'écart de temps entre les deux phases.
        // N'existait QUE dans ce pipeline avant ce correctif (ni albumFirstPass, ni
        // AlbumCompletionWorker, ni InfoCompleterWorker, ni MatchDialog ne soumettaient à
        // AcoustID) — centralisé ici, tous les pipelines qui appellent saveEntry() en bénéficient
        // maintenant de façon uniforme.
        if (MetadataCache.SOURCE_ACOUSTID.equals(source) && !written.recordingMbid.isBlank()) {
            try { new AcoustIdSubmitter().submit(fichier, written); }
            catch (Exception ex) { if (log != null) log.accept("AcoustID submit skip: " + ex.getMessage()); }
        }

        submitToMusicBrainz(mbOauth, written, log != null ? log : msg -> {});

        Path finalPath = fichier.toPath();
        String renameError = null;
        if (maskIndex >= 0) {
            try {
                Path curPath   = fichier.toPath();
                Path oldParent = curPath.getParent();
                String libRoot = Config.get().libraryRoot();
                Path root = (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                        ? java.nio.file.Paths.get(libRoot)
                        : (scanRoot != null ? scanRoot : oldParent);
                Path newPath = renamer.rename(curPath, written, maskIndex, root);
                if (newPath != null) {
                    finalPath = newPath;
                    // Re-classer l'historique sous le nouveau chemin — sinon le prochain scan/
                    // redémarrage ne reconnaît plus ce fichier comme déjà tagué (loadTaggedPaths()
                    // cherche le chemin ACTUEL) et son statut retombe à PENDING malgré un fichier
                    // parfaitement tagué sur disque. cache.deleteFileHistory() existait déjà pour
                    // ça mais n'était appelée nulle part ; MainFrame.buildRenameJob() (renommage en
                    // masse) le fait correctement, ce pipeline (Enregistrer/BatchProcessor/App CLI)
                    // ne le faisait pas.
                    cache.deleteFileHistory(curPath.toFile().getAbsolutePath());
                    cache.recordFileTagging(newPath.toFile().getAbsolutePath(), cacheKey, source);
                    if (Config.get().deleteEmptyDirsAfterRename()) {
                        FileRenamer.deleteEmptyAncestors(oldParent, root);
                    }
                }
            } catch (Exception ex) {
                renameError = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            }
        }
        return new SaveResult(written, cover, finalPath, renameError);
    }

    /**
     * Soumet à MusicBrainz (si OAuth configuré) les tags utilisateur (genre + mood) et le rating
     * du TagInfo pour son recordingMbid — no-op silencieux si OAuth non configuré ou pas de MBID.
     * Anciennement dupliqué à l'identique dans TaggingWorker et InfoCompleterWorker (même risque
     * de divergence que le reste des cascades genre/pochette centralisées ici).
     */
    public static void submitToMusicBrainz(MusicBrainzOAuth mbOauth, TagInfo info,
                                            java.util.function.Consumer<String> log) {
        String token = Config.get().str("mb.oauth.token", "");
        if (token.isBlank() || info.recordingMbid.isBlank()) return;

        java.util.List<String> tags = new java.util.ArrayList<>();
        if (!info.genre.isBlank())
            java.util.Arrays.stream(info.genre.split(",")).map(String::trim)
                    .filter(s -> !s.isBlank()).forEach(tags::add);
        if (!info.mood.isBlank()) tags.add(info.mood);

        try {
            if (!tags.isEmpty()) { mbOauth.submitUserTags(info.recordingMbid, tags, token); log.accept("MB tags soumis: " + tags); }
        } catch (Exception e) { log.accept("MB tags skip: " + e.getMessage()); }

        try {
            int rating = parseStars(info.rating);
            if (rating > 0) { mbOauth.submitRating(info.recordingMbid, rating, token); log.accept("MB rating soumis: " + rating); }
        } catch (Exception e) { log.accept("MB rating skip: " + e.getMessage()); }

        // Ajout à la collection MB personnelle (optionnel — nécessite une collection existante
        // configurée dans Préférences > MusicBrainz). Nécessite releaseMbid (pas recordingMbid :
        // l'API collection travaille sur des releases, pas des enregistrements individuels).
        String collectionId = Config.get().mbCollectionId();
        if (!collectionId.isBlank() && !info.releaseMbid.isBlank()) {
            try {
                mbOauth.addReleaseToCollection(collectionId, info.releaseMbid, token);
                log.accept("MB collection : release ajoutée");
            } catch (Exception e) { log.accept("MB collection skip: " + e.getMessage()); }
        }
    }

    /** Convertit une valeur de rating brute (1-5 ou 1-255) en étoiles 1-5. Retourne 0 si non applicable. */
    private static int parseStars(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int v = Integer.parseInt(raw.trim());
            if (v >= 1 && v <= 5) return v;
            if (v >= 6 && v <= 255) return Math.max(1, Math.min(5, (int) Math.round(v * 5.0 / 255)));
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    /**
     * Translittère l'artiste vers l'alias MusicBrainz dans l'une des locales préférées (par ordre
     * de priorité, voir Config.translateLocales()) si son nom n'est pas en écriture latine (ex.
     * cyrillique, japonais, coréen, chinois, arabe…) et que l'option est activée. {@code
     * aliasCache} évite de refaire le lookup réseau pour le même artiste sur plusieurs pistes —
     * passer une {@code ConcurrentHashMap} si l'appelant tourne dans un pool de threads (voir
     * TaggingWorker).
     */
    public static void translateArtist(TagInfo ti, MusicBrainzClient mb, java.util.Map<String, String> aliasCache) {
        if (!Config.get().translateArtists() || ti.artistMbid.isBlank() || !hasNonLatinChars(ti.artist)) return;
        try {
            String alias = aliasCache.computeIfAbsent(ti.artistMbid, mbid -> {
                try { return mb.lookupArtistAlias(mbid, Config.get().translateLocales()); }
                catch (Exception e) { return ""; }
            });
            if (!alias.isBlank()) {
                ti.artist     = alias;
                ti.artistSort = alias;
            }
        } catch (Exception ignored) {}
    }

    /**
     * Retourne true si la chaîne contient un caractère non-latin (japonais, coréen, chinois,
     * arabe, cyrillique, hébreu, thaï…). Les caractères latins de base + latin étendu
     * (accents FR, etc.) passent.
     */
    public static boolean hasNonLatinChars(String s) {
        if (s == null || s.isBlank()) return false;
        return s.codePoints().anyMatch(cp -> {
            if (!Character.isLetter(cp)) return false;
            if (cp <= 0x024F) return false;
            if (cp >= 0x1E00 && cp <= 0x1EFF) return false; // accents vietnamiens etc.
            return true; // cyrillique, grec, arabe, CJK, hangul, kana…
        });
    }
}
