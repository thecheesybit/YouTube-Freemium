package app.movy.shell;

/** Main-frame events from the WebView client, delivered on the UI thread. */
public interface PageEvents {
    /** Main-frame URL changed (including SPA route changes). */
    void onUrl(String url);

    void onPageFinished(String url);

    /** A main-frame load started (not called for SPA route changes). */
    default void onPageStarted(String url) { }

    /** The main frame painted something; the load is clearly alive. */
    default void onPageCommitVisible(String url) { }

    /** Network error or 5xx on the main frame. */
    void onMainFrameError();
}
