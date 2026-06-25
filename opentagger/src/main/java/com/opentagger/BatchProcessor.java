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
import java.util.concurrent.atomic.AtomicLong;

public class BatchProcessor {

    private final int SEUIL_AUTO = Config.get().minScoreAuto();

    // Rate limiter partagé pour respecter MB 1 req/s (comme Picard ratecontrol.py)
    // Toutes les requêtes MB passent par ce token bucket, pas seulement 1 par fichier.
    private static final AtomicLong LAST_MB_REQUEST_MS = new AtomicLong(0);
    private static final long       MB_MIN_INTERVAL_MS = 1050;

    private final MusicBrainzClient mbClient  = new MusicBrainzClient();
    private final AcoustIdClient    acoustId  = new AcoustIdClient();
    private final DiscogsClient     discogs   = new DiscogsClient();
    private final LastFmClient      lastFm    = new LastFmClient();
    private final FanArtClient      fanArt    = new FanArtClient();
    private final LocalCorrector    corrector = new LocalCorrector();
    private final TagWriter         writer    = new TagWriter();
    private final FileRenamer       renamer   = new FileRenamer();
    private final MetadataCache     cache     = new MetadataCache();
    private final boolean           useAcoustId;
    private final int               maskIndex;

    // Compteurs thread-safe pour le résumé final
    private final AtomicInteger total      = new AtomicInteger(0);
    private final AtomicInteger appliques  = new AtomicInteger(0);
    private final AtomicInteger renommes   = new AtomicInteger(0);
    private final AtomicInteger incertains = new AtomicInteger(0);
    private final AtomicInteger erreurs    = new AtomicInteger(0);

    public BatchProcessor(boolean useAcoustId, int maskIndex) {
        this.useAcoustId = useAcoustId;
        this.maskIndex   = maskIndex;
    }

    public void process(List<File> fichiers) {
        total.set(fichiers.size());
        System.out.println("Fichiers trouvés : " + total);
        System.out.println();

        // Traitement parallèle : 3 threads max (limité par le rate-limit MB 1 req/s)
        // Au-delà de 3, les threads se bloquent mutuellement dans mbRateLimit()
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

    /**
     * Respecte le rate-limit MB : attend le temps nécessaire pour garantir
     * au moins MB_MIN_INTERVAL_MS entre deux requêtes sur l'ensemble des threads.
     * Analogue à Picard's ratecontrol.get_delay_to_next_request().
     */
    public static synchronized void mbRateLimit() {
        long now  = System.currentTimeMillis();
        long last = LAST_MB_REQUEST_MS.get();
        long wait = MB_MIN_INTERVAL_MS - (now - last);
        if (wait > 0) {
            try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        LAST_MB_REQUEST_MS.set(System.currentTimeMillis());
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
                return;
            }

            TagInfo best = resultats.get(0);

            if (best.score >= SEUIL_AUTO) {
                corrector.correct(best, fichier.toPath());

                if (best.genre.isBlank()) {
                    System.out.println("  → Genres via Discogs...");
                    try { discogs.enrichGenres(best); } catch (Exception e) { /* ignore */ }
                }
                if (best.genre.isBlank()) {
                    System.out.println("  → Genres via Last.fm...");
                    try { lastFm.enrichGenres(best); } catch (Exception e) { /* ignore */ }
                }

                Path cover = null;
                if (!best.artistMbid.isBlank()) {
                    System.out.println("  → Pochette via FanArt.tv / CAA...");
                    try { cover = fanArt.downloadCover(best); } catch (Exception e) { /* ignore */ }
                }
                writer.write(fichier, best, cover);
                appliques.incrementAndGet();

                // Sauvegarder dans l'historique pour éviter les re-lookups
                cache.saveTaggingHistory(best);
                cache.recordFileTagging(fichier.getAbsolutePath(), best.recordingMbid);

                String renomme = "—";
                if (maskIndex >= 0) {
                    try {
                        Path nouveau = renamer.rename(fichier.toPath(), best, maskIndex);
                        if (nouveau != null) { renommes.incrementAndGet(); renomme = nouveau.getFileName().toString(); }
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
            }

        } catch (Exception e) {
            System.out.println("  ✗ Erreur : " + e.getMessage());
            erreurs.incrementAndGet();
        }
    }

    private List<TagInfo> findTags(File fichier) throws Exception {
        // Stratégie 1 : AcoustID (empreinte audio) si activé
        if (useAcoustId) {
            System.out.println("  → Analyse audio (AcoustID)...");
            // AcoustID fait un appel réseau MB interne — rate-limit appliqué à l'intérieur
            List<TagInfo> resultats = acoustId.identify(fichier);
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
        mbRateLimit(); // respecter 1 req/s MB
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
