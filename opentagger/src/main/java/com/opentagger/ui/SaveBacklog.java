package com.opentagger.ui;

import com.opentagger.Config;
import java.util.function.BooleanSupplier;

/**
 * Contre-pression entre l'identification et l'enregistrement. L'identification (réseau/CPU) va bien plus vite que
 * l'enregistrement (déplacements et écritures sur disque, surtout sur un disque USB mécanique) : sans frein, des milliers de
 * fichiers « Identifié » s'accumulent EN MÉMOIRE — rien n'est sur le disque, et tout est perdu si l'app se ferme — pendant que le
 * disque est saturé par deux travaux qui se disputent la même tête de lecture. Au-delà de {@code tagging.max_unsaved} fichiers
 * identifiés non enregistrés (500 par défaut), les nouveaux fichiers attendent que l'enregistrement rattrape son retard.
 * Actif seulement si l'enregistrement automatique l'est : en mode manuel, l'utilisateur choisit quand enregistrer.
 */
public final class SaveBacklog {
    private SaveBacklog() {}

    /** Nombre de fichiers identifiés, sélectionnés, pas encore écrits — mis à jour périodiquement par MainFrame. */
    private static volatile int unsaved;

    public static void setUnsaved(int n) { unsaved = n; }

    /** Enregistrement automatique volontairement reporté (scans de démarrage en cours, voir
     *  MainFrame.scheduleAutoSaveFollowUp) : le frein ne s'applique pas, l'attente ne servirait à rien. */
    private static volatile boolean savingDeferred;
    public static void setSavingDeferred(boolean v) { savingDeferred = v; }
    public static int unsaved() { return unsaved; }

    /** Vrai s'il faut attendre. */
    static boolean mustWait(int unsavedNow, int limit) { return limit > 0 && unsavedNow >= limit; }

    /**
     * Bloque tant que l'arriéré d'enregistrement dépasse la limite. Plafonné à 10 minutes par appel (jamais d'attente infinie, même
     * si l'enregistrement est bloqué ailleurs) et interrompu par l'annulation.
     */
    public static void awaitRoom(BooleanSupplier cancelled) {
        Config cfg = Config.get();
        if (!cfg.autoSaveEnabled() || savingDeferred) return;
        int limit = cfg.num("tagging.max_unsaved", 500);
        long deadline = System.currentTimeMillis() + 10 * 60_000L;
        while (mustWait(unsaved, limit) && !cancelled.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}
