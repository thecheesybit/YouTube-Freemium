package app.movy.adblock;

import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import app.movy.shell.KillLog;
import app.movy.shell.NavRules;
import app.movy.shell.PageEvents;
import app.movy.shell.ShellConfig;

/**
 * Capacitor's client plus request blocking and navigation policy. Capacitor behaviour is kept
 * by delegating to super for everything that is not blocked.
 */
public class AdblockWebViewClient extends BridgeWebViewClient {
    private static final Map<String, String> CORS = new HashMap<>();

    static {
        CORS.put("Access-Control-Allow-Origin", "*");
    }

    private final ShellConfig cfg;
    private final NavRules rules;
    private final BlockStats stats;
    /** Current top-level page URL, written from UI + IO threads. */
    private volatile String topUrl = "";
    /** Set when document-start scripts are unsupported. */
    private volatile String fallbackScript;
    private volatile PageEvents events;
    private volatile app.movy.shell.AssetCacheInterceptor assetCache;

    public AdblockWebViewClient(Bridge bridge, ShellConfig cfg, NavRules rules, BlockStats stats) {
        super(bridge);
        this.cfg = cfg;
        this.rules = rules;
        this.stats = stats;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (!request.isForMainFrame()) {
            WebResourceResponse rescued = app.movy.shell.DohRescue.intercept(request, cfg.debug);
            if (rescued != null) return rescued;
        }
        if (AdblockSwitch.isOn()) {
            Uri u = request.getUrl();
            if (request.isForMainFrame()) {
                topUrl = u.toString();
            } else {
                String scheme = u.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    WebResourceResponse r = filter(request, u);
                    if (r != null) return r;
                }
            }
        }
        app.movy.shell.AssetCacheInterceptor ac = assetCache;
        if (ac != null && !request.isForMainFrame()) {
            WebResourceResponse cached = ac.intercept(request);
            if (cached != null) return cached;
        }
        return super.shouldInterceptRequest(view, request);
    }

    public void setAssetCache(app.movy.shell.AssetCacheInterceptor cache) {
        this.assetCache = cache;
    }

    @Nullable
    private WebResourceResponse filter(WebResourceRequest request, Uri u) {
        String url = u.toString();
        String source = topUrl;
        stats.onChecked();
        for (String type : RequestTypes.candidates(request, u.getPath())) {
            BlockResult r = AdblockEngine.check(url, source, type);
            if (r.isAllowed()) continue;
            boolean redirect = r.kind == BlockResult.Kind.REDIRECT;
            stats.onBlocked(url, type, redirect);
            if (redirect) {
                return new WebResourceResponse(r.mime, "utf-8", 200, "OK", CORS, new ByteArrayInputStream(r.body));
            }
            return new WebResourceResponse(RequestTypes.emptyMime(type), "utf-8", 200, "OK", CORS,
                    new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        String url = u.toString();
        boolean main = request.isForMainFrame();
        boolean http = "http".equals(u.getScheme()) || "https".equals(u.getScheme());
        if (AdblockSwitch.isOn() && http && !AdblockEngine.check(url, topUrl, "document").isAllowed()) {
            if (main) {
                kill(url, "ad-host");
            } else {
                stats.onBlocked(url, "document", false);
            }
            return true;
        }
        if (!main) {
            if (rules.subframeAllowed(url)) return false;
            kill(url, "frame-scheme");
            return true;
        }
        if (rules.mainFrame(url, request.isRedirect(), request.hasGesture(), view.getUrl()) == NavRules.Verdict.ALLOW) {
            return false;
        }
        kill(url, request.isRedirect() ? "redirect" : "off-site");
        return true;
    }

    private void kill(String url, String why) {
        stats.onNavigationKilled();
        android.util.Log.i(KillLog.TAG, "navigation killed (" + why + ") " + KillLog.describe(url, cfg.debug)
                + " total=" + stats.redirectsKilled());
    }

    public void setEvents(PageEvents events) {
        this.events = events;
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        super.doUpdateVisitedHistory(view, url, isReload);
        PageEvents e = events;
        if (e != null && url != null && url.startsWith("http")) e.onUrl(url);
    }

    @Override
    public void onReceivedError(WebView view, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
        super.onReceivedError(view, request, error);
        if (!request.isForMainFrame() || events == null) return;
        switch (error.getErrorCode()) {
            case ERROR_HOST_LOOKUP: case ERROR_CONNECT: case ERROR_IO: case ERROR_TIMEOUT:
            case ERROR_FAILED_SSL_HANDSHAKE: case ERROR_PROXY_AUTHENTICATION:
                events.onMainFrameError();
                break;
            default:
                break;
        }
    }

    public void setFallbackScript(String script) {
        this.fallbackScript = script;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (url != null) topUrl = url;
        String fb = fallbackScript;
        if (fb != null) view.evaluateJavascript(fb, null);
        super.onPageStarted(view, url, favicon);
        if (events != null) events.onPageStarted(url);
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        super.onPageCommitVisible(view, url);
        if (events != null) events.onPageCommitVisible(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        if (AdblockSwitch.isOn()) stats.logSummary(url);
        super.onPageFinished(view, url);
        if (events != null) events.onPageFinished(url);
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        // Intentionally not delegating to Capacitor. Only a 5xx on the main frame counts as "not responding".
        if (request.isForMainFrame() && errorResponse != null && errorResponse.getStatusCode() >= 500 && events != null) {
            events.onMainFrameError();
        }
    }
}
