package app.movy.adblock;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/** Cheap counters; logs a summary to logcat tag "MovyAdblock" every LOG_EVERY blocks. */
public final class BlockStats {
    private static final int LOG_EVERY = 10;
    private final AtomicInteger blocked = new AtomicInteger();
    private final AtomicInteger redirected = new AtomicInteger();
    private final AtomicInteger checked = new AtomicInteger();
    private final AtomicInteger popupsKilled = new AtomicInteger();
    private final AtomicInteger navigationsKilled = new AtomicInteger();
    /** Debug builds may log URLs; release logs redact them (same hashing as KillLog). */
    private final boolean debug;

    public BlockStats(boolean debug) {
        this.debug = debug;
    }

    public void onPopupKilled() {
        popupsKilled.incrementAndGet();
    }

    public void onNavigationKilled() {
        navigationsKilled.incrementAndGet();
    }

    public int popupsKilled() {
        return popupsKilled.get();
    }

    public int navigationsKilled() {
        return navigationsKilled.get();
    }

    /** Ads and trackers blocked this session (plain blocks plus redirect-resource replacements). */
    public int adsBlocked() {
        return blocked.get() + redirected.get();
    }

    /** "Redirects killed" total for a later UI. */
    public int redirectsKilled() {
        return popupsKilled.get() + navigationsKilled.get();
    }

    void onChecked() {
        checked.incrementAndGet();
    }

    void onBlocked(String url, String type, boolean redirect) {
        int total = (redirect ? redirected : blocked).incrementAndGet() + (redirect ? blocked : redirected).get();
        if (total == 1 || total % LOG_EVERY == 0) {
            Log.i(AdblockEngine.TAG, "blocked=" + blocked.get() + " redirected=" + redirected.get()
                    + " checked=" + checked.get() + " last=" + type + " " + abbreviate(url));
        }
    }

    /** One-line summary, logged on each page finish. */
    void logSummary(String page) {
        Log.i(AdblockEngine.TAG, "page done blocked=" + blocked.get() + " redirected=" + redirected.get()
                + " checked=" + checked.get() + " redirectsKilled=" + redirectsKilled() + " page=" + abbreviate(page == null ? "" : page));
    }

    private String abbreviate(String s) {
        return app.movy.shell.KillLog.describe(s, debug);
    }
}
