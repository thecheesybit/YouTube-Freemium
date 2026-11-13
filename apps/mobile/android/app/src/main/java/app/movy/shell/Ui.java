package app.movy.shell;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Tiny view helpers shared by the native launch screens. */
final class Ui {
    static final int BLACK = 0xFF000000;
    static final int WHITE = 0xFFFFFFFF;
    static final int MUTED = 0xFF8E8E93;
    static final int CARD = 0xFF151517;
    static final int CARD_STROKE = 0xFF2C2C2E;

    private Ui() {}

    static boolean isTv(Context c) {
        return c.getResources().getBoolean(R.bool.movy_is_tv);
    }

    /** Side padding for full-screen native panels: overscan-safe on TV, none elsewhere. */
    static int safeH(Context c) {
        return isTv(c) ? dp(c, UiTokens.TV_SAFE_H) : 0;
    }

    static int safeV(Context c) {
        return isTv(c) ? dp(c, UiTokens.TV_SAFE_V) : 0;
    }

    /** Text size multiplier (10-foot on TV). */
    static float textScale(Context c) {
        return isTv(c) ? TvLayout.textScale(c.getResources().getConfiguration().screenWidthDp) : 1f;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return g;
    }

    static GradientDrawable ring(Context c, int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /** Black or white, whichever reads better on {@code bg}. */
    static int onColor(int bg) {
        double l = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg);
        return l > 150 ? BLACK : WHITE;
    }

    static void fadeIn(View v, long delayMs, float fromDy) {
        v.setAlpha(0f);
        v.setTranslationY(fromDy);
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
}
