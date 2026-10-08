package com.opentagger.ui;

import com.opentagger.Config;
import com.opentagger.I18n;
import com.opentagger.TrashHelper;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Doublons écartés automatiquement à l'Enregistrement (2026-09-26, demande utilisateur : « je ne
 * veux plus passer par un outil à lancer manuellement »). Remplace, pour les cas SÛRS, la
 * recherche manuelle (DuplicatesDialog, qui reste disponible pour un contrôle ponctuel).
 *
 * Vrai doublon = même enregistrement MusicBrainz, sur la MÊME release, au même disque/piste, et
 * durées à 3 s près (sinon l'un peut être tronqué). Le fichier qui vient d'être enregistré doit
 * avoir été identifié avec un score ≥ 95 ; l'autre doit être déjà TAGUÉ. La même chanson sur un
 * AUTRE album (compilation, best-of) n'est jamais concernée : chaque album doit rester complet.
 *
 * On garde le meilleur (critères duplicates.criteria_order, comme le dialogue manuel) ; l'autre
 * part dans la corbeille d'OpenTagger (restaurable), journalisé dans ~/.opentagger/doublons.log.
 * Plafonné par session (duplicates.auto_trash_max_per_run), désactivable dans les Réglages.
 */
public final class AutoDedup {

    private AutoDedup() {}

    static final int MIN_SCORE = 95;
    static final int MAX_DURATION_GAP_SEC = 3;

    private static final AtomicInteger TRASHED = new AtomicInteger();
    /** Un seul déplacement à la fois : la corbeille est sur le disque système, une copie depuis
     *  MyBook peut être lente — jamais sur l'EDT ni en parallèle. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-dedup");
        t.setDaemon(true);
        return t;
    });

    /** Numéro de piste/disque normalisé : « 04 », « 4/12 » → 4 ; vide → défaut. */
    static int number(String s, int dflt) {
        if (s == null) return dflt;
        String t = s.trim();
        int i = 0;
        while (i < t.length() && Character.isDigit(t.charAt(i))) i++;
        if (i == 0) return dflt;
        try { return Integer.parseInt(t.substring(0, i)); } catch (NumberFormatException e) { return dflt; }
    }

    /** Même enregistrement, même release, même disque et même piste (tous renseignés). */
    static boolean sameTrack(TagInfo a, TagInfo b) {
        if (a == null || b == null) return false;
        if (a.recordingMbid.isBlank() || a.releaseMbid.isBlank()) return false;
        if (!a.recordingMbid.equalsIgnoreCase(b.recordingMbid)) return false;
        if (!a.releaseMbid.equalsIgnoreCase(b.releaseMbid)) return false;
        int ta = number(a.track, -1), tb = number(b.track, -1);
        if (ta < 0 || ta != tb) return false;
        return number(a.discNo, 1) == number(b.discNo, 1);
    }

    static boolean durationsClose(int a, int b) {
        return a > 0 && b > 0 && Math.abs(a - b) <= MAX_DURATION_GAP_SEC;
    }

    /** Durée RÉELLE du fichier (lue au scan), pas celle annoncée par MusicBrainz. */
    static int fileDuration(FileEntry e) {
        if (e.current != null && e.current.durationSec > 0) return e.current.durationSec;
        return e.result != null ? e.result.durationSec : 0;
    }

    private static Path pathOf(FileEntry e) {
        return (e.currentPath != null ? e.currentPath : e.file.toPath()).toAbsolutePath();
    }

    /**
     * Sur l'EDT, juste après l'enregistrement de {@code saved}. Cherche un autre fichier TAGUÉ du
     * tableau qui est la même piste ; le choix du meilleur et la mise à la corbeille se font en
     * arrière-plan, puis {@code onRemoved} (rappelé sur l'EDT) retire l'écarté du tableau.
     */
    public static void onSaved(FileEntry saved, Collection<FileEntry> all, Consumer<FileEntry> onRemoved,
                               Consumer<String> journal) {
        if (!Config.get().bool("duplicates.auto_trash_enabled", false)) return;
        if (saved.status != FileEntry.Status.TAGGED) return;
        TagInfo st = saved.activeTags();
        if (st == null || st.recordingMbid.isBlank() || st.releaseMbid.isBlank()) return;
        if (saved.result == null || saved.result.score < MIN_SCORE) return;
        Path sp = pathOf(saved);
        List<FileEntry> twins = new ArrayList<>();
        for (FileEntry o : all) {
            if (o == saved || o.status != FileEntry.Status.TAGGED) continue;
            TagInfo ot = o.activeTags();
            if (ot == null || !st.recordingMbid.equalsIgnoreCase(ot.recordingMbid)) continue;
            if (!sameTrack(st, ot) || pathOf(o).equals(sp)) continue;
            twins.add(o);
        }
        if (twins.isEmpty()) return;
        int max = Config.get().num("duplicates.auto_trash_max_per_run", 50);
        for (FileEntry other : twins) {
            int ds = fileDuration(saved), dO = fileDuration(other);
            if (!durationsClose(ds, dO)) {
                journal.accept(I18n.t("Doublon possible laissé en place (durées %ss / %ss) : %s ↔ %s",
                        ds, dO, sp.getFileName(), pathOf(other).getFileName()));
                continue;
            }
            IO.submit(() -> handlePair(saved, other, max, onRemoved, journal));
        }
    }

    private static void handlePair(FileEntry saved, FileEntry other, int max, Consumer<FileEntry> onRemoved,
                                   Consumer<String> journal) {
        try {
            File fs = pathOf(saved).toFile(), fo = pathOf(other).toFile();
            if (!fs.isFile() || !fo.isFile()) return;
            if (TRASHED.get() >= max) {
                note(journal, I18n.t("Doublon laissé en place (plafond de %s par session atteint) : %s ↔ %s",
                        max, fs.getName(), fo.getName()));
                return;
            }
            FileEntry best  = DuplicateDetector.bestInGroup(List.of(saved, other));
            FileEntry loser = best == saved ? other : saved;
            File keep = pathOf(best).toFile(), drop = pathOf(loser).toFile();
            long dropSize = drop.length();
            if (!TrashHelper.moveToTrash(drop)) {
                note(journal, I18n.t("Doublon : mise à la corbeille refusée/échouée pour %s", drop));
                return;
            }
            TRASHED.incrementAndGet();
            System.out.println("[OT] ♊ Doublon écarté (corbeille OpenTagger) : " + drop + " — gardé : " + keep);
            try {
                String line = java.time.LocalDateTime.now() + "\t" + drop + "\t" + dropSize + "\t" + keep + "\t"
                        + keep.length() + "\t" + best.activeTags().artist + " – " + best.activeTags().title + "\n";
                Files.writeString(Paths.get(Config.configDir(), "doublons.log"), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
            javax.swing.SwingUtilities.invokeLater(() -> {
                onRemoved.accept(loser);
                journal.accept(I18n.t("♊ Doublon écarté → corbeille OpenTagger : %s (gardé : %s)",
                        drop.getName(), keep.getName()));
            });
        } catch (Exception ex) {
            note(journal, I18n.t("Doublon : erreur %s", ex.getMessage()));
        }
    }

    private static void note(Consumer<String> journal, String msg) {
        System.out.println("[OT] " + msg);
        javax.swing.SwingUtilities.invokeLater(() -> journal.accept(msg));
    }
}
