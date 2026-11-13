package app.movy.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** LRU accounting for the asset cache: sizes, last access and first-store time per key. */
final class CacheIndex {
    static final class Info {
        final long size;
        final long storedAt;
        long lastAccess;

        Info(long size, long storedAt, long lastAccess) {
            this.size = size;
            this.storedAt = storedAt;
            this.lastAccess = lastAccess;
        }
    }

    private final Map<String, Info> map = new LinkedHashMap<>();
    private long total;

    synchronized void put(String key, long size, long storedAt, long now) {
        Info old = map.remove(key);
        if (old != null) total -= old.size;
        map.put(key, new Info(size, storedAt, now));
        total += size;
    }

    synchronized void touch(String key, long now) {
        Info i = map.remove(key);
        if (i == null) return;
        i.lastAccess = now;
        map.put(key, i); // most recently used goes last
    }

    synchronized boolean remove(String key) {
        Info old = map.remove(key);
        if (old == null) return false;
        total -= old.size;
        return true;
    }

    synchronized boolean has(String key) {
        return map.containsKey(key);
    }

    synchronized long totalBytes() {
        return total;
    }

    synchronized int count() {
        return map.size();
    }

    synchronized void clear() {
        map.clear();
        total = 0;
    }

    /** Keys to evict, least recently used first, until the total fits maxBytes. The index itself is untouched. */
    synchronized List<String> overflow(long maxBytes) {
        List<String> out = new ArrayList<>();
        long t = total;
        for (Map.Entry<String, Info> e : map.entrySet()) {
            if (t <= maxBytes) break;
            out.add(e.getKey());
            t -= e.getValue().size;
        }
        return out;
    }

    /** Keys stored ttlMs or longer ago. */
    synchronized List<String> expired(long now, long ttlMs) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Info> e : map.entrySet()) {
            if (CachePolicy.isExpired(e.getValue().storedAt, now, ttlMs)) out.add(e.getKey());
        }
        return out;
    }
}
