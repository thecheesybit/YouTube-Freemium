package app.movy.shell;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure navigation policy (no Android types). The app never launches another app or browser:
 * every decision is either "load it here" or "drop it silently".
 *
 * Main frame: only allowed hosts, plus (a) server redirects out of the start page (domain moves),
 * (b) sign-in hosts for a user tap. Popups: only a user-tap to an allowed host, loaded in the main view.
 * Subframes are not restricted here; the ad engine decides.
 */
public final class NavRules {
    public enum Verdict { ALLOW, KILL }

    /** Built-in sign-in hosts; "host/prefix" limits a host to a path prefix. */
    static final List<String> DEFAULT_AUTH_HOSTS = Collections.unmodifiableList(Arrays.asList(
            "accounts.google.com", "appleid.apple.com", "www.facebook.com/login", "m.facebook.com/login",
            "discord.com/oauth2", "challenges.cloudflare.com"));

    private volatile List<String> allowedHosts;
    private volatile List<String> authHosts;
    private final List<String> builtinAuthHosts;
    private final boolean anyWeb;
    private volatile Set<String> startHosts;
    private final Set<String> mirrors = ConcurrentHashMap.newKeySet();
    /** True while the current main-frame chain began at the start page / initial load. */
    private boolean chainTrusted = true;
    /** True while the current chain began with a user-tapped sign-in navigation. */
    private boolean chainAuth;

    public NavRules(String startUrl, List<String> allowedHosts, List<String> extraAuthHosts, String externalLinks) {
        this.allowedHosts = lower(allowedHosts);
        this.builtinAuthHosts = lower(extraAuthHosts);
        this.authHosts = withBuiltins(this.builtinAuthHosts);
        this.anyWeb = "in-app".equals(externalLinks);
        this.startHosts = Collections.singleton(UrlParts.parse(startUrl).host);
    }

    private static List<String> withBuiltins(List<String> extra) {
        List<String> auth = new ArrayList<>(DEFAULT_AUTH_HOSTS);
        auth.addAll(extra);
        return Collections.unmodifiableList(auth);
    }

    /**
     * Live update (remote config / source change): allowed hosts, start-page hosts and extra sign-in hosts.
     * Takes effect for the next navigation decision.
     */
    public void update(List<String> newAllowedHosts, Set<String> newStartHosts, List<String> extraAuthHosts) {
        this.allowedHosts = lower(newAllowedHosts);
        this.startHosts = Collections.unmodifiableSet(new java.util.HashSet<>(newStartHosts));
        List<String> extra = new ArrayList<>(builtinAuthHosts);
        extra.addAll(lower(extraAuthHosts));
        this.authHosts = withBuiltins(extra);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in != null) for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return Collections.unmodifiableList(out);
    }

    /** "*.x.com" matches x.com and subdomains; "x.com" matches exactly. */
    static boolean hostMatches(String pattern, String host) {
        if (pattern.startsWith("*.")) {
            String base = pattern.substring(2);
            return host.equals(base) || host.endsWith("." + base);
        }
        return host.equals(pattern);
    }

    public boolean isAllowedHost(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (mirrors.contains(h)) return true;
        for (String p : allowedHosts) if (hostMatches(p, h)) return true;
        return false;
    }

    boolean isAuthTarget(UrlParts p) {
        for (String pattern : authHosts) {
            int slash = pattern.indexOf('/');
            String hostPattern = slash < 0 ? pattern : pattern.substring(0, slash);
            if (!hostMatches(hostPattern, p.host)) continue;
            if (slash < 0 || p.path.startsWith(pattern.substring(slash))) return true;
        }
        return false;
    }

    /** Patterns the page script may treat as on-site (configured + learned mirrors). */
    public List<String> allowedPatterns() {
        List<String> out = new ArrayList<>(allowedHosts);
        out.addAll(mirrors);
        return out;
    }

    public List<String> authPatterns() {
        return authHosts;
    }

    /** Main-frame navigation. {@code currentUrl} is the page being left (may be null). */
    public synchronized Verdict mainFrame(String url, boolean redirect, boolean gesture, String currentUrl) {
        UrlParts p = UrlParts.parse(url);
        switch (p.scheme) {
            case "about":
                return "about:blank".equalsIgnoreCase(url.trim()) ? Verdict.ALLOW : Verdict.KILL;
            case "javascript":
            case "blob":
                return Verdict.ALLOW;
            case "http":
            case "https":
                break;
            default:
                return Verdict.KILL; // data:, file:, intent:, market:, tel:, mailto:, custom schemes
        }
        if (isAllowedHost(p.host)) {
            if (!redirect) {
                chainTrusted = isStartPage(p);
                chainAuth = false;
            }
            return Verdict.ALLOW;
        }
        if (redirect && chainTrusted) {
            mirrors.add(p.host); // domain move / mirror: remember for this session
            return Verdict.ALLOW;
        }
        if (isAuthTarget(p)) {
            boolean onAuthPage = isAuthTarget(UrlParts.parse(currentUrl));
            if ((!redirect && (gesture || onAuthPage)) || (redirect && chainAuth)) {
                chainAuth = true;
                chainTrusted = false;
                return Verdict.ALLOW;
            }
            return Verdict.KILL;
        }
        return anyWeb ? Verdict.ALLOW : Verdict.KILL;
    }

    private boolean isStartPage(UrlParts p) {
        boolean root = p.path.isEmpty() || "/".equals(p.path);
        return root && (startHosts.contains(p.host) || mirrors.contains(p.host));
    }

    /** Popup (window.open / target=_blank). Never a new window: either load in the main view or drop. */
    public boolean popupOpensInMain(String url, boolean gesture, boolean blockedByEngine) {
        if (!gesture || blockedByEngine) return false;
        UrlParts p = UrlParts.parse(url);
        return p.isWeb() && isAllowedHost(p.host);
    }

    /** Iframe navigation: not host-restricted (the ad engine decides); only other-app schemes are dropped. */
    public boolean subframeAllowed(String url) {
        UrlParts p = UrlParts.parse(url);
        switch (p.scheme) {
            case "http": case "https": case "about": case "data": case "blob": case "javascript":
                return true;
            default:
                return false;
        }
    }
}
