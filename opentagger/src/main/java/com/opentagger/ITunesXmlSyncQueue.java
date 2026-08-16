package com.opentagger;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File d'attente EN MÉMOIRE des changements à répercuter dans le XML iTunes — jamais appliquée
 * automatiquement (voir ITunesXmlWriter.apply(), action manuelle explicite requise). Alimentée par
 * FileRenamer (un fichier renommé/déplacé dont on connaît le Track ID iTunes — voir
 * knownTrackIdByPath, renseigné par ITunesImportDialog lors d'un import) et par MainFrame (édition
 * d'une note sur un fichier dont le Track ID est connu).
 *
 * Purement en mémoire, perdue au redémarrage — sans conséquence réelle : un changement non encore
 * répercuté reste simplement visible tel quel côté OpenTagger/disque, rien n'est perdu, juste pas
 * encore propagé vers le XML tant que l'utilisateur ne déclenche pas l'écriture.
 */
public final class ITunesXmlSyncQueue {

    private ITunesXmlSyncQueue() {}

    /** Path connu → Track ID iTunes — mis à jour à chaque renommage pour que le PROCHAIN
     *  renommage du même fichier soit encore suivi (pas seulement le premier). */
    private static final Map<Path, Integer> knownTrackIdByPath = new ConcurrentHashMap<>();

    private static final Map<Integer, Path>    pendingLocations = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> pendingRatings   = new ConcurrentHashMap<>();

    /** Enregistre l'association initiale, établie par un import XML (voir ITunesImportDialog). */
    public static void registerKnownPath(Path path, int trackId) {
        knownTrackIdByPath.put(path.toAbsolutePath().normalize(), trackId);
    }

    /** Appelé par FileRenamer.moveFile() pour CHAQUE déplacement — no-op silencieux si {@code src}
     *  ne correspond à aucun Track ID connu (immense majorité des fichiers, jamais importés depuis
     *  iTunes). Fait suivre l'association vers le nouveau chemin pour le prochain renommage. */
    public static void onFileMoved(Path src, Path dst) {
        Integer trackId = knownTrackIdByPath.remove(src.toAbsolutePath().normalize());
        if (trackId == null) return;
        Path dstAbs = dst.toAbsolutePath().normalize();
        knownTrackIdByPath.put(dstAbs, trackId);
        pendingLocations.put(trackId, dstAbs);
    }

    /** Appelé quand une note est modifiée sur un fichier de Track ID connu (voir MainFrame). */
    public static void queueRatingChange(int trackId, int stars) {
        pendingRatings.put(trackId, Math.max(1, Math.min(5, stars)));
    }

    public static int pendingCount() {
        Set<Integer> all = new HashSet<>(pendingLocations.keySet());
        all.addAll(pendingRatings.keySet());
        return all.size();
    }

    /** Instantané des changements en attente, PUIS vidage — l'appelant (ITunesXmlWriter.apply())
     *  est responsable de les appliquer ; en cas d'échec d'écriture, les changements sont perdus
     *  de la file (pas de ré-essai automatique) mais restent visibles côté disque/OpenTagger,
     *  aucune perte de données réelle, juste à relancer manuellement si besoin. */
    public static Map<Integer, ITunesXmlWriter.PendingChange> snapshotAndClear() {
        Map<Integer, ITunesXmlWriter.PendingChange> out = new HashMap<>();
        Set<Integer> all = new HashSet<>(pendingLocations.keySet());
        all.addAll(pendingRatings.keySet());
        for (int trackId : all) {
            out.put(trackId, new ITunesXmlWriter.PendingChange(
                    pendingLocations.get(trackId), pendingRatings.get(trackId)));
        }
        pendingLocations.clear();
        pendingRatings.clear();
        return out;
    }
}
