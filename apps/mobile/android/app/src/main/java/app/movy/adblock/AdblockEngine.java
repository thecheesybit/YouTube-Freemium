package app.movy.adblock;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide facade over the native adblock engine. The native handle is thread-safe
 * (Send + Sync) and is never destroyed while the process lives.
 */
public final class AdblockEngine {
    static final String TAG = "MovyAdblock";
    private static final String ASSET_DAT = "adblock/engine.dat";
    private static final String ASSET_RESOURCES = "adblock/resources.json";

    private static volatile long handle = 0L;
    private static final CountDownLatch READY = new CountDownLatch(1);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private AdblockEngine() {}

    /** Starts loading on a background thread (idempotent). Returns immediately. */
    public static void load(Context context) {
        if (!STARTED.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> doLoad(app), "movy-adblock-load");
        t.start();
    }

    private static void doLoad(Context app) {
        long t0 = System.nanoTime();
        try {
            byte[] dat = readAsset(app, ASSET_DAT);
            String resources = null;
            try {
                resources = new String(readAsset(app, ASSET_RESOURCES), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.w(TAG, "resources.json missing: redirects and scriptlets disabled");
            }
            long h = NativeBridge.nativeCreate(dat, resources);
            if (h == 0L) {
                Log.e(TAG, "nativeCreate failed (engine.dat incompatible or corrupt?)");
            } else {
                handle = h;
                Log.i(TAG, "engine ready in " + (System.nanoTime() - t0) / 1_000_000 + " ms (" + dat.length / 1024 + " KiB)");
            }
        } catch (Throwable e) { // includes UnsatisfiedLinkError
            Log.e(TAG, "engine load failed", e);
        } finally {
            READY.countDown();
        }
    }

    private static byte[] readAsset(Context ctx, String path) throws IOException {
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** True once the engine finished loading successfully. */
    public static boolean isReady() {
        return handle != 0L;
    }

    /** Waits (never on the UI thread!) until loading finished or the timeout elapsed. */
    public static boolean awaitReady(long timeoutMs) {
        try {
            READY.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return isReady();
    }

    public static BlockResult check(String url, String sourceUrl, String type) {
        long h = handle;
        if (h == 0L) return BlockResult.ALLOW;
        String r = NativeBridge.nativeCheck(h, url, sourceUrl, type);
        if (r == null) return BlockResult.ALLOW;
        if (r.startsWith("R:")) {
            BlockResult redirect = parseDataUrl(r.substring(2));
            return redirect != null ? redirect : BlockResult.BLOCK;
        }
        return BlockResult.BLOCK;
    }

    /** Parses "data:MIME;base64,PAYLOAD". */
    static BlockResult parseDataUrl(String dataUrl) {
        if (!dataUrl.startsWith("data:")) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return null;
        String meta = dataUrl.substring(5, comma);
        boolean b64 = meta.endsWith(";base64");
        String mime = b64 ? meta.substring(0, meta.length() - 7) : meta;
        if (mime.isEmpty()) mime = "application/octet-stream";
        String payload = dataUrl.substring(comma + 1);
        try {
            byte[] body = b64 ? Base64.decode(payload, Base64.DEFAULT) : payload.getBytes(StandardCharsets.UTF_8);
            return BlockResult.redirect(mime, body);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Cosmetic filtering JSON for a page URL ("{}" when unavailable). */
    public static String cosmeticResources(String url) {
        long h = handle;
        if (h == 0L) return "{}";
        String r = NativeBridge.nativeCosmetic(h, url);
        return r == null ? "{}" : r;
    }

    /** CSS selectors hiding generic class/id based elements (JSON array). */
    public static String hiddenSelectors(List<String> classes, List<String> ids, List<String> exceptions) {
        long h = handle;
        if (h == 0L) return "[]";
        String r = NativeBridge.nativeHiddenSelectors(
                h, classes.toArray(new String[0]), ids.toArray(new String[0]), exceptions.toArray(new String[0]));
        return r == null ? "[]" : r;
    }

    /** Parses a JSON string array; malformed input yields whatever was parsed so far. */
    static List<String> jsonStrings(String json) {
        ArrayList<String> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
            // malformed input from a page: treat as empty
        }
        return out;
    }
}
