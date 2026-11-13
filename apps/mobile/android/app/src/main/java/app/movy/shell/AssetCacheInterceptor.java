package app.movy.shell;

import android.content.Context;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android face of {@link AssetCache}: answers WebView sub-resource requests from the disk cache (TV only for now). */
public final class AssetCacheInterceptor {
    private static final String DIR = "movy-assets";

    private final AssetCache cache;

    private AssetCacheInterceptor(AssetCache cache) {
        this.cache = cache;
    }

    /** Null unless the config enables the cache. Starts the index scan and expiry sweep on a background thread. */
    public static AssetCacheInterceptor create(Context ctx, ShellConfig cfg) {
        if (!cfg.cacheEnabled) return null;
        AssetCache c = new AssetCache(new File(ctx.getCacheDir(), DIR), new HttpFetcher(), System::currentTimeMillis,
                cfg.cacheMaxBytes, cfg.cacheTtlMs);
        c.scanAsync();
        return new AssetCacheInterceptor(c);
    }

    /** Null when the request is not cacheable or could not be satisfied (the WebView then loads it itself). */
    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request.isForMainFrame()) return null;
        String url = request.getUrl().toString();
        Map<String, String> headers = request.getRequestHeaders();
        AssetCache.Entry e = cache.serve(request.getMethod(), url, headers == null ? new LinkedHashMap<>() : headers);
        if (e == null) return null;
        try {
            Map<String, String> h = new LinkedHashMap<>(e.headers);
            h.remove("Content-Type");
            WebResourceResponse r = new WebResourceResponse(e.mime, null, 200, "OK", h, e.open());
            cache.sweepIfDue();
            return r;
        } catch (IOException ex) {
            return null;
        }
    }

    /** Bytes currently held (approximate until the startup scan has finished). */
    public long sizeBytes() {
        return cache.sizeBytes();
    }

    public int entryCount() {
        return cache.entryCount();
    }

    /** onTrimMemory / background: drops the in-memory index. */
    public void releaseMemory() {
        cache.releaseMemory();
    }

    /** Settings, Clear Cache. */
    public void clear() {
        cache.clear();
    }
}
