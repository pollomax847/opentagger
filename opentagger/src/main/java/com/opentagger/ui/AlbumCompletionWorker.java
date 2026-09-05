package com.opentagger.ui;

import com.opentagger.I18n;
import com.opentagger.DiscogsClient;
import com.opentagger.LastFmClient;
import com.opentagger.LocalCorrector;
import com.opentagger.MetadataCache;
import com.opentagger.MusicBrainzClient;
import com.opentagger.MusicBrainzClient.ReleaseTracklist;
import com.opentagger.MusicBrainzClient.ReleaseTrack;
import com.opentagger.TagEnrichment;
import com.opentagger.TaggerScript;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Complète les albums partiellement tagués (ou identifiés, pas encore enregistrés).
 *
 * Algorithme :
 * 1. Groupe les fichiers TAGGED ou IDENTIFIED par releaseMbid (ancres également valables tant
 *    qu'elles ne viennent pas d'une identification texte, voir plus bas).
 * 2. Pour chaque release, récupère la tracklist complète sur MusicBrainz.
 * 3. Identifie les pistes absentes du groupe.
 * 4. Cherche parmi les fichiers SKIPPED/PENDING ceux dont le titre correspond.
 * 5. Identifie les fichiers trouvés avec les métadonnées exactes de la piste (statut IDENTIFIED,
 *    pas encore écrit sur le disque — voir TagEnrichment.saveEntry(), appelé plus tard par
 *    SaveWorker via "Enregistrer tout", façon Picard).
 *
 * Parallélisé par release (un thread-pool, même clé de config "batch.threads" que
 * TaggingWorker/BatchProcessor) — avant ça, cette passe traitait un fichier à la fois avec un
 * lookup MB + enrichissement genre/pochette + empreinte AcoustID + écriture (chaîne de repli M4A
 * comprise) + soumission MB + renommage par fichier, entièrement séquentiel ; sur une grosse
 * bibliothèque avec beaucoup d'albums incomplets détectés, ça pouvait bloquer la passe pendant
 * des heures (constaté en direct : ~48 min sans terminer, bloquant toute nouvelle session de
 * taguage via la garde mutuelle-exclusion de MainFrame). MusicBrainzClient et LastFmClient tiennent
 * un état mutable entre appels (déjà documenté pour TaggingWorker) — instance fraîche par tâche ;
 * DiscogsClient/TaggerScript sont sans état, partagés tels quels. candidateIndex est mutable et
 * partagé entre toutes les releases (un même fichier candidat ne doit être réclamé que par UNE
 * piste) — "trouver + retirer" est donc rendu atomique via synchronized sur la map, pour éviter
 * que deux releases traitées en parallèle ne réclament le même fichier.
 */
public class AlbumCompletionWorker extends SwingWorker<Void, String> {

    private final FileTableModel  tableModel;
    private final Consumer<String>  statusCallback;
    private final Consumer<FileEntry> onUpdate;
    private final Runnable          doneCallback;
    // Barre de progression partagée (bas-droite de MainFrame) — absente jusqu'ici, contrairement à
    // TaggingWorker/SaveWorker/InfoCompleterWorker (retour utilisateur, 2026-08-10 : "verrifie les
    // logs pas de journal... et on ne sait pas combien il reste"). Unité = release/album traité
    // (pas piste individuelle) : c'est déjà l'unité de parallélisation (voir la Javadoc de la
    // classe) et celle du résumé final ("X album(s) analysé(s)"), cohérent avec le reste du worker.
    private final java.util.function.BiConsumer<Integer, Integer> onProgress;
    private volatile int totalReleases = 0;

