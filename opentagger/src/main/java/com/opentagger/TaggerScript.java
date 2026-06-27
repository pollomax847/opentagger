package com.opentagger;

import com.opentagger.model.TagInfo;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * Script tagger JavaScript — transformations custom sur les tags avant écriture.
 *
 * Le script accède à un objet {@code tags} exposant tous les champs publics de TagInfo.
 * Lecture et écriture directes sur les champs Java via Nashorn.
 *
 * Exemples :
 *   // Mettre le genre en minuscules
 *   tags.genre = tags.genre.toLowerCase();
 *
 *   // Synchroniser albumArtist si vide
 *   if (!tags.albumArtist) tags.albumArtist = tags.artist;
 *
 *   // Supprimer "(feat. …)" du titre
 *   tags.title = tags.title.replace(/\s*\(feat\..*?\)/gi, '').trim();
 *
 *   // Forcer Classical si compositeur renseigné
 *   if (tags.composer && !tags.genre) tags.genre = 'Classical';
 *
 *   // Corriger les apostrophes typographiques restantes
 *   tags.title = tags.title.replace(/[‘’]/g, "'");
 */
public class TaggerScript {

    private ScriptEngine engine;

    public TaggerScript() {
        try {
            ScriptEngineManager mgr = new ScriptEngineManager();
            engine = mgr.getEngineByName("nashorn");
            if (engine == null) engine = mgr.getEngineByName("js");
        } catch (Exception e) {
            engine = null;
        }
    }

    public boolean isReady() { return engine != null; }

    /**
     * Applique le script tagger.script sur le TagInfo.
     * Le script reçoit l'objet {@code tags} — les modifications sont in-place.
     * Les erreurs JS sont loguées sans interrompre le taguage.
     */
    public synchronized void apply(TagInfo info) {
        String script = Config.get().str("tagger.script", "").trim();
        if (script.isBlank() || engine == null) return;
        try {
            engine.put("tags", info);
            engine.eval(script);
        } catch (Exception e) {
            System.err.println("[TaggerScript] Erreur : " + e.getMessage());
        }
    }
}
