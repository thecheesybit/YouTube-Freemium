package app.movy.shell;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Rational;
import android.view.OrientationEventListener;
import android.view.WindowManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;

import com.getcapacitor.BridgeActivity;

import app.movy.adblock.AdblockEngine;
import app.movy.adblock.AdblockSwitch;
import app.movy.adblock.AdblockWebViewClient;
import app.movy.adblock.BlockStats;
import app.movy.adblock.CosmeticBridge;

public class MainActivity extends BridgeActivity {
    public static final String ACTION_PIP_PLAY_PAUSE = "app.movy.shell.PIP_PLAY_PAUSE";
    private static final long EXIT_WINDOW_MS = 2000;

    private static final String DEBUG_TAG = "MovyDebug";

    private ShellConfig cfg;
    private NavRules rules;
    private BlockStats stats;
    private ShellWebChromeClient chrome;
    private long lastBackMs;
    private volatile long lastUserFsExitMs = -10_000;
    private String fallbackScript;
    private Prefs prefs;
    private SourceRepository repo;
    private LaunchController launch;
    private LoadingUi loadingUi;
    private boolean clearHistoryOnFinish;
    private CookiePersistence cookies;
    private SourceHealth health;
    private Failover failover;
    private IslandController island;
    private SettingsSheet settings;
    private TvController tvKeys;
    private TvHint tvHint;
    private TvIdle tvIdle;
    private TvLifecycle lifecycle;
    private app.movy.shell.AssetCacheInterceptor assetCache;
    /** Top-level URL of the page, readable from WebView binder threads (TvBridge). */
    private volatile String pageUrl = "";

    private boolean isMediaPlaying = false;
    private AdPill adPill;
    private MediaBridge mediaBridge;
    private PlayerHost player;
    private OrientationEventListener orientationListener;
    private boolean wasLandscape = false;

    private boolean isAnyFullscreen() {
        if (chrome != null && chrome.isFullscreen()) return true;
        if (player != null && player.chrome() != null && player.chrome().isFullscreen()) return true;
        return false;
    }

    private void hideAnyFullscreen() {
        if (chrome != null && chrome.isFullscreen()) {
            chrome.onHideCustomView();
        }
        if (player != null && player.chrome() != null && player.chrome().isFullscreen()) {
            player.chrome().onHideCustomView();
        }
    }

    private WebView mediaView() {
        return player != null && player.state() != PlayerHost.State.HIDDEN && player.webView() != null
                ? player.webView()
                : (bridge != null ? bridge.getWebView() : null);
    }

    private void shareUrl(String url, String title) {
        if (url == null || url.isEmpty()) return;
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("text/plain");
        sendIntent.putExtra(Intent.EXTRA_TEXT, url);
        if (title != null && !title.isEmpty()) {
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            sendIntent.putExtra(Intent.EXTRA_TITLE, title);
        }
        Intent chooser = Intent.createChooser(sendIntent, null);
        try {
            startActivity(chooser);
        } catch (Exception e) {
            Log.w("MainActivity", "Failed to start share chooser: " + e.getMessage());
        }
    }

