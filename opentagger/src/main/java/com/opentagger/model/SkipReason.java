package com.opentagger.model;

/** Catégorise pourquoi un {@link FileEntry} est resté SKIPPED/ERROR — calqué 1:1 sur les sites
 *  d'affectation de ces statuts dans TaggingWorker.processEntry()/findTags(). En mémoire
 *  uniquement (comme le reste de FileEntry), jamais persisté. */
public enum SkipReason {
    FILE_MISSING, NOT_IDENTIFIED, LOW_SCORE, DURATION_MISMATCH, ERROR_GENERIC
}
