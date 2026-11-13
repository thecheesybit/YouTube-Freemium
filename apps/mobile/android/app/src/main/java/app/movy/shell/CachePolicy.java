package app.movy.shell;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules of the TV asset cache: what may be cached, when an entry expires and when it is revalidated.
 * Cached: images, fonts and versioned static JS/CSS (200 responses only). Never cached: documents, API JSON, media
 * segments, ranged requests, authenticated or signed requests, no-store/private responses.
 */
final class CachePolicy {
    enum Kind { IMAGE, FONT, STATIC, NONE }

    /** YouTube Freemium caches only CSS/JS and fonts: never images, audio or video. */
    static boolean CACHE_IMAGES = false; // not final so image-path unit tests can opt in

    static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final long REVALIDATE_MS = DAY_MS;
    static final long MAX_ENTRY_BYTES = 12L * 1024 * 1024;

    private static final Pattern EXT = Pattern.compile("\\.([a-z0-9]{2,5})$");
    private static final Pattern HASHED = Pattern.compile("[.\\-_][A-Za-z0-9_-]{8,}\\.(js|mjs|css)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSIONED_QUERY = Pattern.compile("(^|&)(v|ver|version|hash|h)=[^&]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIGNED_QUERY = Pattern.compile("(^|&)(token|sig|signature|expires|x-amz-[a-z-]+|policy|key-pair-id)=", Pattern.CASE_INSENSITIVE);
    private static final Pattern YT_STATIC = Pattern.compile("^https?://[^/]*(youtube\\.com|ytimg\\.com|gstatic\\.com)/s/");
    private static final String IMAGE_EXT = "png jpg jpeg gif webp avif svg ico bmp";
    private static final String FONT_EXT = "woff woff2 ttf otf eot";
    private static final String MEDIA_EXT = "m3u8 ts m4s mp4 webm mkv mp3 aac m4a mpd flv key vtt srt";

    private CachePolicy() {}

    /**
     * Request-side decision from the URL and request headers (Accept, Range, Authorization).
     * Header names are matched case-insensitively.
     */
    static Kind classifyRequest(String method, String url, Map<String, String> headers) {
        if (!"GET".equalsIgnoreCase(method) || url == null) return Kind.NONE;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return Kind.NONE;
        if (header(headers, "range") != null || header(headers, "authorization") != null) return Kind.NONE;
        String path = lower;
        String query = "";
        int q = lower.indexOf('?');
        if (q >= 0) {
            path = lower.substring(0, q);
            query = url.substring(q + 1);
        }
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        if (SIGNED_QUERY.matcher(query).find()) return Kind.NONE;
        String ext = extension(path);
        if (ext != null && contains(MEDIA_EXT, ext)) return Kind.NONE;
        if (ext != null && contains(IMAGE_EXT, ext)) return CACHE_IMAGES ? Kind.IMAGE : Kind.NONE;
        if (ext != null && contains(FONT_EXT, ext)) return Kind.FONT;
        String accept = header(headers, "accept");
        if (accept != null && accept.toLowerCase(Locale.ROOT).startsWith("image/")) return CACHE_IMAGES ? Kind.IMAGE : Kind.NONE;
        if (("js".equals(ext) || "mjs".equals(ext) || "css".equals(ext))
                && (YT_STATIC.matcher(lower).find() || HASHED.matcher(path).find() || VERSIONED_QUERY.matcher(query).find())) return Kind.STATIC;
        return Kind.NONE;
    }

    /** Response-side decision: status, headers and body size. */
    static boolean cacheableResponse(Kind kind, int status, Map<String, String> h, long size) {
        if (kind == Kind.NONE || status != 200) return false;
        if (size <= 0 || size > MAX_ENTRY_BYTES) return false;
        String cc = lower(header(h, "cache-control"));
        if (cc.contains("no-store") || cc.contains("private")) return false;
        if (lower(header(h, "vary")).trim().equals("*")) return false;
        String ct = lower(header(h, "content-type"));
        if (ct.startsWith("text/html") || ct.contains("json") || ct.startsWith("video/") || ct.startsWith("audio/")
                || ct.contains("mpegurl") || ct.contains("event-stream")) return false;
        if (kind == Kind.IMAGE && !(ct.startsWith("image/") || ct.isEmpty() || ct.contains("octet-stream"))) return false;
        if (kind == Kind.STATIC && !(ct.contains("javascript") || ct.contains("css") || ct.contains("ecmascript"))) return false;
        if (kind == Kind.FONT && !(ct.startsWith("font/") || ct.contains("font") || ct.contains("octet-stream"))) return false;
        if (header(h, "set-cookie") != null) {
            // CDN challenge cookies ride on immutable static files: cache the body, never re-emit the cookie.
            return kind == Kind.STATIC && (cc.contains("immutable") || maxAge(cc) >= DAY_MS / 1000);
        }
        return true;
    }

    /** An entry is deleted ttlMs after it was first stored, whatever the server says. */
    static boolean isExpired(long storedAt, long now, long ttlMs) {
        return storedAt <= 0 || now - storedAt >= ttlMs || storedAt - now > DAY_MS; // a clock far in the past counts as corrupt
    }

    /** Served from disk at once; revalidated in the background at most once per 24 h. */
    static boolean needsRevalidate(long validatedAt, long now) {
        return now - validatedAt >= REVALIDATE_MS || validatedAt - now > DAY_MS;
    }

    /** Outcome of a conditional request for an existing entry. */
    enum Revalidation { KEEP_AND_REFRESH, REPLACE, EVICT, IGNORE }

    static Revalidation onRevalidated(int status) {
        if (status == 304) return Revalidation.KEEP_AND_REFRESH;
        if (status == 200) return Revalidation.REPLACE;
        if (status == 404 || status == 410) return Revalidation.EVICT;
        return Revalidation.IGNORE; // 5xx and friends: keep what we have, try again later
    }

    static long maxAge(String cacheControlLower) {
        int i = cacheControlLower.indexOf("max-age=");
        if (i < 0) return 0;
        int j = i + 8;
        int k = j;
        while (k < cacheControlLower.length() && Character.isDigit(cacheControlLower.charAt(k))) k++;
        try {
            return Long.parseLong(cacheControlLower.substring(j, k));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        Matcher m = EXT.matcher(file);
        return m.find() ? m.group(1) : null;
    }

    private static boolean contains(String list, String word) {
        return (" " + list + " ").contains(" " + word + " ");
    }

    static String header(Map<String, String> h, String name) {
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
