package app.movy.shell;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;

import java.io.File;

/**
 * Storage helpers for the Settings screen (W5b). Nothing here is ever called automatically:
 * only an explicit, confirmed user action may clear anything.
 */
public final class StorageManager {
    private final Context ctx;

    public StorageManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Flushes cookies to disk. Call on pause/stop, page finished and source switch. */
    public static void flushCookies() {
        CookieManager.getInstance().flush();
    }

    /** Bytes used by the app's data and cache directories (WebView profile included). */
    public long usage() {
        long total = size(ctx.getFilesDir().getParentFile());
        File ext = ctx.getExternalCacheDir();
        return total + (ext != null ? size(ext) : 0);
    }

    /** Bytes in cache only (HTTP cache, code cache). */
    public long cacheUsage() {
        return size(ctx.getCacheDir()) + size(new File(ctx.getFilesDir().getParentFile(), "app_webview/Default/HTTP Cache"));
    }

    /** "Clear cache only": HTTP cache. Keeps cookies, logins and site storage. Main thread. */
    public void clearCacheOnly(WebView webView) {
        webView.clearCache(true);
        deleteContents(ctx.getCacheDir());
    }

    /** "Sign out of everything": cookies, DOM storage, IndexedDB, caches. Main thread; confirm first. */
    public void clearAllSiteData(WebView webView) {
        CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> cm.flush());
        WebStorage.getInstance().deleteAllData();
        new CookiePersistence(ctx).clear();
        webView.clearCache(true);
        webView.clearFormData();
        deleteContents(ctx.getCacheDir());
    }

    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += size(k);
        return total;
    }

    private static void deleteContents(File dir) {
        File[] kids = dir == null ? null : dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) deleteContents(k);
            //noinspection ResultOfMethodCallIgnored
            k.delete();
        }
    }
}
