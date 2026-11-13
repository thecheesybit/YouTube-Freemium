package app.movy.shell;

/**
 * Pure maths for the TV pointer ("cursor mode"): speed ramps up while a D-pad direction is held, the position is
 * clamped to the screen, and pushing against an edge asks the page to scroll. Pixels throughout.
 */
final class CursorMath {
    /** Pixels per second at first press and at full speed, and the time to reach full speed. */
    static final float BASE_SPEED = 420f;
    static final float MAX_SPEED = 2200f;
    static final long RAMP_MS = 900;

    final float w;
    final float h;
    float x;
    float y;

    CursorMath(float w, float h) {
        this.w = w;
        this.h = h;
        this.x = w / 2f;
        this.y = h / 2f;
    }

    /** Speed in px/s after a direction has been held for {@code heldMs}. */
    static float speed(long heldMs) {
        float t = Math.max(0f, Math.min(1f, heldMs / (float) RAMP_MS));
        return BASE_SPEED + (MAX_SPEED - BASE_SPEED) * t * t;
    }

    /** Moves by one tick of {@code dtMs} in direction (dx, dy), each -1..1; false when the pointer did not move. */
    boolean step(int dx, int dy, long heldMs, long dtMs) {
        float d = speed(heldMs) * dtMs / 1000f;
        float nx = clamp(x + dx * d, 0, w - 1);
        float ny = clamp(y + dy * d, 0, h - 1);
        boolean moved = nx != x || ny != y;
        x = nx;
        y = ny;
        return moved;
    }

    /** -1, 0 or 1: the page scrolls vertically when the pointer sits within {@code margin} of an edge and is pushed into it. */
    int edgeScrollY(int dy, float margin) {
        if (dy < 0 && y <= margin) return -1;
        if (dy > 0 && y >= h - 1 - margin) return 1;
        return 0;
    }

    int edgeScrollX(int dx, float margin) {
        if (dx < 0 && x <= margin) return -1;
        if (dx > 0 && x >= w - 1 - margin) return 1;
        return 0;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
