package com.opentagger;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DiscogsPaceTest {

    @Test
    public void waitsOnlyForTheRemainingPartOfTheInterval() {
        assertEquals(1100, DiscogsClient.paceDelay(5000, 5000, 1100));   // appel immédiat après le précédent
        assertEquals(400, DiscogsClient.paceDelay(5700, 5000, 1100));
        assertEquals(0, DiscogsClient.paceDelay(6100, 5000, 1100));      // intervalle déjà écoulé
        assertEquals(0, DiscogsClient.paceDelay(9000, 5000, 1100));
    }

    @Test
    public void firstCallNeverWaits() {
        assertEquals(0, DiscogsClient.paceDelay(System.currentTimeMillis(), 0, 1100));
    }
}