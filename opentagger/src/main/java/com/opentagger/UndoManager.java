package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/**
 * Pile Undo/Redo pour les modifications de tags.
 *
 * Chaque commande mémorise l'état COMPLET de TagInfo (avant/après).
 * On utilise la réflexion pour copier les champs String de TagInfo
 * sans avoir à les lister à la main — robuste aux ajouts de champs.
 *
 * Persistance (2026-08-16, demande utilisateur — écart réel constaté face à SongKong, dont la doc
 * annonce un undo qui survit à un redémarrage) : chaque push() est aussi écrit dans
 * MetadataCache.undo_history (best-effort, ne bloque jamais l'undo en mémoire de la session en
 * cours en cas d'échec DB). Au démarrage, bindPersistence() rejoue le log persistant dans
 * undoStack via une résolution path→FileEntry fournie par l'appelant (MainFrame, qui seul connaît
 * les FileEntry vivants de la session) — un chemin qui ne correspond à aucun FileEntry chargé
 * (dossier fermé depuis, fichier renommé hors session) est silencieusement ignoré plutôt que de
 * planter ou d'inventer une entrée fictive. Le redoStack, lui, ne persiste jamais : rétablir après
 * redémarrage n'a pas de sens (rien à "refaire" tant que rien n'a été annulé dans la session).
 */
public class UndoManager {

    public static final int MAX_HISTORY = 100;

    /** Une commande = snapshot avant + snapshot après + référence à l'entrée. */
    public record Command(FileEntry entry, TagInfo before, TagInfo after, String description) {}

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    private final List<Runnable> listeners = new ArrayList<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Enregistre une modification.
     * @param entry      l'entrée modifiée
     * @param before     snapshot TagInfo AVANT modification (cloner avant d'appliquer)
     * @param after      snapshot TagInfo APRÈS modification
     * @param description libellé pour le menu Edit
     */
    public void push(FileEntry entry, TagInfo before, TagInfo after, String description) {
        if (undoStack.size() >= MAX_HISTORY) undoStack.removeLast();
        undoStack.push(new Command(entry, before, after, description));
        redoStack.clear();
        persist(entry, before, after, description);
        notifyListeners();
    }

    private void persist(FileEntry entry, TagInfo before, TagInfo after, String description) {
        try {
            Path p = entry.currentPath != null ? entry.currentPath : entry.file.toPath();
            String beforeJson = MAPPER.writeValueAsString(before);
            String afterJson  = MAPPER.writeValueAsString(after);
            try (MetadataCache c = new MetadataCache()) {
                c.pushUndoHistory(p.toString(), description, beforeJson, afterJson, MAX_HISTORY);
            }
        } catch (Exception ignored) {
            // Best-effort — voir commentaire de classe. L'undo en mémoire de cette session
            // fonctionne quoi qu'il arrive ici.
        }
    }

    /**
     * Recharge le log persistant dans undoStack — à appeler une fois au démarrage, après que le
     * tableau des fichiers de la session soit peuplé. {@code resolver} retrouve le FileEntry
     * vivant correspondant à un chemin (ou {@code null} si absent de la session courante, auquel
     * cas la ligne est ignorée).
     */
    public void bindPersistence(Function<Path, FileEntry> resolver) {
        try (MetadataCache c = new MetadataCache()) {
            for (MetadataCache.UndoRow row : c.loadUndoHistory(MAX_HISTORY)) {
                FileEntry entry = resolver.apply(java.nio.file.Paths.get(row.path()));
                if (entry == null) continue;
                TagInfo before = MAPPER.readValue(row.beforeJson(), TagInfo.class);
                TagInfo after   = MAPPER.readValue(row.afterJson(),  TagInfo.class);
                if (undoStack.size() >= MAX_HISTORY) undoStack.removeLast();
                undoStack.push(new Command(entry, before, after, row.description()));
            }
        } catch (Exception ignored) {
            // Best-effort — un log persistant illisible/corrompu ne doit jamais empêcher le
            // démarrage de l'application, juste repartir avec une pile undo vide comme avant ce
            // correctif.
        }
        notifyListeners();
    }

    public boolean canUndo() { return !undoStack.isEmpty(); }
    public boolean canRedo() { return !redoStack.isEmpty(); }

    public String undoDescription() {
        return undoStack.isEmpty() ? "" : undoStack.peek().description();
    }
    public String redoDescription() {
        return redoStack.isEmpty() ? "" : redoStack.peek().description();
    }

    /** Annule la dernière commande — restaure l'état AVANT. */
    public FileEntry undo() {
        if (!canUndo()) return null;
        Command cmd = undoStack.pop();
        applySnapshot(cmd.entry(), cmd.before());
        redoStack.push(cmd);
        notifyListeners();
        return cmd.entry();
    }

    /** Rétablit la commande annulée — restaure l'état APRÈS. */
    public FileEntry redo() {
        if (!canRedo()) return null;
        Command cmd = redoStack.pop();
        applySnapshot(cmd.entry(), cmd.after());
        undoStack.push(cmd);
        notifyListeners();
        return cmd.entry();
    }

    /** Vide toute l'historique (ex : fermeture de dossier). */
    public void clear() {
        undoStack.clear();
        redoStack.clear();
        notifyListeners();
    }

    public void addListener(Runnable r) { listeners.add(r); }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    /**
     * Copie profonde d'un TagInfo — TOUS les champs déclarés, pas seulement les String. Avant ce
     * correctif, le filtre `f.getType() != String.class` excluait tout champ non-String ajouté au
     * modèle sans que personne ne le remarque (aujourd'hui seuls score/durationSec/mbDurationSec
     * sont concernés, aucun n'étant éditable par DetailPanel — donc pas de perte observable
     * actuellement — mais le prochain champ non-String ajouté à TagInfo aurait silencieusement
     * cessé d'être restauré par Annuler/Rétablir). Score était déjà copié séparément ; supprimé
     * ici car couvert par la boucle générique.
     */
    public static TagInfo snapshot(TagInfo src) {
        if (src == null) return new TagInfo();
        TagInfo dst = new TagInfo();
        copyAllFields(src, dst);
        return dst;
    }

    private void applySnapshot(FileEntry entry, TagInfo snap) {
        if (entry.result == null) entry.result = new TagInfo();
        copyAllFields(snap, entry.result);
    }

    private static void copyAllFields(TagInfo src, TagInfo dst) {
        for (Field f : TagInfo.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                f.set(dst, f.get(src));
            } catch (IllegalAccessException ignored) {}
        }
    }

    private void notifyListeners() { listeners.forEach(Runnable::run); }
}
