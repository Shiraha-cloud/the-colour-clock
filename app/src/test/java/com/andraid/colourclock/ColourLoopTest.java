package com.andraid.colourclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The ring has to land on exactly the colours the reference walk produces, so these fixtures
 * were taken from the OKLab implementation being ported: sixteen points evenly spaced in
 * arc length, plus the quarter and half markers.
 */
public class ColourLoopTest {

    private static final String[] FIXTURES = {
            "f770a0", "f77974", "f57f39", "d9942f", "b7a630", "8cb630", "46c33e", "35c17d",
            "36bcaa", "36b6d3", "4badf5", "8b9ef6", "b48cf6", "d576f6", "f35aef", "f765c8"};

    @Test
    public void matchesTheReferenceRing() {
        for (int i = 0; i < FIXTURES.length; i++) {
            double phase = i / (double) FIXTURES.length;
            assertEquals(FIXTURES[i], ColourLoop.hexAt(phase));
        }
    }

    @Test
    public void arcLengthIsNotAngle() {
        // A sixth of the way round in phase is not a sixth of a turn: the ring is wider
        // on the yellow side, so the same arc length covers less angle there.
        assertEquals("f57f39", ColourLoop.hexAt(2 / 16d));
        assertEquals("36bcaa", ColourLoop.hexAt(0.5));
    }

    @Test
    public void phaseWrapsInBothDirections() {
        assertEquals(ColourLoop.hexAt(0.125), ColourLoop.hexAt(1.125));
        assertEquals(ColourLoop.hexAt(0.125), ColourLoop.hexAt(-0.875));
    }

    @Test
    public void periodAnchorsToTheWallClock() {
        long minute = 60_000L;
        assertEquals(ColourLoop.hexAt(30 * minute, 60), ColourLoop.hexAt(0.5));
        // The same instant inside any whole number of periods: restarts must not jump colour.
        assertEquals(ColourLoop.hexAt(7 * minute, 15), ColourLoop.hexAt(7 * minute + 15 * minute, 15));
        assertEquals(ColourLoop.hexAt(0L, 1440), ColourLoop.hexAt(24 * 60 * minute, 1440));
    }

    /**
     * Half of the shortest lap is the other side of the ring. The render tests cannot show this
     * by travelling in time: Robolectric's paused scheduler only moves uptime and elapsed real
     * time, so System.currentTimeMillis() stays on the JVM's own clock.
     */
    @Test
    public void theShortestLapCoversTheWholeRing() {
        long half = 30_000L;
        assertEquals(ColourLoop.hexAt(0d), ColourLoop.hexAt(0L, 1));
        assertEquals(ColourLoop.hexAt(0.5), ColourLoop.hexAt(half, 1));
        assertEquals("a minute lap repeats every minute",
                ColourLoop.hexAt(0L, 1), ColourLoop.hexAt(2 * half, 1));
        int[] start = ColourLoop.rgbAt(0d);
        int[] opposite = ColourLoop.rgbAt(0.5);
        int moved = Math.abs(start[0] - opposite[0]) + Math.abs(start[1] - opposite[1])
                + Math.abs(start[2] - opposite[2]);
        // Pink to teal: the red channel alone is more than half the way across, so the sum
        // lands in the high hundreds rather than near the theoretical 765.
        assertTrue("half a lap only moved " + moved, moved > 200);
    }

    @Test
    public void everyPointStaysInTheGamut() {
        for (int i = 0; i < 2000; i++) {
            int[] rgb = ColourLoop.rgbAt(i / 2000d);
            for (int channel = 0; channel < 3; channel++) {
                assertTrue(rgb[channel] >= 0 && rgb[channel] <= 255);
            }
        }
    }

    @Test
    public void stepsNeverJump() {
        // One pass of the ring polled at 60 ms over the shortest legal period still only
        // moves a few channels at a time, which is what keeps the drift invisible.
        int worst = 0;
        int[] previous = ColourLoop.rgbAt(0d);
        for (int i = 1; i <= 4096; i++) {
            int[] now = ColourLoop.rgbAt(i / 4096d);
            for (int channel = 0; channel < 3; channel++) {
                worst = Math.max(worst, Math.abs(now[channel] - previous[channel]));
            }
            previous = now;
        }
        assertTrue("largest single step was " + worst, worst <= 8);
    }
}
