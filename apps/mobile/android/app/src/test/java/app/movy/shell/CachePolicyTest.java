package app.movy.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CachePolicyTest {
    @org.junit.Before public void enableImagesForLegacyTests() { CachePolicy.CACHE_IMAGES = true; }
    @org.junit.After public void restoreImageDefault() { CachePolicy.CACHE_IMAGES = false; }

    private static final Map<String, String> NONE = Collections.emptyMap();
    private static final long DAY = CachePolicy.DAY_MS;

    private static Map<String, String> h(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static CachePolicy.Kind classify(String url) {
        return CachePolicy.classifyRequest("GET", url, NONE);
    }

    @Test public void imagesAndFontsAreCacheableByExtension() {
        assertEquals(CachePolicy.Kind.IMAGE, classify("https://cdn.example.com/t/p/w342/abc.jpg"));
        assertEquals(CachePolicy.Kind.IMAGE, classify("https://cdn.example.com/x/poster.WEBP?w=300"));
        assertEquals(CachePolicy.Kind.FONT, classify("https://cdn.example.com/f/inter.woff2"));
    }

    @Test public void imagesAreNotCachedByDefault() {
        CachePolicy.CACHE_IMAGES = false;
        assertEquals(CachePolicy.Kind.NONE, classify("https://cdn.example.com/t/p/w342/abc.jpg"));
        assertEquals(CachePolicy.Kind.FONT, classify("https://cdn.example.com/f/inter.woff2"));
        assertEquals(CachePolicy.Kind.STATIC, classify("https://www.youtube.com/s/player/a5c2aafe/player-plasma-es6-en_GB.vflset/base.js"));
    }

    @Test public void imageAcceptHeaderCoversProxiedImagesWithoutExtension() {
        assertEquals(CachePolicy.Kind.IMAGE, CachePolicy.classifyRequest("GET", "https://img.example.com/proxy?u=1&w=500",
                h("Accept", "image/avif,image/webp,*/*;q=0.8")));
        assertEquals(CachePolicy.Kind.NONE, CachePolicy.classifyRequest("GET", "https://img.example.com/api/list", h("Accept", "*/*")));
    }

    @Test public void onlyVersionedScriptsAndStylesAreStatic() {
        assertEquals(CachePolicy.Kind.STATIC, classify("https://a.example.com/assets/index-XsMk3Q9a.css"));
        assertEquals(CachePolicy.Kind.STATIC, classify("https://a.example.com/_next/static/chunks/main.3f9c2b1a77.js"));
        assertEquals(CachePolicy.Kind.STATIC, classify("https://a.example.com/app.js?v=20240101"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/app.js"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/style.css"));
    }

    @Test public void neverCachedKindsOfRequest() {
        assertEquals(CachePolicy.Kind.NONE, CachePolicy.classifyRequest("POST", "https://a.example.com/x.png", NONE));
        assertEquals(CachePolicy.Kind.NONE, CachePolicy.classifyRequest("GET", "https://a.example.com/x.png", h("Range", "bytes=0-")));
        assertEquals(CachePolicy.Kind.NONE, CachePolicy.classifyRequest("GET", "https://a.example.com/x.png", h("authorization", "Bearer 1")));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/seg/1.ts"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/master.m3u8"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/v/clip.mp4"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/api/titles.json"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/page"));
        assertEquals(CachePolicy.Kind.NONE, classify("https://a.example.com/x.png?token=abc"));
        assertEquals(CachePolicy.Kind.NONE, classify("data:image/png;base64,AAAA"));
        assertEquals(CachePolicy.Kind.NONE, classify("blob:https://a.example.com/uuid"));
    }

    @Test public void responseRules() {
        Map<String, String> img = h("Content-Type", "image/jpeg", "Cache-Control", "public, max-age=31536000");
        assertTrue(CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, img, 1000));
        assertFalse("200 only", CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 206, img, 1000));
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 404, img, 1000));
        assertFalse("empty body", CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, img, 0));
        assertFalse("too large", CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, img, CachePolicy.MAX_ENTRY_BYTES + 1));
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.NONE, 200, img, 1000));
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, h("Content-Type", "image/png", "Cache-Control", "no-store"), 10));
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, h("Content-Type", "image/png", "cache-control", "private, max-age=60"), 10));
        assertFalse("api json", CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, h("Content-Type", "application/json", "Cache-Control", "max-age=600"), 10));
        assertFalse("html", CachePolicy.cacheableResponse(CachePolicy.Kind.STATIC, 200, h("Content-Type", "text/html"), 10));
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, h("Content-Type", "image/png", "Vary", "*"), 10));
    }

    @Test public void setCookieOnlyAllowedOnImmutableStaticFiles() {
        Map<String, String> js = h("Content-Type", "application/javascript", "Set-Cookie", "cf=1", "Cache-Control", "public, max-age=31536000, immutable");
        assertTrue(CachePolicy.cacheableResponse(CachePolicy.Kind.STATIC, 200, js, 100));
        Map<String, String> shortJs = h("Content-Type", "text/css", "Set-Cookie", "cf=1", "Cache-Control", "max-age=60");
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.STATIC, 200, shortJs, 100));
        Map<String, String> img = h("Content-Type", "image/png", "Set-Cookie", "s=1", "Cache-Control", "max-age=31536000");
        assertFalse(CachePolicy.cacheableResponse(CachePolicy.Kind.IMAGE, 200, img, 100));
    }

    @Test public void expiresThirtyDaysAfterFirstStoreWhateverTheServerSays() {
        long ttl = 30 * DAY;
        assertFalse(CachePolicy.isExpired(1000, 1000 + 29 * DAY, ttl));
        assertTrue(CachePolicy.isExpired(1000, 1000 + 30 * DAY, ttl));
        assertTrue("bad stamp", CachePolicy.isExpired(0, 5, ttl));
        assertTrue("stored in the far future = clock went back", CachePolicy.isExpired(10 * DAY, 1000, ttl));
    }

    @Test public void revalidatesAtMostOncePerDay() {
        assertFalse(CachePolicy.needsRevalidate(1000, 1000 + DAY - 1));
        assertTrue(CachePolicy.needsRevalidate(1000, 1000 + DAY));
        assertTrue(CachePolicy.needsRevalidate(10 * DAY, 1000));
    }

    @Test public void revalidationOutcomes() {
        assertEquals(CachePolicy.Revalidation.KEEP_AND_REFRESH, CachePolicy.onRevalidated(304));
        assertEquals(CachePolicy.Revalidation.REPLACE, CachePolicy.onRevalidated(200));
        assertEquals(CachePolicy.Revalidation.EVICT, CachePolicy.onRevalidated(404));
        assertEquals(CachePolicy.Revalidation.EVICT, CachePolicy.onRevalidated(410));
        assertEquals(CachePolicy.Revalidation.IGNORE, CachePolicy.onRevalidated(503));
    }
}
