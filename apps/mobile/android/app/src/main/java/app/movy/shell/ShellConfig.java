package app.movy.shell;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable shell settings read from assets/movy.json (generated at build time; sources live in LiveConfig). */
public final class ShellConfig {
    public final List<String> authHosts;
    /** "block" or "in-app"; legacy "open-external" is treated as "block". */
    public final String externalLinks;
    public final boolean debug;
    public final String userAgent;
    public final boolean adblockEnabled;
    public final boolean cosmetic;
    public final boolean blockPopups;
    public final String backgroundColor;
    public final boolean tv;
    /** Disk asset cache (TV only for now). */
    public final boolean cacheEnabled;
    public final long cacheMaxBytes;
    public final long cacheTtlMs;

    private ShellConfig(JSONObject o, boolean tv, boolean debug) throws JSONException {
        List<String> auth = new ArrayList<>();
        JSONArray aa = o.optJSONArray("authHosts");
        if (aa != null) for (int i = 0; i < aa.length(); i++) auth.add(aa.getString(i).toLowerCase());
        this.authHosts = Collections.unmodifiableList(auth);
        this.externalLinks = "in-app".equals(o.optString("externalLinks", "block")) ? "in-app" : "block";
        this.debug = debug;
        this.userAgent = o.isNull("userAgent") ? null : o.optString("userAgent", null);
        JSONObject ab = o.optJSONObject("adblock");
        this.adblockEnabled = ab == null || ab.optBoolean("enabled", true);
        this.cosmetic = this.adblockEnabled && (ab == null || ab.optBoolean("cosmetic", true));
        this.blockPopups = ab == null || ab.optBoolean("blockPopups", true);
        this.backgroundColor = o.optString("backgroundColor", "#000000");
        this.tv = tv;
        JSONObject ca = o.optJSONObject("cache");
        this.cacheEnabled = tv && ca != null && ca.optBoolean("enabled", false);
        this.cacheMaxBytes = (ca == null ? 300 : ca.optLong("maxMb", 300)) * 1024L * 1024L;
        this.cacheTtlMs = (ca == null ? 30 : ca.optLong("maxAgeDays", 30)) * 24L * 60L * 60L * 1000L;
    }

    public static ShellConfig load(Context ctx) {
        try (InputStream in = ctx.getAssets().open("movy.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            boolean tv = ctx.getResources().getBoolean(R.bool.movy_is_tv);
            boolean debug = (ctx.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            return new ShellConfig(new JSONObject(out.toString(StandardCharsets.UTF_8.name())), tv, debug);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Cannot read assets/movy.json: " + e.getMessage(), e);
        }
    }
}
