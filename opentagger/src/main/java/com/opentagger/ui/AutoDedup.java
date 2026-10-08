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

    private static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        return n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Le fichier est-il rangé là où ses tags le disent : son nom contient le titre et son dossier (hors « Disc 01 »)
     *  le nom de l'album ? Comparaison tolérante (accents, ponctuation, casse, débuts seulement). */
    static boolean wellPlaced(Path path, TagInfo tags) {
        if (path == null || tags == null) return false;
        String title = norm(tags.title), album = norm(tags.album);
        if (title.isEmpty() || album.isEmpty()) return false;
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = norm(dot > 0 ? file.substring(0, dot) : file);
        Path dir = path.getParent();
        if (dir == null || dir.getFileName() == null) return false;
        String folder = dir.getFileName().toString();
        if (folder.matches("(?i)^(disc|disque|cd)\\s*\\d+.*") && dir.getParent() != null && dir.getParent().getFileName() != null)
            folder = dir.getParent().getFileName().toString();
        return stem.contains(title.substring(0, Math.min(14, title.length())))
                && norm(folder).contains(album.substring(0, Math.min(10, album.length())));
    }

    /** Le NOM du fichier annonce-t-il un autre morceau que ses tags (contenu ≠ nom, ex. « 12 - Deorro - Going Up.mp3 » qui
     *  contient Peter von Poehl) ? Faux si le titre est inconnu : dans le doute, on ne conclut pas à une erreur de nom. */
    static boolean nameContradictsTags(Path path, TagInfo tags) {
        if (path == null || tags == null) return false;
        // Sans les précisions de version (« (radio edit) », « [Remastered] », « - Live ») : « 01 - Yannick - Ces Soirées
        // Là.mp3 » est bien « Ces Soirées Là (radio Edit) », ce n'est pas un autre morceau.
        String core = tags.title == null ? "" : tags.title.replaceAll("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]", " ")
                .replaceAll("\\s+-\\s+.*$", "");
        String title = norm(core);
        if (title.length() < 3) title = norm(tags.title);
        if (title.length() < 3) return false;
        String file = path.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String stem = norm(dot > 0 ? file.substring(0, dot) : file);
        return !stem.contains(title.substring(0, Math.min(14, title.length())));
    }

    /** Peut-on jeter ce fichier comme doublon ? Oui s'il est rangé selon ses tags (vraie copie du même album), ou si son nom
     *  annonce un autre morceau (fichier mal nommé). NON s'il porte le bon titre mais se trouve dans le dossier d'un AUTRE
     *  album (piste d'une compilation que l'identification a rattachée à une autre parution) : c'est « la même chanson sur un
     *  autre album », jamais touchée — vu en direct le 2026-10-08 (« Now That's What I Call Running/3-15 Footloose.mp3 »
     *  jeté au profit de « Grammy's Greatest Moments », « Le Meilleur de Frank Michael/17 T'en vas pas » au profit
     *  d'« Olympia 99 »…). */
    static boolean disposable(Path path, TagInfo tags) {
        return wellPlaced(path, tags) || nameContradictsTags(path, tags);
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
            // Le fichier bien rangé gagne toujours (2026-10-08) : un fichier mal nommé (contenu ≠ nom, ex. « 12 - Deorro -
            // Going Up.mp3 » qui contient en réalité Peter von Poehl) n'est pas renommé à l'enregistrement quand la
            // destination existe déjà (même audio) — la qualité seule faisait alors jeter la copie BIEN rangée de l'album.
            boolean savedPlaced = wellPlaced(pathOf(saved), saved.activeTags());
            boolean otherPlaced = wellPlaced(pathOf(other), other.activeTags());
            if (savedPlaced != otherPlaced) best = savedPlaced ? saved : other;
            FileEntry loser = best == saved ? other : saved;
            if (!disposable(pathOf(loser), loser.activeTags())) {
                note(journal, I18n.t("Même chanson rangée dans un autre album (%s) — laissée en place",
                        pathOf(loser).getParent() != null ? pathOf(loser).getParent().getFileName() : pathOf(loser)));
                return;
            }
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

    /** Résultat de {@link #resolveAtSave} : {@code resolved} faux = rien fait (fichier laissé en place comme avant). */
    public record Resolution(boolean resolved, Path keptPath, String message) {}

    /**
     * L'enregistrement n'a pas pu renommer {@code saved} parce que {@code existing} — à l'emplacement visé, donc bien rangé —
     * contient déjà le même audio (DuplicateFileException, empreinte confirmée). Au lieu de laisser le fichier mal nommé en
     * place « à relire dans Outils → Doublons » (2026-10-08 : 360 cas en une session, ex. « 02 - David Guetta - Memories.mp3 »
     * contenant « Secoues Ton Boule »), on garde UNE copie, au bon endroit : la meilleure (format puis débit) ; si c'est la
     * nouvelle, elle remplace l'ancienne à l'emplacement visé. L'autre part dans la corbeille d'OpenTagger. Appelé hors EDT.
     */
    public static Resolution resolveAtSave(Path saved, Path existing, TagInfo tags, Consumer<String> log) {
        if (!Config.get().bool("duplicates.auto_trash_enabled", false)) return new Resolution(false, saved, "");
        try {
            File fs = saved.toFile(), fe = existing.toFile();
            if (!fs.isFile() || !fe.isFile()) return new Resolution(false, saved, "");
            int max = Config.get().num("duplicates.auto_trash_max_per_run", 50);
            if (TRASHED.get() >= max) return new Resolution(false, saved, "");
            // Le fichier qui n'a pas pu être renommé porte-t-il le bon titre mais dans le dossier d'un autre album (piste de
            // compilation rattachée à une autre parution) ? Alors ce n'est pas un doublon à jeter : laissé en place, comme avant.
            if (!disposable(saved, tags)) return new Resolution(false, saved, "");
            FileEntry eNew = new FileEntry(fs, tags), eOld = new FileEntry(fe, tags);
            // Ancienne copie d'abord : à qualité égale, c'est elle qui reste (déjà en place, rien à déplacer).
            boolean newIsBetter = DuplicateDetector.bestInGroup(List.of(eOld, eNew)) == eNew
                    && DuplicateDetector.qualityScore(eNew) > DuplicateDetector.qualityScore(eOld);
            long dropSize;
            File dropped;
            if (newIsBetter) {
                dropped = fe; dropSize = fe.length();
                if (!TrashHelper.moveToTrash(fe)) return new Resolution(false, saved, "");
                java.nio.file.Files.move(saved, existing);
            } else {
                dropped = fs; dropSize = fs.length();
                if (!TrashHelper.moveToTrash(fs)) return new Resolution(false, saved, "");
            }
            TRASHED.incrementAndGet();
            String what = newIsBetter ? "ancienne copie (moins bonne qualité)" : "copie mal rangée";
            System.out.println("[OT] ♊ Doublon à l'enregistrement : " + what + " → corbeille OpenTagger : " + dropped + " — gardé : " + existing);
            try {
                String line = java.time.LocalDateTime.now() + "\t" + dropped + "\t" + dropSize + "\t" + existing + "\t"
                        + existing.toFile().length() + "\t" + tags.artist + " – " + tags.title + "\n";
                Files.writeString(Paths.get(Config.configDir(), "doublons.log"), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
            String msg = newIsBetter
                    ? I18n.t("Doublon : meilleure qualité, remplace « %s » (ancienne copie → corbeille OpenTagger)", existing.getFileName())
                    : I18n.t("Doublon de « %s » déjà bien rangé — cette copie mal nommée → corbeille OpenTagger", existing.getFileName());
            log.accept(msg);
            return new Resolution(true, existing, msg);
        } catch (Exception ex) {
            log.accept(I18n.t("Doublon : résolution automatique impossible (%s) — fichier laissé en place", ex.getMessage()));
            return new Resolution(false, saved, "");
        }
    }

    private static void note(Consumer<String> journal, String msg) {
        System.out.println("[OT] " + msg);
        javax.swing.SwingUtilities.invokeLater(() -> journal.accept(msg));
    }
}
