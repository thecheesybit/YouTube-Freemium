package app.movy.adblock;

import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.json.JSONArray;

import app.movy.shell.KillLog;
import app.movy.shell.NavRules;
import java.util.Set;

/**
 * JS interface "MovyAdblock" + document-start injection of assets/adblock/cosmetic.js into all frames.
 * Methods run on a WebView binder thread, so blocking briefly for the engine is fine.
 */
public final class CosmeticBridge {
    public static final String NAME = "MovyAdblock";
    private static final long ENGINE_WAIT_MS = 2500;
    private static final String TAG = "MovyAdblock";

    private final NavRules rules;
    private final BlockStats stats;
    private final boolean debug;

    private CosmeticBridge(NavRules rules, BlockStats stats, boolean debug) {
        this.rules = rules;
        this.stats = stats;
        this.debug = debug;
    }

    /** Hosts navguard.js treats as on-site: {"allow":[...],"auth":[...]}. */
    @JavascriptInterface
    public String navConfig() {
        try {
            return new JSONObject().put("allow", new JSONArray(rules.allowedPatterns()))
                    .put("auth", new JSONArray(rules.authPatterns())).toString();
        } catch (org.json.JSONException e) {
            return "{\"allow\":[],\"auth\":[]}";
        }
    }

    /** navguard.js reports a script-level kill ("popup" or "navigation"). */
    @JavascriptInterface
    public void killed(String kind, String target) {
        if ("popup".equals(kind)) stats.onPopupKilled(); else stats.onNavigationKilled();
        Log.i(KillLog.TAG, "js " + kind + " killed " + KillLog.describe(target, debug) + " total=" + stats.redirectsKilled());
    }

    @JavascriptInterface
    public String cosmetic(String url) {
        if (url == null || !AdblockSwitch.isOn()) return "{}";
        AdblockEngine.awaitReady(ENGINE_WAIT_MS);
        return AdblockEngine.cosmeticResources(url);
    }

    @JavascriptInterface
    public String hidden(String classesJson, String idsJson, String exceptionsJson) {
        if (!AdblockSwitch.isOn() || !AdblockEngine.isReady()) return "[]";
        return AdblockEngine.hiddenSelectors(
                AdblockEngine.jsonStrings(classesJson), AdblockEngine.jsonStrings(idsJson),
                AdblockEngine.jsonStrings(exceptionsJson));
    }

    /**
     * Installs the interface and script. Returns the script when document-start scripts are unsupported
     * so the caller can fall back to evaluating it from onPageStarted; null when injected natively.
     */
    public static String install(Context ctx, WebView webView, boolean cosmetic, boolean tv, NavRules rules,
                                 BlockStats stats, boolean debug) {
        String script;
        String loading;
        String navguard;
        String tvnav = null;
        String scam;
        String youtube = null;
        try {
            JSONObject opts = new JSONObject().put("cosmetic", cosmetic).put("tv", tv);
            script = "(function(){var MOVY_OPTS=" + opts + ";" + readAsset(ctx, "adblock/cosmetic.js") + "\n})();";
            loading = readAsset(ctx, "shell/loading.js");
            navguard = "(function(){" + readAsset(ctx, "shell/navguard.js") + "\n})();";
            scam = "(function(){" + readAsset(ctx, "shell/scamguard.js") + "\n})();";
            try {
                youtube = readAsset(ctx, "shell/youtube.js");
            } catch (Exception ignored) {}
            if (tv) tvnav = readAsset(ctx, "shell/tvnav-pure.js") + "\n" + readAsset(ctx, "shell/tvnav.js") + "\n" + readAsset(ctx, "shell/tvfs.js"); // each file is its own IIFE
        } catch (Exception e) {
            Log.e(TAG, "cannot prepare cosmetic script", e);
            return null;
        }
        webView.addJavascriptInterface(new CosmeticBridge(rules, stats, debug), NAME);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> all = Collections.singleton("*");
            WebViewCompat.addDocumentStartJavaScript(webView, script, all);
            WebViewCompat.addDocumentStartJavaScript(webView, loading, all);
            WebViewCompat.addDocumentStartJavaScript(webView, navguard, all);
            WebViewCompat.addDocumentStartJavaScript(webView, scam, all);
            if (youtube != null) WebViewCompat.addDocumentStartJavaScript(webView, youtube, all);
            if (tvnav != null) WebViewCompat.addDocumentStartJavaScript(webView, tvnav, all);
            return null;
        }
        Log.w(TAG, "DOCUMENT_START_SCRIPT unsupported: falling back to onPageStarted injection");
        return script + "\n" + loading + (youtube != null ? "\n" + youtube : "");
    }

    private static String readAsset(Context ctx, String path) throws IOException {
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
