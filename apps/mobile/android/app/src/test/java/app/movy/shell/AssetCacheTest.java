package app.movy.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AssetCacheTest {
    @org.junit.Before public void enableImagesForLegacyTests() { CachePolicy.CACHE_IMAGES = true; }
    @org.junit.After public void restoreImageDefault() { CachePolicy.CACHE_IMAGES = false; }

    private static final long DAY = CachePolicy.DAY_MS;
    private static final String IMG = "https://cdn.example.com/p/a.jpg";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private final long[] now = {1_000_000L};
    private FakeFetcher net;
    private File dir;

    @Before public void setUp() throws IOException {
        net = new FakeFetcher();
        dir = tmp.newFolder("assets");
    }

    private AssetCache cache(long maxBytes) {
        return new AssetCache(dir, net, () -> now[0], maxBytes, 30 * DAY);
    }

    private static Map<String, String> req() {
        Map<String, String> m = new HashMap<>();
        m.put("User-Agent", "test");
        return m;
    }

    private static byte[] read(AssetCache.Entry e) throws IOException {
        try (InputStream in = e.open()) {
            return in.readAllBytes();
        }
    }

    @Test public void missFetchesAndStoresThenHitIsServedFromDisk() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        AssetCache.Entry first = c.serve("GET", IMG, req());
        assertNotNull(first);
        assertEquals("image/jpeg", first.mime);
        assertArrayEquals("AAAA".getBytes(), read(first));
        assertEquals(1, net.calls.size());
        AssetCache.Entry second = c.serve("GET", IMG, req());
        assertArrayEquals("AAAA".getBytes(), read(second));
        assertEquals("a hit does not touch the network", 1, net.calls.size());
        assertEquals(4, c.sizeBytes());
    }

    @Test public void nonCacheableRequestsAreNotHandled() {
        AssetCache c = cache(10_000_000);
        assertNull(c.serve("POST", IMG, req()));
        assertNull(c.serve("GET", "https://a.example.com/api/list.json", req()));
        assertEquals(0, net.calls.size());
    }

    @Test public void noStoreResponseIsServedOnceAndNotKept() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        net.headers.put("Cache-Control", "no-store");
        AssetCache.Entry e = c.serve("GET", IMG, req());
        assertNotNull(e);
        assertTrue(e.deleteOnClose);
        read(e);
        assertFalse("one-shot file removed after reading", e.body.exists());
        assertEquals(0, c.sizeBytes());
        c.serve("GET", IMG, req());
        assertEquals("fetched again", 2, net.calls.size());
    }

    @Test public void failedOrMissingResourcesFallBackToTheWebView() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 404, "", "text/plain", null);
        assertNull(c.serve("GET", IMG, req()));
        net.fail = true;
        assertNull(c.serve("GET", "https://cdn.example.com/p/b.jpg", req()));
    }

    @Test public void setCookieIsNeverReEmitted() throws IOException {
        AssetCache c = cache(10_000_000);
        String js = "https://a.example.com/assets/index-XsMk3Q9a.js";
        net.put(js, 200, "var a=1;", "application/javascript", null);
        net.headers.put("Set-Cookie", "cf=1");
        net.headers.put("Cache-Control", "public, max-age=31536000, immutable");
        AssetCache.Entry e = c.serve("GET", js, req());
        assertNotNull(e);
        assertNull(CachePolicy.header(e.headers, "set-cookie"));
        assertNull(CachePolicy.header(c.serve("GET", js, req()).headers, "set-cookie"));
    }

    @Test public void corsHeadersSurviveAndAreAddedForCorsRequests() throws IOException {
        AssetCache c = cache(10_000_000);
        String font = "https://cdn.example.com/f/inter.woff2";
        net.put(font, 200, "FONT", "font/woff2", null);
        net.headers.put("Access-Control-Allow-Origin", "https://a.example.com");
        AssetCache.Entry e = c.serve("GET", font, req());
        assertEquals("https://a.example.com", CachePolicy.header(e.headers, "access-control-allow-origin"));
        // an image first stored by a no-CORS request, later requested in CORS mode
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        c.serve("GET", IMG, req());
        Map<String, String> cors = req();
        cors.put("Origin", "https://a.example.com");
        assertEquals("https://a.example.com", CachePolicy.header(c.serve("GET", IMG, cors).headers, "access-control-allow-origin"));
    }

    @Test public void expiredEntryIsDeletedAndFetchedFresh() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "OLD", "image/png", null);
        c.serve("GET", IMG, req());
        now[0] += 30 * DAY;
        net.put(IMG, 200, "NEW!", "image/png", null);
        assertArrayEquals("NEW!".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals(2, net.calls.size());
    }

    @Test public void sweepRemovesExpiredEntries() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "OLD", "image/png", null);
        c.serve("GET", IMG, req());
        assertEquals(1, c.entryCount());
        now[0] += 31 * DAY;
        c.sweep();
        assertEquals(0, c.entryCount());
        assertEquals(0, dir.list().length);
    }

    @Test public void revalidation304KeepsTheEntryAndRefreshesTheClock() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        c.serve("GET", IMG, req());
        now[0] += 2 * DAY;
        net.status = 304;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals("\"v1\"", net.calls.get(net.calls.size() - 1).ifNoneMatch);
        net.calls.clear();
        assertArrayEquals("AAAA".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals("fresh again, no new revalidation", 0, net.calls.size());
    }

    @Test public void revalidation200ReplacesTheBodyKeepingTheFirstStoreTime() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        c.serve("GET", IMG, req());
        now[0] += 2 * DAY;
        net.put(IMG, 200, "BBBBBB", "image/jpeg", "\"v2\"");
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertArrayEquals("BBBBBB".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals(6, c.sizeBytes());
        now[0] += 29 * DAY; // 31 days after the first store
        net.put(IMG, 200, "CC", "image/jpeg", null);
        assertArrayEquals("CC".getBytes(), read(c.serve("GET", IMG, req())));
    }

    @Test public void revalidation404EvictsTheEntry() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        c.serve("GET", IMG, req());
        now[0] += 2 * DAY;
        net.status = 404;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals(0, c.entryCount());
    }

    @Test public void revalidationServerErrorKeepsTheEntry() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", "\"v1\"");
        c.serve("GET", IMG, req());
        now[0] += 2 * DAY;
        net.status = 503;
        c.revalidate(CachePolicy.Kind.IMAGE, AssetCache.keyOf(IMG), IMG, req());
        assertEquals(1, c.entryCount());
        assertArrayEquals("AAAA".getBytes(), read(c.serve("GET", IMG, req())));
    }

    @Test public void lruEvictsTheLeastRecentlyUsedAboveTheCap() {
        AssetCache c = cache(10);
        String a = "https://cdn.example.com/a.jpg", b = "https://cdn.example.com/b.jpg", d = "https://cdn.example.com/d.jpg";
        net.put(a, 200, "AAAA", "image/jpeg", null);
        net.put(b, 200, "BBBB", "image/jpeg", null);
        net.put(d, 200, "DDDD", "image/jpeg", null);
        c.serve("GET", a, req());
        c.serve("GET", b, req());
        c.serve("GET", a, req()); // a is now more recent than b
        c.serve("GET", d, req()); // 12 bytes > 10: evicts b
        assertEquals(2, c.entryCount());
        assertEquals(8, c.sizeBytes());
        net.calls.clear();
        c.serve("GET", a, req());
        assertEquals("a survived", 0, net.calls.size());
        c.serve("GET", b, req());
        assertEquals("b was evicted", 1, net.calls.size());
    }

    @Test public void corruptEntryIsDroppedAndRefetched() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        c.serve("GET", IMG, req());
        File body = new File(dir, AssetCache.keyOf(IMG) + ".b");
        java.nio.file.Files.write(body.toPath(), new byte[] {1, 2});
        net.calls.clear();
        assertArrayEquals("AAAA".getBytes(), read(c.serve("GET", IMG, req())));
        assertEquals(1, net.calls.size());
    }

    @Test public void scanRebuildsTheIndexAndCleansLeftovers() throws IOException {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        c.serve("GET", IMG, req());
        new File(dir, "deadbeef.b").createNewFile(); // orphan body
        new File(dir, "cafe.t123").createNewFile(); // half-written temp file
        AssetCache again = cache(10_000_000);
        again.scan();
        assertEquals(1, again.entryCount());
        assertEquals(4, again.sizeBytes());
        assertFalse(new File(dir, "deadbeef.b").exists());
        assertFalse(new File(dir, "cafe.t123").exists());
    }

    @Test public void clearRemovesEverything() {
        AssetCache c = cache(10_000_000);
        net.put(IMG, 200, "AAAA", "image/jpeg", null);
        c.serve("GET", IMG, req());
        c.clear();
        assertEquals(0, c.sizeBytes());
        assertEquals(0, dir.list().length);
    }

    // ---------- fake network ----------

    static final class Call {
        final String url;
        final String ifNoneMatch;

        Call(String url, String ifNoneMatch) {
            this.url = url;
            this.ifNoneMatch = ifNoneMatch;
        }
    }

    static final class FakeFetcher implements Fetcher {
        final List<Call> calls = new ArrayList<>();
        final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String[]> bodies = new HashMap<>();
        int status = 200;
        boolean fail;

        void put(String url, int st, String body, String type, String etag) {
            bodies.put(url, new String[] {String.valueOf(st), body, type, etag});
            headers.clear();
            status = 200;
        }

        @Override
        public Result fetch(String url, Map<String, String> req, String inm, String ims) throws IOException {
            calls.add(new Call(url, inm));
            if (fail) throw new IOException("offline");
            String[] b = bodies.get(url);
            int st = inm != null ? status : Integer.parseInt(b[0]);
            if (inm != null && status == 200) st = Integer.parseInt(b[0]);
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put("Content-Type", b[2]);
            if (b[3] != null) h.put("ETag", b[3]);
            InputStream body = st == 200 ? new ByteArrayInputStream(b[1].getBytes()) : null;
            return new Result(st, Collections.unmodifiableMap(h), body, null);
        }
    }
}
