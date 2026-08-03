package com.opentagger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opentagger.model.TagInfo;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Scripts tagger JavaScript — transformations custom sur les tags avant écriture.
 *
 * Plusieurs scripts nommés, activables/désactivables individuellement (dans l'esprit des
 * greffons Picard, sans chargeur de code tiers) — stockés dans {@code ~/.opentagger/scripts.json}.
 * Chaque script activé s'exécute dans l'ordre de la liste sur le même objet {@code tags}
 * (les scripts suivants voient les modifications des précédents).
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

    public record ScriptDef(String name, boolean enabled, String code) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STORE_PATH = Config.configDir() + "/scripts.json";

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
     * Charge la liste des scripts. Migration automatique : si {@code scripts.json} n'existe pas
     * encore et que l'ancienne propriété unique {@code tagger.script} est renseignée, elle est
     * reprise comme premier script (activé) pour ne rien perdre de la config existante.
     */
    public static List<ScriptDef> loadScripts() {
        File f = new File(STORE_PATH);
        if (!f.exists()) {
            String legacy = Config.get().str("tagger.script", "").trim();
            List<ScriptDef> migrated = new ArrayList<>();
            if (!legacy.isBlank()) migrated.add(new ScriptDef("Script migré", true, legacy));
            if (!migrated.isEmpty()) saveScripts(migrated);
            return migrated;
        }
        try {
            ScriptDef[] arr = MAPPER.readValue(f, ScriptDef[].class);
            List<ScriptDef> list = new ArrayList<>();
            for (ScriptDef s : arr) list.add(s);
            return list;
        } catch (Exception e) {
            System.err.println("[TaggerScript] Lecture scripts.json impossible : " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static void saveScripts(List<ScriptDef> scripts) {
        try {
            File f = new File(STORE_PATH);
            f.getParentFile().mkdirs();
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(f, scripts);
        } catch (Exception e) {
            System.err.println("[TaggerScript] Écriture scripts.json impossible : " + e.getMessage());
        }
    }

    /**
     * Applique tous les scripts activés, dans l'ordre, sur le TagInfo.
     * Les modifications sont in-place. Une erreur dans un script est loguée sans interrompre
     * les autres scripts ni le taguage.
     *
     * @return true si au moins un script activé a été exécuté (utile aux appelants qui ne
     *         réécrivent le fichier que "si quelque chose a changé" — un script activé est
     *         considéré comme une modification intentionnelle même si elle n'est pas détectable
     *         par une simple comparaison de champs).
     */
    public synchronized boolean apply(TagInfo info) {
        if (engine == null) return false;
        if (!Config.get().scriptsEnabled()) return false;
        List<ScriptDef> scripts = loadScripts();
        if (scripts.isEmpty()) return false;
        engine.put("tags", info);
        boolean ranAny = false;
        for (ScriptDef s : scripts) {
            if (!s.enabled() || s.code() == null || s.code().isBlank()) continue;
            try {
                engine.eval(s.code());
                ranAny = true;
            } catch (Exception e) {
                System.err.println("[TaggerScript] Erreur dans \"" + s.name() + "\" : " + e.getMessage());
            }
        }
        return ranAny;
    }
}
