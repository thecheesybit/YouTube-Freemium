package app.movy.shell;

/**
 * Pure rules of the lean TV app: the movie is never kept on disk (Chromium's HTTP cache is trimmed above 100 MB) and RAM is
 * released as soon as the app is not on screen. Android-free so it can be unit tested.
 */
final class LeanPolicy {
    /** WebView HTTP cache above this many bytes is cleared (cold start, and after playback when backgrounded). */
    static final long HTTP_CACHE_LIMIT_BYTES = 100L * 1024 * 1024;
    /** In the background for longer than this: destroy the WebView and finish. */
    static final long BACKGROUND_DESTROY_MS = 60_000L;

    // ComponentCallbacks2 levels (copied so the policy stays free of Android classes)
    static final int TRIM_UI_HIDDEN = 20;
    static final int TRIM_RUNNING_LOW = 10;

    enum TrimAction { NONE, RELEASE_RAM, BACKGROUND }

    private LeanPolicy() {}

    static boolean shouldTrimHttpCache(long bytes) {
        return bytes > HTTP_CACHE_LIMIT_BYTES;
    }

    /** UI_HIDDEN behaves as background; RUNNING_LOW and above drops the RAM caches. */
    static TrimAction onTrimMemory(int level) {
        if (level == TRIM_UI_HIDDEN) return TrimAction.BACKGROUND;
        if (level >= TRIM_RUNNING_LOW) return TrimAction.RELEASE_RAM;
        return TrimAction.NONE;
    }

    static boolean backgroundTimeoutReached(long backgroundedAtMs, long nowMs) {
        return nowMs - backgroundedAtMs >= BACKGROUND_DESTROY_MS;
    }
}
