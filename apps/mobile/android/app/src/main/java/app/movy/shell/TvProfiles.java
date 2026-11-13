package app.movy.shell;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-source TV navigation profiles (selectors only), shipped in the defaults and overridable from Remote Config.
 * Values are kept as compact JSON strings, one per source id, and handed to the page as-is. Unknown keys are dropped
 * (an older app must survive a newer remote profile); known keys are type and size checked.
 */
public final class TvProfiles {
    private static final Pattern ID = Pattern.compile("[a-z0-9-]+");
    private static final String[] LISTS = {"header", "bottomBar", "rows", "cards", "focusables", "skip", "firstFocus", "playButtons", "killOnTv"};
    private static final String[] SERVERS = {"toggle", "list", "item", "active", "failed", "frame"};
    private static final String[] EPISODES = {"list", "item", "next"};
    private static final String[] PLAYER_TEXT = {"frame", "video", "root", "fullscreenTarget"};
    private static final String[] PLAYER_LISTS = {"fullscreenButton", "controls", "overlayKill"};
    private static final int MAX_SELECTOR = 400;
    private static final int MAX_LIST = 40;

    private TvProfiles() {}

    /** Applies {@code remote} (id -> profile object) over {@code base}; the remote wins per top-level key. */
    public static Map<String, String> merge(Map<String, String> base, JSONObject remote) throws JSONException {
        Map<String, String> out = new LinkedHashMap<>(base);
        Iterator<String> ids = remote.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            if (!ID.matcher(id).matches()) throw new IllegalArgumentException("tvProfiles id");
            JSONObject prof = sanitize(remote.getJSONObject(id));
            JSONObject merged = out.containsKey(id) ? new JSONObject(out.get(id)) : new JSONObject();
            Iterator<String> keys = prof.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                merged.put(k, prof.get(k));
            }
            out.put(id, merged.toString());
        }
        return Collections.unmodifiableMap(out);
    }

    /** Validated copy containing only known keys. */
    static JSONObject sanitize(JSONObject p) throws JSONException {
        JSONObject o = new JSONObject();
        for (String k : LISTS) if (p.has(k)) o.put(k, list(p, k));
        if (p.has("servers")) o.put("servers", texts(p.getJSONObject("servers"), SERVERS));
        if (p.has("episodes")) o.put("episodes", texts(p.getJSONObject("episodes"), EPISODES));
        if (p.has("player")) {
            JSONObject src = p.getJSONObject("player");
            JSONObject pl = texts(src, PLAYER_TEXT);
            for (String k : PLAYER_LISTS) if (src.has(k)) pl.put(k, list(src, k));
            if (src.has("depth")) pl.put("depth", range(src, "depth", 0, 4));
            o.put("player", pl);
        }
        if (p.has("viewportWidth")) o.put("viewportWidth", range(p, "viewportWidth", 320, 3840));
        return o;
    }

    private static JSONArray list(JSONObject o, String key) throws JSONException {
        JSONArray a = o.getJSONArray(key);
        if (a.length() > MAX_LIST) throw new IllegalArgumentException(key + " too long");
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) out.put(selector(a.get(i), key));
        return out;
    }

    private static JSONObject texts(JSONObject o, String[] keys) throws JSONException {
        JSONObject out = new JSONObject();
        for (String k : keys) if (o.has(k)) out.put(k, selector(o.get(k), k));
        return out;
    }

    private static String selector(Object v, String key) {
        if (!(v instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        String s = ((String) v).trim();
        if (s.isEmpty() || s.length() > MAX_SELECTOR) throw new IllegalArgumentException(key + " length");
        return s;
    }

    private static int range(JSONObject o, String key, int min, int max) throws JSONException {
        Object v = o.get(key);
        if (!(v instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double d = ((Number) v).doubleValue();
        if (d != Math.floor(d) || d < min || d > max) throw new IllegalArgumentException(key + " out of range");
        return (int) d;
    }
}
