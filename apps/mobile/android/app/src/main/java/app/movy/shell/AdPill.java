package app.movy.shell;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A dynamic-island style pill that drops from the top centre while an ad is being skipped. */
final class AdPill {
    private static final long MIN_VISIBLE_MS = 1400;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView pill;
    private final float drop;
    private boolean visible;
    private long shownAt;
    private Runnable pendingHide;

    AdPill(Activity act) {
        float d = act.getResources().getDisplayMetrics().density;
        drop = 36 * d;
        pill = new TextView(act);
        pill.setText("Skipping ads for you");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(14f);
        pill.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (10 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2000000);
        bg.setCornerRadius(100 * d);
        bg.setStroke((int) Math.max(1, d), 0x33FFFFFF);
        pill.setBackground(bg);
        pill.setElevation(12 * d);
        pill.setAlpha(0f);
        pill.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (34 * d);
        ((ViewGroup) act.getWindow().getDecorView()).addView(pill, lp);
        pill.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY(0f);
        });
    }

    /** Heartbeat from the page; if it stops (ad gone, page changed) the pill leaves by itself. */
    private final Runnable watchdog = this::hide;

    void show() {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 3500);
        if (pendingHide != null) {
            main.removeCallbacks(pendingHide);
            pendingHide = null;
        }
        if (visible) return;
        visible = true;
        shownAt = SystemClock.elapsedRealtime();
        pill.setVisibility(View.VISIBLE);
        pill.setScaleX(0.25f);
        pill.setScaleY(0.55f);
        pill.setTranslationY(-drop);
        pill.setAlpha(0f);
        pill.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(420).setInterpolator(new OvershootInterpolator(1.1f)).start();
    }

    void hide() {
        main.removeCallbacks(watchdog);
        if (!visible || pendingHide != null) return;
        long wait = Math.max(0, MIN_VISIBLE_MS - (SystemClock.elapsedRealtime() - shownAt));
        pendingHide = () -> {
            pendingHide = null;
            visible = false;
            pill.animate().alpha(0f).scaleX(0.25f).scaleY(0.55f).translationY(-drop)
                    .setDuration(260).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> pill.setVisibility(View.INVISIBLE)).start();
        };
        main.postDelayed(pendingHide, wait);
    }
}
