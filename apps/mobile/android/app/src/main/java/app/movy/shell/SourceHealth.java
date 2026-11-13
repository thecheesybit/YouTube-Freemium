package app.movy.shell;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real {@link HealthChecker}: HEAD (fallback: ranged GET) on each enabled source's start URL, in parallel on a small
 * pool, 5 s timeout, no cookies, IPv4 first. The result is a hint for the Island dots and the failover suggestion.
 */
public final class SourceHealth implements HealthChecker {
    private static final long STALE_MS = 60_000;
    private static final int TIMEOUT_MS = 5_000;

    private final SourceRepository repo;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "movy-health");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long lastCheckMs = Long.MIN_VALUE;

    public SourceHealth(SourceRepository repo) {
        this.repo = repo;
    }

    @Override
    public Status status(String sourceId) {
        Status s = sourceId == null ? null : statuses.get(sourceId);
        return s != null ? s : Status.UNKNOWN;
    }

    @Override
    public void refresh() {
        long last = lastCheckMs;
        if (last == Long.MIN_VALUE || SystemClock.elapsedRealtime() - last > STALE_MS) refreshNow();
    }

    @Override
    public void refreshNow() {
        if (!running.compareAndSet(false, true)) return;
        List<Source> sources = repo.enabledSources();
        if (sources.isEmpty()) {
            running.set(false);
            return;
        }
        AtomicInteger left = new AtomicInteger(sources.size());
        for (Source s : sources) {
            pool.execute(() -> {
                try {
                    statuses.put(s.id, probe(s));
                } finally {
                    if (left.decrementAndGet() == 0) finishRound();
                }
            });
        }
    }

    private void finishRound() {
        lastCheckMs = SystemClock.elapsedRealtime();
        running.set(false);
        main.post(() -> {
            for (Listener l : listeners) l.onHealthChanged();
        });
    }

    @Override
    public Source suggestion(String excludeId) {
        return HealthClassifier.suggest(repo.enabledSources(), this::status, excludeId);
    }

    @Override
    public void addListener(Listener l) {
        listeners.add(l);
    }

    private static Status probe(Source s) {
        long start = SystemClock.elapsedRealtime();
        int code;
        try {
            code = request(s.url, "HEAD", false);
            if (HealthClassifier.headNeedsFallback(code)) {
                start = SystemClock.elapsedRealtime();
                code = request(s.url, "GET", true);
            }
        } catch (IOException e) {
            code = 0;
        }
        return HealthClassifier.classify(code, SystemClock.elapsedRealtime() - start);
    }

    private static int request(String url, String method, boolean range) throws IOException {
        URL u = new URL(url);
        String path = u.getFile().isEmpty() ? "/" : u.getFile();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36");
        headers.put("Accept", "*/*");
        if (range) headers.put("Range", "bytes=0-0");
        if ("https".equals(u.getProtocol())) {
            return Ipv4FirstHttps.status(method, u.getHost(), path, Collections.unmodifiableMap(headers), TIMEOUT_MS, TIMEOUT_MS);
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }
}
