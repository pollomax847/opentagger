package com.opentagger;

import com.opentagger.model.TagInfo;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class BatchProcessor {

    private final int SEUIL_AUTO = Config.get().minScoreAuto();

    // mbClient / acoustId / lastFm sont volontairement NON partagés entre les threads du pool
    // (contrairement à discogs/fanArt/corrector/writer/renamer/cache, qui sont sans état
    // inter-appel ou déjà synchronisés) : ces trois classes gardent le résultat du dernier
    // appel dans un champ d'instance relu juste après (lastRawJson, lastFingerprint,
    // cachedTagsKey/List...). Les partager entre threads concurrents faisait qu'un thread
    // pouvait lire/mettre en cache le résultat du fichier d'un AUTRE thread — corruption
    // silencieuse de tags. Une instance fraîche par tâche coûte rien de plus (le HttpClient
    // sous-jacent, lui, reste statique/partagé dans chaque classe).
    // Partagé entre threads : ConcurrentHashMap (contrairement à LastFmClient/MusicBrainzClient,
    // une simple Map<String,String> de cache n'a pas d'état interne dangereux à partager).
    private final java.util.Map<String, String> aliasCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final DiscogsClient     discogs      = new DiscogsClient();
    private final FanArtClient      fanArt       = new FanArtClient();
    private final LocalCorrector    corrector    = new LocalCorrector();
    private final TaggerScript      taggerScript = new TaggerScript();
    private final TagWriter         writer       = new TagWriter();
    private final FileRenamer       renamer      = new FileRenamer();
    private final MetadataCache     cache        = new MetadataCache();
    private final boolean           useAcoustId;
    private final int               maskIndex;
    private final Path              scanRoot;

    // Compteurs thread-safe pour le résumé final
    private final AtomicInteger total      = new AtomicInteger(0);
    private final AtomicInteger appliques  = new AtomicInteger(0);
    private final AtomicInteger renommes   = new AtomicInteger(0);
    private final AtomicInteger incertains = new AtomicInteger(0);
    private final AtomicInteger erreurs    = new AtomicInteger(0);

    public BatchProcessor(boolean useAcoustId, int maskIndex) {
        this(useAcoustId, maskIndex, null);
    }

    /**
     * @param scanRoot dossier scanné (mode {@code --dossier}) : sert de racine commune pour le
     *                 renommage par masque, comme entry.scanRoot côté GUI. Sans lui (constructeur
     *                 à 2 arguments), chaque fichier utilisait son PROPRE dossier parent comme
     *                 racine — un masque à sous-dossiers ("AlbumArtist/Album/Track") imbriquait
     *                 alors la nouvelle arborescence À L'INTÉRIEUR du dossier de chaque fichier au
     *                 lieu de réorganiser à la racine du dossier scanné.
     */
    public BatchProcessor(boolean useAcoustId, int maskIndex, Path scanRoot) {
        this.useAcoustId = useAcoustId;
        this.maskIndex   = maskIndex;
        this.scanRoot    = scanRoot;
    }

    public void process(List<File> fichiers) {
        total.set(fichiers.size());
        System.out.println("Fichiers trouvés : " + total);
        System.out.println();

        // Traitement parallèle : le rate-limit MB (1 req/s, centralisé dans
        // MusicBrainzClient.getWithRetry()) borne de toute façon le débit réel des requêtes MB
        // quel que soit le nombre de threads ; au-delà de 3, le gain vient surtout des étapes non-MB
        // (BPM, paroles, empreinte, écriture disque) qui peuvent, elles, tourner en parallèle.
        int threads = Config.get().num("batch.threads", 3);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < fichiers.size(); i++) {
            final int idx = i;
            final File f  = fichiers.get(i);
            futures.add(pool.submit(() -> {
                System.out.printf("[%d/%d] %s%n", idx + 1, total.get(), f.getName());
                processOne(f);
                System.out.println();
            }));
        }

        pool.shutdown();
        for (Future<?> future : futures) {
            try { future.get(); }
            catch (Exception e) { erreurs.incrementAndGet(); }
        }

        cache.close();
        printSummary();
    }

    private void processOne(File fichier) {
        try {
            // Vérifier l'historique : si ce fichier a déjà été tagué, réutiliser le résultat
            String cachedMbid = cache.getFileTagging(fichier.getAbsolutePath());
            if (cachedMbid != null) {
                TagInfo hist = cache.getTaggingHistory(cachedMbid);
                if (hist != null) {
                    System.out.printf("  ✓ (cache) %s - %s%n", hist.artist, hist.title);
                    writer.write(fichier, hist);
                    appliques.incrementAndGet();
                    return;
                }
            }

            List<TagInfo> resultats = findTags(fichier);

            if (resultats.isEmpty()) {
                System.out.println("  ✗ Aucun résultat trouvé.");
                incertains.incrementAndGet();
                moveIfConfiguredSkipped(fichier);
                return;
            }

            TagInfo best = resultats.get(0);

            if (best.score >= SEUIL_AUTO) {
                corrector.correct(best, fichier.toPath());
                taggerScript.apply(best);

                // Translittération artiste (si nom non-Latin et option activée) — instance
                // MusicBrainzClient fraîche, comme findTags() (pas de partage inter-threads).
                TagEnrichment.translateArtist(best, new MusicBrainzClient(), aliasCache);

                // Empreinte AcoustID même si l'identification vient du texte (pas seulement
                // d'AcoustID) — comme Picard. Fingerprinter est thread-safe (sémaphore statique).
                if (Config.get().saveAcoustidFingerprints() && best.acoustidFingerprint.isBlank()
                        && FpcalcInstaller.isAvailable()) {
                    try { best.acoustidFingerprint = Fingerprinter.compute(fichier).fingerprint(); }
                    catch (Exception ignored) {}
                }

                // BPM (ffmpeg) — présent dans le pipeline GUI (TaggingWorker/InfoCompleterWorker)
                // mais absent ici jusqu'à présent : le CLI/batch ne calculait jamais le BPM, même
                // avec les mêmes réglages activés.
                if (best.bpm.isBlank() && BpmDetector.isAvailable()) {
                    int bpm = new BpmDetector().detect(fichier.getAbsolutePath());
                    if (bpm > 0) best.bpm = String.valueOf(bpm);
                }

                // Instance LastFmClient fraîche : voir le commentaire de classe sur le
                // partage inter-threads (cachedTagsKey/cachedTagsList d'instance).
                LastFmClient lastFm = new LastFmClient();
                TagEnrichment.enrichGenre(best, discogs, lastFm, cache);
                TagEnrichment.enrichClassicalWork(best, new MusicBrainzClient());
                // Mood + URLs artiste Last.fm — même gap : absents du CLI/batch jusqu'à présent.
                if (best.mood.isBlank()) { try { lastFm.enrichMood(best, cache); } catch (Exception ignored) {} }
                try { lastFm.enrichArtistUrls(best, cache); } catch (Exception ignored) {}

                // Paroles — même gap : absentes du CLI/batch jusqu'à présent.
                try { new LyricsClient().enrich(best); } catch (Exception ignored) {}

                Path cover = TagEnrichment.resolveCover(best, fichier, new CaaClient(), fanArt, cache);
                writer.write(fichier, best, cover);
                appliques.incrementAndGet();

                // Sauvegarder dans l'historique pour éviter les re-lookups
                TagEnrichment.recordSuccess(cache, fichier, best);

                String renomme = "—";
                if (maskIndex >= 0) {
                    try {
                        Path oldParent = fichier.toPath().getParent();
                        // Même résolution de racine que le pipeline GUI (TaggingWorker) : la
                        // bibliothèque configurée en priorité, sinon le dossier scanné — sans ça
                        // (avant ce correctif) chaque fichier utilisait son propre dossier parent,
                        // imbriquant la nouvelle arborescence au lieu de réorganiser à la racine.
                        String libRoot = Config.get().libraryRoot();
                        Path root = (!libRoot.isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libRoot)))
                                ? java.nio.file.Paths.get(libRoot)
                                : (scanRoot != null ? scanRoot : oldParent);
                        Path nouveau = renamer.rename(fichier.toPath(), best, maskIndex, root);
                        if (nouveau != null) {
                            renommes.incrementAndGet();
                            renomme = nouveau.getFileName().toString();
                            if (Config.get().deleteEmptyDirsAfterRename()) {
                                FileRenamer.deleteEmptyAncestors(oldParent, root);
                            }
                        }
                    } catch (Exception e) { /* ignore */ }
                }

                System.out.printf("  ✓ (%d%%) %s - %s | Genre: %s | Pochette: %s | Renommé: %s%n",
                        best.score, best.artist, best.title,
                        best.genre.isBlank() ? "—" : best.genre,
                        cover != null ? "✓" : "—",
                        renomme);
            } else {
                System.out.printf("  ? Incertain (%d%%) : %s - %s — ignoré%n",
                        best.score, best.artist, best.title);
                incertains.incrementAndGet();
                moveIfConfiguredSkipped(fichier);
            }

        } catch (Exception e) {
            System.out.println("  ✗ Erreur : " + e.getMessage());
            erreurs.incrementAndGet();
            moveIfConfiguredSkipped(fichier);
        }
    }

    /** Déplace un fichier non tagué (aucun résultat / score < seuil / erreur) vers le dossier
     *  dédié si configuré — mêmes règles que le pipeline GUI (TaggingWorker). */
    private void moveIfConfiguredSkipped(File fichier) {
        if (!Config.get().skippedMoveEnabled()) return;
        String folder = Config.get().skippedMoveFolder();
        if (folder.isBlank()) return;
        try {
            FileRenamer.moveToFolder(fichier.toPath(), java.nio.file.Paths.get(folder));
        } catch (Exception ignored) {}
    }

    private List<TagInfo> findTags(File fichier) throws Exception {
        // Instances fraîches par appel (voir commentaire sur les champs de la classe) : ce
        // findTags() tourne en parallèle sur jusqu'à batch.threads threads différents.
        MusicBrainzClient mbClient = new MusicBrainzClient();

        // Stratégie 1 : AcoustID (empreinte audio) si activé
        if (useAcoustId) {
            System.out.println("  → Analyse audio (AcoustID)...");
            // AcoustID fait un appel réseau MB interne — rate-limit appliqué à l'intérieur
            List<TagInfo> resultats = new AcoustIdClient().identify(fichier);
            if (!resultats.isEmpty()) return resultats;
            System.out.println("  → AcoustID sans résultat, essai par tags texte...");
        }

        // Stratégie 2 : recherche MusicBrainz par tags existants
        String artiste = getTag(fichier, FieldKey.ARTIST);
        String titre   = getTag(fichier, FieldKey.TITLE);

        if (artiste.isBlank() && titre.isBlank()) {
            System.out.println("  → Aucun tag existant et AcoustID désactivé.");
            return List.of();
        }

        // Vérifier le cache MB avant l'appel réseau
        String qHash = MetadataCache.queryHash(artiste, titre);
        String cached = cache.getRecordingSearch(qHash);
        if (cached != null) {
            System.out.printf("  → Cache MB : \"%s - %s\"%n", artiste, titre);
            return mbClient.parseFromCache(cached);
        }

        System.out.printf("  → Recherche MusicBrainz : \"%s - %s\"%n", artiste, titre);
        List<TagInfo> results = mbClient.searchRecording(artiste, titre);

        // Mettre en cache la réponse
        String rawJson = mbClient.lastRawJson();
        if (!rawJson.isBlank()) cache.putRecordingSearch(qHash, rawJson);

        return results;
    }

    private String getTag(File fichier, FieldKey key) {
        try {
            AudioFile audio = AudioFileIO.read(fichier);
            Tag tag = audio.getTag();
            return tag != null ? tag.getFirst(key) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private void printSummary() {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║  Résumé du traitement                        ║");
        System.out.println("╠══════════════════════════════════════════════╣");
        System.out.printf( "║  Total traité  : %d%n", total.get());
        System.out.printf( "║  ✓ Appliqués   : %d%n", appliques.get());
        System.out.printf( "║  ✓ Renommés    : %d%n", renommes.get());
        System.out.printf( "║  ? Incertains  : %d (score < %d%%)%n", incertains.get(), SEUIL_AUTO);
        System.out.printf( "║  ✗ Erreurs     : %d%n", erreurs.get());
        System.out.println("╚══════════════════════════════════════════════╝");
    }
}
