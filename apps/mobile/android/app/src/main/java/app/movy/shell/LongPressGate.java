package app.movy.shell;

/**
 * Pure state machine for a held remote key (D-pad CENTER). A short press is replayed on release; holding it fires
 * OPEN once and then swallows every further event of that hold (auto-repeats every ~50 ms and the final UP) so they
 * never reach the view the long press just opened. A fresh DOWN (repeat 0) starts over.
 */
final class LongPressGate {
    enum Out { PASS, SWALLOW, OPEN, REPLAY_CLICK }

    private boolean held;
    private boolean fired;

    /**
     * @param down     ACTION_DOWN (true) or ACTION_UP (false)
     * @param repeat   KeyEvent.getRepeatCount()
     * @param eligible whether a fresh press may start a long-press (false over native views / fullscreen video)
     */
    Out onEvent(boolean down, int repeat, boolean eligible) {
        if (down) {
            if (repeat == 0) {
                fired = false;
                held = eligible;
                return eligible ? Out.SWALLOW : Out.PASS;
            }
            if (fired) return Out.SWALLOW;
            if (held) {
                fired = true;
                return Out.OPEN;
            }
            return Out.PASS;
        }
        boolean wasFired = fired;
        boolean wasHeld = held;
        held = false;
        fired = false;
        if (wasFired) return Out.SWALLOW;
        return wasHeld ? Out.REPLAY_CLICK : Out.PASS;
    }
}
