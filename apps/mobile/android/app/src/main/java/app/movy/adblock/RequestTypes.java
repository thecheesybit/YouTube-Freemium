package app.movy.adblock;

import android.webkit.WebResourceRequest;

import java.util.Locale;
import java.util.Map;

/** Infers adblock request types from WebView requests (WebView exposes no resource type). */
final class RequestTypes {
    private RequestTypes() {}

    private static final String[] SCRIPT = {"script"};
    private static final String[] STYLE = {"stylesheet"};
    private static final String[] IMAGE = {"image"};
    private static final String[] MEDIA = {"media"};
    private static final String[] FONT = {"font"};
    private static final String[] SUBDOC = {"subdocument"};
    private static final String[] XHR = {"xmlhttprequest"};
    private static final String[] OTHER = {"other"};
    /** No hints at all: a script, XHR or fetch. Checked in this order. */
    private static final String[] AMBIGUOUS = {"xmlhttprequest", "script"};

    /** Candidate adblock types for the request, most likely first. */
    static String[] candidates(WebResourceRequest req, String path) {
        Map<String, String> h = req.getRequestHeaders();
        if (h != null) {
            String dest = h.get("Sec-Fetch-Dest");
            if (dest != null) {
                String[] t = fromFetchDest(dest);
                if (t != null) return t;
            }
        }
        String[] byExt = fromExtension(path);
        if (byExt != null) return byExt;
        String accept = h == null ? null : h.get("Accept");
        if (accept != null) {
            if (accept.startsWith("text/css")) return STYLE;
            if (accept.startsWith("image/")) return IMAGE;
            if (accept.startsWith("text/html")) return req.isForMainFrame() ? OTHER : SUBDOC;
            if (accept.startsWith("video/") || accept.startsWith("audio/")) return MEDIA;
            if (accept.startsWith("application/json")) return XHR;
        }
        return AMBIGUOUS;
    }

    private static String[] fromFetchDest(String d) {
        switch (d) {
            case "script": case "worker": case "sharedworker": case "serviceworker": return SCRIPT;
            case "style": return STYLE;
            case "image": return IMAGE;
            case "video": case "audio": case "track": return MEDIA;
            case "font": return FONT;
            case "iframe": case "frame": return SUBDOC;
            case "empty": return XHR;
            default: return null;
        }
    }

    private static String[] fromExtension(String path) {
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || path.length() - dot > 6 || path.indexOf('/', dot) >= 0) return null;
        switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "js": case "mjs": return SCRIPT;
            case "css": return STYLE;
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "avif": case "svg": case "ico": case "bmp":
                return IMAGE;
            case "mp4": case "webm": case "m4v": case "mp3": case "ogg": case "m4a": case "wav": case "m3u8": case "ts": case "mpd":
                return MEDIA;
            case "woff": case "woff2": case "ttf": case "otf": case "eot": return FONT;
            case "json": return XHR;
            default: return null;
        }
    }

    /** MIME type for the empty response that replaces a blocked request. */
    static String emptyMime(String type) {
        switch (type) {
            case "script": return "application/javascript";
            case "stylesheet": return "text/css";
            case "image": return "image/gif";
            case "subdocument": case "document": return "text/html";
            case "media": return "video/mp4";
            case "font": return "font/woff2";
            default: return "text/plain";
        }
    }
}
