package app.movy.shell;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.getcapacitor.Bridge;

import app.movy.adblock.AdblockWebViewClient;
import app.movy.adblock.BlockStats;
import app.movy.adblock.CosmeticBridge;

/**
 * Manages the dedicated Player WebView and its container (Full screen or Docked Mini Player).
 * Used when the active source is YouTube.
 */
final class PlayerHost {
    public enum State { HIDDEN, FULL, MINI }

    public interface Host {
        void configureSettings(WebView wv);
        void onPlayerStateChanged(State state);
        void onOpenBrowse(String url);
        void onShare(String url, String title);
        void onThemeColor(String color);
        void onMediaState(boolean isPlaying, boolean isVideo, String title, String artist,
                          String artworkUrl, double duration, double position,
                          int width, int height, int left, int top, int right, int bottom);
        void onAdSkipping(boolean on);
        void onEnterPip();
    }

    private final Activity activity;
    private final Bridge bridge;
    private final ShellConfig cfg;
    private final NavRules rules;
    private final BlockStats stats;
    private final Host host;

    private State state = State.HIDDEN;
    private FrameLayout container;
    private WebView playerWebView;
    private ShellWebChromeClient chrome;
    private MediaBridge mediaBridge;
    private boolean pipActive = false;

    // Mini player bar views
    private FrameLayout miniBar;
    private LinearLayout textLayout;
    private TextView titleView;
    private TextView channelView;
    private ImageButton playPauseButton;
    private ImageButton closeButton;
    private View miniTouchOverlay;
    private View topSeparator;

    private boolean isPlaying = false;
    private int bottomNavHeightPx;

    PlayerHost(Activity activity, Bridge bridge, ShellConfig cfg, NavRules rules, BlockStats stats, Host host) {
        this.activity = activity;
        this.bridge = bridge;
        this.cfg = cfg;
        this.rules = rules;
        this.stats = stats;
        this.host = host;

        float density = activity.getResources().getDisplayMetrics().density;
        this.bottomNavHeightPx = Math.round(48 * density); // ytm-pivot-bar-renderer height

        buildContainer();
    }

    private void buildContainer() {
        float density = activity.getResources().getDisplayMetrics().density;

        container = new FrameLayout(activity);
        container.setVisibility(View.GONE);
        container.setBackgroundColor(UiTokens.BLACK);

        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root != null) {
            root.addView(container, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Mini bar container for controls (sits alongside the WebView in MINI mode)
        miniBar = new FrameLayout(activity);
        miniBar.setVisibility(View.GONE);

        // Top hairline separator
        topSeparator = new View(activity);
        topSeparator.setBackgroundColor(UiTokens.SEPARATOR);
        FrameLayout.LayoutParams sepLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(density)));
        sepLp.gravity = Gravity.TOP;
        miniBar.addView(topSeparator, sepLp);

        // Info layout (Title + Channel)
        textLayout = new LinearLayout(activity);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int startMargin = Math.round(114 * density) + Math.round(12 * density);
        int endMargin = Math.round(88 * density);
        textLp.setMargins(startMargin, 0, endMargin, 0);
        miniBar.addView(textLayout, textLp);

        titleView = new TextView(activity);
        titleView.setTextColor(UiTokens.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textLayout.addView(titleView);

        channelView = new TextView(activity);
        channelView.setTextColor(UiTokens.LABEL_SECONDARY);
        channelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        channelView.setSingleLine(true);
        channelView.setEllipsize(TextUtils.TruncateAt.END);
        textLayout.addView(channelView);

        // Touch overlay to expand when tapping anywhere on the bar (except buttons)
        miniTouchOverlay = new View(activity);
        miniTouchOverlay.setOnClickListener(v -> expand());
        FrameLayout.LayoutParams touchLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        miniBar.addView(miniTouchOverlay, touchLp);

        // Play/Pause button
        playPauseButton = new ImageButton(activity);
        playPauseButton.setBackgroundColor(Color.TRANSPARENT);
        playPauseButton.setImageResource(R.drawable.ic_play);
        playPauseButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int btnPad = Math.round(8 * density);
        playPauseButton.setPadding(btnPad, btnPad, btnPad, btnPad);
        FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(
                Math.round(40 * density), Math.round(40 * density));
        playLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        playLp.setMargins(0, 0, Math.round(44 * density), 0);
        playPauseButton.setOnClickListener(v -> {
            if (playerWebView != null) {
                playerWebView.evaluateJavascript(
                        isPlaying ? "window.__ytf_pause&&window.__ytf_pause()" : "window.__ytf_play&&window.__ytf_play()",
                        null
                );
            }
        });
        miniBar.addView(playPauseButton, playLp);

        // Close button
        closeButton = new ImageButton(activity);
        closeButton.setBackgroundColor(Color.TRANSPARENT);
        closeButton.setImageResource(R.drawable.ic_close);
        closeButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        closeButton.setPadding(btnPad, btnPad, btnPad, btnPad);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                Math.round(40 * density), Math.round(40 * density));
        closeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        closeLp.setMargins(0, 0, Math.round(4 * density), 0);
        closeButton.setOnClickListener(v -> close());
        miniBar.addView(closeButton, closeLp);

