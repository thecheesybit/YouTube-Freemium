package app.movy.shell;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.io.File;
import java.util.function.Supplier;

/**
 * TV only: keeps the app lean. Background = media paused, timers stopped, RAM caches dropped; after 60 s the WebView is
 * destroyed and the process ends (the last page is reopened on return). BACK-exit ends the process. The WebView HTTP
 * cache (where Chromium may keep stream segments) is trimmed over 100 MB at cold start and after playback; cookies,
 * localStorage and IndexedDB are never touched.
 */
final class TvLifecycle {
    static final String TAG = "MovyLifecycle";

    interface Host {
        /** Stops/starts native timers and releases overlay bitmaps (idle timer, pointer cursor, hints). */
        void setBackgrounded(boolean backgrounded);

        /** Remembers the active source and its URL so the next launch reopens it. */
        void saveResume();

        void flushCookies();

        /** Drops the asset cache index and other native RAM caches. */
        void releaseRam();
    }

    private final Activity activity;
    private final Supplier<WebView> webView;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean background;
    private boolean destroyed;
    private final Runnable destroyLater = this::destroyWebViewAndExit;

    TvLifecycle(Activity activity, Supplier<WebView> webView, Host host) {
        this.activity = activity;
        this.webView = webView;
        this.host = host;
    }

    /** WebView HTTP cache directory of this app (the profile used by the system WebView in this process). */
    static File httpCacheDir(Context ctx) {
        return new File(ctx.getCacheDir(), "WebView/Default/HTTP Cache");
    }

    /** Cold start, before the WebView exists: delete the HTTP disk cache when it is over the limit. Never touches site data. */
    static boolean trimColdStart(Context ctx) {
        File dir = httpCacheDir(ctx);
        long size = sizeOf(dir);
        if (!LeanPolicy.shouldTrimHttpCache(size)) return false;
        deleteRecursive(dir);
        Log.i(TAG, "cold start: HTTP cache " + (size >> 20) + " MB over the limit, cleared");
        return true;
    }

    void onStop() {
        if (background || destroyed) return;
        background = true;
        WebView wv = webView.get();
        if (wv != null) {
            wv.evaluateJavascript("(function(){try{window.__movyTv&&window.__movyTv.media&&window.__movyTv.media('pause');"
                    + "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})}catch(e){}})()", null);
            wv.onPause();
            wv.pauseTimers();
            wv.clearCache(false); // RAM cache only
        }
        host.setBackgrounded(true);
        host.flushCookies();
        host.releaseRam();
        trimHttpCacheAfterPlayback();
        main.postDelayed(destroyLater, LeanPolicy.BACKGROUND_DESTROY_MS);
        Log.i(TAG, "background: media paused, timers stopped, RAM caches released");
    }

    void onStart() {
        main.removeCallbacks(destroyLater);
        if (!background || destroyed) return;
        background = false;
        WebView wv = webView.get();
        if (wv != null) {
            wv.resumeTimers();
            wv.onResume();
        }
        host.setBackgrounded(false);
        Log.i(TAG, "foreground again");
    }

    void onTrimMemory(int level) {
        switch (LeanPolicy.onTrimMemory(level)) {
            case BACKGROUND:
                WebView wv = webView.get();
                if (wv != null) wv.clearCache(false);
                host.releaseRam();
                break;
            case RELEASE_RAM:
                WebView w = webView.get();
                if (w != null) w.clearCache(false);
                host.releaseRam();
                Log.i(TAG, "trim memory level " + level + ": RAM caches dropped");
                break;
            default:
                break;
        }
    }

    /** Activity.onDestroy: nothing may keep running. */
    void onDestroy() {
        main.removeCallbacksAndMessages(null);
    }

    private void trimHttpCacheAfterPlayback() {
        final File dir = httpCacheDir(activity);
        new Thread(() -> {
            long size = sizeOf(dir);
            if (!LeanPolicy.shouldTrimHttpCache(size)) return;
            main.post(() -> {
                WebView wv = webView.get();
                if (wv != null && background) {
                    wv.clearCache(true); // disk cache only: cookies, localStorage and IndexedDB are not part of it
                    Log.i(TAG, "background: HTTP cache " + (size >> 20) + " MB over the limit, cleared");
                }
            });
        }, "movy-trim").start();
    }

    private void destroyWebViewAndExit() {
        if (!background || destroyed) return;
        destroyed = true;
        host.saveResume();
        WebView wv = webView.get();
        if (wv != null) {
            if (wv.getParent() instanceof ViewGroup) ((ViewGroup) wv.getParent()).removeView(wv);
            wv.destroy();
        }
        Log.i(TAG, "background > " + (LeanPolicy.BACKGROUND_DESTROY_MS / 1000) + " s: WebView destroyed, exiting");
        activity.finish();
        endProcessSoon();
    }

    /** BACK-exit: flush everything, drop the task, end the process so nothing stays in RAM. */
    void exitApp() {
        host.flushCookies();
        host.saveResume();
        Log.i(TAG, "exit: finishing task and ending the process");
        activity.finishAndRemoveTask();
        endProcessSoon();
    }

    private void endProcessSoon() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 350);
    }

    static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