    private void applyBarAppearance(int color) {
        boolean dark = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) < 128;
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(!dark);
        c.setAppearanceLightNavigationBars(!dark);
    }

    private void updateThemeColor(String colorStr) {
        if (cfg == null || cfg.tv || colorStr == null || colorStr.isEmpty()) return;
        try {
            int color;
            if (colorStr.startsWith("#")) {
                color = Color.parseColor(colorStr);
            } else if (colorStr.startsWith("rgb")) {
                String nums = colorStr.substring(colorStr.indexOf('(') + 1, colorStr.indexOf(')'));
                String[] parts = nums.split(",");
                if (parts.length > 3 && Float.parseFloat(parts[3].trim()) == 0f) return; // transparent: no page background yet
                int r = Integer.parseInt(parts[0].trim());
                int g = Integer.parseInt(parts[1].trim());
                int b = Integer.parseInt(parts[2].trim());
                color = Color.rgb(r, g, b);
            } else {
                return;
            }
            // Paint the bar itself, not just its icons: it kept the theme's grey in portrait. Under enforced
            // edge-to-edge (API 35+) setStatusBarColor is ignored and the content view's padding shows through.
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color); // applyBarAppearance also darkens the nav handle; keep it on a matching bar
            View content = findViewById(android.R.id.content);
            if (content != null) content.setBackgroundColor(color);
            applyBarAppearance(color);
        } catch (Exception ignored) {
        }
    }

    private boolean isAutoRotateEnabled() {
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** The in-player PiP button: leave native fullscreen if needed, then go straight to the PiP window. */
    private void enterPipFromButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasPipSupport()) return;
        if (isAnyFullscreen()) hideAnyFullscreen();
        getWindow().getDecorView().postDelayed(() -> {
            try {
                PictureInPictureParams.Builder b = buildPipParams();
                if (b != null) enterPictureInPictureMode(b.build());
            } catch (Exception e) {
                Log.w("MainActivity", "PiP button failed: " + e.getMessage());
            }
        }, 350);
    }
    private Object[] pendingMedia;
    private boolean isVideoMedia = false;
    private int mediaWidth = 16;
    private int mediaHeight = 9;
    private final Rect mediaRect = new Rect();
    private boolean notificationPermRequested = false;

    private PlaybackService playbackService;
    private boolean serviceBound = false;
    private boolean serviceRequested = false;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            playbackService = ((PlaybackService.LocalBinder) binder).getService();
            if (pendingMedia != null) {
                playbackService.updateState((Boolean) pendingMedia[0], (String) pendingMedia[1], (String) pendingMedia[2], (String) pendingMedia[3], (Long) pendingMedia[4], (Long) pendingMedia[5]);
            }
            serviceBound = true;
            playbackService.setActionCallback(new PlaybackService.ActionCallback() {
                @Override public void onPlay() { evalJs("window.__ytf_play&&window.__ytf_play()"); }
                @Override public void onPause() { evalJs("window.__ytf_pause&&window.__ytf_pause()"); }
                @Override public void onNext() { evalJs("window.__ytf_next&&window.__ytf_next()"); }
                @Override public void onPrev() { evalJs("window.__ytf_prev&&window.__ytf_prev()"); }
                @Override public void onSeekTo(long pos) { evalJs("window.__ytf_seekTo&&window.__ytf_seekTo(" + (pos / 1000.0) + ")"); }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            serviceBound = false;
            serviceRequested = false;
        }
    };

    private final BroadcastReceiver pipReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_PIP_PLAY_PAUSE.equals(intent.getAction())) {
                if (isMediaPlaying) {
                    evalJs("window.__ytf_pause&&window.__ytf_pause()");
                } else {
                    evalJs("window.__ytf_play&&window.__ytf_play()");
                }
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (prefs.backgroundPlayEnabled()) {
                    evalJs("window.__ytf_setBg&&window.__ytf_setBg(true)");
                    keepWebViewVisible();
                    WebView mv = mediaView();
                    if (mv != null) {
                        for (long delay : new long[]{300, 1000, 2500}) {
                            mv.postDelayed(() ->
                                    evalJs("window.__ytf_resumeIfWanted&&window.__ytf_resumeIfWanted()"), delay);
                        }
                    }
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                if (bridge != null && bridge.getWebView() != null) {
                    bridge.getWebView().onResume();
                    bridge.getWebView().resumeTimers();
                }
                if (player != null && player.webView() != null) {
                    player.webView().onResume();
                    player.webView().resumeTimers();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        cfg = ShellConfig.load(this);
        if (cfg.tv) TvLifecycle.trimColdStart(this); // before the WebView exists: stream segments never pile up
        applyRestOrientation();
        stats = new BlockStats(cfg.debug);
        prefs = new Prefs(this);
        AdblockSwitch.set(cfg.adblockEnabled && prefs.adblockEnabled());
        LiveConfig defaults = Defaults.load(this);
        LiveConfig initial = defaults;
        String lastGood = prefs.lastGoodRemote();
        if (lastGood != null) {
            try {
                initial = LiveConfig.merge(defaults, lastGood, UrlDecoder.IDENTITY);
            } catch (IllegalArgumentException e) {
                Log.i("MovyRemote", "cached remote config ignored: " + e.getMessage());
            }
        }
        repo = new SourceRepository(prefs, initial);
        if (cfg.tv && prefs.tvResumeId() != null) {
            repo.seedResume(prefs.tvResumeId(), prefs.tvResumeUrl());
            prefs.clearTvResume();
        }
        rules = new NavRules(initial.recommended().url, initial.allAllowedHosts(), cfg.authHosts, cfg.externalLinks);
        applyHosts(initial);
        repo.addListener(new SourceRepository.Listener() {
            @Override public void onConfigChanged(LiveConfig config) {
                applyHosts(config);
                reloadTvProfile();
            }
            @Override public void onActiveSourceChanged(Source active) {
                if (active == null || !"youtube".equals(active.id)) {
                    if (player != null) player.close();
                }
            }
        });
        RemoteApplier applier = new RemoteApplier(defaults, repo::config, repo::update, prefs::setLastGoodRemote,
                line -> Log.i("MovyRemote", line));
        if (cfg.adblockEnabled) AdblockEngine.load(this); // background thread; never blocks startup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, new IntentFilter(ACTION_PIP_PLAY_PAUSE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(pipReceiver, new IntentFilter(ACTION_PIP_PLAY_PAUSE));
        }
        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, screenFilter);
        super.onCreate(savedInstanceState);
        if (BuildConfig.MOVY_REMOTE) RemoteBackends.start(this, applier, BuildConfig.MOVY_REMOTE_PARAM, cfg.tv, cfg.debug, prefs.instanceId());
    }

    /** Phones are portrait outside fullscreen video; tablets follow the rotation setting; TV keeps its manifest orientation. */
    private void applyRestOrientation() {
        if (cfg == null || cfg.tv) return;
        boolean phone = OrientationPolicy.isPhone(getResources().getConfiguration().smallestScreenWidthDp);
        setRequestedOrientation(OrientationPolicy.forState(phone, false));
    }

    /** Navigation allowlist = union of all enabled sources' hosts + extraAllowedHosts (live). */
    private void applyHosts(LiveConfig c) {
        rules.update(c.allAllowedHosts(), c.startHosts(), c.authHosts);
        DohRescue.setHosts(c.dohHosts);
    }

    /** Source API for the Island (W5b). */
    public SourceRepository sources() {
        return repo;
    }

    public Prefs prefs() {
        return prefs;
    }

    public StorageManager storage() {
        return new StorageManager(this);
    }

    public ErrorPanel errorPanel() {
        return launch.errorPanel();
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        // Earliest point where the WebView exists but Capacitor has not started loading yet.
        WebView wv = findViewById(com.getcapacitor.android.R.id.webview);
        if (wv != null && cfg != null) fallbackScript = CosmeticBridge.install(this, wv, cfg.cosmetic, cfg.tv, rules, stats, cfg.debug);
    }

    @Override
    protected void load() {
        super.load();
        WebView wv = bridge.getWebView();
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        configureSettings(wv);
        if (cfg.debug) WebView.setWebContentsDebuggingEnabled(true);
        if (!cfg.tv) {
            player = new PlayerHost(this, bridge, cfg, rules, stats, new PlayerHost.Host() {
                @Override public void configureSettings(WebView pwv) {
                    MainActivity.this.configureSettings(pwv);
                }
                @Override public void onPlayerStateChanged(PlayerHost.State state) {
                    if (state == PlayerHost.State.HIDDEN) {
                        if (!isAnyFullscreen()) applyRestOrientation();
                    }
                }
                @Override public void onOpenBrowse(String url) {
                    if (player != null) player.minimize();
                    bridge.getWebView().loadUrl(url);
                }
                @Override public void onShare(String url, String title) {
                    shareUrl(url, title);
                }
                @Override public void onThemeColor(String color) {
                    updateThemeColor(color);
                }
                @Override public void onMediaState(boolean isPlaying, boolean isVideo, String title, String artist,
                                                  String artworkUrl, double duration, double position,
                                                  int width, int height, int left, int top, int right, int bottom) {
                    onMediaStateUpdated(isPlaying, isVideo, title, artist, artworkUrl, duration, position, width, height, left, top, right, bottom);
                }
                @Override public void onAdSkipping(boolean on) {
                    if (on && !isInPictureInPictureMode()) adPill.show(); else adPill.hide();
                }
                @Override public void onEnterPip() {
                    enterPipFromButton();
                }
            });
            orientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                    if (cfg == null || cfg.tv || !isAutoRotateEnabled()) return;
                    boolean phone = OrientationPolicy.isPhone(getResources().getConfiguration().smallestScreenWidthDp);
                    if (!phone) return;

                    boolean landscape = OrientationPolicy.isLandscape(orientation);
                    boolean portrait = OrientationPolicy.isPortrait(orientation);

                    if (landscape && !wasLandscape) {
                        wasLandscape = true;
                        if (player != null && player.state() == PlayerHost.State.FULL && isMediaPlaying && isVideoMedia && !isAnyFullscreen()) {
                            injectGestureKey(player.webView());
                            if (player.webView() != null) {
                                player.webView().evaluateJavascript("window.__ytf_requestFullscreen ? window.__ytf_requestFullscreen() : (function(){var b=document.querySelector('button.fullscreen-icon,.ytp-fullscreen-button,button[aria-label*=\"ull screen\"]');if(b)b.click();})()", null);
                            }
                        }
                    } else if (portrait && wasLandscape) {
                        wasLandscape = false;
                        if (isAnyFullscreen()) {
                            hideAnyFullscreen();
                        }
                    }
                }
            };
            if (orientationListener.canDetectOrientation()) {
                orientationListener.enable();
            }
        }
        mediaBridge = new MediaBridge("browse", (isPlaying, isVideo, title, artist, artworkUrl, duration, position, width, height, left, top, right, bottom) -> {
            if (player != null && player.state() != PlayerHost.State.HIDDEN) return;
            runOnUiThread(() -> onMediaStateUpdated(isPlaying, isVideo, title, artist, artworkUrl, duration, position, width, height, left, top, right, bottom));
        }, new MediaBridge.Host() {
            @Override public void openVideo(String url) {
                runOnUiThread(() -> {
                    if (player != null) player.open(url);
                });
            }
            @Override public void openBrowse(String url) {
                runOnUiThread(() -> wv.loadUrl(url));
            }
            @Override public void minimize() {
                runOnUiThread(() -> {
                    if (player != null) player.minimize();
                });
            }
            @Override public void share(String url, String title) {
                runOnUiThread(() -> shareUrl(url, title));
            }
            @Override public void themeColor(String color) {
                runOnUiThread(() -> updateThemeColor(color));
            }
        });
        adPill = new AdPill(this);
        mediaBridge.setAdListener(on -> runOnUiThread(() -> { if (on && !isInPictureInPictureMode()) adPill.show(); else adPill.hide(); }));
        mediaBridge.setPipListener(() -> runOnUiThread(this::enterPipFromButton));
        wv.addJavascriptInterface(mediaBridge, MediaBridge.NAME);
        // Capacitor installs a global java.net.CookieHandler that wraps the WebView's CookieManager (and drops session
        // cookies in its plugin load/destroy). The shell does not use it; it only gets in the way of HTTP clients such as Firebase.
        java.net.CookieHandler.setDefault(null);
        cookies = new CookiePersistence(this);
        cookies.restore(); // session cookies lost by a process kill, before the first load
        AdblockWebViewClient client = new AdblockWebViewClient(bridge, cfg, rules, stats);
        client.setFallbackScript(fallbackScript);
        assetCache = AssetCacheInterceptor.create(this, cfg);
        client.setAssetCache(assetCache);
        bridge.setWebViewClient(client);
        wv.setBackgroundColor(Color.parseColor(cfg.backgroundColor));
        loadingUi = new LoadingUi(this);
        chrome = new ShellWebChromeClient(bridge, this, cfg, rules, stats, loadingUi);
        wv.setWebChromeClient(chrome);
        // TV has no offline or download features: without a listener the WebView ignores download requests.
        if (!cfg.tv) wv.setDownloadListener(new DownloadHandler(this));
        setupSystemBars();
        launch = new LaunchController(this, repo, prefs, getString(R.string.app_name), BuildConfig.MOVY_REMOTE);
        health = new SourceHealth(repo);
        failover = new Failover(this, repo, health, launch, suggested -> island.onFailureShown(suggested));
        settings = new SettingsSheet(this, repo, prefs, stats, health, () -> bridge.getWebView(), cfg.adblockEnabled,
                getString(R.string.app_name), cfg.tv);
        island = new IslandController(this, repo, prefs, health, launch, cfg.tv, settings::show,
                () -> bridge.getWebView().requestFocus());
        chrome.setFullscreenListener(island::onFullscreen);
        if (player != null) player.chrome().setFullscreenListener(island::onFullscreen);
        if (cfg.tv) setupTv();
        loadingUi.setProgressListener(p -> {
            launch.onProgress(p);
            failover.onProgress(p);
        });
        client.setEvents(new PageEvents() {
            @Override public void onUrl(String url) {
                pageUrl = url;
                repo.rememberUrl(url);
                island.onUrl(url);
                if (!cfg.tv && BackPolicy.isWatch(url)) {
                    Source a = repo.active();
                    if (a == null || "youtube".equals(a.id)) {
                        if (player != null) player.open(url);
                        if (wv.canGoBack()) {
                            wv.goBack();
                        } else {
                            if (a != null) wv.loadUrl(a.url);
                        }
                    }
                }
                // Going to Shorts dismisses a docked video; a full-screen one was just opened (the page behind can land on a Short).
                if (url != null && url.contains("/shorts/") && player != null && player.state() == PlayerHost.State.MINI) {
                    player.close();
                }
            }
            @Override public void onPageStarted(String url) {
                if (url != null) pageUrl = url;
                failover.onLoadStarted();
            }
            @Override public void onPageCommitVisible(String url) { failover.onAlive(); }
            @Override public void onPageFinished(String url) {
                failover.onAlive();
                if (tvIdle != null) {
                    tvIdle.touch();
                    if (tvIdle.isIdle()) setPageIdle(true);
                }
                if (tvHint != null) Log.i(KillLog.TAG, "tvhint: finished home=" + island.isHomeNow() + " exp=" + island.isExpanded());
                if (tvHint != null && island.isHomeNow() && !island.isExpanded() && !launch.isChooserShowing()) tvHint.onHomeLoaded();
                StorageManager.flushCookies();
                snapshotCookies();
                if (clearHistoryOnFinish) {
                    clearHistoryOnFinish = false;
                    bridge.getWebView().clearHistory();
                }
            }
            @Override public void onMainFrameError() { failover.onFailure(); }
        });
        repo.setLoader((source, url) -> {
            StorageManager.flushCookies();
            clearHistoryOnFinish = true;
            loadingUi.begin();
            failover.onLoadStarted();
            bridge.getWebView().loadUrl(url);
        });
        if (cfg.debug) launch.setDebugHold(getIntent().getLongExtra("movy_hold_splash", 0));
        launch.start();
        handleDeepLink(getIntent());
        health.refreshNow(); // background; the dots fill in as results arrive
        handleDebugIntent(getIntent());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        });
    }

    /** Content sits below the system bars (status bar visible); TV is always immersive. */
    private void setupSystemBars() {
        View content = findViewById(android.R.id.content);
        int bg = Color.parseColor(cfg.backgroundColor);
        content.setBackgroundColor(bg);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            if (cfg.tv && insets.isVisible(WindowInsetsCompat.Type.systemBars())) v.post(this::applyImmersive);
            if (isAnyFullscreen()) {
                v.setPadding(0, 0, 0, 0);
            } else {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));            }
            return WindowInsetsCompat.CONSUMED;
        });
        applyBarAppearance(bg);
        if (cfg.tv) applyImmersive();
        ViewCompat.requestApplyInsets(content);
    }

    /** TV: no system bars, ever. Called on resume, focus, dialogs, IME close, fullscreen exit and whenever bars show up. */
    void applyImmersive() {
        if (cfg != null && cfg.tv) TvImmersive.apply(getWindow());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }


    @Override
    public void onResume() {
        super.onResume();
        evalJs("window.__ytf_setBg&&window.__ytf_setBg(false)");
        if (bridge != null && bridge.getWebView() != null) {
            bridge.getWebView().onResume();
            bridge.getWebView().resumeTimers();
        }
        if (player != null && player.webView() != null) {
            player.webView().onResume();
            player.webView().resumeTimers();
        }
        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }
        applyImmersive();
    }

    public app.movy.shell.AssetCacheInterceptor assetCache() {
        return assetCache;
    }

    private void reloadTvProfile() {
        if (!cfg.tv || bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.reloadProfile&&window.__movyTv.reloadProfile()", null);
    }

    private void setPageIdle(boolean idle) {
        if (bridge == null || bridge.getWebView() == null) return;
        bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.idle&&window.__movyTv.idle(" + idle + ")", null);
    }

    private void configureSettings(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        if (cfg.tv) {
            // TV renders the page at a wider layout width (profile viewportWidth, default 1280 CSS px) and scales it to fit.
            s.setUseWideViewPort(true);
            s.setLoadWithOverviewMode(true);
            wv.setVerticalScrollBarEnabled(false);
            wv.setHorizontalScrollBarEnabled(false);
        }
        s.setUserAgentString(cfg.userAgent != null ? cfg.userAgent : chromeLikeUserAgent(s.getUserAgentString()));
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setFocusable(true);
        wv.requestFocus();
    }

    /** Drops the "; wv" marker and the "Version/x.y" token so sites treat the view like Chrome. */
    static String chromeLikeUserAgent(String ua) {
        return ua.replace("; wv", "").replaceAll("\\s*Version/\\d+(\\.\\d+)*", "");
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!isAnyFullscreen()) applyRestOrientation();
        if (chrome != null) chrome.onConfigurationChanged();
        if (player != null && player.chrome() != null) player.chrome().onConfigurationChanged();
    }

    /** TV: the remote's keys are routed by {@link TvController} before the page sees them. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (cfg.tv && e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN
                && isAnyFullscreen() && !(tvIdle != null && tvIdle.isIdle())) noteUserLeftFullscreen();
        if (tvIdle != null && tvIdle.onKey(e)) return true; // first key after idle only wakes the screen
        if (tvKeys != null && tvKeys.dispatch(e)) return true;
        if (tvKeys != null && tvKeys.trapPanelKeys(e)) return true;
        if (island != null && island.handleKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private void setupTv() {
        ViewGroup content = findViewById(android.R.id.content);
        TvCursor cursor = new TvCursor(this, content, () -> bridge.getWebView());
        tvHint = new TvHint(this, prefs);
        tvKeys = new TvController(new TvController.Host() {
            @Override public boolean nativeOverlayActive() {
                return (settings != null && settings.isOpen()) || launch.isChooserShowing()
                        || launch.errorPanel().isShowing() || island.isExpanded();
            }
            @Override public boolean isFullscreen() { return isAnyFullscreen(); }
            @Override public void toggleIsland() {
                if (tvHint != null) tvHint.dismiss();
                if (settings != null && settings.isOpen()) return;
                island.menuToggle();
            }
            @Override public void pickSlot(int slot) {
                if (launch.isChooserShowing() || (settings != null && settings.isOpen())) return;
                island.pickSlot(slot);
            }
            @Override public void stepSource(int delta) {
                if (launch.isChooserShowing() || (settings != null && settings.isOpen())) return;
                String name = island.stepSource(delta);
                if (name != null) Toast.makeText(MainActivity.this, name, Toast.LENGTH_SHORT).show();
            }
            @Override public void media(String action, int seconds) {
                bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.media('" + action + "'," + seconds + ")", null);
            }
            @Override public boolean trapKeys(KeyEvent ev) {
                if (settings != null && settings.isOpen()) return settings.handleKey(ev);
                if (launch.isChooserShowing()) return TvFocusTrap.handle(ev, launch.chooserView());
                if (launch.errorPanel().isShowing()) return TvFocusTrap.handle(ev, launch.errorPanel());
                return false;
            }
            @Override public boolean deliver(KeyEvent ev) { return dispatchToPage(ev); }
        }, cursor, cfg.debug);
        bridge.getWebView().addJavascriptInterface(new TvBridge(repo::config, repo::active, () -> pageUrl,
                () -> runOnUiThread(() -> {
                    if (tvKeys == null || island.isExpanded() || launch.isChooserShowing()
                            || (settings != null && settings.isOpen()) || isAnyFullscreen()) return;
                    if (tvHint != null) tvHint.dismiss();
                    island.menuToggle();
                }),
                () -> runOnUiThread(this::injectGestureKey),
                json -> { if (cfg.debug) Log.i("MovyMedia", json); },
                () -> SystemClock.elapsedRealtime() - lastUserFsExitMs < 3000), TvBridge.NAME);
        lifecycle = new TvLifecycle(this, () -> bridge == null ? null : bridge.getWebView(), new TvLifecycle.Host() {
            @Override public void setBackgrounded(boolean bg) {
                if (bg) {
                    if (tvIdle != null) tvIdle.suspend();
                    if (tvHint != null) tvHint.dismiss();
                    if (tvKeys != null && tvKeys.cursor().isActive()) tvKeys.cursor().exit();
                } else if (tvIdle != null) {
                    tvIdle.resume();
                    applyImmersive();
                }
            }
            @Override public void saveResume() {
                Source a = repo.active();
                if (a != null) prefs.setTvResume(a.id, repo.lastUrl(a));
            }
            @Override public void flushCookies() {
                StorageManager.flushCookies();
                snapshotCookies();
            }
            @Override public void releaseRam() {
                if (assetCache != null) assetCache.releaseMemory();
            }
        });
        tvIdle = new TvIdle(new TvIdle.Host() {
            @Override public void setNativeIdle(boolean idle) {
                island.setIdle(idle);
                if (settings != null) settings.setAlpha(idle ? 0f : 1f);
                if (idle) {
                    if (tvHint != null) tvHint.dismiss();
                    if (tvKeys != null && tvKeys.cursor().isActive()) tvKeys.cursor().exit();
                }
            }
            @Override public void setPageIdle(boolean idle) { MainActivity.this.setPageIdle(idle); }
            @Override public boolean idleSuspended() {
                return launch.isChooserShowing() || launch.errorPanel().isShowing();
            }
        });
    }

    /** A real input event grants the page transient user activation (needed for requestFullscreen after a route change). */
    private void injectGestureKey(WebView wv) {
        if (wv == null) return;
        long t = SystemClock.uptimeMillis();
        wv.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1, 0));
        wv.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F1, 0));
    }

    private void injectGestureKey() {
        injectGestureKey(mediaView());
    }

    private boolean dispatchToPage(KeyEvent e) {
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onPause() {
        if (orientationListener != null) {
            orientationListener.disable();
        }
        StorageManager.flushCookies();
        snapshotCookies();
        if (!isInPictureInPictureMode() && prefs.backgroundPlayEnabled()) {
            evalJs("window.__ytf_setBg&&window.__ytf_setBg(true)");
        }
        super.onPause();
        if (bridge != null && bridge.getWebView() != null && (isMediaPlaying || prefs.backgroundPlayEnabled())) {
            bridge.getWebView().onResume();
            bridge.getWebView().resumeTimers();
        }
        if (player != null && player.webView() != null && (isMediaPlaying || prefs.backgroundPlayEnabled())) {
            player.webView().onResume();
            player.webView().resumeTimers();
        }
    }

    /**
     * Chromium hides the page (and pauses its video) when the window goes invisible: screen off, app
     * switch. Telling the view tree the window is still visible keeps audio/video running.
     */
    private void keepWebViewVisible() {
        if (!prefs.backgroundPlayEnabled()) return;
        final android.view.View decor = getWindow().getDecorView();
        for (long delay : new long[]{0, 250, 1000, 3000}) {
            decor.postDelayed(() -> {
                try {
                    decor.dispatchWindowVisibilityChanged(android.view.View.VISIBLE);
                } catch (Exception ignored) {
                }
            }, delay);
        }
    }

    @Override
    public void onStop() {
        StorageManager.flushCookies();
        snapshotCookies();
        super.onStop();
        if ((isMediaPlaying || prefs.backgroundPlayEnabled())) {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().onResume();
                bridge.getWebView().resumeTimers();
            }
            if (player != null && player.webView() != null) {
                player.webView().onResume();
                player.webView().resumeTimers();
            }
        }
        if (prefs.backgroundPlayEnabled()) {
            evalJs("window.__ytf_setBg&&window.__ytf_setBg(true)");
            keepWebViewVisible();
        }
        if (lifecycle != null) lifecycle.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        evalJs("window.__ytf_setBg&&window.__ytf_setBg(false)");
        if (lifecycle != null) lifecycle.onStart();
    }


    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (lifecycle != null) lifecycle.onTrimMemory(level);
    }

    @Override
    public void onDestroy() {
        if (orientationListener != null) {
            orientationListener.disable();
        }
        try { unregisterReceiver(pipReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        if (serviceBound) {
            unbindService(serviceConn);
            serviceBound = false;
            serviceRequested = false;
        }
        if (player != null) {
            player.close();
        }
        if (lifecycle != null) lifecycle.onDestroy();
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (prefs.pipEnabled() && isMediaPlaying && isVideoMedia && hasPipSupport()) {
                try {
                    PictureInPictureParams.Builder b = buildPipParams();
                    if (b != null) enterPictureInPictureMode(b.build());
                } catch (Exception e) {
                    Log.w("MainActivity", "Failed to enter PiP: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        // Keep the bridge's volatile flag in sync BEFORE the JS call so inPip() is accurate immediately.
        if (mediaBridge != null) mediaBridge.setPipActive(isInPictureInPictureMode);
        if (player != null) player.setPipActive(isInPictureInPictureMode);
        if (isInPictureInPictureMode) {
            evalJs("window.__ytf_setBg&&window.__ytf_setBg(false)");
            if (player != null && player.state() == PlayerHost.State.MINI) {
                player.expand();
            }
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().setVisibility(View.INVISIBLE);
            }
        } else {
            if (bridge != null && bridge.getWebView() != null) {
                bridge.getWebView().setVisibility(View.VISIBLE);
            }
        }
        if (isInPictureInPictureMode && adPill != null) adPill.hide();
        if (island != null) island.setPipMode(isInPictureInPictureMode);
        evalJs("window.__ytf_setPip&&window.__ytf_setPip(" + isInPictureInPictureMode + ")");
    }

    private void evalJs(String js) {
        runOnUiThread(() -> {
            WebView v = mediaView();
            if (v != null) {
                v.evaluateJavascript(js, null);
            }
        });
    }

    private boolean hasPipSupport() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private Rational computeAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return new Rational(16, 9);
        float r = (float) width / (float) height;
        if (r < 0.42f) return new Rational(42, 100);
        if (r > 2.38f) return new Rational(238, 100);
        return new Rational(width, height);
    }

    private PictureInPictureParams.Builder buildPipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        b.setAspectRatio(computeAspectRatio(mediaWidth, mediaHeight));
        if (mediaRect.width() > 0 && mediaRect.height() > 0) {
            b.setSourceRectHint(mediaRect);
        }
        ArrayList<RemoteAction> actions = new ArrayList<>();
        int iconRes = isMediaPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String actionTitle = isMediaPlaying ? "Pause" : "Play";
        Intent intent = new Intent(ACTION_PIP_PLAY_PAUSE).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 100, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        RemoteAction remoteAction = new RemoteAction(Icon.createWithResource(this, iconRes), actionTitle, actionTitle, pi);
        actions.add(remoteAction);
        b.setActions(actions);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            boolean canAutoEnter = prefs.pipEnabled() && isMediaPlaying && isVideoMedia && hasPipSupport();
            b.setAutoEnterEnabled(canAutoEnter);
        }
        return b;
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasPipSupport()) {
            try {
                PictureInPictureParams.Builder b = buildPipParams();
                if (b != null) setPictureInPictureParams(b.build());
            } catch (Exception e) {
                // Ignore if in invalid state
            }
        }
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermRequested) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notificationPermRequested = true;
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
    }

    private void onMediaStateUpdated(boolean isPlaying, boolean isVideo, String title, String artist,
                                     String artworkUrl, double duration, double position,
                                     int width, int height, int left, int top, int right, int bottom) {
        this.isMediaPlaying = isPlaying;
        this.isVideoMedia = isVideo;
        this.mediaWidth = width > 0 ? width : 16;
        this.mediaHeight = height > 0 ? height : 9;
        this.mediaRect.set(left, top, right, bottom);

        // Phase 3 item 1: Screen never sleeps during a video
        if (isPlaying && isVideo) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        updatePipParams();

        if ((isPlaying && prefs.backgroundPlayEnabled()) || serviceBound || serviceRequested) {
            if (isPlaying) checkNotificationPermission();
            Intent serviceIntent = new Intent(this, PlaybackService.class);
            if (!serviceBound && !serviceRequested) {
                serviceRequested = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
            }
            pendingMedia = new Object[]{isPlaying, title, artist, artworkUrl, (long) duration, (long) position};
            if (playbackService != null) {
                playbackService.updateState(isPlaying, title, artist, artworkUrl, (long) duration, (long) position);
            }
        }
    }

    private void handleDeepLink(Intent intent) {
        if (intent == null || intent.getData() == null || repo == null) return;
        Uri uri = intent.getData();
        String host = uri.getHost();
        if (host == null) return;
        String url = uri.toString();
        if (host.contains("music.youtube.com")) {
            repo.openUrl("youtube-music", url);
        } else if (host.contains("youtube.com") || host.contains("youtu.be")) {
            if (!cfg.tv && BackPolicy.isWatch(url)) {
                String behind = repo.active() != null && "youtube".equals(repo.active().id) ? repo.lastUrl(repo.active()) : null;
                // Reloading a Short behind the player would close it again (onUrl closes the player on /shorts/).
                if (behind == null || behind.contains("/shorts/")) behind = "https://m.youtube.com";
                repo.openUrl("youtube", behind);
                if (player != null) player.open(url);
            } else {
                repo.openUrl("youtube", url);
            }
        }
    }

    /** TV: leaving the app ends the process so nothing lingers in RAM; other flavors just finish. */
    /** BACK in fullscreen: the WebView itself leaves fullscreen, so the page is told it was the user (no re-entry). */
    private void noteUserLeftFullscreen() {
        lastUserFsExitMs = SystemClock.elapsedRealtime();
        bridge.getWebView().evaluateJavascript("window.__movyTv&&window.__movyTv.userExitFs&&window.__movyTv.userExitFs()", null);
    }

    private void leaveApp() {
        if (cfg.tv && lifecycle != null) lifecycle.exitApp();
        else finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
        handleDebugIntent(intent);
    }

    private void snapshotCookies() {
        if (cookies == null) return;
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (Source s : repo.config().enabledSources()) urls.add(s.url);
        if (bridge != null && bridge.getWebView() != null) urls.add(bridge.getWebView().getUrl());
        cookies.snapshot(urls);
    }

    /** Debug builds only: {@code --es movy_eval "<js>"} runs JS in the page and logs the result (tag MovyDebug); {@code --ez movy_force_chooser true} shows the chooser. */
    private void handleDebugIntent(Intent intent) {
        if (!cfg.debug || intent == null || bridge == null) return;
        if (intent.getBooleanExtra("movy_force_fail", false) && failover != null) {
            bridge.getWebView().postDelayed(failover::onFailure, 2500);
        }
        if (intent.getBooleanExtra("movy_force_chooser", false) && launch != null) {
            bridge.getWebView().postDelayed(launch::debugShowChooser, 1500);
        }
        String js = intent.getStringExtra("movy_eval");
        if (js == null) return;
        bridge.getWebView().postDelayed(() -> bridge.getWebView().evaluateJavascript(js,
                v -> Log.i(DEBUG_TAG, "eval result: " + v)), 800);
    }

    private void onBack() {
        WebView wv = bridge.getWebView();
        if (tvKeys != null && tvKeys.cursor().isActive()) {
            tvKeys.cursor().exit();
            return;
        }
        if (settings != null && settings.handleBack()) return;
        if (island != null && island.isExpanded()) {
            island.collapse();
            return;
        }
        if (launch.isChooserShowing()) {
            leaveApp();
            return;
        }

        if (isAnyFullscreen()) {
            lastUserFsExitMs = SystemClock.elapsedRealtime();
            if (cfg.tv) wv.evaluateJavascript("window.__movyTv&&window.__movyTv.userExitFs&&window.__movyTv.userExitFs()", null);
            hideAnyFullscreen();
            return;
        }

        if (cfg.tv) {
            backDefault(wv);
            return;
        }

        // YouTube Music: close full-screen player first if open
        Source active = repo.active();
        if (active != null && "youtube-music".equals(active.id)) {
            if (mediaBridge != null && mediaBridge.isMusicPlayerOpen()) {
                wv.evaluateJavascript("window.__ytf_collapseMusicPlayer&&window.__ytf_collapseMusicPlayer()", null);
                return;
            }
        }

        // Phone dual-WebView BackPolicy action
        PlayerHost.State pState = player != null ? player.state() : PlayerHost.State.HIDDEN;
        BackPolicy.Action action = BackPolicy.action(false, pState, wv.canGoBack());

        switch (action) {
            case MINIMIZE_PLAYER:
                if (player != null) player.minimize();
                break;
            case BROWSE_BACK:
                android.webkit.WebBackForwardList h = wv.copyBackForwardList();
                java.util.List<String> urls = new java.util.ArrayList<>();
                for (int i = 0; i < h.getSize(); i++) urls.add(h.getItemAtIndex(i).getUrl());
                int d = BackPolicy.decide(urls, h.getCurrentIndex());
                if (d < 0) {
                    wv.goBackOrForward(d);
                } else if (d == BackPolicy.HOME) {
                    if (active != null) repo.openUrl(active.id, active.url);
                    else finish();
                } else {
                    wv.goBack();
                }
                break;
            case MOVE_TASK_TO_BACK:
                moveTaskToBack(true);
                break;
            case FINISH:
            default:
                finish();
                break;
        }
    }

    private void backDefault(WebView wv) {
        if (wv.canGoBack()) {
            wv.goBack();
        } else if (cfg.tv) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastBackMs < EXIT_WINDOW_MS) {
                leaveApp();
            } else {
                lastBackMs = now;
                Toast.makeText(this, R.string.movy_press_back_again, Toast.LENGTH_SHORT).show();
            }
        } else {
            finish();
        }
    }
}
