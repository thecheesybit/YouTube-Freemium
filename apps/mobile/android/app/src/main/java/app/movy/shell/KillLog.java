package app.movy.shell;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Privacy-preserving descriptions of blocked targets for logcat. Pure Java. */
public final class KillLog {
    public static final String TAG = "MovyShell";
    private static final int HASH_CHARS = 8;
    private static final int MAX_DEBUG_LEN = 200;

    private KillLog() {}

    /** Release: "scheme://<8 hex of sha256(host)>"; debug: the (truncated) URL. */
    public static String describe(String url, boolean debug) {
        if (url == null) return "(null)";
        if (debug) return url.length() > MAX_DEBUG_LEN ? url.substring(0, MAX_DEBUG_LEN) + "..." : url;
        UrlParts p = UrlParts.parse(url);
        if (p.scheme.isEmpty()) return "(unparsed)";
        if (p.host.isEmpty()) return p.scheme + ":";
        return p.scheme + "://" + hashHost(p.host);
    }

    static String hashHost(String host) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(host.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
                if (sb.length() >= HASH_CHARS) break;
            }
            return sb.substring(0, HASH_CHARS);
        } catch (NoSuchAlgorithmException e) {
            return "00000000";
        }
    }
}
