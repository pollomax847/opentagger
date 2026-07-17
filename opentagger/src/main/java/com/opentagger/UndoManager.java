package com.opentagger;

import com.opentagger.model.FileEntry;
import com.opentagger.model.TagInfo;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Pile Undo/Redo pour les modifications de tags.
 *
 * Chaque commande mémorise l'état COMPLET de TagInfo (avant/après).
 * On utilise la réflexion pour copier les champs String de TagInfo
 * sans avoir à les lister à la main — robuste aux ajouts de champs.
 */
public class UndoManager {

    public static final int MAX_HISTORY = 100;

    /** Une commande = snapshot avant + snapshot après + référence à l'entrée. */
    public record Command(FileEntry entry, TagInfo before, TagInfo after, String description) {}

    private final Deque<Command> undoStack = new ArrayDeque<>();
    private final Deque<Command> redoStack = new ArrayDeque<>();

    private final List<Runnable> listeners = new ArrayList<>();

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

    /** Copie profonde d'un TagInfo (uniquement les champs String + int score). */
    public static TagInfo snapshot(TagInfo src) {
        if (src == null) return new TagInfo();
        TagInfo dst = new TagInfo();
        dst.score = src.score;
        for (Field f : TagInfo.class.getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                f.set(dst, f.get(src));
            } catch (IllegalAccessException ignored) {}
        }
        return dst;
    }

    private void applySnapshot(FileEntry entry, TagInfo snap) {
        if (entry.result == null) entry.result = new TagInfo();
        TagInfo dst = entry.result;
        dst.score = snap.score;
        for (Field f : TagInfo.class.getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                f.set(dst, f.get(snap));
            } catch (IllegalAccessException ignored) {}
        }
    }

    private void notifyListeners() { listeners.forEach(Runnable::run); }
}
