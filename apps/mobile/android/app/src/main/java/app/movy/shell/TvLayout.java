package app.movy.shell;

/**
 * Pure 10-foot sizing for the native TV screens, computed from the window size in dp so it behaves the same at
 * 640x360 (720p at 2x), 960x540 (1080p at 2x) and 1280x720 (1080p at 1.5x).
 */
final class TvLayout {
    private TvLayout() {}

    static final int CARD_GAP_DP = 16;
    static final int CARD_MIN_DP = 120;
    static final int CARD_MAX_DP = 220;

    /** Width inside the overscan-safe margins. */
    static int usableWidthDp(int screenWidthDp) {
        return Math.max(0, screenWidthDp - 2 * Math.round(UiTokens.TV_SAFE_H));
    }

    static int usableHeightDp(int screenHeightDp) {
        return Math.max(0, screenHeightDp - 2 * Math.round(UiTokens.TV_SAFE_V));
    }

    /** Chooser card width so that {@code count} cards fit one row inside the safe area, within the design bounds. */
    static int cardWidthDp(int screenWidthDp, int count) {
        int n = Math.max(1, count);
        int fit = (usableWidthDp(screenWidthDp) - (n - 1) * CARD_GAP_DP) / n;
        return Math.max(CARD_MIN_DP, Math.min(CARD_MAX_DP, fit));
    }

    /** Cards that fit one row at {@link #cardWidthDp}. */
    static int cardsPerRow(int screenWidthDp, int count) {
        int w = cardWidthDp(screenWidthDp, count);
        int usable = usableWidthDp(screenWidthDp);
        return Math.max(1, Math.min(Math.max(1, count), (usable + CARD_GAP_DP) / (w + CARD_GAP_DP)));
    }

    /** Settings side panel width: 420dp * 1.3, never more than 60% of the screen. */
    static int panelWidthDp(int screenWidthDp) {
        return Math.min(Math.round(UiTokens.PANEL_W * 1.3f), Math.round(screenWidthDp * 0.6f));
    }

    /** Text scale: a little smaller on the 640dp-wide (720p) layout so rows do not overflow. */
    static float textScale(int screenWidthDp) {
        return screenWidthDp < 800 ? 1.1f : UiTokens.TV_SCALE;
    }

    /** Island scale (1.0..1.4) so that four source tiles plus the settings bubble fit the screen height without scrolling. */
    static float islandScale(int screenHeightDp) {
        float fit = screenHeightDp / (float) (98 + 76 * 4);
        return Math.max(1f, Math.min(1.4f, fit));
    }

    /** Dense layout on short (720p at 2x) screens: gaps shrink. */
    static boolean dense(int screenHeightDp) {
        return screenHeightDp < 400;
    }
}
