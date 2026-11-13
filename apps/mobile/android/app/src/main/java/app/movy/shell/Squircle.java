package app.movy.shell;

import android.graphics.Path;

/** Continuous-curvature rounded rectangles ("squircles") from four cubics. Reuses the given Path, no allocation. */
final class Squircle {
    /** Control-point ratio of a quarter circle. */
    private static final float CIRCLE = 0.5523f;
    /** Control-point ratio of the full squircle corner (reaches further along the edges than a circle). */
    private static final float SMOOTH = 0.60f;
    private static final float REACH = 1.17f;

    private Squircle() {}

    /**
     * @param smooth 0 = plain rounded rect (exact capsule when radius is half the short side), 1 = full squircle
     */
    static void rect(Path p, float l, float t, float r, float b, float radius, float smooth) {
        float half = Math.min(r - l, b - t) / 2f;
        float rad = Math.max(0f, Math.min(radius, half));
        float s = Math.max(0f, Math.min(1f, smooth));
        float k = Math.min(rad * (1f + (REACH - 1f) * s), half);
        float c = k * (CIRCLE + (SMOOTH - CIRCLE) * s); // control offset from each end point, toward the corner
        p.rewind();
        p.moveTo(l + k, t);
        p.lineTo(r - k, t);
        p.cubicTo(r - k + c, t, r, t + k - c, r, t + k);
        p.lineTo(r, b - k);
        p.cubicTo(r, b - k + c, r - k + c, b, r - k, b);
        p.lineTo(l + k, b);
        p.cubicTo(l + k - c, b, l, b - k + c, l, b - k);
        p.lineTo(l, t + k);
        p.cubicTo(l, t + k - c, l + k - c, t, l + k, t);
        p.close();
    }

    /** Smoothness that fades in with the radius so tiny radii stay circular. */
    static float smoothFor(float radiusPx, float density) {
        return Math.max(0f, Math.min(1f, (radiusPx / density - 6f) / 12f));
    }
}
