package app.movy.shell;

import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Keeps D-pad focus inside an open native panel (Settings, chooser, error panel): arrow keys never reach the page behind
 * and never move focus out of the panel. Exactly one element is focused: when none is, the first focusable gets it.
 */
final class TvFocusTrap {
    private TvFocusTrap() {}

    /** True when the event was an arrow key (always consumed while the trap is active). Other keys fall through. */
    static boolean handle(KeyEvent e, ViewGroup scope) {
        int dir = direction(e.getKeyCode());
        if (dir == 0) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        View focus = scope.findFocus();
        if (focus == null) {
            View first = firstFocusable(scope);
            if (first != null) first.requestFocus();
            return true;
        }
        View next = focus.focusSearch(dir);
        if (next != null && next != focus && isInside(next, scope)) next.requestFocus();
        return true;
    }

    /** Re-establishes focus after the panel's content was rebuilt; returns true when something took focus. */
    static boolean ensureFocus(ViewGroup scope) {
        if (scope.findFocus() != null) return true;
        View first = firstFocusable(scope);
        return first != null && first.requestFocus();
    }

    static View firstFocusable(ViewGroup scope) {
        java.util.ArrayList<View> out = new java.util.ArrayList<>();
        scope.addFocusables(out, View.FOCUS_DOWN, View.FOCUSABLES_ALL);
        for (View v : out) if (v.isShown() && v.isFocusable()) return v;
        return null;
    }

    static boolean isInside(View v, ViewGroup scope) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == scope) return true;
        return v == scope;
    }

    /** View.FOCUS_* for a D-pad keycode, 0 when it is not an arrow. */
    static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            default: return 0;
        }
    }
}
