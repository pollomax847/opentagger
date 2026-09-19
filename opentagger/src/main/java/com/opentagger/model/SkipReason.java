package com.opentagger.model;

/** Catégorise pourquoi un {@link FileEntry} est resté SKIPPED/ERROR — calqué 1:1 sur les sites
 *  d'affectation de ces statuts dans TaggingWorker.processEntry()/findTags(). En mémoire
 *  uniquement (comme le reste de FileEntry), jamais persisté. */
public enum SkipReason {
    FILE_MISSING, NOT_IDENTIFIED, LOW_SCORE, DURATION_MISMATCH, ERROR_GENERIC, NETWORK_ERROR,
    // Contredit une release déjà corroborée par ≥2 autres pistes du même groupe/dossier — voir
    // TaggingWorker.shouldCapForGroupMismatch(). Distinct de LOW_SCORE : le score trouvé peut être
    // élevé (92, 100...), le problème n'est pas la confiance de CE match mais son incohérence avec
    // le reste du groupe (2026-09-19, correctif du plafonnage de score qui ne bloquait en réalité
    // rien — voir son commentaire d'appel).
    GROUP_MISMATCH
}
