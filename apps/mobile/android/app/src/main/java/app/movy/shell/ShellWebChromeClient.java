package app.movy.shell;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

import java.io.ByteArrayInputStream;

import app.movy.adblock.AdblockEngine;
import app.movy.adblock.BlockStats;

/**
 * Extends Capacitor's chrome client (keeps its permission, geolocation, file chooser and dialog handling)
 * with fullscreen video and popup control.
 */
public class ShellWebChromeClient extends BridgeWebChromeClient {
    private static final long POPUP_TIMEOUT_MS = 3_000;

    private final Bridge bridgeRef;
    private final Activity activity;
    private final ShellConfig cfg;
    private final NavRules rules;
    private final BlockStats stats;
    private final LoadingUi loadingUi;
    private Bitmap transparentPoster;
    private final Handler main = new Handler(Looper.getMainLooper());

    private View customView;
    private CustomViewCallback customCallback;
    private FrameLayout customContainer;
    private java.util.function.Consumer<Boolean> fullscreenListener;

    /** Never ask "leave this page?": switching sources or apps must be effortless. */
    @Override
    public boolean onJsBeforeUnload(WebView view, String url, String message, android.webkit.JsResult result) {
        result.confirm();
        return true;
    }

    public ShellWebChromeClient(Bridge bridge, Activity activity, ShellConfig cfg, NavRules rules,
                                 BlockStats stats, LoadingUi loadingUi) {
        super(bridge);
        this.bridgeRef = bridge;
        this.loadingUi = loadingUi;
        this.activity = activity;
        this.cfg = cfg;
        this.rules = rules;
        this.stats = stats;
    }

    /** Replaces the grey default play-icon placeholder with nothing. */
    @Override
    public Bitmap getDefaultVideoPoster() {
        if (transparentPoster == null) transparentPoster = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        return transparentPoster;
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
        super.onProgressChanged(view, newProgress);
        loadingUi.onProgress(newProgress);
    }

    /** Called on the UI thread whenever web fullscreen starts or ends. */
    public void setFullscreenListener(java.util.function.Consumer<Boolean> l) {
        this.fullscreenListener = l;
    }

    public boolean isFullscreen() {
        return customView != null;
    }

