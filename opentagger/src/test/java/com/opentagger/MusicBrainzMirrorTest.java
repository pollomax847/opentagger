package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class MusicBrainzMirrorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ── commandes : celles du README officiel, ni plus ni moins ─────────────────────────────────────────

    @Test
    public void stepsFollowTheOfficialReadme() {
        var withSearch = MusicBrainzMirror.steps(true);
        List<String> cmds = withSearch.stream().flatMap(s -> s.commands().stream()).toList();
        assertTrue(cmds.contains("docker compose build"));
        assertTrue(cmds.stream().anyMatch(c -> c.endsWith("docker compose run --rm -T musicbrainz createdb.sh -fetch")));
        assertTrue(cmds.contains("docker compose up -d"));
        assertTrue(cmds.contains("docker compose up -d musicbrainz search"));
        assertTrue(cmds.stream().anyMatch(c -> c.endsWith("docker compose exec -T search fetch-backup-archives")));
        assertTrue(cmds.contains("docker compose exec -T search load-backup-archives"));
        var noSearch = MusicBrainzMirror.steps(false).stream().flatMap(s -> s.commands().stream()).toList();
        assertFalse(noSearch.stream().anyMatch(c -> c.contains("search")));
        assertTrue(withSearch.stream().filter(s -> s.id().equals("createdb")).findFirst().orElseThrow().longRunning());
    }

    @Test
    public void windowsPathsBecomeWslPaths() {
        assertEquals("/mnt/d/musicbrainz-docker", MusicBrainzMirror.toWslPath("D:\\musicbrainz-docker"));
        assertEquals("/mnt/c/Users/x y/mb", MusicBrainzMirror.toWslPath("C:\\Users\\x y\\mb"));
        assertEquals("/home/u/mb", MusicBrainzMirror.toWslPath("/home/u/mb"));
    }

    @Test
    public void shellCommandQuotesTheDirectory() {
        List<String> cmd = MusicBrainzMirror.shellCommand("docker compose ps", java.nio.file.Paths.get("projet d'essai"));
        String line = cmd.get(cmd.size() - 1);
        assertTrue(line.contains("docker compose ps"));
        assertTrue("apostrophe protégée : " + line, line.contains("'\\''"));
        assertEquals(MusicBrainzMirror.isWindows() ? "wsl.exe" : "bash", cmd.get(0));
    }

    // ── téléchargement : décompression sûre ─────────────────────────────────────────────────────────────

    private static Path zipOf(Path where, String... nameAndContent) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameAndContent[i]));
                z.write(nameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        Path f = where.resolve("a.zip");
        Files.write(f, bos.toByteArray());
        return f;
    }

    @Test
    public void unzipStripsTheRootFolder() throws Exception {
        Path zip = zipOf(tmp.getRoot().toPath(), "musicbrainz-docker-master/", "", "musicbrainz-docker-master/docker-compose.yml", "services:",
                "musicbrainz-docker-master/admin/configure", "#!/bin/bash");
        Path dst = tmp.newFolder("dst").toPath();
        MusicBrainzMirror.unzipStrippingRoot(zip, dst);
        assertTrue(MusicBrainzMirror.projectPresent(dst));
        assertTrue(Files.isRegularFile(dst.resolve("admin/configure")));
    }

    @Test(expected = java.io.IOException.class)
    public void unzipRefusesPathTraversal() throws Exception {
        Path zip = zipOf(tmp.getRoot().toPath(), "root/../../evil.txt", "x");
        MusicBrainzMirror.unzipStrippingRoot(zip, tmp.newFolder("dst2").toPath());
    }

    // ── test du serveur : faux miroir HTTP local ────────────────────────────────────────────────────────

    private HttpServer fakeMirror(boolean artistKnown, boolean searchWorks) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/ws/2/artist/", ex -> {
            byte[] body = (artistKnown ? "{\"id\":\"0383dadf\",\"name\":\"Queen\"}" : "{\"error\":\"Not Found\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(artistKnown ? 200 : 404, body.length); ex.getResponseBody().write(body); ex.close();
        });
        s.createContext("/ws/2/recording", ex -> {
            byte[] body = (searchWorks ? "{\"recordings\":[{\"id\":\"x\",\"title\":\"Bohemian Rhapsody\"}]}" : "{\"error\":\"search down\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(searchWorks ? 200 : 503, body.length); ex.getResponseBody().write(body); ex.close();
        });
        s.start();
        return s;
    }

    private static String url(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort() + "/ws/2"; }

    @Test
    public void testDetectsAFullMirror() throws Exception {
        HttpServer s = fakeMirror(true, true);
        try {
            var r = MusicBrainzMirror.test(url(s));
            assertTrue(r.message(), r.lookupOk());
            assertTrue(r.message(), r.searchOk());
        } finally { s.stop(0); }
    }

    @Test
    public void testDetectsAMirrorWithoutSearchIndexes() throws Exception {
        HttpServer s = fakeMirror(true, false);
        try {
            var r = MusicBrainzMirror.test(url(s));
            assertTrue(r.lookupOk());
            assertFalse(r.searchOk());
            assertTrue(r.message().contains("INDISPONIBLE"));
        } finally { s.stop(0); }
    }

    @Test
    public void testDetectsAnIncompleteImport() throws Exception {
        HttpServer s = fakeMirror(false, false);
        try {
            var r = MusicBrainzMirror.test(url(s));
            assertFalse(r.lookupOk());
            assertTrue(r.message().contains("import incomplet"));
        } finally { s.stop(0); }
    }

    @Test
    public void testReportsAnUnreachableServer() {
        var r = MusicBrainzMirror.test("http://127.0.0.1:1/ws/2");
        assertFalse(r.lookupOk());
        assertTrue(r.message(), r.message().startsWith("Serveur injoignable"));
    }

    // ── bascule des réglages avec retour arrière (réglages EN MÉMOIRE : jamais le vrai settings.properties) ─────

    private static MusicBrainzMirror.Settings memory(java.util.Map<String, String> m) {
        return new MusicBrainzMirror.Settings() {
            @Override public String get(String k, String d) { return m.getOrDefault(k, d); }
            @Override public void put(String k, String v) { m.put(k, v); }
        };
    }

    @Test
    public void activateThenDeactivateRestoresThePreviousSettings() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("musicbrainz.server", "https://exemple.test/ws/2");
        m.put("musicbrainz.rate_limit_ms", "1100");
        m.put("batch.threads", "3");
        var s = memory(m);
        MusicBrainzMirror.activate("http://localhost:5000/ws/2", s);
        assertEquals("http://localhost:5000/ws/2", m.get("musicbrainz.server"));
        assertEquals("0", m.get("musicbrainz.rate_limit_ms"));
        assertTrue(Integer.parseInt(m.get("batch.threads")) >= 3);
        assertEquals("true", m.get("musicbrainz.mirror.active"));
        // activer deux fois ne doit PAS écraser l'état d'origine mémorisé
        MusicBrainzMirror.activate("http://localhost:5000/ws/2", s);
        MusicBrainzMirror.deactivate(s);
        assertEquals("https://exemple.test/ws/2", m.get("musicbrainz.server"));
        assertEquals("1100", m.get("musicbrainz.rate_limit_ms"));
        assertEquals("3", m.get("batch.threads"));
        assertEquals("", m.get("musicbrainz.mirror.active"));
    }

    @Test
    public void deactivateWithoutHistoryFallsBackToThePublicServer() {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        MusicBrainzMirror.deactivate(memory(m));
        assertEquals("https://musicbrainz.org/ws/2", m.get("musicbrainz.server"));
        assertEquals("1100", m.get("musicbrainz.rate_limit_ms"));
    }
    @Test
    public void theMetaBrainzQuestionIsAnsweredOnStdinWithTheUsersDeclaration() {
        // Le script de téléchargement POSE la question « usage commercial ? (y/n) » : sans réponse il bloque indéfiniment
        // (constaté : 3 h sans un octet téléchargé). La réponse est celle de l'utilisateur.
        String personal = MusicBrainzMirror.steps(true, false).stream().filter(s -> s.id().equals("createdb")).findFirst().orElseThrow().commands().get(1);
        String commercial = MusicBrainzMirror.steps(true, true).stream().filter(s -> s.id().equals("createdb")).findFirst().orElseThrow().commands().get(1);
        assertTrue(personal, personal.startsWith("printf 'n\\n\\n' | "));
        assertTrue(commercial, commercial.startsWith("printf 'y\\n\\n' | "));
        assertTrue(personal.contains(" -T "));        // pas de terminal : l'entrée vient du tube
        // par défaut (surcharge sans paramètre) : jamais « commercial » par erreur
        assertTrue(MusicBrainzMirror.steps(true).get(1).commands().get(1).startsWith("printf 'n\\n\\n' | "));
    }

    @Test
    public void aHalfInitialisedDatabaseIsResetBeforeImportButTheDumpIsKept() {
        var createdb = MusicBrainzMirror.steps(true).stream().filter(s -> s.id().equals("createdb")).findFirst().orElseThrow();
        // la préparation passe AVANT l'import
        assertEquals(MusicBrainzMirror.prepDbCommand(), createdb.commands().get(0));
        assertTrue(createdb.commands().get(1).endsWith("createdb.sh -fetch"));
        String script = MusicBrainzMirror.prepDbScript();
        assertTrue(script.contains("docker volume rm"));
        assertTrue(script.contains("_pgdata"));
        // jamais le volume du dump, ni « down -v » qui supprimerait tous les volumes
        assertFalse(script.contains("dbdump"));
        assertFalse(script.contains("down -v"));
        // une base déjà remplie n'est touchée que si la table des artistes est vide ou absente
        assertTrue(script.contains("musicbrainz.artist"));
        assertTrue(script.trim().endsWith("exit 0"));
        // un import déjà actif en tâche de fond (même fenêtre fermée) n'est jamais pris pour un reste d'interruption :
        // le contrôle des conteneurs précède TOUT le reste du script
        int guard = script.indexOf("docker ps -q --filter name=musicbrainz-run");
        assertTrue(guard >= 0 && guard < script.indexOf("docker volume rm") && guard < script.indexOf("docker compose down"));
        assertTrue(script.contains("exit 1"));
        // transmis en base64 : aucun guillemet à protéger entre Windows, wsl.exe et bash
        String cmd = MusicBrainzMirror.prepDbCommand();
        assertTrue(cmd, cmd.matches("echo [A-Za-z0-9+/=]+ \\| base64 -d \\| sh"));
    }
}