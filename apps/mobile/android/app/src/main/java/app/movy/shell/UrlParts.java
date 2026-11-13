package app.movy.shell;

import java.util.Locale;

/** Minimal, Android-free URL splitter: scheme, lower-cased host (no port, userinfo or trailing dot) and path. */
final class UrlParts {
    final String scheme;
    final String host;
    final String path;

    private UrlParts(String scheme, String host, String path) {
        this.scheme = scheme;
        this.host = host;
        this.path = path;
    }

    boolean isWeb() {
        return "http".equals(scheme) || "https".equals(scheme);
    }

    static UrlParts parse(String url) {
        if (url == null) return new UrlParts("", "", "");
        String u = url.trim().replace('\\', '/');
        int colon = u.indexOf(':');
        if (colon <= 0) return new UrlParts("", "", "");
        String scheme = u.substring(0, colon).toLowerCase(Locale.ROOT);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.';
            if (!ok) return new UrlParts("", "", "");
        }
        String rest = u.substring(colon + 1);
        if (!rest.startsWith("//")) return new UrlParts(scheme, "", "");
        rest = rest.substring(2);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        String path = end < rest.length() && rest.charAt(end) == '/' ? stripQueryAndFragment(rest.substring(end)) : "/";
        int at = authority.lastIndexOf('@');
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;
        String host = hostPort;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            host = close > 0 ? hostPort.substring(0, close + 1) : hostPort;
        } else {
            int p = hostPort.lastIndexOf(':');
            if (p >= 0) host = hostPort.substring(0, p);
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return new UrlParts(scheme, host, path.toLowerCase(Locale.ROOT));
    }

    private static String stripQueryAndFragment(String p) {
        int q = p.length();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '?' || c == '#') {
                q = i;
                break;
            }
        }
        return p.substring(0, q);
    }
}