    // Genre (Discogs/Last.fm) — la tracklist MB n'en fournit pas, donc "ancre MB fiable" ne
    // dispensait pas de cet enrichissement ; jusqu'ici absent, les fichiers complétés par ce
    // worker sortaient sans genre, contrairement aux fichiers tagués par TaggingWorker/
    // InfoCompleterWorker/MatchDialog. La pochette est désormais résolue à l'Enregistrement
    // (TagEnrichment.saveEntry(), via SaveWorker), plus ici — voir processRelease().
    private final DiscogsClient  discogs = new DiscogsClient();
    private final LocalCorrector corrector = new LocalCorrector();
    private final TaggerScript   taggerScript = new TaggerScript();

    private final AtomicInteger matched  = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private final Map<String, String> aliasCache = new ConcurrentHashMap<>();

    // Champ plutôt que variable locale de doInBackground() — même raison que TaggingWorker.pool
    // (voir son commentaire) : sans ça, completionWorker.cancel(true) (le "annuler" self-toggle de
    // MainFrame.completeAlbums()) n'interrompait que le thread de doInBackground(), pas les
    // releases déjà en cours de traitement dans le pool.
    private volatile ExecutorService pool;

    public AlbumCompletionWorker(FileTableModel tableModel,
                                 Consumer<String> statusCallback, Consumer<FileEntry> onUpdate,
                                 Runnable doneCallback) {
        this(tableModel, statusCallback, onUpdate, doneCallback, null);
    }

    public AlbumCompletionWorker(FileTableModel tableModel,
                                 Consumer<String> statusCallback, Consumer<FileEntry> onUpdate,
                                 Runnable doneCallback,
                                 java.util.function.BiConsumer<Integer, Integer> onProgress) {
        this.tableModel     = tableModel;
        this.statusCallback = statusCallback;
        this.onUpdate       = onUpdate;
        this.doneCallback   = doneCallback;
        this.onProgress     = onProgress;
    }

    /** À appeler à la place de cancel(true) directement (SwingWorker.cancel() est final) — voir
     *  TaggingWorker.stopNow(), même raison et même correctif. */
    public void stopNow() {
        ExecutorService p = pool;
        if (p != null) p.shutdownNow();
        cancel(true);
    }

