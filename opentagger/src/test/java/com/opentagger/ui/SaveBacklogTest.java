package com.opentagger.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SaveBacklogTest {
    @Test public void waitsOnlyAtOrAboveTheLimit() {
        assertFalse(SaveBacklog.mustWait(499, 500));
        assertTrue(SaveBacklog.mustWait(500, 500));
        assertTrue(SaveBacklog.mustWait(3500, 500));
    }
    @Test public void aLimitOfZeroDisablesTheBrake() {
        assertFalse(SaveBacklog.mustWait(100000, 0));
    }
}
