package app.movy.shell;

import java.util.List;

/**
 * Island visibility rule: is a URL the home page of one of the sources? The host must match the source's allowedHosts
 * and the path must equal the start URL's path, or be "/" or empty. Query, hash and a trailing slash are ignored.
 * Pure Java (no Android types) so it is unit tested.
 */
final class HomePageRule {
    private HomePageRule() {}

    static boolean isHome(LiveConfig config, String url) {
        return config != null && isHomeOfAny(config.sources, url);
    }

    static boolean isHomeOfAny(List<Source> sources, String url) {
        for (Source s : sources) if (isHome(s, url)) return true;
        return false;
    }

    static boolean isHome(Source source, String url) {
        UrlParts page = UrlParts.parse(url);
        if (!page.isWeb() || page.host.isEmpty() || !hostAllowed(source, page.host)) return false;
        String path = normalize(page.path);
        return path.isEmpty() || path.equals(normalize(UrlParts.parse(source.url).path));
    }

    /** True when the URL is a web URL on one of the source's hosts. */
    static boolean belongsTo(Source source, String url) {
        UrlParts page = UrlParts.parse(url);
        return page.isWeb() && !page.host.isEmpty() && hostAllowed(source, page.host);
    }

    /** Host plus normalised path (no query, hash or trailing slash): identifies a page for landing comparisons. */
    static String key(String url) {
        UrlParts page = UrlParts.parse(url);
        return page.host + normalize(page.path);
    }

    private static boolean hostAllowed(Source s, String host) {
        for (String p : s.allowedHosts) if (NavRules.hostMatches(p, host)) return true;
        return NavRules.hostMatches(UrlParts.parse(s.url).host, host);
    }

    /** Lower-cased path without trailing slashes; "/" becomes "". */
    private static String normalize(String path) {
        String p = path == null ? "" : path;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }
}