        container.addView(miniBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void ensureWebView() {
        if (playerWebView != null) return;

        playerWebView = new WebView(activity);
        host.configureSettings(playerWebView);
        if (cfg.debug) WebView.setWebContentsDebuggingEnabled(true);
        playerWebView.setBackgroundColor(UiTokens.BLACK);
        playerWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CosmeticBridge.install(activity, playerWebView, cfg.cosmetic, cfg.tv, rules, stats, cfg.debug);

        AdblockWebViewClient client = new AdblockWebViewClient(bridge, cfg, rules, stats);
        playerWebView.setWebViewClient(client);

        chrome = new ShellWebChromeClient(bridge, activity, cfg, rules, stats, null);
        playerWebView.setWebChromeClient(chrome);

        mediaBridge = new MediaBridge("player", (isPlay, isVid, title, artist, art, dur, pos, w, h, l, t, r, b) -> {
            activity.runOnUiThread(() -> {
                updateMediaState(isPlay, title, artist);
                host.onMediaState(isPlay, isVid, title, artist, art, dur, pos, w, h, l, t, r, b);
            });
        }, new MediaBridge.Host() {
            @Override public void openVideo(String url) {
                activity.runOnUiThread(() -> open(url));
            }
            @Override public void openBrowse(String url) {
                activity.runOnUiThread(() -> host.onOpenBrowse(url));
            }
            @Override public void minimize() {
                activity.runOnUiThread(PlayerHost.this::minimize);
            }
            @Override public void share(String url, String title) {
                activity.runOnUiThread(() -> host.onShare(url, title));
            }
            @Override public void themeColor(String color) {
                activity.runOnUiThread(() -> host.onThemeColor(color));
            }
        });
        mediaBridge.setPipActive(pipActive);
        mediaBridge.setAdListener(on -> activity.runOnUiThread(() -> host.onAdSkipping(on)));
        mediaBridge.setPipListener(() -> activity.runOnUiThread(host::onEnterPip));

        playerWebView.addJavascriptInterface(mediaBridge, MediaBridge.NAME);
        if (!cfg.tv) playerWebView.setDownloadListener(new DownloadHandler(activity));

        // Insert playerWebView at index 0 in container (behind mini controls)
        container.addView(playerWebView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void open(String watchUrl) {
        ensureWebView();

        state = State.FULL;
        container.setVisibility(View.VISIBLE);
        applyFullLayout();
        playerWebView.evaluateJavascript("window.__ytf_setMini&&window.__ytf_setMini(false)", null);
        playerWebView.loadUrl(watchUrl);
        host.onPlayerStateChanged(state);
    }

    public void minimize() {
        if (state != State.FULL || playerWebView == null) return;

        // Tell player page to isolate only the video element before resizing
        playerWebView.evaluateJavascript("window.__ytf_setMini&&window.__ytf_setMini(true)", null);

        ViewGroup parent = (ViewGroup) container.getParent();
        if (parent != null) TransitionManager.beginDelayedTransition(parent);

        state = State.MINI;
        applyMiniLayout();
        host.onPlayerStateChanged(state);
    }

    public void expand() {
        if (state != State.MINI || playerWebView == null) return;

        ViewGroup parent = (ViewGroup) container.getParent();
        if (parent != null) TransitionManager.beginDelayedTransition(parent);

        state = State.FULL;
        applyFullLayout();

        // Restore normal page layout
        playerWebView.evaluateJavascript("window.__ytf_setMini&&window.__ytf_setMini(false)", null);
        host.onPlayerStateChanged(state);
    }

    public void close() {
        if (playerWebView != null) {
            playerWebView.loadUrl("about:blank");
        }
        isPlaying = false;
        container.setVisibility(View.GONE);
        state = State.HIDDEN;
        host.onPlayerStateChanged(state);
    }

    private void applyFullLayout() {
        float density = activity.getResources().getDisplayMetrics().density;
        container.setBackgroundColor(UiTokens.BLACK);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        container.setLayoutParams(cLp);

        if (playerWebView != null) {
            FrameLayout.LayoutParams wvLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            playerWebView.setLayoutParams(wvLp);
        }

        miniBar.setVisibility(View.GONE);
    }

    private void applyMiniLayout() {
        float density = activity.getResources().getDisplayMetrics().density;
        container.setBackgroundColor(UiTokens.SURFACE);

        int barHeight = Math.round(64 * density);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight, Gravity.BOTTOM);
        cLp.bottomMargin = bottomNavHeightPx;
        container.setLayoutParams(cLp);

        if (playerWebView != null) {
            int wvWidth = Math.round(114 * density);
            FrameLayout.LayoutParams wvLp = new FrameLayout.LayoutParams(
                    wvWidth, barHeight, Gravity.START | Gravity.CENTER_VERTICAL);
            playerWebView.setLayoutParams(wvLp);
        }

        miniBar.setVisibility(View.VISIBLE);
    }

    public void updateMediaState(boolean isPlay, String title, String channel) {
        this.isPlaying = isPlay;
        if (titleView != null && title != null) titleView.setText(title);
        if (channelView != null && channel != null) channelView.setText(channel);
        if (playPauseButton != null) {
            playPauseButton.setImageResource(isPlay ? R.drawable.ic_pause : R.drawable.ic_play);
        }
    }

    public void setPipActive(boolean active) {
        this.pipActive = active;
        if (mediaBridge != null) mediaBridge.setPipActive(active);
    }

    public State state() {
        return state;
    }

    public WebView webView() {
        return playerWebView;
    }

    public ShellWebChromeClient chrome() {
        return chrome;
    }

    public MediaBridge mediaBridge() {
        return mediaBridge;
    }
}

