package com.opentagger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.Test;

public class DiskIoThrottleWindowsTest {

    @Test public void mechanicalDisksAreThrottledAndSsdsAreNot() {
        Map<Character, String> m = DiskIoThrottle.parseWindowsDrives(
                "C;1;SSD\r\nD;0;HDD\r\nE;4;HDD\r\nF;5;Unspecified\r\nG;2;Unspecified\r\nH;3;SSD\r\n");
        assertFalse(m.containsKey('C'));
        assertFalse(m.containsKey('H'));
        assertEquals("win-disk-4", m.get('E'));
        assertEquals("win-disk-5", m.get('F'));
        assertEquals("win-disk-0", m.get('D'));
    }

    @Test public void garbageLinesAreIgnored() {
        assertTrue(DiskIoThrottle.parseWindowsDrives("oops\r\n;;\r\nXX;1;HDD\r\n").isEmpty());
    }

    @Test public void noDeviceMeansNoLimit() throws Exception {
        assertNull(DiskIoThrottle.acquireForDevice(null));
    }

    @Test(timeout = 5000) public void aThreadNeverBlocksItselfOnTheSameDisk() throws Exception {
        Semaphore outer = DiskIoThrottle.acquireForDevice("test-disk-A");
        assertNotNull(outer);
        Semaphore inner = DiskIoThrottle.acquireForDevice("test-disk-A"); // le même thread relit le même disque
        assertNull("pas de second permis", inner);
        DiskIoThrottle.release(inner);
        DiskIoThrottle.release(outer);
        Semaphore again = DiskIoThrottle.acquireForDevice("test-disk-A");
        assertNotNull("le permis est bien rendu", again);
        DiskIoThrottle.release(again);
    }

    @Test(timeout = 10000) public void twoThreadsOnTheSameDiskTakeTurns() throws Exception {
        Semaphore held = DiskIoThrottle.acquireForDevice("test-disk-B");
        final boolean[] gotIt = {false};
        Thread other = new Thread(() -> {
            try {
                Semaphore g = DiskIoThrottle.acquireForDevice("test-disk-B");
                gotIt[0] = true;
                DiskIoThrottle.release(g);
            } catch (InterruptedException ignored) {}
        });
        other.start();
        Thread.sleep(300);
        assertFalse("l'autre thread doit attendre", gotIt[0]);
        DiskIoThrottle.release(held);
        other.join(5000);
        assertTrue(gotIt[0]);
    }
}
