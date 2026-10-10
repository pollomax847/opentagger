package com.opentagger;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.Test;

/** « Audio illisible » : un vrai fichier abîmé est signalé, un fichier sain ou absent ne l'est jamais. */
public class DecodeProblemTest {

    private static boolean ffmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    @Test
    public void absentFileIsNotAnAudioProblem() {
        assertNull(new AudioTranscoder().decodeProblem(Path.of("definitely-not-here.m4a")));
    }

    @Test
    public void garbageBytesNamedM4aAreReported() throws Exception {
        assumeTrue(ffmpegAvailable());
        Path f = Files.createTempFile("ot-garbage", ".m4a");
        byte[] junk = new byte[200_000];
        new Random(42).nextBytes(junk);
        Files.write(f, junk);
        assertNotNull(new AudioTranscoder().decodeProblem(f));
    }

    @Test
    public void healthyAudioIsNotReported() throws Exception {
        assumeTrue(ffmpegAvailable());
        Path f = Files.createTempFile("ot-healthy", ".mp3");
        Process p = new ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
            "-q:a", "5", f.toString(), "-loglevel", "error").redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assumeTrue(p.waitFor() == 0);
        assertNull(new AudioTranscoder().decodeProblem(f));
    }
}
