package app.movy.shell;

import android.view.KeyEvent;

/**
 * Pure classification of remote-control keys (TV). Which keycode a given remote's MENU / tools / guide button sends
 * differs per vendor, so several candidates open the Island; the colour keys jump to sources 1-4 and the transport
 * keys control the page's video. No Android state here: unit tested.
 */
final class TvKeys {
    private TvKeys() {}

    enum Action { NONE, TOGGLE_ISLAND, SOURCE, NEXT_SOURCE, PREV_SOURCE, PLAY_PAUSE, PLAY, PAUSE, FORWARD, REWIND }

    /** Result of {@link #classify}: an action plus, for SOURCE, the 0-based source slot. */
    static final class Result {
        final Action action;
        final int slot;

        Result(Action action, int slot) {
            this.action = action;
            this.slot = slot;
        }
    }

    private static final Result NONE = new Result(Action.NONE, -1);
    private static final Result NEXT = new Result(Action.NEXT_SOURCE, -1);
    private static final Result PREV = new Result(Action.PREV_SOURCE, -1);
    private static final Result ISLAND = new Result(Action.TOGGLE_ISLAND, -1);

    /** Every keycode a vendor remote may use for MENU / tools / guide / info; all open the Island. */
    static boolean opensIsland(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MENU:                  // 82
            case KeyEvent.KEYCODE_INFO:                  // 165
            case KeyEvent.KEYCODE_GUIDE:                 // 172
            case KeyEvent.KEYCODE_BOOKMARK:              // 174
            case KeyEvent.KEYCODE_SETTINGS:              // 176
            case KeyEvent.KEYCODE_MEDIA_TOP_MENU:        // 226
            case KeyEvent.KEYCODE_TV_DATA_SERVICE:       // 230
            case KeyEvent.KEYCODE_TV_TELETEXT:           // 233
            case KeyEvent.KEYCODE_TV_CONTENTS_MENU:      // 256
            case KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU: // 257
                return true;
            default:
                return false;
        }
    }

    /** Red, green, yellow, blue map to 0..3, anything else to -1. */
    static int colourSlot(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_PROG_RED: return 0;
            case KeyEvent.KEYCODE_PROG_GREEN: return 1;
            case KeyEvent.KEYCODE_PROG_YELLOW: return 2;
            case KeyEvent.KEYCODE_PROG_BLUE: return 3;
            default: return -1;
        }
    }

    private static Action transportAction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return Action.PLAY_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return Action.PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return Action.PAUSE;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD: return Action.FORWARD;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD: return Action.REWIND;
            default: return Action.NONE;
        }
    }

    static Result classify(int code) {
        if (opensIsland(code)) return ISLAND;
        if (code == KeyEvent.KEYCODE_CHANNEL_UP) return NEXT;
        if (code == KeyEvent.KEYCODE_CHANNEL_DOWN) return PREV;
        int slot = colourSlot(code);
        if (slot >= 0) return new Result(Action.SOURCE, slot);
        Action t = transportAction(code);
        return t == Action.NONE ? NONE : new Result(t, -1);
    }

    /** Signed seek step in seconds for FORWARD / REWIND; 0 for other actions. */
    static int seekSeconds(Action a) {
        return a == Action.FORWARD ? 10 : a == Action.REWIND ? -10 : 0;
    }

    /** Colour of the dot on the Nth Island tile (TV): red, green, yellow, blue; 0 when there is no colour key for that slot. */
    static int colourFor(int slot) {
        switch (slot) {
            case 0: return 0xFFFF453A;
            case 1: return 0xFF30D158;
            case 2: return 0xFFFFD60A;
            case 3: return 0xFF0A84FF;
            default: return 0;
        }
    }

    /** Wrapping index of the source after a step of delta from current (current < 0 starts at the first/last). */
    static int stepSlot(int current, int count, int delta) {
        if (count <= 0) return -1;
        if (current < 0) return delta > 0 ? 0 : count - 1;
        return ((current + delta) % count + count) % count;
    }
}
