package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The live (remotely overridable) configuration. Immutable. The APK ships a full default
 * (assets/movy-defaults.json); remote values override it per field via {@link #merge}.
 * Invalid input throws {@link IllegalArgumentException}; callers ignore it and keep the previous config.
 */
public final class LiveConfig {
    public static final int[] PALETTE = {0xFFE50914, 0xFF22C55E, 0xFFFACC15, 0xFF3B82F6, 0xFFA855F7, 0xFFF97316};
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final Pattern HOST = Pattern.compile("(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final int MAX_TEXT = 200;

    public final int contentVersion;
    public final List<Source> sources;
    public final String recommendedSource;
    public final String tagline;
    public final String credit;
    public final int splashMinMs;
    public final String chooserTitle;
    public final String chooserSubtitle;
    public final String chooserCta;
    public final String chooserRecommended;
    public final String chooserAlways;
    /** "home" or "always". */
    public final String islandVisibility;
    /** Nullable. */
    public final Announcement announcement;
    public final int minBuild;
    public final String updateMessage;
    public final List<String> extraAllowedHosts;
    public final List<String> authHosts;
    /** Hosts answered over DNS-over-HTTPS (see DohRescue). */
    public final List<String> dohHosts;
    /** TV navigation profiles: source id -> compact profile JSON (selectors only). */
    public final java.util.Map<String, String> tvProfiles;

    private LiveConfig(Draft d) {
        contentVersion = d.contentVersion;
        sources = Collections.unmodifiableList(new ArrayList<>(d.sources));
        recommendedSource = d.recommendedSource;
        tagline = d.tagline;
        credit = d.credit;
        splashMinMs = d.splashMinMs;
        chooserTitle = d.chooserTitle;
        chooserSubtitle = d.chooserSubtitle;
        chooserCta = d.chooserCta;
        chooserRecommended = d.chooserRecommended;
        chooserAlways = d.chooserAlways;
        islandVisibility = d.islandVisibility;
        announcement = d.announcement;
        minBuild = d.minBuild;
        updateMessage = d.updateMessage;
        extraAllowedHosts = Collections.unmodifiableList(new ArrayList<>(d.extraAllowedHosts));
        authHosts = Collections.unmodifiableList(new ArrayList<>(d.authHosts));
        dohHosts = Collections.unmodifiableList(new ArrayList<>(d.dohHosts));
        tvProfiles = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(d.tvProfiles));
    }

    /** Sources the user may use, in display order. */
    public List<Source> enabledSources() {
        List<Source> out = new ArrayList<>();
        for (Source s : sources) if (s.enabled) out.add(s);
        return out;
    }

    public Source find(String id) {
        if (id != null) for (Source s : sources) if (s.id.equals(id)) return s;
        return null;
    }

    /** Recommended source when enabled, else the first enabled one. */
    public Source recommended() {
        Source r = find(recommendedSource);
        if (r != null && r.enabled) return r;
        return enabledSources().get(0);
    }

    /** Union of all enabled sources' hosts plus extraAllowedHosts. */
    public List<String> allAllowedHosts() {
        Set<String> out = new LinkedHashSet<>();
        for (Source s : enabledSources()) out.addAll(s.allowedHosts);
        out.addAll(extraAllowedHosts);
        return new ArrayList<>(out);
    }

    /** Profile JSON of a source (empty object when it has none). */
    public String tvProfileFor(String sourceId) {
        String p = sourceId == null ? null : tvProfiles.get(sourceId);
        return p != null ? p : "{}";
    }

    /** The source whose hosts match {@code url}, else null. */
    public Source sourceForUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        String host = UrlParts.parse(url).host;
        for (Source s : enabledSources()) {
            for (String pat : s.allowedHosts) if (NavRules.hostMatches(pat, host)) return s;
        }
        return null;
    }

    public Set<String> startHosts() {
        Set<String> out = new HashSet<>();
        for (Source s : enabledSources()) out.add(UrlParts.parse(s.url).host);
        return out;
    }

    // ---- parsing ----

    /** Parses a complete config (the build-time defaults). */
    public static LiveConfig parseDefaults(String json, UrlDecoder decoder) {
        return merge(null, json, decoder);
    }

    /** Applies {@code json} over {@code base} (null base = empty defaults, sources required). */
    public static LiveConfig merge(LiveConfig base, String json, UrlDecoder decoder) {
        if (json == null) throw new IllegalArgumentException("no config");
        JSONObject o;
        try {
            Object parsed = new JSONTokener(json).nextValue();
            if (!(parsed instanceof JSONObject)) throw new IllegalArgumentException("config is not an object");
            o = (JSONObject) parsed;
        } catch (JSONException e) {
            throw new IllegalArgumentException("invalid JSON");
        }
        try {
            return mergeObject(base, o, decoder);
        } catch (JSONException | ClassCastException e) {
            throw new IllegalArgumentException("invalid field type");
        }
    }

    private static LiveConfig mergeObject(LiveConfig base, JSONObject o, UrlDecoder dec) throws JSONException {
        Draft d = base == null ? new Draft() : new Draft(base);
        if (o.has("contentVersion")) d.contentVersion = nonNegInt(o, "contentVersion");
        if (o.has("sources")) d.sources = parseSources(o.getJSONArray("sources"), dec);
        if (o.has("recommendedSource")) d.recommendedSource = text(o, "recommendedSource", 40);
        if (o.has("tagline")) d.tagline = text(o, "tagline", 120);
        if (o.has("credit")) d.credit = text(o, "credit", 60);
        JSONObject splash = obj(o, "splash");
        if (splash != null) {
            if (splash.has("tagline")) d.tagline = text(splash, "tagline", 120);
            if (splash.has("credit")) d.credit = text(splash, "credit", 60);
            if (splash.has("minMs")) d.splashMinMs = Math.min(nonNegInt(splash, "minMs"), 3000);
        }
        JSONObject ch = obj(o, "chooser");
        if (ch != null) {
            if (ch.has("title")) d.chooserTitle = text(ch, "title", MAX_TEXT);
            if (ch.has("subtitle")) d.chooserSubtitle = text(ch, "subtitle", MAX_TEXT);
            if (ch.has("cta")) d.chooserCta = text(ch, "cta", 40);
            if (ch.has("recommendedLabel")) d.chooserRecommended = text(ch, "recommendedLabel", 40);
            if (ch.has("alwaysLabel")) d.chooserAlways = text(ch, "alwaysLabel", 80);
        }
        JSONObject island = obj(o, "island");
        if (island != null && island.has("visibility")) {
            String v = text(island, "visibility", 10);
            if (!"home".equals(v) && !"always".equals(v)) throw new IllegalArgumentException("island.visibility");
            d.islandVisibility = v;
        }
        if (o.has("announcement")) {
            d.announcement = o.isNull("announcement") ? null : parseAnnouncement(o.getJSONObject("announcement"));
        }
        if (o.has("minBuild")) d.minBuild = nonNegInt(o, "minBuild");
        if (o.has("updateMessage")) d.updateMessage = text(o, "updateMessage", 400);
        if (o.has("extraAllowedHosts")) d.extraAllowedHosts = hostList(o.getJSONArray("extraAllowedHosts"), false);
        if (o.has("authHosts")) d.authHosts = hostList(o.getJSONArray("authHosts"), true);
        if (o.has("dohHosts")) d.dohHosts = hostList(o.getJSONArray("dohHosts"), false);
        if (o.has("tvProfiles")) d.tvProfiles = TvProfiles.merge(d.tvProfiles, o.getJSONObject("tvProfiles"));
        d.validate();
        return new LiveConfig(d);
    }

    private static JSONObject obj(JSONObject o, String key) throws JSONException {
        return o.has(key) && !o.isNull(key) ? o.getJSONObject(key) : null;
    }

    private static String text(JSONObject o, String key, int max) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        String s = ((String) v).trim();
        if (s.length() > max) throw new IllegalArgumentException(key + " too long");
        return s;
    }

    private static int nonNegInt(JSONObject o, String key) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double n = ((Number) v).doubleValue();
        if (n < 0 || n > Integer.MAX_VALUE || n != Math.floor(n)) throw new IllegalArgumentException(key + " out of range");
        return (int) n;
    }

    private static List<Source> parseSources(JSONArray a, UrlDecoder dec) throws JSONException {
        if (a.length() == 0) throw new IllegalArgumentException("sources empty");
        List<Source> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            String id = text(s, "id", 40);
            if (!ID.matcher(id).matches() || !ids.add(id)) throw new IllegalArgumentException("source id");
            String name = text(s, "name", 40);
            if (name.isEmpty()) throw new IllegalArgumentException("source name");
            String url = s.has("urlEnc") ? dec.decode(text(s, "urlEnc", 2000)) : text(s, "url", 2000);
            UrlParts p = UrlParts.parse(url);
            if (!p.isWeb() || p.host.isEmpty()) throw new IllegalArgumentException("source url");
            List<String> hosts = s.has("allowedHosts") ? hostList(s.getJSONArray("allowedHosts"), false)
                    : new ArrayList<>(Arrays.asList(p.host, "*." + p.host));
            if (hosts.isEmpty()) throw new IllegalArgumentException("source hosts");
            int accent = PALETTE[i % PALETTE.length];
            if (s.has("accent")) {
                String c = text(s, "accent", 7);
                if (!COLOR.matcher(c).matches()) throw new IllegalArgumentException("source accent");
                accent = 0xFF000000 | Integer.parseInt(c.substring(1), 16);
            }
            boolean enabled = !s.has("enabled") || s.getBoolean("enabled");
            out.add(new Source(id, name, url, hosts, accent, enabled));
        }
        return out;
    }

    /** Host patterns; sign-in hosts ({@code withPath}) may carry a path prefix ("host/path"). */
    private static List<String> hostList(JSONArray a, boolean withPath) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object v = a.get(i);
            if (!(v instanceof String)) throw new IllegalArgumentException("host must be a string");
            String h = ((String) v).trim().toLowerCase(Locale.ROOT);
            String hostPart = withPath && h.contains("/") ? h.substring(0, h.indexOf('/')) : h;
            if (h.length() > 253 || !HOST.matcher(hostPart).matches()) throw new IllegalArgumentException("host pattern");
            out.add(h);
        }
        return out;
    }

    private static Announcement parseAnnouncement(JSONObject a) throws JSONException {
        String id = text(a, "id", 60);
        String text = text(a, "text", 300);
        if (id.isEmpty() || text.isEmpty()) throw new IllegalArgumentException("announcement");
        String level = a.has("level") ? text(a, "level", 10) : "info";
        if (!level.equals("info") && !level.equals("warn") && !level.equals("critical")) level = "info";
        String url = a.has("url") ? text(a, "url", 2000) : "";
        return new Announcement(id, text, level, url);
    }

    /** Mutable working copy used only while merging. */
    private static final class Draft {
        int contentVersion;
        List<Source> sources = new ArrayList<>();
        String recommendedSource = "";
        String tagline = "";
        String credit = "";
        int splashMinMs = 600;
        String chooserTitle = "Choose your source";
        String chooserSubtitle = "";
        String chooserCta = "Continue";
        String chooserRecommended = "Recommended";
        String chooserAlways = "Always open this source";
        String islandVisibility = "home";
        Announcement announcement;
        int minBuild;
        String updateMessage = "";
        List<String> extraAllowedHosts = new ArrayList<>();
        List<String> authHosts = new ArrayList<>();
        List<String> dohHosts = new ArrayList<>();
        java.util.Map<String, String> tvProfiles = new java.util.LinkedHashMap<>();

        Draft() {}

        Draft(LiveConfig b) {
            contentVersion = b.contentVersion;
            sources = new ArrayList<>(b.sources);
            recommendedSource = b.recommendedSource;
            tagline = b.tagline;
            credit = b.credit;
            splashMinMs = b.splashMinMs;
            chooserTitle = b.chooserTitle;
            chooserSubtitle = b.chooserSubtitle;
            chooserCta = b.chooserCta;
            chooserRecommended = b.chooserRecommended;
            chooserAlways = b.chooserAlways;
            islandVisibility = b.islandVisibility;
            announcement = b.announcement;
            minBuild = b.minBuild;
            updateMessage = b.updateMessage;
            extraAllowedHosts = new ArrayList<>(b.extraAllowedHosts);
            authHosts = new ArrayList<>(b.authHosts);
            dohHosts = new ArrayList<>(b.dohHosts);
            tvProfiles = new java.util.LinkedHashMap<>(b.tvProfiles);
        }

        void validate() {
            boolean anyEnabled = false;
            for (Source s : sources) anyEnabled |= s.enabled;
            if (!anyEnabled) throw new IllegalArgumentException("no enabled source");
            if (recommendedSource.isEmpty()) {
                for (Source s : sources) {
                    if (s.enabled) {
                        recommendedSource = s.id;
                        break;
                    }
                }
            }
            boolean found = false;
            for (Source s : sources) found |= s.id.equals(recommendedSource);
            if (!found) throw new IllegalArgumentException("recommendedSource is not a source");
        }
    }
}
