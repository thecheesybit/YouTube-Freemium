package app.movy.shell;

import android.webkit.JavascriptInterface;

public class MediaBridge {
    public static final String NAME = "YouTubeFreemiumBridge";

    public interface Listener {
        void onMediaState(boolean isPlaying, boolean isVideo, String title, String artist,
                          String artworkUrl, double duration, double position,
                          int videoWidth, int videoHeight,
                          int left, int top, int right, int bottom);
    }

    public interface Host {
        void openVideo(String url);
        void openBrowse(String url);
        void minimize();
        void share(String url, String title);
        void themeColor(String color);
    }

    private final String role;
    private final Listener listener;
    private Host host;
    private java.util.function.Consumer<Boolean> adListener;
    private Runnable pipListener;
    /** Set by MainActivity in onPictureInPictureModeChanged; read by the JS bridge on a binder thread. */
    private volatile boolean pipActive = false;
    private volatile boolean musicPlayerOpen = false;

    public MediaBridge(String role, Listener listener, Host host) {
        this.role = role != null ? role : "browse";
        this.listener = listener;
        this.host = host;
    }

    public MediaBridge(Listener listener) {
        this("browse", listener, null);
    }

    public void setHost(Host host) {
        this.host = host;
    }

    public void setAdListener(java.util.function.Consumer<Boolean> l) {
        this.adListener = l;
    }

    public void setPipListener(Runnable r) {
        this.pipListener = r;
    }

    /** Called by MainActivity on the UI thread to keep track of the PiP state. */
    public void setPipActive(boolean active) {
        this.pipActive = active;
    }

    /** JS bridge: returns true while the app is in the PiP window. Safe to call off the UI thread. */
    @JavascriptInterface
    public boolean inPip() {
        return pipActive;
    }

    @JavascriptInterface
    public void musicPlayerState(boolean open) {
        this.musicPlayerOpen = open;
    }

    public boolean isMusicPlayerOpen() {
        return musicPlayerOpen;
    }

    @JavascriptInterface
    public String role() {
        return role;
    }

    @JavascriptInterface
    public void openVideo(String url) {
        if (host != null) host.openVideo(url);
    }

    @JavascriptInterface
    public void openBrowse(String url) {
        if (host != null) host.openBrowse(url);
    }

    @JavascriptInterface
    public void minimize() {
        if (host != null) host.minimize();
    }

    @JavascriptInterface
    public void share(String url, String title) {
        if (host != null) host.share(url, title);
    }

    @JavascriptInterface
    public void themeColor(String color) {
        if (host != null) host.themeColor(color);
    }

    @JavascriptInterface
    public void enterPip() {
        if (pipListener != null) pipListener.run();
    }

    @JavascriptInterface
    public void adSkipping(boolean on) {
        if (adListener != null) adListener.accept(on);
    }

    @JavascriptInterface
    public void log(String msg) {
        android.util.Log.d("YTF", msg);
    }

    @JavascriptInterface
    public void reportMediaState(boolean isPlaying, boolean isVideo, String title, String artist,
                                 String artworkUrl, double duration, double position,
                                 int videoWidth, int videoHeight,
                                 int left, int top, int right, int bottom) {
        if (listener != null) {
            listener.onMediaState(isPlaying, isVideo, title, artist, artworkUrl, duration, position,
                    videoWidth, videoHeight, left, top, right, bottom);
        }
    }
}
