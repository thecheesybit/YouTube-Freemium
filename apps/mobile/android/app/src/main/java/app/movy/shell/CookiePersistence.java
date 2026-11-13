package app.movy.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps session cookies (no expiry) across a process kill. Verified on this device: Chrome WebView writes only
 * persistent cookies to disk, so a force-stop drops session logins. We snapshot {@code getCookie(url)} per
 * origin on pause/stop and, at startup, re-add the pairs that are missing before the first load.
 * Restored cookies are host-only session cookies (attributes are not recoverable from getCookie).
 * Private app storage only; cleared together with "clear all site data".
 */
final class CookiePersistence {
    private static final String FILE = "movy_cookie_snapshot";

    private final SharedPreferences sp;

    CookiePersistence(Context ctx) {
        this.sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Stores the current cookies of each url's origin; an empty result removes that origin's entry. */
    void snapshot(Collection<String> urls) {
        CookieManager cm = CookieManager.getInstance();
        SharedPreferences.Editor e = sp.edit();
        for (String url : urls) {
            String origin = origin(url);
            if (origin == null) continue;
            String cookies = cm.getCookie(origin);
            if (cookies == null || cookies.isEmpty()) e.remove(origin); else e.putString(origin, cookies);
        }
        e.apply();
    }

    /** Re-adds snapshotted cookies that the WebView no longer has. Call before the first load. */
    void restore() {
        CookieManager cm = CookieManager.getInstance();
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String origin = entry.getKey();
            boolean secure = origin.startsWith("https:");
            for (String pair : missing(cm.getCookie(origin), (String) entry.getValue())) {
                cm.setCookie(origin, pair + "; path=/" + (secure ? "; Secure" : ""));
            }
        }
        cm.flush();
    }

    void clear() {
        sp.edit().clear().apply();
    }

    /** Scheme + host of a web url ("https://host"), or null. */
    static String origin(String url) {
        UrlParts p = UrlParts.parse(url);
        return p.isWeb() && !p.host.isEmpty() ? p.scheme + "://" + p.host : null;
    }

    /** "name=value" pairs of {@code stored} whose name is absent from {@code current}. */
    static List<String> missing(String current, String stored) {
        Map<String, String> have = parse(current);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : parse(stored).entrySet()) {
            if (!have.containsKey(e.getKey())) out.add(e.getKey() + "=" + e.getValue());
        }
        return out;
    }

    private static Map<String, String> parse(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        if (header == null) return out;
        for (String part : header.split(";")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            out.put(t.substring(0, eq), t.substring(eq + 1));
        }
        return out;
    }
}
