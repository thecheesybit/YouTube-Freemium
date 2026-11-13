package app.movy.shell;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Disk cache for images, fonts and versioned static files (TV). Entries are served instantly from disk, revalidated in
 * the background at most once per 24 h, deleted 30 days after they were first stored, and evicted LRU above the size
 * cap. Writes are atomic (temp file, then rename); a corrupt entry is dropped. Rules live in {@link CachePolicy}.
 */
final class AssetCache {
    /** A servable response. {@code deleteOnClose} marks a one-shot file (fetched but not cacheable). */
    static final class Entry {
        final File body;
        final String mime;
        final Map<String, String> headers;
        final long size;
        final boolean deleteOnClose;

        Entry(File body, String mime, Map<String, String> headers, long size, boolean deleteOnClose) {
            this.body = body;
            this.mime = mime;
            this.headers = headers;
            this.size = size;
            this.deleteOnClose = deleteOnClose;
        }

        InputStream open() throws IOException {
            final FileInputStream in = new FileInputStream(body);
            if (!deleteOnClose) return in;
            return new java.io.FilterInputStream(in) {
                @Override public void close() throws IOException {
                    super.close();
                    //noinspection ResultOfMethodCallIgnored
                    body.delete();
                }
            };
        }
    }

    private static final String[] KEEP = {"Content-Type", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
        "Access-Control-Expose-Headers", "Timing-Allow-Origin", "Cross-Origin-Resource-Policy"};
    private static final long RETRY_AFTER_FAILURE_MS = 60L * 60 * 1000;