    @Override
    protected Void doInBackground() throws Exception {
        // ── 1. Collecter les releases depuis les fichiers TAGGED ou IDENTIFIED ───
        // releaseMbid → {recordingMbid → FileEntry}
        Map<String, Map<String, FileEntry>> releaseGroups = new LinkedHashMap<>();

        List<FileEntry> candidates = new ArrayList<>(); // SKIPPED/PENDING à compléter

        // Ouvrir le cache ici pour vérifier la source d'identification des ancres TAGGED
        MetadataCache cacheForSrc = new MetadataCache();
        try {
            // allEntries() : sinon un filtre actif au moment du lancement de "Passe complète
            // album" masquait des ancres TAGGED/IDENTIFIED et des candidats SKIPPED/PENDING hors
            // vue — la complétion ratait des albums pourtant présents dans la bibliothèque (même
            // classe de bug que le "round 13" de MainFrame/RenamePreviewDialog).
            for (FileEntry e : tableModel.allEntries()) {
                // IDENTIFIED accepté comme ancre au même titre que TAGGED : porte déjà les mêmes
                // données d'identification complètes (releaseMbid/recordingMbid), juste pas
                // encore écrites sur le disque — inutile d'attendre "Enregistrer tout" pour
                // pouvoir compléter les albums correspondants en mémoire.
                if (e.status == FileEntry.Status.TAGGED || e.status == FileEntry.Status.IDENTIFIED) {
                    // N'utiliser comme ancre de release que les fichiers identifiés par source FIABLE.
                    // SOURCE_TEXT = recherche texte = releaseMbid potentiellement faux → faux positifs.
                    // Pour TAGGED, la source vient du cache SQLite (renseigné à l'Enregistrement) ;
                    // pour IDENTIFIED (jamais passé par cache.recordFileTagging(), qui n'a lieu qu'à
                    // l'Enregistrement), elle vient directement du TagInfo en mémoire — voir
                    // TagInfo.identificationSource, persisté par tous les pipelines d'identification.
                    String source;
                    if (e.status == FileEntry.Status.TAGGED) {
                        String path = (e.currentPath != null ? e.currentPath : e.file.toPath()).toString();
                        source = cacheForSrc.getFileTaggingSource(path);
                    } else {
                        source = e.result != null ? e.result.identificationSource : null;
                    }
                    if (MetadataCache.SOURCE_TEXT.equals(source) || source == null || source.isBlank()) continue;

                    TagInfo ref    = e.result != null ? e.result : e.current;
                    String rMbid   = ref != null ? ref.releaseMbid   : "";
                    String recMbid = ref != null ? ref.recordingMbid : "";
                    if (!rMbid.isBlank() && !recMbid.isBlank()) {
                        releaseGroups
                            .computeIfAbsent(rMbid, k -> new LinkedHashMap<>())
                            .put(recMbid, e);
                    }
                } else if (e.status == FileEntry.Status.SKIPPED || e.status == FileEntry.Status.PENDING) {
                    candidates.add(e);
                }
            }
        } finally {
            cacheForSrc.close();
        }

        if (releaseGroups.isEmpty()) {
            publish(I18n.t("Aucun album identifié parmi les fichiers tagués/identifiés."));
            return null;
        }
        if (candidates.isEmpty()) {
            publish(I18n.t("Aucun fichier SKIPPED/PENDING à compléter."));
            return null;
        }

        totalReleases = releaseGroups.size();
        publish(I18n.t("Analyse de %d album(s) — %d fichier(s) à récupérer possible(s)…",
                releaseGroups.size(), candidates.size()));

        // Index titre normalisé → FileEntry pour les candidats. Partagé entre toutes les tâches
        // parallèles ci-dessous — accès protégé par synchronized (voir processRelease).
        // N'appelle PLUS AudioFileIO.read() par candidat : e.current a déjà été rempli au scan
        // (MainFrame.loadDirectory()/loadSingleFile()) avec les tags exacts du fichier — les
        // relire ici referait le même travail pour rien. Avant ce fix, cette étape (un
        // AudioFileIO.read() par candidat, même en parallèle) pouvait à elle seule prendre
        // plusieurs minutes sur une grosse bibliothèque, avant même que le traitement par release
        // ne démarre — observé en direct via jstack, "Tout tagger" semblait bloqué ici alors que le
        // vrai traitement par release n'avait pas encore commencé. Étant maintenant du pur accès
        // mémoire (pas d'I/O), plus besoin de thread-pool du tout.
        // Map<String, FileEntry> avant ce correctif : un put() sur le même titre normalisé
        // écrasait silencieusement le candidat précédent. Or plusieurs candidats DIFFÉRENTS
        // partagent très souvent le même titre normalisé ("Intro", "Outro", "Interlude", "Skit"
        // existent sur d'innombrables albums différents) — avec plusieurs fichiers "01 - Intro.mp3"
        // non identifiés provenant d'albums différents dans la bibliothèque, un seul restait
        // joignable par cette passe ; les autres devenaient invisibles, sans le moindre message.
        Map<String, List<FileEntry>> candidateIndex = new ConcurrentHashMap<>();
        for (FileEntry e : candidates) {
            if (isCancelled()) break;
            java.nio.file.Path p = e.currentPath != null ? e.currentPath : e.file.toPath();
            if (!java.nio.file.Files.exists(p)) continue; // fichier introuvable, ignorer
            String t = (e.current != null && e.current.title != null) ? e.current.title.trim() : "";
            if (t.isBlank()) t = filenameTitle(p.getFileName().toString());
            String key = normalize(t);
            // ArrayList simple, pas une variante thread-safe : tous les accès (lecture ET
            // suppression) se font plus bas sous synchronized(candidateIndex) — cette boucle de
            // construction, elle, tourne avant tout traitement parallèle.
            if (!key.isBlank())
                candidateIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        // ── 2. Pour chaque release (en parallèle), récupérer la tracklist et compléter
        MetadataCache cache = new MetadataCache();
        try {
            int threads = Math.max(1, com.opentagger.Config.get().num("batch.threads", 3));
            pool = Executors.newFixedThreadPool(threads);
            List<Future<?>> futures = new ArrayList<>();

            for (Map.Entry<String, Map<String, FileEntry>> group : releaseGroups.entrySet()) {
                if (isCancelled()) break;
                String relMbid = group.getKey();
                Map<String, FileEntry> found = group.getValue();
                futures.add(pool.submit(() -> processRelease(
                        relMbid, found, cache, candidateIndex, new MusicBrainzClient(), new LastFmClient())));
            }

            pool.shutdown();
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        } finally {
            cache.close();
        }
        return null;
    }

    /** Traite une release entière : tracklist + toutes ses pistes manquantes. Appelé en parallèle,
     *  un thread par release, depuis le pool créé dans doInBackground(). */
    private void processRelease(String relMbid, Map<String, FileEntry> found, MetadataCache cache,
                                 Map<String, List<FileEntry>> candidateIndex,
                                 MusicBrainzClient mb, LastFmClient lastFm) {
        if (isCancelled()) return;

        ReleaseTracklist tl = fetchTracklist(cache, relMbid, mb);
        if (tl == null) return;
        int done = releases.incrementAndGet();
        if (onProgress != null) onProgress.accept(done, totalReleases);

        publish(I18n.t("Album : %s (%d piste(s) trouvée(s) / %d au total)",
                tl.album(), found.size(), tl.tracks().size()));

        // Avant ce correctif, une piste manquante sans AUCUN candidat correspondant nulle part
        // dans la bibliothèque était simplement ignorée en silence — impossible de savoir, à la
        // fin, LESQUELLES précisément manquaient encore (seulement le compte de départ ci-dessus).
        List<String> stillMissing = new ArrayList<>();

        // Une seule fois par release (comme TrackMatcher.findBestTrack en interne) — ReleaseTrack
        // ne connaît que son propre n° de disque, pas le total de la release.
        int releaseDiscTotal = tl.tracks().stream()
                .mapToInt(MusicBrainzClient.ReleaseTrack::disc).max().orElse(0);

        for (ReleaseTrack track : tl.tracks()) {
            if (isCancelled()) break;
            if (found.containsKey(track.recordingMbid())) continue; // déjà là

            // Chercher dans les candidats par titre, et le réclamer immédiatement : "trouver +
            // retirer" doit être atomique, sinon deux releases traitées en parallèle peuvent
            // réclamer le même fichier candidat.
            FileEntry hit;
            synchronized (candidateIndex) {
                hit = findCandidate(candidateIndex, track, releaseDiscTotal);
                if (hit == null) {
                    stillMissing.add((track.trackNo() > 0 ? track.trackNo() + ". " : "") + track.title());
                    continue;
                }
                // Retire hit de la liste qui le contient réellement (le titre normalisé qui a
                // servi à le trouver peut différer de celui d'un doublon partageant la même clé).
                for (List<FileEntry> bucket : candidateIndex.values()) {
                    if (bucket.remove(hit)) break;
                }
            }

            // Construire le TagInfo complet
            TagInfo ti = new TagInfo();
            ti.title           = track.title();
            ti.artist          = track.artist();
            ti.albumArtist     = tl.albumArtist();
            ti.albumArtistSort = tl.albumArtistSort();
            ti.album           = tl.album();
            ti.year            = tl.year();
            ti.track           = track.trackNo() > 0 ? String.valueOf(track.trackNo()) : "";
            ti.trackTotal      = track.trackTotal() > 0 ? String.valueOf(track.trackTotal()) : "";
            ti.discNo          = track.disc()  > 0 ? String.valueOf(track.disc())  : "";
            ti.releaseMbid     = tl.releaseMbid();
            ti.releaseGroupMbid= tl.releaseGroupMbid();
            ti.recordingMbid   = track.recordingMbid();
            ti.artistMbid      = track.artistMbid();
            ti.isCompilation   = tl.isCompilation() ? "1" : "";
            ti.score           = 100;

            // Translittération artiste (si nom non-Latin et option activée) — même logique
            // partagée que TaggingWorker.
            TagEnrichment.translateArtist(ti, mb, aliasCache);

            java.nio.file.Path writePath = hit.currentPath != null ? hit.currentPath : hit.file.toPath();

            // Script tagger utilisateur — même logique partagée que TaggingWorker/BatchProcessor/
            // App/InfoCompleterWorker.
            taggerScript.apply(ti);

            // detectClassical() (remplit ti.isClassical, la case "Musique classique" du panneau)
            // était absent de ce pipeline — TaggingWorker/BatchProcessor/MatchDialog/App.java
            // l'appellent via LocalCorrector.correct(), mais AlbumCompletionWorker n'a jamais eu de
            // LocalCorrector du tout. enrichClassicalWork() (juste en dessous) contourne déjà le
            // problème pour les DONNÉES (basé sur la présence d'un Work MB, pas sur isClassical),
            // mais la case à cocher elle-même pouvait rester décochée à tort si aucun Work MB
            // n'était trouvé.
            corrector.detectClassical(ti);

            // Genre (Discogs/Last.fm) — la tracklist MB n'en fournit pas, il faut le chercher
            // comme les autres pipelines. La pochette, elle, n'est plus résolue ici : façon
            // Picard, l'identification ne touche jamais le disque (ni la pochette — téléchargée
            // dans un fichier temporaire — ni les tags) ; voir TagEnrichment.saveEntry(), appelé
            // plus tard par SaveWorker ("Enregistrer tout").
            TagEnrichment.enrichGenre(ti, discogs, lastFm, cache);
            // No-op ici en pratique : la tracklist MB (ReleaseTrack) ne porte pas de workMbid,
            // seulement recordingMbid — ajouté quand même pour cohérence avec les autres pipelines
            // et pour rester correct si ReleaseTrack gagne un jour ce champ.
            TagEnrichment.enrichClassicalWork(ti, mb, cache);

            // Empreinte AcoustID : calculée systématiquement après toute identification
            // réussie (TaggingWorker/BatchProcessor/App/MatchDialog le font déjà, comme
            // Picard) — un fichier retrouvé via la tracklist d'album est identifié tout
            // aussi sûrement (score 100 ci-dessus). Reste ici : pur calcul local (fpcalc), ne
            // touche pas le disque du fichier lui-même.
            if (com.opentagger.Config.get().saveAcoustidFingerprints() && ti.acoustidFingerprint.isBlank()
                    && com.opentagger.FpcalcInstaller.isAvailable()) {
                try {
                    ti.acoustidFingerprint = com.opentagger.Fingerprinter.compute(writePath.toFile()).fingerprint();
                } catch (Exception ignored) {}
            }
            ti.identificationSource = MetadataCache.SOURCE_MBID;

            // Muter hit/tableModel SUR l'EDT : ce FileEntry est aussi comparé en
            // direct par le TableRowSorter depuis l'EDT, et une mutation concurrente
            // pendant un tri casse le contrat de Comparator (déjà vu 697× en 3 jours).
            final FileEntry hitFinal = hit;
            SwingUtilities.invokeLater(() -> {
                hitFinal.result  = ti;
                hitFinal.status  = FileEntry.Status.IDENTIFIED;
                hitFinal.message = "";
                tableModel.update(hitFinal);
                // Manquait entièrement au Journal jusqu'ici : ce worker ne publiait que du texte
                // (publish(String), voir process() plus bas) — aucun FileEntry n'était jamais
                // transmis à MainFrame.appendLog(), contrairement à TaggingWorker/InfoCompleterWorker.
                // Résultat : les pistes retrouvées par complétion d'album étaient invisibles dans le
                // Journal (juste un message dans la barre de statut, écrasé aussitôt).
                if (onUpdate != null) onUpdate.accept(hitFinal);
            });

            publish(I18n.t("  ✓ %s → piste %d \"%s\" (identifié, pas encore enregistré)",
                    hit.filename(), track.trackNo(), track.title()));
            matched.incrementAndGet();
        }

        if (!stillMissing.isEmpty()) {
            publish(I18n.t("  ⚠ %s : album toujours incomplet — %d piste(s) introuvable(s) dans la bibliothèque : %s",
                    tl.album(), stillMissing.size(), String.join(", ", stillMissing)));
        }
    }

    @Override
    protected void process(List<String> chunks) {
        // Chaque message publish() (résumé d'album, piste retrouvée, erreur tracklist...) doit
        // rester visible dans le Journal, pas seulement flasher dans la barre de statut — avant ce
        // correctif, SEUL le dernier message de chaque lot batché par SwingWorker atteignait
        // statusCallback (la barre de statut, écrasée en continu) ; tout le reste du lot était
        // perdu sans laisser de trace. Aggravé ici par le traitement en parallèle par release (un
        // thread par album, voir la Javadoc de la classe), qui produit des lots de plusieurs
        // messages à la fois bien plus souvent qu'un traitement séquentiel — retour utilisateur :
        // "pas d'info dans le journal ce que l'application trouve".
        for (String s : chunks) log(s);
        if (!chunks.isEmpty()) statusCallback.accept(chunks.get(chunks.size() - 1));
    }

    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }

