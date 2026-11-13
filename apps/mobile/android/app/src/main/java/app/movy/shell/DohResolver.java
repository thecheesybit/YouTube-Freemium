package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a host over DNS-over-HTTPS (IPv4 answers only) for networks whose resolver poisons or black-holes some
 * names. The WebView always uses the system resolver, so {@link DohProxy} uses this for the few configured hosts.
 */
final class DohResolver {
    private static final String[] PROVIDERS = {"cloudflare-dns.com", "dns.google"};
    private static final long MIN_TTL_MS = 60_000;
    private static final long MAX_TTL_MS = 600_000;
    private static final int TIMEOUT_MS = 4000;

    private static final class Entry {
        final List<InetAddress> addrs;
        final long expiresAt;

        Entry(List<InetAddress> addrs, long expiresAt) {
            this.addrs = addrs;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Entry> cache = new HashMap<>();

    /** IPv4 addresses for {@code host}, from cache when fresh; throws when no provider answered. */
    List<InetAddress> resolve(String host) throws IOException {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Entry e = cache.get(host);
            if (e != null && e.expiresAt > now) return e.addrs;
        }
        IOException last = null;
        for (String provider : PROVIDERS) {
            try {
                Ipv4FirstHttps.Response r = Ipv4FirstHttps.request("GET", provider,
                        "/dns-query?name=" + host + "&type=A",
                        Collections.singletonMap("Accept", "application/dns-json"), null, TIMEOUT_MS, TIMEOUT_MS);
                if (r.status != 200) throw new IOException("doh status " + r.status);
                Parsed p = parse(r.body);
                if (p.addrs.isEmpty()) throw new IOException("doh no answer");
                long ttl = Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, p.ttlSeconds * 1000L));
                synchronized (cache) {
                    cache.put(host, new Entry(p.addrs, now + ttl));
                }
                return p.addrs;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("doh failed");
    }

    static final class Parsed {
        final List<InetAddress> addrs;
        final int ttlSeconds;

        Parsed(List<InetAddress> addrs, int ttlSeconds) {
            this.addrs = addrs;
            this.ttlSeconds = ttlSeconds;
        }
    }

    /** Parses an application/dns-json body; keeps only A records. */
    static Parsed parse(String body) throws IOException {
        try {
            JSONArray answers = new JSONObject(body).optJSONArray("Answer");
            List<InetAddress> out = new ArrayList<>();
            int ttl = Integer.MAX_VALUE;
            if (answers != null) {
                for (int i = 0; i < answers.length(); i++) {
                    JSONObject a = answers.getJSONObject(i);
                    if (a.optInt("type") != 1) continue;
                    String ip = a.optString("data", "");
                    if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) continue;
                    out.add(InetAddress.getByName(ip));
                    ttl = Math.min(ttl, a.optInt("TTL", 60));
                }
            }
            return new Parsed(Collections.unmodifiableList(out), ttl == Integer.MAX_VALUE ? 60 : ttl);
        } catch (JSONException e) {
            throw new IOException("doh bad json");
        }
    }
}
