package app.movy.shell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LongPressGateTest {
    private static final LongPressGate.Out PASS = LongPressGate.Out.PASS;
    private static final LongPressGate.Out SWALLOW = LongPressGate.Out.SWALLOW;

    @Test public void shortPressIsReplayedOnRelease() {
        LongPressGate g = new LongPressGate();
        assertEquals(SWALLOW, g.onEvent(true, 0, true));
        assertEquals(LongPressGate.Out.REPLAY_CLICK, g.onEvent(false, 0, true));
    }

    @Test public void holdOpensOnceThenSwallowsRepeatsAndUp() {
        LongPressGate g = new LongPressGate();
        assertEquals(SWALLOW, g.onEvent(true, 0, true));
        assertEquals(LongPressGate.Out.OPEN, g.onEvent(true, 1, true));
        // the Island is now open, so the host reports not eligible: still swallowed
        for (int i = 2; i < 30; i++) assertEquals(SWALLOW, g.onEvent(true, i, false));
        assertEquals(SWALLOW, g.onEvent(false, 0, false));
    }

    @Test public void freshDownAfterLongPressPassesToTheOpenView() {
        LongPressGate g = new LongPressGate();
        g.onEvent(true, 0, true);
        g.onEvent(true, 1, true);
        g.onEvent(false, 0, false);
        assertEquals(PASS, g.onEvent(true, 0, false));
        assertEquals(PASS, g.onEvent(true, 1, false));
        assertEquals(PASS, g.onEvent(false, 0, false));
    }

    @Test public void notEligibleNeverArms() {
        LongPressGate g = new LongPressGate();
        assertEquals(PASS, g.onEvent(true, 0, false));
        assertEquals(PASS, g.onEvent(true, 1, false));
        assertEquals(PASS, g.onEvent(false, 0, false));
    }

    @Test public void stuckStateIsClearedByNextFreshDown() {
        LongPressGate g = new LongPressGate();
        g.onEvent(true, 0, true);
        g.onEvent(true, 1, true);            // fired, UP lost
        assertEquals(SWALLOW, g.onEvent(true, 0, true));
        assertEquals(LongPressGate.Out.REPLAY_CLICK, g.onEvent(false, 0, true));
    }
}
