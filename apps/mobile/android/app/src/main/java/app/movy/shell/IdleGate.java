package app.movy.shell;

/**
 * Pure state machine for the TV idle mode: after {@link #IDLE_MS} without a key everything hides; the first key after
 * that only wakes (it is swallowed together with its repeats and its key-up). Play/pause is the exception: it wakes and
 * acts at once. Time is passed in so it can be unit tested.
 */
final class IdleGate {
    static final long IDLE_MS = 5000;

    enum Decision { PASS, SWALLOW }

    private final long idleMs;
    private boolean idle;
    private long lastKeyAt;
    private int swallowing = -1;

    IdleGate(long idleMs, long now) {
        this.idleMs = idleMs;
        this.lastKeyAt = now;
    }

    boolean isIdle() {
        return idle;
    }

    /** Time at which the next idle check is due (only meaningful while awake). */
    long dueAt() {
        return lastKeyAt + idleMs;
    }

    /** Keys that act immediately even when they wake (transport). */
    static boolean actsWhenWaking(int keyCode) {
        return keyCode == 85 || keyCode == 126 || keyCode == 127;
    }

    /** Feeds one key event. {@code woke} (out, index 0) is set when this event ended the idle state. */
    Decision onKey(boolean down, int keyCode, long now, boolean[] woke) {
        if (woke != null && woke.length > 0) woke[0] = false;
        if (!down) {
            if (swallowing == keyCode) {
                swallowing = -1;
                return Decision.SWALLOW;
            }
            return Decision.PASS;
        }
        if (swallowing == keyCode) return Decision.SWALLOW; // held key repeats
        if (idle) {
            idle = false;
            lastKeyAt = now;
            if (woke != null && woke.length > 0) woke[0] = true;
            if (actsWhenWaking(keyCode)) return Decision.PASS;
            swallowing = keyCode;
            return Decision.SWALLOW;
        }
        lastKeyAt = now;
        return Decision.PASS;
    }

    /** Checks the clock; true when this call switched to idle. */
    boolean tick(long now) {
        if (!idle && now - lastKeyAt >= idleMs) {
            idle = true;
            return true;
        }
        return false;
    }

    /** Resets the timer without a key (native UI changes, page loads). */
    void touch(long now) {
        if (!idle) lastKeyAt = now;
    }
}
