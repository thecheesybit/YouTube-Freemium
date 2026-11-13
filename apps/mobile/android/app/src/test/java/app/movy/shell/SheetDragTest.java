package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SheetDragTest {
    @Test public void longPullDismisses() {
        assertTrue(SheetDrag.shouldDismiss(700f, 2000f, 0f, 3f));
    }

    @Test public void shortSlowPullSpringsBack() {
        assertFalse(SheetDrag.shouldDismiss(200f, 2000f, 100f, 3f));
    }

    @Test public void fastFlingDismissesEvenWhenShort() {
        assertTrue(SheetDrag.shouldDismiss(100f, 2000f, 4000f, 3f));
    }

    @Test public void pullingUpOrFlingingBackNeverDismisses() {
        assertFalse(SheetDrag.shouldDismiss(-50f, 2000f, 5000f, 3f));
        assertFalse(SheetDrag.shouldDismiss(700f, 2000f, -5000f, 3f));
    }

    @Test public void sheetNeverCoversFullHeight() {
        assertEquals(1800, UiTokens.sheetHeight(2000));
        assertTrue(UiTokens.isCompact(411));
        assertFalse(UiTokens.isCompact(600));
    }
}
