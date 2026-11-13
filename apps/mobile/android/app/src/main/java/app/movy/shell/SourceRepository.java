package app.movy.shell;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the live config, the active source and last URLs per source.
 * Listeners are always called on the main thread. Used by the chooser, the Island (W5b) and navigation.
 */
public final class SourceRepository {
    /** Called on the main thread after the config changed (remote update) or the active source switched. */
    public interface Listener {
        void onConfigChanged(LiveConfig config);

        void onActiveSourceChanged(Source active);
    }

    /** Implemented by MainActivity: loads a url of a source in the WebView and shows the progress UI. */
    public interface Loader {
        void load(Source source, String url);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, String> lastUrls = new HashMap<>();
    private final Prefs prefs;
    private volatile LiveConfig config;
    private volatile String activeId;
    private Loader loader;
    private volatile String resumeId;

    public SourceRepository(Prefs prefs, LiveConfig initial) {
        this.prefs = prefs;
        this.config = initial;
    }

    /** TV: reopen this source on its last page at launch (after a background exit). */
    public void seedResume(String sourceId, String url) {
        Source s = config.find(sourceId);
        if (s == null || url == null) return;
        resumeId = sourceId;
        synchronized (lastUrls) {
            lastUrls.put(sourceId, url);
        }
    }

    public void setLoader(Loader loader) {
        this.loader = loader;
    }

    public LiveConfig config() {
        return config;
    }

    /** Enabled sources in display order. */
    public List<Source> enabledSources() {
        return config.enabledSources();
    }

    /** The source currently shown; null before the first one is chosen. */
    public Source active() {
        return config.find(activeId);
    }

    public Source byId(String id) {
        return config.find(id);
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Replaces the live config (any thread) and notifies listeners on the main thread. */
    public void update(LiveConfig next) {
        config = next;
        main.post(() -> {
            for (Listener l : listeners) l.onConfigChanged(next);
        });
    }

    /**
     * Source to open at launch: the saved default when enabled and "ask every launch" is off, else null
     * (show the chooser). Fallback order for a vanished default: the chooser is shown.
     */
    public Source launchSource() {
        Source r = config.find(resumeId);
        if (r != null && r.enabled) return r;
        String def = prefs.defaultSourceId();
        Source s = config.find(def);
        if (s != null && s.enabled && !prefs.askEveryLaunch()) return s;
        return null;
    }

    /** Source to preselect in the chooser: last used, else the recommended one. */
    public Source preselected() {
        Source last = config.find(prefs.lastSourceId());
        if (last != null && last.enabled) return last;
        return config.recommended();
    }

    /** Switches to a source: its last URL in this session, or its start URL. Main thread. */
    public void switchTo(String id) {
        Source s = config.find(id);
        if (s == null || !s.enabled) return;
        activate(s, lastUrl(s));
    }

    /** Opens a specific URL under a source. */
    public void openUrl(String id, String url) {
        Source s = config.find(id);
        if (s != null && s.enabled) activate(s, url != null ? url : s.url);
    }

    /** Loads the start URL of a source, ignoring its remembered page (error-panel Retry uses reload instead). */
    public void openStart(String id) {
        Source s = config.find(id);
        if (s != null && s.enabled) activate(s, s.url);
    }

    private void activate(Source s, String url) {
        activeId = s.id;
        prefs.setLastSourceId(s.id);
        if (loader != null) loader.load(s, url);
        for (Listener l : listeners) l.onActiveSourceChanged(s);
    }

    /** The last page URL seen for a source in this process, or its start URL. */
    public String lastUrl(Source s) {
        synchronized (lastUrls) {
            String u = lastUrls.get(s.id);
            return u != null ? u : s.url;
        }
    }

    /** Called by the WebView client on every main-frame URL change. Not persisted. */
    public void rememberUrl(String url) {
        Source a = active();
        if (a == null || url == null) return;
        if (!a.allowedHosts.isEmpty() && !matchesSource(a, url)) return;
        synchronized (lastUrls) {
            lastUrls.put(a.id, url);
        }
    }

    private static boolean matchesSource(Source s, String url) {
        String host = UrlParts.parse(url).host;
        for (String p : s.allowedHosts) if (NavRules.hostMatches(p, host)) return true;
        return false;
    }
}
