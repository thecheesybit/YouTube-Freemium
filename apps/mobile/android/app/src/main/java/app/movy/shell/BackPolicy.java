package app.movy.shell;

import java.util.List;

/**
 * Back-button policy for YouTube: Back from a video skips earlier videos and
 * lands on the last list page (home, search results, channel). Pure Java with
 * no Android imports, so it is unit-testable without an emulator.
 */
final class BackPolicy {
    /** Caller does normal goBack(). */
    static final int DEFAULT = 0;
    /** Caller loads the active source's home URL (clears history). */
    static final int HOME = 1;

    public enum Action {
        EXIT_FULLSCREEN,
        MINIMIZE_PLAYER,
        BROWSE_BACK,
        MOVE_TASK_TO_BACK,
        FINISH
    }

    private BackPolicy() {}

    /**
     * Determines high-level action for a Back press when two WebViews are active.
     */
    static Action action(boolean fullscreen, PlayerHost.State playerState, boolean canGoBack) {
        if (fullscreen) return Action.EXIT_FULLSCREEN;
        if (playerState == PlayerHost.State.FULL) return Action.MINIMIZE_PLAYER;
        if (canGoBack) return Action.BROWSE_BACK;
        if (playerState == PlayerHost.State.MINI) return Action.MOVE_TASK_TO_BACK;
        return Action.FINISH;
    }

    /**
     * Returns the navigation action for a Back press inside the browse view.
     *
     * @param urls    the full back/forward list (index 0 = oldest)
     * @param current index of the page currently shown
     * @return {@link #DEFAULT}, {@link #HOME}, or a negative number of steps for
     *         {@code WebView.goBackOrForward(steps)}.
     */
    static int decide(List<String> urls, int current) {
        if (urls == null || current < 0 || current >= urls.size()) return DEFAULT;
        String currentUrl = urls.get(current);
        if (!isYouTubeHost(currentUrl)) return DEFAULT;
        if (!isPlayerPage(currentUrl)) return DEFAULT;

        // Walk backwards to find the first non-player page.
        for (int i = current - 1; i >= 0; i--) {
            String url = urls.get(i);
            if (!isPlayerPage(url)) {
                // Jump directly to that list page (steps is negative).
                return i - current;
            }
        }
        // No list page in history: load home.
        return HOME;
    }

    /**
     * True when the URL is a YouTube watch or Shorts player page.
     * Music.youtube.com player is an overlay on a list page, so it always returns false.
     */
    static boolean isPlayerPage(String url) {
        if (url == null) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            if (host == null) return false;
            // YouTube Music: treat as non-player so DEFAULT is returned.
            if (host.endsWith("music.youtube.com")) return false;
            String path = u.getPath();
            if (path == null) return false;
            return path.startsWith("/watch") || path.startsWith("/shorts/");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when the URL is specifically a YouTube watch page.
     */
    static boolean isWatch(String url) {
        if (url == null) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            if (host == null) return false;
            if (!(host.equals("youtube.com") || host.equals("m.youtube.com") || host.equals("www.youtube.com"))) {
                return false;
            }
            String path = u.getPath();
            return path != null && (path.equals("/watch") || path.startsWith("/watch"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isYouTubeHost(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URL(url).getHost();
            if (host == null) return false;
            return host.equals("youtube.com")
                    || host.equals("m.youtube.com")
                    || host.equals("www.youtube.com");
        } catch (Exception e) {
            return false;
        }
    }
}
