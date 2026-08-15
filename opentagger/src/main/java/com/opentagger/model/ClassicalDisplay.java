package com.opentagger.model;

/** Compose une chaîne d'affichage lisible ("Bach: Goldberg Variations, BWV 988 — Variation 17
 *  (17/32)") à partir des champs déjà résolus par TagEnrichment.enrichClassicalWork()/
 *  MusicBrainzClient.resolveClassicalWork() — ces champs existent et sont écrits en tag depuis un
 *  moment, mais rien ne les composait jusqu'ici en une seule chaîne lisible (chacun n'était visible
 *  qu'individuellement dans l'onglet "Classique" de DetailPanel). */
public final class ClassicalDisplay {

    private ClassicalDisplay() {}

    public static String summarize(TagInfo t) {
        if (t == null || !"1".equals(t.isClassical)) return "";

        // overallWork prime sur work : rempli UNIQUEMENT si ce recording est un mouvement d'une
        // œuvre parente (relation "parts", voir resolveClassicalWork()) — dans ce cas work contient
        // le titre du MOUVEMENT lui-même, pas l'œuvre globale qu'on veut afficher ici. Si overallWork
        // est vide, c'est une pièce autonome : work est alors la bonne (et seule) valeur.
        String work = !t.overallWork.isBlank() ? t.overallWork : t.work;

        StringBuilder rest = new StringBuilder();
        if (!work.isBlank()) rest.append(work);

        // Le catalogue (BWV/opus...) est quasi-toujours DÉJÀ intégré au titre de l'œuvre tel que
        // renvoyé par MB (voir le commentaire de resolveClassicalWork()) — classicalCatalog/opus
        // n'est qu'une extraction régulière du même texte, pas une donnée indépendante. Ne
        // l'ajouter séparément que s'il n'apparaît pas déjà dans "work", sinon on duplique
        // ("Goldberg Variations, BWV 988, BWV 988").
        String catalog = !t.opus.isBlank() ? t.opus : t.classicalCatalog;
        if (!catalog.isBlank() && !work.toLowerCase().contains(catalog.toLowerCase()))
            rest.append(rest.isEmpty() ? catalog : ", " + catalog);

        // titleMovement (résolu automatiquement) prime sur movement (jamais rempli qu'à la main ou
        // relu depuis un tag déjà écrit — voir son commentaire de champ) — repli utile pour les
        // fichiers tagués avant l'ajout de titleMovement.
        String mvt = !t.titleMovement.isBlank() ? t.titleMovement : t.movement;
        if (!mvt.isBlank()) {
            rest.append(rest.isEmpty() ? "" : " — ").append(mvt);
            if (!t.movementNo.isBlank() && !t.movementTotal.isBlank())
                rest.append(" (").append(t.movementNo).append("/").append(t.movementTotal).append(")");
        }

        if (t.composer.isBlank()) return rest.toString();
        // Rien d'autre que le compositeur résolu (ex. Work MB pas encore atteint) : l'afficher
        // seul plutôt qu'avec un ":" final sans rien après.
        return rest.isEmpty() ? t.composer : t.composer + ": " + rest;
    }
}
