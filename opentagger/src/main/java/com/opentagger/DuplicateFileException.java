package com.opentagger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Le nom visé par un renommage est déjà pris par le MÊME AUDIO : au lieu de créer « nom (2) », le fichier reste où il est.
 * Deux cas : copie identique octet pour octet (un des deux est inutile), ou même audio dans un fichier différent (autre
 * encodage, autre source), confirmé par la comparaison des empreintes — jamais d'après le nom ni les tags. C'est à l'utilisateur
 * de choisir lequel garder (Outils → Doublons).
 */
public class DuplicateFileException extends IOException {
    private final Path existing;
    private final boolean identical;

    public DuplicateFileException(Path existing, boolean identical) {
        super(I18n.t("Doublon : « %s » est déjà présent (%s) — fichier laissé en place, à relire dans Outils → Doublons",
                existing.getFileName(),
                identical ? I18n.t("copie identique") : I18n.t("même audio confirmé par empreinte, fichier différent")));
        this.existing = existing;
        this.identical = identical;
    }

    /** Le fichier déjà présent à l'emplacement visé. */
    public Path existing() { return existing; }

    /** Vrai si les deux fichiers sont identiques octet pour octet. */
    public boolean identical() { return identical; }
}