    private final File dir;
    private final Fetcher fetcher;
    private final LongSupplier clock;
    private final long maxBytes;
    private final long ttlMs;
    private final CacheIndex index = new CacheIndex();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> revalidating = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "movy-asset-cache");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastSweep;
    private volatile boolean indexDropped;

    AssetCache(File dir, Fetcher fetcher, LongSupplier clock, long maxBytes, long ttlMs) {
        this.dir = dir;
        this.fetcher = fetcher;
        this.clock = clock;
        this.maxBytes = maxBytes;
        this.ttlMs = ttlMs;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** Low memory: forget the in-memory index; it is rebuilt from disk on the next use. */
    void releaseMemory() {
        index.clear();
        indexDropped = true;
    }

    long sizeBytes() {
        return index.totalBytes();
    }

    int entryCount() {
        return index.count();
    }

    // ---------- serving ----------

    /** The cached (or freshly fetched and stored) response, or null when the request is not for the cache. */
    Entry serve(String method, String url, Map<String, String> reqHeaders) {
        CachePolicy.Kind kind = CachePolicy.classifyRequest(method, url, reqHeaders);
        if (kind == CachePolicy.Kind.NONE) return null;
        if (indexDropped) {
            indexDropped = false;
            scanAsync();
        }
        String key = keyOf(url);
        long now = clock.getAsLong();
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        Entry e;
        synchronized (lock) {
            e = readEntry(key, now);
            if (e == null) e = fetchAndStore(kind, key, url, reqHeaders, now);
            else {
                index.touch(key, now);
                if (CachePolicy.needsRevalidate(validatedAt(key), now)) scheduleRevalidate(kind, key, url, reqHeaders);
            }
        }
        if (e == null) return null;
        return withCors(e, reqHeaders);
    }

    /** Fonts and images requested in CORS mode work even when the stored copy came from a no-CORS request. */
    private static Entry withCors(Entry e, Map<String, String> req) {
        String origin = CachePolicy.header(req, "origin");
        if (origin == null || CachePolicy.header(e.headers, "access-control-allow-origin") != null) return e;
        Map<String, String> h = new LinkedHashMap<>(e.headers);
        h.put("Access-Control-Allow-Origin", origin);
        h.put("Vary", "Origin");
        return new Entry(e.body, e.mime, h, e.size, e.deleteOnClose);
    }

    private Entry readEntry(String key, long now) {
        File meta = metaFile(key);
        File body = bodyFile(key);
        if (!meta.isFile() || !body.isFile()) {
            if (meta.exists() || body.exists()) drop(key);
            return null;
        }
        try {
            JSONObject m = new JSONObject(readAll(meta));
            long storedAt = m.getLong("storedAt");
            long size = m.getLong("size");
            if (body.length() != size) { drop(key); return null; }
            if (CachePolicy.isExpired(storedAt, now, ttlMs)) { drop(key); return null; }
            Map<String, String> headers = new LinkedHashMap<>();
            JSONObject hs = m.optJSONObject("headers");
            if (hs != null) {
                java.util.Iterator<String> it = hs.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    headers.put(k, hs.getString(k));
                }
            }
            if (!index.has(key)) index.put(key, size, storedAt, now);
            return new Entry(body, mimeOf(headers), headers, size, false);
        } catch (JSONException | IOException ex) {
            drop(key); // corrupt entry
            return null;
        }
    }

    private long validatedAt(String key) {
        try {
            return new JSONObject(readAll(metaFile(key))).optLong("validatedAt", 0);
        } catch (JSONException | IOException e) {
            return 0;
        }
    }

    // ---------- storing ----------

    private Entry fetchAndStore(CachePolicy.Kind kind, String key, String url, Map<String, String> req, long now) {
        Fetcher.Result r = null;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs(); // Settings, Clear Cache may have removed the directory
        File tmp = new File(dir, key + ".t" + Long.toHexString(System.nanoTime()));
        try {
            r = fetcher.fetch(url, req, null, null);
            if (r.status != 200 || r.body == null) return null;
            long size = copyLimited(r.body, tmp);
            if (size < 0) { delete(tmp); return null; } // too large: let the WebView load it itself
            Map<String, String> kept = keep(r.headers);
            if (!CachePolicy.cacheableResponse(kind, r.status, r.headers, size)) {
                return new Entry(tmp, mimeOf(kept), kept, size, true); // served once, deleted when read
            }
            commit(key, url, tmp, size, r.headers, kept, now, now);
            tmp = null;
            enforceCap();
            return new Entry(bodyFile(key), mimeOf(kept), kept, size, false);
        } catch (IOException | RuntimeException e) {
            delete(tmp);
            return null;
        } finally {
            if (r != null) r.close();
        }
    }

    /** Moves the temp body into place and writes the meta (meta last: an entry exists only once complete). */
    private void commit(String key, String url, File tmp, long size, Map<String, String> all, Map<String, String> kept,
                        long storedAt, long validatedAt) throws IOException {
        File body = bodyFile(key);
        delete(body);
        if (!tmp.renameTo(body)) throw new IOException("rename failed");
        try {
            JSONObject m = new JSONObject();
            m.put("url", url);
            m.put("size", size);
            m.put("storedAt", storedAt);
            m.put("validatedAt", validatedAt);
            String etag = CachePolicy.header(all, "etag");
            String lm = CachePolicy.header(all, "last-modified");
            if (etag != null) m.put("etag", etag);
            if (lm != null) m.put("lastModified", lm);
            JSONObject hs = new JSONObject();
            for (Map.Entry<String, String> e : kept.entrySet()) hs.put(e.getKey(), e.getValue());
            m.put("headers", hs);
            File mt = new File(dir, key + ".m.tmp");
            writeAll(mt, m.toString());
            File meta = metaFile(key);
            delete(meta);
            if (!mt.renameTo(meta)) throw new IOException("meta rename failed");
        } catch (JSONException e) {
            drop(key);
            throw new IOException(e);
        }
        index.put(key, size, storedAt, clock.getAsLong());
    }

    private static Map<String, String> keep(Map<String, String> all) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : KEEP) {
            String v = CachePolicy.header(all, k);
            if (v != null) out.put(k, v); // Set-Cookie and everything else is dropped, never re-emitted
        }
        return out;
    }

    private static String mimeOf(Map<String, String> headers) {
        String ct = CachePolicy.header(headers, "content-type");
        if (ct == null) return "application/octet-stream";
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim();
    }

    /** Copies up to MAX_ENTRY_BYTES; returns the size, or -1 when the body is larger. */
    private static long copyLimited(InputStream in, File to) throws IOException {
        long total = 0;
        byte[] buf = new byte[16 * 1024];
        try (OutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > CachePolicy.MAX_ENTRY_BYTES) return -1;
                out.write(buf, 0, n);
            }
        }
        return total;
    }

    // ---------- revalidation ----------

    private void scheduleRevalidate(CachePolicy.Kind kind, String key, String url, Map<String, String> req) {
        if (!revalidating.add(key)) return;
        final Map<String, String> headers = new LinkedHashMap<>(req);
        worker.execute(() -> {
            try {
                revalidate(kind, key, url, headers);
            } finally {
                revalidating.remove(key);
            }
        });
    }

    /** Conditional GET: 304 refreshes the timestamp, 200 replaces the body, 404/410 evicts, anything else retries later. */
    void revalidate(CachePolicy.Kind kind, String key, String url, Map<String, String> req) {
        JSONObject meta;
        try {
            meta = new JSONObject(readAll(metaFile(key)));
        } catch (JSONException | IOException e) {
            return;
        }
        long now = clock.getAsLong();
        Fetcher.Result r = null;
        File tmp = new File(dir, key + ".t" + Long.toHexString(System.nanoTime()));
        try {
            r = fetcher.fetch(url, req, meta.optString("etag", null), meta.optString("lastModified", null));
            switch (CachePolicy.onRevalidated(r.status)) {
                case KEEP_AND_REFRESH:
                    updateValidated(key, meta, now);
                    break;
                case EVICT:
                    synchronized (locks.computeIfAbsent(key, k -> new Object())) { drop(key); }
                    break;
                case REPLACE: {
                    long size = r.body == null ? -1 : copyLimited(r.body, tmp);
                    if (size > 0 && CachePolicy.cacheableResponse(kind, r.status, r.headers, size)) {
                        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
                            commit(key, url, tmp, size, r.headers, keep(r.headers), meta.optLong("storedAt", now), now);
                            tmp = null;
                        }
                        enforceCap();
                    } else {
                        synchronized (locks.computeIfAbsent(key, k -> new Object())) { drop(key); }
                    }
                    break;
                }
                default:
                    updateValidated(key, meta, now - CachePolicy.REVALIDATE_MS + RETRY_AFTER_FAILURE_MS);
                    break;
            }
        } catch (IOException | RuntimeException e) {
            updateValidated(key, meta, now - CachePolicy.REVALIDATE_MS + RETRY_AFTER_FAILURE_MS);
        } finally {
            if (r != null) r.close();
            delete(tmp);
        }
    }

    private void updateValidated(String key, JSONObject meta, long when) {
        try {
            meta.put("validatedAt", when);
            File mt = new File(dir, key + ".m.tmp");
            writeAll(mt, meta.toString());
            File m = metaFile(key);
            delete(m);
            //noinspection ResultOfMethodCallIgnored
            mt.renameTo(m);
        } catch (JSONException | IOException ignored) {
            // the next serve revalidates again
        }
    }

    // ---------- housekeeping ----------

    /** Rebuilds the index from disk and removes expired, orphaned and half-written files. Call off the main thread. */
    void scan() {
        long now = clock.getAsLong();
        index.clear();
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<String> metas = new HashSet<>();
        for (File f : files) {
            String n = f.getName();
            if (n.contains(".t") && !n.endsWith(".m") && !n.endsWith(".b")) { delete(f); continue; }
            if (n.endsWith(".m.tmp")) { delete(f); continue; }
            if (n.endsWith(".m")) metas.add(n.substring(0, n.length() - 2));
        }
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".b") && !metas.contains(n.substring(0, n.length() - 2))) delete(f);
        }
        for (String key : metas) readEntry(key, now);
        sweep();
    }

    /** Deletes expired entries, then evicts least recently used ones above the cap. */
    void sweep() {
        long now = clock.getAsLong();
        lastSweep = now;
        for (String k : index.expired(now, ttlMs)) drop(k);
        enforceCap();
    }

    void sweepIfDue() {
        if (clock.getAsLong() - lastSweep >= CachePolicy.DAY_MS) worker.execute(this::sweep);
    }

    void scanAsync() {
        worker.execute(this::scan);
    }

    private void enforceCap() {
        List<String> victims = index.overflow(maxBytes);
        for (String k : victims) drop(k);
    }

    /** Removes everything (Settings, Clear Cache). */
    void clear() {
        index.clear();
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) delete(f);
    }

    private void drop(String key) {
        index.remove(key);
        delete(bodyFile(key));
        delete(metaFile(key));
    }

    // ---------- files ----------

    static String keyOf(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private File bodyFile(String key) {
        return new File(dir, key + ".b");
    }

    private File metaFile(String key) {
        return new File(dir, key + ".m");
    }

    private static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        if (f != null) f.delete();
    }

    private static String readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void writeAll(File f, String s) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }
}
