package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.ListenBrainzClient;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Synchronise le nombre d'écoutes ListenBrainz sur les fichiers déjà identifiés du tableau.
 *
 * Contrairement aux autres enrichissements (Discogs/Last.fm/CAA, un appel réseau par fichier),
 * ListenBrainz n'expose pas d'endpoint "compte pour cette piste précise" — seulement un
 * classement paginé des pistes les plus écoutées. Un seul appel couvre donc tout le lot
 * (ListenBrainzClient.fetchTopRecordingCounts), puis chaque fichier est mis à jour localement par
 * correspondance recordingMbid — les fichiers sans recordingMbid ou absents du classement (au-delà
 * de listenbrainz.max_tracks, ou jamais écoutés) sont simplement ignorés, pas une erreur.
 * Action manuelle uniquement (menu Outils / barre d'outils) — jamais déclenchée automatiquement
 * après un taguage, le fetch top-N étant coûteux à refaire pour un petit lot.
 */
public class ListenBrainzSyncWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>     entries;
    private final Consumer<String>    onProgress;
    private final Consumer<FileEntry> onUpdate;
    private final ListenBrainzClient  client = new ListenBrainzClient();
    private final TagWriter           writer = new TagWriter();

    private int updated = 0, skipped = 0, errors = 0;

    public ListenBrainzSyncWorker(List<FileEntry> entries, Consumer<String> onProgress, Consumer<FileEntry> onUpdate) {
        this.entries    = entries;
        this.onProgress = onProgress;
        this.onUpdate   = onUpdate;
    }

    @Override
    protected Void doInBackground() throws Exception {
        String username = Config.get().listenbrainzUsername();
        if (username.isBlank()) {
            onProgress.accept("Aucun nom d'utilisateur ListenBrainz configuré (Préférences → APIs).");
            return null;
        }

        onProgress.accept("Récupération des statistiques ListenBrainz pour \"" + username + "\"…");
        Map<String, Integer> counts = client.fetchTopRecordingCounts(username, Config.get().listenbrainzMaxTracks());
        onProgress.accept(counts.size() + " piste(s) dans le classement ListenBrainz récupéré.");

        int total = entries.size();
        int done  = 0;
        for (FileEntry entry : entries) {
            if (isCancelled()) break;
            done++;
            onProgress.accept("[" + done + "/" + total + "] " + entry.filename());

            TagInfo ti = entry.activeTags();
            String mbid = ti != null ? ti.recordingMbid : "";
            if (mbid == null || mbid.isBlank()) { skipped++; continue; }

            Integer count = counts.get(mbid);
            if (count == null) { skipped++; continue; }

            try {
                File fichier = entry.currentPath != null ? entry.currentPath.toFile() : entry.file;
                ti.listenbrainzPlayCount = String.valueOf(count);
                writer.write(fichier, ti);

                final FileEntry ef = entry;
                SwingUtilities.invokeLater(() -> { ef.result = ti; onUpdate.accept(ef); });
                updated++;
            } catch (Exception ex) {
                onProgress.accept("  ✗ " + entry.filename() + " : " + ex.getMessage());
                errors++;
            }
        }

        onProgress.accept(String.format("Terminé — %d mis à jour, %d sans correspondance, %d erreur(s).",
                updated, skipped, errors));
        return null;
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        // rien : les mises à jour sont déjà publiées via onUpdate/invokeLater dans doInBackground()
    }

    public int getUpdated() { return updated; }
    public int getSkipped() { return skipped; }
    public int getErrors()  { return errors; }
}
