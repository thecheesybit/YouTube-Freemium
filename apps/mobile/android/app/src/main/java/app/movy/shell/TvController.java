package app.movy.shell;

import android.util.Log;
import android.view.KeyEvent;

/**
 * Routes remote-control keys on TV before the WebView sees them: Island keys (MENU and friends), colour keys (sources
 * 1-4), transport keys (video), the long press of CENTER (Island fallback for remotes without MENU) and the long press
 * of BACK (pointer mode). Everything else, including short CENTER presses, goes on to the page unchanged.
 */
final class TvController {
    interface Host {
        /** Chooser, Settings, the error panel or the expanded Island is up: native views own the keys. */
        boolean nativeOverlayActive();

        boolean isFullscreen();

        void toggleIsland();

        void pickSlot(int slot);

        /** CHANNEL_UP / CHANNEL_DOWN: switch to the next (+1) or previous (-1) source and toast its name. */
        void stepSource(int delta);

        void media(String action, int seconds);

        /** Arrow keys while Settings, the chooser or the error panel is open: kept inside the panel. True when consumed. */
        boolean trapKeys(KeyEvent e);

        /** Sends the event on to the normal dispatch (Activity -> WebView). */
        boolean deliver(KeyEvent e);
    }

    private static final String TAG = "MovyKeys";

    private final Host host;
    private final TvCursor cursor;
    private final boolean debug;
    private final LongPressGate centerGate = new LongPressGate();
    private boolean backLong;

    TvController(Host host, TvCursor cursor, boolean debug) {
        this.host = host;
        this.cursor = cursor;
        this.debug = debug;
    }

    TvCursor cursor() {
        return cursor;
    }

    /** Focus trap for the open native panel; false when none is open or while the pointer is active. */
    boolean trapPanelKeys(KeyEvent e) {
        return !cursor.isActive() && host.trapKeys(e);
    }

    /** True when the event was consumed here. */
    boolean dispatch(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (debug) {
            Log.i(TAG, "key " + KeyEvent.keyCodeToString(code) + " (" + code + ") " + (down ? "DOWN" : "UP") + " repeat="
                    + e.getRepeatCount() + " scan=" + e.getScanCode() + " src=0x" + Integer.toHexString(e.getSource())
                    + " dev=" + e.getDeviceId());
        }
        if (code == KeyEvent.KEYCODE_BACK) return handleBack(e, down);
        if (cursor.isActive() && cursor.handle(e)) return true;

        TvKeys.Result r = TvKeys.classify(code);
        switch (r.action) {
            case TOGGLE_ISLAND:
                if (down && e.getRepeatCount() == 0) host.toggleIsland();
                return true;
            case NEXT_SOURCE:
            case PREV_SOURCE:
                if (down && e.getRepeatCount() == 0 && !host.nativeOverlayActive()) host.stepSource(r.action == TvKeys.Action.NEXT_SOURCE ? 1 : -1);
                return true;
            case SOURCE:
                if (down && e.getRepeatCount() == 0) host.pickSlot(r.slot);
                return true;
            case PLAY_PAUSE:
            case PLAY:
            case PAUSE:
            case FORWARD:
            case REWIND:
                if (host.nativeOverlayActive()) return false;
                if (down && (e.getRepeatCount() == 0 || r.action == TvKeys.Action.FORWARD || r.action == TvKeys.Action.REWIND)) {
                    int secs = TvKeys.seekSeconds(r.action);
                    host.media(secs != 0 ? "seek" : r.action == TvKeys.Action.PLAY ? "play"
                            : r.action == TvKeys.Action.PAUSE ? "pause" : "playpause", secs);
                }
                return true;
            default:
                break;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER && !cursor.isActive()) return handleCenter(e, down);
        return false;
    }

    /**
     * Long press of BACK toggles pointer mode; the matching key-up is swallowed. A short BACK while the pointer is up
     * leaves pointer mode.
     */
    private boolean handleBack(KeyEvent e, boolean down) {
        if (down && e.getRepeatCount() >= 1 && !backLong && !host.nativeOverlayActive()) {
            backLong = true;
            cursor.toggle();
            return true;
        }
        if (backLong) {
            if (!down) backLong = false;
            return true;
        }
        if (!down && cursor.isActive()) {
            cursor.exit();
            return true;
        }
        return cursor.isActive() && down;
    }

    /**
     * CENTER: held back until release so a long press can open the Island without the page also seeing a click. A short
     * press is replayed (down + up) to the page on release. Not used over native views or in fullscreen video.
     */
    private boolean handleCenter(KeyEvent e, boolean down) {
        boolean eligible = !host.nativeOverlayActive() && !host.isFullscreen();
        switch (centerGate.onEvent(down, e.getRepeatCount(), eligible)) {
            case OPEN:
                host.toggleIsland();
                return true;
            case SWALLOW:
                return true;
            case REPLAY_CLICK:
                long t = e.getDownTime();
                host.deliver(new KeyEvent(t, e.getEventTime(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
                host.deliver(new KeyEvent(t, e.getEventTime(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
                return true;
            default:
                return false;
        }
    }
}
