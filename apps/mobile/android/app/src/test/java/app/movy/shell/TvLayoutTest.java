package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The three TV window sizes the layout must handle, in dp: 640x360 (720p at 2x), 960x540 (this TV), 1280x720 (1080p at 1.5x). */
public class TvLayoutTest {
    private static final int[][] SIZES = {{640, 360}, {960, 540}, {1280, 720}};

    @Test public void safeAreaKeepsOverscanMargins() {
        assertEquals(544, TvLayout.usableWidthDp(640));
        assertEquals(864, TvLayout.usableWidthDp(960));
        assertEquals(306, TvLayout.usableHeightDp(360));
        assertEquals(486, TvLayout.usableHeightDp(540));
    }

    @Test public void fourCardsFitOneRowAtEverySize() {
        for (int[] s : SIZES) {
            int w = TvLayout.cardWidthDp(s[0], 4);
            int row = 4 * w + 3 * TvLayout.CARD_GAP_DP;
            assertTrue(s[0] + " card " + w, w >= TvLayout.CARD_MIN_DP && w <= TvLayout.CARD_MAX_DP);
            if (s[0] >= 960) assertTrue("row " + row, row <= TvLayout.usableWidthDp(s[0]));
        }
        assertEquals(204, TvLayout.cardWidthDp(960, 4));
        assertEquals(220, TvLayout.cardWidthDp(1280, 4));
        assertEquals(124, TvLayout.cardWidthDp(640, 4));
        assertEquals(4, TvLayout.cardsPerRow(960, 4));
        assertEquals(4, TvLayout.cardsPerRow(1280, 4));
    }

    @Test public void narrowScreensWrapInsteadOfOverflowing() {
        int per = TvLayout.cardsPerRow(640, 4);
        assertTrue(per >= 1 && per <= 4);
        assertTrue(per * TvLayout.cardWidthDp(640, 4) + (per - 1) * TvLayout.CARD_GAP_DP <= TvLayout.usableWidthDp(640));
    }

    @Test public void panelWidthIsCappedToSixtyPercent() {
        assertEquals(384, TvLayout.panelWidthDp(640));
        assertEquals(546, TvLayout.panelWidthDp(960));
        assertEquals(546, TvLayout.panelWidthDp(1280));
    }

    @Test public void islandScaleFitsFourTilesAndStaysInRange() {
        for (int[] s : SIZES) {
            float k = TvLayout.islandScale(s[1]);
            assertTrue(k >= 1f && k <= 1.4f);
            float needed = (98 + 76 * 4) * k;
            if (s[1] >= 540) assertTrue(s[1] + " needs " + needed, needed <= s[1] + 0.5f);
        }
        assertEquals(1f, TvLayout.islandScale(360), 0.001f);
        assertEquals(1.4f, TvLayout.islandScale(720), 0.001f);
    }

    @Test public void textScaleAndDensity() {
        assertEquals(1.1f, TvLayout.textScale(640), 0.001f);
        assertEquals(UiTokens.TV_SCALE, TvLayout.textScale(960), 0.001f);
        assertTrue(TvLayout.dense(360));
        assertFalse(TvLayout.dense(540));
    }
}
