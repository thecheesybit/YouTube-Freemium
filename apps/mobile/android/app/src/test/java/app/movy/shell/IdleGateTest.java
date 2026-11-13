package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleGateTest {
    private static final int DPAD_DOWN = 20;
    private static final int PLAY_PAUSE = 85;

    @Test public void goesIdleAfterFiveSecondsWithoutKeys() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        assertFalse(g.tick(4999));
        assertFalse(g.isIdle());
        assertTrue(g.tick(5000));
        assertTrue(g.isIdle());
        assertFalse("idle is reported once", g.tick(9000));
    }

    @Test public void everyKeyRestartsTheTimer() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.onKey(true, DPAD_DOWN, 3000, null);
        g.onKey(false, DPAD_DOWN, 3050, null);
        assertFalse(g.tick(7999));
        assertEquals(8000, g.dueAt());
        assertTrue(g.tick(8000));
    }

    @Test public void firstKeyAfterIdleOnlyWakesAndIsSwallowedWithRepeatsAndUp() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.tick(6000);
        boolean[] woke = new boolean[1];
        assertEquals(IdleGate.Decision.SWALLOW, g.onKey(true, DPAD_DOWN, 6100, woke));
        assertTrue(woke[0]);
        assertFalse(g.isIdle());
        assertEquals(IdleGate.Decision.SWALLOW, g.onKey(true, DPAD_DOWN, 6150, woke)); // held key repeat
        assertFalse(woke[0]);
        assertEquals(IdleGate.Decision.SWALLOW, g.onKey(false, DPAD_DOWN, 6200, woke));
        // the next key is a normal key again
        assertEquals(IdleGate.Decision.PASS, g.onKey(true, DPAD_DOWN, 6300, woke));
        assertEquals(IdleGate.Decision.PASS, g.onKey(false, DPAD_DOWN, 6350, woke));
    }

    @Test public void playPauseWakesAndActsImmediately() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.tick(6000);
        boolean[] woke = new boolean[1];
        assertEquals(IdleGate.Decision.PASS, g.onKey(true, PLAY_PAUSE, 6100, woke));
        assertTrue(woke[0]);
        assertFalse(g.isIdle());
        assertEquals(IdleGate.Decision.PASS, g.onKey(false, PLAY_PAUSE, 6150, woke));
    }

    @Test public void keyUpOfAnOlderKeyIsNotSwallowed() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.onKey(true, 22, 100, null); // RIGHT held down before idle (unlikely, but the up must pass)
        g.tick(6000);
        g.onKey(true, DPAD_DOWN, 6100, null); // wakes, swallowed
        assertEquals(IdleGate.Decision.PASS, g.onKey(false, 22, 6150, null));
    }

    @Test public void touchRestartsTheCountdownButNeverWakes() {
        IdleGate g = new IdleGate(IdleGate.IDLE_MS, 0);
        g.touch(4000);
        assertFalse(g.tick(8999));
        g.tick(9000);
        g.touch(9500);
        assertTrue("still idle", g.isIdle());
    }
}
