package app.movy.shell;

/**
 * Design tokens shared by the Island, Settings, chooser and error panel (colours mirror values/movy_colors.xml;
 * Canvas code and views read these constants). Radii are dp, spring values are physics parameters.
 */
final class UiTokens {
    private UiTokens() {}

    // ---- colours ----
    static final int BLACK = 0xFF000000;
    static final int WHITE = 0xFFFFFFFF;
    static final int SURFACE = 0xFF1C1C1E;
    static final int CARD = 0xFF2C2C2E;
    static final int SEPARATOR = 0xFF38383A;
    static final int LABEL_SECONDARY = 0xFF8E8E93;
    static final int LABEL_ISLAND = 0xFFEBEBF5;
    static final int HAIRLINE = 0x14FFFFFF;
    static final int BLUE = 0xFF0A84FF;
    static final int GREEN = 0xFF34C759;
    static final int RED = 0xFFFF453A;
    static final int SWITCH_OFF = 0xFF39393D;
    static final int SCRIM = 0x59000000;
    static final int HEALTH_UP = 0xFF30D158;
    static final int HEALTH_SLOW = 0xFFFFD60A;
    static final int HEALTH_DOWN = 0xFFFF453A;
    static final int HEALTH_UNKNOWN = 0xFF8E8E93;
    static final int BADGE_BLUE = 0xFF0A84FF;
    static final int BADGE_GREEN = 0xFF30D158;
    static final int BADGE_ORANGE = 0xFFFF9F0A;
    static final int BADGE_PURPLE = 0xFFBF5AF2;
    static final int BADGE_GREY = 0xFF8E8E93;
    static final int BADGE_RED = 0xFFFF453A;

    // ---- Island geometry (dp) ----
    static final float CAPSULE_W = 7;
    static final float CAPSULE_H = 64;
    static final float SLIVER_W = 2;
    static final float ISLAND_INSET = 6;
    static final float ISLAND_W = 84;
    static final float ISLAND_CORNER = 30;
    static final float TILE = 44;
    static final float TILE_CORNER = 11;
    static final float BUBBLE = 44;

    // ---- panel geometry (dp) ----
    static final float PANEL_W = 420;
    static final float PANEL_MARGIN = 20;
    static final float PANEL_CORNER = 28;
    static final float GROUP_CORNER = 12;
    static final float ROW_MIN = 44;
    static final float BADGE = 29;
    static final float BADGE_CORNER = 7;
    static final float SEPARATOR_INSET = 56;
    static final float BUTTON_CORNER = 14;
    static final float SWITCH_W = 51;
    static final float SWITCH_H = 31;

    // ---- form factors ----
    /** Overscan-safe margins on TV (dp): nothing interactive is drawn closer to the screen edge. */
    static final float TV_SAFE_H = 48;
    static final float TV_SAFE_V = 27;
    /** Text and tile scale for 10-foot viewing. */
    static final float TV_SCALE = 1.25f;
    /** Phones: smallest width below this many dp. */
    static final int COMPACT_MAX_SW_DP = 600;

    static boolean isCompact(int smallestWidthDp) {
        return smallestWidthDp < COMPACT_MAX_SW_DP;
    }

    /** Sheet height as a share of the available height: a bottom sheet never covers the whole screen. */
    static final float SHEET_MAX_FRACTION = 0.9f;

    static int sheetHeight(int availableHeightPx) {
        return Math.round(availableHeightPx * SHEET_MAX_FRACTION);
    }

    // ---- springs: stiffness, damping ratio ----
    static final float EXPAND_STIFFNESS = 380f;
    static final float EXPAND_DAMPING = 0.78f;
    static final float COLLAPSE_STIFFNESS = 500f;
    static final float COLLAPSE_DAMPING = 0.9f;
    static final float PRESS_STIFFNESS = 900f;
    static final float PRESS_DAMPING = 0.7f;
    static final float SOFT_STIFFNESS = 420f;
    static final float SOFT_DAMPING = 0.82f;
    static final float PANEL_STIFFNESS = 320f;
    static final float PANEL_DAMPING = 0.86f;

    // ---- timing (ms) ----
    static final long BUBBLE_DELAY_MS = 70;

    /** Collapsed Island capsule on touch devices: red so it is noticed (blends to black as it expands). */
    static final int ISLAND_IDLE = 0xFFE50914;
    /** Attention wiggle of the collapsed capsule (touch devices only). */
    static final long NUDGE_EVERY_MS = 10_000;
    static final long NUDGE_DURATION_MS = 520;
    /** Peak sideways travel of the wiggle, in dp. */
    static final float NUDGE_AMPLITUDE_DP = 3.5f;
    static final long TILE_STAGGER_MS = 25;
    static final float CONTENT_START = 0.6f;
    static final float PRESS_SCALE = 0.92f;
    static final float INACTIVE_SCALE = 0.94f;
}