    // ---- fullscreen video ----

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        Log.i("MovyShell", "fullscreen enter");
        customView = view;
        customCallback = callback;
        applyOrientation(true);
        if (cfg.debug) for (long d : new long[] {300, 1500, 4000}) main.postDelayed(() -> logState("t+" + d), d);
        Window w = activity.getWindow();
        customContainer = new FrameLayout(activity);
        customContainer.setBackgroundColor(0xFF000000);
        customContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((ViewGroup) w.getDecorView()).addView(customContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadingUi.setSuppressed(true);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        w.setAttributes(lp);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsetsCompat.Type.systemBars());
        if (fullscreenListener != null) fullscreenListener.accept(true);
    }

    @Override
    public void onHideCustomView() {
        if (customView == null) return;
        Log.i("MovyShell", "fullscreen exit");
        Window w = activity.getWindow();
        main.removeCallbacks(relayout);
        ((ViewGroup) w.getDecorView()).removeView(customContainer);
        customContainer.removeAllViews();
        customContainer = null;
        customView = null;
        loadingUi.setSuppressed(false);
        applyOrientation(false);
        w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        w.setAttributes(lp);
        if (cfg.tv) TvImmersive.apply(w);
        else WindowCompat.getInsetsController(w, w.getDecorView()).show(WindowInsetsCompat.Type.systemBars());
        ViewCompat.requestApplyInsets(activity.findViewById(android.R.id.content));
        CustomViewCallback cb = customCallback;
        customCallback = null;
        if (cb != null) cb.onCustomViewHidden();
        if (fullscreenListener != null) fullscreenListener.accept(false);
    }

    /** Phones: landscape while fullscreen, portrait after. Tablets: user setting. TV: manifest orientation. */
    private void applyOrientation(boolean fullscreen) {
        if (cfg.tv) return;
        boolean phone = OrientationPolicy.isPhone(activity.getResources().getConfiguration().smallestScreenWidthDp);
        activity.setRequestedOrientation(OrientationPolicy.forState(phone, fullscreen));
    }

    private void logState(String tag) {
        View v = customView;
        FrameLayout b = customContainer;
        View p = pageView();
        Log.i("MovyShell", "fs " + tag + " video=" + (v == null ? "null" : v.getClass().getSimpleName() + " " + v.getWidth() + "x" + v.getHeight()
                + " vis=" + v.getVisibility() + " att=" + v.isAttachedToWindow() + " children=" + (v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : -1))
                + " box=" + (b == null ? "null" : b.getWidth() + "x" + b.getHeight() + " parent=" + (b.getParent() == null ? "null" : b.getParent().getClass().getSimpleName()))
                + " page=" + (p == null ? "null" : p.getWidth() + "x" + p.getHeight() + " vis=" + p.getVisibility())
                + " cfg=" + activity.getResources().getConfiguration().screenWidthDp + "x" + activity.getResources().getConfiguration().screenHeightDp);
    }

    private View pageView() {
        return bridgeRef.getWebView();
    }

    /** Activity handled a configuration change (rotation): make the video view follow the new size. */
    public void onConfigurationChanged() {
        if (customView == null) return;
        main.removeCallbacks(relayout);
        relayout.run();
        main.postDelayed(relayout, 150);
        main.postDelayed(relayout, 500);
    }

    private final Runnable relayout = this::doRelayout;

    private void doRelayout() {
        FrameLayout box = customContainer;
        View video = customView;
        if (box == null || video == null) return;
        box.requestLayout();
        video.requestLayout();
        if (cfg.debug) logState("relayout");
        box.invalidate();
        if (FullscreenPolicy.needsReattach(video.getWidth(), video.getHeight(), box.getWidth(), box.getHeight())) {
            Log.i("MovyShell", "fullscreen: video view stale after rotation, re-attaching");
            box.removeView(video);
            box.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    // ---- popups ----

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        if (!isUserGesture) {
            // No tap behind it: nothing to read a target from, refuse right away (window.open returns null).
            stats.onPopupKilled();
            Log.i(KillLog.TAG, "popup killed (no gesture) total=" + stats.redirectsKilled());
            return false;
        }
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        WebView temp = new WebView(view.getContext());
        temp.getSettings().setJavaScriptEnabled(false);
        temp.setWebViewClient(new PopupClient(view, temp));
        transport.setWebView(temp);
        resultMsg.sendToTarget();
        main.postDelayed(temp::destroy, POPUP_TIMEOUT_MS); // popup that never navigates anywhere
        return true;
    }

    /** Throwaway client: captures the popup's target URL, never lets it hit the network or become visible. */
    private final class PopupClient extends WebViewClient {
        private final WebView opener;
        private final WebView temp;
        private boolean done;

        PopupClient(WebView opener, WebView temp) {
            this.opener = opener;
            this.temp = temp;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return new WebResourceResponse("text/plain", "utf-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            handle(r.getUrl());
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
            if (url != null) handle(Uri.parse(url));
        }

        private void handle(Uri target) {
            String scheme = target.getScheme();
            if (done || scheme == null || scheme.equals("about")) return;
            done = true;
            main.post(temp::destroy);
            String url = target.toString();
            boolean web = "http".equals(scheme) || "https".equals(scheme);
            boolean adHit = web && app.movy.adblock.AdblockSwitch.isOn()
                    && !AdblockEngine.check(url, opener.getUrl(), "document").isAllowed();
            if (rules.popupOpensInMain(url, true, adHit)) {
                Log.i(KillLog.TAG, "popup to allowed host opened in main view");
                opener.loadUrl(url);
                return;
            }
            stats.onPopupKilled();
            Log.i(KillLog.TAG, "popup killed " + KillLog.describe(url, cfg.debug) + " total=" + stats.redirectsKilled());
        }
    }
}
