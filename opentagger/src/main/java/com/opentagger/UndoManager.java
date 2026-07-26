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
