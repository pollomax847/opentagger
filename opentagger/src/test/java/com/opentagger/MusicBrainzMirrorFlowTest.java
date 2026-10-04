package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.opentagger.MusicBrainzMirror.Next;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class MusicBrainzMirrorFlowTest {

    private static MusicBrainzMirror.Settings memory(Map<String, String> m) {
        return new MusicBrainzMirror.Settings() {
            @Override public String get(String k, String d) { return m.getOrDefault(k, d); }
            @Override public void put(String k, String v) { m.put(k, v); }
        };
    }

    @Test
    public void theSingleButtonFollowsTheStateOfTheMachine() {
        // actif : seule action proposée = revenir en arrière
        assertEquals(Next.REVERT, MusicBrainzMirror.nextAction(true, true, true, true));
        // Docker absent : installer Docker (jamais lancer des étapes qui échoueraient « docker: command not found »)
        assertEquals(Next.INSTALL_DOCKER, MusicBrainzMirror.nextAction(true, false, false, false));
        // Docker installé mais service arrêté : le démarrer
        assertEquals(Next.START_DOCKER, MusicBrainzMirror.nextAction(true, true, false, false));
        // tout est prêt : installer le miroir
        assertEquals(Next.INSTALL_MIRROR, MusicBrainzMirror.nextAction(true, true, true, false));
        if (MusicBrainzMirror.isWindows()) {
            // sans WSL, rien d'autre n'a de sens
            assertEquals(Next.INSTALL_WSL, MusicBrainzMirror.nextAction(false, false, false, false));
        }
    }

    @Test
    public void completedStepsAreRememberedSoTheInstallResumes() {
        Map<String, String> m = new HashMap<>();
        var s = memory(m);
        assertTrue(MusicBrainzMirror.doneSteps(s).isEmpty());
        MusicBrainzMirror.markDone("build", s);
        MusicBrainzMirror.markDone("createdb", s);
        MusicBrainzMirror.markDone("build", s);                       // idempotent
        assertEquals(java.util.List.of("build", "createdb"), new java.util.ArrayList<>(MusicBrainzMirror.doneSteps(s)));
        MusicBrainzMirror.clearDone(s);
        assertTrue(MusicBrainzMirror.doneSteps(s).isEmpty());
    }

    @Test
    public void dockerInstallUsesWingetWithTheOfficialPackageId() {
        var c = MusicBrainzMirror.dockerInstallCommand();
        assertEquals("winget.exe", c.get(0));
        assertTrue(c.contains("Docker.DockerDesktop"));
        assertTrue(c.contains("--accept-package-agreements"));
    }
    @Test
    public void wingetAlreadyInstalledIsNotAFailure() {
        assertTrue(MusicBrainzMirror.wingetAlreadyInstalled(-1978335189));   // « Aucune mise à jour applicable » (trace réelle)
        assertTrue(!MusicBrainzMirror.wingetAlreadyInstalled(0));
        assertTrue(!MusicBrainzMirror.wingetAlreadyInstalled(1));
    }

    @Test
    public void dockerDesktopInstalledButNotInWslLeadsToStartingItNotReinstalling() {
        // Docker Desktop présent (docker « installé »), service pas encore répondant → démarrer, pas réinstaller
        assertEquals(Next.START_DOCKER, MusicBrainzMirror.nextAction(true, true, false, false));
    }
    @Test
    public void dockerDataDiskTooSmallBlocksTheInstallation() {
        var small = MusicBrainzMirror.dockerDataCheck("C:\\", 33, 350);   // cas réel : 33 Go libres sur C:
        assertTrue(!small.ok());
        assertEquals("dockerdata", small.id());
        assertTrue(small.detail().contains("33 Go libres"));
        assertTrue(small.detail().toLowerCase().contains("trop petit") || small.detail().contains("data-root"));
        assertTrue(MusicBrainzMirror.dockerDataCheck("D:\\", 924, 350).ok());
    }
}