    @Override
    protected void done() {
        if (!isCancelled()) {
            statusCallback.accept(I18n.t(
                "Complétion albums — %d album(s) analysé(s), %d piste(s) récupérée(s)",
                releases.get(), matched.get()));
        }
        if (doneCallback != null) doneCallback.run();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ReleaseTracklist fetchTracklist(MetadataCache cache, String relMbid, MusicBrainzClient mb) {
        String cacheKey = "release:" + relMbid;
        try {
            String cached = cache.getLookup(cacheKey);
            if (cached != null) {
                ReleaseTracklist tl = mb.parseReleaseFromCache(cached);
                if (tl != null) return tl;
            }
            ReleaseTracklist tl = mb.lookupRelease(relMbid);
            if (tl != null) {
                String raw = mb.lastRawJson();
                if (!raw.isBlank()) cache.putLookup(cacheKey, raw);
            }
            return tl;
        } catch (Exception e) {
            publish(I18n.t("  ⚠ Impossible de récupérer la tracklist : %s", e.getMessage()));
            return null;
        }
    }

    /** Doit être appelé avec le verrou sur candidateIndex déjà tenu par l'appelant. */
    /**
     * Trouve le meilleur fichier candidat pour UNE piste manquante donnée. Avant ce correctif
     * (2026-07-28, suite à un doute exprimé par l'utilisateur en comparant avec Picard) : une
     * cascade de 3 paliers indépendants (titre exact → inclusion → ≥70% de mots partagés) qui
     * s'arrêtait au PREMIER candidat "compatible artiste" rencontré à chaque palier — jamais de
     * vrai classement entre plusieurs candidats plausibles, et surtout AUCUNE prise en compte de
     * la durée, alors que c'est le signal le plus fort pour départager deux titres qui se
     * ressemblent (ex. deux chansons nommées pareil par deux artistes différents, l'une en 2min,
     * l'autre en 5min). Repris maintenant sur {@link TrackMatcher#scoreTrack}, le même score
     * pondéré (titre+durée+artiste+n°piste+n°disque) que Picard utilise et que le pipeline
     * d'identification principal utilise déjà ailleurs dans ce projet — les 3 anciens paliers
     * servent maintenant seulement à RASSEMBLER les candidats plausibles (pré-filtrage nécessaire :
     * scorer TOUS les fichiers SKIPPED/PENDING contre CHAQUE piste manquante serait bien trop
     * coûteux sur une grosse bibliothèque), plus un vrai argmax + seuil décide ENSUITE, au lieu de
     * s'arrêter au premier qui répond à peu près. artistCompatible() reste un filtre DUR en amont
     * (pas seulement une composante parmi d'autres du score) : conservé tel quel, c'est le
     * garde-fou qui a corrigé un vrai cas trouvé en production (voir sa Javadoc, "Mercy" Madame
     * Monsieur vs Shawn Mendes) — le poids artiste (6) dans le score pondéré seul n'aurait pas
     * suffi à écarter ce genre de faux positif si le titre matchait bien par ailleurs.
     */
    private FileEntry findCandidate(Map<String, List<FileEntry>> index,
                                     MusicBrainzClient.ReleaseTrack track, int releaseDiscTotal) {
        String norm = normalize(track.title());
        if (norm.isBlank()) return null;
        // Piste MB au titre générique ("Unknown", "Track 5"...) : rare sur une vraie release
        // cataloguée mais pas impossible (bootlegs, field recordings) — un candidat SKIPPED tout
        // aussi mal nommé (dictaphone) matcherait sinon même en correspondance "exacte".
        if (isGenericTitle(norm)) return null;

        // Rassemble TOUS les candidats plausibles des 3 anciens paliers (au lieu de retourner au
        // premier trouvé) — un Set pour dédoublonner un même FileEntry apparu dans plusieurs paliers.
        java.util.LinkedHashSet<FileEntry> pool = new java.util.LinkedHashSet<>();
        List<FileEntry> exact = index.get(norm);
        if (exact != null) pool.addAll(exact);
        for (Map.Entry<String, List<FileEntry>> e : index.entrySet()) {
            String k = e.getKey();
            if ((k.contains(norm) && norm.length() >= 12) || (norm.contains(k) && k.length() >= 12))
                pool.addAll(e.getValue());
        }
        String[] trackWords = norm.split("\\s+");
        if (trackWords.length >= 2) {
            int minShared = Math.max(1, (int) (trackWords.length * 0.7));
            for (Map.Entry<String, List<FileEntry>> e : index.entrySet()) {
                if (countSharedWords(trackWords, e.getKey().split("\\s+")) >= minShared)
                    pool.addAll(e.getValue());
            }
        }
        if (pool.isEmpty()) return null;

        double threshold = com.opentagger.Config.get().trackMatchingThreshold();
        FileEntry best = null;
        double bestScore = -1.0;
        for (FileEntry c : pool) {
            if (!artistCompatible(c, track.artist())) continue;
            TagInfo candTags = new TagInfo();
            // Même repli que la construction de l'index ci-dessus (doInBackground()) : un candidat
            // SKIPPED/PENDING a très souvent un titre de tag vide (c'est justement pour ça qu'il
            // n'a pas été identifié normalement) — sans repli sur le nom de fichier, scoreTrack()
            // ignorerait silencieusement la composante titre (poids 22, la plus lourde) pour la
            // quasi-totalité des candidats réels.
            String candTitle = (c.current != null && c.current.title != null && !c.current.title.isBlank())
                    ? c.current.title
                    : filenameTitle((c.currentPath != null ? c.currentPath : c.file.toPath()).getFileName().toString());
            candTags.title = candTitle;
            if (c.current != null) {
                candTags.artist     = c.current.artist;
                candTags.track      = c.current.track;
                candTags.trackTotal = c.current.trackTotal;
                candTags.discNo     = c.current.discNo;
                candTags.discTotal  = c.current.discTotal;
            }
            int durMs = (c.current != null && c.current.durationSec > 0) ? c.current.durationSec * 1000 : -1;
            double score = com.opentagger.TrackMatcher.scoreTrack(candTags, track, durMs, releaseDiscTotal);
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return bestScore >= threshold ? best : null;
    }

    /**
     * Garde-fou ajouté après un cas réel trouvé en testant l'appariement sur une vraie
     * compilation ("110 Hits Été 2018") : une piste titrée juste "Mercy" existe à la fois chez
     * Madame Monsieur (sur cette compilation) et chez Shawn Mendes (ailleurs dans la
     * bibliothèque) — sans cette vérification, un candidat SKIPPED/PENDING nommé "Mercy" par un
     * artiste QUELCONQUE aurait pu être réclamé pour la piste de Madame Monsieur, lui collant un
     * artiste/album/MBID complètement faux. Permissif par construction (comme le matching de
     * titre ci-dessus) : si le candidat n'a AUCUN artiste connu (nom de fichier seul, cas
     * fréquent pour un vrai fichier égaré), on ne peut rien vérifier — on fait alors confiance au
     * seul titre, comme avant ce correctif. Le rejet ne s'applique que si le candidat A un
     * artiste renseigné ET qu'il ne ressemble à rien à l'artiste attendu.
     */
    private boolean artistCompatible(FileEntry candidate, String expectedArtist) {
        String candArtist = (candidate.current != null && candidate.current.artist != null)
                ? candidate.current.artist.trim() : "";
        if (candArtist.isBlank() || expectedArtist == null || expectedArtist.isBlank()) return true;
        String a = normalize(candArtist), b = normalize(expectedArtist);
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private int countSharedWords(String[] a, String[] b) {
        Set<String> setB = new HashSet<>(Arrays.asList(b));
        int count = 0;
        for (String w : a) if (setB.contains(w)) count++;
        return count;
    }

    // Mots sans aucun pouvoir discriminant dans un titre (dictaphone/téléphone : "Recording 001",
    // "Titre_Inconnu", "Musique_29007"...) — un fichier ou une piste ainsi nommé(e) n'est presque
    // jamais un vrai titre catalogué sur MusicBrainz. Laisser deux titres "se ressembler" alors que
    // les deux sont génériques (ex. une piste MB vaguement titrée "Recording 001" et un fichier
    // candidat dictaphone du même nom) revient à faire confiance à du bruit textuel plutôt qu'à une
    // vraie correspondance. Partagé avec TaggingWorker.matchFileToTrack() (même risque, extrait ici
    // pour éviter que ce garde-fou existe dans un seul des deux appariements piste↔fichier du projet
    // — trouvé lors d'un audit ultérieur : ce fix n'avait été appliqué qu'à TaggingWorker jusque-là).
    static final java.util.Set<String> GENERIC_TITLE_WORDS = java.util.Set.of(
            "recording", "rec", "track", "piste", "titre", "title", "inconnu", "unknown",
            "untitled", "download", "audio", "voice", "memo", "musique", "daily", "sound", "clip");

    static boolean isGenericTitle(String normTitle) {
        if (normTitle.isBlank()) return false; // le cas "aucun titre" est géré séparément
        for (String w : normTitle.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (w.chars().allMatch(Character::isDigit)) continue; // un numéro seul est ignoré
            if (!GENERIC_TITLE_WORDS.contains(w)) return false; // mot informatif → pas générique
        }
        return true;
    }

    static String normalize(String s) {
        if (s == null) return "";
        s = s.toLowerCase();
        // Retirer "(feat. X)", "[feat. X]", "(remix)", etc.
        s = s.replaceAll("\\s*\\(feat\\.?.*?\\)", "");
        s = s.replaceAll("\\s*\\[feat\\.?.*?\\]", "");
        s = s.replaceAll("\\s*\\(.*?(?:remix|edit|version|mix|radio|extended|live|karaoke).*?\\)", "");
        // Retirer caractères spéciaux
        s = s.replaceAll("[^a-z0-9 ]", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private String filenameTitle(String filename) {
        // Retire l'extension
        int dot = filename.lastIndexOf('.');
        String name = dot > 0 ? filename.substring(0, dot) : filename;
        // Retire le numéro de piste en tête : "03 - " ou "03."
        name = name.replaceAll("^\\d{1,3}[\\s.\\-_]+", "");
        // Retire "Artiste - " en tête si présent
        name = name.replaceAll("^[^-]+ - ", "");
        return name.trim();
    }
}
