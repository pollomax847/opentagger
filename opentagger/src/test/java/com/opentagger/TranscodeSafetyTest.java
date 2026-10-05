package com.opentagger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TranscodeSafetyTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aTruncatedConversionIsNotAccepted() {
        assertFalse("60 s sur 215 s : source abîmée", AudioTranscoder.durationsMatch(215, 60));
        assertFalse(AudioTranscoder.durationsMatch(215, 210));
    }

    @Test
    public void aNormalConversionIsAccepted() {
        assertTrue(AudioTranscoder.durationsMatch(215, 215));
        assertTrue("arrondis et silence de fin : 1-2 s d'écart", AudioTranscoder.durationsMatch(215, 213));
        assertTrue("1 % d'un long fichier", AudioTranscoder.durationsMatch(3600, 3630));
        assertFalse(AudioTranscoder.durationsMatch(3600, 3300));
    }

    @Test
    public void aFailedFileIsRememberedAcrossSessionsButOnlyWhileItIsUnchanged() throws Exception {
        Path memory = tmp.newFile("failed.txt").toPath();
        Path audio = Files.write(tmp.newFile("x.m4a").toPath(), new byte[]{1, 2, 3, 4});

        TranscodeFailureMemory first = new TranscodeFailureMemory(memory);
        assertFalse(first.known(audio));
        first.remember(audio);
        assertTrue(first.known(audio));

        TranscodeFailureMemory second = new TranscodeFailureMemory(memory);       // nouvelle session
        assertTrue("retenu après redémarrage", second.known(audio));

        Files.write(audio, new byte[]{1, 2, 3, 4, 5, 6});                         // fichier remplacé ou réparé
        assertFalse("retenté s'il a changé", second.known(audio));
    }

    @Test
    public void anUnknownFileIsNotKnownAsFailed() throws Exception {
        TranscodeFailureMemory m = new TranscodeFailureMemory(tmp.getRoot().toPath().resolve("none.txt"));
        assertFalse(m.known(Files.write(tmp.newFile("y.m4a").toPath(), new byte[]{9})));
    }
}
