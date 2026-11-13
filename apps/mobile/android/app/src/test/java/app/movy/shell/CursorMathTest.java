package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CursorMathTest {
    @Test public void speedRampsFromBaseToMax() {
        assertEquals(CursorMath.BASE_SPEED, CursorMath.speed(0), 0.01f);
        assertEquals(CursorMath.MAX_SPEED, CursorMath.speed(CursorMath.RAMP_MS), 0.01f);
        assertEquals(CursorMath.MAX_SPEED, CursorMath.speed(10_000), 0.01f);
        assertTrue(CursorMath.speed(450) > CursorMath.speed(100));
    }

    @Test public void startsInTheMiddleAndMoves() {
        CursorMath m = new CursorMath(1920, 1080);
        assertEquals(960f, m.x, 0.01f);
        assertEquals(540f, m.y, 0.01f);
        assertTrue(m.step(1, 0, 0, 16));
        assertTrue(m.x > 960f);
        assertEquals(540f, m.y, 0.01f);
    }

    @Test public void clampsToTheScreenAndReportsNoMovement() {
        CursorMath m = new CursorMath(100, 100);
        for (int i = 0; i < 200; i++) m.step(1, 1, 2000, 16);
        assertEquals(99f, m.x, 0.01f);
        assertEquals(99f, m.y, 0.01f);
        assertFalse(m.step(1, 1, 2000, 16));
    }

    @Test public void edgeScrollOnlyWhenPushedIntoTheEdge() {
        CursorMath m = new CursorMath(1920, 1080);
        assertEquals(0, m.edgeScrollY(1, 40));
        m.y = 1075;
        assertEquals(1, m.edgeScrollY(1, 40));
        assertEquals(0, m.edgeScrollY(-1, 40));
        m.y = 5;
        assertEquals(-1, m.edgeScrollY(-1, 40));
        m.x = 5;
        assertEquals(-1, m.edgeScrollX(-1, 40));
        m.x = 1915;
        assertEquals(1, m.edgeScrollX(1, 40));
        assertEquals(0, m.edgeScrollX(0, 40));
    }
}
