package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.LastFmClient;
import com.opentagger.TagWriter;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import javax.swing.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Synchronise le nombre d'écoutes Last.fm sur les fichiers déjà identifiés du tableau — même
 * architecture que {@link ListenBrainzSyncWorker} (voir son commentaire pour le pourquoi d'un seul
 * fetch en masse plutôt qu'un appel par fichier), juste avec LastFmClient.fetchTopTrackCounts() à la
 * place. Demande utilisateur (2026-08-29), vérifiée en direct contre l'API Last.fm réelle avant
 * implémentation (user.getTopTracks renvoie bien un MBID par piste quand résolu côté Last.fm).
 * Action manuelle uniquement (menu Outils / barre d'outils) — jamais déclenchée automatiquement.
 */
public class LastFmSyncWorker extends SwingWorker<Void, FileEntry> {

    private final List<FileEntry>     entries;
    private final Consumer<String>    onProgress;
    private final Consumer<FileEntry> onUpdate;
    private final LastFmClient        client = new LastFmClient();
    private final TagWriter           writer = new TagWriter();

    private int updated = 0, skipped = 0, errors = 0;

    public LastFmSyncWorker(List<FileEntry> entries, Consumer<String> onProgress, Consumer<FileEntry> onUpdate) {
        this.entries    = entries;
        this.onProgress = onProgress;
        this.onUpdate   = onUpdate;
    }

    @Override
    protected Void doInBackground() throws Exception {
        String username = Config.get().lastfmUsername();
        if (username.isBlank()) {
            onProgress.accept(I18n.t("Aucun nom d'utilisateur Last.fm configuré (Préférences → APIs)."));
            return null;
        }

        onProgress.accept(I18n.t("Récupération des statistiques Last.fm pour \"%s\"…", username));
        Map<String, Integer> counts = client.fetchTopTrackCounts(username, Config.get().lastfmMaxTracks());
        onProgress.accept(I18n.t("%d piste(s) dans le classement Last.fm récupéré.", counts.size()));

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
                ti.lastfmPlayCount = String.valueOf(count);
                writer.write(fichier, ti);

                final FileEntry ef = entry;
                // message : sans lui, la ligne Journal ("✓ Tagué : ...") ne dirait pas pourquoi le
                // fichier a été réécrit — appendLog() l'affiche en suffixe, voir MainFrame.
                final int countFinal = count;
                SwingUtilities.invokeLater(() -> {
                    ef.result  = ti;
                    ef.message = I18n.t("Last.fm : %d écoute(s)", countFinal);
                    onUpdate.accept(ef);
                });
                updated++;
            } catch (Exception ex) {
                onProgress.accept("  ✗ " + entry.filename() + " : " + ex.getMessage());
                errors++;
            }
        }

        onProgress.accept(I18n.t("Terminé — %d mis à jour, %d sans correspondance, %d erreur(s).",
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
