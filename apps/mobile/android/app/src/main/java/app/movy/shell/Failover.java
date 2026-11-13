package app.movy.shell;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Failover: when the active source's main-frame load fails (network error, 5xx, or no first paint / 80% progress
 * within 15 s) the error panel offers Retry and, when health knows a working source, "Switch to X". It never switches by
 * itself. Shows names only, never URLs or hosts.
 */
final class Failover implements HealthChecker.Listener {
    static final long TIMEOUT_MS = 15_000;
    private static final int ALIVE_PROGRESS = 80;

    interface Listener {
        /** The error panel is up; {@code suggested} (nullable) is the source to highlight in the Island. */
        void onFailureShown(Source suggested);
    }

    private final Activity activity;
    private final SourceRepository repo;
    private final HealthChecker health;
    private final LaunchController launch;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::onFailure;

    Failover(Activity activity, SourceRepository repo, HealthChecker health, LaunchController launch, Listener listener) {
        this.activity = activity;
        this.repo = repo;
        this.health = health;
        this.launch = launch;
        this.listener = listener;
        health.addListener(this);
    }

    /** A main-frame load started: arm the 15 s watchdog. */
    void onLoadStarted() {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, TIMEOUT_MS);
    }

    /** The load is clearly alive: first paint or progress past 80%. */
    void onAlive() {
        main.removeCallbacks(timeout);
    }

    void onProgress(int percent) {
        if (percent >= ALIVE_PROGRESS) onAlive();
    }

    /** Network error, 5xx or watchdog timeout for the active source. */
    void onFailure() {
        main.removeCallbacks(timeout);
        Source active = repo.active();
        if (active == null || activity.isFinishing()) return;
        launch.onMainFrameError(); // shows "<Name> isn't responding" + Retry, drops the splash
        health.refreshNow(); // fresh dots and a fresh suggestion
        Source suggestion = fillExtras(active);
        listener.onFailureShown(suggestion);
    }

    @Override
    public void onHealthChanged() {
        ErrorPanel panel = launch.errorPanel();
        Source active = repo.active();
        if (!panel.isShowing() || active == null || panel.extrasContainer().getChildCount() > 0) return;
        Source suggestion = fillExtras(active);
        if (suggestion != null) listener.onFailureShown(suggestion);
    }

    private Source fillExtras(Source active) {
        ViewGroup extras = launch.errorPanel().extrasContainer();
        extras.removeAllViews();
        Source suggestion = health.suggestion(active.id);
        if (suggestion == null) return null;
        TextView b = IosViews.text(activity, activity.getString(R.string.movy_error_switch, suggestion.name), 17, UiTokens.BLUE, true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(0, Ui.dp(activity, 15), 0, Ui.dp(activity, 15));
        IosViews.style(b, UiTokens.SURFACE, false);
        b.setOnFocusChangeListener((v, f) -> IosViews.style(b, UiTokens.SURFACE, f));
        b.setOnClickListener(v -> {
            launch.errorPanel().hide();
            repo.switchTo(suggestion.id);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(activity, 12);
        extras.addView(b, lp);
        return suggestion;
    }
}
