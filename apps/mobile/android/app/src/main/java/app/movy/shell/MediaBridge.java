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

    private final Listener listener;
    private java.util.function.Consumer<Boolean> adListener;

    public MediaBridge(Listener listener) {
        this.listener = listener;
    }

    public void setAdListener(java.util.function.Consumer<Boolean> l) {
        this.adListener = l;
    }

    private Runnable pipListener;

    public void setPipListener(Runnable r) {
        this.pipListener = r;
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
