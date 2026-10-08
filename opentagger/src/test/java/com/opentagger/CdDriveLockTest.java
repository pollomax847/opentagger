package com.opentagger;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.Test;

/** Le lecteur de CD ne sert pas deux lectures à la fois : tout accès passe par un verrou unique. */
public class CdDriveLockTest {

    @Test
    public void thereIsOneSharedStaticDriveLock() throws Exception {
        Field f = CdRipper.class.getDeclaredField("DRIVE");
        f.setAccessible(true);
        assertTrue(java.lang.reflect.Modifier.isStatic(f.getModifiers()));
        assertTrue(f.get(null) instanceof ReentrantLock);
    }

    @Test
    public void aSampleTrackHasAShortTimeout() {
        assertTrue(CdAudioIdentifier.SAMPLE_TIMEOUT_SEC <= 180);
    }
}
