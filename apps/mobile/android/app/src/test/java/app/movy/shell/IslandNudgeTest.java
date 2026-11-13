package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IslandNudgeTest {
    @Test
    public void restsAtBothEnds() {
        assertEquals(0f, IslandPainter.nudgeOffset(0f, 10f), 1e-6f);
        assertEquals(0f, IslandPainter.nudgeOffset(1f, 10f), 1e-6f);
        assertEquals(0f, IslandPainter.nudgeOffset(-0.5f, 10f), 1e-6f);
    }

    @Test
    public void staysWithinAmplitudeAndDecays() {
        float early = 0f;
        float late = 0f;
        for (int i = 1; i < 100; i++) {
            float t = i / 100f;
            float o = Math.abs(IslandPainter.nudgeOffset(t, 10f));
            assertTrue("offset " + o + " at " + t, o <= 10f);
            if (t < 0.3f) early = Math.max(early, o);
            if (t > 0.7f) late = Math.max(late, o);
        }
        assertTrue("wiggle should decay", late < early);
    }

    @Test
    public void swingsBothWays() {
        boolean left = false;
        boolean right = false;
        for (int i = 1; i < 100; i++) {
            float o = IslandPainter.nudgeOffset(i / 100f, 10f);
            left |= o < -1f;
            right |= o > 1f;
        }
        assertTrue(left && right);
    }

    @Test
    public void nudgeIsGentleAndInfrequent() {
        assertTrue(UiTokens.NUDGE_EVERY_MS >= 10_000);
        assertTrue(UiTokens.NUDGE_AMPLITUDE_DP <= 4f);
    }
}
