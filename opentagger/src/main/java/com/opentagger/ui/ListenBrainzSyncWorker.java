package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
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
            onProgress.accept(I18n.t("Aucun nom d'utilisateur ListenBrainz configuré (Préférences → APIs)."));
            return null;
        }

        log(I18n.t("Récupération des statistiques ListenBrainz pour \"%s\"…", username));
        onProgress.accept(I18n.t("Récupération des statistiques ListenBrainz pour \"%s\"…", username));
        Map<String, Integer> counts = client.fetchTopRecordingCounts(username, Config.get().listenbrainzMaxTracks());
        log(I18n.t("%d piste(s) dans le classement ListenBrainz récupéré.", counts.size()));
        onProgress.accept(I18n.t("%d piste(s) dans le classement ListenBrainz récupéré.", counts.size()));

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
                // Relecture COMPLÈTE du fichier quand l'entrée n'a pas été identifiée pendant cette session
                // (ti = entry.current, issu du cache de scan : ancien contenu partiel, voir TagFieldRegistry) :
                // avec tags.clear_existing_tags=true, TagWriter repart d'un tag vide et n'écrit QUE ce qu'on
                // lui donne — écrire ce TagInfo partiel effaçait pays/label/ReplayGain/ids... du fichier.
                if (entry.result == null) {
                    TagInfo fresh = com.opentagger.TagReader.read(fichier);
                    if (!fresh.title.isBlank() || !fresh.artist.isBlank()) ti = fresh;
                }
                ti.listenbrainzPlayCount = String.valueOf(count);
                writer.write(fichier, ti);

                final FileEntry ef = entry;
                // message : sans lui, la ligne Journal ("✓ Tagué : ...") ne dirait pas pourquoi le
                // fichier a été réécrit — appendLog() l'affiche en suffixe, voir MainFrame.
                final int countFinal = count;
                final TagInfo tiFinal = ti;
                SwingUtilities.invokeLater(() -> {
                    ef.result  = tiFinal;
                    ef.message = I18n.t("ListenBrainz : %d écoute(s)", countFinal);
                    onUpdate.accept(ef);
                });
                log("  " + entry.filename() + " → ListenBrainz " + countFinal + " écoute(s)");
                updated++;
            } catch (Exception ex) {
                log("  ✗ " + entry.filename() + " : " + ex.getMessage());
                onProgress.accept("  ✗ " + entry.filename() + " : " + ex.getMessage());
                errors++;
            }
        }

        log(I18n.t("Terminé — %d mis à jour, %d sans correspondance, %d erreur(s).",
                updated, skipped, errors));
        onProgress.accept(I18n.t("Terminé — %d mis à jour, %d sans correspondance, %d erreur(s).",
                updated, skipped, errors));
        return null;
    }

    @Override
    protected void process(List<FileEntry> chunks) {
        // rien : les mises à jour sont déjà publiées via onUpdate/invokeLater dans doInBackground()
    }

    /** Sans ceci, cette synchro ne laissait AUCUNE trace en dehors du statut UI éphémère
     *  (onProgress→setStatus()) et du Journal en mémoire (appendLog(), jamais imprimé) — repéré en
     *  direct (2026-09-19) : l'utilisateur avait lancé cette synchro 3 fois, mais journalctl ne
     *  montrait absolument rien, contrairement à tout le reste du pipeline (TaggingWorker,
     *  AlbumCompletionWorker...) qui logue déjà sur la console via ce même motif. Même format que
     *  AlbumCompletionWorker.log()/InfoCompleterWorker.log(). */
    private static void log(String msg) {
        System.out.println("[OT " + java.time.LocalTime.now().toString().substring(0, 8) + "] " + msg);
        System.out.flush();
    }

    public int getUpdated() { return updated; }
    public int getSkipped() { return skipped; }
    public int getErrors()  { return errors; }
}
