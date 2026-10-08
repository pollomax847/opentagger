package com.opentagger;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Garde-fou « l'interface doit correspondre au code » : un réglage que les Préférences enregistrent doit être LU quelque part, sinon c'est
 * une case/un curseur qui ne fait rien (cas réel : « rename.follow_log », jamais lue). Et une même clé ne doit pas avoir deux valeurs par
 * défaut différentes selon l'endroit qui la lit.
 */
public class SettingsConsistencyTest {

    private static final Path MAIN = Paths.get("src", "main", "java");

    private static String read(Path p) { try { return Files.readString(p); } catch (IOException e) { throw new RuntimeException(e); } }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) { return s.filter(p -> p.toString().endsWith(".java")).toList(); }
    }

    @Test public void everySettingSavedByThePreferencesIsReadSomewhere() throws IOException {
        String dialog = read(MAIN.resolve("com/opentagger/ui/SettingsDialog.java"));
        TreeSet<String> saved = new TreeSet<>();
        Matcher m = Pattern.compile("setProperty\\(\\s*\"([a-z0-9_.]+)\"").matcher(dialog);
        while (m.find()) saved.add(m.group(1));
        StringBuilder others = new StringBuilder();
        for (Path p : sources()) if (!p.getFileName().toString().equals("SettingsDialog.java")) others.append(read(p)).append('\n');
        List<String> dead = new ArrayList<>();
        for (String k : saved) if (!others.toString().contains("\"" + k + "\"")) dead.add(k);
        assertTrue("Réglages des Préférences que rien ne lit (case/curseur sans effet) : " + dead, dead.isEmpty());
    }

    @Test public void aKeyNeverHasTwoDifferentDefaults() throws IOException {
        java.util.Map<String, java.util.Map<String, TreeSet<String>>> byKey = new java.util.TreeMap<>();
        Pattern call = Pattern.compile("(?:num|bool|dbl)\\s*\\(\\s*\"([a-z0-9_.]+)\"\\s*,\\s*([^)]+?)\\s*\\)");
        for (Path p : sources()) {
            Matcher m = call.matcher(read(p));
            while (m.find())
                byKey.computeIfAbsent(m.group(1), k -> new java.util.TreeMap<>())
                     .computeIfAbsent(m.group(2).trim(), k -> new TreeSet<>()).add(p.getFileName().toString());
        }
        List<String> conflicts = new ArrayList<>();
        byKey.forEach((k, defaults) -> { if (defaults.size() > 1) conflicts.add(k + " -> " + defaults); });
        assertTrue("Clés avec des valeurs par défaut différentes selon le fichier : " + conflicts, conflicts.isEmpty());
    }

    @Test public void theBundledDefaultsOnlyMentionKeysTheCodeReads() throws IOException {
        StringBuilder code = new StringBuilder();
        for (Path p : sources()) code.append(read(p)).append('\n');
        List<String> orphans = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("src", "main", "resources", "settings.properties"))) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            String key = t.substring(0, t.indexOf('=')).trim();
            if (!code.toString().contains("\"" + key + "\"")) orphans.add(key);
        }
        assertTrue("Valeurs par défaut livrées pour des clés que le code ne lit pas : " + orphans, orphans.isEmpty());
    }
}
