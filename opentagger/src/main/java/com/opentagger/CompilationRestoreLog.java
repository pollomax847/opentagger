package com.opentagger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Trace chaque "préservation de compilation" (voir TaggingWorker.findTags()) — retour utilisateur
 * (2026-08-17) inquiet que cette restauration fasse confiance aveuglément à d'anciens tags
 * potentiellement faux par-dessus une identification fraîche. Purement en mémoire (perdu au
 * redémarrage), consultable via Outils → Rapport compilations restaurées, cohérent avec le reste
 * de la session qui ne persiste pas ce genre d'historique de diagnostic.
 */
public final class CompilationRestoreLog {

    private CompilationRestoreLog() {}

    public record Entry(String path, String source, String restoredAlbum, String restoredAlbumArtist,
                         String discardedAlbum, String discardedAlbumArtist, long ts) {}

    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    public static void record(String path, String source, String restoredAlbum, String restoredAlbumArtist,
                               String discardedAlbum, String discardedAlbumArtist) {
        entries.add(new Entry(path, source, restoredAlbum, restoredAlbumArtist,
                discardedAlbum, discardedAlbumArtist, System.currentTimeMillis()));
    }

    public static List<Entry> all() {
        return entries;
    }
}
