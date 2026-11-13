package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class TvKeysTest {
    @Test public void menuAndVendorMenuKeysOpenTheIsland() {
        int[] keys = {82, 165, 172, 174, 176, 226, 230, 233, 256, 257};
        for (int k : keys) {
            assertTrue("key " + k, TvKeys.opensIsland(k));
            assertEquals(TvKeys.Action.TOGGLE_ISLAND, TvKeys.classify(k).action);
        }
        assertEquals(KeyEvent.KEYCODE_MENU, 82);
    }

    @Test public void dpadBackAndHomeNeverOpenTheIsland() {
        int[] keys = {19, 20, 21, 22, 23, 4, 3, 66};
        for (int k : keys) assertFalse("key " + k, TvKeys.opensIsland(k));
    }

    @Test public void colourKeysMapToSourcesOneToFourInOrder() {
        assertEquals(0, TvKeys.colourSlot(183));
        assertEquals(1, TvKeys.colourSlot(184));
        assertEquals(2, TvKeys.colourSlot(185));
        assertEquals(3, TvKeys.colourSlot(186));
        assertEquals(-1, TvKeys.colourSlot(187));
        TvKeys.Result r = TvKeys.classify(185);
        assertEquals(TvKeys.Action.SOURCE, r.action);
        assertEquals(2, r.slot);
    }

    @Test public void colourDotsAreRedGreenYellowBlue() {
        assertEquals(0xFFFF453A, TvKeys.colourFor(0));
        assertEquals(0xFF30D158, TvKeys.colourFor(1));
        assertEquals(0xFFFFD60A, TvKeys.colourFor(2));
        assertEquals(0xFF0A84FF, TvKeys.colourFor(3));
        assertEquals(0, TvKeys.colourFor(4));
    }

    @Test public void transportKeysClassify() {
        assertEquals(TvKeys.Action.PLAY_PAUSE, TvKeys.classify(85).action);
        assertEquals(TvKeys.Action.PLAY, TvKeys.classify(126).action);
        assertEquals(TvKeys.Action.PAUSE, TvKeys.classify(127).action);
        assertEquals(TvKeys.Action.FORWARD, TvKeys.classify(90).action);
        assertEquals(TvKeys.Action.REWIND, TvKeys.classify(89).action);
        assertEquals(10, TvKeys.seekSeconds(TvKeys.Action.FORWARD));
        assertEquals(-10, TvKeys.seekSeconds(TvKeys.Action.REWIND));
        assertEquals(0, TvKeys.seekSeconds(TvKeys.Action.PLAY));
        assertEquals(TvKeys.Action.NONE, TvKeys.classify(29).action);
    }

    @Test public void channelKeysStepSources() {
        assertEquals(TvKeys.Action.NEXT_SOURCE, TvKeys.classify(166).action);
        assertEquals(TvKeys.Action.PREV_SOURCE, TvKeys.classify(167).action);
        assertEquals(1, TvKeys.stepSlot(0, 4, 1));
        assertEquals(0, TvKeys.stepSlot(3, 4, 1));
        assertEquals(3, TvKeys.stepSlot(0, 4, -1));
        assertEquals(0, TvKeys.stepSlot(-1, 4, 1));
        assertEquals(3, TvKeys.stepSlot(-1, 4, -1));
        assertEquals(-1, TvKeys.stepSlot(0, 0, 1));
    }
}
